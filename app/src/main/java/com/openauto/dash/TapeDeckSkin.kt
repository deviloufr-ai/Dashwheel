package com.openauto.dash

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Eject
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/*
 * Tape Deck skin: an 80s synthwave head unit. A neon sunset with a rolling
 * perspective grid sits behind everything; the top bar is a chrome strip with a
 * VFD clock; speed, clock and fuel are drawn seven-segment digits; music plays
 * on a cassette with spinning reels and piano-key transport; directions glow on
 * a green phosphor CRT; weather is a neon sign; apps are radio preset buttons.
 * Everything is drawn on canvases with system fonts: no images, no resources.
 *
 * The light version is a Miami morning: a pastel sky with no stars, a pale
 * lilac ground, and silver hi-fi hardware with dark ink legends. The VFD clock
 * and the CRT are screens, so they stay dark in both.
 */

// ---------------------------------------------------------------- palette

// The core inks come from DashColors (Accent cyan, Accent2 magenta, Good green);
// these are the extra hardware colours of the mockup, as night / day pairs.
// DashColors.Light is snapshot state, so every read redraws on a switch.
private fun tone(night: Color, day: Color): Color = if (DashColors.Light) day else night

private val TdInk get() = tone(Color(0xFF0D0221), Color(0xFFE6DAF6))
private val TdSkyMid get() = tone(Color(0xFF261447), Color(0xFFFFDDEC))
private val TdSkyLow get() = tone(Color(0xFF541F63), Color(0xFFFFD4BA))
private val TdGround get() = tone(Color(0xFF2A0845), Color(0xFFF7ECFA))
private val TdMountain get() = tone(Color(0xFF1A0B2E), Color(0xFFD3BDEF))
private val TdYellow get() = tone(Color(0xFFFFD319), Color(0xFFFFB81F))
private val TdOrange get() = tone(Color(0xFFFF8A3D), Color(0xFFFF7D8C))
private val TdVfd = Color(0xFF05040A)
private val TdVfdRim get() = tone(Color(0xFF3B3A48), Color(0xFF9A9CAB))
private val TdChromeTop get() = tone(Color(0xFF2B2A35), Color(0xFFF5F6F9))
private val TdChromeBottom get() = tone(Color(0xFF121118), Color(0xFFC7CAD4))
private val TdPresetTop get() = tone(Color(0xFF34333F), Color(0xFFF8F9FB))
private val TdPresetBottom get() = tone(Color(0xFF1B1A22), Color(0xFFCDD0D9))
private val TdPresetSide get() = tone(Color(0xFF0A0910), Color(0xFFA2A5B3))
private val TdShell get() = tone(Color(0xFF1D1C24), Color(0xFFDADCE3))
private val TdHeadBlock get() = tone(Color(0xFF2A2933), Color(0xFFC2C5CF))
private val TdWindow get() = tone(Color(0xFF0B0A10), Color(0xFF2C2936))
private val TdWindowRim get() = tone(Color(0xFF555468), Color(0xFF8C8E9D))
private val TdLabel = Color(0xFFF2E8D5)
private val TdPurple = Color(0xFF7B2CBF)
private val TdTape = Color(0xFF4A3426)
private val TdHub = Color(0xFFEDEDED)
private val TdKeyTop get() = tone(Color(0xFFF1F1F4), Color(0xFFFAFAFC))
private val TdKeyBottom get() = tone(Color(0xFFA9A9B6), Color(0xFFCACBD5))
private val TdKeySide get() = tone(Color(0xFF5B5B6B), Color(0xFF9B9DAC))
private val TdPhosphor = Color(0xFF33FF99)
private val TdCrtCentre = Color(0xFF06281C)
private val TdCrtEdge = Color(0xFF021009)
private val TdBezelTop get() = tone(Color(0xFF221C2C), Color(0xFFF1F2F6))
private val TdBezel get() = tone(Color(0xFF15121C), Color(0xFFD5D7DF))
private val TdBezelBottom get() = tone(Color(0xFF0F0C15), Color(0xFFB6B9C5))
private val TdBezelRim get() = tone(Color(0xFF2E2940), Color(0xFFA4A7B5))
private val TdBezelOuter get() = tone(Color(0xFF0A0812), Color(0xFF8E91A0))

/** Behind the LED readouts: the black VFD glass at night, a pearl panel by day. */
private val TdPanel get() = tone(TdVfd, Color(0xFFFFFBFD))

/** The same glass behind an alert (AlertChrome.kt). */
internal val TapeDeckGlass: Color get() = TdPanel

/** Ink printed on the cassette label and the white keys: the shell colour at night, deep purple by day. */
private val TdPrint get() = if (DashColors.Light) DashColors.TextPrimary else TdShell

/** Legends on the top bar pills and presets: neon cyan at night, dark ink on the silver by day. */
private val TdLegend get() = if (DashColors.Light) DashColors.TextPrimary else DashColors.Accent

/** Cyan lit on the dark VFD clock: the accent at night; by day that accent is deepened for pale panels, so a brighter tube. */
private val TdVfdCyan get() = if (DashColors.Light) Color(0xFF2BE3F0) else DashColors.Accent

/** Yellow lettering: the LED yellow at night, a deeper amber that reads on the pearl panels by day. */
private val TdYellowText get() = if (DashColors.Light) Color(0xFFBF7200) else TdYellow

/** Day only: the morning sun's creamy halo. */
private val TdSunGlow = Color(0xD9FFF0C0)

/** Day only: the purple-grey of soft shadows under the silver hardware. */
private val TdShade = Color(0xFF3B2A5C)

/** Drop shadow under hardware at [alpha]: black at night, a softer purple-grey by day. */
private fun tdShadow(alpha: Float): Color =
    if (DashColors.Light) TdShade.copy(alpha = alpha * 0.45f) else Color.Black.copy(alpha = alpha)

/** Gradient end fading [c] out: transparent black at night; [c] at zero alpha by day, as a fade to black greys a pale page. */
private fun fadeOf(c: Color): Color = if (DashColors.Light) c.copy(alpha = 0f) else Color.Transparent

private val TOP_BAR_HEIGHT = 64.dp
private val KEY_DEPTH = 8.dp
private val KEY_TRAVEL = 5.dp
private val PRESET_DEPTH = 6.dp
private val PRESET_TRAVEL = 3.dp
private val CRT_BEZEL = 10.dp

private const val RPM_LEDS = 24
private const val FUEL_LEDS = 20
private const val REEL_SPIN_MS = 2_400
private const val SPECTRUM_LOOP_MS = 4_000

// ---------------------------------------------------------------- public contract

/**
 * Whole-screen synthwave backdrop: starry gradient sky, a striped sun on the
 * horizon, wireframe mountains, a dark ground and a perspective grid whose
 * cross lines roll towards the viewer. By day: a starless pastel sky, lilac
 * hills and a pale ground with cyan rails. Everything between the stars and
 * the rolling cross lines is recorded once per size and appearance into an
 * offscreen layer; each step of the ambient ticker (about 20 a second, none
 * with effects off) only redraws the sky, the star twinkle and the cross lines
 * around it.
 */
@Composable
internal fun tapeDeckBackground(): Modifier {
    val loop = rememberLoop(BG_LOOP_MS)
    val cyan = DashColors.Accent
    val magenta = DashColors.Accent2
    val light = DashColors.Light
    return remember(cyan, magenta, light, loop) {
        Modifier.drawWithCache {
            val w = size.width
            val h = size.height
            val horizon = h * HORIZON
            val ground = h - horizon
            val sx = w / 1280f
            val sy = h / 720f
            fun poly(c: FloatArray, close: Boolean) = Path().apply {
                moveTo(c[0] * sx, horizon + (c[1] - 400f) * sy)
                for (i in 2 until c.size step 2) lineTo(c[i] * sx, horizon + (c[i + 1] - 400f) * sy)
                if (close) close()
            }

            // Day: blooms at half strength (they barely show on a pale page), lines a
            // little lighter, and cyan rails under the magenta cross lines.
            val glow = if (light) 0.5f else 1f
            val ink = if (light) 0.75f else 1f
            val rail = if (light) cyan else magenta

            val sky = Brush.verticalGradient(0f to TdInk, 0.6f to TdSkyMid, 1f to TdSkyLow, startY = 0f, endY = horizon)
            val stars = STARS.map { group -> group.map { Offset(it.x * w, it.y * horizon) } }
            val starWidths = floatArrayOf(1.5.dp.toPx(), 2.1.dp.toPx(), 2.8.dp.toPx())

            val sunR = 0.195f * h
            val sunC = Offset(w / 2f, horizon - 0.028f * h)
            val cuts = Path().apply {
                for (i in SUN_CUTS.indices step 2) {
                    val top = horizon - SUN_CUTS[i] * sunR
                    addRect(Rect(0f, top, w, top + SUN_CUTS[i + 1] * sunR))
                }
            }
            val disc = Path().apply { addOval(Rect(sunC, sunR)) }
            val sun = difference(disc, cuts, fallback = disc)
            val sunFill = Brush.verticalGradient(
                0f to TdYellow, 0.5f to TdOrange, 1f to magenta, startY = sunC.y - sunR, endY = horizon
            )
            val haloR = sunR * 1.8f
            val haloInk = if (light) TdSunGlow else magenta.copy(alpha = 0.45f)
            val halo = Brush.radialGradient(
                0.45f to haloInk, 1f to fadeOf(haloInk), center = sunC, radius = haloR
            )

            val mountain = TdMountain
            val hillGlowInk = magenta.copy(alpha = 0.22f * glow)
            val ridgeInk = magenta.copy(alpha = 0.45f * ink)
            val hillFill = Path().apply { HILLS.forEach { addPath(poly(it, close = true)) } }
            val hillEdge = Path().apply { HILLS.forEach { addPath(poly(it, close = false)) } }
            val ridges = Path().apply {
                for (i in RIDGES.indices step 4) {
                    moveTo(RIDGES[i] * sx, horizon + (RIDGES[i + 1] - 400f) * sy)
                    lineTo(RIDGES[i + 2] * sx, horizon + (RIDGES[i + 3] - 400f) * sy)
                }
            }
            val hillStroke = Stroke(2.dp.toPx(), join = StrokeJoin.Round)
            val hillGlow = Stroke(7.dp.toPx(), join = StrokeJoin.Round)
            val ridgeStroke = Stroke(1.5.dp.toPx())

            val groundFill = Brush.verticalGradient(listOf(TdGround, TdInk), startY = horizon, endY = h)
            val verticals = Path().apply {
                for (k in -18..18) {
                    moveTo(w / 2f + k * 16f * sx, horizon)
                    lineTo(w / 2f + k * 150f * sx, h)
                }
            }
            val verticalInk = Brush.verticalGradient(
                0f to rail.copy(alpha = 0.12f * ink), 1f to rail.copy(alpha = 0.75f * ink), startY = horizon, endY = h
            )
            val gridStroke = Stroke(1.5.dp.toPx())
            val thin = 1.5.dp.toPx()
            val wide = 5.dp.toPx()
            val crossGlow = 0.22f * glow
            val crossInk = 0.8f * ink
            val bloomH = 14.dp.toPx()
            val bloom = Brush.verticalGradient(
                0f to fadeOf(magenta), 0.5f to magenta.copy(alpha = 0.55f * glow), 1f to fadeOf(magenta),
                startY = horizon - bloomH, endY = horizon + bloomH
            )

            // The sun, hills, ground and rails never move: rendered once, then only composited.
            val scenery = obtainGraphicsLayer().apply { compositingStrategy = CompositingStrategy.Offscreen }
            scenery.record {
                drawCircle(halo, radius = haloR, center = sunC)
                drawPath(sun, sunFill)
                drawPath(hillFill, mountain)
                drawPath(hillEdge, hillGlowInk, style = hillGlow)
                drawPath(hillEdge, magenta, style = hillStroke)
                drawPath(ridges, ridgeInk, style = ridgeStroke)
                drawRect(groundFill, topLeft = Offset(0f, horizon), size = Size(w, ground))
                drawPath(verticals, verticalInk, style = gridStroke)
            }

            onDrawBehind {
                val t = loop.value
                drawRect(sky, size = Size(w, horizon))
                if (!light) {
                    stars.forEachIndexed { k, group ->
                        val a = 0.3f + 0.55f * (0.5f + 0.5f * sin((t * 6f + k / 3f) * 2f * PI.toFloat()))
                        drawPoints(group, PointMode.Points, Color.White.copy(alpha = a), starWidths[k], StrokeCap.Round)
                    }
                }
                drawLayer(scenery)
                // Cross lines sit at depths 1, 2, 3... that slide towards the
                // viewer; y = horizon + ground / depth gives the perspective spacing.
                val roll = (t * GRID_ROLLS_PER_LOOP) % 1f
                for (i in 0 until GRID_DEPTHS) {
                    val y = horizon + ground / (i + 1f - roll)
                    if (y > h) continue
                    val fade = ((y - horizon) / (ground * 0.35f)).coerceIn(0.08f, 1f)
                    drawLine(magenta.copy(alpha = crossGlow * fade), Offset(0f, y), Offset(w, y), wide)
                    drawLine(magenta.copy(alpha = crossInk * fade), Offset(0f, y), Offset(w, y), thin)
                }
                drawRect(bloom, topLeft = Offset(0f, horizon - bloomH), size = Size(w, bloomH * 2f))
                drawRect(magenta, topLeft = Offset(0f, horizon - thin), size = Size(w, thin * 2f))
            }
        }
    }
}

