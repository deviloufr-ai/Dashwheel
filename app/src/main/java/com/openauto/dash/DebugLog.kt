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
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    /** The newest part of the event log that goes in a report, leaving room in it for the summary. */
    private const val REPORT_EVENTS_CHARS = 40_000
    // ActivityTaskManager: which app started which screen, to see who puts a launcher in front at boot.
    // PhoneLink: each try to reach the phone, and why a link ended (issue #118).
    // chatty: where Android dropped lines of the app writing the most (a Hizpo's report lacked some of ours, issue #60).
    private const val LOGCAT = "logcat -d -v threadtime -t 3000 CarPower:I EmbeddedApp:I MediaResume:I DebugLog:I UnitLauncher:I NavDirections:I WazeScreen:I MapsScreen:I TileText:I SystemDisplays:I SystemDisplayHelper:I ShadowRoute:I WazeDestination:I SecondScreen:I DisplayLink:I ActivityTaskManager:I chatty:I PhoneLink:I *:W"

    /**
     * Every screen opened or brought to the front, from the events log: it is
     * written far less than the main one, so it still goes back to the boot
     * when the main log only holds the last half minute.
     */
    private const val SCREENS = "logcat -b events -d -v threadtime | grep -E ' (wm|am)_(create_activity|restart_activity|new_intent|task_moved_to_front|set_resumed_activity|finish_activity): | am_proc_start: .*,activity,' | tail -n 300"

    /** Every setting and property naming a launcher or a home screen: where a unit keeps its own choice. */
    private const val LAUNCHER_SETTINGS = "getprop | grep -i -E 'launcher|home|freeform|persist.sys.maps'; for t in global system secure; do settings list \$t | grep -i -E 'launcher|home' | sed \"s/^/\$t: /\"; done"

    /**
     * The car's temperature as CAN apps leave it (NWD's car_config_out_temp, issue #122): every setting
     * table and system property naming a temperature or holding one ("16℃"). An NWD unit's status bar
     * showed 16℃ offline with no such system setting, so it keeps it elsewhere, or is the weather kept.
     */
    private const val TEMP_SETTINGS = "for t in system global secure; do settings list \$t | grep -i -E 'temp|℃|℉|°' | sed \"s/^/\$t: /\"; done | head -n 40; " +
        "getprop | grep -i -E 'temp|℃|℉|°' | grep -v -i -E 'thermal|battery|cpu|gpu|skin|tsens' | head -n 20"

    /**
     * The screens Android holds on each display, and which one it counts as
     * the home screen: the one it shows whenever the app in front goes away.
     */
    private const val STACKS = "dumpsys activity activities | grep -E 'Display #|Stack #|\\* Task|\\* Hist|mActivityType|activityType=' | head -n 80"

    /** Whether storage is encrypted per file: then only apps that can run locked are Home at the first start. */
    private const val ENCRYPTION = "echo \$(getprop ro.crypto.type) \$(getprop ro.crypto.state)"

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One line after the other, in the order they were noted. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    /**
     * One line of what the car and the unit just did, kept across reboots. Stamped
     * now, written on the log's own thread: callers include the main thread at
     * switch-off, where a slow flash write would stall the screen.
     */
    fun note(context: Context, message: String) {
        val line = "${stamp()} $message\n"
        val file = File(context.applicationContext.filesDir, EVENTS)
        writer.launch { append(file, line) }
    }

    /**
     * [note], written before this returns: for the crash handler, on a thread
     * about to die with the process; a line handed to the log's own thread
     * would die with it, and the restart that follows would be unexplained.
     */
    fun noteNow(context: Context, message: String) {
        append(File(context.applicationContext.filesDir, EVENTS), "${stamp()} $message\n")
    }

    private fun append(file: File, line: String) {
        synchronized(lock) {
            runCatching {
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
            // Nobody asked for this one: no shell that would raise the unit's ADB prompt at every start.
            if (!DockShell.unattended(app)) return@launch
            runCatching {
                val log = DockShell.shell(app, LOGCAT)
                if (log.isBlank()) return@runCatching
                val text = "$log\n--- Screens opened ---\n${runCatching { DockShell.shell(app, SCREENS) }.getOrDefault("")}"
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
            // The firmware family and what pointed at it (UnitPlatform), then the QF ignition property as the unit has it.
            UnitPlatform.reportLines().forEach { (k, v) -> appendLine("$k: $v") }
            appendLine("Ignition property: ${sh("getprop sys.qf.is.acc.on")}")
            // Android's Home, and the one a QF unit starts by itself ([UnitLauncher]).
            val home = context.packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY
            )?.activityInfo?.packageName
            appendLine("Home: Android $home, unit's own ${sh("getprop persist.sys.qf.launcher").ifBlank { "-" }}")
            // Whether the Maps dock layouts are offered ([DockSupport]).
            appendLine("App windows: ${DockSupport.describe(context)}")
            appendLine("Encryption: ${sh(ENCRYPTION).ifBlank { "-" }}")
            appendLine("Launcher settings:")
            appendLine(sh(LAUNCHER_SETTINGS).ifBlank { "-" })
            appendLine("Temperature settings:")
            appendLine(sh(TEMP_SETTINGS).ifBlank { "-" })
            appendLine("Resources now: ${ResourceWatch.lineNow(context)}")
            appendLine()
            appendLine("Lost deep sleeps:")
            appendLine(CarPower.sleepLost(context).joinToString("\n").ifBlank { "none" })
            appendLine()
            // The crashes the unit still holds ([CrashReports]): a restart every few seconds in the event log has its cause here.
            appendLine("Crashes kept:")
            appendLine(CrashReports.kept(context).joinToString("\n").ifBlank { "none" })
        }
        val events = synchronized(lock) { runCatching { File(context.filesDir, EVENTS).readText() }.getOrDefault("") }
        val snapshots = synchronized(lock) {
            File(context.filesDir, SNAP_DIR).listFiles()?.sortedBy { it.name }?.joinToString("\n") { runCatching { it.readText() }.getOrDefault("") }
        }.orEmpty()
        // The link to the phone cuts what doesn't fit from the front: the long logs
        // go first, the short sections that matter most last, where they are kept.
        fenced("Saved snapshots", snapshots.ifBlank { "none" }) + fenced("Log now", sh(LOGCAT)) +
            fenced("Screens opened", sh(SCREENS).ifBlank { "none" }) + fenced("Screens held", sh(STACKS).ifBlank { "none" }) +
            fenced("Event log (kept across reboots)", events.takeLast(REPORT_EVENTS_CHARS).ifBlank { "empty" }) +
            // What grew over the days, and why the app's processes ended (Android 11 and later).
            fenced("Resource trend", ResourceWatch.trend(events).ifBlank { "none yet" }) +
            fenced("App exits", ResourceWatch.exitReasons(context).ifBlank { "not kept (Android 9 and 10) or none" }) +
            fenced("Summary", head)
    }

    private fun fenced(title: String, body: String) = "### $title\n```\n$body\n```\n\n"

    /** Gives the report to the linked phone; true when saved there, false when it failed, null without an answer. */
    suspend fun sendToPhone(context: Context): Boolean? =
        PhoneLink.sendLog("Unit log ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}", report(context))

    /**
     * Saves the report on the unit itself, in Download/Dashwheel, to copy to
     * a USB stick or read with a file manager; the file's name, or null when
     * it could not be written.
     */
    suspend fun saveOnUnit(context: Context): String? {
        val text = report(context)
        return withContext(Dispatchers.IO) {
            val name = "Unit-log-${SimpleDateFormat("yyyy-MM-dd-HH-mm", Locale.US).format(Date())}.txt"
            runCatching {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    // Android 9 has no MediaStore.Downloads: the file goes straight into
                    // the shared folder, with the storage permission the row asks for.
                    @Suppress("DEPRECATION")
                    val dir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "Dashwheel")
                    if (!dir.isDirectory && !dir.mkdirs()) error("no folder")
                    File(dir, name).writeText(text)
                    return@runCatching name
                }
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_DOWNLOADS}/Dashwheel")
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("no file")
                resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: error("not writable")
                name
            }.getOrNull()
        }
    }

    private fun stamp() = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
}
