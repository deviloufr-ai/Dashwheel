package com.openauto.dash

import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.math.min

/*
 * Clay skin (2026-10-13 car-free trends): claymorphism, soft 3D. Pastel
 * cards you could squeeze, each puffed up by a soft coloured shadow under
 * its lower right, a catch of light along its upper left edges and a shade
 * along its lower right ones; big round keys that squash when pressed; and
 * a puffy 3D turn arrow (ClaySkinArrow.kt) leaning toward the turn, with a
 * few clay balls floating around it. Light by design (a lilac, peach and
 * butter page); its night version sets muted clay on a deep plum page.
 *
 * Android 10 cannot blur a view, so the shadow is a native shadow layer on
 * the card's own rounded shape (Skia blurs a rounded rect analytically, once)
 * and the inner light is stacked translucent strokes; effects off leaves a
 * flat card on a crisp offset shade. Tiles are in ClaySkinTiles.kt.
 * Mockup: D:/dashwheel-design/v17-no-car (section 3).
 */

internal val ClayPalette = DashPalette(
    Background = Color(0xFF231A38), Bar = Color.Transparent, Card = Color(0xFF3E3266), CardHi = Color(0x29B9A6FF),
    Accent = Color(0xFFB9A6FF), Secondary = Color(0xFFFFA083), Critical = Color(0xFFFF6B70), Good = Color(0xFF6FE3A8),
    Muted = Color(0xFFA79CC8), TextPrimary = Color(0xFFF4EEFF), TextSecondary = Color(0xFFD3C9F0),
    Accent2 = Color(0xFFFFC266), Line = Color(0x33B9A6FF), Glow = 0.25f, Bare = true,
    BackgroundStops = listOf(Color(0xFF30234F), Color(0xFF231A38), Color(0xFF34223B)),
    Font = DashFont.SANS,
    HeroWeight = FontWeight.Black,
    Skin = DashSkin.CLAY
)

internal val ClayLightPalette = DashPalette(
    Background = Color(0xFFECE6FF), Bar = Color.Transparent, Card = Color(0xFFF4EFFF), CardHi = Color(0x147B5CFF),
    Accent = Color(0xFF6A4BE0), Secondary = Color(0xFFE0603F), Critical = Color(0xFFC62828), Good = Color(0xFF0E8A55),
    Muted = Color(0xFF6E6590), TextPrimary = Color(0xFF3A2E5C), TextSecondary = Color(0xFF54477E),
    Accent2 = Color(0xFFC98400), Line = Color(0x227B5CFF), Glow = 0f, Bare = true, Light = true,
    BackgroundStops = listOf(Color(0xFFE3DBFF), Color(0xFFFFE0D4), Color(0xFFFFEEC7)),
    Font = DashFont.SANS,
    HeroWeight = FontWeight.Black,
    Skin = DashSkin.CLAY
)

/** The skin's corners (dialogs, menus, tiles being arranged). */
internal val ClayShapes = skinShapes(44, 40, 30)

/**
 * The Home Clay was drawn around (SkinLayouts.kt). Wide: the next turn as a
 * big lilac card on the left, the speed disc and the weather beside it, the
 * music wide under them. The bar is the row of clay keys, so no dock row.
 */
internal val ClayLayout = SkinLayout(
    TemplatePage(listOf(BuiltinKind.NAVIGATION, BuiltinKind.SPEED_HUD, BuiltinKind.MEDIA, BuiltinKind.WEATHER)),
    wide = listOf(
        DashboardItem.BuiltinWidget(BuiltinKind.NAVIGATION, x = 0, y = 0, w = 6, h = 7),
        DashboardItem.BuiltinWidget(BuiltinKind.SPEED_HUD, x = 6, y = 0, w = 3, h = 4),
        DashboardItem.BuiltinWidget(BuiltinKind.WEATHER, x = 9, y = 0, w = 3, h = 4),
        DashboardItem.BuiltinWidget(BuiltinKind.MEDIA, x = 6, y = 4, w = 6, h = 3)
    )
)

// --- Clay tints -------------------------------------------------------------------

/** The pastels the cards and keys are made of. */
internal enum class ClayHue { LILAC, CORAL, SKY, BUTTER, MINT, PINK, AQUA, PEACH }

/** One pastel: the card's [fill], the [ink] of its big figure, the colour of its soft [shadow]. */
@androidx.compose.runtime.Immutable
internal data class ClayTint(val fill: Color, val ink: Color, val shadow: Color)

private val NightShadow = Color(0xFF0B0716)

