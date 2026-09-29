package com.openauto.dash

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.util.Locale
import javax.net.ssl.SSLException

/**
 * Language the mechanic writes and speaks in. [label] is the language's own
 * name (for the picker), [promptName] its English name (for Gemini), [locale]
 * the voice and the number format. Entry names are persisted.
 */
enum class AiLanguage(val label: String, val promptName: String, val locale: Locale) {
    ENGLISH("English", "English", Locale.UK),
    FRENCH("Français", "French", Locale.FRANCE),
    GERMAN("Deutsch", "German", Locale.GERMANY),
    SPANISH("Español", "Spanish", Locale("es", "ES")),
    ITALIAN("Italiano", "Italian", Locale.ITALY),
    PORTUGUESE("Português", "Portuguese", Locale("pt", "PT")),
    DUTCH("Nederlands", "Dutch", Locale("nl", "NL")),
    POLISH("Polski", "Polish", Locale("pl", "PL"));

    /**
     * The app's strings in this language, whatever the launcher's own: spoken
     * lines must match the voice. Inside `Configuration().apply {}` a bare
     * `locale` is the Configuration's own (deprecated) field, which left the
     * strings in the app's language: English words read by a French voice.
     */
    fun resources(context: Context): Resources {
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config).resources
    }

    companion object {
        /** The language matching [locale], English when the mechanic doesn't speak it. */
        fun of(locale: Locale): AiLanguage = entries.firstOrNull { it.locale.language == locale.language } ?: ENGLISH
    }
}

data class AiConfig(
    val apiKey: String = "",
    /** The key was unlocked with the activation code, so it isn't shown on screen. */
    val keyFromCode: Boolean = false,
    /** The driver's pick; null follows the launcher's language. */
    val languageChoice: AiLanguage? = null,
    val speak: Boolean = true,
    /** Say the start-up briefing when the car starts. */
    val briefing: Boolean = true
) {
    /** The language actually used: the pick, else the launcher's current one. */
    val language: AiLanguage get() = languageChoice ?: AiLanguage.of(Locale.getDefault())
}

object AiSettings {
    private const val PREFS = "ai_prefs"

    fun load(context: Context): AiConfig {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return AiConfig(
            apiKey = p.getString("api_key", "").orEmpty(),
            keyFromCode = p.getBoolean("key_from_code", false),
            languageChoice = p.getString("language", null)?.let { runCatching { AiLanguage.valueOf(it) }.getOrNull() },
            speak = p.getBoolean("speak", true),
            briefing = p.getBoolean("briefing", true)
        )
    }

    fun save(context: Context, config: AiConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("api_key", config.apiKey.trim())
            .putBoolean("key_from_code", config.keyFromCode)
            .apply { config.languageChoice?.let { putString("language", it.name) } ?: remove("language") }
            .putBoolean("speak", config.speak)
            .putBoolean("briefing", config.briefing)
            .apply()
    }
}

/** Builds the question for Gemini and reads its answer back. Pure, so it's unit-tested. */
object MechanicPrompt {

    /**
     * JSON shape Gemini must answer in (Gemini's OpenAPI-style schema): exactly
     * one entry per code asked, and only those codes. Without that, a busy
     * model once renamed P1352 to P1351 and invented a second fault.
     */
    fun schema(codes: List<String>): JSONObject {
        fun str() = JSONObject().put("type", "STRING")
        fun list() = JSONObject().put("type", "ARRAY").put("items", str())
        val adviceFields = listOf(
            "code" to str().put("enum", JSONArray(codes)), "meaning" to str(), "explanation" to str(), "symptoms" to list(),
            "causes" to list(), "check_first" to str(), "checks" to list(), "repair" to str(),
            "cost" to str(), "diy" to str(), "driving" to str()
        )
        val advice = JSONObject().put("type", "OBJECT")
            .put("properties", JSONObject().apply { adviceFields.forEach { (k, v) -> put(k, v) } })
            .put("required", JSONArray(adviceFields.map { it.first }))
        return JSONObject().put("type", "OBJECT").put(
            "properties",
            JSONObject()
                .put("severity", str().put("enum", JSONArray(listOf("ok", "soon", "stop"))))
                .put("summary", str())
                .put("overview", str())
                .put(
                    "codes",
                    JSONObject().put("type", "ARRAY").put("items", advice)
                        .put("minItems", codes.size).put("maxItems", codes.size)
                )
        ).put("required", JSONArray(listOf("severity", "summary", "overview", "codes")))
    }

