package com.openauto.dash

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
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
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Glyph skin (2026-10-13 trends): Nothing's dot-matrix interface and Teenage
 * Engineering hardware. A pale aluminium panel with a fine dot grid (by
 * night a black one with grey dots), off-white modules with two screws in
 * their top corners and a mono label row, readings in the skin's own dot
 * font (GlyphSkinDots.kt), real round keys with a physical edge and red kept
 * for one thing at a time. The edges of the screen are light strips: the
 * left and right ones chase toward a turn that is near on that side, the
 * bottom one is the fuel gauge in segments. The tiles are in
 * GlyphSkinTiles.kt, the car drawn as a cloud of dots in GlyphSkinCar.kt.
 * Mockup: D:/dashwheel-design/v16-trends/index.html, section 4.
 */

internal val GlyphPalette = DashPalette(
    Background = Color(0xFF0B0B0B), Bar = Color.Transparent, Card = Color(0xFF161616), CardHi = Color(0x14FFFFFF),
    Accent = Color(0xFFF0282E), Secondary = Color(0xFFEDEDEA), Critical = Color(0xFFFF5A5F), Good = Color(0xFF52E3A4),
    Muted = Color(0xFF8A8A84), TextPrimary = Color(0xFFEDEDEA), TextSecondary = Color(0xFFB5B5AF),
    Accent2 = Color(0xFFF0282E), Line = Color(0x26FFFFFF), Glow = 0.3f, Bare = true,
    BackgroundStops = listOf(Color(0xFF111111), Color(0xFF0B0B0B), Color(0xFF070707)),
    Font = DashFont.SANS,
    HeroWeight = FontWeight.Bold,
    Skin = DashSkin.GLYPH
)

internal val GlyphLightPalette = DashPalette(
    Background = Color(0xFFE9E9E5), Bar = Color.Transparent, Card = Color(0xFFF6F6F2), CardHi = Color(0x10000000),
    Accent = Color(0xFFD71921), Secondary = Color(0xFF111111), Critical = Color(0xFFC62828), Good = Color(0xFF0E8A55),
    Muted = Color(0xFF6B6B64), TextPrimary = Color(0xFF111111), TextSecondary = Color(0xFF4A4A46),
    Accent2 = Color(0xFFD71921), Line = Color(0x26000000), Glow = 0f, Bare = true, Light = true,
    BackgroundStops = listOf(Color(0xFFEFEFEB), Color(0xFFE9E9E5), Color(0xFFE2E2DD)),
    Font = DashFont.SANS,
    HeroWeight = FontWeight.Bold,
    Skin = DashSkin.GLYPH
)

/** The skin's corners (dialogs, menus, tiles being arranged). */
internal val GlyphShapes = skinShapes(22, 22, 12)

/**
 * The Home Glyph was drawn around: the time over the car's readings on the
 * left, the driver's car in dots large in the middle, the next turn over the
 * music on the right. No app row: the bar's keys open the map, the music and
 * the phone.
 *
 * ```
 *   0  1  2  3  4  5  6  7  8  9 10 11
 * 0 CLOCK---  RANGE (the dot car)---- NAV-----
 * 1 |      |  |                    |  |      |
 * 2 |______|  |                    |  |      |
 * 3 TELEMETRY |                    |  |______|
 * 4 |      |  |                    |  MEDIA---
 * 5 |      |  |                    |  |      |
 * 6 |______|  |____________________|  |______|
 * ```
 */
internal val GlyphLayout = SkinLayout(
    home = TemplatePage(listOf(BuiltinKind.NAVIGATION, BuiltinKind.RANGE, BuiltinKind.CLOCK, BuiltinKind.TELEMETRY, BuiltinKind.MEDIA)),
    wide = listOf(
        DashboardItem.BuiltinWidget(BuiltinKind.CLOCK, x = 0, y = 0, w = 3, h = 3),
        DashboardItem.BuiltinWidget(BuiltinKind.TELEMETRY, x = 0, y = 3, w = 3, h = 4),
        DashboardItem.BuiltinWidget(BuiltinKind.RANGE, x = 3, y = 0, w = 6, h = 7),
        DashboardItem.BuiltinWidget(BuiltinKind.NAVIGATION, x = 9, y = 0, w = 3, h = 4),
        DashboardItem.BuiltinWidget(BuiltinKind.MEDIA, x = 9, y = 4, w = 3, h = 3)
    )
)

// --- Shared look ------------------------------------------------------------------

/** Corners of the modules. */
internal val GLYPH_CARD = 22.dp

