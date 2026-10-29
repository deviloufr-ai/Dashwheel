package com.openauto.dash

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * Paper skin (2026-10-13 car-free looks): calm technology and the e-paper
 * revival. Ink on a grey page, a slightly lighter panel grey, 2 dp ink rules
 * and tiny corners; no glow, no gradient, no shadow. The material is the
 * wow: crisp ink, ordered-dither (Bayer 4 x 4) fills and hatching built once
 * as small repeating tiles, and the e-ink refresh, a tile flashing ink then
 * paper once (about a quarter of a second) when its main value changes in a
 * way worth a look: a new turn, a new hour, a new song, a new limit. Nothing
 * else moves. By night the same page, ink and paper swapped and dimmed.
 *
 * Mockup: D:/dashwheel-design/v17-no-car/index.html, section 6 (s6.png).
 * This file: palettes, the shared look (type, rules, textures, the refresh),
 * the page, the bar, the window frame and the stamp. The tiles and the
 * junction diagram are in PaperSkinTiles.kt and PaperSkinMap.kt.
 */

internal val PaperPalette = DashPalette(
    Background = Color(0xFF151614), Bar = Color.Transparent, Card = Color(0xFF1C1D1A), CardHi = Color(0x14A8A9A1),
    Accent = Color(0xFFA8A9A1), Secondary = Color(0xFF8A8B84), Critical = Color(0xFFE5625E), Good = Color(0xFF4FBF8A),
    Muted = Color(0xFF74756F), TextPrimary = Color(0xFFA8A9A1), TextSecondary = Color(0xFF8A8B84),
    Accent2 = Color(0xFFA8A9A1), Line = Color(0xFF7E7F78), Glow = 0f, Bare = true,
    BackgroundStops = listOf(Color(0xFF151614), Color(0xFF151614)),
    Font = DashFont.SANS,
    HeroWeight = FontWeight.Bold,
    Skin = DashSkin.PAPER
)

internal val PaperLightPalette = DashPalette(
    Background = Color(0xFFCFD0C8), Bar = Color.Transparent, Card = Color(0xFFDCDDD5), CardHi = Color(0x14141414),
    Accent = Color(0xFF141414), Secondary = Color(0xFF3A3B36), Critical = Color(0xFFB3261E), Good = Color(0xFF0E7A4A),
    Muted = Color(0xFF5A5B54), TextPrimary = Color(0xFF141414), TextSecondary = Color(0xFF33342F),
    Accent2 = Color(0xFF141414), Line = Color(0xFF141414), Glow = 0f, Bare = true, Light = true,
    BackgroundStops = listOf(Color(0xFFCFD0C8), Color(0xFFCFD0C8)),
    // The day's amber, deepened: the usual one fades into the grey page.
    Tacho = Color(0xFF8A4F00),
    Font = DashFont.SANS,
    HeroWeight = FontWeight.Bold,
    Skin = DashSkin.PAPER
)

/** The skin's corners (dialogs, menus, tiles being arranged): barely rounded, like a cut sheet. */
internal val PaperShapes = skinShapes(4, 2, 2)

// --- The material ------------------------------------------------------------------

/** The ink: text, rules, the route. */
internal val paperInk: Color get() = DashColors.TextPrimary

/** The page itself. */
internal val paperPage: Color get() = DashColors.Background

/** The panel grey, a shade off the page (the mockup's right column). */
internal val paperPanel: Color get() = DashColors.Card

/** The rules between sections: ink by day, a dimmer ink by night. */
internal val paperRule: Color get() = DashColors.Line

/** The brightest paper there is: streets, the refresh's second frame. By night the darkest. */
internal val paperBlank: Color get() = paperBlankFor(DashColors.Light)

internal fun paperBlankFor(light: Boolean): Color = if (light) Color(0xFFE8E8E2) else Color(0xFF0B0B0A)

/** The rules' and frames' weight. */
internal val PAPER_RULE = 2.dp

/** A stamp or a label box's corner: next to none. */
internal val PAPER_CORNER = 2.dp

/** Big figures centred in their line box, trimmed, so a large time sits where it is put. */
private val TightFigure = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)

