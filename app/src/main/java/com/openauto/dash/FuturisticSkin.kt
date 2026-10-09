package com.openauto.dash

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/*
 * Futuristic skin: a holographic HUD in deep space. The page is near black
 * with cyan and violet light pools and a perspective grid floor that scrolls
 * toward the viewer; the driver's own car floats over its tile as a glowing
 * cyan line hologram on a pad of spinning rings, a scan line sweeping over it
 * and its readings pointed out with dots and leader lines
 * (FuturisticSkinHologram.kt). Speed sits in a ticked ring with a lit arc,
 * directions light perspective chevrons in sequence, music dances on an
 * equalizer, and every panel is a cut-corner HUD plate with a hairline border
 * and bright corner accents (FuturisticSkinTiles.kt). Numerals are the system
 * sans stretched wide with tabular digits, the nearest a system face gets to
 * a display face like Orbitron, and they keep their width as values change.
 *
 * The day version is a lab: a pale blue-white page with deep teal ink, the
 * same geometry and lines, no glow smear. With effects off nothing moves and
 * nothing glows, and the panels turn opaque.
 */

internal val FuturisticPalette = DashPalette(
    Background = Color(0xFF03050A), Bar = Color.Transparent, Card = Color(0xFF0A1628), CardHi = Color(0x223DF5FF),
    Accent = Color(0xFF3DF5FF), Secondary = Color(0xFF8B7BFF), Critical = Color(0xFFFF4D6D),
    Good = Color(0xFF52E58A), Muted = Color(0xFF7FA3C0), TextPrimary = Color(0xFFF2FBFF), TextSecondary = Color(0xFF9FB4C8),
    Accent2 = Color(0xFF8B7BFF), Line = Color(0x593DF5FF), Glow = 1f, Bare = true,
    BackgroundStops = listOf(Color(0xFF071427), Color(0xFF03050A), Color(0xFF0D0A24)),
    Skin = DashSkin.FUTURISTIC
)

// Day: a lab bench under daylight. Deep teal ink on a pale blue-white page,
// the violet deepened so it still reads, glows kept to a trace.
internal val FuturisticLightPalette = DashPalette(
    Background = Color(0xFFEAF2F7), Bar = Color.Transparent, Card = Color(0xFFF9FCFE), CardHi = Color(0x1A0A6E7C),
    Accent = Color(0xFF0A6E7C), Secondary = Color(0xFF5B4BD0), Critical = Color(0xFFD12C4A),
    Good = Color(0xFF138A4C), Muted = Color(0xFF557584), TextPrimary = Color(0xFF07262E), TextSecondary = Color(0xFF2C4D59),
    Accent2 = Color(0xFF5B4BD0), Line = Color(0x400A6E7C), Glow = 0.25f, Light = true, Bare = true,
    BackgroundStops = listOf(Color(0xFFF5FAFC), Color(0xFFE6F0F5), Color(0xFFEDEAFA)),
    Skin = DashSkin.FUTURISTIC
)

/** The skin's corners: cut, not rounded, so dialogs and menus are HUD plates too; big cards cut on one diagonal. */
internal val FuturisticShapes = Shapes(
    extraSmall = CutCornerShape(4.dp),
    small = CutCornerShape(6.dp),
    medium = CutCornerShape(10.dp),
    large = CutCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomEnd = 16.dp, bottomStart = 4.dp),
    extraLarge = CutCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomEnd = 16.dp, bottomStart = 4.dp)
)

// --- Shared look ----------------------------------------------------------------------

/** How strongly glows are drawn: the palette's glow scaled by the effects setting (0 with effects off, a trace by day). */
internal val futGlow: Float get() = DashColors.Glow

/** The hottest line ink: a pale cyan core at night (the glow around it is the accent), the deep teal by day. */
internal val FutCore: Color get() = if (DashColors.Light) DashColors.Accent else lerp(DashColors.Accent, Color.White, 0.4f)

/** Ink on a filled cyan button: near black at night, white on the deep teal by day. */
internal val FutOnFill: Color get() = if (DashColors.Light) Color.White else Color(0xFF03050A)

/** The panels' big cut (top-left, bottom-right) and the small one (the other two corners). */
internal val FUT_CUT = 16.dp
internal val FUT_CUT_SMALL = 5.dp

/** A tile's panel shape, for clipping its ripple to the plate. */
internal val FutPanelShape: Shape = FutHudShape(FUT_CUT, FUT_CUT_SMALL)

