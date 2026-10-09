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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocalGasStation
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/*
 * Simple skin, the tiles (the look itself is in SimpleSkin.kt): every reading
 * on a pane of glass with very few things on it, its main figure as large as
 * the tile allows; the clock alone stands bare and thin on the page. The
 * driver's car stands in the showroom light on the one tile that has room for
 * it (SimpleSkinCar.kt): the fuel and range tile first, else telemetry, else
 * the speed.
 */

private const val DOT = " · "

/** Whole pane tappable with the tap feedback, or nothing when [enabled] is false (arranging, nothing to do). */
@Composable
private fun Modifier.simpleTap(enabled: Boolean, label: String, onClick: () -> Unit): Modifier {
    if (!enabled) return this
    val tap = rememberTapFeedback()
    return clickable(onClickLabel = label, role = Role.Button) { tap(); onClick() }
}

/** Room around a pane's content, following the tile's smaller side. */
private fun padFor(w: Dp, h: Dp): Dp = (min(w.value, h.value) * 0.1f).coerceIn(14f, 30f).dp

/**
 * Simple renderer for app shortcuts, launch bars and the main widgets.
 * Anything else keeps its standard renderer.
 */
@Composable
internal fun SimpleTile(item: DashboardItem, env: SkinTileEnv) {
    when (item) {
        is DashboardItem.AppShortcut -> SimpleApp(item, env)
        is DashboardItem.LaunchBar -> SimpleLaunchBar(item, env)
        is DashboardItem.BuiltinWidget -> when (item.kind) {
            BuiltinKind.TELEMETRY -> SimpleTelemetry(env)
            BuiltinKind.SPEED_HUD -> SimpleSpeed(env)
            BuiltinKind.MEDIA -> SimpleMedia(env)
            BuiltinKind.NAVIGATION -> SimpleDirections(env)
            BuiltinKind.CLOCK -> SimpleClock(env)
            BuiltinKind.WEATHER -> SimpleWeather(env)
            BuiltinKind.RANGE -> SimpleRange(item, env)
            else -> StandardSkinnedTile(item, env)
        }
        else -> StandardSkinnedTile(item, env)
    }
}

// --- Small pieces -----------------------------------------------------------------

/**
 * Children in a row, in order, each one only if it fits whole in the room
 * left (so a chip is dropped, never cut), [gap] apart.
 */
@Composable
private fun FitRow(gap: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
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
            placed.forEachIndexed { i, p -> p?.placeRelative(xs[i], (height - p.height) / 2) }
        }
    }
}

/** A status pill of glass: a coloured dot (hollow when [hollow]) and a short text, [size] dp type. */
@Composable
private fun SimpleChip(text: String, dot: Color?, size: Float, modifier: Modifier = Modifier, hollow: Boolean = false) {
    Row(
        modifier = modifier
            .heightIn(min = (size * 2.3f).dp)
            .simpleGlass(100.dp)
            .padding(horizontal = (size * 0.85f).dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (dot != null) {
            Box(
                Modifier
                    .size((size * 0.48f).dp)
                    .cachedDraw(dot, hollow) {
                        val stroke = Stroke(1.5f * density)
                        onDrawBehind {
                            if (hollow) drawCircle(dot, radius = this.size.minDimension / 2f - stroke.width / 2f, style = stroke)
                            else drawCircle(dot)
                        }
                    }
            )
            Spacer(Modifier.width((size * 0.5f).dp))
        }
        SimpleLine(text, simpleText(size, DashColors.TextPrimary))
    }
}

/** A glass button with its word (and an optional icon); [gold] for the one thing to do. At least 56 dp tall. */
@Composable
private fun SimplePillButton(text: String, icon: ImageVector?, gold: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    val ink = if (gold) SimpleGoldInk else DashColors.TextPrimary
    val top = simpleGoldTop
    val bottom = simpleGoldBottom
    Row(
        modifier = Modifier
            .heightIn(min = DashSize.TouchPrimary)
            .then(
                if (gold) Modifier
                    .cachedDraw(top, bottom) {
                        val fill = Brush.linearGradient(listOf(top, bottom), Offset.Zero, Offset(size.width, size.height))
                        onDrawBehind { drawRoundRect(fill, cornerRadius = CornerRadius(size.height / 2f)) }
                    }
                    .clip(RoundedCornerShape(50))
                else Modifier.simpleGlass(100.dp)
            )
            .clickable(enabled = enabled, role = Role.Button) { tap(); onClick() }
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
        }
        SimpleLine(text, simpleText(18f, ink, FontWeight.SemiBold))
    }
}

/**
 * A quiet state on its pane: an icon in a soft round, a title and one hint;
 * the hint is gold when a tap does something, and the whole pane is the target.
 */
@Composable
private fun SimpleEmpty(icon: ImageVector, title: String, hint: String, editing: Boolean, onTap: (() -> Unit)?) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer()
            .simpleGlass(editing = editing)
            .simpleTap(onTap != null && !editing, hint) { onTap?.invoke() },
        contentAlignment = Alignment.CenterStart
    ) {
        val pad = padFor(maxWidth, maxHeight)
        val wide = maxWidth >= maxHeight * 1.3f
        val roomy = maxHeight >= 220.dp
        val iconD = (min(maxHeight.value * 0.36f, maxWidth.value * 0.2f)).coerceIn(40f, 96f).dp
        val textW = if (wide) maxWidth - pad * 2 - iconD - 18.dp else maxWidth - pad * 2
        val titleSize = min(maxHeight.value * 0.15f, textW.value / (title.length * 0.42f + 1f)).coerceIn(17f, 40f)
        val hintSize = (titleSize * 0.6f).coerceIn(14f, 22f)
        val glyph: @Composable () -> Unit = {
            Box(
                Modifier
                    .size(iconD)
                    .clip(CircleShape)
                    .cachedDraw(DashColors.Light) {
                        val fill = if (DashColors.Light) Color.Black.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.07f)
                        onDrawBehind { drawCircle(fill) }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(iconD * 0.5f))
            }
        }
        val words: @Composable () -> Unit = {
            Column {
                SimpleLine(title, simpleText(titleSize, DashColors.TextPrimary, FontWeight.SemiBold), maxLines = 2)
                Spacer(Modifier.height(4.dp))
                SimpleLine(hint, simpleText(hintSize, if (onTap != null) DashColors.Accent else DashColors.TextSecondary), maxLines = 2)
            }
        }
        if (wide) {
            Row(Modifier.padding(pad), verticalAlignment = Alignment.CenterVertically) {
                glyph()
                Spacer(Modifier.width(18.dp))
                words()
            }
        } else {
            Column(Modifier.padding(pad)) {
                if (roomy) {
                    glyph()
                    Spacer(Modifier.height(14.dp))
                }
                words()
            }
        }
    }
}

// --- Speed --------------------------------------------------------------------------

