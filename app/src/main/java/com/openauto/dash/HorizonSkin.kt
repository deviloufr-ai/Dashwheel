package com.openauto.dash

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random
import androidx.compose.ui.layout.layoutId
import androidx.compose.foundation.layout.widthIn

/*
 * Horizon skin: no widgets and no boxes. The page is a living scene (in dark
 * mode an evening whose sky follows the clock from night to dusk, with stars;
 * in light mode a bright noon), a skyline that pulses like an equalizer and a
 * road running to the horizon, and every tile is typography set straight on
 * it: serif display numbers, italic serif units, small sans lines and
 * letter-spaced caps, all with a soft shadow (a pale halo by day) so they read
 * over every part of the scene.
 */

// --- Shared look ------------------------------------------------------------------

/** Text shadows and the soft halos behind icons: deep indigo in the evening, warm white by day. */
private val Shade get() = if (DashColors.Light) Color(0xFFFFFAF0) else Color(0xFF07091C)

/** Ink on the warm filled play button. */
private val PlayInk get() = if (DashColors.Light) DashColors.OnAccent else Color(0xFF2A1530)

private val TileShape = RoundedCornerShape(20.dp)
private val TilePad = 12.dp
private const val DOT = " · "

/** Horizon line, as a fraction of the page height. */
private const val HORIZON = 0.66f

/** Where each tile's text block sits: toward the screen edge the tile is nearest. */
private enum class Side(val h: Alignment.Horizontal, val box: Alignment, val text: TextAlign) {
    START(Alignment.Start, Alignment.CenterStart, TextAlign.Start),
    CENTER(Alignment.CenterHorizontally, Alignment.Center, TextAlign.Center),
    END(Alignment.End, Alignment.CenterEnd, TextAlign.End)
}

private fun sideOf(item: DashboardItem): Side {
    val mid = item.x + item.w / 2f
    return when {
        mid < GRID_COLS * 0.42f -> Side.START
        mid > GRID_COLS * 0.58f -> Side.END
        else -> Side.CENTER
    }
}

private val DisplayLineHeight = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

/**
 * Soft shadow scaled to the text size, so type stays legible over the whole scene:
 * a dark drop shadow in the evening, a pale halo centred on the ink by day.
 * Blurred only with effects at full (see [softTextShadow]).
 */
@Composable
private fun softShadow(sizeSp: Float): Shadow? {
    val d = LocalDensity.current.density
    val light = DashColors.Light
    return softTextShadow(
        color = Shade.copy(alpha = if (light) 0.7f else 0.6f),
        blurRadius = (sizeSp * 0.22f).coerceIn(5f, 26f) * d,
        offset = if (light) Offset.Zero else Offset(0f, (1f + sizeSp * 0.015f) * d)
    )
}

/** Big serif display type (numbers, titles); [italic] for units and quiet states. */
@Composable
private fun display(sizeSp: Float, color: Color = DashColors.TextPrimary, italic: Boolean = false): TextStyle = TextStyle(
    color = color,
    fontFamily = FontFamily.Serif,
    fontWeight = FontWeight.Normal,
    fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
    fontSize = sizeSp.sp,
    lineHeight = 1.05.em,
    lineHeightStyle = DisplayLineHeight,
    letterSpacing = if (italic) 0.em else (-0.02).em,
    shadow = softShadow(sizeSp)
)

/** Small sans UI text. */
@Composable
private fun ui(
    sizeSp: Float,
    color: Color = DashColors.TextSecondary,
    weight: FontWeight = FontWeight.Normal
): TextStyle = TextStyle(
    color = color,
    fontFamily = FontFamily.SansSerif,
    fontWeight = weight,
    fontSize = sizeSp.sp,
    lineHeight = 1.3.em,
    letterSpacing = 0.01.em,
    shadow = softShadow(sizeSp)
)

/** Letter-spaced small caps label ("NOW PLAYING"). */
@Composable
private fun caps(sizeSp: Float, color: Color = DashColors.Muted): TextStyle = TextStyle(
    color = color,
    fontFamily = FontFamily.SansSerif,
    fontWeight = FontWeight.Medium,
    fontSize = sizeSp.sp,
    lineHeight = 1.3.em,
    letterSpacing = 0.25.em,
    shadow = softShadow(sizeSp)
)

/** One run of scene text: no background, single line unless [maxLines] says otherwise. */
@Composable
private fun SceneText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    align: TextAlign? = null,
    overflow: TextOverflow = TextOverflow.Ellipsis
) {
    Text(
        text = text,
        modifier = modifier,
        style = if (align != null) style.copy(textAlign = align) else style,
        maxLines = maxLines,
        softWrap = maxLines > 1,
        overflow = overflow
    )
}

/**
 * Largest font size in sp (within [minSp]..[maxSp]) at which [sample] set in
 * [style] fits [maxW] on one line with its line box no taller than [maxH].
 * Measured once at 100 sp and scaled, since type grows linearly with size.
 */
@Composable
private fun fitSp(sample: String, style: TextStyle, maxW: Dp, maxH: Dp, minSp: Float, maxSp: Float): Float {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(sample, style, maxW, maxH, minSp, maxSp, density) {
        val probe = measurer.measure(sample, style.copy(fontSize = 100.sp, shadow = null), softWrap = false, maxLines = 1)
        val w = probe.size.width.coerceAtLeast(1)
        val h = probe.size.height.coerceAtLeast(1)
        val byW = with(density) { maxW.toPx() } * 100f / w
        val byH = with(density) { maxH.toPx() } * 100f / h
        min(byW, byH).coerceIn(minSp, maxSp)
    }
}

/** The first of [candidates] (longest first) that fits [maxW] on one line, else the last; measured again only when they change. */
@Composable
private fun firstFitting(candidates: List<String>, style: TextStyle, maxW: Dp): String {
    val measurer = rememberTextMeasurer()
    val px = with(LocalDensity.current) { maxW.toPx() }
    return remember(candidates, style, px, measurer) {
        candidates.firstOrNull {
            measurer.measure(it, style, softWrap = false, maxLines = 1).size.width <= px
        } ?: candidates.last()
    }
}

/** Digits replaced by '8' so a readout keeps one size while its value changes. */
private fun template(text: String): String = text.map { if (it.isDigit()) '8' else it }.joinToString("")

/** Whole tile tappable (with a soft rounded ripple), or nothing when [enabled] is false. */
private fun Modifier.tap(enabled: Boolean, label: String, onClick: () -> Unit): Modifier =
    if (enabled) clip(TileShape).clickable(onClickLabel = label, role = Role.Button, onClick = onClick) else this

// --- Background scene -------------------------------------------------------------

private const val REF_W = 1280f
private const val REF_H = 720f
private const val REF_HORIZON = 472f
private const val LOOP_MS = 120_000
private const val TWO_PI = 6.2831855f
private const val STAR_COUNT = 170
private const val DUSK_STARS = 80
private const val DASH_GAP = 0.34f
private const val DASH_COUNT = 20
private const val DASH_CYCLES = 110

/** Positions of the sky gradient stops, top of the page to the horizon. */
private val SKY_STOPS = floatArrayOf(0f, 0.5f, 0.78f, 0.92f, 1f)

/** Every colour of the scene at one time of day; looks blend between keyframes. */
private data class SkyLook(
    val sky: List<Color>,
    val glow: Color,
    val glowOuter: Color,
    val glowAlpha: Float,
    val stars: Float,
    val extraStars: Float,
    val moon: Float,
    val sun: Float,
    val hillFar: Color,
    val hillNear: Color,
    val building: Color,
    val buildingEdge: Color,
    val groundTop: Color,
    val groundBottom: Color,
    val roadTop: Color,
    val roadBottom: Color,
    val lane: Color,
    val roadEdge: Color
)

private val NightLook = SkyLook(
    sky = listOf(Color(0xFF03051A), Color(0xFF080C28), Color(0xFF10143A), Color(0xFF1C1A4C), Color(0xFF2C2458)),
    glow = Color(0xFF8C78D8), glowOuter = Color(0xFF3A2A7A), glowAlpha = 0.3f,
    stars = 1f, extraStars = 1f, moon = 1f, sun = 0f,
    hillFar = Color(0x991A1A40), hillNear = Color(0xF0100C28),
    building = Color(0xFF0A0716), buildingEdge = Color(0x8CF9B274),
    groundTop = Color(0xFF120C24), groundBottom = Color(0xFF040308),
    roadTop = Color(0xFF1E172B), roadBottom = Color(0xFF0A0710),
    lane = Color(0x80F9D9A0), roadEdge = Color(0x47FFECD2)
)

