package com.openauto.dash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.openauto.dash.vehicle.rememberDoors
import kotlin.math.cos
import kotlin.math.sin

/*
 * Panorama skin, the car on its plinth: the driver's own car (MyCarLook.kt,
 * its 3/4 picture, as a photo) standing on a lit disc of glowing elliptic
 * rings, its soft contact shadow and a faded mirror reflection under it, a
 * blue light from the plinth catching its lower body. Small callouts around
 * it say how it is: a green dot when all is well, amber when something is
 * due or wants a look. Without an imported car, a drawn MPV in profile, a
 * one-box with a long panoramic windscreen, stands in its place.
 *
 * The rings are the only part that moves (a light running slowly round the
 * outer ring, in its own small layer); the car, its shadow and reflection
 * are drawn once into an offscreen layer and composited as they are.
 */

/** The drawn car's box (100 x 40 units): its width over its height. */
internal const val PANO_CAR_ASPECT = 100f / 40f

/** How much of the car's height its reflection shows before it has faded out. */
private const val REFLECTION = 0.3f

/** One turn of the light round the plinth's ring, in ms. */
private const val RING_TURN_MS = 14_000

/** A reading pointed out beside the car: its name, its value and whether it wants a look ([attention]). */
@Immutable
internal class PanoCallout(val label: String, val value: String, val attention: Boolean)

/**
 * Where the car stands in a box of [size]: as large as fits [share] of the
 * width and the room above the plinth, its wheels on the plinth's middle
 * line low in the box, with room under it for the reflection.
 */
private fun carRect(size: Size, aspect: Float, share: Float): Rect {
    val w = size.width
    val h = size.height
    val floor = h * 0.8f
    val top = h * 0.06f
    val cw = minOf(w * share, (floor - top) * aspect)
    val ch = cw / aspect
    return Rect(Offset((w - cw) / 2f, floor - ch), Size(cw, ch))
}

/**
 * The stage filling [modifier]'s box: the plinth, the car and its
 * [callouts], up to four, at the stage's corners (doors, tyres at the top,
 * battery, service at the bottom, in the order given). On a narrow stage the
 * car takes more of the width and only the top two callouts stay.
 */
@Composable
internal fun PanoHero(callouts: List<PanoCallout>, modifier: Modifier = Modifier) {
    val mine by MyCarLook.shown.collectAsState()
    val doors = rememberDoors()
    val view = mine?.portrait
    val open = doors?.anyOpen == true
    val aspect = view?.aspect ?: PANO_CAR_ASPECT
    val said = stringResource(if (open) R.string.skin_panorama_car_open_cd else R.string.skin_panorama_car_cd)
    BoxWithConstraints(modifier.semantics { contentDescription = said }) {
        val roomy = maxWidth >= 520.dp
        val share = if (roomy) (if (view != null) 0.68f else 0.72f) else 0.86f
        PanoPlinth(aspect, share, Modifier.fillMaxSize())
        PanoCar(view, open, aspect, share, Modifier.fillMaxSize())
        val shown = if (roomy || maxHeight >= 300.dp) callouts.take(if (roomy) 4 else 2) else emptyList()
        val textSize = (maxWidth.value / 34f).coerceIn(15f, 18f)
        val maxW = if (roomy) (maxWidth * 0.3f) else (maxWidth * 0.48f)
        shown.forEachIndexed { i, c ->
            val align = when (i) {
                0 -> Alignment.TopStart
                1 -> Alignment.TopEnd
                2 -> Alignment.BottomStart
                else -> Alignment.BottomEnd
            }
            // The design's staggered spots: the second one lower than the first, the bottom ones over the plinth's ends.
            val y = when (i) {
                0 -> maxHeight * 0.03f
                1 -> maxHeight * 0.13f
                2 -> -(maxHeight * 0.14f)
                else -> -(maxHeight * 0.19f)
            }
            PanoCalloutChip(c, textSize, Modifier.align(align).offset(y = y).padding(horizontal = 6.dp).widthIn(max = maxW))
        }
    }
}

