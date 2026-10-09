package com.openauto.dash

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * Watches each drive for the car-care tiles and speaks up by itself, so the
 * driver never has to look: short trips that starve the particle filter, revving
 * a cold engine, holding a robotised gearbox on the throttle, two hours without
 * a break, and a range that won't reach the destination.
 */

/** One drive, engine start to engine off. */
internal data class Drive(
    val startedAt: Long,
    val lastAt: Long = startedAt,
    val lastRunningAt: Long = startedAt,
    val runningMs: Long = 0,
    val movingMs: Long = 0,
    val distanceKm: Double = 0.0,
    /** Warm engine at 60 km/h or more: the driving that lets a particle filter clean itself. */
    val hotFastMs: Long = 0,
    /** Moving with the revs in the car's relaxed band. */
    val sweetMs: Long = 0,
    val overRevMs: Long = 0,
    val hardAccel: Int = 0,
    val hardBrake: Int = 0,
    val clutchHolds: Int = 0,
    val peakCoolant: Int = 0,
    val coldRevWarned: Boolean = false,
    // Trackers for events that must last a moment before they count.
    val lastSpeed: Int = 0,
    val lastSpeedAt: Long = 0,
    val lastEventAt: Long = 0,
    val coldRevSince: Long? = null,
    val clutchSince: Long? = null,
    val clutchCounted: Boolean = false,
    val clutchSpokenAt: Long = 0
) {
    /** 0-100: smooth, relaxed driving scores high. Too early to tell under a kilometre. */
    val ecoScore: Int?
        get() {
            if (distanceKm < 1.0 || movingMs <= 0) return null
            val per10km = 10.0 / max(distanceKm, 5.0)
            val harsh = (hardAccel * 3 + hardBrake * 4) * per10km
            val revs = overRevMs.toDouble() / movingMs * 60
            return (100 - harsh - revs).toInt().coerceIn(0, 100)
        }

    /** Share of the moving time spent in the relaxed rev band, 0-100. */
    val sweetPercent: Int? get() = if (movingMs < 60_000) null else (sweetMs * 100 / movingMs).toInt().coerceIn(0, 100)

    /** Long enough at speed with a hot engine for the particle filter. */
    val filterFriendly: Boolean get() = hotFastMs >= CareRules.LONG_DRIVE_MS
}

/** How the particle filter's recent drives went; kept across days. */
internal data class FilterLog(
    /** Short drives in a row since the last long one. */
    val shortStreak: Int = 0,
    val lastLongAt: Long = 0,
    /** The streak last spoken about, so reminders come every other short drive. */
    val warnedStreak: Int = 0
)

/** Driving time since the last real stop. */
internal data class RestTimer(
    val drivingMs: Long = 0,
    val lastAt: Long = 0,
    val stoppedSince: Long? = null,
    /** The driving time (minutes) last announced; 0 before the first reminder. */
    val spokenMin: Int = 0
)

internal data class CareState(
    val drive: Drive? = null,
    val lastDrive: Drive? = null,
    val filter: FilterLog = FilterLog(),
    val rest: RestTimer = RestTimer()
)

internal sealed interface CareEvent {
    data object ColdRevs : CareEvent
    data object ClutchHold : CareEvent
    data class BreakDue(val minutes: Int) : CareEvent
    data class FilterNeedsDrive(val streak: Int) : CareEvent
}

/** The rules, pure so they're unit-tested. */
internal object CareRules {
    const val RUNNING_RPM = 400
    const val MOVING_KMH = 3
    const val MAX_STEP_MS = 5_000L
    /** No reading for this long: the unit slept or the adapter dropped, the drive is over. */
    const val GAP_MS = 5 * 60_000L
    const val ENGINE_OFF_MS = 3 * 60_000L
    /** Shorter drives (a quick restart) don't count either way. */
    const val MIN_DRIVE_MS = 2 * 60_000L
    /** Idling on the drive or in a car park isn't a trip: under this, the filter streak doesn't move. */
    const val MIN_TRIP_KM = 0.5
    const val LONG_DRIVE_MS = 10 * 60_000L
    const val FILTER_SPEED_KMH = 60
    const val FILTER_WARN_STREAK = 3
    const val HARD_ACCEL_KMHS = 10.0
    /** The shortest time a change in speed is measured over. */
    const val MIN_ACCEL_SPAN_MS = 300L
    const val HARD_BRAKE_KMHS = -12.0
    const val COLD_REV_MS = 3_000L
    const val CLUTCH_RPM = 1200
    const val CLUTCH_LOAD = 45
    const val CLUTCH_MS = 2_500L
    const val CLUTCH_REPEAT_MS = 15 * 60_000L
    const val BREAK_RESET_MS = 15 * 60_000L
    const val FIRST_BREAK_MIN = 120
    const val BREAK_REPEAT_MIN = 30

