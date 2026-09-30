package com.openauto.dash

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * For the test builds: a small event log that survives reboots (logcat does
 * not), and a report of it with the unit's log that is sent as an issue on the
 * project's GitHub. The token is never in the app: it is pushed to the unit
 * by tools/dash_log.ps1 (fine-grained, issues only).
 */
object DebugLog {

    private const val TAG = "DebugLog"
    private const val FILE = "debug.log"
    private const val KEEP_BYTES = 100_000
    private const val TOKEN_PATH = "/data/local/tmp/dashwheel_issue_token"
    private const val ISSUES = "https://api.github.com/repos/deviloufr-ai/Dashwheel/issues"

    /** GitHub refuses an issue body over 65,536 characters. */
    private const val BODY_MAX = 60_000

    private val lock = Any()

    /** One line of what the car and the unit just did, kept across reboots. */
    fun note(context: Context, message: String) {
        val line = "${SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())} $message\n"
        synchronized(lock) {
            runCatching {
                val file = File(context.applicationContext.filesDir, FILE)
                if (file.length() > KEEP_BYTES) file.writeText(file.readText().takeLast(KEEP_BYTES / 2))
                file.appendText(line)
            }
        }
    }

    /** The report: build, boot, lost deep sleeps, the event log, then the unit's log. */
    suspend fun report(context: Context): String {
        val events = synchronized(lock) {
            runCatching { File(context.filesDir, FILE).readText() }.getOrDefault("")
        }
        val boot = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(-1)
        suspend fun sh(cmd: String) = runCatching { DockShell.shell(context, cmd) }.getOrDefault("").trim()
        val reason = sh("getprop sys.boot.reason; getprop ro.boot.bootreason").replace('\n', ' ')
        val acc = sh("getprop sys.qf.is.acc.on")
        val head = buildString {
            appendLine("App ${runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()}")
            appendLine("Android ${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Boot count $boot, uptime ${SystemClock.elapsedRealtime() / 1000} s, now ${Date()}")
            appendLine("Boot reason: $reason")
            appendLine("Ignition property: $acc")
            appendLine()
            appendLine("Lost deep sleeps:")
            appendLine(CarPower.sleepLost(context).joinToString("\n").ifBlank { "none" })
        }
        val logcat = withContext(Dispatchers.IO) {
            DockShell.shell(context, "logcat -d -v threadtime -t 5000 CarPower:I EmbeddedApp:I MediaResume:I DebugLog:I *:W")
        }
        val pstore = withContext(Dispatchers.IO) {
            DockShell.shell(context, "ls -l /sys/fs/pstore 2>&1 | head -5")
        }
        return fenced("Summary", head) + fenced("Event log (kept across reboots)", events.ifBlank { "empty" }) +
            fenced("pstore", pstore) + fenced("Logcat (this boot, newest last)", logcat)
    }

    private fun fenced(title: String, body: String) = "### $title\n```\n$body\n```\n\n"

    /** Sends the report as a new issue; the result is the issue's address or the reason it failed. */
    suspend fun send(context: Context): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val token = DockShell.shell(context, "cat $TOKEN_PATH 2>/dev/null").trim()
            require(token.isNotEmpty() && !token.contains(' ') && !token.contains('\n')) { "no token" }
            val full = report(context)
            // The newest lines matter most: cut from the front of the log.
            val body = if (full.length > BODY_MAX) "(cut)\n" + full.takeLast(BODY_MAX) else full
            val json = JSONObject()
                .put("title", "Unit log ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}")
                .put("body", body)
                .toString()
            val conn = (URL(ISSUES).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 30_000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("Content-Type", "application/json")
            }
            try {
                conn.outputStream.use { it.write(json.toByteArray()) }
                val code = conn.responseCode
                if (code != HttpURLConnection.HTTP_CREATED) error("GitHub answered $code")
                JSONObject(conn.inputStream.bufferedReader().readText()).getString("html_url")
            } finally {
                conn.disconnect()
            }
        }.onFailure { Log.w(TAG, "can't send the log", it) }
    }
}
