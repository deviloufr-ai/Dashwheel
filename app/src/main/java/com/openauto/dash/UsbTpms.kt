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
import kotlin.math.roundToInt

/*
 * A USB TPMS receiver of the driver's own: the 433 MHz dongles sold with four
 * valve-cap or in-wheel sensors, on a CH340 serial chip (1a86:7523) at 19200
 * baud, 8N1. It sends by itself, nothing is asked: one 8-byte frame per tyre,
 *
 *     55 AA 08 <tyre> <pressure> <temperature> <status> <XOR of the 7 bytes before>
 *
 * pressure × 3.44 kPa, temperature − 50 °C, tyre 00 front left, 01 front
 * right, 10 rear left, 11 rear right (worked out by github.com/danderik88/
 * usb-tpms-home-assistant, one sensor unscrewed at a time). Its readings go
 * into [Tyres], so the Tyres tile, the tyre alert and its voice show them as
 * they would the head unit's TPMS app.
 *
 * Only the device the driver picked in Settings is ever opened: the same
 * chip is in OBD cables, and the head unit's own TPMS app may be reading a
 * receiver of its own.
 */

/** What the receiver is doing, for its settings page. */
enum class UsbTpmsState { OFF, UNPLUGGED, NOT_ALLOWED, BUSY, LISTENING, RECEIVING }

internal object UsbTpms {
    private const val TAG = "UsbTpms"
    private const val PREFS = "tpms_usb"
    private const val KEY_DEVICE = "device"
    private const val KEY_UNIT = "unit"

    const val BAUD = 19_200

    /** Looked for again this often while it is unplugged, not allowed or taken. */
    private const val RETRY_MS = 10_000L
    private const val READ_CHUNK = 4096
    private const val READ_POLL_MS = 200

    private val _device = MutableStateFlow<String?>(null)
    /** The receiver chosen ([UsbObd.keyOf]), null: none. */
    val device: StateFlow<String?> = _device

    private val _state = MutableStateFlow(UsbTpmsState.OFF)
    val state: StateFlow<UsbTpmsState> = _state

    private val _unit = MutableStateFlow(PressureUnit.BAR)
    /** How its pressures are shown, where no TPMS app says so. */
    val unit: StateFlow<PressureUnit> = _unit

    /** A receiver is chosen. */
    val on: Boolean get() = _device.value != null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loaded = false
    private var job: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Synchronized
    fun start(context: Context) {
        if (loaded) return
        loaded = true
        val prefs = prefs(context)
        _device.value = prefs.getString(KEY_DEVICE, null)
        prefs.getString(KEY_UNIT, null)?.let { pressureUnit(it) }?.let { _unit.value = it }
        if (on) listen(context.applicationContext)
    }

    /** [adapter] is the receiver: read from now on, and at each start. */
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
        _state.value = UsbTpmsState.OFF
        Tyres.receiverGone()
    }

    fun setUnit(context: Context, unit: PressureUnit) {
        prefs(context).edit().putString(KEY_UNIT, unit.name).apply()
        _unit.value = unit
        if (!Tyres.fromApp) Tyres.setUnit(unit)
    }

    /** Plugged in, or allowed: looked for now rather than at the next round. */
    fun plugged(context: Context) {
        start(context)
        wake.trySend(Unit)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * The CH340 plugged in opens [UsbTpmsAttachActivity], so Android can let
     * Dashwheel have it for good ("Use by default"), without asking again at
     * each start. Off unless a receiver is chosen: otherwise Android made
     * Dashwheel the default for the head unit's own receiver.
     */
    private fun openWhenPlugged(context: Context, on: Boolean) {
        val state = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        runCatching {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, UsbTpmsAttachActivity::class.java), state, PackageManager.DONT_KILL_APP
            )
        }.onFailure { Log.w(TAG, "could not switch the plug-in handler", it) }
    }

    @Synchronized
    private fun listen(app: Context) {
        Tyres.judgeFromNowOn()
        if (!Tyres.fromApp) Tyres.setUnit(_unit.value)
        if (job?.isActive == true) {
            wake.trySend(Unit)
            return
        }
        job = scope.launch {
            while (currentCoroutineContext().isActive) {
                val key = _device.value ?: break
                val adapter = UsbObd.attached(app).firstOrNull { it.key == key }
                when {
                    adapter == null -> _state.value = UsbTpmsState.UNPLUGGED
                    // Asked only from its settings page: nothing pops up by itself mid-drive.
                    !UsbObd.permitted(app, adapter) -> _state.value = UsbTpmsState.NOT_ALLOWED
                    else -> read(app, adapter)
                }
                withTimeoutOrNull(RETRY_MS) { wake.receive() }
            }
        }
    }

    /** Reads [adapter] until it is unplugged, fails, or another receiver is chosen. */
    private suspend fun read(app: Context, adapter: UsbObdAdapter) {
        val manager = app.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
        val port = UsbSerialProber.getDefaultProber().probeDevice(adapter.device)?.ports?.firstOrNull() ?: return
        val connection = runCatching { manager.openDevice(adapter.device) }.getOrNull()
        if (connection == null) {
            _state.value = UsbTpmsState.BUSY
            return
        }
        try {
            port.open(connection)
            port.setParameters(BAUD, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        } catch (e: Exception) {
            // Taken by the OBD link, or by the head unit's TPMS app.
            Log.w(TAG, "USB ${adapter.key}: ${e.message}")
            runCatching { port.close() }
            runCatching { connection.close() }
            _state.value = UsbTpmsState.BUSY
            return
        }
        Log.i(TAG, "USB ${adapter.key} (${adapter.label}) open at $BAUD baud")
        _state.value = UsbTpmsState.LISTENING
        val frames = TpmsFrameReader()
        val buffer = ByteArray(READ_CHUNK)
        try {
            while (currentCoroutineContext().isActive && _device.value == adapter.key) {
                val n = port.read(buffer, READ_POLL_MS)
                if (n <= 0) continue
                for (frame in frames.feed(buffer, n)) {
                    val pos = frame.tyre ?: continue
                    _state.value = UsbTpmsState.RECEIVING
                    Tyres.heard(pos, frame.toTyre(SystemClock.elapsedRealtime()))
                }
            }
        } catch (e: IOException) {
            Log.i(TAG, "USB ${adapter.key}: ${e.message}")
            _state.value = UsbTpmsState.UNPLUGGED
        } finally {
            runCatching { port.close() }
        }
    }
}

