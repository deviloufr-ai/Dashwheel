package com.openauto.dash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.openauto.dash.vehicle.FUEL_HOLD_MS
import com.openauto.dash.vehicle.K
import com.openauto.dash.vehicle.Key
import com.openauto.dash.vehicle.Source
import com.openauto.dash.vehicle.SourceKind
import com.openauto.dash.vehicle.Vehicle
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
    /** The car's full lock in tenths of a degree, as the car app counts it (5450 on the C4 Picasso). */
    private const val EXTRA_STEERING_RATE = "extra_rate"
    /**
     * The car app's line picture, 0..90 with 45 straight ahead: written at
     * every step of the wheel, its own lines on or off (VehicleSender in the
     * ROM's car app), so it still moves once the driver turned those lines off.
     */
    private const val TRACE_INDEX = "KeyDefaultTraceAngle"
    /** After the broadcast or the log ([steeringWrite]), the coarser line picture keeps out of the way this long. */
    private const val FINE_ANGLE_MS = 3_000L
    /** The ROM's own reverse flag, "true" while reverse is engaged. */
    private const val REVERSE_FLAG = "sys.qf.backcar_state"
    /** While reversing, the flag is read again this often ([watchReverse]). */
    private const val REVERSE_REREAD_MS = 2_000L
    /** How long the car app's steering angle is believed without a new one: it sends only when the line picture changes. */
    private const val STEERING_HOLD_MS = 60 * 60_000L

    /** Body data older than this is stale (the car app sends it every two seconds while it changes). */
    private const val FRESH_MS = 6_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The car app on the vehicle bus: the body's numbers (speed with the driver's correction, revs, fuel, range,
     * mileage) good while the body is fresh ([FRESH_MS]), its states on a change ([putState]), and the climate,
     * the parking sensors, reverse and the steering angle as it says them.
     */
    private val busPublisher = Vehicle.bus.register(Source("qf.car_app", SourceKind.VEHICLE_APP, "QF car app"))
    /** What the body last said for the states it puts on a change only. */
    private val stated = HashMap<Key<*>, Any>()
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

    /** When the broadcast or the log last gave the angle ([SystemClock.elapsedRealtime]). */
    @Volatile private var fineAngleAt = 0L
    /** The car's full lock in degrees: the C4 Picasso's until the car app's broadcast tells this car's. */
    @Volatile private var fullLockDeg = DEFAULT_FULL_LOCK_DEG
    private const val DEFAULT_FULL_LOCK_DEG = 545f
    private const val PREFS = "car_box"
    private const val PREF_FULL_LOCK = "full_lock_deg"

    /**
     * The car app's line picture ([TRACE_INDEX]) as an angle: at the start,
     * when reverse comes on, and each time it changes. It is the only angle
     * left once the car app's own moving lines are off (its broadcast stops
     * with them), and the broadcast only comes when the wheel turns, so a
     * reverse with the wheel still would have none.
     */
    private fun traceSteering(context: Context) {
        if (SystemClock.elapsedRealtime() - fineAngleAt < FINE_ANGLE_MS) return
        val index = runCatching { android.provider.Settings.Global.getInt(context.contentResolver, TRACE_INDEX) }.getOrNull() ?: return
        traceIndexDegrees(index, fullLockDeg)?.let { setSteering(it) }
    }

    /**
     * The angle read from the car app's log ([StockLines]), when its broadcast is off. [asCarApp] false for
     * another bus forwarding its angle here ([CarFeed]): the store follows, the bus hears it from its own source.
     */
    internal fun steeringWrite(degrees: Float, asCarApp: Boolean = true) {
        fineAngleAt = SystemClock.elapsedRealtime()
        if (asCarApp) setSteering(degrees) else _steering.value = degrees
    }

    /** The car app's angle: the old store and the bus. It says it on a change only, and a wheel can stay put through a whole manoeuvre, so it is kept long. */
    private fun setSteering(degrees: Float) {
        _steering.value = degrees
        busPublisher.put(K.STEERING_ANGLE, degrees.toDouble(), ttlMs = STEERING_HOLD_MS)
    }

    private fun onSteeringBroadcast(context: Context, intent: Intent) {
        val rawRate = intent.getFloatExtra(EXTRA_STEERING_RATE, 0f)
        val rate = rawRate / 10f
        if (rate in 90f..900f && rate != fullLockDeg) {
            fullLockDeg = rate
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat(PREF_FULL_LOCK, rate).apply()
        }
        steeringWrite(broadcastDegrees(intent.getIntExtra(EXTRA_STEERING, 0), rawRate, fullLockDeg))
    }

    /** Whether this unit's car app shares the car's data (the QF firmware). */
    val available: Boolean get() = started

    fun start(context: Context) {
        if (started) return
        val app = context.applicationContext
        if (!UnitPlatform.hasPackage(app, RomPopups.VEHICLE_PACKAGE)) return
        started = true
        flagSeenOn = systemProperty(REVERSE_FLAG) == "true"
        setReversing(flagSeenOn)
        watchReverse()
        fullLockDeg = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(PREF_FULL_LOCK, DEFAULT_FULL_LOCK_DEG)
        traceSteering(app)
        // Called on a binder thread: one settings read per step of the wheel.
        runCatching {
            app.contentResolver.registerContentObserver(
                android.provider.Settings.Global.getUriFor(TRACE_INDEX), false,
                object : ContentObserver(null) {
                    override fun onChange(selfChange: Boolean, uri: Uri?) = traceSteering(app)
                }
            )
        }.onFailure { Log.w(TAG, "no steering from the line picture", it) }
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
                        setReversing(false)
                    }
                }
            }
        }
    }

    /** The car app's reverse: the old store and the bus. */
    private fun setReversing(on: Boolean) {
        _reversing.value = on
        busPublisher.put(K.REVERSE, on)
    }

    /**
     * Reverse as another source says it ([LearnedSignals] as the driver taught it, [CarFeed] from the unit's bus),
     * for a car whose box or ROM doesn't: the old store follows, and the bus hears it from that source.
     */
    internal fun reverseWrite(on: Boolean) {
        _reversing.value = on
    }

    /** Puts a state when it changed since the body last said it: the newest change among equals is the one that stands. */
    private fun <T : Any> putState(key: Key<T>, value: T) {
        synchronized(stated) {
            if (stated[key] == value) return
            stated[key] = value
        }
        busPublisher.put(key, value)
    }

    /** What the body says, on the bus (the old store has the whole record). */
    private fun publishBody(b: CarBody, range: Int?) {
        busPublisher.put(K.VEHICLE_SPEED, b.speedKmh?.let(SpeedCorrection::corrected)?.toDouble(), ttlMs = FRESH_MS)
        busPublisher.put(K.RPM, b.rpm?.toDouble(), ttlMs = FRESH_MS)
        // The lights count as on from the side lights up: the unit's own headlight signal is the illumination wire.
        putState(K.HEADLIGHTS, b.highBeam || b.dippedBeam || b.sidelights)
        putState(K.DRIVER_BELT, b.driverBeltUnfastened)
        putState(K.HANDBRAKE, b.handbrake)
        // The car says these on a change, so a parked car's last word stands for hours (a tile shows it until the next).
        busPublisher.put(K.FUEL_LEVEL, fuelPercentToPublish(fuelPercentOf(b), range)?.toDouble(), ttlMs = FUEL_HOLD_MS)
        busPublisher.put(K.RANGE, range?.toDouble(), ttlMs = FUEL_HOLD_MS)
        busPublisher.put(K.ODOMETER, b.odometer?.takeIf { it > 0f }?.toDouble())
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
                ACTION_SHARE -> runCatching { UnitPlatform.markUsed(Cap.VEHICLE_BUS); onShare(intent.getByteArrayExtra(EXTRA_SHARE)) }
                    .onFailure { Log.w(TAG, "unreadable shared data", it) }
                ACTION_RADAR -> runCatching {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<RadarState>(EXTRA_RADAR)?.let { setRadar(it.toRadar()) }
                }.onFailure { Log.w(TAG, "unreadable radar", it) }
                ACTION_REVERSE_ON -> {
                    traceSteering(context)
                    setReversing(true)
                    logSteeringSource(context)
                }
                ACTION_REVERSE_OFF -> setReversing(false)
                ACTION_STEERING -> if (intent.hasExtra(EXTRA_STEERING)) onSteeringBroadcast(context, intent)
            }
        }
    }

    /** For the debug log: where this reverse's steering lines get their angle. */
    private fun logSteeringSource(context: Context) {
        val r = context.contentResolver
        fun global(key: String) = runCatching { android.provider.Settings.Global.getString(r, key) }.getOrNull()
        Log.i(
            TAG,
            "reverse: car app lines ${global("KeyTrackSwitch")}, line picture ${global(TRACE_INDEX)}, " +
                "full lock $fullLockDeg°, last fine angle ${(SystemClock.elapsedRealtime() - fineAngleAt) / 1000} s ago"
        )
    }

    private fun onShare(data: ByteArray?) {
        SignalSources.shared(data)
        when (shareType(data)) {
            SHARE_BODY -> parseCarBody(data!!)?.let {
                val range = it.range?.takeIf { r -> r in 1f..MAX_RANGE_KM }?.roundToInt()
                // McuReader keeps the real fuel and range through the demo by itself.
                McuReader.carBoxWrite(fuelPercentOf(it), range)
                // The demo shows its own body; the car's next message brings the real one back.
                if (DemoMode.isOn) return
                _body.value = it
                bodyAt = SystemClock.elapsedRealtime()
                publishBody(it, range)
            }
            SHARE_AC -> parseClimate(data!!)?.let {
                _climate.value = it
                busPublisher.put(K.CLIMATE, it)
            }
            SHARE_RADAR -> parseRadar(data!!)?.let { setRadar(it) }
            // Sent when a door changes, so the doors are only known from the first change on.
            SHARE_DOORS -> parseDoorBits(data!!)?.let {
                McuReader.carBoxDoors(it)
                busPublisher.put(K.DOORS, McuReader.doorsOf(it))
            }
        }
    }

    private fun setRadar(radar: Radar) {
        _radar.value = radar
        busPublisher.put(K.RADAR, radar)
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

    private fun systemProperty(name: String): String? = SysProps.get(name)
}

/**
 * The car app's steering [angle] as wheel degrees, right positive. It comes in
 * tenths of a degree with the full lock [rate] in the same tenths (5450 on the
 * C4 Picasso); a box that counts in other units still gives its full lock in
 * them, so the angle is then taken as a share of it, on a car that locks at
 * [fullLockDeg].
 */
internal fun broadcastDegrees(angle: Int, rate: Float, fullLockDeg: Float): Float =
    if (rate > 0f && rate / 10f !in 90f..900f) angle / rate * fullLockDeg else angle / 10f

/**
 * The car app's line picture [index] (0..90, 45 straight ahead) as steering
 * wheel degrees, right positive, for a car that locks at [fullLockDeg]; null
 * when it isn't one. The car app picks it as 45 + angle × 45 / full lock.
 */
internal fun traceIndexDegrees(index: Int, fullLockDeg: Float): Float? =
    if (index in 0..90) (index - 45) * fullLockDeg / 45f else null

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
