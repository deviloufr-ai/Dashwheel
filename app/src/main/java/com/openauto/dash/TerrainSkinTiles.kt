package com.openauto.dash

import com.openauto.dash.vehicle.any
import com.openauto.dash.vehicle.rememberPanelGauges
import com.openauto.dash.vehicle.K
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/*
 * Terrain skin, the widgets on their smoked cards (the look is in
 * TerrainSkin.kt, the car in TerrainSkinCar.kt): spaced mono labels, light
 * numerals, amber for the one live thing. The next turn is a big amber block
 * with its arrow, the weather a temperature with its rain line and six
 * hourly bars, the music its cover and three round keys.
 */

/** Room around a card's content, following the tile's smaller side. */
internal fun trPad(w: Dp, h: Dp): Dp = (min(w.value, h.value) * 0.1f).coerceIn(14f, 22f).dp

/**
 * Terrain renderer for app shortcuts, launch bars and the main widgets.
 * Anything else keeps its standard renderer.
 */
@Composable
internal fun TerrainTile(item: DashboardItem, env: SkinTileEnv) {
    when (item) {
        is DashboardItem.AppShortcut -> TrApp(item, env)
        is DashboardItem.LaunchBar -> TrLaunchBar(item, env)
        is DashboardItem.BuiltinWidget -> when (item.kind) {
            BuiltinKind.TELEMETRY -> TrTelemetry(env)
            BuiltinKind.SPEED_HUD -> TrSpeed(env)
            BuiltinKind.MEDIA -> TrMedia(env)
            BuiltinKind.NAVIGATION -> TrDirections(env)
            BuiltinKind.CLOCK -> TrClock(env)
            BuiltinKind.WEATHER -> TrWeather(env)
            BuiltinKind.RANGE -> TerrainRange(item, env)
            else -> StandardSkinnedTile(item, env)
        }
        else -> StandardSkinnedTile(item, env)
    }
}

// --- Small pieces -----------------------------------------------------------------

/** Children in a row, in order, each only if it fits whole in the room left (dropped, never cut), [gap] apart. */
@Composable
internal fun TrFitRow(gap: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val gapPx = gap.roundToPx()
        val placed = arrayOfNulls<Placeable>(measurables.size)
        val xs = IntArray(measurables.size)
        var x = 0
        var height = 0
        measurables.forEachIndexed { i, m ->
            val room = constraints.maxWidth - x
            if (room <= 0 || m.maxIntrinsicWidth(constraints.maxHeight) > room) return@forEachIndexed
            val p = m.measure(Constraints(maxWidth = room, maxHeight = constraints.maxHeight))
            placed[i] = p
            xs[i] = x
            x += p.width + gapPx
            height = max(height, p.height)
        }
        val width = (x - gapPx).coerceIn(constraints.minWidth, constraints.maxWidth)
        layout(width, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            placed.forEachIndexed { i, p -> p?.placeRelative(xs[i], height - p.height) }
        }
    }
}

/** Largest size within [minSize]..[maxSize] at which [sample] in the light figure face fits [maxW] x [maxH] on one line. */
@Composable
internal fun trFit(sample: String, maxW: Dp, maxH: Dp, minSize: Float, maxSize: Float, weight: FontWeight = FontWeight.Light): Float {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = trText(100f, weight = weight, figure = true)
    return remember(sample, style, maxW, maxH, minSize, maxSize, density) {
        val probe = measurer.measure(sample, style.copy(fontSize = (100f / density.fontScale).sp), softWrap = false, maxLines = 1)
        val byW = with(density) { maxW.toPx() } * 100f / probe.size.width.coerceAtLeast(1)
        val byH = with(density) { maxH.toPx() } * 100f / probe.size.height.coerceAtLeast(1)
        min(byW, byH).coerceIn(minSize, maxSize.coerceAtLeast(minSize))
    }
}

/** Digits replaced by '8' so a readout keeps one size while its value changes. */
internal fun trTemplate(text: String): String = text.map { if (it.isDigit()) '8' else it }.joinToString("")

/** The amber block of the design: a rounded square of amber with a soft amber glow at night, [content] in it. */
@Composable
private fun AmberBlock(size: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val amber = DashColors.Accent
    val glowK = trGlow
    Box(
        modifier
            .size(size)
            .cachedDraw(amber, glowK, size) {
                val r = CornerRadius(this.size.minDimension * 0.25f)
                onDrawBehind {
                    if (glowK > 0f) {
                        for (k in 1..3) {
                            val g = k * 5f * density
                            drawRoundRect(amber.copy(alpha = 0.07f * glowK), Offset(-g, -g), Size(this.size.width + 2 * g, this.size.height + 2 * g), CornerRadius(r.x + g))
                        }
                    }
                    drawRoundRect(amber, cornerRadius = r)
                }
            },
        contentAlignment = Alignment.Center
    ) { content() }
}

