package com.openauto.dash

import android.graphics.Matrix
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
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
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
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
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.toSize
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Liquid skin (2026-10-13 trends): Apple's Liquid Glass, as in iOS 26 and
 * CarPlay Ultra. A vivid dusk wallpaper of soft colour blobs (teal, blue,
 * pink, orange on deep violet; a bright pastel version by day), and over it
 * capsules and rounded cards of glass that brighten what is behind them; the
 * driver's car big in the middle (LiquidSkinCar.kt), the readings on glass
 * around it (LiquidSkinTiles.kt), and the bar a floating glass dock whose
 * brighter lens slides to the dashboard on screen.
 * Mockup: D:/dashwheel-design/v16-trends/index.html, section 1.
 *
 * Android 10 has no live blur, so the glass is faked: inside each card the
 * same wallpaper is drawn again (it is procedural, so the very same blobs) at
 * lower contrast under a white veil, then a translucent white gradient, faint
 * inner highlights, a bright rim along the top edge, and a soft shadow of
 * stacked faint shapes under it. Nothing moves while driving; parked, a light
 * crosses the dock now and then.
 */

internal val LiquidPalette = DashPalette(
    Background = Color(0xFF140F33), Bar = Color.Transparent, Card = Color(0xFF2A2160), CardHi = Color(0x33FFFFFF),
    Accent = Color(0xFF9FD0FF), Secondary = Color(0xFFFF7AB6), Critical = Color(0xFFFF5A5F), Good = Color(0xFF5DFFB0),
    Muted = Color(0xFFB9B3E0), TextPrimary = Color(0xFFFFFFFF), TextSecondary = Color(0xFFDCD8FA),
    Accent2 = Color(0xFFB69BFF), Line = Color(0x40FFFFFF), Glow = 0.5f, Bare = true,
    BackgroundStops = listOf(Color(0xFF2A1A6E), Color(0xFF1B1247), Color(0xFF120C2E)),
    Warning = Color(0xFFFFC15A),
    Font = DashFont.SANS,
    HeroWeight = FontWeight.SemiBold,
    Skin = DashSkin.LIQUID
)

internal val LiquidLightPalette = DashPalette(
    Background = Color(0xFFF1EEFF), Bar = Color.Transparent, Card = Color(0xFFFFFFFF), CardHi = Color(0x1A3A4BD8),
    Accent = Color(0xFF3A4BD8), Secondary = Color(0xFFC72A6A), Critical = Color(0xFFC62828), Good = Color(0xFF0E8A55),
    Muted = Color(0xFF5E6390), TextPrimary = Color(0xFF161A3A), TextSecondary = Color(0xFF40466F),
    Accent2 = Color(0xFF7A4BD8), Line = Color(0x26303080), Glow = 0.1f, Bare = true, Light = true,
    BackgroundStops = listOf(Color(0xFFF6F1FF), Color(0xFFEEF0FF), Color(0xFFE4EEFF)),
    Warning = Color(0xFFB45A00),
    Font = DashFont.SANS,
    HeroWeight = FontWeight.SemiBold,
    Skin = DashSkin.LIQUID
)

/** The skin's corners (dialogs, menus, tiles being arranged). */
internal val LiquidShapes = skinShapes(34, 30, 22)

/**
 * The Home Liquid was drawn around (SkinLayouts.kt): the turn card top left
 * with the speed disc under it, the car large in the middle, the music card
 * on the right with the engine's chip under it. The bar is the dock.
 *
 *     x  0 1 2 | 3 4 5 6 7 8 | 9 10 11
 *     y0 NAV   | RANGE (car) | MEDIA
 *     y4 SPEED |             | MEDIA
 *     y5 SPEED |             | TELEMETRY
 */
internal val LiquidLayout = SkinLayout(
    home = TemplatePage(
        listOf(BuiltinKind.RANGE, BuiltinKind.NAVIGATION, BuiltinKind.MEDIA, BuiltinKind.SPEED_HUD, BuiltinKind.TELEMETRY),
        dock = false
    ),
    wide = listOf(
        DashboardItem.BuiltinWidget(BuiltinKind.NAVIGATION, x = 0, y = 0, w = 3, h = 4),
        DashboardItem.BuiltinWidget(BuiltinKind.SPEED_HUD, x = 0, y = 4, w = 3, h = 3),
        DashboardItem.BuiltinWidget(BuiltinKind.RANGE, x = 3, y = 0, w = 6, h = 7),
        DashboardItem.BuiltinWidget(BuiltinKind.MEDIA, x = 9, y = 0, w = 3, h = 5),
        DashboardItem.BuiltinWidget(BuiltinKind.TELEMETRY, x = 9, y = 5, w = 3, h = 2)
    )
)

// --- Type ---------------------------------------------------------------------------

/** Big figures centred in their line box, so large numbers sit where they are put. */
private val FigureLineHeight = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

/**
 * The skin's type at [size] dp whatever the system font scale: a round
 * geometric sans; [figure] for the big tight numbers (distance, speed).
 */
@Composable
internal fun lqText(size: Float, color: Color = DashColors.TextPrimary, weight: FontWeight = FontWeight.Normal, figure: Boolean = false): TextStyle =
    TextStyle(
        color = color,
        fontFamily = FontFamily.SansSerif,
        fontWeight = weight,
        fontSize = fixedSp(size),
        fontFeatureSettings = "tnum",
        lineHeight = if (figure) 1.04.em else 1.24.em,
        lineHeightStyle = if (figure) FigureLineHeight else null,
        letterSpacing = if (figure) (-0.03).em else 0.em
    )

