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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/*
 * The car's data as the QF firmware's car app (com.qf.vehicle) shares it with
 * launchers, straight from the CAN box: the body and trip computer, the
 * climate control, the doors and the parking sensors (decoded in
 * CarBoxData.kt), plus the reverse gear. The car app sends them to every
 * app once Dashwheel is listed as a launcher that wants them ("<package>KeyShare…" = 1 in the
 * global settings, see [RomPopups.writeGlobals]); each is sent when it
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
    /** The car app's steering angle for its guide lines, sent only while they're on (Global KeyTrackSwitch). */
    private const val ACTION_STEERING = "com.qf.vehicle.action.swa"
    /** Steering wheel tenths of a degree, right positive; a new one each time its line picture changes (about 12°). */
    private const val EXTRA_STEERING = "extra_angle"
    /** The ROM's own reverse flag, "true" while reverse is engaged. */
    private const val REVERSE_FLAG = "sys.qf.backcar_state"
    /** While reversing, the flag is read again this often ([watchReverse]). */
    private const val REVERSE_REREAD_MS = 2_000L

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

    private val _steering = MutableStateFlow<Float?>(null)
    /** The steering wheel's angle in degrees, right positive, once the car has sent it ([ReverseView]). */
    val steering: StateFlow<Float?> = _steering

    /**
     * The car app's last line picture (0..90, 45 straight ahead, global
     * KeyDefaultTraceAngle) as an angle: its broadcast only comes when the
     * wheel turns, so a reverse with the wheel still would have none.
     */
    private fun lastSteering(context: Context) {
        if (_steering.value != null) return
        val index = runCatching { android.provider.Settings.Global.getInt(context.contentResolver, "KeyDefaultTraceAngle") }.getOrNull() ?: return
        if (index !in 0..90) return
        _steering.value = (index - 45) * FULL_LOCK_DEG / 45f
    }

    /** The car app's full lock (its extra_rate, 5450 tenths of a degree on the C4 Picasso). */
    private const val FULL_LOCK_DEG = 545f

    /** The angle read from the car app's log ([StockLines]), when its broadcast is off. */
    internal fun steeringWrite(degrees: Float) {
        _steering.value = degrees
    }

    /** Whether this unit's car app shares the car's data (the QF firmware). */
    val available: Boolean get() = started

    fun start(context: Context) {
        if (started) return
        val app = context.applicationContext
        if (!isPackageInstalled(app, RomPopups.VEHICLE_PACKAGE)) return
        started = true
        flagSeenOn = systemProperty(REVERSE_FLAG) == "true"
        _reversing.value = flagSeenOn
        watchReverse()
        lastSteering(app)
        val filter = IntentFilter().apply {
            addAction(ACTION_SHARE)
            addAction(ACTION_RADAR)
            addAction(ACTION_REVERSE_ON)
            addAction(ACTION_REVERSE_OFF)
            addAction(ACTION_STEERING)
        }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        register(app)
    }

    /**
     * Lists Dashwheel with the car app for its data. At start, and again when
     * an alert built on it is turned on: the settings permission may have
     * been granted since.
     */
    fun register(context: Context) {
        if (!started) return
        val app = context.applicationContext
        val me = app.packageName
        scope.launch {
            RomPopups.writeGlobals(
                app,
                mapOf(
                    "${me}KeyShareCarbodyState" to 1, "${me}KeyShareAcState" to 1,
                    "${me}KeyShareRadarState" to 1, "${me}KeyShareDoorWindow" to 1
                )
            )
        }
    }

    /** This unit's ROM does set its reverse flag: it was read on at least once. */
    @Volatile private var flagSeenOn = false

    /**
     * Reverse ends on one broadcast only: missed, the voice would stay held,
     * the alerts away and the reverse view up. So while reversing the ROM's
     * flag is read again every few seconds, and ends it ([ReverseReads]).
     */
    private fun watchReverse() {
        scope.launch {
            _reversing.collectLatest { on ->
                var reads = ReverseReads(seenOn = flagSeenOn)
                while (on) {
                    delay(REVERSE_REREAD_MS)
                    reads = reads.next(systemProperty(REVERSE_FLAG) == "true")
                    flagSeenOn = reads.seenOn
                    if (reads.over) {
                        Log.w(TAG, "reverse ended without its broadcast")
                        _reversing.value = false
                    }
                }
            }
        }
    }

    /** Reverse as the driver taught it ([LearnedSignals]), for a car whose box or ROM doesn't say. */
    internal fun reverseWrite(on: Boolean) {
        _reversing.value = on
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
                ACTION_REVERSE_ON -> {
                    lastSteering(context)
                    _reversing.value = true
                }
                ACTION_REVERSE_OFF -> _reversing.value = false
                ACTION_STEERING -> if (intent.hasExtra(EXTRA_STEERING)) _steering.value = intent.getIntExtra(EXTRA_STEERING, 0) / 10f
            }
        }
    }

    private fun onShare(data: ByteArray?) {
        SignalSources.shared(data)
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
            // Sent when a door changes, so the doors are only known from the first change on.
            SHARE_DOORS -> parseDoorBits(data!!)?.let { McuReader.carBoxDoors(it) }
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
        // Front ones too: a car with sensors at both ends sees them both.
        front = listOf(0, 0, 7, 4, 0, 0),
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

/**
 * What the ROM's reverse flag said each time it was read again during a
 * reverse. Reverse is [over] once the flag, seen on before, has read off
 * three times in a row: a unit that never sets it ends nothing, and neither
 * do the moments Dashwheel clears it itself to take the camera ([ReverseCamera]).
 */
internal data class ReverseReads(val seenOn: Boolean = false, val offInARow: Int = 0) {
    fun next(on: Boolean) = ReverseReads(seenOn || on, if (on) 0 else offInARow + 1)

    val over: Boolean get() = seenOn && offInARow >= 3
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