    /**
     * [car] names the car, engine and gearbox ([CarProfile.promptDescription]).
     * [references] are the generic meanings the built-in table gives for some
     * of the codes ([ObdCodes.tableTitle]): something to check against, so a
     * code isn't read as another; [currency] is the driver's own.
     */
    fun build(
        codes: List<String>,
        car: String,
        language: AiLanguage,
        data: ObdData?,
        references: Map<String, String> = emptyMap(),
        currency: String = "€"
    ): String = buildString {
        appendLine(MechanicPersona.of(car))
        appendLine("Its OBD scan reports these stored fault codes: ${codes.joinToString(", ")}.")
        if (references.isNotEmpty()) {
            appendLine("Generic meanings from the car's built-in code table (the maker's own meaning wins where it differs): " +
                references.entries.joinToString("; ") { (code, meaning) -> "$code = $meaning" } + ".")
        }
        readings(data)?.let { appendLine("Live readings at the time of the scan: $it.") }
        appendLine()
        appendLine("Answer in ${language.promptName}, with correct spelling and all accents. The driver reads the details parked, on the car's screen: be concrete, practical and specific to this engine.")
        appendLine("- severity: \"ok\" = fine to keep driving normally; \"soon\" = drive gently and get it checked within days; \"stop\" = stop driving, risk of damage or danger.")
        appendLine("- summary: ONE short sentence that will be spoken aloud to the driver: the problem in plain words and what to do. No code numbers, no jargon.")
        appendLine("- overview: 2 or 3 sentences: what is going on overall and, with several codes, how they relate.")
        appendLine("- codes: one entry per code, in the same order, each with:")
        appendLine("  - meaning: a short title, at most 8 words.")
        appendLine("  - explanation: 2 to 4 sentences: what this part or system does on this engine and what the fault means.")
        appendLine("  - symptoms: 2 to 4 things the driver may notice.")
        appendLine("  - causes: the 3 or 4 most likely causes on this engine, most likely first.")
        appendLine("  - check_first: the single cheapest, simplest thing to check first.")
        appendLine("  - checks: 3 to 5 diagnostic steps in order, cheapest and simplest first.")
        appendLine("  - repair: the usual fix and the part involved.")
        appendLine("  - cost: a rough range in the driver's currency ($currency) at an independent garage, parts and labour, and what the part costs alone.")
        appendLine("  - diy: whether a home mechanic can do it: how hard it is (easy, medium or hard, said in that language) and the tools needed.")
        appendLine("  - driving: whether the car can still be driven, and what happens if the fault is ignored.")
        appendLine("Consider the codes together and with the readings: several codes often share one cause. Discuss only these codes, exactly as written: do not assume or add any other fault.")
        append("Where you are not sure what a code means on this car, say so plainly and rate it \"soon\" rather than guess; never call a fault harmless without good grounds.")
    }

    /** Live values worth sending; zeros mean "not reported" and are left out. */
    private fun readings(d: ObdData?): String? {
        d ?: return null
        val parts = buildList {
            add(if (d.rpm > 0) "engine running at ${d.rpm} rpm" else "engine not running")
            if (d.rpm > 0) add("speed ${d.speedKmh} km/h")
            if (d.coolantTempC != 0) add("coolant ${d.coolantTempC} °C")
            if (d.intakeTempC != 0) add("intake air ${d.intakeTempC} °C")
            if (d.rpm > 0) add("engine load ${d.engineLoadPct} %")
            if (d.voltage > 0.0) add(String.format(Locale.US, "battery %.1f V", d.voltage))
        }
        return parts.joinToString(", ")
    }

