package com.openauto.dash

import android.content.Context
import com.openauto.dash.vehicle.K
import com.openauto.dash.vehicle.Vehicle
import androidx.annotation.StringRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

/*
 * The sensor watch: what this car usually reads, learned drive after drive,
 * and a word when the last few drives drift away from it, before anything
 * fails hard enough to store a fault code. The battery resting lower than it
 * used to, a thermostat stuck open (the engine slow to warm, cooler on the
 * motorway), the idle hunting, the particle filter cleaning itself more and
 * more often. Plain statistics on the unit, so it works offline; Gemini only
 * says what may cause it, when there is a key.
 *
 * LiveWatch (AiMechanic.kt) speaks of a reading that is wrong now; this one
 * of a reading that is still fine but no longer the car's own.
 */

/** Which way a reading going is bad news. */
internal enum class BadWay { LOWER, HIGHER, EITHER }

/**
 * A reading the watch learns. [minShift] is the least change worth a word,
 * in the reading's own unit, or as a share of the usual value when
 * [relative]. [byWeather]: compared only with drives in similar outside
 * temperatures (a cold battery, a cold engine).
 */
internal enum class WatchSignal(
    @StringRes val labelRes: Int,
    val bad: BadWay,
    val minShift: Double,
    val relative: Boolean = false,
    val byWeather: Boolean = false
) {
    /** The battery with the ignition on, before the engine starts. */
    RESTING_VOLTS(R.string.watch_resting_volts, BadWay.LOWER, 0.25, byWeather = true),
    /** The alternator's voltage, engine running a few minutes. */
    CHARGING_VOLTS(R.string.watch_charging_volts, BadWay.EITHER, 0.35),
    /** Minutes of running for the coolant to go from 40 to 70 °C, from a cold start. */
    WARMUP_MIN(R.string.watch_warmup, BadWay.HIGHER, 0.35, relative = true, byWeather = true),
    /** The coolant once warm, at speed: a thermostat stuck open shows here first. */
    SETTLED_COOLANT(R.string.watch_settled_coolant, BadWay.EITHER, 5.0),
    /** The revs at idle with the engine warm. */
    IDLE_RPM(R.string.watch_idle_rpm, BadWay.EITHER, 90.0),
    /** Kilometres between two particle filter regenerations, as the engine computer counts them. */
    FILTER_KM(R.string.watch_filter_km, BadWay.LOWER, 0.3, relative = true)
}

/**
 * One drive's value of a reading. [tag] keeps apart what isn't comparable
 * (a voltage from the engine computer or from the adapter's own pin), and
 * [outsideC] is the weather it was read in, when known.
 */
internal data class WatchSample(val at: Long, val value: Double, val tag: Int = 0, val outsideC: Double? = null)

/** The last drives drifted from the car's usual. */
internal data class WatchFinding(val signal: WatchSignal, val usual: Double, val recent: Double, val at: Long) {
    val higher: Boolean get() = recent > usual
}

/** Where a reading stands, for the Sensor watch page. */
internal data class WatchStatus(
    val signal: WatchSignal,
    /** Drives learned so far, out of [DriftRules.MIN_BASE] + [DriftRules.RECENT] needed. */
    val learned: Int,
    val usual: Double?,
    val recent: Double?,
    val finding: WatchFinding?
)

/** The drift test, pure so it's unit-tested. */
internal object DriftRules {
    /** Drives the drift is judged on. */
    const val RECENT = 3
    /** Drives before them the usual is learned from, at least. */
    const val MIN_BASE = 8
    /** The usual is learned from this many drives at most, the latest. */
    const val MAX_BASE = 30
    /** A drive older than this is no longer "recent". */
    const val RECENT_MAX_AGE_MS = 30L * 24 * 3_600_000L
    /** How far apart in outside temperature two drives may be and still be compared, for [WatchSignal.byWeather]. */
    const val WEATHER_SPAN_C = 7.0
    /** The spread of the usual counts this many times over: a reading that wanders a lot needs a bigger change. */
    const val SPREAD_FACTOR = 3.0

    fun median(values: List<Double>): Double {
        val s = values.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }

    /** The median absolute deviation, scaled to a standard deviation for normal data. */
    fun spread(values: List<Double>): Double {
        val m = median(values)
        return median(values.map { abs(it - m) }) * 1.4826
    }