/** Wide numerals: the system sans stretched, as a display face would be. */
private val WideGlyphs = TextGeometricTransform(scaleX = 1.16f)

private val NumLineHeight = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

/**
 * Hero numerals ([sizeDp] follows the tile, whatever the font scale): bold
 * sans stretched wide, tabular digits so a reading never jumps, with a soft
 * [glow] of their colour at night (effects at full only, see [softTextShadow]).
 */
@Composable
internal fun futNum(
    sizeDp: Float,
    color: Color = DashColors.TextPrimary,
    glow: Color = DashColors.Accent,
    weight: FontWeight = FontWeight.Bold
): TextStyle {
    val d = LocalDensity.current.density
    return TextStyle(
        color = color,
        fontFamily = FontFamily.SansSerif,
        fontWeight = weight,
        fontSize = fixedSp(sizeDp),
        lineHeight = 1.08.em,
        lineHeightStyle = NumLineHeight,
        letterSpacing = 0.02.em,
        fontFeatureSettings = "tnum",
        textGeometricTransform = WideGlyphs,
        shadow = if (DashColors.Light) null else softTextShadow(glow.copy(alpha = 0.75f), (sizeDp * 0.26f).coerceIn(4f, 26f) * d)
    )
}

/** Letter-spaced HUD caps ("ENGINE"): set the text uppercase where it is used. */
@Composable
internal fun futCaps(sizeDp: Float, color: Color = DashColors.Muted, weight: FontWeight = FontWeight.Medium): TextStyle = TextStyle(
    color = color,
    fontFamily = FontFamily.SansSerif,
    fontWeight = weight,
    fontSize = fixedSp(sizeDp),
    lineHeight = 1.25.em,
    letterSpacing = 0.2.em
)

/** Plain reading text (titles, instructions). */
@Composable
internal fun futText(sizeDp: Float, color: Color = DashColors.TextPrimary, weight: FontWeight = FontWeight.Normal): TextStyle = TextStyle(
    color = color,
    fontFamily = FontFamily.SansSerif,
    fontWeight = weight,
    fontSize = fixedSp(sizeDp),
    lineHeight = 1.25.em,
    letterSpacing = 0.01.em
)

