package com.openauto.dash

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/*
 * A USB-CAN adapter of the driver's own on slcan firmware (CANable, CANable
 * 2.0 and their clones: a virtual serial port, 16d0:117e for the CANable),
 * wired to the car's CAN-H, CAN-L and ground. It only ever listens: the
 * channel is opened listen-only ("L", or CANable's silent mode then "O"),
 * and an adapter that can do neither is not opened at all, so Dashwheel never
 * sends on the car's bus, not even an acknowledgement.
 *
 * What it hears goes to My readings: a reading "From the CAN bus" names a
 * frame's id and works its value out of the frame's bytes, and can feed a
 * car element (fuel level, doors, indicators...) on the vehicle bus
 * ([ReadingFeed]). The frames heard are also listed on its settings page,
 * to find which one moves with the pedal or the fuel gauge.
 *
 * Only the device the driver picked in Settings is ever opened, as for the
 * USB tyre receiver ([UsbTpms]): a head unit can have serial chips of its own.
 */

/** What the CAN adapter is doing, for its settings page. */
enum class UsbCanState { OFF, UNPLUGGED, NOT_ALLOWED, BUSY, NOT_SLCAN, NO_LISTEN_ONLY, LISTENING, RECEIVING }

/** One frame id as heard: its last frame, how many came, and how many of them changed the data. */
data class CanSeen(val frame: CanFrame, val count: Int, val changes: Int, val at: Long)

internal object UsbCan {
    private const val TAG = "UsbCan"
    private const val PREFS = "can_usb"
    private const val KEY_DEVICE = "device"
    private const val KEY_BITRATE = "bitrate"

    /** A virtual serial port ignores it; a serial bridge in front of an slcan chip is usually set to it. */
    private const val BAUD = 115_200

    private const val RETRY_MS = 10_000L
    private const val READ_CHUNK = 4096
    private const val READ_POLL_MS = 200
    private const val ANSWER_MS = 600L
    private const val WRITE_MS = 500

    /** No frame for this long: open but the bus is quiet (ignition off, or the wrong bit rate). */
    private const val QUIET_MS = 3_000L

    /** The table of ids heard keeps at most this many; a garbled bus can't fill the memory. */
    private const val MAX_IDS = 512

    private val _device = MutableStateFlow<String?>(null)
    /** The adapter chosen ([UsbObd.keyOf]), null: none. */
    val device: StateFlow<String?> = _device

    private val _bitrate = MutableStateFlow(Slcan.DEFAULT_BITRATE)
    val bitrate: StateFlow<Int> = _bitrate

    private val _state = MutableStateFlow(UsbCanState.OFF)
    val state: StateFlow<UsbCanState> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val seen = ConcurrentHashMap<Long, CanSeen>()
    private var loaded = false
    private var job: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Synchronized
    fun start(context: Context) {
        if (loaded) return
        loaded = true
        val prefs = prefs(context)
        _device.value = prefs.getString(KEY_DEVICE, null)
        _bitrate.value = prefs.getInt(KEY_BITRATE, Slcan.DEFAULT_BITRATE).takeIf { it in Slcan.BITRATES } ?: Slcan.DEFAULT_BITRATE
        if (_device.value != null) listen(context.applicationContext)
    }

    /** [adapter] is the CAN adapter: listened to from now on, and at each start. */
    fun choose(context: Context, adapter: UsbObdAdapter) {
        start(context)
        prefs(context).edit().putString(KEY_DEVICE, adapter.key).apply()
        _device.value = adapter.key
        openWhenPlugged(context, true)
        listen(context.applicationContext)
    }

    fun forget(context: Context) {
        start(context)
        prefs(context).edit().remove(KEY_DEVICE).apply()
        _device.value = null
        openWhenPlugged(context, false)
        job?.cancel()
        job = null
        _state.value = UsbCanState.OFF
        seen.clear()
    }

    /** The bus's speed; the channel is opened again at it. */
    fun setBitrate(context: Context, bps: Int) {
        if (bps !in Slcan.BITRATES) return
        start(context)
        prefs(context).edit().putInt(KEY_BITRATE, bps).apply()
        _bitrate.value = bps
        seen.clear()
        wake.trySend(Unit)
    }

    /** Plugged in, or allowed: looked for now rather than at the next round. */
    fun plugged(context: Context) {
        start(context)
        wake.trySend(Unit)
    }

    /** Every id heard since the adapter opened, lowest first. */
    fun heard(): List<CanSeen> = seen.values.sortedWith(compareBy({ it.frame.extended }, { it.frame.id }))

    /** The last frame with [id], for My readings' Test. */
    fun last(id: Int, extended: Boolean): CanFrame? = seen[slot(id, extended)]?.frame

    /** Forgets the ids heard, to see which come back. */
    fun clearHeard() = seen.clear()

    private fun slot(id: Int, extended: Boolean): Long = id.toLong() or (if (extended) 1L shl 32 else 0L)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** [frame] heard on the bus; the reader's thread. */
    private fun heard(frame: CanFrame, at: Long) {
        val key = slot(frame.id, frame.extended)
        val was = seen[key]
        if (was == null && seen.size >= MAX_IDS) return
        val changed = was != null && was.frame.bytes != frame.bytes
        seen[key] = CanSeen(frame, (was?.count ?: 0) + 1, (was?.changes ?: 0) + if (changed) 1 else 0, at)
        MyReadings.canFrame(frame)
    }

