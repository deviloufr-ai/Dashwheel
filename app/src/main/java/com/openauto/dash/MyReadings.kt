package com.openauto.dash

import android.content.Context
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
import kotlinx.coroutines.flow.first
import com.openauto.dash.vehicle.Source
import com.openauto.dash.vehicle.SourceKind
import com.openauto.dash.vehicle.Vehicle

/*
 * The driver's own readings: any OBD request typed in with the formula that
 * turns its answer into a value, figures worked out from the others ("LPG
 * litres an hour from the air flow"), and running totals of those (litres
 * since reset). Asked for by an LPG driver whose petrol gauge never moves, so
 * nothing built in could tell what the car burns: they know their car's
 * numbers and want to put them on the screen themselves.
 */

/** A request to the car, a formula over the other readings, a formula added up over time, or a frame heard on the CAN bus. */
enum class MyReadingKind { PID, FORMULA, TOTAL, CAN }

/**
 * One reading. [key] is its name in formulas ("lpg_lh"). For [MyReadingKind.PID]
 * the [request] ("0110", "22F40C") is sent to [header] (null: every computer,
 * as the regular OBD polls) and [formula] reads the answer's bytes A, B, C...;
 * [replyAddress] and [session] work as in the advanced readings (PidCandidate).
 * For FORMULA, [formula] works over the other names; for TOTAL, the same, its
 * value per hour added up (L/h becomes litres, km/h kilometres). For CAN,
 * [request] is the frame's id ("408", 8 digits for a 29-bit one), heard
 * through the USB CAN adapter ([UsbCan]), and [formula] reads its data bytes.
 * [feeds]: the car element its value stands for on the vehicle bus.
 */
data class MyReading(
    val key: String,
    val name: String,
    val unit: String,
    val kind: MyReadingKind,
    val formula: String,
    val request: String = "",
    val header: String? = null,
    val replyAddress: String? = null,
    val session: String? = null,
    val decimals: Int = 1,
    val onTile: Boolean = true,
    val feeds: ReadingFeed? = null
) {
    fun toJson(): JSONObject = JSONObject().put("key", key).put("name", name).put("unit", unit).put("kind", kind.name)
        .put("formula", formula).put("request", request).putOpt("header", header).putOpt("reply", replyAddress)
        .putOpt("session", session).put("decimals", decimals).put("tile", onTile).putOpt("feeds", feeds?.name)

    /** The CAN frame id it reads, null when it isn't a CAN reading or the id can't be one. */
    val canId: Int?
        get() {
            if (kind != MyReadingKind.CAN) return null
            val id = request.toLongOrNull(16) ?: return null
            return when (request.length) {
                3 -> id.takeIf { it <= 0x7FF }?.toInt()
                8 -> id.takeIf { it <= 0x1FFFFFFF }?.toInt()
                else -> null
            }
        }

    /** Its formula reads bytes A..H: a request's answer or a frame's data. */
    val readsBytes: Boolean get() = kind == MyReadingKind.PID || kind == MyReadingKind.CAN

    /** A 29-bit id: typed with 8 digits. */
    val canExtended: Boolean get() = request.length == 8

    /** The request as built for [PidCandidate], so the replies are read the way the advanced readings read theirs. */
    val replyHeader: String
        get() {
            val mode = request.take(2).toIntOrNull(16) ?: return request
            return String.format(Locale.US, "%02X", mode + 0x40) + request.drop(2)
        }

    companion object {
        fun fromJson(o: JSONObject): MyReading? {
            val kind = MyReadingKind.entries.firstOrNull { it.name == o.optString("kind") } ?: return null
            val key = o.optString("key").takeIf { it.isNotBlank() } ?: return null
            return MyReading(
                key, o.optString("name", key), o.optString("unit"), kind, o.optString("formula"), o.optString("request"),
                o.optString("header").takeIf { it.isNotBlank() }, o.optString("reply").takeIf { it.isNotBlank() },
                o.optString("session").takeIf { it.isNotBlank() }, o.optInt("decimals", 1).coerceIn(0, 3), o.optBoolean("tile", true),
                ReadingFeed.of(o.optString("feeds").takeIf { it.isNotBlank() })
            )
        }
    }
}

