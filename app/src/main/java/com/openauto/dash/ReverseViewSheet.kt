package com.openauto.dash

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.ViewQuilt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch

/*
 * Settings, Car, Reverse view: Dashwheel's guide lines and radar over the
 * reversing camera ([ReverseView]). The lines are lined up on the reverse
 * screen itself, where the camera picture is.
 */
@Composable
internal fun ReverseViewSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val on by ReverseView.on.collectAsState()
    val layout by ReverseView.layout.collectAsState()
    val hideStock by ReverseView.hideStock.collectAsState()
    val ownCamera by ReverseView.ownCamera.collectAsState()
    val denied = stringResource(R.string.reverse_picture_denied)
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) ReverseView.setOwnCamera(context, true) else Toast.makeText(context, denied, Toast.LENGTH_LONG).show()
    }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val failed = stringResource(R.string.reverse_stock_failed)

    SettingsSheet(
        title = stringResource(R.string.reverse_title),
        onDismiss = onDismiss,
        actions = {
            SheetButton(stringResource(R.string.reverse_reset), primary = false) {
                ReverseView.setCalibration(context, ReverseCalibration())
            }
            SheetButton(stringResource(R.string.reverse_preview)) { ReverseView.tryIt() }
        }
    ) {
        SwitchRow(
            stringResource(R.string.reverse_switch),
            stringResource(R.string.reverse_switch_detail),
            checked = on
        ) { ReverseView.setOn(context, it) }
        if (on && !SplitAccessibilityService.isConnected) {
            Text(stringResource(R.string.reverse_needs_access), color = DashColors.Warning, style = MaterialTheme.typography.bodySmall)
        }

        SettingsSection(stringResource(R.string.reverse_layout_title))
        SegmentedSwitch(
            options = ReverseLayout.entries,
            chosen = layout,
            icon = {
                when (it) {
                    ReverseLayout.BOTH -> Icons.Filled.ViewQuilt
                    ReverseLayout.CAMERA -> Icons.Filled.Videocam
                    ReverseLayout.RADAR -> Icons.Filled.Sensors
                }
            },
            title = { stringResource(it.titleRes) },
            onChoose = { ReverseView.setLayout(context, it) }
        )
        SwitchHint(stringResource(R.string.reverse_layout_hint))

        SettingsSection(stringResource(R.string.reverse_picture_title))
        SegmentedSwitch(
            options = listOf(false, true),
            chosen = ownCamera,
            icon = { if (it) Icons.Filled.Videocam else Icons.Filled.Layers },
            title = { stringResource(if (it) R.string.reverse_picture_own else R.string.reverse_picture_rom) },
            onChoose = { own ->
                if (!own || ReverseCamera.hasPermission(context)) ReverseView.setOwnCamera(context, own)
                else askCamera.launch(Manifest.permission.CAMERA)
            }
        )
        SwitchHint(stringResource(if (ownCamera) R.string.reverse_picture_hint_own else R.string.reverse_picture_hint_rom))

        SettingsSection(stringResource(R.string.reverse_stock_title))
        SegmentedSwitch(
            options = listOf(false, true),
            chosen = hideStock,
            icon = { if (it) Icons.Filled.LayersClear else Icons.Filled.Layers },
            title = { stringResource(if (it) R.string.reverse_stock_hide else R.string.reverse_stock_keep) },
            onChoose = { hide ->
                if (!busy && hide != hideStock) {
                    busy = true
                    scope.launch {
                        if (!ReverseView.setHideStock(context, hide)) Toast.makeText(context, failed, Toast.LENGTH_LONG).show()
                        busy = false
                    }
                }
            }
        )
        SwitchHint(stringResource(if (hideStock) R.string.reverse_stock_hint_hide else R.string.reverse_stock_hint_keep))

        SettingsSection(stringResource(R.string.reverse_boot_title))
        val tailgate by ReverseView.tailgate.collectAsState()
        SwitchRow(stringResource(R.string.reverse_boot_title), stringResource(R.string.reverse_boot_detail), checked = tailgate > 0f) {
            ReverseView.setTailgate(context, if (it) ReverseView.DEFAULT_TAILGATE_M else 0f)
        }
        if (tailgate > 0f) {
            var room by remember(tailgate) { mutableStateOf(tailgate) }
            Text(stringResource(R.string.reverse_boot_room, (room * 100).roundToInt()), color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
            Slider(
                value = room,
                onValueChange = { room = (it * 20f).roundToInt() / 20f },
                onValueChangeFinished = { ReverseView.setTailgate(context, room) },
                valueRange = 0.5f..1.5f,
                colors = SliderDefaults.colors(
                    thumbColor = if (DashColors.Light) DashColors.Accent else Color.White,
                    activeTrackColor = DashColors.Accent,
                    inactiveTrackColor = DashColors.CardHi
                )
            )
        }

        SettingsSection(stringResource(R.string.reverse_lines_title))
        Text(stringResource(R.string.reverse_lines_hint), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
    }
}
