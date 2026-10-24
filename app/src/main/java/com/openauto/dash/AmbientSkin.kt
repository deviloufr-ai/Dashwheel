package com.openauto.dash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
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
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * Ambient skin (2026-10-13 car-free trends): light as information, after the
 * cabin light of cars that signals and the edge glow of voice assistants.
 * Mockup: D:/dashwheel-design/v17-no-car/index.html, section 1.
 *
 * The whole screen is a field of light: a few huge soft round glows over
 * near-black (a pale page by day). Their colours carry the drive's state:
 * calm blue and violet while cruising; a warm amber rising from the side of
 * the next turn as it nears, breathing slowly only while the car stands; red
 * when the car has a problem; a rainbow along the foot while the assistant
 * listens. Every change is a slow cross-fade on an event, nothing runs on its
 * own while driving. Few words float in the light, big and thin, with no
 * boxes (AmbientSkinTiles.kt). Android 10 cannot blur: the glows are soft by
 * construction, radial gradients fading to nothing.
 */

internal val AmbientPalette = DashPalette(
    Background = Color(0xFF05070F), Bar = Color.Transparent, Card = Color(0xFF10141F), CardHi = Color(0x14FFFFFF),
    Accent = Color(0xFFFFB347), Secondary = Color(0xFF3B6CFF), Critical = Color(0xFFFF5A5F), Good = Color(0xFF52E3A4),
    Muted = Color(0xFF9AA3BD), TextPrimary = Color(0xFFF3F6FF), TextSecondary = Color(0xFFC3CAE0),
    Accent2 = Color(0xFF8F7BFF), Line = Color(0x1AFFFFFF), Glow = 0.6f, Bare = true,
    BackgroundStops = listOf(Color(0xFF0A0D1A), Color(0xFF05070F), Color(0xFF030409)),
    Font = DashFont.SANS,
    HeroWeight = FontWeight.ExtraLight,
    Skin = DashSkin.AMBIENT
)

internal val AmbientLightPalette = DashPalette(
    Background = Color(0xFFF3F4FA), Bar = Color.Transparent, Card = Color(0xFFFFFFFF), CardHi = Color(0x10101C40),
    Accent = Color(0xFFC2620F), Secondary = Color(0xFF2F55D4), Critical = Color(0xFFC62828), Good = Color(0xFF0E8A55),
    Muted = Color(0xFF5B6380), TextPrimary = Color(0xFF10121C), TextSecondary = Color(0xFF3C4258),
    Accent2 = Color(0xFF6A4FD8), Line = Color(0x1A10121C), Glow = 0.1f, Bare = true, Light = true,
    BackgroundStops = listOf(Color(0xFFFFF8F0), Color(0xFFF3F4FA), Color(0xFFE9ECFA)),
    Font = DashFont.SANS,
    HeroWeight = FontWeight.ExtraLight,
    Skin = DashSkin.AMBIENT
)

/** The skin's corners (dialogs, menus, tiles being arranged). */
internal val AmbientShapes = skinShapes(28, 26, 20)

/**
 * The Home Ambient was drawn around: few tiles, lots of light around them.
 * Wide screen, 12 x 7:
 *
 *     CLOCK    . SPEED_HUD .  (light)
 *     (light)  . SPEED_HUD .  (light)
 *     (light)  NAVIGATION     MEDIA
 */
internal val AmbientLayout = SkinLayout(
    home = TemplatePage(listOf(BuiltinKind.SPEED_HUD, BuiltinKind.NAVIGATION, BuiltinKind.MEDIA, BuiltinKind.CLOCK)),
    wide = listOf(
        DashboardItem.BuiltinWidget(BuiltinKind.CLOCK, x = 0, y = 0, w = 3, h = 2),
        DashboardItem.BuiltinWidget(BuiltinKind.SPEED_HUD, x = 3, y = 0, w = 6, h = 4),
        DashboardItem.BuiltinWidget(BuiltinKind.NAVIGATION, x = 3, y = 4, w = 6, h = 3),
        DashboardItem.BuiltinWidget(BuiltinKind.MEDIA, x = 9, y = 4, w = 3, h = 3)
    )
)

// --- Type -------------------------------------------------------------------------

