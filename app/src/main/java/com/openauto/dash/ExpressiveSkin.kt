package com.openauto.dash

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalIndication
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
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
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * Expressive skin (2026-10-13 trends): Material 3 Expressive, the look Android
 * Auto took on in 2026. Whole-design skin (see Skins.kt): its page, its bar
 * (a floating dark toolbar with the coral Talk button at its end), the frame
 * over a docked app window and the main widgets (ExpressiveSkinTiles.kt), the
 * driver's car in its scalloped badge (ExpressiveSkinCar.kt).
 *
 * The shapes are the identity: each widget is known by its own. The turn is a
 * coral tile with the arrow in a dark flower, the speed a tall pill, the music
 * a lime card (or the colour of the album art) with the cover in a morphing
 * blob and the wavy progress line, the car stands in a 12-bump cookie. Bold
 * flat tonal colours, heavy condensed figures, springy motion on appear and
 * press; the slow turns (badge, flower) and the blob only move while parked.
 * Mockup: D:/dashwheel-design/v16-trends/index.html, section 2.
 */

internal val ExpressivePalette = DashPalette(
    Background = Color(0xFF10141C), Bar = Color.Transparent, Card = Color(0xFF1C2331), CardHi = Color(0x2EC3D6FF),
    Accent = Color(0xFFFF9C80), Secondary = Color(0xFFD5F26C), Critical = Color(0xFFFF5A5F), Good = Color(0xFF7BE0A8),
    Muted = Color(0xFF8E9AB3), TextPrimary = Color(0xFFEEF2FA), TextSecondary = Color(0xFFB4BED3),
    Accent2 = Color(0xFFC3D6FF), Line = Color(0x29C3D6FF), Glow = 0f, Bare = true,
    BackgroundStops = listOf(Color(0xFF161C28), Color(0xFF10141C), Color(0xFF0B0E15)),
    Tacho = Color(0xFFFFB86B),
    Font = DashFont.SANS,
    HeroWeight = FontWeight.Black,
    Skin = DashSkin.EXPRESSIVE
)

internal val ExpressiveLightPalette = DashPalette(
    Background = Color(0xFFE8EDF6), Bar = Color.Transparent, Card = Color(0xFFFFFFFF), CardHi = Color(0x1A1D2433),
    Accent = Color(0xFFE0613F), Secondary = Color(0xFF5E7A00), Critical = Color(0xFFC62828), Good = Color(0xFF0E8A55),
    Muted = Color(0xFF5B6478), TextPrimary = Color(0xFF141A26), TextSecondary = Color(0xFF475069),
    Accent2 = Color(0xFF3F63C8), Line = Color(0x241D2433), Glow = 0f, Bare = true, Light = true,
    BackgroundStops = listOf(Color(0xFFEFF3FA), Color(0xFFE8EDF6), Color(0xFFDFE6F2)),
    Tacho = Color(0xFFB0600A),
    Font = DashFont.SANS,
    HeroWeight = FontWeight.Black,
    Skin = DashSkin.EXPRESSIVE
)

/** The skin's corners (dialogs, menus, tiles being arranged). */
internal val ExpressiveShapes = skinShapes(40, 36, 28)

/**
 * The Home Expressive was drawn around (SkinLayouts.kt), on the 12 x 7 grid:
 *
 * ```
 *    0 1 2 3 4 5 6 7 8 9 10 11
 * 0  C C C C C N N N N N N  N     C clock, N next turn
 * 1  C C C C C N N N N N N  N
 * 2  R R R R R S S S M M M  M     R the car in its badge (range)
 * 3  R R R R R S S S M M M  M     S speed pill, M music
 * 4  R R R R R S S S M M M  M
 * 5  R R R R R S S S M M M  M
 * 6  R R R R R D D D D D D  D     D the app dock
 * ```
 */
