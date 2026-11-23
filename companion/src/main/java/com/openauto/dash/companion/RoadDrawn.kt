package com.openauto.dash.companion

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openauto.dash.roadwatch.PlacedVehicle
import com.openauto.dash.roadwatch.RoadWarning
import com.openauto.dash.roadwatch.VehicleKind
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * Road watch drawn instead of filmed (experimental, issue 179): the road seen
 * from behind and above the car, its lane, and each vehicle the camera found
 * standing where its box puts it (DrawnRoad). The lane lines run under the car
 * at its speed; the car ahead wears a ring and its distance. Nothing here is
 * measured that the camera overlay doesn't show: it is the same RoadView, drawn.
 */

private const val LANE_M = 3.5f
private const val HALF_LANE = LANE_M / 2
/** The car's own length: a vehicle [PlacedVehicle.aheadM] ahead stands that far beyond its nose. */
private const val CAR_LENGTH_M = 4.4f
/** A dash and the gap after it, metres. */
private const val DASH_M = 3f
private const val DASH_EVERY_M = 9f
private const val POST_EVERY_M = 25f
private const val GROUND_EVERY_M = 12.5f
/** How far the road is drawn. */
private const val FAR_M = 220f
/** Where the road itself ends: the horizon, for the eye. */
private const val ROAD_END_M = 100_000f
private const val SQUEEZE_M = 60f
/** Lane offset 0.25 is a wheel on the line: the car that far off its lane's middle, metres. */
private const val OFFSET_TO_M = (HALF_LANE - 0.9f) / 0.25f

private val SkyTop = Color(0xFF04071A)
private val SkyLow = Color(0xFF10244A)
private val Glow = Color(0xFF7FD8FF)
private val Ground = Color(0xFF070B14)
private val RoadNear = Color(0xFF1A2231)
private val RoadFar = Color(0xFF0E131D)
private val Cyan = Color(0xFF3FD0FF)
private val ClayTop = Color(0xFFEEF3F8)
private val ClayLow = Color(0xFF66727F)
private val Glass = Color(0xFF0E1824)
private val GlassTop = Color(0xFF3A526C)
private val Lamp = Color(0xFFFF4A3D)

/** Where the eye is, and where a point of the road lands on a [w] x [h] screen. */
private class Eye(val w: Float, val h: Float) {
    val horizon = h * 0.3f
    /** Pixels a metre at the car's tail: as a wide screen shows it, and never more than lets an upright one hold three lanes. */
    private val perM0 = min(h * 0.14f, w / (LANE_M * 3.2f))
    /** The eye this far behind the car's tail. */
    private val back = 16f
    private val f = perM0 * back
    /** As high as puts the tail at 0.86 of the height. */
    private val eyeM = h * 0.56f / perM0
    /** The nearest row drawn, a little under the screen's foot, metres ahead of the tail (negative: behind). */
    val nearM = f * eyeM / (h * 1.06f - horizon) - back

    /**
     * The road ahead drawn shorter than it is, more so the further off: true
     * to scale, a car 50 m ahead is a speck near the horizon. What is beside
     * or behind the car keeps its size.
     */
    private fun squeeze(z: Float) = if (z <= 0f) z else SQUEEZE_M * ln(1f + z / SQUEEZE_M)

    fun perM(z: Float) = f / (squeeze(z) + back)
    fun at(x: Float, z: Float) = Offset(w / 2 + x * perM(z), horizon + eyeM * perM(z))
}

/** What moves between frames: the road under the car, each vehicle easing to where it was last seen. */
private class Motion {
    var last = 0L
    var travelled = 0f
    /** By vehicle: metres ahead, metres aside, as drawn now. */
    val shown = HashMap<Int, FloatArray>()
    var carX = 0f
}

