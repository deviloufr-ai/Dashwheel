package com.openauto.dash

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Reads the head unit's **CANbox/MCU** stream by tailing the `mcu_services`
 * logcat (needs root). The MCU logs every car value as
 * `dispatchToClients - cmdId: X - data : [ .. ]`, so we parse those into a live
 * map of `key -> latest bytes`, tracking when each last changed (for the CAN
 * monitor's "flip" highlight and, later, to read door/fuel/light state).
 *
 * cmdId 65 (control 0x41) is a multiplexed status channel `41 fd <sub> ..`, so
 * we split those into per-`sub` keys ("65.0C", "65.05", …) to keep each value
 * stable instead of overwriting each other.
 */
object McuReader {

    data class Entry(val key: String, val cmdId: Int, val bytes: List<Int>, val hex: String, val changedAt: Long)

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    /** One frame just took a new value; [previousHex] is null the first time its key is seen. */
    data class Change(val entry: Entry, val previousHex: String?)

    // Every change, in order: [entries] only keeps the latest state, so a quick
    // press-and-release (a steering wheel button, SteeringWheelStore) would be lost.
    private val _changes = MutableSharedFlow<Change>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val changes: SharedFlow<Change> = _changes.asSharedFlow()

    /**
     * The door bitfield byte from the MCU status frame `41 FD 0C 38 <bits> …`
     * (cmdId 65, sub 0x0C, subtype 0x38). Confirmed on the C4 Picasso: bit 0x80
     * = front-left door. Other bits map to the other doors (to be confirmed).
     * null until first seen.
     */
    private val _doorBits = MutableStateFlow<Int?>(null)
    val doorBits: StateFlow<Int?> = _doorBits.asStateFlow()

    /** Decoded door open/closed state (C4 Picasso bit map, confirmed 2026-09-20). */
    data class DoorState(
        val frontLeft: Boolean = false,
        val frontRight: Boolean = false,
        val rearLeft: Boolean = false,
        val rearRight: Boolean = false,
        val tailgate: Boolean = false,
        val bonnet: Boolean = false
    ) {
        val anyOpen: Boolean get() = frontLeft || frontRight || rearLeft || rearRight || tailgate || bonnet
    }

    private val _doorState = MutableStateFlow<DoorState?>(null)
    val doorState: StateFlow<DoorState?> = _doorState.asStateFlow()

    // --- Fuel (CANbox) ------------------------------------------------------
    // The C4 Picasso's OBD does not report fuel level, but the CANbox does —
    // somewhere in the MCU stream as a raw byte. We can't know which byte a
    // priori (the firmware is stripped), so it's *learned*: the user picks the
    // byte matching their dash gauge in the Range widget's finder, with a
    // one-point calibration. [fullRaw] is the raw value that equals a full tank.
    data class FuelMapping(val key: String, val byteIndex: Int, val fullRaw: Int)

    @Volatile
    private var fuelMapping: FuelMapping? = null
    val fuelConfigured: Boolean get() = fuelMapping != null

    private val _fuelPercent = MutableStateFlow<Int?>(null)
    /** Live fuel level 0..100 decoded from the learned CANbox byte, or null. */
    val fuelPercent: StateFlow<Int?> = _fuelPercent.asStateFlow()

    // --- Range (CANbox) -----------------------------------------------------
    // The head unit's own trip computer shows the car's distance to empty, so
    // it's in the MCU stream too. Learned the same way: the user types the km
    // the trip computer shows and picks the word that holds it.

    @Volatile
    private var rangeMapping: RangeMapping? = null
    val rangeConfigured: Boolean get() = rangeMapping != null

    private val _rangeKm = MutableStateFlow<Int?>(null)
    /** The car's own distance to empty in km, from the learned CANbox word, or null. */
    val rangeKm: StateFlow<Int?> = _rangeKm.asStateFlow()

    private var appContext: Context? = null
    private const val PREFS = "mcu_prefs"

    /** Give McuReader an app context so the learned fuel mapping can persist. */
    fun setContext(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            loadFuelMapping()
            loadRangeMapping()
        }
    }

    private fun loadRangeMapping() {
        val p = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return
        val key = p.getString("range_key", null) ?: return
        val idx = p.getInt("range_byte", -1)
        val scale = p.getInt("range_scale", 1)
        if (idx >= 0 && scale > 0) rangeMapping = RangeMapping(key, idx, p.getBoolean("range_be", true), scale)
    }

    /** Persist the learned range word; decodes from the frame already seen, else the next one. */
    internal fun saveRangeMapping(m: RangeMapping) {
        rangeMapping = m
        setRange(_entries.value.firstOrNull { it.key == m.key }?.let { m.decode(it.bytes) })
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putString("range_key", m.key)
            ?.putInt("range_byte", m.index)
            ?.putBoolean("range_be", m.bigEndian)
            ?.putInt("range_scale", m.scale)
            ?.apply()
    }

    /** Forget the learned range word (e.g. to pick another one). */
    fun clearRangeMapping() {
        rangeMapping = null
        setRange(null)
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.remove("range_key")?.remove("range_byte")?.remove("range_be")?.remove("range_scale")?.apply()
    }

    private fun loadFuelMapping() {
        val p = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return
        val key = p.getString("fuel_key", null) ?: return
        val idx = p.getInt("fuel_byte", -1)
        val full = p.getInt("fuel_fullraw", -1)
        if (idx >= 0 && full > 0) fuelMapping = FuelMapping(key, idx, full)
    }

    /** Persist the learned fuel byte + calibration; takes effect on the next frame. */
    fun saveFuelMapping(key: String, byteIndex: Int, fullRaw: Int) {
        val fm = FuelMapping(key, byteIndex, fullRaw.coerceAtLeast(1))
        fuelMapping = fm
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putString("fuel_key", fm.key)
            ?.putInt("fuel_byte", fm.byteIndex)
            ?.putInt("fuel_fullraw", fm.fullRaw)
            ?.apply()
    }

    // --- Fuel Finder: persisted Capture A ------------------------------------
    // The two capture points are taken minutes to days apart (fuel only drops by
    // actually driving), so Capture A is saved to prefs and survives closing the
    // dialog and app restarts. Capture B is then taken later against this A.

    /** Persist Capture A (fuel % + a snapshot of every frame's bytes). */
    fun saveFuelCaptureA(pct: Int, snapshot: Map<String, List<Int>>) {
        val json = JSONObject()
        snapshot.forEach { (k, bytes) -> json.put(k, JSONArray(bytes)) }
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putInt("fuelcapA_pct", pct)
            ?.putString("fuelcapA_data", json.toString())
            ?.apply()
    }

    /** The saved Capture A (fuel % to frame→bytes), or null if none is stored. */
    fun loadFuelCaptureA(): Pair<Int, Map<String, List<Int>>>? {
        val p = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return null
        val pct = p.getInt("fuelcapA_pct", 0)
        val data = p.getString("fuelcapA_data", null) ?: return null
        if (pct <= 0) return null
        return runCatching {
            val obj = JSONObject(data)
            val map = LinkedHashMap<String, List<Int>>()
            obj.keys().forEach { k ->
                val arr = obj.getJSONArray(k)
                map[k] = (0 until arr.length()).map { arr.getInt(it) }
            }
            pct to (map as Map<String, List<Int>>)
        }.getOrNull()
    }

    /** Discard the saved Capture A (on Reset or once a mapping is chosen). */
    fun clearFuelCaptureA() {
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.remove("fuelcapA_pct")?.remove("fuelcapA_data")?.apply()
    }

    /** Forget the learned fuel byte (e.g. to re-run the finder). */
    fun clearFuelMapping() {
        fuelMapping = null
        setFuel(null)
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.remove("fuel_key")?.remove("fuel_byte")?.remove("fuel_fullraw")?.apply()
    }

    // The car's real doors, fuel and range, kept up to date while the demo shows
    // its own and put back when it ends.
    @Volatile private var realDoors: DoorState? = null
    @Volatile private var realFuel: Int? = null
    @Volatile private var realRange: Int? = null

    private fun setDoors(v: DoorState?) {
        realDoors = v
        if (!DemoMode.isOn) _doorState.value = v
    }

    private fun setFuel(v: Int?) {
        realFuel = v
        if (!DemoMode.isOn) _fuelPercent.value = v
    }

    private fun setRange(v: Int?) {
        realRange = v
        if (!DemoMode.isOn) _rangeKm.value = v
    }

    /**
     * Fuel and range from the car app's shared data ([CarBox]), for whichever
     * of the two was never learned from the MCU stream: a learned byte wins.
     */
    internal fun carBoxWrite(fuelPercent: Int?, rangeKm: Int?) {
        if (fuelMapping == null && fuelPercent != null) setFuel(fuelPercent)
        if (rangeMapping == null && rangeKm != null) setRange(rangeKm)
    }

    /** [DemoMode]'s doors, fuel and range. */
    internal fun demoWrite(doors: DoorState?, fuelPercent: Int?, rangeKm: Int?) {
        _doorState.value = doors
        _fuelPercent.value = fuelPercent
        _rangeKm.value = rangeKm
    }

    /** The demo is over: the car's doors, fuel and range back, as they are now. */
    internal fun endDemo() {
        _doorState.value = realDoors
        _fuelPercent.value = realFuel
        _rangeKm.value = realRange
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // All guarded by this object's lock.
    private var job: Job? = null
    private var process: Process? = null
    private var refCount = 0
    private var shutdown: Job? = null
    // Sorted by key so publishing is a plain copy, no per-line sort. Only the reader thread touches it.
    private val latest = java.util.TreeMap<String, Entry>()

    private val regex = Regex("""dispatchToClients - cmdId:\s*(\d+)\s*-\s*data\s*:\s*\[([0-9a-fA-F ]*)]""")
    private val whitespace = Regex("\\s+")
    private const val HEX_DIGITS = "0123456789ABCDEF"

    /** How long the root logcat outlives its last user: a page swipe lets go and takes it again at once. */
    private const val LINGER_MS = 30_000L
    private const val RETRY_MIN_MS = 2_000L
    private const val RETRY_MAX_MS = 5 * 60_000L
    /** A logcat that ran this long was working: the next failure starts the backoff over. */
    private const val HEALTHY_MS = 60_000L

    @Synchronized
    fun start() {
        refCount++
        shutdown?.cancel()
        shutdown = null
        if (job?.isActive == true) return
        job = scope.launch { tail() }
    }

    @Synchronized
    fun stop() {
        if (refCount == 0) return
        if (--refCount > 0) return
        shutdown?.cancel()
        shutdown = scope.launch {
            delay(LINGER_MS)
            synchronized(this@McuReader) {
                if (refCount > 0) return@launch
                job?.cancel()
                job = null
                process?.let(::kill)
                process = null
            }
        }
    }

    /**
     * Runs `su logcat` and reads it for as long as the reader is wanted. When
     * it ends by itself (su refused at boot, logcat killed), it starts again,
     * waiting longer each time it fails quickly.
     */
    private suspend fun tail() {
        val ctx = currentCoroutineContext()
        var backoff = RETRY_MIN_MS
        while (ctx.isActive) {
            val startedAt = System.currentTimeMillis()
            // Started under the lock: a stop() can't slip in between and miss it.
            val p = synchronized(this) {
                if (!ctx.isActive) return
                runCatching { Runtime.getRuntime().exec(arrayOf("su", "-c", "exec logcat -s mcu_services:D")) }
                    .getOrNull()?.also { process = it }
            }
            if (p != null) {
                try {
                    val reader = p.inputStream.bufferedReader()
                    while (ctx.isActive) parse(reader.readLine() ?: break)
                } catch (e: IOException) {
                    // Killed by stop(), or logcat went away: same as its end.
                } finally {
                    synchronized(this) { if (process === p) process = null }
                    kill(p)
                }
            }
            if (System.currentTimeMillis() - startedAt >= HEALTHY_MS) backoff = RETRY_MIN_MS
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(RETRY_MAX_MS)
        }
    }

    /**
     * Ends [p]. Closing its output also ends the logcat that su started: a su
     * may not pass the kill on, but logcat dies on its next line to a closed pipe.
     */
    private fun kill(p: Process) {
        runCatching { p.inputStream.close() }
        runCatching { p.destroy() }
        runCatching { p.destroyForcibly() }
    }

    private fun parse(line: String) {
        val m = regex.find(line) ?: return
        val cmdId = m.groupValues[1].toIntOrNull() ?: return
        val bytes = m.groupValues[2].trim().split(whitespace)
            .filter { it.isNotEmpty() }
            .mapNotNull { it.toIntOrNull(16) }
        if (bytes.isEmpty()) return

        val sub = if (bytes.size >= 3 && bytes[1] == 0xfd) bytes[2] else -1
        val key = if (sub >= 0) cmdId.toString() + "." + hex2(sub) else cmdId.toString()
        // Runs for every line the CANbox logs (dozens a second): no String.format per byte.
        val hex = buildString(bytes.size * 3) {
            bytes.forEachIndexed { i, b ->
                if (i > 0) append(' ')
                append(HEX_DIGITS[(b shr 4) and 0x0F]).append(HEX_DIGITS[b and 0x0F])
            }
        }
        val prev = latest[key]
        // The CANbox repeats most frames several times a second; only a new
        // value is worth a new entry and waking every collector for.
        if (prev == null || prev.hex != hex) {
            val entry = Entry(key, cmdId, bytes, hex, System.currentTimeMillis())
            latest[key] = entry
            _entries.value = latest.values.toList()
            _changes.tryEmit(Change(entry, prev?.hex))
        }

        // Fuel: the learned CANbox byte → percent, calibrated against a full tank.
        fuelMapping?.let { fm ->
            if (key == fm.key && bytes.size > fm.byteIndex && fm.fullRaw > 0) {
                setFuel((bytes[fm.byteIndex] * 100 / fm.fullRaw).coerceIn(0, 100))
            }
        }

        // Range: the learned 16-bit word → km, straight from the car's trip computer.
        rangeMapping?.let { rm ->
            if (key == rm.key) rm.decode(bytes)?.let { setRange(it) }
        }

        // Door bitfield: cmdId 65, [.. 0C 38 <bits> ..] → byte index 4.
        if (cmdId == 65 && bytes.size > 4 && bytes[2] == 0x0C && bytes[3] == 0x38) {
            val b = bytes[4]
            _doorBits.value = b
            setDoors(doorsOf(b))
        }
    }

    /** The doors from the car app's shared data ([CarBox]), the same bits as the MCU frame's. */
    internal fun carBoxDoors(bits: Int) = setDoors(doorsOf(bits))

    private fun doorsOf(b: Int) = DoorState(
        frontLeft = b and 0x80 != 0,
        frontRight = b and 0x40 != 0,
        rearLeft = b and 0x20 != 0,
        rearRight = b and 0x10 != 0,
        tailgate = b and 0x08 != 0,
        bonnet = b and 0x04 != 0
    )

    private fun hex2(b: Int): String = charArrayOf(HEX_DIGITS[(b shr 4) and 0x0F], HEX_DIGITS[b and 0x0F]).concatToString()
}
