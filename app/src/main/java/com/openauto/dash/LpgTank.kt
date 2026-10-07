package com.openauto.dash

import android.content.Context
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
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * The second tank: an LPG (autogas) tank the car's own gauge knows nothing
 * about. Its level is worked out: the kilometres driven on LPG since the last
 * full fill, at what the car uses. "Full" after each fill puts it back to 100 %;
 * the litres pumped, when given, teach it the car's real consumption.
 *
 * Which fuel burns: most LPG systems start on petrol and switch over once the
 * engine is warm, so in Auto the kilometres count once the OBD's coolant
 * reading passes the switch temperature, or, without an adapter, after the
 * first kilometres of each drive. Always and the driver's own switch cover the rest.
 */

enum class LpgMode { AUTO, ALWAYS, MANUAL }

/** The driver's settings. [tankL] is what a full fill takes (an LPG tank fills to about 80 %). */
internal data class LpgSettings(
    val enabled: Boolean = false,
    val tankL: Double = 40.0,
    /** L/100 km typed in; null: the car's own consumption, a fifth more. */
    val useL100: Double? = null,
    /** A litre of LPG; null: the nearest station's price at each fill. */
    val pricePerL: Double? = null,
    val mode: LpgMode = LpgMode.AUTO,
    /** Coolant temperature at which the system switches to LPG (Auto). */
    val switchC: Int = 40
)

/** One full fill: [kmBefore] driven on LPG since the one before (0 for the first), the [liters] pumped where told. */
internal data class LpgFill(val at: Long, val kmBefore: Double, val liters: Double? = null, val pricePerL: Double? = null)

internal data class LpgState(
    /** The last full fill; 0 until the first. */
    val filledAt: Long = 0L,
    val kmSinceFill: Double = 0.0,
    /** The driver's switch, in [LpgMode.MANUAL]. */
    val onLpg: Boolean = true,
    /** The low level was said since the last fill. */
    val warned: Boolean = false,
    /** Newest first. */
    val fills: List<LpgFill> = emptyList()
)

/** What the tile shows. */
internal data class LpgReading(
    val percent: Int,
    val liters: Double,
    val rangeKm: Double,
    val useL100: Double,
    /** [useL100] comes from the litres pumped, not from a setting. */
    val learned: Boolean,
    val burning: Boolean,
    val pricePerL: Double?,
    val kmSinceFill: Double,
    val filledAt: Long
) {
    val low: Boolean get() = percent <= LpgRules.LOW_PCT
    val costPer100: Double? get() = pricePerL?.let { it * useL100 }
}

/** The rules, pure so they're unit-tested. */
internal object LpgRules {
    /** Without a coolant reading, the first kilometres of a drive are on petrol. */
    const val WARMUP_KM = 1.0
    const val LOW_PCT = 15
    /** Fills with their litres over this many kilometres before the car's own figure replaces the setting. */
    const val MIN_LEARN_KM = 300.0
    /** LPG burns about a fifth more than petrol, litre for litre. */
    const val PETROL_TO_LPG = 1.2
    /** More than this between two readings is the GPS jumping, not driving. */
    const val MAX_STEP_KM = 1.0
    private const val LEARN_FILLS = 5
    private const val MAX_FILLS = 20

    fun burning(s: LpgSettings, onLpg: Boolean, coolantC: Int?, tripKm: Double): Boolean = when (s.mode) {
        LpgMode.ALWAYS -> true
        LpgMode.MANUAL -> onLpg
        LpgMode.AUTO -> if (coolantC != null) coolantC >= s.switchC else tripKm >= WARMUP_KM
    }

    /** The car's real use from the last full fills whose litres are known: each one put back what the kilometres before it burned. */
    fun learnedUse(fills: List<LpgFill>): Double? {
        val known = fills.filter { it.liters != null && it.kmBefore > 0 }.take(LEARN_FILLS)
        val km = known.sumOf { it.kmBefore }
        if (km < MIN_LEARN_KM) return null
        return (known.sumOf { it.liters ?: 0.0 } / km * 100).takeIf { it in 3.0..30.0 }
    }

