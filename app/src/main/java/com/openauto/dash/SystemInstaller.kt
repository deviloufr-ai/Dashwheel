package com.openauto.dash

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Self-installs the app into `/system/priv-app` on a **rooted** device, so it
 * runs as a privileged system app (the same mechanism OEM/aftermarket car
 * launchers use to gain system privileges). Uses `su`; the user grants root
 * via Magisk.
 *
 * Does NOT reboot automatically unless asked ([reboot]). Writing to `/system`
 * can fail (or, with enforcing dm-verity, risk a boot loop), so this is a
 * power-user action guarded by a confirmation in the UI.
 */
object SystemInstaller {

    private const val DIR = "/system/priv-app/OpenAutoDash"
    private const val DEST = "$DIR/OpenAutoDash.apk"

    /** True if the app is already running from a system/privileged location. */
    fun isSystemApp(context: Context): Boolean {
        val info = context.applicationInfo
        val flagged = (info.flags and
            (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
        val path = info.sourceDir.startsWith("/system/") ||
            info.sourceDir.startsWith("/system_ext/") ||
            info.sourceDir.startsWith("/priv-app/") ||
            info.sourceDir.startsWith("/product/") ||
            info.sourceDir.startsWith("/vendor/") ||
            info.sourceDir.startsWith("/odm/")
        return flagged || path
    }

    /**
     * True if a root shell (`su`) is available and granted. Bounded: if the
     * Magisk prompt is left unanswered the probe gives up instead of pinning an
     * IO thread forever.
     *
     * Each `su` makes Magisk show its "granted Superuser rights" toast, so a
     * yes is kept for the process (the probe, the shells and the installers
     * all ask), and the probe turns that toast off for this app, as Magisk's
     * own switch under Superuser does: a launcher on root all day showed it
     * over and over.
     */
    fun isRootAvailable(): Boolean {
        if (rootGranted) return true
        return runCatching {
            RootShell.su("id && { $quietToast; true; }", ROOT_PROBE_TIMEOUT_S).exit == 0
        }.getOrDefault(false).also { if (it) rootGranted = true }
    }

    @Volatile private var rootGranted = false

    /** Magisk's per-app toast switch, off for this app; nothing where there is no Magisk. */
    private val quietToast =
        "magisk --sqlite \"UPDATE policies SET notification=0 WHERE uid=${android.os.Process.myUid()}\" >/dev/null 2>&1"

    private const val ROOT_PROBE_TIMEOUT_S = 8L

    /** Copying the APK and syncing /data or /system is slow on this unit, but must not hang forever. */
    private const val INSTALL_TIMEOUT_S = 120L

    /** Name of the privileged-permission whitelist, the same for every install path. */
    internal const val PRIVAPP_XML_NAME = "privapp-permissions-openautodash.xml"

    /**
     * Every privileged permission the app asks for (the manifest's
     * "signature|privileged" ones). A ROM that enforces the whitelist refuses
     * to boot with a priv-app missing one, so a permission added to the
     * manifest is added here too. One line, no single quotes: it is echoed
     * inside single quotes by the install scripts.
     */
    internal const val PRIVAPP_XML =
        "<permissions><privapp-permissions package=\"com.openauto.dash\">" +
            "<permission name=\"android.permission.BIND_APPWIDGET\"/>" +
            "<permission name=\"android.permission.WRITE_SECURE_SETTINGS\"/>" +
            "<permission name=\"android.permission.READ_LOGS\"/>" +
            "</privapp-permissions></permissions>"

    /**
     * Installs the app as a privileged system app using `su`.
     *
     * On a Magisk device it creates a **systemless Magisk module** that provides
     * the APK at `/system/priv-app/OpenAutoDash` (mounted from `/data`, so it does
     * NOT need free space on the full `/system` partition) plus a
     * privapp-permissions whitelist so the priv-app boots cleanly. Without Magisk
     * it falls back to writing `/system` directly (fails if `/system` is full).
     */
    fun installAsSystemApp(context: Context): Result<Unit> = runCatching {
        val apk = context.applicationInfo.sourceDir
        val mod = "/data/adb/modules/openautodash"
        val script = """
            if [ -d /data/adb/modules ]; then
              mkdir -p $mod/system/priv-app/OpenAutoDash $mod/system/etc/permissions || exit 21
              cp '$apk' $mod/system/priv-app/OpenAutoDash/OpenAutoDash.apk || exit 22
              chmod 644 $mod/system/priv-app/OpenAutoDash/OpenAutoDash.apk
              cat > $mod/module.prop <<'P'
            id=openautodash
            name=OpenAuto Dash (priv-app)
            version=v1
            versionCode=1
            author=OpenAutoDash
            description=Installs OpenAuto Dash as a privileged system app (systemless).
            P
              echo '$PRIVAPP_XML' > $mod/system/etc/permissions/$PRIVAPP_XML_NAME || exit 23
              echo OKINSTALL:magisk
            else
              mount -o remount,rw / 2>/dev/null
              mount -o remount,rw /system 2>/dev/null
              mkdir -p /system/priv-app/OpenAutoDash || exit 31
              # A full /system stops the copy half-way: no cut-off APK is left where the
              # system would find it at the next start, nor /system writable.
              cp '$apk' /system/priv-app/OpenAutoDash/OpenAutoDash.apk || {
                rm -rf /system/priv-app/OpenAutoDash; sync; mount -o remount,ro /system 2>/dev/null; exit 32
              }
              chmod 644 /system/priv-app/OpenAutoDash/OpenAutoDash.apk
              chcon u:object_r:system_file:s0 /system/priv-app/OpenAutoDash/OpenAutoDash.apk 2>/dev/null
              mkdir -p /system/etc/permissions
              # Fatal only where the ROM enforces the whitelist (see AdbInstaller).
              echo '$PRIVAPP_XML' > /system/etc/permissions/$PRIVAPP_XML_NAME || {
                if [ "$(getprop ro.control_privapp_permissions)" = enforce ]; then rm -rf /system/priv-app/OpenAutoDash; exit 33; fi
              }
              chmod 644 /system/etc/permissions/$PRIVAPP_XML_NAME
              chcon u:object_r:system_file:s0 /system/etc/permissions/$PRIVAPP_XML_NAME 2>/dev/null
              sync
              mount -o remount,ro /system 2>/dev/null
              mount -o remount,ro / 2>/dev/null
              echo OKINSTALL:system
            fi
        """.trimIndent()

        val res = RootShell.su(script, INSTALL_TIMEOUT_S)
        if (!res.out.contains("OKINSTALL")) {
            error((res.err.ifBlank { res.out }).trim().ifBlank { context.getString(R.string.sys_install_su_failed) })
        }
        compileInBackground { cmd -> RootShell.su(cmd, COMPILE_TIMEOUT_MS / 1000L).all }
    }

    /** Reboots the device via root so the system-app install takes effect. */
    fun reboot(): Result<Unit> = runCatching {
        Runtime.getRuntime().exec(arrayOf("su", "-c", "svc power reboot || reboot"))
        Unit
    }

    /**
     * Installs the app as a privileged system app, trying `su` first (Magisk:
     * systemless, survives a full /system) and falling back to the head unit's
     * internal root ADB socket ([AdbInstaller]).
     */
    fun install(context: Context): Result<Unit> {
        val viaSu = installAsSystemApp(context)
        if (viaSu.isSuccess) return viaSu
        val viaAdb = AdbInstaller.installViaAdb(context)
        return if (viaAdb.isSuccess) viaAdb else viaAdb.recoverCatching {
            error("su: ${viaSu.exceptionOrNull()?.message}; ADB: ${it.message}")
        }
    }

    /** Reboots via su, falling back to the root ADB socket. */
    fun rebootDevice(context: Context): Result<Unit> =
        reboot().recoverCatching { AdbInstaller.rebootViaAdb(context).getOrThrow() }

    /** A full AOT compile of a Compose app on this CPU takes minutes. */
    internal const val COMPILE_TIMEOUT_MS = 600_000

    /**
     * Compiles the app ahead of time from the baseline profiles it ships, so
     * Compose does not run interpreted on the unit's slow CPU after the
     * install. Runs on a thread of its own through [run] (the shell the install
     * used) and never holds anything up; a failure only costs speed.
     */
    fun compileInBackground(run: (String) -> String) {
        thread(isDaemon = true, name = "dexopt") {
            runCatching { run("cmd package compile -m speed-profile -f $APP_PACKAGE") }
                .onSuccess { Log.i(TAG, "compiled: ${it.trim()}") }
                .onFailure { Log.w(TAG, "compile after install failed", it) }
        }
    }

    private const val APP_PACKAGE = "com.openauto.dash"
    private const val TAG = "SystemInstaller"
}

/**
 * The one way the app runs `su`: both output streams read at once (a full
 * pipe cannot stall the command) and the command killed after a timeout, so
 * an unanswered Magisk prompt or a stuck command cannot hang its caller.
 */
internal object RootShell {

    class Output(val exit: Int, val out: String, val err: String) {
        val all: String get() = out + err
    }

    /**
     * `su -c [cmd]`, or, with [cmd] null, `su` running [stdin] as its script
     * (no quoting gets in the way). Throws when [timeoutS] runs out.
     */
    fun su(cmd: String?, timeoutS: Long, stdin: String? = null): Output {
        val process = Runtime.getRuntime().exec(if (cmd != null) arrayOf("su", "-c", cmd) else arrayOf("su"))
        // One builder per stream: the two readers run concurrently.
        val out = StringBuilder()
        val err = StringBuilder()
        val reader = Thread { runCatching { out.append(process.inputStream.bufferedReader().readText()) } }
        val errReader = Thread { runCatching { err.append(process.errorStream.bufferedReader().readText()) } }
        reader.start(); errReader.start()
        // su gone before reading its script: the exit status says why.
        runCatching { process.outputStream.bufferedWriter().use { w -> stdin?.let(w::write) } }
        if (!process.waitFor(timeoutS, TimeUnit.SECONDS)) {
            process.destroy()
            throw IllegalStateException("su timed out")
        }
        reader.join(2000); errReader.join(2000)
        return Output(process.exitValue(), out.toString(), err.toString())
    }

    /**
     * One `su` kept open, the commands written to it one after another and
     * each answer read back up to an end mark. The docking poller lists the
     * windows every few seconds, and a fresh su for each (a fork, Magisk's
     * grant, two reader threads) cost more than the listing itself. One
     * command at a time: the caller serialises. A command that runs out of
     * time, or a shell that has gone, closes the session; the next command
     * opens a new one. Both output streams come back as one ([Output.out]).
     * [shell] is `su`; a test hands it a plain shell.
     */
    class Session(shell: String = "su") {
        private val process: Process = Runtime.getRuntime().exec(arrayOf(shell))
        private val input = process.outputStream.bufferedWriter()
        private val lines = LinkedBlockingQueue<String>()

        init {
            // Read on its own thread, so a wait for the end mark can time out.
            Thread({
                runCatching { process.inputStream.bufferedReader().forEachLine { lines.put(it) } }
                lines.put(GONE)
            }, "su-session").apply { isDaemon = true; start() }
        }

        /** Whether the shell is still there to take a command. */
        val alive: Boolean get() = process.isAlive

        /** Runs [cmd] (one line), waiting [timeoutS] at most for its end; throws when the shell fails. */
        fun run(cmd: String, timeoutS: Long): Output {
            require('\n' !in cmd) { "one line at a time" }
            try {
                // A subshell keeps a command's `;` chains and redirections to itself.
                // The empty line puts the end mark on a line of its own, also after
                // an output that does not end its last line (it is taken off below).
                input.write("( $cmd ) 2>&1\n__dw=\$?\necho\necho \"$MARK \$__dw\"\n")
                input.flush()
            } catch (e: IOException) {
                close()
                throw IllegalStateException("su gone: ${e.message}")
            }
            val out = ArrayList<String>()
            val deadline = System.nanoTime() + timeoutS * 1_000_000_000L
            while (true) {
                val wait = deadline - System.nanoTime()
                val line = (if (wait > 0) lines.poll(wait, TimeUnit.NANOSECONDS) else null) ?: run {
                    close()
                    throw IllegalStateException("su timed out")
                }
                if (line === GONE) {
                    close()
                    throw IllegalStateException("su exited")
                }
                if (line.startsWith(MARK)) {
                    if (out.lastOrNull()?.isEmpty() == true) out.removeAt(out.lastIndex)
                    return Output(line.substringAfter(' ').trim().toIntOrNull() ?: -1, out.joinToString("") { it + "\n" }, "")
                }
                out += line
            }
        }

        fun close() {
            runCatching { input.close() }
            process.destroy()
        }

        private companion object {
            /** Ends each command's output, followed by its exit status. */
            const val MARK = "__DW_END__"

            /** Queued once the shell's output ends: it has gone. */
            val GONE = String()
        }
    }
}