/** What the driver typed that can't be kept. */
enum class MyReadingProblem(@StringRes val text: Int) {
    NO_NAME(R.string.mine_err_name),
    BAD_KEY(R.string.mine_err_key),
    KEY_TAKEN(R.string.mine_err_key_taken),
    BAD_REQUEST(R.string.mine_err_request),
    BAD_ADDRESS(R.string.mine_err_address),
    BAD_SESSION(R.string.mine_err_session),
    BAD_FORMULA(R.string.mine_err_syntax),
    NO_BYTES(R.string.mine_err_no_bytes),
    BAD_CAN_ID(R.string.mine_err_can_id),
    FEED_TAKEN(R.string.mine_err_feed_taken)
}

/** The checks and names, without Android. Pure, unit-tested. */
object MyReadingRules {
    /** The values Dashwheel already reads, by name, for the driver's formulas. */
    val BUILT_IN = listOf("speed", "rpm", "coolant", "intake", "throttle", "load", "fuel", "volts", "gps_speed", "lpg", "fuel_flow")

    /** Only reads: live data (01), vehicle information (09), and the makers' own data (21, 22). Nothing that writes, clears or resets. */
    val READ_MODES = setOf("01", "09", "21", "22")

    private val KEY = Regex("^[a-z][a-z0-9_]{0,23}$")
    private val HEX = Regex("^[0-9A-F]+$")

    /** Hex digits only, upper case: "01 10" → "0110". */
    fun cleanHex(s: String): String = s.uppercase(Locale.ROOT).filter { it in '0'..'9' || it in 'A'..'F' }

    /** A name for formulas from what the driver called the reading: "LPG use" → "lpg_use", unique among [taken]. */
    fun keyFor(name: String, taken: Set<String>): String {
        val base = name.lowercase(Locale.ROOT)
            .map { if (it in 'a'..'z' || it in '0'..'9') it else '_' }.joinToString("")
            .replace(Regex("_+"), "_").trim('_')
            .let { if (it.isEmpty() || !it[0].isLetter()) "r_$it".trimEnd('_') else it }
            .take(20)
            .let { if (reserved(it)) "my_$it" else it }
        if (base !in taken) return base
        var n = 2
        while ("${base}_$n" in taken) n++
        return "${base}_$n"
    }

    /** A name the formulas already mean something by: a byte, a function, a built-in value. */
    fun reserved(key: String): Boolean =
        (key.length == 1 && key[0] in 'a'..'h') || key in Formula.FUNCTIONS || key in BUILT_IN

    /** The first thing wrong with [r] among the [others] (the readings it isn't), or null when it can be saved. */
    fun problem(r: MyReading, others: List<MyReading>): MyReadingProblem? {
        if (r.name.isBlank()) return MyReadingProblem.NO_NAME
        if (!KEY.matches(r.key) || reserved(r.key)) return MyReadingProblem.BAD_KEY
        if (others.any { it.key == r.key }) return MyReadingProblem.KEY_TAKEN
        if (r.kind == MyReadingKind.PID) {
            val req = r.request
            if (req.length < 4 || req.length % 2 != 0 || !HEX.matches(req) || req.take(2) !in READ_MODES) return MyReadingProblem.BAD_REQUEST
            if (listOfNotNull(r.header, r.replyAddress).any { it.length != 3 && it.length != 8 || !HEX.matches(it) }) return MyReadingProblem.BAD_ADDRESS
            if (r.replyAddress != null && r.header == null) return MyReadingProblem.BAD_ADDRESS
            // Programming sessions (02, 85) can stop a running engine: the advanced readings' safe list only.
            if (r.session != null && (r.session !in PidProbe.SAFE_SESSIONS || r.header == null)) return MyReadingProblem.BAD_SESSION
        }
        if (r.kind == MyReadingKind.CAN && r.canId == null) return MyReadingProblem.BAD_CAN_ID
        if (Formula.names(r.formula) == null) return MyReadingProblem.BAD_FORMULA
        if (!r.readsBytes && Formula.usesBytes(r.formula)) return MyReadingProblem.NO_BYTES
        // Two readings feeding one element would take turns on the screen.
        if (r.feeds != null && others.any { it.feeds == r.feeds }) return MyReadingProblem.FEED_TAKEN
        return null
    }

    /** What each fed element is now: the value of the reading that feeds it, where it has one. */
    fun feeds(readings: List<MyReading>, values: Map<String, Double>): Map<ReadingFeed, Double> =
        readings.mapNotNull { r -> r.feeds?.let { f -> values[r.key]?.let { f to it } } }.toMap()

    /** [frame] read by [r] (a CAN reading of that id): its value, or why there is none. */
    fun read(r: MyReading, frame: CanFrame): Formula.Result = Formula.evaluate(r.formula, frame.bytes, NO_NAMES)

