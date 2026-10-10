package com.openauto.dash

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * The Anime skin's drawn pictures: the comic speed burst, the car driving on
 * its tile (the driver's own car as a pink sticker, or a drawn one), the
 * record and the weather glyphs. Each is drawn once per size into a cache;
 * what moves is only redrawn, or turned in its own layer.
 */

// --- Comic burst ------------------------------------------------------------------

/** The mockup's twenty-point burst, as fractions of its box. */
private val BurstPoints = floatArrayOf(
    0.5f, 0f, 0.6f, 0.18f, 0.82f, 0.06f, 0.78f, 0.3f, 1f, 0.34f, 0.84f, 0.5f, 1f, 0.66f, 0.78f, 0.7f,
    0.84f, 0.94f, 0.62f, 0.82f, 0.5f, 1f, 0.38f, 0.82f, 0.16f, 0.94f, 0.22f, 0.7f, 0f, 0.66f, 0.16f, 0.5f,
    0f, 0.34f, 0.22f, 0.3f, 0.18f, 0.06f, 0.4f, 0.18f
)

/** The burst filling [r]. */
internal fun animeBurstPath(r: Rect): Path = Path().apply {
    moveTo(r.left + BurstPoints[0] * r.width, r.top + BurstPoints[1] * r.height)
    var i = 2
    while (i < BurstPoints.size) {
        lineTo(r.left + BurstPoints[i] * r.width, r.top + BurstPoints[i + 1] * r.height)
        i += 2
    }
    close()
}

/** A burst's box is this much wider than tall. */
internal const val ANIME_BURST_ASPECT = 1.15f

/**
 * The speed in a yellow comic burst: pink numerals with an ink outline and
 * the unit (or [caption]) under them, over an ink star with a pink misprint
 * shadow. Over the speed warning the burst turns amber and the numerals
 * ink, which reads on amber where white does not. No reading shows a dash.
 */
@Composable
internal fun AnimeSpeedBurst(speed: Int?, caption: String, modifier: Modifier) {
    val t = animeTones
    val units = LocalUnits.current
    val warn = speedOver(speed)
    val fill = if (warn) DashColors.Warning else t.yellow
    val numColor = if (warn) t.ink else t.pink
    BoxWithConstraints(
        modifier.cachedDraw(fill, t) {
            val sh = size.minDimension * 0.04f
            val body = Rect(0f, 0f, size.width - sh, size.height - sh)
            val border = (size.minDimension * 0.035f).coerceAtLeast(3.dp.toPx())
            val shadow = animeBurstPath(body.translate(sh, sh))
            val edge = animeBurstPath(body)
            val face = animeBurstPath(body.deflate(border))
            onDrawBehind {
                drawPath(shadow, t.pink)
                drawPath(edge, t.ink)
                drawPath(face, fill)
            }
        }
    ) {
        val sh = min(maxWidth.value, maxHeight.value) * 0.04f
        val w = maxWidth - sh.dp
        val h = maxHeight - sh.dp
        Box(Modifier.fillMaxSize().padding(end = sh.dp, bottom = sh.dp), contentAlignment = Alignment.Center) {
            val numSize = animeFit("188", animeComic(100f, numColor), w * 0.56f, h * 0.4f, 14f, 400f)
            val capSize = min(
                (numSize * 0.24f).coerceIn(10f, 52f),
                animeFit(caption, animeComic(100f, t.ink), w * 0.6f, h * 0.13f, 8f, 52f)
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AnimeFigure(speed?.let { units.speed(it).toString() } ?: "–", animeComic(numSize, numColor), ink = t.ink, outline = 0.05f)
                AnimeText(caption, animeComic(capSize, t.ink))
            }
        }
    }
}

// --- The car ----------------------------------------------------------------------

/** The car's loop: it bobs four times and its lane dashes pass eight times. */
private const val CAR_LOOP_MS = 3_600

/** Speed lines behind the car: height and length (car heights), thickness, whole cycles per loop, phase. */
private val LineY = floatArrayOf(0.12f, 0.36f, 0.6f, 0.82f)
private val LineLen = floatArrayOf(0.9f, 1.24f, 1f, 0.8f)
private val LineThick = floatArrayOf(0.045f, 0.056f, 0.045f, 0.034f)
private val LineCycles = intArrayOf(5, 6, 4, 5)
private val LinePhase = floatArrayOf(0f, 0.3f, 0.55f, 0.15f)

/** The drawn car's box, in its own units. */
private const val DRAWN_W = 200f
private const val DRAWN_H = 84f

/** Where the scene's road and car go in a [w] x [h] box, and how thick the sticker outline is. */
private class CarGeom(
    val road: Rect,
    val roadH: Float,
    val inkLine: Float,
    val car: Rect,
    val facingRight: Boolean,
    val white: Float,
    val ink: Float
) {
    val pad: Float get() = white + ink + 1f
}

private fun Density.carGeom(w: Float, h: Float, view: CarView?): CarGeom {
    val inkLine = 3.dp.toPx()
    val roadH = (h * 0.14f).coerceIn(10.dp.toPx(), 26.dp.toPx())
    val road = Rect(w * 0.02f, h - roadH - inkLine, w * 0.98f - inkLine, h - inkLine)
    // Speed lines stream off the tail, so the car keeps to the side its nose points to.
    val facingRight = view?.let { v ->
        v.nose?.let { it.x > 0.5f } ?: v.wheels.takeIf { it.size >= 2 }?.let { it[0].x > it[1].x }
    } ?: true
    val aspect = view?.aspect ?: (DRAWN_W / DRAWN_H)
    val area = if (facingRight) Rect(w * 0.2f, h * 0.06f, w * 0.97f, road.top + roadH * 0.45f)
    else Rect(w * 0.03f, h * 0.06f, w * 0.8f, road.top + roadH * 0.45f)
    val cw = min(area.width, area.height * aspect).coerceAtLeast(1f)
    val ch = cw / aspect
    val car = Rect(Offset(area.center.x - cw / 2f, area.bottom - ch), Size(cw, ch))
    val white = (ch * 0.045f).coerceIn(2.dp.toPx(), 6.dp.toPx())
    val ink = (ch * 0.032f).coerceIn(2.dp.toPx(), 4.5.dp.toPx())
    return CarGeom(road, roadH, inkLine, car, facingRight, white, ink)
}

/**
 * The car on a short road: the driver's own car seen from the side
 * (MyCarLook), tinted pink with a white then ink sticker outline, or a drawn
 * cute car without one. While [moving] it bobs, speed lines stream off its
 * tail and the lane dashes run under it (still, with effects off). The car
 * sticker is drawn once per size into a bitmap in its own layer, so a step
 * only redraws the road and the lines and moves that layer.
 */
@Composable
internal fun AnimeCarScene(moving: Boolean, modifier: Modifier) {
    val mine by MyCarLook.shown.collectAsState()
    val view = mine?.side
    val t = animeTones
    val animate = moving && DashColors.Effects != DashEffects.NONE
    val loop = if (animate) rememberLoop(CAR_LOOP_MS) else null
    // The car's height in px, set where the sticker is laid out, for the bob.
    val carH = remember { floatArrayOf(0f) }
    Box(modifier) {
        Spacer(
            Modifier
                .fillMaxSize()
                .cachedDraw(view, t, moving, loop) {
                    val w = size.width
                    val h = size.height
                    if (w < 8f || h < 8f) return@cachedDraw onDrawBehind { }
                    val g = carGeom(w, h, view)
                    val road = g.road
                    val car = g.car
                    val ch = car.height
                    val roadEdge = Stroke(g.inkLine)
                    val dashLen = g.roadH * 2.2f
                    val dashH = (g.roadH * 0.18f).coerceAtLeast(2f)
                    val shade = Rect(car.left + car.width * 0.06f, road.top - g.roadH * 0.12f, car.right - car.width * 0.06f, road.top + g.roadH * 0.38f)
                    val tail = if (g.facingRight) car.left + ch * 0.25f else car.right - ch * 0.25f
                    val away = if (g.facingRight) -1f else 1f
                    onDrawBehind {
                        val p = loop?.value ?: 0f
                        drawRoundRect(t.road, road.topLeft, road.size, CornerRadius(g.roadH / 2f))
                        drawRoundRect(t.ink, road.topLeft, road.size, CornerRadius(g.roadH / 2f), style = roadEdge)
                        clipRect(road.left + g.roadH / 2f, road.top, road.right - g.roadH / 2f, road.bottom) {
                            val shift = (p * 8f % 1f) * dashLen * 2f
                            var x = road.left + (if (g.facingRight) -shift else shift) - dashLen * 2f
                            while (x < road.right) {
                                drawRoundRect(t.yellow, Offset(x, road.center.y - dashH / 2f), Size(dashLen, dashH), CornerRadius(dashH / 2f))
                                x += dashLen * 2f
                            }
                        }
                        drawOval(t.ink.copy(alpha = 0.28f), shade.topLeft, shade.size)
                        // The lines trail off the tail but never past the scene's own edges.
                        if (moving) clipRect(0f, 0f, w, h) {
                            for (i in LineY.indices) {
                                val f = if (loop != null) (p * LineCycles[i] + LinePhase[i]) % 1f else 0.35f
                                val alpha = if (f < 0.2f) f / 0.2f else 1f - (f - 0.2f) / 0.8f
                                val len = LineLen[i] * ch
                                val y = car.top + LineY[i] * ch
                                val x0 = tail + away * f * ch * 1.8f
                                val x1 = x0 + away * len
                                val thick = (LineThick[i] * ch).coerceAtLeast(2f)
                                drawLine(t.ink, Offset(x0, y), Offset(x1, y), thick + g.inkLine, StrokeCap.Round, alpha = alpha)
                                drawLine(Color.White, Offset(x0, y), Offset(x1, y), thick, StrokeCap.Round, alpha = alpha)
                            }
                        }
                    }
                }
        )
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = loop?.value
                    translationY = if (p != null) -(0.5f + 0.5f * sin(p * 4f * 2f * PI.toFloat())) * carH[0] * 0.03f else 0f
                }
                .cachedDraw(view, t) {
                    val w = size.width
                    val h = size.height
                    if (w < 8f || h < 8f) return@cachedDraw onDrawBehind { }
                    val g = carGeom(w, h, view)
                    carH[0] = g.car.height
                    val sticker = animeCarSticker(this, layoutDirection, view, g.car.width, g.car.height, g.white, g.ink, g.pad, t)
                    val at = Offset(g.car.left - g.pad, g.car.top - g.pad)
                    onDrawBehind { drawImage(sticker, at) }
                }
        )
    }
}