private val DayTints = mapOf(
    ClayHue.LILAC to ClayTint(Color(0xFFF4EFFF), Color(0xFF5B3FD1), Color(0xFF7A60BE)),
    ClayHue.CORAL to ClayTint(Color(0xFFFFF3EC), Color(0xFFF0603C), Color(0xFFC0705A)),
    ClayHue.SKY to ClayTint(Color(0xFFE6F6FF), Color(0xFF2F7FD6), Color(0xFF5A86B8)),
    ClayHue.BUTTER to ClayTint(Color(0xFFFFF5D9), Color(0xFFB86E00), Color(0xFFB8935A)),
    ClayHue.MINT to ClayTint(Color(0xFFE2F7E8), Color(0xFF248A45), Color(0xFF5E9E78)),
    ClayHue.PINK to ClayTint(Color(0xFFFFE6EF), Color(0xFFC93D72), Color(0xFFB86A8C)),
    ClayHue.AQUA to ClayTint(Color(0xFFDDF4F6), Color(0xFF16808A), Color(0xFF5A9AA0)),
    ClayHue.PEACH to ClayTint(Color(0xFFFFEADB), Color(0xFFC85A22), Color(0xFFC08060))
)

private val NightTints = mapOf(
    ClayHue.LILAC to ClayTint(Color(0xFF473B74), Color(0xFFCDBDFF), NightShadow),
    ClayHue.CORAL to ClayTint(Color(0xFF5A3B4C), Color(0xFFFFA083), NightShadow),
    ClayHue.SKY to ClayTint(Color(0xFF30456A), Color(0xFF94CEFF), NightShadow),
    ClayHue.BUTTER to ClayTint(Color(0xFF534834), Color(0xFFFFCB6B), NightShadow),
    ClayHue.MINT to ClayTint(Color(0xFF2F5046), Color(0xFF8EE6B0), NightShadow),
    ClayHue.PINK to ClayTint(Color(0xFF573654), Color(0xFFFF9CC4), NightShadow),
    ClayHue.AQUA to ClayTint(Color(0xFF2B4D57), Color(0xFF85E0E6), NightShadow),
    ClayHue.PEACH to ClayTint(Color(0xFF584232), Color(0xFFFFB585), NightShadow)
)

/** [hue] in the day or night version. */
internal fun clayTint(hue: ClayHue, light: Boolean): ClayTint = (if (light) DayTints else NightTints).getValue(hue)

/** [hue] in the version on screen now. */
internal val ClayHue.tint: ClayTint get() = clayTint(this, DashColors.Light)

/** The purple of the Talk pill and the turn arrow. */
internal val clayPurple: Color get() = if (DashColors.Light) Color(0xFF7B5CFF) else Color(0xFF8D72FF)

/** The orange of the play button. */
internal val clayOrange: Color get() = if (DashColors.Light) Color(0xFFFFB547) else Color(0xFFFFB13D)

// --- The clay itself ----------------------------------------------------------------

/** Corners of the clay cards, as the design's 44 px. */
internal val CLAY_CARD = 40.dp

/** A radius larger than any tile: rounds a square into a disc, a bar into a pill. */
internal val CLAY_ROUND = 1000.dp

/**
 * Inner light, as stroke width (dp) and strength pairs: stacked, the thin
 * strong ones at the edge and the wide faint ones further in, so the light
 * fades into the card like the design's inset shadows.
 */
private val INNER_LIGHT = floatArrayOf(3f, 0.9f, 8f, 0.45f, 16f, 0.24f, 28f, 0.12f)

/**
 * A puffy clay card of [tint] behind the content, clipped to its corners
 * ([radius], [CLAY_ROUND] for a disc or a pill): a soft coloured shadow
 * below and right of it, the fill, a catch of light along its top-left
 * edges and a shade along its bottom-right ones. [depth] scales the shadow
 * (keys sit lower than cards). Effects reduced: a tighter shadow; off: a
 * flat card on a crisp offset shade. While arranging, a dashed rim.
 */