    /** The names [formula] reads that are neither built in nor one of [readings]. */
    fun unknownNames(formula: String, readings: List<MyReading>): List<String> {
        val keys = readings.map { it.key }.toSet()
        return Formula.names(formula).orEmpty().filter { it !in keys && it !in BUILT_IN }
    }

    /**
     * Every reading's value now: PID readings from [pids] (their last answer),
     * totals from [totals], formulas worked out over [builtIn] and each other,
     * a reading that reads itself round a loop giving none.
     */
    fun values(readings: List<MyReading>, builtIn: Map<String, Double?>, pids: Map<String, Double>, totals: Map<String, Double>): Map<String, Double> {
        val byKey = readings.associateBy { it.key }
        val done = HashMap<String, Double?>()
        val busy = HashSet<String>()
        lateinit var names: Formula.Names
        fun valueOf(key: String): Double? {
            if (key in done) return done[key]
            val r = byKey[key] ?: return builtIn[key]
            if (!busy.add(key)) return null
            val v = when (r.kind) {
                MyReadingKind.PID, MyReadingKind.CAN -> pids[key]
                MyReadingKind.TOTAL -> totals[key] ?: 0.0
                MyReadingKind.FORMULA -> Formula.evaluate(r.formula, null, names).value
            }
            busy -= key
            done[key] = v
            return v
        }
        names = object : Formula.Names {
            override fun known(name: String) = name in byKey || name in BUILT_IN
            override fun value(name: String) = valueOf(name)
        }
        readings.forEach { valueOf(it.key) }
        return done.mapNotNull { (k, v) -> v?.let { k to it } }.toMap()
    }

    /** What each total adds per hour now: its formula over the same names as [values]. */
    fun rates(readings: List<MyReading>, builtIn: Map<String, Double?>, values: Map<String, Double>): Map<String, Double> {
        val names = object : Formula.Names {
            override fun known(name: String) = readings.any { it.key == name } || name in BUILT_IN
            override fun value(name: String) = values[name] ?: builtIn[name]
        }
        return readings.filter { it.kind == MyReadingKind.TOTAL }
            .mapNotNull { r -> Formula.evaluate(r.formula, null, names).value?.let { r.key to it } }.toMap()
    }

    /** The answer [reply] to [r]'s request, as a value; null when there is none to read. */
    fun read(r: MyReading, reply: String?): Formula.Result {
        val bytes = ObdParser.dataBytes(PidProbe.frames(reply), r.replyHeader) ?: return Formula.Result(null, Formula.Problem.NO_VALUE)
        return Formula.evaluate(r.formula, bytes, NO_NAMES)
    }

    private val NO_NAMES = object : Formula.Names {
        override fun known(name: String) = false
        override fun value(name: String): Double? = null
    }

    /**
     * Ready-made readings for a petrol engine run on LPG: the air flow, the
     * gas that air burns (15.5 g of air per gram of LPG, 540 g a litre), its
     * running total and its use per 100 km. [names] are the four names shown.
     */
    fun examples(names: List<String>, taken: Set<String>): List<MyReading> {
        val out = mutableListOf<MyReading>()
        fun add(name: String, unit: String, kind: MyReadingKind, formula: String, request: String = "", decimals: Int = 1, onTile: Boolean = true): String {
            val key = keyFor(name, taken + out.map { it.key })
            out += MyReading(key, name, unit, kind, formula, request, decimals = decimals, onTile = onTile)
            return key
        }
        val maf = add(names[0], "g/s", MyReadingKind.PID, "(A*256+B)/100", "0110", onTile = false)
        val lh = add(names[1], "L/h", MyReadingKind.FORMULA, "$maf * 3600 / 15.5 / 540")
        add(names[2], "L", MyReadingKind.TOTAL, lh, decimals = 2)
        add(names[3], "L/100 km", MyReadingKind.FORMULA, "if(speed > 5, $lh / speed * 100, 0)")
        return out
    }

    /** The engine computer's own requests on a VW diesel, as a Sharan 7N driver found them: what the VW set reads first. */
    const val VW_BOOST_REQUEST = "221057"