/** The drawn road for [view], with the speed large over it. */
@Composable
internal fun RoadDrawn(view: RoadView, modifier: Modifier = Modifier) {
    val now by produceState(0L) { while (true) withFrameNanos { value = it } }
    val motion = remember { Motion() }
    val measurer = rememberTextMeasurer()
    val distance = view.lead?.let { stringResource(R.string.road_lead_m, it.distanceM.roundToInt()) }
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val dt = if (motion.last == 0L) 0f else ((now - motion.last) / 1e9f).coerceIn(0f, 0.1f)
            motion.last = now
            motion.travelled = (motion.travelled + (view.speedKmh ?: 0f) / 3.6f * dt) % (DASH_EVERY_M * POST_EVERY_M)
            val ease = 1f - exp(-dt * 8f)
            motion.carX += ((view.lane.offset ?: 0f) * OFFSET_TO_M - motion.carX) * ease
            val placed = view.placed.mapIndexed { i, v ->
                // An untracked box has no number of its own: its place in the list stands in for it.
                val key = if (v.id != 0) v.id else -1 - i
                val cur = motion.shown.getOrPut(key) { floatArrayOf(v.aheadM, v.sideM) }
                cur[0] += (v.aheadM - cur[0]) * ease
                cur[1] += (v.sideM - cur[1]) * ease
                key to v.copy(aheadM = cur[0], sideM = cur[1])
            }
            motion.shown.keys.retainAll(placed.map { it.first }.toSet())
            val eye = Eye(size.width, size.height)
            val pulse = 0.5f + 0.5f * sin(now / 1e9f * 2f * PI.toFloat() / 1.6f)
            scenery(eye)
            road(eye, motion.travelled, view, pulse)
            for ((_, v) in placed) vehicle(eye, v, motion.carX, view.warning, pulse, measurer, distance)
            ownCar(eye, motion.carX)
            // The edges fall away into the dark, so the eye stays on the road.
            drawRect(Brush.radialGradient(listOf(Color.Transparent, Color(0xCC03050C)), Offset(size.width / 2, size.height * 0.55f), size.maxDimension * 0.75f))
        }
        view.speedKmh?.let { kmh ->
            Column(Modifier.align(Alignment.CenterStart).padding(start = 24.dp)) {
                Text(kmh.roundToInt().toString(), color = Color.White, fontSize = 72.sp, fontWeight = FontWeight.SemiBold, lineHeight = 72.sp)
                Text("km/h", color = CompanionColors.Muted, fontSize = 16.sp)
            }
        }
    }
}

private fun DrawScope.scenery(eye: Eye) {
    drawRect(Brush.verticalGradient(listOf(SkyTop, SkyLow), 0f, eye.horizon), size = Size(size.width, eye.horizon))
    drawRect(Ground, Offset(0f, eye.horizon), Size(size.width, size.height - eye.horizon))
    // The last light on the horizon, where the road goes.
    val sun = Offset(size.width / 2, eye.horizon)
    drawRect(Brush.radialGradient(listOf(Glow.copy(alpha = 0.6f), Color(0x334682FF), Color.Transparent), sun, size.width * 0.6f), size = Size(size.width, eye.horizon))
    drawRect(Brush.verticalGradient(listOf(Glow.copy(alpha = 0.3f), Color.Transparent), eye.horizon, eye.horizon + size.height * 0.16f), Offset(0f, eye.horizon), Size(size.width, size.height * 0.16f))
}

private fun DrawScope.quad(a: Offset, b: Offset, c: Offset, d: Offset, brush: Brush) {
    drawPath(Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); lineTo(d.x, d.y); close() }, brush)
}

/** A strip of the road from [x0] to [x1] across and [z0] to [z1] ahead. */
private fun DrawScope.strip(eye: Eye, x0: Float, x1: Float, z0: Float, z1: Float, brush: Brush) =
    quad(eye.at(x0, z0), eye.at(x1, z0), eye.at(x1, z1), eye.at(x0, z1), brush)