/** One line of HUD text (or [maxLines]), ellipsized. */
@Composable
internal fun FutText(
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
 * Largest size (dp-based, see [fixedSp]) within [minDp]..[maxDp] at which
 * [sample] in [style] fits [maxW] on one line, its line no taller than [maxH].
 * Measured once at 100 and scaled, since type grows linearly with size.
 */
@Composable
internal fun futFitSp(sample: String, style: TextStyle, maxW: Dp, maxH: Dp, minDp: Float, maxDp: Float): Float {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val probe = fixedSp(100f)
    return remember(sample, style, maxW, maxH, minDp, maxDp, density, probe) {
        val m = measurer.measure(sample, style.copy(fontSize = probe, shadow = null), softWrap = false, maxLines = 1)
        val byW = with(density) { maxW.toPx() } * 100f / m.size.width.coerceAtLeast(1)
        val byH = with(density) { maxH.toPx() } * 100f / m.size.height.coerceAtLeast(1)
        min(byW, byH).coerceIn(minDp, max(minDp, maxDp))
    }
}

/** The first of [candidates] (longest first) that fits [maxW] on one line, else the last. */
@Composable
internal fun futFirstFitting(candidates: List<String>, style: TextStyle, maxW: Dp): String {
    val measurer = rememberTextMeasurer()
    val px = with(LocalDensity.current) { maxW.toPx() }
    return remember(candidates, style, px, measurer) {
        candidates.firstOrNull { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width <= px } ?: candidates.last()
    }
}

/** Digits as '8' so a readout is sized once, whatever its value. */
internal fun futTemplate(text: String): String = text.map { if (it.isDigit()) '8' else it }.joinToString("")

/**
 * The HUD plate's outline in a [w] x [h] box: the top-left and bottom-right
 * corners cut by [big], the other two by [small], drawn [inset] in from the
 * edges (half a stroke, so a border is not cut by the clip).
 */
internal fun futHudPath(w: Float, h: Float, big: Float, small: Float, inset: Float = 0f): Path {
    val b = min(big, min(w, h) * 0.3f)
    val s = min(small, min(w, h) * 0.15f)
    val l = inset
    val t = inset
    val r = w - inset
    val bo = h - inset
    return Path().apply {
        moveTo(l + b, t)
        lineTo(r - s, t)
        lineTo(r, t + s)
        lineTo(r, bo - b)
        lineTo(r - b, bo)
        lineTo(l + s, bo)
        lineTo(l, bo - s)
        lineTo(l, t + b)
        close()
    }
}

/** [futHudPath] as a shape, for clips and ripples. */
internal class FutHudShape(private val big: Dp, private val small: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(with(density) { futHudPath(size.width, size.height, big.toPx(), small.toPx()) })
}

/** How a panel is framed: a full plate, or only its corner brackets over a fainter fill. */
internal enum class FutFrame { PLATE, BRACKETS }

/**
 * A HUD panel behind the content, in [tone] (cyan or violet): a translucent
 * deep-navy plate (pale glass by day) with a light pool in it, a specular top
 * edge, a hairline border and bright L-shaped accents on the two big cut
 * corners; outside it, a soft halo of stacked strokes (no blur on Android 10).
 * [editing] dashes the border, so a tile reads as movable. With effects off
 * the plate is opaque and there is no halo.
 */
@Composable
internal fun Modifier.futPanel(tone: Color, editing: Boolean = false, frame: FutFrame = FutFrame.PLATE): Modifier {
    val light = DashColors.Light
    val effects = DashColors.Effects
    val glow = futGlow
    val card = DashColors.Card
    return cachedDraw(tone, editing, frame, light, effects, glow, card) {
        val w = size.width
        val h = size.height
        val line = 1.dp.toPx()
        val inset = line / 2f
        val big = min(FUT_CUT.toPx(), min(w, h) * 0.3f)
        val small = min(FUT_CUT_SMALL.toPx(), min(w, h) * 0.15f)
        val outline = futHudPath(w, h, big, small, inset)
        val plain = effects == DashEffects.NONE
        val fill: Brush = when {
            plain -> SolidColor(card)
            light -> Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.84f), Color(0xFFF0F6F9).copy(alpha = 0.76f)))
            else -> Brush.verticalGradient(listOf(Color(0xFF0B1A30).copy(alpha = 0.62f), Color(0xFF050C18).copy(alpha = 0.76f)))
        }
        val fillAlpha = if (frame == FutFrame.BRACKETS && !plain) 0.55f else 1f
        val wash = if (plain) null else Brush.radialGradient(
            listOf(tone.copy(alpha = if (light) 0.06f else 0.11f), Color.Transparent),
            center = Offset(w * 0.18f, 0f), radius = max(w, h) * 0.9f
        )
        val halos = if (glow > 0f && !light) listOf(Stroke(10.dp.toPx()) to 0.05f * glow, Stroke(4.dp.toPx()) to 0.10f * glow) else emptyList()
        val dash = if (editing) PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())) else null
        val border = Stroke(line, pathEffect = dash)
        val borderInk = tone.copy(alpha = if (editing) 0.9f else if (light) 0.5f else 0.42f)
        val leg = min(14.dp.toPx(), min(w, h) * 0.12f)
        val accentStroke = Stroke(2.dp.toPx(), cap = StrokeCap.Square, join = StrokeJoin.Miter)
        val corners = Path().apply {
            moveTo(inset, big + leg)
            lineTo(inset, big)
            lineTo(big, inset)
            lineTo(big + leg, inset)
            moveTo(w - inset, h - big - leg)
            lineTo(w - inset, h - big)
            lineTo(w - big, h - inset)
            lineTo(w - big - leg, h - inset)
            if (frame == FutFrame.BRACKETS) {
                moveTo(w - small - leg, inset)
                lineTo(w - small, inset)
                lineTo(w - inset, small)
                lineTo(w - inset, small + leg)
                moveTo(small + leg, h - inset)
                lineTo(small, h - inset)
                lineTo(inset, h - small)
                lineTo(inset, h - small - leg)
            }
        }
        val sheen = Color.White.copy(alpha = if (light) 0.9f else 0.10f)
        val sheenStart = Offset(big + leg, inset + line)
        val sheenEnd = Offset(w - small - line, inset + line)
        onDrawBehind {
            for ((stroke, alpha) in halos) drawPath(outline, tone.copy(alpha = alpha), style = stroke)
            drawPath(outline, fill, alpha = fillAlpha)
            if (wash != null) drawPath(outline, wash)
            if (!plain) drawLine(sheen, sheenStart, sheenEnd, line)
            if (frame == FutFrame.PLATE || editing) drawPath(outline, borderInk, style = border)
            drawPath(corners, tone, style = accentStroke)
        }
    }
}

