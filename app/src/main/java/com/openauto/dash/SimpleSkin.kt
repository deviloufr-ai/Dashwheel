package com.openauto.dash

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
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
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * Simple skin: few huge readable things, the car lit as in a showroom, frosted glass, a gold Talk button.
 * Whole-design skin (see Skins.kt): its page background, top bar, the frame
 * over a docked app window and the main widgets.
 *
 * The page is a deep navy room with a warm pool of light that breathes very
 * slowly; every reading sits on a pane of glass (Android 10 cannot blur, so
 * the glass is a translucent fill, a hairline rim and a catch of light along
 * its top edge), the next turn on a gold one. The driver's own car stands in
 * the light on the one tile that has room for it (SimpleSkinCar.kt). By day
 * the room turns warm off-white and the panes solid white on soft shadows.
 * Tiles live in SimpleSkinTiles.kt.
 */

internal val SimplePalette = DashPalette(
    Background = Color(0xFF0A0F17), Bar = Color.Transparent, Card = Color(0xFF1A2230), CardHi = Color(0x1FFFFFFF),
    Accent = Color(0xFFF2C14E), Secondary = Color(0xFF8FB8FF), Critical = Color(0xFFFF5A52), Good = Color(0xFF5BD69B),
    Muted = Color(0xFFA9B4C6), TextPrimary = Color(0xFFF4F6FA), TextSecondary = Color(0xFFC3CBD8),
    Accent2 = Color(0xFFE0A92E), Line = Color(0x24FFFFFF), Glow = 0.35f, Bare = true,
    BackgroundStops = listOf(Color(0xFF1A2638), Color(0xFF0A0F17), Color(0xFF16233A)),
    // An orange amber: the gold accent must never pass for a warning.
    Warning = Color(0xFFFF9B3D),
    HeroWeight = FontWeight.SemiBold,
    Skin = DashSkin.SIMPLE
)

internal val SimpleLightPalette = DashPalette(
    Background = Color(0xFFF3F1EC), Bar = Color.Transparent, Card = Color.White, CardHi = Color(0x14000000),
    Accent = Color(0xFF94650A), Secondary = Color(0xFF2F6BD8), Critical = Color(0xFFC62828), Good = Color(0xFF1E8E5A),
    Muted = Color(0xFF5E6672), TextPrimary = Color(0xFF14171C), TextSecondary = Color(0xFF3E4652),
    Accent2 = Color(0xFF7A5206), Line = Color(0x1A000000), Glow = 0.1f, Light = true, Bare = true,
    BackgroundStops = listOf(Color(0xFFFFFFFF), Color(0xFFF3F1EC), Color(0xFFEFE3C8)),
    Warning = Color(0xFFB56A00),
    HeroWeight = FontWeight.SemiBold,
    Skin = DashSkin.SIMPLE
)

/** The skin's corners (dialogs, menus, tiles being arranged). */
internal val SimpleShapes = skinShapes(32, 28, 20)

// --- Shared look ------------------------------------------------------------------

/** Corners of the tiles' glass panes and of the bar's buttons. */
internal val SIMPLE_CARD = 28.dp
private val BarCorner = 22.dp

/** Ink on gold (the turn arrow's tile, the Talk button). */
internal val SimpleGoldInk = Color(0xFF1A1306)

/** The gold of the Talk button and the arrow tile, top and bottom: brighter at night, deeper by day. */
internal val simpleGoldTop: Color get() = if (DashColors.Light) Color(0xFFE9B949) else Color(0xFFF7D27A)
internal val simpleGoldBottom: Color get() = if (DashColors.Light) Color(0xFFC98E1A) else Color(0xFFE0A92E)

/** A pane of the skin: [CLEAR] glass, or the [GOLD] glass of the next turn. */
internal enum class SimpleTone { CLEAR, GOLD }

/**
 * The skin's glass pane behind the content, clipped to its corners. At night a
 * translucent fill brighter at the top, a hairline rim and a catch of light
 * along the top edge; by day a solid white pane on a soft shadow (stacked
 * faint rounded rects, no blur). Effects off: a solid fill and its rim, no
 * shadow and no sheen. While the tile is being arranged its rim is dashed, so
 * the pane reads as movable.
 */