/**
 * The skin's type at [size] dp whatever the system font scale: a plain bold
 * grotesque for words and the big figures, or with [mono] the typewriter
 * face of the values ("21:24 · 11 km"). [caps] spaces the letters of the
 * bar's words and the stamps; [hero] trims and tightens a big figure.
 */
@Composable
internal fun paperText(
    size: Float,
    color: Color = DashColors.TextPrimary,
    weight: FontWeight = FontWeight.Normal,
    mono: Boolean = false,
    caps: Boolean = false,
    hero: Boolean = false
): TextStyle = TextStyle(
    color = color,
    fontFamily = if (mono) FontFamily.Monospace else FontFamily.SansSerif,
    fontWeight = weight,
    fontSize = fixedSp(size),
    fontFeatureSettings = "tnum",
    lineHeight = if (hero) 1.0.em else 1.2.em,
    lineHeightStyle = if (hero) TightFigure else null,
    letterSpacing = when {
        caps -> 0.08.em
        hero -> (-0.03).em
        else -> 0.em
    }
)

/** One run of the skin's text: one line with an ellipsis unless [maxLines] says otherwise; [caps] upper-cases it. */
@Composable
internal fun PaperLine(
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

/** A 2 dp ink rule along the top edge: the line between two sections of the column. */
internal fun Modifier.paperRuleTop(): Modifier = drawBehind {
    val w = PAPER_RULE.toPx()
    drawRect(paperRule, Offset.Zero, Size(size.width, w))
}

/**
 * A sheet's 2 dp ink frame, drawn inside the box; dashed while the tile is
 * being arranged, so it reads as movable. With [fill] the panel grey under it.
 */
internal fun Modifier.paperFrame(editing: Boolean = false, fill: Boolean = false): Modifier = drawBehind {
    val w = PAPER_RULE.toPx()
    if (fill) drawRect(paperPanel)
    val dash = if (editing) PathEffect.dashPathEffect(floatArrayOf(10f * density, 6f * density)) else null
    drawRect(paperRule, Offset(w / 2f, w / 2f), Size(size.width - w, size.height - w), style = Stroke(w, pathEffect = dash))
}

/** While arranging, a dashed ink frame round a tile that has none of its own. */
internal fun Modifier.paperEditing(editing: Boolean): Modifier = if (editing) paperFrame(editing = true) else this

/**
 * The whole area tappable, or nothing when [enabled] is false. No ripple: a
 * press inks the area faintly at once and lets go at once, as e-paper would,
 * with the usual tap feedback.
 */
@Composable
internal fun Modifier.paperTap(enabled: Boolean, label: String, onClick: () -> Unit): Modifier {
    if (!enabled) return this
    val tap = rememberTapFeedback()
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    return this
        .drawWithContent {
            drawContent()
            if (pressed) drawRect(paperInk.copy(alpha = 0.12f))
        }
        .clickable(interactionSource = source, indication = null, onClickLabel = label, role = Role.Button) { tap(); onClick() }
}

// --- Textures: ordered dither and hatching, built once ---------------------------------

/** The 4 x 4 Bayer matrix: the order in which a tile's sixteen dots are inked as a fill gets darker. */
private val BAYER4 = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

/** Which of a 4 x 4 tile's dots are inked at [level] of 16 (0 blank, 16 solid), row by row. */
internal fun bayerInked(level: Int): BooleanArray {
    val l = level.coerceIn(0, 16)
    return BooleanArray(16) { BAYER4[it] < l }
}

/** A [period]-square tile of 45 degree hatching ("/" lines) [width] dots thick, row by row; it repeats without a seam. */
internal fun hatchInked(period: Int, width: Int): BooleanArray =
    BooleanArray(period * period) { i -> ((i % period) + (i / period)) % period < width }

/**
 * The textures as repeating brushes: a tile of a few pixels made into a
 * bitmap once per ink, ground, pattern and dot size, then kept. Drawing a
 * fill is then one rectangle with a shader. The cache is small and cleared
 * when it grows (a theme fade makes new colours for a moment).
 */
internal object PaperTextures {
    private data class Key(val kind: Int, val ink: Int, val ground: Int, val a: Int, val b: Int, val dot: Int)

    private val brushes = HashMap<Key, ShaderBrush>()

    /** Ordered dither at [level] of 16 in [ink] over [ground] (transparent lets what is under show), [dot] pixels a dot. */
    fun dither(ink: Color, ground: Color, level: Int, dot: Int): ShaderBrush =
        get(Key(0, ink.toArgb(), ground.toArgb(), level, 0, dot)) { bayerInked(level) to 4 }

    /** Hatching, [period] dots apart and [width] thick, in [ink] over [ground]. */
    fun hatch(ink: Color, ground: Color, period: Int, width: Int, dot: Int): ShaderBrush =
        get(Key(1, ink.toArgb(), ground.toArgb(), period, width, dot)) { hatchInked(period, width) to period }

    private fun get(key: Key, mask: () -> Pair<BooleanArray, Int>): ShaderBrush {
        brushes[key]?.let { return it }
        if (brushes.size > 64) brushes.clear()
        val (inked, side) = mask()
        val dot = key.dot.coerceAtLeast(1)
        val px = side * dot
        val pixels = IntArray(px * px) { i ->
            val x = (i % px) / dot
            val y = (i / px) / dot
            if (inked[y * side + x]) key.ink else key.ground
        }
        val bitmap = android.graphics.Bitmap.createBitmap(pixels, px, px, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
        return ShaderBrush(ImageShader(bitmap, TileMode.Repeated, TileMode.Repeated)).also { brushes[key] = it }
    }
}

/** A dither dot in pixels for this screen: one on the head unit, a little more on a denser screen. */
internal fun androidx.compose.ui.unit.Density.paperDot(): Int = max(1, density.roundToInt())

// --- The e-ink refresh -------------------------------------------------------------

/**
 * The e-paper refresh over a tile: when [key] changes (never on the first
 * showing), the tile goes ink for a moment, then blank paper, then shows
 * its new value, about a quarter of a second in all, as an e-ink screen
 * does. By night the ink frame is dimmed; with reduced effects it is the
 * ink frame alone, faint; with effects off, or on a page out of sight,
 * there is none. Only drawing follows the frames: nothing recomposes.
 */
@Composable
internal fun Modifier.paperRefresh(key: Any?): Modifier {
    val stage = remember { mutableIntStateOf(0) }
    val seen = remember { arrayOfNulls<Any>(1) }
    val first = remember { booleanArrayOf(true) }
    val effects = DashColors.Effects
    val active = LocalPageActive.current
    LaunchedEffect(key) {
        val was = seen[0]
        seen[0] = key
        if (first[0]) {
            first[0] = false
            return@LaunchedEffect
        }
        if (was == key || effects == DashEffects.NONE || !active) return@LaunchedEffect
        try {
            stage.intValue = 1
            delay(PAPER_FLASH_INK_MS)
            if (effects == DashEffects.FULL) {
                stage.intValue = 2
                delay(PAPER_FLASH_PAPER_MS)
            }
        } finally {
            stage.intValue = 0
        }
    }
    return drawWithContent {
        drawContent()
        when (stage.intValue) {
            1 -> {
                val light = DashColors.Light
                val k = if (effects == DashEffects.FULL) 1f else 0.4f
                drawRect(paperInk.copy(alpha = (if (light) 1f else 0.55f) * k))
            }
            2 -> drawRect(paperBlank)
        }
    }
}

/** The refresh's two frames: ink, then blank paper. */
internal const val PAPER_FLASH_INK_MS = 110L
internal const val PAPER_FLASH_PAPER_MS = 120L

// --- The page ----------------------------------------------------------------------

/** The page: the e-paper grey, flat. By night the dim page. Nothing on it moves. */
@Composable
internal fun paperBackground(): Modifier {
    val page = DashColors.Background
    return remember(page) { Modifier.drawBehind { drawRect(page) } }
}

/** The page behind a widget Paper does not redraw: a sheet of the panel grey in its ink frame. */
@Composable
internal fun Modifier.paperFallbackPanel(): Modifier = paperFrame(fill = true)

// --- The bar -----------------------------------------------------------------------

/** The bar's height: its text buttons are [DashSize.Bar] tall, under a 2 dp rule. */
internal val PAPER_BAR = 76.dp

/**
 * The bar: a thin ruled strip of plain words. On the left APPS and LAYOUT,
 * then MAP, MUSIC and PHONE where the bar is wide enough (each only if it
 * fits whole); in the middle the time in the typewriter face (not while a
 * clock tile or the head unit's own bar shows it) and the dashboard's place;
 * on the right the car's alerts, the phone, the setup, the OBD link as a
 * square and its letters, TALK boxed in ink and MORE.
 */
@Composable
internal fun PaperTopBar(m: TopBarModel) {
    val context = LocalContext.current
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(PAPER_BAR)
            .graphicsLayer()
            .paperRuleTop()
    ) {
        val narrow = maxWidth < NARROW_BAR
        val shared = barIsShared()
        Row(
            modifier = Modifier.fillMaxSize().padding(start = 6.dp, end = 10.dp, top = PAPER_RULE),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PaperFitRow(if (narrow) Modifier else Modifier.weight(1f)) {
                PaperBarButton(stringResource(R.string.dash_apps), stringResource(R.string.dash_all_apps), m.onApps)
                LayoutPicker(m) { open ->
                    PaperBarButton(stringResource(R.string.dash_layout_button), stringResource(R.string.dash_screen_layout, m.layout.title), open)
                }
                if (!narrow) {
                    PaperBarButton(stringResource(R.string.paper_map), stringResource(R.string.paper_map_cd)) {
                        openNavigationApp(context, NavDirections.state.value)
                    }
                    PaperBarButton(stringResource(R.string.paper_music), stringResource(R.string.paper_music_cd)) { paperOpenMusic(context, m.onApps) }
                    PaperBarButton(stringResource(R.string.paper_phone), stringResource(R.string.paper_phone_cd)) {
                        context.launchSafely(Intent(Intent.ACTION_DIAL))
                    }
                }
            }
            Row(modifier = Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!m.merged && !ClockInSight.on.value) {
                    PaperLine(m.clock, paperText(28f, DashColors.TextPrimary, FontWeight.Bold, mono = true), overflow = TextOverflow.Clip)
                }
                PagePositionChip(Modifier.padding(start = 10.dp))
            }
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
                Box(Modifier.layoutId(BarRank.OBD)) { PaperObdMark(m.obdConnection, m.onConnectObd) }
                // Talk is the bar's one inked button: it keeps its place like the link and the menu.
                Box(Modifier.layoutId(BarRank.OBD).padding(horizontal = 6.dp)) { PaperTalkButton() }
                Box(Modifier.layoutId(BarRank.MORE)) {
                    MorePicker(m, geminiInBar = true) { open ->
                        PaperBarButton(stringResource(R.string.dash_more), stringResource(R.string.dash_more), open)
                    }
                }
            }
        }
    }
}