@Composable
internal fun Modifier.clay(
    tint: ClayTint,
    radius: Dp = CLAY_CARD,
    editing: Boolean = false,
    depth: Float = 1f,
    inset: Boolean = false
): Modifier {
    val light = DashColors.Light
    val effects = DashColors.Effects
    val shape = remember(radius) { RoundedCornerShape(radius) }
    return this
        .cachedDraw(tint, light, effects, radius, editing, depth, inset) {
            val w = size.width
            val h = size.height
            val r = radius.toPx().coerceAtMost(min(w, h) / 2f)
            val dp = density
            val plain = effects == DashEffects.NONE
            val shadowInk = tint.shadow.copy(alpha = if (light) 0.34f else 0.62f)
            // The soft cast shadow: Skia blurs a rounded rect analytically, so it costs one draw.
            val paint = if (!plain && !inset) android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = tint.fill.toArgb()
                val k = if (effects == DashEffects.FULL) 1f else 0.55f
                setShadowLayer(26f * dp * depth * k, 10f * dp * depth * k, 14f * dp * depth * k, shadowInk.toArgb())
            } else null
            val crisp = if (plain && !inset) Offset(5f * dp * depth, 6f * dp * depth) else null
            val hi = Color.White.copy(alpha = if (light) 0.85f else 0.20f)
            val lo = (if (light) tint.shadow else Color.Black).copy(alpha = if (light) 0.32f else 0.38f)
            // An inset well takes the light the other way round: shaded at its top left.
            val from = if (inset) lo else hi
            val to = if (inset) hi else lo
            val edges = if (!plain) {
                Brush.linearGradient(
                    0f to from, 0.42f to from.copy(alpha = 0f), 0.58f to to.copy(alpha = 0f), 1f to to,
                    start = Offset.Zero, end = clayLightEnd(w, h)
                )
            } else null
            val face = if (!plain && !inset) {
                Brush.linearGradient(
                    0f to Color.White.copy(alpha = if (light) 0.32f else 0.06f), 0.5f to Color.Transparent,
                    start = Offset.Zero, end = Offset(w * 0.7f, h * 0.9f)
                )
            } else null
            val rim = if (editing) Stroke(2f * dp, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f * dp, 8f * dp))) else null
            val rimInk = tint.ink.copy(alpha = 0.7f)
            val wellRim = if (plain && inset) Stroke(1.5f * dp) else null
            onDrawBehind {
                when {
                    paint != null -> drawIntoCanvas { it.nativeCanvas.drawRoundRect(0f, 0f, w, h, r, r, paint) }
                    crisp != null -> {
                        drawRoundRect(shadowInk, crisp, Size(w, h), CornerRadius(r))
                        drawRoundRect(tint.fill, cornerRadius = CornerRadius(r))
                    }
                    else -> drawRoundRect(tint.fill, cornerRadius = CornerRadius(r))
                }
                if (face != null) drawRoundRect(face, cornerRadius = CornerRadius(r))
                if (edges != null) {
                    var i = 0
                    while (i < INNER_LIGHT.size) {
                        val sw = (INNER_LIGHT[i] * dp).coerceAtMost(min(w, h) / 3f)
                        val half = sw / 2f
                        drawRoundRect(
                            edges, Offset(half, half), Size(w - sw, h - sw), CornerRadius((r - half).coerceAtLeast(0f)),
                            alpha = INNER_LIGHT[i + 1], style = Stroke(sw)
                        )
                        i += 2
                    }
                }
                if (wellRim != null) drawRoundRect(rimInk.copy(alpha = 0.35f), Offset(0.75f * dp, 0.75f * dp), Size(w - 1.5f * dp, h - 1.5f * dp), CornerRadius(r), style = wellRim)
                if (rim != null) {
                    val half = rim.width / 2f
                    drawRoundRect(rimInk, Offset(half, half), Size(w - rim.width, h - rim.width), CornerRadius((r - half).coerceAtLeast(0f)), style = rim)
                }
            }
        }
        .clip(shape)
}

/**
 * Where a top-left to bottom-right light gradient over a [w] x [h] box ends
 * so its middle runs through the top-right and bottom-left corners: the
 * whole top and left edges take the light and the bottom and right ones the
 * shade, however wide the card (a plain corner-to-corner gradient would
 * shade the top edge's right end of a wide card).
 */
internal fun clayLightEnd(w: Float, h: Float): Offset {
    val k = 2f * w * h / (w * w + h * h).coerceAtLeast(1f)
    return Offset(h * k, w * k)
}

/** An inset well in a card (the arrival pill, the progress groove): a little darker than [card], shaded at its top left. */
@Composable
internal fun Modifier.clayWell(card: ClayTint, radius: Dp = CLAY_ROUND): Modifier {
    val light = DashColors.Light
    val well = remember(card, light) {
        card.copy(fill = lerp(card.fill, if (light) Color(0xFF7B5CFF) else Color.Black, if (light) 0.05f else 0.18f))
    }
    return clay(well, radius, inset = true)
}