    /**
     * The drives a reading's usual is learned from: those before the [RECENT]
     * last, alike in kind (the same [WatchSample.tag]) and, for readings that
     * follow the weather, in outside temperature.
     */
    fun base(signal: WatchSignal, history: List<WatchSample>): Pair<List<WatchSample>, List<WatchSample>> {
        val last = history.lastOrNull() ?: return emptyList<WatchSample>() to emptyList()
        val alike = history.filter { it.tag == last.tag }
        val recent = alike.takeLast(RECENT)
        val before = alike.dropLast(RECENT)
        val weather = recent.mapNotNull { it.outsideC }.takeIf { it.size == recent.size }?.let(::median)
        val comparable = if (signal.byWeather && weather != null) {
            before.filter { s -> s.outsideC == null || abs(s.outsideC - weather) <= WEATHER_SPAN_C }
        } else {
            before
        }
        return comparable.takeLast(MAX_BASE) to recent
    }

    /** The usual and the recent value, while still learning too (either null until known). */
    fun status(signal: WatchSignal, history: List<WatchSample>, now: Long): WatchStatus {
        val (base, recent) = base(signal, history)
        val finding = check(signal, history, now)
        return WatchStatus(
            signal = signal,
            learned = (base.size + recent.size).coerceAtMost(MIN_BASE + RECENT),
            usual = base.takeIf { it.size >= MIN_BASE }?.let { median(it.map(WatchSample::value)) },
            recent = recent.takeIf { it.size == RECENT }?.let { median(it.map(WatchSample::value)) },
            finding = finding
        )
    }

    /**
     * Whether the [RECENT] last drives drifted from the usual: their median
     * past the least change worth a word ([WatchSignal.minShift], or the
     * usual's own spread three times over when that is bigger), the bad way,
     * and each of them at least half way there, so one odd drive among them
     * doesn't make it.
     */
    fun check(signal: WatchSignal, history: List<WatchSample>, now: Long): WatchFinding? {
        val (base, recent) = base(signal, history)
        if (base.size < MIN_BASE || recent.size < RECENT) return null
        if (now - recent.first().at > RECENT_MAX_AGE_MS) return null
        val usual = median(base.map(WatchSample::value))
        val values = recent.map(WatchSample::value)
        val latest = median(values)
        val least = if (signal.relative) abs(usual) * signal.minShift else signal.minShift
        val limit = maxOf(least, SPREAD_FACTOR * spread(base.map(WatchSample::value)))
        val shift = latest - usual
        val badWay = when (signal.bad) {
            BadWay.LOWER -> shift < 0
            BadWay.HIGHER -> shift > 0
            BadWay.EITHER -> true
        }
        if (!badWay || abs(shift) < limit) return null
        val sameWay = values.all { v -> (v - usual) * shift > 0 && abs(v - usual) >= limit / 2 }
        if (!sameWay) return null
        return WatchFinding(signal, usual, latest, now)
    }
}

/**
 * Reads one drive: from the first reading after a gap (the ignition, or the
 * adapter coming back) to the last before the next gap. Fed every OBD poll;
 * [finish] gives each reading the drive had enough of. Not thread-safe: the
 * OBD loop is its only caller.
 */
internal class DriveSampler(private val hotC: Int = LiveWatch.DEFAULT_HOT_C) {
    private var lastTaken: Long? = null
    private var startedAt: Long? = null
    private var engineStarted = false
    private var runningSince: Long? = null
    private var runningMs = 0L
    private var lastAt: Long? = null
    private var voltsFromEcu: Boolean? = null
    private var outside: Double? = null

    private val resting = ArrayList<Double>()
    private val charging = ArrayList<Double>()
    private val settled = ArrayList<Double>()
    private val idle = ArrayList<Double>()

    // A cold start: the running time the coolant passed 40 °C and then 70 °C.
    private var coldStart: Boolean? = null
    private var at40: Long? = null
    private var at70: Long? = null
    private var warmestC = 0

    val empty: Boolean get() = startedAt == null

