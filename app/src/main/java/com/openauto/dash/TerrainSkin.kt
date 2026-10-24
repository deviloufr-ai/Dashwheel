package com.openauto.dash

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import java.util.Locale
import kotlin.math.min

/*
 * Terrain skin (2026-10-13 trends, after RivianOS 2.0 on Unreal Engine): the
 * car itself is the middle of the screen, standing on a pad in a landscape of
 * contour lines; a dock down the side (the rail, CanvasSkin.kt), an outdoor
 * look with the real weather beside it. The contour landscape is decoration,
 * drawn once from noise seeded by the car's rough position
 * (TerrainSkinMap.kt): it is not the shape of the land. The car and its
 * problems pinned on it are in TerrainSkinCar.kt, the other widgets in
 * TerrainSkinTiles.kt.
 *
 * Cards are smoked glass: a dark olive fill a little see-through, a sand
 * hairline, a deep soft shadow; labels in spaced mono capitals, numerals light
 * and large, amber for what is live. By day the same on sand paper with
 * brown ink. Android 10 cannot blur, so smoke is a translucent fill.
 */

internal val TerrainPalette = DashPalette(
    Background = Color(0xFF141611), Bar = Color.Transparent, Card = Color(0xFF181A13), CardHi = Color(0x1FE8D6AA),
    Accent = Color(0xFFFFAD33), Secondary = Color(0xFF6FB2D8), Critical = Color(0xFFFF5A46), Good = Color(0xFF7FD69A),
    Muted = Color(0xFFA59C84), TextPrimary = Color(0xFFF2ECDF), TextSecondary = Color(0xFFE5DCC4),
    Accent2 = Color(0xFFFFD27A), Line = Color(0x29E8D6AA), Glow = 0.35f, Bare = true,
    BackgroundStops = listOf(Color(0xFF1A1C15), Color(0xFF141611), Color(0xFF0D0E0A)),
    Tacho = Color(0xFFFFAD33),
    Font = DashFont.SANS,
    HeroWeight = FontWeight.Light,
    Skin = DashSkin.TERRAIN
)

internal val TerrainLightPalette = DashPalette(
    Background = Color(0xFFEFE9DC), Bar = Color.Transparent, Card = Color(0xFFFBF8F1), CardHi = Color(0x1A5A4A28),
    Accent = Color(0xFFB0600A), Secondary = Color(0xFF2F78A8), Critical = Color(0xFFC62828), Good = Color(0xFF2E7D4F),
    Muted = Color(0xFF6D6650), TextPrimary = Color(0xFF1F1D16), TextSecondary = Color(0xFF4A4535),
    Accent2 = Color(0xFFD08A2A), Line = Color(0x33403A28), Glow = 0f, Bare = true, Light = true,
    BackgroundStops = listOf(Color(0xFFF5F0E5), Color(0xFFEFE9DC), Color(0xFFE2DAC8)),
    Tacho = Color(0xFFB0600A),
    Font = DashFont.SANS,
    HeroWeight = FontWeight.Light,
    Skin = DashSkin.TERRAIN
)

/** The skin's corners (dialogs, menus, tiles being arranged). */
internal val TerrainShapes = skinShapes(22, 22, 16)

/**
 * The Home Terrain was drawn around (SkinLayouts.kt): the car large in the
 * middle left with the next turn under it, the weather, the engine and the
 * music down the right. The rail stands at the side, outside the grid.
 *
 * ```
 *   0 1 2 3 4 5 6 7 | 8 9 10 11
 * 0 R R R R R R R R | W W W  W
 * 1 R R R R R R R R | W W W  W
 * 2 R R R R R R R R | W W W  W
 * 3 R R R R R R R R | T T T  T
 * 4 R R R R R R R R | T T T  T
 * 5 N N N N N . . . | M M M  M
 * 6 N N N N N . . . | M M M  M
 * ```
 */
