package com.openauto.dash

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Spatial skin (2026-10-13 car-free trends): spatial design after visionOS.
 * Glass windows float at different depths over a 3D night city, the ones on
 * the left turned toward the middle from their outer edge, the ones on the
 * right the other way, the middle ones flat; small glass ornaments hang under
 * them, the speed floats in the air with no box, and the bar is a glass dock
 * pill at the foot of the screen with round glass keys and a gradient mic.
 * Whole-design skin (see Skins.kt): this file has the look, the background,
 * the bar and the window frame; the widgets are in SpatialSkinTiles.kt and
 * the city in SpatialSkinCity.kt (a backdrop, not the live map).
 *
 * Android 10 cannot blur, so glass is pre-softened: a dark (by day white)
 * veil, a white gradient over it, a thin light rim, a catch of light along
 * its top and a deep shadow of stacked faint shapes. The tilt is a fixed
 * graphicsLayer transform. With effects off the glass turns opaque, the
 * windows stand flat and nothing glows.
 *
 * Mockup: D:/dashwheel-design/v17-no-car (section 5, s5.png).
 */

internal val SpatialPalette = DashPalette(
    Background = Color(0xFF0A0D16), Bar = Color.Transparent, Card = Color(0xFF222838), CardHi = Color(0x2EFFFFFF),
    Accent = Color(0xFF5AD1FF), Secondary = Color(0xFF8F6BFF), Critical = Color(0xFFFF5A5F), Good = Color(0xFF52E3A4),
    Muted = Color(0xFFA2AABF), TextPrimary = Color(0xFFFFFFFF), TextSecondary = Color(0xFFCCD3E3),
    Accent2 = Color(0xFF8F6BFF), Line = Color(0x47FFFFFF), Glow = 0.4f, Bare = true,
    BackgroundStops = listOf(Color(0xFF121828), Color(0xFF0A0D16), Color(0xFF06080E)),
    Font = DashFont.SANS,
    HeroWeight = FontWeight.Light,
    Skin = DashSkin.SPATIAL
)

internal val SpatialLightPalette = DashPalette(
    Background = Color(0xFFE8EDF5), Bar = Color.Transparent, Card = Color(0xFFFFFFFF), CardHi = Color(0x140D1220),
    Accent = Color(0xFF0A8FC4), Secondary = Color(0xFF6A4BE0), Critical = Color(0xFFC62828), Good = Color(0xFF0E8A55),
    Muted = Color(0xFF5A6378), TextPrimary = Color(0xFF0D1220), TextSecondary = Color(0xFF384055),
    Accent2 = Color(0xFF6A4BE0), Line = Color(0x260D1220), Glow = 0.1f, Bare = true, Light = true,
    BackgroundStops = listOf(Color(0xFFF4F7FC), Color(0xFFE8EDF5), Color(0xFFDCE3EE)),
    Font = DashFont.SANS,
    HeroWeight = FontWeight.Light,
    Skin = DashSkin.SPATIAL
)

/** The skin's corners (dialogs, menus, tiles being arranged). */
internal val SpatialShapes = skinShapes(36, 32, 24)

/**
 * The Home Spatial was drawn around (SkinLayouts.kt): the turn's window on
 * the left, the music's on the right, the speed floating between them at the
 * top and the city left open in the middle; the dock's apps stay on the bar.
 */
internal val SpatialLayout = SkinLayout(
    home = TemplatePage(listOf(BuiltinKind.NAVIGATION, BuiltinKind.MEDIA, BuiltinKind.SPEED_HUD, BuiltinKind.WEATHER)),
    wide = listOf(
        DashboardItem.BuiltinWidget(BuiltinKind.NAVIGATION, x = 0, y = 0, w = 4, h = 5),
        DashboardItem.BuiltinWidget(BuiltinKind.SPEED_HUD, x = 4, y = 0, w = 4, h = 2),
        DashboardItem.BuiltinWidget(BuiltinKind.MEDIA, x = 8, y = 0, w = 4, h = 5)
    )
)

// --- Type ---------------------------------------------------------------------------

/** Big figures centred in their line box, so large numbers sit where they are put. */
private val FigureLineHeight = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)

/**
 * The skin's type at [size] dp (whatever the system font scale): the plain
 * sans, [figure] for big numbers (tight, trimmed), [caps] spaced wide as the
 * mockup's "KM/H".
 */
@Composable
internal fun spText(
    size: Float,
    color: Color = DashColors.TextPrimary,
    weight: FontWeight = FontWeight.Normal,
    figure: Boolean = false,
    caps: Boolean = false
): TextStyle = TextStyle(
    color = color,
    fontFamily = FontFamily.SansSerif,
    fontWeight = weight,
    fontSize = fixedSp(size),
    fontFeatureSettings = "tnum",
    lineHeight = if (figure) 1.0.em else 1.25.em,
    lineHeightStyle = if (figure) FigureLineHeight else null,
    letterSpacing = when {
        caps -> 0.3.em
        figure -> (-0.03).em
        else -> 0.em
    }
)

