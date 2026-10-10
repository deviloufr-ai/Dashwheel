package com.openauto.dash

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layoutId
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
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Modern skin: frosted glass cards over a city seen in 3D (ModernSkinCity.kt),
 * the route glowing blue under them with the car's puck on it. Cards are dark
 * translucent glass with a hairline rim, a catch of light on the top edge and
 * a soft drop shadow (Android 10 cannot blur what lies behind, so the frost
 * is a gradient fill); numbers are heavy and tightly set in the system sans,
 * labels small bold caps. Next turns sit on a blue gradient tile, fuel is a
 * gradient ring, music gets cover art made from the song's name, and when the
 * tank runs low the fuel card turns amber with the cheapest fuel nearby. By
 * day the plan turns light grey, the glass white and the blue deeper.
 * Tiles are drawn in ModernSkinTiles.kt.
 */

internal val ModernPalette = DashPalette(
    Background = Color(0xFF0B0E14), Bar = Color.Transparent, Card = Color(0xFF171C25), CardHi = Color(0x14FFFFFF),
    Accent = Color(0xFF6EA8FE), Secondary = Color(0xFFF5A524), Critical = Color(0xFFE5484D), Good = Color(0xFF46C08A),
    Muted = Color(0xFF8B95A3), TextPrimary = Color(0xFFECEFF3), TextSecondary = Color(0xFFB5BECB), Accent2 = Color(0xFF4E86F2),
    Line = Color(0x1AFFFFFF), Glow = 0.45f, Warning = Color(0xFFF5A524), Bare = true, Skin = DashSkin.MODERN
)

internal val ModernLightPalette = DashPalette(
    Background = Color(0xFFEEF1F5), Bar = Color.Transparent, Card = Color.White, CardHi = Color(0x0F0B0E14),
    Accent = Color(0xFF2F6FE0), Secondary = Color(0xFFC77A06), Critical = Color(0xFFD13438), Good = Color(0xFF1C8C5E),
    Muted = Color(0xFF5E6878), TextPrimary = Color(0xFF0E1218), TextSecondary = Color(0xFF3D4756), Accent2 = Color(0xFF1F55C0),
    Line = Color(0x1A0B0E14), Glow = 0.15f, Light = true, Bare = true, Skin = DashSkin.MODERN
)

/** The skin's corners (dialogs, menus, tiles being arranged). */
internal val ModernShapes = skinShapes(28, 28, 14)

// --- Shared look --------------------------------------------------------------------

/** A card's corner: the mockup's 28 dp, never more than a fifth of its shorter side. */
private class CardCorner(private val max: Dp) : CornerSize {
    override fun toPx(shapeSize: Size, density: Density): Float =
        min(with(density) { max.toPx() }, shapeSize.minDimension * 0.2f)
}

/** Glass cards' shape, any size. */
internal val ModernCardShape: Shape = RoundedCornerShape(CardCorner(28.dp))

/** The blue of the turn tile and the bar's main key: the mockup's #8CBBFF to #4E86F2 at night, deeper by day. */
internal fun modernBlueBrush(light: Boolean): Brush = Brush.linearGradient(
    if (light) listOf(Color(0xFF4F8DF5), Color(0xFF1F55C0)) else listOf(Color(0xFF8CBBFF), Color(0xFF4E86F2))
)

/** How strong the skin's glows are: full, half with effects reduced, none with them off; softer by day, so nothing smears in sun. */
internal val modernGlow: Float get() = DashColors.Effects.scale * if (DashColors.Light) 0.6f else 1f

/** Ink on [modernBlueBrush]: the page's near black at night, white by day. */
internal val ModernOnBlue: Color get() = if (DashColors.Light) Color.White else Color(0xFF0B0E14)

/** What a glass card says beyond being glass: plain, or the amber of a suggestion. */
internal enum class GlassTone { PLAIN, AMBER }

/**
 * Frosted glass: a soft drop shadow, a translucent fill a little lighter at
 * the top, a faint sheen over the upper part, a hairline rim brighter at the
 * top and a catch of light along the top edge. [tone] AMBER washes it amber
 * from the top left with an amber rim; [rim] (arranging) draws the rim in
 * that colour. With effects off it is an opaque card with a plain rim and no
 * shadow; by day white glass with a lighter shadow.
 */
