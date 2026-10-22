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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Umbrella
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import java.util.Date
import java.util.Locale
import kotlin.math.min

/*
 * Windshield skin, the tiles (the look is in WindshieldSkin.kt, the road in
 * WindshieldSkinScene.kt): the widgets float over the road as AR tags. The
 * next turn is a cyan-outlined tag, the weather and the range small smoked
 * tags with a thin leader line down to the road, the clock and the engine
 * smoked tags, the music a compact smoked card, the speed a big glowing
 * numeral with no box at all, and the apps the mockup's QuickSelect column
 * of square smoked keys.
 */

private const val DOT = " · "

/** Windshield renderer for app shortcuts, launch bars and the main widgets; anything else keeps its standard one. */
@Composable
internal fun WindshieldTile(item: DashboardItem, env: SkinTileEnv) {
    when (item) {
        is DashboardItem.AppShortcut -> WsApp(item, env)
        is DashboardItem.LaunchBar -> WsQuickSelect(item, env)
        is DashboardItem.BuiltinWidget -> when (item.kind) {
            BuiltinKind.TELEMETRY -> WsTelemetry(env)
            BuiltinKind.SPEED_HUD -> WsSpeedHud(env)
            BuiltinKind.MEDIA -> WsMedia(env)
            BuiltinKind.NAVIGATION -> WsNavTag(env)
            BuiltinKind.CLOCK -> WsClockTag(env)
            BuiltinKind.WEATHER -> WsWeatherTag(env)
            BuiltinKind.RANGE -> WsRangeTag(item, env)
            else -> StandardSkinnedTile(item, env)
        }
        else -> StandardSkinnedTile(item, env)
    }
}

// --- Small pieces -------------------------------------------------------------------

/** Largest size (dp, for [wsText]) within [minSize]..[maxSize] at which [sample] fits [maxW] x [maxH] on one line. */
@Composable
private fun wsFit(sample: String, weight: FontWeight, maxW: Dp, maxH: Dp, minSize: Float, maxSize: Float): Float {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = wsText(100f, weight = weight)
    return remember(sample, style, maxW, maxH, minSize, maxSize, density) {
        val probe = measurer.measure(sample, style.copy(fontSize = (100f / density.fontScale).sp), softWrap = false, maxLines = 1)
        val byW = with(density) { maxW.toPx() } * 100f / probe.size.width.coerceAtLeast(1)
        val byH = with(density) { maxH.toPx() } * 100f / probe.size.height.coerceAtLeast(1)
        min(byW, byH).coerceIn(minSize, maxSize.coerceAtLeast(minSize))
    }
}

/** Digits replaced by '8' so a readout keeps one size while its value changes. */
private fun template(text: String): String = text.map { if (it.isDigit()) '8' else it }.joinToString("")

/** The whole tile's outline while arranging, dashed, so a bare tag still shows the room it takes. */
@Composable
private fun Modifier.wsEditFrame(editing: Boolean): Modifier {
    if (!editing) return this
    val rim = wsRim.copy(alpha = 0.7f)
    return cachedDraw(rim) {
        val stroke = Stroke(1.5f * density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f * density, 7f * density)))
        val r = WS_CARD.toPx()
        onDrawBehind { drawRoundRect(rim, Offset(stroke.width / 2f, stroke.width / 2f), Size(size.width - stroke.width, size.height - stroke.width), CornerRadius(r), style = stroke) }
    }
}

/**
 * An AR tag standing over the road: the [tag] (smoked, or cyan when [lit]) at
 * the tile's top left and, where the tile leaves room under it, a thin leader
 * line falling from its left end to a small dot on the road. [tag] is given
 * the text size worked out from the tile and whether it has room for a
 * second line.
 */
