package com.openauto.dash

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

/**
 * The shell through Shizuku (the Shizuku app, `moe.shizuku.privileged.api`):
 * for a unit or a phone without root and without the K706's internal ADB
 * socket. Shizuku runs as the shell user, started over wireless debugging
 * (Android 11+) or from a PC with ADB, or as root at boot on a rooted device;
 * once the driver has allowed Dashwheel in it, Dashwheel's commands run
 * through a service of its own that Shizuku starts ([ShizukuShellService]).
 *
 * Where it stands ([state]) is kept for the settings row; [ready] is the one
 * answer the shell probe ([PrivilegedShell]) and the command runner
 * ([DockShell]) need. The binder arrives on its own once Shizuku runs
 * (through the ShizukuProvider in the manifest), so [start] listens for it
 * and re-probes the shell when it comes or goes.
 *
 * Once allowed, Shizuku also grants Dashwheel the two development permissions
 * it asks for (WRITE_SECURE_SETTINGS, READ_LOGS), as `adb shell pm grant`
 * from a PC would: those stay after Shizuku stops, so the car app's switches
 * keep working on their own.
 */
object ShizukuShell {

    private const val TAG = "ShizukuShell"
    const val PACKAGE = "moe.shizuku.privileged.api"
    /** Where to get it: the project's download page (a QR code for the phone, a link on the unit). */
    const val DOWNLOAD_URL = "https://shizuku.rikka.app/download/"
    private const val REQUEST_CODE = 7811
    /** Starting the service spawns a process from the APK: the first command waits for it. */
    private const val BIND_TIMEOUT_MS = 8_000L

    private val _state = MutableStateFlow(ShizukuState.NOT_INSTALLED)
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    @Volatile private var appContext: Context? = null
    @Volatile private var started = false
    @Volatile private var granted = false

    /** Shizuku's binder answers and Dashwheel is allowed: commands can run. Cheap, any thread. */
    val ready: Boolean
        get() = Edition.full && runCatching {
            Shizuku.pingBinder() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    /** The user the commands run as, 0 or 2000; null while not ready. */
    val uid: Int? get() = if (ready) runCatching { Shizuku.getUid() }.getOrNull() else null

    /** Once per process, at the launcher's start: listens for Shizuku and reads where it stands. */
    fun start(context: Context) {
        appContext = context.applicationContext
        if (started || Edition.play) return
        started = true
        Shizuku.addBinderReceivedListenerSticky {
            Log.i(TAG, "Shizuku binder received (uid ${runCatching { Shizuku.getUid() }.getOrNull()})")
            onChange()
        }
        Shizuku.addBinderDeadListener {
            Log.i(TAG, "Shizuku binder gone")
            unbindQuietly()
            onChange()
        }
        Shizuku.addRequestPermissionResultListener { code, result ->
            if (code != REQUEST_CODE) return@addRequestPermissionResultListener
            Log.i(TAG, "Shizuku permission: ${if (result == PackageManager.PERMISSION_GRANTED) "granted" else "denied"}")
            onChange()
        }
        refresh()
    }

    /** Reads where Shizuku stands again (the launcher came back to the front: it may have been installed or started). */
    fun refresh() {
        val context = appContext ?: return
        val installed = isPackageInstalled(context, PACKAGE)
        val next = when {
            !installed -> ShizukuState.NOT_INSTALLED
            !Shizuku.pingBinder() -> ShizukuState.NOT_RUNNING
            Shizuku.isPreV11() -> ShizukuState.OLD_VERSION
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> ShizukuState.NOT_ALLOWED
            else -> ShizukuState.READY
        }
        if (next != _state.value) Log.i(TAG, "Shizuku: $next")
        _state.value = next
        if (next == ShizukuState.READY) grantOwnPermissions(context)
    }

    private fun onChange() {
        refresh()
        PrivilegedShell.recheck()
    }

    /**
     * Asks the driver, through Shizuku's own dialog. When they refused for good
     * there, Shizuku shows no dialog again: its own app is opened instead, where
     * the choice is undone under Authorized applications.
     */
    fun requestPermission(context: Context) {
        if (!Shizuku.pingBinder() || Shizuku.isPreV11()) return openApp(context)
        if (Shizuku.shouldShowRequestPermissionRationale()) return openApp(context)
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }
            .onFailure { Log.w(TAG, "could not ask Shizuku: ${it.message}"); openApp(context) }
    }

