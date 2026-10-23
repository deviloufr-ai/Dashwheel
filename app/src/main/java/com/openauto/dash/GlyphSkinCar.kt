package com.openauto.dash

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.openauto.dash.vehicle.rememberDoors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Glyph skin, the hero: the driver's own car as a cloud of dots. The car
 * pack's picture (MyCarLook.kt, its 3/4 view, else the side) is sampled once,
 * off the main thread, onto a grid of dots, each as large as that spot of the
 * car is dark (light by night), slightly scattered so it reads as a point
 * cloud, the tail lamps' dots in red. Without a car pack, the drawn MPV of the
 * Panorama look is turned to dots the same way. A dotted shadow under it, and
 * while parked a slow scan running across it; still while driving.
 */

/** Dot sizes, smallest first, as a share of a cell (radius). */
private val LEVEL_R = floatArrayOf(0.15f, 0.24f, 0.33f, 0.43f)

/**
 * The car as dots: for each dot its place (x, y as fractions of the picture,
 * sorted by x), its [tone] (0 dark to 1 light, against the car's own range)
 * and its [cover] (how much of the cell the car fills), plus whether it is on
 * a tail lamp ([red]). [cell] is one cell's width as a fraction of the
 * picture's width; [aspect] the picture's width over its height.
 */
internal class GlyphCloud(
    val aspect: Float,
    val cell: Float,
    val xy: FloatArray,
    val tone: FloatArray,
    val cover: FloatArray,
    val red: BooleanArray
) {
    val count: Int get() = tone.size
}

/**
 * [px] (ARGB, [cols] x [rows], transparent around the car) as a [GlyphCloud]:
 * one dot per cell the car covers, odd rows shifted half a cell so the grid
 * reads as a cloud, each nudged a little by a fixed scatter. Red cells (a
 * lamp) are marked, unless the car is red all over. Pure, so it is unit-tested.
 */
internal fun glyphCloudOf(px: IntArray, cols: Int, rows: Int): GlyphCloud? {
    if (cols <= 0 || rows <= 0 || px.size < cols * rows) return null
    // The car's own darkest and lightest (5th and 95th centile), so a dark car is not all one size.
    val hist = IntArray(256)
    var opaque = 0
    var reds = 0
    for (c in px) {
        if ((c ushr 24) < 90) continue
        opaque++
        hist[lum(c)]++
        if (isLampRed(c)) reds++
    }
    if (opaque == 0) return null
    val lo = centile(hist, opaque, 0.05f)
    val hi = centile(hist, opaque, 0.95f).coerceAtLeast(lo + 1)
    val markRed = reds < opaque * 0.06f
    val n = px.count { (it ushr 24) >= 90 }
    val xs = FloatArray(n)
    val ys = FloatArray(n)
    val tones = FloatArray(n)
    val covers = FloatArray(n)
    val reddish = BooleanArray(n)
    var k = 0
    for (y in 0 until rows) for (x in 0 until cols) {
        val c = px[y * cols + x]
        val a = c ushr 24
        if (a < 90) continue
        val h = scatter(x, y)
        val jx = ((h and 0xFF) / 255f - 0.5f) * 0.44f
        val jy = (((h ushr 8) and 0xFF) / 255f - 0.5f) * 0.44f
        xs[k] = (x + 0.5f + (if (y % 2 == 1) 0.5f else 0f) + jx) / cols
        ys[k] = (y + 0.5f + jy) / rows
        tones[k] = ((lum(c) - lo).toFloat() / (hi - lo)).coerceIn(0f, 1f)
        covers[k] = a / 255f
        reddish[k] = markRed && isLampRed(c)
        k++
    }
    // Sorted by x, so the scan finds its band of dots by a binary search.
    val order = (0 until n).sortedBy { xs[it] }
    val xy = FloatArray(n * 2)
    val t = FloatArray(n)
    val cv = FloatArray(n)
    val rd = BooleanArray(n)
    order.forEachIndexed { i, j ->
        xy[i * 2] = xs[j]
        xy[i * 2 + 1] = ys[j]
        t[i] = tones[j]
        cv[i] = covers[j]
        rd[i] = reddish[j]
    }
    return GlyphCloud(cols.toFloat() / rows, 1f / cols, xy, t, cv, rd)
}

private fun lum(c: Int): Int {
    val r = (c shr 16) and 0xFF
    val g = (c shr 8) and 0xFF
    val b = c and 0xFF
    return (r * 77 + g * 151 + b * 28) shr 8
}