/**
 * The car as a sticker bitmap of [cw] x [ch] plus [pad] all round: its
 * silhouette stamped out [white] + [ink] in ink and [white] in white (a
 * die-cut outline that follows the car, or the picture's edge when it has no
 * transparency), the picture laid on a pink ramp and its own line art in ink
 * over it. Without a car of the driver's, the drawn one.
 */
private fun animeCarSticker(
    density: Density, dir: LayoutDirection, view: CarView?, cw: Float, ch: Float, white: Float, ink: Float, pad: Float, t: AnimeTones
): ImageBitmap {
    val bw = ceil(cw + 2f * pad).toInt().coerceAtLeast(1)
    val bh = ceil(ch + 2f * pad).toInt().coerceAtLeast(1)
    val bmp = ImageBitmap(bw, bh)
    val paper = t.rim ?: Color.White
    CanvasDrawScope().draw(density, dir, androidx.compose.ui.graphics.Canvas(bmp), Size(bw.toFloat(), bh.toFloat())) {
        if (view != null) {
            val at = IntOffset(pad.roundToInt(), pad.roundToInt())
            val dst = IntSize(cw.roundToInt().coerceAtLeast(1), ch.roundToInt().coerceAtLeast(1))
            val inkTint = ColorFilter.tint(t.ink, BlendMode.SrcIn)
            stampOut(view.photo, at, dst, white + ink, inkTint)
            stampOut(view.photo, at, dst, white, ColorFilter.tint(paper, BlendMode.SrcIn))
            drawImage(view.photo, dstOffset = at, dstSize = dst, colorFilter = pinkRamp(t))
            drawImage(view.outline, dstOffset = at, dstSize = dst, alpha = 0.45f, colorFilter = inkTint)
        } else {
            animeDrawnCar(Offset(pad, pad), cw / DRAWN_W, t, white, ink, paper)
        }
    }
    return bmp
}

