package com.openauto.dash

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import kotlin.coroutines.resume

/** A USB serial device plugged into the unit that may be the OBD adapter. */
data class UsbObdAdapter(
    /** Vendor and product id ("0403:6001"): what is saved, since the same cable comes back under another device path. */
    val key: String,
    val label: String,
    internal val device: UsbDevice
)

/**
 * OBD adapters on a USB cable: ELM327 cables (FTDI, CH340, PL2303, CP210x)
 * and adapters such as the OBDX Pro VT that show up as a plain USB serial
 * port. Some head units (Joying) refuse Bluetooth devices that aren't their
 * own, and a cable doesn't share a radio with the phone either.
 *
 * Only a device the driver let Dashwheel use (Android's own dialog, when it
 * is plugged in or on Connect) or saved as the adapter is ever opened:
 * opening one takes it from its kernel driver, and a head unit can have
 * serial chips of its own on the same bus.
 *
 * Gives [ObdBluetoothManager] the same byte streams a Bluetooth socket would.
 */
internal object UsbObd {
    private const val TAG = "Obd"
    private const val PREFS = "obd_prefs"
    private const val KEY_DEVICE = "obd_usb"
    private const val KEY_BAUD = "obd_usb_baud"
    private const val ACTION_PERMISSION = "com.openauto.dash.USB_OBD_PERMISSION"

    /**
     * The speeds ELM327 adapters come set to: 38400 for most cables, 115200
     * for STN chips (OBDLink, OBDX), then the rarer ones. A virtual serial
     * port (CDC) ignores it and answers on the first.
     */
    private val BAUDS = listOf(38_400, 115_200, 500_000, 9_600, 230_400, 57_600)
    private const val PROBE_MS = 1_200L
    private const val PERMISSION_TIMEOUT_MS = 60_000L

    private fun manager(context: Context): UsbManager? = context.getSystemService(Context.USB_SERVICE) as? UsbManager

    /** Every serial device plugged in, whether or not Dashwheel may use it yet. */
    fun attached(context: Context): List<UsbObdAdapter> {
        val manager = manager(context) ?: return emptyList()
        val prober = UsbSerialProber.getDefaultProber()
        return runCatching { manager.deviceList.values.toList() }.getOrDefault(emptyList())
            .mapNotNull { device -> prober.probeDevice(device)?.let { adapterOf(it) } }
            .sortedBy { it.label.lowercase() }
    }

    private fun adapterOf(driver: UsbSerialDriver): UsbObdAdapter {
        val device = driver.device
        val chip = driver.javaClass.simpleName.removeSuffix("SerialDriver")
        val name = runCatching { device.productName }.getOrNull()?.trim().orEmpty()
        return UsbObdAdapter(keyOf(device), name.ifEmpty { "USB $chip" }, device)
    }

    fun keyOf(device: UsbDevice): String = "%04x:%04x".format(Locale.US, device.vendorId, device.productId)

    fun permitted(context: Context, adapter: UsbObdAdapter): Boolean =
        runCatching { manager(context)?.hasPermission(adapter.device) == true }.getOrDefault(false)

    fun savedKey(context: Context): String? = prefs(context).getString(KEY_DEVICE, null)

    fun save(context: Context, key: String) {
        val prefs = prefs(context)
        val edit = prefs.edit().putString(KEY_DEVICE, key)
        // Another adapter may run at another speed.
        if (key != prefs.getString(KEY_DEVICE, null)) edit.remove(KEY_BAUD)
        edit.apply()
    }