/** Children in a row, in order, each only if it fits whole in the room left (left out, never cut). */
@Composable
internal fun PaperFitRow(modifier: Modifier = Modifier, gap: Dp = 0.dp, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val gapPx = gap.roundToPx()
        val placed = arrayOfNulls<Placeable>(measurables.size)
        val xs = IntArray(measurables.size)
        var x = 0
        var height = 0
        var full = false
        measurables.forEachIndexed { i, m ->
            if (full) return@forEachIndexed
            val room = constraints.maxWidth - x
            val p = m.measure(Constraints(maxHeight = constraints.maxHeight))
            if (p.width > room) {
                full = true
                return@forEachIndexed
            }
            placed[i] = p
            xs[i] = x
            x += p.width + gapPx
            height = max(height, p.height)
        }
        val width = (x - gapPx).coerceAtLeast(0).coerceIn(constraints.minWidth, constraints.maxWidth)
        layout(width, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            placed.forEachIndexed { i, p -> p?.placeRelative(xs[i], (height - p.height) / 2) }
        }
    }
}

/** A word of the bar in spaced capitals, [DashSize.Bar] tall, the whole height its target. */
@Composable
private fun PaperBarButton(label: String, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = DashSize.Bar)
            .widthIn(min = DashSize.Bar)
            .paperTap(true, description, onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        PaperLine(label, paperText(17f, DashColors.TextPrimary, FontWeight.Bold, caps = true), caps = true)
    }
}