internal val TerrainLayout = SkinLayout(
    home = TemplatePage(listOf(BuiltinKind.RANGE, BuiltinKind.NAVIGATION, BuiltinKind.WEATHER, BuiltinKind.MEDIA, BuiltinKind.TELEMETRY)),
    wide = listOf(
        DashboardItem.BuiltinWidget(BuiltinKind.RANGE, x = 0, y = 0, w = 8, h = 5),
        DashboardItem.BuiltinWidget(BuiltinKind.NAVIGATION, x = 0, y = 5, w = 5, h = 2),
        DashboardItem.BuiltinWidget(BuiltinKind.WEATHER, x = 8, y = 0, w = 4, h = 3),
        DashboardItem.BuiltinWidget(BuiltinKind.TELEMETRY, x = 8, y = 3, w = 4, h = 2),
        DashboardItem.BuiltinWidget(BuiltinKind.MEDIA, x = 8, y = 5, w = 4, h = 2)
    )
)

// --- Shared look ------------------------------------------------------------------

/** Corners of the smoked cards. */
internal val TR_CARD = 22.dp

private const val DOT = " · "

/** The engraved mono labels' ink: sand at night, the muted brown by day. */
internal val trLabelInk: Color get() = if (DashColors.Light) Color(0xFF6D6650) else Color(0xFFC2B89D)

/** The sand hairline round the smoked cards. */
internal val trRim: Color get() = if (DashColors.Light) Color(0x38403A28) else Color(0x29E8D6AA)

/** Ink on the amber (the turn's block, Talk): the night's olive black, white by day. */
internal val trOnAmber: Color get() = if (DashColors.Light) Color.White else Color(0xFF141611)

/** Glow strength: none by day, where a glow only smears in the sun, and none with effects off. */
internal val trGlow: Float get() = if (DashColors.Light) 0f else DashColors.Effects.scale

/** Rain hours' blue in the forecast bars. */
internal val trRain: Color get() = if (DashColors.Light) Color(0xFF2F78A8) else Color(0xFF6FB2D8)

/** Dry hours' olive in the forecast bars. */
internal val trDry: Color get() = if (DashColors.Light) Color(0xFFB9B092) else Color(0xFF5E6A4C)

/** Big figures centred in their line box, so large numbers sit where they are put. */
private val FigureLineHeight = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

/**
 * The skin's type at [size] dp whatever the font scale: the plain sans for
 * words, light and tight for [figure]s, or with [mono] the spaced mono
 * capitals of the labels and the header line.
 */
@Composable
internal fun trText(
    size: Float,
    color: Color = DashColors.TextPrimary,
    weight: FontWeight = FontWeight.Normal,
    figure: Boolean = false,
    mono: Boolean = false
): TextStyle = TextStyle(
    color = color,
    fontFamily = if (mono) FontFamily.Monospace else FontFamily.SansSerif,
    fontWeight = weight,
    fontSize = fixedSp(if (mono) maxOf(size, MIN_CAPS_DP) else size),
    fontFeatureSettings = "tnum",
    lineHeight = if (figure) 1.05.em else 1.24.em,
    lineHeightStyle = if (figure) FigureLineHeight else null,
    letterSpacing = when {
        mono -> 0.14.em
        figure -> (-0.03).em
        else -> 0.em
    }
)