/** The whole of [shape] tappable with the unit's tap feedback, or nothing when [enabled] is false. */
@Composable
internal fun Modifier.futTap(enabled: Boolean, label: String, shape: Shape = FutPanelShape, onClick: () -> Unit): Modifier {
    val tap = rememberTapFeedback()
    return if (enabled) clip(shape).clickable(onClickLabel = label, role = Role.Button) { tap(); onClick() } else this
}

// --- Background -----------------------------------------------------------------------

/** One grid cell's travel toward the viewer, as in the mockup (60 px in 1.6 s). */
private const val GRID_CELL_MS = 1_600

/** Top of the floor, as a share of the page height. */
private const val FLOOR_TOP = 0.58f

/** The grid's vanishing point, above the floor's far edge by this share of the floor's height. */
private const val VP_RISE = 0.25f

/** Depth between two cross lines (the nearest sits at depth 1, the floor's far edge at 5). */
private const val DEPTH_STEP = 0.32f
private const val CROSS_LINES = 16

/**
 * The page: near black with three light pools (cyan under the middle, violet
 * from the top right, cyan from the bottom left) and a perspective grid floor
 * fading in from its far edge, its cross lines rolling toward the viewer. The
 * pools and the receding lines never move: they are recorded once per size
 * and look into a layer, and each ambient step (about 20 a second, 10 with
 * effects reduced, none with effects off) only draws that layer and a dozen
 * lines. By day the page is pale blue-white with the grid in teal and the
 * pools a faint tint; with effects off there are no pools and no line glow.
 */
@Composable
internal fun futuristicBackground(): Modifier {
    val effects = DashColors.Effects
    val loop = rememberLoop(if (effects == DashEffects.REDUCED) GRID_CELL_MS * 2 else GRID_CELL_MS)
    val bg = DashColors.Background
    val cyan = DashColors.Accent
    val violet = DashColors.Secondary
    val light = DashColors.Light
    return remember(bg, cyan, violet, light, effects, loop) {
        Modifier.drawWithCache {
            val w = size.width
            val h = size.height
            val hy = h * FLOOR_TOP
            val floor = h - hy
            val vpY = hy - floor * VP_RISE
            val depth = h - vpY
            val cx = w / 2f
            val pools = when (effects) {
                DashEffects.FULL -> 1f
                DashEffects.REDUCED -> 0.6f
                DashEffects.NONE -> 0f
            } * (if (light) 0.5f else 1f)
            val base: Brush = if (light) Brush.verticalGradient(listOf(Color(0xFFF6FAFC), bg)) else SolidColor(bg)
            val ink = if (light) 0.30f else 0.40f

            // Receding lines through the vanishing point, spaced a tenth of the width at the bottom.
            val spacing = w / 10f
            val ratio = (hy - vpY) / depth
            val reach = (cx / (spacing * ratio)).toInt() + 1
            val verticals = Path().apply {
                for (i in -reach..reach) {
                    moveTo(cx + i * spacing * ratio, hy)
                    lineTo(cx + i * spacing, h)
                }
            }
            val verticalInk = Brush.verticalGradient(
                0f to cyan.copy(alpha = 0f), 0.45f to cyan.copy(alpha = ink * 0.8f), 1f to cyan.copy(alpha = ink * 0.8f),
                startY = hy, endY = h
            )
            val gridStroke = Stroke(1.dp.toPx())

            fun pool(color: Color, alpha: Float, center: Offset, rx: Float, ry: Float): Pair<Brush, Triple<Offset, Float, Float>> =
                Brush.radialGradient(0f to color.copy(alpha = alpha * pools), 0.7f to color.copy(alpha = 0f), center = center, radius = rx) to
                    Triple(center, rx, ry)
            val poolList = if (pools > 0f) listOf(
                pool(cyan, 0.22f, Offset(w * 0.5f, h * 0.66f), w * 0.4375f, h * 0.36f),
                pool(violet, 0.22f, Offset(w * 0.88f, 0f), w * 0.41f, h * 0.58f),
                pool(cyan, 0.14f, Offset(w * 0.06f, h), w * 0.33f, h * 0.44f)
            ) else emptyList()

            val scenery = obtainGraphicsLayer().apply { compositingStrategy = CompositingStrategy.Offscreen }
            scenery.record {
                drawRect(base)
                for ((brush, geo) in poolList) {
                    val (c, rx, ry) = geo
                    scale(1f, ry / rx, c) { drawCircle(brush, radius = rx, center = c) }
                }
                drawPath(verticals, verticalInk, style = gridStroke)
            }

            val lineGlow = 0.10f * DashColors.Glow
            val thin = 1.dp.toPx()
            val wide = 5.dp.toPx()
            val fadeSpan = floor * 0.45f
            onDrawBehind {
                drawLayer(scenery)
                val roll = loop.value
                for (i in 0 until CROSS_LINES) {
                    val z = 1f + (i - roll) * DEPTH_STEP
                    if (z <= 0.05f) continue
                    val y = vpY + depth / z
                    if (y > h + wide) continue
                    if (y <= hy) break
                    val fade = ((y - hy) / fadeSpan).coerceIn(0f, 1f)
                    if (fade < 0.01f) continue
                    val near = (y - hy) / floor
                    if (lineGlow > 0f && !light) drawLine(cyan.copy(alpha = lineGlow * fade), Offset(0f, y), Offset(w, y), wide)
                    drawLine(cyan.copy(alpha = ink * fade), Offset(0f, y), Offset(w, y), thin * (0.7f + 0.8f * near))
                }
            }
        }
    }
}