/**
 * Chrome strip top bar: the gradient DASHWHEEL logo, APPS and LAYOUT pills on
 * the left, a VFD clock in the centre, the setup pill, the OBD LED, outside
 * temperature, alert chips and the ⋮ pill on the right, over a glowing magenta rule. By day the
 * strip is brushed aluminium with dark ink legends.
 */
@Composable
internal fun TapeDeckTopBar(m: TopBarModel) {
    val magenta = DashColors.Accent2
    val legend = TdLegend
    val light = DashColors.Light
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(TOP_BAR_HEIGHT)
            // Own layer, so the animated page background redrawing each step
            // does not re-record the bar (no clip: its glow spills below it).
            .graphicsLayer()
            .drawWithCache {
                val chrome = Brush.verticalGradient(listOf(TdChromeTop, TdChromeBottom))
                val rule = 2.dp.toPx()
                val glowH = 16.dp.toPx()
                val glow = Brush.verticalGradient(
                    listOf(magenta.copy(alpha = if (light) 0.28f else 0.45f), fadeOf(magenta)),
                    startY = size.height, endY = size.height + glowH
                )
                val grain = if (light) brushedGrain(size) else null
                val edge = Color.White.copy(alpha = if (light) 0.9f else 0.10f)
                onDrawBehind {
                    drawRect(glow, topLeft = Offset(0f, size.height), size = Size(size.width, glowH))
                    drawRect(chrome)
                    if (grain != null) drawPath(grain, Color.White.copy(alpha = 0.4f))
                    drawLine(edge, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1f)
                    drawRect(magenta, topLeft = Offset(0f, size.height - rule), size = Size(size.width, rule))
                }
            }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        // Narrow screens drop the logo so the pills never run into the clock.
        val showLogo = maxWidth >= 980.dp
        val narrow = maxWidth < NARROW_BAR
        val shared = barIsShared()
        Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            // Each side gets half of what the clock leaves, so the clock stays
            // centred and nothing prints over it. An upright screen, or half of a
            // split one, has no room for that: the pills go down to their icons
            // and take only what they need, like the standard bar's.
            Row(
                modifier = if (narrow) Modifier else Modifier.weight(1f).wrapContentWidth(Alignment.Start, unbounded = true),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showLogo) {
                    TapeLogo()
                    Spacer(Modifier.width(18.dp))
                }
                NeonPill(if (narrow) null else stringResource(R.string.tape_apps_caps), stringResource(R.string.tape_cd_all_apps), m.onApps) {
                    Icon(Icons.Filled.Apps, contentDescription = null, tint = legend, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(6.dp))
                LayoutPicker(m) { open ->
                    NeonPill(
                        if (narrow) null else stringResource(R.string.tape_layout_caps),
                        stringResource(R.string.tape_cd_screen_layout, m.layout.title),
                        open
                    ) {
                        LayoutIcon(m.layout, null, legend, Modifier.size(16.dp))
                    }
                }
            }

            // The head unit's status bar shows the time while it is up.
            if (!m.merged) Box(Modifier.padding(horizontal = 10.dp)) { VfdClock(m.clock) }

            // ⋮ and the OBD LED get their room first; what ranks last and no
            // longer fits beside the clock is left out (the outside temperature
            // first: the page's weather tile says the same).
            BarEnd(modifier = Modifier.weight(1f)) {
                if (m.setupPending) {
                    Box(Modifier.layoutId(BarRank.SETUP).padding(end = 6.dp)) {
                        SetupPill(onClick = { m.onSetup(false) }, compact = narrow || shared)
                    }
                }
                Box(Modifier.layoutId(BarRank.OBD)) { ObdLed(m.obdConnection, m.onConnectObd) }
                if (!narrow) Box(Modifier.layoutId(BarRank.TEMP)) { OutsideTemp() }
                Row(modifier = Modifier.layoutId(BarRank.ALERTS), verticalAlignment = Alignment.CenterVertically) {
                    VehicleAlerts(m.obdConnection, m.obd)
                }
                Box(Modifier.layoutId(BarRank.PHONE)) { PhonePill() }
                Box(Modifier.layoutId(BarRank.MORE)) {
                    MorePicker(m) { open ->
                        NeonPill(null, stringResource(R.string.tape_cd_more), open) {
                            Icon(Icons.Filled.MoreVert, contentDescription = null, tint = legend, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Draws the skinned widgets, app presets and launch bars; everything else keeps its standard renderer. */
@Composable
internal fun TapeDeckTile(item: DashboardItem, env: SkinTileEnv) {
    when (item) {
        is DashboardItem.AppShortcut -> TapePreset(item, env)
        is DashboardItem.LaunchBar -> TapePresetBar(item, env)
        is DashboardItem.BuiltinWidget -> when (item.kind) {
            BuiltinKind.TELEMETRY -> TapeTelemetry(env)
            BuiltinKind.SPEED_HUD -> TapeSpeedHud(env)
            BuiltinKind.MEDIA -> TapeMedia(env)
            BuiltinKind.NAVIGATION -> TapeNavigation(env)
            BuiltinKind.CLOCK -> TapeClock(env)
            BuiltinKind.WEATHER -> TapeWeather()
            BuiltinKind.RANGE -> TapeRange(item, env)
            else -> StandardSkinnedTile(item, env)
        }
        else -> StandardSkinnedTile(item, env)
    }
}

/**
 * A CRT monitor around a docked Maps window: page-coloured masks outside the
 * rounded plastic bezel, the bezel itself, a bulged elliptical screen edge,
 * scanlines and a vignette. By day the bezel is silver-grey and the scanlines
 * and vignette lighter, so a daytime map stays readable. The middle stays
 * see-through; nothing animates.
 */
@Composable
internal fun TapeDeckWindowFrame(modifier: Modifier) {
    val mask = DashColors.Background
    val led = DashColors.Good
    val light = DashColors.Light
    Spacer(
        modifier = modifier
            .fillMaxSize()
            .drawWithCache {
                val w = size.width
                val h = size.height
                val bezel = CRT_BEZEL.toPx()
                if (w <= bezel * 4f || h <= bezel * 4f) return@drawWithCache onDrawBehind { }
                val outerR = CornerRadius(18.dp.toPx())
                val screenRect = Rect(bezel, bezel, w - bezel, h - bezel)
                val rx = min(30.dp.toPx(), screenRect.width * 0.2f)
                val ry = min(44.dp.toPx(), screenRect.height * 0.2f)
                val outer = Path().apply { addRoundRect(RoundRect(0f, 0f, w, h, outerR)) }
                val screen = Path().apply { addRoundRect(RoundRect(screenRect, CornerRadius(rx, ry))) }
                val corners = difference(Path().apply { addRect(Rect(0f, 0f, w, h)) }, outer, fallback = Path())
                val plastic = difference(outer, screen, fallback = Path())
                val plasticShade = Brush.verticalGradient(
                    0f to Color.White.copy(alpha = if (light) 0.5f else 0.07f), 0.5f to fadeOf(Color.White), 1f to tdShadow(0.25f)
                )
                val scanInk = Color.Black.copy(alpha = if (light) 0.12f else 0.25f)
                val scan = scanlines(screenRect)
                val vignetteR = screenRect.height * 0.75f
                val vignette = Brush.radialGradient(
                    0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = if (light) 0.3f else 0.5f),
                    center = screenRect.center, radius = vignetteR
                )
                val lip = Stroke(2.dp.toPx())
                val edge = Stroke(2.dp.toPx())
                val ledC = Offset(w - bezel - rx * 0.4f, h - bezel / 2f)
                val ledR = 2.5.dp.toPx()
                val body = TdBezel
                val lipInk = TdBezelRim
                val edgeInk = TdBezelOuter
                onDrawBehind {
                    clipPath(screen) {
                        drawPath(scan, scanInk)
                        scale(scaleX = screenRect.width / screenRect.height, scaleY = 1f, pivot = screenRect.center) {
                            drawCircle(vignette, radius = vignetteR, center = screenRect.center)
                        }
                    }
                    drawPath(corners, mask)
                    drawPath(plastic, body)
                    drawPath(plastic, plasticShade)
                    drawPath(screen, lipInk, style = lip)
                    drawRoundRect(edgeInk, cornerRadius = outerR, style = edge)
                    drawCircle(led.copy(alpha = 0.35f), radius = ledR * 2.4f, center = ledC)
                    drawCircle(led, radius = ledR, center = ledC)
                }
            }
    )
}

// ---------------------------------------------------------------- background data

private const val BG_LOOP_MS = 14_000
private const val GRID_ROLLS_PER_LOOP = 10
private const val GRID_DEPTHS = 16
private const val HORIZON = 0.55f

/** Sun stripe cut-outs: distance above the horizon and height, in sun radii (from the mockup). */
private val SUN_CUTS = floatArrayOf(0.557f, 0.029f, 0.414f, 0.043f, 0.271f, 0.057f, 0.129f, 0.071f)

/** Mountain outlines in the 1280x720 mockup frame (horizon at y = 400). */
private val HILLS = listOf(
    floatArrayOf(0f, 400f, 60f, 330f, 120f, 360f, 190f, 290f, 260f, 350f, 330f, 310f, 400f, 400f),
    floatArrayOf(880f, 400f, 950f, 320f, 1010f, 355f, 1090f, 285f, 1160f, 340f, 1220f, 300f, 1280f, 330f, 1280f, 400f)
)

/** Inner ridge lines, x0 y0 x1 y1 each. */
private val RIDGES = floatArrayOf(
    190f, 290f, 230f, 400f, 190f, 290f, 150f, 400f, 60f, 330f, 82f, 400f, 330f, 310f, 352f, 400f,
    1090f, 285f, 1050f, 400f, 1090f, 285f, 1130f, 400f, 950f, 320f, 920f, 400f, 1220f, 300f, 1196f, 400f
)

/** Three twinkle groups of stars, x and y as fractions of the width and the sky height. */
private val STARS: List<List<Offset>> = Random(11).let { rnd ->
    List(3) { List(18) { Offset(rnd.nextFloat(), 0.12f + rnd.nextFloat() * 0.76f) } }
}

// ---------------------------------------------------------------- shared bits

/**
 * VFD / terminal lettering: monospace glowing in its own colour. On the light
 * page the glow is tight and faint (a bloom washes out on pastel), unless the
 * text sits [onScreen], a display that stays dark in both modes.
 */
private fun vfdText(
    color: Color,
    size: TextUnit,
    glow: Boolean = true,
    weight: FontWeight = FontWeight.Normal,
    onScreen: Boolean = false
) = TextStyle(
    color = color,
    fontSize = size,
    lineHeight = size * 1.15f,
    fontFamily = FontFamily.Monospace,
    fontWeight = weight,
    shadow = when {
        !glow -> null
        onScreen || !DashColors.Light -> softTextShadow(color.copy(alpha = 0.75f), size.value * 0.9f)
        else -> softTextShadow(color.copy(alpha = 0.3f), size.value * 0.4f)
    }
)

/** Chrome lettering: bold, wide-spaced sans; its optional glow is tight and faint by day. */
private fun chromeText(color: Color, size: TextUnit, glow: Boolean = false) = TextStyle(
    color = color,
    fontSize = size,
    lineHeight = size * 1.15f,
    fontFamily = FontFamily.SansSerif,
    fontWeight = FontWeight.Bold,
    letterSpacing = 0.16.em,
    shadow = when {
        !glow -> null
        DashColors.Light -> softTextShadow(color.copy(alpha = 0.3f), size.value * 0.4f)
        else -> softTextShadow(color.copy(alpha = 0.7f), size.value)
    }
)

/** Tube / CRT flicker: alpha 1 most of the time, with a short stutter every few seconds (steady with effects off). */
@Composable
private fun rememberFlicker(): State<Float> {
    val alpha = remember { mutableFloatStateOf(1f) }
    val still = DashColors.Effects == DashEffects.NONE
    LaunchedEffect(still) {
        alpha.floatValue = 1f
        if (still) return@LaunchedEffect
        val rnd = Random(System.nanoTime())
        while (true) {
            delay(3_500L + rnd.nextLong(4_500L))
            alpha.floatValue = 0.78f
            delay(60L)
            alpha.floatValue = 1f
            delay(110L)
            alpha.floatValue = 0.88f
            delay(50L)
            alpha.floatValue = 1f
        }
    }
    return alpha
}

/** Text that blinks between full and [offAlpha] (changes only its layer alpha). */
@Composable
private fun BlinkingText(text: String, style: TextStyle, periodMs: Long = 600L, offAlpha: Float = 0.2f) {
    val blink = rememberBlink(periodMs)
    Text(
        text,
        style = style,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.graphicsLayer { alpha = if (blink.value) 1f else offAlpha }
    )
}

/**
 * A VFD window with a neon rim that glows outward in falling-alpha strokes:
 * black glass with a faint sheen at night, a flat pearl panel by day. Cached
 * across recompositions (a live readout recomposes on every sample).
 */
@Composable
private fun Modifier.vfdPanel(rim: Color, corner: Dp = 14.dp): Modifier = cachedDraw(rim, corner) {
    val r = CornerRadius(corner.toPx())
    val line = 1.5.dp.toPx()
    val halos = Array(3) { Stroke(line + (3 - it) * 4.dp.toPx()) }
    val haloInk = rim.copy(alpha = 0.07f)
    val panel = TdPanel
    val sheen = if (DashColors.Light) null else {
        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.05f), Color.Transparent), endY = size.height * 0.4f)
    }
    val edge = Stroke(line)
    onDrawBehind {
        for (halo in halos) drawRoundRect(haloInk, cornerRadius = r, style = halo)
        drawRoundRect(panel, cornerRadius = r)
        if (sheen != null) drawRoundRect(sheen, cornerRadius = r)
        drawRoundRect(rim, cornerRadius = r, style = edge)
    }
}

/**
 * Recessed black display window (the top bar clock), dark in both modes; by
 * day a white bevel under its lower edge sinks it into the silver strip.
 */
private val VfdInset = Modifier.drawWithCache {
    val r = CornerRadius(8.dp.toPx())
    val bevel = DashColors.Light
    val lift = Offset(0f, 1.dp.toPx())
    val glass = Brush.verticalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.06f)), startY = size.height * 0.6f)
    val rimInk = TdVfdRim
    val edge = Stroke(1.dp.toPx())
    onDrawBehind {
        if (bevel) drawRoundRect(Color.White.copy(alpha = 0.85f), topLeft = lift, size = size, cornerRadius = r)
        drawRoundRect(TdVfd, cornerRadius = r)
        drawRoundRect(glass, cornerRadius = r)
        drawRoundRect(rimInk, cornerRadius = r, style = edge)
    }
}

