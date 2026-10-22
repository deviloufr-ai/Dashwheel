package com.openauto.dash

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/*
 * Liquid skin, the tiles (the look itself is in LiquidSkin.kt, the car in
 * LiquidSkinCar.kt): every reading on Liquid glass. The next turn on a card
 * with its arrow in a glass key, the speed in a round glass disc with the
 * road's sign over its rim, the music on a tall card with its cover and a
 * bright white play key, the engine's readings on a chip card, the clock and
 * the weather on cards in the same language, apps as glass keys.
 */

private const val DOT = " · "

/** Room around a card's content, following the tile's smaller side. */
private fun padFor(w: Dp, h: Dp): Dp = (min(w.value, h.value) * 0.08f).coerceIn(14f, 24f).dp

/**
 * Liquid renderer for app shortcuts, launch bars and the main widgets.
 * Anything else keeps its standard renderer on the fallback glass.
 */
@Composable
internal fun LiquidTile(item: DashboardItem, env: SkinTileEnv) {
    when (item) {
        is DashboardItem.AppShortcut -> LqApp(item, env)
        is DashboardItem.LaunchBar -> LqLaunchBar(item, env)
        is DashboardItem.BuiltinWidget -> when (item.kind) {
            BuiltinKind.TELEMETRY -> LqTelemetry(env)
            BuiltinKind.SPEED_HUD -> LqSpeed(env)
            BuiltinKind.MEDIA -> LqMedia(env)
            BuiltinKind.NAVIGATION -> LqDirections(env)
            BuiltinKind.CLOCK -> LqClock(env)
            BuiltinKind.WEATHER -> LqWeather(env)
            BuiltinKind.RANGE -> LqRange(item, env)
            else -> StandardSkinnedTile(item, env)
        }
        else -> StandardSkinnedTile(item, env)
    }
}

// --- Small pieces -----------------------------------------------------------------

/** Largest size (dp, for [lqText]) within [minSize]..[maxSize] at which [sample] fits [maxW] x [maxH] on one line. */
@Composable
internal fun lqFit(sample: String, weight: FontWeight, maxW: Dp, maxH: Dp, minSize: Float, maxSize: Float, figure: Boolean = true): Float {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = lqText(100f, weight = weight, figure = figure)
    return remember(sample, style, maxW, maxH, minSize, maxSize, density) {
        val probe = measurer.measure(sample, style.copy(fontSize = (100f / density.fontScale).sp), softWrap = false, maxLines = 1)
        val byW = with(density) { maxW.toPx() } * 100f / probe.size.width.coerceAtLeast(1)
        val byH = with(density) { maxH.toPx() } * 100f / probe.size.height.coerceAtLeast(1)
        min(byW, byH).coerceIn(minSize, maxSize.coerceAtLeast(minSize))
    }
}

/** Digits replaced by '8' so a readout keeps one size while its value changes. */
private fun template(text: String): String = text.map { if (it.isDigit()) '8' else it }.joinToString("")

/** A rounded glass key with an icon, for the cards' quiet states and the turn arrow. */
@Composable
private fun LqGlyph(icon: ImageVector, size: Dp) {
    Box(Modifier.size(size).liquidKey(size * 0.34f), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = DashColors.TextPrimary, modifier = Modifier.size(size * 0.5f))
    }
}

/**
 * A quiet state on its card: a glass key with an icon, a title and one hint;
 * the hint is in the accent's ink when a tap does something, and the whole
 * card is the target.
 */
@Composable
private fun LqEmpty(icon: ImageVector, title: String, hint: String, editing: Boolean, onTap: (() -> Unit)?) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .liquidGlass(editing = editing)
            .lqTap(onTap != null && !editing, hint) { onTap?.invoke() },
        contentAlignment = Alignment.CenterStart
    ) {
        val pad = padFor(maxWidth, maxHeight)
        val wide = maxWidth >= maxHeight * 1.3f
        val iconD = min(maxHeight.value * 0.3f, maxWidth.value * 0.22f).coerceIn(44f, 72f).dp
        val textW = if (wide) maxWidth - pad * 2 - iconD - 16.dp else maxWidth - pad * 2
        val titleSize = min(maxHeight.value * 0.12f, textW.value / (title.length * 0.42f + 1f)).coerceIn(17f, 26f)
        val hintSize = (titleSize * 0.74f).coerceIn(14f, 19f)
        val words: @Composable () -> Unit = {
            Column {
                LqLine(title, lqText(titleSize, DashColors.TextPrimary, FontWeight.SemiBold), maxLines = 2)
                Spacer(Modifier.height(4.dp))
                LqLine(hint, lqText(hintSize, if (onTap != null) DashColors.AccentInk else lqSoft), maxLines = 2)
            }
        }
        if (wide) {
            Row(Modifier.padding(pad), verticalAlignment = Alignment.CenterVertically) {
                LqGlyph(icon, iconD)
                Spacer(Modifier.width(16.dp))
                words()
            }
        } else {
            val roomy = maxHeight >= 190.dp
            Column(Modifier.padding(pad)) {
                if (roomy) {
                    LqGlyph(icon, iconD)
                    Spacer(Modifier.height(14.dp))
                }
                words()
            }
        }
    }
}

/** A capsule button, at least 56 dp tall: [filled] bright for the one thing to do, else a glass key. */
@Composable
private fun LqPillButton(text: String, filled: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    val fill = lqBright
    Box(
        modifier = Modifier
            .heightIn(min = DashSize.TouchPrimary)
            .then(
                if (filled) Modifier
                    .cachedDraw(fill) { onDrawBehind { drawRoundRect(fill, cornerRadius = CornerRadius(size.height / 2f)) } }
                    .clip(RoundedCornerShape(50))
                else Modifier.liquidKey(100.dp)
            )
            .clickable(enabled = enabled, role = Role.Button) { tap(); onClick() }
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center
    ) {
        LqLine(text, lqText(18f, if (filled) lqOnBright else DashColors.TextPrimary, FontWeight.SemiBold))
    }
}