    /**
     * Ready-made readings for a VW group diesel's engine computer (7E0), from a
     * Sharan 7N driver's list: boost and its target in mbar, oil temperature,
     * torque, and the power that torque makes at the revs (Nm × rpm / 7023 is
     * metric horsepower). [names] are the five names shown.
     */
    fun vwDiesel(names: List<String>, taken: Set<String>): List<MyReading> {
        val out = mutableListOf<MyReading>()
        fun add(name: String, unit: String, kind: MyReadingKind, formula: String, request: String = "", decimals: Int = 1): String {
            val key = keyFor(name, taken + out.map { it.key })
            out += MyReading(key, name, unit, kind, formula, request, header = if (kind == MyReadingKind.PID) VW_ENGINE else null, decimals = decimals)
            return key
        }
        add(names[0], "mbar", MyReadingKind.PID, "A*256+B", VW_BOOST_REQUEST, decimals = 0)
        add(names[1], "mbar", MyReadingKind.PID, "A*256+B", "221149", decimals = 0)
        add(names[2], "°C", MyReadingKind.PID, "(A*256+B-2731.4)/10", "2211BE")
        val torque = add(names[3], "Nm", MyReadingKind.PID, "(A*256+B)*0.1", "221047")
        add(names[4], "PS", MyReadingKind.FORMULA, "$torque * rpm / 7023")
        return out
    }

    private const val VW_ENGINE = "7E0"
}

/** Keeps the driver's readings, asks the car for theirs while connected, and works the rest out every second. */
object MyReadings {
    private const val PREFS = "my_readings"
    private const val TICK_MS = 1_000L
    private const val POLL_MS = 2_000L
    private const val TIMEOUT_MS = 1_500L
    /** A car answer older than this is no longer shown. */
    private const val STALE_MS = 15_000L
    /** A CAN frame older than this: the bus went quiet (most frames come several times a second). */
    private const val CAN_STALE_MS = 5_000L
    /** A gap longer than this between two ticks (the unit asleep) adds nothing to the totals. */
    private const val MAX_GAP_MS = 10_000L
    private const val SAVE_TOTALS_MS = 30_000L

    private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _list = MutableStateFlow<List<MyReading>>(emptyList())
    val list: StateFlow<List<MyReading>> = _list.asStateFlow()

    private val _values = MutableStateFlow<Map<String, Double>>(emptyMap())
    /** Each reading's value now, by key; a reading with none is left out. */
    val values: StateFlow<Map<String, Double>> = _values.asStateFlow()

    /** The car's last answer to a request or the last frame's value; [demo]: made up by the demo, never counted once it ends. */
    private class Answer(val value: Double, val at: Long, val demo: Boolean = false, val can: Boolean = false)

    /**
     * The readings that feed a car element, on the vehicle bus: the driver's
     * own, so they outrank the car's built-in decoding (LEARNED), as taught
     * signals do.
     */
    private val feedPublisher = ReadingFeedPublisher(
        Vehicle.bus.register(Source("mine", SourceKind.LEARNED, "My readings")),
        ReadingFeedPublisher::corrected
    )

    /** The CAN readings by the frame id they read, rebuilt when the list changes. */
    @Volatile
    private var canIndex: Pair<List<MyReading>, Map<Long, List<MyReading>>> = emptyList<MyReading>() to emptyMap()
    private val pids = HashMap<String, Answer>()
    private val totals = HashMap<String, Double>()
    /** While the demo runs, the totals it counts: the real ones as they were, its made-up drive added, dropped when it ends. */
    private var demoTotals: HashMap<String, Double>? = null
    private var totalsSavedAt = 0L

    fun setContext(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        load()
        scope.launch { tickLoop() }
        scope.launch { pollLoop() }
    }

    fun save(reading: MyReading, replacing: String?) {
        val rest = _list.value.filter { it.key != replacing && it.key != reading.key }
        val at = _list.value.indexOfFirst { it.key == replacing }.takeIf { it >= 0 } ?: _list.value.size
        _list.value = rest.toMutableList().apply { add(at.coerceAtMost(size), reading) }
        if (replacing != null && replacing != reading.key) {
            synchronized(totals) { totals.remove(replacing)?.let { totals[reading.key] = it } }
            synchronized(pids) { pids.remove(replacing) }
        }
        persist()
        // A feed set or taken off shows at once.
        tick(System.currentTimeMillis(), 0L)
    }

    fun delete(key: String) {
        _list.value = _list.value.filter { it.key != key }
        synchronized(totals) { totals.remove(key) }
        synchronized(pids) { pids.remove(key) }
        persist()
        // The last reading gone, the loop sleeps: what it fed is withdrawn here.
        tick(System.currentTimeMillis(), 0L)
    }