// --- Top bar --------------------------------------------------------------------------

private val BAR_HEIGHT = 64.dp
private val BAR_BUTTON = 60.dp
private val BarShape: Shape = FutHudShape(10.dp, 3.dp)

/** Bars at least this wide name the car beside the link's state. */
private val NAMED_BAR = 1100.dp

/**
 * The bar as a thin HUD line in the same order as every other look: all apps
 * and the layout on the left (their words in caps where the bar is wide
 * enough), the wide clock with the date and the dashboard's position in the
 * middle over a brighter stretch of the line, then the setup pill, vehicle
 * alerts, the phone, the outside temperature, the link's state (a blinking
 * dot, the car's name on a wide bar) and the ⋮ menu.
 */
@Composable
internal fun FuturisticTopBar(m: TopBarModel) {
    val cyan = DashColors.Accent
    val glow = futGlow
    val ink = DashColors.TextPrimary
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(BAR_HEIGHT)
            // Own layer, so the rolling grid behind never re-records the bar.
            .graphicsLayer()
            .cachedDraw(cyan, glow) {
                val w = size.width
                val y = size.height - 1.dp.toPx()
                val rail = Brush.horizontalGradient(
                    0f to cyan.copy(alpha = 0f), 0.14f to cyan.copy(alpha = 0.42f),
                    0.86f to cyan.copy(alpha = 0.42f), 1f to cyan.copy(alpha = 0f)
                )
                val mid0 = w * 0.36f
                val mid1 = w * 0.64f
                val tick = 6.dp.toPx()
                val hair = 1.dp.toPx()
                val bright = 1.5.dp.toPx()
                val bloom = 6.dp.toPx()
                val dot = 1.5.dp.toPx()
                onDrawBehind {
                    drawLine(rail, Offset(0f, y), Offset(w, y), hair)
                    if (glow > 0f) drawLine(cyan.copy(alpha = 0.16f * glow), Offset(mid0 + tick, y), Offset(mid1 - tick, y), bloom)
                    drawLine(cyan, Offset(mid0 + tick, y), Offset(mid1 - tick, y), bright)
                    drawLine(cyan, Offset(mid0, y - tick), Offset(mid0 + tick, y), bright)
                    drawLine(cyan, Offset(mid1, y - tick), Offset(mid1 - tick, y), bright)
                    drawCircle(cyan.copy(alpha = 0.7f), dot, Offset(w * 0.14f, y))
                    drawCircle(cyan.copy(alpha = 0.7f), dot, Offset(w * 0.86f, y))
                }
            }
            .padding(horizontal = 8.dp)
    ) {
        val narrow = maxWidth < NARROW_BAR
        val labels = maxWidth >= LABELLED_BAR
        val named = maxWidth >= NAMED_BAR
        val shared = barIsShared()
        Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Row(modifier = if (narrow) Modifier else Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                FutBarButton(
                    onClick = m.onApps,
                    description = stringResource(R.string.skin_futuristic_cd_all_apps),
                    label = if (labels) stringResource(R.string.dash_apps) else null
                ) {
                    Icon(Icons.Filled.Apps, contentDescription = null, tint = cyan, modifier = Modifier.size(26.dp))
                }
                LayoutPicker(m) { open ->
                    FutBarButton(
                        onClick = open,
                        description = stringResource(R.string.skin_futuristic_cd_screen_layout, m.layout.title),
                        label = if (labels) stringResource(R.string.dash_layout_button) else null
                    ) {
                        LayoutIcon(m.layout, null, cyan, Modifier.size(26.dp))
                    }
                }
            }
            // The head unit's status bar shows the time while it is up.
            Row(modifier = Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!m.merged) FutBarClock(m.clock, showDate = !narrow)
                PagePositionChip(Modifier.padding(start = 10.dp))
            }
            BarEnd(modifier = Modifier.weight(1f)) {
                if (m.setupPending) {
                    Box(Modifier.layoutId(BarRank.SETUP).padding(end = 6.dp)) {
                        SetupPill(onClick = { m.onSetup(false) }, compact = narrow || shared)
                    }
                }
                Row(modifier = Modifier.layoutId(BarRank.ALERTS), verticalAlignment = Alignment.CenterVertically) {
                    VehicleAlerts(m.obdConnection, m.obd)
                }
                Box(Modifier.layoutId(BarRank.PHONE)) { PhonePill() }
                if (!narrow) Box(Modifier.layoutId(BarRank.TEMP)) { FutOutsideTemp() }
                Box(Modifier.layoutId(BarRank.OBD)) { FutLinkStatus(m.obdConnection, m.onConnectObd, named) }
                Box(Modifier.layoutId(BarRank.MORE)) {
                    MorePicker(m) { open ->
                        FutBarButton(onClick = open, description = stringResource(R.string.skin_futuristic_cd_more)) {
                            Icon(Icons.Filled.MoreVert, contentDescription = null, tint = ink, modifier = Modifier.size(26.dp))
                        }
                    }
                }
            }
        }
    }
}