/** Room a module leaves around itself inside its tile, so the dot grid shows between modules as in the design. */
internal val GLYPH_INSET = 4.dp

/** The bar's height: the round keys, their edge and the fuel strip under them. */
internal val GLYPH_BAR = 100.dp

/** The red of the one active signal. */
internal val glyphRed: Color get() = DashColors.Accent

/** An unlit dot or segment: pale grey on the light panel, dark grey on the black one. */
internal val glyphOff: Color get() = if (DashColors.Light) Color(0xFFD9D9D2) else Color(0xFF2A2A2A)

/** The modules' hairline rim. */
internal val glyphRim: Color get() = if (DashColors.Light) Color(0xFFD0D0C9) else Color(0xFF2B2B2B)

/**
 * The skin's mono face at [size] dp (whatever the system font scale); [caps]
 * spaces the letters out as the hardware's printed labels.
 */
@Composable
internal fun glyphMono(size: Float, color: Color = DashColors.TextSecondary, weight: FontWeight = FontWeight.Normal, caps: Boolean = false): TextStyle =
    TextStyle(
        color = color,
        fontFamily = FontFamily.Monospace,
        fontWeight = weight,
        fontSize = fixedSp(if (caps) size.coerceAtLeast(MIN_CAPS_DP) else size),
        letterSpacing = if (caps) 0.16.em else 0.02.em,
        lineHeight = 1.25.em
    )

/** One run of mono text, in capitals as the hardware prints them; one line with an ellipsis unless [maxLines] says otherwise. */
@Composable
internal fun GlyphLine(text: String, style: TextStyle, modifier: Modifier = Modifier, maxLines: Int = 1, align: TextAlign? = null) {
    Text(
        text = text.uppercase(Locale.getDefault()),
        modifier = modifier,
        style = if (align != null) style.copy(textAlign = align) else style,
        maxLines = maxLines,
        softWrap = maxLines > 1,
        overflow = TextOverflow.Ellipsis
    )
}

/** Width in dp a mono line of [chars] characters takes at [size] dp, spaced as caps when [caps]. */
internal fun glyphMonoWidth(chars: Int, size: Float, caps: Boolean): Float = chars * size * (0.6f + if (caps) 0.16f else 0.02f)

/**
 * A module of the panel behind the content: an off-white plate (a dark one by
 * night) with a hairline rim, a catch of light on its top edge, a soft
 * shadow under it and two screws in its top corners, clipped to its corners.
 * Effects off: the plain plate, its rim and its screws. Dashed while the tile
 * is being arranged, so it reads as movable.
 */
@Composable
internal fun Modifier.glyphModule(editing: Boolean = false, inset: Dp = GLYPH_INSET, radius: Dp = GLYPH_CARD, screws: Boolean = true): Modifier {
    val light = DashColors.Light
    val effects = DashColors.Effects
    val card = DashColors.Card
    val rimInk = glyphRim
    val shape = remember(radius) { RoundedCornerShape(radius) }
    return this
        .padding(inset)
        .cachedDraw(light, effects, card, rimInk, editing, radius, screws) {
            val w = size.width
            val h = size.height
            val r = radius.toPx().coerceAtMost(min(w, h) / 2f)
            val plain = effects == DashEffects.NONE
            val fill: Brush = if (plain) SolidColor(card) else Brush.verticalGradient(
                if (light) listOf(Color(0xFFFAFAF7), card) else listOf(Color(0xFF1B1B1B), card)
            )
            val rim = if (editing) rimInk.copy(alpha = 1f).let { if (light) lerp(it, Color.Black, 0.3f) else lerp(it, Color.White, 0.25f) } else rimInk
            val rimW = (if (editing) 1.5f else 1f) * density
            val rimStroke = Stroke(rimW, pathEffect = if (editing) PathEffect.dashPathEffect(floatArrayOf(10f * density, 7f * density)) else null)
            val half = rimW / 2f
            val shadow = if (plain) null else if (light) floatArrayOf(1f, 2f, 0.04f, 4f, 6f, 0.03f, 8f, 11f, 0.02f)
            else floatArrayOf(1f, 2f, 0.3f, 4f, 6f, 0.2f, 8f, 11f, 0.12f)
            val catch = if (!plain) Color.White.copy(alpha = if (light) 1f else 0.08f) else null
            val screwR = 3.5f * density
            val screwFill = if (light) Color(0xFFCFCFC8) else Color(0xFF2E2E2E)
            val screwShade = if (light) Color(0x40000000) else Color(0x66000000)
            val screwLit = if (light) Color.White.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.12f)
            val left = Offset(12f * density, 12f * density)
            val right = Offset(w - 12f * density, 12f * density)
            onDrawBehind {
                if (shadow != null) {
                    var i = 0
                    while (i < shadow.size) {
                        val k = shadow[i] * density
                        val dy = shadow[i + 1] * density
                        drawRoundRect(Color.Black.copy(alpha = shadow[i + 2]), Offset(-k, -k + dy), Size(w + 2f * k, h + 2f * k), CornerRadius(r + k))
                        i += 3
                    }
                }
                drawRoundRect(fill, cornerRadius = CornerRadius(r))
                drawRoundRect(rim, Offset(half, half), Size(w - rimW, h - rimW), CornerRadius(r - half), style = rimStroke)
                if (catch != null) drawLine(catch, Offset(r, rimW + density * 0.5f), Offset(w - r, rimW + density * 0.5f), density)
                if (screws) for (c in arrayOf(left, right)) {
                    drawCircle(screwFill, screwR, c)
                    drawArc(screwShade, 200f, 140f, false, Offset(c.x - screwR, c.y - screwR), Size(screwR * 2f, screwR * 2f), style = Stroke(density))
                    if (!plain) drawArc(screwLit, 20f, 140f, false, Offset(c.x - screwR, c.y - screwR), Size(screwR * 2f, screwR * 2f), style = Stroke(density * 0.8f))
                }
            }
        }
        .clip(shape)
}

