package com.openauto.dash

import androidx.annotation.StringRes
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.filled.VerifiedUser
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SensorDoor
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.StayCurrentLandscape
import androidx.compose.material.icons.filled.StayCurrentPortrait
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.TireRepair
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.delay

/*
 * The Settings screen: everything set once, full screen in two columns like
 * a car's own settings. Categories on the left (along the top on an upright
 * screen), the chosen one's settings on the right, most of them right there
 * and the deep ones (car profile, AI, servicing, readings, wheel buttons,
 * language) one tap further in the same pane, with a back arrow
 * (SettingsSheet.kt). One subject, one place: every alert under Alerts, what
 * the screen does under Display, what it looks like under Look.
 */

internal enum class SettingsTab(@StringRes val titleRes: Int, val icon: ImageVector) {
    CAR(R.string.settings_section_car, Icons.Filled.DirectionsCar),
    LOOK(R.string.settings_section_look, Icons.Filled.Palette),
    DISPLAY(R.string.settings_section_display, Icons.Filled.Tv),
    ALERTS(R.string.alert_section, Icons.Filled.NotificationsActive),
    DRIVING(R.string.settings_section_driving, Icons.Filled.Speed),
    PHONE(R.string.settings_section_phone, Icons.Filled.PhoneAndroid),
    ADVANCED(R.string.settings_section_advanced, Icons.Filled.Tune)
}

/** The settings that open further into the pane. */
private enum class Deep { CAR, AI, UPKEEP, EXPLORER, WHEEL, LANGUAGE }

