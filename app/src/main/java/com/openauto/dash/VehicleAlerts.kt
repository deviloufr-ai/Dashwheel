package com.openauto.dash

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/*
 * The bar's vehicle alerts, in two levels. A warning (amber) is a reading out
 * of range: it shows while the reading is, then goes by itself. A critical
 * alert (red) is one to stop the car for: it stays on the bar until the
 * driver taps it, even after the
 * reading recovers, so a fault seen at 90 km/h is still there at the next
 * red light.
 */

internal enum class AlertLevel { WARNING, CRITICAL }

/** One alert: [key] identifies the reading (one chip per reading), [text] is what the chip and the voice say. */
internal data class VehicleAlert(val key: String, val level: AlertLevel, val icon: ImageVector, val text: String)

/**
 * The alerts [obdData] calls for right now, worst first. The battery's comes
 * from [battery] (a steady judgement, [BatteryJudge]), never from one reading.
 */
internal fun vehicleAlerts(
    context: Context,
    obdData: ObdData,
    battery: BatteryState,
    units: UnitSystem = Units.current.value
): List<VehicleAlert> {
    val alerts = ArrayList<VehicleAlert>(2)
    battery.level?.let { level ->
        alerts += VehicleAlert(KEY_BATTERY, level, Icons.Filled.BatteryAlert, context.getString(R.string.dash_alert_battery, battery.volts))
    }
    val temp = obdData.coolantTempC
    val hotC = CarProfileStore.current.hotC
    if (temp >= coolantWarningC(hotC)) {
        val level = if (temp >= coolantCriticalC(hotC)) AlertLevel.CRITICAL else AlertLevel.WARNING
        val text = context.getString(if (units.fahrenheit) R.string.units_alert_coolant_f else R.string.dash_alert_coolant, units.temp(temp))
        alerts += VehicleAlert(KEY_COOLANT, level, Icons.Filled.Thermostat, text)
    }
    return alerts.sortedByDescending { it.level }
}

/** The battery as the bar and the battery tile see it: a steady level, and this trip's range. */
internal data class BatteryState(
    val level: AlertLevel? = null,
    /** The median the level was judged on, for the chip's text. */
    val volts: Double = 0.0,
    /** Lowest and highest trusted reading with the engine running, since it last started. */
    val tripMin: Double? = null,
    val tripMax: Double? = null
)

/**
 * Judges the battery from a stream of readings without being fooled by one.
 *
 * A reading only counts when it comes from the engine computer (the adapter's
 * own ATRV is often miscalibrated: 16.9 V on a 14.5 V bus), is plausible, and
 * the engine has run for [GRACE_MS] (glow plugs and the starter pull the
 * voltage down to 10–11 V; the alternator overshoots right after). The level
 * is the median of the last [WINDOW_MS], judged once the window is at least
 * [MIN_SPAN_MS] long. Engine off: no alert here, the battery tile shows a
 * weak battery at rest and the AI mechanic's watch ([LiveWatch]) says it.
 */
internal class BatteryJudge {
    private val times = ArrayDeque<Long>()
    private val volts = ArrayDeque<Double>()
    private var runningSince: Long? = null
    private var lastAt: Long? = null
    private var tripMin: Double? = null
    private var tripMax: Double? = null

    fun feed(d: ObdData, now: Long): BatteryState {
        // A gap (adapter dropped) empties the window: its readings are no longer "the last 30 s".
        if (lastAt.let { it != null && now - it > GAP_MS }) clearWindow()
        lastAt = now
        val running = d.rpm > RUNNING_RPM
        if (!running) {
            runningSince = null
            clearWindow()
            return BatteryState(tripMin = tripMin, tripMax = tripMax)
        }
        val since = runningSince ?: now.also {
            runningSince = it
            // A new start is a new trip.
            tripMin = null
            tripMax = null
        }
        val v = d.voltage
        val trusted = d.voltageFromEcu && v in MIN_PLAUSIBLE_V..MAX_PLAUSIBLE_V
        if (trusted && now - since >= GRACE_MS) {
            times.addLast(now)
            volts.addLast(v)
            while (times.isNotEmpty() && now - times.first() > WINDOW_MS) {
                times.removeFirst()
                volts.removeFirst()
            }
            tripMin = minOf(tripMin ?: v, v)
            tripMax = maxOf(tripMax ?: v, v)
        }
        if (times.isEmpty() || times.last() - times.first() < MIN_SPAN_MS) {
            return BatteryState(tripMin = tripMin, tripMax = tripMax)
        }
        val median = volts.sorted().let { it[it.size / 2] }
        val level = when {
            median < CRITICAL_LOW_V || median > CRITICAL_HIGH_V -> AlertLevel.CRITICAL
            median < WARNING_LOW_V || median > WARNING_HIGH_V -> AlertLevel.WARNING
            else -> null
        }
        return BatteryState(level, median, tripMin, tripMax)
    }