internal val ExpressiveLayout = SkinLayout(
    home = TemplatePage(
        listOf(BuiltinKind.NAVIGATION, BuiltinKind.RANGE, BuiltinKind.MEDIA, BuiltinKind.SPEED_HUD, BuiltinKind.CLOCK),
        dock = true
    ),
    wide = listOf(
        DashboardItem.BuiltinWidget(BuiltinKind.CLOCK, x = 0, y = 0, w = 5, h = 2),
        DashboardItem.BuiltinWidget(BuiltinKind.RANGE, x = 0, y = 2, w = 5, h = 5),
        DashboardItem.BuiltinWidget(BuiltinKind.NAVIGATION, x = 5, y = 0, w = 7, h = 2),
        DashboardItem.BuiltinWidget(BuiltinKind.SPEED_HUD, x = 5, y = 2, w = 3, h = 4),
        DashboardItem.BuiltinWidget(BuiltinKind.MEDIA, x = 8, y = 2, w = 4, h = 4),
        DashboardItem.LaunchBar(emptyList(), x = 5, y = 6, w = 7, h = 1)
    )
)

// --- Tones ------------------------------------------------------------------------

/**
 * The skin's bold tonal colours, day and night. Night keeps the same vivid
 * tiles a little deeper, and turns the dark speed pill into its inverse (a
 * pale pill on the dark page), as Material's inverse surface does.
 */
internal object ExTone {
    private val light: Boolean get() = DashColors.Light

    /** The turn tile and the Talk button. */
    val coral: Color get() = if (light) Color(0xFFFF8A6B) else Color(0xFFF47D5F)
    val onCoral: Color = Color(0xFF2A0B00)

    /** The music card while the player gives no cover to take colours from. */
    val lime: Color get() = if (light) Color(0xFFD5F26C) else Color(0xFFC4E05C)
    val onLime: Color = Color(0xFF162000)

    /** The speed pill: dark by day, its inverse by night. */
    val pill: Color get() = if (light) Color(0xFF1D2433) else Color(0xFFC9D8FF)
    val onPill: Color get() = if (light) Color.White else Color(0xFF0E1730)

    /** The floating toolbar. */
    val bar: Color get() = if (light) Color(0xFF1D2433) else Color(0xFF242D42)
    val onBar: Color get() = if (light) Color(0xFFDCE4F5) else Color(0xFFDCE4F5)

    /** The car's scalloped badge. */
    val badge: Color get() = if (light) Color(0xFFC3D6FF) else Color(0xFF2C3E66)

    /** White chips and quiet cards. */
    val chip: Color get() = if (light) Color.White else Color(0xFF232C3F)

    /** The weather card. */
    val sky: Color get() = if (light) Color(0xFFBEE3FF) else Color(0xFF1E4866)
    val onSky: Color get() = if (light) Color(0xFF07263A) else Color(0xFFE2F3FF)

    /** The engine card. */
    val lilac: Color get() = if (light) Color(0xFFE2D7FF) else Color(0xFF3A2F5E)
    val onLilac: Color get() = if (light) Color(0xFF1E1240) else Color(0xFFF0EAFF)

    /** The chips' small squares: the arrival, the rain, the range. */
    val dotRain: Color = Color(0xFF6AA8FF)
    val dotRange: Color get() = if (light) Color(0xFF8DB600) else Color(0xFFB4D84E)
}

// --- Shapes -----------------------------------------------------------------------

private const val TWO_PI = (2.0 * PI).toFloat()

/**
 * A round with [lobes] smooth bumps [depth] deep (a share of the radius),
 * filling [size] so the bumps touch its box, into [path] (reset first);
 * [turn] in radians.
 */
