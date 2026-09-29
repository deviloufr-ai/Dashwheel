package com.openauto.dash.companion

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import com.openauto.dash.link.ObdOffer
import com.openauto.dash.link.ObdRelayState
import com.openauto.dash.link.ObdRx
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * OBD through the phone: this phone keeps the Bluetooth link to the car's
 * ELM327 adapter and passes its bytes to and from the head unit over the
 * car link ([LinkServer]). The head unit runs its own OBD code unchanged; the
 * phone only carries bytes. Worth it where the head unit's Bluetooth is one
 * radio shared with calls and phone projection, which starves the adapter.
 *
 * The driver picks the adapter once; the head unit asks for the link
 * ([open]) when it wants OBD and lets it go ([close]), and the link dropping
 * lets it go too.
 */
object ObdRelay {
    private const val TAG = "ObdRelay"
    private const val PREFS = "obd_relay"
    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    enum class Status { IDLE, CONNECTING, RELAYING, FAILED }

    private val _adapter = MutableStateFlow<CarBluetooth.Device?>(null)
    /** The adapter this phone relays, or null: the head unit uses its own Bluetooth. */
    val adapter: StateFlow<CarBluetooth.Device?> = _adapter

    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status

    private var loaded = false
    private val lock = Any()
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var output: OutputStream? = null
    /** Bumped by every open and close: a reader or a connect from before stops quietly. */
    @Volatile private var generation = 0

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _adapter.value = p.getString("address", null)?.let { CarBluetooth.Device(it, p.getString("name", null).orEmpty()) }
        loaded = true
    }

    /** Relays [device] from now on, or stops relaying (null); the head unit is told at once. */
    fun choose(context: Context, device: CarBluetooth.Device?) {
        load(context)
        close()
        _adapter.value = device
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (device == null) clear() else putString("address", device.address).putString("name", device.name)
        }.apply()
        _status.value = Status.IDLE
        LinkServer.send(offer(context))
    }

    /** What this phone offers the head unit, sent on every link up. */
    fun offer(context: Context): ObdOffer {
        load(context)
        return ObdOffer(_adapter.value?.name?.ifEmpty { _adapter.value?.address })
    }

    /** The head unit wants OBD: connects to the adapter on a thread of its own and says how it went. */
    fun open(context: Context) {
        load(context)
        val chosen = _adapter.value
        if (chosen == null) {
            LinkServer.send(ObdRelayState(false, "no adapter chosen on the phone"))
            return
        }
        val gen = synchronized(lock) {
            closeLocked()
            ++generation
        }
        _status.value = Status.CONNECTING
        Thread({ connect(context, chosen, gen) }, "obd-relay").start()
    }

    /** Bytes from the head unit, for the adapter. */
    fun write(data: String) {
        val out = output ?: return
        try {
            out.write(data.toByteArray(Charsets.ISO_8859_1))
            out.flush()
        } catch (e: IOException) {
            Log.w(TAG, "write: ${e.message}")
            lost("write failed")
        }
    }

    /** The head unit is done with OBD, or the car link is gone: the adapter is let go. */
    fun close() {
        synchronized(lock) {
            generation++
            closeLocked()
        }
        if (_status.value != Status.FAILED) _status.value = Status.IDLE
    }

    private fun closeLocked() {
        runCatching { output?.close() }
        runCatching { socket?.close() }
        output = null
        socket = null
    }

    @SuppressLint("MissingPermission")
    private fun connect(context: Context, chosen: CarBluetooth.Device, gen: Int) {
        if (!CarBluetooth.hasPermission(context)) return failed(gen, "Nearby devices not allowed on the phone")
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) return failed(gen, "Bluetooth is off on the phone")
        val device = runCatching { adapter.getRemoteDevice(chosen.address) }.getOrNull() ?: return failed(gen, "unknown adapter")
        // The same three ways the head unit tries: clone adapters answer on one or another.
        val ways = listOf<(BluetoothDevice) -> BluetoothSocket>(
            { it.createRfcommSocketToServiceRecord(SPP) },
            { it.createInsecureRfcommSocketToServiceRecord(SPP) },
            { it.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType).invoke(it, 1) as BluetoothSocket }
        )
        for (way in ways) {
            if (gen != generation) return
            runCatching { adapter.cancelDiscovery() }
            val s = runCatching { way(device) }.getOrNull() ?: continue
            try {
                s.connect()
            } catch (e: IOException) {
                runCatching { s.close() }
                Thread.sleep(500)
                continue
            }
            val input: InputStream
            synchronized(lock) {
                if (gen != generation) {
                    runCatching { s.close() }
                    return
                }
                socket = s
                output = s.outputStream
                input = s.inputStream
            }
            _status.value = Status.RELAYING
            LinkServer.send(ObdRelayState(true))
            read(input, gen)
            return
        }
        failed(gen, "the adapter refused the phone")
    }

    /** Passes the adapter's bytes on until the link to it ends. */
    private fun read(input: InputStream, gen: Int) {
        val buffer = ByteArray(1024)
        try {
            while (gen == generation) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n > 0) LinkServer.send(ObdRx(String(buffer, 0, n, Charsets.ISO_8859_1)))
            }
        } catch (e: IOException) {
            Log.i(TAG, "read ended: ${e.message}")
        }
        if (gen == generation) lost("the adapter link closed")
    }

    private fun lost(reason: String) {
        synchronized(lock) {
            generation++
            closeLocked()
        }
        _status.value = Status.FAILED
        LinkServer.send(ObdRelayState(false, reason))
    }

    private fun failed(gen: Int, reason: String) {
        if (gen != generation) return
        Log.w(TAG, reason)
        _status.value = Status.FAILED
        LinkServer.send(ObdRelayState(false, reason))
    }
}
