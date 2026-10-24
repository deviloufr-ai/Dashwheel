package com.openauto.dash.unit.fyt

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.openauto.dash.BuildConfig
import com.openauto.dash.CameraControl
import com.openauto.dash.link.CallState
import com.openauto.dash.Cap
import com.openauto.dash.CapClaim
import com.openauto.dash.DockShell
import com.openauto.dash.IgnitionEvent
import com.openauto.dash.IgnitionSource
import com.openauto.dash.KeySource
import com.openauto.dash.LauncherTakeover
import com.openauto.dash.MediaBridge
import com.openauto.dash.Need
import com.openauto.dash.PhoneBridge
import com.openauto.dash.PhoneCall
import com.openauto.dash.PopupTakeover
import com.openauto.dash.R
import com.openauto.dash.SignalCatalogue
import com.openauto.dash.SleepSurvival
import com.openauto.dash.SysProps
import com.openauto.dash.UnitAdapter
import com.openauto.dash.UnitCaps
import com.openauto.dash.UnitKeyPress
import com.openauto.dash.UnitPlatform
import com.openauto.dash.VehicleBusSource
import com.openauto.dash.unitBusRpm
import com.openauto.dash.unitBusSpeed
import com.openauto.dash.vehicle.Publisher
import com.openauto.dash.VolumeControl
import com.openauto.unitproto.UnitFamily
import com.openauto.unitproto.fyt.FytBt
import com.openauto.unitproto.fyt.FytCanbus
import com.openauto.unitproto.fyt.FytKey
import com.openauto.unitproto.fyt.FytMain
import com.openauto.unitproto.fyt.FytModules
import com.openauto.unitproto.fyt.FytProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/*
 * The FYT / SYU firmware (Joying, Atoto, Mekede, Junsun, Teyes...; UIS7862,
 * UIS8581a, SC9853i): everything goes through its toolkit service
 * ([FytToolkit]). The car's data is module CANBUS (speed, revs and whatever
 * the car's CAN decoder sends under its own codes), the wheel keys module
 * MAIN once the unit is told to hand them over, and the Bluetooth phone module
 * BT. The ignition comes as broadcasts. Worked out by the community from
 * decompiled firmware, not by the maker: what is unconfirmed is said so.
 */
internal class FytAdapter(context: Context) : UnitAdapter {
    private val app = context.applicationContext
    private val toolkit = FytToolkit(app)
    private val hasServer = UnitPlatform.hasPackage(app, FytProtocol.PACKAGE)

    override val family = UnitFamily.FYT

    override val ignition: IgnitionSource = FytIgnition
    override val bus: VehicleBusSource? = if (hasServer) FytCanbusSource(toolkit) else null
    override val keys: KeySource? = if (hasServer) FytKeys(app, toolkit) else null
    override val phone: PhoneBridge? = if (hasServer) FytPhone(toolkit) else null
    override val media: MediaBridge? = null
    override val volume: VolumeControl? = null
    override val launcher: LauncherTakeover? = null
    override val popups: PopupTakeover? = null
    override val survival: SleepSurvival? = null
    override val camera: CameraControl? = null

    override val caps: UnitCaps = UnitCaps(
        buildList {
            add(CapClaim(Cap.IGNITION))
            if (hasServer) {
                add(CapClaim(Cap.VEHICLE_BUS))
                add(CapClaim(Cap.SPEED_RPM))
                add(CapClaim(Cap.UNIT_KEYS, Need.SHELL, R.string.unit_fyt_keys_note))
                add(CapClaim(Cap.PHONE_BRIDGE))
            }
        }
    )

    override val catalogue = SignalCatalogue(
        broadcasts = setOf(FytProtocol.ACTION_ACC_ON, FytProtocol.ACTION_ACC_OFF, FytProtocol.ACTION_EXTERNAL_COMMAND),
        propPrefixes = setOf("ro.fyt.", "sys.fyt.", "ro.build.fyt", FytProtocol.PROP_CARLINK_TYPE)
    )

    override val vendorPackages: Set<String> = FytProtocol.PACKAGES
}

/** ACC on and off as the firmware broadcasts them; nothing to read in between. */
internal object FytIgnition : IgnitionSource {
    override fun read(): Boolean? = null
    override val actions: Set<String> = setOf(FytProtocol.ACTION_ACC_ON, FytProtocol.ACTION_ACC_OFF)
    override fun decode(intent: Intent): IgnitionEvent? = when (intent.action) {
        FytProtocol.ACTION_ACC_ON -> IgnitionEvent.ON
        FytProtocol.ACTION_ACC_OFF -> IgnitionEvent.OFF
        else -> null
    }
}

/**
 * Module CANBUS: the generic codes every FYT unit has ([FytCanbus.GENERIC])
 * plus the per-car codes the driver taught with the Signal Finder
 * ([watch]); while the finder's sniffer is open, the whole range ([sniff]),
 * so the finder can see which code moved with a door. Every update is
 * handed to the finder as raw values; speed and revs go to the dashboard.
 */
