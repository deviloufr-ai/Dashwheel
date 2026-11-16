package com.openauto.dash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The USB CAN adapter's row in Settings, Car: what it is doing now. */
@Composable
internal fun UsbCanRow(onClick: () -> Unit) {
    val state by UsbCan.state.collectAsState()
    SettingsRow(Icons.Filled.Cable, stringResource(R.string.can_usb_title), stringResource(state.summaryRes), onClick)
}

/**
 * Which USB serial device is the CAN adapter ([UsbCan]), the bus's bit rate,
 * whether it hears anything, and the frames heard, to find the one that
 * moves with a pedal, a door or the fuel gauge.
 */
@Composable
internal fun UsbCanSheet(onDismiss: () -> Unit) {
    ParkedOnly(onDismiss)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val chosen by UsbCan.device.collectAsState()
    val state by UsbCan.state.collectAsState()
    val bitrate by UsbCan.bitrate.collectAsState()
    var looked by remember { mutableIntStateOf(0) }
    var attached by remember { mutableStateOf<List<UsbObdAdapter>?>(null) }
    var denied by remember { mutableStateOf(false) }
    var frames by remember { mutableStateOf<List<CanSeen>>(emptyList()) }
    LaunchedEffect(looked, state) { attached = withContext(Dispatchers.IO) { UsbObd.attached(context) } }
    // Twice a second: enough to see a byte move with a pedal, without redrawing at the bus's pace.
    LaunchedEffect(chosen) {
        while (chosen != null) {
            frames = UsbCan.heard()
            delay(500)
        }
        frames = emptyList()
    }
    fun use(adapter: UsbObdAdapter) = scope.launch {
        denied = !UsbObd.allow(context, adapter)
        if (!denied) UsbCan.choose(context, adapter)
    }
    SettingsSheet(
        title = stringResource(R.string.can_usb_title),
        onDismiss = onDismiss,
        actions = { SheetButton(stringResource(R.string.ai_done), onClick = onDismiss) }
    ) {
        Text(stringResource(R.string.can_usb_explanation), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)

        Label(stringResource(R.string.tpms_usb_devices))
        val list = attached
        if (list != null && list.isEmpty()) Hint(stringResource(R.string.tpms_usb_none_plugged))
        CompositionLocalProvider(LocalSheetInPane provides true) {
            list?.forEach { adapter ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(adapter.label, color = DashColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Hint(adapter.key)
                    }
                    Spacer(Modifier.width(8.dp))
                    if (adapter.key == chosen) {
                        SheetButton(stringResource(R.string.tpms_usb_forget), primary = false) { UsbCan.forget(context) }
                    } else {
                        SheetButton(stringResource(R.string.tpms_usb_use)) { use(adapter) }
                    }
                }
            }
            SheetButton(stringResource(R.string.tpms_usb_look_again), primary = false) { looked++ }
        }
        if (denied) Hint(stringResource(R.string.tpms_usb_denied))

        Label(stringResource(R.string.can_usb_bitrate))
        ChoiceRow(Slcan.CAR_BITRATES, bitrate, { "${it / 1000}k" }) { UsbCan.setBitrate(context, it) }
        Hint(stringResource(R.string.can_usb_bitrate_hint))

        if (chosen == null) return@SettingsSheet
        Label(stringResource(state.summaryRes))
        val adapter = list?.firstOrNull { it.key == chosen }
        if (state == UsbCanState.NOT_ALLOWED && adapter != null) {
            CompositionLocalProvider(LocalSheetInPane provides true) {
                SheetButton(stringResource(R.string.tpms_usb_allow)) { use(adapter) }
            }
        }
        when (state) {
            UsbCanState.BUSY -> Hint(stringResource(R.string.tpms_usb_busy_hint))
            UsbCanState.NOT_SLCAN -> Hint(stringResource(R.string.can_usb_not_slcan_hint))
            UsbCanState.NO_LISTEN_ONLY -> Hint(stringResource(R.string.can_usb_no_listen_hint))
            UsbCanState.LISTENING -> Hint(stringResource(R.string.can_usb_quiet_hint))
            else -> Unit
        }

        Label(stringResource(R.string.can_usb_frames, frames.size))
        Hint(stringResource(R.string.can_usb_frames_hint))
        if (frames.isNotEmpty()) {
            CompositionLocalProvider(LocalSheetInPane provides true) {
                SheetButton(stringResource(R.string.can_usb_clear), primary = false) { UsbCan.clearHeard(); frames = emptyList() }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            frames.take(MAX_SHOWN).forEach { seen ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${seen.frame.idText.padStart(3)}  ${seen.frame.dataText}",
                        color = if (seen.changes > 0) DashColors.TextPrimary else DashColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                    )
                    Text(
                        stringResource(R.string.can_usb_changes, seen.changes),
                        color = DashColors.Muted, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

/** A busy bus has a hundred ids or more; past these the list scrolls for nothing. */
private const val MAX_SHOWN = 120

@Composable
private fun Hint(text: String) {
    Text(text, color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
}

private val UsbCanState.summaryRes: Int
    get() = when (this) {
        UsbCanState.OFF -> R.string.tpms_usb_state_off
        UsbCanState.UNPLUGGED -> R.string.can_usb_state_unplugged
        UsbCanState.NOT_ALLOWED -> R.string.tpms_usb_state_not_allowed
        UsbCanState.BUSY -> R.string.can_usb_state_busy
        UsbCanState.NOT_SLCAN -> R.string.can_usb_state_not_slcan
        UsbCanState.NO_LISTEN_ONLY -> R.string.can_usb_state_no_listen
        UsbCanState.LISTENING -> R.string.can_usb_state_listening
        UsbCanState.RECEIVING -> R.string.can_usb_state_receiving
    }
