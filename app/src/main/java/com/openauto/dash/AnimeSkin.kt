package com.openauto.dash

import android.graphics.Bitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/*
 * Anime skin: the dashboard as an anime opening scene. The page is a sunset
 * sky (a night sky with stars, a moon and lit windows in the dark palette)
 * over a lilac town and a road whose yellow lane line scrolls by, with clouds
 * drifting and a few sakura petals falling. Every widget is a flat cel-coloured
 * sticker with a thick ink outline and a hard offset shadow: the speed in a
 * comic burst, directions in a speech bubble the car seems to say, music on a
 * halftone card with a spinning record, the car's state as tilted chips, and
 * the driver's own car, tinted pink with a white then ink sticker outline,
 * driving on its tile with speed lines.
 *
 * Shared pieces live here (palettes, tones, type, the sticker, the page scene,
 * the bar, the Maps frame, the stamp); the tiles are in AnimeSkinTiles.kt and
 * the drawn pictures (car, burst, record, weather) in AnimeSkinArt.kt.
 */

// Dusk is the default look: deep indigo stickers with a pale rim read at night without glare.
internal val AnimePalette = DashPalette(
    Background = Color(0xFF15123A), Bar = Color.Transparent, Card = Color(0xFF2E2858), CardHi = Color(0xFF3E3772),
    Accent = Color(0xFFFF6FAE), Secondary = Color(0xFFFFE066), Critical = Color(0xFFFF4D4D),
    Good = Color(0xFF4FD69C), Muted = Color(0xFFB7AEDF), TextPrimary = Color(0xFFFFF5FA), TextSecondary = Color(0xFFDCD3F5),
    Accent2 = Color(0xFF8FD0FF), Line = Color(0x40FFF5FA), Glow = 0.4f, Bare = true,
    BackgroundStops = listOf(Color(0xFF0B0A26), Color(0xFF1B1752), Color(0xFF3C2568), Color(0xFF74407A)),
    Skin = DashSkin.ANIME, HeroWeight = FontWeight.Black
)

// The approved daylight scene: white and pastel stickers in ink over a blue to peach sky.
internal val AnimeLightPalette = DashPalette(
    Background = Color(0xFFA6DBFF), Bar = Color.Transparent, Card = Color.White, CardHi = Color(0xFFFFE6F1),
    Accent = Color(0xFFE8327F), Secondary = Color(0xFF6C3FD1), Critical = Color(0xFFD92D2D),
    Good = Color(0xFF12985F), Muted = Color(0xFF4A4A6A), TextPrimary = Color(0xFF22223B), TextSecondary = Color(0xFF3B3B5C),
    Accent2 = Color(0xFF2D86D9), Line = Color(0x3322223B), Glow = 0.2f, Light = true, Bare = true,
    BackgroundStops = listOf(Color(0xFF5DBBFF), Color(0xFFA6DBFF), Color(0xFFFFC9E0), Color(0xFFFFE6C2)),
    Skin = DashSkin.ANIME, HeroWeight = FontWeight.Black,
    // The stock day amber is lost on the sky blue page: a deeper one reads there.
    Tacho = Color(0xFF8A4B00)
)

/** The skin's corners (dialogs, menus, tiles being arranged): round and friendly. */
internal val AnimeShapes = skinShapes(28, 22, 18)

// --- Tones ------------------------------------------------------------------------

/**
 * The cel colours the stickers are drawn in, by day and at night. [ink] is the
 * outline and the hard shadow; [rim] is the pale ring inside the ink that lifts
 * a dark night sticker off the dark sky (none by day, where the ink does it).
 */
internal class AnimeTones(
    val light: Boolean,
    val ink: Color,
    val paper: Color,
    val onPaper: Color,
    val subOnPaper: Color,
    val pink: Color,
    val yellow: Color,
    val mint: Color,
    val sky: Color,
    val blush: Color,
    val lemon: Color,
    val rim: Color?,
    val dots: Color,
    val carBody: Color,
    val carShade: Color,
    val glass: Color,
    val road: Color,
    val kerb: Color
) {
    /** Sticker fills taken in turn by app shortcuts and launch-bar pills. */
    val fills: List<Color> = listOf(paper, lemon, mint, sky, blush)
}

internal val AnimeDay = AnimeTones(
    light = true,
    ink = Color(0xFF22223B), paper = Color.White, onPaper = Color(0xFF22223B), subOnPaper = Color(0xFF4A4A6A),
    pink = Color(0xFFFF4F9A), yellow = Color(0xFFFFE066), mint = Color(0xFF9BE7C4), sky = Color(0xFFBFE3FF),
    blush = Color(0xFFFFD6E8), lemon = Color(0xFFFFE066), rim = null, dots = Color(0x38FF4F9A),
    carBody = Color(0xFFFF8FC0), carShade = Color(0xFFE9679F), glass = Color(0xFFBFE3FF),
    // A sunlit lilac road (the mockup's is darker) so ink text of plain tiles still reads over it.
    road = Color(0xFF9D93C6), kerb = Color.White
)

