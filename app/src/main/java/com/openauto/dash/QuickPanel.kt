package com.openauto.dash

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/*
 * The quick panel's pieces: what ⋮ opens (TopBar.kt MorePicker) and the
 * switches on Settings' first page. A switch is lit while it is on and says
 * so in a word; an action is a tile that never lights.
 */

/** How wide ⋮'s panel is: three switches a finger finds side by side. */
internal val QUICK_PANEL_WIDTH = 480.dp

/**
 * @param on lit or not for a switch; null for an action, which only does [onClick]
 * @param primary an action that stands out: Settings
 */
@Composable
internal fun QuickTile(
    icon: ImageVector,
    label: String,
    on: Boolean?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    /** A switch's state in a word; null where it isn't known, or for an action. */
    line: String? = null,
    onClick: () -> Unit
) {
    val tap = rememberTapFeedback()
    val lit = (on == true || primary) && enabled
    val ink = when {
        !enabled -> DashColors.Muted
        lit -> DashColors.OnAccent
        else -> DashColors.TextPrimary
    }
    val shape = DashShape.Medium
    val act = { tap(); onClick() }
    Row(
        modifier = modifier
            .clip(shape)
            .background(if (lit) DashColors.Accent else DashColors.Card.copy(alpha = 1f))
            .border(1.dp, if (lit) DashColors.Accent else DashColors.Line, shape)
            .then(
                if (on != null) Modifier.toggleable(value = on, enabled = enabled, role = Role.Switch) { act() }
                else Modifier.clickable(enabled = enabled, role = Role.Button) { act() }
            )
            .heightIn(min = DashSize.MenuRow)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (on == null) Arrangement.Center else Arrangement.Start
    ) {
        // A switch is its name and its state: three to a row leave no room for an icon as well.
        if (on == null) {
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
        }
        Column {
            Text(
                label, color = ink, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            // The state in a word under the name: tint alone said nothing (QuickSwitches.kt).
            if (on != null && line != null) Text(
                line, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = ink.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/** The switches the driver picked (QuickSwitches.kt, the same as the Quick switches tile), [columns] to a row. */
@Composable
internal fun QuickSwitches(columns: Int, modifier: Modifier = Modifier) {
    val tiles = quickSwitchUis()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { u -> QuickTile(u.icon, u.name, u.on, Modifier.weight(1f).fillMaxHeight(), line = u.line, onClick = u.onClick) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** The screen's brightness and the music's volume, side by side. */
@Composable
internal fun QuickSliders(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    DisposableEffect(Unit) { UnitLight.start(context); onDispose { } }
    val day by UnitLight.day.collectAsState()
    val night by UnitLight.night.collectAsState()
    val isNight by UnitLight.dark.collectAsState()
    val level = if (isNight) night else day
    val audio = remember { MediaVolume.audio(context) }
    val volume by rememberMusicVolume(audio)
    // Where the keys set the volume (MediaVolume) the level isn't known: no slider for it.
    val byKeys by MediaVolume.byKeys.collectAsState()
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        QuickSlider(
            stringResource(R.string.widgets_light), level, UnitLight.MIN..UnitLight.MAX,
            "${level * 100 / UnitLight.MAX} %", Modifier.weight(1f)
        ) { UnitLight.set(context, it) }
        if (!byKeys) {
            QuickSlider(
                stringResource(R.string.settings_section_volume), volume, 0..MediaVolume.max(audio),
                volume.toString(), Modifier.weight(1f)
            ) { MediaVolume.set(context, it) }
        }
    }
}

@Composable
private fun QuickSlider(label: String, value: Int, range: IntRange, shown: String, modifier: Modifier, onSet: (Int) -> Unit) {
    var dragged by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = DashColors.TextSecondary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(shown, color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            value = dragged,
            onValueChange = { dragged = it },
            onValueChangeFinished = { onSet(dragged.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = if (DashColors.Light) DashColors.Accent else Color.White, activeTrackColor = DashColors.Accent,
                inactiveTrackColor = DashColors.CardHi
            )
        )
    }
}