    fun step(s: CareState, d: ObdData, now: Long, car: CarProfile): Pair<CareState, List<CareEvent>> {
        val events = mutableListOf<CareEvent>()
        val running = d.rpm > RUNNING_RPM
        var drive = s.drive
        var lastDrive = s.lastDrive
        var filter = s.filter

        // A drive ends after a gap in the readings or a few minutes with the engine off.
        if (drive != null && (now - drive.lastAt > GAP_MS || (!running && now - drive.lastRunningAt > ENGINE_OFF_MS))) {
            filter = close(drive, filter)
            lastDrive = drive
            drive = null
        }
        if (drive == null && running) {
            drive = Drive(startedAt = now, lastSpeed = d.speedKmh, lastSpeedAt = now)
            if (car.particleFilter && filter.shortStreak >= FILTER_WARN_STREAK &&
                (filter.warnedStreak == 0 || filter.shortStreak >= filter.warnedStreak + 2)
            ) {
                events += CareEvent.FilterNeedsDrive(filter.shortStreak)
                filter = filter.copy(warnedStreak = filter.shortStreak)
            }
        } else if (drive != null) {
            drive = advance(drive, d, now, car, running, events)
        }

        val rest = restStep(s.rest, d, now, events)
        return CareState(drive, lastDrive, filter, rest) to events
    }

    private fun close(drive: Drive, filter: FilterLog): FilterLog = when {
        drive.runningMs < MIN_DRIVE_MS || drive.distanceKm < MIN_TRIP_KM -> filter
        drive.filterFriendly -> FilterLog(shortStreak = 0, lastLongAt = drive.lastAt, warnedStreak = 0)
        else -> filter.copy(shortStreak = filter.shortStreak + 1)
    }

    private fun advance(v: Drive, d: ObdData, now: Long, car: CarProfile, running: Boolean, events: MutableList<CareEvent>): Drive {
        val dt = (now - v.lastAt).coerceIn(0, MAX_STEP_MS)
        val moving = d.speedKmh > MOVING_KMH
        var x = v.copy(
            lastAt = now,
            lastRunningAt = if (running) now else v.lastRunningAt,
            runningMs = v.runningMs + if (running) dt else 0,
            movingMs = v.movingMs + if (moving) dt else 0,
            distanceKm = v.distanceKm + d.speedKmh * dt / 3_600_000.0,
            hotFastMs = v.hotFastMs + if (d.coolantTempC >= car.hotC - 10 && d.speedKmh >= FILTER_SPEED_KMH) dt else 0,
            sweetMs = v.sweetMs + if (moving && d.rpm in car.sweetBand) dt else 0,
            overRevMs = v.overRevMs + if (d.rpm > car.ecoRpmMax) dt else 0,
            peakCoolant = max(v.peakCoolant, d.coolantTempC),
        )

        // Hard acceleration / braking from the change in speed since a reading at
        // least [MIN_ACCEL_SPAN_MS] old: readings closer than that (a fast adapter
        // polled often) keep the older one, or no span ever counted.
        val span = now - v.lastSpeedAt
        if (span >= MIN_ACCEL_SPAN_MS || span < 0) x = x.copy(lastSpeed = d.speedKmh, lastSpeedAt = now)
        if (span in MIN_ACCEL_SPAN_MS..3_000 && now - v.lastEventAt >= 3_000) {
            val a = (d.speedKmh - v.lastSpeed) / (span / 1000.0)
            if (a >= HARD_ACCEL_KMHS) x = x.copy(hardAccel = x.hardAccel + 1, lastEventAt = now)
            else if (a <= HARD_BRAKE_KMHS) x = x.copy(hardBrake = x.hardBrake + 1, lastEventAt = now)
        }

        // Revving a cold engine (0 °C = the car didn't say, so no judgement).
        // Below zero counts: a frosty start is when it matters most.
        if (d.coolantTempC != 0 && d.coolantTempC < car.coldC && d.rpm > car.coldRpmLimit + 300) {
            val since = x.coldRevSince ?: now
            x = x.copy(coldRevSince = since)
            if (!x.coldRevWarned && now - since >= COLD_REV_MS) {
                x = x.copy(coldRevWarned = true)
                events += CareEvent.ColdRevs
            }
        } else x = x.copy(coldRevSince = null)

        // A robotised gearbox held on a slope with the throttle slips its clutch:
        // standing still, revs up and a heavy load.
        if (car.gearbox == GearboxType.ROBOTISED && d.speedKmh <= MOVING_KMH && d.rpm >= CLUTCH_RPM && d.engineLoadPct >= CLUTCH_LOAD) {
            val since = x.clutchSince ?: now
            x = x.copy(clutchSince = since)
            if (!x.clutchCounted && now - since >= CLUTCH_MS) {
                x = x.copy(clutchHolds = x.clutchHolds + 1, clutchCounted = true)
                if (x.clutchSpokenAt == 0L || now - x.clutchSpokenAt >= CLUTCH_REPEAT_MS) {
                    x = x.copy(clutchSpokenAt = now)
                    events += CareEvent.ClutchHold
                }
            }
        } else x = x.copy(clutchSince = null, clutchCounted = false)
        return x
    }

