package com.openauto.dash

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.Normalizer

/*
 * What Settings opens on: how things stand before anything is changed. Four
 * cards (the adapter, the phone, the update, the servicing), a line for what
 * Dashwheel may not use yet, then the groups as tiles that say what each
 * holds now, so most visits end here. A search over every setting's name
 * sits at the top, typed on the screen or from the phone's keyboard.
 */

/** Which groups show every setting rather than the usual ones; remembered per group. */
internal object SettingsShowAll {
    private const val PREFS = "settings_view"
    private val _open = MutableStateFlow<Set<SettingsTab>>(emptySet())
    val open: StateFlow<Set<SettingsTab>> = _open.asStateFlow()
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _open.value = SettingsTab.entries.filter { prefs.getBoolean(it.name, false) }.toSet()
    }

    fun set(context: Context, tab: SettingsTab, on: Boolean) {
        load(context)
        _open.value = if (on) _open.value + tab else _open.value - tab
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(tab.name, on).apply()
    }
}

/**
 * @param narrow an upright screen: the cards two by two, the tiles in two columns
 * @param onGo opens a group, and the pages [path] in it; extra: the setting is one "Show all" keeps;
 *   the last: the name of the setting searched for, scrolled to there
 */
@Composable
internal fun SettingsOverview(
    m: TopBarModel,
    theme: ThemeState,
    narrow: Boolean,
    onGo: (SettingsTab, List<Deep>, Boolean, Int?) -> Unit,
    onClose: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
        if (narrow) {
            SettingsHeader(onClose)
            Spacer(Modifier.height(12.dp))
            SettingsSearchField(query, { query = it }, Modifier.fillMaxWidth())
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SettingsHeader(onClose)
                Spacer(Modifier.weight(1f))
                SettingsSearchField(query, { query = it }, Modifier.widthIn(max = 520.dp).weight(1.4f))
            }
        }
        Spacer(Modifier.height(16.dp))
        val scroll = rememberScrollState()
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxSize().imePadding().verticalScroll(scroll)) {
                if (query.isBlank()) OverviewBody(m, theme, narrow, onGo)
                else SearchResults(query.trim(), onGo)
            }
            MoreBelow(scroll, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun OverviewBody(m: TopBarModel, theme: ThemeState, narrow: Boolean, onGo: (SettingsTab, List<Deep>, Boolean, Int?) -> Unit) {
    val cards: List<@Composable (Modifier) -> Unit> = listOf(
        { ObdCard(it) { onGo(SettingsTab.CONNECTIONS, listOf(Deep.OBD), false, null) } },
        { PhoneCard(it) { onGo(SettingsTab.CONNECTIONS, emptyList(), false, null) } },
        { UpdateCard(m, it) },
        { UpkeepCard(it) { onGo(SettingsTab.CAR, listOf(Deep.UPKEEP), false, null) } }
    )
    Grid(cards, columns = if (narrow) 2 else 4)
    val missing = missingAccess()
    if (missing.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        NotAllowedStrip(missing.map { stringResource(it.titleRes) }) { onGo(SettingsTab.CONNECTIONS, listOf(Deep.ACCESS), false, null) }
    }
    Spacer(Modifier.height(20.dp))
    val tiles: List<@Composable (Modifier) -> Unit> = SettingsTab.groups.map { group ->
        { modifier -> GroupTile(group, groupSummary(group, m, theme), modifier) { onGo(group, emptyList(), false, null) } }
    }
    Grid(tiles, columns = if (narrow) 2 else 4)
    Spacer(Modifier.height(16.dp))
}

/** Equal cells in rows of [columns], the last row left-aligned. */
@Composable
private fun Grid(cells: List<@Composable (Modifier) -> Unit>, columns: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        cells.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                row.forEach { cell -> cell(Modifier.weight(1f).fillMaxHeight()) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private enum class CardTone { PLAIN, GOOD, WARN, BAD }

@Composable
private fun CardTone.color(): Color = when (this) {
    CardTone.PLAIN -> DashColors.TextSecondary
    CardTone.GOOD -> DashColors.Good
    CardTone.WARN -> DashColors.Warning
    CardTone.BAD -> DashColors.Critical
}

/** One status: what it is, how it stands, one more line; the whole card opens where it is set. */
@Composable
private fun StatusCard(icon: ImageVector, label: String, value: String, detail: String?, tone: CardTone, modifier: Modifier, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    val shape = DashShape.Medium
    Column(
        modifier = modifier
            .clip(shape)
            .background(DashColors.CardHi.copy(alpha = DashColors.CardHi.alpha * 0.6f))
            .then(if (tone == CardTone.WARN || tone == CardTone.BAD) Modifier.border(1.dp, tone.color().copy(alpha = 0.6f), shape) else Modifier)
            .clickable(role = Role.Button) { tap(); onClick() }
            .heightIn(min = 96.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tone.color(), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, color = tone.color(), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(value, color = DashColors.TextPrimary, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (detail != null) Text(detail, color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ObdCard(modifier: Modifier, onClick: () -> Unit) {
    val source by DeepObdSource.source.collectAsState()
    val connection by ObdBluetoothManager.connectionState.collectAsState()
    val name = remember(connection) { ObdBluetoothManager.savedDeviceName() }
    val linked = connection == ObdConnectionState.CONNECTED
    val deep = source == ObdSource.DEEPOBD
    StatusCard(
        icon = if (linked) Icons.Filled.BluetoothConnected else Icons.Filled.Bluetooth,
        label = stringResource(R.string.settings_obd_title),
        value = obdValue(),
        detail = when {
            deep -> null
            linked -> stringResource(R.string.settings_ov_linked)
            connection == ObdConnectionState.CONNECTING -> stringResource(R.string.settings_ov_connecting)
            name != null -> stringResource(R.string.settings_ov_not_linked)
            else -> stringResource(R.string.settings_ov_obd_pick)
        },
        tone = if (linked) CardTone.GOOD else CardTone.PLAIN,
        modifier = modifier,
        onClick = onClick
    )
}

/** Where the readings come from, in a word or two: the Deep OBD app, or the adapter's name. */
@Composable
private fun obdValue(): String {
    val source by DeepObdSource.source.collectAsState()
    val connection by ObdBluetoothManager.connectionState.collectAsState()
    val name = remember(connection) { ObdBluetoothManager.savedDeviceName() }
    return when {
        source == ObdSource.DEEPOBD -> stringResource(R.string.settings_ov_obd_deep)
        name != null -> name
        connection == ObdConnectionState.CONNECTED -> stringResource(R.string.settings_ov_linked)
        else -> stringResource(R.string.settings_ov_obd_none)
    }
}

@Composable
private fun PhoneCard(modifier: Modifier, onClick: () -> Unit) {
    val state by PhoneLink.state.collectAsState()
    val s = state
    StatusCard(
        icon = Icons.Filled.PhoneAndroid,
        label = stringResource(R.string.settings_section_phone),
        value = when (s) {
            is PhoneLinkState.Connected -> s.phoneName.ifEmpty { stringResource(R.string.phone_unnamed) }
            PhoneLinkState.Searching -> stringResource(R.string.settings_ov_phone_away)
            PhoneLinkState.Unpaired -> stringResource(R.string.phone_status_unpaired)
        },
        detail = when (s) {
            is PhoneLinkState.Connected -> stringResource(R.string.settings_ov_linked)
            PhoneLinkState.Searching -> stringResource(R.string.settings_ov_not_linked)
            PhoneLinkState.Unpaired -> stringResource(R.string.phone_pair)
        },
        tone = if (s is PhoneLinkState.Connected) CardTone.GOOD else CardTone.PLAIN,
        modifier = modifier,
        onClick = onClick
    )
}

@Composable
private fun UpdateCard(m: TopBarModel, modifier: Modifier) {
    val context = LocalContext.current
    if (Edition.play) {
        // Google Play updates this edition: the card says which version runs and opens its listing.
        StatusCard(
            icon = Icons.Filled.SystemUpdate,
            label = stringResource(R.string.settings_ov_updates),
            value = stringResource(R.string.settings_version, m.versionName),
            detail = stringResource(R.string.about_updates_play),
            tone = CardTone.PLAIN,
            modifier = modifier
        ) { context.launchSafely(Intent(Intent.ACTION_VIEW, Uri.parse(PLAY_LISTING + context.packageName))) }
        return
    }
    val waiting = m.update.updateInfo != null
    StatusCard(
        icon = Icons.Filled.SystemUpdate,
        label = stringResource(R.string.settings_ov_updates),
        value = if (waiting) updateTitle(m) else stringResource(R.string.settings_version, m.versionName),
        detail = updateDetail(m),
        tone = if (waiting) CardTone.WARN else CardTone.PLAIN,
        modifier = modifier
    ) { updateTap(m) }
}

@Composable
private fun UpkeepCard(modifier: Modifier, onClick: () -> Unit) {
    val state by Maintenance.state.collectAsState()
    val first = remember(state) { state.statuses(System.currentTimeMillis()).firstOrNull { it.stage != UpkeepStage.UNKNOWN } }
    StatusCard(
        icon = Icons.Filled.Handyman,
        label = stringResource(R.string.upkeep_dialog_title),
        value = first?.let { upkeepLine(it) } ?: stringResource(R.string.settings_ov_upkeep_none),
        detail = if (first == null) stringResource(R.string.upkeep_settings_detail) else null,
        tone = when (first?.stage) {
            UpkeepStage.DUE -> CardTone.BAD
            UpkeepStage.SOON -> CardTone.WARN
            else -> CardTone.PLAIN
        },
        modifier = modifier,
        onClick = onClick
    )
}

/** What Dashwheel may not use yet, named, with the way to allow it. */
@Composable
private fun NotAllowedStrip(names: List<String>, onOpen: () -> Unit) {
    val shape = DashShape.Medium
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(DashColors.Warning.copy(alpha = 0.10f))
            .border(1.dp, DashColors.Warning.copy(alpha = 0.5f), shape)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = DashColors.Warning, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_ov_not_allowed, names.joinToString(", ")), color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.settings_ov_not_allowed_detail), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(12.dp))
        CompositionLocalProvider(LocalSheetInPane provides true) {
            SheetButton(stringResource(R.string.settings_ov_allow)) { onOpen() }
        }
    }
}

/** A group: its icon and name, and what it holds now in one line. */
@Composable
private fun GroupTile(group: SettingsTab, summary: String, modifier: Modifier, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    Column(
        modifier = modifier
            .clip(DashShape.Medium)
            .background(DashColors.CardHi.copy(alpha = DashColors.CardHi.alpha * 0.6f))
            .clickable(role = Role.Button) { tap(); onClick() }
            .heightIn(min = 112.dp)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(group.icon, contentDescription = null, tint = DashColors.Accent, modifier = Modifier.size(30.dp))
        Spacer(Modifier.weight(1f))
        Text(stringResource(group.titleRes), color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(summary, color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** What [group] holds now, short enough for a tile. */
@Composable
private fun groupSummary(group: SettingsTab, m: TopBarModel, theme: ThemeState): String {
    val context = LocalContext.current
    return when (group) {
        SettingsTab.OVERVIEW -> ""
        SettingsTab.CAR -> CarProfileStore.profile.collectAsState().value.displayName(context)
        SettingsTab.CONNECTIONS -> {
            val phone = when (val s = PhoneLink.state.collectAsState().value) {
                is PhoneLinkState.Connected -> s.phoneName.ifEmpty { stringResource(R.string.phone_unnamed) }
                PhoneLinkState.Searching -> stringResource(R.string.settings_ov_phone_away)
                PhoneLinkState.Unpaired -> stringResource(R.string.phone_status_unpaired)
            }
            "${obdValue()}, $phone"
        }
        SettingsTab.LOOK -> stringResource(theme.mode.titleRes)
        SettingsTab.SOUND -> {
            val on = SpokenEvents.on.collectAsState().value
            val speed = SpeedVolume.on.collectAsState().value
            val spoken = pluralStringResource(R.plurals.settings_sum_spoken, on.size, on.size, SpokenEvent.entries.size)
            if (speed) "$spoken. ${stringResource(R.string.speed_volume_title)}" else spoken
        }
        SettingsTab.POPUPS -> {
            val replaced = RomPopups.replaced.collectAsState().value.size
            if (replaced > 0) pluralStringResource(R.plurals.settings_sum_popups, replaced, replaced)
            else stringResource(R.string.settings_sum_popups_none)
        }
        SettingsTab.DRIVING -> {
            val buttons = SteeringWheelStore.buttons.collectAsState().value
            val assigned = buttons.count { it.assignment != null }
            if (SteeringWheelStore.AVAILABLE && assigned > 0) pluralStringResource(R.plurals.wheel_settings_detail_count, assigned, assigned)
            else stringResource(if (m.lockWhileMoving) R.string.settings_drive_lock else R.string.settings_section_buttons)
        }
        SettingsTab.SYSTEM -> {
            val units = Units.current.collectAsState().value
            "${languageName(AppLanguage.current(context))}, ${unitsSummary(units)}"
        }
    }
}

/* ---------- Search ---------- */

/**
 * One setting as the search knows it: its name, its group, the pages it is
 * in, whether "Show all" keeps it, and whether this unit has it at all.
 */
private class SettingsEntry(
    @StringRes val title: Int,
    val tab: SettingsTab,
    val path: List<Deep> = emptyList(),
    val extra: Boolean = false,
    /** Its row's second line, searched too: "camera" finds the reverse view. */
    @StringRes val detail: Int? = null,
    val offered: () -> Boolean = { true }
)

/** Every setting by name, in the order of the groups. */
private val SETTINGS_INDEX: List<SettingsEntry> by lazy {
    val full = { Edition.full }
    listOf(
        SettingsEntry(R.string.car_menu, SettingsTab.CAR, listOf(Deep.CAR)),
        SettingsEntry(R.string.mycar_title, SettingsTab.CAR, listOf(Deep.CAR_LOOK), detail = R.string.mycar_settings_detail),
        SettingsEntry(R.string.ai_title, SettingsTab.CAR, listOf(Deep.AI), detail = R.string.settings_ai_detail),
        SettingsEntry(R.string.upkeep_dialog_title, SettingsTab.CAR, listOf(Deep.UPKEEP), detail = R.string.upkeep_settings_detail),
        SettingsEntry(R.string.lpg_title, SettingsTab.CAR, listOf(Deep.LPG)),
        SettingsEntry(R.string.vehicle_speed_fix, SettingsTab.CAR, detail = R.string.settings_speed_fix_detail),
        SettingsEntry(R.string.reverse_title, SettingsTab.CAR, listOf(Deep.REVERSE), detail = R.string.reverse_settings_detail),

        SettingsEntry(R.string.settings_obd_title, SettingsTab.CONNECTIONS, listOf(Deep.OBD)),
        SettingsEntry(R.string.settings_section_phone, SettingsTab.CONNECTIONS),
        SettingsEntry(R.string.phone_pair, SettingsTab.CONNECTIONS),
        SettingsEntry(R.string.phone_gps, SettingsTab.CONNECTIONS, detail = R.string.phone_gps_detail),
        SettingsEntry(R.string.settings_section_second_screen, SettingsTab.CONNECTIONS, listOf(Deep.SECOND_SCREEN)),
        SettingsEntry(R.string.setup_access_title, SettingsTab.CONNECTIONS, listOf(Deep.ACCESS)),

        SettingsEntry(R.string.dash_theme_picker_title, SettingsTab.LOOK),
        SettingsEntry(R.string.settings_appearance_title, SettingsTab.LOOK),
        SettingsEntry(R.string.dash_bar_auto_hide, SettingsTab.LOOK, detail = R.string.dash_bar_auto_hide_detail),
        SettingsEntry(R.string.bar_items_custom, SettingsTab.LOOK, extra = true),
        SettingsEntry(R.string.dash_effects_title, SettingsTab.LOOK, extra = true),
        SettingsEntry(R.string.settings_side_rail, SettingsTab.LOOK, extra = true, detail = R.string.settings_side_rail_detail),
        SettingsEntry(R.string.settings_tabs_nav, SettingsTab.LOOK, extra = true),

        SettingsEntry(R.string.voice_section, SettingsTab.SOUND),
        SettingsEntry(R.string.briefing_setting, SettingsTab.SOUND),
        SettingsEntry(R.string.voice_warnings, SettingsTab.SOUND),
        SettingsEntry(R.string.voice_faults, SettingsTab.SOUND),
        SettingsEntry(R.string.widgets_cam, SettingsTab.SOUND),
        SettingsEntry(R.string.voice_messages, SettingsTab.SOUND),
        SettingsEntry(R.string.widgets_limit, SettingsTab.SOUND, extra = true),
        SettingsEntry(R.string.voice_weather, SettingsTab.SOUND, extra = true),
        SettingsEntry(R.string.voice_lpg, SettingsTab.SOUND, extra = true),
        SettingsEntry(R.string.speed_volume_title, SettingsTab.SOUND, detail = R.string.speed_volume_detail),
        SettingsEntry(R.string.volume_way_title, SettingsTab.SOUND, extra = true, offered = { MediaVolume.choiceOffered }),
        SettingsEntry(R.string.settings_tap_sound, SettingsTab.SOUND, extra = true, detail = R.string.settings_tap_sound_detail),
        SettingsEntry(R.string.settings_resume_music, SettingsTab.SOUND, extra = true),

        SettingsEntry(R.string.alert_style_section, SettingsTab.POPUPS),
        SettingsEntry(R.string.alert_kind_call, SettingsTab.POPUPS),
        SettingsEntry(R.string.alert_kind_doors, SettingsTab.POPUPS),
        SettingsEntry(R.string.alert_kind_radar, SettingsTab.POPUPS),
        SettingsEntry(R.string.alert_kind_ac, SettingsTab.POPUPS),
        SettingsEntry(R.string.alert_kind_tyres, SettingsTab.POPUPS),
        SettingsEntry(R.string.alert_kind_belt, SettingsTab.POPUPS),
        SettingsEntry(R.string.alert_kind_volume, SettingsTab.POPUPS),

        SettingsEntry(R.string.settings_drive_lock, SettingsTab.DRIVING, detail = R.string.settings_drive_lock_detail),
        SettingsEntry(R.string.places_title, SettingsTab.DRIVING, listOf(Deep.PLACES)),
        SettingsEntry(R.string.wheel_title, SettingsTab.DRIVING, listOf(Deep.WHEEL), offered = { SteeringWheelStore.AVAILABLE }),
        SettingsEntry(R.string.keys_section, SettingsTab.DRIVING, offered = { Edition.full && KeyTargets.available() }),

        SettingsEntry(R.string.language_title, SettingsTab.SYSTEM, listOf(Deep.LANGUAGE)),
        SettingsEntry(R.string.units_title, SettingsTab.SYSTEM, listOf(Deep.UNITS)),
        SettingsEntry(R.string.settings_screen_title, SettingsTab.SYSTEM),
        SettingsEntry(R.string.settings_backup_section, SettingsTab.SYSTEM),
        SettingsEntry(R.string.demo_menu_start, SettingsTab.SYSTEM),
        SettingsEntry(R.string.setup_again, SettingsTab.SYSTEM, extra = true),
        SettingsEntry(R.string.settings_unit_bar_title, SettingsTab.SYSTEM, extra = true, offered = { FreeformBar.supported && Edition.full }),
        SettingsEntry(R.string.dash_menu_check_updates, SettingsTab.SYSTEM, offered = full),
        SettingsEntry(R.string.about_updates_play, SettingsTab.SYSTEM, offered = { Edition.play }),
        SettingsEntry(R.string.settings_section_about, SettingsTab.SYSTEM, listOf(Deep.ABOUT)),
        SettingsEntry(R.string.help_title, SettingsTab.SYSTEM, listOf(Deep.ABOUT)),
        SettingsEntry(R.string.tour_settings_row, SettingsTab.SYSTEM, listOf(Deep.ABOUT)),
        SettingsEntry(R.string.settings_unit_tools, SettingsTab.SYSTEM, listOf(Deep.UNIT_TOOLS), offered = full),
        SettingsEntry(R.string.boot_menu, SettingsTab.SYSTEM, listOf(Deep.UNIT_TOOLS), offered = full),
        SettingsEntry(R.string.settings_system_perms, SettingsTab.SYSTEM, listOf(Deep.UNIT_TOOLS), offered = full),
        SettingsEntry(R.string.dash_system_app_title, SettingsTab.SYSTEM, listOf(Deep.UNIT_TOOLS), offered = full),
        SettingsEntry(R.string.settings_lab, SettingsTab.SYSTEM, listOf(Deep.LAB)),
        SettingsEntry(R.string.explore_title, SettingsTab.SYSTEM, listOf(Deep.LAB, Deep.EXPLORER), detail = R.string.explore_settings_detail),
        SettingsEntry(R.string.signals_title, SettingsTab.SYSTEM, listOf(Deep.LAB, Deep.SIGNALS), detail = R.string.signals_settings_detail),
        SettingsEntry(R.string.settings_sleep_title, SettingsTab.SYSTEM, listOf(Deep.LAB), offered = { CarPower.available }),
        SettingsEntry(R.string.settings_log_title, SettingsTab.SYSTEM, listOf(Deep.LAB))
    )
}

/** A deep page's own title, for the path a result shows. */
@StringRes
private fun Deep.titleRes(): Int = when (this) {
    Deep.CAR -> R.string.car_menu
    Deep.CAR_LOOK -> R.string.mycar_title
    Deep.REVERSE -> R.string.reverse_title
    Deep.SIGNALS -> R.string.signals_title
    Deep.AI -> R.string.ai_title
    Deep.UPKEEP -> R.string.upkeep_dialog_title
    Deep.EXPLORER -> R.string.explore_title
    Deep.WHEEL -> R.string.wheel_title
    Deep.PLACES -> R.string.places_title
    Deep.LANGUAGE -> R.string.language_title
    Deep.SECOND_SCREEN -> R.string.settings_section_second_screen
    Deep.LPG -> R.string.lpg_title
    Deep.OBD -> R.string.settings_obd_title
    Deep.UNITS -> R.string.units_title
    Deep.ACCESS -> R.string.setup_access_title
    Deep.ABOUT -> R.string.settings_section_about
    Deep.LAB -> R.string.settings_lab
    Deep.UNIT_TOOLS -> R.string.settings_unit_tools
}

/** Lower case without accents, so "eco" finds "Éco" and "volumen" finds "Volumen". */
private fun fold(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

private class Found(val entry: SettingsEntry, val title: String, val path: String, val rank: Int)

@Composable
private fun SearchResults(query: String, onGo: (SettingsTab, List<Deep>, Boolean, Int?) -> Unit) {
    val context = LocalContext.current
    val words = remember(query) { fold(query).split(' ').filter { it.isNotBlank() } }
    val found = remember(words, context.resources.configuration) {
        SETTINGS_INDEX.filter { it.offered() }.mapNotNull { entry ->
            val title = context.getString(entry.title)
            val pages = listOf(context.getString(entry.tab.titleRes)) +
                entry.path.map { context.getString(it.titleRes()) }.filter { it != title }
            val named = fold("$title ${pages.joinToString(" ")}")
            val hay = entry.detail?.let { "$named ${fold(context.getString(it))}" } ?: named
            if (words.any { it !in hay }) return@mapNotNull null
            val t = fold(title)
            val rank = when {
                t.startsWith(words.first()) -> 0
                words.all { it in t } -> 1
                words.all { it in named } -> 2
                else -> 3
            }
            Found(entry, title, pages.joinToString(" › "), rank)
        }.sortedBy { it.rank }
    }
    if (found.isEmpty()) {
        Text(
            stringResource(R.string.settings_search_none, query),
            color = DashColors.TextSecondary,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(12.dp)
        )
        return
    }
    val tap = rememberTapFeedback()
    found.forEach { f ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = DashSize.Bar)
                .clip(DashShape.Medium)
                .clickable { tap(); onGo(f.entry.tab, f.entry.path, f.entry.extra, f.entry.title) }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(f.entry.tab.icon, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(f.path, color = DashColors.Accent, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(f.title, color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = DashColors.Muted)
        }
        HorizontalDivider(color = DashColors.Line, modifier = Modifier.padding(horizontal = 12.dp))
    }
}

/** The search over every setting's name: a field a thumb hits, with a way to clear it. */
@Composable
private fun SettingsSearchField(query: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val keyboard = LocalSoftwareKeyboardController.current
    val tap = rememberTapFeedback()
    BasicTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = DashColors.TextPrimary),
        cursorBrush = SolidColor(DashColors.Accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        modifier = modifier,
        decorationBox = { field ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = DashSize.TouchPrimary)
                    .clip(DashShape.Medium)
                    .background(DashColors.CardHi)
                    .padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(stringResource(R.string.settings_search_hint), color = DashColors.Muted, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                    field()
                }
                if (query.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .size(DashSize.Touch)
                            .clip(DashShape.Medium)
                            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.settings_search_clear)) { tap(); onChange("") },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.settings_search_clear), tint = DashColors.TextSecondary)
                    }
                }
            }
        }
    )
}