/** Big figures centred in their line box, so a huge thin numeral sits where it is put. */
private val FigureLine = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)

/**
 * The skin's type at [size] dp whatever the system font scale: the system
 * sans at [weight] (Roboto ships Thin and Light, so big figures go really
 * thin). [caps] tracks the letters wide as the mockup's "K M / H";
 * [figure] tightens a big numeral and centres it in its line.
 */
@Composable
internal fun amText(
    size: Float,
    color: Color = DashColors.TextPrimary,
    weight: FontWeight = FontWeight.Light,
    caps: Boolean = false,
    figure: Boolean = false
): TextStyle = TextStyle(
    color = color,
    fontFamily = FontFamily.SansSerif,
    fontWeight = weight,
    fontSize = fixedSp(if (caps) max(size, MIN_CAPS_DP) else size),
    fontFeatureSettings = "tnum",
    lineHeight = if (figure) 1.0.em else 1.25.em,
    lineHeightStyle = if (figure) FigureLine else null,
    letterSpacing = when {
        caps -> 0.3.em
        figure -> (-0.03).em
        else -> 0.em
    }
)

/** One run of the skin's words: a single line with an ellipsis unless [maxLines] says otherwise. */
@Composable
internal fun AmLine(
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

/** [AmLine] for words in two inks ("Left in **300 m**"). */
@Composable
internal fun AmLine(
    text: AnnotatedString,
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

/** How strong the skin's glows are: the effects setting's, halved by day where a glow only smears in the sun. */
internal val amGlow: Float get() = DashColors.Effects.scale * if (DashColors.Light) 0.5f else 1f

/** The warm ink of a distance or a turn: pale amber at night, deep amber by day. */
internal val amWarmInk: Color get() = if (DashColors.Light) DashColors.AccentInk else Color(0xFFFFCF8A)

// --- The light's state -------------------------------------------------------------

/** No warm light further than this from the turn, in metres. */
internal const val AMBIENT_TURN_M = 600f

/** The next turn's distance in metres from the navigation's words ("300 m", "1,2 km", "0.2 mi"); null when it gives none. */
internal fun ambientTurnMeters(distance: String): Float? {
    val m = NavState.DISTANCE.find(distance) ?: return null
    val v = m.groupValues[1].replace(',', '.').toFloatOrNull() ?: return null
    return when (m.groupValues[2]) {
        "m" -> v
        "km" -> v * 1000f
        "ft" -> v * 0.3048f
        "yd" -> v * 0.9144f
        "mi" -> v * 1609.34f
        else -> null
    }
}

/**
 * How warm the light is [meters] before the turn: none beyond
 * [AMBIENT_TURN_M], then rising to full at the turn, in tenths so the light
 * changes in a few steps as the turn nears, not at every metre.
 */
internal fun ambientWarmth(meters: Float?): Float {
    if (meters == null || meters < 0f || meters > AMBIENT_TURN_M) return 0f
    val raw = 0.35f + 0.65f * (1f - meters / AMBIENT_TURN_M)
    return (raw * 10f).roundToInt() / 10f
}

/** The light a guided route gives: how warm (0 none) and from which side (-1 left, 1 right, 0 ahead, from the foot). */
internal data class AmbientTurn(val warmth: Float, val side: Int)

internal fun ambientTurn(nav: NavState): AmbientTurn {
    if (!nav.active) return AmbientTurn(0f, 0)
    val warmth = ambientWarmth(ambientTurnMeters(nav.distance))
    if (warmth == 0f) return AmbientTurn(0f, 0)
    val side = nav.turnSide().sign
    // Straight on (a roundabout, an exit): a softer glow from the foot.
    return AmbientTurn(if (side == 0) (warmth * 7f).roundToInt() / 10f else warmth, side)
}

/** Whether the car has a problem worth the red: a critical reading, a stop warning of the car's own, a tyre. */
private fun carProblem(critical: Boolean, psa: PsaTrip?, tyres: Boolean): Boolean =
    critical || tyres || psa?.warnings?.any { PsaWarnings.of(it)?.level == PsaWarnings.Level.STOP } == true

/**
 * What the field of light shows now, each value cross-faded on its event:
 * [warm] the turn's amber and its [side], [alert] the red of a problem,
 * [listen] the assistant's rainbow. [pulse] breathes the amber while the car
 * stands and [phase] runs the rainbow along the foot; both hold while driving.
 */
@Stable
internal class AmbientLight {
    val warm = Animatable(0f)
    val side = mutableFloatStateOf(0f)
    val alert = Animatable(0f)
    val listen = Animatable(0f)
    val pulse = Animatable(1f)
    val phase = mutableFloatStateOf(0f)
    var turnNear by mutableStateOf(false)
    var listening by mutableStateOf(false)
}

private const val RISE_MS = 1_400
private const val FALL_MS = 2_200
private const val SWAP_MS = 700
private const val ALERT_MS = 1_600
private const val LISTEN_MS = 700
private const val PULSE_MS = 2_800L
private const val EDGE_MS = 4_000L

/**
 * The light's state, followed from the navigation, the car's warnings and
 * the assistant. Values are only read while drawing, so a change redraws the
 * page's light and recomposes nothing.
 */
@Composable
internal fun rememberAmbientLight(): AmbientLight {
    val l = remember { AmbientLight() }
    LaunchedEffect(l) {
        launch {
            NavDirections.state.map(::ambientTurn).distinctUntilChanged().collectLatest { t ->
                l.turnNear = t.warmth > 0f
                if (t.warmth > 0f && t.side.toFloat() != l.side.floatValue) {
                    // The turn changed sides: the old glow goes before the new one rises.
                    if (l.warm.value > 0.02f) l.warm.animateTo(0f, tween(SWAP_MS))
                    l.side.floatValue = t.side.toFloat()
                }
                val ms = if (t.warmth >= l.warm.value) RISE_MS else FALL_MS
                l.warm.animateTo(t.warmth, tween(ms, easing = FastOutSlowInEasing))
            }
        }
        launch {
            combine(
                snapshotFlow { AlertCenter.critical.isNotEmpty() },
                PsaTripComputer.state,
                Tyres.problems
            ) { critical, psa, tyres -> carProblem(critical, psa, tyres.isNotEmpty()) }
                .distinctUntilChanged()
                .collectLatest { on -> l.alert.animateTo(if (on) 1f else 0f, tween(ALERT_MS)) }
        }
        launch {
            GeminiLive.active.collectLatest { on ->
                l.listening = on
                l.listen.animateTo(if (on) 1f else 0f, tween(LISTEN_MS))
            }
        }
    }
    return l
}

/**
 * The slow breath of the turn's amber and the rainbow's drift, stepped at
 * the effects setting's rate and only while there is one to show and the
 * car stands; held (the amber full) while driving or with effects off.
 */
@Composable
private fun AmbientTicker(l: AmbientLight, moving: Boolean, effects: DashEffects) {
    LaunchedEffect(l, moving, effects) {
        val step = when (effects) {
            DashEffects.FULL -> 50L
            DashEffects.REDUCED -> 100L
            DashEffects.NONE -> null
        }
        if (step == null || moving) {
            l.pulse.animateTo(1f, tween(600))
            return@LaunchedEffect
        }
        snapshotFlow { l.turnNear || l.listening }.collectLatest { on ->
            if (!on) {
                l.pulse.animateTo(1f, tween(600))
                return@collectLatest
            }
            while (true) {
                delay(step - System.currentTimeMillis() % step)
                withFrameMillis { }
                val now = System.currentTimeMillis()
                l.pulse.snapTo(0.5f + 0.5f * cos(2.0 * PI * (now % PULSE_MS) / PULSE_MS).toFloat())
                l.phase.floatValue = (now % EDGE_MS).toFloat() / EDGE_MS
            }
        }
    }
}

// --- Background --------------------------------------------------------------------

private val NightBlue = Color(0xFF3B6CFF)
private val NightViolet = Color(0xFF7A3CFF)
private val NightIndigo = Color(0xFF2B3CFF)
private val NightRed = Color(0xFFFF3B4A)
private val NightWarmCore = Color(0xFFFFB347)
private val NightWarmMid = Color(0xFFFF7A2F)
private val DayBlue = Color(0xFF5B83FF)
private val DayViolet = Color(0xFFA07BFF)
private val DayIndigo = Color(0xFF7FA2FF)
private val DayRed = Color(0xFFFF4D5A)
private val DayWarmCore = Color(0xFFFFA94D)
private val DayWarmMid = Color(0xFFFF8A3D)

/** The assistant's colours, looping (its first and last are the same, so the drift has no seam). */
private val Rainbow = listOf(Color(0xFFFF7AC8), Color(0xFFFFB347), Color(0xFF6FE0FF), Color(0xFF8F7BFF), Color(0xFFFF7AC8))

/** The rainbow's soft edge: height in dp and strength, the widest faintest first. */
private val EdgeLayers = floatArrayOf(34f, 0.07f, 20f, 0.12f, 10f, 0.22f, 3f, 0.9f)

/**
 * The page: the field of light (see the file's header). Effects off: the
 * plain page, and the same news as crisp strips at the edges (the turn's
 * side, red along the top, the rainbow along the foot).
 */
@Composable
internal fun ambientBackground(): Modifier {
    val light = DashColors.Light
    val effects = DashColors.Effects
    val stops = DashColors.BackgroundStops
    val page = DashColors.Background
    val warmInk = DashColors.Accent
    val red = DashColors.Critical
    val moving = LocalCarMoving.current
    val glow = rememberAmbientLight()
    AmbientTicker(glow, moving, effects)
    return remember(glow, light, effects, stops, page, warmInk, red) { ambientField(glow, light, effects, stops, page, warmInk, red) }
}

/** A soft round pool of [c]: [a] at its heart, fading to nothing at [r]. */
private fun pool(c: Color, a: Float, center: Offset, r: Float): Brush = Brush.radialGradient(
    0f to c.copy(alpha = a), 0.45f to c.copy(alpha = a * 0.42f), 1f to c.copy(alpha = 0f),
    center = center, radius = r
)

private fun ambientField(
    g: AmbientLight, light: Boolean, effects: DashEffects, stops: List<Color>, page: Color, warmInk: Color, red: Color
): Modifier = Modifier.drawWithCache {
    val w = size.width
    val h = size.height
    val big = max(w, h)
    val dp = density
    val plain = effects == DashEffects.NONE
    val k = if (effects == DashEffects.REDUCED) 0.75f else 1f
    val base: Brush = if (plain) SolidColor(page) else Brush.verticalGradient(stops)
    val blueC = Offset(w * 0.94f, h * 0.02f)
    val blueR = big * 0.5f
    val blue = pool(if (light) DayBlue else NightBlue, (if (light) 0.32f else 0.62f) * k, blueC, blueR)
    val violetC = Offset(w * 0.8f, h * 1.12f)
    val violetR = big * 0.48f
    val violet = pool(if (light) DayViolet else NightViolet, (if (light) 0.26f else 0.55f) * k, violetC, violetR)
    val indigoC = Offset(w * 0.06f, h * 0.92f)
    val indigoR = big * 0.34f
    val indigo = pool(if (light) DayIndigo else NightIndigo, (if (light) 0.14f else 0.2f) * k, indigoC, indigoR)
    val redR = big * 0.52f
    val redA = pool(if (light) DayRed else NightRed, (if (light) 0.3f else 0.55f) * k, blueC, redR)
    val redBC = Offset(w * 0.04f, h * 1.04f)
    val redBR = big * 0.42f
    val redB = pool(if (light) DayRed else NightRed, (if (light) 0.22f else 0.4f) * k, redBC, redBR)
    // The turn's glow, drawn at the origin and moved and scaled there, so it is built once.
    val warmR = min(big * 0.56f, w * 0.95f)
    val warm = Brush.radialGradient(
        0f to (if (light) DayWarmCore else NightWarmCore).copy(alpha = (if (light) 0.7f else 0.95f) * k),
        0.35f to (if (light) DayWarmMid else NightWarmMid).copy(alpha = (if (light) 0.38f else 0.6f) * k),
        0.75f to Color.Transparent,
        center = Offset.Zero, radius = warmR
    )
    val leftC = Offset(-w * 0.06f, h * 0.5f)
    val rightC = Offset(w * 1.06f, h * 0.5f)
    val footC = Offset(w * 0.5f, h * 1.1f)
    val rainbow = Brush.horizontalGradient(Rainbow, 0f, w, TileMode.Repeated)
    val strip = 8f * dp
    val thin = 4f * dp
    onDrawBehind {
        drawRect(base)
        val warmth = g.warm.value
        val side = g.side.floatValue
        val alert = g.alert.value
        val listen = g.listen.value
        if (!plain) {
            // The calm pools give way to the red, and to the amber on their side.
            val calm = 1f - 0.65f * alert
            drawCircle(blue, blueR, blueC, alpha = calm * (1f - 0.6f * warmth * max(side, 0f)))
            drawCircle(violet, violetR, violetC, alpha = calm)
            drawCircle(indigo, indigoR, indigoC, alpha = calm * (1f - 0.7f * warmth * max(-side, 0f)))
            if (alert > 0.01f) {
                drawCircle(redA, redR, blueC, alpha = alert)
                drawCircle(redB, redBR, redBC, alpha = alert)
            }
            if (warmth > 0.01f) {
                val c = when {
                    side < -0.5f -> leftC
                    side > 0.5f -> rightC
                    else -> footC
                }
                val s = 0.6f + 0.4f * warmth
                val a = ((0.35f + 0.65f * warmth) * (0.72f + 0.28f * g.pulse.value)).coerceAtMost(1f)
                translate(c.x, c.y) {
                    scale(s, s, Offset.Zero) { drawCircle(warm, warmR, Offset.Zero, alpha = a) }
                }
            }
            if (listen > 0.01f) {
                translate(-g.phase.floatValue * w, 0f) {
                    var i = 0
                    while (i < EdgeLayers.size) {
                        val eh = EdgeLayers[i] * dp
                        drawRect(rainbow, Offset(0f, h - eh), Size(2f * w, eh), alpha = EdgeLayers[i + 1] * listen)
                        i += 2
                    }
                }
            }
        } else {
            if (warmth > 0.01f) {
                when {
                    side < -0.5f -> drawRect(warmInk, Offset.Zero, Size(strip, h), alpha = warmth)
                    side > 0.5f -> drawRect(warmInk, Offset(w - strip, 0f), Size(strip, h), alpha = warmth)
                    else -> {
                        drawRect(warmInk, Offset.Zero, Size(strip, h), alpha = warmth * 0.6f)
                        drawRect(warmInk, Offset(w - strip, 0f), Size(strip, h), alpha = warmth * 0.6f)
                    }
                }
            }
            if (alert > 0.01f) drawRect(red, Offset.Zero, Size(w, thin), alpha = alert)
            if (listen > 0.01f) drawRect(rainbow, Offset(0f, h - thin), Size(w, thin), alpha = listen)
        }
    }
}

// --- Bar --------------------------------------------------------------------------

/** The bar's height: its 64 dp targets and a little air. */
private val AMBIENT_BAR = 72.dp

/**
 * The bar, along the foot of the screen: no plates, only thin icons and a
 * few words in the light. Apps and Layout on the left with their words
 * where the bar is wide, then the time (unless a clock tile or the head
 * unit's own bar shows it) and the dashboard's position; Talk in the middle
 * as a small glowing pill that turns into "Listening" with its waves while
 * the assistant listens; on the right the setup pill, alerts and the phone
 * where they fit, the OBD link as a dot and its letters, and ⋮.
 */
@Composable
internal fun AmbientTopBar(m: TopBarModel) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(AMBIENT_BAR)
            .graphicsLayer()
    ) {
        val narrow = maxWidth < NARROW_BAR
        val labels = !narrow && maxWidth >= LABELLED_BAR
        val shared = barIsShared()
        val showClock = !m.merged && !ClockInSight.on.value
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                AmBarButton(
                    onClick = m.onApps,
                    description = stringResource(R.string.dash_all_apps),
                    label = if (labels) stringResource(R.string.dash_apps) else null
                ) { Icon(Icons.Outlined.Apps, contentDescription = null, tint = DashColors.TextPrimary, modifier = Modifier.size(26.dp)) }
                LayoutPicker(m) { open ->
                    AmBarButton(
                        onClick = open,
                        description = stringResource(R.string.dash_screen_layout, m.layout.title),
                        label = if (labels) stringResource(R.string.dash_layout_button) else null
                    ) { LayoutIcon(m.layout, null, DashColors.TextPrimary, Modifier.size(26.dp)) }
                }
                if (showClock) {
                    AmLine(m.clock, amText(24f, DashColors.TextPrimary, FontWeight.Light), Modifier.padding(start = 12.dp), overflow = TextOverflow.Clip)
                }
                if (!narrow) PagePositionChip(Modifier.padding(start = 10.dp))
            }
            AmTalkPill(compact = narrow)
            BarEnd(modifier = Modifier.weight(1f)) {
                if (m.demo) {
                    Box(Modifier.layoutId(BarRank.DEMO).padding(end = 8.dp)) { DemoBadge(onStop = m.onDemo, compact = true) }
                }
                if (m.setupPending) {
                    Box(Modifier.layoutId(BarRank.SETUP).padding(end = 8.dp)) { SetupPill(onClick = { m.onSetup(false) }, compact = narrow || shared) }
                }
                Row(modifier = Modifier.layoutId(BarRank.ALERTS), verticalAlignment = Alignment.CenterVertically) {
                    VehicleAlerts()
                }
                Box(Modifier.layoutId(BarRank.PHONE).padding(end = 8.dp)) { PhonePill() }
                if (narrow) {
                    Box(Modifier.layoutId(BarRank.CLOCK).padding(end = 6.dp)) { PagePositionChip() }
                }
                Box(Modifier.layoutId(BarRank.OBD).padding(end = 4.dp)) { AmObdMark(m.obdConnection, m.onConnectObd) }
                Box(Modifier.layoutId(BarRank.MORE)) {
                    MorePicker(m, geminiInBar = true) { open ->
                        AmBarButton(onClick = open, description = stringResource(R.string.dash_more)) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = null, tint = DashColors.TextPrimary, modifier = Modifier.size(26.dp))
                        }
                    }
                }
            }
        }
    }
}

