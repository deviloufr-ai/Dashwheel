package com.openauto.dash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import com.openauto.dash.vehicle.K
import com.openauto.dash.vehicle.Source
import com.openauto.dash.vehicle.SourceKind
import com.openauto.dash.vehicle.SourceLiveness
import com.openauto.dash.vehicle.Vehicle
import com.openauto.dash.vehicle.tyresFlow
import java.util.Locale
import kotlin.math.roundToInt

/*
 * The tyres, from the head unit's TPMS app (com.syt.tmps on the QF firmware)
 * and its tyre sensors (worked out from the firmware): it tells every app
 * each tyre's pressure (always kPa) and temperature (°C) about every four
 * seconds, whatever its own screen shows. Its alarm thresholds stay private,
 * so the same defaults are applied here ([tyreProblem]). Or from a USB TPMS
 * receiver Dashwheel reads itself ([UsbTpms]), on any unit.
 */

enum class TyrePos { FRONT_LEFT, FRONT_RIGHT, REAR_LEFT, REAR_RIGHT, SPARE }

data class Tyre(
    val kPa: Int,
    val celsius: Int,
    val sensorBatteryLow: Boolean,
    val leak: Boolean,
    val noSignal: Boolean,
    /** When it was heard ([SystemClock.elapsedRealtime]). */
    val at: Long,
    /** The USB receiver's status byte as sent, for the sensors sheet; 0 from the TPMS app. */
    val status: Int = 0
)

enum class TyreProblem { LEAK, LOW, HIGH, HOT }

/** The unit the driver chose in the TPMS app, to show pressures the same way. */
enum class PressureUnit { BAR, KPA, PSI }

/** The alarm limits: the TPMS app's own by default, the driver's from Settings ([Tyres.limits]). */
data class TyreLimits(val lowKpa: Int = TYRE_LOW_KPA, val highKpa: Int = TYRE_HIGH_KPA, val hotC: Int = TYRE_HOT_C)

object Tyres {
    const val TPMS_PACKAGE = "com.syt.tmps"
    private const val ACTION = "TPMS_APP_STATE_SEND"

    /** The TPMS app on the vehicle bus: every tyre it has heard, as one state. */
    private val busPublisher = Vehicle.bus.register(Source("tpms", SourceKind.VEHICLE_APP, "TPMS app"))
    // A USB receiver of the driver's own is the source "tpms.usb" ([UsbTpms]); see there for how it ranks.

    /** What the TPMS app has told, tyre by tyre, as it comes: the receiver's own memory, put on the bus whole. */
    private val heard = HashMap<TyrePos, Tyre>()

    private val _unit = MutableStateFlow(PressureUnit.BAR)
    val unit: StateFlow<PressureUnit> = _unit

    private val _problems = MutableStateFlow<Map<TyrePos, TyreProblem>>(emptyMap())
    /**
     * What's wrong, tyre by tyre ([tyreProblems]): the one judgement the
     * alert, its voice and every tile share, so none says "fine" while
     * another says "low". It is made from [K.TYRES] on the vehicle bus (so
     * from whichever source is resolved: the TPMS app, or the demo), again
     * at each change of the tyres or the car profile, and now and then
     * while there are tyres, so a sensor that went quiet stops holding a
     * warning up.
     */
    val problems: StateFlow<Map<TyrePos, TyreProblem>> = _problems

    private val _limits = MutableStateFlow(TyreLimits())
    /** The alarm limits the warnings go by, whatever reports the tyres (Settings, Car, USB tyre sensors). */
    val limits: StateFlow<TyreLimits> = _limits
    private var limitsLoaded = false

    private var started = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Whether this unit has the TPMS app that reports the tyres. */
    val fromApp: Boolean get() = started

    /**
     * Whether the tyres on the bus right now come from the unit's TPMS app (source "tpms") and not a USB receiver
     * ("tpms.usb"): only the app's sensors report a battery, so only then is a battery mark shown.
     */
    fun sampleFromApp(): Boolean = Vehicle.bus.sample(K.TYRES).value?.source?.id == "tpms"

    /** Whether something reports the tyres: the unit's TPMS app, or a USB receiver the driver chose ([UsbTpms]). */
    val available: Boolean get() = started || UsbTpms.on