private fun DrawScope.road(eye: Eye, travelled: Float, view: RoadView, pulse: Float) {
    val near = eye.nearM
    val edge = LANE_M * 1.5f
    val nearY = eye.at(0f, near).y
    // Faint lines across the ground, going by with the road: the land beside it has depth too.
    var gz = near - (near + travelled).mod(GROUND_EVERY_M)
    while (gz < FAR_M) {
        if (gz > near) {
            val y = eye.at(0f, gz).y
            drawLine(Glow.copy(alpha = 0.07f * (1f - gz / FAR_M)), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5f)
        }
        gz += GROUND_EVERY_M
    }
    strip(eye, -edge - 0.6f, edge + 0.6f, near, ROAD_END_M, Brush.verticalGradient(listOf(RoadFar, RoadNear), eye.horizon, nearY))
    // The car's own lane, lit ahead of it as far as the vehicle it follows.
    val reach = view.lead?.let { CAR_LENGTH_M + it.distanceM } ?: 70f
    strip(
        eye, -HALF_LANE + 0.15f, HALF_LANE - 0.15f, CAR_LENGTH_M, reach,
        Brush.verticalGradient(listOf(Color.Transparent, Cyan.copy(alpha = 0.26f)), eye.at(0f, reach).y, eye.at(0f, CAR_LENGTH_M).y)
    )
    val warn = view.warning
    for (side in intArrayOf(-1, 1)) {
        val x = side * HALF_LANE
        val leaving = (side < 0 && warn == RoadWarning.LANE_LEFT) || (side > 0 && warn == RoadWarning.LANE_RIGHT)
        val seen = if (side < 0) view.lane.left != null else view.lane.right != null
        val colour = if (leaving) CompanionColors.Amber else Color.White
        if (leaving) strip(
            eye, x - 0.7f, x + 0.7f, near, 45f,
            Brush.verticalGradient(listOf(Color.Transparent, CompanionColors.Amber.copy(alpha = 0.2f + 0.25f * pulse)), eye.at(0f, 45f).y, nearY)
        )
        // A line the camera doesn't see is drawn faint: the lane is assumed there, not read.
        dashes(eye, x, travelled, colour, if (seen || leaving) 1f else 0.35f)
        // The lanes beside, and the road's edges.
        dashes(eye, x + side * LANE_M, travelled, Color.White, 0f, solid = 0.4f)
    }
    // Posts along both sides, going by at the car's speed.
    var z = near - (near + travelled).mod(POST_EVERY_M)
    while (z < FAR_M) {
        if (z > near) for (side in intArrayOf(-1, 1)) {
            val foot = eye.at(side * (edge + 1.4f), z)
            val m = eye.perM(z)
            val fade = (1f - z / FAR_M).coerceIn(0f, 1f)
            drawLine(Color.White.copy(alpha = 0.35f * fade), foot, Offset(foot.x, foot.y - 1.1f * m), strokeWidth = max(1f, 0.1f * m))
            drawCircle(CompanionColors.Amber.copy(alpha = 0.9f * fade), max(1f, 0.09f * m), Offset(foot.x, foot.y - 0.95f * m))
        }
        z += POST_EVERY_M
    }
}

/** A lane line [x] metres off the lane's middle: dashes running by, or with [solid] an unbroken line that faint. */
private fun DrawScope.dashes(eye: Eye, x: Float, travelled: Float, colour: Color, strength: Float, solid: Float = 0f) {
    val near = eye.nearM
    if (solid > 0f) {
        strip(eye, x - 0.06f, x + 0.06f, near, ROAD_END_M, Brush.verticalGradient(listOf(Color.Transparent, colour.copy(alpha = solid)), eye.horizon, eye.at(0f, near).y))
        return
    }
    var z = near - (near + travelled).mod(DASH_EVERY_M)
    while (z < FAR_M) {
        val z0 = max(z, near)
        val z1 = z + DASH_M
        if (z1 > near) {
            val fade = (1f - z0 / FAR_M).coerceIn(0f, 1f) * strength
            strip(eye, x - 0.22f, x + 0.22f, z0, z1, SolidColor(colour.copy(alpha = 0.16f * fade)))
            strip(eye, x - 0.08f, x + 0.08f, z0, z1, SolidColor(colour.copy(alpha = fade)))
        }
        z += DASH_EVERY_M
    }
}