/** Whole module tappable with the tap feedback, or nothing when [enabled] is false. */
@Composable
internal fun Modifier.glyphTap(enabled: Boolean, label: String, onClick: () -> Unit): Modifier {
    if (!enabled) return this
    val tap = rememberTapFeedback()
    return clickable(onClickLabel = label, role = Role.Button) { tap(); onClick() }
}

// --- Background: the panel and its light strips -------------------------------------

/** How far before a turn its side strip starts to chase, in metres. */
internal const val GLYPH_SIGNAL_M = 400

/** One step of the strips' chase, and one full chase, in ms. */
private const val CHASE_STEP_MS = 110L
private const val CHASE_MS = 1_100L

/** Segments in a side strip. */
private const val SIDE_SEGMENTS = 9

/**
 * The side whose strip chases: the next turn's, while a route is followed
 * and the turn is within [GLYPH_SIGNAL_M] metres; null otherwise (ahead, far,
 * or no distance given).
 */
internal fun glyphSignalSide(active: Boolean, metres: Int?, side: TurnSide): TurnSide? =
    if (active && side != TurnSide.AHEAD && metres != null && metres in 0..GLYPH_SIGNAL_M) side else null

/** Whether segment [i] of a strip is lit at chase [phase] (0..1): a wave running along the strip, as the design's. */
internal fun glyphChaseLit(i: Int, phase: Float): Boolean {
    val p = ((phase - i * 0.06f) % 1f + 1f) % 1f
    return p < 0.5f
}

/**
 * The page: the pale aluminium panel (black by night) with its fine dot grid,
 * a soft light in its middle, and the light strips along its left and right
 * edges, unlit until a turn is near on their side, when they chase toward it.
 * The chase is the one thing here that moves, and only for the last few
 * hundred metres before the turn; the rest is drawn once.
 */
@Composable
internal fun glyphBackground(): Modifier {
    val light = DashColors.Light
    val effects = DashColors.Effects
    val page = DashColors.Background
    val nav by NavDirections.state.collectAsState()
    val signal = remember(nav) { glyphSignalSide(nav.active, NowRules.metres(nav.distance), nav.turnSide()) }
    val chase = if (signal != null) rememberWallClock(CHASE_STEP_MS) else null
    return remember(light, effects, page, signal, chase) { glyphBackdrop(light, effects, page, signal, chase) }
}