/** A reading of the chip cards: its name small and faded over its value large, as in the design. */
@Composable
internal fun LqReading(label: String, value: String, warn: Boolean, size: Float, modifier: Modifier = Modifier) {
    Column(modifier) {
        LqLine(label, lqText((size * 0.48f).coerceAtLeast(14f), lqSoft))
        LqLine(value, lqText(size, if (warn) DashColors.Warning else DashColors.TextPrimary, FontWeight.SemiBold, figure = true), overflow = TextOverflow.Clip)
    }
}

// --- Speed: the glass disc ----------------------------------------------------------

/**
 * Speed: a round glass disc with the speed large and its unit under it, and
 * the road's limit sign overlapping its rim at the top right when it is
 * known. A thin ring inside the rim glows in the accent, amber past the
 * limit. Without a speed, a dash; a tap then connects the adapter.
 */
@Composable
private fun LqSpeed(env: SkinTileEnv) {
    val context = env.context
    DisposableEffect(Unit) {
        SpeedLimit.start(context)
        onDispose { SpeedLimit.stop() }
    }
    val speed = rememberSpeedKmh(env)
    val limit by SpeedLimit.limit.collectAsState()
    val units = LocalUnits.current
    val over = speedOver(speed)
    val color = when {
        speed == null -> DashColors.Muted
        over -> DashColors.Warning
        else -> DashColors.TextPrimary
    }
    val canConnect = speed == null && env.obdConnection.isIdle && !env.editing
    val said = speed?.let { "${units.speed(it)} ${units.speedUnit}" } ?: stringResource(R.string.info_speed_no_signal)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The disc and the sign's overhang: 1.16 discs wide, one high.
        val d = min(maxHeight.value * 0.96f, maxWidth.value / 1.16f).coerceAtLeast(72f)
        val r = d / 2f
        val left = ((maxWidth.value - d * 1.16f) / 2f).coerceAtLeast(0f)
        val top = ((maxHeight.value - d) / 2f).coerceAtLeast(0f)
        val ringInk = if (over) DashColors.Warning else DashColors.Accent
        val glowK = if (DashColors.Light) 0f else DashColors.Effects.scale
        Box(
            Modifier
                .offset(left.dp, top.dp)
                .size(d.dp)
                .liquidGlass((d / 2f).dp, editing = env.editing)
                .cachedDraw(ringInk, glowK, speed != null) {
                    val inset = size.minDimension * 0.07f
                    val rr = size.minDimension / 2f - inset
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val ringW = 2.dp.toPx()
                    val live = speed != null
                    onDrawBehind {
                        if (live && glowK > 0f) drawCircle(ringInk.copy(alpha = 0.14f * glowK), rr, c, style = Stroke(ringW * 5f))
                        drawCircle(ringInk.copy(alpha = if (live) 0.7f else 0.25f), rr, c, style = Stroke(ringW))
                    }
                }
                .lqTap(canConnect, stringResource(R.string.horizon_connect_obd), env.onConnectObd)
                .semantics(mergeDescendants = true) { contentDescription = said },
            contentAlignment = Alignment.Center
        ) {
            val numSize = lqFit("188", FontWeight.SemiBold, (d * 0.62f).dp, (d * 0.42f).dp, 24f, 140f)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                LqLine(
                    speed?.let { units.speed(it).toString() } ?: "–",
                    lqText(if (speed != null) numSize else numSize * 0.7f, color, FontWeight.SemiBold, figure = true),
                    overflow = TextOverflow.Clip
                )
                LqLine(units.speedUnit, lqText((d * 0.09f).coerceIn(14f, 22f), lqSoft))
            }
        }
        limit?.let {
            // Over the rim at the top right, as in the design.
            val sign = d * 0.32f
            val a = Math.toRadians(-33.5)
            val cx = left + r + (cos(a) * r * 1.19f).toFloat()
            val cy = top + r + (sin(a) * r * 1.19f).toFloat()
            LqLimitSign(units.speed(it), sign.dp, Modifier.offset((cx - sign / 2f).dp, (cy - sign / 2f).coerceAtLeast(0f).dp))
        }
    }
}

/** The road's limit as its sign: a white disc in a red ring, the number in black, on a soft shadow (a road sign keeps its colours day and night). */
@Composable
private fun LqLimitSign(limit: Int, d: Dp, modifier: Modifier) {
    val said = stringResource(R.string.liquid_speed_limit_cd, limit)
    val shadow = DashColors.Effects != DashEffects.NONE
    Box(
        modifier = modifier
            .size(d)
            .semantics { contentDescription = said }
            .cachedDraw(d, shadow) {
                val ring = size.minDimension * 0.1f
                onDrawBehind {
                    val r = size.minDimension / 2f
                    if (shadow) {
                        for (k in 1..3) drawCircle(Color.Black.copy(alpha = 0.07f), r + k * 2f * density, center + Offset(0f, 4f * density))
                    }
                    drawCircle(Color.White, r)
                    drawCircle(Color(0xFFFF3B4A), r - ring / 2f, style = Stroke(ring))
                }
            },
        contentAlignment = Alignment.Center
    ) {
        LqLine(limit.toString(), lqText((d.value * if (limit >= 100) 0.32f else 0.38f).coerceAtLeast(14f), Color(0xFF111111), FontWeight.Bold), overflow = TextOverflow.Clip)
    }
}

// --- Directions ---------------------------------------------------------------------

private val CLOCK_PART = Regex("""^\d{1,2}[:h.]\d{2}(\s?[AaPp]\.?[Mm]\.?)?$""")
private val DISTANCE_PART = Regex("""\d+(?:[.,]\d+)?\s?(km|m|mi|ft|yd)\b""")

/**
 * The next turn's arrow, as in the design: white strokes in a rounded glass
 * key, the navigation's own glyph tinted when it gives one.
 */