/**
 * A quiet state on its card: the amber block with an icon, a title and one
 * hint; the hint is amber ink when a tap does something, and the whole card is the target.
 */
@Composable
private fun TrEmpty(icon: ImageVector, title: String, hint: String, editing: Boolean, onTap: (() -> Unit)?) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer()
            .trCard(editing = editing)
            .trTap(onTap != null && !editing, hint) { onTap?.invoke() },
        contentAlignment = Alignment.CenterStart
    ) {
        val pad = trPad(maxWidth, maxHeight)
        val wide = maxWidth >= maxHeight * 1.3f
        val block = min(maxHeight.value * 0.42f, maxWidth.value * 0.24f).coerceIn(44f, 96f).dp
        val titleSize = (block.value * 0.3f).coerceIn(17f, 26f)
        val words: @Composable () -> Unit = {
            Column {
                TrLine(title, trText(titleSize, DashColors.TextPrimary, FontWeight.SemiBold), maxLines = 2)
                Spacer(Modifier.height(4.dp))
                TrLine(hint, trText((titleSize * 0.75f).coerceIn(14f, 19f), if (onTap != null) DashColors.AccentInk else DashColors.TextSecondary), maxLines = 2)
            }
        }
        val glyph: @Composable () -> Unit = {
            AmberBlock(block) { Icon(icon, contentDescription = null, tint = trOnAmber, modifier = Modifier.size(block * 0.5f)) }
        }
        if (wide) {
            Row(Modifier.padding(pad), verticalAlignment = Alignment.CenterVertically) {
                glyph()
                Spacer(Modifier.width(18.dp))
                words()
            }
        } else {
            Column(Modifier.padding(pad)) {
                if (this@BoxWithConstraints.maxHeight >= 190.dp) {
                    glyph()
                    Spacer(Modifier.height(14.dp))
                }
                words()
            }
        }
    }
}

/** A round key: [main] filled in the light ink with the dark icon (the design's play), else a faint sand disc. */
@Composable
private fun TrRoundKey(icon: ImageVector, label: String, d: Dp, main: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    val fill = if (main) DashColors.TextPrimary else DashColors.TextPrimary.copy(alpha = if (DashColors.Light) 0.08f else 0.1f)
    Box(
        modifier = Modifier
            .size(d.coerceAtLeast(DashSize.TouchPrimary))
            .clip(CircleShape)
            .background(fill)
            .clickable(enabled = enabled, onClickLabel = label, role = Role.Button) { tap(); onClick() }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = if (main) DashColors.Background else DashColors.TextPrimary, modifier = Modifier.size(d * 0.42f))
    }
}

/** A pill button, at least 56 dp tall: [filled] in amber for the one thing to do, else a sand outline. */
@Composable
private fun TrPill(text: String, filled: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .heightIn(min = DashSize.TouchPrimary)
            .clip(shape)
            .background(if (filled) DashColors.Accent else DashColors.TextPrimary.copy(alpha = 0.08f))
            .clickable(enabled = enabled, role = Role.Button) { tap(); onClick() }
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center
    ) {
        TrLine(text, trText(17f, if (filled) trOnAmber else DashColors.TextPrimary, FontWeight.SemiBold))
    }
}

// --- Navigation -----------------------------------------------------------------------

/** The turn's arrow in [ink], drawn as in the design for the side the navigation says, or the app's own glyph tinted. */
@Composable
private fun TrTurnArrow(nav: NavState, size: Dp, ink: Color) {
    val bitmap = remember(nav.icon) { nav.icon?.asImageBitmap() }
    if (bitmap != null) {
        Image(bitmap, contentDescription = nav.instruction, contentScale = ContentScale.Fit, colorFilter = ColorFilter.tint(ink), modifier = Modifier.size(size))
        return
    }
    val side = nav.turnSide()
    Box(
        Modifier
            .size(size)
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
                val stroke = Stroke(2.8f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
                onDrawBehind { drawPath(path, ink, style = stroke) }
            }
    )
}