@Composable
private fun WsTagStand(
    editing: Boolean,
    lit: Boolean,
    leader: Boolean,
    onTap: (() -> Unit)?,
    tapLabel: String,
    tag: @Composable (size: Float, roomy: Boolean) -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer().wsEditFrame(editing)) {
        val size = min(maxWidth.value / 15f, maxHeight.value * 0.14f).coerceIn(17f, 30f)
        val roomy = maxHeight >= (size * 4.6f).dp
        val tileW = maxWidth
        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .widthIn(max = tileW)
                    .heightIn(min = DashSize.TouchPrimary)
                    .wsSmoke(WS_TAG, lit = lit, editing = editing)
                    .wsTap(onTap != null && !editing, tapLabel) { onTap?.invoke() }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.CenterStart
            ) { tag(size, roomy) }
            if (leader) WsLeader(lit, Modifier.weight(1f).padding(start = 22.dp).width(8.dp))
        }
    }
}

/** The leader line from a tag down to the road: lit ink fading as it falls, a small dot where it lands. */
@Composable
private fun WsLeader(lit: Boolean, modifier: Modifier) {
    val ink = if (lit) wsLit else (if (DashColors.Light) Color(0xFF0A3C5C) else Color(0xFF8CDCFF))
    val glowK = wsGlow
    Spacer(
        modifier.cachedDraw(ink, glowK) {
            val h = size.height
            if (h < 24f * density) return@cachedDraw onDrawBehind { }
            val x = 0.75f * density
            val line = Brush.verticalGradient(listOf(ink.copy(alpha = 0.7f), ink.copy(alpha = 0.1f)), 0f, h - 6f * density)
            val dotC = Offset(x, h - 4f * density)
            val dotGlow = if (glowK > 0f) Brush.radialGradient(listOf(ink.copy(alpha = 0.45f * glowK), Color.Transparent), dotC, 8f * density) else null
            onDrawBehind {
                drawLine(line, Offset(x, 0f), Offset(x, h - 6f * density), 1.5f * density)
                if (dotGlow != null) drawCircle(dotGlow, 8f * density, dotC)
                drawCircle(ink.copy(alpha = 0.8f), 2.5f * density, dotC)
            }
        }
    )
}

/** A tag's quiet state: an icon, a bold title and a hint in the accent's ink when a tap does something. */
@Composable
private fun WsQuiet(icon: ImageVector, title: String, hint: String, size: Float, roomy: Boolean, tappable: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = wsLit, modifier = Modifier.size((size * 1.15f).dp))
        Spacer(Modifier.width(10.dp))
        Column {
            WsLine(title, wsText(size, DashColors.TextPrimary, FontWeight.SemiBold))
            if (roomy) WsLine(hint, wsText((size * 0.8f).coerceAtLeast(14f), if (tappable) DashColors.AccentInk else DashColors.TextSecondary), maxLines = 2)
        }
    }
}

// --- Directions: the cyan tag ---------------------------------------------------------

/**
 * The next turn as the cyan AR tag: the arrow, the distance in the lit ink and
 * the street, and with the room the arrival and what is left of the trip
 * under them. A tap opens the navigation app. Without a route, or without
 * notification access, a smoked tag says so and how to start.
 */
@Composable
private fun WsNavTag(env: SkinTileEnv) {
    val nav by NavDirections.state.collectAsState()
    val context = env.context
    val arrivalWord = stringResource(R.string.windshield_arrival)
    when {
        !env.hasMediaAccess -> {
            val hint = stringResource(R.string.horizon_tap_allow_notification_access)
            WsTagStand(env.editing, lit = false, leader = true, onTap = { CarMediaController.openNotificationAccessSettings(context) }, tapLabel = hint) { size, roomy ->
                WsQuiet(Icons.Filled.NotificationsActive, stringResource(R.string.info_directions_access_title), hint, size, roomy, !env.editing)
            }
        }
        !nav.active -> {
            val hint = stringResource(R.string.horizon_start_navigation)
            WsTagStand(env.editing, lit = false, leader = true, onTap = { openNavigationApp(context, nav) }, tapLabel = hint) { size, roomy ->
                WsQuiet(Icons.Filled.Navigation, stringResource(R.string.info_directions_no_route), hint, size, roomy, !env.editing)
            }
        }
        else -> {
            val eta = remember(nav.eta, arrivalWord) {
                nav.etaParts.map { if (WS_CLOCK_PART.matches(it)) "$arrivalWord $it" else it }
                    .sortedBy { if (it.startsWith(arrivalWord)) 0 else 1 }
                    .joinToString(DOT)
            }
            val (value, unit) = nav.distanceParts
            val distance = if (unit.isEmpty()) value else "$value $unit"
            WsTagStand(env.editing, lit = true, leader = true, onTap = { openNavigationApp(context, nav) }, tapLabel = stringResource(R.string.horizon_open_navigation)) { size, roomy ->
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        WsTurnArrow(nav, (size * 1.5f).dp)
                        if (distance.isNotEmpty()) {
                            Spacer(Modifier.width(10.dp))
                            WsLine(distance, wsText(size * 1.3f, wsLit, FontWeight.SemiBold), overflow = TextOverflow.Clip)
                        }
                        Spacer(Modifier.width(10.dp))
                        WsLine(nav.instruction, wsText(size, DashColors.TextPrimary, FontWeight.Medium), Modifier.weight(1f, fill = false), maxLines = if (roomy && distance.isEmpty()) 2 else 1)
                    }
                    if (roomy && eta.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        WsLine(eta, wsText((size * 0.82f).coerceAtLeast(14f), DashColors.TextSecondary))
                    }
                }
            }
        }
    }
}