/** One run of the skin's text: one line with an ellipsis unless [maxLines] says otherwise. */
@Composable
internal fun LqLine(
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

/** Words beside a big figure: the text's colour, a little faded, as in the design. */
internal val lqSoft: Color get() = DashColors.TextSecondary

/** The ink on the bright white play key and lens: deep violet by night, white on the indigo key by day. */
internal val lqOnBright: Color get() = if (DashColors.Light) Color.White else Color(0xFF1A1240)

/** The bright key (play, the one thing to do): white by night, the indigo accent by day. */
internal val lqBright: Color get() = if (DashColors.Light) DashColors.Accent else Color.White.copy(alpha = 0.94f)

// --- The wallpaper ------------------------------------------------------------------

/**
 * Where the page's wallpaper is, in screen pixels, and its size: written by
 * the page background, read by every pane of glass so it draws the same
 * wallpaper behind itself at the right spot.
 */
internal object LiquidWall {
    val origin = mutableStateOf(Offset.Zero)
    val size = mutableStateOf(Size.Zero)
}

/** One colour blob of the wallpaper: its middle and radii as shares of the page, its colour. */
private class Blob(val x: Float, val y: Float, val rx: Float, val ry: Float, val color: Color)

// The design's four blobs on a 1280 x 720 page (its wall reaches 60 px past each edge).
private val NightBlobs = listOf(
    Blob(0.041f, -0.083f, 0.41f, 0.58f, Color(0xFF1FC8C8)),
    Blob(0.916f, -0.036f, 0.55f, 0.72f, Color(0xFF3D5BFF)),
    Blob(0.631f, 0.99f, 0.50f, 0.58f, Color(0xFFFF3D7F)),
    Blob(0.15f, 1.176f, 0.41f, 0.53f, Color(0xFFFF8A3D))
)
private val DayBlobs = listOf(
    Blob(0.041f, -0.083f, 0.46f, 0.62f, Color(0xFF7FE6E0)),
    Blob(0.916f, -0.036f, 0.58f, 0.76f, Color(0xFF9DB4FF)),
    Blob(0.631f, 0.99f, 0.52f, 0.6f, Color(0xFFFFA9CF)),
    Blob(0.15f, 1.176f, 0.44f, 0.56f, Color(0xFFFFC796))
)
private val NightBase = listOf(Color(0xFF2A1A6E), Color(0xFF120C2E))
private val DayBase = listOf(Color(0xFFF6F1FF), Color(0xFFE2ECFF))

/** The wallpaper's paint for a page of one size: the base gradient and one elliptic gradient per blob, with the box each one fills. */
internal class WallPaint(val base: Brush, val blobs: List<Brush>, val boxes: List<Rect>)

/** An elliptic radial gradient: [colors] from the middle out to [rx] x [ry], built once. */
private fun ellipse(center: Offset, rx: Float, ry: Float, colors: List<Color>, stops: List<Float>): Brush {
    val shader = RadialGradient(
        center.x, center.y, rx.coerceAtLeast(1f),
        colors.map { it.toArgb() }.toIntArray(), stops.toFloatArray(), Shader.TileMode.CLAMP
    ).apply { setLocalMatrix(Matrix().apply { setScale(1f, ry / rx.coerceAtLeast(1f), center.x, center.y) }) }
    return object : ShaderBrush() {
        override fun createShader(size: Size): Shader = shader
    }
}

/** Paints already built, by size and day or night: the page, the stamps, the frame. */
private val wallCache = LinkedHashMap<Triple<Int, Int, Boolean>, WallPaint>()

/** The wallpaper's paint for a page [size] big, [light] for the day version; cached. */
internal fun wallPaint(size: Size, light: Boolean): WallPaint {
    val key = Triple(size.width.roundToInt(), size.height.roundToInt(), light)
    wallCache[key]?.let { return it }
    val w = size.width
    val h = size.height
    val baseColors = if (light) DayBase else NightBase
    // The design's 160 degrees: from the top a little left of the middle to the foot a little right.
    val base = Brush.linearGradient(baseColors, Offset(w * 0.33f, 0f), Offset(w * 0.67f, h))
    val blobs = ArrayList<Brush>(4)
    val boxes = ArrayList<Rect>(4)
    for (b in if (light) DayBlobs else NightBlobs) {
        val c = Offset(b.x * w, b.y * h)
        val rx = b.rx * w
        val ry = b.ry * h
        blobs += ellipse(c, rx, ry, listOf(b.color, b.color.copy(alpha = 0.45f), b.color.copy(alpha = 0f)), listOf(0f, 0.38f, 0.7f))
        boxes += Rect(c.x - rx * 0.7f, c.y - ry * 0.7f, c.x + rx * 0.7f, c.y + ry * 0.7f)
    }
    if (wallCache.size >= 6) wallCache.remove(wallCache.keys.first())
    return WallPaint(base, blobs, boxes).also { wallCache[key] = it }
}

/** The wallpaper over [area] (page pixels): the base, then each blob over the part of its box inside the area. */
private fun DrawScope.drawWall(wall: WallPaint, area: Rect) {
    drawRect(wall.base, area.topLeft, area.size)
    for (i in wall.blobs.indices) {
        val box = wall.boxes[i].intersect(area)
        if (box.width > 0f && box.height > 0f) drawRect(wall.blobs[i], box.topLeft, box.size)
    }
}

private val screenSpot = IntArray(2)

/** Where [view]'s window starts on the screen. */
private fun windowOrigin(view: View): Offset {
    view.rootView.getLocationOnScreen(screenSpot)
    return Offset(screenSpot[0].toFloat(), screenSpot[1].toFloat())
}

/** Writes where this element is on the screen into [spot] each time it is placed. */
@Composable
private fun Modifier.trackScreen(spot: MutableState<Offset>): Modifier {
    val view = LocalView.current
    return onGloballyPositioned { c ->
        val at = c.positionInWindow() + windowOrigin(view)
        if (at != spot.value) spot.value = at
    }
}

/**
 * The page: the dusk wallpaper of soft blobs on deep violet (pastels by day),
 * still, drawn once per size. It also says where it is, for the glass.
 */
@Composable
internal fun liquidBackground(): Modifier {
    val light = DashColors.Light
    val view = LocalView.current
    return remember(light, view) {
        Modifier
            .onGloballyPositioned { c ->
                val at = c.positionInWindow() + windowOrigin(view)
                if (LiquidWall.origin.value != at) LiquidWall.origin.value = at
                val s = c.size.toSize()
                if (LiquidWall.size.value != s) LiquidWall.size.value = s
            }
            .drawWithCache {
                val wall = wallPaint(size, light)
                val area = Rect(Offset.Zero, size)
                onDrawBehind { drawWall(wall, area) }
            }
    }
}

// --- Glass --------------------------------------------------------------------------

/** Corners of the glass cards. */
internal val LIQUID_CARD = 34.dp

/** How bright the wallpaper's blobs stay inside the glass: softer than on the page. */
private const val BLOB_INSIDE = 0.55f

/**
 * A pane of Liquid glass behind the content, clipped to its corners ([radius]
 * past half the smaller side makes a capsule or a circle). Inside, the
 * wallpaper again at lower contrast under a white veil ([refract]; off for
 * small keys over glass), a white gradient brighter at the top left, a soft
 * pink catch at the bottom right, a bright rim along the top and a soft drop
 * shadow. [lift] scales the shadow; [bright] is the brighter lens glass.
 * Effects off: a plain opaque card and its hairline. While arranged its rim
 * is dashed, so it reads as movable.
 */
@Composable
internal fun Modifier.liquidGlass(
    radius: Dp = LIQUID_CARD,
    editing: Boolean = false,
    refract: Boolean = true,
    lift: Float = 1f,
    bright: Boolean = false
): Modifier {
    val light = DashColors.Light
    val effects = DashColors.Effects
    val card = DashColors.Card
    val accent = DashColors.Accent
    val shape = remember(radius) { RoundedCornerShape(radius) }
    val spot = remember { mutableStateOf(Offset.Zero) }
    val wallSize by LiquidWall.size
    return this
        .then(if (refract) Modifier.trackScreen(spot) else Modifier)
        .cachedDraw(light, effects, card, accent, editing, radius, refract, wallSize, lift, bright) {
            val w = size.width
            val h = size.height
            val r = radius.toPx().coerceAtMost(min(w, h) / 2f)
            val corner = CornerRadius(r)
            val plain = effects == DashEffects.NONE
            val wall = if (refract && !plain && wallSize.width > 0f) wallPaint(wallSize, light) else null
            val veil = Color.White.copy(alpha = if (light) 0.42f else 0.06f)
            val fallback = if (light) Color.White.copy(alpha = 0.7f) else Color(0x40FFFFFF)
            val k = if (bright) 1.35f else 1f
            val glass = if (light) Brush.linearGradient(
                0f to Color.White.copy(alpha = 0.62f * k), 0.45f to Color.White.copy(alpha = 0.34f * k), 1f to Color.White.copy(alpha = 0.44f * k),
                start = Offset.Zero, end = Offset(w * 0.34f, h)
            ) else Brush.linearGradient(
                0f to Color.White.copy(alpha = 0.24f * k), 0.45f to Color.White.copy(alpha = 0.07f * k), 1f to Color.White.copy(alpha = 0.12f * k),
                start = Offset.Zero, end = Offset(w * 0.34f, h)
            )
            // The design's two inner lights: a wide white one from above the top left, a pink one from under the bottom right.
            val topLight = ellipse(Offset(w * 0.2f, -h * 0.1f), w * 1.2f, h * 0.6f, listOf(Color.White.copy(alpha = if (light) 0.5f else 0.3f), Color.White.copy(alpha = 0f)), listOf(0f, 0.5f))
            val pinkLight = ellipse(Offset(w, h * 1.2f), w * 0.8f, h * 0.5f, listOf(Color(0xFFFFC8FF).copy(alpha = if (light) 0.22f else 0.18f), Color.Transparent), listOf(0f, 0.6f))
            val rimW = (if (editing) 1.6f else 1.2f) * density
            val half = rimW / 2f
            val rimBrush = when {
                editing -> SolidColor((if (light) accent else Color.White).copy(alpha = 0.8f))
                plain -> SolidColor(if (light) Color(0x33303080) else Color(0x55FFFFFF))
                light -> Brush.verticalGradient(listOf(Color.White, Color.White.copy(alpha = 0.55f), Color(0x22303080)), 0f, h)
                else -> Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.75f), Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.22f)), 0f, h)
            }
            val rimStroke = Stroke(rimW, pathEffect = if (editing) PathEffect.dashPathEffect(floatArrayOf(10f * density, 7f * density)) else null)
            val specular = Brush.horizontalGradient(
                listOf(Color.White.copy(alpha = 0f), Color.White.copy(alpha = if (bright) 1f else 0.85f), Color.White.copy(alpha = 0f)),
                r * 0.4f, w - r * 0.4f
            )
            val shadowInk = if (light) Color(0xFF2A2A6E) else Color(0xFF0A0028)
            val s = lift * effects.scale.coerceAtLeast(0.5f)
            val shadow = when {
                plain -> null
                light -> floatArrayOf(2f, 5f, 0.05f * s, 6f, 11f, 0.035f * s, 11f, 17f, 0.025f * s, 16f, 24f, 0.015f * s)
                else -> floatArrayOf(2f, 6f, 0.14f * s, 6f, 12f, 0.1f * s, 11f, 18f, 0.07f * s, 17f, 25f, 0.04f * s)
            }
            onDrawBehind {
                if (shadow != null) {
                    var i = 0
                    while (i < shadow.size) {
                        val g = shadow[i] * density
                        val dy = shadow[i + 1] * density
                        drawRoundRect(shadowInk.copy(alpha = shadow[i + 2]), Offset(-g, -g + dy), Size(w + 2f * g, h + 2f * g), CornerRadius(r + g))
                        i += 3
                    }
                }
                if (plain) {
                    drawRoundRect(card, cornerRadius = corner)
                } else {
                    if (wall != null) {
                        // The wallpaper behind this pane, softened: the glass bends and brightens it.
                        val o = spot.value - LiquidWall.origin.value
                        translate(-o.x, -o.y) {
                            drawRoundRect(wall.base, o, size, corner)
                            for (b in wall.blobs) drawRoundRect(b, o, size, corner, alpha = BLOB_INSIDE)
                        }
                        drawRoundRect(veil, cornerRadius = corner)
                    } else {
                        drawRoundRect(fallback, cornerRadius = corner)
                    }
                    drawRoundRect(glass, cornerRadius = corner)
                    drawRoundRect(topLight, cornerRadius = corner)
                    drawRoundRect(pinkLight, cornerRadius = corner)
                }
                drawRoundRect(rimBrush, Offset(half, half), Size(w - rimW, h - rimW), CornerRadius((r - half).coerceAtLeast(0f)), style = rimStroke)
                if (!plain && !editing) drawLine(specular, Offset(r * 0.4f, 1.2f * density), Offset(w - r * 0.4f, 1.2f * density), 1.4f * density, StrokeCap.Round)
            }
        }
        .clip(shape)
}

