package com.openauto.dash.unit.mtc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import com.openauto.dash.CameraControl
import com.openauto.dash.Cap
import com.openauto.dash.CapClaim
import com.openauto.dash.unitBusOutsideTemp
import com.openauto.dash.unitBusRpm
import com.openauto.dash.unitBusSpeed
import com.openauto.dash.unitBusVolts
import com.openauto.dash.vehicle.Publisher
import com.openauto.dash.IgnitionEvent
import com.openauto.dash.IgnitionSource
import com.openauto.dash.KeySource
import com.openauto.dash.LauncherTakeover
import com.openauto.dash.MediaBridge
import com.openauto.dash.Need
import com.openauto.dash.PhoneBridge
import com.openauto.dash.PopupTakeover
import com.openauto.dash.PrivilegedShell
import com.openauto.dash.R
import com.openauto.dash.SignalCatalogue
import com.openauto.dash.SleepSurvival
import com.openauto.dash.SurvivalStep
import com.openauto.dash.UnitAdapter
import com.openauto.dash.UnitCaps
import com.openauto.dash.UnitKeyPress
import com.openauto.dash.UnitPlatform
import com.openauto.dash.VehicleBusSource
import com.openauto.dash.VolumeControl
import com.openauto.unitproto.UnitFamily
import com.openauto.unitproto.mtc.MtcIrKeys
import com.openauto.unitproto.mtc.MtcProtocol
import com.openauto.unitproto.mtc.MtcSync
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/*
 * Microntek MTCD / MTCE (Dasaita, Xtrons, Eonon, Pumpkin... on PX5 / PX6 /
 * PX30) and HCT's MTCH (QCM6125): keys through the framework's CarManager
 * or its key broadcasts, the ignition's end through its power-off
 * broadcast, the Vehicle app's sync frame as raw bytes for the Signal
 * Finder (its layout is learned, not known). The firmware force-stops apps
 * it has not whitelisted at ignition off, so staying alive is a step the
 * driver takes in its BootControl app or ROM mod settings.
 */
internal class MtcAdapter(context: Context) : UnitAdapter {
    private val app = context.applicationContext

    override val family = UnitFamily.MTC

    override val ignition: IgnitionSource = MtcIgnition
    override val bus: VehicleBusSource = MtcSyncBus
    override val keys: KeySource = MtcKeys
    override val phone: PhoneBridge? = null
    override val media: MediaBridge? = null
    override val volume: VolumeControl? = null
    override val launcher: LauncherTakeover? = null
    override val popups: PopupTakeover? = null
    override val survival: SleepSurvival = MtcSurvival(app)
    override val camera: CameraControl? = null

    override val caps: UnitCaps = UnitCaps(
        listOf(
            CapClaim(Cap.IGNITION),
            CapClaim(Cap.UNIT_KEYS),
            CapClaim(Cap.VEHICLE_BUS),
            CapClaim(Cap.SLEEP_SURVIVAL)
        )
    )

    override val catalogue = SignalCatalogue(broadcasts = MtcProtocol.BROADCASTS, settingsSystem = true)

    override val vendorPackages: Set<String> = MtcProtocol.PACKAGES
}

/** Power-off as the unit announces it; a wake broadcast on some ROMs. Nothing to read in between. */
internal object MtcIgnition : IgnitionSource {
    override fun read(): Boolean? = null
    override val actions: Set<String> = setOf(MtcProtocol.ACTION_BOOTCHECK, MtcProtocol.ACTION_START_APP)
    override fun decode(intent: Intent): IgnitionEvent? = when (intent.action) {
        MtcProtocol.ACTION_BOOTCHECK -> if (intent.getStringExtra(MtcProtocol.EXTRA_CLASS) == MtcProtocol.CLASS_POWEROFF) IgnitionEvent.OFF else null
        MtcProtocol.ACTION_START_APP -> IgnitionEvent.WAKE
        else -> null
    }
}

/**
 * The unit's keys: through the CarManager where the framework has it, else
 * its key broadcasts (PX3 and some ROMs), never both for one press.
 */
internal object MtcKeys : KeySource {
    private const val TAG = "MtcKeys"
    override val vendor = "mtc"

    private val _listening = MutableStateFlow(false)
    override val listening: StateFlow<Boolean> = _listening

    private var manager: MtcCarManager? = null
    private var onKey: ((UnitKeyPress) -> Unit)? = null
    private var receiving = false

