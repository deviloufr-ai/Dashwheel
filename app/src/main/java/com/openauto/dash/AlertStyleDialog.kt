package com.openauto.dash

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.AirlineSeatReclineNormal
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.SensorDoor
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.TireRepair
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/*
 * Settings, Look, Alerts: the design of each of Dashwheel's alerts. One row
 * per alert with its design; the picker shows each design as a small screen
 * with the alert where it would be, and "Try it" puts a made-up one on the
 * real screen.
 */

@Composable
internal fun AlertStyleRows() {
    val context = LocalContext.current
    val styles by AlertStyleStore.styles.collectAsState()
    val spoken by AlertStyleStore.spoken.collectAsState()
    var picking by remember { mutableStateOf<AlertKind?>(null) }
    // The car's own alerts only on units whose car app Dashwheel can stand in for.
    val carApp = remember { RomPopups.available(context, RomPopups.Kind.DOORS) }

    SettingsSection(stringResource(R.string.alert_section))
    val access = shellAccess()
    AlertKind.entries.filter { kind ->
        when (kind) {
            AlertKind.CALL -> true
            AlertKind.DOORS -> carApp && RomPopups.canWork(RomPopups.Kind.DOORS, access)
            AlertKind.RADAR -> carApp && RomPopups.canWork(RomPopups.Kind.RADAR, access)
            AlertKind.AC -> carApp
            AlertKind.TYRES -> Tyres.available
            AlertKind.BELT -> carApp && RomPopups.canWork(RomPopups.Kind.BELT, access)
        }
    }.forEach { kind ->
        val style = stringResource(styles.of(kind).title)
        val detail = if (kind.speakable && kind in spoken) stringResource(R.string.alert_with_voice, style) else style
        SettingsRow(kind.icon, stringResource(kind.label), detail) { picking = kind }
    }
    picking?.let { kind -> AlertStyleDialog(kind) { picking = null } }
}

private val AlertKind.icon: ImageVector
    get() = when (this) {
        AlertKind.CALL -> Icons.Filled.Call
        AlertKind.DOORS -> Icons.Filled.SensorDoor
        AlertKind.RADAR -> Icons.Filled.Sensors
        AlertKind.AC -> Icons.Filled.AcUnit
        AlertKind.TYRES -> Icons.Filled.TireRepair
        AlertKind.BELT -> Icons.Filled.AirlineSeatReclineNormal
    }

private val AlertKind.label: Int
    get() = when (this) {
        AlertKind.CALL -> R.string.alert_kind_call
        AlertKind.DOORS -> R.string.alert_kind_doors
        AlertKind.RADAR -> R.string.alert_kind_radar
        AlertKind.AC -> R.string.alert_kind_ac
        AlertKind.TYRES -> R.string.alert_kind_tyres
        AlertKind.BELT -> R.string.alert_kind_belt
    }

private val AlertKind.dialogTitle: Int
    get() = when (this) {
        AlertKind.CALL -> R.string.alert_kind_call_title
        AlertKind.DOORS -> R.string.alert_kind_doors_title
        AlertKind.RADAR -> R.string.alert_kind_radar_title
        AlertKind.AC -> R.string.alert_kind_ac_title
        AlertKind.TYRES -> R.string.alert_kind_tyres_title
        AlertKind.BELT -> R.string.alert_kind_belt_title
    }

/** The alert's colour in the picker's small screens. */
@Composable
private fun AlertKind.tint(): Color = when (this) {
    AlertKind.DOORS, AlertKind.RADAR -> DashColors.Warning
    AlertKind.TYRES, AlertKind.BELT -> DashColors.Critical
    else -> DashColors.Accent
}