    /** One reading at [now], [outsideC] the outside temperature when known. Taken once a second at most. */
    fun feed(d: ObdData, now: Long, outsideC: Double?) {
        if (startedAt == null) startedAt = now
        // Running time counts only between readings close together.
        val before = lastAt
        if (d.rpm > LiveWatch.RUNNING_RPM && before != null && now - before < GAP_MS) runningMs += now - before
        lastAt = now
        if (lastTaken.let { it != null && now - it < TAKE_MS }) return
        lastTaken = now
        if (outsideC != null) outside = outsideC
        val running = d.rpm > LiveWatch.RUNNING_RPM
        if (running && !engineStarted) engineStarted = true
        if (running) { if (runningSince == null) runningSince = now } else runningSince = null

        val v = d.voltage
        if (v in LiveWatch.MIN_PLAUSIBLE_V..LiveWatch.MAX_PLAUSIBLE_V) {
            // Volts from two sources aren't alike: the drive keeps the first one's.
            if (voltsFromEcu == null) voltsFromEcu = d.voltageFromEcu
            if (voltsFromEcu == d.voltageFromEcu) {
                if (!engineStarted && d.rpm == 0) add(resting, v)
                if (running && runningMs >= CHARGE_AFTER_MS) add(charging, v)
            }
        }

        val c = d.coolantTempC
        if (c in PLAUSIBLE_COOLANT) {
            if (coldStart == null) coldStart = c < COLD_C
            if (coldStart == true && running) {
                if (c >= COLD_C && at40 == null) at40 = runningMs
                if (c >= WARM_C && at70 == null) at70 = runningMs
                warmestC = maxOf(warmestC, c)
            }
            if (running && runningMs >= SETTLED_AFTER_MS && d.speedKmh >= SETTLED_KMH) add(settled, c.toDouble())
            if (running && d.speedKmh == 0 && c >= hotC - IDLE_WARM_MARGIN_C && d.throttlePct <= IDLE_THROTTLE_PCT) add(idle, d.rpm.toDouble())
        }
    }

    /** What the drive measured, by reading; a reading it had too little of is left out. */
    fun finish(): Map<WatchSignal, WatchSample> {
        val at = startedAt ?: return emptyMap()
        val tag = if (voltsFromEcu == true) TAG_ECU else TAG_PIN
        val out = LinkedHashMap<WatchSignal, WatchSample>()
        if (resting.size >= MIN_RESTING) out[WatchSignal.RESTING_VOLTS] = WatchSample(at, DriftRules.median(resting), tag, outside)
        if (charging.size >= MIN_CHARGING) out[WatchSignal.CHARGING_VOLTS] = WatchSample(at, DriftRules.median(charging), tag, outside)
        warmupMinutes()?.let { out[WatchSignal.WARMUP_MIN] = WatchSample(at, it, 0, outside) }
        if (settled.size >= MIN_SETTLED) out[WatchSignal.SETTLED_COOLANT] = WatchSample(at, DriftRules.median(settled), 0, outside)
        if (idle.size >= MIN_IDLE) out[WatchSignal.IDLE_RPM] = WatchSample(at, DriftRules.median(idle), 0, outside)
        return out
    }

    /**
     * Minutes of running from 40 to 70 °C. An engine that never got to 70
     * yet ran long enough is reckoned from how far it got: a thermostat stuck
     * open in winter may never get there at all.
     */
    private fun warmupMinutes(): Double? {
        val from = at40 ?: return null
        val to = at70
        if (to != null) return ((to - from) / 60_000.0).takeIf { it > 0.2 }
        val ran = runningMs - from
        val rose = warmestC - COLD_C
        if (ran < WARMUP_CENSOR_MS || rose <= 0) return null
        return (ran / 60_000.0) * (WARM_C - COLD_C) / rose
    }

    private fun add(list: ArrayList<Double>, v: Double) {
        // A long drive keeps every other value past the cap: the median is as good.
        if (list.size >= MAX_VALUES) {
            val kept = list.filterIndexed { i, _ -> i % 2 == 0 }
            list.clear()
            list.addAll(kept)
        }
        list.add(v)
    }