/**
 * A small key's glass over a card: a faint white fill, a bright line along
 * its top inside edge, no wallpaper and no shadow (the card has them).
 */
@Composable
internal fun Modifier.liquidKey(radius: Dp): Modifier {
    val light = DashColors.Light
    val plain = DashColors.Effects == DashEffects.NONE
    val shape = remember(radius) { RoundedCornerShape(radius) }
    return this
        .cachedDraw(light, plain, radius) {
            val w = size.width
            val h = size.height
            val r = radius.toPx().coerceAtMost(min(w, h) / 2f)
            val fill = if (light) Color(0x1F2A2A8A) else Color.White.copy(alpha = 0.18f)
            val rim = if (light) Brush.verticalGradient(listOf(Color.White, Color(0x1A303080)), 0f, h)
            else Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.7f), Color.White.copy(alpha = 0.08f)), 0f, h)
            val stroke = Stroke(density)
            onDrawBehind {
                drawRoundRect(fill, cornerRadius = CornerRadius(r))
                if (!plain) drawRoundRect(rim, Offset(density / 2f, density / 2f), Size(w - density, h - density), CornerRadius(r - density / 2f), style = stroke)
            }
        }
        .clip(shape)
}

/** Whole pane tappable with the tap feedback, or nothing when [enabled] is false (arranging, nothing to do). */
@Composable
internal fun Modifier.lqTap(enabled: Boolean, label: String, onClick: () -> Unit): Modifier {
    if (!enabled) return this
    val tap = rememberTapFeedback()
    return clickable(onClickLabel = label, role = Role.Button) { tap(); onClick() }
}