@Composable
private fun LqTurnKey(nav: NavState, size: Dp) {
    val ink = DashColors.TextPrimary
    val bitmap = remember(nav.icon) { nav.icon?.asImageBitmap() }
    val side = nav.turnSide()
    Box(Modifier.size(size).liquidKey(size * 0.34f), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(bitmap, contentDescription = nav.instruction, contentScale = ContentScale.Fit, colorFilter = ColorFilter.tint(ink), modifier = Modifier.fillMaxSize(0.62f))
        } else {
            Spacer(
                Modifier
                    .fillMaxSize(0.56f)
                    .semantics { contentDescription = nav.instruction }
                    .cachedDraw(side, ink) {
                        val s = this.size.minDimension / 24f
                        // The design's arrow in a 24 grid: up the stem, round the corner, out to the side, its head.
                        val path = Path().apply {
                            when (side) {
                                TurnSide.AHEAD -> {
                                    moveTo(12f * s, 21f * s); lineTo(12f * s, 4f * s)
                                    moveTo(7f * s, 9f * s); lineTo(12f * s, 4f * s); lineTo(17f * s, 9f * s)
                                }
                                else -> {
                                    val right = side == TurnSide.RIGHT
                                    fun x(v: Float) = (if (right) 24f - v else v) * s
                                    moveTo(x(17f), 21f * s); lineTo(x(17f), 11f * s)
                                    cubicTo(x(17f), 9.3f * s, x(15.7f), 8f * s, x(14f), 8f * s)
                                    lineTo(x(5f), 8f * s)
                                    moveTo(x(9f), 4f * s); lineTo(x(5f), 8f * s); lineTo(x(9f), 12f * s)
                                }
                            }
                        }
                        val stroke = Stroke(2.6f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        onDrawBehind { drawPath(path, ink, style = stroke) }
                    }
            )
        }
    }
}

/**
 * The next turn on a glass card, as in the design: the arrow in its glass
 * key, the distance large ("300 m"), the street, a hairline, then the
 * arrival ("Home 21:24" when the guidance was started here, else "Arrival
 * 21:24") and what is left (time, distance). A wide low tile puts the arrow
 * beside the words. Tap opens the navigation app. Without a route (or
 * without notification access) a quiet card says so and how to start.
 */
@Composable
private fun LqDirections(env: SkinTileEnv) {
    val nav by NavDirections.state.collectAsState()
    val context = env.context
    val canTap = !env.editing
    when {
        !env.hasMediaAccess -> LqEmpty(
            Icons.Filled.NotificationsActive,
            stringResource(R.string.info_directions_access_title), stringResource(R.string.horizon_tap_allow_notification_access), env.editing,
            if (canTap) ({ CarMediaController.openNotificationAccessSettings(context) }) else null
        )
        !nav.active -> LqEmpty(
            Icons.Filled.Navigation,
            stringResource(R.string.info_directions_no_route), stringResource(R.string.horizon_start_navigation), env.editing,
            if (canTap) ({ openNavigationApp(context, nav) }) else null
        )
        else -> {
            val arrivalWord = stringResource(R.string.liquid_arrival, "%s")
            val eta = remember(nav.eta, arrivalWord) { etaLine(nav, arrivalWord) }
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .liquidGlass(editing = env.editing)
                    .lqTap(canTap, stringResource(R.string.horizon_open_navigation)) { openNavigationApp(context, nav) }
            ) {
                val pad = padFor(maxWidth, maxHeight) + 2.dp
                val innerW = maxWidth - pad * 2
                val innerH = maxHeight - pad * 2
                val wide = maxWidth > maxHeight * 1.45f
                val (value, unit) = nav.distanceParts
                val streetSize = min(innerW.value / 13f, innerH.value * 0.085f).coerceIn(16f, 24f)
                val etaSize = (streetSize * 0.8f).coerceIn(14f, 19f)
                val sample = template(if (unit.isEmpty()) value.ifEmpty { "888" } else "$value $unit")
                if (wide) {
                    val key = (innerH * 0.5f).coerceIn(48.dp, 96.dp)
                    val textW = innerW - key - 20.dp
                    val distSize = lqFit(sample, FontWeight.SemiBold, textW * 0.8f, innerH * 0.4f, 24f, 80f)
                    Row(Modifier.fillMaxSize().padding(pad), verticalAlignment = Alignment.CenterVertically) {
                        LqTurnKey(nav, key)
                        Spacer(Modifier.width(20.dp))
                        TurnWords(nav, value, unit, distSize, streetSize, etaSize, eta, maxLines = 1, divider = innerH >= 130.dp)
                    }
                } else {
                    val key = min(innerW.value * 0.24f, innerH.value * 0.22f).coerceIn(48f, 72f).dp
                    val wordsH = innerH - key - 14.dp - (streetSize * 1.3f + etaSize * 1.4f + 30f).dp
                    val distSize = lqFit(sample, FontWeight.SemiBold, innerW * 0.86f, wordsH, 24f, 64f)
                    Column(Modifier.fillMaxSize().padding(pad)) {
                        LqTurnKey(nav, key)
                        Spacer(Modifier.weight(1f))
                        TurnWords(nav, value, unit, distSize, streetSize, etaSize, eta, maxLines = if (innerH > 320.dp) 2 else 1, divider = true)
                    }
                }
            }
        }
    }
}

/** The arrival and what is left, split for the card's last line: (where and when, how long and how far). */
private fun etaLine(nav: NavState, arrivalWord: String): Pair<Pair<String?, String>, String> {
    val parts = nav.etaParts
    val clock = parts.firstOrNull { CLOCK_PART.matches(it) }
    val distance = parts.firstOrNull { it != clock && DISTANCE_PART.containsMatchIn(it) }
    val duration = parts.firstOrNull { it != clock && it != distance }
    val now = System.currentTimeMillis()
    // The destination is only known when the guidance was started from Dashwheel.
    val place = NavHandoff.destination?.takeIf { now - it.second < OnMyWayText.DESTINATION_MS }?.first?.takeIf { it.isNotBlank() }
    val start: Pair<String?, String> = when {
        clock != null && place != null -> place to clock
        clock != null -> null to arrivalWord.replace("%s", clock)
        else -> null to (duration ?: "")
    }
    val end = listOfNotNull(duration.takeIf { clock != null }, distance).joinToString(DOT)
    return start to end
}

