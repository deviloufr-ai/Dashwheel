package com.openauto.dash

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
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

/*
 * Simple skin, the showroom: the driver's own car (MyCarLook.kt, its 3/4
 * picture, as a photo whatever the car tiles' style, since the room lights a
 * real car) standing on the floor in a warm spotlight, with its contact
 * shadow and a faded mirror reflection on the floor. Without an imported car,
 * a drawn silver car in profile stands in its place.
 *
 * One car per screen: the tiles that could show it (fuel and range first,
 * then telemetry, then speed) put themselves forward while their page is on
 * screen ([rememberSimpleCarHost]), and only the first in that order draws it.
 */

/** Which tile gets the car when several could show it: the highest number. */
internal const val SIMPLE_CAR_RANGE = 3
internal const val SIMPLE_CAR_TELEMETRY = 2
internal const val SIMPLE_CAR_SPEED = 1

/** The tiles on screen now that have room for the car, with their priority. */
private object Showroom {
    val hosts = mutableStateMapOf<Any, Int>()
}

/**
 * Whether this tile draws the car: it [fits], its page is on screen, and no
 * tile with a higher priority (or an equal one put forward first) on screen
 * wants it too. Before its own entry lands, a tile says yes when nothing
 * on screen outranks it, so a lone car never blinks in a frame late.
 */
@Composable
internal fun rememberSimpleCarHost(priority: Int, fits: Boolean): Boolean {
    val token = remember { Any() }
    val wants = fits && LocalPageActive.current
    DisposableEffect(token, wants, priority) {
        if (wants) Showroom.hosts[token] = priority
        onDispose { Showroom.hosts.remove(token) }
    }
    if (!wants) return false
    var best: Any? = null
    var bestPriority = Int.MIN_VALUE
    var bestId = 0
    for ((t, p) in Showroom.hosts) {
        val id = System.identityHashCode(t)
        if (p > bestPriority || (p == bestPriority && id < bestId)) {
            best = t
            bestPriority = p
            bestId = id
        }
    }
    return if (token in Showroom.hosts) best === token else best == null || priority > bestPriority
}

/** Drawn car's box (100 x 42 units): its width over its height. */
private const val DRAWN_ASPECT = 100f / 42f

/** How much of the car's height its reflection shows before it has faded out. */
private const val REFLECTION = 0.38f

/**
 * Where the car stands in a box of [size]: as large as fits whole, its wheels
 * on a floor line low in the box, with room under it for the reflection (none
 * with effects off, so the car grows into that room).
 */
private fun carRect(size: Size, aspect: Float, reflection: Boolean): Rect {
    val w = size.width
    val h = size.height
    val floor = h * if (reflection) 0.8f else 0.94f
    val top = h * 0.06f
    val areaW = w * 0.92f
    val areaH = floor - top
    val cw = minOf(areaW, areaH * aspect)
    val ch = cw / aspect
    return Rect(Offset((w - cw) / 2f, floor - ch), Size(cw, ch))
}

/**
 * The car in its showroom, filling [modifier]'s box. The spotlight is its own
 * small layer, the only part that moves (it breathes with the room's light);
 * the car, its shadow and its reflection are drawn once into a layer of their
 * own (an offscreen one, which the reflection's fade needs anyway) and
 * composited as they are on every other frame.
 */