/** [a] minus [b], or [fallback] if Skia's path ops refuse (never expected for these simple shapes). */
private fun difference(a: Path, b: Path, fallback: Path): Path =
    runCatching { Path.combine(PathOperation.Difference, a, b) }.getOrDefault(fallback)

/** 2 px dark lines every 4 px across [area], as one path. */
private fun scanlines(area: Rect): Path = Path().apply {
    var y = area.top
    while (y < area.bottom) {
        addRect(Rect(area.left, y, area.right, min(y + 2f, area.bottom)))
        y += 4f
    }
}

/** Brushed-aluminium grain across [area]: 1 px streaks of random length and spacing (fixed seed, so it never shimmers). */
private fun brushedGrain(area: Size): Path = Path().apply {
    val rnd = Random(5)
    var y = 1f
    while (y < area.height) {
        val x = rnd.nextFloat() * area.width * 0.6f
        addRect(Rect(x, y, min(area.width, x + area.width * (0.2f + rnd.nextFloat() * 0.6f)), y + 1f))
        y += 2f + rnd.nextFloat() * 3f
    }
}

/** A row of LED segments, the first [lit] of [count] on (with a halo), the rest as dim ghosts. */
@Composable
private fun LedBar(lit: Int, count: Int, modifier: Modifier, colorAt: (Int) -> Color) {
    val ghost = DashColors.haze(0.08f)
    Spacer(
        modifier.drawBehind {
            val gap = min(3.dp.toPx(), size.width / count * 0.3f)
            val segW = (size.width - gap * (count - 1)) / count
            val r = CornerRadius(2.dp.toPx())
            val halo = 2.dp.toPx()
            for (i in 0 until count) {
                val x = i * (segW + gap)
                if (i < lit) {
                    val c = colorAt(i)
                    drawRoundRect(
                        c.copy(alpha = 0.3f), topLeft = Offset(x - halo, -halo),
                        size = Size(segW + halo * 2f, size.height + halo * 2f), cornerRadius = r
                    )
                    drawRoundRect(c, topLeft = Offset(x, 0f), size = Size(segW, size.height), cornerRadius = r)
                } else {
                    drawRoundRect(ghost, topLeft = Offset(x, 0f), size = Size(segW, size.height), cornerRadius = r)
                }
            }
        }
    )
}

// ---------------------------------------------------------------- seven-segment digits

// Geometry of one digit cell, from the mockup's seg_digit(): 64 x 116 units,
// 12-unit hexagonal segments with 2-unit gaps, 20 units between digits.
private const val SEG_W = 64f
private const val SEG_H = 116f
private const val SEG_T = 12f
private const val SEG_GAP = 2f
private const val SEG_SPACE = 20f
private const val SEG_COLON_W = 14f
private const val SEG_SKEW = 0.105f // tan 6°: the italic lean

/** Lit segments a..g (bits 0..6) for the digits 0..9. */
private val DIGIT_MASKS = intArrayOf(63, 6, 91, 79, 102, 109, 125, 7, 127, 111)

private fun segMask(c: Char): Int = c.digitToIntOrNull()?.let { DIGIT_MASKS[it] } ?: if (c == '-') 64 else 0

private fun segGap(text: String, i: Int): Float =
    if (text[i] == ':' || text[i - 1] == ':') SEG_SPACE * 0.5f else SEG_SPACE

/** Width of [text] in segment units (digit height = [SEG_H]), including the italic overhang. */
private fun segUnits(text: String): Float {
    if (text.isEmpty()) return 0f
    var w = 0f
    text.forEachIndexed { i, c ->
        if (i > 0) w += segGap(text, i)
        w += if (c == ':') SEG_COLON_W else SEG_W
    }
    return w + SEG_SKEW * SEG_H
}

/** A closed polygon from unit-space x,y pairs, scaled by [s] with the italic skew baked in. */
private fun segPoly(s: Float, vararg p: Float): Path = Path().apply {
    for (i in p.indices step 2) {
        val x = (p[i] + SEG_SKEW * (SEG_H - p[i + 1])) * s
        val y = p[i + 1] * s
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/** The seven hexagonal segments a..g of one digit cell at scale [s]. */
private fun segmentPaths(s: Float): List<Path> {
    val k = SEG_T / 2f
    val lx = k
    val rx = SEG_W - k
    val top = k
    val mid = SEG_H / 2f
    val bot = SEG_H - k
    val g = SEG_GAP
    fun hseg(y: Float, x0: Float, x1: Float) =
        segPoly(s, x0, y, x0 + k, y - k, x1 - k, y - k, x1, y, x1 - k, y + k, x0 + k, y + k)
    fun vseg(x: Float, y0: Float, y1: Float) =
        segPoly(s, x, y0, x + k, y0 + k, x + k, y1 - k, x, y1, x - k, y1 - k, x - k, y0 + k)
    return listOf(
        hseg(top, lx + g, rx - g),
        vseg(rx, top + g, mid - g),
        vseg(rx, mid + g, bot - g),
        hseg(bot, lx + g, rx - g),
        vseg(lx, mid + g, bot - g),
        vseg(lx, top + g, mid - g),
        hseg(mid, lx + g, rx - g)
    )
}

/** The two square dots of a colon cell at scale [s]. */
private fun colonPaths(s: Float): List<Path> = listOf(0.32f, 0.70f).map { fy ->
    val cy = SEG_H * fy
    val cx = SEG_COLON_W / 2f
    val r = SEG_T * 0.55f
    segPoly(s, cx - r, cy - r, cx + r, cy - r, cx + r, cy + r, cx - r, cy + r)
}

private fun DrawScope.drawSegment(path: Path, lit: Boolean, color: Color, ghost: Color, glow: Stroke, glowAlpha: Float) {
    if (lit) {
        drawPath(path, color.copy(alpha = glowAlpha), style = glow)
        drawPath(path, color)
    } else {
        drawPath(path, ghost)
    }
}

/**
 * Seven-segment LED digits, [height] tall, drawn on a canvas: digits, ' ' (a
 * blank showing all-ghost segments), '-' and ':'. Unlit segments stay as faint
 * ghosts; lit ones get a wide low-alpha glow pass under the solid pass, fainter
 * on the light page unless the digits sit [onScreen] (a display dark in both
 * modes). The colon lights while [colonOn] returns true (read at draw time, so
 * a blinking colon only redraws). The segment paths depend on the height
 * alone, so a new value only redraws them.
 */
@Composable
private fun SevenSegment(
    text: String,
    height: Dp,
    color: Color,
    modifier: Modifier = Modifier,
    onScreen: Boolean = false,
    colonOn: () -> Boolean = { true }
) {
    val ghost = color.copy(alpha = 0.08f)
    val glowAlpha = if (onScreen || !DashColors.Light) 0.28f else 0.12f
    val geometry = remember { SegGeometry() }
    Spacer(
        modifier
            .size(height * (segUnits(text) / SEG_H), height)
            .drawBehind {
                val s = size.height / SEG_H
                geometry.fit(s)
                val segs = geometry.segs
                val dots = geometry.dots
                val glow = geometry.glow
                var x = 0f
                text.forEachIndexed { i, c ->
                    if (i > 0) x += segGap(text, i) * s
                    if (c == ':') {
                        val lit = colonOn()
                        translate(left = x) { dots.forEach { drawSegment(it, lit, color, ghost, glow, glowAlpha) } }
                        x += SEG_COLON_W * s
                    } else {
                        val mask = segMask(c)
                        translate(left = x) {
                            segs.forEachIndexed { k, p ->
                                drawSegment(p, mask and (1 shl k) != 0, color, ghost, glow, glowAlpha)
                            }
                        }
                        x += SEG_W * s
                    }
                }
            }
    )
}

/** One digit size's segment and colon paths and glow stroke, rebuilt only when the scale [fit] is given changes. */
private class SegGeometry {
    private var scale = -1f
    var segs: List<Path> = emptyList()
        private set
    var dots: List<Path> = emptyList()
        private set
    var glow = Stroke(0f)
        private set

    fun fit(s: Float) {
        if (s == scale) return
        scale = s
        segs = segmentPaths(s)
        dots = colonPaths(s)
        glow = Stroke(width = SEG_T * s * 0.9f, join = StrokeJoin.Round)
    }
}

/** Three-digit speed with ghost 8s in the unused places; all ghosts when there is no reading. */
private fun speedDigits(speed: Int?): String = speed?.coerceIn(0, 999)?.toString()?.padStart(3, ' ') ?: "   "

/**
 * Seven-segment [digits] with a [unit] beside them and an optional [label]
 * above, scaled to fill the box (width- or height-bound, whichever is tighter).
 */
@Composable
private fun SegValue(
    label: String?,
    digits: String,
    unit: String,
    color: Color,
    labelSize: TextUnit,
    modifier: Modifier = Modifier
) {
    val magenta = DashColors.Accent2
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val ratio = segUnits(digits) / SEG_H
        val labelH = if (label != null) (labelSize.value * 1.3f + 4f).dp else 0.dp
        val rough = minOf(maxHeight - labelH, maxWidth / (ratio + 0.6f))
        val unitSize = (rough.value * 0.17f).coerceIn(14f, 30f).sp
        val unitW = (unitSize.value * 0.8f * unit.length + 8f).dp
        val digitH = minOf(maxHeight - labelH, (maxWidth - unitW) / ratio).coerceAtLeast(12.dp)
        Column {
            if (label != null) {
                Text(label, style = vfdText(magenta, labelSize, glow = false), maxLines = 1)
                Spacer(Modifier.height(4.dp))
            }
            Row(verticalAlignment = Alignment.Bottom) {
                SevenSegment(digits, digitH, color)
                Spacer(Modifier.width(8.dp))
                Text(unit, style = chromeText(color, unitSize, glow = true), maxLines = 1, softWrap = false)
            }
        }
    }
}

// ---------------------------------------------------------------- top bar parts

/** DASHWHEEL in bold wide letters with a cyan to magenta gradient: glowing at night, stamped into the silver by day. */
@Composable
private fun TapeLogo() {
    val cyan = DashColors.Accent
    val magenta = DashColors.Accent2
    Text(
        "DASHWHEEL",
        maxLines = 1,
        style = TextStyle(
            brush = Brush.horizontalGradient(listOf(cyan, magenta)),
            fontSize = 20.sp,
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.18.em,
            shadow = if (DashColors.Light) {
                Shadow(Color.White.copy(alpha = 0.9f), offset = Offset(0f, 1.5f), blurRadius = 1f)
            } else {
                softTextShadow(magenta.copy(alpha = 0.55f), 14f)
            }
        )
    )
}

/**
 * Cyan-outlined pill button (icon, optional label) in a 56 dp touch target;
 * lights up while pressed. Dark inside at night, a raised pale pill with an
 * ink legend on the silver strip by day.
 */
@Composable
private fun NeonPill(label: String?, description: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
    val cyan = DashColors.Accent
    val legend = TdLegend
    val idle = if (DashColors.Light) Color.White.copy(alpha = 0.55f) else Color.Black.copy(alpha = 0.3f)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .heightIn(min = DashSize.TouchPrimary)
            .widthIn(min = DashSize.TouchPrimary)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .height(40.dp)
                .cachedDraw(cyan, idle, interaction) {
                    val r = CornerRadius(size.height / 2f)
                    val line = 1.5.dp.toPx()
                    val halo = Stroke(line + 6.dp.toPx())
                    val edge = Stroke(line)
                    onDrawBehind {
                        drawRoundRect(cyan.copy(alpha = if (pressed) 0.3f else 0.12f), cornerRadius = r, style = halo)
                        drawRoundRect(if (pressed) cyan.copy(alpha = 0.25f) else idle, cornerRadius = r)
                        drawRoundRect(cyan, cornerRadius = r, style = edge)
                    }
                }
                .padding(horizontal = if (label != null) 14.dp else 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon()
            if (label != null) {
                Spacer(Modifier.width(8.dp))
                Text(label, style = chromeText(legend, 14.sp), maxLines = 1)
            }
        }
    }
}

