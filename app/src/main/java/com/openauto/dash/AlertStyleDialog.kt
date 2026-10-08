package com.openauto.dash

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.AirlineSeatReclineNormal
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.SensorDoor
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.TireRepair
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
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

/**
 * Every alert Dashwheel can show, one row each: its switch, and a tap for its
 * design, its voice and "Try it". One list, so a design picked for an alert
 * that is switched off can't go unnoticed. Calls on a unit without the
 * head unit's own Bluetooth app have no switch: the companion's calls always
 * show. Only the alerts this unit can have are listed ([RomPopups.available],
 * [RomPopups.canWork]).
 */
@Composable
internal fun AlertStyleRows() {
    val context = LocalContext.current
    var picking by remember { mutableStateOf<AlertKind?>(null) }
    val access = shellAccess()
    // The volume bar has its row under Driving, Sound (VolumeAlertRow), with the rest of the sound settings.
    val kinds = remember(access) {
        AlertKind.entries.filter { kind -> kind != AlertKind.VOLUME && offered(context, kind, access) }
    }
    // Alerts this unit could have once the car app's settings can be written.
    val held = remember(access) {
        AlertKind.entries.any { kind ->
            val rom = kind.romKind ?: return@any false
            kind != AlertKind.CALL && RomPopups.available(context, rom) && !RomPopups.canWork(rom, access)
        }
    }
    SettingsSection(stringResource(R.string.alert_style_section))
    kinds.forEach { kind -> AlertRow(kind) { picking = kind } }
    if (held && access != PrivilegedShell.Access.UNKNOWN) {
        // The Play edition can never write the car app's settings: one line says which edition can.
        if (Edition.play) Text(
            stringResource(R.string.alert_more_with_github),
            color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
        else GrantHint()
    }
    picking?.let { kind -> AlertStyleDialog(kind) { picking = null } }
}

/**
 * The alerts held back for want of the car settings permission: a plain line
 * pointing at the wiki, and the adb command itself only once asked for, copied
 * to the clipboard so it never has to be read off the car screen.
 */
@Composable
private fun GrantHint() {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.alert_more_with_permission), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = {
            context.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("adb", GRANT_COMMAND))
            copied = true
        }) {
            Text(stringResource(if (copied) R.string.alert_command_copied else R.string.alert_copy_command))
        }
        if (copied) {
            Text(GRANT_COMMAND, color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}

/** Whether [kind] can work on this unit, so its row is offered. */
private fun offered(context: Context, kind: AlertKind, access: PrivilegedShell.Access): Boolean {
    val rom = kind.romKind ?: return kind != AlertKind.GEMINI || GeminiLive.available(context)
    return kind == AlertKind.CALL || RomPopups.available(context, rom) && RomPopups.canWork(rom, access)
}

/** Settings, Driving, Sound: the volume bar's switch and design, with the other sound settings. */
@Composable
internal fun VolumeAlertRow() {
    val context = LocalContext.current
    val access = shellAccess()
    if (!remember(access) { offered(context, AlertKind.VOLUME, access) }) return
    var picking by remember { mutableStateOf(false) }
    AlertRow(AlertKind.VOLUME) { picking = true }
    if (picking) AlertStyleDialog(AlertKind.VOLUME) { picking = false }
}

/** One command from a PC that lets Dashwheel write the car app's settings without root. */
private const val GRANT_COMMAND = "adb shell pm grant com.openauto.dash android.permission.WRITE_SECURE_SETTINGS"

/** The switch that turns [this] alert on, in [RomPopups]; none for Gemini Live, opened by the driver. */
private val AlertKind.romKind: RomPopups.Kind? get() = if (this == AlertKind.GEMINI) null else RomPopups.Kind.valueOf(name)

/** Whether [kind] has a switch on this unit: all but Gemini Live, and the calls where only the companion brings them. */
@Composable
private fun switchable(kind: AlertKind): Boolean {
    val context = LocalContext.current
    return remember(kind) { kind.romKind != null && (kind != AlertKind.CALL || RomPopups.available(context, RomPopups.Kind.CALL)) }
}

/**
 * One alert: its name, what it does now (its design and voice, what it does
 * when off, or why it can't work yet) and its switch, a target of its own.
 */
@Composable
private fun AlertRow(kind: AlertKind, onOpen: () -> Unit) {
    val context = LocalContext.current
    val styles by AlertStyleStore.styles.collectAsState()
    val spoken by AlertStyleStore.spoken.collectAsState()
    val replaced by RomPopups.replaced.collectAsState()
    val failed by RomPopups.failed.collectAsState()
    val a11y by SplitAccessibilityService.connected.collectAsState()
    val volumeHidden by VolumeRomHide.active.collectAsState()
    val hasSwitch = switchable(kind)
    val on = !hasSwitch || kind.romKind in replaced
    val style = stringResource(styles.of(kind).title)
    val detail = when {
        on && kind == AlertKind.VOLUME && kind.romKind in failed -> stringResource(R.string.settings_rom_volume_needs_magisk)
        on && kind.romKind in failed -> stringResource(R.string.settings_rom_needs_root)
        on && kind == AlertKind.VOLUME && !volumeHidden -> stringResource(R.string.settings_rom_volume_next_start)
        on && kind == AlertKind.RADAR && !a11y -> stringResource(R.string.settings_rom_radar_needs_access)
        on && kind.speakable && kind in spoken -> stringResource(R.string.alert_with_voice, style)
        on -> style
        else -> stringResource(kind.offDetail)
    }
    val tap = rememberTapFeedback()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = DashSize.Bar)
            .clip(DashShape.Medium)
            .clickable { tap(); onOpen() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(kind.icon, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(kind.label), color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
            Text(detail, color = DashColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(12.dp))
        if (hasSwitch) {
            AlertSwitch(on) { tap(); kind.romKind?.let { rom -> RomPopups.setReplaced(context, rom, it) } }
        } else {
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = DashColors.Muted)
        }
    }
    HorizontalDivider(color = DashColors.Line, modifier = Modifier.padding(horizontal = 12.dp))
}