@Composable
internal fun SimpleShowroomCar(modifier: Modifier) {
    val mine by MyCarLook.shown.collectAsState()
    val doors by McuReader.doorState.collectAsState()
    val view = mine?.portrait
    val open = doors?.anyOpen == true
    val light = DashColors.Light
    val effects = DashColors.Effects
    val warn = DashColors.Warning
    val ink = DashColors.TextPrimary
    val breath = rememberSimpleBreath()
    val aspect = view?.aspect ?: DRAWN_ASPECT
    val reflection = effects != DashEffects.NONE
    val said = stringResource(if (open) R.string.skin_simple_car_open_cd else R.string.skin_simple_car_cd)
    Box(modifier.semantics { contentDescription = said }) {
        if (effects != DashEffects.NONE) {
            Spacer(
                Modifier
                    .matchParentSize()
                    .cachedDraw(light, effects, aspect) {
                        val car = carRect(size, aspect, reflection)
                        val warm = if (light) Color.White.copy(alpha = 0.95f) else SimpleWarmLight.copy(alpha = 0.2f)
                        val beamC = Offset(car.center.x, car.top + car.height * 0.15f)
                        val beamRx = car.width * 0.78f
                        val beamRy = car.height * 1.15f
                        val beam = Brush.radialGradient(0f to warm, 0.65f to warm.copy(alpha = 0f), center = beamC, radius = beamRx)
                        val floorC = Offset(car.center.x, car.bottom)
                        val floorRx = car.width * 0.72f
                        val floorRy = car.height * 0.2f
                        val floorWarm = if (light) Color.White.copy(alpha = 0.8f) else SimpleWarmLight.copy(alpha = 0.12f)
                        val floorLight = Brush.radialGradient(listOf(floorWarm, floorWarm.copy(alpha = 0f)), floorC, floorRx)
                        val glow = effects.scale
                        onDrawBehind {
                            val a = glow * (0.55f + 0.35f * breath.value) / 0.9f
                            scale(1f, beamRy / beamRx, beamC) { drawCircle(beam, beamRx, beamC, alpha = a) }
                            scale(1f, floorRy / floorRx, floorC) { drawCircle(floorLight, floorRx, floorC, alpha = a) }
                        }
                    }
            )
        }
        Spacer(
            Modifier
                .matchParentSize()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .cachedDraw(view, light, effects, open, warn, ink, aspect) {
                    val car = carRect(size, aspect, reflection)
                    val body: DrawScope.() -> Unit = {
                        if (view != null) drawCarView(view, CarLookStyle.PHOTO, car, ink, warn, open)
                        else drawSimpleCar(car, light, open, warn)
                    }
                    // The reflection: the car upside down under the floor line, faint, fading out downward.
                    val reflRect = Rect(car.left, car.bottom, car.right, car.bottom + car.height * REFLECTION)
                    val reflPaint = Paint().apply { alpha = if (light) 0.12f else 0.17f }
                    val reflFade = Brush.verticalGradient(listOf(Color.Black, Color.Transparent), reflRect.top, reflRect.bottom)
                    // The shadow under the car: a wide soft one and a dark contact line under the wheels.
                    val shadowC = Offset(car.center.x, car.bottom - car.height * 0.02f)
                    val shadowRx = car.width * 0.56f
                    val shadowRy = car.height * 0.09f
                    val shadow = Brush.radialGradient(
                        0f to Color.Black.copy(alpha = if (light) 0.32f else 0.85f), 0.7f to Color.Transparent,
                        center = shadowC, radius = shadowRx
                    )
                    val contactRx = car.width * 0.42f
                    val contact = Brush.radialGradient(
                        0f to Color.Black.copy(alpha = if (light) 0.35f else 0.7f), 0.6f to Color.Transparent,
                        center = shadowC, radius = contactRx
                    )
                    // The spotlight's catch on the car itself: lights its pixels only (SrcAtop), brightest on the roof.
                    val catch = if (effects != DashEffects.NONE) {
                        val c = if (light) Color.White else SimpleWarmLight
                        Brush.radialGradient(
                            listOf(c.copy(alpha = 0.16f * effects.scale), Color.Transparent),
                            Offset(car.center.x, car.top), car.width * 0.6f
                        )
                    } else null
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
                        scale(1f, shadowRy * 0.4f / contactRx, shadowC) { drawCircle(contact, contactRx, shadowC) }
                        body()
                        if (catch != null) drawRect(catch, car.topLeft, car.size, blendMode = BlendMode.SrcAtop)
                    }
                }
        )
    }
}

// --- The drawn car ------------------------------------------------------------------

/**
 * A silver car in profile, nose left, for when the driver has not imported
 * their own: painted body with a highlight along the shoulder, dark glass
 * with a glare, wheels with rims, lamps. Shapes are built once in a 100 x 42
 * box and scaled into place.
 */