private fun DrawScope.vehicle(eye: Eye, v: PlacedVehicle, carX: Float, warn: RoadWarning?, pulse: Float, measurer: TextMeasurer, distance: String?) {
    val z = CAR_LENGTH_M + v.aheadM
    val foot = eye.at(carX + v.sideM, z)
    val m = eye.perM(z)
    // Far off it comes out of the haze.
    val fade = ((FAR_M * 0.6f - v.aheadM) / 30f).coerceIn(0f, 1f)
    if (fade == 0f) return
    if (v.lead) {
        val tone = if (warn == RoadWarning.COLLISION || warn == RoadWarning.TOO_CLOSE) CompanionColors.Red else Cyan
        val rw = v.kind.widthM * m * (1.25f + 0.2f * pulse)
        val ring = Offset(foot.x - rw / 2, foot.y - rw * 0.16f)
        val ringSize = Size(rw, rw * 0.32f)
        drawOval(tone.copy(alpha = 0.22f * (1f - pulse * 0.6f)), ring, ringSize, style = Stroke(max(6f, 0.3f * m)))
        drawOval(tone.copy(alpha = 1f - pulse * 0.5f), ring, ringSize, style = Stroke(max(2f, 0.07f * m)))
    }
    rear(v.kind, foot, m, fade, lampGlow = 0.7f)
    if (v.lead && distance != null) {
        val text = measurer.measure(distance, TextStyle(color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold))
        val pad = 10.dp.toPx()
        val boxW = text.size.width + pad * 2
        val boxH = text.size.height + pad
        // Under the ring, on the car's own lit lane: above it, it would hide the vehicles further on.
        val top = foot.y + v.kind.widthM * m * 0.3f + 6.dp.toPx()
        val corner = CornerRadius(12.dp.toPx())
        drawRoundRect(Color(0xCC08141E), Offset(foot.x - boxW / 2, top), Size(boxW, boxH), corner)
        drawRoundRect(Cyan.copy(alpha = 0.8f), Offset(foot.x - boxW / 2, top), Size(boxW, boxH), corner, style = Stroke(1.5.dp.toPx()))
        drawText(text, topLeft = Offset(foot.x - text.size.width / 2f, top + pad / 2))
    }
}

/** The driver's own car, from behind, its lamps lit and its beam on the road ahead. */
private fun DrawScope.ownCar(eye: Eye, carX: Float) {
    val foot = eye.at(carX, 0f)
    val m = eye.perM(0f)
    drawOval(
        Brush.radialGradient(listOf(Cyan.copy(alpha = 0.28f), Color.Transparent), Offset(foot.x, foot.y), 1.9f * m),
        Offset(foot.x - 1.9f * m, foot.y - 0.5f * m), Size(3.8f * m, 1f * m)
    )
    rear(VehicleKind.CAR, foot, m, 1f, lampGlow = 1f)
}

/**
 * A [kind] seen from behind, its wheels on [foot], [m] pixels a metre: soft
 * clay shapes with their lamps lit, as a toy on the road rather than a
 * picture of any real car.
 */