// --- Weather: a small tag over the road -------------------------------------------------

/**
 * The weather as the mockup's tag: the rain to come first when there is some
 * ("Rain from 21:40", the word in bold), then the temperature and the sky in
 * words; a short tile keeps one line, the rain when it comes. While there is
 * nothing yet it says why (no GPS, offline).
 */
@Composable
private fun WsWeatherTag(env: SkinTileEnv) {
    val w = rememberWeather()
    val wait = rememberWeatherWait()
    val units = LocalUnits.current
    val timeFmt = rememberTimeFormat()
    val nowMs = rememberWallClock(60_000L).longValue
    val skyInk = if (DashColors.Light) DashColors.Accent else Color(0xFF9FD0FF)
    WsTagStand(env.editing, lit = false, leader = true, onTap = null, tapLabel = "") { size, roomy ->
        if (w == null) {
            val reason = when (wait) {
                WeatherWait.LOADING -> R.string.horizon_loading
                WeatherWait.NO_GPS -> R.string.info_waiting_gps
                WeatherWait.UNAVAILABLE -> R.string.info_weather_unavailable
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                WsLine("--°", wsText(size * 1.2f, DashColors.Muted, FontWeight.SemiBold))
                Spacer(Modifier.width(10.dp))
                WsLine(stringResource(reason), wsText(size * 0.9f, DashColors.TextSecondary))
            }
            return@WsTagStand
        }
        val rainWhen = when {
            w.rainFromMs != null && w.rainUntilMs != null && w.rainFromMs <= nowMs -> stringResource(R.string.windshield_rain_until, timeFmt.format(Date(w.rainUntilMs)))
            w.rainFromMs != null -> stringResource(R.string.windshield_rain_from, timeFmt.format(Date(w.rainFromMs)))
            else -> null
        }
        val rainLine: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Umbrella, contentDescription = null, tint = skyInk, modifier = Modifier.size((size * 1.05f).dp))
                Spacer(Modifier.width(10.dp))
                WsLine(stringResource(R.string.windshield_rain), wsText(size, DashColors.TextPrimary, FontWeight.Bold), Modifier.alignByBaseline())
                Spacer(Modifier.width(7.dp))
                WsLine(rainWhen.orEmpty(), wsText(size, DashColors.TextPrimary), Modifier.alignByBaseline())
            }
        }
        val tempLine: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(weatherIcon(w.code), contentDescription = null, tint = skyInk, modifier = Modifier.size((size * 1.05f).dp))
                Spacer(Modifier.width(10.dp))
                WsLine("${units.temp(w.tempC)}°", wsText(size * 1.1f, DashColors.TextPrimary, FontWeight.Bold), Modifier.alignByBaseline())
                Spacer(Modifier.width(8.dp))
                WsLine(w.condition, wsText(size * 0.9f, DashColors.TextSecondary), Modifier.alignByBaseline())
            }
        }
        Column {
            when {
                rainWhen != null && roomy -> { rainLine(); Spacer(Modifier.height(4.dp)); tempLine() }
                rainWhen != null -> rainLine()
                else -> tempLine()
            }
        }
    }
}

