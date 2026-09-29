package com.openauto.dash.companion

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.openauto.dash.link.CarNotice
import com.openauto.dash.link.CarNotices
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray

/**
 * What the car wants the driver to know once out of it ([CarNotice]):
 * servicing, fault codes, a weak battery, the particulate filter. The latest
 * list from the head unit is kept for the screen, and each notice is posted
 * as a phone notification once, the first time it arrives.
 */
object CarNewsStore {
    private const val PREFS = "car_news"
    private const val CHANNEL = "car_news"
    private const val MAX_SEEN = 200

    private val _notices = MutableStateFlow<List<CarNotice>>(emptyList())
    val notices: StateFlow<List<CarNotice>> = _notices
    private var loaded = false
    private var seen: List<String> = emptyList()

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        val p = prefs(context)
        seen = p.getString("seen", null)?.let { raw -> JSONArray(raw).let { a -> (0 until a.length()).map { a.getString(it) } } }.orEmpty()
        _notices.value = p.getString("notices", null)?.let { raw ->
            runCatching { com.openauto.dash.link.LinkCodec.decode(raw.toByteArray()) as? CarNotices }.getOrNull()?.notices
        }.orEmpty()
        loaded = true
    }

    /** The head unit's current list: kept, and what's new is notified. */
    @Synchronized
    fun update(context: Context, message: CarNotices) {
        load(context)
        _notices.value = message.notices
        val fresh = message.notices.filter { it.id !in seen }
        fresh.forEach { post(context, it) }
        seen = (seen + fresh.map { it.id }).takeLast(MAX_SEEN)
        prefs(context).edit()
            .putString("seen", JSONArray(seen).toString())
            .putString("notices", String(com.openauto.dash.link.LinkCodec.encode(message)))
            .apply()
    }

    private fun post(context: Context, notice: CarNotice) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33
        ) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.news_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, 1, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle(notice.title)
            .setContentText(notice.text)
            .setStyle(Notification.BigTextStyle().bigText(notice.text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .build()
        manager.notify(notice.id.hashCode(), n)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