/** A bar button with no plate: a [DashSize.Bar] target, the thin icon and its word beside it where given. */
@Composable
private fun AmBarButton(onClick: () -> Unit, description: String, label: String? = null, icon: @Composable () -> Unit) {
    val tap = rememberTapFeedback()
    Row(
        modifier = Modifier
            .height(DashSize.Bar)
            .widthIn(min = DashSize.Bar)
            .clip(RoundedCornerShape(50))
            .clickable(role = Role.Button, onClickLabel = description) { tap(); onClick() }
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = if (label != null) 14.dp else 0.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        if (label != null) {
            Spacer(Modifier.width(10.dp))
            AmLine(label, amText(18f, DashColors.TextPrimary, FontWeight.Normal))
        }
    }
}

/**
 * The OBD link as a dot of light and its letters: lit with a halo when live,
 * hollow when off, breathing while connecting, a "!" on error, so it reads
 * without its colour. What a tap does is the shared [ObdMark]'s.
 */
@Composable
private fun AmObdMark(state: ObdConnectionState, onConnect: () -> Unit) {
    val color = obdStatusColor(state)
    val pulse = if (state == ObdConnectionState.CONNECTING) rememberLoop(900, reverse = true, status = true) else null
    val halo = amGlow
    ObdMark(state, onConnect, shape = RoundedCornerShape(50)) {
        Row(Modifier.height(DashSize.Bar).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(
                Modifier
                    .size(20.dp)
                    .cachedDraw(state, color, halo, pulse) {
                        val r = 5.dp.toPx()
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val glow = Brush.radialGradient(listOf(color.copy(alpha = 0.6f * halo), Color.Transparent), c, r * 2.4f)
                        val ring = Stroke(1.5f * density)
                        onDrawBehind {
                            when (state) {
                                ObdConnectionState.CONNECTED -> {
                                    if (halo > 0f) drawCircle(glow, r * 2.4f, c)
                                    drawCircle(color, r, c)
                                }
                                ObdConnectionState.DISCONNECTED -> drawCircle(color, r - density, c, style = ring)
                                ObdConnectionState.CONNECTING -> drawCircle(color, r, c, alpha = 0.3f + 0.7f * (pulse?.value ?: 1f))
                                ObdConnectionState.ERROR -> drawCircle(color, r, c)
                            }
                        }
                    }
            )
            Spacer(Modifier.width(6.dp))
            AmLine(
                stringResource(R.string.dash_obd_short).uppercase(Locale.getDefault()),
                amText(15f, if (state == ObdConnectionState.DISCONNECTED) DashColors.TextSecondary else DashColors.TextPrimary, FontWeight.Normal, caps = true)
            )
            if (state == ObdConnectionState.ERROR) AmLine("!", amText(16f, color, FontWeight.Bold))
        }
    }
}