/** One run of the skin's text: a single line with an ellipsis unless [maxLines] says otherwise; [caps] upper-cases it. */
@Composable
internal fun SpLine(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    align: TextAlign? = null,
    caps: Boolean = false,
    overflow: TextOverflow = TextOverflow.Ellipsis
) {
    Text(
        text = if (caps) text.uppercase(Locale.getDefault()) else text,
        modifier = modifier,
        style = if (align != null) style.copy(textAlign = align) else style,
        maxLines = maxLines,
        softWrap = maxLines > 1,
        overflow = overflow
    )
}

/** Largest size (dp, for [spText]) within [minSize]..[maxSize] at which [sample] fits [maxW] x [maxH] on one line. */
@Composable
internal fun spFit(sample: String, weight: FontWeight, maxW: Dp, maxH: Dp, minSize: Float, maxSize: Float, figure: Boolean = true): Float {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = spText(100f, weight = weight, figure = figure)
    return remember(sample, style, maxW, maxH, minSize, maxSize, density) {
        val probe = measurer.measure(sample, style.copy(fontSize = (100f / density.fontScale).sp), softWrap = false, maxLines = 1)
        val byW = with(density) { maxW.toPx() } * 100f / probe.size.width.coerceAtLeast(1)
        val byH = with(density) { maxH.toPx() } * 100f / probe.size.height.coerceAtLeast(1)
        min(byW, byH).coerceIn(minSize, maxSize.coerceAtLeast(minSize))
    }
}

/** The first of [candidates] (longest first) that fits [maxW] on one line at [size], else the last. */
@Composable
internal fun spFirstFitting(candidates: List<String>, size: Float, weight: FontWeight, maxW: Dp): String {
    val measurer = rememberTextMeasurer()
    val style = spText(size, weight = weight)
    val px = with(LocalDensity.current) { maxW.toPx() }
    return remember(candidates, style, px, measurer) {
        candidates.firstOrNull { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width <= px } ?: candidates.lastOrNull().orEmpty()
    }
}

/** Digits replaced by '8' so a readout keeps one size while its value changes. */
internal fun spTemplate(text: String): String = text.map { if (it.isDigit()) '8' else it }.joinToString("")

/** The soft shadow under a floating figure: dark at night, a white halo by day; none with effects off. */
internal fun spFloatShadow(): Shadow? =
    if (DashColors.Light) softTextShadow(Color.White.copy(alpha = 0.95f), 18f)
    else softTextShadow(Color.Black.copy(alpha = 0.65f), 30f, Offset(0f, 4f))

// --- Glass --------------------------------------------------------------------------

/** The big windows' corners; small ones round less (see [SpWindowShape]). */
internal val SP_WINDOW = 36.dp

/**
 * A window's outline: [max] corners, but never more than an eighth of its
 * smaller side nor less than 16 dp, so a small window stays a window and not
 * a pill.
 */
internal class SpWindowShape(private val max: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = spCorner(size, max, density)
        return Outline.Rounded(RoundRect(Rect(Offset.Zero, size), CornerRadius(r)))
    }
}

internal fun spCorner(size: Size, max: Dp, density: Density): Float = with(density) {
    val cap = max.toPx()
    (size.minDimension * 0.125f).coerceIn(min(16.dp.toPx(), cap), cap).coerceAtMost(size.minDimension / 2f)
}

/**
 * A pane of the skin's glass behind the content, clipped to its corners
 * (unless [clip] is false): at night a dark veil with a white gradient over
 * it, brighter at its top left, a thin white rim, a catch of light along the
 * top edge and a deep soft shadow under it; by day frosted white on a soft
 * blue-grey shadow. Effects off: the solid card and its rim. [round] makes
 * it a circle or a capsule; [lift] scales the shadow (0 for keys sitting on
 * another pane). While the tile is arranged its rim is dashed.
 */