internal fun polarPath(path: Path, size: Size, lobes: Int, depth: Float, turn: Float = 0f): Path {
    path.reset()
    val cx = size.width / 2f
    val cy = size.height / 2f
    val r = min(cx, cy) / (1f + depth)
    val steps = max(lobes * 20, 120)
    for (i in 0..steps) {
        val t = i * TWO_PI / steps
        val rr = r * (1f + depth * cos(lobes * t))
        val x = cx + rr * cos(t + turn)
        val y = cy + rr * sin(t + turn)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

/**
 * The morphing blob at [phase] (0..1, a full cycle) filling [size], into
 * [path] (reset first): a round pushed out of true by three slow waves, so
 * no two moments look alike and it never shows a corner.
 */
internal fun blobPath(path: Path, size: Size, phase: Float): Path {
    path.reset()
    val cx = size.width / 2f
    val cy = size.height / 2f
    val r = min(cx, cy) / 1.13f
    val p = phase * TWO_PI
    val steps = 96
    for (i in 0..steps) {
        val t = i * TWO_PI / steps
        val k = 1f + 0.07f * sin(2f * t + p) + 0.04f * sin(3f * t - p + 1f) + 0.02f * cos(t + p)
        val x = cx + r * k * cos(t)
        val y = cy + r * k * sin(t)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

/** [polarPath] as a [Shape], to clip and fill with. */
internal class ExPolarShape(private val lobes: Int, private val depth: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(polarPath(Path(), size, lobes, depth))
}

/** The 12-bump cookie: the car's badge. */
internal val ExCookie: Shape = ExPolarShape(12, 0.075f)

/** The 8-petal flower: the turn's arrow, the weather's sky, the gear. */
internal val ExFlower: Shape = ExPolarShape(8, 0.15f)

/** The tall pill: the speed, the toolbar, the chips. */
internal val ExPill: Shape = RoundedCornerShape(50)

/** The squircle: the app keys. */
internal val ExSquircle: Shape = RoundedCornerShape(30)

/** The cards' corners. */
internal val EX_CARD: Dp = 36.dp
internal val ExCardShape: Shape = RoundedCornerShape(EX_CARD)

// --- Text -------------------------------------------------------------------------

/** Big figures trimmed to their glyphs, so a giant number sits where it is put. */
private val FigureLine = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)

/** The heavy figures squeezed narrow, after the mockup's condensed black (Android has no width axis). */
private val Squeeze = TextGeometricTransform(scaleX = 0.8f)

/**
 * The skin's type at [size] dp (whatever the system font scale): the plain
 * sans for words, or with [hero] the heavy condensed figures (the speed, the
 * distance to the turn). [wide] sets a figure black and unsqueezed (the clock).
 */
@Composable
internal fun exText(
    size: Float,
    color: Color = DashColors.TextPrimary,
    weight: FontWeight = FontWeight.Medium,
    hero: Boolean = false,
    wide: Boolean = false
): TextStyle {
    val figure = hero || wide
    return TextStyle(
        color = color,
        fontFamily = FontFamily.SansSerif,
        fontWeight = if (figure) FontWeight.Black else weight,
        fontSize = fixedSp(size),
        fontFeatureSettings = "tnum",
        lineHeight = if (figure) 1.0.em else 1.2.em,
        lineHeightStyle = if (figure) FigureLine else null,
        letterSpacing = when {
            hero -> (-0.01).em
            wide -> (-0.03).em
            else -> 0.em
        },
        textGeometricTransform = if (hero) Squeeze else null
    )
}

/** One run of the skin's text: one line with an ellipsis unless [maxLines] says otherwise. */
@Composable
internal fun ExLine(
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

/** Largest size (dp, for [exText]) within [minSize]..[maxSize] at which [sample] fits [maxW] x [maxH] on one line. */
@Composable
internal fun exFit(sample: String, hero: Boolean, maxW: Dp, maxH: Dp, minSize: Float, maxSize: Float, wide: Boolean = false): Float {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = exText(100f, hero = hero, wide = wide)
    return remember(sample, style, maxW, maxH, minSize, maxSize, density) {
        val probe = measurer.measure(sample, style.copy(fontSize = (100f / density.fontScale).sp), softWrap = false, maxLines = 1)
        val byW = with(density) { maxW.toPx() } * 100f / probe.size.width.coerceAtLeast(1)
        val byH = with(density) { maxH.toPx() } * 100f / probe.size.height.coerceAtLeast(1)
        min(byW, byH).coerceIn(minSize, maxSize.coerceAtLeast(minSize))
    }
}

/** Digits replaced by '8' so a readout keeps one size while its value changes. */
internal fun exTemplate(text: String): String = text.map { if (it.isDigit()) '8' else it }.joinToString("")

// --- Surfaces and motion -------------------------------------------------------------

/**
 * A flat tonal card of [fill] in [shape] with, unless the effects are off, a
 * soft drop shadow under it (stacked faint copies of the shape, no blur),
 * clipped to the shape. While the tile is being arranged its outline is
 * dashed, so it reads as movable.
 */
@Composable
internal fun Modifier.exCard(fill: Color, shape: Shape = ExCardShape, editing: Boolean = false, shadow: Boolean = true): Modifier {
    val light = DashColors.Light
    val effects = DashColors.Effects
    val ink = DashColors.TextPrimary
    return this
        .cachedDraw(fill, shape, editing, shadow, light, effects, ink) {
            val outline = shape.createOutline(size, layoutDirection, this)
            val drop = shadow && effects != DashEffects.NONE
            val shadowInk = if (light) Color(0xFF14284A) else Color.Black
            val steps = if (light) floatArrayOf(1.5f, 0.06f, 4f, 0.045f, 8f, 0.025f) else floatArrayOf(2f, 0.22f, 5f, 0.14f, 9f, 0.08f)
            val dash = if (editing) Stroke(2f * density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f * density, 7f * density))) else null
            onDrawBehind {
                if (drop) {
                    var i = 0
                    while (i < steps.size) {
                        translate(0f, steps[i] * density) { drawOutline(outline, shadowInk.copy(alpha = steps[i + 1])) }
                        i += 2
                    }
                }
                drawOutline(outline, fill)
                if (dash != null) drawOutline(outline, ink.copy(alpha = 0.5f), style = dash)
            }
        }
        .clip(shape)
}

/**
 * The springy pop of a tile when its dashboard comes into sight: from 92 % to
 * full size on a bouncy spring, in the tile's own layer. Not while the car
 * moves (it snaps in place), and never with the effects off.
 */
@Composable
internal fun Modifier.exAppear(): Modifier {
    if (DashColors.Effects == DashEffects.NONE) return this
    val active = LocalPageActive.current
    val moving = LocalCarMoving.current
    val scale = remember { Animatable(if (active && !moving) APPEAR_FROM else 1f) }
    LaunchedEffect(active) {
        if (active && !moving) {
            scale.snapTo(APPEAR_FROM)
            scale.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 380f))
        }
    }
    return graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

private const val APPEAR_FROM = 0.92f

/** Shrinks what follows to 95 % while [interaction] is pressed, back on a bouncy spring. */
@Composable
internal fun Modifier.exSquish(interaction: MutableInteractionSource, pressedScale: Float = 0.95f): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) pressedScale else 1f, spring(dampingRatio = 0.45f, stiffness = 700f), label = "exPress")
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** The tap with its feedback through [interaction] (the squish reads it), or nothing when [enabled] is false. */
@Composable
internal fun Modifier.exClick(interaction: MutableInteractionSource, enabled: Boolean, label: String, onClick: () -> Unit): Modifier {
    if (!enabled) return this
    val tap = rememberTapFeedback()
    return clickable(interaction, LocalIndication.current, onClickLabel = label, role = Role.Button) { tap(); onClick() }
}

