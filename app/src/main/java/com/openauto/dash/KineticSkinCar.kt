package com.openauto.dash

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * Kinetic skin, the car strip: the driver's own car (MyCarLook.kt, its side
 * picture) as a dark silhouette lit only at its edges, nose to the left as it
 * drives toward the turn, its headlamp and tail lamp glowing, a contact
 * shadow on a hairline road and streaks of speed passing behind it. While
 * parked, a light runs along its side now and then and the streaks drift;
 * while the car moves, with effects off or on a hidden page, all of it holds
 * still. Without an imported car, a drawn MPV in profile stands in.
 *
 * Three layers: the streaks and the sweep each redraw alone on the ambient
 * clock; the car, its lamps and its shadow are drawn once into an offscreen
 * layer and composited as they are.
 */

/** The drawn car's box (100 x 40 units): its width over its height. */
private const val KN_CAR_ASPECT = 100f / 40f

/** The ambient clock's turn: the streaks cross 3 to 7 times in it, the sweep 3 times. */
private const val STRIP_TURN_MS = 24_000

/** Where the car stands, which way it faces and where its lamps are, in a box of some size. */
private class KnCarGeo(val car: Rect, val flip: Boolean, val head: Offset, val tail: Offset)

/**
 * Where the car stands in [size]: on a strip, about four fifths of its height,
 * a little left of the middle so the range has the end; on a tall tile, most
 * of the width low in the box. [flip] when the picture's nose is on its right.
 */
private fun knCarGeo(size: Size, view: CarView?, tall: Boolean): KnCarGeo {
    val w = size.width
    val h = size.height
    val aspect = view?.aspect ?: KN_CAR_ASPECT
    val car = if (tall) {
        var cw = w * 0.82f
        var ch = cw / aspect
        if (ch > h * 0.5f) { ch = h * 0.5f; cw = ch * aspect }
        Rect(Offset((w - cw) / 2f, h * 0.95f - ch), Size(cw, ch))
    } else {
        var ch = h * 0.8f
        var cw = ch * aspect
        if (cw > w * 0.5f) { cw = w * 0.5f; ch = cw / aspect }
        val cx = (w * 0.46f).coerceIn(cw / 2f, w - cw / 2f)
        Rect(Offset(cx - cw / 2f, h * 0.95f - ch), Size(cw, ch))
    }
    // The picture's nose: said by the pack, else told by the front wheel; the drawn car faces right too.
    val noseRight = when {
        view == null -> true
        view.nose != null -> view.nose.x > 0.5f
        view.wheels.size >= 2 -> view.wheels[0].x > view.wheels[1].x
        else -> false
    }
    val noseX = view?.nose?.let { if (noseRight) 1f - it.x else it.x } ?: 0.04f
    val noseY = view?.nose?.y ?: 0.52f
    val tailX = view?.tail?.let { if (noseRight) 1f - it.x else it.x } ?: 0.97f
    val tailY = if (view == null) 0.36f else (view.tail?.y ?: 0.42f) - 0.06f
    return KnCarGeo(
        car, noseRight,
        Offset(car.left + car.width * noseX, car.top + car.height * noseY),
        Offset(car.left + car.width * tailX, car.top + car.height * tailY)
    )
}

/**
 * The strip filling [modifier]'s box: streaks, the car and its lamps, the
 * sweep, and the range beside the car (at the end of a strip, over the car
 * on a [tall] tile). Fuel not known with the adapter idle: a tap connects it.
 */