@Composable
internal fun Modifier.modernGlass(shape: Shape = ModernCardShape, tone: GlassTone = GlassTone.PLAIN, rim: Color? = null): Modifier {
    val light = DashColors.Light
    val plain = DashColors.Effects == DashEffects.NONE
    val card = DashColors.Card
    val line = DashColors.Line
    val warn = DashColors.Warning
    val lifted = if (plain) this.graphicsLayer() else this.shadow(
        if (light) 6.dp else 14.dp, shape, clip = false,
        ambientColor = Color.Black, spotColor = Color.Black
    )
    return lifted.clip(shape).cachedDraw(shape, light, plain, tone, card, line, warn, rim) {
        val outline = shape.createOutline(size, layoutDirection, this)
        val radius = when (outline) {
            is androidx.compose.ui.graphics.Outline.Rounded -> outline.roundRect.topLeftCornerRadius.x
            else -> 0f
        }
        val fill: Brush = when {
            plain -> Brush.verticalGradient(listOf(card, card))
            light -> Brush.verticalGradient(listOf(Color(0xF2FFFFFF), Color(0xDEF8FAFC)))
            else -> Brush.verticalGradient(listOf(Color(0xB81E2430), Color(0xA6141920)))
        }
        val wash = if (tone == GlassTone.AMBER) Brush.linearGradient(
            listOf(warn.copy(alpha = if (light) 0.22f else 0.30f), warn.copy(alpha = 0f)),
            start = Offset.Zero, end = Offset(size.width * 0.75f, size.height * 0.75f)
        ) else null
        val sheen = if (plain) null else Brush.verticalGradient(
            listOf(Color.White.copy(alpha = if (light) 0.5f else 0.06f), Color.Transparent),
            startY = 0f, endY = size.height * 0.45f
        )
        val stroke = (if (rim != null) 1.5f else 1f) * density
        val border: Brush = when {
            rim != null -> Brush.verticalGradient(listOf(rim, rim))
            tone == GlassTone.AMBER -> Brush.verticalGradient(listOf(warn.copy(alpha = 0.6f), warn.copy(alpha = 0.35f)))
            plain -> Brush.verticalGradient(listOf(line, line))
            light -> Brush.verticalGradient(listOf(Color(0x140B0E14), Color(0x220B0E14)))
            else -> Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.06f)))
        }
        val inset = radius.coerceAtLeast(size.width * 0.08f)
        val catch = if (plain) null else Brush.horizontalGradient(
            listOf(Color.Transparent, Color.White.copy(alpha = if (light) 1f else 0.32f), Color.Transparent),
            startX = inset, endX = size.width - inset
        )
        onDrawWithContent {
            drawRect(fill)
            if (wash != null) drawRect(wash)
            if (sheen != null) drawRect(sheen, size = Size(size.width, size.height * 0.45f))
            drawContent()
            drawRoundRect(
                border,
                topLeft = Offset(stroke / 2f, stroke / 2f),
                size = Size(size.width - stroke, size.height - stroke),
                cornerRadius = CornerRadius((radius - stroke / 2f).coerceAtLeast(0f)),
                style = Stroke(stroke)
            )
            if (catch != null) drawLine(catch, Offset(inset, stroke * 0.75f), Offset(size.width - inset, stroke * 0.75f), stroke * 1.25f)
        }
    }
}

/** How a run of Modern text is set. */
internal enum class Face { NUMBER, TEXT, CAPS }

private val TightLines = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)

/**
 * The skin's type at [fontSize]: [Face.NUMBER] heavy and tightly tracked with
 * tabular digits (the mockup's Manrope 800 at -2 px), [Face.TEXT] a slightly
 * tight sans, [Face.CAPS] small letter-spaced capitals.
 */
internal fun modernStyle(fontSize: TextUnit, color: Color, weight: FontWeight, face: Face): TextStyle = when (face) {
    Face.NUMBER -> TextStyle(
        color = color, fontSize = fontSize, fontWeight = weight, fontFamily = FontFamily.SansSerif,
        letterSpacing = (-0.035).em, fontFeatureSettings = "tnum", lineHeight = 1.05.em, lineHeightStyle = TightLines
    )
    Face.TEXT -> TextStyle(
        color = color, fontSize = fontSize, fontWeight = weight, fontFamily = FontFamily.SansSerif,
        letterSpacing = (-0.01).em, lineHeight = 1.25.em
    )
    Face.CAPS -> TextStyle(
        color = color, fontSize = fontSize, fontWeight = weight, fontFamily = FontFamily.SansSerif,
        letterSpacing = 0.08.em, lineHeight = 1.2.em
    )
}

