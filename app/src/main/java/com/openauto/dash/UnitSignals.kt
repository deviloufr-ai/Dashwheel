package com.openauto.dash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.openauto.dash.vehicle.K
import com.openauto.dash.vehicle.Source
import com.openauto.dash.vehicle.SourceKind
import com.openauto.dash.vehicle.Vehicle
import com.openauto.unitproto.UnitFamily
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/*
 * Small things the QF firmware (ROCO K706) tells every app, read once at the
 * start from its system properties and then followed by broadcast (worked
 * out from the firmware):
 *  - the headlights, for the Auto appearance (lights on: the dark version);
 *  - the phone on the unit's Bluetooth: its name and battery (0..5), for the bar;
 *  - CarPlay / Android Auto (zlink): connected, and on screen or not. While
 *    it's on screen the phone's calls are its business, and Dashwheel's own
 *    alerts keep to the pill;
 *  - the car's outside temperature sensor, as the CAN box reads it (what the
 *    firmware's own status bar shows).
 * Other units say none of this: everything stays at "nothing to show".
 */
object UnitSignals {
    private const val ACTION_HEADLIGHT = "com.qf.action.headlight_state"
    private const val ACTION_BATTERY = "com.qf.action.bt.battery"
    private const val ACTION_BT_STATE = "com.qf.action.BT_STATE"
    private const val ACTION_ZLINK = "com.zjinnova.zlink"
    /** The CAN app's outside temperature, extra "extra_string" ("12.5℃", "54.5℉", "" when its switch is off); sent on change only. */
    private const val ACTION_OUT_TEMP = "com.qf.vehicle.action.TEMP_STRING"

    private const val PREFS = "unit_signals"
    private const val KEY_OUT_TEMP = "out_temp_c"
    private const val KEY_OUT_TEMP_AT = "out_temp_at"
    /** The firmware only says the temperature again when it changes: a reading this recent is still shown after a restart. */
    private const val OUT_TEMP_KEEP_MS = 2 * 3_600_000L

    private var outTempLogged = false
    private val _outsideTempC = MutableStateFlow<Double?>(null)
    /** The car's outside temperature sensor in °C, or null when the car doesn't send one. */
    val outsideTempC: StateFlow<Double?> = _outsideTempC

    /** The phone as the unit's Bluetooth knows it: [battery] 0..5, or null when not reported. */
    data class UnitPhone(val name: String, val battery: Int?)

    private val _headlightsOn = MutableStateFlow(false)
    val headlightsOn: StateFlow<Boolean> = _headlightsOn

    /** The headlights as the driver taught them ([LearnedSignals]). */
    internal fun headlightsWrite(on: Boolean) {
        _headlightsOn.value = on
    }

    /** The headlights from the MCU's own message ([McuFeed]), the same wire the broadcast tells of. */
    internal fun mcuHeadlights(on: Boolean) {
        _headlightsOn.value = on
    }

    private val _handbrake = MutableStateFlow<Boolean?>(null)
    /** The handbrake wire as the MCU reads it, or null until it says (many installs leave it unwired). */
    val handbrake: StateFlow<Boolean?> = _handbrake

    internal fun mcuHandbrake(on: Boolean) {
        _handbrake.value = on
    }

    private val _volts = MutableStateFlow<Double?>(null)
    /** The supply voltage as the MCU last sent it ([McuFeed]); [supplyVolts] reads the firmware's copy. */
    val volts: StateFlow<Double?> = _volts

    /**
     * The unit on the vehicle bus: the supply voltage its power board measures ([mcuVolts], the MCU's
     * message; else the firmware's copy of the figure, read while somebody wants it). A MCU source: it
     * outranks the OBD adapter's volts whenever it has one.
     */
    private val busPublisher = Vehicle.bus.register(Source("qf.mcu", SourceKind.MCU, "Unit MCU"))

    internal fun mcuVolts(v: Double) {
        if (v in 9.0..16.0) {
            _volts.value = v
            mcuVoltsAt = android.os.SystemClock.elapsedRealtime()
            busPublisher.put(K.BATTERY_VOLTS, v, ttlMs = MCU_VOLTS_KEEP_MS)
        }
    }

    @Volatile private var mcuVoltsAt = Long.MIN_VALUE / 2

    /** The MCU says its volts now and then (on a change, it seems): a figure is believed for this long. */
    private const val MCU_VOLTS_KEEP_MS = 10 * 60_000L
    /** How often the firmware's copy of the supply voltage is read while the key is wanted (and the MCU has not said). */
    private const val PROP_VOLTS_EVERY_MS = 5_000L

    private val _phone = MutableStateFlow<UnitPhone?>(null)
    /** The phone connected to the unit for calls, or null. */
    val phone: StateFlow<UnitPhone?> = _phone

    private val _projection = MutableStateFlow(false)
    /** CarPlay, Android Auto or another phone projection is connected. */
    val projection: StateFlow<Boolean> = _projection

    private val _projectionOnScreen = MutableStateFlow(false)
    /** …and its screen is the one in front. */
    val projectionOnScreen: StateFlow<Boolean> = _projectionOnScreen