/** [img] laid round its place in rings of radius [r], which grows its silhouette by [r]. */
private fun DrawScope.stampOut(img: ImageBitmap, at: IntOffset, dst: IntSize, r: Float, tint: ColorFilter) {
    for (ring in 1..2) {
        val rr = r * ring / 2f
        for (k in 0 until 12) {
            val a = (k * 30f + ring * 15f) * PI.toFloat() / 180f
            drawImage(
                img,
                dstOffset = IntOffset(at.x + (cos(a) * rr).roundToInt(), at.y + (sin(a) * rr).roundToInt()),
                dstSize = dst,
                colorFilter = tint
            )
        }
    }
}

/** The picture's brightness on a ramp from deep magenta to pale pink, a little more contrast so a dark car still reads pink. */
private fun pinkRamp(t: AnimeTones): ColorFilter {
    val dark = if (t.light) Color(0xFF7A2160) else Color(0xFF5A1748)
    val light = if (t.light) Color(0xFFFFD6EA) else Color(0xFFF7B3D3)
    fun row(d: Float, l: Float): FloatArray {
        val k = (l - d) * 1.25f
        return floatArrayOf(0.3f * k, 0.59f * k, 0.11f * k, 0f, (d - (l - d) * 0.08f) * 255f)
    }
    return ColorFilter.colorMatrix(
        ColorMatrix(row(dark.red, light.red) + row(dark.green, light.green) + row(dark.blue, light.blue) + floatArrayOf(0f, 0f, 0f, 1f, 0f))
    )
}