// --- Background -------------------------------------------------------------------

private fun pageStops(light: Boolean): List<Color> =
    if (light) ExpressiveLightPalette.BackgroundStops else ExpressivePalette.BackgroundStops

/** The page's still colour [fy] of the way down it: what the window frame's corners are painted with. */
private fun pageColorAt(fy: Float, light: Boolean): Color {
    val s = pageStops(light)
    val t = fy.coerceIn(0f, 1f) * (s.size - 1)
    val i = t.toInt().coerceAtMost(s.size - 2)
    return lerp(s[i], s[i + 1], t - i)
}

/**
 * The page: the cool pale blue-grey of the mockup (deep blue-black at night),
 * a soft tonal light in the top-left corner and a giant faint flower rising
 * from the bottom-right corner behind the toolbar. Nothing moves. Effects
 * off: the plain page.
 */
@Composable
internal fun expressiveBackground(): Modifier {
    val light = DashColors.Light
    val effects = DashColors.Effects
    return remember(light, effects) {
        Modifier.drawWithCache {
            val w = size.width
            val h = size.height
            val base = Brush.verticalGradient(pageStops(light))
            val plain = effects == DashEffects.NONE
            val tint = if (light) Color(0xFFC3D6FF) else Color(0xFF2C3E66)
            val corner = Brush.radialGradient(
                listOf(tint.copy(alpha = if (light) 0.55f else 0.4f), Color.Transparent),
                Offset(w * 0.08f, h * 0.05f), max(w, h) * 0.45f
            )
            val d = h * 0.95f
            val flower = polarPath(Path(), Size(d, d), 8, 0.15f)
            val flowerAt = Offset(w - d * 0.62f, h - d * 0.55f)
            val flowerInk = tint.copy(alpha = if (light) 0.35f else 0.28f)
            onDrawBehind {
                drawRect(base)
                if (!plain) {
                    drawRect(corner)
                    translate(flowerAt.x, flowerAt.y) { drawPath(flower, flowerInk) }
                }
            }
        }
    }
}