/**
 * The bar's clock: [clock] in cyan seven-segment digits on a recessed VFD,
 * colon blinking each second. The window is a screen, dark in both modes.
 */
@Composable
private fun VfdClock(clock: String) {
    val cyan = TdVfdCyan
    val blink = rememberBlink()
    val digits = clock.filter { it.isDigit() || it == ':' }
    val suffix = clock.filter { it.isLetter() }.uppercase()
    Row(
        modifier = Modifier
            .height(44.dp)
            .then(VfdInset)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SevenSegment(digits, 28.dp, cyan, onScreen = true, colonOn = { blink.value })
        if (suffix.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Text(suffix, style = vfdText(cyan, 14.sp, onScreen = true), maxLines = 1)
        }
    }
}

/** OBD status LED and label; what a tap does is the shared [ObdMark]'s. */
@Composable
private fun ObdLed(state: ObdConnectionState, onConnect: () -> Unit) {
    val legend = TdLegend
    val color = obdStatusColor(state)
    val blink = if (state == ObdConnectionState.CONNECTING) rememberBlink(350L) else null
    ObdMark(state, onConnect, shape = RoundedCornerShape(12.dp)) {
        Row(modifier = Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(
                Modifier
                    .size(10.dp)
                    .cachedDraw(color, state, blink) {
                        val r = size.minDimension * 1.3f
                        val halo = Brush.radialGradient(listOf(color.copy(alpha = 0.6f), fadeOf(color)), center = size.center, radius = r)
                        onDrawBehind {
                            val on = blink?.value ?: true
                            if (on && state != ObdConnectionState.DISCONNECTED) drawCircle(halo, radius = r)
                            drawCircle(if (on) color else color.copy(alpha = 0.3f))
                        }
                    }
            )
            Spacer(Modifier.width(8.dp))
            Text("OBD", style = chromeText(legend, 14.sp), maxLines = 1)
        }
    }
}

/** Outside temperature in neon magenta; nothing until the first weather fetch. */
@Composable
private fun OutsideTemp() {
    val weather = rememberWeather()
    if (weather != null) {
        Text(
            LocalUnits.current.tempTight(weather.tempC),
            style = vfdText(DashColors.Accent2, 20.sp),
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
}

// ---------------------------------------------------------------- telemetry & speed

/** One VFD readout: magenta label over a glowing cyan value (magenta when [alert]). */
private class TdReadout(val label: String, val value: String, val alert: Boolean = false)

/** A row of [items] under a hairline rule, spread evenly. */
@Composable
private fun ReadoutRow(items: List<TdReadout>, valueSize: TextUnit, modifier: Modifier = Modifier) {
    val cyan = DashColors.Accent
    val magenta = DashColors.Accent2
    val labelSize = if (valueSize.value * 0.55f < 14f) 14.sp else valueSize * 0.55f
    Row(
        modifier = modifier
            .drawBehind { drawLine(magenta.copy(alpha = 0.35f), Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        items.forEach { r ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(r.label, style = vfdText(magenta, labelSize, glow = false), maxLines = 1)
                Text(r.value, style = vfdText(if (r.alert) magenta else cyan, valueSize), maxLines = 1, softWrap = false)
            }
        }
    }
}

/** "RPM" + a 24-LED bar (green, yellow, magenta) lit up to rpm / SKIN_RPM_MAX + the reading. */
@Composable
private fun RpmLeds(rpm: Int, live: Boolean, labelSize: TextUnit, modifier: Modifier) {
    val cyan = DashColors.Accent
    val green = DashColors.Good
    val magenta = DashColors.Accent2
    val lit = if (live) (rpm / SKIN_RPM_MAX * RPM_LEDS).roundToInt().coerceIn(0, RPM_LEDS) else 0
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.tape_rpm_caps), style = vfdText(cyan, labelSize), maxLines = 1)
        Spacer(Modifier.width(8.dp))
        LedBar(lit, RPM_LEDS, Modifier.weight(1f).fillMaxHeight()) { i ->
            when {
                i < 14 -> green
                i < 19 -> TdYellow
                else -> magenta
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (live) rpm.toString() else "----",
            style = vfdText(cyan, labelSize),
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = (labelSize.value * 2.6f).dp)
        )
    }
}

/**
 * Telemetry as a VFD panel: big seven-segment speed, the RPM LED bar and
 * TEMP / BATT / LOAD readouts. Without OBD the digits are ghosts and a
 * blinking "NO OBD · TAP" connects; wide short tiles put the bar and readouts
 * beside the speed.
 */
@Composable
private fun TapeTelemetry(env: SkinTileEnv) {
    val cyan = DashColors.Accent
    val magenta = DashColors.Accent2
    val state = env.obdConnection
    val live = state == ObdConnectionState.CONNECTED
    val idle = state.isIdle
    val d = env.obdData
    val speedColor = if (live && d.speedKmh >= SPEED_WARNING_KMH) DashColors.Warning else cyan
    val volts = live && d.voltage > 0.0
    val units = LocalUnits.current
    val speedUnit = units.speedUnit.uppercase()
    val readouts = listOf(
        TdReadout(stringResource(R.string.tape_temp_caps), if (live) units.tempTight(d.coolantTempC) else "--", live && d.coolantTempC >= 105),
        TdReadout(stringResource(R.string.tape_batt_caps), if (volts) "%.1fV".format(d.voltage) else "--", volts && d.voltage !in 12.0..15.0),
        TdReadout(stringResource(R.string.tape_load_caps), if (live) "${d.engineLoadPct}%" else "--")
    )
    val chooseAdapter = stringResource(R.string.tape_cd_choose_adapter)
    Box(
        Modifier
            .fillMaxSize()
            .padding(4.dp)
            .vfdPanel(magenta)
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = idle && !env.editing, role = Role.Button, onClick = env.onConnectObd)
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)) {
            val label = (maxHeight.value * 0.06f).coerceIn(14f, 18f).sp
            val ledH = (maxHeight * 0.085f).coerceIn(14.dp, 40.dp)
            val valueSize = (maxHeight.value * 0.085f).coerceIn(15f, 40f).sp
            val side = maxWidth > maxHeight * 2.4f
            val showReadouts = maxHeight >= 200.dp
            val digits = speedDigits(if (live) units.speed(d.speedKmh) else null)
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.tape_telemetry_caps), style = chromeText(magenta, label), maxLines = 1)
                    Spacer(Modifier.weight(1f))
                    when (state) {
                        ObdConnectionState.CONNECTED ->
                            Text(stringResource(R.string.tape_live_caps), style = vfdText(DashColors.Good, label), maxLines = 1)
                        ObdConnectionState.CONNECTING -> BlinkingText(stringResource(R.string.tape_linking_caps), vfdText(cyan, label))
                        ObdConnectionState.ERROR -> BlinkingText(stringResource(R.string.tape_obd_error_tap), vfdText(magenta, label))
                        ObdConnectionState.DISCONNECTED -> BlinkingText(stringResource(R.string.tape_no_obd_tap), vfdText(magenta, label))
                    }
                }
                Spacer(Modifier.height(6.dp))
                if (side) {
                    Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        SegValue(null, digits, speedUnit, speedColor, label, Modifier.weight(1f).fillMaxHeight())
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1.1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            RpmLeds(d.rpm, live, label, Modifier.fillMaxWidth().height(ledH))
                            ReadoutRow(readouts, valueSize, Modifier.fillMaxWidth())
                        }
                    }
                } else {
                    SegValue(null, digits, speedUnit, speedColor, label, Modifier.weight(1f).fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    RpmLeds(d.rpm, live, label, Modifier.fillMaxWidth().height(ledH))
                    if (showReadouts) {
                        Spacer(Modifier.height(8.dp))
                        ReadoutRow(readouts, valueSize, Modifier.fillMaxWidth())
                    }
                }
            }
        }
        if (live) {
            // Invisible 48 dp target over "LIVE": pick another adapter, as the standard tile does.
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(width = 104.dp, height = 48.dp)
                    .clickable(enabled = !env.editing, role = Role.Button, onClick = env.onPickDevice)
                    .semantics { contentDescription = chooseAdapter }
            )
        }
    }
}