/**
 * A cute rounded MPV from the side, nose to the right, in a 200 x 84 box at
 * [o] scaled by [u] px per unit: pink body, sky windows, big wheels, ink
 * lines, with the same white then ink sticker outline as the driver's car.
 */
private fun DrawScope.animeDrawnCar(o: Offset, u: Float, t: AnimeTones, white: Float, ink: Float, paper: Color) {
    fun x(v: Float) = o.x + v * u
    fun y(v: Float) = o.y + v * u
    val body = Path().apply {
        moveTo(x(14f), y(67f))
        quadraticTo(x(4f), y(67f), x(5f), y(56f))
        lineTo(x(8f), y(30f))
        quadraticTo(x(10f), y(13f), x(28f), y(12f))
        lineTo(x(118f), y(10f))
        quadraticTo(x(133f), y(10f), x(144f), y(20f))
        lineTo(x(162f), y(35f))
        quadraticTo(x(190f), y(37f), x(194f), y(48f))
        quadraticTo(x(198f), y(58f), x(192f), y(65f))
        quadraticTo(x(190f), y(67f), x(182f), y(67f))
        close()
    }
    val windows = Path().apply {
        moveTo(x(20f), y(35f))
        lineTo(x(22f), y(23f))
        quadraticTo(x(24f), y(18f), x(32f), y(18f))
        lineTo(x(116f), y(17f))
        quadraticTo(x(128f), y(17f), x(136f), y(24f))
        lineTo(x(150f), y(35f))
        close()
    }
    val wheels = listOf(Offset(x(46f), y(67f)), Offset(x(156f), y(67f)))
    val wheelR = 16f * u
    val round = StrokeJoin.Round
    // The sticker outline: ink, then white, both round the body and the wheels.
    drawPath(body, t.ink, style = Stroke(2f * (white + ink), join = round))
    for (c in wheels) drawCircle(t.ink, wheelR + white + ink, c)
    drawPath(body, paper, style = Stroke(2f * white, join = round))
    for (c in wheels) drawCircle(paper, wheelR + white, c)

    drawPath(body, t.carBody)
    clipPath(body) {
        drawRect(t.carShade, Offset(x(0f), y(52f)), Size(DRAWN_W * u, 16f * u))
    }
    drawPath(windows, t.glass)
    drawLine(Color.White.copy(alpha = 0.75f), Offset(x(30f), y(22f)), Offset(x(50f), y(22f)), 3f * u, StrokeCap.Round)
    drawPath(windows, t.ink, style = Stroke(2.5f * u, join = round))
    drawLine(t.ink, Offset(x(62f), y(18f)), Offset(x(62f), y(35f)), 5f * u)
    drawLine(t.ink, Offset(x(104f), y(17f)), Offset(x(104f), y(35f)), 5f * u)
    drawLine(t.ink.copy(alpha = 0.6f), Offset(x(62f), y(37f)), Offset(x(62f), y(62f)), 2f * u)
    drawLine(t.ink.copy(alpha = 0.6f), Offset(x(106f), y(37f)), Offset(x(106f), y(60f)), 2f * u)
    drawLine(Color.White.copy(alpha = 0.7f), Offset(x(34f), y(14.5f)), Offset(x(112f), y(13f)), 3f * u, StrokeCap.Round)
    drawOval(t.yellow, Offset(x(182f), y(42f)), Size(11f * u, 7f * u))
    drawOval(t.ink, Offset(x(182f), y(42f)), Size(11f * u, 7f * u), style = Stroke(1.6f * u))
    drawRoundRect(Color(0xFFFF5A6E), Offset(x(6f), y(34f)), Size(5f * u, 11f * u), CornerRadius(2f * u))
    drawPath(body, t.ink, style = Stroke(3f * u, join = round))
    for (c in wheels) {
        drawCircle(t.ink, wheelR, c)
        drawCircle(paper, wheelR * 0.42f, c)
        drawCircle(t.ink, wheelR * 0.14f, c)
    }
}

