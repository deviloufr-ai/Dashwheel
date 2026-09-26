package com.openauto.dash

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Keeps the car link working for the whole process rather than for one
 * dashboard screen: the OBD poll with the AI mechanic and car-care watchers,
 * and the adapter reconnect. Tied to the screen, they all stopped whenever it
 * was rebuilt (a language change, or Android reclaiming the launcher behind a
 * full-screen app) until the next resume.
 */
internal object VehicleMonitor {
    private const val POLL_MS = 500L
    private const val FIRST_RETRY_MS = 5_000L
    private const val MAX_RETRY_MS = 60_000L
    /** The paired devices load just after Bluetooth says it's on. */
    private const val BLUETOOTH_SETTLE_MS = 2_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false
    private var appContext: Context? = null

    /** The launcher is in front; see [redials]. */
    private val foreground = MutableStateFlow(false)

    fun start(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        ContextCompat.registerReceiver(
            context.applicationContext, bluetoothState,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        scope.launch { pollWhileConnected() }
        scope.launch { reconnectWhileWanted() }
    }

    /**
     * Bluetooth back on (the unit switches it off to sleep): the adapter is
     * dialled then, not whenever the retries, slowed down by the tries made
     * while it was off, next come round.
     */
    private val bluetoothState = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) != BluetoothAdapter.STATE_ON) return
            scope.launch {
                delay(BLUETOOTH_SETTLE_MS)
                if (redials(foreground.value, CarPower.ignition.value)) connectSaved()
            }
        }
    }

    fun setForeground(inFront: Boolean) {
        foreground.value = inFront
    }

    /**
     * Dials the saved adapter when the link is down and the Bluetooth
     * permission is held; nothing happens without a saved adapter, or while
     * the car is switched off. The driver's own tap on OBD still dials then.
     */
    fun connectSaved() {
        val context = appContext ?: return
        if (CarPower.ignition.value == false) return
        if (!ObdBluetoothManager.connectionState.value.isIdle) return
        val saved = ObdBluetoothManager.savedDeviceAddress() ?: return
        val missingPerms = requiredBluetoothPermissions().any {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (!missingPerms) scope.launch { ObdBluetoothManager.connect(saved) }
    }

    private suspend fun pollWhileConnected() {
        combine(ObdBluetoothManager.connectionState, DemoMode.active) { connection, demo ->
            // The demo feeds the readings itself and must not scan, speak or save anything.
            connection == ObdConnectionState.CONNECTED && !demo
        }
            .distinctUntilChanged()
            .collectLatest { live ->
                if (!live) return@collectLatest
                coroutineScope {
                    // The AI mechanic checks for fault codes by itself once the first
                    // readings are in (it only speaks about codes it hasn't heard before).
                    launch {
                        delay(3000)
                        AiMechanic.autoScan()
                    }
                    while (true) {
                        ObdBluetoothManager.poll()
                        AiMechanic.watch(ObdBluetoothManager.data.value)
                        CarCare.watch(ObdBluetoothManager.data.value)
                        delay(POLL_MS)
                    }
                }
            }
    }

    /**
     * Redials a missing adapter while it is wanted ([redials]): after 5 s,
     * then less and less often up to once a minute, so an adapter that is
     * unplugged or asleep does not keep the Bluetooth radio calling it.
     * Wanted again, or a link that was up and dropped, starts over at 5 s.
     */
    private suspend fun reconnectWhileWanted() {
        combine(foreground, CarPower.ignition) { inFront, ignition -> redials(inFront, ignition) }
            .distinctUntilChanged()
            .collectLatest { wanted ->
                if (!wanted) {
                    // Off as far as the broadcasts said: the unit itself is asked now and then.
                    while (true) {
                        delay(MAX_RETRY_MS)
                        CarPower.refresh()
                    }
                }
                var wait = FIRST_RETRY_MS
                while (true) {
                    if (ObdBluetoothManager.connectionState.value == ObdConnectionState.CONNECTED) {
                        ObdBluetoothManager.connectionState.first { it != ObdConnectionState.CONNECTED }
                        wait = FIRST_RETRY_MS
                    }
                    CarPower.refresh()
                    connectSaved()
                    delay(wait)
                    wait = (wait * 2).coerceAtMost(MAX_RETRY_MS)
                }
            }
    }
}

/**
 * Whether a missing adapter is redialled by itself. With the ignition on it
 * is, whichever app is in front: the link feeds the alerts, the drive log and
 * the volume all drive long, and one lost behind CarPlay or a call screen
 * used to stay lost until the launcher came back. With the ignition off it
 * isn't: the link was closed on purpose, and one opened then goes stale as the
 * unit sleeps while the adapter still holds it, refusing the next start's.
 * On a unit that doesn't say ([ignition] null), only with the launcher [inFront].
 */
internal fun redials(inFront: Boolean, ignition: Boolean?): Boolean = ignition ?: inFront