// --- Bar --------------------------------------------------------------------------

/** The bar's height: the floating toolbar and a little air under it. */
private val EX_BAR = 84.dp

/** The toolbar's height and its round buttons. */
private val TOOLBAR_H = 68.dp
private val TOOL = 56.dp

/**
 * The bar, as Material 3 Expressive's floating toolbar: on the right a dark
 * pill holding Apps, Layout, the OBD link and ⋮ as round icon buttons, with
 * the coral Talk button at its end like a FAB. On the left, on the page
 * itself, the time large and bold (not while a clock tile or the head unit's
 * own bar shows it) with the outside temperature, the dashboard's position,
 * then the demo badge, the setup pill, the car's alerts and the phone where
 * they fit.
 */
@Composable
internal fun ExpressiveTopBar(m: TopBarModel) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(EX_BAR)
            .graphicsLayer()
    ) {
        val narrow = maxWidth < NARROW_BAR
        val shared = barIsShared()
        Row(
            modifier = Modifier.fillMaxSize().padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                val showClock = !m.merged && !ClockInSight.on.value
                if (showClock) ExBarClock(m.clock, withTemp = !narrow)
                PagePositionChip(Modifier.padding(start = if (showClock) 10.dp else 0.dp))
                BarEnd(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    if (m.demo) {
                        Box(Modifier.layoutId(BarRank.DEMO).padding(end = 8.dp)) { DemoBadge(onStop = m.onDemo, compact = true) }
                    }
                    if (m.setupPending) {
                        Box(Modifier.layoutId(BarRank.SETUP).padding(end = 8.dp)) { SetupPill(onClick = { m.onSetup(false) }, compact = narrow || shared) }
                    }
                    Row(modifier = Modifier.layoutId(BarRank.ALERTS), verticalAlignment = Alignment.CenterVertically) {
                        VehicleAlerts()
                    }
                    Box(Modifier.layoutId(BarRank.PHONE).padding(end = 4.dp)) { PhonePill() }
                }
            }
            ExToolbar(m, compact = narrow)
        }
    }
}

/** The bar's time, black and tight, the outside temperature beside it once known. */
@Composable
private fun ExBarClock(clock: String, withTemp: Boolean) {
    val temp = if (withTemp) rememberOutsideTempC() else null
    val units = LocalUnits.current
    Row(verticalAlignment = Alignment.Bottom) {
        ExLine(clock, exText(36f, DashColors.TextPrimary, wide = true), Modifier.alignByBaseline(), overflow = TextOverflow.Clip)
        if (temp != null) {
            Spacer(Modifier.width(10.dp))
            ExLine("${units.temp(temp)}°", exText(22f, DashColors.TextSecondary, FontWeight.Medium), Modifier.alignByBaseline())
        }
    }
}