/**
 * The next turn, as in the design: the big amber block with the arrow, the
 * distance large, the turn and the street under it, and the arrival in mono
 * capitals. A tall tile stacks the block over the words. Tap opens the
 * navigation app; without a route (or notification access) a quiet card
 * says so and how to start.
 */
@Composable
private fun TrDirections(env: SkinTileEnv) {
    val nav by NavDirections.state.collectAsState()
    val context = env.context
    val canTap = !env.editing
    when {
        !env.hasMediaAccess -> TrEmpty(
            Icons.Filled.NotificationsActive,
            stringResource(R.string.info_directions_access_title), stringResource(R.string.horizon_tap_allow_notification_access), env.editing,
            if (canTap) ({ CarMediaController.openNotificationAccessSettings(context) }) else null
        )
        !nav.active -> TrEmpty(
            Icons.Filled.Navigation,
            stringResource(R.string.info_directions_no_route), stringResource(R.string.horizon_start_navigation), env.editing,
            if (canTap) ({ openNavigationApp(context, nav) }) else null
        )
        else -> {
            val eta = remember(nav.eta) { nav.etaParts.joinToString(" · ").uppercase(Locale.getDefault()) }
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer()
                    .trCard(editing = env.editing)
                    .trTap(canTap, stringResource(R.string.horizon_open_navigation)) { openNavigationApp(context, nav) }
            ) {
                val pad = trPad(maxWidth, maxHeight)
                val innerW = maxWidth - pad * 2
                val innerH = maxHeight - pad * 2
                val wide = maxWidth > maxHeight * 1.25f
                val (value, unit) = nav.distanceParts
                val dist = if (unit.isEmpty()) value else "$value $unit"
                val streetSize = (min(innerW.value / 18f, innerH.value * 0.15f)).coerceIn(16f, 22f)
                if (wide) {
                    val block = (innerH * 0.86f).coerceIn(56.dp, 112.dp)
                    val textW = innerW - block - 20.dp
                    val distSize = trFit(trTemplate(dist.ifEmpty { "888 m" }), textW, innerH * 0.4f, 24f, 52f, FontWeight.SemiBold)
                    Row(Modifier.fillMaxSize().padding(pad), verticalAlignment = Alignment.CenterVertically) {
                        AmberBlock(block) { TrTurnArrow(nav, block * 0.56f, trOnAmber) }
                        Spacer(Modifier.width(20.dp))
                        TrTurnWords(nav, dist, distSize, streetSize, eta, maxLines = if (innerH >= 150.dp) 2 else 1)
                    }
                } else {
                    val block = min(innerW.value * 0.4f, innerH.value * 0.34f).coerceIn(52f, 120f).dp
                    val distSize = trFit(trTemplate(dist.ifEmpty { "888 m" }), innerW, innerH * 0.2f, 24f, 56f, FontWeight.SemiBold)
                    Column(Modifier.fillMaxSize().padding(pad)) {
                        if (innerH >= 260.dp) {
                            TrLabel(stringResource(R.string.terrain_next_turn))
                            Spacer(Modifier.height(10.dp))
                        }
                        AmberBlock(block) { TrTurnArrow(nav, block * 0.56f, trOnAmber) }
                        Spacer(Modifier.weight(1f))
                        TrTurnWords(nav, dist, distSize, streetSize, eta, maxLines = if (innerH > 300.dp) 2 else 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun TrTurnWords(nav: NavState, dist: String, distSize: Float, streetSize: Float, eta: String, maxLines: Int) {
    Column {
        if (dist.isNotEmpty()) {
            TrLine(dist, trText(distSize, DashColors.TextPrimary, FontWeight.SemiBold, figure = true), overflow = TextOverflow.Clip)
            Spacer(Modifier.height(6.dp))
            TrLine(nav.instruction, trText(streetSize, DashColors.TextSecondary), maxLines = maxLines)
        } else {
            TrLine(nav.instruction, trText((streetSize * 1.3f).coerceAtMost(30f), DashColors.TextPrimary, FontWeight.SemiBold), maxLines = maxLines + 1)
        }
        if (eta.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            TrLine(eta, trText(14f, DashColors.Muted, mono = true))
        }
    }
}

// --- Weather ------------------------------------------------------------------------

/** The forecast bars cover this many hours. */
private const val WX_HOURS = 6

private const val HOUR_MS = 3_600_000L

/**
 * Which of the next [hours] hours, from the start of [nowMs]'s hour, are wet
 * by the forecast's first wet spell [rainFromMs]..[rainUntilMs] (the hourly
 * rain WeatherRepo keeps). Pure, so it is unit-tested.
 */
internal fun terrainWetHours(nowMs: Long, rainFromMs: Long?, rainUntilMs: Long?, hours: Int = WX_HOURS): BooleanArray {
    val first = nowMs - Math.floorMod(nowMs, HOUR_MS)
    return BooleanArray(hours) { i ->
        val start = first + i * HOUR_MS
        rainFromMs != null && rainUntilMs != null && start < rainUntilMs && start + HOUR_MS > rainFromMs
    }
}

/**
 * Weather for the next hours, as in the design: WEATHER · NEXT 6 H, the
 * temperature large and light with the rain line beside it (when the rain
 * starts and how long it lasts, or until when it rains, or the sky and how
 * long it stays dry), then one bar per hour, the wet ones tall in blue, the
 * dry ones low in olive, and the hours under them in mono. While there is
 * nothing yet, it says why.
 */
@Composable
private fun TrWeather(env: SkinTileEnv) {
    val w = rememberWeather()
    val wait = rememberWeatherWait()
    val units = LocalUnits.current
    val timeFmt = rememberTimeFormat()
    val hourFmt = rememberDateFormat(if (units.clock24) "HH" else "h")
    val nowMs = rememberWallClock(60_000L).longValue
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer().trCard(editing = env.editing)) {
        val pad = trPad(maxWidth, maxHeight)
        val innerW = maxWidth - pad * 2
        val innerH = maxHeight - pad * 2
        Column(Modifier.fillMaxSize().padding(pad)) {
            TrLabel(stringResource(R.string.terrain_weather_next, WX_HOURS))
            if (w == null) {
                Spacer(Modifier.weight(1f))
                TrLine("--°", trText(trFit("88°", innerW * 0.5f, innerH * 0.45f, 30f, 64f), DashColors.Muted, FontWeight.Light, figure = true))
                val why = when (wait) {
                    WeatherWait.LOADING -> R.string.horizon_loading
                    WeatherWait.NO_GPS -> R.string.info_waiting_gps
                    WeatherWait.UNAVAILABLE -> R.string.info_weather_unavailable
                }
                TrLine(stringResource(why), trText(16f, DashColors.TextSecondary), maxLines = 2)
                Spacer(Modifier.weight(1f))
                return@Column
            }
            val from = w.rainFromMs
            val until = w.rainUntilMs
            val (line1, line2) = when {
                from != null && until != null && from <= nowMs ->
                    stringResource(R.string.terrain_rain_until, timeFmt.format(Date(until))) to w.condition
                from != null && until != null ->
                    stringResource(R.string.terrain_rain_from, timeFmt.format(Date(from))) to
                        stringResource(R.string.terrain_rain_for, ((until - from + HOUR_MS / 2) / HOUR_MS).toInt().coerceAtLeast(1))
                else -> w.condition to stringResource(R.string.terrain_dry, RAIN_HORIZON_H)
            }
            val bars = innerH >= 150.dp
            val labels = innerH >= 190.dp
            val barsH = if (bars) (innerH * 0.26f).coerceIn(30.dp, 72.dp) else 0.dp
            val below = barsH + (if (bars) 14.dp else 0.dp) + (if (labels) 22.dp else 0.dp)
            val temp = "${units.temp(w.tempC)}°"
            val tempSize = trFit(trTemplate(temp), innerW * 0.42f, innerH - below - 30.dp, 30f, 80f)
            val lineSize = (tempSize * 0.3f).coerceIn(15f, 20f)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TrLine(temp, trText(tempSize, DashColors.TextPrimary, FontWeight.Light, figure = true), overflow = TextOverflow.Clip)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    TrLine(line1, trText(lineSize, if (from != null) DashColors.TextPrimary else DashColors.TextSecondary, FontWeight.Medium))
                    TrLine(line2, trText(lineSize, DashColors.TextSecondary))
                }
            }
            if (bars) {
                Spacer(Modifier.weight(1f))
                val wet = remember(nowMs / HOUR_MS, from, until) { terrainWetHours(nowMs, from, until) }
                TrHourBars(wet, Modifier.fillMaxWidth().height(barsH))
                if (labels) {
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        val first = nowMs - Math.floorMod(nowMs, HOUR_MS)
                        for (i in 0 until WX_HOURS) {
                            TrLine(hourFmt.format(Date(first + i * HOUR_MS)), trText(14f, DashColors.Muted, mono = true), Modifier.weight(1f), align = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}

/** The hourly bars: the wet hours tall and blue, the dry ones low and olive, rounded on top. */
@Composable
private fun TrHourBars(wet: BooleanArray, modifier: Modifier) {
    val rain = trRain
    val dry = trDry
    val said = stringResource(R.string.terrain_rain_hours_cd, wet.count { it })
    androidx.compose.foundation.layout.Spacer(
        modifier
            .semantics { contentDescription = said }
            .cachedDraw(wet.contentToString(), rain, dry) {
                val n = wet.size
                val gap = 7.dp.toPx()
                val bw = (size.width - gap * (n - 1)) / n
                val top = CornerRadius(min(4.dp.toPx(), bw / 2f))
                onDrawBehind {
                    for (i in 0 until n) {
                        val bh = size.height * (if (wet[i]) 0.9f else 0.3f)
                        val x = i * (bw + gap)
                        drawRoundRect(if (wet[i]) rain else dry, Offset(x, size.height - bh), Size(bw, bh), top)
                    }
                }
            }
    )
}

// --- Media --------------------------------------------------------------------------

/**
 * Music on a smoked card, as in the design: PLAYING, the cover with the song
 * and who sings it beside it, then three round keys, play / pause the light
 * one. A low wide tile becomes one row with the keys at its end.
 */
@Composable
private fun TrMedia(env: SkinTileEnv) {
    val ms = env.mediaState
    val context = env.context
    if (!env.hasMediaAccess) {
        TrEmpty(
            Icons.Filled.MusicNote,
            stringResource(R.string.info_media_access_needed), stringResource(R.string.horizon_tap_allow_notification_access), env.editing,
            if (!env.editing) ({ CarMediaController.openNotificationAccessSettings(context) }) else null
        )
        return
    }
    val hasTrack = ms.hasMedia && ms.title.isNotBlank()
    val title = if (hasTrack) ms.title else stringResource(R.string.info_nothing_playing)
    val sub = if (hasTrack) ms.artist.ifBlank { stringResource(R.string.horizon_unknown_artist) } else stringResource(R.string.horizon_play_something)
    val label = stringResource(
        when {
            !hasTrack -> R.string.terrain_music
            ms.isPlaying -> R.string.terrain_playing
            else -> R.string.terrain_paused
        }
    )
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer().trCard(editing = env.editing)) {
        val pad = trPad(maxWidth, maxHeight)
        val innerW = maxWidth - pad * 2
        val innerH = maxHeight - pad * 2
        val row = maxHeight < 150.dp || (maxWidth > maxHeight * 2.4f && innerW >= 480.dp)
        if (row) {
            val key = innerH.coerceIn(52.dp, 64.dp)
            Row(Modifier.fillMaxSize().padding(pad), verticalAlignment = Alignment.CenterVertically) {
                if (innerW >= 400.dp) {
                    TrCover(ms, Modifier.size(innerH.coerceIn(44.dp, 96.dp)))
                    Spacer(Modifier.width(14.dp))
                }
                Column(Modifier.weight(1f)) {
                    TrLine(title, trText((innerH.value * 0.24f).coerceIn(17f, 24f), DashColors.TextPrimary, FontWeight.SemiBold))
                    TrLine(sub, trText(15f, DashColors.TextSecondary))
                }
                Spacer(Modifier.width(10.dp))
                TrMediaKeys(env, key, prev = innerW >= 460.dp, spread = false)
            }
        } else {
            val key = (innerH * 0.3f).coerceIn(52.dp, 64.dp)
            val labelH = 22.dp
            val cover = (innerH - labelH - key - 22.dp).coerceIn(44.dp, 96.dp)
            Column(Modifier.fillMaxSize().padding(pad)) {
                TrLabel(label)
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TrCover(ms, Modifier.size(cover))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        TrLine(title, trText((cover.value * 0.28f).coerceIn(18f, 24f), DashColors.TextPrimary, FontWeight.SemiBold), maxLines = if (cover >= 80.dp) 2 else 1)
                        TrLine(sub, trText((cover.value * 0.2f).coerceIn(15f, 17f), DashColors.TextSecondary))
                    }
                }
                Spacer(Modifier.weight(1f))
                TrMediaKeys(env, key, prev = true, spread = true)
            }
        }
    }
}

/** The song's cover with rounded corners, or the design's dusk gradient (violet to rust) while the player gives none. */
@Composable
private fun TrCover(ms: MediaState, modifier: Modifier) {
    val bitmap = remember(ms.artwork) { ms.artwork?.asImageBitmap() }
    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .cachedDraw(bitmap == null) {
                val dusk = Brush.linearGradient(listOf(Color(0xFF3B2A5E), Color(0xFFC2563A)), Offset.Zero, Offset(size.width, size.height))
                onDrawBehind { drawRect(dusk) }
            },
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(Icons.Filled.MusicNote, contentDescription = null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.fillMaxSize(0.4f))
    }
}

@Composable
private fun TrMediaKeys(env: SkinTileEnv, d: Dp, prev: Boolean, spread: Boolean) {
    val playing = env.mediaState.isPlaying
    val enabled = !env.editing
    val c = env.mediaController
    Row(
        modifier = if (spread) Modifier.fillMaxWidth() else Modifier,
        horizontalArrangement = if (spread) Arrangement.SpaceBetween else Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (prev) TrRoundKey(Icons.Filled.SkipPrevious, stringResource(R.string.horizon_cd_previous_track), d, false, enabled) { c.previous() }
        TrRoundKey(
            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            stringResource(if (playing) R.string.horizon_cd_pause else R.string.horizon_cd_play),
            d, true, enabled
        ) { c.playPause() }
        TrRoundKey(Icons.Filled.SkipNext, stringResource(R.string.horizon_cd_next_track), d, false, enabled) { c.next() }
    }
}

// --- Telemetry ----------------------------------------------------------------------

/**
 * The engine on a smoked card: ENGINE · OBD, then the coolant, the battery
 * and the revs as light numerals under their mono names, as many as fit.
 * Without the link, the card says so and offers to connect.
 */
@Composable
private fun TrTelemetry(env: SkinTileEnv) {
    val state = env.obdConnection
    val units = LocalUnits.current
    // Whatever the car answers is shown, adapter or not.
    val g = rememberPanelGauges(K.COOLANT_TEMP, K.BATTERY_VOLTS)
    val gear by rememberGear()
    val live = state == ObdConnectionState.CONNECTED || g.any || gear.rpm != null
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .trCard(editing = env.editing)
            .trTap(state.isIdle && !env.editing, stringResource(R.string.horizon_connect_obd), env.onConnectObd)
    ) {
        val pad = trPad(maxWidth, maxHeight)
        val innerW = maxWidth - pad * 2
        val innerH = maxHeight - pad * 2
        val numSize = min(innerH.value * 0.36f, innerW.value / 7.5f).coerceIn(24f, 56f)
        Column(Modifier.fillMaxSize().padding(pad)) {
            TrLabel(trJoin(stringResource(R.string.terrain_engine), stringResource(R.string.dash_obd_short)))
            Spacer(Modifier.weight(1f))
            when {
                live -> {
                    TrFitRow(22.dp) {
                        g.coolantTempC?.let {
                            TrReadout(stringResource(R.string.skin_panorama_coolant), "${units.temp(it)}", "°", it >= COOLANT_WARNING_C, numSize)
                        }
                        g.voltage?.let {
                            TrReadout(stringResource(R.string.skin_panorama_battery), String.format(Locale.getDefault(), "%.1f", it), "V", it !in BATTERY_OK_V, numSize)
                        }
                        TrReadout(stringResource(R.string.skin_panorama_revs), gear.rpm?.let { groupThousands(it) } ?: "--", stringResource(R.string.skin_panorama_rpm), false, numSize)
                    }
                }
                state == ObdConnectionState.CONNECTING -> TrLine(stringResource(R.string.horizon_obd_connecting), trText(18f, DashColors.AccentInk, FontWeight.Medium))
                else -> TrFitRow(10.dp) {
                    TrPill(stringResource(R.string.horizon_connect_obd), filled = true, enabled = !env.editing, onClick = env.onConnectObd)
                    if (state == ObdConnectionState.ERROR) {
                        TrPill(stringResource(R.string.tape_cd_choose_adapter), filled = false, enabled = !env.editing, onClick = env.onPickDevice)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
        }
    }
}

/** A readout: its mono name over its light numeral and small unit; amber when out of range. */
@Composable
private fun TrReadout(label: String, value: String, unit: String, warn: Boolean, size: Float) {
    Column {
        TrLabel(label)
        Row {
            TrLine(value, trText(size, if (warn) DashColors.Warning else DashColors.TextPrimary, FontWeight.Light, figure = true), Modifier.alignByBaseline(), overflow = TextOverflow.Clip)
            Spacer(Modifier.width(4.dp))
            TrLine(unit, trText((size * 0.36f).coerceAtLeast(14f), DashColors.TextSecondary), Modifier.alignByBaseline())
        }
    }
}

// --- Speed --------------------------------------------------------------------------

/**
 * Speed on a smoked card: SPEED · GPS (or OBD), the speed large and light,
 * the unit in mono, and the road's limit as its sign in the top corner.
 */
@Composable
private fun TrSpeed(env: SkinTileEnv) {
    val context = env.context
    DisposableEffect(Unit) {
        SpeedLimit.start(context)
        onDispose { SpeedLimit.stop() }
    }
    val speed = rememberSpeedKmh()
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
    val source = speedSource("")
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .trCard(editing = env.editing)
            .trTap(canConnect, stringResource(R.string.horizon_connect_obd), env.onConnectObd)
            .semantics(mergeDescendants = true) { contentDescription = said }
    ) {
        val pad = trPad(maxWidth, maxHeight)
        val innerW = maxWidth - pad * 2
        val innerH = maxHeight - pad * 2
        val sign = (min(innerW.value, innerH.value) * 0.3f).coerceIn(40f, 72f).dp
        val numSize = trFit("188", innerW * 0.75f, innerH - 50.dp, 34f, 190f)
        Box(Modifier.fillMaxSize().padding(pad)) {
            TrLabel(trJoin(stringResource(R.string.terrain_speed), source), Modifier.align(Alignment.TopStart))
            limit?.let { Box(Modifier.align(Alignment.TopEnd)) { TrLimitSign(units.speed(it), sign) } }
            Row(Modifier.align(Alignment.BottomStart), verticalAlignment = Alignment.Bottom) {
                TrLine(speed?.let { units.speed(it).toString() } ?: "–", trText(if (speed != null) numSize else numSize * 0.6f, color, FontWeight.Light, figure = true), Modifier.alignByBaseline(), overflow = TextOverflow.Clip)
                Spacer(Modifier.width(8.dp))
                TrLine(units.speedUnit, trText(16f, trLabelInk, FontWeight.Medium, mono = true), Modifier.alignByBaseline(), caps = true)
            }
        }
    }
}

/** The road's limit as its sign: a white disc in a red ring, the number in black (a road sign keeps its colours day and night). */
@Composable
private fun TrLimitSign(limit: Int, d: Dp) {
    val said = stringResource(R.string.skin_panorama_speed_limit_cd, limit)
    Box(
        modifier = Modifier
            .size(d)
            .semantics { contentDescription = said }
            .cachedDraw(d) {
                val ring = size.minDimension * 0.115f
                onDrawBehind {
                    val r = size.minDimension / 2f
                    drawCircle(Color.White, r)
                    drawCircle(Color(0xFFD8232A), r - ring / 2f, style = Stroke(ring))
                }
            },
        contentAlignment = Alignment.Center
    ) {
        TrLine(limit.toString(), trText((d.value * if (limit >= 100) 0.32f else 0.4f).coerceAtLeast(14f), Color(0xFF111111), FontWeight.Bold), overflow = TextOverflow.Clip)
    }
}

// --- Clock --------------------------------------------------------------------------

/** The time large and light on a smoked card, the date under it in mono capitals. Tap opens the alarms. */
@Composable
private fun TrClock(env: SkinTileEnv) {
    val now = rememberNow(60_000L)
    val locale = Locale.getDefault()
    val units = LocalUnits.current
    val timeFmt = rememberDateFormat(units.digitsPattern())
    val dateFmt = rememberDateFormat("EEEdMMM", best = true)
    val context = env.context
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .trCard(editing = env.editing)
            .trTap(!env.editing, stringResource(R.string.horizon_open_clock)) { openClockApp(context) },
        contentAlignment = Alignment.CenterStart
    ) {
        val pad = trPad(maxWidth, maxHeight)
        val innerW = maxWidth - pad * 2
        val innerH = maxHeight - pad * 2
        val amPm = units.amPm(now, locale)
        val timeText = timeFmt.format(now)
        val timeSize = trFit(trTemplate(timeText), if (amPm != null) innerW * 0.8f else innerW, innerH - 30.dp, 30f, 260f)
        Column(Modifier.padding(pad)) {
            Row {
                TrLine(timeText, trText(timeSize, DashColors.TextPrimary, FontWeight.Light, figure = true), Modifier.alignByBaseline(), overflow = TextOverflow.Clip)
                if (amPm != null) {
                    Spacer(Modifier.width((timeSize * 0.06f).dp))
                    TrLine(amPm, trText((timeSize * 0.2f).coerceAtLeast(14f), trLabelInk, mono = true), Modifier.alignByBaseline())
                }
            }
            TrLabel(dateFmt.format(now), size = (innerH.value * 0.09f).coerceIn(14f, 20f))
        }
    }
}

// --- Apps ---------------------------------------------------------------------------

/** App shortcut: a smoked key, the app's icon and its name, side by side on a wide tile, stacked on a square one. */
@Composable
private fun TrApp(item: DashboardItem.AppShortcut, env: SkinTileEnv) {
    val app = env.appsByPackage[item.packageName]
    val label = appLabel(app, item.packageName)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .trCard(editing = env.editing)
            .trTap(!env.editing, stringResource(R.string.horizon_open_app, label)) { env.onLaunchApp(item.packageName) }
            .semantics(mergeDescendants = true) { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        val row = maxWidth >= maxHeight * 1.5f && maxWidth >= 180.dp
        if (row) {
            val icon = (maxHeight.value * 0.5f).coerceIn(30f, 72f).dp
            Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                TrAppGlyph(app, icon)
                Spacer(Modifier.width(14.dp))
                TrLine(label, trText((icon.value * 0.4f).coerceIn(16f, 24f), DashColors.TextPrimary, FontWeight.Medium), Modifier.weight(1f, fill = false))
            }
        } else {
            val icon = (min(maxWidth.value, maxHeight.value) * 0.42f).coerceIn(30f, 96f).dp
            Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                TrAppGlyph(app, icon)
                if (this@BoxWithConstraints.maxHeight >= 96.dp) {
                    Spacer(Modifier.height(8.dp))
                    TrLine(label, trText((icon.value * 0.3f).coerceIn(14f, 20f), DashColors.TextPrimary, FontWeight.Medium), align = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun TrAppGlyph(app: AppEntry?, size: Dp) {
    if (app != null) AppIcon(icon = app.icon, size = size)
    else Icon(Icons.Filled.Apps, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(size * 0.8f))
}

/**
 * Launch bar: a row of smoked keys, one per app (icon and name where there
 * is room), and a pencil at the end. Apps that would get less than a 64 dp
 * key are left out.
 */
@Composable
private fun TrLaunchBar(item: DashboardItem.LaunchBar, env: SkinTileEnv) {
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer()) {
        val gap = 10.dp
        val edit = min(maxHeight.value, 64f).coerceAtLeast(56f).dp
        val room = maxWidth - edit - gap
        val fit = ((room + gap).value / (64f + gap.value)).toInt().coerceAtLeast(1)
        val pkgs = item.packages.take(fit)
        val per = if (pkgs.isEmpty()) 0f else (room.value - gap.value * (pkgs.size - 1)) / pkgs.size
        val labels = per >= 150f
        val icon = (maxHeight.value * 0.46f).coerceIn(24f, 48f).dp
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
            if (pkgs.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxHeight().trCard(editing = env.editing).padding(horizontal = 20.dp), contentAlignment = Alignment.CenterStart) {
                    TrLine(stringResource(R.string.terrain_launch_bar_empty), trText(16f, DashColors.TextSecondary), maxLines = 2)
                }
            } else {
                pkgs.forEach { pkg ->
                    val app = env.appsByPackage[pkg]
                    val name = appLabel(app, pkg)
                    Row(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .widthIn(min = 64.dp)
                            .trCard(18.dp, editing = env.editing)
                            .trTap(!env.editing, stringResource(R.string.horizon_open_app, name)) { env.onLaunchApp(pkg) }
                            .semantics(mergeDescendants = true) { contentDescription = name }
                            .padding(horizontal = 10.dp),
                        horizontalArrangement = if (labels) Arrangement.Start else Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TrAppGlyph(app, icon)
                        if (labels) {
                            Spacer(Modifier.width(12.dp))
                            TrLine(name, trText(16f, DashColors.TextPrimary, FontWeight.Medium), Modifier.weight(1f, fill = false))
                        }
                    }
                }
            }
            val tap = rememberTapFeedback()
            val editLabel = stringResource(R.string.horizon_cd_edit_launch_bar)
            Box(
                Modifier
                    .size(edit)
                    .trCard(18.dp)
                    .clickable(onClickLabel = editLabel, role = Role.Button) { tap(); env.onEditLaunchBar() }
                    .semantics { contentDescription = editLabel },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Edit, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(22.dp))
            }
        }
    }
}