@Composable
internal fun Modifier.simpleGlass(radius: Dp = SIMPLE_CARD, tone: SimpleTone = SimpleTone.CLEAR, editing: Boolean = false): Modifier {
    val light = DashColors.Light
    val effects = DashColors.Effects
    val gold = DashColors.Accent
    val card = DashColors.Card
    val shape = remember(radius) { RoundedCornerShape(radius) }
    return this
        .cachedDraw(light, effects, gold, card, tone, editing, radius) {
            val w = size.width
            val h = size.height
            val r = radius.toPx().coerceAtMost(min(w, h) / 2f)
            val corner = CornerRadius(r)
            val plain = effects == DashEffects.NONE
            val goldTone = tone == SimpleTone.GOLD
            val fill: Brush = when {
                light && goldTone -> Brush.verticalGradient(listOf(lerp(Color.White, gold, 0.12f), lerp(Color.White, gold, 0.2f)))
                light -> Brush.verticalGradient(listOf(card, card))
                plain && goldTone -> Brush.verticalGradient(listOf(lerp(card, gold, 0.2f), lerp(card, gold, 0.2f)))
                plain -> Brush.verticalGradient(listOf(card, card))
                goldTone -> Brush.verticalGradient(listOf(gold.copy(alpha = 0.19f), gold.copy(alpha = 0.11f)))
                else -> Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.105f), Color.White.copy(alpha = 0.05f)))
            }
            val rim = when {
                goldTone -> gold.copy(alpha = if (plain) 0.7f else if (light) 0.5f else 0.45f)
                light -> Color.Black.copy(alpha = if (plain) 0.16f else 0.07f)
                else -> Color.White.copy(alpha = if (plain) 0.18f else 0.13f)
            }.let { if (editing) it.copy(alpha = (it.alpha * 2.2f).coerceAtMost(0.8f)) else it }
            val rimW = (if (editing) 1.5f else 1f) * density
            val rimStroke = Stroke(
                width = rimW,
                pathEffect = if (editing) PathEffect.dashPathEffect(floatArrayOf(10f * density, 7f * density)) else null
            )
            val half = rimW / 2f
            // Night glass only: a soft sheen on the upper half and the catch of light on the top edge.
            val sheen = if (!light && !plain) {
                Brush.verticalGradient(listOf(Color.White.copy(alpha = if (goldTone) 0.06f else 0.05f), Color.Transparent), 0f, h * 0.5f)
            } else null
            val catchLight = if (!light && !plain) {
                val c = if (goldTone) Color(0xFFFFF1C8) else Color.White
                Brush.horizontalGradient(listOf(Color.Transparent, c.copy(alpha = 0.28f), Color.Transparent), r * 0.6f, w - r * 0.6f)
            } else null
            // Day shadow: four faint rounded rects, each a little wider and lower than the last.
            val shadow = if (light && !plain) floatArrayOf(1.5f, 2f, 0.035f, 4f, 5f, 0.03f, 7f, 9f, 0.022f, 11f, 14f, 0.015f) else null
            onDrawBehind {
                if (shadow != null) {
                    var i = 0
                    while (i < shadow.size) {
                        val k = shadow[i] * density
                        val dy = shadow[i + 1] * density
                        drawRoundRect(
                            Color.Black.copy(alpha = shadow[i + 2]),
                            topLeft = Offset(-k, -k + dy),
                            size = Size(w + 2f * k, h + 2f * k),
                            cornerRadius = CornerRadius(r + k)
                        )
                        i += 3
                    }
                }
                drawRoundRect(fill, cornerRadius = corner)
                if (sheen != null) drawRoundRect(sheen, cornerRadius = corner)
                drawRoundRect(rim, topLeft = Offset(half, half), size = Size(w - rimW, h - rimW), cornerRadius = CornerRadius(r - half), style = rimStroke)
                if (catchLight != null) drawLine(catchLight, Offset(r * 0.6f, half + density * 0.5f), Offset(w - r * 0.6f, half + density * 0.5f), density)
            }
        }
        .clip(shape)
}

/** Big figures centred in their line box, so huge numbers sit where they are put. */
private val FigureLineHeight = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

/**
 * The skin's type: the system sans at [size] dp (whatever the system font
 * scale, so text sized from a tile never outgrows it), tabular figures.
 * [weight] Light for the thin clock and temperatures, SemiBold for readings.
 */
