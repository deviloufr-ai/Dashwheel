package com.openauto.dash

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.annotation.StringRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** Live OBD-II telemetry values (0 when unknown). */
data class ObdData(
    val speedKmh: Int = 0,
    val rpm: Int = 0,
    val coolantTempC: Int = 0,
    val intakeTempC: Int = 0,
    val throttlePct: Int = 0,
    val engineLoadPct: Int = 0,
    val fuelLevelPct: Int = 0,
    val voltage: Double = 0.0,
    /**
     * [voltage] came from the engine computer (PID 0142), not the adapter's own
     * ATRV. Only the former is trusted for alerts: clone adapters misread ATRV.
     */
    val voltageFromEcu: Boolean = false
)

/** The readings once the engine computer has gone quiet (engine off): nothing turns, nothing moves. */
internal fun ObdData.engineStopped(): ObdData = copy(speedKmh = 0, rpm = 0, throttlePct = 0, engineLoadPct = 0)

/**
 * The order to try [count] ways of reaching the adapter in: the one that
 * worked [last] time, then the others as they come.
 */
internal fun channelOrder(last: Int, count: Int): List<Int> {
    val first = last.takeIf { it in 0 until count } ?: 0
    return listOf(first) + (0 until count).filter { it != first }
}

/** Whether [listed] (addresses without colons, separated by commas) has the adapter at [address]. */
internal fun unitListsAdapter(listed: String?, address: String): Boolean {
    val bare = address.replace(":", "")
    return bare.isNotEmpty() && listed?.contains(bare, ignoreCase = true) == true
}

/** The engine warning lamp as the engine computer reports it (PID 0101). */
data class EngineLamp(val on: Boolean, val storedCodes: Int)

/** Connection lifecycle for the ELM327 adapter. */
enum class ObdConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

/** No link and nothing in progress: the state in which "Connect" makes sense. */
val ObdConnectionState.isIdle: Boolean get() = this == ObdConnectionState.DISCONNECTED || this == ObdConnectionState.ERROR

/**
 * Singleton manager for OBD-II telemetry over a Bluetooth ELM327 adapter.
 *
 * Connects to the adapter over the standard Serial Port Profile (SPP) RFCOMM
 * channel, issues AT setup commands, and polls the standard PIDs for speed
 * (010D), RPM (010C) and coolant temperature (0105).
 *
 * All socket work runs on [Dispatchers.IO]; callers observe [data] and
 * [connectionState] from the UI.
 */
object ObdBluetoothManager {

    private const val TAG = "Obd"

    /** Well-known SPP UUID used by ELM327 clones. */
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    /** Upper bound for a single command's reply read, in milliseconds. */
    private const val READ_TIMEOUT_MS = 2000L

    /** Fault-code requests are slower: several computers may answer, and clones take their time. */
    private const val DTC_TIMEOUT_MS = 6000L

    private const val PREFS = "obd_prefs"
    private const val KEY_MAC = "obd_device_mac"
    /** Which of [socketFactories] reached the adapter last time. */
    private const val KEY_CHANNEL = "obd_channel"
    private const val CHANNEL_PAUSE_MS = 500L

    // Serializes all adapter I/O: the 500ms poll loop and Scan/Clear must not
    // hit the single RFCOMM socket at the same time (garbled replies / errors).
    private val commandMutex = Mutex()

    private var appContext: Context? = null
    // Opened under commandMutex on IO, but closed by disconnect() from any thread.
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var inputStream: InputStream? = null
    @Volatile private var outputStream: OutputStream? = null

    /**
     * Bumped by [disconnect]. A connect that was still opening its link when
     * the driver disconnected sees it changed and closes that link instead of
     * reporting CONNECTED.
     */
    @Volatile private var generation = 0
    private val linkLock = Any()

    // What the polls learned about this car since the link came up (only touched under commandMutex).
    /** The PIDs the engine computer says it serves; null when it wasn't asked yet or didn't say. */
    private var supported: SupportedPids? = null
    /** The poll number [supported] was last asked on, so an asleep computer isn't asked every poll. */
    private var supportedAskedAt = 0
    private var pollCount = 0
    /** Polls in a row where neither speed nor revs answered: engine off, key in accessory. */
    private var silentPolls = 0
    /** When speed or revs last answered (elapsed ms); a short glitch mid-drive must not read as engine off. */
    private var lastAliveAt = 0L
    /** Per PID: polls in a row it went unanswered while the engine computer answered others. */
    private val misses = IntArray(256)

    private val _data = MutableStateFlow(ObdData())
    val data: StateFlow<ObdData> = _data.asStateFlow()