private val DuskLook = SkyLook(
    sky = listOf(Color(0xFF0A0F2C), Color(0xFF2B2160), Color(0xFF8A3F72), Color(0xFFE47459), Color(0xFFF9B274)),
    glow = Color(0xFFFFD6A0), glowOuter = Color(0xFFFF8A5C), glowAlpha = 0.8f,
    stars = 0.9f, extraStars = 0.15f, moon = 1f, sun = 0f,
    hillFar = Color(0x735A3272), hillNear = Color(0xE62E1E4E),
    building = Color(0xFF140C24), buildingEdge = Color(0x8CF9B274),
    groundTop = Color(0xFF1A1030), groundBottom = Color(0xFF06040C),
    roadTop = Color(0xFF33253F), roadBottom = Color(0xFF120D1A),
    lane = Color(0x9EF9D9A0), roadEdge = Color(0x59FFECD2)
)

/**
 * Light mode's fixed noon, made for dark ink: a pale sky with a soft sun, hazy
 * hills and a blue-grey skyline, a meadow fading to sand and light asphalt
 * with white markings, so text reads everywhere, below the horizon too.
 */
private val NoonLook = SkyLook(
    sky = listOf(Color(0xFF8FC1EE), Color(0xFFA9D0F3), Color(0xFFCFE4F5), Color(0xFFF0F1EA), Color(0xFFFFF6E8)),
    glow = Color(0xFFFFF8E8), glowOuter = Color(0xFFFFE6C2), glowAlpha = 0.7f,
    stars = 0f, extraStars = 0f, moon = 0f, sun = 1f,
    hillFar = Color(0xA6A9BFD6), hillNear = Color(0xFFC5D3AE),
    building = Color(0xFF97A9C2), buildingEdge = Color(0xB3FFFFFF),
    groundTop = Color(0xFFDCE3C2), groundBottom = Color(0xFFEADFC2),
    roadTop = Color(0xFFC3C6CB), roadBottom = Color(0xFFA0A4AB),
    lane = Color(0xF2FFFFFF), roadEdge = Color(0xCCFFFFFF)
)

/** Hour of day → evening look (dark mode): night 21:00–05:00, dusk light in between and never brighter. */
private val SkyKeys: List<Pair<Float, SkyLook>> = listOf(
    0f to NightLook, 5f to NightLook, 5.75f to DuskLook, 20.5f to DuskLook, 21f to NightLook, 24f to NightLook
)

private fun mixF(a: Float, b: Float, f: Float): Float = a + (b - a) * f

private fun mixLook(a: SkyLook, b: SkyLook, f: Float): SkyLook = SkyLook(
    sky = List(a.sky.size) { lerp(a.sky[it], b.sky[it], f) },
    glow = lerp(a.glow, b.glow, f), glowOuter = lerp(a.glowOuter, b.glowOuter, f),
    glowAlpha = mixF(a.glowAlpha, b.glowAlpha, f),
    stars = mixF(a.stars, b.stars, f), extraStars = mixF(a.extraStars, b.extraStars, f),
    moon = mixF(a.moon, b.moon, f), sun = mixF(a.sun, b.sun, f),
    hillFar = lerp(a.hillFar, b.hillFar, f), hillNear = lerp(a.hillNear, b.hillNear, f),
    building = lerp(a.building, b.building, f), buildingEdge = lerp(a.buildingEdge, b.buildingEdge, f),
    groundTop = lerp(a.groundTop, b.groundTop, f), groundBottom = lerp(a.groundBottom, b.groundBottom, f),
    roadTop = lerp(a.roadTop, b.roadTop, f), roadBottom = lerp(a.roadBottom, b.roadBottom, f),
    lane = lerp(a.lane, b.lane, f), roadEdge = lerp(a.roadEdge, b.roadEdge, f)
)

/** The scene at [date]: the clock-driven evening, or the fixed noon when [light]. */
private fun skyLookAt(date: Date, light: Boolean): SkyLook {
    if (light) return NoonLook
    val cal = Calendar.getInstance().apply { time = date }
    val hour = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60f
    for (i in 1 until SkyKeys.size) {
        val (h1, b) = SkyKeys[i]
        if (hour <= h1) {
            val (h0, a) = SkyKeys[i - 1]
            if (a === b) return a
            val f = ((hour - h0) / (h1 - h0)).coerceIn(0f, 1f)
            return mixLook(a, b, f * f * (3f - 2f * f))
        }
    }
    return NightLook
}

/** The scene's colour at [yFrac] of the page height (sky above the horizon, ground below). */
private fun sceneColorAt(look: SkyLook, yFrac: Float): Color {
    if (yFrac >= HORIZON) {
        return lerp(look.groundTop, look.groundBottom, ((yFrac - HORIZON) / (1f - HORIZON)).coerceIn(0f, 1f))
    }
    val f = (yFrac / HORIZON).coerceIn(0f, 1f)
    for (i in 1 until SKY_STOPS.size) {
        if (f <= SKY_STOPS[i]) {
            return lerp(look.sky[i - 1], look.sky[i], (f - SKY_STOPS[i - 1]) / (SKY_STOPS[i] - SKY_STOPS[i - 1]))
        }
    }
    return look.sky.last()
}

/** Near hills (from the mockup) and a taller far range: start point, then cubic segments, in 1280×720 space. */
private val NearHills = floatArrayOf(
    0f, 432f,
    60f, 420f, 120f, 402f, 190f, 412f,
    260f, 424f, 300f, 396f, 380f, 400f,
    470f, 405f, 520f, 430f, 600f, 440f,
    627f, 442f, 653f, 444f, 680f, 446f,
    760f, 438f, 840f, 410f, 930f, 404f,
    1020f, 398f, 1080f, 420f, 1160f, 414f,
    1210f, 410f, 1250f, 420f, 1280f, 424f
)
private val FarHills = floatArrayOf(
    0f, 420f,
    90f, 410f, 170f, 392f, 260f, 398f,
    350f, 404f, 400f, 384f, 490f, 388f,
    580f, 392f, 630f, 414f, 720f, 418f,
    810f, 422f, 870f, 396f, 960f, 392f,
    1050f, 388f, 1130f, 408f, 1210f, 404f,
    1240f, 402f, 1262f, 408f, 1280f, 410f
)

/** Road centre line control x (bending right as it recedes) and half widths, bottom → horizon. */
private val RoadCentre = floatArrayOf(640f, 646f, 662f, 673f)
private val RoadHalf = floatArrayOf(262f, 177f, 93f, 9f)

private fun cubic(t: Float, a: Float, b: Float, c: Float, d: Float): Float {
    val u = 1f - t
    return u * u * u * a + 3f * u * u * t * b + 3f * u * t * t * c + t * t * t * d
}

/** Seeded stars and skyline, generated once; the skyline's top edges are drawn in one native call. */
private class HorizonScene {
    val starX = FloatArray(STAR_COUNT)
    val starY = FloatArray(STAR_COUNT)
    val starR = FloatArray(STAR_COUNT)
    val starK = IntArray(STAR_COUNT)
    val starPhase = FloatArray(STAR_COUNT)
    val bx: FloatArray
    val bw: FloatArray
    val bh: FloatArray
    val bk: IntArray
    val bPhase: FloatArray
    val edges: FloatArray
    val edgePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
    }

    init {
        val rnd = Random(7)
        val radii = floatArrayOf(0.7f, 0.9f, 1.1f, 1.5f)
        for (i in 0 until STAR_COUNT) {
            starX[i] = rnd.nextFloat()
            val r = rnd.nextFloat()
            starY[i] = 0.02f + 0.43f * r * r.coerceAtLeast(0.35f)
            starR[i] = radii[rnd.nextInt(radii.size)] * 1.15f
            // Every seventh star twinkles, 1.7–4 s per cycle (a whole number of cycles per loop).
            starK[i] = if (i % 7 == 0) 30 + rnd.nextInt(41) else 0
            starPhase[i] = rnd.nextFloat() * TWO_PI
        }
        val xs = ArrayList<Float>()
        val ws = ArrayList<Float>()
        val hs = ArrayList<Float>()
        var x = 14f
        val widths = floatArrayOf(10f, 12f, 14f, 18f, 22f)
        val gaps = floatArrayOf(3f, 4f, 6f)
        while (x < 1266f) {
            val w = widths[rnd.nextInt(widths.size)]
            // Leave the road's vanishing point open.
            if (!(x + w > 628f && x < 716f)) {
                xs += x
                ws += w
                hs += 14f + rnd.nextInt(53)
            }
            x += w + gaps[rnd.nextInt(gaps.size)]
        }
        bx = xs.toFloatArray()
        bw = ws.toFloatArray()
        bh = hs.toFloatArray()
        bk = IntArray(bx.size) { 30 + rnd.nextInt(46) }
        bPhase = FloatArray(bx.size) { rnd.nextFloat() * TWO_PI }
        edges = FloatArray(bx.size * 4)
    }
}