@Composable
internal fun simpleText(
    size: Float,
    color: Color = DashColors.TextPrimary,
    weight: FontWeight = FontWeight.Normal,
    figure: Boolean = false
): TextStyle = TextStyle(
    color = color,
    fontFamily = FontFamily.SansSerif,
    fontWeight = weight,
    fontSize = fixedSp(size),
    fontFeatureSettings = "tnum",
    lineHeight = if (figure) 1.05.em else 1.25.em,
    lineHeightStyle = if (figure) FigureLineHeight else null,
    letterSpacing = if (figure) (-0.025).em else 0.em
)

/** One run of the skin's text: a single line with an ellipsis unless [maxLines] says otherwise. */
@Composable
internal fun SimpleLine(
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
 * Largest size (in dp, for [simpleText]) within [minSize]..[maxSize] at which
 * [sample] set in [style] fits [maxW] on one line and [maxH] tall. Measured
 * once at 100 and scaled, since type grows linearly with its size.
 */
@Composable
internal fun simpleFit(sample: String, style: TextStyle, maxW: Dp, maxH: Dp, minSize: Float, maxSize: Float): Float {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(sample, style, maxW, maxH, minSize, maxSize, density) {
        val probe = measurer.measure(sample, style.copy(fontSize = (100f / density.fontScale).sp), softWrap = false, maxLines = 1)
        val w = probe.size.width.coerceAtLeast(1)
        val h = probe.size.height.coerceAtLeast(1)
        val byW = with(density) { maxW.toPx() } * 100f / w
        val byH = with(density) { maxH.toPx() } * 100f / h
        min(byW, byH).coerceIn(minSize, maxSize.coerceAtLeast(minSize))
    }
}

/** The first of [candidates] (longest first) that fits [maxW] on one line, else the last. */
@Composable
internal fun simpleFirstFitting(candidates: List<String>, style: TextStyle, maxW: Dp): String {
    val measurer = rememberTextMeasurer()
    val px = with(LocalDensity.current) { maxW.toPx() }
    return remember(candidates, style, px, measurer) {
        candidates.firstOrNull { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width <= px } ?: candidates.last()
    }
}

/** Digits replaced by '8' so a readout keeps one size while its value changes. */
internal fun simpleTemplate(text: String): String = text.map { if (it.isDigit()) '8' else it }.joinToString("")

/**
 * The page's slow breath for the showroom light: 0..1 and back over eight
 * seconds, in sixteen steps each way, so the whole room is redrawn about four
 * times a second and not twenty (the change between steps is far below what
 * the eye sees in a light this soft). Still with effects off.
 */
@Composable
internal fun rememberSimpleBreath(): State<Float> {
    val loop = rememberLoop(BREATH_HALF_MS, reverse = true, rest = 0.5f)
    return remember(loop) {
        derivedStateOf {
            val q = (loop.value * BREATH_STEPS).roundToInt() / BREATH_STEPS
            q * q * (3f - 2f * q)
        }
    }
}

private const val BREATH_HALF_MS = 4_000
private const val BREATH_STEPS = 16f

/** The warm white of the showroom light, at night. */
internal val SimpleWarmLight = Color(0xFFFFECC8)

// --- Background -------------------------------------------------------------------

/** A soft elliptic pool of colour, as fractions of the page, fading out at [reach] of its radii. */
private class Pool(val x: Float, val y: Float, val rx: Float, val ry: Float, val color: Color, val reach: Float = 0.7f)

/** The room's still light: the blue (night) or warm sand (day) field behind the car, the cooler corner top left. */
private val NightPools = listOf(
    Pool(0.72f, 0.62f, 0.594f, 0.583f, Color(0xFF2A3D5C)),
    Pool(0.20f, 0.0f, 0.469f, 0.417f, Color(0xFF1A2638))
)
private val DayPools = listOf(
    Pool(0.72f, 0.62f, 0.594f, 0.583f, Color(0x8CF2E1BC)),
    Pool(0.20f, 0.0f, 0.469f, 0.417f, Color(0x99DCE3EC))
)

/** The room's floor: the page darkens (night) or warms (day) along its last third. */
private val NightFloor = Color(0xFF05080D)
private val DayFloor = Color(0xFFE6E0D4)
private const val FLOOR_FROM = 0.7f

/** The breathing showroom light, high and right of centre as in the design. */
private val NightSpot = Pool(0.656f, 0.30f, 0.36f, 0.48f, SimpleWarmLight.copy(alpha = 0.16f), reach = 0.65f)
private val DaySpot = Pool(0.656f, 0.30f, 0.36f, 0.48f, Color.White.copy(alpha = 0.85f), reach = 0.65f)

/**
 * The page: deep navy with a blue field where the car usually stands, a
 * cooler corner and a darker floor, and a warm pool of light high on the right
 * that breathes over eight seconds (half as bright with reduced effects). By
 * day the same room in warm off-white with a bright pool. Effects off: a
 * plain fill darkening a little toward the floor.
 */
@Composable
internal fun simpleBackground(): Modifier {
    val light = DashColors.Light
    val effects = DashColors.Effects
    val base = DashColors.Background
    val breath = rememberSimpleBreath()
    return remember(light, effects, base, breath) { backdrop(light, effects, base, breath) }
}

private fun DrawScope.drawPool(p: Pool, brush: Brush, alpha: Float = 1f) {
    val c = Offset(size.width * p.x, size.height * p.y)
    val rx = size.width * p.rx
    val ry = size.height * p.ry
    scale(1f, ry / rx, c) { drawCircle(brush, radius = rx, center = c, alpha = alpha) }
}

private fun poolBrush(p: Pool, w: Float, h: Float): Brush =
    Brush.radialGradient(0f to p.color, p.reach to p.color.copy(alpha = 0f), center = Offset(w * p.x, h * p.y), radius = w * p.rx)

private fun backdrop(light: Boolean, effects: DashEffects, base: Color, breath: State<Float>): Modifier = Modifier.drawWithCache {
    val w = size.width
    val h = size.height
    val floor = if (light) DayFloor else NightFloor
    if (effects == DashEffects.NONE) {
        val plain = Brush.verticalGradient(listOf(base, lerp(base, floor, 0.6f)))
        return@drawWithCache onDrawBehind { drawRect(plain) }
    }
    val pools = if (light) DayPools else NightPools
    val brushes = pools.map { poolBrush(it, w, h) }
    val floorBrush = Brush.verticalGradient(listOf(floor.copy(alpha = 0f), floor), startY = h * FLOOR_FROM, endY = h)
    val spot = if (light) DaySpot else NightSpot
    val spotBrush = poolBrush(spot, w, h)
    val glow = effects.scale
    onDrawBehind {
        drawRect(base)
        for (i in pools.indices) drawPool(pools[i], brushes[i])
        drawRect(floorBrush, topLeft = Offset(0f, h * FLOOR_FROM), size = Size(w, h * (1f - FLOOR_FROM)))
        drawPool(spot, spotBrush, alpha = glow * (0.55f + 0.35f * breath.value))
    }
}

/** The page's still colour at ([fx], [fy]) of its size: what the window frame's corners are painted with. */
private fun pageColorAt(fx: Float, fy: Float, light: Boolean, effects: DashEffects, base: Color): Color {
    val floor = if (light) DayFloor else NightFloor
    if (effects == DashEffects.NONE) return lerp(base, lerp(base, floor, 0.6f), fy.coerceIn(0f, 1f))
    var out = base
    for (p in if (light) DayPools else NightPools) {
        val dx = (fx - p.x) / p.rx
        val dy = (fy - p.y) / p.ry
        val t = (sqrt(dx * dx + dy * dy) / p.reach).coerceIn(0f, 1f)
        out = lerp(out, p.color.copy(alpha = 1f), p.color.alpha * (1f - t))
    }
    if (fy > FLOOR_FROM) out = lerp(out, floor, ((fy - FLOOR_FROM) / (1f - FLOOR_FROM)).coerceIn(0f, 1f))
    return out
}

// --- Bar --------------------------------------------------------------------------

/** The bar's height: its 64 dp buttons and a little air. */
private val SIMPLE_BAR = 76.dp

/**
 * The bar (at the foot of the screen): a few big glass buttons, as in the
 * design's row of actions. Apps and Layout on the left with their words where
 * the bar is wide enough; the thin clock and the dashboard's position in the
 * middle (the clock only while no clock tile is in sight, and not while the
 * head unit's own bar shows the time); on the right the setup pill, vehicle
 * alerts and the phone where they fit, then the OBD link, the gold Talk button
 * with its slow ring and ⋮ nearest the edge.
 */
@Composable
internal fun SimpleTopBar(m: TopBarModel) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(SIMPLE_BAR)
            .graphicsLayer()
    ) {
        val narrow = maxWidth < NARROW_BAR
        val labels = maxWidth >= LABELLED_BAR
        val shared = barIsShared()
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = if (narrow) Modifier else Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SimpleBarButton(
                    onClick = m.onApps,
                    description = stringResource(R.string.dash_all_apps),
                    label = if (labels) stringResource(R.string.dash_apps) else null
                ) {
                    Icon(Icons.Filled.Apps, contentDescription = null, tint = DashColors.TextPrimary, modifier = Modifier.size(28.dp))
                }
                LayoutPicker(m) { open ->
                    SimpleBarButton(
                        onClick = open,
                        description = stringResource(R.string.dash_screen_layout, m.layout.title),
                        label = if (labels) stringResource(R.string.dash_layout_button) else null
                    ) {
                        LayoutIcon(m.layout, null, DashColors.TextSecondary, Modifier.size(28.dp))
                    }
                }
            }
            Row(modifier = Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!m.merged && !ClockInSight.on.value) {
                    SimpleLine(m.clock, simpleText(32f, DashColors.TextPrimary, FontWeight.Light, figure = true), overflow = TextOverflow.Clip)
                }
                PagePositionChip(Modifier.padding(start = 10.dp))
            }
            BarEnd(modifier = Modifier.weight(1f)) {
                if (m.demo) {
                    Box(Modifier.layoutId(BarRank.DEMO).padding(end = 8.dp)) { DemoBadge(onStop = m.onDemo, compact = true) }
                }
                if (m.setupPending) {
                    Box(Modifier.layoutId(BarRank.SETUP).padding(end = 8.dp)) {
                        SetupPill(onClick = { m.onSetup(false) }, compact = narrow || shared)
                    }
                }
                Row(modifier = Modifier.layoutId(BarRank.ALERTS), verticalAlignment = Alignment.CenterVertically) {
                    VehicleAlerts(m.obdConnection, m.obd)
                }
                Box(Modifier.layoutId(BarRank.PHONE).padding(end = 8.dp)) { PhonePill() }
                Box(Modifier.layoutId(BarRank.OBD).padding(end = 10.dp)) { SimpleObdPill(m.obdConnection, m.onConnectObd) }
                // The voice is the skin's main button: it keeps its place like the link and the menu.
                Box(Modifier.layoutId(BarRank.OBD).padding(end = 10.dp)) { SimpleTalkButton(compact = narrow) }
                Box(Modifier.layoutId(BarRank.MORE)) {
                    MorePicker(m, geminiInBar = true) { open ->
                        SimpleBarButton(onClick = open, description = stringResource(R.string.dash_more)) {
                            Icon(Icons.Filled.MoreVert, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(28.dp))
                        }
                    }
                }
            }
        }
    }
}