/**
 * Speed: the number huge on a glass card with the road's limit sign beside it
 * when it is known. With the most room (and the car's priority, see
 * [rememberSimpleCarHost]), the card stands beside the car in its light.
 */
@Composable
private fun SimpleSpeed(env: SkinTileEnv) {
    val context = env.context
    DisposableEffect(Unit) {
        SpeedLimit.start(context)
        onDispose { SpeedLimit.stop() }
    }
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer()) {
        val wide = maxWidth >= 600.dp && maxHeight >= 260.dp && maxWidth >= maxHeight * 1.5f
        val tall = !wide && maxWidth >= 300.dp && maxHeight >= 480.dp && maxHeight >= maxWidth * 1.3f
        val host = rememberSimpleCarHost(SIMPLE_CAR_SPEED, wide || tall)
        when {
            host && wide -> Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                SpeedCard(env, Modifier.weight(0.46f).fillMaxHeight(0.72f))
                Spacer(Modifier.width(12.dp))
                SimpleShowroomCar(Modifier.weight(0.54f).fillMaxHeight())
            }
            host && tall -> Column(Modifier.fillMaxSize()) {
                SimpleShowroomCar(Modifier.weight(0.55f).fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                SpeedCard(env, Modifier.weight(0.45f).fillMaxWidth())
            }
            else -> SpeedCard(env, Modifier.fillMaxSize())
        }
    }
}

/** The glass speed card; in its own scope, so a new reading redraws the card and not the car beside it. */
@Composable
private fun SpeedCard(env: SkinTileEnv, modifier: Modifier) {
    val speed = rememberSpeedKmh(env.obdData, env.obdConnection)
    val limit by SpeedLimit.limit.collectAsState()
    val units = LocalUnits.current
    val idle = env.obdConnection.isIdle
    val canConnect = speed == null && idle && !env.editing
    val over = speed != null && limit != null && speed > limit!! + SpeedLimit.TOLERANCE_KMH
    val color = when {
        speed == null -> DashColors.Muted
        over -> DashColors.Critical
        limit == null && speed >= SPEED_WARNING_KMH -> DashColors.Warning
        else -> DashColors.TextPrimary
    }
    val said = speed?.let { "${units.speed(it)} ${units.speedUnit}" } ?: stringResource(R.string.info_speed_no_signal)
    BoxWithConstraints(
        modifier = modifier
            .simpleGlass(editing = env.editing)
            .simpleTap(canConnect, stringResource(R.string.horizon_connect_obd), env.onConnectObd)
            .semantics(mergeDescendants = true) { contentDescription = said }
    ) {
        val padH = (maxWidth.value * 0.08f).coerceIn(16f, 36f).dp
        val padV = (maxHeight.value * 0.1f).coerceIn(10f, 28f).dp
        val innerW = maxWidth - padH * 2
        val innerH = maxHeight - padV * 2
        val shownLimit = limit
        val sign = shownLimit != null && innerW >= 200.dp && innerH >= 60.dp
        val signD = if (sign) min(min(innerH.value * 0.72f, innerW.value * 0.3f), 140f).coerceAtLeast(52f).dp else 0.dp
        val gap = if (sign) (signD * 0.22f).coerceAtLeast(12.dp) else 0.dp
        val textW = innerW - signD - gap
        val hint = when {
            speed != null -> null
            idle -> stringResource(R.string.skin_simple_no_signal_connect)
            else -> stringResource(R.string.info_speed_no_signal)
        }
        val hintSize = (innerH.value * 0.12f).coerceIn(13f, 20f)
        // A roomy card adds a thin speed scale under the number (with the limit marked on it)
        // and where the speed comes from, so a big pane is not a lone number in empty glass.
        val roomy = speed != null && innerH >= 190.dp && textW >= 220.dp
        val sourceSize = (innerH.value * 0.06f).coerceIn(13f, 18f)
        val scaleBlock = if (roomy) (14f + 7f + 10f + sourceSize * 1.3f).dp else 0.dp
        val numH = innerH - scaleBlock - if (hint != null) (hintSize * 1.3f + 6f).dp else 0.dp
        val numSize = simpleFit("188", simpleText(100f, weight = FontWeight.SemiBold, figure = true), textW * 0.7f, numH, 28f, 260f)
        val unitSize = (numSize * 0.24f).coerceIn(14f, 40f)
        Row(Modifier.fillMaxSize().padding(horizontal = padH, vertical = padV), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row {
                    // A lone dash at the number's size reads as a bar, so the placeholder is smaller.
                    if (speed != null) {
                        SimpleLine(units.speed(speed).toString(), simpleText(numSize, color, FontWeight.SemiBold, figure = true), Modifier.alignByBaseline(), overflow = TextOverflow.Clip)
                    } else {
                        SimpleLine("–", simpleText(numSize * 0.5f, color, FontWeight.SemiBold, figure = true), Modifier.alignByBaseline(), overflow = TextOverflow.Clip)
                    }
                    Spacer(Modifier.width((unitSize * 0.4f).dp))
                    SimpleLine(units.speedUnit, simpleText(unitSize, DashColors.Muted, FontWeight.Light), Modifier.alignByBaseline().weight(1f, fill = false))
                }
                if (hint != null) {
                    Spacer(Modifier.height(6.dp))
                    SimpleLine(hint, simpleText(hintSize, if (canConnect) DashColors.Accent else DashColors.TextSecondary), maxLines = 2)
                }
                if (roomy) {
                    Spacer(Modifier.height(14.dp))
                    SpeedScale(speed, limit, color, Modifier.fillMaxWidth().height(7.dp))
                    Spacer(Modifier.height(10.dp))
                    SimpleLine(
                        speedSource(env.obdConnection == ObdConnectionState.CONNECTED, speed, ""),
                        simpleText(sourceSize, DashColors.Muted, FontWeight.Medium).copy(letterSpacing = 0.2.em)
                    )
                }
            }
            if (sign) {
                Spacer(Modifier.width(gap))
                SpeedLimitSign(units.speed(shownLimit), signD)
            }
        }
    }
}

/** Top of the speed scale under a roomy speed card, in km/h. */
private const val SCALE_MAX_KMH = 180f

/**
 * A thin scale under the speed: a faint track filled to the speed in the
 * number's colour, and the road's limit as a short white tick when known.
 */
@Composable
private fun SpeedScale(speed: Int, limit: Int?, fill: Color, modifier: Modifier) {
    val track = DashColors.TextPrimary.copy(alpha = 0.12f)
    val mark = DashColors.TextPrimary
    Box(
        modifier.cachedDraw(speed, limit, fill, track, mark) {
            val h = size.height
            val r = CornerRadius(h / 2f)
            val fw = (size.width * (speed / SCALE_MAX_KMH).coerceIn(0f, 1f)).coerceAtLeast(if (speed > 0) h else 0f)
            val lx = limit?.let { size.width * (it / SCALE_MAX_KMH).coerceIn(0f, 1f) }
            onDrawBehind {
                drawRoundRect(track, cornerRadius = r)
                if (fw > 0f) drawRoundRect(fill, size = Size(fw, h), cornerRadius = r)
                if (lx != null) drawLine(mark, Offset(lx, -h * 0.6f), Offset(lx, h * 1.6f), 2f * density, StrokeCap.Round)
            }
        }
    )
}