// --- Fuel and range: a small tag -------------------------------------------------------

/**
 * The range as a tag over the road: the pump in amber, how far the fuel goes
 * in bold and the level, a thin gauge under them with the room. Fuel not
 * learned yet: the standard tile on the smoked card, which explains how.
 */
@Composable
private fun WsRangeTag(item: DashboardItem, env: SkinTileEnv) {
    val fuel = rememberFuel(env)
    if (fuel == null) {
        Box(Modifier.fillMaxSize().wsSmoke(WS_CARD, editing = env.editing)) { StandardSkinnedTile(item, env) }
        return
    }
    val units = LocalUnits.current
    val low = fuel.percent <= SKIN_LOW_FUEL_PCT
    val pump = if (low) DashColors.Warning else if (DashColors.Light) DashColors.Accent2 else Color(0xFFFFD27A)
    val said = stringResource(R.string.windshield_range_cd, units.distanceText(fuel.rangeKm))
    WsTagStand(env.editing, lit = false, leader = true, onTap = null, tapLabel = "") { size, roomy ->
        Column(Modifier.semantics(mergeDescendants = true) { contentDescription = said }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.LocalGasStation, contentDescription = null, tint = pump, modifier = Modifier.size((size * 1.05f).dp))
                Spacer(Modifier.width(10.dp))
                WsLine(units.distanceText(fuel.rangeKm), wsText(size * 1.15f, if (low) DashColors.Warning else DashColors.TextPrimary, FontWeight.Bold), Modifier.alignByBaseline())
                Spacer(Modifier.width(10.dp))
                WsLine("${fuel.percent} %", wsText(size * 0.9f, DashColors.TextSecondary), Modifier.alignByBaseline())
            }
            if (roomy) {
                Spacer(Modifier.height(8.dp))
                WsGauge(fuel.percent / 100f, if (low) DashColors.Warning else pump, Modifier.width((size * 7f).dp).height(5.dp))
            }
        }
    }
}

/** A thin gauge: the faint track and the filled share in [ink]. */
@Composable
private fun WsGauge(fraction: Float, ink: Color, modifier: Modifier) {
    val track = DashColors.TextPrimary.copy(alpha = 0.14f)
    Spacer(
        modifier.cachedDraw(fraction, ink, track) {
            val y = size.height / 2f
            val fw = size.width * fraction.coerceIn(0f, 1f)
            onDrawBehind {
                drawLine(track, Offset(y, y), Offset(size.width - y, y), size.height, StrokeCap.Round)
                if (fw > 0f) drawLine(ink, Offset(y, y), Offset(fw.coerceAtLeast(y), y), size.height, StrokeCap.Round)
            }
        }
    )
}

// --- Clock: a smoked tag -----------------------------------------------------------------

/** The time large on a smoked tag with the date under it, no leader (it points at nothing ahead). Tap opens the alarms. */
@Composable
private fun WsClockTag(env: SkinTileEnv) {
    val now = rememberNow(60_000L)
    val locale = Locale.getDefault()
    val units = LocalUnits.current
    val timeFmt = rememberDateFormat(units.digitsPattern())
    val dateFmt = rememberDateFormat("EEEEdMMMM", best = true)
    val context = env.context
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer().wsEditFrame(env.editing), contentAlignment = Alignment.CenterStart) {
        val time = timeFmt.format(now)
        val amPm = units.amPm(now, locale)
        val dateSize = min(maxWidth.value / 16f, maxHeight.value * 0.12f).coerceIn(15f, 24f)
        val timeSize = wsFit(template(time), FontWeight.SemiBold, maxWidth * (if (amPm != null) 0.62f else 0.74f), maxHeight - (dateSize * 1.3f + 40f).dp, 26f, 120f)
        Column(
            Modifier
                .wsSmoke(WS_CARD, editing = env.editing)
                .wsTap(!env.editing, stringResource(R.string.horizon_open_clock)) { openClockApp(context) }
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Row {
                WsLine(time, wsText(timeSize, DashColors.TextPrimary, FontWeight.SemiBold), Modifier.alignByBaseline(), overflow = TextOverflow.Clip)
                if (amPm != null) {
                    Spacer(Modifier.width(6.dp))
                    WsLine(amPm, wsText((timeSize * 0.26f).coerceAtLeast(14f), DashColors.TextSecondary), Modifier.alignByBaseline())
                }
            }
            WsLine(dateFmt.format(now).replaceFirstChar { it.titlecase(locale) }, wsText(dateSize, DashColors.TextSecondary))
        }
    }
}

