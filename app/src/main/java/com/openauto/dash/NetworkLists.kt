package com.openauto.dash

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lsposed.hiddenapibypass.HiddenApiBypass

/*
 * The Wi-Fi networks and Bluetooth devices tiles: the networks around (saved
 * ones first) and the paired devices, each joined or left with a tap. Android
 * 10 keeps joining and leaving to the system's own apps: Dashwheel does it
 * where it holds the system's network and Bluetooth permissions (granted by
 * PMPatch3, see MyCarLook / PmPatch), and otherwise opens the system's
 * Wi-Fi or Bluetooth screen at the same place.
 */

private const val TAG = "NetworkLists"

/** A network around, or a saved one out of range: [netId] when saved (-1 otherwise). */
internal data class WifiEntry(val ssid: String, val netId: Int, val secured: Boolean, val level: Int?, val connected: Boolean)

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
internal fun wifiEntries(context: Context): List<WifiEntry> {
    val wm = context.getSystemService(WifiManager::class.java) ?: return emptyList()
    val current = runCatching { wm.connectionInfo?.ssid?.removeSurrounding("\"") }.getOrNull()
    val saved: List<WifiConfiguration> = runCatching { wm.configuredNetworks.orEmpty() }.getOrDefault(emptyList())
    val scans: List<ScanResult> = runCatching { wm.scanResults.orEmpty() }.getOrDefault(emptyList())
    val byName = LinkedHashMap<String, WifiEntry>()
    for (s in scans.sortedByDescending { it.level }) {
        val name = s.SSID?.takeIf { it.isNotBlank() } ?: continue
        if (name in byName) continue
        val conf = saved.firstOrNull { it.SSID?.removeSurrounding("\"") == name }
        val secured = s.capabilities.contains("WPA") || s.capabilities.contains("WEP") || s.capabilities.contains("SAE")
        byName[name] = WifiEntry(name, conf?.networkId ?: -1, secured, WifiManager.calculateSignalLevel(s.level, 4), name == current)
    }
    for (c in saved) {
        val name = c.SSID?.removeSurrounding("\"") ?: continue
        if (name !in byName) byName[name] = WifiEntry(name, c.networkId, true, null, name == current)
    }
    return byName.values.sortedWith(compareByDescending<WifiEntry> { it.connected }.thenByDescending { it.netId >= 0 }.thenByDescending { it.level ?: -1 })
}

/** Joins [entry] (a saved network) or leaves it when it is the one in use; false when Android refuses it to Dashwheel. */
@Suppress("DEPRECATION")
internal fun wifiJoinOrLeave(context: Context, entry: WifiEntry): Boolean {
    val wm = context.getSystemService(WifiManager::class.java) ?: return false
    return runCatching {
        if (entry.connected) wm.disconnect()
        else if (entry.netId >= 0) {
            // The system's own join (connect by id), else the older enable-and-reconnect.
            runCatching {
                HiddenApiBypass.invoke(WifiManager::class.java, wm, "connect", entry.netId, null)
                true
            }.getOrElse { wm.enableNetwork(entry.netId, true) && wm.reconnect() }
        } else false
    }.onFailure { Log.w(TAG, "wifi ${entry.ssid}: ${it.message}") }.getOrDefault(false)
}

/** A paired device and whether it is connected (Android's own Bluetooth, not the unit's phone radio). */
internal data class BtEntry(val device: BluetoothDevice, val name: String, val connected: Boolean, val audio: Boolean)

@SuppressLint("MissingPermission")
internal fun btEntries(context: Context): List<BtEntry> {
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
    return runCatching {
        adapter.bondedDevices.orEmpty().map { d ->
            val connected = runCatching { HiddenApiBypass.invoke(BluetoothDevice::class.java, d, "isConnected") as Boolean }.getOrDefault(false)
            val cls = runCatching { d.bluetoothClass?.majorDeviceClass }.getOrNull()
            val audio = cls == android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO || cls == android.bluetooth.BluetoothClass.Device.Major.PHONE
            BtEntry(d, d.name ?: d.address, connected, audio)
        }.sortedWith(compareByDescending<BtEntry> { it.connected }.thenBy { it.name.lowercase() })
    }.getOrDefault(emptyList())
}

/**
 * Connects [entry] through the audio and phone profiles it has (or
 * disconnects it); false when Android refuses it, or the device has no such
 * profile (an OBD adapter is reached by its app, not connected here).
 */
