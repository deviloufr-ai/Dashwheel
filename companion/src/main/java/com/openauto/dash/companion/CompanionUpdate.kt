package com.openauto.dash.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Keeps the companion up to date from the same GitHub release the launcher
 * updates from: the release's `dashwheel-companion.apk`, built with the same
 * run number as the launcher, so its tag (`v1.0.42`) tells the build.
 *
 * Checked when the app opens and when sharing starts, at most every few hours.
 * On Wi-Fi the new build downloads by itself and a notification offers to
 * install it; on mobile data the card waits for a tap. Android always asks
 * before installing, and only an APK signed with the same key replaces this one.
 */
object CompanionUpdate {
    data class Release(val build: Long, val name: String, val apkUrl: String)

    sealed interface Status {
        data object None : Status
        data class Available(val release: Release) : Status
        data class Downloading(val release: Release, val percent: Int) : Status
        data class Ready(val release: Release, val file: File) : Status
        data class Failed(val release: Release) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.None)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var checking: Job? = null
    private var downloading: Job? = null
    /** When this process last asked GitHub: a new process always asks. */
    private var checkedAt = 0L
    /** Opened from the notification in a new process: install as soon as the check finds the download. */
    private var installPending = false

    /** Looks for a newer build unless one was looked for lately; [force] looks anyway. */
    fun check(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        // A local build is signed with the debug key: a release could never install over it.
        if (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) return
        if (checking?.isActive == true || downloading?.isActive == true) return
        val now = System.currentTimeMillis()
        if (!force && checkedAt != 0L && now - checkedAt < CHECK_EVERY_MS) return
        checking = scope.launch {
            val release = withContext(Dispatchers.IO) { fetchLatest() } ?: return@launch
            checkedAt = now
            if (release.build <= installedBuild(app)) {
                withContext(Dispatchers.IO) { apkFile(app).delete() }
                _status.value = Status.None
                return@launch
            }
            val file = apkFile(app)
            _status.value = when {
                prefs(app).getLong(KEY_DOWNLOADED, -1L) == release.build && file.length() > 0L -> Status.Ready(release, file)
                else -> Status.Available(release)
            }
            when (val s = _status.value) {
                is Status.Ready -> if (installPending) install(app) else notifyReady(app, s.release)
                is Status.Available -> if (onUnmetered(app)) download(app)
                else -> Unit
            }
        }
    }

    /** "Later": the card stays away until a newer build comes along. */
    fun dismiss(context: Context) {
        val release = release(_status.value) ?: return
        prefs(context).edit().putLong(KEY_DISMISSED, release.build).apply()
    }

    fun isDismissed(context: Context, status: Status): Boolean =
        release(status)?.build?.let { it == prefs(context).getLong(KEY_DISMISSED, -1L) } == true

    /** Downloads the build on offer (or after a failure); a running download goes on. */
    fun download(context: Context) {
        val app = context.applicationContext
        val release = when (val s = _status.value) {
            is Status.Available -> s.release
            is Status.Failed -> s.release
            else -> return
        }
        if (downloading?.isActive == true) return
        downloading = scope.launch {
            _status.value = Status.Downloading(release, 0)
            val file = apkFile(app)
            val ok = withContext(Dispatchers.IO) {
                fetchApk(release.apkUrl, file) { percent -> _status.value = Status.Downloading(release, percent) }
            }
            if (ok) {
                prefs(app).edit().putLong(KEY_DOWNLOADED, release.build).apply()
                _status.value = Status.Ready(release, file)
                notifyReady(app, release)
            } else {
                withContext(Dispatchers.IO) { file.delete() }
                _status.value = Status.Failed(release)
            }
        }
    }

