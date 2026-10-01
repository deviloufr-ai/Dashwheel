package com.openauto.dash

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.min

/*
 * Dashwheel's parking radar, in place of the car app's ([RomPopups]), in the
 * design the driver chose ([AlertStyle]; never full screen, it would hide the
 * reversing camera): the car from above with an arc per sensor, closer to the
 * car and redder as something gets near. Up while reversing, or while any
 * sensor sees something; gone a second after. Drawn through the accessibility
 * service, the only window Dashwheel can put above the ROM's reversing camera
 * ([AlertWindow]).
 */

object RadarOverlay {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false
    private var window: AlertWindow? = null
    private var hiding: Job? = null

    /** A second without anything near before the radar goes, so it doesn't flicker. */
    private const val LINGER_MS = 1_000L

    /** The sensors to show, or null when there's no radar up: the picker's made-up ones while it is tried. */
    val alert: StateFlow<Radar?> =
        combine(RomPopups.replaced, CarBox.radar, CarBox.reversing, AlertPreview.radar, ReverseView.covering) { replaced, radar, reversing, preview, reverseView ->
            // The reverse view has its own radar.
            preview ?: radar.takeIf {
                !reverseView && RomPopups.Kind.RADAR in replaced && it != null && it.present && (reversing || it.active)
            }
        }.stateIn(scope, SharingStarted.Eagerly, null)

    val style: StateFlow<AlertStyle?> =
        combine(alert, AlertStyleStore.styles) { radar, styles -> radar?.let { styles.of(AlertKind.RADAR) } }
            .stateIn(scope, SharingStarted.Eagerly, null)

    fun start(context: Context) {
        if (started) return
        started = true
        val app = AppLanguage.wrap(context.applicationContext)
        scope.launch {
            style.collect { style ->
                if (style != null) {
                    hiding?.cancel()
                    val w = window ?: AlertWindow(app, "radar", AlertKind.RADAR.cardAt.gravity, aboveCamera = { true }).also { window = it }
                    if (w.canShow()) w.show(style) { RadarAlertContent(style) }
                } else if (hiding?.isActive != true) {
                    hiding = launch {
                        delay(LINGER_MS)
                        window?.hide()
                    }
                }
            }
        }
    }
}

@Composable
private fun RadarAlertContent(style: AlertStyle) {
    val live by RadarOverlay.alert.collectAsState()
    val radar = rememberLast(live) ?: return
    RadarAlert(radar, style)
}

@Composable
private fun RadarAlert(radar: Radar, style: AlertStyle) = when (style) {
    AlertStyle.PILL -> RadarPill(radar)
    AlertStyle.PANEL -> RadarPanel(radar)
    else -> RadarCard(radar)
}

/** A level's colour: red when close, amber, then green far away; faint when clear. */
@Composable
private fun levelColor(level: Int?): Color = when {
    level == null || level !in 1..Radar.MAX_LEVEL -> DashColors.Muted.copy(alpha = 0.25f)
    level <= 3 -> DashColors.Critical
    level <= 6 -> DashColors.Warning
    else -> DashColors.Good
}

/** The nearest side, for the pill: the rear when reversing and both see something. */
@Composable
private fun nearestLabel(radar: Radar): String {
    fun min(l: List<Int?>) = l.filterNotNull().filter { it in 1..Radar.MAX_LEVEL }.minOrNull() ?: Int.MAX_VALUE
    return stringResource(if (min(radar.front) < min(radar.rear)) R.string.alert_radar_front else R.string.alert_radar_rear)
}

@Composable
private fun RadarPill(radar: Radar) {
    val closest = radar.closest
    AlertSurface(AlertStyle.PILL, tone = levelColor(closest)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Sensors, contentDescription = null, tint = levelColor(closest), modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                if (closest != null) nearestLabel(radar) else stringResource(R.string.alert_radar_title),
                color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.width(12.dp))
            // Fuller as something gets closer.
            val fill = if (closest == null) 0f else (Radar.MAX_LEVEL + 1 - closest) / Radar.MAX_LEVEL.toFloat()
            val bar = if (alertSquare) DashShape.Small else DashShape.Pill
            Box(Modifier.width(90.dp).height(8.dp).background(DashColors.CardHi, bar)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(fill).background(levelColor(closest), bar))
            }
        }
    }
}