    /** L/100 km and whether it was learned. */
    fun use(s: LpgSettings, fills: List<LpgFill>, petrolUse: Double): Pair<Double, Boolean> =
        learnedUse(fills)?.let { it to true } ?: ((s.useL100?.takeIf { it > 0 } ?: petrolUse * PETROL_TO_LPG) to false)

    /** The price to count with: the driver's, else the last one paid. */
    fun price(s: LpgSettings, fills: List<LpgFill>): Double? = s.pricePerL?.takeIf { it > 0 } ?: fills.firstNotNullOfOrNull { it.pricePerL }

    /** The tank now; null until the first full fill. */
    fun reading(s: LpgSettings, st: LpgState, petrolUse: Double, burning: Boolean): LpgReading? {
        if (st.filledAt == 0L) return null
        val (use, learned) = use(s, st.fills, petrolUse)
        val tank = s.tankL.coerceAtLeast(1.0)
        val liters = (tank - st.kmSinceFill * use / 100).coerceIn(0.0, tank)
        return LpgReading(
            percent = (liters / tank * 100).roundToInt(),
            liters = liters,
            rangeKm = liters / use * 100,
            useL100 = use,
            learned = learned,
            burning = burning,
            pricePerL = price(s, st.fills),
            kmSinceFill = st.kmSinceFill,
            filledAt = st.filledAt
        )
    }

    /** [km] more on LPG. */
    fun drove(st: LpgState, km: Double): LpgState = st.copy(kmSinceFill = st.kmSinceFill + km)

    /** A full fill at [now]: back to 100 %, and the kilometres since the last one kept with it. */
    fun fill(st: LpgState, now: Long, pricePerL: Double?): LpgState = st.copy(
        filledAt = now,
        kmSinceFill = 0.0,
        warned = false,
        fills = (listOf(LpgFill(now, if (st.filledAt == 0L) 0.0 else st.kmSinceFill, pricePerL = pricePerL)) + st.fills).take(MAX_FILLS)
    )

    /** The litres pumped at the last fill, told after the tap. */
    fun pumped(st: LpgState, liters: Double): LpgState =
        st.fills.firstOrNull()?.let { last -> st.copy(fills = listOf(last.copy(liters = liters.takeIf { it > 0 })) + st.fills.drop(1)) } ?: st

    /** What the gauge says the last fill put in: the starting suggestion for the litres pumped. */
    fun suggestedLiters(s: LpgSettings, st: LpgState, petrolUse: Double): Double {
        val km = st.fills.firstOrNull()?.kmBefore ?: 0.0
        val (use, _) = use(s, st.fills.drop(1), petrolUse)
        return (km * use / 100).coerceIn(0.0, s.tankL)
    }

    fun settingsJson(s: LpgSettings): JSONObject = JSONObject()
        .put("enabled", s.enabled).put("tank", s.tankL).putOpt("use", s.useL100).putOpt("price", s.pricePerL)
        .put("mode", s.mode.name).put("switch_c", s.switchC)

    fun settingsFrom(o: JSONObject): LpgSettings = LpgSettings(
        enabled = o.optBoolean("enabled"),
        tankL = o.optDouble("tank", 40.0),
        useL100 = if (o.isNull("use")) null else o.optDouble("use").takeIf { !it.isNaN() },
        pricePerL = if (o.isNull("price")) null else o.optDouble("price").takeIf { !it.isNaN() },
        mode = runCatching { LpgMode.valueOf(o.optString("mode")) }.getOrDefault(LpgMode.AUTO),
        switchC = o.optInt("switch_c", 40)
    )

    fun stateJson(st: LpgState): JSONObject = JSONObject()
        .put("filled_at", st.filledAt).put("km", st.kmSinceFill).put("on_lpg", st.onLpg).put("warned", st.warned)
        .put("fills", JSONArray().apply {
            st.fills.forEach { f -> put(JSONObject().put("at", f.at).put("km", f.kmBefore).putOpt("l", f.liters).putOpt("price", f.pricePerL)) }
        })