/**
 * The whole page as a living scene: in dark mode a sky that follows the real
 * time of day (indigo night with stars and a crescent moon, the warm dusk /
 * dawn gradient the rest of the day), in light mode a pale noon sky with a soft
 * sun; then hills, a skyline whose buildings pulse like an equalizer, the
 * ground and a road running to a vanishing point with centre dashes moving
 * toward the viewer. Animated values are read only while drawing; the scene
 * moves slowly, so it steps with the ambient ticker (about 20 times a second,
 * and stands still with effects off).
 */
@Composable
internal fun horizonBackground(): Modifier {
    val now = rememberNow(60_000L)
    val light = DashColors.Light
    val look = remember(now, light) { skyLookAt(now, light) }
    val scene = remember { HorizonScene() }
    val time = rememberLoop(LOOP_MS)
    return remember(look, scene, time) { sceneModifier(look, scene, time) }
}

private fun hillPath(points: FloatArray, sx: Float, s: Float, hy: Float, w: Float): Path {
    fun y(v: Float) = hy + (v - REF_HORIZON) * s
    return Path().apply {
        moveTo(0f, hy)
        lineTo(points[0] * sx, y(points[1]))
        var i = 2
        while (i + 5 < points.size) {
            cubicTo(
                points[i] * sx, y(points[i + 1]),
                points[i + 2] * sx, y(points[i + 3]),
                points[i + 4] * sx, y(points[i + 5])
            )
            i += 6
        }
        lineTo(w, hy)
        close()
    }
}

private fun sceneModifier(look: SkyLook, scene: HorizonScene, time: State<Float>): Modifier = Modifier.drawWithCache {
    val w = size.width
    val h = size.height
    val hy = h * HORIZON
    val s = h / REF_H
    val sx = w / REF_W

    val skyBrush = Brush.verticalGradient(
        *Array(SKY_STOPS.size) { SKY_STOPS[it] to look.sky[it] }, startY = 0f, endY = hy
    )

    // A soft sun (noon only): no hard disc, so big numerals over it stay readable.
    val sunC = Offset(w * 0.8f, h * 0.2f)
    val sunHaloR = 260f * s
    val sunHalo = Brush.radialGradient(
        listOf(Color(0x66FFF8E6), Color(0x22FFF2D6), Color.Transparent), center = sunC, radius = sunHaloR
    )
    val sunCoreR = 64f * s
    val sunCore = Brush.radialGradient(
        0f to Color(0xD9FFFDF5), 0.3f to Color(0x8CFFF8E8), 1f to Color(0x00FFF6E0), center = sunC, radius = sunCoreR
    )

    val moonC = Offset(w * 0.742f, h * 0.178f)
    val moonR = 20f * s
    val moonPath = Path().apply {
        val disc = Path().apply { addOval(Rect(moonC, moonR)) }
        val bite = Path().apply { addOval(Rect(moonC + Offset(10f * s, -7f * s), 18f * s)) }
        op(disc, bite, PathOperation.Difference)
    }
    val moonHaloR = 90f * s
    val moonHalo = Brush.radialGradient(listOf(Color(0x30F4EBD9), Color.Transparent), center = moonC, radius = moonHaloR)

    val glowC = Offset(w * 0.594f, hy)
    val glowRx = w * 0.375f
    val glowRy = h * 0.167f
    val glowBrush = Brush.radialGradient(
        listOf(look.glow.copy(alpha = look.glowAlpha), look.glowOuter.copy(alpha = 0f)), center = glowC, radius = glowRx
    )

    // Static stars in four draw calls: two sizes, dusk set and the extra night set.
    val starBrush = Brush.verticalGradient(listOf(Color.White, Color.White.copy(alpha = 0.12f)), startY = 0f, endY = h * 0.46f)
    val small = ArrayList<Offset>()
    val big = ArrayList<Offset>()
    val nightSmall = ArrayList<Offset>()
    val nightBig = ArrayList<Offset>()
    for (i in 0 until STAR_COUNT) {
        if (scene.starK[i] != 0) continue
        val p = Offset(scene.starX[i] * w, scene.starY[i] * h)
        val isBig = scene.starR[i] > 1.2f
        when {
            i < DUSK_STARS && isBig -> big += p
            i < DUSK_STARS -> small += p
            isBig -> nightBig += p
            else -> nightSmall += p
        }
    }
    val starFadeEnd = h * 0.46f

    val farHills = hillPath(FarHills, sx, s, hy, w)
    val nearHills = hillPath(NearHills, sx, s, hy, w)

    val groundBrush = Brush.verticalGradient(listOf(look.groundTop, look.groundBottom), startY = hy, endY = h)
    val horizonLine = Brush.horizontalGradient(
        listOf(Color.Transparent, look.glow.copy(alpha = 0.6f * look.glowAlpha), Color.Transparent), startX = 0f, endX = w
    )

    // Road: centre curve with y control points evenly spaced, so a curve parameter is a screen height.
    val gy = h - hy
    val ry = FloatArray(4) { h - gy * it / 3f }
    val cx = FloatArray(4) { RoadCentre[it] * sx }
    val hw = FloatArray(4) { RoadHalf[it] * sx }
    val leftEdge = Path().apply {
        moveTo(cx[0] - hw[0], ry[0])
        cubicTo(cx[1] - hw[1], ry[1], cx[2] - hw[2], ry[2], cx[3] - hw[3], ry[3])
    }
    val rightEdge = Path().apply {
        moveTo(cx[0] + hw[0], ry[0])
        cubicTo(cx[1] + hw[1], ry[1], cx[2] + hw[2], ry[2], cx[3] + hw[3], ry[3])
    }
    val road = Path().apply {
        moveTo(cx[0] - hw[0], ry[0])
        cubicTo(cx[1] - hw[1], ry[1], cx[2] - hw[2], ry[2], cx[3] - hw[3], ry[3])
        lineTo(cx[3] + hw[3], ry[3])
        cubicTo(cx[2] + hw[2], ry[2], cx[1] + hw[1], ry[1], cx[0] + hw[0], ry[0])
        close()
    }
    val roadBrush = Brush.verticalGradient(listOf(look.roadTop, look.roadBottom), startY = hy, endY = h)
    val edgeBrush = Brush.verticalGradient(
        listOf(look.roadEdge.copy(alpha = look.roadEdge.alpha * 0.15f), look.roadEdge), startY = hy, endY = h
    )
    val edgeStroke = Stroke(width = 2f * s)

    scene.edgePaint.color = look.buildingEdge.toArgb()
    scene.edgePaint.strokeWidth = 2f * s

    onDrawBehind {
        val t = time.value

        drawRect(skyBrush, size = Size(w, hy))
        if (look.sun > 0.01f) {
            drawCircle(sunHalo, radius = sunHaloR, center = sunC, alpha = look.sun)
            drawCircle(sunCore, radius = sunCoreR, center = sunC, alpha = look.sun)
        }
        val breathe = 0.9f + 0.1f * sin(TWO_PI * 5f * t)
        scale(1f, glowRy / glowRx, glowC) {
            drawCircle(glowBrush, radius = glowRx, center = glowC, alpha = breathe)
        }

        if (look.stars > 0.01f) {
            drawPoints(small, PointMode.Points, starBrush, strokeWidth = 1.7f * s, cap = StrokeCap.Round, alpha = look.stars)
            drawPoints(big, PointMode.Points, starBrush, strokeWidth = 3f * s, cap = StrokeCap.Round, alpha = look.stars)
            if (look.extraStars > 0.01f) {
                val a = look.stars * look.extraStars
                drawPoints(nightSmall, PointMode.Points, starBrush, strokeWidth = 1.7f * s, cap = StrokeCap.Round, alpha = a)
                drawPoints(nightBig, PointMode.Points, starBrush, strokeWidth = 3f * s, cap = StrokeCap.Round, alpha = a)
            }
            for (i in 0 until STAR_COUNT) {
                val k = scene.starK[i]
                if (k == 0) continue
                val set = if (i < DUSK_STARS) 1f else look.extraStars
                val y = scene.starY[i] * h
                val fade = (1f - y / starFadeEnd).coerceAtLeast(0.15f)
                val tw = 0.2f + 0.8f * (0.5f + 0.5f * sin(TWO_PI * k * t + scene.starPhase[i]))
                drawCircle(
                    Color.White,
                    radius = scene.starR[i] * 1.2f * s,
                    center = Offset(scene.starX[i] * w, y),
                    alpha = (look.stars * set * fade * tw).coerceIn(0f, 1f)
                )
            }
        }
        if (look.moon > 0.01f) {
            drawCircle(moonHalo, radius = moonHaloR, center = moonC, alpha = look.moon)
            drawPath(moonPath, Color(0xFFF4EBD9), alpha = look.moon)
        }

        drawPath(farHills, look.hillFar)
        drawPath(nearHills, look.hillNear)

        // Skyline as an equalizer: each building breathes on its own phase.
        var n = 0
        for (i in scene.bx.indices) {
            val amp = 0.45f + 0.55f * (0.5f + 0.5f * sin(TWO_PI * scene.bk[i] * t + scene.bPhase[i]))
            val bh = scene.bh[i] * s * amp
            val x = scene.bx[i] * sx
            val bw = scene.bw[i] * sx
            val top = hy - bh
            drawRect(look.building, topLeft = Offset(x, top), size = Size(bw, bh))
            scene.edges[n++] = x
            scene.edges[n++] = top + s
            scene.edges[n++] = x + bw
            scene.edges[n++] = top + s
        }
        drawIntoCanvas { it.nativeCanvas.drawLines(scene.edges, 0, n, scene.edgePaint) }

        drawRect(groundBrush, topLeft = Offset(0f, hy), size = Size(w, h - hy))
        clipRect(top = hy) {
            scale(1f, glowRy * 0.45f / glowRx, glowC) {
                drawCircle(glowBrush, radius = glowRx, center = glowC, alpha = 0.35f * breathe)
            }
        }
        drawLine(horizonLine, Offset(0f, hy), Offset(w, hy), strokeWidth = 1.5f * s)

        drawPath(road, roadBrush)
        drawPath(leftEdge, edgeBrush, style = edgeStroke)
        drawPath(rightEdge, edgeBrush, style = edgeStroke)

        // Centre dashes in perspective (screen fraction 1 - 1/z), sliding toward the viewer;
        // kept quieter right at the bottom, where tiles sit over the road.
        val phase = (t * DASH_CYCLES) % 1f
        for (i in 0 until DASH_COUNT) {
            val z0 = 1f + (i - phase) * DASH_GAP
            val z1 = z0 + DASH_GAP * 0.5f
            if (z1 <= 1f) continue
            val f0 = 1f - 1f / z0.coerceAtLeast(1f)
            if (f0 > 0.86f) break
            val f1 = 1f - 1f / z1
            drawLine(
                look.lane,
                Offset(cubic(f0, cx[0], cx[1], cx[2], cx[3]), h - gy * f0),
                Offset(cubic(f1, cx[0], cx[1], cx[2], cx[3]), h - gy * f1),
                strokeWidth = (4.5f * (1f - f0) + 0.6f) * s,
                alpha = (0.7f + 1.5f * f0 - 2.2f * f0 * f0).coerceIn(0.2f, 1f)
            )
        }
    }
}

