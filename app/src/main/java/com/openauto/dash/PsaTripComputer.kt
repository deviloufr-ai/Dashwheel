package com.openauto.dash

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import com.openauto.dash.vehicle.ignitionFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/*
 * The trip computer of a Peugeot, Citroën or DS, as its CAN box (RZC protocol)
 * tells the head unit: instant consumption, range, both trips and the car's
 * warning messages. The car app keeps these to its own screen (its shared data
 * stays empty for these cars), so they are read off the CAN box stream itself
 * ([McuFeed], cmd 65, or the root log through [McuReader]). Frames are
 * `FD LEN TYPE data… sum`: LEN counts itself, the type, the data and the sum;
 * the sum is the low byte of LEN + TYPE + data. Worked out from the firmware
 * (PeugeotDataParser), not yet seen on the car.
 */

/**
 * The CAN box's frames out of its stream, which comes in pieces that need not
 * end on a frame: `FD LEN TYPE data… sum`, a frame kept only when its sum is right.
 */
internal class RzcFrames {
    private val buffer = ByteArray(512)
    private var filled = 0

    /** Adds [bytes] from [from] on and hands every whole frame to [onFrame] (type, data). */
    fun push(bytes: ByteArray, from: Int, onFrame: (Int, ByteArray) -> Unit) {
        for (i in from until bytes.size) {
            if (filled == buffer.size) filled = 0
            buffer[filled++] = bytes[i]
        }
        var start = 0
        while (true) {
            // Resync on a frame head.
            while (start < filled && !isHead(buffer[start])) start++
            if (filled - start < 4) break
            val len = buffer[start + 1].toInt() and 0xFF
            // No frame of this box is longer: a stray head byte would hold the stream up.
            if (len < 3 || len > MAX_LEN) { start++; continue }
            if (filled - start < len + 1) break
            val frame = buffer.copyOfRange(start, start + len + 1)
            if (sumOk(frame)) {
                onFrame(frame[2].toInt() and 0xFF, frame.copyOfRange(3, frame.size - 1))
                start += frame.size
            } else {
                start++
            }
        }
        System.arraycopy(buffer, start, buffer, 0, filled - start)
        filled -= start
    }

    private fun isHead(b: Byte) = b == 0xFD.toByte() || b == 0xFC.toByte()

    companion object {
        private const val MAX_LEN = 64

        /** The low byte of LEN + TYPE + data equals the last byte. */
        fun sumOk(frame: ByteArray): Boolean {
            var sum = 0
            for (i in 1 until frame.size - 1) sum += frame[i].toInt() and 0xFF
            return (sum and 0xFF) == (frame.last().toInt() and 0xFF)
        }
    }
}

/** One trip of the trip computer; null where the car says "--". */
data class PsaTripPage(val avgL100: Float?, val avgKmh: Int?, val distanceKm: Int?)

/** The trip computer as the car last told it. */
data class PsaTrip(
    /** Instant consumption, L/100 km. */
    val instantL100: Float? = null,
    val rangeKm: Int? = null,
    val toDestinationKm: Int? = null,
    val trip1: PsaTripPage? = null,
    val trip2: PsaTripPage? = null,
    /** The car's warning codes now (0x3A), its "no message" codes left out. */
    val warnings: List<Int> = emptyList(),
    val at: Long = 0L
)

object PsaTripComputer {
    private const val TAG = "PsaTrip"

    internal const val TYPE_PAGE0 = 0x33
    internal const val TYPE_TRIP1 = 0x34
    internal const val TYPE_TRIP2 = 0x35
    internal const val TYPE_WARNINGS = 0x3A
    private const val TYPE_QUERY = 0x8F

    /** A reading older than this is not shown. */
    private const val FRESH_MS = 5 * 60_000L
    /** The pages are asked again this often while someone looks. */
    private const val QUERY_EVERY_MS = 30_000L

    private val _state = MutableStateFlow<PsaTrip?>(null)
    val state: StateFlow<PsaTrip?> = _state

    /** The demo's trip computer and its warnings ([DemoMode]); its end puts back what the car said ([trip]). */
    internal fun demoWrite(trip: PsaTrip?) {
        _state.value = trip
    }

    /** [state] while fresh. */
    fun fresh(): PsaTrip? = _state.value?.takeIf { SystemClock.elapsedRealtime() - it.at <= FRESH_MS }