/** A glass button of the bar: [DashSize.Bar] tall, a square with the icon alone, a pill with its word beside it. */
@Composable
private fun SimpleBarButton(onClick: () -> Unit, description: String, label: String? = null, icon: @Composable () -> Unit) {
    val tap = rememberTapFeedback()
    Row(
        modifier = Modifier
            .height(DashSize.Bar)
            .widthIn(min = DashSize.Bar)
            .simpleGlass(BarCorner)
            .clickable(role = Role.Button, onClickLabel = description) { tap(); onClick() }
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = if (label != null) 20.dp else 0.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        if (label != null) {
            Spacer(Modifier.width(12.dp))
            SimpleLine(label, simpleText(19f, DashColors.TextPrimary, FontWeight.SemiBold))
        }
    }
}

/**
 * The OBD link as a glass pill: a dot in the link's colour (lit with a soft
 * halo when live, hollow when off, pulsing while connecting) and the letters
 * OBD, with a "!" on error, so it reads without its colour. What a tap does
 * is the shared [ObdMark]'s.
 */
@Composable
private fun SimpleObdPill(state: ObdConnectionState, onConnect: () -> Unit) {
    val color = obdStatusColor(state)
    val pulse = if (state == ObdConnectionState.CONNECTING) rememberLoop(900, reverse = true, status = true) else null
    val halo = DashColors.Effects.scale
    ObdMark(state, onConnect, shape = RoundedCornerShape(BarCorner)) {
        Row(
            modifier = Modifier
                .height(DashSize.Bar)
                .simpleGlass(BarCorner)
                .padding(start = 18.dp, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(22.dp)
                    .cachedDraw(state, color, halo) {
                        val r = 5.5.dp.toPx()
                        val glow = Brush.radialGradient(listOf(color.copy(alpha = 0.5f * halo), Color.Transparent), Offset(size.width / 2f, size.height / 2f), r * 2f)
                        val ring = Stroke(2.dp.toPx())
                        onDrawBehind {
                            when (state) {
                                ObdConnectionState.CONNECTED -> {
                                    if (halo > 0f) drawCircle(glow, radius = r * 2f)
                                    drawCircle(color, radius = r)
                                }
                                ObdConnectionState.DISCONNECTED -> drawCircle(color, radius = r - 1.dp.toPx(), style = ring)
                                ObdConnectionState.CONNECTING -> drawCircle(color, radius = r, alpha = 0.3f + 0.7f * (pulse?.value ?: 1f))
                                ObdConnectionState.ERROR -> drawCircle(color, radius = r)
                            }
                        }
                    }
            )
            Spacer(Modifier.width(8.dp))
            SimpleLine(
                stringResource(R.string.dash_obd_short),
                simpleText(18f, if (state == ObdConnectionState.DISCONNECTED) DashColors.TextSecondary else DashColors.TextPrimary, FontWeight.SemiBold)
            )
            if (state == ObdConnectionState.ERROR) {
                Spacer(Modifier.width(3.dp))
                SimpleLine("!", simpleText(18f, color, FontWeight.Black))
            }
        }
    }
}