/** A lamp's red, as [rearLamps] finds it. */
private fun isLampRed(c: Int): Boolean {
    val r = (c shr 16) and 0xFF
    val g = (c shr 8) and 0xFF
    val b = c and 0xFF
    return r > 110 && r > g * 1.6f && r > b * 1.4f
}

private fun centile(hist: IntArray, total: Int, q: Float): Int {
    val target = (total * q).toInt()
    var sum = 0
    for (i in hist.indices) {
        sum += hist[i]
        if (sum > target) return i
    }
    return 255
}

/** A fixed scatter for cell ([x], [y]): the same cloud every time. */
private fun scatter(x: Int, y: Int): Int {
    var h = x * 374761393 + y * 668265263
    h = (h xor (h ushr 13)) * 1274126177
    return h xor (h ushr 16)
}

/** The size a dot of [tone] and [cover] is drawn at: dark parts large on the light panel, light parts large on the black one. */
internal fun glyphDotLevel(tone: Float, cover: Float, light: Boolean): Int {
    val ink = if (light) 1f - tone else tone
    val v = cover * (0.3f + 0.7f * ink)
    return (v * LEVEL_R.size).toInt().coerceIn(0, LEVEL_R.size - 1)
}

/** [src] sampled onto [cols] cells across: halved step by step first, so every pixel counts, not one in ten. */
private fun sampleBitmap(src: Bitmap, cols: Int): GlyphCloud? {
    val soft = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
    val rows = (cols * soft.height.toFloat() / soft.width).roundToInt().coerceAtLeast(1)
    var b = soft
    while (b.width / 2 >= cols * 2 && b.height / 2 >= rows * 2) {
        val next = Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true)
        if (b !== soft) b.recycle()
        b = next
    }
    val small = Bitmap.createScaledBitmap(b, cols, rows, true)
    if (b !== soft && b !== small) b.recycle()
    val px = IntArray(cols * rows)
    small.getPixels(px, 0, cols, 0, 0, cols, rows)
    if (small !== soft) small.recycle()
    return glyphCloudOf(px, cols, rows)
}

/** The drawn MPV (Panorama's) painted into a picture to sample, for when no car was imported. */
private fun drawnCarBitmap(): Bitmap {
    val w = 800
    val h = (w / PANO_CAR_ASPECT).roundToInt()
    val image = ImageBitmap(w, h)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(w.toFloat(), h.toFloat())) {
        drawPanoCar(Rect(0f, 0f, w.toFloat(), h.toFloat()), light = true)
    }
    return image.asAndroidBitmap()
}

/** The dots laid out in a box: the points of each size and the red ones, in px, ready to draw. */
private class PlacedCloud(
    val levels: Array<FloatArray>,
    val red: FloatArray,
    val floor: Array<FloatArray>,
    val pitch: Float,
    val rect: Rect
)

private fun place(cloud: GlyphCloud, rect: Rect, light: Boolean): PlacedCloud {
    val counts = IntArray(LEVEL_R.size)
    var reds = 0
    for (i in 0 until cloud.count) {
        if (cloud.red[i]) reds++ else counts[glyphDotLevel(cloud.tone[i], cloud.cover[i], light)]++
    }
    val levels = Array(LEVEL_R.size) { FloatArray(counts[it] * 2) }
    val red = FloatArray(reds * 2)
    val at = IntArray(LEVEL_R.size)
    var r = 0
    for (i in 0 until cloud.count) {
        val x = rect.left + cloud.xy[i * 2] * rect.width
        val y = rect.top + cloud.xy[i * 2 + 1] * rect.height
        if (cloud.red[i]) {
            red[r++] = x; red[r++] = y
        } else {
            val l = glyphDotLevel(cloud.tone[i], cloud.cover[i], light)
            levels[l][at[l]++] = x
            levels[l][at[l]++] = y
        }
    }
    // The shadow: the same grid in an ellipse under the wheels, its dots shrinking outward.
    val pitch = cloud.cell * rect.width
    val cx = rect.center.x
    val cy = rect.bottom - rect.height * 0.02f
    val rx = rect.width * 0.56f
    val ry = max(rect.height * 0.09f, pitch * 2.5f)
    val bands = Array(3) { ArrayList<Float>() }
    var y = cy - ry
    var row = 0
    while (y <= cy + ry) {
        var x = cx - rx + if (row % 2 == 1) pitch / 2f else 0f
        while (x <= cx + rx) {
            val d = ((x - cx) / rx).let { it * it } + ((y - cy) / ry).let { it * it }
            if (d < 1f) {
                val band = when {
                    d < 0.3f -> 0
                    d < 0.65f -> 1
                    else -> 2
                }
                bands[band].add(x); bands[band].add(y)
            }
            x += pitch
        }
        y += pitch
        row++
    }
    return PlacedCloud(levels, red, Array(3) { bands[it].toFloatArray() }, pitch, rect)
}