/** Speed alone: seven-segment digits + KM/H on a cyan VFD, the source top right; magenta over the limit. */
@Composable
private fun TapeSpeedHud(env: SkinTileEnv) {
    val speed = rememberSpeedKmh(env.obdData, env.obdConnection)
    val cyan = DashColors.Accent
    val magenta = DashColors.Accent2
    val source = speedSource(
        env.obdConnection == ObdConnectionState.CONNECTED, speed, stringResource(R.string.info_speed_no_signal).uppercase()
    )
    val over = (speed ?: 0) >= SPEED_WARNING_KMH
    val color = if (over) DashColors.Warning else cyan
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .padding(4.dp)
            .vfdPanel(if (over) magenta else cyan)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        val label = (maxHeight.value * 0.07f).coerceIn(14f, 18f).sp
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.tape_speed_caps), style = chromeText(magenta, label), maxLines = 1)
                Spacer(Modifier.weight(1f))
                Text(source, style = vfdText(if (speed != null) DashColors.Good else DashColors.Muted, label), maxLines = 1)
            }
            Spacer(Modifier.height(4.dp))
            val units = LocalUnits.current
            SegValue(
                null,
                if (speed != null) speedDigits(units.speed(speed)) else " --",
                units.speedUnit.uppercase(),
                if (speed != null) color else color.copy(alpha = 0.5f),
                label,
                Modifier.weight(1f).fillMaxWidth()
            )
        }
    }
}

// ---------------------------------------------------------------- media: cassette deck

private const val CAS_W = 410f
private const val CAS_H = 256f
private const val CASSETTE_ASPECT = CAS_W / CAS_H

/**
 * The music player as a cassette deck: the tape (label with title and artist,
 * reels that spin while playing, packs that follow progress) over piano-key
 * transport, and on roomy tiles a VFD with the counter and a spectrum
 * analyser. Without media access the whole tile opens the access settings.
 */
@Composable
private fun TapeMedia(env: SkinTileEnv) {
    val state = env.mediaState
    val access = env.hasMediaAccess
    // The playback position is read only by the reels and the counter, so it never recomposes the deck.
    val controller = env.mediaController
    val spin = rememberSpin(REEL_SPIN_MS, running = state.isPlaying)
    val loaded = state.hasMedia && state.title.isNotBlank()
    val title = when {
        !access -> stringResource(R.string.info_media_access_needed).uppercase()
        loaded -> state.title.uppercase()
        else -> stringResource(R.string.info_nothing_playing).uppercase()
    }
    val artist = when {
        !access -> stringResource(R.string.tape_tap_to_enable_caps)
        loaded -> state.artist.uppercase().ifBlank { stringResource(R.string.tape_unknown_artist_caps) }
        else -> stringResource(R.string.tape_start_music_app_caps)
    }
    val openAccess = { CarMediaController.openNotificationAccessSettings(env.context) }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .padding(4.dp)
            .then(if (!access) Modifier.clickable(enabled = !env.editing, onClick = openAccess) else Modifier)
    ) {
        val gap = 8.dp
        val keysH = (maxHeight * 0.22f).coerceIn(48.dp, 72.dp)
        val casW = minOf(maxWidth, (maxHeight - keysH - gap) * CASSETTE_ASPECT).coerceAtLeast(0.dp)
        val casH = casW / CASSETTE_ASPECT
        val colW = maxOf(casW, minOf(maxWidth, 170.dp))
        val deckRight = maxWidth - colW - 12.dp >= 100.dp
        val spareH = maxHeight - casH - keysH - gap
        val deckBelow = !deckRight && spareH >= 64.dp + gap
        val iconSize = (keysH * 0.4f).coerceIn(18.dp, 30.dp)
        Row(
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                Modifier.width(colW),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Cassette(title, artist, state, controller, spin, Modifier.size(casW, casH))
                Spacer(Modifier.height(gap))
                if (access) {
                    TransportKeys(env, state, iconSize, Modifier.width(colW).height(keysH))
                } else {
                    PianoKey(stringResource(R.string.tape_cd_grant_media_access), !env.editing, openAccess, Modifier.width(colW).height(keysH)) {
                        Text(stringResource(R.string.tape_grant_access_caps), style = chromeText(TdPrint, 14.sp), maxLines = 1)
                    }
                }
                if (deckBelow) {
                    Spacer(Modifier.height(gap))
                    DeckPanel(state, controller, access, Modifier.fillMaxWidth().height(minOf(spareH - gap, 150.dp)))
                }
            }
            if (deckRight) DeckPanel(state, controller, access, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

/**
 * A compact cassette, [modifier] sized at the 410:256 aspect: shell, screws,
 * cream label with stripes, [title], [artist] and side A, the tape window and
 * the head block. The shell is dark at night and silver with a bright sheen by
 * day. The reels live on their own layer, so spinning them only re-records
 * that layer.
 */
@Composable
private fun Cassette(
    title: String,
    artist: String,
    state: MediaState,
    controller: CarMediaController,
    spin: State<Float>,
    modifier: Modifier
) {
    val cyan = DashColors.Accent
    val magenta = DashColors.Accent2
    val light = DashColors.Light
    val measurer = rememberTextMeasurer()
    Box(modifier) {
        Spacer(
            Modifier
                .fillMaxSize()
                .cachedDraw(title, artist, cyan, magenta, light, measurer) {
                    val s = size.width / CAS_W
                    val minText = 14.sp.toPx()
                    val titleLayout = measurer.measure(
                        title,
                        TextStyle(
                            color = TdPrint, fontSize = maxOf(20f * s, minText).toSp(), fontFamily = FontFamily.SansSerif,
                            fontWeight = FontWeight.Bold, letterSpacing = 0.06.em
                        ),
                        overflow = TextOverflow.Ellipsis, softWrap = false, maxLines = 1,
                        constraints = Constraints(maxWidth = (286f * s).toInt().coerceAtLeast(1))
                    )
                    val artistLayout = measurer.measure(
                        artist,
                        TextStyle(color = TdPurple, fontSize = maxOf(15f * s, minText).toSp(), fontFamily = FontFamily.Monospace),
                        overflow = TextOverflow.Ellipsis, softWrap = false, maxLines = 1,
                        constraints = Constraints(maxWidth = (300f * s).toInt().coerceAtLeast(1))
                    )
                    val sideLayout = measurer.measure(
                        "A",
                        TextStyle(
                            color = magenta, fontSize = maxOf(30f * s, 14.sp.toPx()).toSp(),
                            fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black
                        )
                    )
                    val head = Path().apply {
                        moveTo(110f * s, 254f * s)
                        lineTo(128f * s, 204f * s)
                        lineTo(282f * s, 204f * s)
                        lineTo(300f * s, 254f * s)
                        close()
                    }
                    val sheen = Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = if (light) 0.55f else 0.08f), fadeOf(Color.White)),
                        startY = 0f, endY = 110f * s
                    )
                    val shade = tdShadow(0.35f)
                    val rim = Stroke(2f * s)
                    // Cassette units (the mockup's 410 x 256 frame) to pixels.
                    fun at(x: Float, y: Float) = Offset(x * s, y * s)
                    fun box(w: Float, h: Float) = Size(w * s, h * s)
                    fun round(r: Float) = CornerRadius(r * s)
                    onDrawBehind {
                        drawRoundRect(shade, at(8f, 12f), box(398f, 250f), round(16f))
                        drawRoundRect(TdShell, at(2f, 2f), box(406f, 252f), round(16f))
                        drawRoundRect(sheen, at(2f, 2f), box(406f, 252f), round(16f))
                        drawRoundRect(TdVfdRim, at(2f, 2f), box(406f, 252f), round(16f), style = rim)
                        for (i in SCREWS.indices step 2) {
                            val c = at(SCREWS[i], SCREWS[i + 1])
                            drawCircle(TdVfdRim, radius = 5f * s, center = c)
                            drawLine(TdShell, c + at(-3f, -3f), c + at(3f, 3f), 1.2f * s)
                            drawLine(TdShell, c + at(-3f, 3f), c + at(3f, -3f), 1.2f * s)
                        }
                        drawRoundRect(TdLabel, at(26f, 20f), box(358f, 150f), round(8f))
                        drawRect(cyan, at(26f, 138f), box(358f, 10f))
                        drawRect(magenta, at(26f, 150f), box(358f, 10f))
                        drawText(titleLayout, topLeft = at(44f, 52f) - Offset(0f, titleLayout.firstBaseline))
                        drawText(artistLayout, topLeft = at(44f, 78f) - Offset(0f, artistLayout.firstBaseline))
                        drawText(
                            sideLayout,
                            topLeft = at(366f, 54f) - Offset(sideLayout.size.width.toFloat(), sideLayout.firstBaseline)
                        )
                        drawRoundRect(TdWindow, at(110f, 92f), box(190f, 62f), round(31f))
                        drawPath(head, TdHeadBlock)
                        drawPath(head, TdVfdRim, style = rim)
                        drawCircle(TdWindow, radius = 6f * s, center = at(160f, 232f))
                        drawCircle(TdWindow, radius = 6f * s, center = at(250f, 232f))
                        drawRoundRect(TdWindow, at(196f, 222f), box(18f, 14f), round(3f))
                    }
                }
        )
        CassetteReels(state, controller, spin)
    }
}

/**
 * The reels, tape packs and window rim on their own layer: spinning redraws
 * only this layer, and only it follows the playback position (the packs).
 */
@Composable
private fun CassetteReels(state: MediaState, controller: CarMediaController, spin: State<Float>) {
    val fraction = rememberUpdatedState(rememberMediaFraction(state, controller))
    Spacer(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .cachedDraw(spin, fraction) {
                val s = size.width / CAS_W
                val teeth = hubTeeth(s)
                val windowRim = Stroke(2f * s)
                val span = PACK_MAX * PACK_MAX - PACK_MIN * PACK_MIN
                val leftC = Offset(152f * s, 123f * s)
                val rightC = Offset(258f * s, 123f * s)
                val hole = TdWindow
                val windowInk = TdWindowRim
                onDrawBehind {
                    val f = fraction.value
                    // Tape moves from the left pack to the right one; area is conserved.
                    val left = sqrt(PACK_MIN * PACK_MIN + span * (1f - f)) * s
                    val right = sqrt(PACK_MIN * PACK_MIN + span * f) * s
                    val angle = spin.value
                    drawReel(leftC, left, 13f * s, angle, teeth, hole)
                    drawReel(rightC, right, 13f * s, angle, teeth, hole)
                    drawRoundRect(
                        windowInk, topLeft = Offset(110f * s, 92f * s), size = Size(190f * s, 62f * s),
                        cornerRadius = CornerRadius(31f * s), style = windowRim
                    )
                    drawLine(
                        Color.White.copy(alpha = 0.10f), Offset(136f * s, 96f * s), Offset(124f * s, 150f * s), 3f * s
                    )
                }
            }
    )
}

private val HAIRLINE = Stroke(1f)
private const val PACK_MIN = 16f
private const val PACK_MAX = 30f

/** Cassette screw positions (x, y) in cassette units. */
private val SCREWS = floatArrayOf(18f, 18f, 392f, 18f, 18f, 238f, 392f, 238f)

