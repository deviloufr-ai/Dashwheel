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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

    private val _tyres = MutableStateFlow<Map<TyrePos, Tyre>>(emptyMap())
    val tyres: StateFlow<Map<TyrePos, Tyre>> = _tyres

    private val _unit = MutableStateFlow(PressureUnit.BAR)
    val unit: StateFlow<PressureUnit> = _unit

    private val _problems = MutableStateFlow<Map<TyrePos, TyreProblem>>(emptyMap())
    /**
     * What's wrong, tyre by tyre ([tyreProblems]): the one judgement the
     * alert, its voice and every tile share, so none says "fine" while
     * another says "low".
     */
    val problems: StateFlow<Map<TyrePos, TyreProblem>> = _problems

    private val _limits = MutableStateFlow(TyreLimits())
    /** The alarm limits the warnings go by, whatever reports the tyres (Settings, Car, USB tyre sensors). */
    val limits: StateFlow<TyreLimits> = _limits
    private var limitsLoaded = false

    private var started = false
    private var judging = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Whether this unit has the TPMS app that reports the tyres. */
    val fromApp: Boolean get() = started

    /** Whether something reports the tyres: the unit's TPMS app, or a USB receiver the driver chose ([UsbTpms]). */
    val available: Boolean get() = started || UsbTpms.on

    /** When a tyre last said anything real (a pressure, its sensor heard), [SystemClock.elapsedRealtime]; 0: not yet. */
    @Volatile
    var heardAt = 0L
        private set

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
        judgeFromNowOn()
    }

    /** The warnings worked out from the readings, once something reports them. */
    internal fun judgeFromNowOn() {
        scope.launch {
            if (judging) return@launch
            judging = true
            judgeLoops()
        }
    }

    private fun judgeLoops() {
        // Judged again at each reading, when the car's pressures change, and
        // now and then so a sensor that went quiet stops holding a warning up.
        scope.launch { combine(_tyres, CarProfileStore.profile, _limits) { _, _, _ -> }.collect { judge() } }
        scope.launch {
            while (true) {
                delay(STALE_CHECK_MS)
                if (_tyres.value.isNotEmpty()) judge()
            }
        }
    }

    private const val STALE_CHECK_MS = 15_000L

    @Synchronized
    private fun judge() {
        _problems.value = tyreProblems(_tyres.value, _problems.value, SystemClock.elapsedRealtime(), CarProfileStore.current, _limits.value)
    }

    /** The driver's alarm limits, kept and judged by at once. */
    fun setLimits(context: Context, limits: TyreLimits) {
        prefs(context).edit().putInt(KEY_LOW, limits.lowKpa).putInt(KEY_HIGH, limits.highKpa).putInt(KEY_HOT, limits.hotC).apply()
        _limits.value = limits
        judge()
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
            heard(pos, tyre)
        }
    }

    /** [tyre] at [pos], from the TPMS app or the USB receiver; any thread. */
    internal fun heard(pos: TyrePos, tyre: Tyre) {
        if (!tyre.noSignal && tyre.kPa > 0) heardAt = tyre.at
        // The demo shows its own tyres; the sensors fill them in again once it's over.
        if (DemoMode.isOn) return
        _tyres.update { it + (pos to tyre) }
    }

    /** The USB receiver let go: its last readings leave too, unless the TPMS app keeps reporting. */
    internal fun receiverGone() {
        if (!started && !DemoMode.isOn) _tyres.value = emptyMap()
    }

    /** The unit the driver chose for the USB receiver's pressures; the TPMS app, where there is one, sets its own. */
    internal fun setUnit(unit: PressureUnit) {
        _unit.value = unit
    }

    /** [DemoMode]'s tyres, and the real ones put back when it ends. */
    internal fun demoWrite(tyres: Map<TyrePos, Tyre>) {
        _tyres.value = tyres
        // Judged at once, on a unit without the TPMS app (no collector) too.
        judge()
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