/**
 * Talk: a small pill of light ringed in the assistant's colours, glowing
 * softly. It opens Gemini Live (Dashwheel's own or Google's) when there is
 * one, else the unit's voice assistant. While a conversation is open it
 * says Listening beside its moving waves (whatever the effects, since it
 * then tells the driver something), and a tap ends it.
 */
@Composable
private fun AmTalkPill(compact: Boolean) {
    val context = LocalContext.current
    val gemini = remember { GeminiLive.available(context) }
    val active by GeminiLive.active.collectAsState()
    val light = DashColors.Light
    val effects = DashColors.Effects
    val glowK = amGlow
    val tap = rememberTapFeedback()
    val label = stringResource(if (active) R.string.ambient_listening else R.string.ambient_talk)
    val description = stringResource(if (active) R.string.ai_gemini_live_end else R.string.ambient_talk_cd)
    val wave = if (active) rememberLoop(900, reverse = true, status = true) else null
    val glowInk = DashColors.Accent2
    val rim = DashColors.TextSecondary
    Row(
        modifier = Modifier
            .height(52.dp)
            .widthIn(min = 64.dp)
            .cachedDraw(light, effects, glowK, active, glowInk, rim) {
                val w = size.width
                val h = size.height
                val r = CornerRadius(h / 2f)
                val plain = effects == DashEffects.NONE
                val ring: Brush = if (plain) SolidColor(rim) else Brush.horizontalGradient(Rainbow)
                val stroke = Stroke(1.5f * density)
                val fill = if (light) Color.White.copy(alpha = 0.72f) else Color.White.copy(alpha = if (active) 0.12f else 0.07f)
                val glowA = (if (active) 0.16f else 0.09f) * glowK
                onDrawBehind {
                    if (glowA > 0f) {
                        for (step in 1..3) {
                            val g = step * 5f * density
                            drawRoundRect(glowInk.copy(alpha = glowA), Offset(-g, -g), Size(w + 2f * g, h + 2f * g), CornerRadius(r.x + g))
                        }
                    }
                    drawRoundRect(fill, cornerRadius = r)
                    drawRoundRect(ring, Offset(stroke.width / 2f, stroke.width / 2f), Size(w - stroke.width, h - stroke.width), CornerRadius(r.x - stroke.width / 2f), alpha = if (active) 1f else 0.75f, style = stroke)
                }
            }
            .clip(RoundedCornerShape(50))
            .clickable(role = Role.Button, onClickLabel = description) {
                tap()
                if (gemini || active) GeminiLive.toggle(context) else startVoiceAssistant(context)
            }
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = if (compact && !active) 16.dp else 22.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (wave != null) AmWaves(wave, Modifier.size(width = 38.dp, height = 26.dp))
        else Icon(Icons.Outlined.Mic, contentDescription = null, tint = DashColors.TextPrimary, modifier = Modifier.size(24.dp))
        if (!compact || active) {
            Spacer(Modifier.width(10.dp))
            AmLine(label, amText(18f, DashColors.TextPrimary, FontWeight.Normal))
        }
    }
}

