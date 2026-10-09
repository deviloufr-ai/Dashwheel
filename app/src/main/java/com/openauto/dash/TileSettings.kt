package com.openauto.dash

import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/*
 * Settings where they act: a tile's own settings open from the tile panel
 * while arranging, the same page Settings opens (one home per setting, a
 * shortcut from the thing itself). Outside Settings the page is the dialog
 * it always was (SettingsSheet.kt).
 */

/** The page behind a tile: its name for the button, and the page itself. */
internal class TileSettings(@StringRes val title: Int, val page: @Composable (onDismiss: () -> Unit) -> Unit)

/** The settings page that belongs to [kind]'s tile; null for a tile with none. */
internal fun tileSettings(kind: BuiltinKind): TileSettings? = when (kind) {
    BuiltinKind.LPG_TANK -> TileSettings(R.string.lpg_title) { LpgTankSheet(onDismiss = it) }
    BuiltinKind.SERVICE -> TileSettings(R.string.upkeep_dialog_title) { UpkeepDialog(onDismiss = it) }
    BuiltinKind.HOME_WORK, BuiltinKind.COMMUTE -> TileSettings(R.string.places_title) { PlacesSheet(onDismiss = it) }
    // Tiles that read the car's specs: tank, battery, filter, gears, running temperature, tyre pressures.
    BuiltinKind.MY_CAR, BuiltinKind.FILTER_CARE, BuiltinKind.WARMUP, BuiltinKind.BATTERY, BuiltinKind.GEAR,
    BuiltinKind.FUEL_TO_DEST, BuiltinKind.FUEL_PRICES, BuiltinKind.FUEL_LOG, BuiltinKind.TYRES, BuiltinKind.ENGINE_TEMPS ->
        TileSettings(R.string.car_menu) { CarSettingsDialog(onDismiss = it) }
    // Tiles that draw the car.
    BuiltinKind.CAR_STATUS, BuiltinKind.DOORS -> TileSettings(R.string.mycar_title) { MyCarLookSheet(onDismiss = it) }
    BuiltinKind.OBD_DTC -> TileSettings(R.string.ai_title) { AiSettingsDialog(onDismiss = it) }
    BuiltinKind.TELEMETRY, BuiltinKind.SPEED_HUD -> TileSettings(R.string.vehicle_speed_fix) { SpeedCorrectionDialog(speedKmh = null, onDismiss = it) }
    BuiltinKind.QUICK_DIAL -> TileSettings(R.string.settings_section_phone) { PhoneSheet(onDismiss = it) }
    BuiltinKind.SPEED_CAMERAS -> spoken(SpokenEvent.SPEED_CAMERAS)
    BuiltinKind.SPEED_LIMIT -> spoken(SpokenEvent.SPEED_LIMIT)
    BuiltinKind.WEATHER_ALERTS -> spoken(SpokenEvent.WEATHER)
    else -> null
}

/** A tile whose setting is whether the car says it: that one switch, as in Sound and voice. */
private fun spoken(event: SpokenEvent) = TileSettings(event.label) { SpokenEventSheet(event, onDismiss = it) }

@Composable
private fun SpokenEventSheet(event: SpokenEvent, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val on by SpokenEvents.on.collectAsState()
    SettingsSheet(
        title = stringResource(R.string.voice_section),
        onDismiss = onDismiss,
        actions = { SheetButton(stringResource(R.string.dash_close)) { onDismiss() } }
    ) {
        SettingsToggle(event.icon, stringResource(event.label), stringResource(event.detail), event in on) { wanted ->
            SpokenEvents.set(context, event, wanted)
            if (event == SpokenEvent.SPEED_CAMERAS && wanted) SpeedCameras.startIfSpeaking(context)
        }
    }
}

/** The phone's link, paired phones and pairing, as under Settings, Connections. */
@Composable
private fun PhoneSheet(onDismiss: () -> Unit) {
    SettingsSheet(
        title = stringResource(R.string.settings_section_phone),
        onDismiss = onDismiss,
        actions = { SheetButton(stringResource(R.string.dash_close)) { onDismiss() } }
    ) {
        PhonePane()
    }
}

/** The tile panel's way to the tile's settings: "Settings" over "LPG", opening that page over the panel. */
@Composable
internal fun TileSettingsButton(settings: TileSettings, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val tap = rememberTapFeedback()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = DashSize.Touch)
            .clip(DashShape.Small)
            .border(1.dp, DashColors.Line, DashShape.Small)
            .clickable(role = Role.Button) { tap(); open = true }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Tune, contentDescription = null, tint = DashColors.Accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        // "Settings" over the page's name: the panel is narrow, the name must fit.
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(stringResource(R.string.settings_title), color = DashColors.TextSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            Text(
                stringResource(settings.title),
                color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = DashColors.Muted)
    }
    if (open) settings.page { open = false }
}