internal class FytCanbusSource(private val toolkit: FytToolkit) : VehicleBusSource {
    private val _listening = MutableStateFlow(false)
    override val listening: StateFlow<Boolean> = _listening

    /**
     * The module calls back on every change of a code it was asked for, and the generic codes (speed, revs, the
     * decoder id) move whenever the car does: half a minute of nothing is a car that is off or a module that is gone.
     * What speed and revs it put would have aged out after 10 s anyway.
     */
    override val silenceMs: Long = 30_000L

    private var out: Publisher? = null
    private var raw: ((String, String) -> Unit)? = null
    private var module: FytToolkit.FytModule? = null
    private val watched = LinkedHashSet<Int>()
    private val registered = HashSet<Int>()
    private var sniffing = false
    @Volatile private var canbusId: Int? = null

    private val callback by lazy { FytToolkit.FytCallback(toolkit.feed, ::onUpdate) }

    override fun start(context: Context, publisher: Publisher, raw: (String, String) -> Unit) {
        if (out != null) return
        out = publisher
        this.raw = raw
        toolkit.onConnection { up -> if (up) subscribe() else dropped() }
        toolkit.connect()
    }

    override fun stop() = Unit

    // On the feed thread.
    private fun subscribe() {
        val m = toolkit.module(FytModules.CANBUS) ?: run { Log.w(TAG, "no CANBUS module"); return }
        module = m
        registered.clear()
        register(m, FytCanbus.GENERIC.toList() + watched + if (sniffing) FytCanbus.SNIFF_RANGE.toList() else emptyList())
        _listening.value = true
    }

    private fun dropped() {
        module = null
        registered.clear()
        _listening.value = false
    }

    private fun register(m: FytToolkit.FytModule, codes: Collection<Int>) {
        for (code in codes) if (registered.add(code)) m.register(callback, code)
    }

    /** The finder's learned keys: the CANBUS codes among them are followed for good. */
    override fun watch(keys: Set<String>) {
        val codes = keys.mapNotNull { FytKey.parse(it) }.filter { it.module == FytModules.CANBUS }.map { it.code }
        toolkit.feed.post {
            val new = codes.filter { watched.add(it) }
            module?.let { register(it, new) }
        }
    }

    /** The sniffer is open: every code of the range is listened to, so what the car sends shows up. */
    override fun sniff(on: Boolean) {
        toolkit.feed.post {
            if (sniffing == on) return@post
            sniffing = on
            val m = module ?: return@post
            if (on) register(m, FytCanbus.SNIFF_RANGE.toList())
            else for (code in FytCanbus.SNIFF_RANGE) if (code !in watched && code !in FytCanbus.GENERIC) {
                if (registered.remove(code)) m.unregister(callback, code)
            }
        }
    }

    private fun onUpdate(code: Int, ints: IntArray?, flts: FloatArray?, strs: Array<String?>?) {
        val o = out ?: return
        val r = raw ?: return
        UnitPlatform.markUsed(Cap.VEHICLE_BUS)
        for ((k, v) in FytKey.values(FytModules.CANBUS, code, ints, flts, strs)) r(k, v)
        when (code) {
            FytCanbus.U_CANBUS_ID -> ints?.firstOrNull()?.let { id ->
                if (canbusId != id) {
                    canbusId = id
                    Log.i(TAG, "CAN decoder 0x${Integer.toHexString(id)}: box ${FytCanbus.decoderOf(id)}, car ${FytCanbus.carOf(id)}")
                }
            }
            FytCanbus.U_CUR_SPEED -> unitBusSpeed(o, FytCanbus.speedKmh(ints))
            FytCanbus.U_ENGINE_SPEED -> unitBusRpm(o, FytCanbus.rpm(ints))
        }
    }

    companion object {
        private const val TAG = "FytCanbus"
    }
}

/**
 * The wheel and panel keys through module MAIN, which the firmware only
 * hands to the app that took them (`sys.carlink.type` set to 2, as the
 * CarPlay app does). Opt-in, since the firmware's own apps lose those keys
 * meanwhile: Settings → Head unit, or the debug property on the emulator.
 * The previous value is put back when the keys are let go.
 */
internal class FytKeys(private val app: Context, private val toolkit: FytToolkit) : KeySource {
    override val vendor = "fyt"

    private val _listening = MutableStateFlow(false)
    override val listening: StateFlow<Boolean> = _listening

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var onKey: ((UnitKeyPress) -> Unit)? = null
    private var module: FytToolkit.FytModule? = null
    private var registeredAt = 0L
    private var previousType: String? = null

    private val callback by lazy {
        FytToolkit.FytCallback(toolkit.feed) { code, ints, _, _ ->
            if (code != FytMain.U_KEY) return@FytCallback
            // Registering with the current value replays the last key pressed: not a press.
            if (SystemClock.elapsedRealtime() - registeredAt < REPLAY_MS) return@FytCallback
            val key = ints?.firstOrNull() ?: return@FytCallback
            UnitPlatform.markUsed(Cap.UNIT_KEYS)
            Log.d(TAG, "unit key $key")
            onKey?.invoke(UnitKeyPress(key, down = true))
        }
    }