/** One pass of the scan across the car, in ms (the band runs past both ends, so it rests a moment between passes). */
private const val SCAN_MS = 6_500

/**
 * The hero filling [modifier]'s box: the car as dots, as large as fits, its
 * shadow in dots, the scan while parked, and the caption under it: a red
 * light (blinking while parked) and the car's name, range, fuel and LIVE in
 * tracked mono. Red goes to the lamps and the light, or to the range alone
 * when the tank is low.
 */
@Composable
internal fun GlyphCarHero(env: SkinTileEnv, fuel: FuelInfo?, modifier: Modifier = Modifier) {
    val mine by MyCarLook.shown.collectAsState()
    val profile by CarProfileStore.profile.collectAsState()
    val doors = rememberDoors()
    val view = mine?.portrait
    val light = DashColors.Light
    val effects = DashColors.Effects
    val ink = DashColors.TextPrimary
    val red = glyphRed
    val moving = LocalCarMoving.current
    val dens = LocalDensity.current
    val said = stringResource(R.string.glyph_car_cd)
    BoxWithConstraints(modifier.semantics { contentDescription = said }) {
        val captionH = 34.dp
        val areaW = maxWidth * 0.94f
        val areaH = maxHeight - captionH - 20.dp
        val aspect = view?.aspect ?: PANO_CAR_ASPECT
        val carW = min(areaW.value, areaH.value * aspect)
        val cols = ((carW / 4.4f / 10f).roundToInt() * 10).coerceIn(60, 190)
        val cloud by produceState<GlyphCloud?>(null, view, cols) {
            value = withContext(Dispatchers.Default) {
                runCatching { sampleBitmap(view?.photo?.asAndroidBitmap() ?: drawnCarBitmap(), cols) }.getOrNull()
            }
        }
        val c = cloud
        val boxW = with(dens) { maxWidth.toPx() }
        val boxH = with(dens) { (maxHeight - captionH).toPx() }
        val placed = remember(c, boxW, boxH, light) {
            c?.let {
                val w = min(boxW * 0.94f, (boxH - with(dens) { 20.dp.toPx() }) * it.aspect)
                val h = w / it.aspect
                val top = (boxH - h) / 2f - h * 0.04f
                place(it, Rect(Offset((boxW - w) / 2f, top), Size(w, h)), light)
            }
        }
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (placed != null) {
                    GlyphCloudLayer(placed, ink, red, effects, Modifier.fillMaxSize())
                    if (!moving && effects != DashEffects.NONE) GlyphScan(placed, ink, Modifier.fillMaxSize())
                }
                if (env.editing) {
                    Spacer(
                        Modifier.fillMaxSize().cachedDraw(ink) {
                            val stroke = Stroke(1.5f * density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f * density, 7f * density)))
                            onDrawBehind { drawRoundRect(ink.copy(alpha = 0.35f), style = stroke, cornerRadius = CornerRadius(GLYPH_CARD.toPx())) }
                        }
                    )
                }
            }
            val name = (profile.model.ifBlank { profile.name }.ifBlank { mine?.name.orEmpty() }).ifBlank { stringResource(R.string.glyph_my_car) }
            GlyphCaption(env, fuel, name, doors?.anyOpen == true, Modifier.fillMaxWidth().height(captionH))
        }
    }
}

/** The car's dots, drawn once into their own layer (only a new car, size or look redraws them). */
@Composable
private fun GlyphCloudLayer(placed: PlacedCloud, ink: Color, red: Color, effects: DashEffects, modifier: Modifier) {
    val light = DashColors.Light
    Spacer(
        modifier
            .graphicsLayer()
            .cachedDraw(placed, ink, red, effects, light) {
                val paints = Array(LEVEL_R.size) { glyphDotPaint(ink, placed.pitch * LEVEL_R[it] * 2f) }
                val redPaint = glyphDotPaint(red, placed.pitch * LEVEL_R[2] * 2f)
                val redGlow = if (effects != DashEffects.NONE) glyphDotPaint(red.copy(alpha = 0.18f * effects.scale), placed.pitch * 1.6f) else null
                val floorInk = ink.copy(alpha = if (light) 0.32f else 0.4f)
                val floorPaints = Array(3) { glyphDotPaint(floorInk, placed.pitch * (0.62f - it * 0.18f)) }
                onDrawBehind {
                    for (i in placed.floor.indices) drawGlyphPoints(placed.floor[i], floorPaints[i])
                    for (i in placed.levels.indices) drawGlyphPoints(placed.levels[i], paints[i])
                    if (redGlow != null) drawGlyphPoints(placed.red, redGlow)
                    drawGlyphPoints(placed.red, redPaint)
                }
            }
    )
}