// --- Top bar ----------------------------------------------------------------------

/**
 * Transparent bar in the same order as every other look: on the left bare
 * icon buttons in the text colour for all apps and the layout, their words
 * in the scene's small sans beside them where the bar is wide enough; the
 * serif clock with the date and the dashboard's position in the middle; on
 * the right the setup pill, any vehicle alerts and the phone, then the OBD
 * link and the ⋮ menu, 60 dp each.
 */
@Composable
internal fun HorizonTopBar(m: TopBarModel) {
    val now = rememberNow(60_000L)
    val locale = Locale.getDefault()
    val dateFmt = rememberDateFormat(LONG_DATE, best = true)
    val date = dateFmt.format(now).replaceFirstChar { it.titlecase(locale) }
    val ink = DashColors.TextPrimary
    val soft = DashColors.TextSecondary
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(64.dp).graphicsLayer()) {
        val narrow = maxWidth < NARROW_BAR
        val labels = maxWidth >= LABELLED_BAR
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(modifier = if (narrow) Modifier else Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                HorizonBarButton(
                    onClick = m.onApps,
                    label = if (labels) stringResource(R.string.dash_apps) else null,
                    description = stringResource(R.string.horizon_cd_all_apps)
                ) {
                    Icon(Icons.Filled.Apps, contentDescription = null, tint = ink, modifier = Modifier.size(28.dp))
                }
                LayoutPicker(m) { open ->
                    HorizonBarButton(
                        onClick = open,
                        label = if (labels) stringResource(R.string.dash_layout_button) else null,
                        description = stringResource(R.string.horizon_cd_screen_layout, m.layout.title)
                    ) {
                        LayoutIcon(m.layout, null, soft, Modifier.size(28.dp))
                    }
                }
            }
            // The head unit's status bar shows the time while it is up.
            Row(modifier = Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!m.merged) {
                    val (digits, amPm) = splitClock(m.clock)
                    SceneText(digits, display(40f), Modifier.alignByBaseline(), overflow = TextOverflow.Clip)
                    if (amPm != null) {
                        Spacer(Modifier.width(4.dp))
                        SceneText(amPm, display(16f, soft, italic = true), Modifier.alignByBaseline())
                    }
                    if (!narrow) {
                        Spacer(Modifier.width(14.dp))
                        SceneText(date, ui(15f, soft), Modifier.alignByBaseline())
                    }
                }
                PagePositionChip(Modifier.padding(start = 10.dp))
            }
            // ⋮ and the OBD link get their room first; the rest only where it fits.
            BarEnd(modifier = Modifier.weight(1f)) {
                if (m.setupPending) {
                    Box(Modifier.layoutId(BarRank.SETUP).padding(end = 6.dp)) {
                        SetupPill(onClick = { m.onSetup(false) }, compact = narrow)
                    }
                }
                Row(modifier = Modifier.layoutId(BarRank.ALERTS), verticalAlignment = Alignment.CenterVertically) {
                    VehicleAlerts(m.obdConnection, m.obd)
                }
                Box(Modifier.layoutId(BarRank.PHONE)) { PhonePill() }
                Box(Modifier.layoutId(BarRank.OBD)) { HorizonObdDot(m.obdConnection, m.onConnectObd) }
                Box(Modifier.layoutId(BarRank.MORE)) {
                    MorePicker(m) { open ->
                        HorizonBarButton(onClick = open, description = stringResource(R.string.horizon_cd_more)) {
                            Icon(Icons.Filled.MoreVert, contentDescription = null, tint = soft, modifier = Modifier.size(28.dp))
                        }
                    }
                }
            }
        }
    }
}

private val HORIZON_BUTTON = 60.dp

/** A bare button of the bar: the icon alone in a 60 dp circle, or with its word beside it in the scene's small sans. */
@Composable
private fun HorizonBarButton(onClick: () -> Unit, description: String, label: String? = null, icon: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .height(HORIZON_BUTTON)
            .widthIn(min = HORIZON_BUTTON)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = if (label != null) 16.dp else 0.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        if (label != null) {
            Spacer(Modifier.width(8.dp))
            SceneText(label, ui(16f, DashColors.TextPrimary, FontWeight.Medium))
        }
    }
}

/**
 * OBD link: the letters OBD beside a mint dot with a glow when connected, a
 * hollow ring when off, a dot that pulses while connecting and a "!" after
 * the letters on error, so the mark reads without its colour. What a tap
 * does is the shared [ObdMark]'s.
 */
@Composable
private fun HorizonObdDot(state: ObdConnectionState, onConnect: () -> Unit) {
    val color = obdStatusColor(state)
    val ink = if (state == ObdConnectionState.DISCONNECTED) DashColors.TextSecondary else color
    val pulse = if (state == ObdConnectionState.CONNECTING) rememberLoop(900, reverse = true, status = true) else null
    ObdMark(state, onConnect, modifier = Modifier.heightIn(min = HORIZON_BUTTON), shape = CircleShape) {
        Row(modifier = Modifier.padding(start = 6.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(24.dp)
                    .drawWithCache {
                        val r = 4.5.dp.toPx()
                        val glow = Brush.radialGradient(listOf(color.copy(alpha = 0.55f), Color.Transparent), size.center, r * 2.6f)
                        val ring = Stroke(1.5.dp.toPx())
                        onDrawBehind {
                            when (state) {
                                ObdConnectionState.CONNECTED -> {
                                    drawCircle(glow, radius = r * 2.6f)
                                    drawCircle(color, radius = r)
                                }
                                ObdConnectionState.DISCONNECTED -> drawCircle(color, radius = r, style = ring)
                                ObdConnectionState.CONNECTING -> drawCircle(color, radius = r, alpha = 0.35f + 0.65f * (pulse?.value ?: 1f))
                                ObdConnectionState.ERROR -> drawCircle(color, radius = r)
                            }
                        }
                    }
            )
            SceneText(stringResource(R.string.dash_obd_short), ui(14f, ink))
            if (state == ObdConnectionState.ERROR) {
                Spacer(Modifier.width(3.dp))
                SceneText("!", ui(14f, ink, FontWeight.Black))
            }
        }
    }
}