/**
 * Opened by Android when the CH340 is plugged in, once a receiver is chosen
 * ([UsbTpms.openWhenPlugged]): wakes the reader and leaves at once.
 */
class UsbTpmsAttachActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UsbTpms.plugged(this)
        finish()
    }
}

/** One reading from the receiver, as sent. */
internal data class TpmsFrame(val position: Int, val pressure: Int, val temperature: Int, val status: Int) {
    /** Null for the spare slot (05): it reports 2.51 bar and 33 °C with no fifth sensor. */
    val tyre: TyrePos? get() = usbTpmsPosition(position)

    val kPa: Int get() = (pressure * KPA_PER_STEP).roundToInt()
    val celsius: Int get() = temperature - 50

    /** Seen while air was escaping; the other bits are not known. */
    val leak: Boolean get() = (status and 0x08) != 0

    fun toTyre(at: Long) = Tyre(kPa = kPa, celsius = celsius, sensorBatteryLow = false, leak = leak, noSignal = false, at = at, status = status)

    companion object {
        const val SIZE = 8
        const val KPA_PER_STEP = 3.44
    }
}

/** The receiver's tyre numbers: the first digit the axle, the second the side. */
internal fun usbTpmsPosition(code: Int): TyrePos? = when (code) {
    0x00 -> TyrePos.FRONT_LEFT
    0x01 -> TyrePos.FRONT_RIGHT
    0x10 -> TyrePos.REAR_LEFT
    0x11 -> TyrePos.REAR_RIGHT
    else -> null
}

/** [bytes] from [from] as a frame, or null when it isn't one (wrong start or checksum). */
internal fun tpmsFrameAt(bytes: ByteArray, from: Int): TpmsFrame? {
    if (bytes.size - from < TpmsFrame.SIZE) return null
    fun b(i: Int) = bytes[from + i].toInt() and 0xFF
    if (b(0) != 0x55 || b(1) != 0xAA || b(2) != 0x08) return null
    var xor = 0
    for (i in 0 until 7) xor = xor xor b(i)
    if (xor != b(7)) return null
    return TpmsFrame(b(3), b(4), b(5), b(6))
}

/**
 * Frames out of the receiver's bytes, which USB hands over in pieces that
 * don't follow frames: what is left of one waits for the next piece. A bad
 * frame is skipped one byte at a time until the next 55 AA.
 */
internal class TpmsFrameReader {
    private var pending = ByteArray(0)

    fun feed(chunk: ByteArray, length: Int = chunk.size): List<TpmsFrame> {
        val bytes = pending + chunk.copyOf(length)
        val out = ArrayList<TpmsFrame>()
        var i = 0
        while (true) {
            i = startAt(bytes, i)
            if (i < 0) {
                // A last 55 may be the start of the next frame.
                pending = if (bytes.isNotEmpty() && bytes.last() == 0x55.toByte()) byteArrayOf(0x55) else ByteArray(0)
                return out
            }
            if (bytes.size - i < TpmsFrame.SIZE) {
                pending = bytes.copyOfRange(i, bytes.size)
                return out
            }
            val frame = tpmsFrameAt(bytes, i)
            if (frame != null) {
                out += frame
                i += TpmsFrame.SIZE
            } else {
                i++
            }
        }
    }

    private fun startAt(bytes: ByteArray, from: Int): Int {
        for (i in from until bytes.size - 1) {
            if (bytes[i] == 0x55.toByte() && bytes[i + 1] == 0xAA.toByte()) return i
        }
        return -1
    }
}