    override fun start(context: Context, onKey: (UnitKeyPress) -> Unit) {
        if (this.onKey != null) return
        this.onKey = onKey
        val app = context.applicationContext
        val m = MtcCarManager.open()
        if (m != null && m.listenKeys { code -> press(code, true) }) {
            manager = m
            _listening.value = true
            Log.i(TAG, "listening to the unit's keys through the CarManager")
            return
        }
        val filter = IntentFilter().apply {
            addAction(MtcProtocol.ACTION_IR_KEY_DOWN)
            addAction(MtcProtocol.ACTION_IR_KEY_UP)
            addAction(MtcProtocol.ACTION_REPORT_EVENT)
        }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        receiving = true
        _listening.value = true
        Log.i(TAG, "listening to the unit's key broadcasts")
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                MtcProtocol.ACTION_IR_KEY_DOWN -> press(intent.getIntExtra(MtcProtocol.EXTRA_KEY_CODE, -1), true)
                MtcProtocol.ACTION_IR_KEY_UP -> press(intent.getIntExtra(MtcProtocol.EXTRA_KEY_CODE, -1), false)
                MtcProtocol.ACTION_REPORT_EVENT -> if (intent.getStringExtra(MtcProtocol.BUNDLE_TYPE) == MtcProtocol.TYPE_KEY) {
                    press(intent.getIntExtra(MtcProtocol.BUNDLE_VALUE, -1), true)
                }
            }
        }
    }

    private fun press(code: Int, down: Boolean) {
        if (code < 0) return
        if (down) UnitPlatform.markUsed(Cap.UNIT_KEYS)
        Log.d(TAG, "unit key $code ${if (down) "down" else "up"}")
        onKey?.invoke(UnitKeyPress(code, down))
    }

    override fun stop() {
        manager?.stop()
        manager = null
        onKey = null
        _listening.value = false
    }

    override fun nameRes(code: Int): Int? = when (MtcIrKeys.name(code)) {
        "power" -> R.string.wheel_unit_power
        "mute" -> R.string.wheel_unit_mute
        "mode" -> R.string.wheel_unit_mode
        "volume_up" -> R.string.wheel_unit_volume_up
        "volume_down" -> R.string.wheel_unit_volume_down
        "navi" -> R.string.wheel_unit_navi
        "previous" -> R.string.wheel_unit_previous
        "next" -> R.string.wheel_unit_next
        "phone", "answer" -> R.string.wheel_unit_phone
        "hang_up" -> R.string.wheel_unit_hang_up
        "radio" -> R.string.wheel_unit_radio
        "voice" -> R.string.wheel_unit_voice
        "play_pause" -> R.string.wheel_unit_play_pause
        else -> null
    }
}

/**
 * The Vehicle app's sync frame: its bytes reach the Signal Finder through the
 * broadcast catalogue (bit by bit, to learn doors and lights from); the
 * fields are decoded only once a layout is confirmed ([MtcSync.Layout]).
 * Nothing is decoded yet (the layout is never set), so nothing is put on the vehicle bus; the code is here, on the
 * publisher API, for the day a layout is confirmed.
 */
internal object MtcSyncBus : VehicleBusSource {
    private const val TAG = "MtcSync"
    private val _listening = MutableStateFlow(false)
    override val listening: StateFlow<Boolean> = _listening

    /** The Vehicle app repeats its sync frame while the car is on: 15 s without a word that put anything is a car that is off. */
    override val silenceMs: Long = 15_000L
    private var out: Publisher? = null
    private var frames = 0

    /** The frame's layout for this unit; unknown until a tester confirms one against the dashboard. */
    var layout: MtcSync.Layout = MtcSync.Layout.UNKNOWN

    override fun start(context: Context, publisher: Publisher, raw: (String, String) -> Unit) {
        if (out != null) return
        out = publisher
        ContextCompat.registerReceiver(context.applicationContext, receiver, IntentFilter(MtcProtocol.ACTION_SYNC), ContextCompat.RECEIVER_EXPORTED)
    }

    override fun stop() = Unit

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val bytes = intent.getByteArrayExtra(MtcProtocol.EXTRA_SYNC) ?: return
            val o = out ?: return
            UnitPlatform.markUsed(Cap.VEHICLE_BUS)
            _listening.value = true
            if (frames++ == 0) Log.i(TAG, "the Vehicle app's sync frames are coming (${bytes.size} bytes)")
            val d = MtcSync.decode(bytes, layout) ?: return
            d.speedKmh?.let { unitBusSpeed(o, it.toFloat()) }
            d.rpm?.let { unitBusRpm(o, it) }
            d.volts?.let { unitBusVolts(o, it) }
            d.tempC?.let { unitBusOutsideTemp(o, it.toDouble()) }
        }
    }
}

/** Staying alive through the unit's sleep: a whitelist the driver fills in, nothing Dashwheel can do itself. */
internal class MtcSurvival(private val app: Context) : SleepSurvival {
    override fun steps(): List<SurvivalStep> = listOf(
        SurvivalStep(R.string.unit_mtc_whitelist_title, R.string.unit_mtc_whitelist_detail, Need.NONE, automatic = false)
    )

    override suspend fun apply(context: Context, access: PrivilegedShell.Access): Boolean = false

    override fun atBoot(context: Context) = Unit

    /** The genuine firmware's whitelist app, to open from the step; null where a ROM mod replaced it. */
    fun bootControlIntent(): Intent? =
        if (UnitPlatform.hasPackage(app, MtcProtocol.PACKAGE_BOOTCONTROL)) app.packageManager.getLaunchIntentForPackage(MtcProtocol.PACKAGE_BOOTCONTROL) else null
}