/** Under this width the categories go along the top: a rail would leave the settings half a screen. */
private val NARROW_SETTINGS = 800.dp

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
    /** Opens the OBD adapter picker (with its Bluetooth permission). */
    onPickObd: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var tab by remember(initialTab) { mutableStateOf(initialTab) }
    var deep by remember { mutableStateOf<Deep?>(null) }
    var bootLogo by remember { mutableStateOf(false) }
    // Another category, or closing: the open sheet leaves first, as by its back arrow.
    val choose: (SettingsTab) -> Unit = { t ->
        OpenSheet.dismiss()
        deep = null
        tab = t
    }
    val close = {
        OpenSheet.dismiss()
        onClose()
    }
    val back = { deep = null }

    val pane: @Composable (Modifier) -> Unit = { paneModifier ->
        Box(modifier = paneModifier) {
            CompositionLocalProvider(LocalSheetInPane provides true) {
                when (deep) {
                    Deep.CAR -> CarSettingsDialog(onDismiss = back)
                    Deep.AI -> AiSettingsDialog(onDismiss = back)
                    Deep.UPKEEP -> UpkeepDialog(onDismiss = back)
                    Deep.EXPLORER -> PidExplorerDialog(onDismiss = back)
                    Deep.WHEEL -> SteeringWheelDialog(onDismiss = back)
                    Deep.LANGUAGE -> LanguageSheet(onDismiss = back)
                    null -> Unit
                }
            }
            // Each category keeps its own place: a long one scrolled down does not move the next.
            if (deep == null) key(tab) {
                val scroll = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll)
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                ) {
                    when (tab) {
                        SettingsTab.CAR -> CarPane(open = { deep = it })
                        SettingsTab.LOOK -> LookPane(theme)
                        SettingsTab.DISPLAY -> DisplayPane(theme, onLanguage = { deep = Deep.LANGUAGE })
                        SettingsTab.ALERTS -> AlertsPane()
                        SettingsTab.DRIVING -> DrivingPane(m, onWheelButtons = { deep = Deep.WHEEL })
                        SettingsTab.PHONE -> PhonePane()
                        SettingsTab.ADVANCED -> AdvancedPane(m, onBootLogo = { bootLogo = true }, onPickObd = onPickObd, onClose = onClose)
                    }
                }
                MoreBelow(scroll, Modifier.align(Alignment.BottomCenter))
            }
        }
    }

    SolidCard(modifier = modifier) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            if (maxWidth < NARROW_SETTINGS) {
                Column(modifier = Modifier.fillMaxSize()) {
                    SettingsTabs(tab, choose, close)
                    pane(Modifier.weight(1f).fillMaxWidth())
                }
            } else {
                Row(modifier = Modifier.fillMaxSize()) {
                    SettingsRail(tab, choose, close)
                    pane(Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }

    if (bootLogo) BootLogoDialog(onDismiss = { bootLogo = false })
}

@Composable
private fun SettingsHeader(onClose: () -> Unit) {
    val tap = rememberTapFeedback()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(DashSize.TouchPrimary)
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
}

/** Wide screens: close, the title and the categories down the left. */
@Composable
private fun SettingsRail(tab: SettingsTab, onChoose: (SettingsTab) -> Unit, onClose: () -> Unit) {
    val tap = rememberTapFeedback()
    Column(
        modifier = Modifier
            .width(260.dp)
            .fillMaxHeight()
            .background(DashColors.CardHi.copy(alpha = DashColors.CardHi.alpha * 0.4f))
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
    ) {
        SettingsHeader(onClose)
        Spacer(Modifier.height(16.dp))
        SettingsTab.entries.forEach { t ->
            val chosen = t == tab
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = DashSize.TouchPrimary)
                    .clip(DashShape.Medium)
                    .then(if (chosen) Modifier.background(DashColors.AccentBrush) else Modifier)
                    .clickable(role = Role.Tab) { tap(); onChoose(t) }
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
}

/** Upright screens: close and the title, then the categories in rows of four across the top. */
@Composable
private fun SettingsTabs(tab: SettingsTab, onChoose: (SettingsTab) -> Unit, onClose: () -> Unit) {
    val tap = rememberTapFeedback()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DashColors.CardHi.copy(alpha = DashColors.CardHi.alpha * 0.4f))
            .padding(12.dp)
    ) {
        SettingsHeader(onClose)
        Spacer(Modifier.height(10.dp))
        SettingsTab.entries.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                row.forEach { t ->
                    val chosen = t == tab
                    val ink = if (chosen) DashColors.OnAccent else DashColors.TextPrimary
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 68.dp)
                            .clip(DashShape.Medium)
                            .then(
                                if (chosen) Modifier.background(DashColors.AccentBrush)
                                else Modifier.border(1.dp, DashColors.Line, DashShape.Medium)
                            )
                            .clickable(role = Role.Tab) { tap(); onChoose(t) }
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(t.icon, contentDescription = null, tint = ink, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(t.titleRes), color = ink,
                            fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                            style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Over the pane's bottom edge while there is more under it: a fade and an arrow. */
@Composable
private fun MoreBelow(scroll: ScrollState, modifier: Modifier = Modifier) {
    if (!scroll.canScrollForward) return
    val card = DashColors.Card.copy(alpha = 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(Brush.verticalGradient(listOf(Color.Transparent, card))),
        contentAlignment = Alignment.BottomCenter
    ) {
        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun CarPane(open: (Deep) -> Unit) {
    val car by CarProfileStore.profile.collectAsState()
    SettingsSection(stringResource(R.string.settings_section_car))
    SettingsRow(Icons.Filled.DirectionsCar, stringResource(R.string.car_menu), car.name) { open(Deep.CAR) }
    SpeedCorrectionRow()
    SettingsRow(Icons.Filled.AutoAwesome, stringResource(R.string.ai_title), stringResource(R.string.settings_ai_detail)) { open(Deep.AI) }
    SettingsRow(Icons.Filled.Handyman, stringResource(R.string.upkeep_dialog_title), stringResource(R.string.upkeep_settings_detail)) { open(Deep.UPKEEP) }
    SettingsRow(Icons.Filled.Science, stringResource(R.string.explore_title), stringResource(R.string.explore_settings_detail)) { open(Deep.EXPLORER) }
}

/** What the dashboard looks like: the theme first, then its day and night and its effects. */
@Composable
private fun LookPane(theme: ThemeState) {
    ThemeGallery(theme)
    Spacer(Modifier.height(20.dp))
    AppearanceSetting(theme)
    Spacer(Modifier.height(20.dp))
    EffectsSetting(theme)
}

/** What the screen does: which way it stands, its bottom bar, its language. */
@Composable
private fun DisplayPane(theme: ThemeState, onLanguage: () -> Unit) {
    val context = LocalContext.current
    ScreenOrientationSetting()
    Spacer(Modifier.height(20.dp))
    BarAutoHideSetting(theme)
    Spacer(Modifier.height(20.dp))
    SettingsSection(stringResource(R.string.language_title))
    SettingsRow(Icons.Filled.Language, stringResource(R.string.language_title), languageName(AppLanguage.current(context)), onLanguage)
}

/** A language by its own name; the system's says which one that is. */
@Composable
private fun languageName(language: AppLanguage): String {
    if (language != AppLanguage.SYSTEM) return language.nativeName
    val system = remember { AppLanguage.systemLocale() }
    return stringResource(R.string.language_system, system.getDisplayLanguage(system).replaceFirstChar { it.titlecase(system) })
}

@Composable
private fun LanguageSheet(onDismiss: () -> Unit) {
    SettingsSheet(
        title = stringResource(R.string.language_title),
        onDismiss = onDismiss,
        actions = {}
    ) {
        LanguageChoices()
    }
}

/**
 * Every alert in one place: which of the head unit's own pop-ups Dashwheel
 * shows in its place (only on firmware that has them), then how each one looks.
 */
@Composable
private fun AlertsPane() {
    val context = LocalContext.current
    val access = shellAccess()
    val kinds = remember(access) {
        RomPopups.Kind.entries.filter { RomPopups.available(context, it) && RomPopups.canWork(it, access) }
    }
    if (kinds.isNotEmpty()) {
        SettingsSection(stringResource(R.string.settings_section_popups))
        kinds.forEach { RomPopupToggle(it) }
        Spacer(Modifier.height(20.dp))
    }
    AlertStyleRows()
}

/**
 * One of the head unit's own pop-ups replaced by Dashwheel's ([RomPopups]).
 * Offered only where it can work (PrivilegedShell): the door alert reads the
 * CANbox through root, the radar switch is written through a shell.
 */
@Composable
private fun RomPopupToggle(kind: RomPopups.Kind) {
    val context = LocalContext.current
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

/** How long a new screen direction waits to be kept before it goes back. */
private const val KEEP_SECONDS = 15

/**
 * Which way the dashboard stands ([ScreenShape]). Auto reads it from the
 * screen and says what it found; the others turn the launcher, which rebuilds
 * the dashboard for that shape with its own layout. A turn is tried out: it
 * goes back by itself unless it is kept, so a screen turned the wrong way
 * never has to be read sideways to be put right.
 */
@Composable
private fun ScreenOrientationSetting() {
    val context = LocalContext.current
    val chosen by ScreenShape.choice.collectAsState()
    val tryingFrom by ScreenShape.tryingFrom.collectAsState()
    SettingsSection(stringResource(R.string.settings_screen_title))
    if (tryingFrom != null && ScreenShape.turned) KeepDirection()
    SegmentedSwitch(
        options = ScreenOrientation.entries,
        chosen = chosen,
        icon = { option ->
            when (option) {
                ScreenOrientation.AUTO -> Icons.Filled.ScreenRotation
                ScreenOrientation.HORIZONTAL -> Icons.Filled.StayCurrentLandscape
                ScreenOrientation.VERTICAL -> Icons.Filled.StayCurrentPortrait
            }
        },
        title = { stringResource(it.titleRes) },
        onChoose = { option -> context.findActivity()?.let { ScreenShape.tryOut(it, option) } }
    )
    SwitchHint(
        stringResource(
            when (chosen) {
                ScreenOrientation.AUTO ->
                    if (ScreenShape.vertical) R.string.settings_screen_auto_hint_tall else R.string.settings_screen_auto_hint_wide
                ScreenOrientation.HORIZONTAL -> R.string.settings_screen_horizontal_hint
                ScreenOrientation.VERTICAL -> R.string.settings_screen_vertical_hint
            }
        )
    )
}

/** Asked once the screen has turned: keep it, or go back (which it does by itself after [KEEP_SECONDS]). */
@Composable
private fun KeepDirection() {
    val context = LocalContext.current
    var left by remember { mutableIntStateOf(KEEP_SECONDS) }
    LaunchedEffect(Unit) {
        while (left > 0) {
            delay(1_000)
            left--
        }
        context.findActivity()?.let { ScreenShape.revert(it) }
    }
    val shape = DashShape.Medium
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .clip(shape)
            .background(DashColors.Warning.copy(alpha = 0.12f))
            .border(1.dp, DashColors.Warning.copy(alpha = 0.6f), shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_screen_keep_title), color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.settings_screen_keep_detail, left), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        CompositionLocalProvider(LocalSheetInPane provides true) {
            SheetButton(stringResource(R.string.settings_screen_revert), primary = false) {
                context.findActivity()?.let { ScreenShape.revert(it) }
            }
            SheetButton(stringResource(R.string.settings_screen_keep)) { ScreenShape.keep() }
        }
    }
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
    Spacer(Modifier.height(20.dp))
    VolumeWaySetting()
    Spacer(Modifier.height(20.dp))
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
    SettingsSection(stringResource(R.string.volume_way_title))
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
    SettingsSection(stringResource(R.string.speed_volume_title))
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
private fun AdvancedPane(m: TopBarModel, onBootLogo: () -> Unit, onPickObd: () -> Unit, onClose: () -> Unit) {
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
    // Google Maps inside a tile needs permissions only the firmware's apps get
    // (EmbeddedApp): whether Android granted them, where they could be.
    val embed = EmbeddedApp.allowed(LocalContext.current)
    if (shell || embed) SystemPermissionsRow(embed)
    if (embed) EmbeddedDiagnosticsRow()
    Spacer(Modifier.height(20.dp))
    // What is allowed and what is not, without running the setup again.
    SettingsSection(stringResource(R.string.setup_access_title))
    Column(modifier = Modifier.padding(horizontal = 12.dp)) { AccessRows(onPickObd) }
    SettingsRow(Icons.Filled.Checklist, stringResource(R.string.setup_again), stringResource(R.string.setup_again_detail)) { m.onSetup(true) }
    Spacer(Modifier.height(20.dp))
    SettingsSection(stringResource(R.string.settings_section_about))
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

/** The one heading of the Settings panes: every group of settings starts with it. */
@Composable
internal fun SettingsSection(title: String) {
    Text(
        title.uppercase(),
        color = DashColors.Accent,
        letterSpacing = 0.08.em,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 8.dp)
    )
}

/**
 * What became of the touches sent to apps inside tiles, and whether the unit's
 * key service is listened to: facts to read out when those misbehave. A tap
 * starts the count again.
 */
@Composable
private fun EmbeddedDiagnosticsRow() {
    val stats by EmbeddedApp.touchStats.collectAsState()
    val detail = stringResource(
        R.string.settings_embed_diag_detail,
        stats.sent, stats.refused, stats.failed, stats.slowestMs,
        if (HeadUnitKeys.listening) "on" else "off"
    ) + (stats.lastError?.let { "\n$it" } ?: "")
    SettingsRow(Icons.Filled.Build, stringResource(R.string.settings_embed_diag_title), detail) { EmbeddedApp.clearTouchStats() }
}

/** Whether Android granted Dashwheel the system permissions: a state to read, nothing to tap. */
@Composable
private fun SystemPermissionsRow(granted: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = DashSize.Bar)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.VerifiedUser, contentDescription = null,
            tint = if (granted) DashColors.Good else DashColors.TextSecondary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_system_perms), color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(if (granted) R.string.settings_system_perms_on else R.string.settings_system_perms_off),
                color = if (granted) DashColors.Good else DashColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
    HorizontalDivider(color = DashColors.Line, modifier = Modifier.padding(horizontal = 12.dp))
}

/** One setting: icon, name, what it is right now or what it does, and a chevron. */
@Composable
internal fun SettingsRow(icon: ImageVector, title: String, detail: String?, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = DashSize.Bar)
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
                    maxLines = 2,
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
 * speedometer while driving along. Also in the telemetry tile's dialog, which
 * opens on the move for that reason.
 */
@Composable
internal fun SpeedCorrectionRow() {
    val context = LocalContext.current
    val offset by SpeedCorrection.offsetKmh.collectAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = DashSize.Bar)
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
        StepButton(Icons.Filled.Remove, stringResource(R.string.vehicle_speed_fix_less), enabled = offset > -SpeedCorrection.MAX_OFFSET_KMH) {
            SpeedCorrection.save(context, offset - 1)
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
        StepButton(Icons.Filled.Add, stringResource(R.string.vehicle_speed_fix_more), enabled = offset < SpeedCorrection.MAX_OFFSET_KMH) {
            SpeedCorrection.save(context, offset + 1)
        }
    }
    HorizontalDivider(color = DashColors.Line, modifier = Modifier.padding(horizontal = 12.dp))
}

/** − or +: a filled square a thumb lands on, since it is pressed on the move. */
@Composable
private fun StepButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    Box(
        modifier = Modifier
            .size(DashSize.TouchPrimary)
            .clip(DashShape.Medium)
            .background(DashColors.CardHi)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = description) { tap(); onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = if (enabled) DashColors.TextPrimary else DashColors.Muted)
    }
}

/** "+3 km/h", "−2 km/h", or "0 km/h" when there's no correction. */
internal fun speedOffsetText(offsetKmh: Int): String = when {
    offsetKmh > 0 -> "+$offsetKmh km/h"
    offsetKmh < 0 -> "−${-offsetKmh} km/h"
    else -> "0 km/h"
}

/** One on/off setting: icon, name, what it does, and a switch; the whole row toggles it. */
@Composable
internal fun SettingsToggle(icon: ImageVector, title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val tap = rememberTapFeedback()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = DashSize.Bar)
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