    companion object {
        const val TAKE_MS = 1_000L
        const val GAP_MS = 5_000L
        const val TAG_ECU = 1
        const val TAG_PIN = 2
        const val COLD_C = 40
        const val WARM_C = 70
        val PLAUSIBLE_COOLANT = -30..140
        const val CHARGE_AFTER_MS = 3 * 60_000L
        const val SETTLED_AFTER_MS = 10 * 60_000L
        const val SETTLED_KMH = 50
        const val IDLE_WARM_MARGIN_C = 10
        const val IDLE_THROTTLE_PCT = 2
        const val WARMUP_CENSOR_MS = 20 * 60_000L
        const val MIN_RESTING = 3
        const val MIN_CHARGING = 120
        const val MIN_SETTLED = 180
        const val MIN_IDLE = 30
        const val MAX_VALUES = 4_000
    }
}

/**
 * The particle filter's regenerations from the engine computer's "km since
 * the last one": it falls back near zero after each, and the distance it had
 * reached is one interval. Pure, so it's unit-tested.
 */
internal object FilterIntervals {
    /** A fall this big is a regeneration, not a misread. */
    const val RESET_KM = 50.0
    const val MIN_INTERVAL_KM = 50.0

    /** The interval ended by [km] after [before] (the highest seen since the last one), or null. */
    fun ended(before: Double?, km: Double): Double? {
        before ?: return null
        return before.takeIf { before - km >= RESET_KM && before >= MIN_INTERVAL_KM }
    }
}

/** The live side: samples each drive, keeps each reading's drives, and speaks when they drift. */
internal object SensorWatch {
    private const val PREFS = "sensor_watch"
    private const val KEY_HISTORY = "history"
    private const val KEY_FINDINGS = "findings"
    private const val KEY_FOUND = "found"
    private const val KEY_FILTER_KM = "filter_km"
    private const val KEY_CAUSES = "causes"
    private const val MAX_HISTORY = 40
    /** A finding said is said again only this long after, if it stands. */
    private const val REPEAT_MS = 14L * 24 * 3_600_000L
    /** The drive's end is noticed after this long without a reading. */
    private const val END_AFTER_MS = 60_000L
    /** A finding waits for the next drive's engine to have run this long before it is said. */
    private const val SAY_AFTER_RUNNING_MS = 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var appContext: Context? = null
    private var sampler: DriveSampler? = null
    private var lastFeed = 0L
    private var runningFrom: Long? = null
    /** One finding is said a drive at most: a second waits for the next. */
    private var saidThisDrive = false
    private var filterKmMax: Double? = null

    private val _history = MutableStateFlow<Map<WatchSignal, List<WatchSample>>>(emptyMap())
    val history: StateFlow<Map<WatchSignal, List<WatchSample>>> = _history.asStateFlow()

    private val _findings = MutableStateFlow<List<WatchFinding>>(emptyList())
    /** What drifted, standing now. */
    val findings: StateFlow<List<WatchFinding>> = _findings.asStateFlow()

    private val _causes = MutableStateFlow<Map<WatchSignal, String>>(emptyMap())
    /** The mechanic's word on each finding, when Gemini gave one. */
    val causes: StateFlow<Map<WatchSignal, String>> = _causes.asStateFlow()

    /** When each finding was last said. */
    private var said: Map<WatchSignal, Long> = emptyMap()

    fun setContext(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        load(context.applicationContext)
        scope.launch { endDrives() }
    }

    /** One OBD reading (the poll loop's thread). */
    @Synchronized
    fun watch(d: ObdData) {
        if (DemoMode.isOn) return
        val now = System.currentTimeMillis()
        if (lastFeed > 0 && now - lastFeed > DriveSampler.GAP_MS * 12) closeDrive()
        lastFeed = now
        val s = sampler ?: DriveSampler(CarProfileStore.current.hotC).also { sampler = it }
        s.feed(d, now, Vehicle.bus.now(K.OUTSIDE_TEMP) ?: WeatherRepo.weather.value?.tempC)
        followFilter(now)
        // Said once the engine has run a minute: not over the start-up briefing, nor to a car being left.
        if (d.rpm > LiveWatch.RUNNING_RPM) {
            val from = runningFrom ?: now.also { runningFrom = it }
            if (!saidThisDrive && now - from >= SAY_AFTER_RUNNING_MS) {
                saidThisDrive = true
                sayPending(now)
            }
        } else {
            runningFrom = null
        }
    }

