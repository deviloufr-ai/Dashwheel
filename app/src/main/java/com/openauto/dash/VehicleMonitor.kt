package com.openauto.dash

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the car link working for the whole process rather than for one
 * dashboard screen: the OBD poll with the AI mechanic and car-care watchers,
 * and the adapter reconnect. Tied to the screen, they all stopped whenever it
 * was rebuilt (a language change, or Android reclaiming the launcher behind a
 * full-screen app) until the next resume.
 */
internal object VehicleMonitor {
    /**
     * The pause between two OBD polls: room for a fault scan to get the adapter.
     * At 500 ms, with a poll's own round trips, the revs moved once in 1.3 s on
     * the second screen while the car's own counter climbed.
     */
    private const val POLL_MS = 100L
    private const val FIRST_RETRY_MS = 5_000L
    private const val MAX_RETRY_MS = 60_000L
    /** The paired devices load just after Bluetooth says it's on. */
    private const val BLUETOOTH_SETTLE_MS = 2_000L
    /** The unit's Bluetooth takes a moment to hand the call audio back. */
    private const val CALL_SETTLE_MS = 2_000L
    private const val USB_SETTLE_MS = 1_500L

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
        ContextCompat.registerReceiver(
            context.applicationContext, usbState,
            IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        DeepObdSource.init(context)
        val listener = context.applicationContext
        scope.launch { DeepObdSource.run(listener) }
        scope.launch { pollWhileConnected() }
        scope.launch { reconnectWhileWanted() }
        scope.launch { redialAfterCalls() }
        scope.launch { followPhoneOffer() }
        // The bar's alerts, judged at every reading whatever is on screen (the demo's too).
        // The application context, not [context]: this runs for good and must not keep the first screen alive.
        val app = context.applicationContext
        scope.launch {
            combine(ObdBluetoothManager.connectionState, ObdBluetoothManager.data, BatteryWatch.state) { connection, data, battery ->
                Triple(connection == ObdConnectionState.CONNECTED, data, battery)
            }.collect { (connected, data, battery) -> AlertCenter.evaluate(strings(app), data, battery, connected) }
        }
    }

    // The chips' text in the launcher's language, wrapped again only when that changes.
    private var stringsFor: Pair<AppLanguage, Context>? = null

    private fun strings(app: Context): Context {
        val language = AppLanguage.current(app)
        stringsFor?.takeIf { it.first == language }?.let { return it.second }
        return AppLanguage.wrap(app).also { stringsFor = language to it }
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
                if (redials(foreground.value || secondScreen.value, CarPower.ignition.value)) connectSaved()
            }
        }
    }

    /** A USB device plugged in or pulled out: maybe the adapter's cable. */
    private val usbState = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            usbChanged()
        }
    }

    /**
     * The USB adapter plugged in or pulled out (Automatic): a link the other
     * way is closed and the adapter dialled the way that now comes first.
     * Plugged in, it is dialled at once rather than at the next retry.
     */
    fun usbChanged() {
        if (!started) return
        scope.launch {
            // The device is listed a moment after the broadcast, and the attach dialog saves it.
            delay(USB_SETTLE_MS)
            if (ObdBluetoothManager.route() == ObdRoute.AUTO) {
                val state = ObdBluetoothManager.connectionState.value
                val wrongSide = state == ObdConnectionState.CONNECTED && ObdBluetoothManager.viaUsb.value != ObdBluetoothManager.usesUsb()
                if (wrongSide) ObdBluetoothManager.disconnect()
            }
            if (redials(foreground.value || secondScreen.value, CarPower.ignition.value)) connectSaved()
        }
    }

    /** The second screen shows the car's readings: they are wanted even with an app in front. */
    private val secondScreen = MutableStateFlow(false)

    fun setForeground(inFront: Boolean) {
        foreground.value = inFront
    }

    fun setSecondScreenShowing(showing: Boolean) {
        secondScreen.value = showing
    }

    /**
     * Dials the saved adapter when the link is down and the Bluetooth
     * permission is held; nothing happens without a saved adapter, or while
     * the car is switched off. The driver's own tap on OBD still dials then.
     */
    fun connectSaved() {
        val context = appContext ?: return
        // Deep OBD holds the adapter and reports by itself: nothing to dial.
        if (DeepObdSource.owns()) return
        if (CarPower.ignition.value == false) return
        if (!ObdBluetoothManager.connectionState.value.isIdle) return
        // On a cable: nothing to pair, and Android's USB access was given when it was plugged in or on Connect.
        if (ObdBluetoothManager.usesUsb()) {
            scope.launch { ObdBluetoothManager.connect(ObdBluetoothManager.savedDeviceAddress().orEmpty()) }
            return
        }
        // Through the phone: its companion holds the adapter, nothing to pair or allow here.
        if (ObdBluetoothManager.usesPhone()) {
            scope.launch { ObdBluetoothManager.connect(ObdBluetoothManager.savedDeviceAddress().orEmpty()) }
            return
        }
        val saved = ObdBluetoothManager.savedDeviceAddress() ?: return
        val missingPerms = requiredBluetoothPermissions().any {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (!missingPerms) scope.launch { ObdBluetoothManager.connect(saved) }
    }

    /**
     * A call through the unit keeps its Bluetooth from reaching the adapter;
     * once it ends the adapter is dialled straight away, not when the retries,
     * slowed down during a long call, next come round.
     */
    private suspend fun redialAfterCalls() {
        var calling = false
        combine(HeadUnitPhone.call, PhoneLink.call) { _, _ -> ObdBluetoothManager.inCall() }
            .distinctUntilChanged()
            .collect { now ->
                val ended = calling && !now
                calling = now
                if (!ended) return@collect
                delay(CALL_SETTLE_MS)
                if (redials(foreground.value || secondScreen.value, CarPower.ignition.value)) connectSaved()
            }
    }

    /**
     * The phone starts or stops offering its adapter (Automatic): a link on
     * the wrong side is closed and the adapter dialled the right way, since
     * the adapter takes one connection at a time.
     */
    private suspend fun followPhoneOffer() {
        ObdBluetoothManager.setContext(appContext ?: return)
        PhoneObd.offer.map { it != null }.distinctUntilChanged().collect {
            if (ObdBluetoothManager.route() != ObdRoute.AUTO) return@collect
            val state = ObdBluetoothManager.connectionState.value
            val wrongSide = state == ObdConnectionState.CONNECTED && ObdBluetoothManager.viaPhone.value != ObdBluetoothManager.usesPhone()
            if (wrongSide) ObdBluetoothManager.disconnect()
            if (redials(foreground.value || secondScreen.value, CarPower.ignition.value)) connectSaved()
        }
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
                        if (!DeepObdSource.owns()) AiMechanic.autoScan()
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
     * Redials a missing adapter while it is wanted ([redials], the second
     * screen showing the car's readings counting as in front): after 5 s,
     * then less and less often up to once a minute, so an adapter that is
     * unplugged or asleep does not keep the Bluetooth radio calling it.
     * Wanted again, or a link that was up and dropped, starts over at 5 s.
     */
    private suspend fun reconnectWhileWanted() {
        combine(foreground, secondScreen, CarPower.ignition) { inFront, cluster, ignition -> redials(inFront || cluster, ignition) }
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