internal val AnimeNight = AnimeTones(
    light = false,
    ink = Color(0xFF0B0A1C), paper = Color(0xFF2E2858), onPaper = Color(0xFFFFF5FA), subOnPaper = Color(0xFFCFC6F0),
    pink = Color(0xFFFF6FAE), yellow = Color(0xFFF2CC48), mint = Color(0xFF1F6152), sky = Color(0xFF254C82),
    blush = Color(0xFF6E2F5E), lemon = Color(0xFF6B5A1C), rim = Color(0xE6F5F0FF), dots = Color(0x2EFF6FAE),
    carBody = Color(0xFFF27AB4), carShade = Color(0xFFC9558E), glass = Color(0xFF8FC2F0),
    road = Color(0xFF1C1836), kerb = Color(0xFFC9C0F0)
)

/** The tones for the palette on screen now. */
internal val animeTones: AnimeTones get() = if (DashColors.Light) AnimeDay else AnimeNight

/** Ink or cream, whichever reads on [fill] (pastels and amber take ink, deep night fills and red take cream). */
internal fun animeOn(fill: Color): Color = if (fill.luminance() > 0.36f) Color(0xFF22223B) else Color(0xFFFFF5FA)

// --- Type -------------------------------------------------------------------------

private val TightLines = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)

/** The comic display face: heavy condensed italic caps, like a manga sound effect. [size] in dp. */
@Composable
internal fun animeComic(size: Float, color: Color): TextStyle = TextStyle(
    color = color,
    fontFamily = CondensedFamily,
    fontWeight = FontWeight.Bold,
    fontStyle = FontStyle.Italic,
    fontSize = fixedSp(size),
    lineHeight = 1.08.em,
    lineHeightStyle = TightLines,
    letterSpacing = 0.02.em
)

/** The friendly rounded body text: the system sans at its black weight. [size] in dp. */
@Composable
internal fun animeRound(size: Float, color: Color, weight: FontWeight = FontWeight.Black): TextStyle = TextStyle(
    color = color,
    fontFamily = FontFamily.SansSerif,
    fontWeight = weight,
    fontSize = fixedSp(size),
    lineHeight = 1.2.em,
    letterSpacing = 0.01.em
)

/**
 * Largest size in dp (within [minDp]..[maxDp]) at which [sample] in [style]
 * fits [maxW] on one line with its line box no taller than [maxH]. Measured
 * once at 100 and scaled, since type grows linearly with its size.
 */
@Composable
internal fun animeFit(sample: String, style: TextStyle, maxW: Dp, maxH: Dp, minDp: Float, maxDp: Float): Float {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val probeStyle = style.copy(fontSize = fixedSp(100f), drawStyle = null, shadow = null)
    return remember(sample, probeStyle, maxW, maxH, minDp, maxDp, density) {
        val probe = measurer.measure(sample, probeStyle, softWrap = false, maxLines = 1)
        val w = probe.size.width.coerceAtLeast(1)
        val h = probe.size.height.coerceAtLeast(1)
        val byW = with(density) { maxW.toPx() } * 100f / w
        val byH = with(density) { maxH.toPx() } * 100f / h
        min(byW, byH).coerceIn(minDp, maxDp)
    }
}

/** Digits replaced by '8', so a readout keeps one size while its value changes. */
internal fun animeTemplate(text: String): String = text.map { if (it.isDigit()) '8' else it }.joinToString("")

/**
 * One line of type with an ink outline of [outline] times its size around
 * every letter (drawn as a stroke under the fill), the way comic lettering
 * sits on a busy panel.
 */
@Composable
internal fun AnimeInkText(text: String, style: TextStyle, ink: Color, outline: Float, modifier: Modifier = Modifier) {
    val px = with(LocalDensity.current) { style.fontSize.toPx() } * outline
    Box(modifier) {
        if (px > 0.5f) {
            Text(
                text, maxLines = 1, softWrap = false,
                style = style.copy(color = ink, drawStyle = Stroke(px * 2f, join = StrokeJoin.Round))
            )
        }
        Text(text, style = style, maxLines = 1, softWrap = false)
    }
}

/**
 * A figure (speed, a reading, the time): one line that never ends in an
 * ellipsis. Where it would be wider than the room it is given, its size
 * shrinks until it fits (its [outline], as in [AnimeInkText], counted in).
 * Words may be cut; numbers never are.
 */
@Composable
internal fun AnimeFigure(text: String, style: TextStyle, modifier: Modifier = Modifier, ink: Color? = null, outline: Float = 0f) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val room = constraints.maxWidth
        val scale = remember(text, style, room, outline, density) {
            if (room == androidx.compose.ui.unit.Constraints.Infinity) 1f else {
                val fontPx = with(density) { style.fontSize.toPx() }
                val w = measurer.measure(text, style.copy(drawStyle = null), softWrap = false, maxLines = 1).size.width + fontPx * outline * 2f
                if (w <= room) 1f else (room / w) * 0.98f
            }
        }
        val fitted = if (scale < 1f) style.copy(fontSize = style.fontSize * scale) else style
        if (ink != null && outline > 0f) {
            AnimeInkText(text, fitted, ink, outline)
        } else {
            Text(text, style = fitted, maxLines = 1, softWrap = false, overflow = TextOverflow.Visible)
        }
    }
}

/** Plain text of the skin (words): one line with an ellipsis unless [maxLines] says otherwise. */
@Composable
internal fun AnimeText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    align: TextAlign? = null
) {
    Text(
        text = text,
        modifier = modifier,
        style = if (align != null) style.copy(textAlign = align) else style,
        maxLines = maxLines,
        softWrap = maxLines > 1,
        overflow = TextOverflow.Ellipsis
    )
}

// --- The sticker ------------------------------------------------------------------