/** Six notches around a hub of radius 13 units, centred on the origin, at scale [s]. */
private fun hubTeeth(s: Float): Path = Path().apply {
    for (k in 0 until 6) {
        val a = k * PI / 3.0
        val cs = cos(a).toFloat()
        val sn = sin(a).toFloat()
        val xs = floatArrayOf(-2f, 2f, 2f, -2f)
        val ys = floatArrayOf(-13f, -13f, -7f, -7f)
        for (j in 0 until 4) {
            val x = (xs[j] * cs - ys[j] * sn) * s
            val y = (xs[j] * sn + ys[j] * cs) * s
            if (j == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
}

/** A tape pack of [packR] around a white toothed hub turned by [angle] degrees; [hole] colours the notches and spindle hole. */
private fun DrawScope.drawReel(c: Offset, packR: Float, hubR: Float, angle: Float, teeth: Path, hole: Color) {
    drawCircle(TdTape, radius = packR, center = c)
    drawCircle(Color.Black.copy(alpha = 0.18f), radius = (packR + hubR) / 2f, center = c, style = HAIRLINE)
    drawCircle(TdHub, radius = hubR, center = c)
    translate(c.x, c.y) {
        rotate(angle, pivot = Offset.Zero) { drawPath(teeth, hole) }
    }
    drawCircle(hole, radius = hubR * 0.3f, center = c)
}

/** Previous / play-pause / next piano keys, plus eject (opens the music app) when there is room. */
@Composable
private fun TransportKeys(env: SkinTileEnv, state: MediaState, iconSize: Dp, modifier: Modifier) {
    val controller = env.mediaController
    val lastApp = remember(state.title) { CarMediaController.getLastMediaPackage(env.context) }
    BoxWithConstraints(modifier) {
        val ejectApp = lastApp?.takeIf { maxWidth >= 250.dp }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val key = Modifier.weight(1f).fillMaxHeight()
            PianoKey(stringResource(R.string.tape_cd_previous_track), !env.editing, { controller.previous() }, key) {
                KeyIcon(Icons.Filled.SkipPrevious, iconSize)
            }
            PianoKey(
                stringResource(if (state.isPlaying) R.string.tape_cd_pause else R.string.tape_cd_play), !env.editing, { controller.playPause() }, key,
                latched = state.isPlaying
            ) {
                KeyIcon(if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, iconSize)
            }
            PianoKey(stringResource(R.string.tape_cd_next_track), !env.editing, { controller.next() }, key) {
                KeyIcon(Icons.Filled.SkipNext, iconSize)
            }
            if (ejectApp != null) {
                PianoKey(stringResource(R.string.tape_cd_open_music_app), !env.editing, { env.onLaunchApp(ejectApp) }, key) {
                    KeyIcon(Icons.Filled.Eject, iconSize)
                }
            }
        }
    }
}

@Composable
private fun KeyIcon(icon: ImageVector, size: Dp) {
    Icon(icon, contentDescription = null, tint = TdPrint, modifier = Modifier.size(size))
}

/**
 * Silver piano key with a 3D side under it. It sinks while touched, and stays
 * down with a lit magenta LED while [latched] (play/pause during playback).
 */
@Composable
private fun PianoKey(
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    latched: Boolean = false,
    content: @Composable () -> Unit
) {
    val magenta = DashColors.Accent2
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val down = pressed || latched
    Box(
        modifier
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .drawWithCache {
                val depth = KEY_DEPTH.toPx()
                val faceH = size.height - depth
                val top = CornerRadius(6.dp.toPx())
                val bottom = CornerRadius(10.dp.toPx())
                val face = Path().apply {
                    addRoundRect(RoundRect(Rect(0f, 0f, size.width, faceH), top, top, bottom, bottom))
                }
                val silver = Brush.verticalGradient(listOf(TdKeyTop, TdKeyBottom), startY = 0f, endY = faceH)
                val side = TdKeySide
                val shadeUp = tdShadow(0.45f)
                val shadeDown = tdShadow(0.2f)
                val ledW = min(34.dp.toPx(), size.width * 0.42f)
                val ledH = 4.dp.toPx()
                val ledTopLeft = Offset((size.width - ledW) / 2f, 6.dp.toPx())
                val halo = 3.dp.toPx()
                onDrawBehind {
                    val travel = if (down) KEY_TRAVEL.toPx() else 0f
                    translate(top = depth + 4.dp.toPx()) {
                        drawPath(face, if (down) shadeDown else shadeUp)
                    }
                    translate(top = depth) { drawPath(face, side) }
                    translate(top = travel) {
                        drawPath(face, silver)
                        drawLine(Color.White.copy(alpha = 0.8f), Offset(top.x, 1f), Offset(size.width - top.x, 1f), 1f)
                        if (latched) {
                            drawRoundRect(
                                magenta.copy(alpha = 0.35f), topLeft = ledTopLeft - Offset(halo, halo),
                                size = Size(ledW + halo * 2f, ledH + halo * 2f), cornerRadius = CornerRadius(ledH)
                            )
                            drawRoundRect(magenta, topLeft = ledTopLeft, size = Size(ledW, ledH), cornerRadius = CornerRadius(ledH / 2f))
                        }
                    }
                }
            }
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(bottom = KEY_DEPTH)
                .offset(y = if (down) KEY_TRAVEL else 0.dp),
            contentAlignment = Alignment.Center
        ) { content() }
    }
}

/** Deck status LED and word, the tape counter, a spectrum analyser and the HI-FI badge on a VFD. */
@Composable
private fun DeckPanel(state: MediaState, controller: CarMediaController, access: Boolean, modifier: Modifier) {
    val cyan = DashColors.Accent
    val magenta = DashColors.Accent2
    val (status, statusColor) = when {
        !access -> stringResource(R.string.info_media_access_needed).uppercase() to magenta
        state.isPlaying -> stringResource(R.string.tape_play_caps) to DashColors.Good
        state.hasMedia -> stringResource(R.string.tape_pause_caps) to TdYellowText
        else -> stringResource(R.string.tape_stop_caps) to DashColors.Muted
    }
    BoxWithConstraints(modifier.vfdPanel(cyan.copy(alpha = 0.8f), 12.dp).padding(horizontal = 10.dp, vertical = 8.dp)) {
        val roomy = maxHeight >= 120.dp
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(
                    Modifier
                        .size(8.dp)
                        .drawBehind {
                            drawCircle(statusColor.copy(alpha = 0.35f), radius = size.minDimension)
                            drawCircle(statusColor)
                        }
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    status, style = vfdText(statusColor, 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.weight(1f))
                if (state.durationMs > 0L) DeckCounter(state, controller, cyan)
            }
            Spacer(Modifier.height(6.dp))
            Spectrum(state.isPlaying, Modifier.weight(1f).fillMaxWidth())
            if (roomy) {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.tape_hifi_stereo_caps), style = chromeText(magenta, 14.sp), maxLines = 1)
            }
        }
    }
}

/** The tape counter ("2:14"), in its own scope so only it follows the playback position. */
@Composable
private fun DeckCounter(state: MediaState, controller: CarMediaController, color: Color) {
    val positionMs = rememberMediaPosition(state, controller)
    SevenSegment(formatTrackTime(positionMs), 18.dp, color)
}

/**
 * Spectrum analyser: cyan to magenta bars sliced into LED blocks, dancing
 * while [playing] and resting flat otherwise (no animation runs then).
 */
@Composable
private fun Spectrum(playing: Boolean, modifier: Modifier) {
    val cyan = DashColors.Accent
    val magenta = DashColors.Accent2
    val loop = if (playing) rememberLoop(SPECTRUM_LOOP_MS) else null
    Spacer(
        // Own layer: the bars redraw every step while playing, the rest of the tile does not.
        modifier.graphicsLayer().drawWithCache {
            val bars = (size.width / 14.dp.toPx()).toInt().coerceIn(6, 40)
            val gap = 3.dp.toPx()
            val barW = (size.width - gap * (bars - 1)) / bars
            val fill = Brush.verticalGradient(listOf(magenta, cyan), startY = 0f, endY = size.height)
            val cell = 5.dp.toPx()
            val slit = 2.dp.toPx()
            val grille = Path().apply {
                var y = size.height - cell
                while (y > 0f) {
                    addRect(Rect(0f, y - slit, size.width, y))
                    y -= cell + slit
                }
            }
            val halo = 2.dp.toPx()
            val slot = TdPanel
            onDrawBehind {
                val phase = (loop?.value ?: 0f) * 2f * PI.toFloat()
                for (i in 0 until bars) {
                    val level = if (loop == null) 0.05f else spectrumLevel(i, bars, phase)
                    val top = size.height * (1f - level)
                    val x = i * (barW + gap)
                    drawRect(
                        magenta.copy(alpha = 0.16f), topLeft = Offset(x - halo, top - halo),
                        size = Size(barW + halo * 2f, size.height - top + halo)
                    )
                    drawRect(fill, topLeft = Offset(x, top), size = Size(barW, size.height - top))
                }
                drawPath(grille, slot)
            }
        }
    )
}

/** Level 0..1 of bar [i]: a few sines at whole multiples of [phase] (so the loop is seamless), bass-heavy. */
private fun spectrumLevel(i: Int, bars: Int, phase: Float): Float {
    val tilt = 1f - 0.5f * i / bars
    val a = sin(phase * (3 + i % 4) + i * 1.9f)
    val b = sin(phase * (5 + i % 5) + i * 0.7f)
    val c = sin(phase * 11 + i * 2.6f)
    return (tilt * (0.5f + 0.28f * a + 0.16f * b + 0.08f * c)).coerceIn(0.04f, 1f)
}

// ---------------------------------------------------------------- navigation: phosphor CRT

/** Phosphor-green terminal text with its full glow (the CRT stays dark in both modes). */
private fun phosphorText(size: TextUnit, alpha: Float = 1f, weight: FontWeight = FontWeight.Normal) =
    vfdText(TdPhosphor.copy(alpha = alpha), size, weight = weight, onScreen = true)

/**
 * Directions on a green phosphor CRT in a plastic bezel: turn glyph, distance
 * in big letters, the street in caps and the ETA line, under scanlines and a
 * vignette with an occasional flicker. No route shows "NO ROUTE_" with a
 * blinking cursor; tapping opens the navigation app (or the access settings).
 */
@Composable
private fun TapeNavigation(env: SkinTileEnv) {
    val nav by NavDirections.state.collectAsState()
    val context = env.context
    val access = env.hasMediaAccess
    val flicker = rememberFlicker()
    val density = LocalDensity.current
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .padding(4.dp)
            .crtBezel(DashColors.Good)
            .clip(RoundedCornerShape(22.dp))
            .clickable(enabled = !env.editing, role = Role.Button) {
                if (access) openNavigationApp(context, nav) else CarMediaController.openNotificationAccessSettings(context)
            }
            .padding(CRT_BEZEL)
    ) {
        val rx = minOf(30.dp, maxWidth * 0.09f)
        val ry = minOf(44.dp, maxHeight * 0.16f)
        // Bulged tube corners: elliptical, wider than tall.
        val screenShape = remember(rx, ry, density) {
            val rxPx = with(density) { rx.toPx() }
            val ryPx = with(density) { ry.toPx() }
            GenericShape { s, _ -> addRoundRect(RoundRect(0f, 0f, s.width, s.height, CornerRadius(rxPx, ryPx))) }
        }
        val innerW = maxWidth - rx * 1.2f - 16.dp
        val innerH = maxHeight - 20.dp
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = flicker.value
                    shape = screenShape
                    clip = true
                }
                .then(CrtScreen)
                .padding(horizontal = rx * 0.6f + 8.dp, vertical = 10.dp)
        ) {
            when {
                !access -> CrtMessage(
                    stringResource(R.string.info_directions_access_title).uppercase(),
                    stringResource(R.string.tape_tap_grant_notification_access_caps),
                    innerW, innerH
                )
                !nav.active -> CrtMessage(
                    stringResource(R.string.info_directions_no_route).uppercase(),
                    stringResource(R.string.tape_tap_open_maps_caps),
                    innerW, innerH
                )
                else -> CrtRoute(nav, innerW, innerH)
            }
            if (access && nav.active) {
                Text(
                    if (nav.packageName == "com.waze") "WAZE" else "MAPS",
                    style = phosphorText(14.sp, alpha = 0.6f),
                    maxLines = 1,
                    modifier = Modifier.align(Alignment.TopEnd)
                )
            }
        }
    }
}

/** Plastic monitor housing (dark at night, silver-grey by day) with a small power LED of [led] colour. */
@Composable
private fun Modifier.crtBezel(led: Color): Modifier = cachedDraw(led) {
    val r = CornerRadius(22.dp.toPx())
    val shade = tdShadow(0.45f)
    val drop = Offset(0f, 6.dp.toPx())
    val outer = TdBezelOuter
    val inset = 2.dp.toPx()
    val innerTopLeft = Offset(inset, inset)
    val innerSize = Size(size.width - inset * 2f, size.height - inset * 2f)
    val innerR = CornerRadius(r.x - inset)
    val body = Brush.verticalGradient(listOf(TdBezelTop, TdBezel, TdBezelBottom))
    val rimInk = TdBezelRim
    val rim = Stroke(1.5.dp.toPx())
    val c = Offset(size.width - 26.dp.toPx(), size.height - CRT_BEZEL.toPx() / 2f)
    onDrawBehind {
        drawRoundRect(shade, topLeft = drop, size = size, cornerRadius = r)
        drawRoundRect(outer, cornerRadius = r)
        drawRoundRect(body, topLeft = innerTopLeft, size = innerSize, cornerRadius = innerR)
        drawRoundRect(rimInk, topLeft = innerTopLeft, size = innerSize, cornerRadius = innerR, style = rim)
        drawCircle(led.copy(alpha = 0.35f), radius = 5.dp.toPx(), center = c)
        drawCircle(led, radius = 2.dp.toPx(), center = c)
    }
}