/** A bare bar button: the icon in a 60 dp target with a cut-corner ripple, its word in caps beside it where there is room. */
@Composable
private fun FutBarButton(onClick: () -> Unit, description: String, label: String? = null, icon: @Composable () -> Unit) {
    val tap = rememberTapFeedback()
    Row(
        modifier = Modifier
            .height(BAR_BUTTON)
            .widthIn(min = BAR_BUTTON)
            .clip(BarShape)
            .clickable(role = Role.Button, onClickLabel = description) { tap(); onClick() }
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = if (label != null) 14.dp else 0.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        if (label != null) {
            Spacer(Modifier.width(8.dp))
            FutText(label.uppercase(Locale.getDefault()), futCaps(14f, DashColors.TextPrimary, FontWeight.SemiBold))
        }
    }
}

/** The bar's clock: wide glowing digits, AM / PM small beside them, the date in spaced caps where there is room. */
@Composable
private fun FutBarClock(clock: String, showDate: Boolean) {
    val (digits, amPm) = splitClock(clock)
    val locale = Locale.getDefault()
    Row(verticalAlignment = Alignment.CenterVertically) {
        FutText(digits, futNum(28f), overflow = TextOverflow.Clip)
        if (amPm != null) {
            Spacer(Modifier.width(4.dp))
            FutText(amPm.uppercase(locale), futCaps(12f, DashColors.Accent))
        }
        if (showDate) {
            val now = rememberNow(60_000L)
            val fmt = rememberDateFormat("EEEdMMM", best = true)
            Spacer(Modifier.width(14.dp))
            FutText(fmt.format(now).uppercase(locale), futCaps(13f))
        }
    }
}

/** Outside temperature, quiet, beside the link; nothing until the first weather fetch. */
@Composable
private fun FutOutsideTemp() {
    val weather = rememberWeather() ?: return
    FutText(
        LocalUnits.current.tempTight(weather.tempC),
        futText(16f, DashColors.TextSecondary, FontWeight.Medium),
        Modifier.padding(horizontal = 10.dp)
    )
}

/**
 * The OBD link as a HUD status: a dot that breathes while the link is live
 * (still with effects off), flashes while connecting, is a hollow ring when
 * off and red on error, then "OBD", or on a wide bar the car's name and the
 * link's state in caps. What a tap does is the shared [ObdMark]'s.
 */