// --- Record -----------------------------------------------------------------------

/**
 * A vinyl record of [size]: ink rim, grooves and a still sheen, and a label
 * (the album art when the player gives one, else pink with a white ring and
 * a mark) that turns while [playing], in its own layer so only the layer
 * turns. Stops where it is on pause, and with effects off.
 */
@Composable
internal fun AnimeRecord(playing: Boolean, art: Bitmap?, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val t = animeTones
    val spin = rememberSpin(3_000, running = playing)
    val label = remember(art) { art?.asImageBitmap() }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Spacer(
            Modifier
                .size(size)
                .cachedDraw(t) {
                    val r = this.size.minDimension / 2f
                    val c = this.size.center
                    val groove = Stroke((r * 0.025f).coerceAtLeast(1f))
                    onDrawBehind {
                        drawCircle(t.ink, r, c)
                        drawCircle(Color(0xFF2B2B4A), r * 0.93f, c)
                        var g = r * 0.88f
                        while (g > r * 0.42f) {
                            drawCircle(Color(0xFF3A3A60), g, c, style = groove)
                            g -= r * 0.07f
                        }
                        // The light on the vinyl stays put while the label turns.
                        drawArc(Color.White.copy(alpha = 0.16f), 200f, 40f, true, Offset(c.x - r * 0.9f, c.y - r * 0.9f), Size(r * 1.8f, r * 1.8f))
                        drawArc(Color.White.copy(alpha = 0.1f), 20f, 40f, true, Offset(c.x - r * 0.9f, c.y - r * 0.9f), Size(r * 1.8f, r * 1.8f))
                    }
                }
        )
        Spacer(
            Modifier
                .size(size * 0.4f)
                .graphicsLayer { rotationZ = spin.value }
                .cachedDraw(t, label) {
                    val r = this.size.minDimension / 2f
                    val c = this.size.center
                    val disc = Path().apply { addOval(Rect(c, r * 0.9f)) }
                    onDrawBehind {
                        drawCircle(Color.White, r, c)
                        if (label != null) {
                            clipPath(disc) {
                                drawImage(
                                    label,
                                    dstOffset = IntOffset((c.x - r).roundToInt(), (c.y - r).roundToInt()),
                                    dstSize = IntSize((2f * r).roundToInt(), (2f * r).roundToInt())
                                )
                            }
                        } else {
                            drawCircle(t.pink, r * 0.9f, c)
                            drawCircle(Color.White, r * 0.62f, c, style = Stroke(r * 0.08f))
                        }
                        // A mark on the label, so the turning shows.
                        drawCircle(Color.White, r * 0.12f, Offset(c.x, c.y - r * 0.62f))
                        drawCircle(t.ink, r * 0.14f, c)
                    }
                }
        )
    }
}

// --- Weather ----------------------------------------------------------------------

/** What the weather glyph shows for a WMO code. */
private enum class Sky { SUN, PARTLY, CLOUD, FOG, RAIN, SNOW, STORM }

private fun skyOf(code: Int): Sky = when (code) {
    0, 1 -> Sky.SUN
    2 -> Sky.PARTLY
    45, 48 -> Sky.FOG
    in 51..67, in 80..82 -> Sky.RAIN
    in 71..77, 85, 86 -> Sky.SNOW
    in 95..99 -> Sky.STORM
    else -> Sky.CLOUD
}

/**
 * The weather as a cel drawing in ink outlines: a sun with rays (a moon when
 * [night]), a puffy cloud, and rain drops, snow, a lightning bolt or fog bars
 * under it. Drawn once per size.
 */