/**
 * Squash on press: the key flattens and widens a little from its foot and
 * springs back, as clay would. Feedback for a touch, so it plays whatever
 * the effects; read in the layer, so only the layer redraws.
 */
@Composable
internal fun Modifier.claySquash(source: MutableInteractionSource): Modifier {
    val pressed by source.collectIsPressedAsState()
    val p by animateFloatAsState(
        if (pressed) 1f else 0f,
        spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMediumLow),
        label = "claySquash"
    )
    return graphicsLayer {
        scaleX = 1f + 0.07f * p
        scaleY = 1f - 0.11f * p
        transformOrigin = TransformOrigin(0.5f, 1f)
    }
}

/**
 * The whole element is a squashy button: the tap feedback, no ripple (the
 * squash is the answer), [label] for talkback. Nothing while [enabled] is false.
 */
@Composable
internal fun Modifier.clayPress(enabled: Boolean, label: String, onClick: () -> Unit): Modifier {
    if (!enabled) return this
    val tap = rememberTapFeedback()
    val source = remember { MutableInteractionSource() }
    return this
        .claySquash(source)
        .clickable(interactionSource = source, indication = null, role = Role.Button, onClickLabel = label) { tap(); onClick() }
}

// --- Type -------------------------------------------------------------------------

/** Big figures centred in their line box, so large numbers sit where they are put. */
private val FigureLineHeight = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)

/**
 * The skin's type at [size] dp whatever the system font scale: the system
 * sans at heavy weights, the nearest the unit has to the design's rounded
 * face. [figure] sets big numerals tight and centred.
 */
@Composable
internal fun clayText(size: Float, color: Color = DashColors.TextPrimary, weight: FontWeight = FontWeight.Bold, figure: Boolean = false): TextStyle =
    TextStyle(
        color = color,
        fontFamily = FontFamily.SansSerif,
        fontWeight = weight,
        fontSize = fixedSp(size),
        fontFeatureSettings = "tnum",
        lineHeight = if (figure) 1.0.em else 1.18.em,
        lineHeightStyle = if (figure) FigureLineHeight else null,
        letterSpacing = if (figure) (-0.03).em else 0.em
    )