/** The road's limit as its sign: a white disc in a red ring, the number in black (a road sign keeps its colours day and night). */
@Composable
private fun SpeedLimitSign(limit: Int, d: Dp) {
    val light = DashColors.Light
    val said = stringResource(R.string.skin_simple_speed_limit_cd, limit)
    Box(
        modifier = Modifier
            .size(d)
            .semantics { contentDescription = said }
            .cachedDraw(light) {
                val ring = size.minDimension * 0.1f
                val edge = Stroke(1f * density)
                onDrawBehind {
                    val r = size.minDimension / 2f
                    drawCircle(Color.White, radius = r)
                    drawCircle(SignRed, radius = r - ring / 2f, style = Stroke(ring))
                    // By day a white sign on a white card needs its edge.
                    if (light) drawCircle(Color.Black.copy(alpha = 0.18f), radius = r - edge.width / 2f, style = edge)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val digits = limit.toString().length
        SimpleLine(limit.toString(), simpleText(d.value * if (digits >= 3) 0.3f else 0.38f, Color(0xFF111111), FontWeight.Bold, figure = true), overflow = TextOverflow.Clip)
    }
}

private val SignRed = Color(0xFFE5484D)

// --- Telemetry ----------------------------------------------------------------------

/**
 * Telemetry: the OBD speed huge, then the engine's readings as glass chips
 * with coloured dots (rpm, coolant, battery, load, as many as fit). With
 * the room and the car's priority, the readings stand bare beside the car;
 * otherwise they sit on a pane.
 */
@Composable
private fun SimpleTelemetry(env: SkinTileEnv) {
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer()) {
        val wide = maxWidth >= 560.dp && maxHeight >= 260.dp && maxWidth >= maxHeight * 1.4f
        val tall = !wide && maxWidth >= 320.dp && maxHeight >= 520.dp && maxHeight >= maxWidth * 1.3f
        val host = rememberSimpleCarHost(SIMPLE_CAR_TELEMETRY, wide || tall)
        when {
            host && wide -> Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                TelemetryPanel(env, glass = false, modifier = Modifier.weight(0.46f).fillMaxHeight())
                SimpleShowroomCar(Modifier.weight(0.54f).fillMaxHeight())
            }
            host && tall -> Column(Modifier.fillMaxSize()) {
                SimpleShowroomCar(Modifier.weight(0.5f).fillMaxWidth())
                TelemetryPanel(env, glass = false, modifier = Modifier.weight(0.5f).fillMaxWidth())
            }
            else -> TelemetryPanel(env, glass = true, modifier = Modifier.fillMaxSize())
        }
    }
}

private class Reading(val text: String, val dot: Color)

@Composable
private fun TelemetryPanel(env: SkinTileEnv, glass: Boolean, modifier: Modifier) {
    val state = env.obdConnection
    val connected = state == ObdConnectionState.CONNECTED
    val d = env.obdData
    val units = LocalUnits.current
    val context = env.context
    val readings = if (connected) buildList {
        add(Reading(context.getString(R.string.horizon_rpm_value, groupThousands(d.rpm)), DashColors.Secondary))
        if (d.coolantTempC > 0) {
            val dot = when {
                d.coolantTempC >= 105 -> DashColors.Warning
                d.coolantTempC < 60 -> DashColors.Secondary
                else -> DashColors.Good
            }
            add(Reading(context.getString(R.string.horizon_coolant_value, units.temp(d.coolantTempC)), dot))
        }
        if (d.voltage > 0.0) {
            add(Reading(String.format(Locale.getDefault(), "%.1f V", d.voltage), if (d.voltage < 11.8 || d.voltage > 15.0) DashColors.Warning else DashColors.Good))
        }
        add(Reading(context.getString(R.string.horizon_load_value, d.engineLoadPct), DashColors.Muted))
    } else emptyList()
    BoxWithConstraints(
        modifier = modifier
            .then(if (glass) Modifier.simpleGlass(editing = env.editing) else Modifier)
            .simpleTap(state.isIdle && !env.editing, stringResource(R.string.horizon_connect_obd), env.onConnectObd)
    ) {
        val pad = padFor(maxWidth, maxHeight)
        val innerW = maxWidth - pad * 2
        val innerH = maxHeight - pad * 2
        val chipSize = (min(innerW.value / 16f, innerH.value * 0.09f)).coerceIn(14f, 21f)
        val belowH = when {
            connected -> (chipSize * 2.3f + 12f).dp
            else -> (DashSize.TouchPrimary.value + 12f).dp
        }
        val numSize = simpleFit("188", simpleText(100f, weight = FontWeight.SemiBold, figure = true), innerW * 0.68f, innerH - belowH, 28f, 220f)
        val unitSize = (numSize * 0.24f).coerceIn(14f, 36f)
        Column(Modifier.fillMaxSize().padding(pad), verticalArrangement = Arrangement.Center) {
            Row {
                if (connected) {
                    SimpleLine(units.speed(d.speedKmh).toString(), simpleText(numSize, DashColors.TextPrimary, FontWeight.SemiBold, figure = true), Modifier.alignByBaseline(), overflow = TextOverflow.Clip)
                } else {
                    SimpleLine("–", simpleText(numSize * 0.5f, DashColors.Muted, FontWeight.SemiBold, figure = true), Modifier.alignByBaseline(), overflow = TextOverflow.Clip)
                }
                Spacer(Modifier.width((unitSize * 0.4f).dp))
                SimpleLine(units.speedUnit, simpleText(unitSize, DashColors.Muted, FontWeight.Light), Modifier.alignByBaseline().weight(1f, fill = false))
            }
            Spacer(Modifier.height(12.dp))
            when (state) {
                ObdConnectionState.CONNECTED -> FitRow(10.dp) {
                    readings.forEach { SimpleChip(it.text, it.dot, chipSize) }
                }
                ObdConnectionState.CONNECTING -> SimpleChip(stringResource(R.string.horizon_obd_connecting), DashColors.Accent, chipSize)
                else -> FitRow(10.dp) {
                    SimplePillButton(stringResource(R.string.horizon_connect_obd), null, gold = true, enabled = !env.editing, onClick = env.onConnectObd)
                    if (state == ObdConnectionState.ERROR) {
                        SimplePillButton(stringResource(R.string.tape_cd_choose_adapter), null, gold = false, enabled = !env.editing, onClick = env.onPickDevice)
                    }
                }
            }
        }
    }
}