@Composable
internal fun Modifier.spatialGlass(max: Dp = SP_WINDOW, editing: Boolean = false, clip: Boolean = true, round: Boolean = false, lift: Float = 1f): Modifier {
    val light = DashColors.Light
    val effects = DashColors.Effects
    val card = DashColors.Card
    val shape: Shape = remember(max, round) { if (round) androidx.compose.foundation.shape.RoundedCornerShape(50) else SpWindowShape(max) }
    val drawn = cachedDraw(light, effects, card, editing, max, round, lift) {
        val w = size.width
        val h = size.height
        val r = if (round) min(w, h) / 2f else spCorner(size, max, this)
        val corner = CornerRadius(r)
        val plain = effects == DashEffects.NONE
        val veil: Color? = when {
            plain -> null
            light -> Color.White.copy(alpha = 0.5f)
            else -> Color(0xFF151A29).copy(alpha = 0.62f)
        }
        val fill: Brush = when {
            plain -> SolidColor(card)
            light -> Brush.linearGradient(listOf(Color.White.copy(alpha = 0.6f), Color.White.copy(alpha = 0.3f)), Offset.Zero, Offset(w * 0.36f, h))
            else -> Brush.linearGradient(listOf(Color.White.copy(alpha = 0.2f), Color.White.copy(alpha = 0.07f)), Offset.Zero, Offset(w * 0.36f, h))
        }
        val sheen = if (!plain) Brush.verticalGradient(
            listOf(Color.White.copy(alpha = if (light) 0.45f else 0.08f), Color.Transparent), 0f, h * 0.4f
        ) else null
        val rimInk = when {
            plain -> if (light) Color(0x400D1220) else Color(0x66FFFFFF)
            light -> Color.White.copy(alpha = 0.95f)
            else -> Color.White.copy(alpha = 0.28f)
        }.let { if (editing) it.copy(alpha = (it.alpha * 2f).coerceAtMost(0.9f)) else it }
        // By day a hairline of ink outside the white rim, so the pane holds its edge on the pale city.
        val outer = if (light && !plain) Color(0x1F0D1220) else null
        val rimW = (if (editing) 1.5f else 1f) * density
        val rimStroke = Stroke(rimW, pathEffect = if (editing) PathEffect.dashPathEffect(floatArrayOf(10f * density, 7f * density)) else null)
        val half = rimW / 2f
        val catchLight = if (!plain) Brush.horizontalGradient(
            listOf(Color.Transparent, Color.White.copy(alpha = if (light) 1f else 0.5f), Color.Transparent), r * 0.4f, w - r * 0.4f
        ) else null
        // The deep soft shadow: faint rounded rects, each wider and lower than the last (the mockup's 0 40 80 black .55).
        val shadowInk = if (light) Color(0xFF1A2440) else Color.Black
        val shadow = when {
            plain || lift <= 0f -> null
            light -> floatArrayOf(2f, 4f, 0.05f, 6f, 10f, 0.04f, 12f, 18f, 0.03f, 20f, 28f, 0.02f)
            else -> floatArrayOf(2f, 6f, 0.14f, 7f, 14f, 0.1f, 14f, 24f, 0.07f, 23f, 34f, 0.045f, 34f, 44f, 0.025f)
        }
        onDrawBehind {
            if (shadow != null) {
                var i = 0
                while (i < shadow.size) {
                    val k = shadow[i] * density * lift
                    val dy = shadow[i + 1] * density * lift
                    drawRoundRect(shadowInk.copy(alpha = shadow[i + 2]), Offset(-k, -k + dy), Size(w + 2f * k, h + 2f * k), CornerRadius(r + k))
                    i += 3
                }
            }
            if (veil != null) drawRoundRect(veil, cornerRadius = corner)
            drawRoundRect(fill, cornerRadius = corner)
            if (sheen != null) drawRoundRect(sheen, cornerRadius = corner)
            if (outer != null) drawRoundRect(outer, Offset(-half, -half), Size(w + rimW, h + rimW), CornerRadius(r + half), style = Stroke(rimW))
            drawRoundRect(rimInk, Offset(half, half), Size(w - rimW, h - rimW), CornerRadius((r - half).coerceAtLeast(0f)), style = rimStroke)
            if (catchLight != null) drawLine(catchLight, Offset(r * 0.4f, half + density * 0.5f), Offset(w - r * 0.4f, half + density * 0.5f), density * 1.2f)
        }
    }
    return if (clip) drawn.clip(shape) else drawn
}

/** Whole pane tappable with the tap feedback, or nothing when [enabled] is false (arranging, nothing to do). */
@Composable
internal fun Modifier.spTap(enabled: Boolean, label: String, onClick: () -> Unit): Modifier {
    if (!enabled) return this
    val tap = rememberTapFeedback()
    return clickable(onClickLabel = label, role = Role.Button) { tap(); onClick() }
}

// --- The tilt -----------------------------------------------------------------------

/** How far the side windows turn toward the middle, in degrees: the mockup's 16, a little less on the unit. */
private const val TILT_FULL = 14f
private const val TILT_REDUCED = 9f

/**
 * Which side of a [cols]-column grid a tile [w] columns wide at column [x]
 * stands on: -1 left of the middle, 1 right of it, 0 across the middle
 * (its centre within a column of it).
 */
internal fun spatialTiltSide(x: Int, w: Int, cols: Int): Int {
    val twiceCentre = 2 * x + w
    return when {
        twiceCentre < cols - 1 -> -1
        twiceCentre > cols + 1 -> 1
        else -> 0
    }
}

/**
 * The window turned toward the middle of the screen: a tile on the left
 * turns about its left edge, its inner edge stepping back, one on the right
 * the other way, one across the middle stays flat. Flat while arranged (so
 * the handles sit where the finger goes) and with effects off. Always in its
 * own layer, so a reading changing inside redraws only the window.
 */