private fun glyphBackdrop(light: Boolean, effects: DashEffects, page: Color, signal: TurnSide?, chase: State<Long>?): Modifier = Modifier.drawWithCache {
    val w = size.width
    val h = size.height
    val dp = density
    val plain = effects == DashEffects.NONE
    val base: Brush = if (plain) SolidColor(page) else Brush.verticalGradient(
        if (light) listOf(Color(0xFFEFEFEB), Color(0xFFE6E6E1)) else listOf(Color(0xFF121212), Color(0xFF080808))
    )
    val glow = if (plain) null else Brush.radialGradient(
        listOf(if (light) Color.White.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.035f), Color.Transparent),
        Offset(w / 2f, h * 0.42f), maxOf(w, h) * 0.6f
    )
    // The dot grid: one dot on a 16 dp tile, repeated by a shader (one rect to draw, not thousands of dots).
    val step = (16f * dp).roundToInt().coerceAtLeast(4)
    val tile = ImageBitmap(step, step)
    Canvas(tile).drawCircle(Offset(step / 2f, step / 2f), 1.1f * dp, Paint().apply { color = if (light) Color(0xFFB9B9B2) else Color(0xFF2C2C2C) })
    val grid = ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))
    // The side strips, in the pages' height (above the bar).
    val pagesH = (h - GLYPH_BAR.toPx()).coerceAtLeast(h * 0.5f)
    val gap = 6f * dp
    val total = pagesH * 0.64f
    val segH = (total - gap * (SIDE_SEGMENTS - 1)) / SIDE_SEGMENTS
    val top = (pagesH - total) / 2f
    val segW = 5f * dp
    val leftX = 3f * dp
    val rightX = w - 3f * dp - segW
    val corner = CornerRadius(segW / 2f)
    val off = if (light) Color(0xFFCFCFC8) else Color(0xFF242424)
    val offShade = if (light) Color(0x1F000000) else Color(0x66000000)
    val lit = Color.White
    val litRim = if (light) Color(0x59000000) else Color.Transparent
    val haloK = effects.scale
    onDrawBehind {
        drawRect(base)
        if (glow != null) drawRect(glow)
        drawRect(grid)
        val phase = chase?.let { (it.value % CHASE_MS).toFloat() / CHASE_MS }
        for (side in 0..1) {
            val x = if (side == 0) leftX else rightX
            val active = phase != null && ((side == 0 && signal == TurnSide.LEFT) || (side == 1 && signal == TurnSide.RIGHT))
            for (i in 0 until SIDE_SEGMENTS) {
                val y = top + i * (segH + gap)
                val on = active && glyphChaseLit(i, phase!!)
                if (on) {
                    if (haloK > 0f) {
                        for (k in 1..3) {
                            val g = k * 3f * dp
                            drawRoundRect(lit.copy(alpha = (if (light) 0.5f else 0.22f) * haloK / k), Offset(x - g, y - g), Size(segW + 2f * g, segH + 2f * g), CornerRadius(corner.x + g))
                        }
                    }
                    drawRoundRect(lit, Offset(x, y), Size(segW, segH), corner)
                    if (light) drawRoundRect(litRim, Offset(x, y), Size(segW, segH), corner, style = Stroke(dp))
                } else {
                    drawRoundRect(off, Offset(x, y), Size(segW, segH), corner)
                    drawLine(offShade, Offset(x + corner.x, y + dp), Offset(x + segW - corner.x, y + dp), dp)
                }
            }
        }
    }
}

/** The page's still colour at [fy] of the way down it: what the window frame's corners are painted with. */
private fun glyphPageAt(fy: Float, light: Boolean, effects: DashEffects, page: Color): Color =
    if (effects == DashEffects.NONE) page
    else lerp(if (light) Color(0xFFEFEFEB) else Color(0xFF121212), if (light) Color(0xFFE6E6E1) else Color(0xFF080808), fy.coerceIn(0f, 1f))

// --- Bar --------------------------------------------------------------------------

/**
 * The bar, as a Teenage Engineering panel: a row of round keys with a
 * physical edge (Apps, the layout, Map, Music, Phone, Car for the OBD link
 * with its dot, and the red Talk key), the time in mono and the dashboard's
 * position on the right before ⋮, and along the foot the fuel gauge as a
 * strip of light segments. A narrow bar keeps Apps, the layout, Map, Car,
 * Talk and ⋮.
 */