@Composable
private fun TurnWords(
    nav: NavState, value: String, unit: String, distSize: Float, streetSize: Float, etaSize: Float,
    eta: Pair<Pair<String?, String>, String>, maxLines: Int, divider: Boolean
) {
    val line = if (DashColors.Light) Color(0x33303080) else Color.White.copy(alpha = 0.25f)
    Column {
        if (value.isNotEmpty()) {
            Row {
                LqLine(value, lqText(distSize, DashColors.TextPrimary, FontWeight.SemiBold, figure = true), Modifier.alignByBaseline(), overflow = TextOverflow.Clip)
                if (unit.isNotEmpty()) {
                    Spacer(Modifier.width((distSize * 0.07f).dp))
                    LqLine(unit, lqText((distSize * 0.42f).coerceAtLeast(15f), lqSoft, FontWeight.Medium), Modifier.alignByBaseline())
                }
            }
            Spacer(Modifier.height(4.dp))
            LqLine(nav.instruction, lqText(streetSize, DashColors.TextPrimary), maxLines = maxLines)
        } else {
            LqLine(nav.instruction, lqText((streetSize * 1.4f).coerceAtMost(32f), DashColors.TextPrimary, FontWeight.SemiBold), maxLines = maxLines + 1)
        }
        val (start, end) = eta
        if (start.second.isNotEmpty() || end.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            if (divider) {
                Spacer(Modifier.fillMaxWidth().height(1.dp).cachedDraw(line) { onDrawBehind { drawRect(line) } })
                Spacer(Modifier.height(10.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (place, time) = start
                if (place != null) {
                    LqLine(place, lqText(etaSize, lqSoft), Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(6.dp))
                }
                LqLine(time, lqText(etaSize, lqSoft), overflow = TextOverflow.Clip)
                Spacer(Modifier.weight(1f).widthIn(min = 12.dp))
                if (end.isNotEmpty()) LqLine(end, lqText(etaSize, lqSoft), overflow = TextOverflow.Clip)
            }
        }
    }
}

// --- Media --------------------------------------------------------------------------

/**
 * Music on a tall glass card, as in the design: the cover across the top
 * with rounded corners on a soft shadow (or the design's own glow of pink and
 * blue with a lit line while the player gives none), the song and who sings
 * it, a thin progress line and three round keys, play / pause bright white.
 * A low tile becomes one row; a wide one puts the cover beside the words.
 */
@Composable
private fun LqMedia(env: SkinTileEnv) {
    val ms = env.mediaState
    val context = env.context
    if (!env.hasMediaAccess) {
        LqEmpty(
            Icons.Filled.MusicNote,
            stringResource(R.string.info_media_access_needed), stringResource(R.string.horizon_tap_allow_notification_access), env.editing,
            if (!env.editing) ({ CarMediaController.openNotificationAccessSettings(context) }) else null
        )
        return
    }
    val hasTrack = ms.hasMedia && ms.title.isNotBlank()
    val title = if (hasTrack) ms.title else stringResource(R.string.info_nothing_playing)
    val sub = if (hasTrack) ms.artist.ifBlank { stringResource(R.string.horizon_unknown_artist) } else stringResource(R.string.horizon_play_something)
    BoxWithConstraints(Modifier.fillMaxSize().liquidGlass(editing = env.editing)) {
        val pad = padFor(maxWidth, maxHeight)
        val innerW = maxWidth - pad * 2
        val innerH = maxHeight - pad * 2
        val compact = maxHeight < 180.dp
        val wide = !compact && maxWidth > maxHeight * 1.6f
        val key = (innerW.value / 5.2f).coerceIn(56f, 62f).dp
        when {
            compact -> Row(Modifier.fillMaxSize().padding(pad), verticalAlignment = Alignment.CenterVertically) {
                if (innerW >= 400.dp) {
                    LqCover(ms, Modifier.size(innerH.coerceIn(44.dp, 110.dp)))
                    Spacer(Modifier.width(16.dp))
                }
                Column(Modifier.weight(1f)) {
                    LqLine(title, lqText((innerH.value * 0.22f).coerceIn(17f, 24f), DashColors.TextPrimary, FontWeight.SemiBold))
                    LqLine(sub, lqText(15f, lqSoft))
                    if (ms.durationMs > 0L) {
                        Spacer(Modifier.height(8.dp))
                        LqProgress(ms, env.mediaController, Modifier.fillMaxWidth())
                    }
                }
                Spacer(Modifier.width(12.dp))
                LqKeys(env, 56.dp, prev = innerW >= 460.dp)
            }
            wide -> Row(Modifier.fillMaxSize().padding(pad), verticalAlignment = Alignment.CenterVertically) {
                val cover = min(innerH.value, innerW.value * 0.36f).dp
                LqCover(ms, Modifier.size(width = cover * 1.15f, height = cover))
                Spacer(Modifier.width(22.dp))
                Column(Modifier.weight(1f)) {
                    LqLine(title, lqText((innerH.value * 0.12f).coerceIn(18f, 28f), DashColors.TextPrimary, FontWeight.SemiBold), maxLines = 2)
                    LqLine(sub, lqText((innerH.value * 0.085f).coerceIn(15f, 19f), lqSoft))
                    Spacer(Modifier.height(14.dp))
                    LqProgress(ms, env.mediaController, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    LqKeys(env, key, prev = true)
                }
            }
            else -> Column(Modifier.fillMaxSize().padding(pad)) {
                val titleSize = (innerW.value / 11f).coerceIn(18f, 25f)
                val subSize = (innerW.value / 16f).coerceIn(15f, 18f)
                val textBlock = titleSize * 1.24f + subSize * 1.24f + 14f + 6f + 14f + key.value * 1.2f + 6f
                val coverH = (innerH.value - textBlock - 16f).coerceAtLeast(0f)
                if (coverH >= 60f) {
                    LqCover(ms, Modifier.fillMaxWidth().height(coverH.coerceAtMost(innerW.value * 0.85f).dp))
                    Spacer(Modifier.height(16.dp))
                }
                Spacer(Modifier.weight(1f))
                LqLine(title, lqText(titleSize, DashColors.TextPrimary, FontWeight.SemiBold))
                LqLine(sub, lqText(subSize, lqSoft))
                Spacer(Modifier.height(14.dp))
                LqProgress(ms, env.mediaController, Modifier.fillMaxWidth())
                Spacer(Modifier.height(14.dp))
                LqKeys(env, key, prev = true, spread = true)
            }
        }
    }
}

/**
 * The song's cover with rounded corners on a soft violet shadow or, while
 * the player publishes none, the design's glow of pink low left and blue
 * high right on deep violet, crossed by a lit pink line, drawn once.
 */
@Composable
private fun LqCover(ms: MediaState, modifier: Modifier) {
    val bitmap = remember(ms.artwork) { ms.artwork?.asImageBitmap() }
    val shadow = DashColors.Effects != DashEffects.NONE
    Box(
        modifier
            .cachedDraw(shadow) {
                val r = CornerRadius(min(size.width, size.height) * 0.11f)
                onDrawBehind {
                    if (shadow) {
                        for (k in 1..3) {
                            val g = k * 4f * density
                            drawRoundRect(Color(0xFF14003A).copy(alpha = 0.09f), Offset(-g, -g + 8f * density), Size(size.width + 2 * g, size.height + 2 * g), CornerRadius(r.x + g))
                        }
                    }
                }
            }
            .clip(RoundedCornerShape(22.dp))
            .cachedDraw(bitmap == null) {
                val w = size.width
                val h = size.height
                val pink = Brush.radialGradient(listOf(Color(0xFFFF5EA0), Color.Transparent), Offset(w * 0.3f, h * 0.7f), min(w, h) * 0.6f)
                val blue = Brush.radialGradient(listOf(Color(0xFF5B7BFF), Color.Transparent), Offset(w * 0.8f, h * 0.2f), min(w, h) * 0.7f)
                val base = Brush.linearGradient(listOf(Color(0xFF1B0F3D), Color(0xFF3A124F)), Offset.Zero, Offset(w, h))
                val lineY = h * 0.58f
                val line = Brush.horizontalGradient(listOf(Color.Transparent, Color(0xFFFFD1F0), Color.Transparent), -w * 0.1f, w * 1.1f)
                val lineGlow = Brush.verticalGradient(listOf(Color.Transparent, Color(0x55FF7AC8), Color.Transparent), lineY - 14f * density, lineY + 14f * density)
                onDrawBehind {
                    if (bitmap != null) return@onDrawBehind
                    drawRect(base)
                    drawRect(pink)
                    drawRect(blue)
                    drawRect(lineGlow, Offset(0f, lineY - 14f * density), Size(w, 28f * density))
                    drawRect(line, Offset(0f, lineY - density), Size(w, 2f * density))
                }
            }
    ) {
        if (bitmap != null) Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

/** Previous (when [prev]), play / pause bright and larger, and next: round keys [d] across, spread over the row when [spread]. */
@Composable
private fun LqKeys(env: SkinTileEnv, d: Dp, prev: Boolean, spread: Boolean = false) {
    val playing = env.mediaState.isPlaying
    val enabled = !env.editing
    val c = env.mediaController
    Row(
        modifier = if (spread) Modifier.fillMaxWidth() else Modifier,
        horizontalArrangement = if (spread) Arrangement.SpaceBetween else Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (prev) LqKey(Icons.Filled.SkipPrevious, stringResource(R.string.horizon_cd_previous_track), d, false, enabled) { c.previous() }
        LqKey(
            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            stringResource(if (playing) R.string.horizon_cd_pause else R.string.horizon_cd_play),
            d * 1.2f, true, enabled
        ) { c.playPause() }
        LqKey(Icons.Filled.SkipNext, stringResource(R.string.horizon_cd_next_track), d, false, enabled) { c.next() }
    }
}

/** A round transport key: [main] bright white (indigo by day) on a soft shadow, the others glass keys. */
@Composable
private fun LqKey(icon: ImageVector, label: String, d: Dp, main: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    val fill = lqBright
    val shadow = DashColors.Effects != DashEffects.NONE
    val size = d.coerceAtLeast(DashSize.TouchDriving)
    Box(
        modifier = Modifier
            .size(size)
            .then(
                if (main) Modifier.cachedDraw(fill, shadow) {
                    onDrawBehind {
                        if (shadow) for (k in 1..3) drawCircle(Color(0xFF14003A).copy(alpha = 0.08f), this.size.minDimension / 2f + k * 3f * density, center + Offset(0f, 5f * density))
                        drawCircle(fill)
                    }
                }.clip(CircleShape) else Modifier.liquidKey(size / 2)
            )
            .clickable(enabled = enabled, onClickLabel = label, role = Role.Button) { tap(); onClick() }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = if (main) lqOnBright else DashColors.TextPrimary, modifier = Modifier.size(size * if (main) 0.42f else 0.44f))
    }
}

/** The thin progress line: a faint track and the elapsed part in white (ink by day); in its own scope, so only it follows the position. */
@Composable
private fun LqProgress(ms: MediaState, controller: CarMediaController, modifier: Modifier) {
    val positionMs = rememberMediaPosition(ms, controller)
    val fraction = if (ms.durationMs > 0L) (positionMs.toFloat() / ms.durationMs).coerceIn(0f, 1f) else 0f
    val on = if (DashColors.Light) DashColors.TextPrimary else Color.White
    val off = if (DashColors.Light) Color(0x22303080) else Color.White.copy(alpha = 0.25f)
    Spacer(
        modifier.height(6.dp).cachedDraw(fraction, on, off) {
            val y = size.height / 2f
            val bar = 6.dp.toPx()
            val fw = size.width * fraction
            onDrawBehind {
                drawLine(off, Offset(bar / 2f, y), Offset(size.width - bar / 2f, y), bar, StrokeCap.Round)
                if (fw > 0f) drawLine(on, Offset(bar / 2f, y), Offset(fw.coerceAtLeast(bar / 2f), y), bar, StrokeCap.Round)
            }
        }
    )
}

// --- Telemetry: the chip card -------------------------------------------------------

/**
 * The engine on a glass chip card, as in the design: readings side by side,
 * each its name small over its value large (revs, battery, then load and
 * coolant where they fit). A taller card puts the revs large on top.
 * Without the link, the card says so and offers to connect.
 */
@Composable
private fun LqTelemetry(env: SkinTileEnv) {
    val state = env.obdConnection
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .liquidGlass(editing = env.editing)
            .lqTap(state.isIdle && !env.editing, stringResource(R.string.horizon_connect_obd), env.onConnectObd)
    ) {
        val pad = padFor(maxWidth, maxHeight)
        val innerW = maxWidth - pad * 2
        val innerH = maxHeight - pad * 2
        val tall = innerH >= 200.dp
        val size = min(innerW.value / 7.5f, innerH.value * (if (tall) 0.2f else 0.32f)).coerceIn(20f, 34f)
        Column(Modifier.fillMaxSize().padding(pad), verticalArrangement = Arrangement.Center) {
            when (state) {
                ObdConnectionState.CONNECTED -> {
                    val d = env.obdData
                    val units = LocalUnits.current
                    val revs = stringResource(R.string.liquid_revs)
                    if (tall) {
                        val big = lqFit("8 888", FontWeight.SemiBold, innerW * 0.7f, innerH * 0.36f, 30f, 96f)
                        LqLine(revs, lqText((big * 0.2f).coerceAtLeast(14f), lqSoft))
                        Row {
                            LqLine(groupThousands(d.rpm), lqText(big, DashColors.TextPrimary, FontWeight.SemiBold, figure = true), Modifier.alignByBaseline(), overflow = TextOverflow.Clip)
                            Spacer(Modifier.width(8.dp))
                            LqLine(stringResource(R.string.liquid_rpm), lqText((big * 0.26f).coerceAtLeast(15f), lqSoft), Modifier.alignByBaseline())
                        }
                        Spacer(Modifier.height(14.dp))
                    }
                    val items = ArrayList<Triple<String, String, Boolean>>(4)
                    if (!tall) items += Triple(revs, groupThousands(d.rpm), false)
                    if (d.voltage > 0.0) items += Triple(stringResource(R.string.liquid_battery), String.format(Locale.getDefault(), "%.1f V", d.voltage), d.voltage !in BATTERY_OK_V)
                    items += Triple(stringResource(R.string.liquid_load), "${d.engineLoadPct} %", false)
                    if (d.coolantTempC > 0) items += Triple(stringResource(R.string.liquid_coolant), "${units.temp(d.coolantTempC)}°", d.coolantTempC >= COOLANT_WARNING_C)
                    val fits = (innerW.value / (size * 4.2f)).toInt().coerceIn(1, items.size)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items.take(fits).forEach { (label, value, warn) -> LqReading(label, value, warn, size, Modifier.weight(1f)) }
                    }
                }
                ObdConnectionState.CONNECTING -> {
                    LqLine(stringResource(R.string.liquid_engine), lqText(15f, lqSoft))
                    LqLine(stringResource(R.string.horizon_obd_connecting), lqText(size * 0.75f, DashColors.AccentInk, FontWeight.Medium))
                }
                else -> {
                    LqLine(stringResource(R.string.liquid_engine), lqText(15f, lqSoft))
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LqPillButton(stringResource(R.string.horizon_connect_obd), filled = true, enabled = !env.editing, onClick = env.onConnectObd)
                        if (state == ObdConnectionState.ERROR && innerW >= 360.dp) {
                            LqPillButton(stringResource(R.string.tape_cd_choose_adapter), filled = false, enabled = !env.editing, onClick = env.onPickDevice)
                        }
                    }
                }
            }
        }
    }
}

// --- Fuel and range -----------------------------------------------------------------

/**
 * Fuel and range. With the room, the hero: the driver's car big on its soft
 * floor shadow with the Range and Coolant chips (LiquidSkinCar.kt).
 * Otherwise a glass card: the range large, the fuel as a thin capsule, the
 * litres. Fuel not learned yet, on a small tile, falls back to the standard
 * tile (on the glass), which explains how to learn it.
 */
@Composable
private fun LqRange(item: DashboardItem, env: SkinTileEnv) {
    val fuel = rememberFuel(env)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val stage = maxWidth >= LQ_HERO_MIN_W && maxHeight >= LQ_HERO_MIN_H
        when {
            stage -> LqHero(env, fuel, Modifier.fillMaxSize())
            fuel == null -> Box(Modifier.fillMaxSize().liquidGlass(editing = env.editing)) { StandardSkinnedTile(item, env) }
            else -> LqRangeCard(fuel, env.editing)
        }
    }
}

