package com.openauto.dash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import org.json.JSONObject
import kotlin.math.roundToInt

/** Where the car's readings come from (Settings, Car). */
enum class ObdSource {
    /** An ELM327 adapter this app talks to itself. */
    ADAPTER,
    /** The Deep OBD app owns the adapter and broadcasts what it reads. */
    DEEPOBD
}

/** The readings Dashwheel takes from Deep OBD's pages. */
internal enum class ObdField { SPEED, RPM, COOLANT, INTAKE, THROTTLE, LOAD, FUEL, VOLTAGE }

/** One line of a Deep OBD page: its label, the job result behind it and the text it shows. */
internal data class DeepItem(val name: String, val result: String, val value: String)

internal data class DeepFrame(val page: String, val items: List<DeepItem>)

/** Reads what Deep OBD broadcasts. Pure, so the guesses can be tested. */
internal object DeepObdParser {

    /** The `obd_data` extra: `{"PageName": "...", "ObdData": [{"Name", "Result", "Value"}, ...]}`. */
    fun frame(json: String): DeepFrame? = runCatching {
        val root = JSONObject(json)
        val list = root.optJSONArray("ObdData") ?: return null
        val items = (0 until list.length()).mapNotNull { i ->
            val o = list.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("Name").trim()
            if (name.isEmpty()) null else DeepItem(name, o.optString("Result").trim(), o.optString("Value").trim())
        }
        DeepFrame(root.optString("PageName"), items)
    }.getOrNull()

    private val KEYWORDS: List<Pair<ObdField, List<String>>> = listOf(
        // Revs before speed: "engine speed" is revs.
        ObdField.RPM to listOf("rpm", "drehzahl", "revs", "u/min", "tr/min", "régime", "regime", "engine speed", "motordrehzahl"),
        ObdField.SPEED to listOf("speed", "geschw", "vitesse", "km/h", "velocidad", "velocità", "snelheid", "prędkość"),
        ObdField.INTAKE to listOf("intake", "ansaug", "admission", "aspiración"),
        ObdField.COOLANT to listOf("coolant", "kühl", "kuehl", "wasser", "water", "liquide", "refriger", "koelvloeistof"),
        ObdField.THROTTLE to listOf("throttle", "drossel", "papillon", "mariposa", "gasklep"),
        ObdField.LOAD to listOf("engine load", "motorlast", "charge moteur", "load", "carga"),
        ObdField.FUEL to listOf("fuel level", "tank", "füllstand", "fuellstand", "niveau carburant", "nivel de combustible"),
        ObdField.VOLTAGE to listOf("voltage", "spannung", "tension", "voltaje", "batter", "spanning")
    )

    private val PRESSURE = listOf("pressure", "druck", "pression", "presión", "presion", "pressione", "druk")

    private val NOT_SPEED = listOf("fan", "lüfter", "luefter", "ventilateur")

    /** The reading a line most likely is, from its label and result name; null when it is none of them. */
    fun guess(item: DeepItem): ObdField? {
        val text = "${item.name} ${item.result}".lowercase()
        // "Intake manifold pressure" is no temperature, "fuel pressure" no level.
        if (PRESSURE.any { it in text }) return null
        return KEYWORDS.firstOrNull { (field, words) ->
            words.any { it in text } && !(field == ObdField.SPEED && NOT_SPEED.any { it in text })
        }?.first
    }

    private val NUMBER = Regex("-?\\d+(?:[.,]\\d+)?")
    private val THOUSANDS = Regex("\\b\\d{1,3}(?:[.,]\\d{3})+\\b")

    /** The first number in [value], with a comma as the decimal sign taken for a point. */
    fun number(value: String): Double? = NUMBER.find(value)?.value?.replace(',', '.')?.toDoubleOrNull()

    /** [value] as the unit [field] is kept in (km/h, °C), converting miles an hour and Fahrenheit. */
    fun reading(field: ObdField, value: String): Double? {
        // Revs never have decimals: "2.150 1/min" is two thousand, written the German way.
        if (field == ObdField.RPM) THOUSANDS.find(value)?.let { return it.value.filter(Char::isDigit).toDouble() }
        val n = number(value) ?: return null
        val unit = value.lowercase()
        return when (field) {
            ObdField.SPEED -> if ("mph" in unit || "mi/h" in unit) n * 1.609344 else n
            ObdField.COOLANT, ObdField.INTAKE ->
                if ("°f" in unit || "fahrenheit" in unit || unit.trimEnd().endsWith(" f")) (n - 32.0) / 1.8 else n
            else -> n
        }
    }

    /** [base] with the [readings] laid over it; what a line did not carry stays as it was. */
    fun apply(base: ObdData, readings: Map<ObdField, Double>, speedFix: (Int) -> Int = { it }): ObdData = base.copy(
        speedKmh = readings[ObdField.SPEED]?.let { speedFix(it.roundToInt().coerceAtLeast(0)) } ?: base.speedKmh,
        rpm = readings[ObdField.RPM]?.roundToInt()?.coerceAtLeast(0) ?: base.rpm,
        coolantTempC = readings[ObdField.COOLANT]?.roundToInt() ?: base.coolantTempC,
        intakeTempC = readings[ObdField.INTAKE]?.roundToInt() ?: base.intakeTempC,
        throttlePct = readings[ObdField.THROTTLE]?.roundToInt()?.coerceIn(0, 100) ?: base.throttlePct,
        engineLoadPct = readings[ObdField.LOAD]?.roundToInt()?.coerceIn(0, 100) ?: base.engineLoadPct,
        fuelLevelPct = readings[ObdField.FUEL]?.roundToInt()?.coerceIn(0, 100) ?: base.fuelLevelPct,
        voltage = readings[ObdField.VOLTAGE] ?: base.voltage,
        voltageFromEcu = if (readings.containsKey(ObdField.VOLTAGE)) true else base.voltageFromEcu
    )
}