    /** Reads Gemini's JSON (tolerating code fences and missing fields); null when unusable. */
    fun parse(text: String): Diagnosis? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val json = runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull() ?: return null
        val summary = json.optString("summary").trim()
        if (summary.isEmpty()) return null
        val severity = when (json.optString("severity").trim().lowercase()) {
            "ok" -> Severity.OK
            "stop" -> Severity.STOP
            else -> Severity.SOON
        }
        val list = json.optJSONArray("codes") ?: JSONArray()
        val codes = (0 until list.length()).mapNotNull { i ->
            val o = list.optJSONObject(i) ?: return@mapNotNull null
            val code = o.optString("code").trim().uppercase()
            if (code.isEmpty()) return@mapNotNull null
            fun text(key: String) = o.optString(key).trim()
            fun lines(key: String) = o.optJSONArray(key)
                ?.let { a -> (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() } }
                .orEmpty()
            CodeAdvice(
                code = code,
                meaning = text("meaning"),
                causes = lines("causes"),
                checkFirst = text("check_first"),
                explanation = text("explanation"),
                symptoms = lines("symptoms"),
                checks = lines("checks"),
                repair = text("repair"),
                cost = text("cost"),
                diy = text("diy"),
                driving = text("driving")
            )
        }
        return Diagnosis(severity, summary, codes, json.optString("overview").trim())
    }
}

/**
 * Plain rules on the live readings (no AI, so they work offline). Each warning
 * is said once, then stays quiet until the reading has recovered, so a flaky
 * adapter reconnecting mid-drive doesn't repeat it.
 */
internal class LiveWatch {
    enum class Alert { OVERHEAT, NOT_CHARGING, WEAK_BATTERY, OVERCHARGING }

    private var overheatArmed = true
    private var chargeArmed = true
    private var batteryArmed = true
    private var overchargeArmed = true
    // When the current low (or high) stretch began; null while the reading is fine.
    private var lowChargeSince: Long? = null
    private var weakSince: Long? = null
    private var highSince: Long? = null
    private var lastSample: Long? = null

    /**
     * When a weak battery was last said (the [check] clock), 0 if never. Kept
     * by the caller across restarts: a battery that is weak at every morning's
     * ignition is said once a day, not at every start.
     */
    var weakSaidAt = 0L

    /**
     * [hotC]: the coolant temperature this engine runs at once warm ([CarProfile.hotC]).
     * A voltage from the adapter's own pin rather than the engine computer
     * ([ObdData.voltageFromEcu]) reads low by a diode's drop, so its limits
     * are [ADAPTER_MARGIN_V] lower: a healthy battery must not sound weak.
     */
    fun check(d: ObdData, now: Long, hotC: Int = DEFAULT_HOT_C): Alert? {
        // Timers only count uninterrupted readings; a gap (adapter dropped) restarts them.
        if (lastSample.let { it == null || now - it > GAP_MS }) {
            lowChargeSince = null
            weakSince = null
            highSince = null
        }
        lastSample = now

        // Judged against this engine's own temperature: a 1.6 HDi runs about 90 °C, its fan cuts in near 100 °C.
        if (d.coolantTempC in 1 until Overheat.clearC(hotC)) overheatArmed = true
        if (overheatArmed && d.coolantTempC >= Overheat.alarmC(hotC)) {
            overheatArmed = false
            return Alert.OVERHEAT
        }

        // Clone adapters can report nonsense volts; only trust a plausible bus voltage.
        val v = d.voltage
        if (v < MIN_PLAUSIBLE_V || v > MAX_PLAUSIBLE_V) return null
        val running = d.rpm > RUNNING_RPM
        val margin = if (d.voltageFromEcu) 0.0 else ADAPTER_MARGIN_V

        // The alternator should hold ~14 V; the e-HDi's smart charging dips lower,
        // so only a long stretch well under 13 V counts.
        if (running && v >= CHARGE_CLEAR_V - margin) chargeArmed = true
        if (running && v < NOT_CHARGING_V - margin) {
            val since = lowChargeSince ?: now.also { lowChargeSince = it }
            if (chargeArmed && now - since >= NOT_CHARGING_MS) {
                chargeArmed = false
                return Alert.NOT_CHARGING
            }
        } else {
            lowChargeSince = null
        }

        // A failed regulator boils the battery: over 15.5 V for half a minute with
        // the engine running. Only the engine computer's reading counts, since
        // clone adapters read their own pin high (16.9 V on a 14.5 V bus).
        if (running && d.voltageFromEcu && v <= OVERCHARGE_CLEAR_V) overchargeArmed = true
        if (running && d.voltageFromEcu && v > OVERCHARGE_V) {
            val since = highSince ?: now.also { highSince = it }
            if (overchargeArmed && now - since >= OVERCHARGE_MS) {
                overchargeArmed = false
                return Alert.OVERCHARGING
            }
        } else {
            highSince = null
        }

        // Ignition on, engine off: a healthy battery rests above 12.4 V. Sustained
        // for a minute, so neither cranking nor the glow plugs and blower at
        // ignition-on are taken for a weak battery; then once a day at most.
        if (v >= BATTERY_CLEAR_V - margin) batteryArmed = true
        if (d.rpm == 0 && v < WEAK_BATTERY_V - margin) {
            val since = weakSince ?: now.also { weakSince = it }
            val saidToday = weakSaidAt > 0 && now - weakSaidAt in 0 until WEAK_REPEAT_MS
            if (batteryArmed && !saidToday && now - since >= WEAK_BATTERY_MS) {
                batteryArmed = false
                weakSaidAt = now
                return Alert.WEAK_BATTERY
            }
        } else {
            weakSince = null
        }
        return null
    }