    internal fun restStep(r: RestTimer, d: ObdData, now: Long, events: MutableList<CareEvent>): RestTimer {
        // Long enough without readings (car parked, unit asleep) is a break.
        var x = if (r.lastAt == 0L || now - r.lastAt >= BREAK_RESET_MS) RestTimer(lastAt = now) else r
        val dt = (now - x.lastAt).coerceIn(0, MAX_STEP_MS)
        x = if (d.speedKmh > MOVING_KMH) {
            x.copy(drivingMs = x.drivingMs + dt, stoppedSince = null, lastAt = now)
        } else {
            val since = x.stoppedSince ?: now
            if (now - since >= BREAK_RESET_MS) RestTimer(lastAt = now) else x.copy(stoppedSince = since, lastAt = now)
        }
        val minutes = (x.drivingMs / 60_000).toInt()
        val next = if (x.spokenMin == 0) FIRST_BREAK_MIN else x.spokenMin + BREAK_REPEAT_MIN
        if (minutes >= next) {
            events += CareEvent.BreakDue(minutes)
            x = x.copy(spokenMin = next)
        }
        return x
    }

    /** Remaining distance (km) in a navigation app's ETA line, e.g. "12 min · 6.4 km · 09:48". */
    fun remainingKm(eta: String): Double? {
        val m = NavState.DISTANCE.find(eta) ?: return null
        val n = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        return when (m.groupValues[2]) {
            "km" -> n
            "m" -> n / 1000
            "mi" -> n * 1.609
            "yd" -> n * 0.000914
            "ft" -> n * 0.000305
            else -> null
        }
    }

    /** How the range compares to the distance left: null when either is unknown. */
    fun fuelVerdict(rangeKm: Int?, toGoKm: Double?): FuelVerdict? {
        if (rangeKm == null || toGoKm == null) return null
        val spare = rangeKm - toGoKm
        return when {
            spare < 0 -> FuelVerdict.SHORT
            spare < TIGHT_SPARE_KM -> FuelVerdict.TIGHT
            else -> FuelVerdict.ENOUGH
        }
    }

    const val TIGHT_SPARE_KM = 50
}

internal enum class FuelVerdict { ENOUGH, TIGHT, SHORT }

/**
 * Low fuel on the move: said once as the range falls to [WARN_KM], and again
 * only after a fill-up took it back over [REARM_KM]. Parked, the start-up
 * briefing has it. Pure, so it's unit-tested.
 */
internal object LowFuel {
    const val WARN_KM = 80
    const val REARM_KM = 150

    /** Whether to say it now, and whether the rule is armed afterwards. A range of 0 is the car not saying. */
    fun step(armed: Boolean, rangeKm: Int?, moving: Boolean): Pair<Boolean, Boolean> = when {
        rangeKm == null || rangeKm <= 0 -> false to armed
        rangeKm >= REARM_KM -> false to true
        armed && moving && rangeKm <= WARN_KM -> true to false
        else -> false to armed
    }
}

/** The live side: feeds [CareRules] every OBD reading, keeps the state and speaks the events. */
object CarCare {
    private const val PREFS = "car_care"
    private const val SAVE_EVERY_MS = 30_000L

    private var appContext: Context? = null
    private val _state = MutableStateFlow(CareState())
    internal val state: StateFlow<CareState> = _state.asStateFlow()
    private var savedAt = 0L
    /** The navigation already warned about a short range (its ETA line's destination part). */
    private var fuelWarnedFor: String? = null