/** One run of Modern text [size] dp tall whatever the system font scale (see [fixedSp]). */
@Composable
internal fun MText(
    text: String,
    size: Float,
    color: Color = DashColors.TextPrimary,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Medium,
    face: Face = Face.TEXT,
    maxLines: Int = 1,
    align: TextAlign = TextAlign.Start,
    overflow: TextOverflow = TextOverflow.Ellipsis
) {
    Text(
        text = text,
        modifier = modifier,
        style = modernStyle(fixedSp(size), color, weight, face).copy(textAlign = align),
        maxLines = maxLines,
        softWrap = maxLines > 1,
        overflow = overflow
    )
}

/**
 * The largest size (within [minDp]..[maxDp]) at which [sample] set as [face]
 * fits [maxW] on one line, its line no taller than [maxH]. Measured once at
 * 100 and scaled, since type grows linearly with size.
 */
@Composable
internal fun fitText(sample: String, face: Face, weight: FontWeight, maxW: Dp, maxH: Dp, minDp: Float, maxDp: Float): Float {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(sample, face, weight, maxW, maxH, minDp, maxDp, density) {
        val probe = measurer.measure(
            sample, modernStyle((100f / density.fontScale).sp, Color.Black, weight, face), softWrap = false, maxLines = 1
        )
        val byW = with(density) { maxW.toPx() } * 100f / probe.size.width.coerceAtLeast(1)
        val byH = with(density) { maxH.toPx() } * 100f / probe.size.height.coerceAtLeast(1)
        min(byW, byH).coerceIn(minDp, maxDp)
    }
}

/** The first of [candidates] (longest first) that fits [maxW] on one line at [size], else the last. */
@Composable
internal fun firstThatFits(candidates: List<String>, size: Float, weight: FontWeight, face: Face, maxW: Dp): String {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(candidates, size, weight, face, maxW, density) {
        val px = with(density) { maxW.toPx() }
        val style = modernStyle((size / density.fontScale).sp, Color.Black, weight, face)
        candidates.firstOrNull { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width <= px } ?: candidates.last()
    }
}

/** Digits replaced by 8, so a readout keeps one size while its value changes. */
internal fun digitsTemplate(text: String): String = text.map { if (it.isDigit()) '8' else it }.joinToString("")

// --- Page background ------------------------------------------------------------------

/**
 * The page: the city in 3D with the route and the car's puck (see
 * [modernCity]); its place on screen is noted for the window frame.
 */
@Composable
internal fun modernBackground(): Modifier {
    val view = LocalView.current
    // No fill under it: the city's bitmap covers the whole page.
    return Modifier
        .onGloballyPositioned { c ->
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            val p = c.positionInWindow()
            val left = loc[0] + p.x.roundToInt()
            val top = loc[1] + p.y.roundToInt()
            val rect = IntRect(left, top, left + c.size.width, top + c.size.height)
            if (ModernCity.page != rect) ModernCity.page = rect
        }
        .modernCity(route = true, carShare = PAGE_CAR_SHARE)
}

// --- Top bar --------------------------------------------------------------------------

private val BAR_HEIGHT = 64.dp
private val ISLAND_SHAPE = RoundedCornerShape(20.dp)
private val KEY_SHAPE = RoundedCornerShape(16.dp)

/**
 * The same bar as every other look, as two glass islands with the clock
 * between them: on the left Apps (the blue key, as the mockup's rail has it)
 * and Layout, their words beside them where the bar is wide enough; the time
 * heavy and tight with a short date and the dashboard's position in the
 * middle; on the right the setup pill, vehicle alerts and the phone where
 * they fit ([BarEnd]), then an island with the OBD pill and ⋮.
 */