    /** Whether the car in the profile has this trip computer: the frame types mean other things on other makes. */
    fun forThisCar(): Boolean = isPsa(CarProfileStore.current.make)

    internal fun isPsa(make: String): Boolean {
        val m = make.trim().lowercase()
        return m.startsWith("peugeot") || m.startsWith("citro") || m == "ds" || m.startsWith("ds ") || m.startsWith("ds automobiles")
    }

    private val frames = RzcFrames()

    /** Bytes of the CAN box stream from [from] on (an MCU message, cmd 65 first). */
    @Synchronized
    fun feed(bytes: ByteArray, from: Int) {
        if (!forThisCar()) return
        frames.push(bytes, from, ::onFrame)
    }

    private fun onFrame(type: Int, d: ByteArray) {
        val was = _state.value ?: PsaTrip()
        val next = decode(was, type, d) ?: return
        val now = SystemClock.elapsedRealtime()
        // Repeats of the same page only refresh its time.
        _state.value = next.copy(at = now)
        if (type == TYPE_PAGE0) next.rangeKm?.let { McuReader.carBoxWrite(null, it) }
        if (type == TYPE_WARNINGS) PsaWarnings.onCodes(next.warnings, was.warnings)
    }

    /** [was] with the page of [type] read from its data [d]; null for a frame that isn't one of these pages. */
    internal fun decode(was: PsaTrip, type: Int, d: ByteArray): PsaTrip? = when (type) {
        TYPE_PAGE0 -> if (d.size < 6) null else was.copy(
            instantL100 = u16(d, 0).takeIf { it <= 300 }?.let { it / 10f },
            rangeKm = u16(d, 2).takeIf { it in 1..2000 },
            toDestinationKm = u16(d, 4).takeIf { it <= 6000 }
        )
        TYPE_TRIP1, TYPE_TRIP2 -> if (d.size < 6) null else {
            val page = PsaTripPage(
                avgL100 = u16(d, 0).takeIf { it <= 300 }?.let { it / 10f },
                avgKmh = u16(d, 2).takeIf { it <= 250 },
                distanceKm = u16(d, 4).takeIf { it <= 9999 }
            )
            if (type == TYPE_TRIP1) was.copy(trip1 = page) else was.copy(trip2 = page)
        }
        TYPE_WARNINGS -> if (d.isEmpty()) null else {
            val n = (d[0].toInt() and 0xFF).coerceAtMost(d.size - 1)
            was.copy(warnings = (1..n).map { d[it].toInt() and 0xFF }.filter { it != 0x00 && it != 0xF0 && it != 0xFF && it !in PsaWarnings.notices }.distinct())
        }
        else -> null
    }

    private fun u16(d: ByteArray, i: Int) = ((d[i].toInt() and 0xFF) shl 8) or (d[i + 1].toInt() and 0xFF)

    // --- Asking the CAN box ----------------------------------------------------

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watchers = 0
    private var poll: Job? = null

    /**
     * Someone shows the trip computer: the stream is read, and the pages are
     * asked for now and every [QUERY_EVERY_MS], as the car app does when its
     * own trip screen opens. [unwatch] when done.
     */
    @Synchronized
    fun watch(context: Context) {
        if (watchers++ > 0) return
        McuReader.start()
        val app = context.applicationContext
        poll = scope.launch {
            while (isActive) {
                if (forThisCar() && !DemoMode.isOn) CanBoxLink.ask(app, listOf(TYPE_PAGE0, TYPE_TRIP1, TYPE_TRIP2, TYPE_WARNINGS).map { queryFrame(it) })
                delay(QUERY_EVERY_MS)
            }
        }
    }

    @Synchronized
    fun unwatch() {
        if (watchers == 0 || --watchers > 0) return
        poll?.cancel()
        poll = null
        McuReader.stop()
    }

    private var started = false
    /** The car's warnings and range are asked for this often while the ignition is on, tile or none. */
    private const val ASK_EVERY_MS = 5 * 60_000L
    /** The CAN box wakes with the car: the first question waits this long. */
    private const val FIRST_ASK_MS = 15_000L
    /** Without the MCU service, the root log is read this long around each question, for the answer. */
    private const val LOG_WINDOW_MS = 30_000L

