package com.openauto.dash

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Do Not Disturb: by Android's own Do Not Disturb access when the driver gave
 * it, else through the shell; with neither, that access is asked for.
 */
private suspend fun setDoNotDisturb(context: Context, on: Boolean): Boolean {
    val nm = context.getSystemService(NotificationManager::class.java)
    if (nm != null && nm.isNotificationPolicyAccessGranted) {
        nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
        return true
    }
    return try {
        DockShell.shell(context, "cmd notification set_dnd ${if (on) "on" else "off"}")
        true
    } catch (e: Exception) {
        Log.w("QuickSwitches", "do not disturb refused: ${e.message}")
        runCatching {
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        false
    }
}

private fun doNotDisturbOn(context: Context): Boolean =
    context.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter.let {
        it != null && it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

/** Mute, the dashboard's day and night look, Do Not Disturb, Wi-Fi and Bluetooth: one tap each. */
@Composable
internal fun QuickSwitchesCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) { RadioSwitches.start(context); onDispose { RadioSwitches.stop(context) } }
    val wifi by RadioSwitches.wifi.collectAsState()
    val bt by RadioSwitches.bluetooth.collectAsState()
    var look by remember { mutableStateOf(DashThemeStore.loadAppearance(context)) }
    LaunchedEffect(Unit) { DashThemeStore.appearanceAsked.collect { look = it } }
    var dnd by remember { mutableStateOf(doNotDisturbOn(context)) }
    // Each state in a word under the name, as the Wi-Fi and Bluetooth tile does: tint alone said nothing.
    val onWord = stringResource(R.string.apps_radio_on)
    val offWord = stringResource(R.string.apps_radio_off)
    val switching = stringResource(R.string.apps_radio_switching)
    fun word(on: Boolean) = if (on) onWord else offWord
    fun radioLine(s: RadioSwitches.State) = when {
        s.busy -> switching
        !s.on -> offWord
        s.detail != null -> s.detail
        else -> onWord
    }
    // Muted is a music volume at zero; where the keys set it (MediaVolume) the level isn't known.
    val audio = remember { MediaVolume.audio(context) }
    val volume by rememberMusicVolume(audio)
    val byKeys by MediaVolume.byKeys.collectAsState()
    val muted = !byKeys && volume == 0
    Card(modifier = modifier) {
        Row(modifier = Modifier.fillMaxSize().padding(DashSpace.Md), horizontalArrangement = Arrangement.spacedBy(DashSpace.Sm)) {
            val each = Modifier.weight(1f).fillMaxHeight()
            Switch(Icons.Filled.VolumeOff, stringResource(R.string.widgets_switch_mute), on = muted, line = if (byKeys) null else word(muted), modifier = each) {
                MediaVolume.toggleMute(context)
            }
            Switch(
                when (look) { DashAppearance.AUTO -> Icons.Filled.BrightnessAuto; DashAppearance.DARK -> Icons.Filled.DarkMode; DashAppearance.LIGHT -> Icons.Filled.LightMode },
                stringResource(R.string.widgets_switch_look), on = look != DashAppearance.AUTO, line = stringResource(look.titleRes), modifier = each
            ) {
                val next = DashAppearance.entries[(look.ordinal + 1) % DashAppearance.entries.size]
                look = next
                DashThemeStore.askAppearance(context, next)
            }
            Switch(
                if (dnd) Icons.Filled.DoNotDisturbOn else Icons.Filled.NotificationsActive,
                stringResource(R.string.widgets_switch_dnd), on = dnd, line = word(dnd), modifier = each
            ) {
                scope.launch {
                    setDoNotDisturb(context, !dnd)
                    delay(500)
                    dnd = doNotDisturbOn(context)
                }
            }
            Switch(if (wifi.on) Icons.Filled.Wifi else Icons.Filled.WifiOff, stringResource(R.string.apps_radio_wifi), on = wifi.on, line = radioLine(wifi), modifier = each) {
                RadioSwitches.toggleWifi(context)
            }
            Switch(if (bt.on) Icons.Filled.Bluetooth else Icons.Filled.BluetoothDisabled, stringResource(R.string.apps_radio_bluetooth), on = bt.on, line = radioLine(bt), modifier = each) {
                RadioSwitches.toggleBluetooth(context)
            }
        }
    }
}

/** One switch: icon, name and, under it, its state in a word ([line], or nothing where the state isn't known). */
@Composable
private fun Switch(icon: ImageVector, name: String, on: Boolean, line: String?, modifier: Modifier, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    Box(
        modifier = modifier.clip(DashShape.Medium)
            .background(if (on) DashColors.Accent.copy(alpha = 0.22f) else DashColors.CardHi)
            .clickable(role = Role.Button, onClickLabel = name) { tap(); onClick() }
            .padding(DashSpace.Xs),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = if (on) DashColors.Accent else DashColors.TextPrimary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(4.dp))
            Text(name, color = DashColors.TextPrimary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (line != null) {
                Text(line, color = if (on) DashColors.AccentInk else DashColors.TextSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun quickSwitchesFace(): WidgetFace {
    val context = LocalContext.current
    DisposableEffect(Unit) { RadioSwitches.start(context); onDispose { RadioSwitches.stop(context) } }
    val wifi by RadioSwitches.wifi.collectAsState()
    val bt by RadioSwitches.bluetooth.collectAsState()
    // Each radio's state in a word, as the tile itself says it: a tinted button alone said nothing.
    val onWord = stringResource(R.string.apps_radio_on)
    val offWord = stringResource(R.string.apps_radio_off)
    val wifiName = stringResource(R.string.apps_radio_wifi)
    val btName = stringResource(R.string.apps_radio_bluetooth)
    return WidgetFace(
        icon = Icons.Filled.BrightnessAuto,
        title = BuiltinKind.QUICK_SWITCHES.label,
        value = "",
        caption = "$wifiName ${if (wifi.on) onWord else offWord} · $btName ${if (bt.on) onWord else offWord}",
        stats = listOf(
            FaceStat(wifiName, wifi.detail ?: if (wifi.on) onWord else offWord),
            FaceStat(btName, if (bt.on) onWord else offWord)
        ),
        actions = listOf(
            FaceAction(Icons.Filled.VolumeOff, stringResource(R.string.widgets_switch_mute), onClick = { MediaVolume.toggleMute(context) }),
            FaceAction(if (wifi.on) Icons.Filled.Wifi else Icons.Filled.WifiOff, stringResource(R.string.apps_radio_wifi), onClick = { RadioSwitches.toggleWifi(context) }, primary = wifi.on),
            FaceAction(if (bt.on) Icons.Filled.Bluetooth else Icons.Filled.BluetoothDisabled, stringResource(R.string.apps_radio_bluetooth), onClick = { RadioSwitches.toggleBluetooth(context) }, primary = bt.on)
        )
    )
}