/** A line Deep OBD sent, and the reading Dashwheel takes it for (null: none). */
internal data class DeepSeen(val item: DeepItem, val field: ObdField?, val chosen: Boolean)

/**
 * The car's readings from the Deep OBD app instead of an adapter of our own
 * (Settings, Car). Deep OBD, once its "send data broadcast" option is on,
 * announces every page it reads; the receiver here is only registered while
 * this source is chosen, so nobody else pays for it. With Deep OBD in charge
 * the adapter is left alone: it takes one connection at a time.
 */
internal object DeepObdSource {
    const val PACKAGE = "de.holeschak.bmw_deep_obd"
    const val ACTION = "$PACKAGE.Notification.Info"

    private const val PREFS = "obd_prefs"
    private const val KEY_SOURCE = "obd_source"
    private const val KEY_MAP = "deepobd_map"
    private const val IGNORED = "NONE"

    /** No frame for this long: Deep OBD is closed or lost the car. */
    private const val STALE_MS = 15_000L

    private var prefs: SharedPreferences? = null

    private val _source = MutableStateFlow(ObdSource.ADAPTER)
    val source: StateFlow<ObdSource> = _source.asStateFlow()

    /** Deep OBD is the one reading the car. */
    fun owns(): Boolean = _source.value == ObdSource.DEEPOBD

    /** Deep OBD is sending right now. */
    @Volatile var live = false
        private set

    private var lastFrameAt = 0L

    /** Line name → reading name, or [IGNORED]; what the driver set by hand. */
    private var overrides: Map<String, String> = emptyMap()

    private val _seen = MutableStateFlow<List<DeepSeen>>(emptyList())
    /** The lines of the last frames, for the mapping list in Settings. */
    val seen: StateFlow<List<DeepSeen>> = _seen.asStateFlow()

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        overrides = runCatching {
            val o = JSONObject(p.getString(KEY_MAP, null) ?: "{}")
            o.keys().asSequence().associateWith { o.getString(it) }
        }.getOrDefault(emptyMap())
        _source.value = ObdSource.entries.firstOrNull { it.name == p.getString(KEY_SOURCE, null) } ?: ObdSource.ADAPTER
    }

    fun setSource(source: ObdSource) {
        prefs?.edit()?.putString(KEY_SOURCE, source.name)?.apply()
        _source.value = source
    }

    fun installed(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

    /** Opens Deep OBD, where the connection to the car is made. */
    fun launch(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE) ?: return
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun fieldFor(item: DeepItem): Pair<ObdField?, Boolean> {
        val set = overrides[item.name]
        return when {
            set == null -> DeepObdParser.guess(item) to false
            set == IGNORED -> null to true
            else -> ObdField.entries.firstOrNull { it.name == set } to true
        }
    }

    /**
     * The next choice for a line, from the one it shows: each reading in turn,
     * then ignored. Coming back round to the guess drops the choice made by hand.
     */
    fun cycle(item: DeepItem) {
        val name = item.name
        val order = ObdField.entries.map { it.name } + IGNORED
        val guessed = DeepObdParser.guess(item)?.name ?: IGNORED
        val shown = overrides[name] ?: guessed
        val next = order[(order.indexOf(shown) + 1) % order.size]
        overrides = if (next == guessed) overrides - name else overrides + (name to next)
        prefs?.edit()?.putString(KEY_MAP, JSONObject(overrides).toString())?.apply()
        _seen.value = _seen.value.map { s ->
            if (s.item.name != name) s else fieldFor(s.item).let { (field, chosen) -> s.copy(field = field, chosen = chosen) }
        }
    }

    private fun onFrame(json: String) {
        val frame = DeepObdParser.frame(json) ?: return
        val seen = frame.items.map { item -> fieldFor(item).let { (field, chosen) -> DeepSeen(item, field, chosen) } }
        // Several lines for one reading: the first wins, as a page lists the main one first.
        val readings = LinkedHashMap<ObdField, Double>()
        for (s in seen) {
            val field = s.field ?: continue
            if (field in readings) continue
            DeepObdParser.reading(field, s.item.value)?.let { readings[field] = it }
        }
        // A page shows some of the lines: those of the others stay listed.
        val names = seen.map { it.item.name }.toSet()
        _seen.value = _seen.value.filter { it.item.name !in names } + seen
        lastFrameAt = android.os.SystemClock.elapsedRealtime()
        live = true
        ObdBluetoothManager.deepObdWrite(DeepObdParser.apply(ObdBluetoothManager.data.value, readings, SpeedCorrection::corrected))
    }

    private fun down() {
        live = false
        ObdBluetoothManager.deepObdDown()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            intent.getStringExtra("obd_data")?.let(::onFrame)
            when (intent.getStringExtra("action")) {
                "disconnect" -> down()
                "connect" -> {
                    lastFrameAt = android.os.SystemClock.elapsedRealtime()
                    live = true
                }
            }
        }
    }

    /**
     * Listens while Deep OBD is the chosen source; started once by [VehicleMonitor].
     * The receiver is exported because the broadcast comes from another app.
     */
    suspend fun run(context: Context) {
        _source.collectLatest { source ->
            if (source != ObdSource.DEEPOBD) return@collectLatest
            ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION), ContextCompat.RECEIVER_EXPORTED)
            try {
                while (true) {
                    delay(1_000)
                    if (live && android.os.SystemClock.elapsedRealtime() - lastFrameAt > STALE_MS) down()
                }
            } finally {
                runCatching { context.unregisterReceiver(receiver) }
                down()
                _seen.value = emptyList()
            }
        }
    }
}