/**
 * The OBD link: a small square and the letters OBD. Filled in the link's
 * colour when live, hollow when off, blinking in steps while it connects
 * (an e-ink blink, not a fade), with a "!" on error so it reads without
 * colour. What a tap does is the shared [ObdMark]'s.
 */
@Composable
private fun PaperObdMark(state: ObdConnectionState, onConnect: () -> Unit) {
    val color = obdStatusColor(state)
    val blink = if (state == ObdConnectionState.CONNECTING) rememberBlink(450L) else null
    ObdMark(state, onConnect, shape = RectangleShape) {
        Row(Modifier.height(DashSize.Bar).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(
                Modifier.size(14.dp).drawBehind {
                    val w = PAPER_RULE.toPx()
                    val on = when (state) {
                        ObdConnectionState.CONNECTED, ObdConnectionState.ERROR -> true
                        ObdConnectionState.CONNECTING -> blink?.value ?: true
                        ObdConnectionState.DISCONNECTED -> false
                    }
                    if (on) drawRect(color) else drawRect(paperInk, Offset(w / 2f, w / 2f), Size(size.width - w, size.height - w), style = Stroke(w))
                }
            )
            Spacer(Modifier.width(8.dp))
            PaperLine(
                stringResource(R.string.dash_obd_short),
                paperText(17f, if (state == ObdConnectionState.DISCONNECTED) DashColors.TextSecondary else DashColors.TextPrimary, FontWeight.Bold, caps = true),
                caps = true
            )
            if (state == ObdConnectionState.ERROR) PaperLine("!", paperText(17f, color, FontWeight.Black))
        }
    }
}