@Composable
internal fun KnCarStrip(fuel: FuelInfo?, env: SkinTileEnv, tall: Boolean, modifier: Modifier) {
    val mine by MyCarLook.shown.collectAsState()
    val view = mine?.let { it.side ?: it.portrait }
    val said = stringResource(R.string.kinetic_car_cd)
    val canConnect = fuel == null && env.obdConnection.isIdle && !env.editing
    val effects = DashColors.Effects
    val clock = if (effects != DashEffects.NONE) rememberLoop(STRIP_TURN_MS) else null
    BoxWithConstraints(
        modifier
            .knTap(canConnect, stringResource(R.string.horizon_connect_obd), env.onConnectObd)
            .semantics { contentDescription = said }
    ) {
        KnStreaks(tall, clock, Modifier.fillMaxSize())
        KnCarBody(view, tall, Modifier.fillMaxSize())
        if (clock != null) KnSweep(view, tall, clock, Modifier.fillMaxSize())
        val readoutH = if (tall) maxHeight * 0.36f else maxHeight * 0.8f
        Box(
            Modifier
                .align(if (tall) Alignment.TopStart else Alignment.CenterEnd)
                .padding(start = 8.dp, end = 12.dp, top = 4.dp)
        ) {
            KnRangeReadout(fuel, readoutH, end = !tall)
        }
    }
}

// --- Streaks ------------------------------------------------------------------------------

/** One streak: its height (0..1 of its band), length (share of the width), ink, phase and how many times it crosses in a turn. */
private class Streak(val y: Float, val len: Float, val alpha: Float, val phase: Float, val laps: Int)

/** The same sixteen streaks every time (a fixed little generator, no random state). */
private val STREAKS: List<Streak> = run {
    var seed = 0x2F6B1D3
    fun next(): Float {
        seed = (seed * 1103515245 + 12345) and 0x7FFFFFFF
        return (seed % 10_000) / 10_000f
    }
    List(16) { Streak(next(), 0.08f + next() * 0.2f, 0.12f + next() * 0.33f, next(), 3 + (next() * 5).toInt().coerceAtMost(4)) }
}

/**
 * The streaks: fine lines passing behind the car toward its tail, each
 * brighter at its head, at different heights and speeds. They move on
 * [clock] (ambient: still while the car moves); with no clock they stand.
 */
@Composable
private fun KnStreaks(tall: Boolean, clock: State<Float>?, modifier: Modifier) {
    val light = DashColors.Light
    Spacer(
        modifier
            .graphicsLayer()
            .cachedDraw(tall, light, clock) {
                val w = size.width
                val h = size.height
                val top = if (tall) h * 0.45f else h * 0.12f
                val band = (if (tall) h * 0.52f else h * 0.84f)
                val ink = if (light) Color(0xFF06070B) else Color(0xFFF4F2EE)
                // One unit streak, scaled to each length: transparent tail, bright head on the right.
                val unit = Brush.horizontalGradient(listOf(ink.copy(alpha = 0f), ink.copy(alpha = if (light) 0.5f else 0.75f)), 0f, 100f)
                val thick = 1.5f * density
                // On a strip they stop short of the range's figure at the end, so it stays clean.
                val span = if (tall) w else w * 0.78f
                onDrawBehind {
                    val t = clock?.value ?: 0f
                    clipRect(0f, 0f, span, h) {
                        for (s in STREAKS) {
                            val len = span * s.len
                            val p = (s.phase + t * s.laps) % 1f
                            val x = -len + p * (span + len)
                            val y = top + band * s.y
                            withTransform({
                                translate(x, y)
                                scale(len / 100f, 1f, Offset.Zero)
                            }) {
                                drawRect(unit, Offset(0f, -thick / 2f), Size(100f, thick), alpha = s.alpha)
                            }
                        }
                    }
                }
            }
    )
}

// --- The car ----------------------------------------------------------------------------

/** The picture's colours pressed toward night: a fifth of its light left, with a touch of blue. */
private val DarkCar = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            0.2f, 0f, 0f, 0f, 6f,
            0f, 0.2f, 0f, 0f, 7f,
            0f, 0f, 0.24f, 0f, 14f,
            0f, 0f, 0f, 1f, 0f
        )
    )
)

/**
 * The car, its lamps' glow and its contact shadow on the road's hairline, in
 * one offscreen layer drawn only when the car or the look changes. The body
 * is near black (a dark car by day too, on the pale page), lit at its edges
 * by a rim of the skin's gradient: blue at the nose, violet, amber at the
 * tail. Effects off: no glow, the lamps as small points.
 */