/** The range on a glass card: its name, the range large, the fuel's capsule, the litres and the share. */
@Composable
private fun LqRangeCard(fuel: FuelInfo, editing: Boolean) {
    val units = LocalUnits.current
    val low = fuel.percent <= SKIN_LOW_FUEL_PCT
    val on = if (low) DashColors.Warning else DashColors.Accent
    val track = if (DashColors.Light) Color(0x22303080) else Color.White.copy(alpha = 0.2f)
    BoxWithConstraints(Modifier.fillMaxSize().liquidGlass(editing = editing)) {
        val pad = padFor(maxWidth, maxHeight)
        val innerW = maxWidth - pad * 2
        val innerH = maxHeight - pad * 2
        val showLine = innerH >= 140.dp
        val numH = innerH - 22.dp - 10.dp - 18.dp - if (showLine) 26.dp else 0.dp
        val numSize = lqFit("888", FontWeight.SemiBold, innerW * 0.6f, numH, 24f, 120f)
        Column(Modifier.fillMaxSize().padding(pad), verticalArrangement = Arrangement.SpaceBetween) {
            LqLine(stringResource(R.string.liquid_range), lqText(16f, lqSoft))
            Row {
                LqLine(units.distance(fuel.rangeKm).toString(), lqText(numSize, if (low) DashColors.Warning else DashColors.TextPrimary, FontWeight.SemiBold, figure = true), Modifier.alignByBaseline(), overflow = TextOverflow.Clip)
                Spacer(Modifier.width((numSize * 0.08f).dp))
                LqLine(units.distanceUnit, lqText((numSize * 0.36f).coerceAtLeast(15f), lqSoft, FontWeight.Medium), Modifier.alignByBaseline())
            }
            Spacer(
                Modifier.fillMaxWidth().height(10.dp).cachedDraw(fuel.percent, on, track) {
                    val h = size.height
                    val fw = size.width * (fuel.percent / 100f).coerceIn(0f, 1f)
                    onDrawBehind {
                        drawRoundRect(track, cornerRadius = CornerRadius(h / 2f))
                        if (fw > 0f) drawRoundRect(on, size = Size(fw.coerceAtLeast(h), h), cornerRadius = CornerRadius(h / 2f))
                    }
                }
            )
            if (showLine) {
                LqLine(
                    String.format(Locale.getDefault(), "%s%.0f L$DOT%d %%", if (fuel.percentEstimated) "≈ " else "", fuel.liters, fuel.percent),
                    lqText(16f, lqSoft)
                )
            }
        }
    }
}

