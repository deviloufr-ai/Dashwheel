package com.openauto.dash

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.AirlineSeatReclineNormal
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.SensorDoor
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.TireRepair
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em

/*
 * The Settings screen: everything set once, full screen in two columns like
 * a car's own settings. Categories on the left, the chosen one's settings on
 * the right, most of them right there (theme, appearance, language, driving,
 * the paired phone, demo mode) and the deep ones (car profile, AI, upkeep, readings, boot logo)
 * one tap away in their own sheet. Replaces the dialogs that used to stack four deep.
 */

internal enum class SettingsTab(@StringRes val titleRes: Int, val icon: ImageVector) {
    CAR(R.string.settings_section_car, Icons.Filled.DirectionsCar),
    LOOK(R.string.settings_section_look, Icons.Filled.Palette),
    DRIVING(R.string.settings_section_driving, Icons.Filled.Speed),
    PHONE(R.string.settings_section_phone, Icons.Filled.PhoneAndroid),
    ADVANCED(R.string.settings_section_advanced, Icons.Filled.Tune)
}

/** The theme choice and its setters, owned by the dashboard root. */
internal data class ThemeState(
    val mode: DashThemeMode,
    val appearance: DashAppearance,
    val effects: DashEffects,
    val onMode: (DashThemeMode) -> Unit,
    val onAppearance: (DashAppearance) -> Unit,
    val onEffects: (DashEffects) -> Unit,
    /** The bottom bar hides itself when unused, after [barHideSeconds] (BarAutoHide.kt). */
    val barAutoHide: Boolean,
    val barHideSeconds: Int,
    val onBarAutoHide: (Boolean) -> Unit,
    val onBarHideSeconds: (Int) -> Unit
)

@Composable
internal fun SettingsScreen(
    m: TopBarModel,
    theme: ThemeState,
    initialTab: SettingsTab,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var tab by remember(initialTab) { mutableStateOf(initialTab) }
    var carSettings by remember { mutableStateOf(false) }
    var aiSettings by remember { mutableStateOf(false) }
    var upkeep by remember { mutableStateOf(false) }
    var explorer by remember { mutableStateOf(false) }
    var bootLogo by remember { mutableStateOf(false) }
    var wheelButtons by remember { mutableStateOf(false) }
    val tap = rememberTapFeedback()

    SolidCard(modifier = modifier) {
        Row(modifier = Modifier.fillMaxSize()) {
            // Left: close, title, the categories.
            Column(
                modifier = Modifier
                    .width(260.dp)
                    .fillMaxHeight()
                    .background(DashColors.CardHi.copy(alpha = DashColors.CardHi.alpha * 0.4f))
                    .padding(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(DashShape.Medium)
                            .background(DashColors.CardHi)
                            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.dash_close)) { tap(); onClose() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.dash_close), tint = DashColors.TextPrimary, modifier = Modifier.size(26.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(
                        stringResource(R.string.settings_title),
                        color = DashColors.TextPrimary,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                }
                Spacer(Modifier.height(16.dp))
                SettingsTab.entries.forEach { t ->
                    val chosen = t == tab
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clip(DashShape.Medium)
                            .then(if (chosen) Modifier.background(DashColors.AccentBrush) else Modifier)
                            .clickable(role = Role.Tab) { tab = t }
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val ink = if (chosen) DashColors.OnAccent else DashColors.TextPrimary
                        Icon(t.icon, contentDescription = null, tint = ink, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(14.dp))
                        Text(stringResource(t.titleRes), color = ink, fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }

            // Right: the chosen category's settings.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                when (tab) {
                    SettingsTab.CAR -> CarPane(
                        m,
                        onCar = { carSettings = true },
                        onAi = { aiSettings = true },
                        onUpkeep = { upkeep = true },
                        onExplorer = { explorer = true }
                    )
                    SettingsTab.LOOK -> LookPane(theme)
                    SettingsTab.DRIVING -> DrivingPane(m, onWheelButtons = { wheelButtons = true })
                    SettingsTab.PHONE -> {
                        PhonePane()
                        RomPopupToggle(RomPopups.Kind.CALL)
                    }
                    SettingsTab.ADVANCED -> AdvancedPane(m, onBootLogo = { bootLogo = true }, onClose = onClose)
                }
            }
        }
    }

    if (carSettings) CarSettingsDialog(onDismiss = { carSettings = false })
    if (aiSettings) AiSettingsDialog(onDismiss = { aiSettings = false })
    if (upkeep) UpkeepDialog(onDismiss = { upkeep = false })
    if (explorer) PidExplorerDialog(onDismiss = { explorer = false })
    if (bootLogo) BootLogoDialog(onDismiss = { bootLogo = false })
    if (wheelButtons) SteeringWheelDialog(onDismiss = { wheelButtons = false })
}