    fun setContext(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _state.value = CareState(
            drive = p.getString("drive", null)?.let { runCatching { driveFrom(JSONObject(it)) }.getOrNull() },
            lastDrive = p.getString("last_drive", null)?.let { runCatching { driveFrom(JSONObject(it)) }.getOrNull() },
            filter = FilterLog(p.getInt("short_streak", 0), p.getLong("last_long_at", 0), p.getInt("warned_streak", 0)),
            rest = RestTimer(p.getLong("rest_ms", 0), p.getLong("rest_at", 0), spokenMin = p.getInt("rest_spoken", 0))
        )
        fuelArmed = p.getBoolean(KEY_FUEL_ARMED, true)
        _filterWatch.value = FilterWatch(sootSaidDay = p.getLong(KEY_SOOT_DAY, -1), additiveSaidAt = p.getLong(KEY_ADDITIVE_AT, 0))
        scope.launch { watchFuel() }
        scope.launch { watchRestWithoutObd() }
    }

    /** How often driving time is counted without the adapter's readings. */
    private const val REST_TICK_MS = 1_000L
    private var gpsHeld = false

    /**
     * Driving time for the break reminder while the OBD adapter is not
     * connected: from the car box's speed, else the GPS's (held only then).
     * With the adapter, [watch] counts it with the rest of the drive. A gap in
     * the adapter's link used to count as a stop and start the 2 hours again.
     */
    private suspend fun watchRestWithoutObd() {
        val speed = carSpeedKmh().stateIn(scope, SharingStarted.Eagerly, 0)
        while (true) {
            delay(REST_TICK_MS)
            val context = appContext ?: continue
            val obd = ObdBluetoothManager.connectionState.value == ObdConnectionState.CONNECTED
            val wantGps = !obd && CarBox.freshBody()?.speedKmh == null && CarPower.ignition.value != false
            if (wantGps && !gpsHeld) gpsHeld = LocationFeed.acquire(context)
            else if (!wantGps && gpsHeld) {
                LocationFeed.release()
                gpsHeld = false
            }
            if (obd || DemoMode.isOn || CarPower.ignition.value == false) continue
            restTick(speed.value, System.currentTimeMillis())
        }
    }

    private fun restTick(kmh: Int, now: Long) {
        val before = _state.value
        val events = mutableListOf<CareEvent>()
        val rest = CareRules.restStep(before.rest, ObdData(speedKmh = kmh), now, events)
        val next = before.copy(rest = rest)
        _state.value = next
        if (now - savedAt >= SAVE_EVERY_MS) save(next, now)
        events.forEach { say(line(it, CarProfileStore.current)) }
    }

    private const val KEY_FUEL_ARMED = "fuel_armed"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var fuelArmed = true

    /**
     * Low fuel while driving, with or without the OBD adapter: the range is
     * the car's own, and the speed whichever source has one ([carSpeedKmh]).
     */
    private suspend fun watchFuel() {
        val fuel = combine(McuReader.fuelPercent, McuReader.rangeKm, ObdBluetoothManager.data) { percent, range, obd ->
            carFuelInfo(percent, obd.fuelLevelPct, range)?.rangeKm
        }
        combine(fuel, carSpeedKmh()) { range, kmh -> range to (kmh >= MOVING_KMH) }
            .distinctUntilChanged()
            .collect { (range, moving) ->
                if (DemoMode.isOn) return@collect
                val (speak, armed) = LowFuel.step(fuelArmed, range, moving)
                if (armed != fuelArmed) {
                    fuelArmed = armed
                    appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putBoolean(KEY_FUEL_ARMED, armed)?.apply()
                }
                if (speak && range != null) sayLowFuel(range)
            }
    }

    /** "Fuel is low…", then the cheapest station around when the prices are known there. */
    private fun sayLowFuel(rangeKm: Int) {
        scope.launch {
            val here = LocationFeed.location.value
            val best = here?.let { withTimeoutOrNull(STATION_WAIT_MS) { FuelPriceRepo.cheapest(it.latitude, it.longitude) } }
            val lines = listOfNotNull(
                fuelLowLine(rangeKm),
                best?.let { stationLine(it) }
            )
            val context = appContext ?: return@launch
            if (!SpokenEvents.isOn(context, SpokenEvent.CAR_TIPS)) return@launch
            val resources = AppLanguage.wrap(context).resources
            CarVoice.announce(lines.joinToString(" ") { it.text(resources) }, InAppNav.locale(context))
        }
    }

