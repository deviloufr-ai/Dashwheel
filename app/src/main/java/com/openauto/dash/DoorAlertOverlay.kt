package com.openauto.dash

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/*
 * Dashwheel's door alert, in place of the head unit's own ([RomPopups]), in
 * the design the driver chose ([AlertStyle]): the doors that are open, over
 * whatever app fills the screen, gone once they are all shut. The side panel
 * and the full screen draw the car from above with those doors swung open. A
 * tap hides it until another door opens. Without "display over other apps"
 * it shows inside the launcher instead ([DoorAlertHost]).
 */

object DoorAlertOverlay {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false
    private var window: AlertWindow? = null

    /** The open doors the driver tapped away: hidden until one more opens. */
    private val dismissed = MutableStateFlow<Set<String>>(emptySet())

    /** The doors to show, or null when there's no alert: the style picker's made-up ones while it is tried. */
    val alert: StateFlow<McuReader.DoorState?> =
        combine(RomPopups.replaced, McuReader.doorState, dismissed, AlertPreview.doors, CarBox.reversing) { replaced, doors, hidden, preview, reversing ->
            val open = doors?.openNames().orEmpty()
            // Held while reversing (the camera comes first), shown after if still open.
            preview ?: doors.takeIf { RomPopups.Kind.DOORS in replaced && !reversing && open.isNotEmpty() && !hidden.containsAll(open) }
        }.stateIn(scope, SharingStarted.Eagerly, null)

    /** The design the alert shows in now: null when there's none. */
    val style: StateFlow<AlertStyle?> =
        combine(alert, AlertStyleStore.styles, UnitSignals.projectionOnScreen) { doors, styles, projected ->
            // Over CarPlay / Android Auto: the pill, so the projection stays usable.
            doors?.let { if (projected) AlertStyle.PILL else styles.of(AlertKind.DOORS) }
        }
            .stateIn(scope, SharingStarted.Eagerly, null)

    private val _showing = MutableStateFlow(false)
    /** True while the overlay window is up (the launcher's popup then stays away). */
    val showing: StateFlow<Boolean> = _showing

    fun start(context: Context) {
        if (started) return
        started = true
        val app = AppLanguage.wrap(context.applicationContext)
        // All shut: the next opening shows again, even the same door.
        scope.launch {
            McuReader.doorState.collect { if (it?.anyOpen != true) dismissed.value = emptySet() }
        }
        scope.launch {
            style.collect { style ->
                if (style != null && !Settings.canDrawOverlays(app)) PipAnchor.grantOverlayPermission(app)
                if (style != null && Settings.canDrawOverlays(app)) {
                    val w = window ?: AlertWindow(app, "doors", AlertKind.DOORS.cardAt.gravity).also { window = it }
                    _showing.value = w.show(style) { DoorAlertContent(style) }
                } else {
                    window?.hide()
                    _showing.value = false
                }
            }
        }
    }

    internal fun dismiss() {
        if (AlertPreview.doors.value != null) {
            AlertPreview.stop()
            return
        }
        dismissed.value = McuReader.doorState.value?.openNames().orEmpty()
    }
}

/** The doors in [style], following [DoorAlertOverlay.alert]; the last ones stay while it animates out. */
@Composable
private fun DoorAlertContent(style: AlertStyle) {
    val live by DoorAlertOverlay.alert.collectAsState()
    val doors = rememberLast(live) ?: return
    DoorAlert(doors, style)
}

@Composable
private fun DoorAlert(doors: McuReader.DoorState, style: AlertStyle) = when (style) {
    AlertStyle.PILL -> DoorPill(doors)
    // The round designs are Gemini Live's only; never offered here.
    AlertStyle.CARD, AlertStyle.BUBBLE, AlertStyle.ICON -> DoorCard(doors)
    AlertStyle.BANNER -> DoorBanner(doors)
    AlertStyle.PANEL -> DoorPanel(doors)
    AlertStyle.FULL -> DoorFullScreen(doors)
}

/** The launcher's own door popup, for when the overlay window isn't allowed. */
@Composable
internal fun DoorAlertHost() {
    val doors by DoorAlertOverlay.alert.collectAsState()
    val style by DoorAlertOverlay.style.collectAsState()
    val overlay by DoorAlertOverlay.showing.collectAsState()
    val d = doors ?: return
    val s = style ?: return
    if (overlay) return
    AlertPopup(s, AlertKind.DOORS.cardAt.alignment) { DoorAlert(d, s) }
}

/** "Front left, Tailgate": the open ones by name. */
@Composable
private fun openDoorNames(doors: McuReader.DoorState): String = buildList {
    if (doors.frontLeft) add(stringResource(R.string.vehicle_door_front_left))
    if (doors.frontRight) add(stringResource(R.string.vehicle_door_front_right))
    if (doors.rearLeft) add(stringResource(R.string.vehicle_door_rear_left))
    if (doors.rearRight) add(stringResource(R.string.vehicle_door_rear_right))
    if (doors.tailgate) add(stringResource(R.string.vehicle_door_tailgate))
    if (doors.bonnet) add(stringResource(R.string.vehicle_door_bonnet))
}.joinToString(", ")

