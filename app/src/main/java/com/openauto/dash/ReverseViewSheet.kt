package com.openauto.dash

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.ViewQuilt
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding

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
    val voiceQuiet by ReverseView.voiceQuiet.collectAsState()
    val denied = stringResource(R.string.reverse_picture_denied)
    val backDone = stringResource(R.string.reverse_back_done)
    val backLines = stringResource(R.string.reverse_back_lines)
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
        // Anything taken from the car's own camera screen: one way back to it, named as such (#111).
        if (on || ownCamera || hideStock) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Icon(Icons.Filled.Undo, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(10.dp))
                Text(
                    stringResource(R.string.reverse_back_detail),
                    color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                SheetButton(stringResource(R.string.reverse_back), primary = false) {
                    if (!busy) {
                        busy = true
                        scope.launch {
                            val lines = ReverseView.backToCar(context)
                            Toast.makeText(context, if (lines) backDone else backLines, Toast.LENGTH_LONG).show()
                            busy = false
                        }
                    }
                }
            }
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

        // The radar's look only matters where the radar shows.
        if (layout != ReverseLayout.CAMERA) {
            val radarLook by ReverseView.radarLook.collectAsState()
            SettingsSection(stringResource(R.string.reverse_radar_title))
            SegmentedSwitch(
                options = ReverseRadarLook.entries,
                chosen = radarLook,
                icon = {
                    when (it) {
                        ReverseRadarLook.GROUND -> Icons.Filled.Terrain
                        ReverseRadarLook.PLAIN -> Icons.Filled.DirectionsCar
                        ReverseRadarLook.ALERT -> Icons.Filled.Sensors
                    }
                },
                title = { stringResource(it.titleRes) },
                onChoose = { ReverseView.setRadarLook(context, it) }
            )
            SwitchHint(stringResource(radarLook.hintRes))
            CarPaintPicker()
        }

        // Dashwheel's own camera picture and hiding the car app's lines both run as root: not in the Play edition.
        if (Edition.full) {
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
        }

        // Only once a second screen is paired: nothing to choose before.
        val displays by DisplayLink.displays.collectAsState()
        if (displays.isNotEmpty()) {
            val secondScreen by ReverseView.secondScreen.collectAsState()
            val config by SecondScreenStore.config.collectAsState()
            SettingsSection(stringResource(R.string.reverse_second_title))
            SegmentedSwitch(
                options = listOf<ReverseLayout?>(null) + ReverseLayout.entries,
                chosen = secondScreen,
                icon = {
                    when (it) {
                        null -> Icons.Filled.Close
                        ReverseLayout.BOTH -> Icons.Filled.ViewQuilt
                        ReverseLayout.CAMERA -> Icons.Filled.Videocam
                        ReverseLayout.RADAR -> Icons.Filled.Sensors
                    }
                },
                title = { it?.let { l -> stringResource(l.titleRes) } ?: stringResource(R.string.reverse_second_off) },
                onChoose = { ReverseView.setSecondScreen(context, it) }
            )
            SwitchHint(
                stringResource(
                    when {
                        secondScreen != null && !config.video -> R.string.reverse_second_hint_video
                        (secondScreen == ReverseLayout.CAMERA || secondScreen == ReverseLayout.BOTH) && !(on && ownCamera) -> R.string.reverse_second_hint_rom
                        else -> R.string.reverse_second_hint
                    }
                )
            )
        }

        if (Edition.full) {
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
        }

        // The voice's silence in reverse is Dashwheel's doing, whether or not the view is on: said here, where a driver looks for it (#110).
        SettingsSection(stringResource(R.string.reverse_voice_title))
        SwitchRow(
            stringResource(R.string.reverse_voice_quiet),
            stringResource(if (voiceQuiet) R.string.reverse_voice_quiet_detail else R.string.reverse_voice_talk_detail),
            checked = voiceQuiet
        ) { ReverseView.setVoiceQuiet(context, it) }

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

/**
 * The drawn car's paint (CarPaint), as swatches: the theme's own grey first,
 * then dark to light and the colours. Here and in the parking radar pop-up.
 */
@Composable
private fun CarPaintPicker() {
    val context = LocalContext.current
    val chosen by CarPaintStore.paint.collectAsState()
    SettingsSection(stringResource(R.string.car_paint_title))
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        for (p in CarPaint.entries) {
            val selected = p == chosen
            val name = stringResource(p.titleRes)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(DashShape.Medium)
                    .clickable(role = Role.RadioButton) { CarPaintStore.set(context, p) }
                    .semantics { contentDescription = name; this.selected = selected }
                    .padding(6.dp)
            ) {
                val ring = if (selected) DashColors.Accent else DashColors.Line
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .border(if (selected) 3.dp else 1.dp, ring, CircleShape)
                        .padding(5.dp)
                        .clip(CircleShape)
                        .background(
                            p.color?.let { SolidColor(it) }
                                ?: Brush.linearGradient(listOf(DashColors.CardHi.compositeOver(DashColors.Card.copy(alpha = 1f)), DashColors.TextSecondary))
                        )
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    name, color = if (selected) DashColors.TextPrimary else DashColors.TextSecondary,
                    style = MaterialTheme.typography.labelMedium, maxLines = 1
                )
            }
        }
    }
    SwitchHint(stringResource(R.string.car_paint_hint))
}
