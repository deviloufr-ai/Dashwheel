package com.openauto.dash.companion

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.openauto.dash.link.CarLog
import java.io.File

/**
 * The car's log, sent from its Settings for a bug report: saved as a file (also
 * copied to Download/Dashwheel), and
 * announced by a notification that opens the phone's share sheet on it (a mail,
 * a chat, a GitHub issue). Its second button opens the project's new-issue page
 * to attach the file to.
 */
object CarLogStore {
    private const val CHANNEL = "car_log"
    private const val DIR = "car_logs"
    private const val KEEP = 3
    private const val NOTIFICATION_ID = 0x10C
    private const val NEW_ISSUE = "https://github.com/deviloufr-ai/Dashwheel/issues/new"

    /** Saves [log] and posts its notification; false when it can't be written. */
    fun receive(context: Context, log: CarLog): Boolean {
        val file = runCatching {
            val dir = File(context.cacheDir, DIR).apply { mkdirs() }
            dir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(KEEP - 1)?.forEach { it.delete() }
            File(dir, log.title.replace(Regex("[^A-Za-z0-9._-]+"), "-") + ".txt").apply { writeText(log.text) }
        }.getOrNull() ?: return false
        saveToDownloads(context, file)
        post(context, log, file)
        return true
    }

    /** A copy in Download/Dashwheel, where a file manager or a PC over USB finds it. */
    private fun saveToDownloads(context: Context, file: File) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Dashwheel")
        }
        runCatching {
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        }
    }

    /** The longest body a new-issue address carries; GitHub refuses addresses past about 8 000 characters. */
    private const val MAX_BODY = 6_000

    /**
     * The new-issue page for [log]. A report the unit wrote for an issue (a crash, a
     * compatibility report) fits in the address and opens filled in, labelled by its title;
     * a whole unit log doesn't, and opens with its title only, for the file to be attached.
     */
    private fun issueUrl(log: CarLog): String {
        val label = when {
            log.title.startsWith("Crash:") -> "crash"
            log.title.startsWith("Compatibility:") -> "compatibility"
            else -> null
        }
        // A long trace is cut at its end, where the frames matter least; the whole of it is the shared file.
        var text = log.text
        while (label != null && text.isNotEmpty() && Uri.encode(text).length > MAX_BODY) text = text.dropLast(text.length / 10 + 20)
        val body = Uri.encode(text).takeIf { label != null && text.isNotEmpty() }
        return NEW_ISSUE + "?title=" + Uri.encode(log.title) +
            (label?.let { "&labels=$it" } ?: "") + (body?.let { "&body=$it" } ?: "")
    }

    private fun post(context: Context, log: CarLog, file: File) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.log_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val uri = FileProvider.getUriForFile(context, context.packageName + ".logs", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, log.title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val share = PendingIntent.getActivity(
            context, 2, Intent.createChooser(send, log.title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val issue = PendingIntent.getActivity(
            context, 3,
            Intent(Intent.ACTION_VIEW, Uri.parse(issueUrl(log))),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle(context.getString(R.string.log_received))
            .setContentText(context.getString(R.string.log_received_detail))
            .setContentIntent(share)
            .addAction(Notification.Action.Builder(null, context.getString(R.string.log_github), issue).build())
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, n)
    }
}