/** A callout: the status dot (green, or amber when it wants a look, with its glow at night), the name and the value. */
@Composable
private fun PanoCalloutChip(c: PanoCallout, size: Float, modifier: Modifier) {
    val dot = if (c.attention) DashColors.Warning else DashColors.Good
    val glowK = panoGlow
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Start) {
        Spacer(
            Modifier
                .size(20.dp)
                .cachedDraw(dot, glowK) {
                    val r = 5.dp.toPx()
                    val glow = Brush.radialGradient(listOf(dot.copy(alpha = 0.6f * glowK), Color.Transparent), Offset(this.size.width / 2f, this.size.height / 2f), r * 2.4f)
                    onDrawBehind {
                        if (glowK > 0f) drawCircle(glow, r * 2.4f)
                        drawCircle(dot, r)
                    }
                }
        )
        Spacer(Modifier.width(6.dp))
        PanoLine(c.label, panoText(size, DashColors.TextSecondary), Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(8.dp))
        PanoLine(c.value, panoText(size, if (c.attention) DashColors.Warning else DashColors.TextPrimary, FontWeight.Bold))
    }
}

/**
 * The plinth: a pool of light on the floor, two glowing elliptic rings under
 * the car's wheels and a brighter light running slowly round the outer one.
 * Its own layer, so each step of the light redraws the plinth alone.
 * Effects off: two still hairline rings.
 */
@Composable
private fun PanoPlinth(aspect: Float, share: Float, modifier: Modifier) {
    val lit = DashColors.Accent
    val light = DashColors.Light
    val effects = DashColors.Effects
    val glowK = DashColors.Effects.scale * if (light) 0.5f else 1f
    val turn = rememberSpin(RING_TURN_MS)
    Spacer(
        modifier
            .graphicsLayer()
            .cachedDraw(lit, light, effects, glowK, aspect, share) {
                val car = carRect(size, aspect, share)
                val c = Offset(car.center.x, car.bottom - car.height * 0.02f)
                val outerRx = car.width * 0.56f
                val outerRy = outerRx * 0.15f
                val innerRx = car.width * 0.42f
                val innerRy = innerRx * 0.14f
                val plain = effects == DashEffects.NONE
                val poolRx = car.width * 0.62f
                val pool = Brush.radialGradient(
                    0f to lit.copy(alpha = (if (light) 0.32f else 0.55f) * glowK), 0.72f to Color.Transparent,
                    center = c, radius = poolRx
                )
                val ringInk = lit.copy(alpha = if (light) 0.45f else 0.5f)
                val ringW = 2.dp.toPx()
                val glowW = 14.dp.toPx()
                val outerTl = Offset(c.x - outerRx, c.y - outerRy)
                val outerSize = Size(outerRx * 2f, outerRy * 2f)
                val innerTl = Offset(c.x - innerRx, c.y - innerRy)
                val innerSize = Size(innerRx * 2f, innerRy * 2f)
                val ringStroke = Stroke(ringW)
                val glowStroke = Stroke(glowW)
                val innerGlowStroke = Stroke(glowW * 0.7f)
                val runnerStroke = Stroke(ringW * 2f, cap = StrokeCap.Round)
                val runnerInk = if (light) lit else Color.White
                onDrawBehind {
                    if (!plain) {
                        scale(1f, outerRy * 1.6f / poolRx, c) { drawCircle(pool, poolRx, c) }
                        // The rings' glow: a wide faint stroke under each line, no blur.
                        drawOval(lit.copy(alpha = 0.16f * glowK), outerTl, outerSize, style = glowStroke)
                        drawOval(lit.copy(alpha = 0.1f * glowK), innerTl, innerSize, style = innerGlowStroke)
                    }
                    drawOval(ringInk, outerTl, outerSize, style = ringStroke)
                    drawOval(ringInk.copy(alpha = ringInk.alpha * 0.6f), innerTl, innerSize, style = ringStroke)
                    if (!plain) {
                        // A light running round the outer ring, its tail fading behind it.
                        val a = turn.value
                        drawArc(runnerInk.copy(alpha = 0.18f), a - 46f, 30f, false, outerTl, outerSize, style = runnerStroke)
                        drawArc(runnerInk.copy(alpha = 0.4f), a - 22f, 16f, false, outerTl, outerSize, style = runnerStroke)
                        drawArc(runnerInk.copy(alpha = 0.8f), a - 8f, 8f, false, outerTl, outerSize, style = runnerStroke)
                    }
                }
            }
    )
}