    /**
     * A frame heard by the CAN adapter ([UsbCan]): the readings of its id take
     * its value, and one that feeds a car element puts it on the bus at once,
     * so a door or an indicator shows without waiting for the next second.
     */
    fun canFrame(frame: CanFrame) {
        val list = _list.value
        var index = canIndex
        if (index.first !== list) {
            index = list to list.filter { it.canId != null }.groupBy { canSlot(it.canId!!, it.canExtended) }
            canIndex = index
        }
        val readings = index.second[canSlot(frame.id, frame.extended)] ?: return
        if (DemoMode.isOn) return
        val now = System.currentTimeMillis()
        for (r in readings) {
            val v = MyReadingRules.read(r, frame).value ?: continue
            synchronized(pids) { pids[r.key] = Answer(v, now, can = true) }
            r.feeds?.let { feedPublisher.set(it, v) }
        }
    }

    private fun canSlot(id: Int, extended: Boolean): Long = id.toLong() or (if (extended) 1L shl 32 else 0L)

    fun addAll(readings: List<MyReading>) {
        _list.value = _list.value + readings
        persist()
    }

    /** Back to 0: one total, or every one when [key] is null. */
    fun resetTotal(key: String?) {
        synchronized(totals) {
            for (t in listOfNotNull(totals, demoTotals)) if (key == null) t.clear() else t.remove(key)
        }
        saveTotals(force = true)
        tick(System.currentTimeMillis(), 0L)
    }

    /** The values Dashwheel reads itself, by the names formulas use. */
    private fun builtIn(): Map<String, Double?> {
        val linked = ObdBluetoothManager.connectionState.value == ObdConnectionState.CONNECTED
        val d = ObdBluetoothManager.readings()
        fun obd(v: Number) = if (linked) v.toDouble() else null
        val answered = ObdBluetoothManager.answered()
        return mapOf(
            "speed" to obd(d.speedKmh),
            "rpm" to obd(d.rpm),
            // 0 is "not read" in the record, and also a winter morning: a reading once the adapter answered that PID.
            "coolant" to obd(d.coolantTempC).takeIf { it != 0.0 || Pid.COOLANT in answered },
            "intake" to obd(d.intakeTempC).takeIf { it != 0.0 || Pid.INTAKE in answered },
            "throttle" to obd(d.throttlePct),
            "load" to obd(d.engineLoadPct),
            "fuel" to obd(d.fuelLevelPct).takeIf { it != 0.0 },
            "volts" to obd(d.voltage).takeIf { it != 0.0 },
            "gps_speed" to LocationFeed.location.value?.takeIf { it.hasSpeed() }?.let { it.speed * 3.6 },
            "lpg" to LpgTank.reading.value?.percent?.toDouble(),
            "fuel_flow" to d.fuelFlowLh
        )
    }

    private suspend fun tickLoop() {
        var last = System.currentTimeMillis()
        while (true) {
            // Asleep while there are no readings.
            if (_list.value.isEmpty()) {
                _list.first { it.isNotEmpty() }
                last = System.currentTimeMillis()
                continue
            }
            delay(TICK_MS)
            val now = System.currentTimeMillis()
            tick(now, now - last)
            last = now
        }
    }

    /** Works every value out, then adds [gapMs] of each total's rate. */
    private fun tick(now: Long, gapMs: Long) {
        val list = _list.value
        if (list.isEmpty()) {
            if (_values.value.isNotEmpty()) _values.value = emptyMap()
            feedPublisher.clear()
            return
        }
        val builtIn = builtIn()
        val answers = synchronized(pids) {
            pids.entries.removeAll { now - it.value.at > (if (it.value.can) CAN_STALE_MS else STALE_MS) || it.value.demo != DemoMode.isOn }
            pids.mapValues { it.value.value }
        }
        // The demo's made-up drive counts in a copy of its own: the driver's real totals never see it.
        val demo = DemoMode.isOn
        val counted = synchronized(totals) {
            demoTotals = if (demo) demoTotals ?: HashMap(totals) else null
            HashMap(demoTotals ?: totals)
        }
        val values = MyReadingRules.values(list, builtIn, answers, counted)
        _values.value = values
        // The demo is the bus's only source of made-up values: nothing of the driver's is fed while it runs.
        if (demo) feedPublisher.clear() else feedPublisher.setAll(MyReadingRules.feeds(list, values))
        if (gapMs in 1..MAX_GAP_MS) {
            val rates = MyReadingRules.rates(list, builtIn, values)
            if (rates.isNotEmpty()) {
                synchronized(totals) {
                    val into = demoTotals ?: totals
                    rates.forEach { (k, perHour) -> into[k] = (into[k] ?: 0.0) + perHour * gapMs / 3_600_000.0 }
                }
                if (!demo) saveTotals(force = false)
            }
        }
    }