    /** As [UsbTpms]: Android lets Dashwheel keep the adapter ("Use by default") once one is chosen. */
    private fun openWhenPlugged(context: Context, on: Boolean) {
        val state = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        runCatching {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, UsbCanAttachActivity::class.java), state, PackageManager.DONT_KILL_APP
            )
        }.onFailure { Log.w(TAG, "could not switch the plug-in handler", it) }
    }

    @Synchronized
    private fun listen(app: Context) {
        if (job?.isActive == true) {
            wake.trySend(Unit)
            return
        }
        job = scope.launch {
            while (currentCoroutineContext().isActive) {
                val key = _device.value ?: break
                val adapter = UsbObd.attached(app).firstOrNull { it.key == key }
                when {
                    adapter == null -> _state.value = UsbCanState.UNPLUGGED
                    // Given again through the privileged shell where there is one; otherwise asked only from its page.
                    !UsbObd.permitted(app, adapter) && !UsbGrant.silently(app, adapter.device) -> _state.value = UsbCanState.NOT_ALLOWED
                    else -> read(app, adapter)
                }
                withTimeoutOrNull(RETRY_MS) { wake.receive() }
            }
        }
    }

    /** Opens [adapter] listen-only and reads it until it is unplugged, fails, or another adapter or bit rate is chosen. */
    private suspend fun read(app: Context, adapter: UsbObdAdapter) {
        val manager = app.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
        val port = UsbSerialProber.getDefaultProber().probeDevice(adapter.device)?.ports?.firstOrNull() ?: return
        val connection = runCatching { manager.openDevice(adapter.device) }.getOrNull()
        if (connection == null) {
            _state.value = UsbCanState.BUSY
            return
        }
        try {
            port.open(connection)
            runCatching { port.setParameters(BAUD, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE) }
            runCatching { port.dtr = true }
        } catch (e: Exception) {
            Log.w(TAG, "USB ${adapter.key}: ${e.message}")
            runCatching { port.close() }
            runCatching { connection.close() }
            _state.value = UsbCanState.BUSY
            return
        }
        val bps = _bitrate.value
        val lines = SlcanLineReader()
        val buffer = ByteArray(READ_CHUNK)
        var opened = false
        try {
            val result = openListenOnly(port, lines, buffer, bps)
            if (result != null) {
                Log.i(TAG, "USB ${adapter.key}: $result")
                _state.value = result
                return
            }
            opened = true
            Log.i(TAG, "USB ${adapter.key} (${adapter.label}) listening at $bps bit/s")
            _state.value = UsbCanState.LISTENING
            seen.clear()
            var lastFrame = 0L
            while (currentCoroutineContext().isActive && _device.value == adapter.key && _bitrate.value == bps) {
                val n = port.read(buffer, READ_POLL_MS)
                val now = SystemClock.elapsedRealtime()
                if (n > 0) {
                    val frames = lines.feed(buffer, n).frames
                    if (frames.isNotEmpty()) {
                        lastFrame = now
                        _state.value = UsbCanState.RECEIVING
                        for (frame in frames) heard(frame, now)
                    }
                }
                if (_state.value == UsbCanState.RECEIVING && now - lastFrame > QUIET_MS) _state.value = UsbCanState.LISTENING
            }
        } catch (e: IOException) {
            Log.i(TAG, "USB ${adapter.key}: ${e.message}")
            _state.value = UsbCanState.UNPLUGGED
        } finally {
            if (opened) runCatching { port.write(Slcan.CLOSE.toByteArray(), WRITE_MS) }
            runCatching { port.close() }
        }
    }

    /**
     * Sets [bps] and opens the channel without ever sending: Lawicel's
     * listen-only open, else CANable's silent mode and a plain open. Null once
     * open; else why not.
     */
    private fun openListenOnly(port: UsbSerialPort, lines: SlcanLineReader, buffer: ByteArray, bps: Int): UsbCanState? {
        // Closed first: a channel left open answers the speed with an error. Its answer doesn't matter.
        command(port, lines, buffer, Slcan.CLOSE)
        val speed = Slcan.bitrateCommand(bps) ?: return UsbCanState.NOT_SLCAN
        if (command(port, lines, buffer, speed) != true) return UsbCanState.NOT_SLCAN
        command(port, lines, buffer, Slcan.NO_TIMESTAMPS)
        if (command(port, lines, buffer, Slcan.OPEN_LISTEN) == true) return null
        if (command(port, lines, buffer, Slcan.SILENT_MODE) != true) return UsbCanState.NO_LISTEN_ONLY
        return if (command(port, lines, buffer, Slcan.OPEN) == true) null else UsbCanState.NO_LISTEN_ONLY
    }

    /** Sends [text] and waits for its answer: true taken, false refused, null nothing came. */
    private fun command(port: UsbSerialPort, lines: SlcanLineReader, buffer: ByteArray, text: String): Boolean? {
        port.write(text.toByteArray(Charsets.US_ASCII), WRITE_MS)
        val until = SystemClock.elapsedRealtime() + ANSWER_MS
        while (SystemClock.elapsedRealtime() < until) {
            val n = port.read(buffer, READ_POLL_MS)
            if (n <= 0) continue
            val read = lines.feed(buffer, n)
            if (read.errors > 0) return false
            if (read.oks > 0) return true
        }
        return null
    }
}

/**
 * Opened by Android when a CANable is plugged in, once an adapter is chosen
 * ([UsbCan.openWhenPlugged]): wakes the reader and leaves at once.
 */
class UsbCanAttachActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UsbCan.plugged(this)
        finish()
    }
}