/**
 * The car itself with its contact shadow, its reflection under the plinth's
 * line and the plinth's blue catching its lower body, all in one offscreen
 * layer drawn only when the car or the look changes.
 */
@Composable
private fun PanoCar(view: CarView?, open: Boolean, aspect: Float, share: Float, modifier: Modifier) {
    val light = DashColors.Light
    val effects = DashColors.Effects
    val warn = DashColors.Warning
    val ink = DashColors.TextPrimary
    val lit = DashColors.Accent
    val reflection = effects != DashEffects.NONE
    Spacer(
        modifier
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .cachedDraw(view, light, effects, open, warn, ink, lit, aspect, share) {
                val car = carRect(size, aspect, share)
                val body: DrawScope.() -> Unit = {
                    if (view != null) drawCarView(view, CarLookStyle.PHOTO, car, ink, warn, open)
                    else drawPanoCar(car, light, open, warn)
                }
                val reflRect = Rect(car.left, car.bottom, car.right, car.bottom + car.height * REFLECTION)
                val reflPaint = Paint().apply { alpha = if (light) 0.12f else 0.16f }
                val reflFade = Brush.verticalGradient(0f to Color.Black, 0.75f to Color.Transparent, startY = reflRect.top, endY = reflRect.bottom)
                val shadowC = Offset(car.center.x, car.bottom - car.height * 0.03f)
                val shadowRx = car.width * 0.5f
                val shadowRy = car.height * 0.08f
                val shadow = Brush.radialGradient(
                    0f to Color.Black.copy(alpha = if (light) 0.3f else 0.8f), 0.7f to Color.Transparent,
                    center = shadowC, radius = shadowRx
                )
                // The plinth's light on the car: its lower body tinted blue, its pixels only (SrcAtop).
                val underLight = if (effects != DashEffects.NONE) Brush.verticalGradient(
                    listOf(Color.Transparent, lit.copy(alpha = (if (light) 0.12f else 0.26f) * effects.scale)),
                    car.top + car.height * 0.55f, car.bottom
                ) else null
                val topLight = if (effects != DashEffects.NONE) Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.12f * effects.scale), Color.Transparent), Offset(car.center.x, car.top), car.width * 0.55f
                ) else null
                onDrawBehind {
                    if (reflection) {
                        drawIntoCanvas { canvas ->
                            canvas.saveLayer(reflRect, reflPaint)
                            withTransform({ scale(1f, -1f, Offset(car.center.x, car.bottom)) }) { body() }
                            drawRect(reflFade, reflRect.topLeft, reflRect.size, blendMode = BlendMode.DstIn)
                            canvas.restore()
                        }
                    }
                    scale(1f, shadowRy / shadowRx, shadowC) { drawCircle(shadow, shadowRx, shadowC) }
                    body()
                    if (underLight != null) drawRect(underLight, car.topLeft, car.size, blendMode = BlendMode.SrcAtop)
                    if (topLight != null) drawRect(topLight, car.topLeft, car.size, blendMode = BlendMode.SrcAtop)
                }
            }
    )
}

// --- The drawn car ------------------------------------------------------------------

/**
 * A big MPV in profile, nose to the right, for when the driver has not
 * imported their own car: a one-box body in aluminium grey whose long
 * windscreen runs from the roof almost to the nose, a small quarter light in
 * front of the doors, deep side glass, a near upright tailgate, swept-back
 * headlamps and five-spoke wheels. Built once in a 100 x 40 box and scaled.
 */