/** The floating toolbar: the dark pill, its round icon buttons and the Talk FAB at its end. */
@Composable
private fun ExToolbar(m: TopBarModel, compact: Boolean) {
    val ink = ExTone.onBar
    Row(
        modifier = Modifier
            .height(TOOLBAR_H)
            .exCard(ExTone.bar, ExPill)
            .padding(start = 8.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 4.dp)
    ) {
        ExToolButton(m.onApps, stringResource(R.string.dash_all_apps)) {
            Icon(Icons.Filled.Apps, contentDescription = null, tint = ink, modifier = Modifier.size(28.dp))
        }
        LayoutPicker(m) { open ->
            ExToolButton(open, stringResource(R.string.dash_screen_layout, m.layout.title)) {
                LayoutIcon(m.layout, null, ink, Modifier.size(28.dp))
            }
        }
        ExToolObd(m.obdConnection, m.onConnectObd)
        MorePicker(m, geminiInBar = true) { open ->
            ExToolButton(open, stringResource(R.string.dash_more)) {
                Icon(Icons.Filled.MoreVert, contentDescription = null, tint = ink, modifier = Modifier.size(28.dp))
            }
        }
        Spacer(Modifier.width(4.dp))
        ExTalkFab(compact)
    }
}

/** A round icon button of the toolbar, springing under the finger. */
@Composable
private fun ExToolButton(onClick: () -> Unit, description: String, icon: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(TOOL)
            .exSquish(interaction, 0.88f)
            .clip(CircleShape)
            .exClick(interaction, true, description, onClick)
            .semantics(mergeDescendants = true) { contentDescription = description },
        contentAlignment = Alignment.Center
    ) { icon() }
}

/**
 * The OBD link on the toolbar: a dot in the link's colour over the letters
 * OBD (hollow when off, pulsing while connecting, a "!" beside it on error),
 * so it reads without its colour. What a tap does is the shared [ObdMark]'s.
 */
@Composable
private fun ExToolObd(state: ObdConnectionState, onConnect: () -> Unit) {
    // Bright marks on the dark toolbar, day and night.
    val color = when (state) {
        ObdConnectionState.CONNECTED -> Color(0xFF7BE0A8)
        ObdConnectionState.CONNECTING -> ExTone.coral
        ObdConnectionState.ERROR -> Color(0xFFFF6B6B)
        ObdConnectionState.DISCONNECTED -> ExTone.onBar.copy(alpha = 0.6f)
    }
    val pulse = if (state == ObdConnectionState.CONNECTING) rememberLoop(900, reverse = true, status = true) else null
    ObdMark(state, onConnect, shape = CircleShape) {
        Box(Modifier.size(TOOL), contentAlignment = Alignment.Center) {
            Spacer(
                Modifier
                    .size(TOOL)
                    .cachedDraw(state, color) {
                        val r = 5.dp.toPx()
                        val c = Offset(size.width / 2f, size.height * 0.3f)
                        val ring = Stroke(2.dp.toPx())
                        onDrawBehind {
                            when (state) {
                                ObdConnectionState.DISCONNECTED -> drawCircle(color, r - 1.dp.toPx(), c, style = ring)
                                ObdConnectionState.CONNECTING -> drawCircle(color, r, c, alpha = 0.3f + 0.7f * (pulse?.value ?: 1f))
                                else -> drawCircle(color, r, c)
                            }
                        }
                    }
            )
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                ExLine(stringResource(R.string.dash_obd_short), exText(15f, ExTone.onBar, FontWeight.Bold))
                if (state == ObdConnectionState.ERROR) ExLine("!", exText(15f, color, FontWeight.Black))
            }
        }
    }
}

/**
 * Talk, the toolbar's FAB: a coral rounded square with the microphone and
 * the word. It opens Gemini Live (Dashwheel's own or Google's) when there is
 * one, else the unit's voice assistant. While a conversation is open it
 * turns red, morphs to a full pill and says End, a ring pulsing round it
 * whatever the effects, since it then tells the driver Gemini is listening.
 */