@Composable
private fun FutLinkStatus(state: ObdConnectionState, onConnect: () -> Unit, named: Boolean) {
    val color = obdStatusColor(state)
    val breathe = when (state) {
        ObdConnectionState.CONNECTED -> rememberLoop(2_000, reverse = true)
        ObdConnectionState.CONNECTING -> rememberLoop(700, reverse = true, status = true)
        else -> null
    }
    val glow = futGlow
    val profile by CarProfileStore.profile.collectAsState()
    val linkWord = stringResource(
        when (state) {
            ObdConnectionState.CONNECTED -> R.string.skin_futuristic_link_live
            ObdConnectionState.CONNECTING -> R.string.skin_futuristic_link_linking
            ObdConnectionState.ERROR -> R.string.skin_futuristic_link_error
            ObdConnectionState.DISCONNECTED -> R.string.skin_futuristic_link_off
        }
    )
    val locale = Locale.getDefault()
    val name = profile.name.trim().take(18)
    val text = if (named) "${name.ifEmpty { stringResource(R.string.dash_obd_short) }} · $linkWord".uppercase(locale)
    else stringResource(R.string.dash_obd_short)
    val ink = if (state == ObdConnectionState.DISCONNECTED) DashColors.TextSecondary else DashColors.TextPrimary
    ObdMark(state, onConnect, modifier = Modifier.heightIn(min = BAR_BUTTON), shape = BarShape) {
        Row(modifier = Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(
                Modifier
                    .size(14.dp)
                    .drawWithCache {
                        val r = 4.dp.toPx()
                        val halo = Brush.radialGradient(listOf(color.copy(alpha = 0.6f), Color.Transparent), size.center, r * 2.8f)
                        val ring = Stroke(1.5.dp.toPx())
                        onDrawBehind {
                            val a = breathe?.let { 0.35f + 0.65f * it.value } ?: 1f
                            when (state) {
                                ObdConnectionState.DISCONNECTED -> drawCircle(color, r, style = ring)
                                else -> {
                                    if (glow > 0f && state != ObdConnectionState.ERROR) drawCircle(halo, r * 2.8f, alpha = a * glow)
                                    drawCircle(color, r, alpha = a)
                                }
                            }
                        }
                    }
            )
            Spacer(Modifier.width(8.dp))
            FutText(text, futCaps(13f, ink, FontWeight.SemiBold))
            if (state == ObdConnectionState.ERROR) {
                Spacer(Modifier.width(4.dp))
                FutText("!", futCaps(14f, color, FontWeight.Black))
            }
        }
    }
}

// --- Maps window frame ----------------------------------------------------------------

/**
 * A HUD frame over a docked Maps window: the two big corners cut off in the
 * page colour, a hairline border along the cut outline, bright corner
 * brackets at all four corners and a light shade along the top and bottom
 * edges. Nothing moves; the middle stays clear and it never takes a touch.
 */
@Composable
internal fun FuturisticWindowFrame(modifier: Modifier) {
    val cyan = DashColors.Accent
    val mask = DashColors.Background
    val light = DashColors.Light
    Spacer(
        modifier = modifier
            .fillMaxSize()
            .drawWithCache {
                val w = size.width
                val h = size.height
                val cut = min(22.dp.toPx(), min(w, h) * 0.2f)
                val small = min(6.dp.toPx(), min(w, h) * 0.1f)
                val line = 1.dp.toPx()
                val outline = futHudPath(w, h, cut, small, line / 2f)
                val masks = Path().apply {
                    moveTo(0f, 0f); lineTo(cut, 0f); lineTo(0f, cut); close()
                    moveTo(w, h); lineTo(w - cut, h); lineTo(w, h - cut); close()
                    moveTo(w, 0f); lineTo(w - small, 0f); lineTo(w, small); close()
                    moveTo(0f, h); lineTo(small, h); lineTo(0f, h - small); close()
                }
                val leg = min(26.dp.toPx(), min(w, h) * 0.15f)
                val inset = 1.5.dp.toPx()
                val brackets = Path().apply {
                    moveTo(inset, cut + leg); lineTo(inset, cut); lineTo(cut, inset); lineTo(cut + leg, inset)
                    moveTo(w - inset, h - cut - leg); lineTo(w - inset, h - cut); lineTo(w - cut, h - inset); lineTo(w - cut - leg, h - inset)
                    moveTo(w - small - leg, inset); lineTo(w - small, inset); lineTo(w - inset, small); lineTo(w - inset, small + leg)
                    moveTo(small + leg, h - inset); lineTo(small, h - inset); lineTo(inset, h - small); lineTo(inset, h - small - leg)
                }
                val shadeInk = if (light) Color(0xFF07262E) else Color(0xFF03050A)
                val top = Brush.verticalGradient(listOf(shadeInk.copy(alpha = if (light) 0.12f else 0.35f), Color.Transparent), startY = 0f, endY = h * 0.07f)
                val bottom = Brush.verticalGradient(listOf(Color.Transparent, shadeInk.copy(alpha = if (light) 0.16f else 0.45f)), startY = h * 0.9f, endY = h)
                val border = Stroke(line)
                val accent = Stroke(2.5.dp.toPx(), cap = StrokeCap.Square)
                onDrawBehind {
                    drawRect(top, size = Size(w, h * 0.07f))
                    drawRect(bottom, topLeft = Offset(0f, h * 0.9f), size = Size(w, h * 0.1f))
                    drawPath(masks, mask)
                    drawPath(outline, cyan.copy(alpha = 0.45f), style = border)
                    drawPath(brackets, cyan, style = accent)
                }
            }
    )
}

