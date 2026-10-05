package com.openauto.dash

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.provider.Settings
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The head unit's Wi-Fi and Bluetooth, on and off from a tile: state read from
 * the system's own broadcasts, switched through the system calls where Android
 * still allows them (Bluetooth up to Android 12), else through the privileged
 * shell (`svc wifi` / `svc bluetooth`, as Android 10 refuses Wi-Fi to apps), and
 * last the system's own panel for the driver to flip it there.
 */
object RadioSwitches {
    private const val TAG = "RadioSwitches"

    data class State(val on: Boolean, val busy: Boolean = false, val detail: String? = null)

    private val _wifi = MutableStateFlow(State(false))
    val wifi: StateFlow<State> = _wifi
    private val _bluetooth = MutableStateFlow(State(false))
    val bluetooth: StateFlow<State> = _bluetooth

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var users = 0
    private var appContext: Context? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = read(context)
    }

    /** Reads the state while a tile shows it; each [start] wants its [stop]. */
    fun start(context: Context) {
        val app = context.applicationContext
        appContext = app
        read(app)
        if (users++ > 0) return
        val filter = IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction("android.bluetooth.device.action.ACL_CONNECTED")
            addAction("android.bluetooth.device.action.ACL_DISCONNECTED")
        }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    fun stop(context: Context) {
        if (users == 0 || --users > 0) return
        runCatching { context.applicationContext.unregisterReceiver(receiver) }
    }

    @SuppressLint("MissingPermission")
    private fun read(context: Context) {
        val wm = context.getSystemService(WifiManager::class.java)
        val wifiOn = wm?.isWifiEnabled == true
        @Suppress("DEPRECATION")
        val ssid = if (wifiOn) runCatching { wm?.connectionInfo?.ssid }.getOrNull()
            ?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && it != "<unknown ssid>" } else null
        _wifi.value = State(wifiOn, busy = _wifi.value.busy && _wifi.value.on != wifiOn, detail = ssid)

        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val btOn = runCatching { adapter?.isEnabled == true }.getOrDefault(false)
        _bluetooth.value = State(btOn, busy = _bluetooth.value.busy && _bluetooth.value.on != btOn)
    }

    fun toggleWifi(context: Context) {
        val app = context.applicationContext
        val on = !_wifi.value.on
        _wifi.value = _wifi.value.copy(busy = true)
        scope.launch {
            @Suppress("DEPRECATION")
            val asked = runCatching { app.getSystemService(WifiManager::class.java)?.setWifiEnabled(on) == true }.getOrDefault(false)
            if (!asked && !viaShell(app, "svc wifi ${if (on) "enable" else "disable"}")) openPanel(app, Settings.Panel.ACTION_WIFI)
            settle(app)
        }
    }

    @SuppressLint("MissingPermission")
    fun toggleBluetooth(context: Context) {
        val app = context.applicationContext
        val on = !_bluetooth.value.on
        _bluetooth.value = _bluetooth.value.copy(busy = true)
        scope.launch {
            val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
            @Suppress("DEPRECATION")
            val asked = runCatching { if (on) adapter?.enable() == true else adapter?.disable() == true }.getOrDefault(false)
            if (!asked && !viaShell(app, "svc bluetooth ${if (on) "enable" else "disable"}")) {
                openPanel(app, Settings.ACTION_BLUETOOTH_SETTINGS)
            }
            settle(app)
        }
    }

    /** The radio takes a moment: read again, and let go of "busy" if no broadcast came. */
    private suspend fun settle(context: Context) {
        delay(SETTLE_MS)
        read(context)
        _wifi.value = _wifi.value.copy(busy = false)
        _bluetooth.value = _bluetooth.value.copy(busy = false)
    }

    private suspend fun viaShell(context: Context, cmd: String): Boolean = try {
        DockShell.shell(context, cmd)
        true
    } catch (e: Exception) {
        Log.w(TAG, "'$cmd' refused: ${e.message}")
        false
    }

    private fun openPanel(context: Context, action: String) {
        runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Log.w(TAG, "no settings for $action", it) }
    }

    private const val SETTLE_MS = 4_000L
}

/** The tile: one big switch for each radio, with the network or the state under it. */
@Composable
internal fun RadioSwitchesCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        RadioSwitches.start(context)
        onDispose { RadioSwitches.stop(context) }
    }
    val wifi by RadioSwitches.wifi.collectAsState()
    val bt by RadioSwitches.bluetooth.collectAsState()
    Card(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxSize().padding(DashSpace.Md),
            horizontalArrangement = Arrangement.spacedBy(DashSpace.Md)
        ) {
            RadioButton(
                icon = if (wifi.on) Icons.Filled.Wifi else Icons.Filled.WifiOff,
                name = stringResource(R.string.apps_radio_wifi),
                state = wifi,
                modifier = Modifier.weight(1f).fillMaxHeight()
            ) { RadioSwitches.toggleWifi(context) }
            RadioButton(
                icon = if (bt.on) Icons.Filled.Bluetooth else Icons.Filled.BluetoothDisabled,
                name = stringResource(R.string.apps_radio_bluetooth),
                state = bt,
                modifier = Modifier.weight(1f).fillMaxHeight()
            ) { RadioSwitches.toggleBluetooth(context) }
        }
    }
}

@Composable
private fun RadioButton(icon: ImageVector, name: String, state: RadioSwitches.State, modifier: Modifier, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    val line = when {
        state.busy -> stringResource(R.string.apps_radio_switching)
        !state.on -> stringResource(R.string.apps_radio_off)
        state.detail != null -> state.detail
        else -> stringResource(R.string.apps_radio_on)
    }
    Box(
        modifier = modifier
            .clip(DashShape.Medium)
            .background(if (state.on) DashColors.Accent.copy(alpha = 0.22f) else DashColors.CardHi)
            .clickable(role = Role.Switch, onClickLabel = name) { tap(); onClick() }
            .padding(DashSpace.Md),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = if (state.on) DashColors.Accent else DashColors.Muted, modifier = Modifier.size(34.dp))
            Spacer(Modifier.height(6.dp))
            Text(name, color = DashColors.TextPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(line, color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The tile as the designs draw it: both states, and a switch for each. */
@Composable
internal fun radioSwitchesFace(): WidgetFace {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        RadioSwitches.start(context)
        onDispose { RadioSwitches.stop(context) }
    }
    val wifi by RadioSwitches.wifi.collectAsState()
    val bt by RadioSwitches.bluetooth.collectAsState()
    val on = stringResource(R.string.apps_radio_on)
    val off = stringResource(R.string.apps_radio_off)
    val wifiName = stringResource(R.string.apps_radio_wifi)
    val btName = stringResource(R.string.apps_radio_bluetooth)
    return WidgetFace(
        icon = if (wifi.on) Icons.Filled.Wifi else Icons.Filled.WifiOff,
        title = BuiltinKind.RADIOS.label,
        value = if (wifi.on) on else off,
        caption = wifi.detail ?: "",
        textValue = true,
        active = wifi.on || bt.on,
        stats = listOf(
            FaceStat(wifiName, wifi.detail ?: if (wifi.on) on else off),
            FaceStat(btName, if (bt.on) on else off)
        ),
        actions = listOf(
            FaceAction(if (wifi.on) Icons.Filled.Wifi else Icons.Filled.WifiOff, wifiName, onClick = { RadioSwitches.toggleWifi(context) }, primary = wifi.on),
            FaceAction(if (bt.on) Icons.Filled.Bluetooth else Icons.Filled.BluetoothDisabled, btName, onClick = { RadioSwitches.toggleBluetooth(context) }, primary = bt.on)
        )
    )
}
