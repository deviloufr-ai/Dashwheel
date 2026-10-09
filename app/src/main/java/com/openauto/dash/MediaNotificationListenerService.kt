package com.openauto.dash

import android.content.Context
import android.util.Log
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * The component the user grants Notification access to. That grant lets
 * [CarMediaController] read active media sessions from other apps via
 * [android.media.session.MediaSessionManager.getActiveSessions], and lets this
 * service read the turn-by-turn notification Google Maps / Waze post while
 * navigating, which feeds [NavDirections] for the dashboard's Directions tile.
 */
class MediaNotificationListenerService : NotificationListenerService() {

    // Text built here follows the language picked in the launcher.
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguage.wrap(base))
    }

    override fun onListenerConnected() {
        // One IPC for both: a navigation already in progress, and the notifications already up.
        val active = runCatching { activeNotifications }
            .onFailure { Log.w("NavDirections", "notifications up at connection unreadable", it) }
            .getOrNull() ?: return
        active.filter { it.packageName in NavDirections.PACKAGES }.forEach { NavDirections.onPosted(this, it) }
        active.sortedBy { it.postTime }.forEach { NotificationFeed.onPosted(this, it) }
    }

    override fun onListenerDisconnected() {
        NavDirections.clear()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName in NavDirections.PACKAGES) NavDirections.onPosted(this, sbn)
        NotificationFeed.onPosted(this, sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName in NavDirections.PACKAGES) NavDirections.onRemoved(sbn)
        NotificationFeed.onRemoved(sbn)
    }
}