@Composable
private fun CarPane(m: TopBarModel, onCar: () -> Unit, onAi: () -> Unit, onUpkeep: () -> Unit, onExplorer: () -> Unit) {
    val car by CarProfileStore.profile.collectAsState()
    SettingsSection(stringResource(R.string.settings_section_car))
    SettingsRow(Icons.Filled.DirectionsCar, stringResource(R.string.car_menu), car.name, onCar)
    SpeedCorrectionRow()
    SettingsRow(Icons.Filled.AutoAwesome, stringResource(R.string.ai_title), stringResource(R.string.settings_ai_detail), onAi)
    SettingsRow(Icons.Filled.Handyman, stringResource(R.string.upkeep_dialog_title), stringResource(R.string.upkeep_settings_detail), onUpkeep)
    SettingsRow(Icons.Filled.Science, stringResource(R.string.explore_title), stringResource(R.string.explore_settings_detail), onExplorer)
    RomPopupToggle(RomPopups.Kind.DOORS)
    RomPopupToggle(RomPopups.Kind.RADAR)
    RomPopupToggle(RomPopups.Kind.AC)
    RomPopupToggle(RomPopups.Kind.TYRES)
    RomPopupToggle(RomPopups.Kind.BELT)
}

/**
 * One of the head unit's own pop-ups replaced by Dashwheel's ([RomPopups]),
 * shown only on firmware that has it.
 */
@Composable
private fun RomPopupToggle(kind: RomPopups.Kind) {
    val context = LocalContext.current
    if (!remember(kind) { RomPopups.available(context, kind) }) return
    // Offered only where it can work (PrivilegedShell): the door alert reads
    // the CANbox through root, the radar switch is written through a shell.
    if (!RomPopups.canWork(kind, shellAccess())) return
    val replaced by RomPopups.replaced.collectAsState()
    val failed by RomPopups.failed.collectAsState()
    val on = kind in replaced
    val (icon, title, detail) = when (kind) {
        RomPopups.Kind.CALL -> Triple(Icons.Filled.Call, stringResource(R.string.settings_rom_call), stringResource(R.string.settings_rom_call_detail))
        RomPopups.Kind.DOORS -> Triple(Icons.Filled.SensorDoor, stringResource(R.string.settings_rom_doors), stringResource(R.string.settings_rom_doors_detail))
        RomPopups.Kind.RADAR -> Triple(Icons.Filled.Sensors, stringResource(R.string.settings_rom_radar), stringResource(R.string.settings_rom_radar_detail))
        RomPopups.Kind.AC -> Triple(Icons.Filled.AcUnit, stringResource(R.string.settings_rom_ac), stringResource(R.string.settings_rom_ac_detail))
        RomPopups.Kind.TYRES -> Triple(Icons.Filled.TireRepair, stringResource(R.string.settings_rom_tyres), stringResource(R.string.settings_rom_tyres_detail))
        RomPopups.Kind.BELT -> Triple(Icons.Filled.AirlineSeatReclineNormal, stringResource(R.string.settings_rom_belt), stringResource(R.string.settings_rom_belt_detail))
    }
    val a11y by SplitAccessibilityService.connected.collectAsState()
    val shown = when {
        on && kind in failed -> stringResource(R.string.settings_rom_needs_root)
        on && kind == RomPopups.Kind.RADAR && !a11y -> stringResource(R.string.settings_rom_radar_needs_access)
        else -> detail
    }
    SettingsToggle(icon, title, shown, on) {
        RomPopups.setReplaced(context, kind, it)
    }
}

@Composable
private fun LookPane(theme: ThemeState) {
    ThemePane(theme)
    Spacer(Modifier.height(20.dp))
    AlertStyleRows()
    Spacer(Modifier.height(20.dp))
    SettingsSection(stringResource(R.string.language_title))
    LanguageChoices()
}