// --- Fuel and range -----------------------------------------------------------------

/**
 * Fuel and range. With the room and the car's priority, the showroom of the
 * design: the car in its light, the range large above it and the doors and
 * fuel as glass chips. Otherwise a pane: the range as one big number, the tank
 * as a bar, the level in words. Fuel not learned yet falls back to the
 * standard tile, which explains how to learn it.
 */
@Composable
private fun SimpleRange(item: DashboardItem, env: SkinTileEnv) {
    val fuel = rememberFuel(env.obdData, env.obdConnection)
    if (fuel == null) {
        StandardSkinnedTile(item, env)
        return
    }
    val doors by McuReader.doorState.collectAsState()
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer()) {
        val host = rememberSimpleCarHost(SIMPLE_CAR_RANGE, maxWidth >= 340.dp && maxHeight >= 250.dp)
        if (host) RangeShowroom(fuel, doors, maxWidth, maxHeight)
        else RangePane(fuel, doors, env.editing)
    }
}

/** The doors and the fuel as status chips; the doors only once the car box has said something. */
@Composable
private fun StatusChips(fuel: FuelInfo, doors: McuReader.DoorState?, size: Float, withRange: Boolean) {
    val units = LocalUnits.current
    val low = fuel.percent <= SKIN_LOW_FUEL_PCT
    FitRow(10.dp) {
        if (doors != null) {
            SimpleChip(
                stringResource(if (doors.anyOpen) R.string.vehicle_door_any_open else R.string.skin_simple_doors_closed),
                if (doors.anyOpen) DashColors.Warning else DashColors.Good, size
            )
        }
        SimpleChip(
            if (withRange) stringResource(R.string.skin_simple_fuel_chip, fuel.percent, units.distanceText(fuel.rangeKm))
            else stringResource(R.string.skin_simple_fuel_chip_short, fuel.percent),
            if (low) DashColors.Warning else DashColors.Good, size
        )
    }
}

@Composable
private fun RangeShowroom(fuel: FuelInfo, doors: McuReader.DoorState?, w: Dp, h: Dp) {
    val units = LocalUnits.current
    val low = fuel.percent <= SKIN_LOW_FUEL_PCT
    val chipSize = (min(w.value / 26f, h.value / 22f)).coerceIn(15f, 22f)
    // A tall showroom says the range in big type too; a low one leaves it to the chip.
    val figure = h >= 380.dp
    val figSize = if (figure) (h.value * 0.11f).coerceIn(34f, 72f) else 0f
    val topH = (if (figure) figSize * 1.15f + 10f else 0f) + chipSize * 2.3f + 8f
    Box(Modifier.fillMaxSize()) {
        SimpleShowroomCar(Modifier.fillMaxSize().padding(top = (topH * 0.8f).dp))
        Column(Modifier.padding(start = 8.dp, top = 4.dp, end = 8.dp)) {
            if (figure) {
                Row {
                    SimpleLine(
                        units.distance(fuel.rangeKm).toString(),
                        simpleText(figSize, if (low) DashColors.Warning else DashColors.TextPrimary, FontWeight.Light, figure = true),
                        Modifier.alignByBaseline(), overflow = TextOverflow.Clip
                    )
                    Spacer(Modifier.width((figSize * 0.15f).dp))
                    SimpleLine(units.distanceUnit, simpleText(figSize * 0.34f, DashColors.Muted, FontWeight.Light), Modifier.alignByBaseline())
                }
                Spacer(Modifier.height(10.dp))
            }
            StatusChips(fuel, doors, chipSize, withRange = !figure)
        }
    }
}

@Composable
private fun RangePane(fuel: FuelInfo, doors: McuReader.DoorState?, editing: Boolean) {
    val units = LocalUnits.current
    val low = fuel.percent <= SKIN_LOW_FUEL_PCT
    BoxWithConstraints(Modifier.fillMaxSize().simpleGlass(editing = editing)) {
        val pad = padFor(maxWidth, maxHeight)
        val innerW = maxWidth - pad * 2
        val innerH = maxHeight - pad * 2
        val capSize = (innerH.value * 0.09f).coerceIn(14f, 20f)
        val lineSize = (capSize * 0.95f).coerceIn(14f, 20f)
        val barH = (innerH.value * 0.05f).coerceIn(8f, 14f).dp
        val showDoors = innerW >= 320.dp
        val numH = innerH - (capSize * 1.3f + lineSize * 1.3f + 24f).dp - barH
        val numSize = simpleFit("888", simpleText(100f, weight = FontWeight.SemiBold, figure = true), innerW * 0.68f, numH, 26f, 200f)
        Column(Modifier.fillMaxSize().padding(pad), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.LocalGasStation, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size((capSize * 1.2f).dp))
                Spacer(Modifier.width(8.dp))
                SimpleLine(stringResource(R.string.car_status_range), simpleText(capSize, DashColors.TextSecondary), Modifier.weight(1f))
                if (showDoors && doors != null) {
                    SimpleChip(
                        stringResource(if (doors.anyOpen) R.string.vehicle_door_any_open else R.string.skin_simple_doors_closed),
                        if (doors.anyOpen) DashColors.Warning else DashColors.Good, (capSize * 0.85f).coerceAtLeast(13f)
                    )
                }
            }
            Row {
                SimpleLine(units.distance(fuel.rangeKm).toString(), simpleText(numSize, if (low) DashColors.Warning else DashColors.TextPrimary, FontWeight.SemiBold, figure = true), Modifier.alignByBaseline(), overflow = TextOverflow.Clip)
                Spacer(Modifier.width((numSize * 0.08f).dp))
                SimpleLine(units.distanceUnit, simpleText((numSize * 0.3f).coerceAtLeast(14f), DashColors.Muted, FontWeight.Light), Modifier.alignByBaseline())
            }
            FuelBar(fuel.percent / 100f, if (low) DashColors.Warning else DashColors.Good, Modifier.fillMaxWidth().height(barH))
            SimpleLine(
                String.format(Locale.getDefault(), "%s%d %%$DOT%.0f L", if (fuel.percentEstimated) "≈ " else "", fuel.percent, fuel.liters),
                simpleText(lineSize, if (low) DashColors.Warning else DashColors.TextSecondary)
            )
        }
    }
}

/** The tank as a rounded bar: a faint track and the level in [color]. */
@Composable
private fun FuelBar(fraction: Float, color: Color, modifier: Modifier) {
    val track = DashColors.TextPrimary.copy(alpha = 0.12f)
    Box(
        modifier.cachedDraw(fraction, color, track) {
            val r = CornerRadius(size.height / 2f)
            val fw = (size.width * fraction.coerceIn(0f, 1f)).coerceAtLeast(if (fraction > 0f) size.height else 0f)
            onDrawBehind {
                drawRoundRect(track, cornerRadius = r)
                if (fw > 0f) drawRoundRect(color, size = Size(fw, size.height), cornerRadius = r)
            }
        }
    )
}

