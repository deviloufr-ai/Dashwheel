package com.openauto.dash.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps [LinkServer] listening while the phone is in a pocket: a foreground
 * service with a quiet, permanent notification saying whether the car is
 * connected. Runs only while sharing is on and at least one car is paired.
 * Also follows the phone calls ([PhoneCalls]), and the favourites, call log
 * and calendar ([PhoneLists]), and shares the GPS with a linked car
 * ([PhoneGpsShare]), for as long as it runs.
 */
class LinkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    /** The notification is up: the service may stay. */
    private var foreground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // Started again by the system after it was killed for memory, the app in the
        // background and not yet let off battery saving: Android refuses the
        // notification, and that used to end the whole app. The next open, or the
        // car's Bluetooth, starts the sharing again.
        try {
            startForeground(NOTIFICATION_ID, notification(LinkServer.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: RuntimeException) {
            Log.w("LinkService", "could not start in the foreground", e)
            stopSelf()
            return
        }
        foreground = true
        LinkServer.start(this)
        PhoneCalls.start(this)
        PhoneLists.start(this)
        PhoneMedia.start(this)
        PhoneBatteryWatch.start(this)
        PhoneGpsShare.start(this)
        // The app is seldom opened once set up: sharing starting is the other time to look for a new build.
        CompanionUpdate.check(this)
        scope.launch {
            LinkServer.state.collect { state ->
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(state))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foreground || !shouldRun(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        LinkServer.start(this)
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        PhoneCalls.stop(this)
        PhoneLists.stop(this)
        PhoneMedia.stop()
        PhoneBatteryWatch.stop()
        PhoneGpsShare.stop()
        LinkServer.stop()
        super.onDestroy()
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL, getString(R.string.service_channel), NotificationManager.IMPORTANCE_MIN).apply {
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(state: LinkState): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = when (state) {
            is LinkState.Connected -> getString(R.string.status_connected, state.unitName)
            LinkState.Unavailable -> getString(R.string.status_unavailable)
            else -> getString(R.string.status_waiting)
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val CHANNEL = "link"
        private const val NOTIFICATION_ID = 1

        /** Sharing on, a car paired, and, with "Start in the car", the car's Bluetooth connected. */
        fun shouldRun(context: Context): Boolean =
            PairedUnits.isEnabled(context) && PairedUnits.load(context).isNotEmpty() && CarBluetooth.allowsSharing(context)

        /** Starts or stops the service to match [shouldRun]. */
        fun sync(context: Context) {
            val intent = Intent(context, LinkService::class.java)
            if (shouldRun(context)) {
                runCatching { ContextCompat.startForegroundService(context, intent) }
            } else {
                context.stopService(intent)
            }
        }
    }
}