@Composable
private fun DrivingPane(m: TopBarModel, onWheelButtons: () -> Unit) {
    val context = LocalContext.current
    val wheelMappings by SteeringWheelStore.mappings.collectAsState()
    SettingsSection(stringResource(R.string.settings_section_driving))
    SettingsToggle(
        Icons.Filled.DirectionsCar, stringResource(R.string.settings_drive_lock),
        stringResource(R.string.settings_drive_lock_detail), m.lockWhileMoving, m.onLockWhileMoving
    )
    SettingsToggle(
        Icons.Filled.VolumeUp, stringResource(R.string.settings_tap_sound),
        stringResource(R.string.settings_tap_sound_detail), FeedbackStore.sound
    ) { FeedbackStore.save(context, it) }
    SettingsRow(
        Icons.Filled.SettingsRemote, stringResource(R.string.wheel_title),
        if (wheelMappings.isEmpty()) stringResource(R.string.wheel_settings_detail_empty)
        else pluralStringResource(R.plurals.wheel_settings_detail_count, wheelMappings.size, wheelMappings.size),
        onWheelButtons
    )
    VolumeWaySetting()
    SpeedVolumeSetting()
    KeyTargetRows()
}

/**
 * How the volume is changed (see [MediaVolume]): for units whose sound
 * ignores Android's volume. The keys are pressed through the privileged
 * shell, so that way is only offered where there is one ([PrivilegedShell]).
 */
@Composable
private fun VolumeWaySetting() {
    val context = LocalContext.current
    val saved by MediaVolume.way.collectAsState()
    val shell = shellAccess().shell
    val options = if (shell) VolumeWay.entries else VolumeWay.entries.filter { it != VolumeWay.KEYS }
    val way = if (saved in options) saved else VolumeWay.AUTO
    Spacer(Modifier.height(12.dp))
    Text(
        stringResource(R.string.volume_way_title),
        color = DashColors.TextPrimary,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(start = 12.dp, bottom = 8.dp)
    )
    SegmentedSwitch(
        options = options,
        chosen = way,
        icon = { option ->
            when (option) {
                VolumeWay.AUTO -> Icons.Filled.AutoAwesome
                VolumeWay.ANDROID -> Icons.Filled.Android
                VolumeWay.KEYS -> Icons.Filled.Adjust
            }
        },
        title = { stringResource(it.titleRes) },
        onChoose = { MediaVolume.saveWay(context, it) }
    )
    SwitchHint(stringResource(way.hintRes))
}

/** Volume follows speed (see [SpeedVolume]): off or one of three strengths. */
@Composable
private fun SpeedVolumeSetting() {
    val context = LocalContext.current
    val level by SpeedVolume.level.collectAsState()
    Spacer(Modifier.height(12.dp))
    Text(
        stringResource(R.string.speed_volume_title),
        color = DashColors.TextPrimary,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(start = 12.dp)
    )
    Text(
        stringResource(R.string.speed_volume_detail),
        color = DashColors.TextSecondary,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(start = 12.dp, bottom = 8.dp)
    )
    SegmentedSwitch(
        options = SpeedVolumeLevel.entries,
        chosen = level,
        icon = { option ->
            when (option) {
                SpeedVolumeLevel.OFF -> Icons.Filled.VolumeOff
                SpeedVolumeLevel.LOW -> Icons.Filled.VolumeMute
                SpeedVolumeLevel.MEDIUM -> Icons.Filled.VolumeDown
                SpeedVolumeLevel.HIGH -> Icons.Filled.VolumeUp
            }
        },
        title = { stringResource(it.titleRes) },
        onChoose = { SpeedVolume.save(context, it) }
    )
    SwitchHint(stringResource(level.hintRes))
}

@Composable
private fun AdvancedPane(m: TopBarModel, onBootLogo: () -> Unit, onClose: () -> Unit) {
    SettingsSection(stringResource(R.string.settings_section_advanced))
    SettingsToggle(
        Icons.Filled.PlayCircle, stringResource(R.string.demo_menu_start),
        stringResource(R.string.demo_settings_detail), m.demo
    ) { on ->
        m.onDemo()
        // Straight to the dashboard it fills; the badge there stops it.
        if (on) onClose()
    }
    // The boot logo and the system-app install write to /system: only with a
    // privileged shell (root or the unit's ADB, see PrivilegedShell) can they
    // do anything, so without one they are not offered.
    val shell = shellAccess().shell
    // Only on the QF001 / K706 firmware the feature was built for.
    if (shell && BootLogoSupport.available) {
        SettingsRow(Icons.Filled.PowerSettingsNew, stringResource(R.string.boot_menu), null, onBootLogo)
    }
    if (shell) {
        SettingsRow(Icons.Filled.Build, stringResource(R.string.dash_system_app_title), stringResource(R.string.settings_system_detail), m.onSystem)
    }
    SettingsRow(Icons.Filled.Checklist, stringResource(R.string.setup_again), stringResource(R.string.setup_again_detail)) { m.onSetup(true) }
    UpdateRow(m)
}