// --- Directions ---------------------------------------------------------------------

/**
 * The next turn on the gold pane of the design: the manoeuvre in dark ink on a
 * gold rounded square, the distance large, the instruction and the arrival
 * under it. Tap opens the navigation app. Without a route (or without
 * notification access), a quiet clear pane says so and how to start.
 */
@Composable
private fun SimpleDirections(env: SkinTileEnv) {
    val nav by NavDirections.state.collectAsState()
    val context = env.context
    val canTap = !env.editing
    when {
        !env.hasMediaAccess -> SimpleEmpty(
            Icons.Filled.NotificationsActive,
            stringResource(R.string.info_directions_access_title), stringResource(R.string.horizon_tap_allow_notification_access), env.editing,
            if (canTap) ({ CarMediaController.openNotificationAccessSettings(context) }) else null
        )
        !nav.active -> SimpleEmpty(
            Icons.Filled.Navigation,
            stringResource(R.string.info_directions_no_route), stringResource(R.string.horizon_start_navigation), env.editing,
            if (canTap) ({ openNavigationApp(context, nav) }) else null
        )
        else -> BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer()
                .simpleGlass(tone = SimpleTone.GOLD, editing = env.editing)
                .simpleTap(canTap, stringResource(R.string.horizon_open_navigation)) { openNavigationApp(context, nav) }
        ) {
            val pad = padFor(maxWidth, maxHeight)
            val innerW = maxWidth - pad * 2
            val innerH = maxHeight - pad * 2
            val stacked = maxHeight > maxWidth * 1.05f
            val tile = (if (stacked) min(innerW.value * 0.42f, innerH.value * 0.3f) else min(innerH.value * 0.78f, innerW.value * 0.26f)).coerceIn(48f, 150f).dp
            val gap = (tile * 0.24f).coerceIn(14.dp, 28.dp)
            val textW = if (stacked) innerW else innerW - tile - gap
            val instrSize = (textW.value / 17f).coerceIn(15f, 30f)
            val etaSize = (instrSize * 0.75f).coerceIn(13f, 22f)
            val instrLines = if ((if (stacked) innerH - tile - gap else innerH) > 200.dp) 2 else 1
            val eta = nav.etaParts.joinToString(DOT)
            val textH = (if (stacked) innerH - tile - gap else innerH) -
                (instrSize * 1.25f * instrLines + (if (eta.isNotEmpty()) etaSize * 1.3f else 0f) + 8f).dp
            val (value, unit) = nav.distanceParts
            val distSample = simpleTemplate(if (unit.isEmpty()) value.ifEmpty { "888" } else "$value $unit")
            val distSize = simpleFit(distSample, simpleText(100f, weight = FontWeight.SemiBold, figure = true), textW, textH, 22f, 180f)
            val etaInk = if (DashColors.Light) lerp(DashColors.TextSecondary, DashColors.Accent, 0.45f) else Color(0xFFE6D7B0)
            val words: @Composable () -> Unit = {
                Column {
                    if (value.isNotEmpty()) {
                        Row {
                            SimpleLine(value, simpleText(distSize, DashColors.TextPrimary, FontWeight.SemiBold, figure = true), Modifier.alignByBaseline(), overflow = TextOverflow.Clip)
                            if (unit.isNotEmpty()) {
                                Spacer(Modifier.width((distSize * 0.12f).dp))
                                SimpleLine(unit, simpleText(distSize * 0.42f, DashColors.TextPrimary, FontWeight.Light), Modifier.alignByBaseline())
                            }
                        }
                    }
                    // Without a distance the instruction is the headline, so it takes the room the number would have had.
                    if (value.isEmpty()) {
                        SimpleLine(nav.instruction, simpleText((instrSize * 1.5f).coerceAtMost(44f), DashColors.TextPrimary, FontWeight.SemiBold), maxLines = instrLines + 1)
                    } else {
                        SimpleLine(nav.instruction, simpleText(instrSize, DashColors.TextPrimary, FontWeight.Medium), maxLines = instrLines)
                    }
                    if (eta.isNotEmpty()) SimpleLine(eta, simpleText(etaSize, etaInk))
                }
            }
            if (stacked) {
                Column(Modifier.fillMaxSize().padding(pad), verticalArrangement = Arrangement.Center) {
                    ArrowTile(nav, tile)
                    Spacer(Modifier.height(gap))
                    words()
                }
            } else {
                Row(Modifier.fillMaxSize().padding(pad), verticalAlignment = Alignment.CenterVertically) {
                    ArrowTile(nav, tile)
                    Spacer(Modifier.width(gap))
                    words()
                }
            }
        }
    }
}

/** The manoeuvre in dark ink on the gold rounded square, a warm glow under it (none with effects off). */
@Composable
private fun ArrowTile(nav: NavState, size: Dp) {
    val top = simpleGoldTop
    val bottom = simpleGoldBottom
    val glow = DashColors.Effects.scale * if (DashColors.Light) 0.5f else 1f
    val bitmap = remember(nav.icon) { nav.icon?.asImageBitmap() }
    Box(
        modifier = Modifier
            .size(size)
            .cachedDraw(top, bottom, glow) {
                val r = size.toPx() * 0.29f
                val fill = Brush.linearGradient(listOf(top, bottom), Offset.Zero, Offset(this.size.width, this.size.height))
                onDrawBehind {
                    if (glow > 0f) {
                        for (k in 1..3) {
                            val g = k * this.size.width * 0.05f
                            drawRoundRect(
                                bottom.copy(alpha = 0.1f * glow),
                                topLeft = Offset(-g, -g + this.size.height * 0.08f),
                                size = Size(this.size.width + 2f * g, this.size.height + 2f * g),
                                cornerRadius = CornerRadius(r + g)
                            )
                        }
                    }
                    drawRoundRect(fill, cornerRadius = CornerRadius(r))
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = nav.instruction,
                contentScale = ContentScale.Fit,
                colorFilter = ColorFilter.tint(SimpleGoldInk),
                modifier = Modifier.size(size * 0.62f)
            )
        } else {
            Icon(Icons.Filled.Directions, contentDescription = nav.instruction, tint = SimpleGoldInk, modifier = Modifier.size(size * 0.58f))
        }
    }
}

// --- Media --------------------------------------------------------------------------

/**
 * Music on a pane: the cover (or a music note on soft gold), the song large
 * and who sings it, a thin gold progress line and big round controls, play in
 * gold. Low tiles become one row; wide ones put the controls at the end.
 */