// --- Clock and weather --------------------------------------------------------------

/** Clock on a glass card: the time large and the date under it. Tap opens the alarms. */
@Composable
private fun LqClock(env: SkinTileEnv) {
    val now = rememberNow(60_000L)
    val locale = Locale.getDefault()
    val units = LocalUnits.current
    val timeFmt = rememberDateFormat(units.digitsPattern())
    val dateFmt = rememberDateFormat(PANO_LONG_DATE, best = true)
    val context = env.context
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .liquidGlass(editing = env.editing)
            .lqTap(!env.editing, stringResource(R.string.horizon_open_clock)) { openClockApp(context) },
        contentAlignment = Alignment.CenterStart
    ) {
        val pad = padFor(maxWidth, maxHeight)
        val innerW = maxWidth - pad * 2
        val innerH = maxHeight - pad * 2
        val dateSize = min(innerW.value / 14f, innerH.value * 0.14f).coerceIn(14f, 24f)
        val amPm = units.amPm(now, locale)
        val timeText = timeFmt.format(now)
        val timeSize = lqFit(template(timeText), FontWeight.SemiBold, if (amPm != null) innerW * 0.8f else innerW, innerH - (dateSize * 1.3f + 6f).dp, 26f, 220f)
        Column(Modifier.padding(pad)) {
            Row {
                LqLine(timeText, lqText(timeSize, DashColors.TextPrimary, FontWeight.SemiBold, figure = true), Modifier.alignByBaseline(), overflow = TextOverflow.Clip)
                if (amPm != null) {
                    Spacer(Modifier.width((timeSize * 0.06f).dp))
                    LqLine(amPm, lqText((timeSize * 0.24f).coerceAtLeast(14f), lqSoft), Modifier.alignByBaseline())
                }
            }
            Spacer(Modifier.height(4.dp))
            LqLine(dateFmt.format(now).replaceFirstChar { it.titlecase(locale) }, lqText(dateSize, lqSoft))
        }
    }
}