/**
 * How far a sticker of [w] x [h] may lean: [base] degrees, less on a big one,
 * so its corners never move more than 6 dp and stay inside the tile.
 */
internal fun animeTilt(base: Float, w: Dp, h: Dp): Float {
    val reach = max(w.value, h.value) / 2f
    if (reach <= 6f) return base
    val limit = Math.toDegrees(asin((6f / reach).toDouble())).toFloat()
    return base.coerceIn(-limit, limit)
}

/**
 * A die-cut sticker: a flat [fill] with a thick ink border and a hard ink
 * shadow [shadow] down and right (a pale rim inside the ink at night), leaning
 * [rotation] degrees. [pill] rounds the ends fully. [dots] lays a halftone
 * over the fill (not with effects off). Tappable when [onClick] is given: a
 * press pushes it down onto its shadow, with the tap feedback. [dashed]
 * draws the border dashed, the mark of a tile being arranged. The content
 * sits inside the border.
 */
@Composable
internal fun AnimeSticker(
    modifier: Modifier,
    fill: Color,
    corner: Dp = 20.dp,
    pill: Boolean = false,
    shadow: Dp = 5.dp,
    border: Dp = 4.dp,
    rotation: Float = 0f,
    dots: Boolean = false,
    dashed: Boolean = false,
    onClick: (() -> Unit)? = null,
    clickLabel: String? = null,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit
) {
    val t = animeTones
    val ink = t.ink
    val rim = t.rim
    val interaction = remember { MutableInteractionSource() }
    val pressed = interaction.collectIsPressedAsState()
    val tap = rememberTapFeedback()
    val halftone = if (dots && DashColors.Effects != DashEffects.NONE) animeHalftone(t.dots) else null
    val shape = if (pill) RoundedCornerShape(percent = 50) else RoundedCornerShape(corner)
    Box(
        modifier = modifier
            .graphicsLayer { rotationZ = rotation }
            .cachedDraw(ink, shadow, corner, pill) {
                val sh = shadow.toPx()
                val body = Size(size.width - sh, size.height - sh)
                val r = if (pill) body.height / 2f else min(corner.toPx(), body.minDimension / 2f)
                onDrawBehind { drawRoundRect(ink, Offset(sh, sh), body, CornerRadius(r)) }
            },
        propagateMinConstraints = true
    ) {
        Box(
            modifier = Modifier
                .padding(end = shadow, bottom = shadow)
                .graphicsLayer {
                    val push = if (pressed.value) shadow.toPx() * 0.7f else 0f
                    translationX = push
                    translationY = push
                }
                .cachedDraw(fill, ink, rim, border, corner, pill, dashed, halftone) {
                    val bw = border.toPx()
                    val r = if (pill) size.height / 2f else min(corner.toPx(), size.minDimension / 2f)
                    val half = bw / 2f
                    val rimW = (bw * 0.5f).coerceAtLeast(1.5f)
                    val edge = Stroke(
                        bw,
                        pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(bw * 2.4f, bw * 1.6f)) else null
                    )
                    onDrawBehind {
                        drawRoundRect(fill, cornerRadius = CornerRadius(r))
                        if (halftone != null) drawRoundRect(halftone, cornerRadius = CornerRadius(r))
                        if (rim != null) {
                            val inset = bw + rimW / 2f
                            drawRoundRect(
                                rim,
                                topLeft = Offset(inset, inset),
                                size = Size(size.width - 2f * inset, size.height - 2f * inset),
                                cornerRadius = CornerRadius((r - inset).coerceAtLeast(0f)),
                                style = Stroke(rimW)
                            )
                        }
                        drawRoundRect(
                            ink,
                            topLeft = Offset(half, half),
                            size = Size(size.width - bw, size.height - bw),
                            cornerRadius = CornerRadius((r - half).coerceAtLeast(0f)),
                            style = edge
                        )
                    }
                }
                .then(
                    if (onClick != null) {
                        Modifier
                            .clip(shape)
                            .clickable(interaction, indication = null, onClickLabel = clickLabel, role = Role.Button) {
                                tap()
                                onClick()
                            }
                    } else Modifier
                )
                .padding(border),
            contentAlignment = contentAlignment,
            content = content
        )
    }
}

/** Halftone brushes already built, by colour and pitch (a handful at most). */
private val halftones = HashMap<Long, ShaderBrush>()