@SuppressLint("MissingPermission")
internal suspend fun btConnectOrDisconnect(context: Context, entry: BtEntry): Boolean {
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
    var done = false
    for (profile in listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)) {
        val proxy = withContext(Dispatchers.Main) { proxyOf(context, adapter, profile) } ?: continue
        val cls = if (profile == BluetoothProfile.A2DP) BluetoothA2dp::class.java else BluetoothHeadset::class.java
        val ok = runCatching { HiddenApiBypass.invoke(cls, proxy, if (entry.connected) "disconnect" else "connect", entry.device) as Boolean }
            .onFailure { Log.w(TAG, "bt ${entry.name} profile $profile: ${it.message}") }.getOrDefault(false)
        done = done || ok
        runCatching { adapter.closeProfileProxy(profile, proxy) }
    }
    return done
}

private suspend fun proxyOf(context: Context, adapter: android.bluetooth.BluetoothAdapter, profile: Int): BluetoothProfile? {
    val result = kotlinx.coroutines.CompletableDeferred<BluetoothProfile?>()
    val asked = runCatching {
        adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(p: Int, proxy: BluetoothProfile) { result.complete(proxy) }
            override fun onServiceDisconnected(p: Int) { result.complete(null) }
        }, profile)
    }.getOrDefault(false)
    if (!asked) return null
    return kotlinx.coroutines.withTimeoutOrNull(3_000) { result.await() }
}

/** The unit's own Bluetooth app, where phones are paired, connected and changed. */
private fun openCarBluetooth(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(HeadUnitPhone.BT_PACKAGE)
        ?: Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun openSettings(context: Context, action: String) {
    runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
internal fun WifiNetworksCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val location = rememberPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    DisposableEffect(Unit) { RadioSwitches.start(context); onDispose { RadioSwitches.stop(context) } }
    val wifi by RadioSwitches.wifi.collectAsState()
    var entries by remember { mutableStateOf<List<WifiEntry>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(wifi.on, wifi.detail, refresh, location.granted) {
        @Suppress("DEPRECATION")
        runCatching { context.getSystemService(WifiManager::class.java)?.startScan() }
        while (true) {
            entries = withContext(Dispatchers.IO) { wifiEntries(context) }
            delay(10_000)
        }
    }
    val refused = stringResource(R.string.widgets_wifi_refused)
    ListCard(
        title = stringResource(R.string.widgets_wifi),
        modifier = modifier,
        onSettings = { openSettings(context, Settings.Panel.ACTION_WIFI) },
        empty = when {
            !wifi.on -> stringResource(R.string.widgets_wifi_off)
            !location.granted -> stringResource(R.string.widgets_wifi_location)
            entries.isEmpty() -> stringResource(R.string.widgets_wifi_none)
            else -> null
        },
        onEmptyClick = { if (!wifi.on) RadioSwitches.toggleWifi(context) else if (!location.granted) location.request() }
    ) {
        entries.take(8).forEach { e ->
            ListRow(
                icon = if (e.secured) Icons.Filled.Lock else Icons.Filled.Wifi,
                name = e.ssid,
                state = when {
                    e.connected -> stringResource(R.string.widgets_list_connected)
                    e.netId >= 0 -> stringResource(R.string.widgets_wifi_saved)
                    else -> ""
                },
                on = e.connected
            ) {
                if (!wifiJoinOrLeave(context, e)) {
                    Toast.makeText(context, refused, Toast.LENGTH_SHORT).show()
                    openSettings(context, Settings.Panel.ACTION_WIFI)
                }
                refresh++
            }
        }
    }
}

@Composable
internal fun BluetoothDevicesCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    DisposableEffect(Unit) { RadioSwitches.start(context); onDispose { RadioSwitches.stop(context) } }
    val bt by RadioSwitches.bluetooth.collectAsState()
    var entries by remember { mutableStateOf<List<BtEntry>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(bt.on, refresh) {
        while (true) {
            entries = withContext(Dispatchers.IO) { btEntries(context) }
            delay(5_000)
        }
    }
    val refused = stringResource(R.string.widgets_bt_refused)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // The phone is on the unit's own Bluetooth chip, run by its Bluetooth app: shown from what
    // that app tells (UnitSignals), and connected or changed in that app, the only one that can.
    val carBluetooth = remember { HeadUnitPhone.available(context) }
    val phone by UnitSignals.phone.collectAsState()
    ListCard(
        title = stringResource(R.string.widgets_bt),
        modifier = modifier,
        onSettings = { openSettings(context, Settings.ACTION_BLUETOOTH_SETTINGS) },
        empty = when {
            carBluetooth -> null
            !bt.on -> stringResource(R.string.widgets_bt_off)
            entries.isEmpty() -> stringResource(R.string.widgets_bt_none)
            else -> null
        },
        onEmptyClick = { if (!bt.on) RadioSwitches.toggleBluetooth(context) }
    ) {
        if (carBluetooth) {
            val p = phone
            ListRow(
                icon = Icons.Filled.Smartphone,
                name = p?.name ?: stringResource(R.string.widgets_bt_phone_none),
                state = if (p != null) stringResource(R.string.widgets_list_connected) else stringResource(R.string.widgets_bt_phone_change),
                on = p != null
            ) { openCarBluetooth(context) }
            if (!bt.on) {
                ListRow(Icons.Filled.Bluetooth, stringResource(R.string.widgets_bt_off), "", false) { RadioSwitches.toggleBluetooth(context) }
            }
        }
        entries.take(8).forEach { e ->
            ListRow(
                icon = if (e.connected) Icons.Filled.BluetoothConnected else Icons.Filled.Bluetooth,
                name = e.name,
                state = when {
                    busy == e.device.address -> stringResource(R.string.apps_radio_switching)
                    e.connected -> stringResource(R.string.widgets_list_connected)
                    else -> stringResource(R.string.widgets_bt_paired)
                },
                on = e.connected
            ) {
                busy = e.device.address
                scope.launchSafely {
                    val ok = btConnectOrDisconnect(context, e)
                    if (!ok) {
                        Toast.makeText(context, refused, Toast.LENGTH_SHORT).show()
                        openSettings(context, Settings.ACTION_BLUETOOTH_SETTINGS)
                    }
                    delay(2_500)
                    busy = null
                    refresh++
                }
            }
        }
    }
}

private fun kotlinx.coroutines.CoroutineScope.launchSafely(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) =
    launch(Dispatchers.Main, block = block)

@Composable
private fun ListCard(
    title: String,
    modifier: Modifier,
    onSettings: () -> Unit,
    empty: String?,
    onEmptyClick: () -> Unit,
    rows: @Composable () -> Unit
) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(DashSpace.Md)) {
            TileHeader(title) {
                IconButton(onClick = onSettings, modifier = Modifier.size(DashSize.Touch)) {
                    Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.widgets_list_settings), tint = DashColors.Muted)
                }
            }
            if (empty != null) {
                Text(
                    empty, color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onEmptyClick).padding(vertical = DashSpace.Md)
                )
            } else {
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(DashSpace.Xs)
                ) { rows() }
            }
        }
    }
}