// --- Tiles ------------------------------------------------------------------------

/**
 * Horizon renderer for app shortcuts, launch bars and the main widgets: text
 * set straight on the scene, toward the screen edge the tile is nearest.
 * Anything else keeps its standard renderer.
 */
@Composable
internal fun HorizonTile(item: DashboardItem, env: SkinTileEnv) {
    val side = sideOf(item)
    when (item) {
        is DashboardItem.AppShortcut -> HorizonApp(item, env)
        is DashboardItem.LaunchBar -> HorizonLaunchBar(item, env)
        is DashboardItem.BuiltinWidget -> when (item.kind) {
            BuiltinKind.TELEMETRY -> HorizonTelemetry(env, side)
            BuiltinKind.SPEED_HUD -> HorizonSpeed(env, side)
            BuiltinKind.MEDIA -> HorizonMedia(env, side)
            BuiltinKind.NAVIGATION -> HorizonDirections(env, side)
            BuiltinKind.CLOCK -> HorizonClock(env, side)
            BuiltinKind.WEATHER -> HorizonWeather(side)
            BuiltinKind.RANGE -> HorizonRange(item, env, side)
            else -> StandardSkinnedTile(item, env)
        }
        else -> StandardSkinnedTile(item, env)
    }
}

/** Speed ([speed] in km/h) as a huge serif number with an italic "km/h" or "mph"; a short dash when there is no reading. */
@Composable
private fun SpeedFigure(speed: Int?, maxW: Dp, maxH: Dp) {
    val units = LocalUnits.current
    val numSp = fitSp("188", display(100f), maxW * 0.78f, maxH, 28f, 400f)
    val unitSp = (numSp * 0.2f).coerceIn(14f, 44f)
    val color = when {
        speed == null -> DashColors.Muted
        speed >= SPEED_WARNING_KMH -> DashColors.Warning
        else -> DashColors.TextPrimary
    }
    Row {
        // A lone dash at display size reads as a grey bar, so the placeholder is smaller.
        if (speed != null) SceneText(units.speed(speed).toString(), display(numSp, color), Modifier.alignByBaseline(), overflow = TextOverflow.Clip)
        else SceneText("–", display(numSp * 0.45f, color), Modifier.alignByBaseline(), overflow = TextOverflow.Clip)
        Spacer(Modifier.width((unitSp * 0.4f).dp))
        SceneText(units.speedUnit, display(unitSp, DashColors.TextSecondary, italic = true), Modifier.alignByBaseline())
    }
}

/** Horizon splits thousands with a thin space ("2 400"). */
private const val THIN_SPACE = '\u2009'

/** The stats line under the telemetry speed, longest first. */
private fun telemetryLines(d: ObdData, context: android.content.Context, units: UnitSystem): List<String> {
    val rpm = context.getString(R.string.horizon_rpm_value, groupThousands(d.rpm, THIN_SPACE))
    val coolantDeg = units.temp(d.coolantTempC)
    val coolant = context.getString(R.string.horizon_coolant_value, coolantDeg)
    val volts = if (d.voltage > 0.0) "%.1f V".format(d.voltage) else null
    val core = listOfNotNull(rpm, coolant, volts)
    return listOf(
        (core + context.getString(R.string.horizon_load_value, d.engineLoadPct)).joinToString(DOT),
        core.joinToString(DOT),
        "$rpm$DOT$coolantDeg°",
        rpm
    )
}

/** Telemetry: the OBD speed as huge serif type, then rpm · coolant · battery (· load) in sans. */
@Composable
private fun HorizonTelemetry(env: SkinTileEnv, side: Side) {
    val state = env.obdConnection
    val connected = state == ObdConnectionState.CONNECTED
    val idle = state.isIdle
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer()
            .tap(idle && !env.editing, stringResource(R.string.horizon_connect_obd), env.onConnectObd)
            .padding(TilePad),
        contentAlignment = side.box
    ) {
        val lineSp = (maxWidth.value / 24f).coerceIn(12f, 20f)
        val lineStyle = ui(lineSp, if (connected || state == ObdConnectionState.CONNECTING) DashColors.TextSecondary else DashColors.Accent)
        val line = when (state) {
            ObdConnectionState.CONNECTED -> firstFitting(telemetryLines(env.obdData, env.context, LocalUnits.current), lineStyle, maxWidth)
            ObdConnectionState.CONNECTING -> stringResource(R.string.horizon_obd_connecting)
            ObdConnectionState.ERROR -> stringResource(R.string.horizon_obd_error_retry)
            ObdConnectionState.DISCONNECTED -> stringResource(R.string.horizon_obd_tap_connect)
        }
        val figureW = maxWidth
        val figureH = maxHeight - (lineSp * 1.3f + 8f).dp
        Column(horizontalAlignment = side.h) {
            SpeedFigure(if (connected) env.obdData.speedKmh else null, figureW, figureH)
            Spacer(Modifier.height(4.dp))
            SceneText(line, lineStyle, align = side.text)
        }
    }
}

/** Speed HUD: the number from OBD or GPS, and where it comes from. */
@Composable
private fun HorizonSpeed(env: SkinTileEnv, side: Side) {
    val speed = rememberSpeedKmh(env.obdData, env.obdConnection)
    val obd = env.obdConnection == ObdConnectionState.CONNECTED
    val idle = env.obdConnection.isIdle
    val canConnect = speed == null && idle && !env.editing
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer()
            .tap(canConnect, stringResource(R.string.horizon_connect_obd), env.onConnectObd)
            .padding(TilePad),
        contentAlignment = side.box
    ) {
        val capsSp = (maxWidth.value / 26f).coerceIn(11f, 16f)
        val noSignal = stringResource(R.string.info_speed_no_signal).uppercase()
        val source = speedSource(
            obd, speed,
            if (idle) "$noSignal · ${stringResource(R.string.horizon_obd_tap_connect).uppercase()}" else noSignal
        )
        val figureW = maxWidth
        val figureH = maxHeight - (capsSp * 1.3f + 8f).dp
        Column(horizontalAlignment = side.h) {
            SpeedFigure(speed, figureW, figureH)
            Spacer(Modifier.height(4.dp))
            SceneText(source, caps(capsSp, if (speed != null) DashColors.Good else DashColors.Muted), align = side.text)
        }
    }
}

/** Thin progress / gauge line over a soft shade, with an optional dot in the text colour at the playhead. */
@Composable
private fun ThinLine(fraction: Float, fill: Color, fromEnd: Boolean, dot: Boolean, modifier: Modifier) {
    val track = DashColors.TextPrimary.copy(alpha = 0.2f)
    val ink = DashColors.TextPrimary
    val shade = Shade.copy(alpha = 0.3f)
    Canvas(modifier.height(12.dp)) {
        val y = size.height / 2f
        val sw = 2.dp.toPx()
        drawLine(shade, Offset(0f, y + sw), Offset(size.width, y + sw), sw)
        drawLine(track, Offset(0f, y), Offset(size.width, y), sw)
        val fw = size.width * fraction.coerceIn(0f, 1f)
        val a = if (fromEnd) size.width - fw else 0f
        val b = if (fromEnd) size.width else fw
        if (fw > 0f) drawLine(fill, Offset(a, y), Offset(b, y), sw)
        if (dot) drawCircle(ink, radius = 5.dp.toPx(), center = Offset(if (fromEnd) a else b, y))
    }
}

/** Minimal round transport button over a soft halo: 1 dp outline in the text colour, or filled warm for play / pause. */
@Composable
private fun RoundControl(icon: ImageVector, label: String, size: Dp, filled: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val ink = DashColors.TextPrimary
    val shade = Shade.copy(alpha = 0.3f)
    Box(
        modifier = Modifier
            .size(size)
            .drawWithCache {
                val r = this.size.minDimension * 0.75f
                val halo = Brush.radialGradient(listOf(shade, Color.Transparent), this.size.center, r)
                onDrawBehind { drawCircle(halo, radius = r) }
            }
            .clip(CircleShape)
            .then(
                if (filled) Modifier.background(DashColors.Accent)
                else Modifier.border(1.dp, ink.copy(alpha = 0.45f), CircleShape)
            )
            .clickable(enabled = enabled, onClickLabel = label, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = label, tint = if (filled) PlayInk else ink, modifier = Modifier.size(size * 0.48f))
    }
}