    private val _connectionState = MutableStateFlow(ObdConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ObdConnectionState> = _connectionState.asStateFlow()

    /** The engine lamp from the last fault-code scan; null until one ran or if the car didn't say. */
    private val _lamp = MutableStateFlow<EngineLamp?>(null)
    val lamp: StateFlow<EngineLamp?> = _lamp.asStateFlow()

    /** Codes of the last scan that are only pending (seen, not yet confirmed by the engine computer). */
    private val _pending = MutableStateFlow<Set<String>>(emptySet())
    val pending: StateFlow<Set<String>> = _pending.asStateFlow()

    /** Why the last connection attempt failed, in the user's language; null after a success or before any attempt. */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /**
     * What a long connection attempt is busy with, for the tile to say: the
     * PIN to type while pairing, or Bluetooth switching on. Null otherwise.
     */
    private val _connectStep = MutableStateFlow<Int?>(null)
    val connectStep: StateFlow<Int?> = _connectStep.asStateFlow()

    /**
     * The last attempt reached an adapter that stayed silent while the unit's
     * phone was connected. On QF units the phone and the adapter share one
     * Bluetooth radio, and a phone whose Android Auto keeps trying to start
     * wirelessly holds it: the link opens, nothing ever answers. Seen with a
     * Galaxy S25; denying Android Auto "Nearby devices" on the phone ends it.
     */
    private val _phoneBlocking = MutableStateFlow(false)
    val phoneBlocking: StateFlow<Boolean> = _phoneBlocking.asStateFlow()

    /** Said once per start of the app, not at every retry. */
    private var phoneBlockingSaid = false

    private val _viaPhone = MutableStateFlow(false)
    /** The link runs through the phone's companion ([PhoneObd]) rather than this unit's Bluetooth. */
    val viaPhone: StateFlow<Boolean> = _viaPhone.asStateFlow()

    private const val KEY_ROUTE = "obd_route"

    /** The way to the adapter the driver chose (Settings, Car). */
    fun route(): ObdRoute =
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString(KEY_ROUTE, null)
            ?.let { name -> ObdRoute.entries.firstOrNull { it.name == name } } ?: ObdRoute.AUTO

    fun setRoute(route: ObdRoute) {
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putString(KEY_ROUTE, route.name)?.apply()
    }

    /** Whether the next link goes through the phone: chosen so, or Automatic with a phone offering its adapter. */
    fun usesPhone(): Boolean = when (route()) {
        ObdRoute.PHONE -> true
        ObdRoute.UNIT -> false
        ObdRoute.AUTO -> PhoneObd.offer.value != null
    }

    /** Something to dial: an adapter saved here, or one the phone relays. */
    fun canDial(): Boolean = usesPhone() || savedDeviceAddress() != null

    private fun fail(@StringRes reason: Int, vararg args: Any): Boolean {
        _lastError.value = appContext?.let { if (args.isEmpty()) it.getString(reason) else it.getString(reason, *args) }
        return false
    }

    /** The saved adapter as the picker showed it: its Bluetooth name, else its address; null when none was chosen. */
    @SuppressLint("MissingPermission")
    fun savedDeviceLabel(): String? {
        val address = savedDeviceAddress() ?: return null
        return bondedDevices().firstOrNull { it.second == address }?.first ?: address
    }

    fun setContext(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Connects to the adapter at [deviceAddress] (a Bluetooth MAC).
     * Returns true on success. Requires BLUETOOTH_CONNECT at runtime (API 31+).
     *
     * One attempt at a time: the resume observer and the 5 s retry loop both
     * call this, often in the same frame, and a second socket to an ELM327
     * (which takes one connection) knocks out the first. A link left from an
     * earlier try is closed first for the same reason, and whatever goes wrong
     * ends in ERROR, never stuck in CONNECTING where no retry would happen.
     *
     * With [byDriver] (the driver tapped Connect), Bluetooth that is off is
     * switched on, and an adapter the head unit no longer has paired is
     * paired again (Android asks for its PIN). The background retries do
     * neither, so nothing pops up by itself while driving.
     */
    @SuppressLint("MissingPermission")
    suspend fun connect(deviceAddress: String, byDriver: Boolean = false): Boolean {
        if (_connectionState.value == ObdConnectionState.CONNECTED) return true
        if (!connectLock.tryLock()) return false
        try {
            if (_connectionState.value == ObdConnectionState.CONNECTED) return true
            val gen = generation
            _connectionState.value = ObdConnectionState.CONNECTING
            val ok = withContext(Dispatchers.IO) {
                // No poll or fault-code scan may talk to the link being replaced.
                commandMutex.withLock {
                    runCatching { if (usesPhone()) openThroughPhone() else open(deviceAddress, byDriver) }
                        .onFailure {
                            Log.w(TAG, "connect failed", it)
                            if (it is SecurityException) fail(R.string.vehicle_err_permission)
                            else _lastError.value = it.message ?: it.javaClass.simpleName
                        }
                        .getOrDefault(false)
                }
            }
            synchronized(linkLock) {
                // Disconnected while opening: that link is no longer wanted.
                if (gen != generation) {
                    closeQuietly()
                    return false
                }
                if (!ok) closeQuietly() else {
                    _lastError.value = null
                    _phoneBlocking.value = false
                }
                // A demo started meanwhile owns the state; it hands back the real one when it ends.
                if (!DemoMode.isOn) _connectionState.value = if (ok) ObdConnectionState.CONNECTED else ObdConnectionState.ERROR
            }
            return ok
        } finally {
            // Cancelled mid-attempt (the screen went away): a CONNECTING left
            // behind would stop every later retry.
            if (_connectionState.value == ObdConnectionState.CONNECTING) {
                closeQuietly()
                _connectionState.value = ObdConnectionState.ERROR
            }
            connectLock.unlock()
        }
    }

    private val connectLock = Mutex()

    @SuppressLint("MissingPermission")
    private suspend fun open(deviceAddress: String, byDriver: Boolean): Boolean {
        val context = appContext ?: return false
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter ?: return fail(R.string.vehicle_err_bt_off)
        if (!adapter.isEnabled && !comingOn(adapter)) {
            Log.w(TAG, "Bluetooth is off")
            if (!byDriver) return fail(R.string.vehicle_err_bt_off)
            // Tapped by the driver: switch it on rather than send them looking for the setting.
            if (!switchBluetoothOn(adapter)) return fail(R.string.vehicle_err_bt_still_off)
        }
        freshLink()
        val device = adapter.getRemoteDevice(deviceAddress)
        val label = runCatching { device.name }.getOrNull() ?: deviceAddress
        // Only a paired adapter can be reached; an unpaired one fails slowly and says nothing.
        if (!isBonded(adapter, deviceAddress)) {
            Log.w(TAG, "$deviceAddress is not paired")
            if (!byDriver) return fail(R.string.vehicle_err_not_paired, label)
            val paired = try {
                pair(device)
            } catch (e: SecurityException) {
                throw e
            } catch (e: RuntimeException) {
                // Seen on a head unit: "BondStateMachine.obtainMessage(int) on a null
                // object reference", from inside Android's Bluetooth service: half
                // shut down while still saying it was on, it then lists no paired
                // device either. Restarting it from the app left it off there for
                // good, so the driver is asked to restart the head unit instead.
                Log.w(TAG, "the Bluetooth service failed", e)
                return fail(R.string.vehicle_err_bt_stuck)
            }
            if (!paired) return fail(R.string.vehicle_err_pairing_failed, label)
        }
        // A channel can accept the link without reaching the adapter's serial
        // port, so one that stays silent gives way to the next. The one that
        // worked last time goes first, so a redial doesn't start with the
        // seconds the others take to fail.
        val factories = socketFactories(device)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getInt(KEY_CHANNEL, 0)
        var accepted = false
        for ((tried, i) in channelOrder(last, factories.size).withIndex()) {
            // Let the radio settle after a failed try, or the next one fails on it.
            if (tried > 0) delay(CHANNEL_PAUSE_MS)
            // A search for devices (the head unit's Bluetooth screen) makes a connection fail.
            runCatching { adapter.cancelDiscovery() }
            val newSocket = tryConnect(runCatching(factories[i]).getOrNull()) ?: continue
            accepted = true
            socket = newSocket
            inputStream = newSocket.inputStream
            outputStream = newSocket.outputStream
            if (initializeAdapter()) {
                silentCommands = 0
                if (i != last) prefs.edit().putInt(KEY_CHANNEL, i).apply()
                return true
            }
            Log.w(TAG, "$deviceAddress: link #$i connected but the adapter never answered")
            closeQuietly()
        }
        // Only a lead for the log: the list also holds adapters that radio merely saw.
        if (unitListsAdapter(systemProperty(PROP_UNIT_OBD), deviceAddress)) {
            Log.w(TAG, "$deviceAddress is known to the head unit's own Bluetooth app, which may hold it")
        }
        if (!accepted) {
            Log.w(TAG, "no RFCOMM channel to $deviceAddress accepted the connection")
            return fail(R.string.vehicle_err_refused, label)
        }
        // A call through the unit: its Bluetooth leaves the adapter alone until the call ends
        // (seen on the K706 with a WhatsApp call), and [VehicleMonitor] dials again then.
        if (inCall()) return fail(R.string.vehicle_err_in_call)
        // The unit's phone connected at the same time: its Android Auto is the likely holder.
        val phone = UnitSignals.phone.value
        if (phone != null) {
            _phoneBlocking.value = true
            sayPhoneBlocking(context, phone.name)
            return fail(R.string.vehicle_err_phone_aa, phone.name)
        }
        // A socket nothing answers on is no adapter: fail, so it is tried again.
        return fail(R.string.vehicle_err_silent, label)
    }

    /** Closes any link left and forgets what the last one learned: another adapter, or the same one in another car. */
    private fun freshLink() {
        closeQuietly()
        _phoneBlocking.value = false
        silentCommands = 0
        supported = null
        supportedAskedAt = 0
        pollCount = 0
        silentPolls = 0
        lastAliveAt = SystemClock.elapsedRealtime()
        misses.fill(0)
    }

    /**
     * The adapter through the phone ([PhoneObd]): the companion reaches it
     * with the phone's Bluetooth and relays it, and the same set-up runs over
     * that as over a socket here.
     */
    private suspend fun openThroughPhone(): Boolean {
        freshLink()
        val name = PhoneObd.offer.value
        if (PhoneLink.state.value !is PhoneLinkState.Connected) return fail(R.string.vehicle_err_phone_not_linked)
        if (name == null) return fail(R.string.vehicle_err_phone_no_adapter)
        _connectStep.value = R.string.vehicle_obd_asking_phone
        val opened = try {
            PhoneObd.open()
        } finally {
            _connectStep.value = null
        }
        when (opened) {
            is PhoneObd.Opened.Failed -> {
                Log.w(TAG, "through the phone: ${opened.reason}")
                return fail(R.string.vehicle_err_phone_relay, name)
            }
            is PhoneObd.Opened.Streams -> {
                inputStream = opened.input
                outputStream = opened.output
                _viaPhone.value = true
            }
        }
        if (initializeAdapter()) return true
        Log.w(TAG, "$name through the phone: connected but the adapter never answered")
        return fail(R.string.vehicle_err_silent, name)
    }

    /** A call ringing or going on, through the unit's Bluetooth or the companion. */
    internal fun inCall(): Boolean = HeadUnitPhone.call.value != null || talking(PhoneLink.call.value)

    private fun sayPhoneBlocking(context: Context, phoneName: String) {
        if (phoneBlockingSaid || DemoMode.isOn) return
        phoneBlockingSaid = true
        val res = AppLanguage.wrap(context).resources
        CarVoice.setContext(context)
        CarVoice.announce(res.getString(R.string.vehicle_phone_aa_say, phoneName), res.configuration.locales[0])
    }

    /**
     * Bluetooth on its way on: the unit switches it off to sleep and back on
     * as it wakes, right when the ignition has the adapter dialled. Waited
     * for rather than given up on; true once it is on.
     */
    private suspend fun comingOn(adapter: BluetoothAdapter): Boolean {
        if (adapter.state != BluetoothAdapter.STATE_TURNING_ON) return false
        if (!waitUntil(BT_ON_TIMEOUT_MS) { adapter.isEnabled }) return false
        // The paired devices and the profiles load just after it says it's on.
        delay(BT_SETTLE_MS)
        return true
    }

    /**
     * Written by the Bluetooth app of QF head units (ROCO K706), whose phone
     * Bluetooth is a second radio with an OBD link of its own: the adapters
     * it found or connected. Connected there, the adapter's one connection
     * is taken.
     */
    private const val PROP_UNIT_OBD = "persist.sys.qf.bt.obd.devices"

    @SuppressLint("PrivateApi")
    private fun systemProperty(name: String): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as String
    }.getOrNull()

    @SuppressLint("MissingPermission")
    private fun isBonded(adapter: BluetoothAdapter, address: String): Boolean =
        adapter.bondedDevices.any { it.address.equals(address, ignoreCase = true) }

    /**
     * Switches the head unit's Bluetooth on; true once it is. Through the
     * Android API (still allowed on the Android 10 these head units run),
     * else through the privileged shell when there is one.
     */
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private suspend fun switchBluetoothOn(adapter: BluetoothAdapter): Boolean {
        val context = appContext ?: return false
        _connectStep.value = R.string.vehicle_obd_switching_bt_on
        try {
            var on = waitUntil(BT_ON_TIMEOUT_MS) {
                // Refused while it is still turning off: asked again until it takes.
                if (adapter.state == BluetoothAdapter.STATE_OFF) adapter.enable()
                adapter.isEnabled
            }
            if (!on && bluetoothShell(context, "enable")) on = waitUntil(BT_ON_TIMEOUT_MS) { adapter.isEnabled }
            if (!on) {
                Log.w(TAG, "Bluetooth did not switch on")
                return false
            }
            // The paired devices and the profiles load just after it says it's on.
            delay(BT_SETTLE_MS)
            return true
        } finally {
            _connectStep.value = null
        }
    }

    private suspend fun bluetoothShell(context: Context, command: String): Boolean =
        PrivilegedShell.access.value.shell &&
            runCatching { DockShell.shell(context, "svc bluetooth $command") }.isSuccess

    private suspend fun waitUntil(timeoutMs: Long, done: () -> Boolean): Boolean {
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < timeoutMs) {
            if (runCatching(done).getOrDefault(false)) return true
            delay(250)
        }
        return runCatching(done).getOrDefault(false)
    }