    /**
     * From the start: the car's warnings (and the range) asked for when the
     * ignition comes on and every [ASK_EVERY_MS] while it is, so a warning
     * shows and is said without any tile on screen. The car app itself asks
     * only when its UART starts and when its trip screen opens.
     */
    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        PsaWarnings.setContext(context)
        val app = context.applicationContext
        scope.launch {
            // An ignition the unit doesn't tell (null) counts as on.
            ignitionFlow().collectLatest { on ->
                if (on == false) return@collectLatest
                delay(FIRST_ASK_MS)
                while (isActive) {
                    if (forThisCar() && !DemoMode.isOn) {
                        val questions = listOf(TYPE_WARNINGS, TYPE_PAGE0).map { queryFrame(it) }
                        if (McuFeed.listening.value) {
                            CanBoxLink.ask(app, questions)
                        } else {
                            McuReader.start()
                            // Let go even when the ignition goes off meanwhile (collectLatest cancels).
                            try {
                                CanBoxLink.ask(app, questions)
                                delay(LOG_WINDOW_MS)
                            } finally {
                                McuReader.stop()
                            }
                        }
                    }
                    delay(ASK_EVERY_MS)
                }
            }
        }
    }

    /** The car app's own question for one page: `FD 04 8F type sum` (the service puts the sum in). */
    internal fun queryFrame(type: Int) = byteArrayOf(0xFD.toByte(), 0x04, TYPE_QUERY.toByte(), type.toByte(), 0)

    internal fun log(msg: String, e: Throwable? = null) = Log.w(TAG, msg, e)
}

/*
 * The car app's CAN service (com.qf.vehicle, exported, no permission): one call,
 * "write this frame to the CAN box" (ICanBusServiceFeature tx 25,
 * doCheckSumAndWriteData2Uart4CanBox), which works the sum out itself. Bound
 * while there is something to send, let go a minute after.
 */
internal object CanBoxLink {
    private const val PACKAGE = "com.qf.vehicle"
    private const val ACTION = "com.qf.vehicle.service.ACTION_CAN_SERVICE"
    private const val DESCRIPTOR = "com.qf.vehicle.service.ICanBusServiceFeature"
    private const val TX_WRITE = 25
    private const val LINGER_MS = 60_000L

    private var binder: IBinder? = null
    private var bound = false
    private var pending: List<ByteArray> = emptyList()
    private var unbindAt = 0L
    private var appContext: Context? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val frames = synchronized(this@CanBoxLink) {
                binder = service
                pending.also { pending = emptyList() }
            }
            if (frames.isNotEmpty()) Thread { write(frames) }.start()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(this@CanBoxLink) { binder = null }
        }
    }

    /**
     * Writes [frames] to the CAN box: through the MCU, as the car app does on
     * units where the box sits behind it, and through the car app's CAN
     * service as well (binding it first when needed) for units where it
     * doesn't. A question asked twice is answered twice, nothing worse.
     */
    fun ask(context: Context, frames: List<ByteArray>) {
        for (f in frames) McuFeed.send(McuFeed.CMD_TO_CAN, withSum(f))
        val app = context.applicationContext
        val now = SystemClock.elapsedRealtime()
        val b = synchronized(this) {
            appContext = app
            unbindAt = now + LINGER_MS
            if (binder == null) {
                pending = frames
                if (!bound) {
                    bound = runCatching {
                        app.bindService(Intent(ACTION).setPackage(PACKAGE), connection, Context.BIND_AUTO_CREATE)
                    }.onFailure { PsaTripComputer.log("no CAN service", it) }.getOrDefault(false)
                }
                null
            } else binder
        }
        if (b != null) write(frames)
        // Let go of the service once nobody asked for a while.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ releaseIfIdle() }, LINGER_MS + 1_000L)
    }

    private fun write(frames: List<ByteArray>) {
        val b = synchronized(this) { binder } ?: return
        for (f in frames) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                data.writeByteArray(f)
                b.transact(TX_WRITE, data, reply, 0)
                reply.readException()
            } catch (e: Exception) {
                PsaTripComputer.log("CAN box write failed", e)
                return
            } finally {
                data.recycle()
                reply.recycle()
            }
            // The car app leaves no gap either, but the box reads a frame at a time.
            Thread.sleep(40)
        }
    }

    /** [frame] with its last byte set to the sum of LEN, TYPE and data, as the CAN box checks it. */
    internal fun withSum(frame: ByteArray): ByteArray {
        val out = frame.copyOf()
        var sum = 0
        for (i in 1 until out.size - 1) sum += out[i].toInt() and 0xFF
        out[out.size - 1] = sum.toByte()
        return out
    }

    @Synchronized
    private fun releaseIfIdle() {
        if (!bound || SystemClock.elapsedRealtime() < unbindAt) return
        runCatching { appContext?.unbindService(connection) }
        bound = false
        binder = null
    }
}