/** The listening waves: five round bars rising and falling out of step; only the drawing follows [t]. */
@Composable
private fun AmWaves(t: State<Float>, modifier: Modifier) {
    val ink = DashColors.TextPrimary
    Spacer(
        modifier.cachedDraw(ink) {
            val bars = 5
            val bw = 4f * density
            val gap = (size.width - bars * bw) / (bars - 1)
            onDrawBehind {
                val v = t.value
                for (i in 0 until bars) {
                    val x = i * (bw + gap) + bw / 2f
                    val k = 0.3f + 0.7f * abs(sin(PI.toFloat() * (v + i * 0.27f)))
                    val bh = size.height * k
                    drawLine(ink, Offset(x, (size.height - bh) / 2f + bw / 2f), Offset(x, (size.height + bh) / 2f - bw / 2f), bw, StrokeCap.Round)
                }
            }
        }
    )
}

// --- Window frame -----------------------------------------------------------------

/**
 * The frame over a docked app window: its corners rounded off in the page's
 * colour, a soft glow of the calm blue-violet breathing in from each edge
 * and a thin rim, so the map sits in the light like everything else. The
 * middle stays clear. Effects off: the corners and the rim alone.
 */
@Composable
internal fun AmbientWindowFrame(modifier: Modifier) {
    val light = DashColors.Light
    val effects = DashColors.Effects
    val page = DashColors.Background
    val ink = DashColors.Accent2
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawWithCache {
                val w = size.width
                val h = size.height
                val r = 26.dp.toPx()
                val edge = 24.dp.toPx().coerceAtMost(min(w, h) / 4f)
                val plain = effects == DashEffects.NONE
                val a = (if (light) 0.2f else 0.34f) * effects.scale.coerceAtLeast(0.6f)
                val glow = ink.copy(alpha = a)
                val mask = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(0f, 0f, w, h))
                    addRoundRect(RoundRect(Rect(0f, 0f, w, h), CornerRadius(r)))
                }
                val top = Brush.verticalGradient(listOf(glow, Color.Transparent), 0f, edge)
                val bottom = Brush.verticalGradient(listOf(Color.Transparent, glow), h - edge, h)
                val left = Brush.horizontalGradient(listOf(glow, Color.Transparent), 0f, edge)
                val right = Brush.horizontalGradient(listOf(Color.Transparent, glow), w - edge, w)
                val rimInk = ink.copy(alpha = if (light) 0.45f else 0.6f)
                val rim = Stroke(1.dp.toPx())
                val half = rim.width / 2f
                onDrawBehind {
                    drawPath(mask, page)
                    if (!plain) {
                        drawRect(top, Offset.Zero, Size(w, edge))
                        drawRect(bottom, Offset(0f, h - edge), Size(w, edge))
                        drawRect(left, Offset.Zero, Size(edge, h))
                        drawRect(right, Offset(w - edge, 0f), Size(edge, h))
                    }
                    drawRoundRect(rimInk, Offset(half, half), Size(w - 2f * half, h - 2f * half), CornerRadius(r - half), style = rim)
                }
            }
    )
}

