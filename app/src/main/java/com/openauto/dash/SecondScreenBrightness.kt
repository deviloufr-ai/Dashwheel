package com.openauto.dash

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.openauto.dash.link.DisplayBrightness
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The second screen's backlight: the day level or the night one, sent to a
 * display wired to the monitor's buttons ([DisplayHello.brightness]) when it
 * links, at each day/night switch and when a level changes. Night is the
 * headlights on or the sun down where the car is, whatever look the
 * dashboard wears: a dark theme by day still wants a bright screen.
 */
object SecondScreenBrightness {

    private val _day = MutableStateFlow(true)
    /** Day now, for the brightness: the level the widget nudges. */
    val day: StateFlow<Boolean> = _day

    fun start(scope: CoroutineScope, context: Context) {
        val minutes = flow { while (true) { emit(System.currentTimeMillis()); delay(60_000L) } }
        scope.launch {
            combine(UnitSignals.headlightsOn, LocationFeed.location, minutes) { headlights, location, now ->
                !headlights && sunUp(now, location?.latitude, location?.longitude)
            }.distinctUntilChanged().collect { _day.value = it }
        }
        scope.launch {
            combine(DisplayLink.state, SecondScreenStore.config, _day) { link, config, isDay ->
                val connected = link as? DisplayLinkState.Connected
                if (connected?.display?.brightness == true) connected to SecondScreenRules.brightness(config, isDay) else null
            }.distinctUntilChanged().collect { wanted ->
                wanted ?: return@collect
                DisplayLink.send(DisplayBrightness(wanted.second))
            }
        }
        // Remembered, so the levels can be set while the display is away.
        scope.launch {
            DisplayLink.state.collect { link ->
                val wired = (link as? DisplayLinkState.Connected)?.display?.brightness ?: return@collect
                if (wired != SecondScreenStore.config.value.brightnessWired) SecondScreenStore.update(context) { it.copy(brightnessWired = wired) }
            }
        }
    }

    /** Moves the level in use now (day or night) by [delta]. */
    fun nudge(context: Context, delta: Int) {
        val isDay = _day.value
        SecondScreenStore.update(context) { c ->
            val level = (SecondScreenRules.brightness(c, isDay) + delta).coerceIn(DisplayBrightness.MIN, DisplayBrightness.MAX)
            if (isDay) c.copy(brightnessDay = level) else c.copy(brightnessNight = level)
        }
    }
}

/** One press of the widget's − or +: a step that shows, without dragging. */
private const val NUDGE = 5

/**
 * The widget: the level in use now (by day or at night), − and + by [NUDGE],
 * and a slider. Faded while the second screen is away; the level is kept
 * and sent when it comes back.
 */
@Composable
internal fun ScreenLightCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val config by SecondScreenStore.config.collectAsState()
    val day by SecondScreenBrightness.day.collectAsState()
    val linked by DisplayLink.state.collectAsState()
    val here = (linked as? DisplayLinkState.Connected)?.display?.brightness == true
    val level = SecondScreenRules.brightness(config, day)
    var dragged by remember(level) { mutableFloatStateOf(level.toFloat()) }

    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(DashSpace.Lg)) {
            TileHeader(stringResource(R.string.apps_kind_screen_light)) {
                Text(
                    stringResource(if (day) R.string.second_screen_light_day else R.string.second_screen_light_night),
                    color = if (here) DashColors.TextPrimary else DashColors.Muted,
                    fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge
                )
            }
            Row(modifier = Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { SecondScreenBrightness.nudge(context, -NUDGE) }, modifier = Modifier.size(DashSize.Touch)) {
                    Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.second_screen_light_down), tint = DashColors.TextPrimary, modifier = Modifier.size(24.dp))
                }
                Slider(
                    value = dragged,
                    onValueChange = { dragged = it },
                    onValueChangeFinished = { SecondScreenBrightness.nudge(context, dragged.roundToInt() - level) },
                    valueRange = DisplayBrightness.MIN.toFloat()..DisplayBrightness.MAX.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = if (DashColors.Light) DashColors.Accent else Color.White, activeTrackColor = DashColors.Accent,
                        inactiveTrackColor = if (DashColors.Glass) DashColors.well(0.35f) else DashColors.CardHi
                    ),
                    modifier = Modifier.weight(1f).alpha(if (here) 1f else 0.5f)
                )
                IconButton(onClick = { SecondScreenBrightness.nudge(context, NUDGE) }, modifier = Modifier.size(DashSize.Touch)) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.second_screen_light_up), tint = DashColors.TextPrimary, modifier = Modifier.size(24.dp))
                }
            }
            Text(
                dragged.roundToInt().toString(),
                color = if (here) DashColors.TextPrimary else DashColors.Muted,
                fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** The widget as the designs draw it: the level as a gauge, − and +. */
@Composable
internal fun screenLightFace(): WidgetFace {
    val context = LocalContext.current
    val config by SecondScreenStore.config.collectAsState()
    val day by SecondScreenBrightness.day.collectAsState()
    val linked by DisplayLink.state.collectAsState()
    val here = (linked as? DisplayLinkState.Connected)?.display?.brightness == true
    val level = SecondScreenRules.brightness(config, day)
    return WidgetFace(
        icon = if (day) Icons.Filled.LightMode else Icons.Filled.DarkMode,
        title = BuiltinKind.SCREEN_LIGHT.label,
        value = level.toString(),
        caption = stringResource(if (day) R.string.second_screen_light_day else R.string.second_screen_light_night),
        fraction = level / DisplayBrightness.MAX.toFloat(),
        active = here,
        actions = listOf(
            FaceAction(Icons.Filled.Remove, stringResource(R.string.second_screen_light_down), onClick = { SecondScreenBrightness.nudge(context, -NUDGE) }),
            FaceAction(Icons.Filled.Add, stringResource(R.string.second_screen_light_up), onClick = { SecondScreenBrightness.nudge(context, NUDGE) })
        )
    )
}