@Composable
internal fun GlyphTopBar(m: TopBarModel) {
    val context = LocalContext.current
    val fuel = rememberFuel()
    BoxWithConstraints(Modifier.fillMaxWidth().height(GLYPH_BAR).graphicsLayer()) {
        val narrow = maxWidth < NARROW_BAR
        val key = if (narrow) 60.dp else 68.dp
        val gap = if (narrow) 8.dp else 14.dp
        val keysH = GLYPH_BAR - 22.dp
        Row(
            Modifier.fillMaxWidth().height(keysH).padding(start = 16.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
                GlyphKey(null, Icons.Filled.Apps, stringResource(R.string.dash_all_apps), key, onClick = m.onApps)
                LayoutPicker(m) { open ->
                    GlyphKey(null, null, stringResource(R.string.dash_screen_layout, m.layout.title), key, onClick = open) {
                        LayoutIcon(m.layout, null, DashColors.TextPrimary, Modifier.size(26.dp))
                    }
                }
                GlyphKey(stringResource(R.string.glyph_key_map), Icons.Filled.Map, stringResource(R.string.glyph_key_map_cd), key, onClick = {
                    openNavigationApp(context, NavDirections.state.value)
                })
                if (!narrow) {
                    GlyphKey(stringResource(R.string.glyph_key_music), Icons.Filled.MusicNote, stringResource(R.string.glyph_key_music_cd), key, onClick = {
                        glyphOpenMusic(context, m.onApps)
                    })
                    GlyphKey(stringResource(R.string.glyph_key_phone), Icons.Filled.Phone, stringResource(R.string.glyph_key_phone_cd), key, onClick = {
                        context.launchSafely(Intent(Intent.ACTION_DIAL))
                    })
                }
                ObdMark(m.obdConnection, m.onConnectObd, shape = CircleShape) {
                    GlyphKey(stringResource(R.string.glyph_key_car), Icons.Filled.DirectionsCar, null, key, dot = obdStatusColor(m.obdConnection), onClick = null)
                }
                GlyphTalkKey(key)
            }
            Spacer(Modifier.width(12.dp))
            BarEnd(modifier = Modifier.weight(1f).fillMaxHeight()) {
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
                Row(Modifier.layoutId(BarRank.CLOCK).padding(end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!m.merged && !ClockInSight.on.value) {
                        GlyphLine(m.clock, glyphMono(24f, DashColors.TextPrimary, FontWeight.Bold))
                    }
                    PagePositionChip()
                }
                Box(Modifier.layoutId(BarRank.MORE)) {
                    MorePicker(m, geminiInBar = true) { open ->
                        GlyphKey(null, Icons.Filled.MoreVert, stringResource(R.string.dash_more), key, onClick = open)
                    }
                }
            }
        }
        GlyphFuelStrip(fuel, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(22.dp).padding(horizontal = 16.dp))
    }
}

/** The last music app used, else the app drawer to pick one. */
private fun glyphOpenMusic(context: Context, orApps: () -> Unit) {
    val pkg = CarMediaController.getLastMediaPackage(context)
    val intent = pkg?.let { context.packageManager.getLaunchIntentForPackage(it) }
    if (intent == null || !context.launchSafely(intent)) orApps()
}

/**
 * A round key of the panel, [size] across: a pale dome (dark by night, red
 * for Talk) on a darker edge that shows under it, with a soft shadow; it sinks
 * onto its edge while pressed. It shows [label] in tracked mono capitals when
 * that fits, else [icon]; [content] replaces both. [dot] puts a small status
 * light above the word. With [onClick] null it only draws (a picker or the
 * OBD mark around it takes the touch).
 */