    /** Readings stopped a while ago: the drive is over (the adapter goes quiet with the engine). */
    private suspend fun endDrives() {
        while (true) {
            delay(END_AFTER_MS / 2)
            synchronized(this) {
                if (lastFeed > 0 && System.currentTimeMillis() - lastFeed >= END_AFTER_MS) closeDrive()
            }
        }
    }

    private fun closeDrive() {
        val s = sampler ?: return
        sampler = null
        runningFrom = null
        saidThisDrive = false
        if (s.empty) return
        val measured = s.finish()
        if (measured.isEmpty()) return
        val next = _history.value.toMutableMap()
        measured.forEach { (signal, sample) -> next[signal] = (next[signal].orEmpty() + sample).takeLast(MAX_HISTORY) }
        _history.value = next
        judge(System.currentTimeMillis())
        save()
    }

    /** A regeneration ended an interval: kept like a drive's reading. */
    private fun followFilter(now: Long) {
        val km = PidExplorer.readings.value[ExtraReading.KM_SINCE_REGEN]?.value ?: return
        val ended = FilterIntervals.ended(filterKmMax, km)
        filterKmMax = if (ended != null) km else maxOf(filterKmMax ?: km, km)
        if (ended != null) {
            val next = _history.value.toMutableMap()
            next[WatchSignal.FILTER_KM] = (next[WatchSignal.FILTER_KM].orEmpty() + WatchSample(now, ended)).takeLast(MAX_HISTORY)
            _history.value = next
            judge(now)
            save()
        }
    }

    /** Each reading checked again: a drift that came back to normal is dropped, a new one asked about. */
    private fun judge(now: Long) {
        val before = _findings.value.associateBy { it.signal }
        val found = WatchSignal.entries.mapNotNull { signal ->
            val fresh = DriftRules.check(signal, _history.value[signal].orEmpty(), now) ?: return@mapNotNull null
            // A standing finding keeps the day it was first made.
            before[signal]?.let { fresh.copy(at = it.at) } ?: fresh
        }
        _findings.value = found
        val gone = before.keys - found.map { it.signal }.toSet()
        if (gone.isNotEmpty()) _causes.value = _causes.value - gone
        found.filter { it.signal !in before }.forEach(::askMechanic)
    }

    /** The first finding not said lately, said; the others wait for the next drives. */
    private fun sayPending(now: Long) {
        val context = appContext ?: return
        val due = _findings.value.firstOrNull { f -> said[f.signal]?.let { now - it >= REPEAT_MS } ?: true } ?: return
        said = said + (due.signal to now)
        save()
        if (!SpokenEvents.isOn(context, SpokenEvent.CAR_TIPS)) return
        val locale = InAppNav.locale(context)
        val resources = AppLanguage.wrap(context).resources
        val line = WatchLines.spoken(due, AiLanguage.of(locale), Units.current.value)
        CarVoice.announce(line.text(resources), locale)
    }

    /** Gemini's word on what may cause [finding] on this car, kept with it. */
    private fun askMechanic(finding: WatchFinding) {
        val context = appContext ?: return
        scope.launch {
            val config = AiSettings.load(context)
            if (config.apiKey.isBlank()) return@launch
            val car = CarProfileStore.current
            val prompt = WatchLines.prompt(finding, car.promptDescription(), config.language)
            GeminiClient.generate(config.apiKey, prompt).onSuccess { reply ->
                val text = reply.text.trim()
                if (text.isNotEmpty()) {
                    _causes.value = _causes.value + (finding.signal to text)
                    save()
                }
            }
        }
    }

    /** Asks again for every standing finding (a key added, the language changed). */
    fun refreshCauses() {
        _causes.value = emptyMap()
        _findings.value.forEach(::askMechanic)
    }

    /** Starts learning again from nothing: after a new battery, a thermostat replaced. */
    fun forget(signal: WatchSignal) {
        synchronized(this) {
            _history.value = _history.value - signal
            _findings.value = _findings.value.filter { it.signal != signal }
            _causes.value = _causes.value - signal
            said = said - signal
            save()
        }
    }

    fun statuses(now: Long = System.currentTimeMillis()): List<WatchStatus> =
        WatchSignal.entries.map { DriftRules.status(it, _history.value[it].orEmpty(), now) }

