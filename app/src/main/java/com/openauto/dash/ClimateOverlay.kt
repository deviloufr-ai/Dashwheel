package com.openauto.dash

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.EventSeat
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/*
 * Dashwheel's climate card, in place of the car app's pop-up ([RomPopups]),
 * in the design the driver chose ([AlertStyle]): the two temperatures, the
 * fan and what's on (A/C, auto, recirculation, demist, seat heating), for a
 * few seconds after each change made with the car's own climate controls.
 */

object ClimateOverlay {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false
    private var window: AlertWindow? = null
    private var timer: Job? = null

    /** How long the card stays after the last change. */
    private const val SHOW_MS = 4_000L

    /** The climate since its last change, while the card is up; null otherwise. */
    private val shown = MutableStateFlow<Climate?>(null)

    val alert: StateFlow<Climate?> =
        combine(shown, AlertPreview.climate) { climate, preview -> preview ?: climate }
            .stateIn(scope, SharingStarted.Eagerly, null)

    val style: StateFlow<AlertStyle?> =
        combine(alert, AlertStyleStore.styles, UnitSignals.projectionOnScreen) { climate, styles, projected ->
            // Over CarPlay / Android Auto: the pill, so the projection stays usable.
            climate?.let { if (projected) AlertStyle.PILL else styles.of(AlertKind.AC) }
        }
            .stateIn(scope, SharingStarted.Eagerly, null)

    fun start(context: Context) {
        if (started) return
        started = true
        val app = AppLanguage.wrap(context.applicationContext)
        // A change, not the state the car app reports first after a start.
        scope.launch {
            CarBox.climate.filterNotNull().drop(1).collect { climate ->
                // Not over the reversing camera: a change made meanwhile is just not shown.
                if (RomPopups.Kind.AC !in RomPopups.replaced.value || CarBox.reversing.value) return@collect
                shown.value = climate
                timer?.cancel()
                timer = launch {
                    delay(SHOW_MS)
                    shown.value = null
                }
            }
        }
        scope.launch {
            CarBox.reversing.collect { if (it) shown.value = null }
        }
        scope.launch {
            style.collect { style ->
                if (style != null) {
                    val w = window ?: AlertWindow(app, "climate", AlertKind.AC.cardAt.gravity).also { window = it }
                    if (w.canShow()) w.show(style) { ClimateAlertContent(style) }
                } else {
                    window?.hide()
                }
            }
        }
    }
}

@Composable
private fun ClimateAlertContent(style: AlertStyle) {
    val live by ClimateOverlay.alert.collectAsState()
    val climate = rememberLast(live) ?: return
    ClimateAlert(climate, style)
}

@Composable
private fun ClimateAlert(c: Climate, style: AlertStyle) = when (style) {
    AlertStyle.PILL -> ClimatePill(c)
    AlertStyle.BANNER -> ClimateBanner(c)
    AlertStyle.PANEL -> ClimatePanel(c)
    else -> ClimateCard(c)
}

/** "21°", "21.5°", "LO", "HI", or a dash when the car sends none. */
private fun ClimateTemp.text(): String = when (this) {
    ClimateTemp.Low -> "LO"
    ClimateTemp.High -> "HI"
    ClimateTemp.None -> "–"
    is ClimateTemp.Degrees -> if (value % 1f == 0f) "${value.toInt()}°" else "%.1f°".format(value)
}

@Composable
private fun Temp(t: ClimateTemp, size: Int) {
    Text(t.text(), color = DashColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = size.sp)
}

/** The fan as bars, as many lit as its speed (seven shown unless the car goes higher). */
@Composable
private fun FanBars(fan: Int, height: Dp) {
    val bars = maxOf(7, fan)
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Icon(Icons.Filled.Air, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(height))
        Spacer(Modifier.width(4.dp))
        repeat(bars) { i ->
            Box(
                Modifier.width(6.dp).height(height * (0.35f + 0.65f * (i + 1) / bars))
                    .background(if (i < fan) DashColors.Accent else DashColors.CardHi, DashShape.Small)
            )
        }
    }
}

/** What's switched on: A/C, AUTO, recirculation, demist, seat heating. */
@Composable
private fun ClimateChips(c: Climate) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        TextChip("A/C", c.ac)
        TextChip("AUTO", c.auto)
        IconChip(Icons.Filled.Loop, c.recirculation)
        IconChip(Icons.Filled.Waves, c.frontDefrost || c.rearDefrost)
        if (c.seatHeatLeft > 0 || c.seatHeatRight > 0) IconChip(Icons.Filled.EventSeat, true)
    }
}

@Composable
private fun TextChip(label: String, on: Boolean) {
    Text(
        label,
        color = if (on) DashColors.OnAccent else DashColors.Muted,
        fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .background(if (on) DashColors.Accent else DashColors.CardHi, DashShape.Pill)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

@Composable
private fun IconChip(icon: ImageVector, on: Boolean) {
    Box(
        Modifier.background(if (on) DashColors.Accent else DashColors.CardHi, DashShape.Pill).padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = if (on) DashColors.OnAccent else DashColors.Muted, modifier = Modifier.size(16.dp))
    }
}

/** The middle of each design: the fan and what's on, or "Climate off". */
@Composable
private fun ClimateMiddle(c: Climate, fanHeight: Dp) {
    if (!c.power) {
        Text(stringResource(R.string.alert_ac_off), color = DashColors.TextSecondary, style = MaterialTheme.typography.titleMedium)
        return
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FanBars(c.fan, fanHeight)
        Spacer(Modifier.height(8.dp))
        ClimateChips(c)
    }
}

@Composable
private fun ClimatePill(c: Climate) {
    AlertSurface(AlertStyle.PILL) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Temp(c.left, 18)
            Spacer(Modifier.width(14.dp))
            if (c.power) FanBars(c.fan, 16.dp) else Text(stringResource(R.string.alert_ac_off), color = DashColors.TextSecondary)
            Spacer(Modifier.width(14.dp))
            Temp(c.right, 18)
        }
    }
}

@Composable
private fun ClimateCard(c: Climate) {
    AlertSurface(AlertStyle.CARD) {
        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp).widthIn(min = 360.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Temp(c.left, 34)
            Spacer(Modifier.width(24.dp))
            ClimateMiddle(c, 24.dp)
            Spacer(Modifier.width(24.dp))
            Temp(c.right, 34)
        }
    }
}

@Composable
private fun ClimateBanner(c: Climate) {
    AlertSurface(AlertStyle.BANNER) {
        Row(
            Modifier.padding(horizontal = 32.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Temp(c.left, 30)
            ClimateMiddle(c, 22.dp)
            Temp(c.right, 30)
        }
    }
}

@Composable
private fun ClimatePanel(c: Climate) {
    AlertSurface(AlertStyle.PANEL) {
        Column(
            modifier = Modifier.fillMaxSize().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            Text(
                stringResource(R.string.alert_kind_ac).uppercase(), color = DashColors.Accent, letterSpacing = 1.5.sp,
                fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Temp(c.left, 52)
                Temp(c.right, 52)
            }
            ClimateMiddle(c, 36.dp)
        }
    }
}

/** The launcher's own climate popup, for when the overlay window isn't allowed. */
@Composable
internal fun ClimateHost() {
    val climate by ClimateOverlay.alert.collectAsState()
    val style by ClimateOverlay.style.collectAsState()
    val c = climate ?: return
    val s = style ?: return
    if (android.provider.Settings.canDrawOverlays(androidx.compose.ui.platform.LocalContext.current)) return
    AlertPopup(s, AlertKind.AC.cardAt.alignment) { ClimateAlert(c, s) }
}