/** One run of the skin's text: one line with an ellipsis unless [maxLines] says otherwise. */
@Composable
internal fun ClayLine(
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

/** Largest size (dp, for [clayText]) within [minSize]..[maxSize] at which [sample] fits [maxW] x [maxH] on one line. */
@Composable
internal fun clayFit(sample: String, weight: FontWeight, maxW: Dp, maxH: Dp, minSize: Float, maxSize: Float, figure: Boolean = true): Float {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = clayText(100f, weight = weight, figure = figure)
    return remember(sample, style, maxW, maxH, minSize, maxSize, density) {
        val probe = measurer.measure(sample, style.copy(fontSize = (100f / density.fontScale).sp), softWrap = false, maxLines = 1)
        val byW = with(density) { maxW.toPx() } * 100f / probe.size.width.coerceAtLeast(1)
        val byH = with(density) { maxH.toPx() } * 100f / probe.size.height.coerceAtLeast(1)
        min(byW, byH).coerceIn(minSize, maxSize.coerceAtLeast(minSize))
    }
}

/** Digits replaced by '8' so a readout keeps one size while its value changes. */
internal fun clayTemplate(text: String): String = text.map { if (it.isDigit()) '8' else it }.joinToString("")

// --- Background -------------------------------------------------------------------

/**
 * The page: the design's pastel gradient corner to corner (lilac, peach,
 * butter; plum at night) with two wide soft pools of light, still. Effects
 * off: the gradient alone.
 */
@Composable
internal fun clayBackground(): Modifier {
    val light = DashColors.Light
    val effects = DashColors.Effects
    val stops = DashColors.BackgroundStops
    return remember(light, effects, stops) { clayBackdrop(light, effects, stops) }
}

private fun clayBackdrop(light: Boolean, effects: DashEffects, stops: List<Color>): Modifier = Modifier.drawWithCache {
    val w = size.width
    val h = size.height
    val base = Brush.linearGradient(stops, Offset.Zero, Offset(w, h))
    val plain = effects == DashEffects.NONE
    val k = effects.scale
    val glowC = Offset(w * 0.12f, h * 0.08f)
    val glowR = maxOf(w, h) * 0.5f
    val glow = Brush.radialGradient(
        listOf((if (light) Color.White else Color(0xFF8D72FF)).copy(alpha = (if (light) 0.55f else 0.16f) * k), Color.Transparent),
        glowC, glowR
    )
    val warmC = Offset(w * 0.92f, h * 0.9f)
    val warmR = maxOf(w, h) * 0.55f
    val warm = Brush.radialGradient(
        listOf((if (light) Color(0xFFFFCDB8) else Color(0xFFFF8F7A)).copy(alpha = (if (light) 0.45f else 0.08f) * k), Color.Transparent),
        warmC, warmR
    )
    onDrawBehind {
        drawRect(base)
        if (!plain) {
            drawCircle(glow, glowR, glowC)
            drawCircle(warm, warmR, warmC)
        }
    }
}

/** The page's gradient colour at ([fx], [fy]) of the window, for the window frame's corners. */
private fun pageColorAt(stops: List<Color>, fx: Float, fy: Float, aspect: Float): Color {
    if (stops.isEmpty()) return Color.Transparent
    // Projection on the corner-to-corner axis of a w x h page (aspect = w / h).
    val t = ((fx * aspect * aspect + fy) / (aspect * aspect + 1f)).coerceIn(0f, 1f)
    val seg = t * (stops.size - 1)
    val i = seg.toInt().coerceAtMost(stops.size - 2).coerceAtLeast(0)
    return if (stops.size == 1) stops[0] else lerp(stops[i], stops[i + 1], seg - i)
}

// --- Bar --------------------------------------------------------------------------

/** The bar's height: the big keys and their soft shadows. */
private val CLAY_BAR = 112.dp

/**
 * The bar, the design's dock: big pastel clay keys in the middle (the map in
 * blue, music in pink, the phone in green, all apps in yellow) and the wide
 * purple Talk pill; the layout key, the time and the dashboard's place on
 * the left; the link, the badges and ⋮ on the right. Pressing a key squashes
 * it. A narrow bar keeps the map, the apps and a round Talk.
 */
@Composable
internal fun ClayTopBar(m: TopBarModel) {
    val context = LocalContext.current
    BoxWithConstraints(Modifier.fillMaxWidth().height(CLAY_BAR)) {
        val narrow = maxWidth < NARROW_BAR
        val key = if (narrow) 62.dp else 80.dp
        val small = if (narrow) 56.dp else 64.dp
        val showClock = !m.merged && !ClockInSight.on.value
        Row(
            Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                LayoutPicker(m) { open ->
                    ClayKey(
                        Icons.Rounded.Apps, ClayHue.LILAC, stringResource(R.string.dash_screen_layout, m.layout.title), small,
                        content = { LayoutIcon(m.layout, null, ClayHue.LILAC.tint.ink, Modifier.size(28.dp)) },
                        onClick = open
                    )
                }
                if (showClock && !narrow) {
                    Spacer(Modifier.width(16.dp))
                    ClayLine(m.clock, clayText(34f, DashColors.TextPrimary, FontWeight.Black, figure = true), overflow = TextOverflow.Clip)
                }
                PagePositionChip(Modifier.padding(start = 10.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(if (narrow) 10.dp else 16.dp), verticalAlignment = Alignment.CenterVertically) {
                ClayKey(Icons.Rounded.Map, ClayHue.SKY, stringResource(R.string.clay_map), key) {
                    openNavigationApp(context, NavDirections.state.value)
                }
                if (!narrow) {
                    ClayKey(Icons.Rounded.MusicNote, ClayHue.PINK, stringResource(R.string.clay_music), key) { clayOpenMusic(context, m.onApps) }
                    ClayKey(Icons.Rounded.Phone, ClayHue.MINT, stringResource(R.string.clay_phone), key) {
                        context.launchSafely(Intent(Intent.ACTION_DIAL))
                    }
                }
                ClayKey(Icons.Rounded.Apps, ClayHue.BUTTER, stringResource(R.string.dash_all_apps), key, onClick = m.onApps)
                ClayTalk(key, round = narrow)
            }
            BarEnd(Modifier.weight(1f)) {
                if (narrow && showClock) {
                    Box(Modifier.layoutId(BarRank.CLOCK).padding(end = 10.dp)) {
                        ClayLine(m.clock, clayText(24f, DashColors.TextPrimary, FontWeight.Black, figure = true), overflow = TextOverflow.Clip)
                    }
                }
                if (m.demo) {
                    Box(Modifier.layoutId(BarRank.DEMO).padding(end = 8.dp)) { DemoBadge(onStop = m.onDemo, compact = true) }
                }
                Row(modifier = Modifier.layoutId(BarRank.ALERTS), verticalAlignment = Alignment.CenterVertically) {
                    VehicleAlerts()
                }
                Box(Modifier.layoutId(BarRank.PHONE).padding(end = 8.dp)) { PhonePill() }
                if (m.setupPending) {
                    Box(Modifier.layoutId(BarRank.SETUP).padding(end = 8.dp)) { SetupPill(onClick = { m.onSetup(false) }, compact = true) }
                }
                Box(Modifier.layoutId(BarRank.OBD).padding(end = 12.dp)) { ClayObdKey(m.obdConnection, m.onConnectObd, small) }
                Box(Modifier.layoutId(BarRank.MORE)) {
                    MorePicker(m, geminiInBar = true) { open ->
                        ClayKey(Icons.Rounded.MoreVert, ClayHue.LILAC, stringResource(R.string.dash_more), small, onClick = open)
                    }
                }
            }
        }
    }
}

/** The last music app used, else all apps to pick one. */
private fun clayOpenMusic(context: Context, orApps: () -> Unit) {
    val pkg = CarMediaController.getLastMediaPackage(context)
    val intent = pkg?.let { context.packageManager.getLaunchIntentForPackage(it) }
    if (intent == null || !context.launchSafely(intent)) orApps()
}

/**
 * A clay key of the bar: a rounded square of [hue] ([size] each way) with
 * the icon in the hue's ink, or [content] in its place. It squashes when pressed.
 */
@Composable
private fun ClayKey(
    icon: ImageVector,
    hue: ClayHue,
    description: String,
    size: Dp,
    content: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    val tint = hue.tint
    Box(
        modifier = Modifier
            .size(size)
            .clayPress(true, description, onClick)
            .clay(tint, size * 0.34f, depth = 0.6f)
            .semantics(mergeDescendants = true) { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        if (content != null) content() else Icon(icon, contentDescription = null, tint = tint.ink, modifier = Modifier.size(size * 0.44f))
    }
}

/**
 * The OBD link as a lilac key: a dot in the link's colour (lit when live,
 * hollow when off, pulsing while connecting) over the letters OBD, a "!"
 * on error. What a tap does is the shared [ObdMark]'s.
 */
@Composable
private fun ClayObdKey(state: ObdConnectionState, onConnect: () -> Unit, size: Dp) {
    val color = obdStatusColor(state)
    val pulse = if (state == ObdConnectionState.CONNECTING) rememberLoop(900, reverse = true, status = true) else null
    val tint = ClayHue.LILAC.tint
    ObdMark(state, onConnect, shape = RoundedCornerShape(size * 0.34f)) {
        Box(Modifier.size(size).clay(tint, size * 0.34f, depth = 0.6f), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(
                    Modifier
                        .size(14.dp)
                        .cachedDraw(state, color) {
                            val r = 6.dp.toPx()
                            val ring = Stroke(2.dp.toPx())
                            onDrawBehind {
                                when (state) {
                                    ObdConnectionState.DISCONNECTED -> drawCircle(color, r - 1.dp.toPx(), style = ring)
                                    ObdConnectionState.CONNECTING -> drawCircle(color, r, alpha = 0.3f + 0.7f * (pulse?.value ?: 1f))
                                    else -> drawCircle(color, r)
                                }
                            }
                        }
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ClayLine(stringResource(R.string.dash_obd_short), clayText(14f, tint.ink, FontWeight.Black))
                    if (state == ObdConnectionState.ERROR) ClayLine("!", clayText(14f, color, FontWeight.Black))
                }
            }
        }
    }
}

/**
 * Talk: the wide purple clay pill with the microphone and the word (a round
 * key when [round]). It opens Gemini Live when there is one, else the unit's
 * voice assistant; while a conversation is open it turns red with an end
 * mark and breathes, since it then tells the driver Gemini is listening.
 */
@Composable
private fun ClayTalk(height: Dp, round: Boolean) {
    val context = LocalContext.current
    val gemini = GeminiLive.rememberAvailable(context)
    val active by GeminiLive.active.collectAsState()
    val word = stringResource(if (active) R.string.ai_gemini_live_end else R.string.clay_talk)
    val description = stringResource(if (active) R.string.ai_gemini_live_end else R.string.clay_talk_cd)
    val light = DashColors.Light
    val fill = if (active) DashColors.Critical else clayPurple
    val tint = remember(fill, light) {
        ClayTint(fill, Color.White, if (light) Color(0xFF5B3FD1) else NightShadow)
    }
    val breath = if (active) rememberLoop(1_400, reverse = true, status = true) else null
    Row(
        modifier = Modifier
            .height(height)
            .then(if (round) Modifier.width(height) else Modifier.width(height * 2.5f))
            .graphicsLayer { if (breath != null) alpha = 0.75f + 0.25f * breath.value }
            .clayPress(true, description) { if (gemini || active) GeminiLive.toggle(context) else startVoiceAssistant(context) }
            .clay(tint, CLAY_ROUND, depth = 0.7f)
            .semantics(mergeDescendants = true) { contentDescription = description },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(if (active) Icons.Rounded.Close else Icons.Rounded.Mic, contentDescription = null, tint = Color.White, modifier = Modifier.size(height * 0.42f))
        if (!round) {
            Spacer(Modifier.width(10.dp))
            ClayLine(word, clayText((height.value * 0.36f).coerceIn(18f, 30f), Color.White, FontWeight.Black))
        }
    }
}

// --- Window frame -----------------------------------------------------------------

/** How thick the clay frame around a docked window is. */
private val FRAME = 12.dp

/**
 * The frame over a docked app window: a thick lilac clay rim with its catch
 * of light on the outer top-left and its shade toward the map, the corners
 * outside it painted in the page's colour behind them. The middle stays clear.
 */
@Composable
internal fun ClayWindowFrame(modifier: Modifier) {
    val light = DashColors.Light
    val effects = DashColors.Effects
    val stops = DashColors.BackgroundStops
    val tint = ClayHue.LILAC.tint
    val view = LocalView.current
    val origin = remember { mutableStateOf<Offset?>(null) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { c -> origin.value = c.positionInWindow() }
            .drawWithCache {
                val w = size.width
                val h = size.height
                val r = CLAY_CARD.toPx()
                val t = FRAME.toPx()
                val o = origin.value ?: Offset.Zero
                val vw = view.width.toFloat().coerceAtLeast(1f)
                val vh = view.height.toFloat().coerceAtLeast(1f)
                val aspect = vw / vh
                val mask = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(0f, 0f, w, h))
                    addRoundRect(RoundRect(Rect(0f, 0f, w, h), CornerRadius(r)))
                }
                val maskBrush = Brush.linearGradient(
                    listOf(pageColorAt(stops, o.x / vw, o.y / vh, aspect), pageColorAt(stops, (o.x + w) / vw, (o.y + h) / vh, aspect)),
                    Offset.Zero, Offset(w, h)
                )
                val plain = effects == DashEffects.NONE
                val band = Stroke(t)
                val hi = Brush.linearGradient(
                    0f to Color.White.copy(alpha = if (light) 0.9f else 0.25f), 0.45f to Color.Transparent,
                    start = Offset.Zero, end = clayLightEnd(w, h)
                )
                val lo = Brush.linearGradient(
                    0.55f to Color.Transparent, 1f to (if (light) tint.shadow else Color.Black).copy(alpha = if (light) 0.4f else 0.5f),
                    start = Offset.Zero, end = clayLightEnd(w, h)
                )
                val edge = Stroke(3.dp.toPx())
                val inner = Stroke(4.dp.toPx())
                val mapShade = (if (light) tint.shadow else Color.Black).copy(alpha = if (light) 0.28f else 0.45f)
                onDrawBehind {
                    drawPath(mask, maskBrush)
                    drawRoundRect(tint.fill, Offset(t / 2f, t / 2f), Size(w - t, h - t), CornerRadius(r - t / 2f), style = band)
                    if (!plain) {
                        val e = edge.width / 2f
                        drawRoundRect(hi, Offset(e, e), Size(w - 2f * e, h - 2f * e), CornerRadius(r - e), style = edge)
                        drawRoundRect(lo, Offset(e, e), Size(w - 2f * e, h - 2f * e), CornerRadius(r - e), style = edge)
                        // The rim's shade falling onto the map along its inner edge.
                        val i = t + inner.width / 2f
                        drawRoundRect(mapShade, Offset(i, i), Size(w - 2f * i, h - 2f * i), CornerRadius((r - i).coerceAtLeast(0f)), style = inner, alpha = 0.6f)
                    }
                }
            }
    )
}