/**
 * Weather on a glass card: the sky's icon and the temperature large, the sky
 * in words, then the feel and the wind, and rain to come in the accent's ink.
 * While there is nothing yet, it says why (no GPS, offline).
 */
@Composable
private fun LqWeather(env: SkinTileEnv) {
    val w = rememberWeather()
    val wait = rememberWeatherWait()
    val units = LocalUnits.current
    val timeFmt = rememberTimeFormat()
    val nowMs = rememberWallClock(60_000L).longValue
    BoxWithConstraints(Modifier.fillMaxSize().liquidGlass(editing = env.editing), contentAlignment = Alignment.CenterStart) {
        val pad = padFor(maxWidth, maxHeight)
        val innerW = maxWidth - pad * 2
        val innerH = maxHeight - pad * 2
        if (w == null) {
            val title = when (wait) {
                WeatherWait.LOADING -> R.string.horizon_loading
                WeatherWait.NO_GPS -> R.string.info_waiting_gps
                WeatherWait.UNAVAILABLE -> R.string.info_weather_unavailable
            }
            Column(Modifier.padding(pad)) {
                LqLine("--°", lqText(lqFit("88°", FontWeight.SemiBold, innerW * 0.5f, innerH * 0.5f, 26f, 96f), DashColors.Muted, FontWeight.SemiBold, figure = true))
                LqLine(stringResource(title), lqText(16f, lqSoft), maxLines = 2)
            }
            return@BoxWithConstraints
        }
        val rain = when {
            w.rainFromMs != null && w.rainUntilMs != null && w.rainFromMs <= nowMs -> stringResource(R.string.canvas_rain_until, timeFmt.format(Date(w.rainUntilMs)))
            w.rainFromMs != null -> stringResource(R.string.canvas_rain_from, timeFmt.format(Date(w.rainFromMs)))
            else -> null
        }
        val condSize = min(innerW.value / 12f, innerH.value * 0.13f).coerceIn(15f, 24f)
        val lineSize = (condSize * 0.82f).coerceIn(14f, 19f)
        val extra = innerH >= 150.dp
        val temp = "${units.temp(w.tempC)}°"
        val below = (condSize * 1.3f + (if (extra) lineSize * 1.3f * (if (rain != null) 2 else 1) else 0f) + 6f).dp
        val tempSize = lqFit(template(temp), FontWeight.SemiBold, innerW * 0.6f, innerH - below, 26f, 160f)
        val feels = stringResource(R.string.horizon_feels, units.temp(w.feelsC))
        val wind = stringResource(if (units.imperial) R.string.units_horizon_wind_mph else R.string.horizon_wind, units.speed(w.windKmh))
        Column(Modifier.padding(pad)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(weatherIcon(w.code), contentDescription = null, tint = DashColors.TextPrimary, modifier = Modifier.size((tempSize * 0.5f).coerceIn(24f, 72f).dp))
                Spacer(Modifier.width(12.dp))
                LqLine(temp, lqText(tempSize, DashColors.TextPrimary, FontWeight.SemiBold, figure = true), overflow = TextOverflow.Clip)
            }
            LqLine(w.condition, lqText(condSize, DashColors.TextPrimary))
            if (extra) {
                LqLine("$feels$DOT$wind", lqText(lineSize, lqSoft))
                if (rain != null) LqLine(rain, lqText(lineSize, DashColors.AccentInk, FontWeight.Medium))
            }
        }
    }
}