@Composable
private fun Modifier.hideOnTap(): Modifier {
    val tap = rememberTapFeedback()
    return clickable(role = Role.Button, onClickLabel = stringResource(R.string.dash_close)) { tap(); DoorAlertOverlay.dismiss() }
}

@Composable
private fun DoorCard(doors: McuReader.DoorState) {
    AlertSurface(AlertStyle.CARD, Modifier.hideOnTap(), tone = DashColors.Warning) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CarFromAbove(doors, Modifier.size(width = 34.dp, height = 56.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.widthIn(min = 140.dp, max = 300.dp)) {
                Text(
                    stringResource(R.string.vehicle_door_any_open), color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium
                )
                Text(openDoorNames(doors), color = DashColors.Warning, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** A capsule at the top: the least in the way. */
@Composable
private fun DoorPill(doors: McuReader.DoorState) {
    AlertSurface(AlertStyle.PILL, Modifier.hideOnTap(), tone = DashColors.Warning) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.DirectionsCar, contentDescription = null, tint = DashColors.Warning, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                openDoorNames(doors), color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.widthIn(max = 360.dp)
            )
        }
    }
}

/** A strip across the whole top of the screen. */
@Composable
private fun DoorBanner(doors: McuReader.DoorState) {
    AlertSurface(AlertStyle.BANNER, Modifier.hideOnTap(), tone = DashColors.Warning) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            AlertStripe(DashColors.Warning)
            Spacer(Modifier.width(14.dp))
            CarFromAbove(doors, Modifier.size(width = 28.dp, height = 46.dp))
            Spacer(Modifier.width(14.dp))
            Text(
                stringResource(R.string.vehicle_door_any_open), color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold,
                maxLines = 1, style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.width(16.dp))
            Text(
                openDoorNames(doors), color = DashColors.Warning, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)
            )
        }
    }
}

/** A full-height panel on the right with the car drawn large, its open doors swung out. */
@Composable
private fun DoorPanel(doors: McuReader.DoorState) {
    AlertSurface(AlertStyle.PANEL, Modifier.hideOnTap(), tone = DashColors.Warning) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                stringResource(R.string.vehicle_door_any_open).uppercase(), color = DashColors.Warning, letterSpacing = 1.5.sp,
                fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge
            )
            CarFromAbove(doors, Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp))
            Text(
                openDoorNames(doors), color = DashColors.TextPrimary, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.alert_tap_to_hide), color = DashColors.Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** The whole screen dimmed, the car large in a card in the middle; a tap anywhere hides it. */