@Composable
private fun KnCarBody(view: CarView?, tall: Boolean, modifier: Modifier) {
    val light = DashColors.Light
    val effects = DashColors.Effects
    val road = DashColors.Line
    Spacer(
        modifier
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .cachedDraw(view, light, effects, road, tall) {
                val geo = knCarGeo(size, view, tall)
                val car = geo.car
                val glowK = effects.scale
                // The gradient's blue at the nose, violet, then amber at the tail.
                val rimInk = knGradientColors(light).let { listOf(it[0], it[1], it[3]) }
                // A picture's outline has every edge of the car, not just its silhouette: fainter, so it lights rather than draws.
                val rimAlpha = when {
                    view != null -> if (light) 0.45f else 0.6f
                    light -> 0.75f
                    else -> 0.9f
                }
                val rim = Brush.horizontalGradient(rimInk.map { it.copy(alpha = rimAlpha) }, car.left, car.right)
                val headR = car.height * 0.62f
                val headGlow = Brush.radialGradient(
                    listOf((if (light) Color(0xFF7AA0E0) else Color.White).copy(alpha = (if (light) 0.22f else 0.42f) * glowK), Color.Transparent),
                    geo.head, headR
                )
                val tailR = car.height * 0.45f
                val tailGlow = Brush.radialGradient(
                    listOf(Color(0xFFFF2A3A).copy(alpha = (if (light) 0.22f else 0.38f) * glowK), Color.Transparent),
                    geo.tail, tailR
                )
                val shadowC = Offset(car.center.x, car.bottom - car.height * 0.02f)
                val shadowRx = car.width * 0.55f
                val shadow = Brush.radialGradient(
                    0f to Color.Black.copy(alpha = if (light) 0.32f else 0.7f), 0.7f to Color.Transparent,
                    center = shadowC, radius = shadowRx
                )
                val topLight = if (glowK > 0f) Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.1f * glowK), Color.Transparent), Offset(car.center.x, car.top), car.width * 0.5f
                ) else null
                onDrawBehind {
                    // The road: a hairline the width of the box, at the wheels.
                    drawLine(road, Offset(0f, car.bottom - car.height * 0.01f), Offset(size.width, car.bottom - car.height * 0.01f), density)
                    scale(1f, car.height * 0.1f / shadowRx, shadowC) { drawCircle(shadow, shadowRx, shadowC) }
                    if (glowK > 0f) {
                        drawCircle(headGlow, headR, geo.head)
                        drawCircle(tailGlow, tailR, geo.tail)
                    }
                    if (view != null) drawPictureCar(view, geo, rim, topLight) else drawKnMpv(car, rim, topLight, light)
                    // The lamps themselves.
                    drawCircle(if (light) Color(0xFFDDE6F5) else Color.White, car.height * 0.035f, geo.head)
                    drawCircle(Color(0xFFFF3B4A), car.height * 0.03f, geo.tail)
                }
            }
    )
}

/** The driver's picture: darkened, flipped to face left, its outline in the rim's colours and a faint light from above on its pixels. */
private fun DrawScope.drawPictureCar(view: CarView, geo: KnCarGeo, rim: Brush, topLight: Brush?) {
    val car = geo.car
    val offset = IntOffset(car.left.roundToInt(), car.top.roundToInt())
    val size = IntSize(car.width.roundToInt().coerceAtLeast(1), car.height.roundToInt().coerceAtLeast(1))
    withTransform({ if (geo.flip) scale(-1f, 1f, car.center) }) {
        // The body in a layer of its own, so the light from above falls on its pixels alone.
        drawIntoCanvas { canvas ->
            canvas.saveLayer(car, Paint())
            drawImage(view.photo, dstOffset = offset, dstSize = size, colorFilter = DarkCar)
            if (topLight != null) drawRect(topLight, car.topLeft, car.size, blendMode = BlendMode.SrcAtop)
            canvas.restore()
        }
        // The rim: the edges alone, painted with the gradient.
        drawIntoCanvas { canvas ->
            canvas.saveLayer(car, Paint())
            drawImage(view.outline, dstOffset = offset, dstSize = size)
            // The rim's colours run nose to tail whichever way the picture was drawn.
            withTransform({ if (geo.flip) scale(-1f, 1f, car.center) }) {
                drawRect(rim, car.topLeft, car.size, blendMode = BlendMode.SrcIn)
            }
            canvas.restore()
        }
    }
}