    private const val BT_ON_TIMEOUT_MS = 15_000L
    private const val BT_SETTLE_MS = 2_000L

    /**
     * Pairs the head unit with [device]: Android shows its own dialog for the
     * adapter's PIN (1234 or 0000 on most ELM327). Waits until the pairing is
     * done, refused or [PAIRING_TIMEOUT_MS] went by; true once paired.
     */
    @SuppressLint("MissingPermission")
    private fun pair(device: BluetoothDevice): Boolean {
        if (device.bondState == BluetoothDevice.BOND_BONDED) return true
        if (device.bondState != BluetoothDevice.BOND_BONDING && !device.createBond()) {
            Log.w(TAG, "${device.address}: pairing could not start")
            return false
        }
        _connectStep.value = R.string.vehicle_obd_pairing_pin
        try {
            val start = SystemClock.elapsedRealtime()
            var sawBonding = false
            while (SystemClock.elapsedRealtime() - start < PAIRING_TIMEOUT_MS) {
                when (device.bondState) {
                    BluetoothDevice.BOND_BONDED -> return true
                    BluetoothDevice.BOND_BONDING -> sawBonding = true
                    // Back to unpaired: wrong PIN or the dialog was cancelled. Before
                    // bonding shows up at all, give the stack a moment to start.
                    else -> if (sawBonding || SystemClock.elapsedRealtime() - start > PAIRING_START_MS) return false
                }
                Thread.sleep(250)
            }
            Log.w(TAG, "${device.address}: pairing timed out")
            return false
        } finally {
            _connectStep.value = null
        }
    }