    private fun clearWindow() {
        times.clear()
        volts.clear()
    }

    companion object {
        const val RUNNING_RPM = 500
        const val GRACE_MS = 30_000L
        const val WINDOW_MS = 30_000L
        const val MIN_SPAN_MS = 25_000L
        const val GAP_MS = 5_000L
        const val MIN_PLAUSIBLE_V = 9.0
        const val MAX_PLAUSIBLE_V = 16.0
        /** Smart charging (e-HDi) runs as low as 12.5–13 V on purpose; below 12.5 V it is not charging. */
        const val WARNING_LOW_V = 12.5
        const val CRITICAL_LOW_V = 12.0
        const val WARNING_HIGH_V = 15.0
        const val CRITICAL_HIGH_V = 15.5
    }
}

/** The one [BatteryJudge], fed by every OBD poll. */
internal object BatteryWatch {
    private val judge = BatteryJudge()
    private val _state = MutableStateFlow(BatteryState())
    val state: StateFlow<BatteryState> = _state

    @Synchronized
    fun feed(d: ObdData, now: Long) {
        _state.value = judge.feed(d, now)
    }
}

internal const val KEY_BATTERY = "battery"
internal const val KEY_COOLANT = "coolant"

/** One alert as it happened, for the log the fault-code tile shows. */
internal data class AlertEvent(val at: Long, val level: AlertLevel, val text: String)

/**
 * The engine's coolant limits for the bar, from the temperature it runs at
 * ([CarProfile.hotC]): amber 15 °C over it, red where the voice says it is
 * overheating ([Overheat.alarmC]), so the chip and the voice never disagree.
 */
internal fun coolantWarningC(hotC: Int): Int = hotC + 15
internal fun coolantCriticalC(hotC: Int): Int = Overheat.alarmC(hotC)

/** Critical alerts raised and not yet acknowledged; the bar draws these whatever the reading does next. */
internal object AlertCenter {
    /** By key: the alert as it was when raised (the text keeps the reading that triggered it). */
    val critical = mutableStateMapOf<String, VehicleAlert>()
    /** The warnings up right now (amber: they go when the reading comes back). */
    val warnings = mutableStateListOf<VehicleAlert>()
    /** The last alerts of either level, newest first, so a chip that came and went can still be read. */
    val history = mutableStateListOf<AlertEvent>()
    private const val HISTORY_MAX = 20
    private val activeWarnings = HashSet<String>()

    /**
     * Judges the readings now ([VehicleMonitor], for the whole process): with
     * Maps or CarPlay full screen the bar is not drawn, and a reading that went
     * critical meanwhile must still be up, and logged, when the driver comes
     * back. Main thread. [connected] false clears the warnings: no reading, no amber.
     */
    fun evaluate(context: Context, obdData: ObdData, battery: BatteryState, connected: Boolean) {
        val live = if (connected) vehicleAlerts(context, obdData, battery) else emptyList()
        live.filter { it.level == AlertLevel.CRITICAL }.forEach { raise(it) }
        val amber = live.filter { it.level == AlertLevel.WARNING }
        noteWarnings(amber)
        if (warnings.toList() != amber) {
            warnings.clear()
            warnings.addAll(amber)
        }
    }

    /** A learned wheel button: every red chip seen at once. */
    fun acknowledgeAll() {
        critical.clear()
    }

    /** Logs the warnings in [warnings] that were not up a moment ago; forgets the ones that went. */
    fun noteWarnings(warnings: List<VehicleAlert>) {
        val keys = warnings.map { it.key }.toSet()
        warnings.forEach { if (activeWarnings.add(it.key)) log(it) }
        activeWarnings.retainAll(keys)
    }

    private fun log(alert: VehicleAlert) {
        history.add(0, AlertEvent(System.currentTimeMillis(), alert.level, alert.text))
        while (history.size > HISTORY_MAX) history.removeAt(history.lastIndex)
    }

    /**
     * Puts [alert] up if it is not up already. Silent: the car's voice is the
     * AI mechanic's watch (LiveWatch), which already says a real overheat or a
     * battery not charging; saying it here as well would say it twice.
     */
    fun raise(alert: VehicleAlert) {
        if (critical.containsKey(alert.key)) return
        critical[alert.key] = alert
        log(alert)
    }

    /** The driver saw it. The same fault raised again later is said again. */
    fun acknowledge(key: String) {
        critical.remove(key)
    }
}