@Composable
private fun AlertStyleDialog(kind: AlertKind, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val styles by AlertStyleStore.styles.collectAsState()
    val spoken by AlertStyleStore.spoken.collectAsState()
    val chosen = styles.of(kind)
    // A preview still up when the picker closes goes with it.
    DisposableEffect(Unit) { onDispose { AlertPreview.stop() } }

    AlertDialog(
        modifier = Modifier.widthIn(max = 760.dp).keepClearOfWindows(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onDismiss,
        containerColor = DashColors.Card,
        title = {
            Text(stringResource(kind.dialogTitle), color = DashColors.TextPrimary)
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                kind.styles.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                        row.forEach { style ->
                            StyleOption(kind, style, style == chosen, Modifier.weight(1f)) {
                                AlertStyleStore.set(context, kind, style)
                                AlertPreview.show(context, kind)
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                if (kind == AlertKind.CALL) {
                    Text(stringResource(R.string.alert_call_note), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                }
                if (kind.speakable) {
                    SettingsToggle(
                        Icons.Filled.RecordVoiceOver, stringResource(R.string.alert_speak),
                        stringResource(
                            when (kind) {
                                AlertKind.CALL -> R.string.alert_speak_call_detail
                                AlertKind.TYRES -> R.string.alert_speak_tyres_detail
                                AlertKind.BELT -> R.string.alert_speak_belt_detail
                                else -> R.string.alert_speak_doors_detail
                            }
                        ),
                        kind in spoken
                    ) { AlertStyleStore.setSpoken(context, kind, it) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { AlertPreview.show(context, kind) }) {
                Text(stringResource(R.string.alert_try), color = DashColors.Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dash_close), color = DashColors.Muted) }
        }
    )
}

@Composable
private fun StyleOption(kind: AlertKind, style: AlertStyle, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    val shape = DashShape.Medium
    Column(
        modifier = modifier
            .clip(shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) DashColors.Accent else DashColors.Line, shape)
            .background(DashColors.CardHi.copy(alpha = DashColors.CardHi.alpha * if (selected) 0.65f else 0.3f))
            .clickable(role = Role.RadioButton) { tap(); onClick() }
            .padding(10.dp)
    ) {
        StyleThumbnail(kind, style, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(style.title), color = if (selected) DashColors.Accent else DashColors.TextPrimary,
            fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge
        )
        Text(
            stringResource(style.detail), color = DashColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/** A small screen: a few tiles, and the alert drawn where the design puts it. */
@Composable
private fun StyleThumbnail(kind: AlertKind, style: AlertStyle, modifier: Modifier) {
    val screen = DashColors.Background.copy(alpha = 1f)
    val tile = DashColors.CardHi.copy(alpha = 1f)
    val alert = kind.tint()
    val frame = DashColors.Line
    Canvas(modifier.clip(DashShape.Small)) {
        val w = size.width
        val h = size.height
        val r = CornerRadius(h * 0.04f)
        drawRect(screen)
        // The dashboard behind: three tiles.
        val gap = w * 0.03f
        val tw = (w - gap * 4) / 3f
        repeat(3) { i -> drawRoundRect(tile, Offset(gap + i * (tw + gap), h * 0.12f), Size(tw, h * 0.76f), r) }
        when (style) {
            AlertStyle.PILL -> drawRoundRect(alert, Offset(w * 0.36f, h * 0.05f), Size(w * 0.28f, h * 0.12f), CornerRadius(h))
            AlertStyle.CARD -> when (kind.cardAt) {
                CardAt.TOP -> drawRoundRect(alert, Offset(w * 0.3f, h * 0.07f), Size(w * 0.4f, h * 0.22f), r)
                CardAt.TOP_END -> drawRoundRect(alert, Offset(w * 0.56f, h * 0.07f), Size(w * 0.4f, h * 0.22f), r)
                CardAt.END -> drawRoundRect(alert, Offset(w * 0.8f, h * 0.2f), Size(w * 0.16f, h * 0.6f), r)
                CardAt.BOTTOM -> drawRoundRect(alert, Offset(w * 0.3f, h * 0.71f), Size(w * 0.4f, h * 0.22f), r)
            }
            AlertStyle.BANNER -> drawRoundRect(alert, Offset(w * 0.02f, h * 0.03f), Size(w * 0.96f, h * 0.16f), r)
            AlertStyle.PANEL -> {
                drawRect(alert.copy(alpha = 0.9f), Offset(w * 0.64f, 0f), Size(w * 0.36f, h))
                drawRoundRect(Color.White.copy(alpha = 0.55f), Offset(w * 0.77f, h * 0.22f), Size(w * 0.1f, h * 0.5f), r)
            }
            AlertStyle.FULL -> {
                drawRect(screen.copy(alpha = 0.85f))
                drawRoundRect(alert, Offset(w * 0.3f, h * 0.25f), Size(w * 0.4f, h * 0.5f), r)
            }
        }
        drawRoundRect(frame, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f), cornerRadius = r)
    }
}