@Composable
private fun SimpleMedia(env: SkinTileEnv) {
    val ms = env.mediaState
    val context = env.context
    if (!env.hasMediaAccess) {
        SimpleEmpty(
            Icons.Filled.MusicNote,
            stringResource(R.string.info_media_access_needed), stringResource(R.string.horizon_tap_allow_notification_access), env.editing,
            if (!env.editing) ({ CarMediaController.openNotificationAccessSettings(context) }) else null
        )
        return
    }
    val hasTrack = ms.hasMedia && ms.title.isNotBlank()
    val title = if (hasTrack) ms.title else stringResource(R.string.info_nothing_playing)
    val sub = if (hasTrack) ms.artist.ifBlank { stringResource(R.string.horizon_unknown_artist) } else stringResource(R.string.horizon_play_something)
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer().simpleGlass(editing = env.editing)) {
        val pad = padFor(maxWidth, maxHeight)
        val innerW = maxWidth - pad * 2
        val innerH = maxHeight - pad * 2
        val compact = maxHeight < 170.dp
        val wide = !compact && maxWidth > maxHeight * 2.1f
        val showProgress = ms.durationMs > 0L
        when {
            compact -> {
                val art = innerH.coerceIn(40.dp, 96.dp)
                val titleSize = (innerH.value * 0.24f).coerceIn(16f, 26f)
                val prev = innerW >= 480.dp
                // A narrow strip gives the words the cover's room: the keys alone take half of it.
                val showArt = innerW >= 380.dp
                Row(Modifier.fillMaxSize().padding(pad), verticalAlignment = Alignment.CenterVertically) {
                    if (showArt) {
                        Cover(ms, art)
                        Spacer(Modifier.width(16.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        SimpleLine(title, simpleText(titleSize, DashColors.TextPrimary, FontWeight.SemiBold))
                        SimpleLine(sub, simpleText(titleSize * 0.72f, DashColors.TextSecondary))
                        if (showProgress) {
                            Spacer(Modifier.height(6.dp))
                            MediaProgress(ms, env.mediaController, false, Modifier.fillMaxWidth())
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    MediaKeys(env, 56.dp, prev)
                }
            }
            wide -> {
                // The words come first: the keys take what they need, then the title keeps at
                // least TEXT_MIN, and only what is left goes to the cover (dropped under 56 dp).
                // A strip too narrow for that also drops the previous key.
                val key = (innerH.value * 0.32f).coerceIn(56f, 84f).dp
                fun keysWidth(prev: Boolean) = key * (if (prev) 3.15f else 2.15f) + key * 0.2f * (if (prev) 2 else 1)
                val prev = innerW - keysWidth(true) - 24.dp - TEXT_MIN >= 56.dp + 20.dp
                val spare = innerW - keysWidth(prev) - 24.dp - TEXT_MIN - 20.dp
                val art = minOf(innerH * 0.92f, 220.dp, spare)
                val showArt = art >= 56.dp
                val textW = innerW - keysWidth(prev) - 24.dp - if (showArt) art + 20.dp else 0.dp
                val titleSize = min(innerH.value * 0.17f, textW.value / 9f).coerceIn(17f, 44f)
                Row(Modifier.fillMaxSize().padding(pad), verticalAlignment = Alignment.CenterVertically) {
                    if (showArt) {
                        Cover(ms, art)
                        Spacer(Modifier.width(20.dp))
                    }
                    Column(Modifier.weight(1f).widthIn(min = TEXT_MIN)) {
                        SimpleLine(title, simpleText(titleSize, DashColors.TextPrimary, FontWeight.SemiBold), maxLines = if (innerH > 200.dp) 2 else 1)
                        SimpleLine(sub, simpleText(titleSize * 0.66f, DashColors.TextSecondary))
                        if (showProgress) {
                            Spacer(Modifier.height(12.dp))
                            MediaProgress(ms, env.mediaController, innerH >= 150.dp, Modifier.fillMaxWidth())
                        }
                    }
                    Spacer(Modifier.width(24.dp))
                    MediaKeys(env, key, prev)
                }
            }
            else -> {
                val key = (min(innerH.value * 0.2f, innerW.value * 0.2f)).coerceIn(56f, 84f).dp
                val art = (min(innerW.value * 0.3f, innerH.value * 0.34f)).coerceIn(48f, 180f).dp
                val titleSize = min(art.value * 0.3f, innerW.value / 12f).coerceIn(17f, 40f)
                Column(Modifier.fillMaxSize().padding(pad)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Cover(ms, art)
                        Spacer(Modifier.width(18.dp))
                        Column {
                            SimpleLine(title, simpleText(titleSize, DashColors.TextPrimary, FontWeight.SemiBold), maxLines = 2)
                            SimpleLine(sub, simpleText(titleSize * 0.66f, DashColors.TextSecondary))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (showProgress) {
                        MediaProgress(ms, env.mediaController, innerH >= 300.dp, Modifier.fillMaxWidth())
                        Spacer(Modifier.height(12.dp))
                    }
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { MediaKeys(env, key, true) }
                }
            }
        }
    }
}

/** The least room the song's title keeps in a row of music, whatever else gives way. */
private val TEXT_MIN = 140.dp

/** The song's cover rounded, or a music note on soft gold while the player publishes none. */
@Composable
private fun Cover(ms: MediaState, size: Dp) {
    val bitmap = remember(ms.artwork) { ms.artwork?.asImageBitmap() }
    val gold = DashColors.Accent
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.2f))
            .cachedDraw(gold) {
                val fill = Brush.linearGradient(listOf(gold.copy(alpha = 0.4f), gold.copy(alpha = 0.12f)), Offset.Zero, Offset(this.size.width, this.size.height))
                onDrawBehind { drawRect(fill) }
            },
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(Icons.Filled.MusicNote, contentDescription = null, tint = DashColors.TextPrimary, modifier = Modifier.size(size * 0.42f))
    }
}

/** Previous (when [prev]), play / pause in gold and next, [size] each, play a little larger. */
@Composable
private fun MediaKeys(env: SkinTileEnv, size: Dp, prev: Boolean) {
    val playing = env.mediaState.isPlaying
    val enabled = !env.editing
    val c = env.mediaController
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(size * 0.2f)) {
        if (prev) MediaKey(Icons.Filled.SkipPrevious, stringResource(R.string.horizon_cd_previous_track), size, false, enabled) { c.previous() }
        MediaKey(
            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            stringResource(if (playing) R.string.horizon_cd_pause else R.string.horizon_cd_play),
            size * 1.15f, true, enabled
        ) { c.playPause() }
        MediaKey(Icons.Filled.SkipNext, stringResource(R.string.horizon_cd_next_track), size, false, enabled) { c.next() }
    }
}