    companion object {
        /** What an engine runs at when its profile doesn't say. */
        const val DEFAULT_HOT_C = 90
        const val RUNNING_RPM = 500
        const val NOT_CHARGING_V = 12.5
        const val CHARGE_CLEAR_V = 13.2
        const val NOT_CHARGING_MS = 120_000L
        const val WEAK_BATTERY_V = 12.0
        const val BATTERY_CLEAR_V = 12.4
        const val WEAK_BATTERY_MS = 60_000L
        /** A weak battery is said again only this long after (it was, a day ago). */
        const val WEAK_REPEAT_MS = 20 * 3_600_000L
        /** How much lower the adapter's own voltage reads than the engine computer's. */
        const val ADAPTER_MARGIN_V = 0.4
        /** The bar's red limit ([BatteryJudge.CRITICAL_HIGH_V]), held this long. */
        const val OVERCHARGE_V = 15.5
        const val OVERCHARGE_CLEAR_V = 14.8
        const val OVERCHARGE_MS = 30_000L
        const val MIN_PLAUSIBLE_V = 9.0
        const val MAX_PLAUSIBLE_V = 16.0
        const val GAP_MS = 5_000L
    }
}

/**
 * A sentence to say: a string (or, with [quantity], a plurals) resource and its
 * arguments. Kept apart from the text so the choice is testable on the JVM;
 * [text] resolves it with [AiLanguage.resources] so it matches the voice.
 */
internal data class SpokenLine(val res: Int, val args: List<Any>, val quantity: Int? = null) {
    fun text(resources: Resources): String {
        // An argument can be a line itself (a weather condition), said in the same language.
        val a = args.map { if (it is SpokenLine) it.text(resources) else it }.toTypedArray()
        return if (quantity != null) resources.getQuantityString(res, quantity, *a) else resources.getString(res, *a)
    }
}

/**
 * Scans again once the engine has been running a while, when the last scan
 * was made with it stopped. The adapter usually connects at ignition-on,
 * before the engine starts: the dashboard lamps are then only self-testing,
 * and some faults are only judged with the engine running.
 */
internal class RescanAfterStart {
    // Told of scans on the IO threads, fed readings on the OBD collector's: both synchronized.
    private var pending = false
    private var runningSince: Long? = null

    /** A scan just finished at [rpm]. */
    @Synchronized
    fun scanned(rpm: Int) {
        pending = rpm == 0
        runningSince = null
    }

    /** True once, when the engine has run [RUNNING_MS] since a scan made with it stopped. */
    @Synchronized
    fun due(rpm: Int, now: Long): Boolean {
        if (!pending) return false
        if (rpm <= LiveWatch.RUNNING_RPM) {
            runningSince = null
            return false
        }
        val since = runningSince ?: now.also { runningSince = it }
        if (now - since < RUNNING_MS) return false
        pending = false
        return true
    }

    companion object {
        const val RUNNING_MS = 20_000L
    }
}

/** Sentences the car says without the AI (offline, no key, or live-reading warnings). */
internal object MechanicLines {

    fun newCodes(codes: List<String>): SpokenLine {
        // Spaced out so the voice reads "P 0 1 2 8", not "P one hundred twenty-eight".
        val spoken = codes.joinToString(", ") { it.toCharArray().joinToString(" ") }
        return SpokenLine(R.plurals.ai_say_new_codes, listOf(codes.size, spoken), quantity = codes.size)
    }

    /** The rules' own sentence when they rate a fault more serious than the AI did ([SeverityFloor]). */
    fun ruleVerdict(severity: Severity): SpokenLine =
        SpokenLine(if (severity == Severity.STOP) R.string.ai_rules_stop else R.string.ai_rules_soon, emptyList())

