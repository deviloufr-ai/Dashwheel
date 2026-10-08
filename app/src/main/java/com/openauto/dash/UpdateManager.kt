package com.openauto.dash

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Details of the newest release found on GitHub. */
data class UpdateInfo(
    val versionName: String,
    val buildNumber: Long,
    val apkUrl: String,
    val notes: String
)

/** Update lifecycle observed by the UI. */
sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data object UpToDate : UpdateStatus
    /** A newer build exists and the driver has not put it off. */
    data class Available(val info: UpdateInfo) : UpdateStatus
    /** A newer build exists but the driver said "later": only Settings still offers it. */
    data class Dismissed(val info: UpdateInfo) : UpdateStatus
    data class Downloading(val info: UpdateInfo, val percent: Int) : UpdateStatus
    /** Downloaded and waiting for the go-ahead to install. */
    data class Ready(val info: UpdateInfo, val file: File) : UpdateStatus
    data object Installing : UpdateStatus
    data class Error(@StringRes val messageRes: Int) : UpdateStatus
}

/** The update a status is about, if any. */
val UpdateStatus.updateInfo: UpdateInfo?
    get() = when (this) {
        is UpdateStatus.Available -> info
        is UpdateStatus.Dismissed -> info
        is UpdateStatus.Downloading -> info
        is UpdateStatus.Ready -> info
        else -> null
    }

/**
 * In-app updater that tracks the installed version against the latest GitHub
 * Release and downloads/installs a newer APK.
 *
 * Version tracking: the installed [currentVersionCode] comes from the APK
 * (set by CI to the Actions run number); the latest build number is parsed from
 * the release tag (e.g. `v1.0.42` → 42). A newer build number means an update.
 *
 * Channels: every build of main is published as a pre-release (beta); once a
 * week the newest beta out for at least two days is promoted to a
 * full release (stable, see promote.yml). The stable channel, the default,
 * follows GitHub's "latest" release; [beta] follows every build of main.
 *
 * Note: Android always shows its own install confirmation, and a downloaded APK
 * can only replace the installed one if both are signed with the same key.
 */
class UpdateManager(private val context: Context) {

    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    val currentVersionName: String = BuildConfig.VERSION_NAME
    val currentVersionCode: Long = BuildConfig.VERSION_CODE.toLong()

    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Where downloads run, whoever asked for them (see [download]). */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var downloadJob: Deferred<Boolean>? = null
    private var downloadBuild = -1L

    /** Beta updates: every build of main, not only the stable ones. Off by default. */
    private val _beta = MutableStateFlow(prefs.getBoolean(KEY_BETA, false))
    val beta: StateFlow<Boolean> = _beta.asStateFlow()

    /** Switches channel; the next [checkForUpdate] looks in the new one. */
    fun setBeta(on: Boolean) {
        prefs.edit().putBoolean(KEY_BETA, on).apply()
        _beta.value = on
    }

    /** Queries GitHub for the latest release and updates [status]. */
    suspend fun checkForUpdate() {
        // A download under way or done keeps its state: the check is for news.
        val keep = _status.value
        if (keep is UpdateStatus.Downloading || keep is UpdateStatus.Ready || keep is UpdateStatus.Installing) return
        _status.value = UpdateStatus.Checking
        val info = withContext(Dispatchers.IO) { if (_beta.value) fetchNewestBeta() else fetchLatestRelease() }
        val downloaded = info?.let { downloadedFile(it) }
        if (info != null && info.buildNumber <= currentVersionCode) withContext(Dispatchers.IO) { forgetDownloads() }
        _status.value = when {
            info == null -> UpdateStatus.Error(R.string.sys_update_check_failed)
            info.buildNumber <= currentVersionCode -> UpdateStatus.UpToDate
            downloaded != null -> UpdateStatus.Ready(info, downloaded)
            prefs.getLong(KEY_DISMISSED, -1L) == info.buildNumber -> UpdateStatus.Dismissed(info)
            else -> UpdateStatus.Available(info)
        }
    }