    /** For Dashwheel's Gemini: each reading's usual and recent value, and what drifted. */
    fun facts(): String? {
        val known = statuses().filter { it.usual != null }
        if (known.isEmpty()) return null
        return known.joinToString("; ") { s ->
            val drift = s.finding?.let { if (it.higher) ", DRIFTED HIGHER" else ", DRIFTED LOWER" }.orEmpty()
            String.format(Locale.ROOT, "%s usual=%.2f recent=%s%s", s.signal.name.lowercase(), s.usual, s.recent?.let { "%.2f".format(Locale.ROOT, it) } ?: "?", drift)
        }
    }

    private fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _history.value = runCatching { WatchStore.decodeHistory(JSONObject(p.getString(KEY_HISTORY, null) ?: "{}")) }.getOrDefault(emptyMap())
        said = runCatching {
            val o = JSONObject(p.getString(KEY_FINDINGS, null) ?: "{}")
            o.keys().asSequence().mapNotNull { k -> WatchSignal.entries.firstOrNull { it.name == k }?.let { it to o.getLong(k) } }.toMap()
        }.getOrDefault(emptyMap())
        _causes.value = runCatching {
            val o = JSONObject(p.getString(KEY_CAUSES, null) ?: "{}")
            o.keys().asSequence().mapNotNull { k -> WatchSignal.entries.firstOrNull { it.name == k }?.let { it to o.getString(k) } }.toMap()
        }.getOrDefault(emptyMap())
        filterKmMax = p.getFloat(KEY_FILTER_KM, -1f).takeIf { it >= 0 }?.toDouble()
        // When each standing finding was first made: the phone's notice for it stays the same one.
        val found = runCatching {
            val o = JSONObject(p.getString(KEY_FOUND, null) ?: "{}")
            o.keys().asSequence().mapNotNull { k -> WatchSignal.entries.firstOrNull { it.name == k }?.let { it to o.getLong(k) } }.toMap()
        }.getOrDefault(emptyMap())
        val now = System.currentTimeMillis()
        _findings.value = WatchSignal.entries.mapNotNull { s ->
            DriftRules.check(s, _history.value[s].orEmpty(), now)?.let { f -> found[s]?.let { f.copy(at = it) } ?: f }
        }
        _causes.value = _causes.value.filterKeys { k -> _findings.value.any { it.signal == k } }
    }

    private fun save() {
        val context = appContext ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_HISTORY, WatchStore.encodeHistory(_history.value).toString())
            .putString(KEY_FINDINGS, JSONObject().apply { said.forEach { (k, v) -> put(k.name, v) } }.toString())
            .putString(KEY_CAUSES, JSONObject().apply { _causes.value.forEach { (k, v) -> put(k.name, v) } }.toString())
            .putString(KEY_FOUND, JSONObject().apply { _findings.value.forEach { put(it.signal.name, it.at) } }.toString())
            .apply { filterKmMax?.let { putFloat(KEY_FILTER_KM, it.toFloat()) } ?: remove(KEY_FILTER_KM) }
            .apply()
    }
}

/** The history as saved: by reading, each drive as [at, value, tag, outside or null]. */
internal object WatchStore {
    fun encodeHistory(history: Map<WatchSignal, List<WatchSample>>): JSONObject = JSONObject().apply {
        history.forEach { (signal, samples) ->
            put(signal.name, JSONArray().apply {
                samples.forEach { s -> put(JSONArray().put(s.at).put(s.value).put(s.tag).put(s.outsideC ?: JSONObject.NULL)) }
            })
        }
    }

    fun decodeHistory(o: JSONObject): Map<WatchSignal, List<WatchSample>> =
        o.keys().asSequence().mapNotNull { key ->
            val signal = WatchSignal.entries.firstOrNull { it.name == key } ?: return@mapNotNull null
            val a = o.optJSONArray(key) ?: return@mapNotNull null
            signal to (0 until a.length()).mapNotNull { i ->
                val e = a.optJSONArray(i) ?: return@mapNotNull null
                val value = e.optDouble(1)
                if (value.isNaN()) return@mapNotNull null
                WatchSample(e.optLong(0), value, e.optInt(2), if (e.isNull(3)) null else e.optDouble(3).takeIf { !it.isNaN() })
            }
        }.toMap()
}