    /**
     * Hands the downloaded APK to Android's installer, first sending the
     * driver to allow installs from this app if that is still needed.
     */
    fun install(context: Context) {
        val ready = _status.value as? Status.Ready ?: return
        installPending = false
        if (!context.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(context, R.string.update_allow_installs, Toast.LENGTH_LONG).show()
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.logs", ready.file)
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, APK_MIME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        }
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    /** Installs the download, or once the check under way (or a new one) has found it again. */
    fun installWhenReady(context: Context) {
        if (_status.value is Status.Ready) {
            install(context)
            return
        }
        installPending = true
        check(context, force = true)
    }

    private fun release(status: Status): Release? = when (status) {
        is Status.Available -> status.release
        is Status.Downloading -> status.release
        is Status.Ready -> status.release
        is Status.Failed -> status.release
        Status.None -> null
    }

    /** Once per build: the card says the rest whenever the app is opened. */
    private fun notifyReady(context: Context, release: Release) {
        if (prefs(context).getLong(KEY_NOTIFIED, -1L) == release.build) return
        prefs(context).edit().putLong(KEY_NOTIFIED, release.build).apply()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.update_channel), NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            context, 4,
            Intent(context, MainActivity::class.java).putExtra(EXTRA_INSTALL, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle(context.getString(R.string.update_ready_title))
            .setContentText(context.getString(R.string.update_ready_text, release.name))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun installedBuild(context: Context): Long =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode }.getOrDefault(Long.MAX_VALUE)

    private fun onUnmetered(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun fetchLatest(): Release? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(LATEST_URL).openConnection() as HttpURLConnection).apply {
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Dashwheel-Companion-Updater")
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name")
            val build = Regex("(\\d+)").findAll(tag).lastOrNull()?.value?.toLongOrNull() ?: return null
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (!asset.optString("name").equals(APK_ASSET, ignoreCase = true)) continue
                val url = asset.optString("browser_download_url")
                // The URL comes from a document fetched over the network: GitHub's own hosts only.
                if (url.startsWith("https://") && Uri.parse(url).host in ALLOWED_HOSTS) return Release(build, tag, url)
            }
            null
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    /** Follows GitHub's redirect to its download host, staying on https and on GitHub's hosts. */
    private fun fetchApk(url: String, file: File, onProgress: (Int) -> Unit): Boolean {
        var next = url
        repeat(5) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(next).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "Dashwheel-Companion-Updater")
                    connectTimeout = 15_000
                    readTimeout = 30_000
                }
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location") ?: return false
                    if (!location.startsWith("https://") || Uri.parse(location).host !in ALLOWED_HOSTS) return false
                    next = location
                    return@repeat
                }
                if (code != HttpURLConnection.HTTP_OK) return false
                val total = connection.contentLengthLong
                file.parentFile?.mkdirs()
                var lastPercent = -1
                connection.inputStream.use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            done += n
                            if (total > 0) {
                                val percent = (done * 100 / total).toInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress(percent)
                                }
                            }
                        }
                        if (total > 0 && done != total) return false
                    }
                }
                return file.length() > 0L
            } catch (e: Exception) {
                return false
            } finally {
                connection?.disconnect()
            }
        }
        return false
    }

    private fun apkFile(context: Context) = File(File(context.cacheDir, "updates"), "companion-update.apk")

    private fun prefs(context: Context) = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    const val EXTRA_INSTALL = "install_update"
    private const val LATEST_URL = "https://api.github.com/repos/deviloufr-ai/Dashwheel/releases/latest"
    /** The companion's asset in each release (see .github/workflows/build.yml). */
    private const val APK_ASSET = "dashwheel-companion.apk"
    private const val APK_MIME = "application/vnd.android.package-archive"
    private const val CHANNEL = "updates"
    private const val NOTIFICATION_ID = 0x0D7
    private const val CHECK_EVERY_MS = 6 * 60 * 60_000L
    private const val KEY_DOWNLOADED = "downloaded_build"
    private const val KEY_DISMISSED = "dismissed_build"
    private const val KEY_NOTIFIED = "notified_build"
    private val ALLOWED_HOSTS = setOf("github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")
}