// --- The drawn car ------------------------------------------------------------------------

/**
 * A big MPV in profile, nose to the right as drawn (the strip mirrors it): a
 * one-box body whose long windscreen runs from the roof almost to the nose,
 * deep side glass and five-spoke wheels, in a 100 x 40 box.
 */
private object KnDrawnCar {
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
    /** The roofline and the windscreen's slope: where the rim light catches. */
    val roof = Path().apply {
        moveTo(2.6f, 25f)
        cubicTo(2.3f, 19f, 3.2f, 12f, 5.4f, 8f)
        cubicTo(6.8f, 5.4f, 9f, 4.1f, 12.5f, 3.8f)
        lineTo(57f, 3.2f)
        cubicTo(65f, 3.2f, 71f, 6.2f, 77f, 11.6f)
        cubicTo(80.5f, 14.6f, 84f, 16.4f, 88.5f, 17.5f)
        cubicTo(93.5f, 18.7f, 97.2f, 19.8f, 98.6f, 22.6f)
    }
    val shoulder = Path().apply {
        moveTo(4f, 18.4f)
        cubicTo(30f, 17.2f, 70f, 17.4f, 96f, 20.6f)
    }
    val pillars = listOf(floatArrayOf(22f, 5.6f, 24f, 16.2f), floatArrayOf(45.2f, 5.2f, 46.6f, 16.2f), floatArrayOf(66.8f, 8.4f, 67.9f, 16f))
    val wheels = listOf(Offset(20f, 31f), Offset(79f, 31f))
    val paint = Brush.verticalGradient(0f to Color(0xFF1C1E29), 0.45f to Color(0xFF0D0E15), 1f to Color(0xFF050508), startY = 3f, endY = 33f)
    val glassFill = Brush.verticalGradient(listOf(Color(0xFF262B40), Color(0xFF07080D)), startY = 5f, endY = 16f)
    val rimMetal = Brush.radialGradient(listOf(Color(0xFF5A6070), Color(0xFF2A2E3A), Color(0xFF15171E)), Offset.Zero, 5f)
}

/** The drawn MPV in [dst], facing left, near black with the [rim] along its roof and shoulder and [topLight] on its body. */
private fun DrawScope.drawKnMpv(dst: Rect, rim: Brush, topLight: Brush?, light: Boolean) {
    val s = dst.width / 100f
    // The rim's brush is in the box's own pixels: drawn into the car's units it is scaled with it.
    withTransform({
        scale(-1f, 1f, dst.center)
        translate(dst.left, dst.top + (dst.height - 40f * s) / 2f)
        scale(s, s, Offset.Zero)
    }) {
        val hair = density / s
        drawPath(KnDrawnCar.body, KnDrawnCar.paint)
        if (topLight != null) {
            clipPath(KnDrawnCar.body) {
                drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.07f), Color.Transparent), 3f, 20f), Offset(0f, 0f), Size(100f, 22f))
            }
        }
        drawPath(KnDrawnCar.glass, KnDrawnCar.glassFill)
        for (p in KnDrawnCar.pillars) drawRect(Color(0xFF0B0C12), Offset(p[0], p[1]), Size(p[2] - p[0], p[3] - p[1]))
        drawPath(KnDrawnCar.shoulder, Color.White.copy(alpha = if (light) 0.18f else 0.12f), style = Stroke(hair, cap = StrokeCap.Round))
        for (c in KnDrawnCar.wheels) {
            drawCircle(Color(0xFF040405), 7.6f, c)
            translate(c.x, c.y) {
                drawCircle(KnDrawnCar.rimMetal, 5f, Offset.Zero)
                for (k in 0 until 5) {
                    val a = Math.toRadians(k * 72.0 - 90.0)
                    drawLine(Color(0xFF0A0B10), Offset.Zero, Offset((cos(a) * 4.4).toFloat(), (sin(a) * 4.4).toFloat()), 1.1f, StrokeCap.Round)
                }
                drawCircle(Color(0xFF8C93A6).copy(alpha = 0.5f), 5f, Offset.Zero, style = Stroke(hair))
            }
        }
    }
    // The rim light along the roofline, in the box's pixels so its colours run nose (left) to tail.
    val roofPx = Path().apply { addPath(KnDrawnCar.roof) }
    val m = androidx.compose.ui.graphics.Matrix().apply {
        translate(dst.right, dst.top + (dst.height - 40f * s) / 2f)
        scale(-s, s)
    }
    roofPx.transform(m)
    drawPath(roofPx, rim, style = Stroke(1.6f * density, cap = StrokeCap.Round))
}

