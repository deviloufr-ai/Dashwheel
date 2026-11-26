package com.openauto.dash

import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.DesktopAccessDisabled
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.VoiceOverOff
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.ui.graphics.vector.ImageVector
import com.openauto.unitproto.UnitFamily
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One switch the Quick switches tile and the bar's quick panel can show, in the order they draw them. */
internal enum class QuickSwitch(val key: String, val icon: ImageVector, @StringRes val label: Int, @StringRes val detail: Int) {
    // What the car says by itself, off for this drive: the same switch as the wheel's Quiet.
    QUIET("quiet", Icons.Filled.VoiceOverOff, R.string.voice_menu_quiet, R.string.voice_quiet_on),
    MUTE("mute", Icons.Filled.VolumeOff, R.string.widgets_switch_mute, R.string.widgets_switch_mute_detail),
    LOOK("look", Icons.Filled.BrightnessAuto, R.string.widgets_switch_look, R.string.widgets_switch_look_detail),
    DND("dnd", Icons.Filled.DoNotDisturbOn, R.string.widgets_switch_dnd, R.string.widgets_switch_dnd_detail),
    WIFI("wifi", Icons.Filled.Wifi, R.string.apps_radio_wifi, R.string.widgets_switch_wifi_detail),
    BLUETOOTH("bluetooth", Icons.Filled.Bluetooth, R.string.apps_radio_bluetooth, R.string.widgets_switch_bluetooth_detail),
    MOBILE_DATA("mobile_data", Icons.Filled.SignalCellularAlt, R.string.widgets_switch_mobile_data, R.string.widgets_switch_mobile_data_detail),
    SCREEN_OFF("screen_off", Icons.Filled.DesktopAccessDisabled, R.string.widgets_switch_screen_off, R.string.widgets_switch_screen_off_detail);

    companion object {
        fun ofKey(key: String): QuickSwitch? = entries.firstOrNull { it.key == key }
    }
}

/** Which switches the driver wants on the Quick switches tile, saved as a comma list. */
internal object QuickSwitchStore {
    private const val PREFS = "quick_switches"
    private const val KEY_CHOSEN = "chosen"

    /** What the tile always showed before it could be chosen, and Quiet, which the bar's menu always had. */
    val DEFAULT: Set<QuickSwitch> = setOf(QuickSwitch.QUIET, QuickSwitch.MUTE, QuickSwitch.LOOK, QuickSwitch.DND, QuickSwitch.WIFI, QuickSwitch.BLUETOOTH)

    private val _chosen = MutableStateFlow(DEFAULT)
    val chosen: StateFlow<Set<QuickSwitch>> = _chosen.asStateFlow()
    private var loaded = false

    /** The saved list; nothing saved means [DEFAULT], names this build doesn't know are dropped. */
    fun parse(saved: String?): Set<QuickSwitch> =
        if (saved == null) DEFAULT
        else saved.split(',').mapNotNull { QuickSwitch.ofKey(it.trim()) }.toSet()

    fun encode(chosen: Set<QuickSwitch>): String = QuickSwitch.entries.filter { it in chosen }.joinToString(",") { it.key }

    /** The switches to draw: chosen and doable here, in the tile's order. */
    fun shown(chosen: Set<QuickSwitch>, available: Set<QuickSwitch>): List<QuickSwitch> =
        QuickSwitch.entries.filter { it in chosen && it in available }

    /**
     * The switches this unit can work: Screen off is the QF firmware's own
     * screen key, sent through a shell; Mobile data needs a SIM slot.
     */
    fun available(qf: Boolean, shell: Boolean, telephony: Boolean): Set<QuickSwitch> =
        QuickSwitch.entries.filter {
            when (it) {
                QuickSwitch.SCREEN_OFF -> qf && shell
                QuickSwitch.MOBILE_DATA -> telephony
                else -> true
            }
        }.toSet()

    fun available(context: Context): Set<QuickSwitch> = available(
        qf = UnitPlatform.adapter.family == UnitFamily.QF,
        shell = PrivilegedShell.access.value.shell,
        telephony = context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
    )

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        _chosen.value = parse(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CHOSEN, null))
    }

    fun set(context: Context, switch: QuickSwitch, on: Boolean) {
        load(context)
        _chosen.value = if (on) _chosen.value + switch else _chosen.value - switch
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_CHOSEN, encode(_chosen.value)).apply()
    }
}