/** A repeating grid of soft dots in [color], 9 dp apart, staggered like printed screentone. */
@Composable
private fun animeHalftone(color: Color): ShaderBrush {
    val pitch = with(LocalDensity.current) { 9.dp.roundToPx() }.coerceAtLeast(4)
    return remember(color, pitch) {
        halftones.getOrPut((color.toArgb().toLong() shl 12) xor pitch.toLong()) {
            val bmp = Bitmap.createBitmap(pitch, pitch, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = color.toArgb() }
            val r = pitch * 0.2f
            canvas.drawCircle(pitch * 0.25f, pitch * 0.25f, r, paint)
            canvas.drawCircle(pitch * 0.75f, pitch * 0.75f, r, paint)
            ShaderBrush(ImageShader(bmp.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
        }
    }
}

// --- Page scene -------------------------------------------------------------------

/** Where the road starts, as a fraction of the page height. */
private const val ROAD_TOP = 0.86f

/** The scene's slow loop: a cloud crosses the page once per loop, everything else a whole number of times. */
private const val SCENE_LOOP_MS = 240_000

/** The town's stepped skyline from the mockup: building edges (% of the width) and roof heights (% of the band). */
private val SkylineX = floatArrayOf(0f, 6f, 11f, 18f, 22f, 30f, 36f, 44f, 47f, 56f, 62f, 70f, 76f, 84f, 90f, 100f)
private val SkylineTop = floatArrayOf(60f, 30f, 50f, 10f, 45f, 25f, 55f, 0f, 40f, 20f, 50f, 15f, 45f, 30f, 55f)

/** A second, paler row of buildings behind, for depth. */
private val FarSkylineX = floatArrayOf(0f, 4f, 9f, 15f, 20f, 26f, 33f, 39f, 42f, 50f, 53f, 60f, 66f, 73f, 80f, 87f, 94f, 100f)
private val FarSkylineTop = floatArrayOf(20f, 0f, 35f, 12f, 28f, 5f, 30f, 18f, 2f, 25f, 10f, 32f, 8f, 22f, 4f, 26f, 14f)

/** Sakura petals: start across the width, whole cycles per scene loop (one fall every 6.5 to 10 s), colour. */
private val PetalX = floatArrayOf(0.24f, 0.41f, 0.6f, 0.75f, 0.89f, 0.97f, 0.52f)
private val PetalCycles = intArrayOf(34, 30, 37, 27, 32, 28, 24)
private val PetalPhase = floatArrayOf(0f, 0.19f, 0.46f, 0.1f, 0.29f, 0.5f, 0.62f)
private val PetalColors = listOf(Color(0xFFFF9CC7), Color(0xFFFFB8D6), Color(0xFFFF9CC7), Color(0xFFFFC7DE))

/** The page's own sky and road, without anything moving, drawn once per size into a bitmap. */
private fun DrawScope.animeStill(light: Boolean, effects: DashEffects) {
    val w = size.width
    val h = size.height
    val s = min(w, h) / 720f
    val roadTop = h * ROAD_TOP
    val t = if (light) AnimeDay else AnimeNight

    val sky = if (light) listOf(Color(0xFF5DBBFF), Color(0xFFA6DBFF), Color(0xFFFFC9E0), Color(0xFFFFE6C2))
    else listOf(Color(0xFF0B0A26), Color(0xFF1B1752), Color(0xFF3C2568), Color(0xFF74407A))
    drawRect(
        Brush.verticalGradient(0f to sky[0], 0.4f to sky[1], 0.74f to sky[2], 1f to sky[3], startY = 0f, endY = roadTop),
        size = Size(w, roadTop)
    )

    val rnd = Random(87)
    if (light) {
        // A soft sun low over the town; a flat disc with effects off.
        val c = Offset(w * 0.76f, roadTop * 0.66f)
        if (effects == DashEffects.NONE) {
            drawCircle(Color(0xFFFFF6D8), 80f * s, c)
        } else {
            val r = 150f * s
            drawCircle(
                Brush.radialGradient(
                    0f to Color(0xFFFFFBE6), 0.54f to Color(0xFFFFFBE6), 0.72f to Color(0xB3FFF0BE), 1f to Color(0x00FFF0BE),
                    center = c, radius = r
                ),
                r, c
            )
        }
    } else {
        // Stars thinning toward the town's glow, a few anime sparkles, and a crescent moon.
        val small = ArrayList<Offset>()
        val big = ArrayList<Offset>()
        repeat(90) {
            val y = rnd.nextFloat().let { it * it } * roadTop * 0.62f
            val p = Offset(rnd.nextFloat() * w, y)
            if (rnd.nextInt(5) == 0) big += p else small += p
        }
        drawPoints(small, PointMode.Points, Color(0xCCFFFFFF), strokeWidth = 1.8f * s, cap = StrokeCap.Round)
        drawPoints(big, PointMode.Points, Color.White, strokeWidth = 3f * s, cap = StrokeCap.Round)
        for (i in SPARKLES.indices step 2) animeSparkle(Offset(SPARKLES[i] * w, SPARKLES[i + 1] * roadTop), 9f * s, Color(0xFFFFF1C9))
        val mc = Offset(w * 0.8f, roadTop * 0.22f)
        val mr = 32f * s
        if (effects != DashEffects.NONE) {
            drawCircle(
                Brush.radialGradient(listOf(Color(0x40FFF1C9), Color.Transparent), mc, mr * 3.4f),
                mr * 3.4f, mc, alpha = effects.scale
            )
        }
        val moon = Path().apply {
            op(
                Path().apply { addOval(Rect(mc, mr)) },
                Path().apply { addOval(Rect(mc + Offset(14f * s, -9f * s), mr * 0.86f)) },
                PathOperation.Difference
            )
        }
        drawPath(moon, Color(0xFFFFF1C9))
    }

    // The town: a paler far row, then the near skyline from the mockup with its windows.
    val band = 118f * s
    val far = if (light) Color(0xFFC6B6EC) else Color(0xFF2C2560)
    val near = if (light) Color(0xFFA88FD8) else Color(0xFF221C4A)
    skyline(FarSkylineX, FarSkylineTop, roadTop, band * 1.25f, far)
    skyline(SkylineX, SkylineTop, roadTop, band, near)
    val winW = 6f * s
    val winH = 8f * s
    val lit = Color(0xFFFFD873)
    val dark = if (light) Color(0xFFC9B8F0) else Color(0xFF3A3170)
    for (i in 0 until SkylineTop.size) {
        val x0 = SkylineX[i] / 100f * w
        val x1 = SkylineX[i + 1] / 100f * w
        val top = roadTop - band * (1f - SkylineTop[i] / 100f)
        var y = top + 10f * s
        while (y + winH < roadTop - 8f * s) {
            var x = x0 + 8f * s
            while (x + winW < x1 - 6f * s) {
                val on = !light && rnd.nextInt(10) < 3
                drawRect(if (on) lit else dark, Offset(x, y), Size(winW, winH), alpha = if (on) 1f else 0.85f)
                x += 15f * s
            }
            y += 17f * s
        }
    }

    // The road: asphalt, a kerb along its top edge and a thin ink line under it.
    drawRect(t.road, Offset(0f, roadTop), Size(w, h - roadTop))
    drawRect(t.kerb, Offset(0f, roadTop), Size(w, 7f * s))
    drawRect(t.ink, Offset(0f, roadTop + 7f * s), Size(w, 2.5f * s))
}

/** Night sparkles' places: x, y as fractions of the width and of the sky. */
private val SPARKLES = floatArrayOf(0.12f, 0.12f, 0.34f, 0.06f, 0.55f, 0.18f, 0.68f, 0.08f, 0.92f, 0.36f, 0.22f, 0.4f)

private fun DrawScope.skyline(xs: FloatArray, tops: FloatArray, base: Float, band: Float, color: Color) {
    val w = size.width
    val path = Path().apply {
        moveTo(0f, base)
        for (i in tops.indices) {
            val y = base - band * (1f - tops[i] / 100f)
            lineTo(xs[i] / 100f * w, y)
            lineTo(xs[i + 1] / 100f * w, y)
        }
        lineTo(w, base)
        close()
    }
    drawPath(path, color)
}

/** A four-pointed anime sparkle of half-size [r] at [c]. */
internal fun DrawScope.animeSparkle(c: Offset, r: Float, color: Color, alpha: Float = 1f) {
    val k = r * 0.22f
    val p = Path().apply {
        moveTo(c.x, c.y - r)
        quadraticTo(c.x + k * 0.4f, c.y - k * 0.4f, c.x + r, c.y)
        quadraticTo(c.x + k * 0.4f, c.y + k * 0.4f, c.x, c.y + r)
        quadraticTo(c.x - k * 0.4f, c.y + k * 0.4f, c.x - r, c.y)
        quadraticTo(c.x - k * 0.4f, c.y - k * 0.4f, c.x, c.y - r)
        close()
    }
    drawPath(p, color, alpha)
}

/** A rounded cloud: a pill and puffs, as offsets from its corner in 720-high units (x, y, w or r...). */
private class Cloud(val pill: Rect, val puffs: FloatArray, val x: Float, val y: Float, val cycles: Int)

private val Clouds = listOf(
    Cloud(Rect(0f, 40f, 180f, 110f), floatArrayOf(90f, 50f, 50f), x = 0.06f, y = 0.28f, cycles = 1),
    Cloud(Rect(0f, 50f, 220f, 120f), floatArrayOf(95f, 55f, 55f, 160f, 60f, 40f), x = 0.46f, y = 0.31f, cycles = 1),
    Cloud(Rect(0f, 24f, 110f, 64f), floatArrayOf(50f, 24f, 28f), x = 0.72f, y = 0.1f, cycles = 2)
)

/**
 * The whole page as the opening scene. The still parts are drawn once per
 * size (and appearance) into a bitmap; each step of the ambient ticker then
 * only lays that bitmap and moves the clouds, the lane line and the petals.
 * With effects off the ticker stands still, so this is one still frame, with
 * no petals; reduced keeps four petals at half the frame rate.
 */
@Composable
internal fun animeBackground(): Modifier {
    val light = DashColors.Light
    val effects = DashColors.Effects
    val time = rememberLoop(SCENE_LOOP_MS)
    return remember(light, effects, time) { animeScene(light, effects, time) }
}

private fun animeScene(light: Boolean, effects: DashEffects, time: State<Float>): Modifier = Modifier.drawWithCache {
    val w = size.width
    val h = size.height
    if (w < 2f || h < 2f) return@drawWithCache onDrawBehind { }
    val s = min(w, h) / 720f
    val still = ImageBitmap(w.toInt(), h.toInt())
    CanvasDrawScope().draw(this, layoutDirection, androidx.compose.ui.graphics.Canvas(still), Size(w.toInt().toFloat(), h.toInt().toFloat())) {
        animeStill(light, effects)
    }
    val t = if (light) AnimeDay else AnimeNight
    val roadTop = h * ROAD_TOP
    val laneY = roadTop + (h - roadTop) * 0.55f
    val dashW = 80f * s
    val dashH = 9f * s
    val dashStroke = Stroke(2f * s)
    val cloudFill = if (light) Color.White else Color(0xFF3A3470)
    val cloudShade = if (light) Color(0x80A0BEE6) else Color(0xCC1E1A44)
    val span = w + 420f * s
    val petal = Path().apply {
        // border-radius 50% 0: two round corners and two sharp tips, centred on the origin.
        val pw = 14f * s
        val ph = 9f * s
        moveTo(pw / 2f, -ph / 2f)
        quadraticTo(-pw / 2f, -ph / 2f, -pw / 2f, ph / 2f)
        quadraticTo(pw / 2f, ph / 2f, pw / 2f, -ph / 2f)
        close()
    }
    val petals = when (effects) {
        DashEffects.FULL -> PetalX.size
        DashEffects.REDUCED -> 4
        DashEffects.NONE -> 0
    }
    val twinkle = !light && effects != DashEffects.NONE
    onDrawBehind {
        val p = time.value
        drawImage(still)

        // Clouds drift left and come back in from the right.
        for (c in Clouds) {
            val x0 = c.x * w - p * span * c.cycles
            val x = ((x0 % span) + span) % span - 300f * s
            val y = c.y * h
            for (pass in 0..1) {
                val dy = if (pass == 0) 8f * s else 0f
                val color = if (pass == 0) cloudShade else cloudFill
                drawRoundRect(
                    color,
                    topLeft = Offset(x + c.pill.left * s, y + c.pill.top * s + dy),
                    size = Size(c.pill.width * s, c.pill.height * s),
                    cornerRadius = CornerRadius(c.pill.height * s / 2f)
                )
                var i = 0
                while (i + 2 < c.puffs.size) {
                    drawCircle(color, c.puffs[i + 2] * s, Offset(x + c.puffs[i] * s, y + c.puffs[i + 1] * s + dy))
                    i += 3
                }
            }
        }

        if (twinkle) {
            for (i in 0 until 3) {
                val a = 0.5f + 0.5f * sin((p * 160f * (i + 2) + i) * 2f * PI.toFloat())
                animeSparkle(Offset(SPARKLES[i * 4] * w, SPARKLES[i * 4 + 1] * roadTop), 9f * s, Color.White, a * effects.scale)
            }
        }

        // The yellow lane line runs right to left, two dashes per second.
        val shift = (p * SCENE_LOOP_MS / 500f % 1f) * dashW * 2f
        var x = -shift
        while (x < w) {
            drawRoundRect(t.yellow, Offset(x, laneY - dashH / 2f), Size(dashW, dashH), CornerRadius(dashH / 2f))
            if (light) drawRoundRect(t.ink, Offset(x, laneY - dashH / 2f), Size(dashW, dashH), CornerRadius(dashH / 2f), style = dashStroke)
            x += dashW * 2f
        }

        // Sakura petals tumbling down and to the left.
        for (i in 0 until petals) {
            val f = (p * PetalCycles[i] + PetalPhase[i]) % 1f
            val px = PetalX[i] * w - f * h * 0.3f + sin((f * 2f + i * 0.3f) * 2f * PI.toFloat()) * 18f * s
            val py = -30f * s + f * (h + 60f * s)
            val alpha = (f / 0.1f).coerceAtMost(1f) * 0.95f
            withTransform({
                translate(px, py)
                rotate(f * 540f + i * 40f, Offset.Zero)
            }) {
                drawPath(petal, PetalColors[i % PetalColors.size], alpha)
            }
        }
    }
}

// --- Top bar ----------------------------------------------------------------------

private val BAR_STICKER = 52.dp

/**
 * The bar as a strip of stickers, in the same order as every look: Apps and
 * Layout on the left (with their words where the bar is wide), then the pink
 * "talk to me" pill for Gemini Live when it is on the unit; the clock tag
 * with the outside temperature and the dashboard's position in the middle;
 * the setup pill, alerts, the phone, the OBD sticker and ⋮ on the right.
 */
@Composable
internal fun AnimeTopBar(m: TopBarModel) {
    val t = animeTones
    val context = LocalContext.current
    val gemini = remember { GeminiLive.available(context) }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(64.dp)) {
        val narrow = maxWidth < NARROW_BAR
        val labels = maxWidth >= LABELLED_BAR
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = if (narrow) Modifier else Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AnimeBarPill(
                    onClick = m.onApps,
                    description = stringResource(R.string.dash_all_apps),
                    label = if (labels) stringResource(R.string.dash_apps) else null,
                    fill = t.paper,
                    tilt = -2f
                ) { tint -> Icon(Icons.Filled.Apps, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp)) }
                LayoutPicker(m) { open ->
                    AnimeBarPill(
                        onClick = open,
                        description = stringResource(R.string.dash_screen_layout, m.layout.title),
                        label = if (labels) stringResource(R.string.dash_layout_button) else null,
                        fill = t.lemon,
                        tilt = 1.5f
                    ) { tint -> LayoutIcon(m.layout, null, tint, Modifier.size(26.dp)) }
                }
                if (gemini) AnimeTalkPill(label = labels && !narrow)
            }
            Row(modifier = Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                // The head unit's status bar shows the time while it is up.
                if (!m.merged) AnimeClockTag(m.clock)
                PagePositionChip(Modifier.padding(start = 10.dp))
            }
            BarEnd(modifier = Modifier.weight(1f)) {
                if (m.setupPending) {
                    Box(Modifier.layoutId(BarRank.SETUP).padding(end = 6.dp)) {
                        SetupPill(onClick = { m.onSetup(false) }, compact = narrow)
                    }
                }
                Row(modifier = Modifier.layoutId(BarRank.ALERTS), verticalAlignment = Alignment.CenterVertically) {
                    VehicleAlerts(m.obdConnection, m.obd)
                }
                Box(Modifier.layoutId(BarRank.PHONE).padding(end = 6.dp)) { PhonePill() }
                Box(Modifier.layoutId(BarRank.OBD).padding(end = 8.dp)) { AnimeObdSticker(m.obdConnection, m.onConnectObd) }
                Box(Modifier.layoutId(BarRank.MORE)) {
                    MorePicker(m, geminiInBar = gemini) { open ->
                        AnimeBarPill(
                            onClick = open,
                            description = stringResource(R.string.dash_more),
                            label = null,
                            fill = t.paper,
                            tilt = 2f
                        ) { tint -> Icon(Icons.Filled.MoreVert, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp)) }
                    }
                }
            }
        }
    }
}