    fun stateFrom(o: JSONObject): LpgState = LpgState(
        filledAt = o.optLong("filled_at"),
        kmSinceFill = o.optDouble("km", 0.0),
        onLpg = o.optBoolean("on_lpg", true),
        warned = o.optBoolean("warned"),
        fills = o.optJSONArray("fills")?.let { a ->
            (0 until a.length()).map { i ->
                val f = a.getJSONObject(i)
                LpgFill(
                    at = f.optLong("at"),
                    kmBefore = f.optDouble("km", 0.0),
                    liters = if (f.isNull("l")) null else f.optDouble("l").takeIf { !it.isNaN() },
                    pricePerL = if (f.isNull("price")) null else f.optDouble("price").takeIf { !it.isNaN() }
                )
            }
        }.orEmpty()
    )
}

/** The live side: counts the LPG kilometres, keeps the fills, says when the tank runs low. */
internal object LpgTank {
    private const val PREFS = "lpg_tank"
    private const val TICK_MS = 5_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var appContext: Context? = null

    private val _settings = MutableStateFlow(LpgSettings())
    val settings: StateFlow<LpgSettings> = _settings.asStateFlow()

    private val _state = MutableStateFlow(LpgState())
    val state: StateFlow<LpgState> = _state.asStateFlow()

    private val _reading = MutableStateFlow<LpgReading?>(null)
    /** The tank now; null until the first full fill (or with the tank off). */
    val reading: StateFlow<LpgReading?> = _reading.asStateFlow()

    // The GPS trip last read: its distance grows as the car moves.
    private var tripStart = 0L
    private var tripM = 0.0
    private var savedTripStart = 0L
    private var savedTripM = 0.0
    private var unsavedKm = 0.0
    private var burning = false

    /** Starts once for the process, after [DriveLog] (which brings back the trip under way); main thread. */
    fun start(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        val p = prefs(app)
        _settings.value = p.getString("settings", null)?.let { runCatching { LpgRules.settingsFrom(JSONObject(it)) }.getOrNull() } ?: LpgSettings()
        _state.value = p.getString("state", null)?.let { runCatching { LpgRules.stateFrom(JSONObject(it)) }.getOrNull() } ?: LpgState()
        // Where the counting stopped: a trip carried on after a restart isn't counted twice.
        tripStart = p.getLong("trip_start", 0L)
        tripM = p.getFloat("trip_m", 0f).toDouble()
        savedTripStart = tripStart
        savedTripM = tripM
        refresh()
        scope.launch {
            while (true) {
                delay(TICK_MS)
                tick()
            }
        }
    }

    private fun tick() {
        val trip = LocationFeed.trip.value
        val movedM = if (trip.startedAt == tripStart) trip.distanceM - tripM else trip.distanceM
        tripStart = trip.startedAt
        tripM = trip.distanceM
        if (DemoMode.isOn) {
            _reading.value = demo()
            return
        }
        val s = _settings.value
        val st = _state.value
        val km = movedM / 1000
        burning = s.enabled && LpgRules.burning(s, st.onLpg, coolant(), trip.distanceM / 1000)
        if (s.enabled && st.filledAt != 0L && burning && km > 0 && km <= LpgRules.MAX_STEP_KM) {
            _state.value = LpgRules.drove(st, km)
            unsavedKm += km
        }
        when {
            unsavedKm >= 0.1 -> save(saveState = true)
            tripStart != savedTripStart || abs(tripM - savedTripM) >= 100 -> save(saveState = false)
        }
        refresh()
        warnIfLow()
    }

    /** The coolant temperature while the adapter answers; null without one. */
    private fun coolant(): Int? {
        if (ObdBluetoothManager.connectionState.value != ObdConnectionState.CONNECTED) return null
        return ObdBluetoothManager.data.value.coolantTempC.takeIf { it != 0 }
    }

    private fun refresh() {
        if (DemoMode.isOn) return
        val s = _settings.value
        _reading.value = if (s.enabled) LpgRules.reading(s, _state.value, CarProfileStore.current.typicalUse, burning) else null
    }