/** One run of the skin's text: one line with an ellipsis unless [maxLines] says more; [caps] upper-cases it. */
@Composable
internal fun TrLine(
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

/** The cards' label: spaced mono capitals in sand ("WEATHER · NEXT 6 H"). */
@Composable
internal fun TrLabel(text: String, modifier: Modifier = Modifier, size: Float = 14f, color: Color = trLabelInk, align: TextAlign? = null) {
    TrLine(text, trText(size, color, FontWeight.Medium, mono = true), modifier, align = align, caps = true)
}

/** "A · B" for the labels. */
internal fun trJoin(vararg parts: String): String = parts.filter { it.isNotBlank() }.joinToString(DOT)

/**
 * A smoked card behind the content, clipped to its corners: the dark olive
 * glass a little see-through, a sand hairline and a deep soft shadow of
 * stacked faint shapes; by day a sand-white pane on a brown shadow. Effects
 * off: the solid card and a firmer rim. While the tile is being arranged its
 * rim is dashed, so it reads as movable.
 */
@Composable
internal fun Modifier.trCard(radius: Dp = TR_CARD, editing: Boolean = false): Modifier {
    val light = DashColors.Light
    val effects = DashColors.Effects
    val card = DashColors.Card
    val rimInk = trRim
    val shape = remember(radius) { RoundedCornerShape(radius) }
    return this
        .cachedDraw(light, effects, card, rimInk, editing, radius) {
            val w = size.width
            val h = size.height
            val r = radius.toPx().coerceAtMost(min(w, h) / 2f)
            val plain = effects == DashEffects.NONE
            val fill: Brush = when {
                plain -> SolidColor(card)
                light -> SolidColor(Color(0xEBFBF8F1))
                else -> Brush.verticalGradient(listOf(Color(0xCC191B14), Color(0xC2121410)))
            }
            val rim = rimInk.let { if (plain) it.copy(alpha = (it.alpha * 1.8f).coerceAtMost(1f)) else it }
                .let { if (editing) it.copy(alpha = (it.alpha * 2.4f).coerceAtMost(0.9f)) else it }
            val rimW = (if (editing) 1.5f else 1f) * density
            val rimStroke = Stroke(rimW, pathEffect = if (editing) PathEffect.dashPathEffect(floatArrayOf(10f * density, 7f * density)) else null)
            val half = rimW / 2f
            // The design's 0 18px 40px shadow: four faint rounded shapes, each wider and lower than the last.
            val shadowInk = if (light) Color(0xFF3A2E14) else Color.Black
            val shadow = when {
                plain -> null
                light -> floatArrayOf(2f, 4f, 0.03f, 6f, 9f, 0.025f, 11f, 14f, 0.018f, 17f, 19f, 0.012f)
                else -> floatArrayOf(2f, 5f, 0.13f, 7f, 10f, 0.09f, 12f, 15f, 0.06f, 18f, 20f, 0.035f)
            }
            onDrawBehind {
                if (shadow != null) {
                    var i = 0
                    while (i < shadow.size) {
                        val k = shadow[i] * density
                        val dy = shadow[i + 1] * density
                        drawRoundRect(shadowInk.copy(alpha = shadow[i + 2]), Offset(-k, -k + dy), Size(w + 2f * k, h + 2f * k), CornerRadius(r + k))
                        i += 3
                    }
                }
                drawRoundRect(fill, cornerRadius = CornerRadius(r))
                drawRoundRect(rim, Offset(half, half), Size(w - rimW, h - rimW), CornerRadius(r - half), style = rimStroke)
            }
        }
        .clip(shape)
}

/** Whole card tappable with the tap feedback, or nothing when [enabled] is false (arranging, nothing to do). */
@Composable
internal fun Modifier.trTap(enabled: Boolean, label: String, onClick: () -> Unit): Modifier {
    if (!enabled) return this
    val tap = rememberTapFeedback()
    return clickable(onClickLabel = label, role = Role.Button) { tap(); onClick() }
}

/** The smoked card behind a widget Terrain does not redraw ([SkinFallbackPanel]). */
@Composable
internal fun Modifier.terrainFallbackPanel(): Modifier = trCard()

// --- Background ---------------------------------------------------------------------

/** The page: the contour landscape (TerrainSkinMap.kt). */
@Composable
internal fun terrainBackground(): Modifier = terrainMapBackground()

// --- Bar ---------------------------------------------------------------------------

/** The slim bar's height. */
private val BAR_HEIGHT = 64.dp

/**
 * The bar, for when the driver turned the rail off: a slim strip of smoked
 * glass. Apps and the layout on the left, Talk beside them, the time and the
 * dashboard's position in the middle, and at the end the alerts, the phone,
 * the setup pill, the OBD link and ⋮, room handed out by rank.
 */
@Composable
internal fun TerrainTopBar(m: TopBarModel) {
    val now = rememberNow(60_000L)
    val dateFmt = rememberDateFormat("EEEdMMM", best = true)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(BAR_HEIGHT)
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .graphicsLayer()
    ) {
        val narrow = maxWidth < NARROW_BAR
        val labels = maxWidth >= LABELLED_BAR && !narrow
        val shared = barIsShared()
        val showDate = maxWidth >= 640.dp && !(narrow && (shared || m.setupPending))
        Row(
            modifier = Modifier.fillMaxSize().trCard(18.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(modifier = if (narrow) Modifier else Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                TrBarKey(onClick = m.onApps, description = stringResource(R.string.dash_all_apps), label = if (labels) stringResource(R.string.dash_apps) else null) {
                    Icon(Icons.Filled.Apps, contentDescription = null, tint = DashColors.Accent, modifier = Modifier.size(26.dp))
                }
                LayoutPicker(m) { open ->
                    TrBarKey(onClick = open, description = stringResource(R.string.dash_screen_layout, m.layout.title), label = if (labels) stringResource(R.string.dash_layout_button) else null) {
                        LayoutIcon(m.layout, null, DashColors.TextSecondary, Modifier.size(24.dp))
                    }
                }
                TrTalkKey()
            }
            // The head unit's status bar shows the time while it is up.
            Row(modifier = Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!m.merged && !ClockInSight.on.value) {
                    val (digits, amPm) = splitClock(m.clock)
                    TrLine(digits, trText(26f, DashColors.TextPrimary, FontWeight.Medium), Modifier.alignByBaseline(), overflow = TextOverflow.Clip)
                    if (amPm != null) {
                        Spacer(Modifier.width(4.dp))
                        TrLine(amPm, trText(14f, trLabelInk, mono = true), Modifier.alignByBaseline())
                    }
                    if (showDate) {
                        Spacer(Modifier.width(12.dp))
                        TrLine(dateFmt.format(now), trText(14f, trLabelInk, FontWeight.Medium, mono = true), Modifier.alignByBaseline(), caps = true)
                    }
                }
                PagePositionChip(Modifier.padding(start = 10.dp))
            }
            BarEnd(modifier = Modifier.weight(1f)) {
                if (m.demo) {
                    Box(Modifier.layoutId(BarRank.DEMO).padding(end = 6.dp)) { DemoBadge(onStop = m.onDemo, compact = true) }
                }
                if (m.setupPending) {
                    Box(Modifier.layoutId(BarRank.SETUP).padding(end = 6.dp)) { SetupPill(onClick = { m.onSetup(false) }, compact = narrow || shared) }
                }
                Row(modifier = Modifier.layoutId(BarRank.ALERTS), verticalAlignment = Alignment.CenterVertically) {
                    VehicleAlerts()
                }
                Box(Modifier.layoutId(BarRank.PHONE).padding(end = 6.dp)) { PhonePill() }
                Box(Modifier.layoutId(BarRank.OBD).fillMaxHeight(), contentAlignment = Alignment.Center) { ObdPill(m.obdConnection, m.onConnectObd) }
                Box(Modifier.layoutId(BarRank.MORE).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    MorePicker(m, geminiInBar = true) { open ->
                        TrBarKey(onClick = open, description = stringResource(R.string.dash_more)) {
                            Icon(Icons.Filled.MoreVert, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
        }
    }
}

/** A key of the bar, 52 dp each way at least: the icon in a rounded square, with its [label] where there is room. */
@Composable
private fun TrBarKey(onClick: () -> Unit, description: String, label: String? = null, icon: @Composable RowScope.() -> Unit) {
    val tap = rememberTapFeedback()
    Row(
        modifier = Modifier
            .heightIn(min = 52.dp)
            .widthIn(min = 52.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClickLabel = description) { tap(); onClick() }
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        icon()
        if (label != null) {
            Spacer(Modifier.width(8.dp))
            TrLine(label, trText(16f, DashColors.TextPrimary, FontWeight.Medium))
        }
    }
}

/**
 * Talk: the microphone in an amber-ringed key. It opens Gemini Live when
 * there is one, else the unit's voice assistant; red with an end mark while
 * a conversation is open.
 */
@Composable
private fun TrTalkKey() {
    val context = LocalContext.current
    val gemini = remember { GeminiLive.available(context) }
    val active by GeminiLive.active.collectAsState()
    val description = stringResource(if (active) R.string.ai_gemini_live_end else R.string.terrain_talk_cd)
    val ink = if (active) DashColors.Critical else DashColors.Accent
    TrBarKey(onClick = { if (gemini || active) GeminiLive.toggle(context) else startVoiceAssistant(context) }, description = description) {
        Icon(if (active) Icons.Filled.Close else Icons.Filled.Mic, contentDescription = null, tint = ink, modifier = Modifier.size(26.dp))
    }
}

// --- Window frame -------------------------------------------------------------------

/**
 * The frame over a docked app window: its corners rounded off in the olive
 * of the page, a smoked band along its edges that fades inward and a sand
 * hairline, so the map reads as one more card of the landscape. The middle
 * stays clear and it takes no touches.
 */
@Composable
internal fun TerrainWindowFrame(modifier: Modifier) {
    val light = DashColors.Light
    val effects = DashColors.Effects
    val rimInk = trRim
    val page = DashColors.Background
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawWithCache {
                val w = size.width
                val h = size.height
                val r = TR_CARD.toPx()
                val mask = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(0f, 0f, w, h))
                    addRoundRect(RoundRect(Rect(0f, 0f, w, h), CornerRadius(r)))
                }
                val band = 6.dp.toPx()
                val smoke = if (effects != DashEffects.NONE) (if (light) Color(0x22403A28) else Color(0x66141611)) else Color.Transparent
                val rimStroke = Stroke(1.dp.toPx())
                val bandStroke = Stroke(band)
                val half = rimStroke.width / 2f
                onDrawBehind {
                    drawPath(mask, page)
                    if (smoke.alpha > 0f) {
                        drawRoundRect(smoke, Offset(band / 2f, band / 2f), Size(w - band, h - band), CornerRadius(r - band / 2f), style = bandStroke)
                    }
                    drawRoundRect(rimInk.copy(alpha = (rimInk.alpha * 1.6f).coerceAtMost(1f)), Offset(half, half), Size(w - 2f * half, h - 2f * half), CornerRadius(r - half), style = rimStroke)
                }
            }
    )
}

// --- Theme stamp ------------------------------------------------------------------------

/**
 * The skin as a stamp in the theme picker (ThemePane): contour lines on the
 * olive (or sand) page, the car as a block on its pad with a red glow at its
 * rear lamp, the dock down the left with its amber key, and a smoked card.
 */
internal fun DrawScope.terrainStamp(p: DashPalette, number: StampNumber) {
    val w = size.width
    val h = size.height
    val light = p.Light
    val bg = if (light) Color(0xFFEFE9DC) else Color(0xFF141611)
    drawRect(bg)
    // Contours: loops round two hills, the 5th one brighter.
    val thin = if (light) Color(0x59705A32) else Color(0x4DB9A878)
    val major = if (light) Color(0x998A6A2A) else Color(0x80D8BE82)
    val hills = listOf(Offset(w * 0.72f, h * 0.12f) to w * 0.06f, Offset(w * 0.22f, h * 0.2f) to w * 0.05f)
    for ((c, r0) in hills) {
        for (k in 0 until 7) {
            val r = r0 + k * w * 0.035f
            scale(1.7f, 0.62f, c) {
                drawCircle(if (k % 5 == 4) major else thin, r, c, style = Stroke(if (k % 5 == 4) 1.4f else 0.9f))
            }
        }
    }
    // The pad and the car.
    val pad = Offset(w * 0.46f, h * 0.66f)
    scale(1f, 0.32f, pad) {
        drawCircle(Brush.radialGradient(listOf(Color.Black.copy(alpha = if (light) 0.18f else 0.55f), Color.Transparent), pad, w * 0.2f), w * 0.2f, pad)
    }
    val car = Rect(w * 0.3f, h * 0.42f, w * 0.62f, h * 0.66f)
    drawRoundRect(if (light) Color(0xFF8E8676) else Color(0xFFB7B2A6), car.topLeft, car.size, CornerRadius(h * 0.06f))
    drawRoundRect(if (light) Color(0xFF2B2A24) else Color(0xFF0B0D0C), Offset(car.left + car.width * 0.18f, car.top + car.height * 0.12f), Size(car.width * 0.62f, car.height * 0.34f), CornerRadius(h * 0.03f))
    val lamp = Offset(car.left + car.width * 0.04f, car.top + car.height * 0.45f)
    drawCircle(Brush.radialGradient(listOf(Color(0xFFFF3C28).copy(alpha = 0.65f), Color.Transparent), lamp, h * 0.16f), h * 0.16f, lamp)
    drawCircle(Color(0xFFFF4A32), h * 0.025f, lamp)
    drawLine(Color(0xFFFF5A46).copy(alpha = 0.8f), lamp, Offset(lamp.x, h * 0.26f), 1.2f, StrokeCap.Round)
    drawRoundRect(if (light) Color(0xEBFBF8F1) else Color(0xE6141611), Offset(lamp.x - w * 0.02f, h * 0.17f), Size(w * 0.2f, h * 0.09f), CornerRadius(h * 0.03f))
    drawRoundRect(Color(0xFFFF5A46).copy(alpha = 0.7f), Offset(lamp.x - w * 0.02f, h * 0.17f), Size(w * 0.2f, h * 0.09f), CornerRadius(h * 0.03f), style = Stroke(1f))
    // The dock.
    drawRect(if (light) Color(0xF2E6DFCF) else Color(0xE00E0F0B), Offset.Zero, Size(w * 0.09f, h))
    drawRoundRect(p.Accent.copy(alpha = 0.22f), Offset(w * 0.015f, h * 0.06f), Size(w * 0.06f, w * 0.06f), CornerRadius(w * 0.015f))
    drawRoundRect(p.Accent, Offset(w * 0.03f, h * 0.06f + w * 0.022f), Size(w * 0.03f, w * 0.016f), CornerRadius(w * 0.005f))
    for (i in 1..3) drawCircle(p.TextSecondary.copy(alpha = 0.5f), w * 0.012f, Offset(w * 0.045f, h * (0.12f + i * 0.13f)))
    // A smoked card at the right with the range.
    val cardTl = Offset(w * 0.72f, h * 0.38f)
    val cardSize = Size(w * 0.24f, h * 0.3f)
    drawRoundRect(if (light) Color(0xEBFBF8F1) else Color(0xCC191B14), cardTl, cardSize, CornerRadius(h * 0.05f))
    drawRoundRect(if (light) Color(0x38403A28) else Color(0x40E8D6AA), cardTl, cardSize, CornerRadius(h * 0.05f), style = Stroke(1f))
    number("412", cardTl.x + cardSize.width * 0.5f, cardTl.y + cardSize.height * 0.42f, 11f, p.TextPrimary)
    drawRoundRect(p.TextPrimary.copy(alpha = 0.12f), Offset(cardTl.x + cardSize.width * 0.12f, cardTl.y + cardSize.height * 0.74f), Size(cardSize.width * 0.76f, h * 0.025f), CornerRadius(h * 0.0125f))
    drawRoundRect(Brush.horizontalGradient(listOf(p.Accent, p.Accent2)), Offset(cardTl.x + cardSize.width * 0.12f, cardTl.y + cardSize.height * 0.74f), Size(cardSize.width * 0.47f, h * 0.025f), CornerRadius(h * 0.0125f))
}