    override fun start(context: Context, onKey: (UnitKeyPress) -> Unit) {
        if (this.onKey != null || !enabled(app)) return
        this.onKey = onKey
        toolkit.onConnection { up -> if (up) subscribe() else { module = null; _listening.value = false } }
        toolkit.connect()
    }

    override fun stop() {
        onKey = null
        module?.unregister(callback, FytMain.U_KEY)
        module = null
        _listening.value = false
        previousType?.let { back -> scope.launch { runCatching { DockShell.shell(app, "setprop ${FytProtocol.PROP_CARLINK_TYPE} $back") } } }
    }

    private fun subscribe() {
        val m = toolkit.module(FytModules.MAIN) ?: run { Log.w(TAG, "no MAIN module: no unit keys"); return }
        module = m
        registeredAt = SystemClock.elapsedRealtime()
        if (!m.register(callback, FytMain.U_KEY)) return
        scope.launch {
            val was = SysProps.get(FytProtocol.PROP_CARLINK_TYPE).orEmpty()
            if (was == FytProtocol.CARLINK_TAKE_KEYS) { _listening.value = true; return@launch }
            val out = runCatching { DockShell.shell(app, "setprop ${FytProtocol.PROP_CARLINK_TYPE} ${FytProtocol.CARLINK_TAKE_KEYS}") }
                .getOrElse { "shell failed: ${it.message}" }
            val ok = SysProps.get(FytProtocol.PROP_CARLINK_TYPE) == FytProtocol.CARLINK_TAKE_KEYS
            if (ok) previousType = was.ifEmpty { "0" }
            _listening.value = ok
            Log.i(TAG, if (ok) "took the unit's keys (${FytProtocol.PROP_CARLINK_TYPE} was \"$was\")" else "could not take the unit's keys: ${out.trim().ifEmpty { "no answer" }}")
        }
    }

    override fun nameRes(code: Int): Int? = if (code == FytMain.KEY_VOICE) R.string.wheel_unit_voice else null

    companion object {
        private const val TAG = "FytKeys"
        private const val PREFS = "unit_fyt"
        private const val KEY_TAKE = "take_keys"
        /** In debug builds the emulator harness turns the keys on with `setprop persist.dashwheel.fytkeys 1`. */
        private const val DEBUG_PROP = "persist.dashwheel.fytkeys"
        private const val REPLAY_MS = 800L

        fun enabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_TAKE, false) ||
                (BuildConfig.DEBUG && SysProps.get(DEBUG_PROP) == "1")

        fun setEnabled(context: Context, on: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_TAKE, on).apply()
        }
    }
}

/**
 * Calls on the unit's Bluetooth through module BT. Its state code 4 is a
 * ringing call and 2 the idle, connected phone (fyt-dialer); the codes in
 * between are unconfirmed, so a call is taken as answered when the state
 * leaves "ringing" for anything but "idle".
 */
internal class FytPhone(private val toolkit: FytToolkit) : PhoneBridge {
    private val _call = MutableStateFlow<PhoneCall?>(null)
    override val call: StateFlow<PhoneCall?> = _call

    private var module: FytToolkit.FytModule? = null
    @Volatile private var phoneName: String? = null
    private var started = false

    private val callback by lazy { FytToolkit.FytCallback(toolkit.feed, ::onUpdate) }

    override fun start(context: Context) {
        if (started) return
        started = true
        toolkit.onConnection { up -> if (up) subscribe() else module = null }
        toolkit.connect()
    }

    private fun subscribe() {
        val m = toolkit.module(FytModules.BT) ?: run { Log.w(TAG, "no BT module"); return }
        module = m
        m.register(callback, FytBt.U_PHONE_NAME)
        m.register(callback, FytBt.U_PHONE_STATE)
    }

    private fun onUpdate(code: Int, ints: IntArray?, flts: FloatArray?, strs: Array<String?>?) {
        when (code) {
            FytBt.U_PHONE_NAME -> phoneName = strs?.lastOrNull { !it.isNullOrBlank() }
            FytBt.U_PHONE_STATE -> onState(ints?.firstOrNull() ?: return)
        }
    }

    private fun onState(state: Int) {
        val before = _call.value
        val now = SystemClock.elapsedRealtime()
        _call.value = when {
            state == FytBt.STATE_RINGING -> {
                UnitPlatform.markUsed(Cap.PHONE_BRIDGE)
                PhoneCall(CallState.Phase.RINGING, number = null, name = phoneName, photo = null, answeredAt = now, canControl = true, viaHeadUnit = true)
            }
            state == FytBt.STATE_CONNECTED || state < FytBt.STATE_CONNECTED -> null
            before != null -> if (before.phase == CallState.Phase.ACTIVE) before else before.copy(phase = CallState.Phase.ACTIVE, answeredAt = now)
            else -> null
        }
        Log.d(TAG, "phone state $state")
    }

    override fun accept() { module?.cmd(FytBt.C_PICKUP) }
    override fun decline() { module?.cmd(FytBt.C_HANG) }
    override fun hangUp() { module?.cmd(FytBt.C_HANG) }

    companion object {
        private const val TAG = "FytPhone"
    }
}