/** Previous, play / pause and next. */
@Composable
private fun MediaControls(env: SkinTileEnv, size: Dp) {
    val playing = env.mediaState.isPlaying
    val enabled = !env.editing
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(size * 0.24f)) {
        RoundControl(Icons.Filled.SkipPrevious, stringResource(R.string.horizon_cd_previous_track), size, filled = false, enabled = enabled) {
            env.mediaController.previous()
        }
        RoundControl(
            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            stringResource(if (playing) R.string.horizon_cd_pause else R.string.horizon_cd_play),
            size, filled = true, enabled = enabled
        ) { env.mediaController.playPause() }
        RoundControl(Icons.Filled.SkipNext, stringResource(R.string.horizon_cd_next_track), size, filled = false, enabled = enabled) {
            env.mediaController.next()
        }
    }
}

/**
 * Music: "NOW PLAYING" caps, the title in large serif, the artist in serif
 * italic, a thin progress line with a dot and minimal round controls. Wide,
 * short tiles put the controls beside the text.
 */
@Composable
private fun HorizonMedia(env: SkinTileEnv, side: Side) {
    val ms = env.mediaState
    val access = env.hasMediaAccess
    val hasTrack = ms.hasMedia && ms.title.isNotBlank()
    val context = env.context
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer()
            .tap(!access && !env.editing, stringResource(R.string.horizon_allow_media_access)) { CarMediaController.openNotificationAccessSettings(context) }
            .padding(TilePad),
        contentAlignment = side.box
    ) {
        val wide = maxWidth > maxHeight * 2.6f && maxWidth >= 520.dp
        val ctrl = min(maxHeight.value * if (wide) 0.42f else 0.2f, 72f).coerceAtLeast(48f).dp
        val titleSp = min(maxHeight.value * if (wide) 0.26f else 0.2f, maxWidth.value / 9f).coerceIn(20f, 76f)
        val subSp = (titleSp * 0.5f).coerceIn(14f, 32f)
        val capsSp = (titleSp * 0.27f).coerceIn(10f, 14f)
        val showCaps = maxHeight >= (if (wide) 120.dp else 160.dp)
        val showProgress = access && ms.durationMs > 0L
        val showTimes = showProgress && maxHeight >= (if (wide) 150.dp else 230.dp)
        val titleLines = if (!wide && maxHeight > 300.dp) 2 else 1
        val textW = if (wide && access) maxWidth - ctrl * 3.5f - 24.dp else maxWidth
        val lineW = min(textW.value * 0.9f, 520f).dp
        val controlsGap = if (maxHeight > 200.dp) 14.dp else 8.dp

        val info: @Composable () -> Unit = {
            Column(horizontalAlignment = side.h) {
                if (showCaps) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (ms.isPlaying) {
                            Box(Modifier.size(6.dp).clip(CircleShape).background(DashColors.Good))
                            Spacer(Modifier.width(8.dp))
                        }
                        SceneText(
                            stringResource(
                                when {
                                    !access -> R.string.horizon_music_caps
                                    ms.isPlaying -> R.string.horizon_now_playing_caps
                                    hasTrack -> R.string.horizon_paused_caps
                                    else -> R.string.horizon_music_caps
                                }
                            ),
                            caps(capsSp)
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }
                SceneText(
                    when {
                        !access -> stringResource(R.string.info_media_access_needed)
                        hasTrack -> ms.title
                        else -> stringResource(R.string.info_nothing_playing)
                    },
                    display(titleSp, italic = !hasTrack || !access),
                    maxLines = titleLines,
                    align = side.text
                )
                if (!access || !hasTrack) {
                    SceneText(
                        stringResource(if (!access) R.string.horizon_tap_allow_notification_access else R.string.horizon_play_something),
                        ui((subSp * 0.8f).coerceIn(12f, 20f), if (!access) DashColors.Accent else DashColors.TextSecondary),
                        align = side.text
                    )
                } else {
                    SceneText(
                        ms.artist.ifBlank { stringResource(R.string.horizon_unknown_artist) },
                        display(subSp, DashColors.TextSecondary, italic = true),
                        align = side.text
                    )
                }
                if (showProgress) {
                    Spacer(Modifier.height(10.dp))
                    MediaProgress(ms, env.mediaController, showTimes, Modifier.width(lineW))
                }
            }
        }

        if (wide) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(textW), contentAlignment = side.box) { info() }
                if (access) {
                    Spacer(Modifier.width(24.dp))
                    MediaControls(env, ctrl)
                }
            }
        } else {
            Column(horizontalAlignment = side.h) {
                info()
                if (access) {
                    Spacer(Modifier.height(controlsGap))
                    MediaControls(env, ctrl)
                }
            }
        }
    }
}

/**
 * The progress line with its playhead dot and, when [showTimes], elapsed and
 * total time under it; in its own scope so only it follows the playback position.
 */
@Composable
private fun MediaProgress(ms: MediaState, controller: CarMediaController, showTimes: Boolean, modifier: Modifier) {
    val positionMs = rememberMediaPosition(ms, controller)
    val fraction = if (ms.durationMs > 0L) (positionMs.toFloat() / ms.durationMs).coerceIn(0f, 1f) else 0f
    ThinLine(fraction, DashColors.Accent, fromEnd = false, dot = true, modifier = modifier)
    if (showTimes) {
        Row(modifier) {
            SceneText(formatTrackTime(positionMs), ui(12f, DashColors.Muted))
            Spacer(Modifier.weight(1f))
            SceneText(formatTrackTime(ms.durationMs), ui(12f, DashColors.Muted))
        }
    }
}

/** A quiet state: a serif italic title and one sans hint; the hint is warm when tapping does something. */
@Composable
private fun SceneEmpty(title: String, hint: String, side: Side, onTap: (() -> Unit)?) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer()
            .tap(onTap != null, hint) { onTap?.invoke() }
            .padding(TilePad),
        contentAlignment = side.box
    ) {
        // The title's size follows its length, so a long one ("Notification access needed") stays on one line where it can.
        val titleSp = min(maxHeight.value * 0.3f, maxWidth.value / (title.length * 0.5f)).coerceIn(22f, 72f)
        val hintSp = (titleSp * 0.32f).coerceIn(12f, 18f)
        Column(horizontalAlignment = side.h) {
            SceneText(title, display(titleSp, italic = true), maxLines = 2, align = side.text)
            Spacer(Modifier.height(4.dp))
            SceneText(
                hint,
                ui(hintSp, if (onTap != null) DashColors.Accent else DashColors.TextSecondary),
                maxLines = 2,
                align = side.text
            )
        }
    }
}

/** The manoeuvre glyph from the navigation notification, tinted warm; a generic arrow without one. */
@Composable
private fun ManeuverGlyph(nav: NavState, size: Dp) {
    val bitmap = remember(nav.icon) { nav.icon?.asImageBitmap() }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = nav.instruction,
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(DashColors.Accent),
            modifier = Modifier.size(size)
        )
    } else {
        Icon(Icons.Filled.Directions, contentDescription = nav.instruction, tint = DashColors.Accent, modifier = Modifier.size(size))
    }
}

/**
 * Directions: the distance to the next turn in big warm serif with the glyph
 * beside it, the instruction in sans and the ETA line. Tap opens the
 * navigation app; without a route, a serif italic "No route".
 */
