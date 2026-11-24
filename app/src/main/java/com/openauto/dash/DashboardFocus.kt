package com.openauto.dash

import android.app.Activity
import android.view.WindowManager
import java.lang.ref.WeakReference

/**
 * Whether the dashboard's window takes the keys. A window that declines them
 * (FLAG_NOT_FOCUSABLE) still gets touches, but a touch no longer moves the
 * keys to it. Two things ask for that, each for its own reason, and the
 * window takes the keys again only once neither does:
 * a Maps window docked over the dashboard ([PipAnchor]), and an app typing
 * inside a tile ([EmbeddedApp]), whose pop-up lists closed the moment the
 * finger landed on the dashboard. Main thread only.
 */
internal object DashboardFocus {

    /** Why the window declines the keys. */
    enum class Reason { DOCKED_WINDOW, TILE_TYPING }

    private var on: WeakReference<Activity>? = null
    private val reasons = HashSet<Reason>()

    /** [reason] starts or stops asking [activity]'s window to decline the keys. True when the window changed. */
    fun decline(activity: Activity, reason: Reason, declined: Boolean): Boolean {
        // A dashboard built anew (the language changed) has a window without the flag.
        if (on?.get() !== activity) {
            reasons.clear()
            on = WeakReference(activity)
        }
        if (!changes(reasons, reason, declined)) return false
        val flag = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (reasons.isEmpty()) activity.window.clearFlags(flag) else activity.window.addFlags(flag)
        return true
    }

    /** Adds or removes [reason]; true when that turns "declined" on or off as a whole. */
    internal fun changes(reasons: MutableSet<Reason>, reason: Reason, declined: Boolean): Boolean {
        val was = reasons.isNotEmpty()
        if (declined) reasons += reason else reasons -= reason
        return was != reasons.isNotEmpty()
    }
}