// --- Bar: the dock ------------------------------------------------------------------

/** The bar's height: the dock and the lens rising out of it. */
private val LIQUID_BAR = 100.dp

/** The dock's height, a slot's width, the lens's size and how far it rises. */
private val DOCK_H = 78.dp
private val SLOT = 62.dp
private val SLOT_GAP = 4.dp
private val DOCK_PAD = 10.dp
private val LENS = 70.dp
private val LENS_RISE = 6.dp

/** The corners' glass capsule: the time, the outside temperature and the OBD link. */
private val CAPSULE_H = 56.dp

/**
 * The bar: a floating glass dock in the middle with the dashboards (a
 * brighter glass lens over the one on screen, sliding on a spring when it
 * changes), the bar's apps, all apps, the layout and the gradient Talk key;
 * on its left a glass capsule with the time, the outside temperature and the
 * OBD link, then the dashboard's name; on its right the alerts, the phone,
 * the setup pill and ⋮. A narrow bar keeps the current dashboard and fewer
 * apps in the dock.
 */
@Composable
internal fun LiquidTopBar(m: TopBarModel) {
    val dash = LocalDashBar.current
    val dashboards = LocalDashboards.current
    val shown by PagePosition.shown.collectAsState()
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(LIQUID_BAR)
            .graphicsLayer()
    ) {
        val narrow = maxWidth < NARROW_BAR
        val showClock = !m.merged && !ClockInSight.on.value
        // Room for the dock: the bar less the corners' least.
        val cornerMin = if (narrow) 185.dp else 250.dp
        val slotsRoom = ((maxWidth - cornerMin * 2 - DOCK_PAD * 2).value / (SLOT + SLOT_GAP).value).toInt().coerceAtLeast(3)
        val tabs = dash?.tabs ?: dashboards?.tabs.orEmpty()
        val current = dash?.currentPage ?: shown.page
        // Apps, Layout and Talk always; then the dashboards (the one on screen first to stay), then the bar's apps.
        val forTabs = (slotsRoom - 3).coerceAtLeast(0)
        val shownTabs = when {
            tabs.size <= forTabs -> tabs
            forTabs == 0 -> emptyList()
            else -> {
                val at = tabs.indexOfFirst { it.page == current }.coerceAtLeast(0)
                val from = (at - forTabs / 2).coerceIn(0, tabs.size - forTabs)
                tabs.subList(from, from + forTabs)
            }
        }
        val apps = dash?.apps.orEmpty().filter { dash?.appsByPackage?.containsKey(it) == true }
            .take((forTabs - shownTabs.size - 1).coerceAtLeast(0))
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            BarEnd(Modifier.weight(1f).fillMaxHeight().padding(top = 6.dp), alignEnd = false) {
                Box(Modifier.layoutId(BarRank.OBD).padding(end = 10.dp)) {
                    StatCapsule(m, showClock = showClock, withTemp = !narrow || !showClock)
                }
                if (!narrow) Box(Modifier.layoutId(BarRank.CLOCK)) { PagePositionChip() }
            }
            Dock(m, shownTabs, current, apps, dash)
            BarEnd(Modifier.weight(1f).fillMaxHeight().padding(top = 6.dp)) {
                if (m.demo) {
                    Box(Modifier.layoutId(BarRank.DEMO).padding(start = 8.dp)) { DemoBadge(onStop = m.onDemo, compact = true) }
                }
                if (m.setupPending) {
                    Box(Modifier.layoutId(BarRank.SETUP).padding(start = 8.dp)) { SetupPill(onClick = { m.onSetup(false) }, compact = narrow) }
                }
                Row(modifier = Modifier.layoutId(BarRank.ALERTS).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    VehicleAlerts()
                }
                Box(Modifier.layoutId(BarRank.PHONE).padding(start = 8.dp)) { PhonePill() }
                if (narrow) Box(Modifier.layoutId(BarRank.CLOCK).padding(start = 6.dp)) { PagePositionChip() }
                Box(Modifier.layoutId(BarRank.MORE).padding(start = 10.dp)) {
                    MorePicker(m, geminiInBar = true) { open ->
                        RoundGlassButton(onClick = open, description = stringResource(R.string.dash_more)) {
                            Icon(Icons.Filled.MoreVert, contentDescription = null, tint = DashColors.TextPrimary, modifier = Modifier.size(28.dp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * The corner capsule from the design: the time, the outside temperature
 * lighter beside it (once known), and the OBD link as a dot with its letters
 * (lit with a halo when live, hollow when off, pulsing while connecting, a
 * "!" on error), which connects on a tap.
 */
@Composable
private fun StatCapsule(m: TopBarModel, showClock: Boolean, withTemp: Boolean) {
    val temp = if (withTemp) rememberOutsideTempC() else null
    val units = LocalUnits.current
    Row(
        Modifier.height(CAPSULE_H).liquidGlass(CAPSULE_H / 2).padding(start = 22.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showClock) {
            LqLine(m.clock, lqText(22f, DashColors.TextPrimary, FontWeight.SemiBold), overflow = TextOverflow.Clip)
            Spacer(Modifier.width(14.dp))
        }
        if (temp != null) {
            LqLine("${units.temp(temp)}°", lqText(20f, lqSoft), overflow = TextOverflow.Clip)
            Spacer(Modifier.width(6.dp))
        }
        LqObd(m.obdConnection, m.onConnectObd)
    }
}

/** The OBD link in the capsule. What a tap does is the shared [ObdMark]'s. */
@Composable
private fun LqObd(state: ObdConnectionState, onConnect: () -> Unit) {
    val color = obdStatusColor(state)
    val pulse = if (state == ObdConnectionState.CONNECTING) rememberLoop(900, reverse = true, status = true) else null
    val halo = if (DashColors.Light) 0f else DashColors.Effects.scale
    ObdMark(state, onConnect, shape = RoundedCornerShape(50)) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(
                Modifier
                    .size(18.dp)
                    .cachedDraw(state, color, halo) {
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
            Spacer(Modifier.width(6.dp))
            LqLine(stringResource(R.string.dash_obd_short), lqText(15f, if (state == ObdConnectionState.DISCONNECTED) lqSoft else DashColors.TextPrimary, FontWeight.Medium))
            if (state == ObdConnectionState.ERROR) LqLine("!", lqText(15f, color, FontWeight.Bold))
        }
    }
}

/** A round glass button of the bar's corners, [DashSize.Bar] across. */
@Composable
private fun RoundGlassButton(onClick: () -> Unit, description: String, icon: @Composable () -> Unit) {
    val tap = rememberTapFeedback()
    Box(
        modifier = Modifier
            .size(DashSize.Bar)
            .liquidGlass(DashSize.Bar / 2)
            .clickable(role = Role.Button, onClickLabel = description) { tap(); onClick() }
            .semantics(mergeDescendants = true) { contentDescription = description },
        contentAlignment = Alignment.Center
    ) { icon() }
}

/**
 * The dock: a capsule of glass holding the dashboards, the bar's apps, all
 * apps, the layout and Talk. The lens, a brighter rounded square of glass
 * rising a little out of the capsule, sits behind the dashboard on screen and
 * springs across when another one shows. A light crosses the capsule now and
 * then while parked.
 */
@Composable
private fun Dock(m: TopBarModel, tabs: List<CanvasTab>, current: Int, apps: List<String>, dash: DashBarModel?) {
    val context = LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val lensAt = tabs.indexOfFirst { it.page == current }
    val target = if (lensAt >= 0) (DOCK_PAD + (SLOT + SLOT_GAP) * lensAt + (SLOT - LENS) / 2).value else 0f
    val lensX by animateFloatAsState(target, spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow), label = "lens")
    Box(Modifier.height(DOCK_H + LENS_RISE), contentAlignment = Alignment.BottomStart) {
        Spacer(Modifier.matchParentSize().padding(top = LENS_RISE).liquidGlass(DOCK_H / 2, lift = 1.3f))
        DockSheen(Modifier.matchParentSize().padding(top = LENS_RISE))
        if (lensAt >= 0) {
            Spacer(
                Modifier
                    .offset { IntOffset((lensX * density.density).roundToInt(), 0) }
                    .padding(bottom = (DOCK_H - LENS) / 2 + LENS_RISE)
                    .size(LENS)
                    .liquidGlass(26.dp, lift = 0.9f, bright = true)
            )
        }
        Row(
            Modifier.height(DOCK_H).padding(horizontal = DOCK_PAD),
            horizontalArrangement = Arrangement.spacedBy(SLOT_GAP),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEach { tab ->
                val name = tab.label(context)
                DockSlot(
                    stringResource(R.string.liquid_show_dashboard, name), raised = tab.page == current,
                    onClick = { if (dash != null) dash.onSelect(tab) else PagePosition.onPick?.invoke(tab.page) }
                ) {
                    Icon(tab.icon, contentDescription = null, tint = DashColors.TextPrimary, modifier = Modifier.size(30.dp))
                }
            }
            if (apps.isNotEmpty() && dash != null) {
                DockDivider()
                apps.forEach { pkg ->
                    val app = dash.appsByPackage[pkg]
                    val name = appLabel(app, pkg)
                    DockSlot(stringResource(R.string.horizon_open_app, name), raised = false, onClick = { dash.onLaunch(pkg) }) {
                        if (app != null) AppIcon(icon = app.icon, size = 34.dp)
                    }
                }
            }
            if (tabs.isNotEmpty() || apps.isNotEmpty()) DockDivider()
            DockSlot(stringResource(R.string.dash_all_apps), raised = false, onClick = m.onApps) {
                Icon(Icons.Filled.Apps, contentDescription = null, tint = DashColors.TextPrimary, modifier = Modifier.size(30.dp))
            }
            LayoutPicker(m) { open ->
                DockSlot(stringResource(R.string.dash_screen_layout, m.layout.title), raised = false, onClick = open) {
                    LayoutIcon(m.layout, null, DashColors.TextPrimary, Modifier.size(28.dp))
                }
            }
            TalkKey()
        }
    }
}

/** A thin upright line between the dock's groups. */
@Composable
private fun DockDivider() {
    val ink = if (DashColors.Light) Color(0x33303080) else Color.White.copy(alpha = 0.22f)
    Spacer(Modifier.width(1.dp).height(34.dp).cachedDraw(ink) { onDrawBehind { drawRect(ink) } })
}

/** One slot of the dock: [SLOT] wide, the dock's height, its icon lifted with the lens when [raised]. */
@Composable
private fun DockSlot(description: String, raised: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit) {
    val tap = rememberTapFeedback()
    val rise by animateFloatAsState(if (raised) -LENS_RISE.value else 0f, spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow), label = "rise")
    Box(
        Modifier
            .size(SLOT, DOCK_H)
            .clip(RoundedCornerShape(22.dp))
            .clickable(role = Role.Button, onClickLabel = description) { tap(); onClick() }
            .semantics(mergeDescendants = true) { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.graphicsLayer { translationY = rise * density }) { icon() }
    }
}

/**
 * The light crossing the dock's glass every ten seconds: a soft white band
 * that takes three seconds to cross, then a pause in which nothing redraws.
 * Ambient motion (still while the car moves, with effects off or reduced, on
 * a hidden page), in its own layer, read only while drawing.
 */
@Composable
private fun DockSheen(modifier: Modifier) {
    if (DashColors.Effects != DashEffects.FULL) return
    val loop = rememberLoop(10_000)
    // The crossing's progress, 1 through the pause: the layer only redraws while the light moves.
    val sweep = remember(loop) { derivedStateOf { (loop.value / SHEEN_SHARE).coerceAtMost(1f) } }
    val light = DashColors.Light
    Spacer(
        modifier
            .graphicsLayer()
            .cachedDraw(light, sweep) {
                val w = size.width
                val h = size.height
                val bandW = h * 1.6f
                val shine = Brush.horizontalGradient(
                    listOf(Color.White.copy(alpha = 0f), Color.White.copy(alpha = if (light) 0.45f else 0.16f), Color.White.copy(alpha = 0f)), 0f, bandW
                )
                val outline = Path().apply { addRoundRect(RoundRect(Rect(0f, 0f, w, h), CornerRadius(h / 2f))) }
                val slant = h * 0.35f
                val streak = Path().apply { moveTo(slant, 0f); lineTo(bandW + slant, 0f); lineTo(bandW - slant, h); lineTo(-slant, h); close() }
                onDrawBehind {
                    val p = sweep.value
                    if (p >= 1f) return@onDrawBehind
                    val x = -bandW + p * (w + bandW * 1.5f)
                    clipPath(outline) { translate(x, 0f) { drawPath(streak, shine) } }
                }
            }
    )
}

/** The share of the sheen's period the light takes to cross the dock. */
private const val SHEEN_SHARE = 0.3f

/**
 * Talk, at the dock's end: a rounded square in a sky-to-violet gradient with
 * its glow and the microphone. It opens Gemini Live (Dashwheel's own or
 * Google's) when there is one, else the unit's voice assistant. While a
 * conversation is open it turns red with an end mark and its ring pulses,
 * since it then says Gemini is listening.
 */
@Composable
private fun TalkKey() {
    val context = LocalContext.current
    val gemini = GeminiLive.rememberAvailable(context)
    val active by GeminiLive.active.collectAsState()
    val glowK = if (active) 1f else if (DashColors.Light) 0.5f * DashColors.Effects.scale else DashColors.Effects.scale
    val tap = rememberTapFeedback()
    val description = stringResource(if (active) R.string.ai_gemini_live_end else R.string.liquid_talk_cd)
    val pulse = if (active) rememberLoop(1_400, status = true) else null
    Box(
        Modifier
            .padding(start = 4.dp)
            .size(SLOT)
            .cachedDraw(active, glowK, pulse) {
                val r = 20.dp.toPx()
                val fill = if (active) Brush.linearGradient(listOf(Color(0xFFFF7A7F), Color(0xFFE5303A)), Offset.Zero, Offset(size.width, size.height))
                else Brush.linearGradient(listOf(Color(0xFF77CCFF), Color(0xFFA46BFF)), Offset(size.width * 0.15f, 0f), Offset(size.width * 0.85f, size.height))
                val glowInk = if (active) Color(0xFFFF4A55) else Color(0xFFA078FF)
                val rim = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.6f), Color.White.copy(alpha = 0f)), 0f, size.height * 0.6f)
                onDrawBehind {
                    if (glowK > 0f) {
                        for (k in 1..3) {
                            val g = k * 5f * density
                            drawRoundRect(glowInk.copy(alpha = 0.13f * glowK), Offset(-g, -g), Size(size.width + 2 * g, size.height + 2 * g), CornerRadius(r + g))
                        }
                    }
                    val t = pulse?.value
                    if (t != null) {
                        val g = t * 10f * density
                        drawRoundRect(glowInk.copy(alpha = 0.5f * (1f - t)), Offset(-g, -g), Size(size.width + 2 * g, size.height + 2 * g), CornerRadius(r + g), style = Stroke(2f * density))
                    }
                    drawRoundRect(fill, cornerRadius = CornerRadius(r))
                    drawRoundRect(rim, Offset(density, density), Size(size.width - 2 * density, size.height - 2 * density), CornerRadius(r - density), style = Stroke(density))
                }
            }
            .clip(RoundedCornerShape(20.dp))
            .clickable(role = Role.Button, onClickLabel = description) {
                tap()
                if (gemini || active) GeminiLive.toggle(context) else startVoiceAssistant(context)
            }
            .semantics(mergeDescendants = true) { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Icon(if (active) Icons.Filled.Close else Icons.Filled.Mic, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
    }
}

// --- Window frame -----------------------------------------------------------------

/**
 * The frame over a docked app window: its corners rounded off with the
 * wallpaper that is behind them (drawn at the window's spot on the screen),
 * and a glass rim, bright along the top, so the map reads as one more pane of
 * glass. The middle stays clear; it takes no touches.
 */
@Composable
internal fun LiquidWindowFrame(modifier: Modifier) {
    val light = DashColors.Light
    val effects = DashColors.Effects
    val view = LocalView.current
    val spot = remember { mutableStateOf(Offset.Zero) }
    val wallSize by LiquidWall.size
    Box(
        modifier = modifier
            .fillMaxSize()
            .trackScreen(spot)
            .cachedDraw(light, effects, wallSize) {
                val w = size.width
                val h = size.height
                val r = LIQUID_CARD.toPx()
                val page = if (wallSize.width > 0f) wallSize else Size(view.rootView.width.toFloat(), view.rootView.height.toFloat())
                val wall = wallPaint(page, light)
                val mask = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(0f, 0f, w, h))
                    addRoundRect(RoundRect(Rect(0f, 0f, w, h), CornerRadius(r)))
                }
                val rimW = 1.5.dp.toPx()
                val half = rimW / 2f
                val rim = if (light) Brush.verticalGradient(listOf(Color.White, Color.White.copy(alpha = 0.5f), Color(0x33303080)), 0f, h)
                else Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.8f), Color.White.copy(alpha = 0.2f), Color.White.copy(alpha = 0.3f)), 0f, h)
                val specular = if (effects != DashEffects.NONE) Brush.horizontalGradient(
                    listOf(Color.White.copy(alpha = 0f), Color.White.copy(alpha = 0.9f), Color.White.copy(alpha = 0f)), r, w - r
                ) else null
                onDrawBehind {
                    val o = spot.value - LiquidWall.origin.value
                    clipPath(mask) { translate(-o.x, -o.y) { drawWall(wall, Rect(o, size)) } }
                    drawRoundRect(rim, Offset(half, half), Size(w - rimW, h - rimW), CornerRadius(r - half), style = Stroke(rimW))
                    if (specular != null) drawLine(specular, Offset(r, rimW + density), Offset(w - r, rimW + density), 1.5f * density, StrokeCap.Round)
                }
            }
    )
}