    private const val STATION_WAIT_MS = 8_000L

    /** "Fuel is low: about 60 kilometres left" (or miles). */
    internal fun fuelLowLine(rangeKm: Int, units: UnitSystem = Units.current.value): SpokenLine =
        SpokenLine(if (units.imperial) R.string.units_briefing_fuel_low_mi else R.string.briefing_fuel_low, listOf(units.distance(rangeKm)))

    /** "The cheapest station nearby is Intermarché, 3 kilometres away" (or miles). */
    internal fun stationLine(best: RankedStation, units: UnitSystem = Units.current.value): SpokenLine {
        val d = units.distance(best.distanceKm).roundToInt().coerceAtLeast(1)
        val name = best.station.name.ifBlank { best.station.town }
        return SpokenLine(if (units.imperial) R.plurals.units_voice_fuel_station_mi else R.plurals.voice_fuel_station, listOf(name, d), quantity = d)
    }

    /** [DemoMode]'s drive stats, never saved (and, when it ends, the real ones back). */
    internal fun demoWrite(state: CareState) {
        _state.value = state
    }

    fun watch(data: ObdData) {
        if (DemoMode.isOn) return
        val now = System.currentTimeMillis()
        val before = _state.value
        val (next, events) = CareRules.step(before, data, now, CarProfileStore.current)
        _state.value = next
        if (next.filter != before.filter || next.lastDrive !== before.lastDrive || now - savedAt >= SAVE_EVERY_MS) save(next, now)
        // The servicing planner's mileage advances with the kilometres of this drive.
        val drive = next.drive
        val previous = before.drive
        if (drive != null && previous != null && drive.startedAt == previous.startedAt) {
            Maintenance.drove(drive.distanceKm - previous.distanceKm)
        }
        // The short-drive reminder waits for the filter's own readings, to be said with them.
        val streak = events.filterIsInstance<CareEvent.FilterNeedsDrive>().firstOrNull()?.streak
        events.filterNot { it is CareEvent.FilterNeedsDrive }.forEach { say(line(it, CarProfileStore.current)) }
        watchFilter(data, started = next.drive != null && next.drive.startedAt != before.drive?.startedAt, streak = streak, now = now)
        checkFuel()
    }

    private const val KEY_SOOT_DAY = "filter_soot_day"
    private const val KEY_ADDITIVE_AT = "filter_additive_at"
    private val _filterWatch = MutableStateFlow(FilterWatch())
    /** The particle filter's regeneration and reminders, for its tile. */
    internal val filterWatch: StateFlow<FilterWatch> = _filterWatch.asStateFlow()

    private fun watchFilter(data: ObdData, started: Boolean, streak: Int?, now: Long) {
        val car = CarProfileStore.current
        var w = _filterWatch.value
        if (started) w = FilterRules.driveStarted(w, now, streak)
        val expected = PidExplorer.state.value.verified.map { it.reading }.toSet()
        val (next, events) = FilterRules.step(
            w, PidExplorer.readings.value, expected, data.speedKmh, data.rpm > CareRules.RUNNING_RPM,
            now, LocalDate.now().toEpochDay(), car
        )
        _filterWatch.value = next
        if (next.sootSaidDay != w.sootSaidDay || next.additiveSaidAt != w.additiveSaidAt) {
            appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
                ?.putLong(KEY_SOOT_DAY, next.sootSaidDay)
                ?.putLong(KEY_ADDITIVE_AT, next.additiveSaidAt)
                ?.apply()
        }
        events.forEach { say(filterLine(it)) }
    }

    private fun filterLine(e: FilterEvent): SpokenLine = when (e) {
        FilterEvent.RegenKeepDriving -> SpokenLine(R.string.car_say_filter_regen, emptyList())
        FilterEvent.RegenStanding -> SpokenLine(R.string.car_say_filter_regen_standing, emptyList())
        is FilterEvent.ShortDrives -> line(CareEvent.FilterNeedsDrive(e.streak), CarProfileStore.current)
        is FilterEvent.SootHigh ->
            if (e.streak != null) SpokenLine(R.string.car_say_filter_soot_short, listOf(e.streak, e.percent))
            else SpokenLine(R.string.car_say_filter_soot, listOf(e.percent))
        is FilterEvent.AdditiveLow -> SpokenLine(R.string.car_say_filter_additive, listOf(e.percent))
    }