@Composable
internal fun Modifier.spatialTilt(item: DashboardItem, editing: Boolean): Modifier {
    val effects = DashColors.Effects
    val side = spatialTiltSide(item.x, item.w, GRID_COLS)
    val deg = when {
        editing || side == 0 -> 0f
        effects == DashEffects.FULL -> TILT_FULL
        effects == DashEffects.REDUCED -> TILT_REDUCED
        else -> 0f
    }
    if (deg == 0f) return graphicsLayer()
    // A positive turn sends the right edge back: the left windows' inner edge.
    val turn = if (side < 0) deg else -deg
    val origin = if (side < 0) TransformOrigin(0f, 0.5f) else TransformOrigin(1f, 0.5f)
    return graphicsLayer {
        rotationY = turn
        cameraDistance = 18f * density
        transformOrigin = origin
    }
}

// --- Background ---------------------------------------------------------------------

/**
 * The page: the made-up night city with its glowing route (SpatialSkinCity.kt),
 * drawn into a bitmap off the main thread once per size and look; the page
 * colour stands in until it is ready. While parked (and with effects on) a
 * short light runs along the route every few seconds; it holds still while
 * the car moves and on a hidden screen.
 */
@Composable
internal fun spatialBackground(): Modifier {
    val light = DashColors.Light
    val glow = DashColors.Effects.scale
    val moving = LocalCarMoving.current
    val view = LocalView.current
    val boxSize = remember { mutableStateOf(IntSize.Zero) }
    val sz = boxSize.value
    val scene by produceState<SpatialCityScene?>(null, sz, light, glow) {
        if (sz.width < 2 || sz.height < 2) return@produceState
        value = withContext(Dispatchers.Default) {
            SpatialCity.scene(sz.width, sz.height, light, glow).also { it.raster() }
        }
    }
    val pulsing = glow > 0f && !moving
    val loop = if (pulsing) rememberLoop(PULSE_PERIOD_MS) else null
    val current = scene
    val samples = current?.samples ?: 0
    // The head's sample while the light runs (the first part of each period), -1 the rest of the time: no redraw in between.
    val head = remember(loop, samples) {
        derivedStateOf {
            val t = loop?.value ?: return@derivedStateOf -1
            val p = t / PULSE_SHARE
            if (p >= 1f || samples == 0) -1 else (p * (samples + PULSE_TAIL)).toInt()
        }
    }
    val page = spatialCityPage(light)
    return Modifier
        .onSizeChanged { if (boxSize.value != it) boxSize.value = it }
        .onGloballyPositioned { c ->
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            val p = c.positionInWindow()
            val left = loc[0] + p.x.roundToInt()
            val top = loc[1] + p.y.roundToInt()
            val rect = IntRect(left, top, left + c.size.width, top + c.size.height)
            if (SpatialCity.page != rect) SpatialCity.page = rect
        }
        .cachedDraw(current, page, head) {
            val raster = current?.raster()
            val exact = raster != null && raster.width == size.width.roundToInt() && raster.height == size.height.roundToInt()
            onDrawBehind {
                drawRect(page)
                if (raster != null) {
                    if (exact) drawImage(raster)
                    else drawImage(raster, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
                    val hd = head.value
                    if (exact && hd > 0) current?.drawPulse(this, hd)
                }
            }
        }
}

/** One run of the light along the route and the pause after it; the run takes [PULSE_SHARE] of the period. */
private const val PULSE_PERIOD_MS = 9_000
private const val PULSE_SHARE = 0.36f

// --- Bar ----------------------------------------------------------------------------

/** The bar's height: the dock pill and the room under it. */
private val SP_BAR = 100.dp

/** The dock pill's height, its keys and the gap between them. */
private const val DOCK_H = 80f
private const val DOCK_KEY = 60f
private const val DOCK_GAP = 12f
private const val DOCK_PAD = 12f
private const val DOCK_FOOT = 10f
private const val DOCK_KEYS = 5

/** The dock pill's width. */
private val dockWidth: Float get() = DOCK_KEYS * DOCK_KEY + (DOCK_KEYS - 1) * DOCK_GAP + 2 * DOCK_PAD

/**
 * The bar: a floating glass dock pill centred at the foot of the window
 * (even when the bar stops short of a parked window's cover), with round
 * glass keys for Apps, Layout, the OBD link and ⋮, and Talk in a cyan to
 * violet gradient. The time floats in the bottom-left corner beside the
 * dashboard's position (not while a clock tile or the unit's own bar shows
 * it); the setup pill, alerts, the phone and the demo badge stand in the
 * bottom-right corner where they fit.
 */
@Composable
internal fun SpatialTopBar(m: TopBarModel) {
    val view = LocalView.current
    val density = LocalDensity.current
    val barLeft = remember { mutableFloatStateOf(0f) }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(SP_BAR)
            .onGloballyPositioned { barLeft.floatValue = it.positionInWindow().x }
            .graphicsLayer()
    ) {
        val narrow = maxWidth < NARROW_BAR
        val half = dockWidth / 2f
        val windowMid = with(density) { (view.width / 2f - barLeft.floatValue).toDp().value }
        val cx = windowMid.coerceIn(half, (maxWidth.value - half).coerceAtLeast(half))
        val leftW = (cx - half - 12f).coerceAtLeast(0f).dp
        val rightW = (maxWidth.value - cx - half - 12f).coerceAtLeast(0f).dp
        val dockTop = SP_BAR.value - DOCK_H - DOCK_FOOT

        Row(
            modifier = Modifier
                .offset { IntOffset(((cx - half) * density.density).roundToInt(), (dockTop * density.density).roundToInt()) }
                .width(dockWidth.dp)
                .height(DOCK_H.dp)
                .spatialGlass(clip = false, round = true)
                .padding(horizontal = DOCK_PAD.dp),
            horizontalArrangement = Arrangement.spacedBy(DOCK_GAP.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SpDockKey(m.onApps, stringResource(R.string.dash_all_apps)) {
                Icon(Icons.Filled.Apps, contentDescription = null, tint = DashColors.TextPrimary, modifier = Modifier.size(28.dp))
            }
            LayoutPicker(m) { open ->
                SpDockKey(open, stringResource(R.string.dash_screen_layout, m.layout.title)) {
                    LayoutIcon(m.layout, null, DashColors.TextPrimary, Modifier.size(28.dp))
                }
            }
            SpDockObd(m.obdConnection, m.onConnectObd)
            MorePicker(m, geminiInBar = true) { open ->
                SpDockKey(open, stringResource(R.string.dash_more)) {
                    Icon(Icons.Filled.MoreVert, contentDescription = null, tint = DashColors.TextPrimary, modifier = Modifier.size(28.dp))
                }
            }
            SpTalkKey()
        }

        // The corners beside the pill.
        Row(
            modifier = Modifier.align(Alignment.BottomStart).width(leftW).height(DOCK_H.dp).padding(start = 22.dp).offset(y = (-DOCK_FOOT).dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val showClock = !m.merged && !ClockInSight.on.value
            if (showClock) SpBarClock(m.clock, withDate = leftW >= 300.dp, Modifier.weight(1f, fill = false))
            // A narrow corner keeps the time; the dashboard's position goes to the other side.
            if (!narrow || !showClock) PagePositionChip(Modifier.padding(start = if (showClock) 10.dp else 0.dp))
        }
        BarEnd(modifier = Modifier.align(Alignment.BottomEnd).width(rightW).height(DOCK_H.dp).padding(end = 22.dp).offset(y = (-DOCK_FOOT).dp)) {
            if (m.demo) {
                Box(Modifier.layoutId(BarRank.DEMO).padding(end = 8.dp)) { DemoBadge(onStop = m.onDemo, compact = true) }
            }
            Row(modifier = Modifier.layoutId(BarRank.ALERTS), verticalAlignment = Alignment.CenterVertically) {
                VehicleAlerts()
            }
            Box(Modifier.layoutId(BarRank.PHONE).padding(end = 8.dp)) { PhonePill() }
            if (m.setupPending) {
                Box(Modifier.layoutId(BarRank.SETUP).padding(end = 8.dp)) { SetupPill(onClick = { m.onSetup(false) }, compact = narrow) }
            }
            if (narrow && !m.merged && !ClockInSight.on.value) {
                Box(Modifier.layoutId(BarRank.CLOCK)) { PagePositionChip() }
            }
        }
    }
}

/** A round glass key of the dock, [DOCK_KEY] dp, the icon alone. */
@Composable
private fun SpDockKey(onClick: () -> Unit, description: String, icon: @Composable () -> Unit) {
    val tap = rememberTapFeedback()
    Box(
        modifier = Modifier
            .size(DOCK_KEY.dp)
            .spatialGlass(round = true, lift = 0f)
            .clickable(role = Role.Button, onClickLabel = description) { tap(); onClick() }
            .semantics(mergeDescendants = true) { contentDescription = description },
        contentAlignment = Alignment.Center
    ) { icon() }
}

/**
 * The OBD link as a round key of the dock: a dot in the link's colour (lit
 * with a halo when live, hollow when off, pulsing while connecting) over the
 * letters OBD, a "!" after them on error, so it reads without its colour.
 * What a tap does is the shared [ObdMark]'s.
 */
@Composable
private fun SpDockObd(state: ObdConnectionState, onConnect: () -> Unit) {
    val color = obdStatusColor(state)
    val pulse = if (state == ObdConnectionState.CONNECTING) rememberLoop(900, reverse = true, status = true) else null
    val halo = if (DashColors.Light) 0f else DashColors.Effects.scale
    ObdMark(state, onConnect, shape = CircleShape) {
        Column(
            modifier = Modifier.size(DOCK_KEY.dp).spatialGlass(round = true, lift = 0f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(
                Modifier
                    .size(14.dp)
                    .cachedDraw(state, color, halo, pulse) {
                        val r = 5.dp.toPx()
                        val glow = Brush.radialGradient(listOf(color.copy(alpha = 0.6f * halo), Color.Transparent), Offset(size.width / 2f, size.height / 2f), r * 2.4f)
                        val ring = Stroke(2.dp.toPx())
                        onDrawBehind {
                            when (state) {
                                ObdConnectionState.CONNECTED -> {
                                    if (halo > 0f) drawCircle(glow, r * 2.4f)
                                    drawCircle(color, r)
                                }
                                ObdConnectionState.DISCONNECTED -> drawCircle(color, r - 1.dp.toPx(), style = ring)
                                ObdConnectionState.CONNECTING -> drawCircle(color, r, alpha = 0.3f + 0.7f * (pulse?.value ?: 1f))
                                ObdConnectionState.ERROR -> drawCircle(color, r)
                            }
                        }
                    }
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SpLine(
                    stringResource(R.string.dash_obd_short),
                    spText(14f, if (state == ObdConnectionState.DISCONNECTED) DashColors.TextSecondary else DashColors.TextPrimary, FontWeight.SemiBold)
                )
                if (state == ObdConnectionState.ERROR) SpLine("!", spText(14f, color, FontWeight.Bold))
            }
        }
    }
}

/**
 * Talk: the dock's round key in the cyan to violet gradient with the mic,
 * a soft glow around it at night that breathes slowly while parked (effects
 * at full). It opens Gemini Live (Dashwheel's own or Google's) when there is
 * one, else the unit's voice assistant. While a conversation is open it turns
 * red with an end mark and its ring pulses whatever the effects, since it then
 * says Gemini is listening.
 */
@Composable
private fun SpTalkKey() {
    val context = LocalContext.current
    val gemini = remember { GeminiLive.available(context) }
    val active by GeminiLive.active.collectAsState()
    val effects = DashColors.Effects
    val light = DashColors.Light
    val tap = rememberTapFeedback()
    val description = stringResource(if (active) R.string.ai_gemini_live_end else R.string.spatial_talk_cd)
    val from = if (active) DashColors.Critical else DashColors.Accent
    val to = if (active) DashColors.Critical else DashColors.Secondary
    val glowK = if (active) 1f else if (light) 0f else effects.scale
    val breath = when {
        active -> rememberLoop(1_400, status = true)
        effects == DashEffects.FULL && !light -> rememberLoop(3_400, reverse = true)
        else -> null
    }
    Box(
        modifier = Modifier
            .size(DOCK_KEY.dp)
            .cachedDraw(from, to, glowK, breath, active) {
                val r = size.minDimension / 2f
                val c = Offset(size.width / 2f, size.height / 2f)
                val fill = Brush.linearGradient(listOf(from, to), Offset(0f, 0f), Offset(size.width, size.height))
                val glow = Brush.radialGradient(
                    0f to from.copy(alpha = 0.55f * glowK), 0.6f to to.copy(alpha = 0.22f * glowK), 1f to Color.Transparent,
                    center = c, radius = r * 1.5f
                )
                val sheen = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.28f), Color.Transparent), 0f, size.height * 0.55f)
                onDrawBehind {
                    val t = breath?.value
                    if (glowK > 0f) drawCircle(glow, r * 1.5f, c, alpha = if (t != null && !active) 0.65f + 0.35f * t else 1f)
                    if (active && t != null) drawCircle(from.copy(alpha = 0.5f * (1f - t)), r + 3.dp.toPx() + t * 6.dp.toPx(), c, style = Stroke(3.dp.toPx()))
                    drawCircle(fill, r, c)
                    drawCircle(sheen, r, c)
                }
            }
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = description) {
                tap()
                if (gemini || active) GeminiLive.toggle(context) else startVoiceAssistant(context)
            }
            .semantics(mergeDescendants = true) { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Icon(if (active) Icons.Filled.Close else Icons.Filled.Mic, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
    }
}

/** The corner's time, floating light with its soft shadow, the date beside it on a wide corner. */
@Composable
private fun SpBarClock(clock: String, withDate: Boolean, modifier: Modifier = Modifier) {
    val now = rememberNow(60_000L)
    val locale = Locale.getDefault()
    val dateFmt = rememberDateFormat("EEEdMMM", best = true)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        SpLine(clock, spText(34f, DashColors.TextPrimary, FontWeight.Light, figure = true).copy(shadow = spFloatShadow()), overflow = TextOverflow.Clip)
        if (withDate) {
            Spacer(Modifier.width(12.dp))
            SpLine(dateFmt.format(now).replaceFirstChar { it.titlecase(locale) }, spText(16f, DashColors.TextSecondary, FontWeight.Medium).copy(shadow = spFloatShadow()))
        }
    }
}