private object PanoDrawnCar {
    val body = Path().apply {
        moveTo(3.4f, 31.5f)
        lineTo(2.6f, 25f)
        cubicTo(2.3f, 19f, 3.2f, 12f, 5.4f, 8f)
        cubicTo(6.8f, 5.4f, 9f, 4.1f, 12.5f, 3.8f)
        lineTo(57f, 3.2f)
        cubicTo(65f, 3.2f, 71f, 6.2f, 77f, 11.6f)
        cubicTo(80.5f, 14.6f, 84f, 16.4f, 88.5f, 17.5f)
        cubicTo(93.5f, 18.7f, 97.2f, 19.8f, 98.6f, 22.6f)
        cubicTo(99.5f, 24.6f, 99.5f, 28.2f, 98.7f, 31.5f)
        lineTo(87.8f, 31.5f)
        arcTo(Rect(Offset(79f, 31f), 8.8f), 0f, -180f, false)
        lineTo(28.8f, 31.5f)
        arcTo(Rect(Offset(20f, 31f), 8.8f), 0f, -180f, false)
        close()
    }

    /** The side glass, from the tailgate's window to the quarter light; its lower edge rises toward the back, as the car's does. */
    val glass = Path().apply {
        moveTo(7.6f, 9.6f)
        cubicTo(8.4f, 7f, 10f, 5.8f, 13f, 5.6f)
        lineTo(56.5f, 5f)
        cubicTo(63.5f, 5.1f, 69f, 7.8f, 74.6f, 12.6f)
        lineTo(77.6f, 15.4f)
        lineTo(62f, 16.1f)
        lineTo(24f, 16.1f)
        cubicTo(17f, 15.9f, 11f, 14.6f, 7.4f, 12.6f)
        close()
    }

    /** The windscreen seen edge-on: a dark glossy band along the body's front slope. */
    val screen = Path().apply {
        moveTo(57.5f, 3.6f)
        cubicTo(65.5f, 3.7f, 71f, 6.6f, 76.8f, 11.9f)
        cubicTo(80.4f, 15f, 84f, 16.8f, 88.2f, 17.8f)
    }
    val glare = Path().apply {
        moveTo(38f, 5.4f)
        lineTo(46f, 5.25f)
        lineTo(39.5f, 15.3f)
        lineTo(31.5f, 15.3f)
        close()
    }

    /** Pillars over the glass (body colour): the rear quarter's, the doors' and the split before the quarter light. */
    val pillars = listOf(
        floatArrayOf(22f, 5.6f, 24f, 16.2f),
        floatArrayOf(45.2f, 5.2f, 46.6f, 16.2f),
        floatArrayOf(66.8f, 8.4f, 67.9f, 16f)
    )
    val cuts = Path().apply {
        moveTo(46f, 15.6f); lineTo(46.4f, 30.2f)
        moveTo(67.3f, 15.6f); lineTo(70f, 25f)
        moveTo(23f, 15.6f); lineTo(25.2f, 26f)
    }
    val shoulder = Path().apply {
        moveTo(4f, 18.4f)
        cubicTo(30f, 17.2f, 70f, 17.4f, 96f, 20.6f)
    }
    val sill = Path().apply {
        moveTo(30f, 29.4f)
        lineTo(69f, 29.4f)
    }
    val headlamp = Path().apply {
        moveTo(88.6f, 18.5f)
        cubicTo(92f, 19.1f, 95.4f, 20f, 97.8f, 21.4f)
        lineTo(96.8f, 22.9f)
        cubicTo(94f, 22.2f, 91f, 21.2f, 88.9f, 20.3f)
        close()
    }
    val mirror = Path().apply {
        moveTo(69.2f, 13.4f)
        lineTo(72.6f, 13.1f)
        lineTo(73.2f, 15.4f)
        lineTo(69.6f, 15.6f)
        close()
    }
    val wheels = listOf(Offset(20f, 31f), Offset(79f, 31f))