/**
 * The updater's one row: what it knows (checking, up to date, a newer build,
 * downloading, downloaded) and the one action that fits. The only place an
 * update the driver put off can still be had.
 */
@Composable
private fun UpdateRow(m: TopBarModel) {
    val status = m.update
    val info = status.updateInfo
    val title = when {
        info != null -> stringResource(R.string.dash_update_to, info.versionName)
        else -> stringResource(R.string.dash_menu_check_updates)
    }
    val detail = when (status) {
        is UpdateStatus.Checking -> stringResource(R.string.dash_update_checking)
        is UpdateStatus.UpToDate -> stringResource(R.string.dash_update_up_to_date, m.versionName)
        is UpdateStatus.Downloading -> stringResource(R.string.dash_update_downloading, status.percent)
        is UpdateStatus.Ready -> stringResource(R.string.dash_update_ready_detail)
        is UpdateStatus.Installing -> stringResource(R.string.dash_update_installing)
        is UpdateStatus.Error -> stringResource(status.messageRes)
        is UpdateStatus.Available, is UpdateStatus.Dismissed -> stringResource(R.string.dash_update_available_detail, m.versionName)
        UpdateStatus.Idle -> stringResource(R.string.settings_version, m.versionName)
    }
    SettingsRow(Icons.Filled.SystemUpdate, title, detail) {
        if (info != null && status !is UpdateStatus.Downloading && status !is UpdateStatus.Installing) m.onUpdate() else m.onCheckUpdates()
    }
}

@Composable
internal fun SettingsSection(title: String) {
    Text(
        title.uppercase(),
        color = DashColors.Accent,
        letterSpacing = 0.08.em,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 6.dp)
    )
}

/** One setting: icon, name, what it is right now or what it does, and a chevron. */
@Composable
internal fun SettingsRow(icon: ImageVector, title: String, detail: String?, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(DashShape.Medium)
            .clickable { tap(); onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) {
                Text(
                    detail,
                    color = DashColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = DashColors.Muted)
    }
    HorizontalDivider(color = DashColors.Line, modifier = Modifier.padding(horizontal = 12.dp))
}

/**
 * The speed correction (see [SpeedCorrection]): − and + by 1 km/h, the value
 * between, applied at once so the speed on screen can be matched to the car's
 * speedometer while driving along. Also in the telemetry tile's dialog.
 */
@Composable
internal fun SpeedCorrectionRow() {
    val context = LocalContext.current
    val offset by SpeedCorrection.offsetKmh.collectAsState()
    val tap = rememberTapFeedback()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Speed, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.vehicle_speed_fix), color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.vehicle_speed_fix_detail),
                color = DashColors.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = { tap(); SpeedCorrection.save(context, offset - 1) },
            enabled = offset > -SpeedCorrection.MAX_OFFSET_KMH,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.vehicle_speed_fix_less), tint = DashColors.TextPrimary)
        }
        Text(
            speedOffsetText(offset),
            color = if (offset == 0) DashColors.TextSecondary else DashColors.Accent,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.width(88.dp)
        )
        IconButton(
            onClick = { tap(); SpeedCorrection.save(context, offset + 1) },
            enabled = offset < SpeedCorrection.MAX_OFFSET_KMH,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.vehicle_speed_fix_more), tint = DashColors.TextPrimary)
        }
    }
    HorizontalDivider(color = DashColors.Line, modifier = Modifier.padding(horizontal = 12.dp))
}

/** "+3 km/h", "−2 km/h", or "0 km/h" when there's no correction. */
internal fun speedOffsetText(offsetKmh: Int): String = when {
    offsetKmh > 0 -> "+$offsetKmh km/h"
    offsetKmh < 0 -> "\u2212${-offsetKmh} km/h"
    else -> "0 km/h"
}

/** One on/off setting: icon, name, what it does, and a switch; the whole row toggles it. */
@Composable
internal fun SettingsToggle(icon: ImageVector, title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val tap = rememberTapFeedback()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(DashShape.Medium)
            .clickable(role = Role.Switch) { tap(); onChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
            Text(detail, color = DashColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = DashColors.OnAccent,
                checkedTrackColor = DashColors.Accent,
                uncheckedThumbColor = DashColors.TextSecondary,
                uncheckedTrackColor = DashColors.CardHi,
                uncheckedBorderColor = DashColors.Line
            )
        )
    }
    HorizontalDivider(color = DashColors.Line, modifier = Modifier.padding(horizontal = 12.dp))
}