/**
 * CRT glass: radial phosphor-black background and a faint wire grid under the
 * content, scanlines and vignette over it. One shared modifier, so the cache
 * survives recomposition.
 */
private val CrtScreen = Modifier.drawWithCache {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return@drawWithCache onDrawWithContent { drawContent() }
    val mid = Offset(w / 2f, h / 2f)
    val glass = Brush.radialGradient(
        0f to TdCrtCentre, 0.8f to TdCrtEdge, center = mid, radius = maxOf(w, h) * 0.6f
    )
    val step = 36.dp.toPx()
    val grid = Path().apply {
        var x = step * 0.6f
        while (x < w) {
            moveTo(x, 0f)
            lineTo(x, h)
            x += step
        }
        var y = step * 0.5f
        while (y < h) {
            moveTo(0f, y)
            lineTo(w, y)
            y += step
        }
        moveTo(0f, h * 0.92f)
        lineTo(w, h * 0.45f)
    }
    val gridStroke = Stroke(1.dp.toPx())
    val scan = scanlines(Rect(0f, 0f, w, h))
    val vignetteR = h * 0.75f
    val vignette = Brush.radialGradient(
        0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f), center = mid, radius = vignetteR
    )
    onDrawWithContent {
        drawRect(glass)
        drawPath(grid, TdPhosphor.copy(alpha = 0.08f), style = gridStroke)
        drawContent()
        drawPath(scan, Color.Black.copy(alpha = 0.28f))
        scale(scaleX = w / h, scaleY = 1f, pivot = mid) {
            drawCircle(vignette, radius = vignetteR, center = mid)
        }
    }
}

/** A terminal message: [title] with a blinking block cursor, [hint] underneath. */
@Composable
private fun CrtMessage(title: String, hint: String, w: Dp, h: Dp) {
    // The title (plus its cursor) shrinks to the screen's width, never under 14 sp; a long one ends in an ellipsis.
    val size = minOf(h.value * 0.2f, w.value / ((title.length + 1) * 0.62f)).coerceIn(14f, 64f).sp
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                title, style = phosphorText(size, weight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            BlinkingText("_", phosphorText(size, weight = FontWeight.Bold), periodMs = 530L, offAlpha = 0f)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            hint,
            style = phosphorText((size.value * 0.34f).coerceIn(14f, 20f).sp, alpha = 0.7f),
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** The active manoeuvre: glyph beside (or above, on narrow tiles) distance, street and ETA. */
@Composable
private fun CrtRoute(nav: NavState, w: Dp, h: Dp) {
    val (value, unit) = nav.distanceParts
    val distance = (value + unit).uppercase()
    val eta = nav.etaParts.joinToString(" · ").uppercase()
    val narrow = w < h * 1.25f
    val big = (if (narrow) minOf(h.value * 0.16f, w.value * 0.18f) else minOf(h.value * 0.26f, w.value * 0.11f)).coerceIn(22f, 96f)
    val streetSize = (big * 0.38f).coerceIn(14f, 34f).sp
    val etaSize = (big * 0.28f).coerceIn(14f, 24f).sp
    val glyph = (if (narrow) minOf(h * 0.32f, w * 0.4f) else minOf(h * 0.55f, w * 0.26f)).coerceIn(36.dp, 170.dp)
    val lines: @Composable (Alignment.Horizontal, TextAlign) -> Unit = { align, textAlign ->
        Column(horizontalAlignment = align) {
            if (distance.isNotEmpty()) {
                Text(distance, style = phosphorText(big.sp, weight = FontWeight.Bold), maxLines = 1, softWrap = false)
            }
            Text(
                nav.instruction.uppercase(),
                style = phosphorText(streetSize),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = textAlign
            )
            if (eta.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.tape_eta_caps, eta),
                    style = phosphorText(etaSize, alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
    if (narrow) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            TurnGlyph(nav, glyph)
            Spacer(Modifier.height(6.dp))
            lines(Alignment.CenterHorizontally, TextAlign.Center)
        }
    } else {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            TurnGlyph(nav, glyph)
            Spacer(Modifier.width(14.dp))
            Box(Modifier.weight(1f)) { lines(Alignment.Start, TextAlign.Start) }
        }
    }
}

/** The notification's turn arrow tinted phosphor green over a soft glow (a generic sign when there is none). */
@Composable
private fun TurnGlyph(nav: NavState, size: Dp) {
    val bitmap = remember(nav.icon) { nav.icon?.asImageBitmap() }
    Box(
        Modifier
            .size(size)
            .glowHalo(TdPhosphor.copy(alpha = 0.28f), 0.6f),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = nav.instruction,
                colorFilter = ColorFilter.tint(TdPhosphor),
                modifier = Modifier.size(size * 0.8f)
            )
        } else {
            Icon(Icons.Filled.Directions, contentDescription = nav.instruction, tint = TdPhosphor, modifier = Modifier.size(size * 0.75f))
        }
    }
}

// ---------------------------------------------------------------- clock, weather, range

/**
 * HH:MM in cyan seven-segment digits with a blinking colon, the date in magenta
 * monospace underneath. On a 12-hour clock the hour's first digit stays dark
 * below ten and AM / PM glows small beside the digits, as on a clock radio.
 */
