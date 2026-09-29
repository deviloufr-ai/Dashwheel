package com.openauto.dash

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/*
 * Small things the QF firmware (ROCO K706) tells every app, read once at the
 * start from its system properties and then followed by broadcast (worked
 * out from the firmware):
 *  - the headlights, for the Auto appearance (lights on: the dark version);
 *  - the phone on the unit's Bluetooth: its name and battery (0..5), for the bar;
 *  - CarPlay / Android Auto (zlink): connected, and on screen or not. While
 *    it's on screen the phone's calls are its business, and Dashwheel's own
 *    alerts keep to the pill.
 * Other units say none of this: everything stays at "nothing to show".
 */
object UnitSignals {
    private const val ACTION_HEADLIGHT = "com.qf.action.headlight_state"
    private const val ACTION_BATTERY = "com.qf.action.bt.battery"
    private const val ACTION_BT_STATE = "com.qf.action.BT_STATE"
    private const val ACTION_ZLINK = "com.zjinnova.zlink"

    /** The phone as the unit's Bluetooth knows it: [battery] 0..5, or null when not reported. */
    data class UnitPhone(val name: String, val battery: Int?)

    private val _headlightsOn = MutableStateFlow(false)
    val headlightsOn: StateFlow<Boolean> = _headlightsOn

    private val _phone = MutableStateFlow<UnitPhone?>(null)
    /** The phone connected to the unit for calls, or null. */
    val phone: StateFlow<UnitPhone?> = _phone

    private val _projection = MutableStateFlow(false)
    /** CarPlay, Android Auto or another phone projection is connected. */
    val projection: StateFlow<Boolean> = _projection

    private val _projectionOnScreen = MutableStateFlow(false)
    /** …and its screen is the one in front. */
    val projectionOnScreen: StateFlow<Boolean> = _projectionOnScreen

    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null

    fun start(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        // Only on the QF firmware: its framework sets this at boot.
        if (systemProperty("sys.qf.is.acc.on").isNullOrBlank()) return
        appContext = app
        _headlightsOn.value = systemProperty("sys.qf.vehicle.headlight_state") == "true"
        _projection.value = systemProperty("sys.qf.carplay.connect") == "true" || systemProperty("sys.qf.bt.aa.connect") == "true"
        refreshPhone(app)
        val filter = IntentFilter().apply {
            addAction(ACTION_HEADLIGHT)
            addAction(ACTION_BATTERY)
            addAction(ACTION_BT_STATE)
            addAction(ACTION_ZLINK)
        }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_HEADLIGHT -> _headlightsOn.value = intent.getBooleanExtra("extra_headlight_state", false)
                ACTION_BATTERY -> _phone.value = _phone.value?.copy(battery = intent.getIntExtra("EXTRA_BATTERY", -1).takeIf { it in 0..5 })
                ACTION_BT_STATE -> {
                    refreshPhone(context)
                    // The name is written a moment after the link comes up.
                    main.postDelayed({ refreshPhone(context) }, 1_500)
                }
                ACTION_ZLINK -> onProjection(intent.getStringExtra("status"))
            }
        }
    }

    /**
     * zlink's own news. Dashwheel sends this action too (the "phone call on"
     * signal, [HeadUnitPhone]): only the connection and screen ones count.
     */
    private fun onProjection(status: String?) {
        when (status) {
            "CONNECTED" -> _projection.value = true
            "DISCONNECT" -> {
                _projection.value = false
                _projectionOnScreen.value = false
            }
            "ENTER", "MAIN_PAGE_SHOW" -> _projectionOnScreen.value = true
            "MAIN_PAGE_HIDDEN" -> _projectionOnScreen.value = false
        }
    }

    /**
     * The car's supply voltage as the unit's own power board measures it (the
     * figure in the firmware's status bar, tenths of a volt), or null off this firmware.
     */
    fun supplyVolts(): Double? =
        systemProperty("sys.qf.update_battery_power")?.toIntOrNull()?.div(10.0)?.takeIf { it in 9.0..16.0 }

    private fun refreshPhone(context: Context) {
        val connected = systemProperty("sys.qf.bt.is.connect") == "true"
        val name = runCatching { Settings.Global.getString(context.contentResolver, "btConnectedName") }.getOrNull()?.trim()
        _phone.value = if (connected && !name.isNullOrEmpty()) {
            UnitPhone(name, systemProperty("sys.qf.bt.battery")?.toIntOrNull()?.takeIf { it in 0..5 })
        } else {
            null
        }
    }

    @SuppressLint("PrivateApi")
    private fun systemProperty(name: String): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as String
    }.getOrNull()
}