@Composable
internal fun GlyphKey(
    label: String?,
    icon: ImageVector?,
    description: String?,
    size: Dp,
    red: Boolean = false,
    dot: Color? = null,
    onClick: (() -> Unit)?,
    content: (@Composable () -> Unit)? = null
) {
    val light = DashColors.Light
    val effects = DashColors.Effects
    val tap = rememberTapFeedback()
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val edge = (size.value * 0.075f).dp
    val ink = when {
        red -> Color.White
        light -> Color(0xFF333333)
        else -> Color(0xFFD8D8D3)
    }
    val labelSize = 14f
    val fits = label != null && glyphMonoWidth(label.length, labelSize, caps = true) <= size.value * 0.8f
    Box(
        modifier = Modifier
            .size(size, size + edge)
            .then(
                if (onClick != null) Modifier.clickable(interactionSource = source, indication = null, role = Role.Button, onClickLabel = description) { tap(); onClick() }
                else Modifier
            )
            .then(if (description != null) Modifier.semantics(mergeDescendants = true) { contentDescription = description } else Modifier)
            .cachedDraw(light, effects, red, size, pressed) {
                val d = size.toPx()
                val e = edge.toPx()
                val r = d / 2f
                val c = Offset(this.size.width / 2f, r + if (pressed) e * 0.6f else 0f)
                val base = Offset(this.size.width / 2f, r + e)
                val plain = effects == DashEffects.NONE
                val face = when {
                    red -> Brush.verticalGradient(listOf(Color(0xFFEE3A40), Color(0xFFC8141C)), c.y - r, c.y + r)
                    light -> Brush.verticalGradient(listOf(Color(0xFFFBFBF8), Color(0xFFDEDED8)), c.y - r, c.y + r)
                    else -> Brush.verticalGradient(listOf(Color(0xFF303030), Color(0xFF1A1A1A)), c.y - r, c.y + r)
                }
                val side = when {
                    red -> Color(0xFF8C0C12)
                    light -> Color(0xFFC4C4BC)
                    else -> Color(0xFF050505)
                }
                val ring = when {
                    red -> Color(0xFFA50F16)
                    light -> Color(0xFFC8C8C0)
                    else -> Color(0xFF3A3A3A)
                }
                val shadow = if (plain) null else Brush.radialGradient(
                    listOf((if (red) Color(0xFFD71921).copy(alpha = 0.35f * effects.scale) else Color.Black.copy(alpha = if (light) 0.16f else 0.5f)), Color.Transparent),
                    Offset(base.x, base.y + e), r * 1.25f
                )
                val catch = if (plain) null else Color.White.copy(alpha = if (red) 0.35f else if (light) 0.9f else 0.12f)
                onDrawBehind {
                    if (shadow != null) drawCircle(shadow, r * 1.25f, Offset(base.x, base.y + e))
                    drawCircle(side, r, base)
                    drawCircle(face, r, c)
                    drawCircle(ring, r - density / 2f, c, style = Stroke(density))
                    if (catch != null) drawArc(
                        catch, 205f, 130f, false, Offset(c.x - r + 2f * density, c.y - r + 2f * density),
                        Size(2f * r - 4f * density, 2f * r - 4f * density), style = Stroke(density * 1.2f, cap = StrokeCap.Round)
                    )
                }
            },
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            Modifier.size(size).offset(y = if (pressed) edge * 0.6f else 0.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (dot != null) {
                Spacer(Modifier.size(7.dp).cachedDraw(dot) { onDrawBehind { drawCircle(dot) } })
                Spacer(Modifier.height(5.dp))
            }
            when {
                content != null -> content()
                fits -> Text(
                    label!!.uppercase(Locale.getDefault()),
                    style = glyphMono(labelSize, ink, FontWeight.Medium, caps = true),
                    maxLines = 1, softWrap = false
                )
                icon != null -> Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size((size.value * 0.36f).dp))
            }
        }
    }
}

/**
 * Talk: the red key. It opens Gemini Live (Dashwheel's own or Google's) when
 * there is one, else the unit's voice assistant; while a conversation is open
 * it shows an end mark and its word turns to End.
 */
@Composable
private fun GlyphTalkKey(size: Dp) {
    val context = LocalContext.current
    val gemini = GeminiLive.rememberAvailable(context)
    val active by GeminiLive.active.collectAsState()
    val word = stringResource(if (active) R.string.ai_gemini_live_end else R.string.glyph_key_talk)
    val description = stringResource(if (active) R.string.ai_gemini_live_end else R.string.glyph_key_talk_cd)
    GlyphKey(word, if (active) Icons.Filled.Close else Icons.Filled.Mic, description, size, red = true, onClick = {
        if (gemini || active) GeminiLive.toggle(context) else startVoiceAssistant(context)
    })
}

/** Segments in the fuel strip. */
private const val FUEL_SEGMENTS = 20

/** The fuel strip's lit segments for [percent], at least one while there is any fuel. */
internal fun glyphFuelSegments(percent: Int, count: Int = FUEL_SEGMENTS): Int =
    if (percent <= 0) 0 else (percent / 100f * count).roundToInt().coerceIn(1, count)

/**
 * The fuel gauge as the bottom light strip: [FUEL_SEGMENTS] segments, lit in
 * ink as far as the tank is full; the reserve's segments are lit red, with a
 * red glow, so when the tank is low the strip is only red. Unknown fuel: all
 * unlit.
 */