/*
 * The car's own warning messages (0x3A), the ones worth a line on screen
 * and, for the serious ones, a word out loud when car warnings are spoken.
 * Codes are the CAN box's (PeugeotDataParser.getWarningString); a code not
 * listed shows as its number.
 */
internal object PsaWarnings {
    enum class Level { STOP, SERVICE, INFO }

    class Warning(val textRes: Int, val level: Level)

    private fun stop(res: Int) = Warning(res, Level.STOP)
    private fun service(res: Int) = Warning(res, Level.SERVICE)
    private fun info(res: Int) = Warning(res, Level.INFO)

    // Every code the car app has a warning for (getWarningString). Its notices
    // that are no warning (an aid switched on, a door locked, a drive mode) are
    // left out, and so are the ones Dashwheel has its own alert for (belts).
    private val known: Map<Int, Warning> = mapOf(
        // Stop the car.
        0x0B to stop(R.string.car_warn_engine_temp),
        0x0E to stop(R.string.car_warn_oil_pressure),
        0x0F to stop(R.string.car_warn_brakes),
        0x1A to stop(R.string.car_warn_brakes),
        0x76 to stop(R.string.car_warn_engine_stop),
        0x12 to stop(R.string.car_warn_puncture),
        0x7F to stop(R.string.car_warn_puncture),
        0x80 to stop(R.string.car_warn_puncture),
        0x81 to stop(R.string.car_warn_puncture),
        0x82 to stop(R.string.car_warn_puncture),
        // See a garage.
        0x0C to service(R.string.car_warn_coolant),
        0x0D to service(R.string.car_warn_oil_level),
        0x13 to service(R.string.car_warn_dpf),
        0x14 to service(R.string.car_warn_suspension),
        0x15 to service(R.string.car_warn_suspension),
        0x16 to service(R.string.car_warn_steering),
        0x1B to service(R.string.car_warn_brake_pads),
        0x1C to service(R.string.car_warn_parking_brake),
        0x6F to service(R.string.car_warn_parking_brake),
        0x1E to service(R.string.car_warn_abs),
        0x1F to service(R.string.car_warn_esp),
        0x4A to service(R.string.car_warn_esp),
        0x20 to service(R.string.car_warn_gearbox),
        0x21 to service(R.string.car_warn_gearbox),
        0x22 to service(R.string.car_warn_cruise),
        0x23 to service(R.string.car_warn_additive),
        0x79 to service(R.string.car_warn_additive),
        0x24 to service(R.string.car_warn_immobiliser),
        0x5F to service(R.string.car_warn_immobiliser),
        0x27 to service(R.string.car_warn_battery),
        0x5A to service(R.string.car_warn_airbag),
        0x5B to service(R.string.car_warn_water_diesel),
        0x60 to service(R.string.car_warn_steering_lock),
        0x64 to service(R.string.car_warn_hybrid),
        0x65 to service(R.string.car_warn_hybrid),
        0x67 to service(R.string.car_warn_hybrid),
        0x66 to service(R.string.car_warn_headlight_levelling),
        0x6E to service(R.string.car_warn_headlight_levelling),
        0x6D to service(R.string.car_warn_driving_aid),
        0x25 to service(R.string.car_warn_driving_aid),
        0x26 to service(R.string.car_warn_driving_aid),
        0x78 to service(R.string.car_warn_driving_aid),
        0x70 to service(R.string.car_warn_driving_aid),
        0x77 to service(R.string.car_warn_engine_repair),
        0x85 to service(R.string.car_warn_pollution),
        0x86 to service(R.string.car_warn_pollution_start),
        // A bulb out: a garage or a bulb, and the others on the road can't see the car brake or turn.
        0x29 to service(R.string.car_warn_bulb_dipped),
        0x2A to service(R.string.car_warn_bulb_main),
        0x2B to service(R.string.car_warn_bulb_brake),
        0x61 to service(R.string.car_warn_bulb_brake_middle),
        0x83 to service(R.string.car_warn_bulb_brake_right_rear),
        0x84 to service(R.string.car_warn_bulb_brake_left_rear),
        0x2C to service(R.string.car_warn_bulb_fog),
        0x2D to service(R.string.car_warn_bulb_indicator),
        0x2E to service(R.string.car_warn_bulb_reversing),
        0x2F to service(R.string.car_warn_bulb_sidelight),
        // Good to know.
        0x11 to info(R.string.car_warn_open),
        0x53 to info(R.string.car_warn_open),
        0x54 to info(R.string.car_warn_open),
        0x55 to info(R.string.car_warn_open),
        0x56 to info(R.string.car_warn_open),
        0x57 to info(R.string.car_warn_open),
        0x58 to info(R.string.car_warn_open),
        0x28 to info(R.string.car_warn_tyres),
        0x39 to info(R.string.car_warn_tyres),
        0x38 to info(R.string.car_warn_tyres),
        0x7B to info(R.string.car_warn_tyres),
        0x7C to info(R.string.car_warn_tyres),
        0x7D to info(R.string.car_warn_tyres),
        0x7E to info(R.string.car_warn_tyres),
        0x37 to info(R.string.car_warn_tpms),
        0x5C to info(R.string.car_warn_tpms),
        0x6A to info(R.string.car_warn_tpms),
        0x71 to info(R.string.car_warn_tpms),
        0x72 to info(R.string.car_warn_tpms),
        0x73 to info(R.string.car_warn_tpms),
        0x74 to info(R.string.car_warn_tpms),
        0x75 to info(R.string.car_warn_tpms),
        0x33 to info(R.string.car_warn_washer),
        0x34 to info(R.string.car_warn_fuel),
        0x31 to info(R.string.car_warn_ice),
        0x36 to info(R.string.car_warn_key_battery)
    )