/** The glass behind a widget Liquid does not redraw ([SkinFallbackPanel]). */
@Composable
internal fun Modifier.liquidFallbackPanel(): Modifier = liquidGlass()

// --- Theme stamp --------------------------------------------------------------------

/**
 * The skin as a stamp in the theme picker (ThemePane): the wallpaper's blobs,
 * a glass turn card and the speed disc on the left, the car in the middle on
 * its shadow, a glass music card on the right, and the dock capsule at the
 * foot with its lens and the gradient Talk key.
 */
internal fun DrawScope.liquidStamp(p: DashPalette, number: StampNumber) {
    val w = size.width
    val h = size.height
    val light = p.Light
    drawWall(wallPaint(size, light), Rect(Offset.Zero, size))
    val glass = if (light) Color.White.copy(alpha = 0.62f) else Color.White.copy(alpha = 0.2f)
    val rim = if (light) Color.White else Color.White.copy(alpha = 0.6f)
    val hair = max(1f, h * 0.008f)
    fun pane(x: Float, y: Float, pw: Float, ph: Float, r: Float) {
        drawRoundRect(Color.Black.copy(alpha = if (light) 0.05f else 0.18f), Offset(x, y + h * 0.02f), Size(pw, ph), CornerRadius(r))
        drawRoundRect(glass, Offset(x, y), Size(pw, ph), CornerRadius(r))
        drawRoundRect(rim, Offset(x, y), Size(pw, ph), CornerRadius(r), style = Stroke(hair))
    }
    // The turn card with its arrow chip and the speed disc.
    pane(w * 0.04f, h * 0.08f, w * 0.22f, h * 0.36f, h * 0.06f)
    drawRoundRect(Color.White.copy(alpha = if (light) 0.8f else 0.3f), Offset(w * 0.07f, h * 0.13f), Size(h * 0.1f, h * 0.1f), CornerRadius(h * 0.03f))
    drawRoundRect(p.TextPrimary, Offset(w * 0.07f, h * 0.28f), Size(w * 0.1f, h * 0.05f), CornerRadius(h * 0.02f))
    drawRoundRect(p.TextSecondary.copy(alpha = 0.6f), Offset(w * 0.07f, h * 0.36f), Size(w * 0.14f, h * 0.025f), CornerRadius(h * 0.01f))
    val disc = Offset(w * 0.13f, h * 0.64f)
    val dr = h * 0.14f
    drawCircle(Color.Black.copy(alpha = if (light) 0.05f else 0.18f), dr, disc + Offset(0f, h * 0.02f))
    drawCircle(glass, dr, disc)
    drawCircle(rim, dr, disc, style = Stroke(hair))
    number("87", disc.x, disc.y, 11f, p.TextPrimary)
    // The car on its soft floor shadow.
    val car = Rect(w * 0.31f, h * 0.36f, w * 0.69f, h * 0.36f + w * 0.38f / PANO_CAR_ASPECT)
    drawOval(Brush.radialGradient(listOf(Color.Black.copy(alpha = if (light) 0.2f else 0.5f), Color.Transparent), Offset(car.center.x, car.bottom), car.width * 0.5f),
        Offset(car.left - car.width * 0.05f, car.bottom - h * 0.04f), Size(car.width * 1.1f, h * 0.08f))
    drawPanoCar(car, light)
    // The music card with its cover.
    pane(w * 0.74f, h * 0.08f, w * 0.22f, h * 0.5f, h * 0.06f)
    drawRoundRect(
        Brush.linearGradient(listOf(Color(0xFFFF5EA0), Color(0xFF5B7BFF)), Offset(w * 0.76f, h * 0.12f), Offset(w * 0.94f, h * 0.3f)),
        Offset(w * 0.765f, h * 0.12f), Size(w * 0.17f, h * 0.2f), CornerRadius(h * 0.04f)
    )
    drawCircle(if (light) p.Accent else Color.White, h * 0.04f, Offset(w * 0.85f, h * 0.48f))
    // The dock, its lens and Talk.
    val dockY = h * 0.8f
    val dockH = h * 0.14f
    pane(w * 0.28f, dockY, w * 0.44f, dockH, dockH / 2f)
    drawRoundRect(Color.White.copy(alpha = if (light) 0.85f else 0.35f), Offset(w * 0.3f, dockY - h * 0.02f), Size(dockH * 1.05f, dockH * 1.05f), CornerRadius(dockH * 0.35f))
    drawRoundRect(rim, Offset(w * 0.3f, dockY - h * 0.02f), Size(dockH * 1.05f, dockH * 1.05f), CornerRadius(dockH * 0.35f), style = Stroke(hair))
    for (i in 1..3) drawCircle(p.TextPrimary.copy(alpha = 0.7f), h * 0.018f, Offset(w * (0.33f + i * 0.075f) + dockH * 0.2f, dockY + dockH / 2f))
    drawRoundRect(
        Brush.linearGradient(listOf(Color(0xFF77CCFF), Color(0xFFA46BFF)), Offset(w * 0.64f, dockY), Offset(w * 0.7f, dockY + dockH)),
        Offset(w * 0.7f - dockH * 1.05f, dockY + dockH * 0.12f), Size(dockH * 0.76f, dockH * 0.76f), CornerRadius(dockH * 0.25f)
    )
}