@Composable
private fun HorizonDirections(env: SkinTileEnv, side: Side) {
    val nav by NavDirections.state.collectAsState()
    val context = env.context
    val canTap = !env.editing
    when {
        !env.hasMediaAccess -> SceneEmpty(
            stringResource(R.string.info_directions_access_title), stringResource(R.string.horizon_tap_allow_notification_access), side,
            if (canTap) ({ CarMediaController.openNotificationAccessSettings(context) }) else null
        )
        !nav.active -> SceneEmpty(
            stringResource(R.string.info_directions_no_route), stringResource(R.string.horizon_start_navigation), side,
            if (canTap) ({ openNavigationApp(context, nav) }) else null
        )
        else -> BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer()
                .tap(canTap, stringResource(R.string.horizon_open_navigation)) { openNavigationApp(context, nav) }
                .padding(TilePad),
            contentAlignment = side.box
        ) {
            val instrSp = (maxWidth.value / 22f).coerceIn(14f, 26f)
            val etaSp = (instrSp * 0.8f).coerceIn(12f, 20f)
            val instrLines = if (maxHeight > 220.dp) 2 else 1
            val eta = nav.etaParts.joinToString(DOT)
            val below = (instrSp * 1.3f * instrLines + (if (eta.isNotEmpty()) etaSp * 1.3f else 0f) + 10f).dp
            val (value, unit) = nav.distanceParts
            val distSp = fitSp(template(value.ifEmpty { "888" }), display(100f), maxWidth * 0.56f, maxHeight - below, 26f, 220f)
            Column(horizontalAlignment = side.h) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ManeuverGlyph(nav, (distSp * 0.5f).coerceAtLeast(24f).dp)
                    Spacer(Modifier.width((distSp * 0.16f).dp))
                    if (value.isNotEmpty()) {
                        Row {
                            SceneText(value, display(distSp, DashColors.Accent), Modifier.alignByBaseline(), overflow = TextOverflow.Clip)
                            if (unit.isNotEmpty()) {
                                Spacer(Modifier.width((distSp * 0.08f).dp))
                                SceneText(
                                    unit,
                                    display((distSp * 0.36f).coerceAtLeast(14f), DashColors.TextSecondary, italic = true),
                                    Modifier.alignByBaseline()
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                SceneText(
                    nav.instruction,
                    ui(instrSp, DashColors.TextPrimary, FontWeight.Medium),
                    maxLines = instrLines,
                    align = side.text
                )
                if (eta.isNotEmpty()) SceneText(eta, ui(etaSp, DashColors.Muted), align = side.text)
            }
        }
    }
}

/** The phrase ("%1$s evening") that sets a weather condition in the part of the day at [date]. */
@StringRes
private fun partOfDayMood(date: Date): Int {
    val hour = Calendar.getInstance().apply { time = date }.get(Calendar.HOUR_OF_DAY)
    return when (hour) {
        in 5..11 -> R.string.horizon_mood_morning
        in 12..17 -> R.string.horizon_mood_afternoon
        in 18..21 -> R.string.horizon_mood_evening
        else -> R.string.horizon_mood_night
    }
}

/** Skeleton of weekday, day and month ("Wednesday 23 September"), set in the order and punctuation of the locale. */
private const val LONG_DATE = "EEEEdMMMM"

/** Clock: huge serif time, the date in sans and, on tall tiles, "20° · clear evening". Tap opens alarms. */
@Composable
private fun HorizonClock(env: SkinTileEnv, side: Side) {
    val now = rememberNow(60_000L)
    val locale = Locale.getDefault()
    val units = LocalUnits.current
    val timeFmt = rememberDateFormat(units.digitsPattern())
    val dateFmt = rememberDateFormat(LONG_DATE, best = true)
    val weather by WeatherRepo.weather.collectAsState()
    val context = env.context
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer()
            .tap(!env.editing, stringResource(R.string.horizon_open_clock)) { openClockApp(context) }
            .padding(TilePad),
        contentAlignment = side.box
    ) {
        val dateSp = (maxWidth.value / 24f).coerceIn(12f, 24f)
        val w = weather
        // Lowercased mid-sentence, except German, whose nouns stay capitalized ("Nebel").
        val mood = if (w != null && maxHeight > 190.dp) {
            "${units.temp(w.tempC)}°$DOT" + stringResource(partOfDayMood(now), w.condition.let { if (locale.language == "de") it else it.lowercase(locale) })
        } else null
        val below = (dateSp * 1.3f * (if (mood != null) 2 else 1) + 8f).dp
        val timeSp = fitSp("00:00", display(100f), maxWidth, maxHeight - below, 28f, 420f)
        val amPm = units.amPm(now, locale)
        Column(horizontalAlignment = side.h) {
            // A 12-hour clock sets its AM / PM small and italic after the digits, like the units elsewhere.
            if (amPm == null) {
                SceneText(timeFmt.format(now), display(timeSp), overflow = TextOverflow.Clip)
            } else {
                Row {
                    SceneText(timeFmt.format(now), display(timeSp), Modifier.alignByBaseline(), overflow = TextOverflow.Clip)
                    Spacer(Modifier.width((timeSp * 0.06f).dp))
                    SceneText(amPm, display((timeSp * 0.22f).coerceAtLeast(14f), DashColors.TextSecondary, italic = true), Modifier.alignByBaseline())
                }
            }
            Spacer(Modifier.height(4.dp))
            SceneText(dateFmt.format(now).replaceFirstChar { it.titlecase(locale) }, ui(dateSp), align = side.text)
            if (mood != null) SceneText(mood, ui(dateSp, DashColors.Muted), align = side.text)
        }
    }
}

/** Weather: serif "20°", the condition with a small warm glyph, and a feels / wind line. */
@Composable
private fun HorizonWeather(side: Side) {
    val w = rememberWeather()
    val wait = rememberWeatherWait()
    if (w == null) {
        // Says why there is none, so "Loading" never stands for a whole drive without GPS or network.
        val title = when (wait) {
            WeatherWait.LOADING -> R.string.horizon_loading
            WeatherWait.NO_GPS -> R.string.info_waiting_gps
            WeatherWait.UNAVAILABLE -> R.string.info_weather_unavailable
        }
        SceneEmpty(stringResource(title), stringResource(R.string.horizon_weather_at_car), side, null)
        return
    }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer()
            .padding(TilePad),
        contentAlignment = side.box
    ) {
        val wide = maxWidth > maxHeight * 1.7f
        val condSp = (min(maxWidth.value, maxHeight.value * 2f) / 18f).coerceIn(14f, 28f)
        val lineSp = (condSp * 0.72f).coerceIn(12f, 20f)
        val units = LocalUnits.current
        val temp = "${units.temp(w.tempC)}°"
        val tempBase = display(100f)
        val details: @Composable (Dp) -> Unit = { maxW ->
            val lineStyle = ui(lineSp, DashColors.Muted)
            val feels = stringResource(R.string.horizon_feels, units.temp(w.feelsC))
            val wind = stringResource(if (units.imperial) R.string.units_horizon_wind_mph else R.string.horizon_wind, units.speed(w.windKmh))
            // Either end can be missing from the forecast; rounding a NaN would throw.
            val range = if (!w.hiC.isNaN() && !w.loC.isNaN()) "${units.temp(w.loC)}° / ${units.temp(w.hiC)}°" else null
            val line = firstFitting(
                listOfNotNull(range?.let { "$feels$DOT$wind$DOT$it" }, "$feels$DOT$wind", feels),
                lineStyle, maxW
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(weatherIcon(w.code), contentDescription = null, tint = DashColors.Accent, modifier = Modifier.size((condSp * 1.2f).dp))
                Spacer(Modifier.width(8.dp))
                SceneText(w.condition, ui(condSp, DashColors.TextPrimary))
            }
            SceneText(line, lineStyle)
        }
        val boxW = maxWidth
        if (wide) {
            val tempSp = fitSp(template(temp), tempBase, boxW * 0.42f, maxHeight, 28f, 320f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                SceneText(temp, display(tempSp), overflow = TextOverflow.Clip)
                Spacer(Modifier.width(16.dp))
                Column { details(boxW * 0.5f) }
            }
        } else {
            val below = (condSp * 1.4f + lineSp * 1.3f + 8f).dp
            val tempSp = fitSp(template(temp), tempBase, boxW * 0.8f, maxHeight - below, 28f, 320f)
            Column(horizontalAlignment = side.h) {
                SceneText(temp, display(tempSp), overflow = TextOverflow.Clip)
                Column(horizontalAlignment = side.h) { details(boxW) }
            }
        }
    }
}

/** Fuel & range: serif "480 km", "range · 62% fuel" and a thin mint gauge; unknown fuel falls back to the standard tile. */
@Composable
private fun HorizonRange(item: DashboardItem, env: SkinTileEnv, side: Side) {
    val fuel = rememberFuel(env.obdData, env.obdConnection)
    if (fuel == null) {
        StandardSkinnedTile(item, env)
        return
    }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer()
            .padding(TilePad),
        contentAlignment = side.box
    ) {
        val lineSp = (maxWidth.value / 22f).coerceIn(12f, 20f)
        val below = (lineSp * 1.3f + 24f).dp
        val numSp = fitSp("888", display(100f), maxWidth * 0.7f, maxHeight - below, 26f, 300f)
        val unitSp = (numSp * 0.36f).coerceIn(14f, 72f)
        val low = fuel.percent <= SKIN_LOW_FUEL_PCT
        val gaugeW = min(maxWidth.value * 0.8f, 420f).dp
        Column(horizontalAlignment = side.h) {
            Row {
                val units = LocalUnits.current
                SceneText("${units.distance(fuel.rangeKm)}", display(numSp), Modifier.alignByBaseline(), overflow = TextOverflow.Clip)
                Spacer(Modifier.width((unitSp * 0.3f).dp))
                SceneText(units.distanceUnit, display(unitSp, DashColors.TextSecondary, italic = true), Modifier.alignByBaseline())
            }
            SceneText(
                stringResource(R.string.horizon_range_fuel, fuel.percent),
                ui(lineSp, if (low) DashColors.Warning else DashColors.TextSecondary),
                align = side.text
            )
            Spacer(Modifier.height(8.dp))
            ThinLine(
                fuel.percent / 100f,
                if (low) DashColors.Warning else DashColors.Good,
                fromEnd = side == Side.END,
                dot = false,
                modifier = Modifier.width(gaugeW)
            )
        }
    }
}