    /** The app that shows CarPlay and Android Auto on this firmware. */
    const val ZLINK_PACKAGE = "com.zjinnova.zlink"

    /** No zlink window is on screen any more, whatever zlink last said. */
    fun projectionLeftScreen() {
        _projectionOnScreen.value = false
    }

    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null

    /** Running on the QF firmware (set by [start]): its signals are the ones above. */
    val onQf: Boolean get() = appContext != null

    /** The car's outside temperature from another family's bus ([CarFeed]). */
    internal fun outsideTempWrite(c: Double?) {
        setOutsideTemp(c)
    }

    fun start(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        // Only on the QF firmware (its framework sets the ignition property at boot): these are its broadcasts.
        if (UnitPlatform.adapter.family != UnitFamily.QF || UnitPlatform.adapter.ignition == null) return
        appContext = app
        _headlightsOn.value = systemProperty("sys.qf.vehicle.headlight_state") == "true"
        _projection.value = systemProperty("sys.qf.carplay.connect") == "true" || systemProperty("sys.qf.bt.aa.connect") == "true"
        refreshPhone(app)
        restoreOutsideTemp(app)
        val filter = IntentFilter().apply {
            addAction(ACTION_HEADLIGHT)
            addAction(ACTION_BATTERY)
            addAction(ACTION_BT_STATE)
            addAction(ACTION_ZLINK)
            addAction(ACTION_OUT_TEMP)
        }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        watchPropertyVolts()
    }

    /** While the volts are wanted and the MCU has not sent any, the firmware's copy of them goes on the bus. */
    private fun watchPropertyVolts() {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            Vehicle.bus.wanted.map { K.BATTERY_VOLTS in it }.distinctUntilChanged().collectLatest { wanted ->
                if (!wanted) return@collectLatest
                while (true) {
                    if (android.os.SystemClock.elapsedRealtime() - mcuVoltsAt > MCU_VOLTS_KEEP_MS) {
                        busPublisher.put(K.BATTERY_VOLTS, propertyVolts(), ttlMs = 3 * PROP_VOLTS_EVERY_MS)
                    }
                    delay(PROP_VOLTS_EVERY_MS)
                }
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_HEADLIGHT -> {
                    UnitPlatform.markUsed(Cap.HEADLIGHTS)
                    _headlightsOn.value = intent.getBooleanExtra("extra_headlight_state", false)
                }
                ACTION_BATTERY -> _phone.value = _phone.value?.copy(battery = intent.getIntExtra("EXTRA_BATTERY", -1).takeIf { it in 0..5 })
                ACTION_BT_STATE -> {
                    refreshPhone(context)
                    // The name is written a moment after the link comes up.
                    main.postDelayed({ refreshPhone(context) }, 1_500)
                }
                ACTION_ZLINK -> onProjection(intent.getStringExtra("status"))
                ACTION_OUT_TEMP -> onOutsideTemp(context, intent.getStringExtra("extra_string"))
            }
        }
    }

    private fun onOutsideTemp(context: Context, text: String?) {
        val c = parseOutsideTemp(text)
        if (c != null) UnitPlatform.markUsed(Cap.OUTSIDE_TEMP)
        // In the unit's log: whether this car's CAN box sends its sensor at all (else the bar shows the
        // weather's). The first reading of each start only, and when it goes, as it is sent on change.
        if (!outTempLogged || c == null) {
            outTempLogged = c != null
            DebugLog.note(context, "outside temperature from the car: \"${text.orEmpty()}\"")
        }
        setOutsideTemp(c)
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (c == null) p.remove(KEY_OUT_TEMP).remove(KEY_OUT_TEMP_AT)
        else p.putFloat(KEY_OUT_TEMP, c.toFloat()).putLong(KEY_OUT_TEMP_AT, System.currentTimeMillis())
        p.apply()
    }

    private fun restoreOutsideTemp(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.contains(KEY_OUT_TEMP)) return
        val age = System.currentTimeMillis() - p.getLong(KEY_OUT_TEMP_AT, 0L)
        if (age in 0..OUT_TEMP_KEEP_MS) setOutsideTemp(p.getFloat(KEY_OUT_TEMP, 0f).toDouble())
    }

    private fun setOutsideTemp(c: Double?) {
        _outsideTempC.value = c
        WeatherRepo.carTempChanged()
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
    fun supplyVolts(): Double? = _volts.value ?: propertyVolts()

    private fun propertyVolts(): Double? =
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

    /**
     * °C from the CAN app's temperature text: "12.5℃", "-3℃", "54.5℉" (its
     * units may come translated, "12.5°C"). Null for "" (its switch off) or a
     * value no car sensor gives.
     */
    internal fun parseOutsideTemp(text: String?): Double? {
        val t = text?.trim().orEmpty()
        val n = Regex("-?\\d+(?:[.,]\\d+)?").find(t)?.value?.replace(',', '.')?.toDoubleOrNull() ?: return null
        val c = if ('℉' in t || t.endsWith("F", ignoreCase = true)) (n - 32) / 1.8 else n
        return c.takeIf { it > -40.0 && it < 70.0 }
    }

    private fun systemProperty(name: String): String? = SysProps.get(name)
}
