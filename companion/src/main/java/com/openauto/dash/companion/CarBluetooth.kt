package com.openauto.dash.companion

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * "Start in the car": sharing runs only while the phone is connected to the
 * car's own Bluetooth (the head unit, for calls and music), instead of all
 * day. The driver picks that Bluetooth device once; [CarBluetoothReceiver]
 * then follows it connecting and disconnecting and starts or stops
 * [LinkService]. With no device picked, sharing runs whenever it is on.
 */
object CarBluetooth {
    private const val PREFS = "car_bluetooth"

    /** A Bluetooth device the phone is paired with. */
    data class Device(val address: String, val name: String)

    private val _car = MutableStateFlow<Device?>(null)
    /** The car's Bluetooth, or null when sharing isn't tied to it. */
    val car: StateFlow<Device?> = _car

    private val _inCar = MutableStateFlow(false)
    /** The phone is connected to [car] right now. */
    val inCar: StateFlow<Boolean> = _inCar

    private var loaded = false

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        val p = prefs(context)
        _car.value = p.getString("address", null)?.let { Device(it, p.getString("name", null).orEmpty()) }
        _inCar.value = p.getBoolean("in_car", false)
        loaded = true
    }

    /** Ties sharing to [device], or unties it (null). */
    fun choose(context: Context, device: Device?) {
        load(context)
        _car.value = device
        prefs(context).edit().apply {
            if (device == null) {
                remove("address")
                remove("name")
            } else {
                putString("address", device.address)
                putString("name", device.name)
            }
        }.apply()
        // Chosen while already connected to it: the next connection would be the first one seen.
        setInCar(context, device != null && isConnectedNow(context, device.address))
        LinkService.sync(context)
    }

    fun setInCar(context: Context, value: Boolean) {
        load(context)
        _inCar.value = value
        prefs(context).edit().putBoolean("in_car", value).apply()
    }

    /** Whether [LinkService] may run as far as the car's Bluetooth goes. */
    fun allowsSharing(context: Context): Boolean {
        load(context)
        return _car.value == null || _inCar.value
    }

    /** Android 12+ asks for "Nearby devices" to name paired devices and hear them connect. */
    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** The devices this phone is paired with, the ones named like [preferred] first. */
    @SuppressLint("MissingPermission")
    fun bonded(context: Context, preferred: Collection<String>): List<Device> {
        if (!hasPermission(context)) return emptyList()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        val devices = runCatching { adapter.bondedDevices }.getOrNull().orEmpty()
            .map { Device(it.address, runCatching { it.name }.getOrNull() ?: it.address) }
        return devices.sortedWith(compareBy<Device> { d -> preferred.none { d.name.contains(it, ignoreCase = true) } }.thenBy { it.name.lowercase() })
    }

    /**
     * Whether the phone is connected to [address] now: through the hidden
     * BluetoothDevice.isConnected(), which every Android since 4.4 has.
     */
    @SuppressLint("MissingPermission")
    private fun isConnectedNow(context: Context, address: String): Boolean {
        if (!hasPermission(context)) return false
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        return runCatching {
            val device = adapter.getRemoteDevice(address)
            BluetoothDevice::class.java.getMethod("isConnected").invoke(device) as Boolean
        }.getOrDefault(false)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Hears the car's Bluetooth come and go, and starts or stops sharing with it. */
class CarBluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val connected = when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> true
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> false
            else -> return
        }
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        } ?: return
        CarBluetooth.load(context)
        val car = CarBluetooth.car.value ?: return
        if (!device.address.equals(car.address, ignoreCase = true)) return
        CarBluetooth.setInCar(context, connected)
        LinkService.sync(context)
    }
}