@Composable
private fun MediaKey(icon: ImageVector, label: String, size: Dp, gold: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    val top = simpleGoldTop
    val bottom = simpleGoldBottom
    Box(
        modifier = Modifier
            .size(size)
            .then(
                if (gold) Modifier
                    .cachedDraw(top, bottom) {
                        val fill = Brush.linearGradient(listOf(top, bottom), Offset.Zero, Offset(this.size.width, this.size.height))
                        onDrawBehind { drawCircle(fill) }
                    }
                    .clip(CircleShape)
                else Modifier.simpleGlass(size)
            )
            .clickable(enabled = enabled, onClickLabel = label, role = Role.Button) { tap(); onClick() }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = if (gold) SimpleGoldInk else DashColors.TextPrimary, modifier = Modifier.size(size * 0.5f))
    }
}

/** The thin gold progress line and, with [times], elapsed and total time; in its own scope, so only it follows the position. */
@Composable
private fun MediaProgress(ms: MediaState, controller: CarMediaController, times: Boolean, modifier: Modifier) {
    val positionMs = rememberMediaPosition(ms, controller)
    val fraction = if (ms.durationMs > 0L) (positionMs.toFloat() / ms.durationMs).coerceIn(0f, 1f) else 0f
    Column(modifier) {
        FuelBar(fraction, DashColors.Accent, Modifier.fillMaxWidth().height(5.dp))
        if (times) {
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth()) {
                SimpleLine(formatTrackTime(positionMs), simpleText(14f, DashColors.Muted))
                Spacer(Modifier.weight(1f))
                SimpleLine(formatTrackTime(ms.durationMs), simpleText(14f, DashColors.Muted))
            }
        }
    }
}

// --- Clock and weather --------------------------------------------------------------

/** Skeleton of weekday, day and month ("Friday 9 October"), in the locale's order. */
private const val LONG_DATE = "EEEEdMMMM"

/**
 * Clock: the time bare on the page in very large thin type, and under it the
 * date with the weather ("Friday 9 October · 14° cloudy") where it fits.
 * Tap opens the alarms.
 */
@Composable
private fun SimpleClock(env: SkinTileEnv) {
    val now = rememberNow(60_000L)
    val locale = Locale.getDefault()
    val units = LocalUnits.current
    val timeFmt = rememberDateFormat(units.digitsPattern())
    val dateFmt = rememberDateFormat(LONG_DATE, best = true)
    val weather by WeatherRepo.weather.collectAsState()
    val context = env.context
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer()
            .clip(RoundedCornerShape(SIMPLE_CARD))
            .simpleTap(!env.editing, stringResource(R.string.horizon_open_clock)) { openClockApp(context) },
        contentAlignment = Alignment.CenterStart
    ) {
        val pad = (min(maxWidth.value, maxHeight.value) * 0.06f).coerceIn(8f, 20f).dp
        val innerW = maxWidth - pad * 2
        val innerH = maxHeight - pad * 2
        val dateSize = min(innerW.value / 20f, innerH.value * 0.11f).coerceIn(14f, 30f)
        val date = dateFmt.format(now).replaceFirstChar { it.titlecase(locale) }
        val w = weather
        val dateStyle = simpleText(dateSize, DashColors.Muted, FontWeight.Light)
        // Lowercased mid-line, except German, whose nouns stay capitalised.
        val line = if (w != null) {
            val temp = "${units.temp(w.tempC)}°"
            val cond = w.condition.let { if (locale.language == "de") it else it.lowercase(locale) }
            simpleFirstFitting(listOf("$date$DOT$temp $cond", "$date$DOT$temp", date), dateStyle, innerW)
        } else date
        val amPm = units.amPm(now, locale)
        val timeText = timeFmt.format(now)
        val timeW = if (amPm != null) innerW * 0.82f else innerW
        val timeSize = simpleFit(simpleTemplate(timeText), simpleText(100f, weight = FontWeight.Light, figure = true), timeW, innerH - (dateSize * 1.3f + 6f).dp, 28f, 420f)
        Column(Modifier.padding(pad)) {
            Row {
                SimpleLine(timeText, simpleText(timeSize, DashColors.TextPrimary, FontWeight.Light, figure = true), Modifier.alignByBaseline(), overflow = TextOverflow.Clip)
                if (amPm != null) {
                    Spacer(Modifier.width((timeSize * 0.06f).dp))
                    SimpleLine(amPm, simpleText((timeSize * 0.22f).coerceAtLeast(14f), DashColors.Muted, FontWeight.Light), Modifier.alignByBaseline())
                }
            }
            Spacer(Modifier.height(4.dp))
            SimpleLine(line, dateStyle)
        }
    }
}

/**
 * Weather on a pane: the temperature large and thin with the condition's
 * glyph, the condition, a feels / wind line and, when rain is coming or
 * falling, when it starts or stops, in gold.
 */
@Composable
private fun SimpleWeather(env: SkinTileEnv) {
    val w = rememberWeather()
    val wait = rememberWeatherWait()
    if (w == null) {
        val title = when (wait) {
            WeatherWait.LOADING -> R.string.horizon_loading
            WeatherWait.NO_GPS -> R.string.info_waiting_gps
            WeatherWait.UNAVAILABLE -> R.string.info_weather_unavailable
        }
        SimpleEmpty(weatherIcon(3), stringResource(title), stringResource(R.string.horizon_weather_at_car), env.editing, null)
        return
    }
    val units = LocalUnits.current
    val timeFmt = rememberTimeFormat()
    val nowMs = rememberWallClock(60_000L).longValue
    val rain = when {
        w.rainFromMs != null && w.rainUntilMs != null && w.rainFromMs <= nowMs -> stringResource(R.string.canvas_rain_until, timeFmt.format(Date(w.rainUntilMs)))
        w.rainFromMs != null -> stringResource(R.string.canvas_rain_from, timeFmt.format(Date(w.rainFromMs)))
        else -> null
    }
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer().simpleGlass(editing = env.editing)) {
        val pad = padFor(maxWidth, maxHeight)
        val innerW = maxWidth - pad * 2
        val innerH = maxHeight - pad * 2
        val wide = maxWidth > maxHeight * 1.6f
        val lineSize = (min(innerW.value / (if (wide) 30f else 18f), innerH.value * 0.1f)).coerceIn(13f, 20f)
        val condSize = (lineSize * 1.3f).coerceIn(16f, 28f)
        val temp = "${units.temp(w.tempC)}°"
        val feels = stringResource(R.string.horizon_feels, units.temp(w.feelsC))
        val wind = stringResource(if (units.imperial) R.string.units_horizon_wind_mph else R.string.horizon_wind, units.speed(w.windKmh))
        val detailsW = if (wide) innerW * 0.5f else innerW
        val lineStyle = simpleText(lineSize, DashColors.TextSecondary)
        val line = simpleFirstFitting(listOf("$feels$DOT$wind", feels), lineStyle, detailsW)
        val details: @Composable () -> Unit = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(weatherIcon(w.code), contentDescription = null, tint = DashColors.Accent, modifier = Modifier.size((condSize * 1.15f).dp))
                    Spacer(Modifier.width(8.dp))
                    SimpleLine(w.condition, simpleText(condSize, DashColors.TextPrimary, FontWeight.Medium))
                }
                SimpleLine(line, lineStyle)
                if (rain != null && innerH >= 120.dp) SimpleLine(rain, simpleText(lineSize, DashColors.Accent, FontWeight.Medium))
            }
        }
        val tempStyle = simpleText(100f, weight = FontWeight.Light, figure = true)
        if (wide) {
            val tempSize = simpleFit(simpleTemplate(temp), tempStyle, innerW * 0.42f, innerH, 28f, 260f)
            Row(Modifier.fillMaxSize().padding(pad), verticalAlignment = Alignment.CenterVertically) {
                SimpleLine(temp, simpleText(tempSize, DashColors.TextPrimary, FontWeight.Light, figure = true), overflow = TextOverflow.Clip)
                Spacer(Modifier.width(18.dp))
                details()
            }
        } else {
            val below = (condSize * 1.4f + lineSize * 1.3f * (if (rain != null) 2 else 1) + 8f).dp
            val tempSize = simpleFit(simpleTemplate(temp), tempStyle, innerW * 0.8f, innerH - below, 28f, 260f)
            Column(Modifier.fillMaxSize().padding(pad), verticalArrangement = Arrangement.Center) {
                SimpleLine(temp, simpleText(tempSize, DashColors.TextPrimary, FontWeight.Light, figure = true), overflow = TextOverflow.Clip)
                details()
            }
        }
    }
}