// --- Theme stamp --------------------------------------------------------------------

/** The look as a stamp in the theme picker (ThemePane): the turn's warm glow on the left, the calm blue on the right, a thin 48. */
internal fun DrawScope.ambientStamp(p: DashPalette, number: StampNumber) {
    val w = size.width
    val h = size.height
    val big = max(w, h)
    val light = p.Light
    val warmC = Offset(-w * 0.04f, h * 0.55f)
    val warmR = big * 0.5f
    drawCircle(
        Brush.radialGradient(
            0f to (if (light) DayWarmCore else NightWarmCore).copy(alpha = if (light) 0.75f else 0.95f),
            0.35f to (if (light) DayWarmMid else NightWarmMid).copy(alpha = if (light) 0.4f else 0.6f),
            0.75f to Color.Transparent,
            center = warmC, radius = warmR
        ),
        warmR, warmC
    )
    val blueC = Offset(w * 0.96f, 0f)
    val blueR = big * 0.48f
    drawCircle(pool(if (light) DayBlue else NightBlue, if (light) 0.4f else 0.7f, blueC, blueR), blueR, blueC)
    val violetC = Offset(w * 0.8f, h * 1.15f)
    val violetR = big * 0.36f
    drawCircle(pool(if (light) DayViolet else NightViolet, if (light) 0.3f else 0.55f, violetC, violetR), violetR, violetC)
    number("48", w * 0.5f, h * 0.47f, h * 0.46f / density, p.TextPrimary)
}