/** App shortcut: the app's own icon (no disc) over a soft shadow, and a sans label. */
@Composable
private fun HorizonApp(item: DashboardItem.AppShortcut, env: SkinTileEnv) {
    val app = env.appsByPackage[item.packageName]
    val label = appLabel(app, item.packageName)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer()
            .tap(!env.editing, stringResource(R.string.horizon_open_app, label)) { env.onLaunchApp(item.packageName) },
        contentAlignment = Alignment.Center
    ) {
        val iconSize = (min(maxWidth.value, maxHeight.value) * 0.42f).coerceIn(30f, 64f).dp
        val labelSp = (iconSize.value * 0.36f).coerceIn(11f, 15f)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AppGlyph(app, iconSize)
            Spacer(Modifier.height(5.dp))
            SceneText(
                label,
                ui(labelSp, DashColors.TextPrimary),
                Modifier.padding(horizontal = 4.dp),
                align = TextAlign.Center
            )
        }
    }
}

/** An app's own launcher icon (no disc) over a soft halo (dark in the evening, pale by day) that lifts it off the scene. */
@Composable
private fun AppGlyph(app: AppEntry?, size: Dp) {
    val shade = Shade.copy(alpha = 0.32f)
    Box(
        modifier = Modifier
            .size(size)
            .drawWithCache {
                val r = this.size.minDimension * 0.85f
                val halo = Brush.radialGradient(listOf(shade, Color.Transparent), this.size.center, r)
                onDrawBehind { drawCircle(halo, radius = r) }
            },
        contentAlignment = Alignment.Center
    ) {
        if (app != null) {
            AppIcon(icon = app.icon, size = size)
        } else {
            Icon(Icons.Filled.Apps, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(size * 0.75f))
        }
    }
}

/**
 * Launch bar: a row of apps (small icon, and a label when there is room)
 * separated by thin dots, with the edit pencil at the end. Tall bars stack
 * each label under its icon.
 */
@Composable
private fun HorizonLaunchBar(item: DashboardItem.LaunchBar, env: SkinTileEnv) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer()
            .padding(horizontal = 8.dp)
    ) {
        val tall = maxHeight >= 140.dp
        val room = (maxWidth - 52.dp).value
        // Never squeeze an app below a 48 dp target (plus its separator).
        val pkgs = item.packages.take((room / 56f).toInt().coerceAtLeast(1))
        val per = room / pkgs.size.coerceAtLeast(1)
        val labels = if (tall) per >= 72f else per >= 118f
        val iconSize = if (tall) (maxHeight.value * 0.34f).coerceIn(28f, 60f).dp else (maxHeight.value * 0.42f).coerceIn(24f, 40f).dp
        val labelSp = if (tall) (iconSize.value * 0.3f).coerceIn(11f, 15f) else (maxHeight.value * 0.18f).coerceIn(12f, 16f)
        val emptySp = (maxHeight.value * 0.26f).coerceIn(16f, 26f)
        Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            if (pkgs.isEmpty()) {
                SceneText(
                    stringResource(R.string.horizon_launch_bar_empty),
                    display(emptySp, DashColors.TextSecondary, italic = true),
                    Modifier.weight(1f).padding(start = 8.dp)
                )
            } else {
                Row(modifier = Modifier.weight(1f).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                    pkgs.forEachIndexed { i, pkg ->
                        if (i > 0) {
                            Box(Modifier.size(3.dp).clip(CircleShape).background(DashColors.TextPrimary.copy(alpha = 0.35f)))
                        }
                        LaunchEntry(
                            app = env.appsByPackage[pkg],
                            packageName = pkg,
                            iconSize = iconSize,
                            label = labels,
                            tall = tall,
                            labelSp = labelSp,
                            enabled = !env.editing,
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        ) { env.onLaunchApp(pkg) }
                    }
                }
            }
            IconButton(onClick = env.onEditLaunchBar) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = stringResource(R.string.horizon_cd_edit_launch_bar),
                    tint = DashColors.TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/** One launch-bar app: icon beside (or, on tall bars, above) its label. */
@Composable
private fun LaunchEntry(
    app: AppEntry?,
    packageName: String,
    iconSize: Dp,
    label: Boolean,
    tall: Boolean,
    labelSp: Float,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val name = appLabel(app, packageName)
    val openLabel = stringResource(R.string.horizon_open_app, name)
    val base = modifier
        .clip(RoundedCornerShape(14.dp))
        .then(
            if (enabled) Modifier.clickable(onClickLabel = openLabel, role = Role.Button, onClick = onClick)
            else Modifier
        )
        .semantics { contentDescription = name }
        .padding(horizontal = 4.dp)
    if (tall) {
        Column(
            modifier = base,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            AppGlyph(app, iconSize)
            if (label) {
                Spacer(Modifier.height(6.dp))
                SceneText(name, ui(labelSp, DashColors.TextPrimary), align = TextAlign.Center)
            }
        }
    } else {
        Row(
            modifier = base,
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppGlyph(app, iconSize)
            if (label) {
                Spacer(Modifier.width(8.dp))
                SceneText(name, ui(labelSp, DashColors.TextPrimary), Modifier.weight(1f, fill = false))
            }
        }
    }
}

// --- Maps window frame ------------------------------------------------------------

/**
 * Soft frame over a docked Maps window: its corners rounded off in the colour
 * of the scene behind them (evening or noon, as the page shows), a thin rim in
 * the text colour that fades out downward, a light fade at the top and a
 * deeper one along the bottom so the map melts into the ground. The middle
 * stays clear.
 */
@Composable
internal fun HorizonWindowFrame(modifier: Modifier) {
    val now = rememberNow(60_000L)
    val light = DashColors.Light
    val look = remember(now, light) { skyLookAt(now, light) }
    val view = LocalView.current
    val screenH = view.resources.displayMetrics.heightPixels.toFloat().coerceAtLeast(1f)
    val topOnScreen = remember { mutableFloatStateOf(Float.NaN) }
    val ink = DashColors.TextPrimary
    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { c ->
                val loc = IntArray(2)
                view.getLocationOnScreen(loc)
                topOnScreen.floatValue = loc[1] + c.positionInWindow().y
            }
            .drawWithCache {
                val w = size.width
                val h = size.height
                val r = 28.dp.toPx()
                val top = topOnScreen.floatValue.takeUnless { it.isNaN() } ?: 64.dp.toPx()
                fun at(y: Float) = sceneColorAt(look, (top + y) / screenH)
                val cTop = at(0f)
                val cBottom = at(h)
                val mask = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(0f, 0f, w, h))
                    addRoundRect(RoundRect(Rect(0f, 0f, w, h), CornerRadius(r)))
                }
                val maskBrush = Brush.verticalGradient(listOf(cTop, cBottom), startY = 0f, endY = h)
                val fadeTop = h * 0.8f
                val bottomFade = Brush.verticalGradient(
                    0f to at(fadeTop).copy(alpha = 0f),
                    0.6f to at(h * 0.92f).copy(alpha = 0.35f),
                    1f to cBottom.copy(alpha = 0.92f),
                    startY = fadeTop, endY = h
                )
                val topFade = Brush.verticalGradient(
                    listOf(cTop.copy(alpha = 0.4f), cTop.copy(alpha = 0f)), startY = 0f, endY = h * 0.08f
                )
                val rim = Brush.verticalGradient(
                    0f to ink.copy(alpha = 0.2f), 0.7f to ink.copy(alpha = 0.12f), 1f to ink.copy(alpha = 0f),
                    startY = 0f, endY = h
                )
                val rimStroke = Stroke(1.dp.toPx())
                val half = rimStroke.width / 2f
                onDrawBehind {
                    drawRect(topFade, size = Size(w, h * 0.08f))
                    drawRect(bottomFade, topLeft = Offset(0f, fadeTop), size = Size(w, h - fadeTop))
                    drawPath(mask, maskBrush)
                    drawRoundRect(
                        rim,
                        topLeft = Offset(half, half),
                        size = Size(w - 2f * half, h - 2f * half),
                        cornerRadius = CornerRadius(r - half),
                        style = rimStroke
                    )
                }
            }
    )
}