/**
 * Talk: the gold button of the design, a microphone in a ring that swells and
 * fades every two seconds (effects at full; a still ring when reduced, none
 * when off). It opens Gemini Live (Dashwheel's own or Google's) when there is
 * one, else the unit's voice assistant. While a conversation is open it turns
 * red and says End, its ring pulsing whatever the effects, since it then
 * tells the driver Gemini is listening.
 */
@Composable
private fun SimpleTalkButton(compact: Boolean) {
    val context = LocalContext.current
    val gemini = remember { GeminiLive.available(context) }
    val active by GeminiLive.active.collectAsState()
    val effects = DashColors.Effects
    val light = DashColors.Light
    val tap = rememberTapFeedback()
    val label = stringResource(if (active) R.string.ai_gemini_live_end else R.string.skin_simple_talk)
    val description = stringResource(if (active) R.string.ai_gemini_live_end else R.string.skin_simple_talk_cd)
    val top = if (active) DashColors.Critical else simpleGoldTop
    val bottom = if (active) lerp(DashColors.Critical, Color.Black, 0.18f) else simpleGoldBottom
    val ink = if (active) Color.White else SimpleGoldInk
    val ring = when {
        active -> rememberLoop(1_400, status = true)
        effects == DashEffects.FULL -> rememberLoop(2_000)
        else -> null
    }
    val shape = remember { RoundedCornerShape(BarCorner) }
    Row(
        modifier = Modifier
            .height(DashSize.Bar)
            .widthIn(min = DashSize.Bar)
            .cachedDraw(top, bottom, effects, light) {
                val r = BarCorner.toPx()
                val fill = Brush.linearGradient(listOf(top, bottom), Offset.Zero, Offset(size.width, size.height))
                val glow = effects.scale * (if (light) 0.5f else 1f)
                onDrawBehind {
                    // A warm glow under the button (the design's gold shadow), in three soft steps.
                    if (glow > 0f) {
                        for (k in 1..3) {
                            val g = k * 3f * density
                            drawRoundRect(
                                bottom.copy(alpha = 0.09f * glow),
                                topLeft = Offset(-g, -g + 4f * density),
                                size = Size(size.width + 2f * g, size.height + 2f * g),
                                cornerRadius = CornerRadius(r + g)
                            )
                        }
                    }
                    drawRoundRect(fill, cornerRadius = CornerRadius(r))
                }
            }
            .clip(shape)
            .clickable(role = Role.Button, onClickLabel = description) {
                tap()
                if (gemini || active) GeminiLive.toggle(context) else startVoiceAssistant(context)
            }
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = if (compact) 12.dp else 22.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .cachedDraw(ink, ring, effects) {
                    val r0 = size.minDimension / 2f
                    val stroke = Stroke(2.dp.toPx())
                    onDrawBehind {
                        val t = ring?.value
                        when {
                            t != null -> drawCircle(ink, radius = r0 * (0.9f + 0.45f * t), alpha = 0.8f * (1f - t), style = stroke)
                            effects != DashEffects.NONE -> drawCircle(ink, radius = r0 * 0.95f, alpha = 0.35f, style = stroke)
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Mic, contentDescription = null, tint = ink, modifier = Modifier.size(28.dp))
        }
        if (!compact) {
            Spacer(Modifier.width(12.dp))
            SimpleLine(label, simpleText(21f, ink, FontWeight.Bold))
        }
    }
}

/**
 * The unit's own voice assistant: head units differ in which entry point they
 * have, so the first one an app answers is started, and none at all is said
 * rather than the button seeming dead (as the steering wheel's Voice does).
 */
private fun startVoiceAssistant(context: Context) {
    val started = listOf(
        Intent(Intent.ACTION_VOICE_COMMAND),
        Intent(RecognizerIntent.ACTION_VOICE_SEARCH_HANDS_FREE),
        Intent(Intent.ACTION_ASSIST),
        Intent(RecognizerIntent.ACTION_WEB_SEARCH)
    ).any { context.launchSafely(it) }
    if (!started) Toast.makeText(context, R.string.wheel_no_voice_assistant, Toast.LENGTH_SHORT).show()
}

// --- Window frame -----------------------------------------------------------------

/**
 * The frame over a docked app window: its corners rounded off in the colour of
 * the room behind them (read from where the window sits on the page), a glass
 * rim and, at night, the catch of light along its top edge, so the map reads
 * as one more pane of the room. The middle stays clear.
 */
@Composable
internal fun SimpleWindowFrame(modifier: Modifier) {
    val light = DashColors.Light
    val effects = DashColors.Effects
    val base = DashColors.Background
    val view = LocalView.current
    val origin = remember { mutableStateOf<Offset?>(null) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { c -> origin.value = c.positionInWindow() }
            .drawWithCache {
                val w = size.width
                val h = size.height
                val r = SIMPLE_CARD.toPx()
                val o = origin.value ?: Offset.Zero
                val vw = view.width.toFloat().coerceAtLeast(1f)
                val vh = view.height.toFloat().coerceAtLeast(1f)
                fun at(x: Float, y: Float) = pageColorAt((o.x + x) / vw, (o.y + y) / vh, light, effects, base)
                val mask = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(0f, 0f, w, h))
                    addRoundRect(RoundRect(Rect(0f, 0f, w, h), CornerRadius(r)))
                }
                // Each side of the mask takes the room's colour along it; the corners are where it shows.
                val maskBrush = Brush.linearGradient(
                    listOf(at(0f, 0f), at(w, h)), start = Offset.Zero, end = Offset(w, h)
                )
                val rim = if (light) Color.Black.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.16f)
                val rimStroke = Stroke(1.dp.toPx())
                val half = rimStroke.width / 2f
                val catchLight = if (!light && effects != DashEffects.NONE) {
                    Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.3f), Color.Transparent), r, w - r)
                } else null
                onDrawBehind {
                    drawPath(mask, maskBrush)
                    drawRoundRect(rim, topLeft = Offset(half, half), size = Size(w - 2f * half, h - 2f * half), cornerRadius = CornerRadius(r - half), style = rimStroke)
                    if (catchLight != null) drawLine(catchLight, Offset(r, half + 0.5f), Offset(w - r, half + 0.5f), 1.dp.toPx())
                }
            }
    )
}