@Composable
internal fun ModernTopBar(m: TopBarModel) {
    val now = rememberNow(60_000L)
    val dateFmt = rememberDateFormat("EEEdMMM", best = true)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(BAR_HEIGHT)
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .graphicsLayer()
    ) {
        val narrow = maxWidth < NARROW_BAR
        val labels = maxWidth >= LABELLED_BAR
        val shared = barIsShared()
        val showDate = maxWidth >= 640.dp && !(narrow && (shared || m.setupPending))
        Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Row(modifier = if (narrow) Modifier else Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                GlassIsland {
                    BarKey(
                        onClick = m.onApps, description = stringResource(R.string.dash_all_apps), primary = true,
                        label = if (labels) stringResource(R.string.dash_apps) else null
                    ) {
                        Icon(Icons.Filled.Apps, contentDescription = null, tint = ModernOnBlue, modifier = Modifier.size(24.dp))
                    }
                    LayoutPicker(m) { open ->
                        BarKey(
                            onClick = open, description = stringResource(R.string.dash_screen_layout, m.layout.title),
                            label = if (labels) stringResource(R.string.dash_layout_button) else null
                        ) {
                            LayoutIcon(m.layout, null, DashColors.TextSecondary, Modifier.size(24.dp))
                        }
                    }
                }
            }
            // The head unit's status bar shows the time while it is up.
            Row(modifier = Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!m.merged) {
                    val (digits, amPm) = splitClock(m.clock)
                    MText(digits, 26f, weight = FontWeight.ExtraBold, face = Face.NUMBER, modifier = Modifier.alignByBaseline(), overflow = TextOverflow.Clip)
                    if (amPm != null) {
                        Spacer(Modifier.width(4.dp))
                        MText(amPm, 14f, DashColors.Muted, Modifier.alignByBaseline(), weight = FontWeight.Bold)
                    }
                    if (showDate) {
                        Spacer(Modifier.width(10.dp))
                        MText(dateFmt.format(now), 14f, DashColors.Muted, Modifier.alignByBaseline(), weight = FontWeight.SemiBold)
                    }
                }
                PagePositionChip(Modifier.padding(start = 10.dp))
            }
            // ⋮ and the OBD link get their room first; the rest only where it fits.
            BarEnd(modifier = Modifier.weight(1f)) {
                if (m.setupPending) {
                    Box(Modifier.layoutId(BarRank.SETUP).padding(end = 6.dp)) {
                        SetupPill(onClick = { m.onSetup(false) }, compact = narrow || shared)
                    }
                }
                Row(modifier = Modifier.layoutId(BarRank.ALERTS), verticalAlignment = Alignment.CenterVertically) {
                    VehicleAlerts(m.obdConnection, m.obd)
                }
                Box(Modifier.layoutId(BarRank.PHONE).padding(end = 8.dp)) { PhonePill() }
                Box(Modifier.layoutId(BarRank.OBD).fillMaxHeight()) {
                    GlassIsland {
                        ObdPill(m.obdConnection, m.onConnectObd)
                        MorePicker(m) { open ->
                            BarKey(onClick = open, description = stringResource(R.string.dash_more)) {
                                Icon(Icons.Filled.MoreVert, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A glass island of the bar holding its keys. */
@Composable
private fun GlassIsland(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxHeight()
            .modernGlass(ISLAND_SHAPE)
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/**
 * A key of the bar, 52 dp at least each way: a rounded square, or a pill with
 * its [label]; [primary] fills it with the blue gradient and a blue glow.
 */
@Composable
private fun BarKey(
    onClick: () -> Unit,
    description: String,
    primary: Boolean = false,
    label: String? = null,
    icon: @Composable () -> Unit
) {
    val tap = rememberTapFeedback()
    val light = DashColors.Light
    val glow = if (primary) modernGlow else 0f
    Row(
        modifier = Modifier
            .height(52.dp)
            .widthIn(min = 52.dp)
            .then(if (glow > 0f) Modifier.shadow(8.dp * glow, KEY_SHAPE, ambientColor = DashColors.Accent, spotColor = DashColors.Accent) else Modifier)
            .clip(KEY_SHAPE)
            .then(if (primary) Modifier.background(modernBlueBrush(light)) else Modifier)
            .clickable(role = Role.Button, onClickLabel = description) { tap(); onClick() }
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = if (label != null) 14.dp else 0.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        if (label != null) {
            Spacer(Modifier.width(8.dp))
            MText(label, 15f, if (primary) ModernOnBlue else DashColors.TextPrimary, weight = FontWeight.Bold)
        }
    }
}

// --- Tiles ----------------------------------------------------------------------------

/**
 * Modern renderer for app shortcuts, launch bars and the main widgets, each on
 * its glass card; anything else keeps its standard renderer.
 */
@Composable
internal fun ModernTile(item: DashboardItem, env: SkinTileEnv) {
    when (item) {
        is DashboardItem.AppShortcut -> ModernApp(item, env)
        is DashboardItem.LaunchBar -> ModernLaunchBar(item, env)
        is DashboardItem.BuiltinWidget -> when (item.kind) {
            BuiltinKind.NAVIGATION -> ModernNavigation(env)
            BuiltinKind.SPEED_HUD -> ModernSpeed(env)
            BuiltinKind.TELEMETRY -> ModernTelemetry(env)
            BuiltinKind.RANGE -> ModernRange(item, env)
            BuiltinKind.MEDIA -> ModernMedia(env)
            BuiltinKind.CLOCK -> ModernClock(env)
            BuiltinKind.WEATHER -> ModernWeather(env)
            else -> StandardSkinnedTile(item, env)
        }
        else -> StandardSkinnedTile(item, env)
    }
}

// --- Maps window frame ----------------------------------------------------------------

/**
 * Glass bezel over a docked Maps window: its corners rounded off with the
 * same city that lies under the page (cut from the page's own picture where it
 * lies on screen, so the plan runs on behind the window), a hairline rim with
 * the catch of light along the top, a light shade at the top edge and a
 * deeper one along the foot. The middle stays clear.
 */
@Composable
internal fun ModernWindowFrame(modifier: Modifier) {
    val view = LocalView.current
    var at by remember { mutableStateOf<IntOffset?>(null) }
    val light = DashColors.Light
    val glow = DashColors.Effects.scale
    val bg = DashColors.Background
    Spacer(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { c ->
                val loc = IntArray(2)
                view.getLocationOnScreen(loc)
                val p = c.positionInWindow()
                val now = IntOffset(loc[0] + p.x.roundToInt(), loc[1] + p.y.roundToInt())
                if (at != now) at = now
            }
            .drawWithCache {
                val w = size.width
                val h = size.height
                val r = 24.dp.toPx()
                val mask = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(0f, 0f, w, h))
                    addRoundRect(RoundRect(Rect(0f, 0f, w, h), CornerRadius(r)))
                }
                // The page's own city under the corners, when the page has drawn it at this size.
                val page = ModernCity.page
                val origin = at
                val raster = if (page != null && origin != null) {
                    ModernCity.scene(page.width, page.height, light, glow, route = true).raster()
                } else null
                val cut = if (page != null && origin != null) {
                    val left = (origin.x - page.left).coerceIn(0, page.width)
                    val top = (origin.y - page.top).coerceIn(0, page.height)
                    val right = (origin.x - page.left + w.roundToInt()).coerceIn(0, page.width)
                    val bottom = (origin.y - page.top + h.roundToInt()).coerceIn(0, page.height)
                    if (right > left && bottom > top) IntRect(left, top, right, bottom) else null
                } else null
                val ink = if (light) Color(0xFF0B0E14) else Color.White
                val topShade = Brush.verticalGradient(listOf(bg.copy(alpha = 0.35f), bg.copy(alpha = 0f)), startY = 0f, endY = h * 0.06f)
                val footShade = Brush.verticalGradient(listOf(bg.copy(alpha = 0f), bg.copy(alpha = 0.55f)), startY = h * 0.86f, endY = h)
                val rim = Stroke(1.dp.toPx())
                val catch = Brush.horizontalGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = if (light) 0.9f else 0.35f), Color.Transparent),
                    startX = r, endX = w - r
                )
                onDrawBehind {
                    drawRect(topShade, size = Size(w, h * 0.06f))
                    drawRect(footShade, topLeft = Offset(0f, h * 0.86f), size = Size(w, h * 0.14f))
                    if (raster != null && cut != null && origin != null && page != null) {
                        clipPath(mask) {
                            drawRect(bg)
                            drawImage(
                                raster,
                                srcOffset = IntOffset(cut.left, cut.top),
                                srcSize = IntSize(cut.width, cut.height),
                                dstOffset = IntOffset(cut.left + page.left - origin.x, cut.top + page.top - origin.y),
                                dstSize = IntSize(cut.width, cut.height)
                            )
                        }
                    } else {
                        drawPath(mask, bg)
                    }
                    drawRoundRect(
                        ink.copy(alpha = if (light) 0.12f else 0.16f),
                        topLeft = Offset(rim.width / 2f, rim.width / 2f),
                        size = Size(w - rim.width, h - rim.width),
                        cornerRadius = CornerRadius(r - rim.width / 2f),
                        style = rim
                    )
                    drawLine(catch, Offset(r, rim.width), Offset(w - r, rim.width), rim.width * 1.5f)
                }
            }
    )
}

// --- Theme picker stamp ---------------------------------------------------------------

/**
 * The skin as a stamp: the city floor running away under a haze, the blue
 * route turning right with its white dashes and the car's dot, a glass
 * next-turn card with its blue tile, and the speed on a glass card.
 */
internal fun DrawScope.modernStamp(p: DashPalette, number: StampNumber) {
    val w = size.width
    val h = size.height
    val light = p.Light
    val ground = if (light) Color(0xFFDCE1E8) else Color(0xFF10151E)
    val street = if (light) Color(0xFFF7F8FA) else Color(0xFF2A3549)
    val top = if (light) Color(0xFFFBFCFD) else Color(0xFF253049)
    drawRect(ground)
    val vp = Offset(w * 0.5f, -h * 0.9f)
    for (i in -7..7) {
        val x = w * 0.5f + i * w * 0.14f
        drawLine(street, Offset(vp.x + (x - vp.x) * 0.45f, 0f), Offset(x, h), strokeWidth = 1.5f)
    }
    var y = h * 0.12f
    var gap = h * 0.1f
    while (y < h) {
        drawLine(street, Offset(0f, y), Offset(w, y), strokeWidth = 1.5f)
        // A row of raised block tops between the streets.
        var x = w * 0.03f
        while (x < w) {
            drawRect(top, Offset(x, y + gap * 0.22f), Size(w * 0.09f, gap * 0.5f))
            x += w * 0.14f
        }
        y += gap
        gap *= 1.25f
    }
    val route = Path().apply {
        moveTo(w * 0.5f, h)
        lineTo(w * 0.5f, h * 0.5f)
        lineTo(w * 0.76f, h * 0.5f)
        lineTo(w * 0.68f, 0f)
    }
    drawPath(route, p.Accent.copy(alpha = 0.35f), style = Stroke(h * 0.08f, cap = StrokeCap.Round))
    drawPath(route, p.Accent, style = Stroke(h * 0.03f, cap = StrokeCap.Round))
    drawPath(route, Color.White, style = Stroke(h * 0.01f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(h * 0.03f, h * 0.06f))))
    drawRect(Brush.verticalGradient(listOf(p.Background, p.Background.copy(alpha = 0f)), endY = h * 0.3f), size = Size(w, h * 0.3f))
    drawCircle(p.Accent.copy(alpha = 0.35f), h * 0.09f, Offset(w * 0.5f, h * 0.82f))
    drawRoundRect(Color.White, Offset(w * 0.485f, h * 0.76f), Size(w * 0.03f, h * 0.12f), CornerRadius(h * 0.02f))
    val glass = if (light) Color(0xE6FFFFFF) else Color(0xB81E2430)
    val rim = if (light) Color(0x1A0B0E14) else Color(0x29FFFFFF)
    drawRoundRect(glass, Offset(w * 0.05f, h * 0.08f), Size(w * 0.4f, h * 0.26f), CornerRadius(h * 0.07f))
    drawRoundRect(rim, Offset(w * 0.05f, h * 0.08f), Size(w * 0.4f, h * 0.26f), CornerRadius(h * 0.07f), style = Stroke(1f))
    drawRoundRect(modernBlueBrush(light), Offset(w * 0.08f, h * 0.12f), Size(h * 0.18f, h * 0.18f), CornerRadius(h * 0.05f))
    drawRoundRect(p.TextPrimary, Offset(w * 0.08f + h * 0.24f, h * 0.14f), Size(w * 0.14f, h * 0.05f), CornerRadius(h * 0.02f))
    drawRoundRect(p.Muted, Offset(w * 0.08f + h * 0.24f, h * 0.23f), Size(w * 0.2f, h * 0.035f), CornerRadius(h * 0.02f))
    drawRoundRect(glass, Offset(w * 0.68f, h * 0.08f), Size(w * 0.27f, h * 0.3f), CornerRadius(h * 0.07f))
    drawRoundRect(rim, Offset(w * 0.68f, h * 0.08f), Size(w * 0.27f, h * 0.3f), CornerRadius(h * 0.07f), style = Stroke(1f))
    number("87", w * 0.815f, h * 0.23f, 14f, p.TextPrimary)
}
