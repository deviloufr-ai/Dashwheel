package com.openauto.dash

import android.content.Context
import com.openauto.dash.link.DriveSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * The trip computer's two trips. "This drive" is the GPS trip the drive log
 * follows ([LocationFeed.trip]). "Since fill-up" counts on from the last
 * refuel in the fuel log: a running total of the GPS trip's distance and
 * moving time, started again from zero each time a refuel is logged. Both
 * come with the fuel they took and what it cost, at the car's measured
 * consumption once the refuels tell it, else the profile's usual figure.
 * Nothing to start or reset by hand.
 */

/** One trip as the trip computer shows it: [cost] null without a fuel price. */
internal data class TripFigures(val km: Double, val movingMs: Long, val avgKmh: Double, val liters: Double, val cost: Double?)

/** Fuel and money for a distance, pure so they're unit-tested. */
internal object TripMath {
    /** [km] driven in [movingMs], at [use] L/100 km and [price] a litre. */
    fun figures(km: Double, movingMs: Long, use: Double, price: Double?): TripFigures {
        val liters = liters(km, use)
        return TripFigures(km, movingMs, avgKmh(km, movingMs), liters, cost(liters, price))
    }

    /** Litres for [km] at [use] L/100 km. */
    fun liters(km: Double, use: Double): Double = (km * use / 100).coerceAtLeast(0.0)

    /** What [liters] cost at [price] a litre; null without a price. */
    fun cost(liters: Double, price: Double?): Double? = price?.takeIf { it > 0 }?.let { liters * it }

    /** The consumption to count with: the car's measured one from its refuels, else the profile's. */
    fun use(measured: Double?, car: CarProfile): Double = measured?.takeIf { it > 0 } ?: car.typicalUse

    /** The price a litre: the last one paid, else the driver's own; null when neither is set. */
    fun price(lastPaid: Double?, car: CarProfile): Double? = lastPaid?.takeIf { it > 0 } ?: car.fuelPrice.takeIf { it > 0 }

    /** Average speed over the moving time, km/h. */
    fun avgKmh(km: Double, movingMs: Long): Double = if (movingMs > 0L) km / (movingMs / 3_600_000.0) else 0.0

    /** A distance as the trip computer shows it: a decimal only under 10 (past that it ticked every 100 m while driving). */
    fun figure(distance: Double, locale: Locale = Locale.getDefault()): String =
        if (distance < 10) String.format(locale, "%.1f", distance) else distance.roundToInt().toString()

    /** Litres to one decimal. */
    fun litersText(liters: Double, locale: Locale = Locale.getDefault()): String = String.format(locale, "%.1f L", liters)
}

/**
 * The distance since the last refuel. [fillAt]: the refuel counted from,
 * null before the first. [tripStartedAt], [tripM], [tripMovingMs]: the GPS
 * trip as last seen, so each new reading adds only what it moved since.
 */
internal data class SinceFillState(
    val fillAt: Long? = null,
    val distanceM: Double = 0.0,
    val movingMs: Long = 0L,
    val tripStartedAt: Long? = null,
    val tripM: Double = 0.0,
    val tripMovingMs: Long = 0L
) {
    val km: Double get() = distanceM / 1000.0
}

/** How the count since the refuel moves, pure so it's unit-tested. */
internal object SinceFillRules {
    /**
     * [s] after the trip computer shows [trip]: the same trip adds what it
     * moved since last seen (never less: a trip restored after a reboot may
     * be a little behind); another trip (a reset, a new drive) adds all it has.
     */
    fun follow(s: SinceFillState, trip: TripState): SinceFillState {
        val same = s.tripStartedAt == trip.startedAt
        val addM = if (same) (trip.distanceM - s.tripM).coerceAtLeast(0.0) else trip.distanceM
        val addMs = if (same) (trip.movingMs - s.tripMovingMs).coerceAtLeast(0L) else trip.movingMs
        return s.copy(
            distanceM = s.distanceM + addM, movingMs = s.movingMs + addMs,
            tripStartedAt = trip.startedAt, tripM = trip.distanceM, tripMovingMs = trip.movingMs
        )
    }

    /** A refuel logged at [fillAt]: the count starts again from zero (from the trip as it stands, so the drive away counts). */
    fun refuel(s: SinceFillState, fillAt: Long?): SinceFillState =
        if (fillAt == null || fillAt == s.fillAt) s else s.copy(fillAt = fillAt, distanceM = 0.0, movingMs = 0L)

    /**
     * The first start, with no count kept yet: the drives logged since the
     * refuel at [fillAt] ([drives] newest first). The trip under way is then
     * added by [follow] as a trip not seen before.
     */
    fun seed(fillAt: Long?, drives: List<DriveSummary>): SinceFillState {
        if (fillAt == null) return SinceFillState()
        val since = drives.filter { !it.ongoing && it.startedAt >= fillAt }
        return SinceFillState(fillAt = fillAt, distanceM = since.sumOf { it.distanceKm * 1000 }, movingMs = since.sumOf { it.movingMs })
    }
}

/** The live side: follows the GPS trip and the fuel log, and keeps the count across restarts. */
internal object SinceFill {
    private const val PREFS = "since_fill"
    /** Written every this many metres: a power cut loses at most this much. */
    private const val SAVE_EVERY_M = 200.0

    // Main thread, where the GPS feed and the fuel log publish.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var appContext: Context? = null
    private var savedM = 0.0

    private val _state = MutableStateFlow(SinceFillState())
    val state: StateFlow<SinceFillState> = _state.asStateFlow()

    /** Starts once for the process, after [DriveLog] (the trip restored) and [FuelLog] (the refuels read); main thread. */
    fun start(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        val saved = prefs(app).getString("state", null)?.let { runCatching { fromJson(JSONObject(it)) }.getOrNull() }
        _state.value = saved ?: SinceFillRules.seed(FuelLog.fills.value.firstOrNull()?.at, DriveLog.drives.value)
        savedM = _state.value.distanceM
        scope.launch { FuelLog.fills.collect { fills -> update(SinceFillRules.refuel(_state.value, fills.firstOrNull()?.at)) } }
        // The demo's trip is not driven: the real one comes back unchanged when it ends.
        scope.launch { LocationFeed.trip.collect { trip -> if (!DemoMode.isOn) update(SinceFillRules.follow(_state.value, trip)) } }
    }

    private fun update(next: SinceFillState) {
        val before = _state.value
        if (next == before) return
        _state.value = next
        val app = appContext ?: return
        if (next.fillAt != before.fillAt || next.tripStartedAt != before.tripStartedAt || abs(next.distanceM - savedM) >= SAVE_EVERY_M) {
            savedM = next.distanceM
            prefs(app).edit().putString("state", toJson(next).toString()).apply()
        }
    }

    private fun toJson(s: SinceFillState) = JSONObject().apply {
        s.fillAt?.let { put("fill", it) }
        put("m", s.distanceM).put("moving", s.movingMs)
        s.tripStartedAt?.let { put("trip", it) }
        put("trip_m", s.tripM).put("trip_moving", s.tripMovingMs)
    }

    private fun fromJson(o: JSONObject) = SinceFillState(
        fillAt = if (o.has("fill")) o.getLong("fill") else null,
        distanceM = o.getDouble("m"), movingMs = o.getLong("moving"),
        tripStartedAt = if (o.has("trip")) o.getLong("trip") else null,
        tripM = o.optDouble("trip_m", 0.0), tripMovingMs = o.optLong("trip_moving", 0L)
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