private fun DrawScope.rear(kind: VehicleKind, foot: Offset, m: Float, alpha: Float, lampGlow: Float) {
    val w = kind.widthM * m
    val left = foot.x - w / 2
    val clay = Brush.verticalGradient(listOf(ClayTop.copy(alpha = alpha), ClayLow.copy(alpha = alpha)), foot.y - kind.heightM * m, foot.y)
    drawOval(Color.Black.copy(alpha = 0.55f * alpha), Offset(left - w * 0.1f, foot.y - 0.14f * m), Size(w * 1.2f, 0.34f * m))
    fun lamp(cx: Float, cy: Float, lw: Float, lh: Float) {
        drawCircle(Brush.radialGradient(listOf(Lamp.copy(alpha = 0.55f * lampGlow * alpha), Color.Transparent), Offset(cx, cy), lw * 1.3f), lw * 1.3f, Offset(cx, cy))
        drawRoundRect(Lamp.copy(alpha = alpha), Offset(cx - lw / 2, cy - lh / 2), Size(lw, lh), CornerRadius(lh / 2))
    }
    when (kind) {
        VehicleKind.MOTORCYCLE, VehicleKind.BICYCLE -> {
            val wheel = 0.2f * m
            drawRoundRect(Color(0xFF07090C).copy(alpha = alpha), Offset(foot.x - wheel / 2, foot.y - 0.66f * m), Size(wheel, 0.66f * m), CornerRadius(wheel / 2))
            drawRoundRect(clay, Offset(foot.x - 0.3f * m, foot.y - 1.25f * m), Size(0.6f * m, 0.8f * m), CornerRadius(0.26f * m))
            drawCircle(ClayTop.copy(alpha = alpha), 0.17f * m, Offset(foot.x, foot.y - 1.4f * m))
            if (kind == VehicleKind.MOTORCYCLE) lamp(foot.x, foot.y - 0.62f * m, 0.2f * m, 0.12f * m)
        }
        VehicleKind.TRUCK, VehicleKind.BUS -> {
            val h = kind.heightM * m
            val tyre = Size(0.5f * m, 0.5f * m)
            drawRoundRect(Color(0xFF07090C).copy(alpha = alpha), Offset(left + 0.1f * m, foot.y - 0.5f * m), tyre, CornerRadius(0.08f * m))
            drawRoundRect(Color(0xFF07090C).copy(alpha = alpha), Offset(left + w - 0.6f * m, foot.y - 0.5f * m), tyre, CornerRadius(0.08f * m))
            drawRoundRect(clay, Offset(left, foot.y - h), Size(w, h - 0.4f * m), CornerRadius(0.12f * m))
            if (kind == VehicleKind.BUS) {
                drawRoundRect(Glass.copy(alpha = alpha), Offset(left + 0.2f * m, foot.y - h + 0.35f * m), Size(w - 0.4f * m, 0.9f * m), CornerRadius(0.1f * m))
            } else {
                // The two doors of the box.
                val seam = Color(0xFF56606C).copy(alpha = 0.7f * alpha)
                drawRoundRect(seam, Offset(left + 0.14f * m, foot.y - h + 0.16f * m), Size(w - 0.28f * m, h - 0.75f * m), CornerRadius(0.05f * m), style = Stroke(max(1f, 0.035f * m)))
                drawLine(seam, Offset(foot.x, foot.y - h + 0.16f * m), Offset(foot.x, foot.y - 0.6f * m), strokeWidth = max(1f, 0.035f * m))
            }
            drawRoundRect(Color(0xFF333B45).copy(alpha = alpha), Offset(left, foot.y - 0.62f * m), Size(w, 0.24f * m), CornerRadius(0.05f * m))
            lamp(left + 0.36f * m, foot.y - 0.5f * m, 0.46f * m, 0.14f * m)
            lamp(left + w - 0.36f * m, foot.y - 0.5f * m, 0.46f * m, 0.14f * m)
        }
        VehicleKind.CAR -> {
            val sill = foot.y - 0.2f * m
            val waist = sill - 0.68f * m
            val roof = foot.y - kind.heightM * m
            val tyre = Size(0.3f * m, 0.36f * m)
            drawRoundRect(Color(0xFF07090C).copy(alpha = alpha), Offset(left + 0.06f * m, foot.y - 0.36f * m), tyre, CornerRadius(0.07f * m))
            drawRoundRect(Color(0xFF07090C).copy(alpha = alpha), Offset(left + w - 0.36f * m, foot.y - 0.36f * m), tyre, CornerRadius(0.07f * m))
            fun cabin(inset: Float, top: Float, bottom: Float) = Path().apply {
                moveTo(left + inset * w, bottom); lineTo(left + (inset + 0.09f) * w, top)
                lineTo(left + (1f - inset - 0.09f) * w, top); lineTo(left + (1f - inset) * w, bottom); close()
            }
            drawPath(cabin(0.1f, roof, waist + 0.1f * m), clay)
            drawPath(cabin(0.2f, roof + 0.14f * m, waist + 0.02f * m), Brush.verticalGradient(listOf(GlassTop.copy(alpha = alpha), Glass.copy(alpha = alpha)), roof, waist))
            drawRoundRect(clay, Offset(left, waist), Size(w, sill - waist), CornerRadius(0.2f * m))
            // The light along the shoulder, and the bumper under it.
            drawRoundRect(Color.White.copy(alpha = 0.5f * alpha), Offset(left + 0.08f * m, waist + 0.02f * m), Size(w - 0.16f * m, 0.06f * m), CornerRadius(0.03f * m))
            drawRoundRect(Color(0xFF3D4651).copy(alpha = alpha), Offset(left + 0.06f * m, sill - 0.16f * m), Size(w - 0.12f * m, 0.16f * m), CornerRadius(0.08f * m))
            drawRoundRect(Color(0xFFF2F5F8).copy(alpha = 0.85f * alpha), Offset(foot.x - 0.2f * m, waist + 0.34f * m), Size(0.4f * m, 0.12f * m), CornerRadius(0.02f * m))
            lamp(left + 0.3f * m, waist + 0.2f * m, 0.42f * m, 0.13f * m)
            lamp(left + w - 0.3f * m, waist + 0.2f * m, 0.42f * m, 0.13f * m)
        }
    }
}