// --- Speed: the glowing numeral -----------------------------------------------------------

/**
 * Speed as a HUD figure painted on the glass: the number large with a cyan
 * glow and no box, KM/H spaced out under it, the road's limit sign beside it;
 * amber over the limit. Without a speed, a dash, and a tap connects OBD.
 */
@Composable
private fun WsSpeedHud(env: SkinTileEnv) {
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
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .wsEditFrame(env.editing)
            .wsTap(canConnect, stringResource(R.string.horizon_connect_obd), env.onConnectObd)
            .semantics(mergeDescendants = true) { contentDescription = said },
        contentAlignment = Alignment.Center
    ) {
        val unitSize = (maxHeight.value * 0.1f).coerceIn(14f, 22f)
        val sign = (min(maxWidth.value, maxHeight.value) * 0.26f).coerceIn(36f, 72f).dp
        val numSize = wsFit("188", FontWeight.SemiBold, maxWidth * 0.62f - sign, maxHeight - (unitSize * 1.8f + 16f).dp, 30f, 220f)
        val glow = softTextShadow((if (over) DashColors.Warning else wsLit).copy(alpha = if (DashColors.Light) 0.25f else 0.55f), 26f)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                WsLine(
                    speed?.let { units.speed(it).toString() } ?: "–",
                    wsText(if (speed != null) numSize else numSize * 0.6f, color, FontWeight.SemiBold).copy(shadow = glow, lineHeight = 1.0.em),
                    overflow = TextOverflow.Clip
                )
                WsLine(units.speedUnit, wsText(unitSize, DashColors.TextSecondary, caps = true).copy(letterSpacing = 0.3.em), caps = true)
            }
            limit?.let {
                Spacer(Modifier.width(16.dp))
                WsLimitSign(units.speed(it), sign)
            }
        }
    }
}

// --- Engine: a smoked tag ----------------------------------------------------------------

/**
 * The engine on a smoked card: the revs large, then coolant, battery and load
 * as small readouts in a row where they fit. Without the link, the card says
 * so and offers to connect.
 */