@Composable
private fun GlyphFuelStrip(fuel: FuelInfo?, modifier: Modifier) {
    val light = DashColors.Light
    val effects = DashColors.Effects
    val ink = DashColors.TextPrimary
    val red = glyphRed
    val off = if (light) Color(0xFFD2D2CC) else Color(0xFF242424)
    val lit = fuel?.let { glyphFuelSegments(it.percent) } ?: 0
    val reserve = glyphFuelSegments(SKIN_LOW_FUEL_PCT)
    val said = fuel?.let { stringResource(R.string.glyph_fuel_cd, it.percent) }
    Spacer(
        modifier
            .then(if (said != null) Modifier.semantics { contentDescription = said } else Modifier)
            .cachedDraw(light, effects, ink, red, off, lit, reserve) {
                val gap = 6.dp.toPx()
                val segH = 6.dp.toPx()
                val segW = min(48.dp.toPx(), (size.width - gap * (FUEL_SEGMENTS - 1)) / FUEL_SEGMENTS)
                val total = segW * FUEL_SEGMENTS + gap * (FUEL_SEGMENTS - 1)
                val x0 = (size.width - total) / 2f
                val y = (size.height - segH) / 2f
                val corner = CornerRadius(segH / 2f)
                val glowK = effects.scale
                onDrawBehind {
                    for (i in 0 until FUEL_SEGMENTS) {
                        val x = x0 + i * (segW + gap)
                        val on = i < lit
                        val color = when {
                            !on -> off
                            i < reserve -> red
                            else -> ink
                        }
                        if (on && i < reserve && glowK > 0f) {
                            for (k in 1..2) {
                                val g = k * 3.dp.toPx()
                                drawRoundRect(red.copy(alpha = 0.22f * glowK / k), Offset(x - g, y - g), Size(segW + 2f * g, segH + 2f * g), CornerRadius(corner.x + g))
                            }
                        }
                        drawRoundRect(color, Offset(x, y), Size(segW, segH), corner)
                    }
                }
            }
    )
}

// --- Window frame -----------------------------------------------------------------

/**
 * The frame over a docked app window: a module's frame, its corners rounded
 * off in the panel's colour behind them, its hairline rim and the two screws
 * in its top corners. The middle stays clear and it takes no touches.
 */
@Composable
internal fun GlyphWindowFrame(modifier: Modifier) {
    val light = DashColors.Light
    val effects = DashColors.Effects
    val page = DashColors.Background
    val rimInk = glyphRim
    val view = LocalView.current
    val origin = remember { mutableStateOf<Offset?>(null) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { c -> origin.value = c.positionInWindow() }
            .drawWithCache {
                val w = size.width
                val h = size.height
                val r = GLYPH_CARD.toPx()
                val o = origin.value ?: Offset.Zero
                val vh = view.height.toFloat().coerceAtLeast(1f)
                val mask = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(0f, 0f, w, h))
                    addRoundRect(RoundRect(Rect(0f, 0f, w, h), CornerRadius(r)))
                }
                val maskBrush = Brush.verticalGradient(listOf(glyphPageAt(o.y / vh, light, effects, page), glyphPageAt((o.y + h) / vh, light, effects, page)))
                val rimW = 3.dp.toPx()
                val half = rimW / 2f
                val screwR = 4.dp.toPx()
                val screwFill = if (light) Color(0xFFCFCFC8) else Color(0xFF2E2E2E)
                val screwRim = if (light) Color(0x59000000) else Color(0x40FFFFFF)
                onDrawBehind {
                    drawPath(mask, maskBrush)
                    drawRoundRect(if (light) Color(0xFFF6F6F2) else Color(0xFF161616), Offset(half, half), Size(w - rimW, h - rimW), CornerRadius(r - half), style = Stroke(rimW))
                    drawRoundRect(rimInk, Offset(rimW, rimW), Size(w - 2f * rimW, h - 2f * rimW), CornerRadius(r - rimW), style = Stroke(density))
                    for (c in arrayOf(Offset(14.dp.toPx(), 14.dp.toPx()), Offset(w - 14.dp.toPx(), 14.dp.toPx()))) {
                        drawCircle(screwFill, screwR, c)
                        drawCircle(screwRim, screwR, c, style = Stroke(density))
                    }
                }
            }
    )
}

/** The module behind a widget Glyph does not redraw ([SkinFallbackPanel]). */
@Composable
internal fun Modifier.glyphFallbackPanel(): Modifier = glyphModule()

// --- Clock help: the sun --------------------------------------------------------------

/**
 * The next sunset, or the next sunrise once the sun has set (the minutes of
 * the local day and true for a sunset), at ([lat], [lon]) on the day of
 * [timeMs]; null where the sun does not rise and set that day.
 */
internal fun glyphSunEvent(timeMs: Long, lat: Double, lon: Double, zone: TimeZone = TimeZone.getDefault()): Pair<Int, Boolean>? {
    val cal = Calendar.getInstance(zone).apply { timeInMillis = timeMs }
    val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
    val midnight = cal.timeInMillis
    var rise: Int? = null
    var set: Int? = null
    // The sun's top on the horizon, refraction counted.
    val horizon = -0.833
    var wasUp = sunAltitudeDeg(midnight, lat, lon) > horizon
    // Two-minute steps: plenty for a time shown to the minute, half the work.
    var m = 2
    while (m < 24 * 60) {
        val up = sunAltitudeDeg(midnight + m * 60_000L, lat, lon) > horizon
        if (up && !wasUp && rise == null) rise = m
        if (!up && wasUp && set == null) set = m
        wasUp = up
        m += 2
    }
    if (rise == null || set == null) return null
    return when {
        nowMin < rise -> rise to false
        nowMin < set -> set to true
        else -> rise to false
    }
}