    fun alert(alert: LiveWatch.Alert, d: ObdData, language: AiLanguage, units: UnitSystem = Units.current.value): SpokenLine {
        // Written the way the voice's language writes it: "12,1" in French.
        val volts = String.format(language.locale, "%.1f", d.voltage)
        return when (alert) {
            // "Degrees" either way: the number follows the driver's scale.
            LiveWatch.Alert.OVERHEAT -> SpokenLine(R.string.ai_say_overheat, listOf(units.temp(d.coolantTempC)))
            LiveWatch.Alert.NOT_CHARGING -> SpokenLine(R.string.ai_say_not_charging, listOf(volts))
            LiveWatch.Alert.WEAK_BATTERY -> SpokenLine(R.string.ai_say_weak_battery, listOf(volts))
            LiveWatch.Alert.OVERCHARGING -> SpokenLine(R.string.ai_say_overcharging, listOf(volts))
        }
    }
}

/** Gemini answered, but not in the requested shape. */
internal class UnreadableAnswerException : Exception("Gemini's answer was unreadable")

/**
 * The AI mechanic. When the OBD link comes up it scans for fault codes by
 * itself; codes it hasn't seen before get explained by Gemini and announced in
 * one spoken sentence, with the full advice on the Fault codes tile. Live
 * readings are watched for overheating and charging problems.
 *
 * Only the codes, the engine readings and the car model leave the car.
 */
object AiMechanic {

    data class State(
        /** Codes from the last scan; null until one has run. */
        val codes: List<String>? = null,
        val diagnosis: Diagnosis? = null,
        val thinking: Boolean = false,
        /** Why there's no AI advice (no key, offline, refused). */
        val note: Note? = null,
        /** The request failed in a way asking again might fix. */
        val canRetry: Boolean = false
    )

    /** Why a tile shows no AI advice; turned into text when shown, in the launcher's language. */
    sealed interface Note {
        /** No Gemini key: only the built-in table's explanations. */
        data object NoKey : Note
        /** Asking Gemini failed because of [error]. */
        data class Unavailable(val error: Throwable) : Note
    }

    private const val PREFS = "ai_mechanic"
    private const val KEY_KNOWN = "known_codes"

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _newFaultAt = MutableStateFlow(0L)

    /** When a scan last found a code the car had not reported before; 0 until one does. The Canvas tabs follow it. */
    val newFaultAt: StateFlow<Long> = _newFaultAt.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Scan results can come from the auto-scan and the Scan button at once.
    private val mutex = Mutex()
    private val liveWatch = LiveWatch()
    private val rescan = RescanAfterStart()
    private var appContext: Context? = null