@Composable
private fun AlertSwitch(on: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = on,
        onCheckedChange = onChange,
        // A thumb's width around the switch, so it is hit rather than the row.
        modifier = Modifier.sizeIn(minWidth = DashSize.TouchPrimary, minHeight = DashSize.TouchPrimary),
        colors = SwitchDefaults.colors(
            checkedThumbColor = DashColors.OnAccent,
            checkedTrackColor = DashColors.Accent,
            uncheckedThumbColor = DashColors.TextSecondary,
            uncheckedTrackColor = DashColors.CardHi,
            uncheckedBorderColor = DashColors.Line
        )
    )
}

/** What each alert does, said on its row while it is off. */
private val AlertKind.offDetail: Int
    get() = when (this) {
        AlertKind.CALL -> R.string.settings_rom_call_detail
        AlertKind.DOORS -> R.string.settings_rom_doors_detail
        AlertKind.RADAR -> R.string.settings_rom_radar_detail
        AlertKind.AC -> R.string.settings_rom_ac_detail
        AlertKind.TYRES -> R.string.settings_rom_tyres_detail
        AlertKind.BELT -> R.string.settings_rom_belt_detail
        AlertKind.GEMINI -> R.string.alert_gemini_detail
        AlertKind.VOLUME -> R.string.settings_rom_volume_detail
    }

private val AlertKind.icon: ImageVector
    get() = when (this) {
        AlertKind.CALL -> Icons.Filled.Call
        AlertKind.DOORS -> Icons.Filled.SensorDoor
        AlertKind.RADAR -> Icons.Filled.Sensors
        AlertKind.AC -> Icons.Filled.AcUnit
        AlertKind.TYRES -> Icons.Filled.TireRepair
        AlertKind.BELT -> Icons.Filled.AirlineSeatReclineNormal
        AlertKind.GEMINI -> Icons.Filled.AutoAwesome
        AlertKind.VOLUME -> Icons.Filled.VolumeUp
    }