@Composable
private fun RadarCard(radar: Radar) {
    AlertSurface(AlertStyle.CARD, tone = levelColor(radar.closest)) {
        Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            RadarFromAbove(radar, Modifier.size(width = 130.dp, height = 240.dp))
        }
    }
}

@Composable
private fun RadarPanel(radar: Radar) {
    AlertSurface(AlertStyle.PANEL, tone = levelColor(radar.closest)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                stringResource(R.string.alert_radar_title).uppercase(), color = DashColors.Accent, letterSpacing = 1.5.sp,
                fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge
            )
            RadarFromAbove(radar, Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp))
            if (radar.closest != null) {
                Text(nearestLabel(radar), color = levelColor(radar.closest), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall)
            }
        }
    }
}

/**
 * The car from above (doors shut) with an arc for each sensor it has: front
 * sensors above, rear below, left to right. An arc sits closer to the bumper
 * and turns red as its sensor's level drops towards 1 (closest).
 */
@Composable
internal fun RadarFromAbove(radar: Radar, modifier: Modifier = Modifier) {
    // A translucent CardHi (the glass and skinned looks) laid on the card, not made opaque as it is: that is white.
    val body = DashColors.CardHi.compositeOver(DashColors.Card.copy(alpha = 1f))
    val edge = DashColors.TextSecondary
    val glass = edge.copy(alpha = 0.24f)
    val warn = DashColors.Warning
    val front = radar.front.map { it to levelColor(it) }
    val rear = radar.rear.map { it to levelColor(it) }
    Canvas(modifier) {
        val s = min(size.width / VIEW_W, size.height / VIEW_H)
        translate((size.width - VIEW_W * s) / 2f, (size.height - VIEW_H * s) / 2f) {
            withTransform({ scale(s, s, Offset.Zero) }) {
                drawCar(body, glass, edge, warn, 0f, 0f, 0f, 0f, 0f, 0f)
                sensorArcs(front, center = Offset(70f, 100f), from = 215f, reach = 1f)
                sensorArcs(rear, center = Offset(70f, 160f), from = 145f, reach = -1f)
            }
        }
    }
}

/**
 * One arc per sensor around [center], left to right: the front ones sweep
 * clockwise from [from] over the top ([reach] 1), the rear ones the other
 * way under the car (-1).
 */
private fun DrawScope.sensorArcs(sensors: List<Pair<Int?, Color>>, center: Offset, from: Float, reach: Float) {
    // Narrow enough that the outermost arcs stay inside the 140-wide drawing.
    val span = 110f
    val step = span / sensors.size
    sensors.forEachIndexed { i, (level, color) ->
        if (level == null) return@forEachIndexed
        val near = level in 1..Radar.MAX_LEVEL
        // Closest (1) hugs the bumper; far (10) and clear sit at the outside.
        val radius = if (near) 66f + (level - 1) * 2.5f else 90f
        val start = if (reach > 0) from + i * step + 1.5f else from - (i + 1) * step + 1.5f
        drawArc(
            color, startAngle = start, sweepAngle = step - 3f, useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius), size = Size(radius * 2, radius * 2),
            style = Stroke(width = if (near) 9f else 5f)
        )
    }
}

/** The launcher's own radar popup, for when neither overlay window is possible. */
@Composable
internal fun RadarHost() {
    val radar by RadarOverlay.alert.collectAsState()
    val style by RadarOverlay.style.collectAsState()
    val r = radar ?: return
    val s = style ?: return
    if (SplitAccessibilityService.isConnected || android.provider.Settings.canDrawOverlays(androidx.compose.ui.platform.LocalContext.current)) return
    AlertPopup(s, AlertKind.RADAR.cardAt.alignment) { RadarAlert(r, s) }
}