private object DrawnCar {
    val body = Path().apply {
        moveTo(3f, 30f)
        lineTo(3f, 24.5f)
        cubicTo(3.5f, 21f, 8f, 19.5f, 16f, 18.5f)
        lineTo(27f, 17.5f)
        cubicTo(32f, 12f, 37f, 6.5f, 44f, 5.5f)
        lineTo(71f, 5f)
        cubicTo(79f, 5f, 86f, 9f, 93f, 14f)
        cubicTo(96f, 16.5f, 97f, 20f, 97f, 24f)
        lineTo(96.5f, 30f)
        lineTo(85f, 30f)
        arcTo(Rect(Offset(76f, 30f), 8.5f), 0f, -180f, false)
        lineTo(30.5f, 30f)
        arcTo(Rect(Offset(22f, 30f), 8.5f), 0f, -180f, false)
        close()
    }
    val glass = Path().apply {
        moveTo(30f, 17.2f)
        cubicTo(34f, 12.5f, 38f, 8.5f, 44.5f, 7.8f)
        lineTo(70f, 7.4f)
        cubicTo(76f, 7.6f, 81f, 10.5f, 86f, 15.8f)
        lineTo(30f, 17.2f)
        close()
    }
    val glare = Path().apply {
        moveTo(46f, 7.8f)
        lineTo(53f, 7.6f)
        lineTo(45f, 16.9f)
        lineTo(38f, 17f)
        close()
    }
    val shoulder = Path().apply {
        moveTo(6f, 21.2f)
        cubicTo(20f, 19.2f, 60f, 18.2f, 95f, 18.6f)
    }
    val cuts = Path().apply {
        moveTo(55f, 7.4f); lineTo(55f, 29.5f)
        moveTo(31f, 17.4f); lineTo(33f, 29.5f)
        moveTo(74f, 16.5f); lineTo(69f, 29f)
    }
    val wheels = listOf(Offset(22f, 30f), Offset(76f, 30f))

    val paint = Brush.verticalGradient(
        0f to Color(0xFFEFF3F7), 0.28f to Color(0xFFC3CCD8), 0.55f to Color(0xFF7D8898), 0.8f to Color(0xFF424B58), 1f to Color(0xFF262C35),
        startY = 5f, endY = 32f
    )
    val glassFill = Brush.verticalGradient(listOf(Color(0xFF2B3746), Color(0xFF0D131B)), startY = 7f, endY = 17.5f)
    val rim = Brush.radialGradient(listOf(Color(0xFFE3E8EE), Color(0xFF8A94A2), Color(0xFF4A525E)), Offset.Zero, 4.8f)
}

/**
 * The drawn car in [dst]; [alert] traces its outline in [warn] (a door open).
 * Its colours stay those of a silver car by day and by night; [light] only
 * softens the dark trim by day.
 */
internal fun DrawScope.drawSimpleCar(dst: Rect, light: Boolean, alert: Boolean = false, warn: Color = Color(0xFFFF9B3D)) {
    val s = dst.width / 100f
    withTransform({
        translate(dst.left, dst.top + (dst.height - 42f * s) / 2f)
        scale(s, s, Offset.Zero)
    }) {
        val hair = 1f / s * density
        drawPath(DrawnCar.body, DrawnCar.paint)
        drawPath(DrawnCar.shoulder, Color.White.copy(alpha = 0.55f), style = Stroke(hair * 1.2f, cap = StrokeCap.Round))
        drawPath(DrawnCar.glass, DrawnCar.glassFill)
        drawPath(DrawnCar.glare, Color.White.copy(alpha = 0.12f))
        drawPath(DrawnCar.cuts, Color(0xFF1B2028).copy(alpha = if (light) 0.4f else 0.55f), style = Stroke(hair))
        // Lamps: a warm white headlamp at the nose, a red one at the tail.
        drawLine(Color(0xFFFFF4D6), Offset(4.5f, 21.8f), Offset(10f, 20.4f), 1.4f, StrokeCap.Round)
        drawLine(Color(0xFFE5484D), Offset(95.6f, 17f), Offset(96.4f, 21.5f), 1.4f, StrokeCap.Round)
        for (c in DrawnCar.wheels) {
            drawCircle(Color(0xFF0A0C0F), 7.2f, c)
            translate(c.x, c.y) {
                drawCircle(DrawnCar.rim, 4.8f, Offset.Zero)
                for (k in 0 until 5) {
                    val a = Math.toRadians(k * 72.0 - 90.0)
                    drawLine(
                        Color(0xFF3C434E), Offset.Zero,
                        Offset((kotlin.math.cos(a) * 4.2).toFloat(), (kotlin.math.sin(a) * 4.2).toFloat()),
                        0.9f, StrokeCap.Round
                    )
                }
                drawCircle(Color(0xFF2A3038), 1.3f, Offset.Zero)
            }
        }
        if (alert) drawPath(DrawnCar.body, warn, style = Stroke(hair * 2.5f, join = StrokeJoin.Round))
    }
}
