package com.openauto.dash

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.qf.vehicle.entity.RadarState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/*
 * The car's data as the QF firmware's car app (com.qf.vehicle) shares it with
 * launchers, straight from the CAN box: the body and trip computer, the
 * climate control and the parking sensors (decoded in CarBoxData.kt), plus
 * the reverse gear. The car app sends them to every app once Dashwheel is
 * listed as a launcher that wants them ("<package>KeyShare…" = 1 in the
 * global settings, written through the root shell); each is sent when it
 * changes, the body at most every two seconds.
 */
object CarBox {
    private const val TAG = "CarBox"
    private const val ACTION_SHARE = "com.qf.vehicle.action.DATA_SHARE"
    private const val EXTRA_SHARE = "extra_DATA_SHARE"
    private const val ACTION_RADAR = "com.qf.vehicle.action.RADAR"
    private const val EXTRA_RADAR = "extra_radar"
    private const val ACTION_REVERSE_ON = "com.qf.action.BACKCAR_START"
    private const val ACTION_REVERSE_OFF = "com.qf.action.BACKCAR_STOP"

    /** Body data older than this is stale (the car app sends it every two seconds while it changes). */
    private const val FRESH_MS = 6_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    private val _body = MutableStateFlow<CarBody?>(null)
    val body: StateFlow<CarBody?> = _body
    /** When [body] was heard ([SystemClock.elapsedRealtime]). */
    internal var bodyAt = 0L
        private set

    private val _climate = MutableStateFlow<Climate?>(null)
    /** The climate control; a new value each time it changes. */
    val climate: StateFlow<Climate?> = _climate

    private val _radar = MutableStateFlow<Radar?>(null)
    val radar: StateFlow<Radar?> = _radar

    private val _reversing = MutableStateFlow(false)
    /** Reverse gear engaged: the ROM's reversing camera is on screen. */
    val reversing: StateFlow<Boolean> = _reversing

    /** Whether this unit's car app shares the car's data (the QF firmware). */
    val available: Boolean get() = started

    fun start(context: Context) {
        if (started) return
        val app = context.applicationContext
        if (!isPackageInstalled(app, RomPopups.VEHICLE_PACKAGE)) return
        started = true
        _reversing.value = systemProperty("sys.qf.backcar_state") == "true"
        val filter = IntentFilter().apply {
            addAction(ACTION_SHARE)
            addAction(ACTION_RADAR)
            addAction(ACTION_REVERSE_ON)
            addAction(ACTION_REVERSE_OFF)
        }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        val me = app.packageName
        scope.launch {
            RomPopups.writeGlobals(
                app,
                mapOf("${me}KeyShareCarbodyState" to 1, "${me}KeyShareAcState" to 1, "${me}KeyShareRadarState" to 1)
            )
        }
    }

    /** The body data while it's current, else null. */
    fun freshBody(): CarBody? = _body.value?.takeIf { SystemClock.elapsedRealtime() - bodyAt <= FRESH_MS }

    /** [DemoMode]'s body, heard [at]; and the real one put back when it ends. */
    internal fun demoWrite(body: CarBody?, at: Long = SystemClock.elapsedRealtime()) {
        _body.value = body
        bodyAt = at
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_SHARE -> runCatching { onShare(intent.getByteArrayExtra(EXTRA_SHARE)) }
                    .onFailure { Log.w(TAG, "unreadable shared data", it) }
                ACTION_RADAR -> runCatching {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<RadarState>(EXTRA_RADAR)?.let { _radar.value = it.toRadar() }
                }.onFailure { Log.w(TAG, "unreadable radar", it) }
                ACTION_REVERSE_ON -> _reversing.value = true
                ACTION_REVERSE_OFF -> _reversing.value = false
            }
        }
    }

    private fun onShare(data: ByteArray?) {
        when (shareType(data)) {
            SHARE_BODY -> parseCarBody(data!!)?.let {
                // McuReader keeps the real fuel and range through the demo by itself.
                McuReader.carBoxWrite(fuelPercentOf(it), it.range?.takeIf { r -> r in 1f..MAX_RANGE_KM }?.roundToInt())
                // The demo shows its own body; the car's next message brings the real one back.
                if (DemoMode.isOn) return
                _body.value = it
                bodyAt = SystemClock.elapsedRealtime()
                it.odometer?.let { km -> Maintenance.carOdometer(km.toInt()) }
            }
            SHARE_AC -> parseClimate(data!!)?.let { _climate.value = it }
            SHARE_RADAR -> parseRadar(data!!)?.let { _radar.value = it }
        }
    }

    private const val MAX_RANGE_KM = 2_000f

    /**
     * The fuel left as a share of the tank in the car profile, taking it for
     * litres (what the car app's trip computer counts on most cars); null
     * when it can't be litres of that tank.
     */
    private fun fuelPercentOf(body: CarBody): Int? {
        val litres = body.fuelLeft ?: return null
        val tank = CarProfileStore.current.tank
        if (tank <= 0 || litres < 0 || litres > tank * 1.1) return null
        return (litres / tank * 100).roundToInt().coerceIn(0, 100)
    }

    /** For [AlertPreview]: made-up sensors and climate, shown the way the car's would be. */
    internal fun sampleRadar() = Radar(
        front = List(6) { null },
        rear = listOf(0, 5, 2, 6, 0, 0),
        left = List(4) { null },
        right = List(4) { null }
    )

    internal fun sampleClimate() = Climate(
        power = true, ac = true, auto = true, recirculation = false, dual = true,
        frontDefrost = false, rearDefrost = false, acMax = false, fan = 3,
        airUp = false, airFace = true, airDown = true,
        left = ClimateTemp.Degrees(21f), right = ClimateTemp.Degrees(21.5f),
        seatHeatLeft = 0, seatHeatRight = 0
    )

    @SuppressLint("PrivateApi")
    private fun systemProperty(name: String): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as String
    }.getOrNull()
}

/** The car app's radar object, in [Radar]'s terms: -1 is a sensor the car doesn't have. */
private fun RadarState.toRadar(): Radar {
    fun lv(b: Byte): Int? = b.toInt().takeIf { it >= 0 }
    return Radar(
        front = frontLevels.map(::lv),
        rear = rearLevels.map(::lv),
        left = sideLevels.take(4).map(::lv),
        right = sideLevels.drop(4).map(::lv)
    )
}