    /** The APK of [info] if an earlier download finished and is still there. */
    private fun downloadedFile(info: UpdateInfo): File? {
        if (prefs.getLong(KEY_DOWNLOADED, -1L) != info.buildNumber) return null
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), APK_NAME)
        return file.takeIf { it.exists() && it.length() > 0L }
    }

    /**
     * True on a connection that costs nothing per byte (home Wi-Fi rather
     * than a phone's hotspot): the only kind an update downloads on by itself.
     */
    fun onUnmeteredNetwork(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** The stable channel: GitHub's "latest" release, which pre-releases never are. */
    private fun fetchLatestRelease(): UpdateInfo? =
        fetchJson("releases/latest")?.let { body -> runCatching { releaseInfo(JSONObject(body)) }.getOrNull() }

    /**
     * The beta channel: the newest build of main, pre-release or not. A
     * branch's test build (tag `<branch>-v1.0.N`) is never offered.
     */
    private fun fetchNewestBeta(): UpdateInfo? {
        val body = fetchJson("releases?per_page=30") ?: return null
        return runCatching {
            val releases = JSONArray(body)
            (0 until releases.length())
                .mapNotNull { releases.optJSONObject(it) }
                .filter { !it.optBoolean("draft") && isMainBuildTag(it.optString("tag_name")) }
                .mapNotNull { releaseInfo(it) }
                .maxByOrNull { it.buildNumber }
        }.getOrNull()
    }

    private fun releaseInfo(json: JSONObject): UpdateInfo? {
        val tag = json.optString("tag_name")
        val buildNumber = parseBuildNumber(tag) ?: return null
        val apkUrl = firstApkAssetUrl(json) ?: return null
        return UpdateInfo(versionName = tag, buildNumber = buildNumber, apkUrl = apkUrl, notes = json.optString("body"))
    }

    /** The body of a GitHub API call on this app's repository, or null on any failure. */
    private fun fetchJson(path: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            val endpoint = URL(
                "https://api.github.com/repos/" +
                    "${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/$path"
            )
            connection = (endpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "OpenAutoDash-Updater")
                connectTimeout = 10_000
                readTimeout = 10_000
            }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                connection.errorStream?.close()
                return null
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun firstApkAssetUrl(release: JSONObject): String? {
        val assets = release.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name")
            // The release also carries the phone companion app: never install that here.
            if (name.endsWith(".apk", ignoreCase = true) && !name.equals(COMPANION_APK_NAME, ignoreCase = true)) {
                val url = asset.optString("browser_download_url").ifBlank { null } ?: continue
                // The URL comes from a JSON document fetched over the network;
                // only accept GitHub's own release hosts.
                val host = Uri.parse(url).host.orEmpty()
                if (url.startsWith("https://") && host in ALLOWED_DOWNLOAD_HOSTS) return url
            }
        }
        return null
    }

    /** True if the app may install APKs (Android 8+ requires a per-app grant). */
    fun canInstallPackages(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /** Sends the user to enable "install unknown apps" for this app. */
    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** Downloads the update APK (unless it already is), then launches the system installer. */
    suspend fun downloadAndInstall(info: UpdateInfo) {
        val file = downloadedFile(info) ?: (if (download(info)) downloadedFile(info) else null) ?: return
        install(file)
    }

    /** Launches the system installer on a downloaded [file]. */
    fun install(file: File) {
        val ready = _status.value as? UpdateStatus.Ready
        // The update is on offer again, to try once more, or nothing is if it was lost.
        fun offerAgain() {
            _status.value = ready?.takeIf { it.file.exists() } ?: UpdateStatus.Idle
        }
        _status.value = UpdateStatus.Installing
        scope.launch {
            if (!withContext(Dispatchers.IO) { isOurUpdate(file) }) {
                Log.w(TAG, "the downloaded file is not this app's update: dropped")
                withContext(Dispatchers.IO) { runCatching { file.delete() } }
                prefs.edit().remove(KEY_DOWNLOADED).apply()
                _status.value = UpdateStatus.Error(R.string.sys_update_download_failed)
                return@launch
            }
            if (!launchInstaller(file)) return@launch offerAgain()
            // An install that goes through replaces the app and ends this process.
            // Still here a while later: Android's own confirmation was cancelled, or
            // the install refused. "Installing" used to stay for good then (the unit
            // sleeps rather than restarts), with no further check and no way to retry.
            delay(INSTALL_WAIT_MS)
            if (_status.value is UpdateStatus.Installing) offerAgain()
        }
    }

    /**
     * Whether [apk] is this app, in a newer build, signed as this one is. The
     * system's installer checks the signature too, but not on a unit where
     * PMPatch switched that check off, and the download waits in a folder
     * other apps can write to. A debug build, signed with another key than
     * the releases, is let through to update to one as before.
     */
    @Suppress("DEPRECATION") // GET_SIGNATURES: the one flag Android 10 reads an APK file's signers with.
    private fun isOurUpdate(apk: File): Boolean = runCatching {
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNATURES) ?: return false
        if (info.packageName != context.packageName) return false
        if (PackageInfoCompat.getLongVersionCode(info) <= currentVersionCode) return false
        val theirs = info.signatures?.map { it.toCharsString() }?.toSet().orEmpty()
        // Not read (a system that doesn't tell): left to the installer's own check.
        if (BuildConfig.DEBUG || theirs.isEmpty()) return true
        val ours = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures?.map { it.toCharsString() }?.toSet().orEmpty()
        theirs == ours
    }.getOrDefault(false)

    /**
     * Downloads [info]'s APK into the app's own downloads folder; [status]
     * follows the progress and ends [UpdateStatus.Ready] or an error. True on success.
     *
     * The download runs in the updater's own scope, not the caller's: the
     * dashboard starts it from an effect keyed on [status], which the download
     * itself changes, and the effect's restart used to cancel it a second in,
     * remove it from the system's downloader and leave "Downloading 0 %" for
     * good. A caller that goes away now only stops waiting. A second call for
     * the same build joins the download under way.
     */
    suspend fun download(info: UpdateInfo): Boolean {
        val running = downloadJob?.takeIf { it.isActive && downloadBuild == info.buildNumber }
        val job = running ?: scope.async { runDownload(info) }.also {
            downloadJob = it
            downloadBuild = info.buildNumber
        }
        return job.await()
    }

    private suspend fun runDownload(info: UpdateInfo): Boolean {
        val apkFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), APK_NAME)
        withContext(Dispatchers.IO) { if (apkFile.exists()) apkFile.delete() }
        prefs.edit().remove(KEY_DOWNLOADED).apply()

        withContext(Dispatchers.IO) { forgetDownloads() }
        // A unit with the system's downloader switched off, or no storage to
        // write to, refuses here: a failed download, not a crash of the launcher
        // at every start while an update is on offer.
        val downloadManager: DownloadManager
        val downloadId: Long
        try {
            downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(info.apkUrl))
                .setTitle("Dashwheel ${info.versionName}")
                .setDescription(context.getString(R.string.sys_update_downloading))
                .setMimeType("application/vnd.android.package-archive")
                // The progress only: the dashboard itself says when it is ready.
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, APK_NAME)
            downloadId = downloadManager.enqueue(request)
        } catch (e: RuntimeException) {
            Log.w(TAG, "the download could not start", e)
            _status.value = UpdateStatus.Error(R.string.sys_update_download_failed)
            return false
        }
        _status.value = UpdateStatus.Downloading(info, 0)

        var success = false
        try {
            success = withContext(Dispatchers.IO) {
                withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) { awaitDownload(downloadManager, info, downloadId) } ?: false
            }
        } finally {
            if (!success) {
                // Failed, stuck or abandoned: nothing left queued in the system's
                // downloader, and never a percentage left standing still.
                downloadManager.remove(downloadId)
                if (_status.value is UpdateStatus.Downloading) _status.value = UpdateStatus.Error(R.string.sys_update_download_failed)
            }
        }
        if (!success) {
            _status.value = UpdateStatus.Error(R.string.sys_update_download_failed)
            return false
        }
        prefs.edit().putLong(KEY_DOWNLOADED, info.buildNumber).apply()
        _status.value = UpdateStatus.Ready(info, apkFile)
        return true
    }

    /**
     * Every earlier download of an update taken out of the system's downloader,
     * files with them. Each one stayed there with its notification, and the
     * downloader posted them all again at every step of the next download: 83
     * of them on the unit, the notification shade flooded while it ran.
     */
    private fun forgetDownloads() {
        runCatching {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            // A query without a filter lists this app's own downloads only.
            val ids = downloadManager.query(DownloadManager.Query())?.use { cursor ->
                val column = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
                buildList { while (cursor.moveToNext()) add(cursor.getLong(column)) }
            }.orEmpty()
            if (ids.isNotEmpty()) downloadManager.remove(*ids.toLongArray())
        }
    }

    /**
     * Follows the download to its end. A download paused or pending (no
     * network, the server gone quiet) that makes no progress for [STALL_MS]
     * counts as failed rather than showing the same percentage forever.
     */
    private suspend fun awaitDownload(downloadManager: DownloadManager, info: UpdateInfo, id: Long): Boolean {
        var lastBytes = -1L
        var lastProgressAt = SystemClock.elapsedRealtime()
        while (true) {
            val query = DownloadManager.Query().setFilterById(id)
            downloadManager.query(query)?.use { cursor ->
                if (!cursor.moveToFirst()) return false
                when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                    DownloadManager.STATUS_SUCCESSFUL -> return true
                    DownloadManager.STATUS_FAILED -> return false
                    else -> {
                        val soFar =
                            cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        val total =
                            cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        if (total > 0) {
                            _status.value = UpdateStatus.Downloading(info, ((soFar * 100) / total).toInt())
                        }
                        if (soFar != lastBytes) {
                            lastBytes = soFar
                            lastProgressAt = SystemClock.elapsedRealtime()
                        } else if (SystemClock.elapsedRealtime() - lastProgressAt > STALL_MS) {
                            return false
                        }
                    }
                }
            } ?: return false
            delay(400)
        }
    }

    /** False when the system's installer could not be opened on [apkFile]. */
    private fun launchInstaller(apkFile: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }.onFailure { Log.w(TAG, "the installer could not be opened", it) }.isSuccess

    /**
     * "Later": the update on offer stops asking (no dot, no prompt) until a
     * newer build comes along; Settings still lists it. A check's own outcome
     * (up to date, failed) just clears.
     */
    fun dismiss() {
        val info = _status.value.updateInfo
        if (info == null) {
            _status.value = UpdateStatus.Idle
            return
        }
        prefs.edit().putLong(KEY_DISMISSED, info.buildNumber).apply()
        _status.value = UpdateStatus.Dismissed(info)
    }

    /** Offers a dismissed update again (Settings → Advanced). */
    fun offerAgain() {
        val d = _status.value as? UpdateStatus.Dismissed ?: return
        prefs.edit().remove(KEY_DISMISSED).apply()
        _status.value = UpdateStatus.Available(d.info)
    }

    companion object {
        private const val TAG = "UpdateManager"
        private const val PREFS = "updates"
        /** Android's confirmation and the install itself, before an update still not installed is offered again. */
        private const val INSTALL_WAIT_MS = 45_000L
        private const val KEY_DISMISSED = "dismissed_build"
        private const val KEY_DOWNLOADED = "downloaded_build"
        private const val KEY_BETA = "beta_channel"

        /** A build of main ("v1.0.42"), as opposed to a branch's test build ("my-branch-v1.0.42"). */
        internal fun isMainBuildTag(tag: String): Boolean = Regex("v\\d+\\.\\d+\\.\\d+").matches(tag)

        /** Build number from a release tag or name: "v1.0.42" -> 42 (the last number wins). */
        internal fun parseBuildNumber(text: String): Long? =
            Regex("(\\d+)").findAll(text).lastOrNull()?.value?.toLongOrNull()

        private const val APK_NAME = "openauto-dash-update.apk"
        /** The whole download, however slowly it still moves. */
        private const val DOWNLOAD_TIMEOUT_MS = 30 * 60_000L
        /**
         * No byte arrived for this long: paused for good, as far as the driver is
         * concerned. Generous, as car Wi-Fi (a phone hotspot) drops out in tunnels.
         */
        private const val STALL_MS = 5 * 60_000L
        /** The phone companion app's asset in each release (see build.yml). */
        const val COMPANION_APK_NAME = "dashwheel-companion.apk"
        private val ALLOWED_DOWNLOAD_HOSTS = setOf("github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")
    }
}