// --- Theme stamp --------------------------------------------------------------------

/**
 * The skin as a stamp in the theme picker (ThemePane): the room with its
 * pool of light, a car on the floor, the speed on a glass card, the gold turn
 * card and the bar's pills with the gold Talk button.
 */
internal fun DrawScope.simpleStamp(p: DashPalette, number: StampNumber) {
    val w = size.width
    val h = size.height
    val light = p.Light
    val glassFill = if (light) Color.White else Color.White.copy(alpha = 0.09f)
    val glassRim = if (light) Color.Black.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.16f)
    val warm = if (light) Color.White.copy(alpha = 0.9f) else SimpleWarmLight.copy(alpha = 0.22f)
    // The light pool.
    val spot = Offset(w * 0.68f, h * 0.36f)
    scale(1f, 0.8f, spot) {
        drawCircle(Brush.radialGradient(listOf(warm, Color.Transparent), spot, w * 0.32f), radius = w * 0.32f, center = spot)
    }
    // The car on its floor.
    val car = Rect(w * 0.47f, h * 0.38f, w * 0.93f, h * 0.38f + w * 0.46f / 2.38f)
    drawOval(Brush.radialGradient(listOf(Color.Black.copy(alpha = if (light) 0.25f else 0.7f), Color.Transparent), Offset(car.center.x, car.bottom), car.width * 0.5f),
        topLeft = Offset(car.left - car.width * 0.02f, car.bottom - car.height * 0.12f), size = Size(car.width * 1.04f, car.height * 0.24f))
    drawSimpleCar(car, light)
    // The speed card and the turn card.
    val corner = CornerRadius(h * 0.07f)
    drawRoundRect(glassFill, Offset(w * 0.05f, h * 0.40f), Size(w * 0.34f, h * 0.30f), corner)
    drawRoundRect(glassRim, Offset(w * 0.05f, h * 0.40f), Size(w * 0.34f, h * 0.30f), corner, style = Stroke(1f))
    number("87", w * 0.17f, h * 0.55f, 15f, p.TextPrimary)
    drawCircle(Color.White, h * 0.075f, Offset(w * 0.32f, h * 0.55f))
    drawCircle(Color(0xFFE5484D), h * 0.075f, Offset(w * 0.32f, h * 0.55f), style = Stroke(h * 0.022f))
    drawRoundRect(p.Accent.copy(alpha = if (light) 0.16f else 0.18f), Offset(w * 0.56f, h * 0.06f), Size(w * 0.39f, h * 0.2f), corner)
    drawRoundRect(p.Accent.copy(alpha = 0.5f), Offset(w * 0.56f, h * 0.06f), Size(w * 0.39f, h * 0.2f), corner, style = Stroke(1f))
    drawRoundRect(p.Accent, Offset(w * 0.585f, h * 0.09f), Size(h * 0.14f, h * 0.14f), CornerRadius(h * 0.04f))
    drawLine(p.TextPrimary.copy(alpha = 0.8f), Offset(w * 0.69f, h * 0.13f), Offset(w * 0.9f, h * 0.13f), h * 0.035f, StrokeCap.Round)
    drawLine(p.TextSecondary.copy(alpha = 0.6f), Offset(w * 0.69f, h * 0.2f), Offset(w * 0.83f, h * 0.2f), h * 0.022f, StrokeCap.Round)
    // A thin clock top left.
    drawLine(p.TextPrimary.copy(alpha = 0.85f), Offset(w * 0.06f, h * 0.14f), Offset(w * 0.3f, h * 0.14f), h * 0.012f, StrokeCap.Round)
    drawLine(p.TextPrimary.copy(alpha = 0.85f), Offset(w * 0.06f, h * 0.24f), Offset(w * 0.3f, h * 0.24f), h * 0.012f, StrokeCap.Round)
    // The bar: glass pills and the gold Talk.
    val barY = h * 0.82f
    val pillH = h * 0.12f
    val pill = CornerRadius(pillH * 0.4f)
    for (i in 0..2) {
        drawRoundRect(glassFill, Offset(w * (0.05f + i * 0.2f), barY), Size(w * 0.17f, pillH), pill)
        drawRoundRect(glassRim, Offset(w * (0.05f + i * 0.2f), barY), Size(w * 0.17f, pillH), pill, style = Stroke(1f))
    }
    drawRoundRect(
        Brush.linearGradient(listOf(Color(0xFFF7D27A), Color(0xFFE0A92E))),
        Offset(w * 0.69f, barY), Size(w * 0.26f, pillH), pill
    )
    drawCircle(SimpleGoldInk, pillH * 0.26f, Offset(w * 0.75f, barY + pillH / 2f), style = Stroke(1.2f))
}