    fun setContext(context: Context) {
        appContext = context.applicationContext
        CarVoice.setContext(context)
        liveWatch.weakSaidAt = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_WEAK_SAID, 0L)
    }

    private const val KEY_WEAK_SAID = "weak_battery_said_at"

    /** [DemoMode]'s fault codes (and, when it ends, the real ones back). */
    internal fun demoWrite(state: State) {
        _state.value = state
    }

    /** Called when the OBD link comes up: scans without being asked. */
    suspend fun autoScan() {
        ObdBluetoothManager.readTroubleCodes().onSuccess { report(it, announce = true) }
    }

    /**
     * New scan results. Codes not heard before are announced when [announce];
     * the explanation always lands on the tile.
     */
    fun report(codes: List<String>, announce: Boolean) {
        // Demo codes are shown as scanned with canned advice, never remembered, asked of Gemini or spoken.
        if (DemoMode.isOn) {
            if (codes.any { it !in _state.value.codes.orEmpty() }) _newFaultAt.value = System.currentTimeMillis()
            _state.value = DemoMode.mechanicState(codes)
            return
        }
        scope.launch {
            mutex.withLock {
                val context = appContext ?: return@withLock
                val list = codes.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct()
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val known = prefs.getString(KEY_KNOWN, "").orEmpty().split(',').filter { it.isNotEmpty() }.toSet()
                // Remember exactly the current codes: one that is repaired and comes back is news again.
                prefs.edit().putString(KEY_KNOWN, list.joinToString(",")).apply()
                rescan.scanned(ObdBluetoothManager.data.value.rpm)
                _state.value = State(codes = list)
                if (list.any { it !in known }) _newFaultAt.value = System.currentTimeMillis()
                if (list.isNotEmpty()) explain(context, list, fresh = if (announce) list.filter { it !in known } else emptyList())
            }
        }
    }

    /** Codes were cleared from the car: forget them. */
    fun cleared() {
        if (DemoMode.isOn) return
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.remove(KEY_KNOWN)?.apply()
        _state.value = State(codes = emptyList())
    }

    /** Asks again for the current codes (after adding a key, changing language, or coming back online). */
    fun refresh() {
        val codes = _state.value.codes ?: return
        if (codes.isEmpty()) return
        scope.launch {
            mutex.withLock {
                val context = appContext ?: return@withLock
                explain(context, codes, fresh = emptyList())
            }
        }
    }

    /** The language the advice on the tile was asked in; null while there's none. */
    @Volatile private var explainedIn: AiLanguage? = null

    /**
     * The launcher's language may have changed (the screen was rebuilt in it):
     * the advice already on the tile was written by Gemini in the old one, so
     * it is asked again (or read from the cache) in the new one. The demo's
     * canned advice is rebuilt the same way.
     */
    fun followLanguage(context: Context) {
        val language = AiSettings.load(context).language
        if (DemoMode.isOn) {
            DemoMode.followLanguage(context, language)
            return
        }
        val before = explainedIn ?: return
        if (before != language) refresh()
    }

    /** Checks one set of live readings against the warning rules, and rescans once the engine runs. */
    fun watch(data: ObdData) {
        if (DemoMode.isOn) return
        if (rescan.due(data.rpm, System.currentTimeMillis())) scope.launch { autoScan() }
        val alert = liveWatch.check(data, System.currentTimeMillis(), CarProfileStore.current.hotC) ?: return
        val context = appContext ?: return
        if (alert == LiveWatch.Alert.WEAK_BATTERY) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_WEAK_SAID, liveWatch.weakSaidAt).apply()
            // Also for the phone: the driver is about to walk away from a car that may not start next time.
            CarNews.weakBattery(data.voltage)
        }
        val config = AiSettings.load(context)
        if (!config.speak) return
        val line = MechanicLines.alert(alert, data, config.language)
        // Overheating or no longer charging can't wait behind a briefing; a weak battery at rest can.
        val urgent = alert != LiveWatch.Alert.WEAK_BATTERY
        CarVoice.announce(line.text(config.language.resources(context)), config.language.locale, urgent)
    }

    /** Fills in the advice for [codes]; speaks only when some are [fresh] (new and to be announced). */
    private suspend fun explain(context: Context, codes: List<String>, fresh: List<String>) {
        val config = AiSettings.load(context)
        explainedIn = config.language
        val say: (String) -> Unit = { if (fresh.isNotEmpty() && config.speak) CarVoice.announce(it, config.language.locale) }
        val resources = config.language.resources(context)
        val offline = MechanicLines.newCodes(fresh).text(resources)
        val car = CarProfileStore.current
        val readings = ObdBluetoothManager.data.value
        // The car's own rules have the last word on how serious it is (MechanicVerdict.kt).
        val floor = SeverityFloor.of(codes, readings.coolantTempC, car.hotC)
        val bounded: (Diagnosis) -> Diagnosis = { d -> SeverityFloor.apply(d, floor) { MechanicLines.ruleVerdict(it).text(resources) } }
        // "v2": answers with the detail sheet; older, shorter ones are asked again.
        val cacheKey = "diag_v2_" + codes.sorted().joinToString(",") + "|" + car.promptDescription().hashCode() + "|" + config.language.name
        val cached = DiagnosisCache.get(context, cacheKey)
        cached?.let { MechanicPrompt.parse(it.text) }?.let { parsed ->
            val d = bounded(parsed.copy(model = cached.model))
            _state.value = State(codes = codes, diagnosis = d)
            say(d.summary)
            return
        }
        if (config.apiKey.isBlank()) {
            _state.value = State(codes = codes, note = Note.NoKey)
            say(offline)
            return
        }

        _state.value = State(codes = codes, thinking = true)
        val references = codes.mapNotNull { code -> ObdCodes.tableTitle(code)?.let { code to it } }.toMap()
        val prompt = MechanicPrompt.build(codes, car.promptDescription(), config.language, readings, references, car.currency)
        GeminiClient.generate(config.apiKey, prompt, MechanicPrompt.schema(codes))
            .mapCatching { reply -> reply to (MechanicPrompt.parse(reply.text) ?: throw UnreadableAnswerException()) }
            .onSuccess { (reply, diagnosis) ->
                DiagnosisCache.put(context, cacheKey, reply)
                val d = bounded(diagnosis.copy(model = reply.model))
                _state.value = State(codes = codes, diagnosis = d)
                say(d.summary)
            }
            .onFailure {
                _state.value = State(codes = codes, note = Note.Unavailable(it), canRetry = true)
                say(offline)
            }
    }

    /** [note] as shown on the tile, in [context]'s language. */
    fun noteText(context: Context, note: Note): String = when (note) {
        Note.NoKey -> context.getString(R.string.ai_note_no_key)
        is Note.Unavailable -> context.getString(R.string.ai_note_unavailable, describe(context, note.error))
    }

    /** A short, human reason for a failed request. Google's own error messages are shown as they come. */
    internal fun describe(context: Context, error: Throwable): String = when (error) {
        is GeminiException -> when (error.status) {
            429 -> context.getString(R.string.ai_error_quota)
            // Every model refused with "high demand": Google's free tier is overloaded.
            503 -> context.getString(R.string.ai_error_gemini_busy)
            400, 401, 403 -> error.message ?: context.getString(R.string.ai_error_key_refused)
            // A 2xx that still failed: the answer had no text.
            in 200..299 -> context.getString(R.string.ai_error_empty)
            else -> error.message ?: context.getString(R.string.ai_error_gemini, error.status)
        }
        is UnreadableAnswerException -> context.getString(R.string.ai_error_unreadable)
        is IOException -> context.getString(networkErrorRes(error))
        else -> error.message ?: context.getString(R.string.ai_error_unknown)
    }

    /** What went wrong on the way to Google, as specific as the exception allows. */
    internal fun networkErrorRes(error: IOException): Int = when (error) {
        is NoInternetAccessException -> R.string.ai_error_no_access
        is UnknownHostException -> R.string.ai_error_no_dns
        is SSLException -> R.string.ai_error_tls
        // Covers SocketTimeoutException and a spent time budget alike.
        is InterruptedIOException -> R.string.ai_error_timeout
        is ConnectException, is NoRouteToHostException -> R.string.ai_error_connect
        else -> R.string.ai_error_offline
    }
}