    private fun warnIfLow() {
        val r = _reading.value ?: return
        val st = _state.value
        if (!r.low || st.warned) return
        _state.value = st.copy(warned = true)
        save(saveState = true)
        val app = appContext ?: return
        if (!SpokenEvents.isOn(app, SpokenEvent.LPG)) return
        val units = Units.current.value
        // Said aloud, so the unit is a full word in the right number, never "km".
        val left = units.distance(r.rangeKm).roundToInt()
        val text = AppLanguage.wrap(app).resources.getQuantityString(
            if (units.imperial) R.plurals.units_lpg_voice_low_mi else R.plurals.lpg_voice_low, left, left
        )
        CarVoice.announce(text, InAppNav.locale(app))
    }

    fun saveSettings(context: Context, s: LpgSettings) {
        appContext = appContext ?: context.applicationContext
        _settings.value = s
        prefs(context).edit().putString("settings", LpgRules.settingsJson(s).toString()).apply()
        refresh()
    }

    /** The tank was just filled to the brim: 100 %, at the nearest station's LPG price where it's known. */
    fun fillUp(context: Context) {
        val app = context.applicationContext
        val here = LocationFeed.location.value
        val station = here?.let { h -> FuelPriceRepo.stations.value?.let { FuelLogRules.stationAt(it, h.latitude, h.longitude, listOf(FuelGrade.GPLC)) } }
        _state.value = LpgRules.fill(_state.value, System.currentTimeMillis(), station?.second ?: _settings.value.pricePerL)
        save(app, saveState = true)
        refresh()
    }

    /** The litres the pump showed for the last fill. */
    fun setPumped(context: Context, liters: Double) {
        _state.value = LpgRules.pumped(_state.value, liters)
        save(context.applicationContext, saveState = true)
        refresh()
    }

    fun suggestedLiters(): Double = LpgRules.suggestedLiters(_settings.value, _state.value, CarProfileStore.current.typicalUse)

    /** The driver's switch: on LPG or on petrol. Returns the new side. */
    fun toggleFuel(context: Context): Boolean {
        val on = !_state.value.onLpg
        _state.value = _state.value.copy(onLpg = on)
        save(context.applicationContext, saveState = true)
        return on
    }

    /** "Tank full" from a steering wheel button: done and said. */
    fun fillUpHandsFree(context: Context) {
        if (!_settings.value.enabled) {
            HandsFree.say(context, R.string.lpg_voice_off)
            return
        }
        fillUp(context)
        HandsFree.say(context, R.string.lpg_voice_full)
    }

    /** LPG or petrol from a steering wheel button: switches the manual mode, or says which fuel Auto counts now. */
    fun switchHandsFree(context: Context) {
        val s = _settings.value
        when {
            !s.enabled -> HandsFree.say(context, R.string.lpg_voice_off)
            s.mode == LpgMode.MANUAL -> HandsFree.say(context, if (toggleFuel(context)) R.string.lpg_voice_on_lpg else R.string.lpg_voice_on_petrol)
            else -> HandsFree.say(context, if (burning) R.string.lpg_voice_on_lpg else R.string.lpg_voice_on_petrol)
        }
    }

    /** A tank half used on a made-up drive; nothing saved. */
    private fun demo(): LpgReading {
        val minute = (System.currentTimeMillis() / 60_000 % 30).toInt()
        val liters = 24.0 - minute * 0.1
        return LpgReading(
            percent = (liters / 40 * 100).roundToInt(), liters = liters, rangeKm = liters / 9.5 * 100, useL100 = 9.5, learned = true,
            burning = true, pricePerL = 0.99, kmSinceFill = 170.0 + minute, filledAt = System.currentTimeMillis() - 3 * 86_400_000L
        )
    }

    private fun save(context: Context? = appContext, saveState: Boolean) {
        val app = context ?: return
        val edit = prefs(app).edit().putLong("trip_start", tripStart).putFloat("trip_m", tripM.toFloat())
        savedTripStart = tripStart
        savedTripM = tripM
        if (saveState) {
            edit.putString("state", LpgRules.stateJson(_state.value).toString())
            unsavedKm = 0.0
        }
        edit.apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