    /**
     * The demo's answer to a standard request, from its made-up drive: the
     * live values the regular polls also read, and the air flow those revs
     * and that load would draw through a 1.6 L engine. Null for the others.
     */
    private fun demoAnswer(request: String): Double? {
        val d = ObdBluetoothManager.readings()
        return when (request) {
            "010D" -> d.speedKmh.toDouble()
            "010C" -> d.rpm.toDouble()
            "0105" -> d.coolantTempC.toDouble()
            "010F" -> d.intakeTempC.toDouble()
            "0111" -> d.throttlePct.toDouble()
            "0104" -> d.engineLoadPct.toDouble()
            "0110" -> d.rpm / 60.0 / 2 * 1.6 * 1.2 * (0.15 + d.engineLoadPct / 100.0)
            else -> null
        }
    }

    private suspend fun pollLoop() {
        while (true) {
            if (_list.value.isEmpty()) {
                _list.first { it.isNotEmpty() }
                continue
            }
            val asks = _list.value.filter { it.kind == MyReadingKind.PID }
            if (asks.isNotEmpty() && DemoMode.isOn) {
                val now = System.currentTimeMillis()
                asks.filter { it.header == null }.forEach { r -> demoAnswer(r.request)?.let { synchronized(pids) { pids[r.key] = Answer(it, now, demo = true) } } }
            } else if (asks.isNotEmpty() && ObdBluetoothManager.connectionState.value == ObdConnectionState.CONNECTED) {
                // One set-up per computer and session, as the advanced readings do.
                for ((_, group) in asks.groupBy { Triple(it.header, it.replyAddress, it.session) }) {
                    val r0 = group.first()
                    val replies = ObdBluetoothManager.queryAll(r0.header, group.map { it.request }, TIMEOUT_MS, r0.replyAddress, r0.session)
                    val now = System.currentTimeMillis()
                    group.forEachIndexed { i, r ->
                        val v = MyReadingRules.read(r, replies.getOrNull(i)).value ?: return@forEachIndexed
                        synchronized(pids) { pids[r.key] = Answer(v, now) }
                    }
                }
            }
            delay(POLL_MS)
        }
    }

    /** One try of [r] as typed, for the editor: the car's raw answer for a request, and the value or what's wrong. */
    data class Test(val reply: String?, val result: Formula.Result)

    suspend fun test(r: MyReading, all: List<MyReading>): Test {
        if (r.kind == MyReadingKind.CAN) {
            val id = r.canId ?: return Test(null, Formula.Result(null, Formula.Problem.NO_VALUE))
            val frame = UsbCan.last(id, r.canExtended) ?: return Test(null, Formula.Result(null, Formula.Problem.NO_VALUE))
            return Test("${frame.idText}: ${frame.dataText}", MyReadingRules.read(r, frame))
        }
        if (r.kind == MyReadingKind.PID) {
            val reply = ObdBluetoothManager.query(r.header, r.request, TIMEOUT_MS * 2, r.replyAddress, r.session)
            return Test(reply, MyReadingRules.read(r, reply))
        }
        val others = all.filter { it.key != r.key }
        val builtIn = builtIn()
        val values = _values.value
        val names = object : Formula.Names {
            override fun known(name: String) = name in MyReadingRules.BUILT_IN || others.any { it.key == name }
            override fun value(name: String) = values[name] ?: builtIn[name]
        }
        return Test(null, Formula.evaluate(r.formula, null, names))
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load() {
        val p = prefs() ?: return
        _list.value = runCatching {
            val a = JSONArray(p.getString("list", "[]"))
            (0 until a.length()).mapNotNull { MyReading.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        runCatching {
            val o = JSONObject(p.getString("totals", "{}") ?: "{}")
            synchronized(totals) { o.keys().forEach { k -> totals[k] = o.getDouble(k) } }
        }
    }

    private fun persist() {
        prefs()?.edit()?.putString("list", JSONArray(_list.value.map { it.toJson() }).toString())?.apply()
        saveTotals(force = true)
    }

    private fun saveTotals(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - totalsSavedAt < SAVE_TOTALS_MS) return
        totalsSavedAt = now
        val o = JSONObject()
        synchronized(totals) { totals.forEach { (k, v) -> o.put(k, v) } }
        prefs()?.edit()?.putString("totals", o.toString())?.apply()
    }
}