    /**
     * The TPMS app's liveness: it tells every tyre about every four seconds, so a minute of nothing from all of them
     * ([TYRE_STALE_MS], the time after which a sensor says nothing about its tyre any more) is an app that stopped or
     * sensors all gone. Then the tyres are withdrawn and the source is down, whatever the old pressures were.
     */
    private val live = SourceLiveness.of(busPublisher, TYRE_STALE_MS, scope)

    init {
        scope.launch {
            combine(tyresFlow(), CarProfileStore.profile, _limits) { tyres, _, _ -> tyres }.collectLatest { tyres ->
                judge(tyres)
                while (tyres.isNotEmpty()) {
                    delay(STALE_CHECK_MS)
                    judge(tyres)
                }
            }
        }
    }

    fun start(context: Context) {
        val app = context.applicationContext
        if (!limitsLoaded) {
            limitsLoaded = true
            val p = prefs(app)
            _limits.value = TyreLimits(p.getInt(KEY_LOW, TYRE_LOW_KPA), p.getInt(KEY_HIGH, TYRE_HIGH_KPA), p.getInt(KEY_HOT, TYRE_HOT_C))
        }
        // A USB receiver of the driver's own, with or without the TPMS app.
        UsbTpms.start(app)
        if (started) return
        if (!UnitPlatform.hasPackage(app, TPMS_PACKAGE)) return
        started = true
        ContextCompat.registerReceiver(app, receiver, IntentFilter(ACTION), ContextCompat.RECEIVER_EXPORTED)
        // The TPMS box gone quiet while driving: plugged in again by itself ([TpmsReset]).
        TpmsReset.start(app)
    }

    private const val STALE_CHECK_MS = 15_000L

    @Synchronized
    private fun judge(tyres: Map<TyrePos, Tyre>) {
        _problems.value = tyreProblems(tyres, _problems.value, SystemClock.elapsedRealtime(), CarProfileStore.current, _limits.value)
    }

    /** The driver's alarm limits, kept and judged by at once. */
    fun setLimits(context: Context, limits: TyreLimits) {
        prefs(context).edit().putInt(KEY_LOW, limits.lowKpa).putInt(KEY_HIGH, limits.highKpa).putInt(KEY_HOT, limits.hotC).apply()
        _limits.value = limits
    }

    private const val PREFS = "tyres"
    private const val KEY_LOW = "low_kpa"
    private const val KEY_HIGH = "high_kpa"
    private const val KEY_HOT = "hot_c"
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pos = tyrePosition(intent.getIntExtra("tires", -1)) ?: return
            val tyre = Tyre(
                kPa = intent.getIntExtra("airpressure", 0),
                celsius = intent.getIntExtra("temperature", 0),
                sensorBatteryLow = intent.getBooleanExtra("lowpower", false),
                leak = intent.getBooleanExtra("leakage", false),
                noSignal = intent.getBooleanExtra("nosignal", false),
                at = SystemClock.elapsedRealtime()
            )
            pressureUnit(intent.getStringExtra("presunit"))?.let { _unit.value = it }
            live.heard()
            val all = synchronized(heard) { heard[pos] = tyre; heard.toMap() }
            busPublisher.put(K.TYRES, all)
        }
    }

    /** The unit the driver chose for the USB receiver's pressures; the TPMS app, where there is one, sets its own. */
    internal fun setUnit(unit: PressureUnit) {
        _unit.value = unit
    }

    /** For [AlertPreview]: made-up tyres, one of them low. */
    internal fun sample(): Map<TyrePos, Tyre> {
        val now = SystemClock.elapsedRealtime()
        return mapOf(
            TyrePos.FRONT_LEFT to Tyre(165, 24, false, false, false, now),
            TyrePos.FRONT_RIGHT to Tyre(235, 25, false, false, false, now),
            TyrePos.REAR_LEFT to Tyre(230, 23, false, false, false, now),
            TyrePos.REAR_RIGHT to Tyre(232, 23, false, false, false, now)
        )
    }
}

/** The TPMS app's tyre numbers: 0 rear left, 1 front left, 2 front right, 3 rear right, 5 the spare. */
internal fun tyrePosition(index: Int): TyrePos? = when (index) {
    0 -> TyrePos.REAR_LEFT
    1 -> TyrePos.FRONT_LEFT
    2 -> TyrePos.FRONT_RIGHT
    3 -> TyrePos.REAR_RIGHT
    5 -> TyrePos.SPARE
    else -> null
}

internal fun pressureUnit(name: String?): PressureUnit? = when (name?.lowercase(Locale.ROOT)) {
    "bar" -> PressureUnit.BAR
    "kpa" -> PressureUnit.KPA
    "psi" -> PressureUnit.PSI
    else -> null
}