    /** Opens the Shizuku app (to start it, or to allow Dashwheel). */
    fun openApp(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE)
        if (intent == null || !context.launchSafely(intent)) openDownload(context)
    }

    /** Opens its page in the store when there is one, else the project's download page. */
    fun openDownload(context: Context) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PACKAGE"))
        if (!context.launchSafely(market)) context.launchSafely(Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_URL)))
    }

    // ---- Commands ----

    /** Guards the two fields below, briefly: never held while waiting for the service. */
    private val lock = Any()
    /** One binding at a time; held through the wait, by the command's own thread only. */
    private val bindLock = Any()
    private var service: IShizukuShell? = null
    private var binding: ServiceConnection? = null

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, ShizukuShellService::class.java.name))
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    /**
     * Runs [cmd] through Shizuku's shell service, bounded by [timeoutS]. The
     * first command starts the service and waits for it. Throws when Shizuku is
     * not ready, when the service does not come, or when the call itself fails;
     * a command's own failure comes back in [RootShell.Output.exit].
     * Never on the main thread: it waits.
     */
    internal fun run(cmd: String, timeoutS: Long): RootShell.Output {
        check(ready) { "Shizuku is not ready" }
        val svc = synchronized(bindLock) {
            synchronized(lock) { service?.takeIf { it.asBinder().isBinderAlive } } ?: bind()
        }
        val bundle = try {
            svc.run(cmd, timeoutS.toInt())
        } catch (e: Exception) {
            // The service died under the call: the next command starts a new one.
            unbindQuietly()
            throw IllegalStateException("Shizuku shell: ${e.message}", e)
        }
        return RootShell.Output(bundle.getInt(ShizukuShellService.EXIT, -1), bundle.getString(ShizukuShellService.OUT).orEmpty(), "")
    }

    /**
     * Starts the service and waits for its binder (Shizuku answers on the main
     * thread, so never from there). Only from inside [bindLock].
     */
    private fun bind(): IShizukuShell {
        val latch = CountDownLatch(1)
        var bound: IShizukuShell? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
                if (binder != null && binder.pingBinder()) bound = IShizukuShell.Stub.asInterface(binder)
                else Log.w(TAG, "Shizuku shell service bound without a live binder")
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                Log.i(TAG, "Shizuku shell service gone")
                synchronized(lock) { if (binding === this) { service = null; binding = null } }
            }
        }
        synchronized(lock) { binding = connection }
        Shizuku.bindUserService(args, connection)
        if (!latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            unbindQuietly()
            throw IllegalStateException("Shizuku shell service did not start in ${BIND_TIMEOUT_MS / 1000}s")
        }
        val svc = bound ?: run { unbindQuietly(); throw IllegalStateException("Shizuku shell service did not answer") }
        synchronized(lock) { service = svc }
        Log.i(TAG, "Shizuku shell service bound (uid ${runCatching { svc.uid() }.getOrNull()})")
        return svc
    }

    private fun unbindQuietly() {
        synchronized(lock) {
            val conn = binding
            service = null
            binding = null
            if (conn != null) runCatching { Shizuku.unbindUserService(args, conn, true) }
        }
    }

    // ---- The permissions a shell can grant ----

    /** Development permissions `pm grant` hands out from the shell; both are in the manifest. */
    private val GRANTS = listOf(Manifest.permission.WRITE_SECURE_SETTINGS, Manifest.permission.READ_LOGS)

    /**
     * Grants Dashwheel what a PC's `adb shell pm grant` would, once per process,
     * off the main thread: the car app's switches then work by Dashwheel's own
     * hand ([RomPopups.writeGlobals]), with or without Shizuku later on.
     */
    private fun grantOwnPermissions(context: Context) {
        if (granted) return
        val missing = GRANTS.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) { granted = true; return }
        granted = true
        thread(isDaemon = true, name = "shizuku-grant") {
            var all = true
            for (perm in missing) {
                val res = runCatching { run("pm grant ${context.packageName} $perm", 10) }.getOrNull()
                when {
                    res == null -> Log.w(TAG, "could not grant $perm through Shizuku")
                    res.exit != 0 -> Log.w(TAG, "Shizuku could not grant $perm: ${res.all.trim().lines().firstOrNull()}")
                    else -> Log.i(TAG, "granted $perm through Shizuku")
                }
                if (res == null || res.exit != 0) all = false
            }
            // A grant that failed (the service not bound in time, Shizuku gone) is tried again at the next refresh, not given up on for the process.
            if (!all) granted = false
            // A grant just made counts at once where the permission is read each time.
            PrivilegedShell.recheck()
        }
    }
}