/** The pastel behind a widget Clay does not redraw ([SkinFallbackPanel]): a lilac clay card. */
@Composable
internal fun Modifier.clayFallbackPanel(): Modifier = clay(ClayHue.LILAC.tint)

// --- Theme stamp --------------------------------------------------------------------

/**
 * The skin as a stamp in the theme picker (ThemePane): the pastel page, a
 * lilac card with the puffy purple arrow and its distance, a coral disc with
 * the speed, a butter music strip with its orange play button, and the row
 * of clay keys with the purple Talk pill. Everything follows the stamp's size.
 */
internal fun DrawScope.clayStamp(p: DashPalette, number: StampNumber) {
    val w = size.width
    val h = size.height
    val light = p.Light
    drawRect(Brush.linearGradient(p.BackgroundStops, Offset.Zero, Offset(w, h)))
    val u = min(w / 2f, h)
    val lilac = clayTint(ClayHue.LILAC, light)
    val coral = clayTint(ClayHue.CORAL, light)
    val butter = clayTint(ClayHue.BUTTER, light)
    fun card(t: ClayTint, x: Float, y: Float, cw: Float, ch: Float, r: Float) {
        drawRoundRect(t.shadow.copy(alpha = if (light) 0.22f else 0.5f), Offset(x + u * 0.025f, y + u * 0.035f), Size(cw, ch), CornerRadius(r))
        drawRoundRect(t.fill, Offset(x, y), Size(cw, ch), CornerRadius(r))
        drawRoundRect(
            Brush.linearGradient(listOf(Color.White.copy(alpha = if (light) 0.7f else 0.18f), Color.Transparent), Offset(x, y), Offset(x + cw * 0.5f, y + ch * 0.5f)),
            Offset(x + u * 0.01f, y + u * 0.01f), Size(cw - u * 0.02f, ch - u * 0.02f), CornerRadius(r), style = Stroke(u * 0.012f)
        )
    }
    // The navigation card and its arrow.
    card(lilac, w * 0.04f, h * 0.07f, w * 0.43f, h * 0.6f, u * 0.09f)
    drawClayArrow(TurnSide.LEFT, Rect(w * 0.07f, h * 0.15f, w * 0.07f + h * 0.4f, h * 0.55f), purple = if (light) Color(0xFF7B5CFF) else Color(0xFF8D72FF), light = light, effects = true)
    number("300", w * 0.355f, h * 0.3f, (h / density * 0.1f).coerceAtLeast(6f), lilac.ink)
    // The speed disc.
    val dc = Offset(w * 0.6f, h * 0.27f)
    val dr = h * 0.2f
    drawCircle(coral.shadow.copy(alpha = if (light) 0.22f else 0.5f), dr, Offset(dc.x + u * 0.025f, dc.y + u * 0.035f))
    drawCircle(coral.fill, dr, dc)
    drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = if (light) 0.6f else 0.15f), Color.Transparent), Offset(dc.x - dr * 0.4f, dc.y - dr * 0.4f), dr), dr, dc)
    number("48", dc.x, dc.y, (h / density * 0.14f).coerceAtLeast(7f), coral.ink)
    // The weather card and the music strip.
    card(clayTint(ClayHue.SKY, light), w * 0.73f, h * 0.07f, w * 0.23f, h * 0.4f, u * 0.08f)
    card(butter, w * 0.51f, h * 0.53f, w * 0.45f, h * 0.18f, u * 0.08f)
    drawCircle(clayOrangeFor(light), h * 0.065f, Offset(w * 0.9f, h * 0.62f))
    drawRoundRect(
        Brush.linearGradient(listOf(Color(0xFFFF9A8B), Color(0xFFFF6A88), Color(0xFFA77BFF))),
        Offset(w * 0.54f, h * 0.555f), Size(h * 0.13f, h * 0.13f), CornerRadius(h * 0.04f)
    )
    // The keys.
    val keyY = h * 0.78f
    val key = h * 0.15f
    listOf(ClayHue.SKY, ClayHue.PINK, ClayHue.MINT, ClayHue.BUTTER).forEachIndexed { i, hue ->
        card(clayTint(hue, light), w * 0.24f + i * (key + w * 0.025f), keyY, key, key, key * 0.34f)
    }
    val talkX = w * 0.24f + 4 * (key + w * 0.025f)
    drawRoundRect(Color(0xFF5B3FD1).copy(alpha = if (light) 0.3f else 0.6f), Offset(talkX + u * 0.02f, keyY + u * 0.03f), Size(key * 2.4f, key), CornerRadius(key / 2f))
    drawRoundRect(if (light) Color(0xFF7B5CFF) else Color(0xFF8D72FF), Offset(talkX, keyY), Size(key * 2.4f, key), CornerRadius(key / 2f))
}

private fun clayOrangeFor(light: Boolean): Color = if (light) Color(0xFFFFB547) else Color(0xFFFFB13D)