@Composable
private fun TapeClock(env: SkinTileEnv) {
    val cyan = DashColors.Accent
    val magenta = DashColors.Accent2
    val context = env.context
    val now = rememberNow(60_000L)
    val blink = rememberBlink()
    val locale = Locale.getDefault()
    val units = LocalUnits.current
    val timeFmt = rememberDateFormat(units.digitsPattern())
    val dateFmt = rememberDateFormat("EEEdMMMyyyy", best = true)
    val time = remember(now, timeFmt) { timeFmt.format(now) }
    val amPm = remember(now, units) { units.amPm(now, locale)?.filter { it.isLetter() }?.uppercase(locale) }
    val date = remember(now, dateFmt) { dateFmt.format(now).uppercase(locale) }
    val digits = time.padStart(5, ' ')
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .padding(4.dp)
            .vfdPanel(cyan)
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = !env.editing, role = Role.Button) { openClockApp(context) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        val dateSize = (maxHeight.value * 0.1f).coerceIn(14f, 26f).sp
        val ratio = segUnits(digits) / SEG_H
        // AM / PM takes about two date letters and a gap off the digits' width.
        val markerW = if (amPm != null) (dateSize.value * 2.2f + 8f).dp else 0.dp
        val digitH = minOf(maxHeight - (dateSize.value * 1.4f).dp - 10.dp, (maxWidth - markerW) / ratio).coerceAtLeast(16.dp)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            if (amPm == null) {
                SevenSegment(digits, digitH, cyan, colonOn = { blink.value })
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    SevenSegment(digits, digitH, cyan, colonOn = { blink.value })
                    Spacer(Modifier.width(8.dp))
                    Text(amPm, style = vfdText(cyan, dateSize), maxLines = 1)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                date,
                style = vfdText(magenta, dateSize).copy(letterSpacing = 0.14.em),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Neon-sign frame: a backing plate inside a glowing [tube], hung on two small
 * brackets. The plate is dark at night; by day it is pale acrylic and the
 * tube's core stays closer to its colour (a white-hot core vanishes on it).
 */
@Composable
private fun Modifier.neonSign(tube: Color): Modifier = cachedDraw(tube) {
    val light = DashColors.Light
    val r = CornerRadius(18.dp.toPx())
    val plate = if (light) Color.White.copy(alpha = 0.6f) else TdInk.copy(alpha = 0.72f)
    val tubes = NEON_WIDTHS.map { Stroke(it.dp.toPx()) }
    val core = lerp(tube, Color.White, if (light) 0.35f else 0.7f)
    val coreStroke = Stroke(0.8.dp.toPx())
    val bracket = TdVfdRim
    val bw = 6.dp.toPx()
    val bh = 9.dp.toPx()
    onDrawBehind {
        drawRoundRect(plate, cornerRadius = r)
        for (i in NEON_WIDTHS.indices) {
            drawRoundRect(tube.copy(alpha = NEON_ALPHAS[i]), cornerRadius = r, style = tubes[i])
        }
        drawRoundRect(core, cornerRadius = r, style = coreStroke)
        for (fx in NEON_BRACKETS) {
            drawRect(bracket, topLeft = Offset(size.width * fx - bw / 2f, -bh / 2f), size = Size(bw, bh))
        }
    }
}

private val NEON_BRACKETS = floatArrayOf(0.25f, 0.75f)

private val NEON_WIDTHS = floatArrayOf(10f, 6f, 3.5f, 2f)
private val NEON_ALPHAS = floatArrayOf(0.06f, 0.14f, 0.35f, 1f)

/**
 * Letters bent from neon tube: a wide faint halo stroke, the coloured tube and
 * its hot pale core. By day the halo and bloom are fainter and the core keeps
 * more colour, so the tube reads as saturated glass on the pale plate.
 */
@Composable
private fun NeonTubeText(text: String, color: Color, size: TextUnit, modifier: Modifier = Modifier) {
    val px = with(LocalDensity.current) { size.toPx() }
    val light = DashColors.Light
    val bloom = if (light) softTextShadow(color.copy(alpha = 0.4f), px * 0.1f) else softTextShadow(color, px * 0.2f)
    val base = TextStyle(fontSize = size, lineHeight = size, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal)
    Box(modifier) {
        Text(
            text, maxLines = 1, softWrap = false,
            style = base.copy(
                color = color.copy(alpha = if (light) 0.14f else 0.22f),
                drawStyle = Stroke(px * 0.12f, join = StrokeJoin.Round)
            )
        )
        Text(
            text, maxLines = 1, softWrap = false,
            style = base.copy(
                color = color,
                drawStyle = Stroke(px * 0.05f, join = StrokeJoin.Round),
                shadow = bloom
            )
        )
        Text(
            text, maxLines = 1, softWrap = false,
            style = base.copy(
                color = lerp(color, Color.White, if (light) 0.3f else 0.6f),
                drawStyle = Stroke(px * 0.016f, join = StrokeJoin.Round)
            )
        )
    }
}

/** An icon in [color] over a soft radial glow (fainter by day). */
@Composable
private fun NeonIcon(icon: ImageVector, color: Color, size: Dp) {
    val glow = color.copy(alpha = if (DashColors.Light) 0.2f else 0.35f)
    Box(
        Modifier
            .size(size)
            .glowHalo(glow, 0.7f, fadeOf(color)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(size * 0.9f))
    }
}

/**
 * Weather as a neon sign: the temperature in magenta tube letters (with the
 * odd flicker), the condition in cyan monospace, feels-like / wind / low-high
 * small. "LOADING" until the first fetch, "NO GPS" or "OFFLINE" when that
 * is why there is none.
 */
@Composable
private fun TapeWeather() {
    val weather = rememberWeather()
    val wait = rememberWeatherWait()
    val cyan = DashColors.Accent
    val magenta = DashColors.Accent2
    val muted = DashColors.TextSecondary
    val flicker = rememberFlicker()
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .padding(8.dp)
            .neonSign(cyan)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        val w = weather
        if (w == null) {
            Row(verticalAlignment = Alignment.Bottom) {
                val word = when (wait) {
                    WeatherWait.LOADING -> stringResource(R.string.tape_loading_caps)
                    WeatherWait.NO_GPS -> stringResource(R.string.info_no_gps).uppercase()
                    WeatherWait.UNAVAILABLE -> stringResource(R.string.orbit_offline).uppercase()
                }
                Text(word, style = vfdText(cyan, 18.sp), maxLines = 1)
                // The cursor blinks only while something is on its way.
                if (wait == WeatherWait.LOADING) BlinkingText("_", vfdText(cyan, 18.sp), offAlpha = 0f)
            }
        } else {
            val wide = maxWidth > maxHeight * 1.9f
            val tempSize = (
                if (wide) minOf(maxHeight.value * 0.78f, maxWidth.value * 0.2f)
                else minOf(maxHeight.value * 0.4f, maxWidth.value * 0.32f)
                ).coerceIn(28f, 150f)
            val iconSize = (tempSize * 0.55f).dp
            val condSize = (tempSize * 0.2f).coerceIn(14f, 28f).sp
            val smallSize = (tempSize * 0.145f).coerceIn(14f, 18f).sp
            val units = LocalUnits.current
            val feels = stringResource(
                if (units.imperial) R.string.units_tape_feels_wind_caps_mph else R.string.tape_feels_wind_caps,
                units.temp(w.feelsC), units.speed(w.windKmh)
            )
            val range = if (w.hiC.isNaN() || w.loC.isNaN()) null
            else stringResource(R.string.tape_low_high_caps, units.temp(w.loC), units.temp(w.hiC))
            val sign: @Composable () -> Unit = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NeonIcon(weatherIcon(w.code), cyan, iconSize)
                    Spacer(Modifier.width(10.dp))
                    NeonTubeText("${units.temp(w.tempC)}°", magenta, tempSize.sp, Modifier.graphicsLayer { alpha = flicker.value })
                }
            }
            val details: @Composable (Alignment.Horizontal) -> Unit = { align ->
                Column(horizontalAlignment = align) {
                    Text(w.condition.uppercase(), style = vfdText(cyan, condSize), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text(feels, style = vfdText(muted, smallSize, glow = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (range != null) {
                        Text(range, style = vfdText(muted, smallSize, glow = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (wide) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    sign()
                    Spacer(Modifier.width(18.dp))
                    Box(Modifier.weight(1f, fill = false)) { details(Alignment.Start) }
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    sign()
                    Spacer(Modifier.height(6.dp))
                    details(Alignment.CenterHorizontally)
                }
            }
        }
    }
}

/**
 * Fuel and range as VFD readouts (seven-segment FUEL % and RANGE KM) over a
 * horizontal LED fuel bar from E to F. Falls back to the standard tile until
 * a fuel level is known (it explains how to learn one).
 */
@Composable
private fun TapeRange(item: DashboardItem, env: SkinTileEnv) {
    val fuel = rememberFuel(env.obdData, env.obdConnection)
    if (fuel == null) {
        StandardSkinnedTile(item, env)
        return
    }
    val cyan = DashColors.Accent
    val magenta = DashColors.Accent2
    val green = DashColors.Good
    val muted = DashColors.Muted
    val color = if (fuel.percent <= SKIN_LOW_FUEL_PCT) DashColors.Warning else cyan
    val lit = (fuel.percent / 100f * FUEL_LEDS).roundToInt().coerceIn(0, FUEL_LEDS)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .padding(4.dp)
            .vfdPanel(magenta)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        val label = (maxHeight.value * 0.06f).coerceIn(14f, 18f).sp
        val barH = (maxHeight * 0.09f).coerceIn(14.dp, 34.dp)
        // Side by side or stacked: whichever gives the taller digits.
        val labelH = (label.value * 1.3f + 4f).dp
        val regionH = maxHeight - labelH - 14.dp - barH - 8.dp
        val ratio = segUnits("888") / SEG_H
        val sideDigits = minOf(regionH - labelH, ((maxWidth - 14.dp) / 2 - 30.dp) / ratio)
        val stackedDigits = minOf(regionH / 2 - labelH, (maxWidth - 30.dp) / ratio)
        val stacked = stackedDigits > sideDigits
        val pct = fuel.percent.coerceIn(0, 999).toString().padStart(3, ' ')
        val units = LocalUnits.current
        val km = units.distance(fuel.rangeKm).coerceIn(0, 9999).toString().padStart(3, ' ')
        val rangeUnit = units.distanceUnit.uppercase()
        val fuelLabel = stringResource(R.string.tape_fuel_caps)
        val rangeLabel = stringResource(R.string.tape_range_caps)
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.tape_fuel_range_caps), style = chromeText(magenta, label), maxLines = 1)
                Spacer(Modifier.weight(1f))
                Text(fuel.source.uppercase(), style = vfdText(muted, label, glow = false), maxLines = 1)
            }
            Spacer(Modifier.height(6.dp))
            if (stacked) {
                Column(Modifier.weight(1f).fillMaxWidth()) {
                    SegValue(fuelLabel, pct, "%", color, label, Modifier.weight(1f).fillMaxWidth())
                    SegValue(rangeLabel, km, rangeUnit, cyan, label, Modifier.weight(1f).fillMaxWidth())
                }
            } else {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    SegValue(fuelLabel, pct, "%", color, label, Modifier.weight(1f).fillMaxHeight())
                    SegValue(rangeLabel, km, rangeUnit, cyan, label, Modifier.weight(1f).fillMaxHeight())
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("E", style = vfdText(magenta, label), maxLines = 1)
                Spacer(Modifier.width(8.dp))
                LedBar(lit, FUEL_LEDS, Modifier.weight(1f).height(barH)) { i ->
                    when {
                        i < FUEL_LEDS * 0.15f -> magenta
                        i < FUEL_LEDS * 0.3f -> TdYellow
                        else -> green
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text("F", style = vfdText(green, label), maxLines = 1)
            }
        }
    }
}

// ---------------------------------------------------------------- apps: radio presets

/**
 * Radio preset button: dark chrome face with a cyan rim over a 3D edge; it
 * sinks while touched. [active] keeps the rim lit and glowing. By day the face
 * is brushed silver with a silver edge; the cyan rim shows only when lit.
 */
@Composable
private fun PresetButton(
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    content: @Composable () -> Unit
) {
    val cyan = DashColors.Accent
    val light = DashColors.Light
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .drawWithCache {
                val depth = PRESET_DEPTH.toPx()
                val faceH = size.height - depth
                val faceSize = Size(size.width, faceH)
                val r = CornerRadius(10.dp.toPx())
                val face = Brush.verticalGradient(listOf(TdPresetTop, TdPresetBottom), startY = 0f, endY = faceH)
                val side = TdPresetSide
                val shade = tdShadow(0.4f)
                val highlight = Color.White.copy(alpha = if (light) 0.9f else 0.15f)
                val rim = Stroke(1.dp.toPx())
                val halo = Stroke(5.dp.toPx())
                onDrawBehind {
                    val lit = pressed || active
                    val travel = if (pressed) PRESET_TRAVEL.toPx() else 0f
                    drawRoundRect(shade, Offset(0f, depth + 3.dp.toPx()), faceSize, r)
                    drawRoundRect(side, topLeft = Offset(0f, depth), size = faceSize, cornerRadius = r)
                    translate(top = travel) {
                        if (lit) drawRoundRect(cyan.copy(alpha = 0.22f), size = faceSize, cornerRadius = r, style = halo)
                        drawRoundRect(face, size = faceSize, cornerRadius = r)
                        drawLine(highlight, Offset(r.x, 1.5f), Offset(size.width - r.x, 1.5f), 1.dp.toPx())
                        drawRoundRect(
                            if (light && !lit) side else cyan.copy(alpha = if (lit) 1f else 0.7f),
                            size = faceSize, cornerRadius = r, style = rim
                        )
                    }
                }
            }
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(bottom = PRESET_DEPTH)
                .offset(y = if (pressed) PRESET_TRAVEL else 0.dp),
            contentAlignment = Alignment.Center
        ) { content() }
    }
}

/** The app's icon, or a generic grid while apps load. */
@Composable
private fun PresetIcon(app: AppEntry?, size: Dp) {
    if (app != null) AppIcon(icon = app.icon, size = size)
    else Icon(Icons.Filled.Apps, contentDescription = null, tint = DashColors.Muted, modifier = Modifier.size(size * 0.7f))
}

/** Preset label: caps monospace, one line. */
@Composable
private fun PresetLabel(name: String, size: TextUnit, modifier: Modifier = Modifier) {
    Text(
        name.uppercase(),
        style = vfdText(DashColors.TextPrimary, size, glow = false).copy(letterSpacing = 0.06.em),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = modifier
    )
}

/** An app shortcut as one radio preset button: icon over (or, on wide tiles, beside) the caps label. */
@Composable
private fun TapePreset(item: DashboardItem.AppShortcut, env: SkinTileEnv) {
    val app = env.appsByPackage[item.packageName]
    val name = appLabel(app, item.packageName)
    BoxWithConstraints(Modifier.fillMaxSize().padding(4.dp)) {
        val faceH = maxHeight - PRESET_DEPTH
        val wide = maxWidth > maxHeight * 1.7f
        val labelSize = (faceH.value * 0.14f).coerceIn(14f, 15f).sp
        val iconSize = (if (wide) faceH * 0.55f else minOf(faceH * 0.5f, maxWidth * 0.5f)).coerceIn(20.dp, 64.dp)
        PresetButton(name, !env.editing, { env.onLaunchApp(item.packageName) }, Modifier.fillMaxSize()) {
            if (wide) {
                Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    PresetIcon(app, iconSize)
                    Spacer(Modifier.width(10.dp))
                    PresetLabel(name, labelSize, Modifier.weight(1f, fill = false))
                }
            } else {
                Column(Modifier.padding(horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    PresetIcon(app, iconSize)
                    Spacer(Modifier.height(4.dp))
                    PresetLabel(name, labelSize)
                }
            }
        }
    }
}

/**
 * A launch bar as a row of numbered radio presets (magenta number, icon, caps
 * label; narrower buttons stack the icon over the label) and a pencil preset
 * at the end that edits the bar.
 */
@Composable
private fun TapePresetBar(item: DashboardItem.LaunchBar, env: SkinTileEnv) {
    val legend = TdLegend
    val magenta = DashColors.Accent2
    BoxWithConstraints(Modifier.fillMaxSize().padding(4.dp)) {
        val n = item.packages.size
        val gap = 8.dp
        val pencilW = 52.dp
        val faceH = maxHeight - PRESET_DEPTH
        val bw = if (n > 0) (maxWidth - pencilW - gap * n) / n else 0.dp
        val rowMode = bw >= 140.dp && faceH < 120.dp
        val labelSize = (faceH.value * 0.16f).coerceIn(14f, 15f).sp
        val numberSize = (faceH.value * (if (rowMode) 0.3f else 0.2f)).coerceIn(14f, 24f).sp
        val iconSize = if (rowMode) (faceH * 0.5f).coerceIn(20.dp, 44.dp) else minOf(faceH * 0.45f, bw * 0.5f).coerceIn(18.dp, 56.dp)
        val showLabel = rowMode || (faceH >= 64.dp && bw >= 60.dp)
        val numberStyle = chromeText(magenta, numberSize, glow = true).copy(fontWeight = FontWeight.Black, letterSpacing = 0.em)
        Row(
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (n == 0) {
                Text(
                    stringResource(R.string.tape_presets_empty_caps),
                    style = vfdText(DashColors.Muted, 14.sp, glow = false),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 8.dp)
                )
            }
            item.packages.forEachIndexed { i, pkg ->
                val app = env.appsByPackage[pkg]
                val name = appLabel(app, pkg)
                PresetButton(name, !env.editing, { env.onLaunchApp(pkg) }, Modifier.weight(1f).fillMaxHeight()) {
                    if (rowMode) {
                        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}", style = numberStyle, maxLines = 1)
                            Spacer(Modifier.width(10.dp))
                            PresetIcon(app, iconSize)
                            Spacer(Modifier.width(10.dp))
                            PresetLabel(name, labelSize, Modifier.weight(1f, fill = false))
                        }
                    } else {
                        Box(Modifier.fillMaxSize()) {
                            Text(
                                "${i + 1}",
                                style = numberStyle,
                                maxLines = 1,
                                modifier = Modifier.align(Alignment.TopStart).padding(start = 7.dp, top = 3.dp)
                            )
                            Column(
                                Modifier.align(Alignment.Center).padding(horizontal = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                PresetIcon(app, iconSize)
                                if (showLabel) {
                                    Spacer(Modifier.height(3.dp))
                                    PresetLabel(name, labelSize)
                                }
                            }
                        }
                    }
                }
            }
            PresetButton(stringResource(R.string.tape_cd_edit_launch_bar), true, env.onEditLaunchBar, Modifier.width(pencilW).fillMaxHeight()) {
                Icon(Icons.Filled.Edit, contentDescription = null, tint = legend, modifier = Modifier.size(22.dp))
            }
        }
    }
}