@Composable
private fun ExTalkFab(compact: Boolean) {
    val context = LocalContext.current
    val gemini = remember { GeminiLive.available(context) }
    val active by GeminiLive.active.collectAsState()
    val word = stringResource(if (active) R.string.ai_gemini_live_end else R.string.expressive_talk)
    val description = stringResource(if (active) R.string.ai_gemini_live_end else R.string.expressive_talk_cd)
    val fill by animateColorAsState(if (active) DashColors.Critical else ExTone.coral, label = "exTalk")
    val ink = if (active) Color.White else ExTone.onCoral
    val corner by animateDpAsState(if (active) 28.dp else 20.dp, spring(dampingRatio = 0.5f, stiffness = 500f), label = "exTalkShape")
    val pulse = if (active) rememberLoop(1_400, status = true) else null
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(corner)
    Row(
        modifier = Modifier
            .height(TOOL)
            .widthIn(min = TOOL)
            .exSquish(interaction, 0.92f)
            .cachedDraw(fill, corner, pulse) {
                val r = corner.toPx()
                onDrawBehind {
                    val t = pulse?.value
                    if (t != null) {
                        val g = t * 8.dp.toPx()
                        drawRoundRect(fill.copy(alpha = 0.45f * (1f - t)), Offset(-g, -g), Size(size.width + 2f * g, size.height + 2f * g), CornerRadius(r + g), style = Stroke(3.dp.toPx()))
                    }
                    drawRoundRect(fill, cornerRadius = CornerRadius(r))
                }
            }
            .clip(shape)
            .exClick(interaction, true, description) {
                if (gemini || active) GeminiLive.toggle(context) else startVoiceAssistant(context)
            }
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = if (compact) 14.dp else 22.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(if (active) Icons.Filled.Close else Icons.Filled.Mic, contentDescription = null, tint = ink, modifier = Modifier.size(26.dp))
        if (!compact) {
            Spacer(Modifier.width(10.dp))
            ExLine(word, exText(20f, ink, FontWeight.Bold))
        }
    }
}

// --- Window frame -----------------------------------------------------------------

/**
 * The frame over a docked app window: its corners rounded off in the page's
 * colour behind them (read from where the window sits), and a thick tonal
 * ring in the badge's periwinkle just inside its edge with a hairline under
 * it, so the map reads as one more of the skin's cards. The middle stays clear.
 */
@Composable
internal fun ExpressiveWindowFrame(modifier: Modifier) {
    val light = DashColors.Light
    val ring = ExTone.badge
    val hair = if (light) Color(0x221D2433) else Color(0x33000000)
    val view = LocalView.current
    val origin = remember { mutableStateOf<Offset?>(null) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { c -> origin.value = c.positionInWindow() }
            .drawWithCache {
                val w = size.width
                val h = size.height
                val r = EX_CARD.toPx()
                val o = origin.value ?: Offset.Zero
                val vh = view.height.toFloat().coerceAtLeast(1f)
                val mask = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(0f, 0f, w, h))
                    addRoundRect(RoundRect(Rect(0f, 0f, w, h), CornerRadius(r)))
                }
                val maskBrush = Brush.verticalGradient(listOf(pageColorAt(o.y / vh, light), pageColorAt((o.y + h) / vh, light)))
                val band = 10.dp.toPx()
                val bandStroke = Stroke(band)
                val hairStroke = Stroke(1.dp.toPx())
                onDrawBehind {
                    drawPath(mask, maskBrush)
                    drawRoundRect(ring, Offset(band / 2f, band / 2f), Size(w - band, h - band), CornerRadius(r - band / 2f), style = bandStroke)
                    drawRoundRect(hair, Offset(band, band), Size(w - 2f * band, h - 2f * band), CornerRadius((r - band).coerceAtLeast(0f)), style = hairStroke)
                }
            }
    )
}

/** The white card behind a widget Expressive does not redraw ([SkinFallbackPanel]). */
@Composable
internal fun Modifier.expressiveFallbackPanel(): Modifier = exCard(ExTone.chip)