/** A sticker pill of the bar: [BAR_STICKER] tall, its icon (drawn in the ink that reads on [fill]) and word. */
@Composable
private fun AnimeBarPill(
    onClick: () -> Unit,
    description: String,
    label: String?,
    fill: Color,
    tilt: Float,
    icon: @Composable (Color) -> Unit
) {
    val on = animeOn(fill)
    AnimeSticker(
        modifier = Modifier
            .height(BAR_STICKER)
            .widthIn(min = BAR_STICKER)
            .semantics(mergeDescendants = true) { contentDescription = description },
        fill = fill,
        pill = true,
        shadow = 4.dp,
        border = 3.dp,
        rotation = tilt,
        onClick = onClick,
        clickLabel = description
    ) {
        Row(
            modifier = Modifier.padding(horizontal = if (label != null) 14.dp else 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            icon(on)
            if (label != null) {
                Spacer(Modifier.width(8.dp))
                AnimeText(label, animeRound(17f, on))
            }
        }
    }
}

/** The bar's clock tag ("09:24 · 14°"), the temperature once the weather is known. */
@Composable
private fun AnimeClockTag(clock: String) {
    val t = animeTones
    val weather by WeatherRepo.weather.collectAsState()
    val units = LocalUnits.current
    val text = weather?.let { "$clock · ${units.temp(it.tempC)}°" } ?: clock
    AnimeSticker(
        modifier = Modifier.height(BAR_STICKER),
        fill = t.paper,
        pill = true,
        shadow = 4.dp,
        border = 3.dp,
        rotation = -1f
    ) {
        AnimeFigure(text, animeRound(22f, t.onPaper), Modifier.padding(horizontal = 16.dp))
    }
}

/**
 * Gemini Live as the scene's hero button: a pink pill with a mic and "TALK
 * TO ME!" in comic letters; while the conversation is open it turns red with
 * a close mark and breathes, so the driver sees Gemini is listening.
 */
@Composable
private fun AnimeTalkPill(label: Boolean) {
    val context = LocalContext.current
    val t = animeTones
    val active by GeminiLive.active.collectAsState()
    val description = stringResource(if (active) R.string.ai_gemini_live_end else R.string.ai_gemini_live)
    val fill = if (active) DashColors.Critical else t.pink
    val pulse = if (active) rememberLoop(900, reverse = true, status = true) else null
    Box(Modifier.graphicsLayer { alpha = pulse?.let { 0.65f + 0.35f * it.value } ?: 1f }) {
        AnimeSticker(
            modifier = Modifier
                .height(BAR_STICKER)
                .widthIn(min = BAR_STICKER)
                .semantics(mergeDescendants = true) { contentDescription = description },
            fill = fill,
            pill = true,
            shadow = 4.dp,
            border = 3.dp,
            rotation = -1.5f,
            onClick = { GeminiLive.toggle(context) },
            clickLabel = description
        ) {
            Row(
                modifier = Modifier.padding(horizontal = if (label) 16.dp else 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(if (active) Icons.Filled.Close else Icons.Filled.Mic, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
                if (label) {
                    Spacer(Modifier.width(8.dp))
                    AnimeInkText(
                        stringResource(if (active) R.string.skin_anime_talk_end else R.string.skin_anime_talk),
                        animeComic(22f, Color.White),
                        t.ink,
                        0.07f
                    )
                }
            }
        }
    }
}

/**
 * The OBD link as a sticker that reads without its colour: mint with a full
 * dot when live, paper with a hollow ring when off, a pink dot that pulses
 * while connecting, red with "!" on error. What a tap does is [ObdMark]'s.
 */
@Composable
private fun AnimeObdSticker(state: ObdConnectionState, onConnect: () -> Unit) {
    val t = animeTones
    val fill = when (state) {
        ObdConnectionState.CONNECTED -> t.mint
        ObdConnectionState.ERROR -> DashColors.Critical
        else -> t.paper
    }
    val on = animeOn(fill)
    val pulse = if (state == ObdConnectionState.CONNECTING) rememberLoop(900, reverse = true, status = true) else null
    val dot = when (state) {
        ObdConnectionState.CONNECTED -> DashColors.Good
        ObdConnectionState.CONNECTING -> t.pink
        ObdConnectionState.ERROR -> Color.White
        ObdConnectionState.DISCONNECTED -> on
    }
    ObdMark(state, onConnect, shape = RoundedCornerShape(percent = 50)) {
        AnimeSticker(Modifier.height(BAR_STICKER), fill = fill, pill = true, shadow = 4.dp, border = 3.dp, rotation = 1.5f) {
            Row(modifier = Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(
                    Modifier
                        .size(14.dp)
                        .drawWithCache {
                            val r = size.minDimension / 2f
                            val ring = Stroke(2.5.dp.toPx())
                            onDrawBehind {
                                when (state) {
                                    ObdConnectionState.DISCONNECTED -> drawCircle(dot, r - ring.width / 2f, style = ring)
                                    ObdConnectionState.CONNECTING -> drawCircle(dot, r * (0.55f + 0.45f * (pulse?.value ?: 1f)))
                                    else -> {
                                        drawCircle(on, r)
                                        drawCircle(dot, r * 0.7f)
                                    }
                                }
                            }
                        }
                )
                Spacer(Modifier.width(8.dp))
                AnimeText(stringResource(R.string.dash_obd_short), animeRound(17f, on))
                if (state == ObdConnectionState.ERROR) AnimeText(" !", animeRound(17f, on))
            }
        }
    }
}

// --- Maps window frame ------------------------------------------------------------

/**
 * A comic panel round a docked Maps window: its corners rounded off in ink,
 * a thick ink border with a pale rim inside it, and two strips of pink washi
 * tape holding the panel at opposite corners. Nothing in the middle.
 */
@Composable
internal fun AnimeWindowFrame(modifier: Modifier) {
    val t = animeTones
    val rim = t.rim ?: Color.White
    Spacer(
        modifier
            .fillMaxSize()
            .drawWithCache {
                val w = size.width
                val h = size.height
                val bw = 5.dp.toPx()
                val r = 22.dp.toPx()
                val rimW = 2.5.dp.toPx()
                val corners = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(0f, 0f, w, h))
                    addRoundRect(RoundRect(Rect(0f, 0f, w, h), CornerRadius(r)))
                }
                val tapeW = 64.dp.toPx()
                val tapeH = 20.dp.toPx()
                val tape = Color(0xBFFF8FC0)
                val tapeEdge = Stroke(1.5.dp.toPx())
                onDrawBehind {
                    drawPath(corners, t.ink)
                    drawRoundRect(
                        t.ink, topLeft = Offset(bw / 2f, bw / 2f), size = Size(w - bw, h - bw),
                        cornerRadius = CornerRadius(r - bw / 2f), style = Stroke(bw)
                    )
                    val inset = bw + rimW / 2f
                    drawRoundRect(
                        rim, topLeft = Offset(inset, inset), size = Size(w - 2f * inset, h - 2f * inset),
                        cornerRadius = CornerRadius((r - inset).coerceAtLeast(0f)), style = Stroke(rimW)
                    )
                    for ((c, deg) in listOf(Offset(tapeH, tapeH) to -38f, Offset(w - tapeH, h - tapeH) to -38f)) {
                        rotate(deg, c) {
                            val tl = Offset(c.x - tapeW / 2f, c.y - tapeH / 2f)
                            drawRect(tape, tl, Size(tapeW, tapeH))
                            drawRect(t.ink.copy(alpha = 0.35f), tl, Size(tapeW, tapeH), style = tapeEdge)
                        }
                    }
                }
            }
    )
}

// --- Theme stamp ------------------------------------------------------------------

/** The skin in the theme picker: its sky over the town and road, a cloud, the speed burst and a speech bubble. */
internal fun DrawScope.animeStamp(p: DashPalette, number: StampNumber) {
    val w = size.width
    val h = size.height
    val t = if (p.Light) AnimeDay else AnimeNight
    val roadTop = h * 0.8f
    drawRect(Brush.verticalGradient(p.BackgroundStops, startY = 0f, endY = roadTop), size = Size(w, roadTop))
    if (p.Light) drawCircle(Color(0xFFFFFBE6), h * 0.16f, Offset(w * 0.78f, h * 0.5f))
    else drawCircle(Color(0xFFFFF1C9), h * 0.08f, Offset(w * 0.84f, h * 0.2f))
    drawRoundRect(if (p.Light) Color.White else Color(0xFF3A3470), Offset(w * 0.48f, h * 0.22f), Size(w * 0.2f, h * 0.1f), CornerRadius(h * 0.05f))
    drawCircle(if (p.Light) Color.White else Color(0xFF3A3470), h * 0.07f, Offset(w * 0.55f, h * 0.22f))
    skyline(SkylineX, SkylineTop, roadTop, h * 0.18f, if (p.Light) Color(0xFFA88FD8) else Color(0xFF221C4A))
    drawRect(t.road, Offset(0f, roadTop), Size(w, h - roadTop))
    drawRect(t.kerb, Offset(0f, roadTop), Size(w, h * 0.02f))
    var x = w * 0.04f
    while (x < w) {
        drawRect(t.yellow, Offset(x, h * 0.89f), Size(w * 0.07f, h * 0.025f))
        x += w * 0.14f
    }
    val burst = Rect(w * 0.06f, h * 0.08f, w * 0.38f, h * 0.72f)
    drawPath(animeBurstPath(burst), t.ink)
    drawPath(animeBurstPath(burst.deflate(h * 0.03f)), t.yellow)
    number("87", burst.center.x, burst.center.y, 14f, t.pink)
    val bubble = Rect(w * 0.6f, h * 0.48f, w * 0.94f, h * 0.7f)
    drawRoundRect(t.ink, bubble.topLeft + Offset(3f, 3f), bubble.size, CornerRadius(h * 0.08f))
    drawRoundRect(t.paper, bubble.topLeft, bubble.size, CornerRadius(h * 0.08f))
    drawRoundRect(t.ink, bubble.topLeft, bubble.size, CornerRadius(h * 0.08f), style = Stroke(1.5f))
}