private val AlertKind.label: Int
    get() = when (this) {
        AlertKind.CALL -> R.string.alert_kind_call
        AlertKind.DOORS -> R.string.alert_kind_doors
        AlertKind.RADAR -> R.string.alert_kind_radar
        AlertKind.AC -> R.string.alert_kind_ac
        AlertKind.TYRES -> R.string.alert_kind_tyres
        AlertKind.BELT -> R.string.alert_kind_belt
        AlertKind.GEMINI -> R.string.ai_gemini_live
        AlertKind.VOLUME -> R.string.alert_kind_volume
    }

private val AlertKind.dialogTitle: Int
    get() = when (this) {
        AlertKind.CALL -> R.string.alert_kind_call_title
        AlertKind.DOORS -> R.string.alert_kind_doors_title
        AlertKind.RADAR -> R.string.alert_kind_radar_title
        AlertKind.AC -> R.string.alert_kind_ac_title
        AlertKind.TYRES -> R.string.alert_kind_tyres_title
        AlertKind.BELT -> R.string.alert_kind_belt_title
        AlertKind.GEMINI -> R.string.alert_kind_gemini_title
        AlertKind.VOLUME -> R.string.alert_kind_volume_title
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
                // Its switch here too: a design is chosen for an alert that shows.
                if (switchable(kind)) {
                    val replaced by RomPopups.replaced.collectAsState()
                    SettingsToggle(
                        kind.icon, stringResource(R.string.alert_show), stringResource(kind.offDetail), kind.romKind in replaced
                    ) { on -> kind.romKind?.let { RomPopups.setReplaced(context, it, on) } }
                    Spacer(Modifier.height(12.dp))
                }
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
                if (kind == AlertKind.GEMINI) {
                    // Which Gemini answers: Dashwheel's own, that knows the car, or Google's app.
                    var ai by remember { mutableStateOf(AiSettings.load(context)) }
                    SettingsToggle(
                        Icons.Filled.AutoAwesome, stringResource(R.string.ai_live_own), stringResource(R.string.ai_live_own_detail), ai.ownLive
                    ) {
                        ai = ai.copy(ownLive = it)
                        AiSettings.save(context, ai)
                    }
                }
                if (kind.speakable) {
                    // Its voice is here with its design, not in the "What the car says" list: said so.
                    Label(stringResource(R.string.alert_also_spoken))
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
    val tile = DashColors.CardHi.compositeOver(DashColors.Card.copy(alpha = 1f))
    val alert = kind.tint()
    val frame = DashColors.Line
    val square = alertSquare
    Canvas(modifier.clip(DashShape.Small)) {
        val w = size.width
        val h = size.height
        // The look's corners: square plates in the hard-edged looks (AlertChrome.kt).
        val r = CornerRadius(h * if (square) 0.015f else 0.04f)
        drawRect(screen)
        // The dashboard behind: three tiles.
        val gap = w * 0.03f
        val tw = (w - gap * 4) / 3f
        repeat(3) { i -> drawRoundRect(tile, Offset(gap + i * (tw + gap), h * 0.12f), Size(tw, h * 0.76f), r) }
        when (style) {
            AlertStyle.PILL -> drawRoundRect(alert, Offset(w * 0.36f, h * 0.05f), Size(w * 0.28f, h * 0.12f), if (square) r else CornerRadius(h))
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
            AlertStyle.BUBBLE -> {
                drawCircle(alert, radius = h * 0.14f, center = Offset(w * 0.86f, h * 0.2f))
                drawCircle(Color.White.copy(alpha = 0.7f), radius = h * 0.05f, center = Offset(w * 0.86f, h * 0.2f))
            }
            AlertStyle.ICON -> {
                drawCircle(alert.copy(alpha = 0.3f), radius = h * 0.1f, center = Offset(w * 0.88f, h * 0.16f))
                drawCircle(alert, radius = h * 0.05f, center = Offset(w * 0.88f, h * 0.16f))
            }
        }
        drawRoundRect(frame, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f), cornerRadius = r)
    }
}