// --- Theme picker stamp ---------------------------------------------------------------

/**
 * The skin as a stamp: the grid floor with its cyan pool, the car outline
 * on its squashed rings, the ticked speed ring with "87" and a cut-corner
 * panel with a chevron, all in [p]'s colours.
 */
internal fun DrawScope.futuristicStamp(p: DashPalette, number: StampNumber) {
    val w = size.width
    val h = size.height
    val ink = p.Accent
    val hy = h * 0.6f
    val pool = Brush.radialGradient(listOf(ink.copy(alpha = if (p.Light) 0.12f else 0.28f), Color.Transparent), Offset(w * 0.55f, h * 0.7f), w * 0.4f)
    drawRect(pool)
    for (i in -6..6) drawLine(ink.copy(alpha = 0.3f), Offset(w * 0.5f + i * w * 0.03f, hy), Offset(w * 0.5f + i * w * 0.14f, h), 1f)
    var y = hy + (h - hy) * 0.15f
    var gap = (h - hy) * 0.12f
    while (y < h) {
        drawLine(ink.copy(alpha = 0.3f), Offset(0f, y), Offset(w, y), 1f)
        y += gap
        gap *= 1.6f
    }
    // The pad and the car over it.
    val padC = Offset(w * 0.6f, h * 0.68f)
    scale(1f, 0.22f, padC) {
        drawCircle(ink.copy(alpha = 0.8f), w * 0.2f, padC, style = Stroke(2f))
        drawCircle(p.Secondary.copy(alpha = 0.8f), w * 0.16f, padC, style = Stroke(3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))))
    }
    val car = futCarProfilePath(w * 0.42f, h * 0.36f, w * 0.36f, w * 0.36f / FUT_CAR_ASPECT)
    withTransform({ scale(-1f, 1f, Offset(w * 0.6f, h * 0.5f)) }) {
        drawPath(car, ink, style = Stroke(1.5f, join = StrokeJoin.Round))
    }
    // The speed ring.
    val c = Offset(w * 0.17f, h * 0.36f)
    val r = h * 0.22f
    for (k in 0 until 24) {
        rotate(k * 15f, c) { drawLine(ink.copy(alpha = 0.5f), Offset(c.x, c.y - r), Offset(c.x, c.y - r * 0.86f), 1f) }
    }
    drawArc(ink, 135f, 120f, false, Offset(c.x - r * 0.72f, c.y - r * 0.72f), Size(r * 1.44f, r * 1.44f), style = Stroke(2.5f))
    number("87", c.x, c.y, 10f, p.TextPrimary)
    // A panel with a chevron.
    val panel = Path().apply {
        val l = w * 0.8f; val t = h * 0.1f; val rr = w * 0.96f; val b = h * 0.42f; val k = h * 0.06f
        moveTo(l + k, t); lineTo(rr, t); lineTo(rr, b - k); lineTo(rr - k, b); lineTo(l, b); lineTo(l, t + k); close()
    }
    drawPath(panel, p.Card.copy(alpha = 0.6f))
    drawPath(panel, ink.copy(alpha = 0.6f), style = Stroke(1f))
    val chev = Path().apply { moveTo(w * 0.845f, h * 0.31f); lineTo(w * 0.88f, h * 0.2f); lineTo(w * 0.915f, h * 0.31f) }
    drawPath(chev, ink, style = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}