@Composable
private fun DoorFullScreen(doors: McuReader.DoorState) {
    val tap = rememberTapFeedback()
    FullScreenModal(onTap = { tap(); DoorAlertOverlay.dismiss() }, tone = DashColors.Warning) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 40.dp, vertical = 28.dp)) {
            CarFromAbove(doors, Modifier.fillMaxHeight().width(240.dp))
            Spacer(Modifier.width(40.dp))
            Column(Modifier.widthIn(max = 460.dp)) {
                Text(
                    stringResource(R.string.vehicle_door_any_open), color = DashColors.Warning, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.displaySmall
                )
                Spacer(Modifier.height(12.dp))
                Text(openDoorNames(doors), color = DashColors.TextPrimary, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(24.dp))
                Text(stringResource(R.string.alert_tap_to_hide), color = DashColors.Muted, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * The car seen from above, nose up, fitted into the space it's given: the
 * open doors swing out on their hinges (animated as they open and shut) and
 * glow in the warning colour, the tailgate and the bonnet lift away from the
 * body.
 */
@Composable
internal fun CarFromAbove(doors: McuReader.DoorState, modifier: Modifier = Modifier) {
    fun swing(open: Boolean) = if (open) 1f else 0f
    val spec = spring<Float>(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow)
    val fl by animateFloatAsState(swing(doors.frontLeft), spec, label = "fl")
    val fr by animateFloatAsState(swing(doors.frontRight), spec, label = "fr")
    val rl by animateFloatAsState(swing(doors.rearLeft), spec, label = "rl")
    val rr by animateFloatAsState(swing(doors.rearRight), spec, label = "rr")
    val tail by animateFloatAsState(swing(doors.tailgate), spec, label = "tail")
    val bonnet by animateFloatAsState(swing(doors.bonnet), spec, label = "bonnet")

    // A translucent CardHi (the glass and skinned looks) laid on the card, not made opaque as it is: that is white.
    val body = DashColors.CardHi.compositeOver(DashColors.Card.copy(alpha = 1f))
    val edge = DashColors.TextSecondary
    // Tinted from the outline, so the windows show on light and dark themes alike.
    val glass = edge.copy(alpha = 0.24f)
    val warn = DashColors.Warning
    Canvas(modifier) {
        // Drawn in a 140 x 260 box (the car is 70 x 190 in its middle) scaled to fit.
        val s = min(size.width / VIEW_W, size.height / VIEW_H)
        translate((size.width - VIEW_W * s) / 2f, (size.height - VIEW_H * s) / 2f) {
            withTransform({ scale(s, s, Offset.Zero) }) {
                drawCar(body, glass, edge, warn, fl, fr, rl, rr, tail, bonnet)
            }
        }
    }
}

/** The car drawing's own space: 140 x 260, the car 70 x 190 in its middle (also the radar's, RadarOverlay.kt). */
internal const val VIEW_W = 140f
internal const val VIEW_H = 260f

internal fun DrawScope.drawCar(
    body: Color, glass: Color, edge: Color, warn: Color,
    fl: Float, fr: Float, rl: Float, rr: Float, tail: Float, bonnet: Float
) {
    val left = 35f
    val right = 105f
    val top = 35f
    val bottom = 225f

    // Wheels, peeking out under the body.
    val tyre = edge.copy(alpha = 0.55f)
    listOf(left - 5f to 62f, right - 3f to 62f, left - 5f to 172f, right - 3f to 172f).forEach { (x, y) ->
        drawRoundRect(tyre, Offset(x, y), Size(8f, 30f), CornerRadius(3f))
    }
    // Mirrors.
    drawOval(body, Offset(left - 14f, 94f), Size(14f, 9f))
    drawOval(body, Offset(right, 94f), Size(14f, 9f))

    // The bonnet and tailgate lift away from the body when open.
    if (bonnet > 0.01f) {
        val at = Offset(left + 4f, top - 30f * bonnet)
        val size = Size(right - left - 8f, 46f)
        drawRoundRect(warn.copy(alpha = 0.35f * bonnet), at, size, CornerRadius(14f))
        drawRoundRect(warn.copy(alpha = bonnet), at, size, CornerRadius(14f), style = Stroke(2.5f))
    }
    if (tail > 0.01f) {
        val at = Offset(left + 6f, bottom - 22f + 26f * tail)
        val size = Size(right - left - 12f, 24f)
        drawRoundRect(warn.copy(alpha = 0.35f * tail), at, size, CornerRadius(8f))
        drawRoundRect(warn.copy(alpha = tail), at, size, CornerRadius(8f), style = Stroke(2.5f))
    }

    // Body, then the glass: windscreen, roof, rear window.
    drawRoundRect(body, Offset(left, top), Size(right - left, bottom - top), CornerRadius(30f))
    drawRoundRect(edge, Offset(left, top), Size(right - left, bottom - top), CornerRadius(30f), style = Stroke(2f))
    val windscreen = Path().apply {
        moveTo(left + 8f, 96f); lineTo(right - 8f, 96f); lineTo(right - 14f, 118f); lineTo(left + 14f, 118f); close()
    }
    drawPath(windscreen, glass)
    drawRoundRect(glass.copy(alpha = glass.alpha * 0.5f), Offset(left + 14f, 122f), Size(right - left - 28f, 62f), CornerRadius(8f))
    val rearWindow = Path().apply {
        moveTo(left + 14f, 188f); lineTo(right - 14f, 188f); lineTo(right - 9f, 204f); lineTo(left + 9f, 204f); close()
    }
    drawPath(rearWindow, glass)

    // Bonnet and tailgate lines, highlighted when open.
    drawLine(if (bonnet > 0.5f) warn else edge.copy(alpha = 0.6f), Offset(left + 12f, top + 6f + 40f * (1 - bonnet)), Offset(right - 12f, top + 6f + 40f * (1 - bonnet)), strokeWidth = if (bonnet > 0.5f) 5f else 2f, cap = StrokeCap.Round)
    drawLine(if (tail > 0.5f) warn else edge.copy(alpha = 0.6f), Offset(left + 12f, bottom - 4f), Offset(right - 12f, bottom - 4f), strokeWidth = if (tail > 0.5f) 5f else 2f, cap = StrokeCap.Round)

    // The doors: hinged at their front edge, swinging out up to 60 degrees.
    door(left, 102f, 46f, -1f, fl, edge, warn)
    door(right, 102f, 46f, 1f, fr, edge, warn)
    door(left, 150f, 40f, -1f, rl, edge, warn)
    door(right, 150f, 40f, 1f, rr, edge, warn)
}

private fun DrawScope.door(hingeX: Float, hingeY: Float, length: Float, side: Float, open: Float, edge: Color, warn: Color) {
    val angle = open * 60f * PI.toFloat() / 180f
    val end = Offset(hingeX + side * length * sin(angle), hingeY + length * cos(angle))
    val hinge = Offset(hingeX, hingeY)
    if (open > 0.02f) {
        // The swept area, faint, then the door itself.
        val sweep = Path().apply {
            moveTo(hingeX, hingeY); lineTo(hingeX, hingeY + length); lineTo(end.x, end.y); close()
        }
        drawPath(sweep, warn.copy(alpha = 0.18f * open))
        drawLine(warn.copy(alpha = 0.35f), hinge, end, strokeWidth = 12f, cap = StrokeCap.Round)
        drawLine(warn, hinge, end, strokeWidth = 6f, cap = StrokeCap.Round)
    } else {
        drawLine(edge, hinge, end, strokeWidth = 4f, cap = StrokeCap.Round)
    }
}