    /** Time for the driver to read the PIN and type it. */
    private const val PAIRING_TIMEOUT_MS = 60_000L
    private const val PAIRING_START_MS = 5_000L

    /**
     * The ways to open an RFCOMM socket to the adapter, in order (like Torque):
     * the secure SPP channel, the insecure one, then a reflection fallback on
     * channel 1. Clone ELM327 adapters fail one but succeed on another.
     */
    @SuppressLint("MissingPermission")
    private fun socketFactories(device: BluetoothDevice): List<() -> BluetoothSocket> = listOf(
        { device.createRfcommSocketToServiceRecord(SPP_UUID) },
        { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
        {
            device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                .invoke(device, 1) as BluetoothSocket
        }
    )

    /**
     * [BluetoothSocket.connect] has no timeout of its own and can sit for half
     * a minute on an adapter that is unpowered or taken: a watchdog closes the
     * socket after [CONNECT_TIMEOUT_MS], which makes the connect return.
     */
    @SuppressLint("MissingPermission")
    private fun tryConnect(candidate: BluetoothSocket?): BluetoothSocket? {
        candidate ?: return null
        val watchdog = Thread {
            try {
                Thread.sleep(CONNECT_TIMEOUT_MS)
                Log.w(TAG, "connect timed out after ${CONNECT_TIMEOUT_MS} ms")
                runCatching { candidate.close() }
            } catch (e: InterruptedException) {
                // The connect returned first: nothing to close.
            }
        }.apply { isDaemon = true; start() }
        return try {
            candidate.connect()
            watchdog.interrupt()
            if (candidate.isConnected) candidate else null
        } catch (e: IOException) {
            watchdog.interrupt()
            Log.w(TAG, "connect: ${e.message}")
            runCatching { candidate.close() }
            null
        }
    }

    /** Per RFCOMM channel tried (three of them). */
    private const val CONNECT_TIMEOUT_MS = 8_000L

    /**
     * Sends the standard ELM327 initialization sequence; false if the adapter
     * said nothing at all, even on a second try with more time for the reset.
     */
    private fun initializeAdapter(): Boolean {
        // An adapter asleep (low-power mode, engine off) wakes on the first
        // character it gets and drops it: a bare return, before ATZ.
        runCatching { outputStream?.run { write("\r".toByteArray()); flush() } }
        Thread.sleep(WAKE_MS)
        for (attempt in 1..INIT_TRIES) {
            val replies = listOf(
                // Reset; clone adapters need a moment after it.
                sendCommand("ATZ", if (attempt == 1) READ_TIMEOUT_MS else RESET_TIMEOUT_MS).also { Thread.sleep(1000) },
                sendCommand("ATE0"),  // echo off
                sendCommand("ATL0"),  // line feeds off
                sendCommand("ATSP0")  // automatic protocol selection
            )
            if (outputStream == null) return false // the link dropped
            if (replies.any { it != null }) {
                // Which PIDs the car serves (0100 also wakes the ECU link); unanswered, everything is polled.
                supported = ObdParser.supportedPids { sendCommand(it) }
                return outputStream != null
            }
            Log.w(TAG, "no reply to the setup commands (try $attempt of $INIT_TRIES)")
        }
        return false
    }

    private const val WAKE_MS = 500L
    private const val INIT_TRIES = 2
    private const val RESET_TIMEOUT_MS = 5_000L

    /** Paired Bluetooth devices as (name, MAC) pairs, for the adapter picker. */
    @SuppressLint("MissingPermission")
    fun bondedDevices(): List<Pair<String, String>> {
        val context = appContext ?: return emptyList()
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: return emptyList()
        val adapter = manager.adapter ?: return emptyList()
        return try {
            adapter.bondedDevices.map { device ->
                (runCatching { device.name }.getOrNull() ?: context.getString(R.string.vehicle_unknown_device)) to device.address
            }.sortedBy { it.first.lowercase() }
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    /** The adapter the user picked, or null if none chosen yet. */
    fun savedDeviceAddress(): String? =
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString(KEY_MAC, null)

    /** The saved adapter's name as Bluetooth knows it, or its address when it is no longer paired; null when none is saved. */
    fun savedDeviceName(): String? {
        val mac = savedDeviceAddress() ?: return null
        return bondedDevices().firstOrNull { it.second.equals(mac, ignoreCase = true) }?.first ?: mac
    }

    /** No adapter any more (Settings, Car): the link closes and nothing is redialled until one is picked. */
    suspend fun forgetDevice() {
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.remove(KEY_MAC)?.remove(KEY_CHANNEL)?.apply()
        disconnect()
    }

    fun saveDeviceAddress(address: String) {
        val prefs = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return
        val other = !address.equals(prefs.getString(KEY_MAC, null), ignoreCase = true)
        val edit = prefs.edit().putString(KEY_MAC, address)
        // Another adapter may answer on another channel.
        if (other) edit.remove(KEY_CHANNEL)
        edit.apply()
    }

    /** Polls speed, RPM and coolant temperature once, updating [data]. */
    suspend fun poll(): Unit = withContext(Dispatchers.IO) {
        if (DemoMode.isOn || _connectionState.value != ObdConnectionState.CONNECTED) return@withContext
        commandMutex.withLock {
            if (_connectionState.value == ObdConnectionState.CONNECTED) pollLocked()
        }
    }

    /**
     * One poll. Speed and revs are asked every time; throttle and load every
     * other poll; the slow readings (temperatures, fuel, voltage) take turns,
     * one per poll. Each request is a round trip to the car, so fewer of them
     * means fresher speed and revs. The first poll asks for everything.
     */
    private fun pollLocked() {
        val cycle = pollCount++
        val first = cycle == 0
        val speed = sendCommand("010D")?.let { ObdParser.parseSpeed(it) }
        val rpm = sendCommand("010C")?.let { ObdParser.parseRpm(it) }
        val alive = speed != null || rpm != null
        silentPolls = if (alive) 0 else silentPolls + 1
        if (alive) lastAliveAt = SystemClock.elapsedRealtime()
        // Asleep at connect (ignition off), the engine computer never said which PIDs it serves: ask once it talks.
        if (alive && supported == null && (first || cycle - supportedAskedAt >= SUPPORTED_RETRY_POLLS)) {
            supportedAskedAt = cycle
            supported = ObdParser.supportedPids { sendCommand(it) }
        }

        val throttle = if (first || cycle % 2 == 0) read(0x11, alive, cycle) { ObdParser.percentFrom(it, "4111") } else null
        val load = if (first || cycle % 2 == 1) read(0x04, alive, cycle) { ObdParser.percentFrom(it, "4104") } else null
        val slot = cycle % SLOW_SLOTS
        val coolant = if (first || slot == 0) read(0x05, alive, cycle) { ObdParser.parseCoolant(it) } else null
        val intake = if (first || slot == 1) read(0x0F, alive, cycle) { ObdParser.tempFrom(it, "410F") } else null
        val fuel = if (first || slot == 2) read(0x2F, alive, cycle) { ObdParser.percentFrom(it, "412F") } else null
        var ecuVolt: Double? = null
        var volt: Double? = null
        if (first || slot == 3) {
            // Prefer the ECU's control-module voltage (PID 0142) — it reads the real
            // bus voltage. Many ELM327 clones report a miscalibrated ATRV (e.g. 16.9V
            // when the bus is ~14.5V), so ATRV is only a fallback when 0142 is
            // unsupported (then it is the only request, not a second one each time).
            ecuVolt = read(0x42, alive, cycle) { ObdParser.parseControlModuleVoltage(it) }
            volt = ecuVolt ?: sendCommand("ATRV")?.let { ObdParser.parseVoltage(it) }
            // Engine running, and the OBD figure is far from what the unit itself measures on the
            // car's supply (12.3 V "not charging" against 14.3 V in the bar): the OBD figure is the
            // one that's off, so the unit's is used, and trusted like the ECU's.
            val unitVolt = UnitSignals.supplyVolts()
            if (unitVolt != null && (rpm ?: _data.value.rpm) > BatteryJudge.RUNNING_RPM && (volt ?: _data.value.voltage).let { it > 0.0 && Math.abs(it - unitVolt) > UNIT_VOLT_DISAGREES_V }) {
                volt = unitVolt
                ecuVolt = unitVolt
            }
        }

        val d = _data.value
        val next = d.copy(
            // With the driver's correction (Settings → Car), so every tile agrees with the car's own speedometer.
            speedKmh = speed?.let(SpeedCorrection::corrected) ?: d.speedKmh,
            rpm = rpm ?: d.rpm,
            coolantTempC = coolant ?: d.coolantTempC,
            intakeTempC = intake ?: d.intakeTempC,
            throttlePct = throttle ?: d.throttlePct,
            engineLoadPct = load ?: d.engineLoadPct,
            fuelLevelPct = fuel ?: d.fuelLevelPct,
            voltage = volt ?: d.voltage,
            voltageFromEcu = if (volt != null) ecuVolt != null else d.voltageFromEcu
        )
        // Engine off with the key in accessory: every PID says NO DATA while the
        // adapter stays linked. The last revs and speed would otherwise stay up
        // for good (a drive that never ends, a battery judged on old readings);
        // the temperatures, fuel and voltage keep their last value, as a gauge would.
        val silentFor = SystemClock.elapsedRealtime() - lastAliveAt
        _data.value = if (silentPolls >= STALE_POLLS && silentFor >= STALE_MS) next.engineStopped() else next
        BatteryWatch.feed(_data.value, System.currentTimeMillis())
    }

    /**
     * Mode-01 [pid] read with [parse], or null. Skipped when the car said it
     * doesn't serve it. With no such list, a PID that went unanswered
     * [MAX_MISSES] times in a row while the engine computer answered others is
     * only tried again every [RETRY_DROPPED_POLLS] polls. One the car claims is
     * always asked: a busy computer (cranking, filter regeneration) can miss a
     * few, and giving up would freeze e.g. the coolant for the whole drive.
     */
    private inline fun <T> read(pid: Int, alive: Boolean, cycle: Int, parse: (String) -> T?): T? {
        val list = supported
        if (list?.has(pid) == false) return null
        if (list == null && misses[pid] >= MAX_MISSES && cycle % RETRY_DROPPED_POLLS != 0) return null
        val value = sendCommand(PID_COMMANDS[pid])?.let(parse)
        if (value != null) misses[pid] = 0 else if (alive) misses[pid]++
        return value
    }

    /** "0100".."01FF", built once. */
    private val PID_COMMANDS = Array(256) { "01" + "%02X".format(java.util.Locale.US, it) }

    /** Polls without speed or revs, and for how long, before those count as gone. */
    private const val STALE_POLLS = 3
    private const val STALE_MS = 10_000L
    /** An unlisted PID that stopped answering is tried again this often (~1 min). */
    private const val RETRY_DROPPED_POLLS = 120
    private const val MAX_MISSES = 3
    /** The slow readings share this many polls, one each. */
    private const val SLOW_SLOTS = 4
    /** OBD and unit voltages further apart than this: the unit's is believed. */
    private const val UNIT_VOLT_DISAGREES_V = 1.0
    private const val SUPPORTED_RETRY_POLLS = 20

    /**
     * Reads stored Diagnostic Trouble Codes (OBD mode 03). Returns the decoded
     * code list (e.g. "P0133"), empty if none, or a failure with a message.
     */
    suspend fun readTroubleCodes(): Result<List<String>> = withContext(Dispatchers.IO) {
        if (DemoMode.isOn) return@withContext DemoMode.scanCodes()
        if (_connectionState.value != ObdConnectionState.CONNECTED) {
            return@withContext failure(R.string.vehicle_obd_not_connected)
        }
        commandMutex.withLock {
            // A scan takes seconds: stop between requests when whoever asked is gone.
            suspend fun ask(command: String, timeoutMs: Long = DTC_TIMEOUT_MS): String? {
                currentCoroutineContext().ensureActive()
                return sendCommand(command, timeoutMs)
            }
            val stored = linkedSetOf<String>()
            val pending = linkedSetOf<String>()
            var answered = false
            fun collect(reply: String?, mode: Int) {
                val codes = reply?.let { ObdParser.parseDtcReply(it, mode) } ?: return
                if (mode == 0x43) answered = true
                (if (mode == 0x43) stored else pending) += codes
            }
            var addressed = false
            try {
                // Give slow computers time: adaptive timing can cut the wait short,
                // and a busy running engine then reads as "NO DATA".
                ask("ATAT0", READ_TIMEOUT_MS)
                ask("ATSTFF", READ_TIMEOUT_MS)
                // What the engine computer itself says: lamp on or off, so an
                // empty read can be checked against it.
                _lamp.value = ask("0101")?.let { ObdParser.parseEngineLamp(it) }
                collect(ask("03"), 0x43)
                collect(ask("07"), 0x47)
                // On CAN, also ask the engine computer on its own address: with
                // everyone answering at once, its reply can be the one lost.
                if (ask("ATDPN", READ_TIMEOUT_MS)?.let(ObdParser::isCan11Bit) == true) {
                    addressed = true
                    if (ask("ATSH7E0", READ_TIMEOUT_MS)?.contains("OK") == true) {
                        collect(ask("03"), 0x43)
                        collect(ask("07"), 0x47)
                    }
                }
            } finally {
                // Put the address and timing back even when cancelled: the regular polls rely on them.
                if (addressed) sendCommand("ATSH7DF", READ_TIMEOUT_MS)
                sendCommand("ATAT1", READ_TIMEOUT_MS)
                sendCommand("ATST32", READ_TIMEOUT_MS)
            }
            if (!answered) return@withLock failure(R.string.vehicle_no_dtc_answer)
            _pending.value = pending - stored
            Result.success((stored + pending).toList())
        }
    }

    /**
     * One read request for the experimental reading finder: [request] bytes
     * ("221A5B"), addressed to [header] (a CAN address such as 7E0) or to
     * everyone when null. The functional address is put back afterwards so the
     * regular polling is unaffected. Null when not connected or unanswered.
     *
     * A computer off the OBD addresses answers from its own [replyAddress]
     * (6A8 answers on 688): the adapter only listens to 7E8-7EF unless told,
     * and a long answer needs flow control sent back to [header]. A [session]
     * ("10C0") is opened first; it lapses by itself a few seconds later.
     */
    suspend fun query(
        header: String?,
        request: String,
        timeoutMs: Long = READ_TIMEOUT_MS,
        replyAddress: String? = null,
        session: String? = null
    ): String? = queryAll(header, listOf(request), timeoutMs, replyAddress, session).firstOrNull()

    /**
     * Like [query] for several [requests] to the same computer: the addresses
     * are set and the session opened once for all of them, then put back once,
     * instead of five or more set-up commands around every request. One reply
     * (or null) per request; empty when not connected.
     */
    suspend fun queryAll(
        header: String?,
        requests: List<String>,
        timeoutMs: Long = READ_TIMEOUT_MS,
        replyAddress: String? = null,
        session: String? = null
    ): List<String?> = withContext(Dispatchers.IO) {
        if (DemoMode.isOn || _connectionState.value != ObdConnectionState.CONNECTED) return@withContext emptyList()
        commandMutex.withLock {
            if (_connectionState.value != ObdConnectionState.CONNECTED) return@withLock emptyList()
            val ownAddresses = header != null && replyAddress != null
            val replies = ArrayList<String?>(requests.size)
            try {
                if (header != null) sendCommand("ATSH$header", READ_TIMEOUT_MS)
                if (ownAddresses) {
                    sendCommand("ATCRA$replyAddress", READ_TIMEOUT_MS)
                    sendCommand("ATFCSH$header", READ_TIMEOUT_MS)
                    sendCommand("ATFCSD300000", READ_TIMEOUT_MS)
                    sendCommand("ATFCSM1", READ_TIMEOUT_MS)
                }
                var sessionAt = 0L
                for (request in requests) {
                    currentCoroutineContext().ensureActive()
                    // Opened once, and again only if a slow answer may have let it lapse.
                    if (header != null && session != null && System.currentTimeMillis() - sessionAt > SESSION_KEEP_MS) {
                        sendCommand(session, timeoutMs)
                        sessionAt = System.currentTimeMillis()
                    }
                    replies += sendCommand(request, timeoutMs)
                }
            } finally {
                if (ownAddresses) {
                    sendCommand("ATFCSM0", READ_TIMEOUT_MS)
                    sendCommand("ATCRA", READ_TIMEOUT_MS)
                }
                if (header != null) sendCommand("ATSH7DF", READ_TIMEOUT_MS)
            }
            replies
        }
    }

    /** A diagnostic session lapses after about five seconds without a request; reopen it well before. */
    private const val SESSION_KEEP_MS = 2_000L

    /** Clears stored trouble codes and turns off the MIL (OBD mode 04). */
    suspend fun clearTroubleCodes(): Result<Unit> = withContext(Dispatchers.IO) {
        if (DemoMode.isOn) return@withContext DemoMode.clearCodes()
        if (_connectionState.value != ObdConnectionState.CONNECTED) {
            return@withContext failure(R.string.vehicle_obd_not_connected)
        }
        commandMutex.withLock {
            val raw = sendCommand("04", DTC_TIMEOUT_MS)
                ?: return@withLock failure(R.string.vehicle_no_response)
            val r = raw.uppercase().trim()
            if (r.contains("44") || r.contains("OK")) {
                Result.success(Unit)
            } else {
                // Common cause: ignition must be ON (engine off) to clear codes.
                failure(R.string.vehicle_clear_rejected, raw)
            }
        }
    }

    /** A failed [Result] whose message is shown in the UI, so it is localized. */
    private fun failure(@StringRes message: Int, vararg args: Any): Result<Nothing> {
        val context = appContext
        val text = when {
            context == null -> "OBD error"
            args.isEmpty() -> context.getString(message)
            else -> context.getString(message, *args)
        }
        return Result.failure(IllegalStateException(text))
    }

    /**
     * Writes a command and reads the reply up to the ELM327 '>' prompt.
     *
     * Reads are bounded by [timeoutMs]: a silent or misbehaving adapter
     * would otherwise block this IO coroutine indefinitely on [InputStream.read].
     */
    private fun sendCommand(command: String, timeoutMs: Long = READ_TIMEOUT_MS): String? {
        val out = outputStream ?: return null
        val input = inputStream ?: return null
        return try {
            val buffer = ByteArray(1024)
            // A reply that came in after its command gave up would otherwise be
            // read as the answer to this one.
            while (input.available() > 0) input.read(buffer)
            out.write((command + "\r").toByteArray())
            out.flush()

            val response = StringBuilder()
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (input.available() > 0) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    response.append(String(buffer, 0, read))
                    if (response.contains(">")) break
                } else {
                    // Only paces the wait for the reply; the loop still ends on the prompt.
                    Thread.sleep(5)
                }
            }
            if (response.isEmpty()) unanswered(command) else silentCommands = 0
            response.toString().replace(">", "").trim().ifEmpty { null }
        } catch (e: IOException) {
            // The link is gone: drop it so the retry opens a fresh one.
            Log.w(TAG, "$command: ${e.message}")
            closeQuietly()
            if (_connectionState.value == ObdConnectionState.CONNECTED) _connectionState.value = ObdConnectionState.ERROR
            null
        }
    }

    /**
     * The adapter sent nothing back to [command], not even its prompt. With
     * the engine off it still says "NO DATA", so [MAX_SILENT_COMMANDS] in a
     * row is a link that is up in name only (lost on a crowded 2.4 GHz band
     * without the socket closing): it would stay "connected" with no readings
     * for good, so it is dropped and dialled again.
     */
    private fun unanswered(command: String) {
        if (++silentCommands < MAX_SILENT_COMMANDS || _connectionState.value != ObdConnectionState.CONNECTED) return
        Log.w(TAG, "$command: nothing from the adapter for $silentCommands commands, link dropped")
        silentCommands = 0
        closeQuietly()
        _connectionState.value = ObdConnectionState.ERROR
    }

    /** Commands in a row without a single byte back (only touched under commandMutex). */
    private var silentCommands = 0
    private const val MAX_SILENT_COMMANDS = 8

    /** [DemoMode]'s readings, shown as if an adapter were connected. */
    internal fun demoWrite(data: ObdData, lamp: EngineLamp?, pending: Set<String>) {
        _connectionState.value = ObdConnectionState.CONNECTED
        _data.value = data
        _lamp.value = lamp
        _pending.value = pending
    }

    /** The demo is over: back to the real link, whose next poll fills the readings in again. */
    internal fun endDemo(lamp: EngineLamp?, pending: Set<String>) {
        val linked = socket?.isConnected == true || (_viaPhone.value && outputStream != null)
        _connectionState.value = if (linked) ObdConnectionState.CONNECTED else ObdConnectionState.DISCONNECTED
        _data.value = ObdData()
        _lamp.value = lamp
        _pending.value = pending
    }

    suspend fun disconnect(): Unit = withContext(Dispatchers.IO) {
        synchronized(linkLock) {
            generation++
            closeQuietly()
            _connectionState.value = ObdConnectionState.DISCONNECTED
            _data.value = ObdData()
        }
    }

    private fun closeQuietly() {
        runCatching { inputStream?.close() }
        runCatching { outputStream?.close() }
        runCatching { socket?.close() }
        inputStream = null
        outputStream = null
        socket = null
        _viaPhone.value = false
    }
}

/** Which way the head unit reaches the OBD adapter (Settings, Car). */
enum class ObdRoute {
    /** Through the phone when its companion offers the adapter, else this unit's Bluetooth. */
    AUTO,
    /** Always this unit's own Bluetooth. */
    UNIT,
    /** Always through the phone's companion. */
    PHONE
}