@Composable
private fun ListRow(icon: androidx.compose.ui.graphics.vector.ImageVector, name: String, state: String, on: Boolean, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = DashSize.Touch).clip(DashShape.Small)
            .background(if (on) DashColors.Accent.copy(alpha = 0.2f) else DashColors.CardHi)
            .clickable(role = Role.Button, onClickLabel = name) { tap(); onClick() }
            .padding(horizontal = DashSpace.Md)
    ) {
        Icon(icon, contentDescription = null, tint = if (on) DashColors.Accent else DashColors.Muted, modifier = Modifier.size(20.dp))
        Text(
            "  $name", color = DashColors.TextPrimary, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)
        )
        if (state.isNotEmpty()) Text(state, color = DashColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun wifiNetworksFace(): WidgetFace {
    val context = LocalContext.current
    DisposableEffect(Unit) { RadioSwitches.start(context); onDispose { RadioSwitches.stop(context) } }
    val wifi by RadioSwitches.wifi.collectAsState()
    return WidgetFace(
        icon = Icons.Filled.Wifi,
        title = BuiltinKind.WIFI_NETWORKS.label,
        value = wifi.detail ?: stringResource(if (wifi.on) R.string.apps_radio_on else R.string.apps_radio_off),
        textValue = true,
        active = wifi.detail != null,
        actions = listOf(FaceAction(Icons.Filled.Settings, stringResource(R.string.widgets_list_settings), onClick = { openSettings(context, Settings.Panel.ACTION_WIFI) }))
    )
}

@Composable
internal fun bluetoothDevicesFace(): WidgetFace {
    val context = LocalContext.current
    var connected by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { while (true) { connected = withContext(Dispatchers.IO) { btEntries(context).firstOrNull { it.connected }?.name }; delay(5_000) } }
    return WidgetFace(
        icon = Icons.Filled.Bluetooth,
        title = BuiltinKind.BT_DEVICES.label,
        value = connected ?: stringResource(R.string.widgets_bt_none_connected),
        textValue = true,
        active = connected != null,
        actions = listOf(FaceAction(Icons.Filled.Settings, stringResource(R.string.widgets_list_settings), onClick = { openSettings(context, Settings.ACTION_BLUETOOTH_SETTINGS) }))
    )
}
