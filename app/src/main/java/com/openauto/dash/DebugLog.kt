package com.openauto.dash

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the unit keeps for a bug report, since logcat is wiped at every reboot:
 * a small event log, and a snapshot of the system log taken around each switch
 * off and wake-up. The driver reviews and sends it from Settings, to the phone
 * ([PhoneLink.sendLog]); the companion app shares it from there (a GitHub issue,
 * a mail). Nothing leaves the unit unless the driver sends it.
 */
object DebugLog {

    private const val TAG = "DebugLog"
    private const val EVENTS = "debug.log"
    private const val SNAP_DIR = "log_snapshots"
    private const val KEEP_BYTES = 100_000
    private const val KEEP_SNAPSHOTS = 4
    // ActivityTaskManager: which app started which screen, to see who puts a launcher in front at boot.
    private const val LOGCAT = "logcat -d -v threadtime -t 3000 CarPower:I EmbeddedApp:I MediaResume:I DebugLog:I UnitLauncher:I ActivityTaskManager:I *:W"

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One line of what the car and the unit just did, kept across reboots. */
    fun note(context: Context, message: String) {
        val line = "${stamp()} $message\n"
        synchronized(lock) {
            runCatching {
                val file = File(context.applicationContext.filesDir, EVENTS)
                if (file.length() > KEEP_BYTES) file.writeText(file.readText().takeLast(KEEP_BYTES / 2))
                file.appendText(line)
            }
        }
    }

    /** Saves the system log as it is now, [why] naming the moment; the last few are kept. */
    fun snapshot(context: Context, why: String) {
        val app = context.applicationContext
        // The system log comes through the shell: the Play edition keeps its event log only.
        if (Edition.play) return
        scope.launch {
            runCatching {
                val text = DockShell.shell(app, LOGCAT)
                if (text.isBlank()) return@runCatching
                synchronized(lock) {
                    val dir = File(app.filesDir, SNAP_DIR).apply { mkdirs() }
                    File(dir, "${System.currentTimeMillis()}.txt").writeText("Snapshot: $why, ${stamp()}\n$text".takeLast(KEEP_BYTES * 2))
                    dir.listFiles()?.sortedBy { it.name }?.dropLast(KEEP_SNAPSHOTS)?.forEach { it.delete() }
                }
            }.onFailure { Log.w(TAG, "can't save a log snapshot", it) }
        }
    }

    /** [snapshot] once [afterMs] have passed: what the unit did in its first minute is gone from the log soon after. */
    fun snapshotLater(context: Context, why: String, afterMs: Long) {
        val app = context.applicationContext
        scope.launch {
            delay(afterMs)
            snapshot(app, why)
        }
    }

    /** When the newest snapshot was taken, or null when there is none. */
    fun lastSnapshot(context: Context): Long? = synchronized(lock) {
        File(context.filesDir, SNAP_DIR).listFiles()?.mapNotNull { it.nameWithoutExtension.toLongOrNull() }?.maxOrNull()
    }

    /** The report: build, boot, lost deep sleeps, the event log, the saved snapshots, then the log as it is now. */
    suspend fun report(context: Context): String = withContext(Dispatchers.IO) {
        val boot = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(-1)
        suspend fun sh(cmd: String) = if (Edition.play) "" else runCatching { DockShell.shell(context, cmd) }.getOrDefault("").trim()
        val reason = sh("getprop sys.boot.reason; getprop ro.boot.bootreason").replace('\n', ' ')
        val head = buildString {
            appendLine("App ${runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()}")
            appendLine("Android ${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Boot count $boot, uptime ${SystemClock.elapsedRealtime() / 1000} s, now ${Date()}")
            appendLine("Boot reason: $reason")
            // What Android kept of the earlier boots and of the last one's end, to tell a watchdog or a crash from a plain reboot.
            appendLine("Last boot reason: ${sh("getprop sys.boot.reason.last").ifBlank { "-" }}")
            appendLine("Boot reason history:")
            appendLine(sh("getprop persist.sys.boot.reason.history").ifBlank { "-" })
            appendLine("Kept kernel logs: ${sh("ls /sys/fs/pstore /proc/last_kmsg 2>&1").replace('\n', ' ')}")
            appendLine("Ignition property: ${sh("getprop sys.qf.is.acc.on")}")
            // Android's Home, and the one a QF unit starts by itself ([UnitLauncher]).
            val home = context.packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY
            )?.activityInfo?.packageName
            appendLine("Home: Android $home, unit's own ${sh("getprop persist.sys.qf.launcher").ifBlank { "-" }}")
            appendLine()
            appendLine("Lost deep sleeps:")
            appendLine(CarPower.sleepLost(context).joinToString("\n").ifBlank { "none" })
        }
        val events = synchronized(lock) { runCatching { File(context.filesDir, EVENTS).readText() }.getOrDefault("") }
        val snapshots = synchronized(lock) {
            File(context.filesDir, SNAP_DIR).listFiles()?.sortedBy { it.name }?.joinToString("\n") { runCatching { it.readText() }.getOrDefault("") }
        }.orEmpty()
        // Newest last, so what is cut to fit the frame (from the front) is the oldest.
        fenced("Summary", head) + fenced("Event log (kept across reboots)", events.ifBlank { "empty" }) +
            fenced("Saved snapshots", snapshots.ifBlank { "none" }) + fenced("Log now", sh(LOGCAT))
    }

    private fun fenced(title: String, body: String) = "### $title\n```\n$body\n```\n\n"

    /** Gives the report to the linked phone; true when saved there, false when it failed, null without an answer. */
    suspend fun sendToPhone(context: Context): Boolean? =
        PhoneLink.sendLog("Unit log ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}", report(context))

    private fun stamp() = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
}