// --- Theme stamp --------------------------------------------------------------------

/**
 * The skin as a stamp in the theme picker (ThemePane): the dot-grid panel,
 * a module with "21:07" in dots and its screws, a dotted car, a row of round
 * keys ending in the red one, and the fuel strip.
 */
internal fun DrawScope.glyphStamp(p: DashPalette, @Suppress("UNUSED_PARAMETER") number: StampNumber) {
    val w = size.width
    val h = size.height
    val light = p.Light
    val ink = p.TextPrimary
    drawRect(p.Background)
    val gridInk = if (light) Color(0xFFB9B9B2) else Color(0xFF2C2C2C)
    val step = h / 12f
    var y = step / 2f
    while (y < h) {
        var x = step / 2f
        while (x < w) {
            drawCircle(gridInk, step * 0.08f, Offset(x, y))
            x += step
        }
        y += step
    }
    // The module with the time.
    val mod = Rect(w * 0.05f, h * 0.08f, w * 0.48f, h * 0.5f)
    drawRoundRect(p.Card, mod.topLeft, mod.size, CornerRadius(h * 0.06f))
    drawRoundRect(if (light) Color(0xFFD0D0C9) else Color(0xFF2B2B2B), mod.topLeft, mod.size, CornerRadius(h * 0.06f), style = Stroke(1f))
    val screw = if (light) Color(0xFFCFCFC8) else Color(0xFF2E2E2E)
    drawCircle(screw, h * 0.015f, Offset(mod.left + h * 0.04f, mod.top + h * 0.04f))
    drawCircle(screw, h * 0.015f, Offset(mod.right - h * 0.04f, mod.top + h * 0.04f))
    val pitch = mod.width * 0.86f / 25f
    drawGlyphText("21:07", mod.left + mod.width * 0.07f, mod.top + (mod.height - pitch * 7f) / 2f + h * 0.02f, pitch, ink)
    // A car in dots.
    val car = Rect(w * 0.54f, h * 0.16f, w * 0.96f, h * 0.48f)
    val dot = car.width / 34f
    var cy = car.top
    var row = 0
    while (cy < car.bottom) {
        var cx = car.left + if (row % 2 == 1) dot / 2f else 0f
        while (cx < car.right) {
            val fx = (cx - car.left) / car.width
            val fy = (cy - car.top) / car.height
            val roof = 0.42f - 0.32f * (1f - ((fx - 0.42f) / 0.42f).let { it * it }).coerceAtLeast(0f)
            val inside = fy >= roof && fy <= 0.86f && !(fy > 0.66f && (kotlin.math.abs(fx - 0.22f) < 0.09f || kotlin.math.abs(fx - 0.8f) < 0.09f))
            if (inside) drawCircle(if (fx < 0.05f && fy < 0.6f) p.Accent else ink.copy(alpha = if (fy < 0.45f) 0.45f else 0.85f), dot * 0.32f, Offset(cx, cy))
            cx += dot
        }
        cy += dot
        row++
    }
    // The keys, the red one last.
    val kd = h * 0.18f
    for (i in 0 until 4) {
        val c = Offset(w * 0.1f + i * kd * 1.3f, h * 0.72f)
        val red = i == 3
        drawCircle(if (red) Color(0xFF8C0C12) else if (light) Color(0xFFC4C4BC) else Color(0xFF050505), kd / 2f, Offset(c.x, c.y + kd * 0.08f))
        drawCircle(if (red) Color(0xFFD71921) else if (light) Color(0xFFF2F2EE) else Color(0xFF262626), kd / 2f, c)
    }
    // The fuel strip.
    val seg = w * 0.8f / 20f
    for (i in 0 until 20) {
        val color = when {
            i < 3 -> p.Accent
            i < 12 -> ink
            else -> if (light) Color(0xFFD2D2CC) else Color(0xFF242424)
        }
        drawRoundRect(color, Offset(w * 0.1f + i * seg, h * 0.92f), Size(seg * 0.75f, h * 0.025f), CornerRadius(h * 0.0125f))
    }
}
