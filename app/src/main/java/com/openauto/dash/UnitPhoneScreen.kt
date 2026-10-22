package com.openauto.dash

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * The unit's own phone and Bluetooth music app (com.qf.bluetooth, QF firmware)
 * only when it is asked for. The firmware brings it in front by itself: its
 * phone key (the CAN box's Voice and Phone buttons both come out as key 301)
 * starts it from inside the app, before any other app sees the key, so nothing
 * turns that off. So as one of its screens comes up ([onWindow]) it is sent
 * back with Back from the accessibility service, unless it was asked for
 * ([asked]): a call is on, Dashwheel opened it ([opened], or a tap on the
 * dashboard just before), a wheel or unit key Dashwheel did nothing with was
 * just pressed (the firmware's own behaviour is the driver's wish then), or
 * the driver moved to it from inside the app.
 */
internal object UnitPhoneScreen {
    private const val TAG = "UnitPhoneScreen"
    private const val PHONE_PACKAGE = HeadUnitPhone.BT_PACKAGE
    /** Dialing asked by another app ("call this place"): always the driver's wish. */
    private const val DIAL_ACTIVITY = "com.qf.bluetooth.receiver.AndroidCallActivity"
    /** The screen has resumed by then, and a call that brought it is known. */
    private const val BACK_AFTER_MS = 400L
    /** A tap or a launch this recent opened it. */
    private const val ASKED_MS = 3_000L
    /** One Back per screen: a second one would reach Dashwheel itself. */
    private const val BACK_ONCE_MS = 1_000L

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var openedAt = Long.MIN_VALUE / 2
    /** It came up asked for and no other app's screen came since: moving between its screens is the driver's doing. */
    private var inUse = false
    private var lastBackAt = Long.MIN_VALUE / 2

    /** Dashwheel itself is opening [packageName]. */
    fun opened(packageName: String) {
        if (packageName == PHONE_PACKAGE) openedAt = SystemClock.uptimeMillis()
    }

    /** A window of [packageName], class [className], just came to the front (accessibility events, main thread). */
    fun onWindow(packageName: CharSequence?, className: CharSequence?) {
        val activity = className?.toString()?.endsWith("Activity") == true
        if (packageName?.toString() != PHONE_PACKAGE) {
            if (activity) inUse = false
            return
        }
        if (!activity || className.toString() == DIAL_ACTIVITY) return
        if (inUse || asked()) {
            inUse = true
            return
        }
        main.postDelayed(::sendBack, BACK_AFTER_MS)
    }

    private fun asked(): Boolean =
        PhoneCallOverlay.call.value != null ||
            SystemClock.uptimeMillis() - openedAt < ASKED_MS ||
            System.currentTimeMillis() - PipAnchor.lastTouchAt < ASKED_MS ||
            SteeringWheelStore.keyLeftToFirmware()

    /** Back to the app, when it is still the one in front and still unasked. */
    private fun sendBack() {
        val now = SystemClock.uptimeMillis()
        if (now - lastBackAt < BACK_ONCE_MS) return
        if (asked() || SplitAccessibilityService.frontPackage() != PHONE_PACKAGE) return
        if (!SplitAccessibilityService.sendBack()) return
        lastBackAt = now
        Log.i(TAG, "the unit's phone app came up unasked: sent back")
    }
}