// --- Window frame -------------------------------------------------------------------

/**
 * The frame over a docked app window: its corners rounded off with the city
 * that lies behind them (the page's own picture, when it is drawn at this
 * size; else its colour), a thin light rim and the catch of light along its
 * top, so the map reads as one more window of the room. The middle stays clear.
 */
@Composable
internal fun SpatialWindowFrame(modifier: Modifier) {
    val view = LocalView.current
    val at = remember { mutableStateOf<IntOffset?>(null) }
    val light = DashColors.Light
    val glow = DashColors.Effects.scale
    val plain = DashColors.Effects == DashEffects.NONE
    Spacer(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { c ->
                val loc = IntArray(2)
                view.getLocationOnScreen(loc)
                val p = c.positionInWindow()
                val now = IntOffset(loc[0] + p.x.roundToInt(), loc[1] + p.y.roundToInt())
                if (at.value != now) at.value = now
            }
            .drawWithCache {
                val w = size.width
                val h = size.height
                val r = 28.dp.toPx()
                val mask = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(0f, 0f, w, h))
                    addRoundRect(RoundRect(Rect(0f, 0f, w, h), CornerRadius(r)))
                }
                val page = SpatialCity.page
                val origin = at.value
                val raster = if (page != null) SpatialCity.existing(page.width, page.height, light, glow)?.rasterIfReady() else null
                val pageInk = spatialCityPage(light)
                val rim = Stroke(1.dp.toPx())
                val half = rim.width / 2f
                val rimInk = if (light) Color.White else Color.White.copy(alpha = if (plain) 0.45f else 0.3f)
                val outer = if (light) Color(0x260D1220) else null
                val catch = if (!plain) Brush.horizontalGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = if (light) 1f else 0.5f), Color.Transparent), r, w - r
                ) else null
                onDrawBehind {
                    clipPath(mask) {
                        drawRect(pageInk)
                        if (raster != null && origin != null && page != null) {
                            drawImage(raster, topLeft = Offset((page.left - origin.x).toFloat(), (page.top - origin.y).toFloat()))
                        }
                    }
                    if (outer != null) drawRoundRect(outer, Offset(-half, -half), Size(w + 2f * half, h + 2f * half), CornerRadius(r + half), style = rim)
                    drawRoundRect(rimInk, Offset(half, half), Size(w - 2f * half, h - 2f * half), CornerRadius(r - half), style = rim)
                    if (catch != null) drawLine(catch, Offset(r, half + 0.5f), Offset(w - r, half + 0.5f), 1.2f.dp.toPx())
                }
            }
    )
}