    /** The car's notices that are no warning: never shown, never said. */
    internal val notices: Set<Int> = setOf(
        0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x30, 0x32, 0x3A, 0x3B, 0x3C, 0x3D, 0x3E, 0x3F,
        0x40, 0x41, 0x42, 0x43, 0x4B, 0x4C, 0x4D, 0x4E, 0x4F, 0x51, 0x59, 0x62, 0x63, 0x6C,
        // Hard tops, a rear wing and a trailer: not on these cars; the rest says nothing to act on.
        0x10, 0x17, 0x18, 0x19, 0x1D, 0x35, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x50, 0x52, 0x5D, 0x5E, 0x68, 0x69,
        0x6B, 0x7A, 0x87
    )

    fun of(code: Int): Warning? = known[code]

    /**
     * The car's messages for the engine computer's own warning lamp: the
     * same fault as its OBD fault codes, said with less (Car problems leaves
     * them out once the codes are read).
     */
    val engineLamp: Set<Int> = setOf(0x77, 0x85, 0x86)

    /**
     * The car's warnings showing now, worded in English for the AI mechanic's
     * prompt ("left rear brake bulb...: see a garage"); notices and unknown
     * codes left out. Empty when the car has sent none.
     */
    fun forPrompt(): List<String> {
        val context = appContext ?: return emptyList()
        // The same list the Car problems tile shows: the last the car sent.
        val codes = PsaTripComputer.state.value?.warnings.orEmpty()
        if (codes.isEmpty()) return emptyList()
        val english = context.createConfigurationContext(
            android.content.res.Configuration(context.resources.configuration).apply { setLocale(java.util.Locale.ENGLISH) }
        ).resources
        return codes.mapNotNull { known[it] }.distinctBy { it.textRes }.map { w ->
            english.getString(w.textRes) + when (w.level) {
                Level.STOP -> " (stop the car)"
                Level.SERVICE -> " (see a garage)"
                Level.INFO -> ""
            }
        }
    }

    @Volatile private var appContext: Context? = null

    fun setContext(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * New codes since [before]: the serious ones are said, once while they
     * last. Low fuel, ice and an open door have their own spoken alerts.
     */
    fun onCodes(now: List<Int>, before: List<Int>) {
        val context = appContext ?: return
        if (DemoMode.isOn) return
        for (code in now - before.toSet()) {
            val w = known[code] ?: continue
            if (w.level == Level.INFO) continue
            if (!SpokenEvents.isOn(context, SpokenEvent.CAR_TIPS)) return
            val res = AppLanguage.wrap(context).resources
            CarVoice.announce(res.getString(R.string.car_say_car_warning, res.getString(w.textRes)), InAppNav.locale(context), urgent = w.level == Level.STOP)
        }
    }
}