@Composable
internal fun AnimeWeatherGlyph(code: Int, night: Boolean, modifier: Modifier) {
    val t = animeTones
    val sky = skyOf(code)
    Spacer(
        modifier.cachedDraw(sky, night, t) {
            val s = size.minDimension
            val c = size.center
            val line = (s * 0.035f).coerceAtLeast(2f)
            val round = StrokeJoin.Round
            val cloudFill = if (t.light) Color.White else Color(0xFFE6E0FA)
            val withCloud = sky != Sky.SUN
            val sunC = if (withCloud) Offset(c.x - s * 0.14f, c.y - s * 0.16f) else c
            val sunR = s * if (withCloud) 0.2f else 0.26f
            val moon = Path().apply {
                op(
                    Path().apply { addOval(Rect(sunC, sunR)) },
                    Path().apply { addOval(Rect(sunC + Offset(sunR * 0.45f, -sunR * 0.3f), sunR * 0.85f)) },
                    androidx.compose.ui.graphics.PathOperation.Difference
                )
            }
            // A cloud: three puffs on a flat base.
            val cy = if (sky == Sky.CLOUD || sky == Sky.PARTLY) c.y + s * 0.06f else c.y - s * 0.04f
            val puffs = listOf(
                Offset(c.x - s * 0.18f, cy + s * 0.04f) to s * 0.15f,
                Offset(c.x + s * 0.02f, cy - s * 0.06f) to s * 0.2f,
                Offset(c.x + s * 0.2f, cy + s * 0.05f) to s * 0.14f
            )
            val base = Rect(c.x - s * 0.33f, cy + s * 0.02f, c.x + s * 0.34f, cy + s * 0.19f)
            onDrawBehind {
                if (sky == Sky.SUN || sky == Sky.PARTLY) {
                    if (night) {
                        drawPath(moon, t.ink, style = Stroke(line * 2f, join = round))
                        drawPath(moon, Color(0xFFFFF1C9))
                    } else {
                        for (k in 0 until 8) {
                            rotate(k * 45f, sunC) {
                                val a = Offset(sunC.x, sunC.y - sunR * 1.25f)
                                val b = Offset(sunC.x, sunC.y - sunR * 1.6f)
                                drawLine(t.ink, a, b, line * 3f, StrokeCap.Round)
                                drawLine(t.yellow, a, b, line * 1.2f, StrokeCap.Round)
                            }
                        }
                        drawCircle(t.ink, sunR + line, sunC)
                        drawCircle(t.yellow, sunR - line * 0.2f, sunC)
                    }
                }
                if (withCloud) {
                    for ((pc, r) in puffs) drawCircle(t.ink, r + line, pc)
                    drawRoundRect(t.ink, base.topLeft - Offset(line, line), Size(base.width + 2f * line, base.height + 2f * line), CornerRadius(base.height))
                    for ((pc, r) in puffs) drawCircle(cloudFill, r - line * 0.2f, pc)
                    drawRoundRect(cloudFill, base.topLeft, base.size, CornerRadius(base.height))
                }
                val below = base.bottom + s * 0.06f
                when (sky) {
                    Sky.RAIN -> for (k in 0..2) {
                        val dx = c.x + (k - 1) * s * 0.2f
                        val drop = Path().apply {
                            moveTo(dx, below)
                            quadraticTo(dx + s * 0.07f, below + s * 0.12f, dx, below + s * 0.15f)
                            quadraticTo(dx - s * 0.07f, below + s * 0.12f, dx, below)
                            close()
                        }
                        drawPath(drop, t.ink, style = Stroke(line * 1.6f, join = round))
                        drawPath(drop, Color(0xFF5DBBFF))
                    }
                    Sky.SNOW -> for (k in 0..2) {
                        val p = Offset(c.x + (k - 1) * s * 0.2f, below + s * 0.07f + (k % 2) * s * 0.05f)
                        drawCircle(t.ink, s * 0.05f + line * 0.8f, p)
                        drawCircle(Color.White, s * 0.05f, p)
                    }
                    Sky.STORM -> {
                        val bolt = Path().apply {
                            moveTo(c.x + s * 0.04f, below - s * 0.04f)
                            lineTo(c.x - s * 0.1f, below + s * 0.12f)
                            lineTo(c.x, below + s * 0.12f)
                            lineTo(c.x - s * 0.06f, below + s * 0.26f)
                            lineTo(c.x + s * 0.12f, below + s * 0.06f)
                            lineTo(c.x + s * 0.02f, below + s * 0.06f)
                            close()
                        }
                        drawPath(bolt, t.ink, style = Stroke(line * 1.6f, join = round))
                        drawPath(bolt, t.yellow)
                    }
                    Sky.FOG -> for (k in 0..1) {
                        val yy = below + s * 0.04f + k * s * 0.09f
                        val a = Offset(c.x - s * 0.3f + k * s * 0.08f, yy)
                        val b = Offset(c.x + s * 0.26f + k * s * 0.06f, yy)
                        drawLine(t.ink, a, b, s * 0.05f + line * 1.6f, StrokeCap.Round)
                        drawLine(cloudFill, a, b, s * 0.05f, StrokeCap.Round)
                    }
                    else -> Unit
                }
            }
        }
    )
}