/** The glass behind a widget Spatial does not redraw ([SkinFallbackPanel]). */
@Composable
internal fun Modifier.spatialFallbackPanel(): Modifier = spatialGlass()

// --- Theme stamp ------------------------------------------------------------------------

/**
 * The skin as a stamp in the theme picker (ThemePane): the city's blocks in
 * perspective with the route glowing through them to its beam, two glass
 * windows turned toward the middle, the speed floating between them and the
 * dock pill at the foot. Everything follows the stamp's size.
 */
internal fun DrawScope.spatialStamp(p: DashPalette, number: StampNumber) {
    val w = size.width
    val h = size.height
    val light = p.Light
    val unit = min(w, h)
    drawRect(Brush.verticalGradient(listOf(spatialCityPage(light), if (light) Color(0xFFDCE3EE) else Color(0xFF0C1020))))
    // The blocks: rows receding to a horizon at the top, nearer rows bigger.
    val top = if (light) Color(0xFFF8FAFD) else Color(0xFF3B4570)
    val front = if (light) Color(0xFFD0D8E6) else Color(0xFF2A3253)
    val side = if (light) Color(0xFFBCC6D8) else Color(0xFF1C2239)
    val page = spatialCityPage(light)
    val vp = Offset(w * 0.5f, h * 0.05f)
    for (row in 0 until 5) {
        val depth = 1f - row / 5f
        val y = h * (0.30f + 0.13f * row + 0.01f * row * row)
        val scale = 0.35f + 0.65f * (row / 4f)
        val fog = (depth * 0.75f).coerceIn(0f, 1f)
        val bw = w * 0.07f * scale
        var col = -1
        while (col < 9) {
            val x = w * (col / 8f) + (row % 2) * w * 0.06f
            // Leave the route's avenue open.
            if (abs01(x - w * 0.47f) > bw * 0.9f) {
                val bh = h * (0.08f + 0.12f * hash01(row * 13 + col)) * scale
                val sx = (x - vp.x) * 0.06f
                drawRect(lerp(side, page, fog), Offset(x + bw, y - bh), Size(bw * 0.25f + sx.coerceAtLeast(0f), bh))
                drawRect(lerp(front, page, fog), Offset(x, y - bh), Size(bw, bh))
                drawRect(lerp(top, page, fog), Offset(x + bw * 0.12f, y - bh - bw * 0.22f), Size(bw, bw * 0.22f))
            }
            col++
        }
    }
    // The route: up the avenue, right, up again to the beam.
    val routeA = if (light) Color(0xFF0A8FC4) else Color(0xFF33BFFF)
    val routeB = if (light) Color(0xFF6A4BE0) else Color(0xFFB380FF)
    val route = Path().apply {
        moveTo(w * 0.42f, h)
        lineTo(w * 0.46f, h * 0.62f)
        lineTo(w * 0.6f, h * 0.6f)
        lineTo(w * 0.62f, h * 0.42f)
    }
    val brush = Brush.verticalGradient(listOf(routeB, routeA), startY = h * 0.42f, endY = h)
    if (!light) drawPath(route, brush, alpha = 0.25f, style = Stroke(unit * 0.07f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    drawPath(route, brush, style = Stroke(unit * 0.025f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    drawLine(
        Brush.verticalGradient(listOf(routeB.copy(alpha = 0f), routeB), startY = h * 0.18f, endY = h * 0.42f),
        Offset(w * 0.62f, h * 0.42f), Offset(w * 0.62f, h * 0.18f), unit * 0.03f, StrokeCap.Round
    )
    // The two windows, their inner edges stepped back.
    val glass = if (light) Color.White.copy(alpha = 0.82f) else Color(0x40FFFFFF)
    val rim = if (light) Color(0x330D1220) else Color(0x66FFFFFF)
    val shadow = if (light) Color(0x1A1A2440) else Color(0x66000000)
    fun window(x0: Float, x1: Float, outerTop: Float, outerBottom: Float, inset: Float, left: Boolean) {
        val path = Path().apply {
            if (left) {
                moveTo(x0, outerTop); lineTo(x1, outerTop + inset); lineTo(x1, outerBottom - inset); lineTo(x0, outerBottom)
            } else {
                moveTo(x0, outerTop + inset); lineTo(x1, outerTop); lineTo(x1, outerBottom); lineTo(x0, outerBottom - inset)
            }
            close()
        }
        drawPath(path, shadow, alpha = 1f, style = Stroke(unit * 0.03f))
        drawPath(path, glass)
        drawPath(path, rim, style = Stroke((unit * 0.008f).coerceAtLeast(1f)))
    }
    window(w * 0.05f, w * 0.32f, h * 0.18f, h * 0.62f, h * 0.03f, left = true)
    window(w * 0.68f, w * 0.95f, h * 0.18f, h * 0.66f, h * 0.03f, left = false)
    // Inside them: the turn's arrow chip and distance bars, the cover's glow.
    drawRoundRect(p.Accent.copy(alpha = 0.45f), Offset(w * 0.08f, h * 0.24f), Size(w * 0.06f, w * 0.06f), CornerRadius(unit * 0.03f))
    drawRoundRect(p.TextPrimary.copy(alpha = 0.85f), Offset(w * 0.08f, h * 0.4f), Size(w * 0.13f, h * 0.06f), CornerRadius(unit * 0.02f))
    drawRoundRect(p.TextSecondary.copy(alpha = 0.6f), Offset(w * 0.08f, h * 0.5f), Size(w * 0.18f, h * 0.03f), CornerRadius(unit * 0.015f))
    drawRoundRect(
        Brush.linearGradient(listOf(Color(0xFFFF5EA0), Color(0xFF5B7BFF)), Offset(w * 0.71f, h * 0.24f), Offset(w * 0.92f, h * 0.44f)),
        Offset(w * 0.71f, h * 0.24f), Size(w * 0.21f, h * 0.2f), CornerRadius(unit * 0.03f)
    )
    drawCircle(if (light) p.TextPrimary else Color.White, unit * 0.035f, Offset(w * 0.815f, h * 0.56f))
    // The speed in the air, the dock at the foot.
    number("87", w * 0.5f, h * 0.18f, (h * 0.16f) / (density * fontScale), p.TextPrimary)
    drawRoundRect(glass, Offset(w * 0.33f, h * 0.82f), Size(w * 0.34f, h * 0.12f), CornerRadius(h * 0.06f))
    drawRoundRect(rim, Offset(w * 0.33f, h * 0.82f), Size(w * 0.34f, h * 0.12f), CornerRadius(h * 0.06f), style = Stroke((unit * 0.008f).coerceAtLeast(1f)))
    for (i in 0 until 4) drawCircle(if (light) Color(0x220D1220) else Color(0x33FFFFFF), h * 0.035f, Offset(w * (0.37f + i * 0.065f), h * 0.88f))
    drawCircle(Brush.linearGradient(listOf(p.Accent, p.Secondary), Offset(w * 0.6f, h * 0.84f), Offset(w * 0.65f, h * 0.92f)), h * 0.04f, Offset(w * 0.63f, h * 0.88f))
}

private fun abs01(v: Float): Float = if (v < 0f) -v else v

private fun hash01(n: Int): Float {
    var x = n * 374_761_393 + 668_265_263
    x = (x xor (x ushr 13)) * 1_274_126_177
    return ((x xor (x ushr 16)) and 0xFFFF) / 65_535f
}