    val paint = Brush.verticalGradient(
        0f to Color(0xFFE9EDF2), 0.22f to Color(0xFFBCC5D0), 0.48f to Color(0xFF858F9C), 0.74f to Color(0xFF4F5864), 1f to Color(0xFF2B313A),
        startY = 3f, endY = 33f
    )
    val glassFill = Brush.verticalGradient(listOf(Color(0xFF34445A), Color(0xFF0B1119)), startY = 5f, endY = 15.6f)
    val rim = Brush.radialGradient(listOf(Color(0xFFE6EBF1), Color(0xFF959FAC), Color(0xFF4A525E)), Offset.Zero, 5f)
}

/**
 * The drawn MPV in [dst]; [alert] traces its outline in [warn] (a door
 * open). Its colours stay those of an aluminium grey car by day and by
 * night; [light] only softens the dark trim by day.
 */
internal fun DrawScope.drawPanoCar(dst: Rect, light: Boolean, alert: Boolean = false, warn: Color = Color(0xFFFFB547)) {
    val s = dst.width / 100f
    withTransform({
        translate(dst.left, dst.top + (dst.height - 40f * s) / 2f)
        scale(s, s, Offset.Zero)
    }) {
        val hair = 1f / s * density
        drawPath(PanoDrawnCar.body, PanoDrawnCar.paint)
        drawPath(PanoDrawnCar.glass, PanoDrawnCar.glassFill)
        drawPath(PanoDrawnCar.screen, Color(0xFF16202C), style = Stroke(1.5f, cap = StrokeCap.Round))
        drawPath(PanoDrawnCar.screen, Color.White.copy(alpha = 0.35f), style = Stroke(hair * 0.8f, cap = StrokeCap.Round))
        drawPath(PanoDrawnCar.glare, Color.White.copy(alpha = 0.1f))
        for (p in PanoDrawnCar.pillars) {
            drawRect(Color(0xFF1B222C), Offset(p[0], p[1]), Size(p[2] - p[0], p[3] - p[1]))
        }
        drawPath(PanoDrawnCar.shoulder, Color.White.copy(alpha = 0.5f), style = Stroke(hair * 1.2f, cap = StrokeCap.Round))
        drawPath(PanoDrawnCar.cuts, Color(0xFF1B2028).copy(alpha = if (light) 0.4f else 0.55f), style = Stroke(hair))
        drawPath(PanoDrawnCar.sill, Color(0xFF1B2028).copy(alpha = 0.35f), style = Stroke(1.2f))
        drawPath(PanoDrawnCar.mirror, Color(0xFF3A434F))
        drawPath(PanoDrawnCar.headlamp, Color(0xFFDDE7F2))
        drawLine(Color(0xFFFFFFFF), Offset(89.6f, 19.5f), Offset(96.6f, 21.6f), 0.5f, StrokeCap.Round)
        // The tail lamp, tall along the tailgate's edge.
        drawLine(Color(0xFFE5484D), Offset(3.1f, 10.5f), Offset(2.7f, 19.5f), 1.3f, StrokeCap.Round)
        for (c in PanoDrawnCar.wheels) {
            drawCircle(Color(0xFF0A0C0F), 7.6f, c)
            translate(c.x, c.y) {
                drawCircle(PanoDrawnCar.rim, 5f, Offset.Zero)
                for (k in 0 until 5) {
                    val a = Math.toRadians(k * 72.0 - 90.0)
                    drawLine(
                        Color(0xFF3C434E), Offset.Zero,
                        Offset((cos(a) * 4.4).toFloat(), (sin(a) * 4.4).toFloat()),
                        1.1f, StrokeCap.Round
                    )
                }
                drawCircle(Color(0xFF2A3038), 1.3f, Offset.Zero)
            }
        }
        if (alert) drawPath(PanoDrawnCar.body, warn, style = Stroke(hair * 2.5f, join = StrokeJoin.Round))
    }
}

/** The hero's size at least: under it a range tile is a card, not a stage. */
internal val PANO_HERO_MIN_W: Dp = 400.dp
internal val PANO_HERO_MIN_H: Dp = 240.dp