/** What is said and shown about a drift, and what Gemini is asked about it. */
internal object WatchLines {

    /** [value] of [signal] as shown and said: "12.3 V", "8 min", "92 °C", "830 rpm", "410 km". */
    fun value(signal: WatchSignal, value: Double, locale: Locale, units: UnitSystem): String = when (signal) {
        WatchSignal.RESTING_VOLTS, WatchSignal.CHARGING_VOLTS -> String.format(locale, "%.1f V", value)
        WatchSignal.WARMUP_MIN -> String.format(locale, "%.0f min", value)
        WatchSignal.SETTLED_COOLANT -> units.tempText(value)
        WatchSignal.IDLE_RPM -> String.format(locale, "%.0f rpm", value)
        WatchSignal.FILTER_KM -> String.format(locale, "%.0f %s", units.distance(value), units.distanceUnit)
    }

    /** [value] as the voice says it: the number alone, the unit is a word in the sentence. */
    fun number(signal: WatchSignal, value: Double, locale: Locale, units: UnitSystem): String = when (signal) {
        WatchSignal.RESTING_VOLTS, WatchSignal.CHARGING_VOLTS -> String.format(locale, "%.1f", value)
        WatchSignal.SETTLED_COOLANT -> units.temp(value).toString()
        WatchSignal.FILTER_KM -> String.format(locale, "%.0f", units.distance(value))
        WatchSignal.WARMUP_MIN, WatchSignal.IDLE_RPM -> String.format(locale, "%.0f", value)
    }

    /** The sentence the voice says, the values written the voice's way ("12,2" in French). */
    fun spoken(f: WatchFinding, language: AiLanguage, units: UnitSystem): SpokenLine {
        val usual = number(f.signal, f.usual, language.locale, units)
        val recent = number(f.signal, f.recent, language.locale, units)
        val res = when (f.signal) {
            WatchSignal.RESTING_VOLTS -> R.string.watch_say_resting_low
            WatchSignal.CHARGING_VOLTS -> if (f.higher) R.string.watch_say_charging_high else R.string.watch_say_charging_low
            WatchSignal.WARMUP_MIN -> R.string.watch_say_warmup_slow
            WatchSignal.SETTLED_COOLANT -> if (f.higher) R.string.watch_say_coolant_high else R.string.watch_say_coolant_low
            WatchSignal.IDLE_RPM -> if (f.higher) R.string.watch_say_idle_high else R.string.watch_say_idle_low
            WatchSignal.FILTER_KM -> R.string.watch_say_filter_often
        }
        if (f.signal == WatchSignal.FILTER_KM) {
            val unit = SpokenLine(if (units.imperial) R.string.watch_unit_miles else R.string.watch_unit_km, emptyList())
            return SpokenLine(res, listOf(recent, usual, unit))
        }
        return SpokenLine(res, listOf(recent, usual))
    }

    /** The question for Gemini: plain text, a few short lines for the car's screen and the phone. */
    fun prompt(f: WatchFinding, car: String, language: AiLanguage): String = buildString {
        appendLine(MechanicPersona.of(car))
        val what = when (f.signal) {
            WatchSignal.RESTING_VOLTS -> "battery voltage with the ignition on, before starting the engine"
            WatchSignal.CHARGING_VOLTS -> "charging voltage with the engine running"
            WatchSignal.WARMUP_MIN -> "minutes of running for the coolant to warm from 40 to 70 °C after a cold start"
            WatchSignal.SETTLED_COOLANT -> "coolant temperature once warm, driving at 50 km/h or more"
            WatchSignal.IDLE_RPM -> "idle speed in rpm with the engine warm"
            WatchSignal.FILTER_KM -> "kilometres between two particulate filter regenerations"
        }
        appendLine(String.format(Locale.ROOT, "Over its last few drives, its %s has moved from a usual %.2f to %.2f. No fault code is stored.", what, f.usual, f.recent))
        appendLine("Answer in ${language.promptName}, plain text, no markdown, at most 4 short lines:")
        appendLine("the 2 or 3 most likely causes on this engine, most likely first, then the one cheapest thing to check first, and whether it can wait.")
        append("Be concrete and specific to this car. If such a change is usually harmless, say so.")
    }
}