/**
 * TALK, boxed in ink: the paper's words on a solid ink block. It opens
 * Gemini Live (Dashwheel's own or Google's) when there is one, else the
 * unit's voice assistant. While a conversation is open the block turns red
 * and says END, blinking in steps, since it then tells the driver Gemini listens.
 */
@Composable
private fun PaperTalkButton() {
    val context = LocalContext.current
    val gemini = GeminiLive.rememberAvailable(context)
    val active by GeminiLive.active.collectAsState()
    val word = stringResource(if (active) R.string.ai_gemini_live_end else R.string.paper_talk)
    val description = stringResource(if (active) R.string.ai_gemini_live_end else R.string.paper_talk_cd)
    val blink = if (active) rememberBlink(700L) else null
    Box(
        modifier = Modifier
            .heightIn(min = DashSize.TouchPrimary)
            .widthIn(min = 92.dp)
            .drawBehind {
                val fill = if (active) DashColors.Critical.copy(alpha = if (blink?.value != false) 1f else 0.7f) else paperInk
                drawRoundRect(fill, cornerRadius = CornerRadius(PAPER_CORNER.toPx()))
            }
            .paperTap(true, description) { if (gemini || active) GeminiLive.toggle(context) else startVoiceAssistant(context) }
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        PaperLine(word, paperText(17f, if (active) Color.White else paperPage, FontWeight.Bold, caps = true), caps = true)
    }
}

/** The last music app used, else the app drawer to pick one. */
private fun paperOpenMusic(context: android.content.Context, orApps: () -> Unit) {
    val pkg = CarMediaController.getLastMediaPackage(context)
    val intent = pkg?.let { context.packageManager.getLaunchIntentForPackage(it) }
    if (intent == null || !context.launchSafely(intent)) orApps()
}

// --- Window frame ------------------------------------------------------------------

/** The frame over a docked app window: a 2 dp ink rule round it, square, nothing else. Never takes touches. */
@Composable
internal fun PaperWindowFrame(modifier: Modifier) {
    Box(modifier.fillMaxSize().paperFrame())
}

// --- Theme stamp -------------------------------------------------------------------