// --- Theme stamp --------------------------------------------------------------------

/**
 * The skin as a stamp in the theme picker (ThemePane): the periwinkle cookie
 * with a car in it, the coral turn tile with its dark flower, the dark speed
 * pill and the lime music card, the floating toolbar with its coral FAB.
 */
internal fun DrawScope.expressiveStamp(p: DashPalette, number: StampNumber) {
    val w = size.width
    val h = size.height
    val light = p.Light
    val badge = if (light) Color(0xFFC3D6FF) else Color(0xFF2C3E66)
    val coral = if (light) Color(0xFFFF8A6B) else Color(0xFFF47D5F)
    val lime = if (light) Color(0xFFD5F26C) else Color(0xFFC4E05C)
    val pill = if (light) Color(0xFF1D2433) else Color(0xFFC9D8FF)
    val onPill = if (light) Color.White else Color(0xFF0E1730)
    val bar = if (light) Color(0xFF1D2433) else Color(0xFF242D42)
    // The cookie and the car standing in it, its nose past the edge.
    val d = h * 0.7f
    translate(w * 0.03f, h * 0.12f) { drawPath(polarPath(Path(), Size(d, d), 12, 0.075f), badge) }
    val carW = d * 1.05f
    drawPanoCar(Rect(Offset(w * 0.03f + d * 0.02f, h * 0.12f + d * 0.42f), Size(carW, carW / PANO_CAR_ASPECT)), light)
    // The turn tile and its flower.
    val tile = CornerRadius(h * 0.07f)
    drawRoundRect(coral, Offset(w * 0.5f, h * 0.06f), Size(w * 0.46f, h * 0.26f), tile)
    val fd = h * 0.18f
    translate(w * 0.53f, h * 0.1f) { drawPath(polarPath(Path(), Size(fd, fd), 8, 0.15f), Color(0xFF2A0B00)) }
    drawRect(Color(0xFF2A0B00), Offset(w * 0.7f, h * 0.13f), Size(w * 0.16f, h * 0.06f))
    // The speed pill and the music card.
    drawRoundRect(pill, Offset(w * 0.5f, h * 0.37f), Size(w * 0.14f, h * 0.38f), CornerRadius(w * 0.07f))
    number("87", w * 0.57f, h * 0.53f, 12f, onPill)
    drawRoundRect(lime, Offset(w * 0.67f, h * 0.37f), Size(w * 0.29f, h * 0.38f), tile)
    val wave = Path().apply {
        moveTo(w * 0.7f, h * 0.62f)
        var x = w * 0.7f
        var up = true
        while (x < w * 0.82f) {
            quadraticTo(x + w * 0.0125f, h * (if (up) 0.6f else 0.64f), x + w * 0.025f, h * 0.62f)
            x += w * 0.025f
            up = !up
        }
        lineTo(w * 0.93f, h * 0.62f)
    }
    drawPath(wave, Color(0xFF162000), style = Stroke(2f, cap = StrokeCap.Round))
    // The floating toolbar and its FAB.
    drawRoundRect(bar, Offset(w * 0.5f, h * 0.8f), Size(w * 0.46f, h * 0.15f), CornerRadius(h * 0.075f))
    drawRoundRect(coral, Offset(w * 0.82f, h * 0.82f), Size(w * 0.12f, h * 0.11f), CornerRadius(h * 0.04f))
}

/** The words a chip or a line uses for an arrival time: the clock part of the ETA line, if it has one. */
internal fun arrivalClock(nav: NavState): String? = nav.etaParts.firstOrNull { EX_ARRIVAL_CLOCK.matches(it) }

private val EX_ARRIVAL_CLOCK = Regex("""^\d{1,2}[:h.]\d{2}(\s?[AaPp]\.?[Mm]\.?)?$""")

/** First letter upper-cased in the locale (a date like "mar. 13 oct."). */
internal fun capFirst(text: String): String = text.replaceFirstChar { it.titlecase(Locale.getDefault()) }