    fun forget(context: Context) {
        prefs(context).edit().remove(KEY_DEVICE).remove(KEY_BAUD).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * The adapter to use with Automatic: the saved one, else one Dashwheel
     * was let use; null when neither is plugged in. Another serial device
     * isn't taken by itself.
     */
    fun chosen(context: Context): UsbObdAdapter? {
        val all = attached(context)
        val saved = savedKey(context)
        return all.firstOrNull { it.key == saved } ?: all.firstOrNull { permitted(context, it) }
    }

    /** The adapter to use with USB chosen in Settings: as [chosen], else the first serial device plugged in. */
    fun pick(context: Context, anyDevice: Boolean): UsbObdAdapter? =
        chosen(context) ?: if (anyDevice) attached(context).firstOrNull() else null

    /** Android's dialog asking to let Dashwheel use [adapter]; true once allowed. */
    suspend fun allow(context: Context, adapter: UsbObdAdapter): Boolean {
        val manager = manager(context) ?: return false
        if (manager.hasPermission(adapter.device)) return true
        val app = context.applicationContext
        return withTimeoutOrNull(PERMISSION_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, intent: Intent) {
                        runCatching { app.unregisterReceiver(this) }
                        if (cont.isActive) cont.resume(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                    }
                }
                ContextCompat.registerReceiver(app, receiver, IntentFilter(ACTION_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
                cont.invokeOnCancellation { runCatching { app.unregisterReceiver(receiver) } }
                // Mutable: Android adds whether it was allowed. Addressed to this app only.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val intent = PendingIntent.getBroadcast(app, 0, Intent(ACTION_PERMISSION).setPackage(app.packageName), flags)
                manager.requestPermission(adapter.device, intent)
            }
        } ?: false
    }

    sealed class Opened {
        class Streams(val input: InputStream, val output: OutputStream) : Opened()
        /** Android wouldn't open it (unplugged meanwhile, or taken). */
        data object Failed : Opened()
        /** Opened, but nothing answered like an ELM327 at any speed. */
        data object Silent : Opened()
    }

    /**
     * Opens [adapter] (allowed already) and finds the speed it talks at: the
     * one that worked last time first, then [BAUDS].
     */
    fun open(context: Context, adapter: UsbObdAdapter): Opened {
        val manager = manager(context) ?: return Opened.Failed
        val driver = UsbSerialProber.getDefaultProber().probeDevice(adapter.device) ?: return Opened.Failed
        val port = driver.ports.firstOrNull() ?: return Opened.Failed
        val connection = runCatching { manager.openDevice(adapter.device) }.getOrNull() ?: return Opened.Failed
        try {
            port.open(connection)
        } catch (e: IOException) {
            Log.w(TAG, "USB ${adapter.key}: ${e.message}")
            runCatching { connection.close() }
            return Opened.Failed
        }
        val link = PortLink(port)
        val saved = prefs(context).getInt(KEY_BAUD, 0)
        val order = listOf(saved).filter { it > 0 } + BAUDS.filter { it != saved }
        try {
            for (baud in order) {
                val answers = try {
                    answersAt(port, baud)
                } catch (e: UnsupportedOperationException) {
                    false // a speed this chip can't run at
                } catch (e: IllegalArgumentException) {
                    false
                }
                if (!answers) continue
                Log.i(TAG, "USB ${adapter.key} (${adapter.label}) answers at $baud baud")
                if (baud != saved) prefs(context).edit().putInt(KEY_BAUD, baud).apply()
                return Opened.Streams(link.input, link.output)
            }
        } catch (e: IOException) {
            // Unplugged while it was tried.
            Log.w(TAG, "USB ${adapter.key}: ${e.message}")
            link.close()
            return Opened.Failed
        }
        Log.w(TAG, "USB ${adapter.key} (${adapter.label}): no ELM327 answer at any speed")
        link.close()
        return Opened.Silent
    }

    /** Sets [baud] and asks "ATI": an ELM327 answers in plain text ending with its ">" prompt. */
    private fun answersAt(port: UsbSerialPort, baud: Int): Boolean {
        port.setParameters(baud, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        // Some adapters only talk once the line says a program is there.
        runCatching { port.setDTR(true) }
        runCatching { port.setRTS(true) }
        val buffer = ByteArray(READ_CHUNK)
        // An adapter asleep wakes on the first character and drops it.
        port.write("\r".toByteArray(), WRITE_TIMEOUT_MS)
        drain(port, buffer, 300)
        port.write("ATI\r".toByteArray(), WRITE_TIMEOUT_MS)
        val reply = StringBuilder()
        val deadline = SystemClock.elapsedRealtime() + PROBE_MS
        while (SystemClock.elapsedRealtime() < deadline && !reply.contains(">")) {
            val n = port.read(buffer, READ_POLL_MS)
            for (i in 0 until n) reply.append((buffer[i].toInt() and 0xFF).toChar())
        }
        return looksLikeElm(reply.toString())
    }

    private fun drain(port: UsbSerialPort, buffer: ByteArray, ms: Long) {
        val until = SystemClock.elapsedRealtime() + ms
        while (SystemClock.elapsedRealtime() < until) port.read(buffer, READ_POLL_MS)
    }

    /** USB reads are whole packets: a buffer this size never cuts one. */
    private const val READ_CHUNK = 4096
    private const val READ_POLL_MS = 50
    private const val WRITE_TIMEOUT_MS = 1_000

    /**
     * The port as the streams [ObdBluetoothManager] reads and writes: a USB
     * read is a call with a timeout, not a stream that says how much is
     * waiting, so [available] reads ahead a little.
     */
    private class PortLink(private val port: UsbSerialPort) {
        private val chunk = ByteArray(READ_CHUNK)
        private var start = 0
        private var end = 0
        @Volatile private var closed = false

        fun close() {
            if (closed) return
            closed = true
            runCatching { port.close() }
        }

        private fun fill(timeoutMs: Int): Int {
            if (closed) throw IOException("USB link closed")
            if (start < end) return end - start
            start = 0
            end = port.read(chunk, timeoutMs).coerceAtLeast(0)
            return end
        }

        val input = object : InputStream() {
            override fun available(): Int = synchronized(this) { fill(AVAILABLE_POLL_MS) }

            override fun read(): Int = synchronized(this) {
                while (fill(BLOCKING_POLL_MS) == 0) Unit
                chunk[start++].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int = synchronized(this) {
                if (len == 0) return 0
                while (fill(BLOCKING_POLL_MS) == 0) Unit
                val n = minOf(len, end - start)
                System.arraycopy(chunk, start, b, off, n)
                start += n
                n
            }

            override fun close() = this@PortLink.close()
        }

        val output = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(b: ByteArray, off: Int, len: Int) {
                if (closed) throw IOException("USB link closed")
                port.write(if (off == 0 && len == b.size) b else b.copyOfRange(off, off + len), WRITE_TIMEOUT_MS)
            }

            override fun close() = this@PortLink.close()
        }

        private companion object {
            const val AVAILABLE_POLL_MS = 10
            const val BLOCKING_POLL_MS = 200
        }
    }
}

/**
 * Whether [reply] to "ATI" came from an ELM327 talking at the speed set:
 * readable text ending with its ">" prompt. At the wrong speed the bytes
 * come out as noise, and a modem answers without the prompt.
 */
internal fun looksLikeElm(reply: String): Boolean {
    if (!reply.contains('>')) return false
    val printable = reply.count { it == '\r' || it == '\n' || it in ' '..'~' }
    return printable >= reply.length * 0.9
}

/**
 * Opened by Android when an adapter is plugged in, if the driver lets
 * Dashwheel handle it (ticking "always" makes that for good): that grants
 * the access, and the adapter is saved and dialled. Nothing is shown.
 */
class UsbObdAttachActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        val device = intent?.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
        if (device != null && UsbSerialProber.getDefaultProber().probeDevice(device) != null) {
            UsbObd.save(this, UsbObd.keyOf(device))
            VehicleMonitor.usbChanged()
        }
        finish()
    }
}