/** The TPMS app's own alarm defaults: at or under 180 kPa, at or over 310 kPa, from 75 °C. */
internal const val TYRE_LOW_KPA = 180
internal const val TYRE_HIGH_KPA = 310
internal const val TYRE_HOT_C = 75

/** How far the driver may move each limit, a step at a time ([Tyres.setLimits]). */
internal val TYRE_LIMIT_LOW_RANGE = 100..250
internal val TYRE_LIMIT_HIGH_RANGE = 260..450
internal val TYRE_LIMIT_HOT_RANGE = 50..120
internal const val TYRE_LIMIT_KPA_STEP = 10
internal const val TYRE_LIMIT_C_STEP = 5

/**
 * A problem ends only this far back on the right side of its limit: a tyre
 * at 180 kPa that reads 181, 180, 181 is one alert, not three.
 */
internal const val TYRE_CLEAR_KPA = 15
internal const val TYRE_CLEAR_C = 5

/** A sensor not heard for this long says nothing about its tyre any more. */
internal const val TYRE_STALE_MS = 60_000L

/**
 * What's wrong with [t], worst first; null when it's fine or its sensor isn't
 * heard. [before] is what was wrong with it at the last reading: that holds
 * until the reading is clearly back ([TYRE_CLEAR_KPA], [TYRE_CLEAR_C]).
 * [limits] holds this tyre's own low limit ([tyreLowKpa]).
 */
internal fun tyreProblem(t: Tyre, before: TyreProblem? = null, limits: TyreLimits = TyreLimits()): TyreProblem? = when {
    t.noSignal -> null
    t.leak -> TyreProblem.LEAK
    t.kPa in 1..limits.lowKpa -> TyreProblem.LOW
    // From 0: a tyre that was low and now reads nothing at all has gone flat, not well.
    before == TyreProblem.LOW && t.kPa in 0 until limits.lowKpa + TYRE_CLEAR_KPA -> TyreProblem.LOW
    t.kPa >= limits.highKpa -> TyreProblem.HIGH
    before == TyreProblem.HIGH && t.kPa > limits.highKpa - TYRE_CLEAR_KPA -> TyreProblem.HIGH
    t.celsius >= limits.hotC -> TyreProblem.HOT
    before == TyreProblem.HOT && t.celsius > limits.hotC - TYRE_CLEAR_C -> TyreProblem.HOT
    else -> null
}

/**
 * A tyre is low at 80 % of the pressure the maker gives for it (the European
 * rule for tyre sensors), else at [lowKpa]: the driver's limit, or the TPMS app's own 180 kPa.
 */
internal fun tyreLowKpa(pos: TyrePos, car: CarProfile, lowKpa: Int = TYRE_LOW_KPA): Int {
    val bar = when (pos) {
        TyrePos.FRONT_LEFT, TyrePos.FRONT_RIGHT -> car.tyreFrontBar
        TyrePos.REAR_LEFT, TyrePos.REAR_RIGHT -> car.tyreRearBar
        TyrePos.SPARE -> null
    } ?: return lowKpa
    return (bar * 100 * 0.8).roundToInt().coerceIn(120, 280)
}

/**
 * What's wrong with each tyre now, given what was wrong at the last look
 * ([before]); tyres whose sensor went quiet ([TYRE_STALE_MS]) are left out.
 */
internal fun tyreProblems(
    tyres: Map<TyrePos, Tyre>,
    before: Map<TyrePos, TyreProblem>,
    now: Long,
    car: CarProfile,
    limits: TyreLimits = TyreLimits()
): Map<TyrePos, TyreProblem> = tyres.mapNotNull { (pos, t) ->
    if (now - t.at > TYRE_STALE_MS) null
    else tyreProblem(t, before[pos], limits.copy(lowKpa = tyreLowKpa(pos, car, limits.lowKpa)))?.let { pos to it }
}.toMap()

/** "2.3 bar", "230 kPa", "33 psi". */
internal fun formatPressure(kPa: Int, unit: PressureUnit): String = when (unit) {
    PressureUnit.BAR -> String.format(Locale.getDefault(), "%.1f bar", kPa / 100.0)
    PressureUnit.KPA -> "$kPa kPa"
    PressureUnit.PSI -> "${(kPa / 6.895).roundToInt()} psi"
}