/**
 * The scan: a band of dots swelling as it runs slowly across the car, with a
 * hairline at its middle; its own layer, so each step redraws only the band.
 * Ambient motion: it holds while driving and off screen (rememberLoop) and
 * the caller leaves it out while moving or with effects off.
 */
@Composable
private fun GlyphScan(placed: PlacedCloud, ink: Color, modifier: Modifier) {
    val t = rememberLoop(SCAN_MS)
    Spacer(
        modifier
            .graphicsLayer()
            .cachedDraw(placed, ink) {
                val paints = Array(LEVEL_R.size) { glyphDotPaint(ink, placed.pitch * (LEVEL_R[it] * 2f + 0.28f)) }
                val band = placed.pitch * 3f
                val rect = placed.rect
                val line = ink.copy(alpha = 0.16f)
                onDrawBehind {
                    val x = rect.left + (t.value * 1.4f - 0.2f) * rect.width
                    if (x < rect.left - band || x > rect.right + band) return@onDrawBehind
                    drawLine(line, Offset(x, rect.top - placed.pitch * 2f), Offset(x, rect.bottom + placed.pitch * 2f), density)
                    for (i in placed.levels.indices) {
                        val pts = placed.levels[i]
                        val from = firstAtOrAfter(pts, x - band)
                        val to = firstAtOrAfter(pts, x + band)
                        drawGlyphPoints(pts, from * 2, (to - from) * 2, paints[i])
                    }
                }
            }
    )
}

/** Index of the first point of [pts] (x, y pairs sorted by x) whose x is at least [x]. */
private fun firstAtOrAfter(pts: FloatArray, x: Float): Int {
    var lo = 0
    var hi = pts.size / 2
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (pts[mid * 2] < x) lo = mid + 1 else hi = mid
    }
    return lo
}

/**
 * The caption under the car: a red light, blinking while parked and live,
 * then the name, the range and the fuel, LIVE while a reading comes from the
 * car, and DOOR OPEN when one is.
 */
@Composable
private fun GlyphCaption(env: SkinTileEnv, fuel: FuelInfo?, name: String, doorOpen: Boolean, modifier: Modifier) {
    val units = LocalUnits.current
    val live = env.obdConnection == ObdConnectionState.CONNECTED || fuel != null
    val moving = LocalCarMoving.current
    val blink = if (live && !moving) rememberBlink(600L) else null
    val red = glyphRed
    val muted = DashColors.Muted
    val low = fuel != null && fuel.percent <= SKIN_LOW_FUEL_PCT
    val liveWord = stringResource(R.string.glyph_live)
    val doorWord = stringResource(R.string.glyph_door_open)
    val locale = Locale.getDefault()
    val text = buildAnnotatedString {
        append(name.uppercase(locale))
        if (fuel != null) {
            append(" · ")
            if (low) withStyle(SpanStyle(color = red)) { append(units.distanceText(fuel.rangeKm).uppercase(locale)) }
            else append(units.distanceText(fuel.rangeKm).uppercase(locale))
            append(" · ${fuel.percent} %")
        }
        if (live) append(" · ${liveWord.uppercase(locale)}")
        if (doorOpen) {
            append(" · ")
            withStyle(SpanStyle(color = DashColors.Warning)) { append(doorWord.uppercase(locale)) }
        }
    }
    Row(modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Spacer(
            Modifier.size(10.dp).cachedDraw(live, blink, red, muted) {
                onDrawBehind {
                    if (!live) drawCircle(muted, this.size.minDimension / 2f - density, style = Stroke(1.5f * density))
                    // Read here, so a blink redraws the light alone.
                    else if (blink?.value != false) drawCircle(red)
                }
            }
        )
        Spacer(Modifier.width(10.dp))
        Text(text, style = glyphMono(14f, muted, FontWeight.Medium, caps = true), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

/** The hero's size at least: under it a range tile is a module with the range in dots. */
internal val GLYPH_HERO_MIN_W = 360.dp
internal val GLYPH_HERO_MIN_H = 220.dp