// --- Apps ---------------------------------------------------------------------------

/**
 * App shortcut: a big glass button with the app's icon and its name, side by
 * side on a wide tile (as in the design's row of actions), stacked on a
 * square one.
 */
@Composable
private fun SimpleApp(item: DashboardItem.AppShortcut, env: SkinTileEnv) {
    val app = env.appsByPackage[item.packageName]
    val label = appLabel(app, item.packageName)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer()
            .simpleGlass(editing = env.editing)
            .simpleTap(!env.editing, stringResource(R.string.horizon_open_app, label)) { env.onLaunchApp(item.packageName) }
            .semantics(mergeDescendants = true) { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        val row = maxWidth >= maxHeight * 1.5f && maxWidth >= 180.dp
        if (row) {
            val icon = (maxHeight.value * 0.5f).coerceIn(30f, 72f).dp
            val labelSize = (icon.value * 0.4f).coerceIn(16f, 28f)
            Row(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                AppGlyph(app, icon)
                Spacer(Modifier.width(16.dp))
                SimpleLine(label, simpleText(labelSize, DashColors.TextPrimary, FontWeight.SemiBold), Modifier.weight(1f, fill = false))
            }
        } else {
            val icon = (min(maxWidth.value, maxHeight.value) * 0.4f).coerceIn(30f, 96f).dp
            val labelSize = (icon.value * 0.3f).coerceIn(13f, 24f)
            Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                AppGlyph(app, icon)
                Spacer(Modifier.height(8.dp))
                SimpleLine(label, simpleText(labelSize, DashColors.TextPrimary, FontWeight.SemiBold), align = TextAlign.Center)
            }
        }
    }
}

/** An app's own launcher icon, or the apps glyph while the apps load. */
@Composable
private fun AppGlyph(app: AppEntry?, size: Dp) {
    if (app != null) AppIcon(icon = app.icon, size = size)
    else Icon(Icons.Filled.Apps, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(size * 0.8f))
}

/**
 * Launch bar: the design's row of big glass buttons, one per app (icon and
 * name where there is room, the name under the icon on a tall bar), and a
 * glass pencil at the end. Apps that would get less than a 64 dp button are
 * left out.
 */
@Composable
private fun SimpleLaunchBar(item: DashboardItem.LaunchBar, env: SkinTileEnv) {
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer()) {
        val gap = 12.dp
        val edit = min(maxHeight.value, 64f).coerceAtLeast(48f).dp
        val room = maxWidth - edit - gap
        val tall = maxHeight >= 150.dp
        val fit = ((room + gap).value / (64f + gap.value)).toInt().coerceAtLeast(1)
        val pkgs = item.packages.take(fit)
        val per = if (pkgs.isEmpty()) 0f else (room.value - gap.value * (pkgs.size - 1)) / pkgs.size
        val labels = if (tall) per >= 90f else per >= 150f
        val icon = if (tall) (maxHeight.value * 0.34f).coerceIn(28f, 64f).dp else (maxHeight.value * 0.46f).coerceIn(24f, 48f).dp
        val labelSize = if (tall) (icon.value * 0.32f).coerceIn(13f, 20f) else (maxHeight.value * 0.2f).coerceIn(14f, 20f)
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
            if (pkgs.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxHeight().simpleGlass(editing = env.editing).padding(horizontal = 20.dp), contentAlignment = Alignment.CenterStart) {
                    SimpleLine(stringResource(R.string.skin_simple_launch_bar_empty), simpleText(labelSize, DashColors.TextSecondary), maxLines = 2)
                }
            } else {
                pkgs.forEach { pkg ->
                    LaunchButton(env.appsByPackage[pkg], pkg, icon, labels, tall, labelSize, env.editing, Modifier.weight(1f).fillMaxHeight()) { env.onLaunchApp(pkg) }
                }
            }
            val tap = rememberTapFeedback()
            val editLabel = stringResource(R.string.horizon_cd_edit_launch_bar)
            Box(
                modifier = Modifier
                    .size(edit)
                    .simpleGlass(20.dp)
                    .clickable(onClickLabel = editLabel, role = Role.Button) { tap(); env.onEditLaunchBar() }
                    .semantics { contentDescription = editLabel },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Edit, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun LaunchButton(
    app: AppEntry?,
    packageName: String,
    iconSize: Dp,
    label: Boolean,
    tall: Boolean,
    labelSize: Float,
    editing: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val name = appLabel(app, packageName)
    val base = modifier
        .widthIn(min = 64.dp)
        .simpleGlass(24.dp, editing = editing)
        .simpleTap(!editing, stringResource(R.string.horizon_open_app, name), onClick)
        .semantics(mergeDescendants = true) { contentDescription = name }
        .padding(horizontal = 10.dp)
    if (tall) {
        Column(base, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            AppGlyph(app, iconSize)
            if (label) {
                Spacer(Modifier.height(8.dp))
                SimpleLine(name, simpleText(labelSize, DashColors.TextPrimary, FontWeight.SemiBold), align = TextAlign.Center)
            }
        }
    } else {
        Row(base, horizontalArrangement = if (label) Arrangement.Start else Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if (label) Spacer(Modifier.width(8.dp))
            AppGlyph(app, iconSize)
            if (label) {
                Spacer(Modifier.width(12.dp))
                SimpleLine(name, simpleText(labelSize, DashColors.TextPrimary, FontWeight.SemiBold), Modifier.weight(1f, fill = false))
            }
        }
    }
}