@Composable
private fun WsTelemetry(env: SkinTileEnv) {
    val state = env.obdConnection
    val units = LocalUnits.current
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .wsSmoke(WS_CARD, editing = env.editing)
            .wsTap(state.isIdle && !env.editing, stringResource(R.string.horizon_connect_obd), env.onConnectObd)
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        val size = min(maxWidth.value / 16f, maxHeight.value * 0.14f).coerceIn(15f, 24f)
        val battery = maxWidth >= 240.dp
        val load = maxWidth >= 340.dp
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            WsLine(stringResource(R.string.windshield_engine), wsText((size * 0.75f).coerceAtLeast(14f), DashColors.TextSecondary, FontWeight.Medium, caps = true), caps = true)
            when (state) {
                ObdConnectionState.CONNECTED -> {
                    val d = env.obdData
                    Row {
                        WsLine(groupThousands(d.rpm), wsText(size * 2.2f, DashColors.TextPrimary, FontWeight.SemiBold), Modifier.alignByBaseline(), overflow = TextOverflow.Clip)
                        Spacer(Modifier.width(8.dp))
                        WsLine(stringResource(R.string.windshield_rpm), wsText(size * 0.9f, DashColors.TextSecondary), Modifier.alignByBaseline())
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        if (d.coolantTempC > 0) WsReadout(stringResource(R.string.windshield_coolant), "${units.temp(d.coolantTempC)}°", d.coolantTempC >= COOLANT_WARNING_C, size)
                        if (d.voltage > 0.0 && battery) WsReadout(stringResource(R.string.windshield_battery), String.format(Locale.getDefault(), "%.1f V", d.voltage), d.voltage !in BATTERY_OK_V, size)
                        if (load) WsReadout(stringResource(R.string.windshield_load), "${d.engineLoadPct} %", false, size)
                    }
                }
                ObdConnectionState.CONNECTING -> WsLine(stringResource(R.string.horizon_obd_connecting), wsText(size, DashColors.AccentInk, FontWeight.Medium))
                else -> Column {
                    WsLine(stringResource(R.string.horizon_connect_obd), wsText(size * 1.2f, DashColors.AccentInk, FontWeight.SemiBold))
                    if (state == ObdConnectionState.ERROR) {
                        Spacer(Modifier.height(6.dp))
                        WsLine(
                            stringResource(R.string.tape_cd_choose_adapter), wsText(size * 0.85f, DashColors.TextSecondary),
                            Modifier.heightIn(min = DashSize.Touch).wsTap(!env.editing, stringResource(R.string.tape_cd_choose_adapter), env.onPickDevice)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WsReadout(label: String, value: String, warn: Boolean, size: Float) {
    Column {
        WsLine(label, wsText((size * 0.75f).coerceAtLeast(14f), DashColors.TextSecondary))
        WsLine(value, wsText(size * 1.05f, if (warn) DashColors.Warning else DashColors.TextPrimary, FontWeight.SemiBold))
    }
}

// --- Media: the compact smoked card ---------------------------------------------------------

/**
 * Music on the mockup's compact smoked card: the cover (or a pink to blue
 * square while the player gives none), the song in bold and who sings it;
 * play / pause filled in the lit ink and next beside them. A taller tile adds
 * the progress line and the previous key.
 */
@Composable
private fun WsMedia(env: SkinTileEnv) {
    val ms = env.mediaState
    val context = env.context
    if (!env.hasMediaAccess) {
        val hint = stringResource(R.string.horizon_tap_allow_notification_access)
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .graphicsLayer()
                .wsSmoke(WS_CARD, editing = env.editing)
                .wsTap(!env.editing, hint) { CarMediaController.openNotificationAccessSettings(context) }
                .padding(18.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            val size = min(maxWidth.value / 14f, maxHeight.value * 0.16f).coerceIn(16f, 24f)
            WsQuiet(Icons.Filled.MusicNote, stringResource(R.string.info_media_access_needed), hint, size, maxHeight >= 90.dp, !env.editing)
        }
        return
    }
    val hasTrack = ms.hasMedia && ms.title.isNotBlank()
    val title = if (hasTrack) ms.title else stringResource(R.string.info_nothing_playing)
    val sub = if (hasTrack) ms.artist.ifBlank { stringResource(R.string.horizon_unknown_artist) } else stringResource(R.string.horizon_play_something)
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer().wsSmoke(WS_CARD, editing = env.editing)) {
        val tall = maxHeight >= 150.dp
        val pad = 16.dp
        val innerH = maxHeight - pad * 2
        val titleSize = (min(maxWidth.value / 14f, innerH.value * 0.22f)).coerceIn(18f, 26f)
        val subSize = (titleSize * 0.78f).coerceIn(15f, 20f)
        val cover = maxWidth >= 260.dp
        val prev = maxWidth >= 420.dp
        if (!tall) {
            Row(Modifier.fillMaxSize().padding(horizontal = pad), verticalAlignment = Alignment.CenterVertically) {
                if (cover) {
                    WsCover(ms, Modifier.size(innerH.coerceIn(40.dp, 64.dp)))
                    Spacer(Modifier.width(14.dp))
                }
                Column(Modifier.weight(1f)) {
                    WsLine(title, wsText(titleSize, DashColors.TextPrimary, FontWeight.SemiBold))
                    WsLine(sub, wsText(subSize, DashColors.TextSecondary))
                }
                Spacer(Modifier.width(8.dp))
                WsKeys(env, prev = prev)
            }
        } else {
            Column(Modifier.fillMaxSize().padding(pad)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WsCover(ms, Modifier.size((innerH * 0.42f).coerceIn(48.dp, 96.dp)))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        WsLine(title, wsText(titleSize, DashColors.TextPrimary, FontWeight.SemiBold), maxLines = if (innerH >= 220.dp) 2 else 1)
                        WsLine(sub, wsText(subSize, DashColors.TextSecondary))
                    }
                }
                Spacer(Modifier.weight(1f))
                if (ms.durationMs > 0L) {
                    WsProgress(ms, env.mediaController, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { WsKeys(env, prev = true) }
            }
        }
    }
}

/** The song's cover with rounded corners, or the mockup's pink to blue square while the player publishes none. */
@Composable
private fun WsCover(ms: MediaState, modifier: Modifier) {
    val bitmap = remember(ms.artwork) { ms.artwork?.asImageBitmap() }
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .cachedDraw(bitmap == null) {
                val fill = Brush.linearGradient(listOf(Color(0xFFFF5EA0), Color(0xFF5B7BFF)), Offset.Zero, Offset(size.width, size.height))
                onDrawBehind { drawRect(fill) }
            }
    ) {
        if (bitmap != null) Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

/** Previous (when [prev]), play / pause filled in the lit ink, next. */
@Composable
private fun WsKeys(env: SkinTileEnv, prev: Boolean) {
    val playing = env.mediaState.isPlaying
    val enabled = !env.editing
    val c = env.mediaController
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (prev) WsKey(Icons.Filled.SkipPrevious, stringResource(R.string.horizon_cd_previous_track), false, enabled) { c.previous() }
        WsKey(
            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            stringResource(if (playing) R.string.horizon_cd_pause else R.string.horizon_cd_play),
            true, enabled
        ) { c.playPause() }
        WsKey(Icons.Filled.SkipNext, stringResource(R.string.horizon_cd_next_track), false, enabled) { c.next() }
    }
}

/** A transport key, 56 dp: [main] a lit rounded square with its glow, else the bare icon. */
@Composable
private fun WsKey(icon: ImageVector, label: String, main: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    val fill = wsLit
    val glowK = wsGlow
    val onLit = if (DashColors.Light) Color.White else Color(0xFF041019)
    Box(
        modifier = Modifier
            .size(DashSize.TouchPrimary)
            .then(
                if (main) Modifier.cachedDraw(fill, glowK) {
                    val r = CornerRadius(16.dp.toPx())
                    onDrawBehind {
                        if (glowK > 0f) for (k in 1..3) {
                            val g = k * 3.5f * density
                            drawRoundRect(fill.copy(alpha = 0.09f * glowK), Offset(-g, -g), Size(size.width + 2 * g, size.height + 2 * g), CornerRadius(r.x + g))
                        }
                        drawRoundRect(fill, cornerRadius = r)
                    }
                } else Modifier
            )
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClickLabel = label, role = Role.Button) { tap(); onClick() }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = if (main) onLit else DashColors.TextPrimary, modifier = Modifier.size(30.dp))
    }
}

/** The thin lit progress line; in its own scope, so only it follows the position. */
@Composable
private fun WsProgress(ms: MediaState, controller: CarMediaController, modifier: Modifier) {
    val positionMs = rememberMediaPosition(ms, controller)
    val fraction = if (ms.durationMs > 0L) (positionMs.toFloat() / ms.durationMs).coerceIn(0f, 1f) else 0f
    WsGauge(fraction, wsLit, modifier.height(4.dp))
}

// --- Apps: the QuickSelect column ------------------------------------------------------------

/** One square smoked key: lit (cyan rim and glow) when [on]. */
@Composable
private fun WsSquareKey(size: Dp, on: Boolean, label: String, editing: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(size)
            .wsSmoke((size.value * 0.3f).dp, lit = on, editing = editing)
            .wsTap(true, label, onClick)
            .semantics(mergeDescendants = true) { contentDescription = label },
        contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
private fun WsAppGlyph(app: AppEntry?, size: Dp) {
    if (app != null) AppIcon(icon = app.icon, size = size)
    else Icon(Icons.Filled.Apps, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(size * 0.8f))
}

/** App shortcut: a square smoked key with the app's icon, its name under it where the tile has room. */
@Composable
private fun WsApp(item: DashboardItem.AppShortcut, env: SkinTileEnv) {
    val app = env.appsByPackage[item.packageName]
    val label = appLabel(app, item.packageName)
    val running by NavDirections.running.collectAsState()
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer(), contentAlignment = Alignment.Center) {
        val named = maxHeight - minOf(maxWidth, maxHeight) * 0.9f >= 26.dp || maxHeight >= 150.dp
        val tileW = maxWidth
        val key = (min(maxWidth.value, maxHeight.value - if (named) 30f else 0f) * 0.9f).coerceIn(48f, 128f).dp
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            WsSquareKey(key, running == item.packageName, stringResource(R.string.horizon_open_app, label), env.editing, { if (!env.editing) env.onLaunchApp(item.packageName) }) {
                WsAppGlyph(app, key * 0.5f)
            }
            if (named) {
                Spacer(Modifier.height(6.dp))
                WsLine(label, wsText(15f, DashColors.TextPrimary, FontWeight.Medium), Modifier.widthIn(max = tileW), align = TextAlign.Center)
            }
        }
    }
}

/**
 * Launch bar: the mockup's QuickSelect, square smoked keys one per app, down
 * a column when the tile stands tall (the look's own Home), along a row
 * otherwise. The navigation app that is guiding is lit. Talk ends the column
 * where there is room; while arranging, the pencil to choose the apps does.
 * Apps that would not fit whole are left out.
 */
@Composable
private fun WsQuickSelect(item: DashboardItem.LaunchBar, env: SkinTileEnv) {
    val running by NavDirections.running.collectAsState()
    val context = LocalContext.current
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer()) {
        val column = maxHeight > maxWidth * 1.4f
        val gap = 14.dp
        val key = (if (column) maxWidth - 6.dp else maxHeight - 6.dp).coerceIn(56.dp, 84.dp)
        val length = if (column) maxHeight else maxWidth
        val slots = ((length + gap) / (key + gap)).toInt().coerceAtLeast(1)
        val editLabel = stringResource(R.string.horizon_cd_edit_launch_bar)
        val showEdit = env.editing || item.packages.isEmpty()
        val apps = item.packages.take((slots - if (showEdit) 1 else 0).coerceAtLeast(0))
        val talk = !showEdit && apps.size < slots
        val keys: @Composable () -> Unit = {
            apps.forEach { pkg ->
                val app = env.appsByPackage[pkg]
                val name = appLabel(app, pkg)
                WsSquareKey(key, running == pkg, stringResource(R.string.horizon_open_app, name), env.editing, { if (!env.editing) env.onLaunchApp(pkg) }) {
                    WsAppGlyph(app, key * 0.46f)
                }
            }
            if (talk) WsTalkKey(key, context)
            if (showEdit) {
                WsSquareKey(key, false, editLabel, false, env.onEditLaunchBar) {
                    Icon(if (item.packages.isEmpty()) Icons.Filled.Add else Icons.Filled.Edit, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(key * 0.36f))
                }
            }
        }
        if (column) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) { keys() }
        } else {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) { keys() }
        }
    }
}

/** Talk as a QuickSelect key: the microphone in the lit ink; red with an end mark while a conversation is open. */
@Composable
private fun WsTalkKey(size: Dp, context: android.content.Context) {
    val gemini = remember { GeminiLive.available(context) }
    val active by GeminiLive.active.collectAsState()
    val label = stringResource(if (active) R.string.ai_gemini_live_end else R.string.windshield_talk_cd)
    WsSquareKey(size, false, label, false, { if (gemini || active) GeminiLive.toggle(context) else startVoiceAssistant(context) }) {
        Icon(
            if (active) Icons.Filled.Close else Icons.Filled.Mic, contentDescription = null,
            tint = if (active) DashColors.Critical else wsLit, modifier = Modifier.size(size * 0.4f)
        )
    }
}
