package com.openauto.dash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale
import kotlin.math.roundToInt

/*
 * The tyres, from the head unit's TPMS app (com.syt.tmps on the QF firmware)
 * and its tyre sensors (worked out from the firmware): it tells every app
 * each tyre's pressure (always kPa) and temperature (°C) about every four
 * seconds, whatever its own screen shows. Its alarm thresholds stay private,
 * so the same defaults are applied here ([tyreProblem]).
 */

enum class TyrePos { FRONT_LEFT, FRONT_RIGHT, REAR_LEFT, REAR_RIGHT, SPARE }

data class Tyre(
    val kPa: Int,
    val celsius: Int,
    val sensorBatteryLow: Boolean,
    val leak: Boolean,
    val noSignal: Boolean,
    /** When it was heard ([SystemClock.elapsedRealtime]). */
    val at: Long
)

enum class TyreProblem { LEAK, LOW, HIGH, HOT }

/** The unit the driver chose in the TPMS app, to show pressures the same way. */
enum class PressureUnit { BAR, KPA, PSI }

object Tyres {
    const val TPMS_PACKAGE = "com.syt.tmps"
    private const val ACTION = "TPMS_APP_STATE_SEND"

    private val _tyres = MutableStateFlow<Map<TyrePos, Tyre>>(emptyMap())
    val tyres: StateFlow<Map<TyrePos, Tyre>> = _tyres

    private val _unit = MutableStateFlow(PressureUnit.BAR)
    val unit: StateFlow<PressureUnit> = _unit

    private var started = false

    /** Whether this unit has the TPMS app that reports the tyres. */
    val available: Boolean get() = started

    fun start(context: Context) {
        if (started) return
        val app = context.applicationContext
        if (!isPackageInstalled(app, TPMS_PACKAGE)) return
        started = true
        ContextCompat.registerReceiver(app, receiver, IntentFilter(ACTION), ContextCompat.RECEIVER_EXPORTED)
    }

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
            // The demo shows its own tyres; the sensors fill them in again once it's over.
            if (DemoMode.isOn) return
            _tyres.update { it + (pos to tyre) }
        }
    }

    /** [DemoMode]'s tyres, and the real ones put back when it ends. */
    internal fun demoWrite(tyres: Map<TyrePos, Tyre>) {
        _tyres.value = tyres
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

/** What's wrong with [t], worst first; null when it's fine or its sensor isn't heard. */
internal fun tyreProblem(t: Tyre): TyreProblem? = when {
    t.noSignal -> null
    t.leak -> TyreProblem.LEAK
    t.kPa in 1..TYRE_LOW_KPA -> TyreProblem.LOW
    t.kPa >= TYRE_HIGH_KPA -> TyreProblem.HIGH
    t.celsius >= TYRE_HOT_C -> TyreProblem.HOT
    else -> null
}

/** "2.3 bar", "230 kPa", "33 psi". */
internal fun formatPressure(kPa: Int, unit: PressureUnit): String = when (unit) {
    PressureUnit.BAR -> String.format(Locale.getDefault(), "%.1f bar", kPa / 100.0)
    PressureUnit.KPA -> "$kPa kPa"
    PressureUnit.PSI -> "${(kPa / 6.895).roundToInt()} psi"
}