// --- Apps ---------------------------------------------------------------------------

/** App shortcut: a pane of glass with the app's icon and its name, side by side on a wide tile, stacked on a square one. */
@Composable
private fun LqApp(item: DashboardItem.AppShortcut, env: SkinTileEnv) {
    val app = env.appsByPackage[item.packageName]
    val label = appLabel(app, item.packageName)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .liquidGlass(28.dp, editing = env.editing)
            .lqTap(!env.editing, stringResource(R.string.horizon_open_app, label)) { env.onLaunchApp(item.packageName) }
            .semantics(mergeDescendants = true) { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        val row = maxWidth >= maxHeight * 1.5f && maxWidth >= 180.dp
        if (row) {
            val icon = (maxHeight.value * 0.5f).coerceIn(30f, 72f).dp
            Row(Modifier.fillMaxSize().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                LqAppGlyph(app, icon)
                Spacer(Modifier.width(14.dp))
                LqLine(label, lqText((icon.value * 0.38f).coerceIn(16f, 24f), DashColors.TextPrimary, FontWeight.SemiBold), Modifier.weight(1f, fill = false))
            }
        } else {
            val icon = (min(maxWidth.value, maxHeight.value) * 0.42f).coerceIn(30f, 96f).dp
            val named = maxHeight >= 96.dp
            Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                LqAppGlyph(app, icon)
                if (named) {
                    Spacer(Modifier.height(8.dp))
                    LqLine(label, lqText((icon.value * 0.3f).coerceIn(14f, 20f), DashColors.TextPrimary, FontWeight.Medium), align = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun LqAppGlyph(app: AppEntry?, size: Dp) {
    if (app != null) AppIcon(icon = app.icon, size = size)
    else Icon(Icons.Filled.Apps, contentDescription = null, tint = lqSoft, modifier = Modifier.size(size * 0.8f))
}

/**
 * Launch bar: a capsule of glass like the dock, its apps as icons in round
 * slots (names beside them where there is room), and a glass pencil at the
 * end. Apps that would get less than a 64 dp slot are left out.
 */
@Composable
private fun LqLaunchBar(item: DashboardItem.LaunchBar, env: SkinTileEnv) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val capsule = min(maxHeight.value, 96f).dp
        val edit = (capsule - 16.dp).coerceIn(48.dp, 64.dp)
        val room = maxWidth - edit - 40.dp
        val fit = (room.value / 72f).toInt().coerceAtLeast(1)
        val pkgs = item.packages.take(fit)
        val per = if (pkgs.isEmpty()) 0f else room.value / pkgs.size
        val labels = per >= 170f
        val icon = (capsule.value * 0.5f).coerceIn(28f, 48f).dp
        Row(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(capsule)
                .liquidGlass(capsule / 2, editing = env.editing)
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (pkgs.isEmpty()) {
                LqLine(stringResource(R.string.liquid_launch_bar_empty), lqText(16f, lqSoft), Modifier.weight(1f).padding(start = 10.dp), maxLines = 2)
            } else {
                pkgs.forEach { pkg ->
                    val app = env.appsByPackage[pkg]
                    val name = appLabel(app, pkg)
                    Row(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(capsule / 2))
                            .lqTap(!env.editing, stringResource(R.string.horizon_open_app, name)) { env.onLaunchApp(pkg) }
                            .semantics(mergeDescendants = true) { contentDescription = name }
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = if (labels) Arrangement.Start else Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LqAppGlyph(app, icon)
                        if (labels) {
                            Spacer(Modifier.width(12.dp))
                            LqLine(name, lqText(17f, DashColors.TextPrimary, FontWeight.Medium), Modifier.weight(1f, fill = false))
                        }
                    }
                }
            }
            val tap = rememberTapFeedback()
            val editLabel = stringResource(R.string.horizon_cd_edit_launch_bar)
            Box(
                Modifier
                    .size(edit)
                    .liquidKey(edit / 2)
                    .clickable(onClickLabel = editLabel, role = Role.Button) { tap(); env.onEditLaunchBar() }
                    .semantics { contentDescription = editLabel },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Edit, contentDescription = null, tint = DashColors.TextPrimary, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/** The coolant in °C while the link is up (0 otherwise), followed alone so a chip redraws when its own reading changes. */
@Composable
internal fun rememberCoolantC(env: SkinTileEnv): Int {
    val coolant by remember(env) { derivedStateOf { if (env.obdConnection == ObdConnectionState.CONNECTED) env.obdData.coolantTempC else 0 } }
    return coolant
}