/**
 * Gemini's answers by codes, car and language, so a fault already explained
 * isn't asked about again. Kept apart from the scan's own state (saved on every
 * scan) and limited to the latest [MAX] answers, oldest dropped first.
 */
private object DiagnosisCache {
    private const val PREFS = "ai_diagnoses"
    private const val KEY_ORDER = "order"
    // Beside a cached answer: which model gave it.
    private const val MODEL_SUFFIX = "|model"
    private const val MAX = 10
    /** Where answers used to be kept, never pruned; cleared out once per run. */
    private const val OLD_PREFS = "ai_mechanic"
    private var oldCleared = false

    fun get(context: Context, key: String): GeminiReply? {
        val prefs = prefs(context)
        val text = prefs.getString(key, null) ?: return null
        return GeminiReply(model = prefs.getString(key + MODEL_SUFFIX, null).orEmpty(), text = text)
    }

    @Synchronized
    fun put(context: Context, key: String, reply: GeminiReply) {
        val prefs = prefs(context)
        val order = order(prefs).filter { it != key } + key
        val dropped = order.dropLast(MAX)
        prefs.edit().apply {
            dropped.forEach { remove(it).remove(it + MODEL_SUFFIX) }
            putString(key, reply.text)
            putString(key + MODEL_SUFFIX, reply.model)
            putString(KEY_ORDER, JSONArray(order.takeLast(MAX)).toString())
        }.apply()
    }

    private fun order(prefs: SharedPreferences): List<String> =
        runCatching {
            val array = JSONArray(prefs.getString(KEY_ORDER, null) ?: return emptyList())
            List(array.length()) { array.getString(it) }
        }.getOrDefault(emptyList())

    @Synchronized
    private fun prefs(context: Context): SharedPreferences {
        if (!oldCleared) {
            oldCleared = true
            val old = context.getSharedPreferences(OLD_PREFS, Context.MODE_PRIVATE)
            val stale = old.all.keys.filter { it.startsWith("diag_") }
            if (stale.isNotEmpty()) old.edit().apply { stale.forEach { remove(it) } }.apply()
        }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}