// --- The sweep ------------------------------------------------------------------------------

/**
 * A light running along the car's side now and then while parked, on its
 * paint only: violet with a blue one half a turn behind (white by day).
 * Three passes a turn of [clock], each crossing in 60 % of its share and
 * resting the rest.
 */
@Composable
private fun KnSweep(view: CarView?, tall: Boolean, clock: State<Float>, modifier: Modifier) {
    val light = DashColors.Light
    val scaleK = DashColors.Effects.scale
    Spacer(
        modifier
            .graphicsLayer()
            .cachedDraw(view, tall, light, scaleK, clock) {
                val geo = knCarGeo(size, view, tall)
                val car = geo.car
                val r = car.height * 0.7f
                val violet = Brush.radialGradient(
                    listOf((if (light) Color.White else Color(0xFFB48CFF)).copy(alpha = 0.55f * scaleK), Color.Transparent), Offset.Zero, r
                )
                val blue = Brush.radialGradient(
                    listOf((if (light) Color.White else Color(0xFF7AB8FF)).copy(alpha = 0.4f * scaleK), Color.Transparent), Offset.Zero, r
                )
                val s = car.width / 100f
                val body = if (view == null) Path().apply {
                    addPath(KnDrawnCar.body)
                    transform(androidx.compose.ui.graphics.Matrix().apply {
                        translate(car.right, car.top + (car.height - 40f * s) / 2f)
                        scale(-s, s)
                    })
                } else null
                val offset = IntOffset(car.left.roundToInt(), car.top.roundToInt())
                val dstSize = IntSize(car.width.roundToInt().coerceAtLeast(1), car.height.roundToInt().coerceAtLeast(1))
                val layer = Rect(car.left, car.top, car.right, car.bottom)
                val layerPaint = Paint()
                onDrawBehind {
                    val t = clock.value * 3f
                    val p1 = ((t % 1f) / 0.6f)
                    val p2 = (((t + 0.5f) % 1f) / 0.6f)
                    if (p1 >= 1f && p2 >= 1f) return@onDrawBehind
                    // Nose (left) to tail.
                    fun lights() {
                        if (p1 < 1f) translate(car.left - r + p1 * (car.width + 2f * r), car.top + car.height * 0.45f) {
                            scale(1.6f, 0.7f, Offset.Zero) { drawCircle(violet, r, Offset.Zero) }
                        }
                        if (p2 < 1f) translate(car.left - r + p2 * (car.width + 2f * r), car.top + car.height * 0.62f) {
                            scale(1.6f, 0.6f, Offset.Zero) { drawCircle(blue, r, Offset.Zero) }
                        }
                    }
                    if (body != null) {
                        clipPath(body) { lights() }
                    } else if (view != null) {
                        drawIntoCanvas { canvas ->
                            canvas.saveLayer(layer, layerPaint)
                            lights()
                            withTransform({ if (geo.flip) scale(-1f, 1f, car.center) }) {
                                drawImage(view.photo, dstOffset = offset, dstSize = dstSize, blendMode = BlendMode.DstIn)
                            }
                            canvas.restore()
                        }
                    }
                }
            }
    )
}
