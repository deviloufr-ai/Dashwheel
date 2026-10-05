package com.openauto.dash.companion

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.openauto.dash.link.ParkingTimer
import java.text.DateFormat
import java.util.Date

/**
 * The parking the driver paid on the car's screen (its Parking timer tile):
 * the phone reminds them a little before it runs out, and when it does,
 * wherever they are by then. Stopped on the car: the reminders are taken back.
 */
object ParkingReminder {
    private const val CHANNEL = "parking"
    private const val EXTRA_ENDS = "ends"
    private const val EXTRA_LAST = "last"
    private const val NOTE_ID = 4711

    fun update(context: Context, message: ParkingTimer) {
        val app = context.applicationContext
        cancel(app)
        if (message.endsAt <= System.currentTimeMillis()) return
        schedule(app, message.endsAt - message.warnMs, message.endsAt, last = false, code = 1)
        schedule(app, message.endsAt, message.endsAt, last = true, code = 2)
    }

    private fun schedule(context: Context, at: Long, endsAt: Long, last: Boolean, code: Int) {
        if (at <= System.currentTimeMillis()) return
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        // Within a minute or two is plenty for a parking meter; exact alarms need a permission of their own.
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(context, endsAt, last, code))
    }

    private fun pending(context: Context, endsAt: Long, last: Boolean, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, code,
            Intent(context, ParkingReminderReceiver::class.java).putExtra(EXTRA_ENDS, endsAt).putExtra(EXTRA_LAST, last),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun cancel(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        for (code in 1..2) alarms.cancel(pending(context, 0, false, code))
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTE_ID)
    }

    internal fun post(context: Context, intent: Intent) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val endsAt = intent.getLongExtra(EXTRA_ENDS, 0L)
        val last = intent.getBooleanExtra(EXTRA_LAST, false)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.parking_channel), NotificationManager.IMPORTANCE_HIGH))
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(endsAt))
        val open = PendingIntent.getActivity(context, 3, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle(context.getString(if (last) R.string.parking_over_title else R.string.parking_soon_title))
            .setContentText(context.getString(if (last) R.string.parking_over_text else R.string.parking_soon_text, time))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .build()
        manager.notify(NOTE_ID, n)
    }
}

class ParkingReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = ParkingReminder.post(context, intent)
}