/**
 * The look on its card in Settings, Look: the grey page with a dithered
 * block of town, a street in its ink casing and the route taking the turn,
 * then the panel with a bold "21:07", its rules and a few hatched rain bars.
 * Everything follows the stamp's size, the dither's dots too.
 */
internal fun DrawScope.paperStamp(p: DashPalette, number: StampNumber) {
    val w = size.width
    val h = size.height
    val ink = p.TextPrimary
    val page = p.Background
    val blank = paperBlankFor(p.Light)
    val dot = max(1, (h / 110f).roundToInt())
    val mapW = w * 0.56f
    drawRect(page)
    // The town: a light dither, two darker blocks.
    drawRect(PaperTextures.dither(ink, page, 3, dot), Offset.Zero, Size(mapW, h))
    drawRect(PaperTextures.dither(ink, page, 7, dot), Offset(w * 0.04f, h * 0.08f), Size(w * 0.16f, h * 0.24f))
    drawRect(PaperTextures.dither(ink, page, 10, dot), Offset(w * 0.32f, h * 0.58f), Size(w * 0.18f, h * 0.3f))
    drawRect(PaperTextures.dither(ink, page, 6, dot), Offset(w * 0.04f, h * 0.6f), Size(w * 0.18f, h * 0.34f))
    // The streets: blank lines in ink casings.
    val street = h * 0.07f
    val casing = street + 2f * max(1f, h * 0.012f)
    val cross = h * 0.44f
    val x0 = w * 0.27f
    drawLine(ink, Offset(0f, cross), Offset(mapW, cross), casing)
    drawLine(ink, Offset(x0, 0f), Offset(x0, h), casing)
    drawLine(blank, Offset(0f, cross), Offset(mapW, cross), street)
    drawLine(blank, Offset(x0, 0f), Offset(x0, h), street)
    // The route taking the turn, and the arrow for the car.
    val route = Path().apply {
        moveTo(x0, h)
        lineTo(x0, cross)
        lineTo(0f, cross)
    }
    drawPath(route, ink, style = Stroke(street * 0.62f, join = StrokeJoin.Round, cap = StrokeCap.Butt))
    val a = h * 0.07f
    val tip = Offset(x0, h * 0.68f)
    val arrow = Path().apply {
        moveTo(tip.x, tip.y - a)
        lineTo(tip.x + a * 0.72f, tip.y + a * 0.72f)
        lineTo(tip.x, tip.y + a * 0.36f)
        lineTo(tip.x - a * 0.72f, tip.y + a * 0.72f)
        close()
    }
    drawPath(arrow, blank)
    drawPath(arrow, ink, style = Stroke(max(1f, h * 0.012f)))
    // The panel and its column.
    val rule = max(1f, h * 0.014f)
    drawRect(p.Card, Offset(mapW, 0f), Size(w - mapW, h))
    drawRect(ink, Offset(mapW, 0f), Size(rule, h))
    val col = mapW + w * 0.05f
    val colW = w - col - w * 0.05f
    number("21:07", col + colW / 2f, h * 0.26f, (h / density) * 0.2f, ink)
    drawRect(ink, Offset(col, h * 0.47f), Size(colW, rule))
    drawRect(ink, Offset(col, h * 0.56f), Size(colW * 0.42f, rule * 1.6f))
    drawRect(ink, Offset(col + colW * 0.6f, h * 0.56f), Size(colW * 0.4f, rule * 1.6f))
    drawRect(ink, Offset(col, h * 0.66f), Size(colW, rule))
    // Rain: six hatched bars.
    val bars = floatArrayOf(0.2f, 0.6f, 1f, 0.75f, 0.25f, 0.1f)
    val barW = colW / 6f
    val base = h * 0.92f
    val tall = h * 0.18f
    val hatch = PaperTextures.hatch(ink, Color.Transparent, max(3, dot * 4), max(1, dot), 1)
    bars.forEachIndexed { i, v ->
        val bh = tall * v
        val x = col + i * barW + barW * 0.1f
        drawRect(hatch, Offset(x, base - bh), Size(barW * 0.8f, bh))
        drawRect(ink, Offset(x, base - bh), Size(barW * 0.8f, bh), style = Stroke(max(1f, rule * 0.7f)))
    }
}