    /** Says once per navigation when the range won't reach the destination. */
    private fun checkFuel() {
        val nav = NavDirections.state.value
        if (!nav.active) {
            fuelWarnedFor = null
            return
        }
        if (fuelWarnedFor != null) return
        val toGo = CareRules.remainingKm(nav.eta) ?: return
        val range = currentRange() ?: return
        if (CareRules.fuelVerdict(range, toGo) == FuelVerdict.SHORT) {
            fuelWarnedFor = nav.eta
            val units = Units.current.value
            say(
                SpokenLine(
                    if (units.imperial) R.string.units_car_say_fuel_short_mi else R.string.car_say_fuel_short,
                    listOf(units.distance(range), units.distance(toGo).toInt())
                )
            )
        }
    }

    /** The range as the fuel tiles show it. */
    internal fun currentRange(): Int? {
        return carFuelInfo(McuReader.fuelPercent.value, ObdBluetoothManager.data.value.fuelLevelPct, McuReader.rangeKm.value)?.rangeKm
    }

    private fun line(e: CareEvent, car: CarProfile): SpokenLine = when (e) {
        CareEvent.ColdRevs -> SpokenLine(R.string.car_say_cold_revs, listOf(car.coldRpmLimit))
        CareEvent.ClutchHold -> SpokenLine(R.string.car_say_clutch_hold, emptyList())
        is CareEvent.FilterNeedsDrive ->
            SpokenLine(if (Units.current.value.imperial) R.string.units_car_say_filter_mph else R.string.car_say_filter, listOf(e.streak))
        is CareEvent.BreakDue -> {
            val h = e.minutes / 60
            val m = e.minutes % 60
            val hours = SpokenLine(R.plurals.car_hours, listOf(h), quantity = h)
            if (m == 0) SpokenLine(R.string.car_say_break, listOf(hours))
            else SpokenLine(R.string.car_say_break, listOf(SpokenLine(R.string.car_hours_minutes, listOf(hours, SpokenLine(R.plurals.car_minutes, listOf(m), quantity = m)))))
        }
    }

    /** Said by the car on its own, when car warnings are spoken, in the launcher's voice. */
    private fun say(line: SpokenLine) {
        val context = appContext ?: return
        if (!SpokenEvents.isOn(context, SpokenEvent.CAR_TIPS)) return
        CarVoice.announce(line.text(AppLanguage.wrap(context).resources), InAppNav.locale(context))
    }

    private fun save(s: CareState, now: Long) {
        savedAt = now
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putString("drive", s.drive?.let { driveJson(it).toString() })
            ?.putString("last_drive", s.lastDrive?.let { driveJson(it).toString() })
            ?.putInt("short_streak", s.filter.shortStreak)
            ?.putLong("last_long_at", s.filter.lastLongAt)
            ?.putInt("warned_streak", s.filter.warnedStreak)
            ?.putLong("rest_ms", s.rest.drivingMs)
            ?.putLong("rest_at", s.rest.lastAt)
            ?.putInt("rest_spoken", s.rest.spokenMin)
            ?.apply()
    }

    // Only the totals survive a restart; the short-lived trackers start afresh.
    private fun driveJson(v: Drive) = JSONObject()
        .put("started", v.startedAt).put("last", v.lastAt).put("last_running", v.lastRunningAt)
        .put("running", v.runningMs).put("moving", v.movingMs).put("km", v.distanceKm)
        .put("hot_fast", v.hotFastMs).put("sweet", v.sweetMs).put("over_rev", v.overRevMs)
        .put("accel", v.hardAccel).put("brake", v.hardBrake).put("clutch", v.clutchHolds)
        .put("peak_coolant", v.peakCoolant).put("cold_warned", v.coldRevWarned)

    private fun driveFrom(o: JSONObject) = Drive(
        startedAt = o.getLong("started"), lastAt = o.getLong("last"), lastRunningAt = o.getLong("last_running"),
        runningMs = o.getLong("running"), movingMs = o.getLong("moving"), distanceKm = o.getDouble("km"),
        hotFastMs = o.getLong("hot_fast"), sweetMs = o.getLong("sweet"), overRevMs = o.getLong("over_rev"),
        hardAccel = o.getInt("accel"), hardBrake = o.getInt("brake"), clutchHolds = o.getInt("clutch"),
        peakCoolant = o.getInt("peak_coolant"), coldRevWarned = o.getBoolean("cold_warned")
    )
}
