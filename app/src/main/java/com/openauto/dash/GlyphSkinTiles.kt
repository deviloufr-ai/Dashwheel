package com.openauto.dash

import com.openauto.dash.vehicle.rememberGauge
import com.openauto.dash.vehicle.rememberGaugeInt
import com.openauto.dash.vehicle.K
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * Glyph skin, the tiles (the look itself is in GlyphSkin.kt): each reading
 * on a module of the panel, its name in a mono label row, its value in the
 * dot font. The time with the outside temperature and the next sunset; the
 * car's readings in rows with dashed rules, the speed in red; the next turn
 * as a dot-matrix arrow and distance; the music with its title in dots and a
 * bar equaliser that only moves while parked; the fuel and range as the
 * driver's car in dots (GlyphSkinCar.kt) where there is the room.
 */

private const val DOT = " · "

/** Room inside a module: its sides, its top (the screws' row) and its foot. */
private val PAD_X = 22.dp
private val PAD_TOP = 18.dp
private val PAD_BOTTOM = 16.dp

/** The label row's height. */
private val LABEL_H = 20.dp

/**
 * Glyph renderer for app shortcuts, launch bars and the main widgets.
 * Anything else keeps its standard renderer.
 */
@Composable
internal fun GlyphTile(item: DashboardItem, env: SkinTileEnv) {
    when (item) {
        is DashboardItem.AppShortcut -> GlyphApp(item, env)
        is DashboardItem.LaunchBar -> GlyphLaunchBar(item, env)
        is DashboardItem.BuiltinWidget -> when (item.kind) {
            BuiltinKind.TELEMETRY -> GlyphTelemetry(env)
            BuiltinKind.SPEED_HUD -> GlyphSpeed(env)
            BuiltinKind.MEDIA -> GlyphMedia(env)
            BuiltinKind.NAVIGATION -> GlyphNav(env)
            BuiltinKind.CLOCK -> GlyphClock(env)
            BuiltinKind.WEATHER -> GlyphWeather(env)
            BuiltinKind.RANGE -> GlyphRange(item, env)
            else -> StandardSkinnedTile(item, env)
        }
        else -> StandardSkinnedTile(item, env)
    }
}

// --- Small pieces -----------------------------------------------------------------

/**
 * A module filling the tile, tappable as a whole when [onTap] is given (and
 * not arranging); [content] gets the room inside its padding.
 */
@Composable
private fun GlyphModuleBox(
    editing: Boolean,
    tapLabel: String? = null,
    onTap: (() -> Unit)? = null,
    content: @Composable BoxWithConstraintsScope.() -> Unit
) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .glyphModule(editing = editing)
            .glyphTap(onTap != null && !editing, tapLabel.orEmpty()) { onTap?.invoke() }
            .padding(start = PAD_X, end = PAD_X, top = PAD_TOP, bottom = PAD_BOTTOM),
        content = content
    )
}

/** The module's label row: its name on the left in tracked mono capitals, [right] on the right. */
@Composable
private fun GlyphLabel(left: String, right: String? = null, rightColor: Color = DashColors.Muted, dot: Color? = null, hollow: Boolean = false) {
    Row(Modifier.fillMaxWidth().height(LABEL_H), verticalAlignment = Alignment.CenterVertically) {
        GlyphLine(left, glyphMono(14f, DashColors.Muted, caps = true), Modifier.weight(1f))
        if (right != null) GlyphLine(right, glyphMono(14f, rightColor, caps = true), align = TextAlign.End)
        if (dot != null) {
            Spacer(Modifier.width(8.dp))
            Spacer(
                Modifier.size(8.dp).cachedDraw(dot, hollow) {
                    onDrawBehind {
                        if (hollow) drawCircle(dot, this.size.minDimension / 2f - density, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f * density))
                        else drawCircle(dot)
                    }
                }
            )
        }
    }
}

/** A quiet state in a module: its label, a mono title and one hint, the hint in ink when a tap does something. */
@Composable
private fun GlyphQuiet(label: String, title: String, hint: String, editing: Boolean, onTap: (() -> Unit)?) {
    GlyphModuleBox(editing, hint, onTap) {
        val boxW = maxWidth
        Column(Modifier.fillMaxSize()) {
            GlyphLabel(label)
            Spacer(Modifier.weight(1f))
            GlyphLine(title, glyphMono((boxW.value / 14f).coerceIn(16f, 24f), DashColors.TextPrimary, FontWeight.Bold), maxLines = 2)
            Spacer(Modifier.height(6.dp))
            GlyphLine(hint, glyphMono(15f, if (onTap != null) DashColors.TextPrimary else DashColors.TextSecondary), maxLines = 3)
            Spacer(Modifier.weight(1f))
        }
    }
}

/** A row of [count] dots as a gauge: [lit] of them in ink, those under [redUnder] in red, the rest unlit. */
@Composable
private fun GlyphDotGauge(count: Int, lit: Int, redUnder: Int, modifier: Modifier) {
    val ink = DashColors.TextPrimary
    val red = glyphRed
    val off = glyphOff
    Spacer(
        modifier.cachedDraw(count, lit, redUnder, ink, red, off) {
            val pitch = size.width / count
            val d = min(pitch * 0.72f, size.height)
            val y = size.height / 2f
            val offPts = ArrayList<Float>()
            val inkPts = ArrayList<Float>()
            val redPts = ArrayList<Float>()
            for (i in 0 until count) {
                val x = pitch * (i + 0.5f)
                val list = when {
                    i >= lit -> offPts
                    i < redUnder -> redPts
                    else -> inkPts
                }
                list.add(x); list.add(y)
            }
            val a = offPts.toFloatArray()
            val b = inkPts.toFloatArray()
            val c = redPts.toFloatArray()
            val pOff = glyphDotPaint(off, d)
            val pInk = glyphDotPaint(ink, d)
            val pRed = glyphDotPaint(red, d)
            onDrawBehind {
                drawGlyphPoints(a, pOff)
                drawGlyphPoints(b, pInk)
                drawGlyphPoints(c, pRed)
            }
        }
    )
}

// --- Clock ------------------------------------------------------------------------

/**
 * TIME and the day in the label row, the time in big dots, and under it the
 * outside temperature and the next sunset (the sunrise once it has set).
 * Tap opens the alarms.
 */
@Composable
private fun GlyphClock(env: SkinTileEnv) {
    val now = rememberNow(60_000L)
    val units = LocalUnits.current
    val locale = Locale.getDefault()
    val timeFmt = rememberDateFormat(units.digitsPattern())
    val dayFmt = rememberDateFormat("EEEd", best = true)
    val hmFmt = rememberTimeFormat()
    val context = env.context
    AutoLight.start(context)
    val place by AutoLight.place.collectAsState()
    val temp = rememberOutsideTempC()
    val tenMinutes = now.time / 600_000L
    val sun = remember(tenMinutes, place) { place?.let { glyphSunEvent(now.time, it.first, it.second) } }
    val sunsetWord = stringResource(R.string.glyph_sunset, "%s")
    val sunriseWord = stringResource(R.string.glyph_sunrise, "%s")
    val sub = remember(temp, sun, units, sunsetWord, sunriseWord, hmFmt) {
        val parts = ArrayList<String>(2)
        if (temp != null) parts += units.tempText(temp)
        if (sun != null) {
            val cal = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, sun.first / 60); set(java.util.Calendar.MINUTE, sun.first % 60)
            }
            parts += (if (sun.second) sunsetWord else sunriseWord).replace("%s", hmFmt.format(cal.time))
        }
        parts.joinToString(DOT)
    }
    GlyphModuleBox(env.editing, stringResource(R.string.horizon_open_clock), { openClockApp(context) }) {
        val timeText = timeFmt.format(now)
        val amPm = units.amPm(now, locale)
        val subH = if (sub.isNotEmpty()) 26.dp else 0.dp
        val pitch = glyphPitch(timeText, if (amPm != null) maxWidth * 0.8f else maxWidth, maxHeight - LABEL_H - subH - 16.dp, max = 15f)
        Column(Modifier.fillMaxSize()) {
            GlyphLabel(stringResource(R.string.glyph_label_time), dayFmt.format(now).uppercase(locale).replace(".", ""))
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.Bottom) {
                GlyphDotText(timeText, pitch, DashColors.TextPrimary)
                if (amPm != null) {
                    Spacer(Modifier.width(pitch * 1.5f))
                    GlyphLine(amPm, glyphMono(16f, DashColors.TextSecondary, FontWeight.Medium))
                }
            }
            Spacer(Modifier.weight(1f))
            if (sub.isNotEmpty()) GlyphLine(sub, glyphMono(16f, DashColors.TextSecondary))
        }
    }
}

// --- Telemetry --------------------------------------------------------------------

/**
 * The car's readings as in the design: CAR and the OBD link's light in the
 * label row, then rows with dashed rules between them, a name on the left
 * and its value in dots on the right: the range, the coolant and the speed,
 * the speed in red. Without the link a tap connects; after an error a key
 * offers to choose the adapter.
 */
@Composable
private fun GlyphTelemetry(env: SkinTileEnv) {
    val state = env.obdConnection
    val connected = state == ObdConnectionState.CONNECTED
    val units = LocalUnits.current
    val fuel = rememberFuel()
    val speed = rememberSpeedKmh()
    val coolant = rememberGaugeInt(K.COOLANT_TEMP) ?: 0
    val hot = coolant >= COOLANT_WARNING_C
    val rangeLabel = stringResource(R.string.glyph_label_range)
    val coolantLabel = stringResource(R.string.glyph_label_coolant, units.tempUnit)
    val speedLabel = stringResource(R.string.glyph_label_speed, units.speedUnit)
    val connectLabel = stringResource(R.string.horizon_connect_obd)
    GlyphModuleBox(env.editing, connectLabel, if (state.isIdle) env.onConnectObd else null) {
        val showKey = state == ObdConnectionState.ERROR && maxHeight >= 250.dp
        val keyH = if (showKey) 60.dp else 0.dp
        val rows = buildList {
            add(Triple(speedLabel, speed?.let { units.speed(it).toString() } ?: "--", glyphRed))
            add(Triple(rangeLabel, fuel?.let { units.distance(it.rangeKm).toString() } ?: "--", if (fuel != null && fuel.percent <= SKIN_LOW_FUEL_PCT) glyphRed else DashColors.TextPrimary))
            add(Triple(coolantLabel, if (connected && coolant > 0) units.temp(coolant).toString() else "--", if (hot) DashColors.Warning else DashColors.TextPrimary))
        }
        val roomRows = ((maxHeight - LABEL_H - keyH) / 54.dp).toInt().coerceIn(1, 3)
        // The design's order, range first, when all three fit; the speed first when only some do.
        val shown = if (roomRows >= 3) listOf(rows[1], rows[2], rows[0]) else rows.take(roomRows)
        val rowH = (maxHeight - LABEL_H - keyH - 6.dp) / shown.size
        val pitch = (rowH.value * 0.56f / 7f).coerceIn(2.5f, 7f).dp
        val rule = DashColors.Line
        Column(Modifier.fillMaxSize()) {
            GlyphLabel(
                stringResource(R.string.glyph_label_car),
                if (state == ObdConnectionState.CONNECTING) stringResource(R.string.horizon_obd_connecting) else stringResource(R.string.dash_obd_short),
                dot = obdStatusColor(state).takeIf { state != ObdConnectionState.CONNECTED } ?: DashColors.TextPrimary,
                hollow = state == ObdConnectionState.DISCONNECTED
            )
            Spacer(Modifier.height(6.dp))
            shown.forEachIndexed { i, (label, value, color) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(rowH)
                        .then(
                            if (i < shown.size - 1) Modifier.cachedDraw(rule) {
                                val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
                                onDrawBehind { drawLine(rule, Offset(0f, size.height - density), Offset(size.width, size.height - density), density, pathEffect = dash) }
                            } else Modifier
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlyphLine(label, glyphMono(14f, DashColors.TextSecondary, caps = true), Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    GlyphDotText(value, pitch, color)
                }
            }
            if (showKey) {
                Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    GlyphMonoKey(stringResource(R.string.tape_cd_choose_adapter), !env.editing, env.onPickDevice)
                }
            }
        }
    }
}

/** A flat mono key in the module (choose the adapter): 56 dp tall, the word in tracked capitals on a pale plate. */
@Composable
private fun GlyphMonoKey(text: String, enabled: Boolean, onClick: () -> Unit) {
    val light = DashColors.Light
    Box(
        Modifier
            .height(56.dp)
            .cachedDraw(light) {
                val fill = if (light) Color(0xFFE6E6E1) else Color(0xFF242424)
                val rim = if (light) Color(0xFFC8C8C0) else Color(0xFF3A3A3A)
                onDrawBehind {
                    drawRoundRect(fill, cornerRadius = CornerRadius(size.height / 2f))
                    drawRoundRect(rim, cornerRadius = CornerRadius(size.height / 2f), style = androidx.compose.ui.graphics.drawscope.Stroke(density))
                }
            }
            .glyphTap(enabled, text, onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        GlyphLine(text, glyphMono(14f, DashColors.TextPrimary, FontWeight.Medium, caps = true))
    }
}

// --- Speed ------------------------------------------------------------------------

/** SPEED and the unit in the label row, the speed in big red dots, where it comes from under it. */
@Composable
private fun GlyphSpeed(env: SkinTileEnv) {
    val units = LocalUnits.current
    val speed = rememberSpeedKmh()
    val connected = env.obdConnection == ObdConnectionState.CONNECTED
    GlyphModuleBox(env.editing) {
        val text = speed?.let { units.speed(it).toString() } ?: "--"
        val pitch = glyphPitch("888", maxWidth, maxHeight - LABEL_H - 30.dp, max = 18f)
        Column(Modifier.fillMaxSize()) {
            GlyphLabel(stringResource(R.string.glyph_label_speed_short), units.speedUnit)
            Spacer(Modifier.weight(1f))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                GlyphDotText(text, pitch, if (speed != null) glyphRed else glyphOff)
            }
            Spacer(Modifier.weight(1f))
            GlyphLine(speedSource(connected, speed, stringResource(R.string.glyph_no_speed)), glyphMono(14f, DashColors.Muted, caps = true), Modifier.fillMaxWidth(), align = TextAlign.Center)
        }
    }
}

// --- Navigation -------------------------------------------------------------------

/** The design's arrow to the left, 11 x 8 dots; the right one is its mirror. */
private val ARROW_LEFT = arrayOf(
    "    #      ",
    "   ##      ",
    "  #########",
    " ##########",
    "  #########",
    "   ##    ##",
    "    #    ##",
    "         ##"
)
private val ARROW_AHEAD = arrayOf(
    "     #     ",
    "    ###    ",
    "   #####   ",
    "  #######  ",
    "    ###    ",
    "    ###    ",
    "    ###    ",
    "    ###    "
)

/** The lit cells of an arrow pattern (rows of '#'), mirrored when [mirror]. */
private fun patternCells(rows: Array<String>, mirror: Boolean): Array<BooleanArray> =
    Array(rows.size) { y -> val r = rows[y]; BooleanArray(r.length) { x -> r[if (mirror) r.length - 1 - x else x] == '#' } }

/** The navigation app's own arrow (a white glyph on transparent) as 11 x 11 dots; null when too little of it shows. */
internal fun iconCells(icon: Bitmap?): Array<BooleanArray>? {
    icon ?: return null
    return runCatching {
        val n = 11
        val soft = if (icon.config == Bitmap.Config.HARDWARE) icon.copy(Bitmap.Config.ARGB_8888, false) else icon
        val small = Bitmap.createScaledBitmap(soft, n, n, true)
        val px = IntArray(n * n)
        small.getPixels(px, 0, n, 0, 0, n, n)
        val cells = Array(n) { y -> BooleanArray(n) { x -> (px[y * n + x] ushr 24) > 110 } }
        cells.takeIf { c -> c.sumOf { r -> r.count { it } } >= 6 }
    }.getOrNull()
}

/** The turn as dots: the lit ones in ink, the unlit ones pale, as on the design's matrix. */
@Composable
private fun GlyphArrow(cells: Array<BooleanArray>, height: Dp, description: String, lit: Color = DashColors.TextPrimary) {
    val off = glyphOff
    val rows = cells.size
    val cols = cells.firstOrNull()?.size ?: 0
    val pitch = height / rows
    Spacer(
        Modifier
            .size(pitch * cols, height)
            .semantics { contentDescription = description }
            .cachedDraw(cells, lit, off, pitch) {
                val p = pitch.toPx()
                val on = ArrayList<Float>()
                val dim = ArrayList<Float>()
                for (y in 0 until rows) for (x in 0 until cols) {
                    val list = if (cells[y][x]) on else dim
                    list.add((x + 0.5f) * p); list.add((y + 0.5f) * p)
                }
                val a = on.toFloatArray()
                val b = dim.toFloatArray()
                val pOn = glyphDotPaint(lit, p * GLYPH_DOT)
                val pOff = glyphDotPaint(off, p * GLYPH_DOT)
                onDrawBehind {
                    drawGlyphPoints(b, pOff)
                    drawGlyphPoints(a, pOn)
                }
            }
    )
}

private val CLOCK_PART = Regex("""^\d{1,2}[:h.]\d{2}(\s?[AaPp]\.?[Mm]\.?)?$""")

/**
 * The next turn as in the design: NAV and the arrival in the label row, the
 * turn as a matrix of dots (the navigation's own arrow when it sends one),
 * the distance in big dots and the street in mono capitals. A wide low tile
 * puts the arrow beside the words. Tap opens the navigation app.
 */
@Composable
private fun GlyphNav(env: SkinTileEnv) {
    val nav by NavDirections.state.collectAsState()
    val context = env.context
    val canTap = !env.editing
    val navLabel = stringResource(R.string.glyph_label_nav)
    when {
        !env.hasMediaAccess -> GlyphQuiet(
            navLabel, stringResource(R.string.info_directions_access_title), stringResource(R.string.horizon_tap_allow_notification_access), env.editing,
            if (canTap) ({ CarMediaController.openNotificationAccessSettings(context) }) else null
        )
        !nav.active -> GlyphQuiet(
            navLabel, stringResource(R.string.info_directions_no_route), stringResource(R.string.horizon_start_navigation), env.editing,
            if (canTap) ({ openNavigationApp(context, nav) }) else null
        )
        else -> {
            val arrivalWord = stringResource(R.string.glyph_arrival, "%s")
            val right = remember(nav.eta, arrivalWord) {
                val parts = nav.etaParts
                parts.firstOrNull { CLOCK_PART.matches(it) }?.let { arrivalWord.replace("%s", it) } ?: parts.firstOrNull()
            }
            val side = nav.turnSide()
            val fromIcon = remember(nav.icon) { iconCells(nav.icon) }
            val pattern = remember(side) {
                if (side == TurnSide.AHEAD) patternCells(ARROW_AHEAD, false) else patternCells(ARROW_LEFT, side == TurnSide.RIGHT)
            }
            val cells = fromIcon ?: pattern
            val (value, unit) = nav.distanceParts
            val distance = if (unit.isNotEmpty() && GlyphFont.writes(unit)) value + unit else value
            val monoUnit = if (unit.isNotEmpty() && !GlyphFont.writes(unit)) unit else null
            GlyphModuleBox(env.editing, stringResource(R.string.horizon_open_navigation), if (canTap) ({ openNavigationApp(context, nav) }) else null) {
                val wide = maxWidth > maxHeight * 1.6f
                val streetSize = (maxWidth.value / 16f).coerceIn(15f, 19f)
                val streetLines = if (maxHeight >= 240.dp) 2 else 1
                val streetH = (streetSize * 1.3f * streetLines).dp
                val boxW = maxWidth
                val boxH = maxHeight
                Column(Modifier.fillMaxSize()) {
                    GlyphLabel(navLabel, right)
                    if (wide) {
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            val arrowH = (boxH - LABEL_H - 16.dp).coerceIn(32.dp, 110.dp)
                            GlyphArrow(cells, arrowH, nav.instruction)
                            Spacer(Modifier.width(18.dp))
                            Column(Modifier.weight(1f)) {
                                if (distance.isNotEmpty()) GlyphDistance(distance, monoUnit, glyphPitch(distance, boxW * 0.45f, (boxH - LABEL_H - streetH - 24.dp), max = 10f))
                                Spacer(Modifier.height(6.dp))
                                GlyphLine(nav.instruction, glyphMono(streetSize, DashColors.TextPrimary), maxLines = streetLines)
                            }
                        }
                    } else {
                        val roomH = boxH - LABEL_H - streetH - 30.dp
                        val arrowH = (roomH * 0.42f).coerceIn(28.dp, 96.dp)
                        val distPitch = glyphPitch(distance.ifEmpty { "8" }, if (monoUnit != null) boxW * 0.75f else boxW, roomH - arrowH - 10.dp, max = 12f)
                        Spacer(Modifier.height(10.dp))
                        GlyphArrow(cells, arrowH, nav.instruction)
                        Spacer(Modifier.weight(1f))
                        if (distance.isNotEmpty()) GlyphDistance(distance, monoUnit, distPitch)
                        Spacer(Modifier.height(10.dp))
                        GlyphLine(nav.instruction, glyphMono(streetSize, DashColors.TextPrimary), maxLines = streetLines)
                    }
                }
            }
        }
    }
}

/** The distance in dots, with its unit in mono beside it when the dot font cannot write it. */
@Composable
private fun GlyphDistance(text: String, monoUnit: String?, pitch: Dp) {
    Row(verticalAlignment = Alignment.Bottom) {
        GlyphDotText(text, pitch, DashColors.TextPrimary)
        if (monoUnit != null) {
            Spacer(Modifier.width(pitch * 1.5f))
            GlyphLine(monoUnit, glyphMono((pitch.value * 3.4f).coerceAtLeast(15f), DashColors.TextSecondary, FontWeight.Medium))
        }
    }
}

// --- Media ------------------------------------------------------------------------

/** Bars in the equaliser. */
private const val EQ_BARS = 18

/** One minute of the equaliser's motion; each bar turns a whole number of times in it, so it loops without a jump. */
private const val EQ_LOOP_MS = 60_000

/** Bar [i]'s height (0..1) at [t] (0..1 of [EQ_LOOP_MS]): two waves of its own, low while nothing plays. */
internal fun glyphEqLevel(i: Int, t: Float, playing: Boolean): Float {
    if (!playing) return 0.12f + 0.04f * ((i * 7) % 5) / 4f
    val a = 2.0 * PI * ((37 + (i * 13) % 41) * t + i * 0.37)
    val b = 2.0 * PI * ((53 + (i * 29) % 37) * t + i * 0.71)
    val v = 0.5 + 0.3 * sin(a) + 0.2 * sin(b)
    return (0.1 + 0.9 * v).toFloat().coerceIn(0.08f, 1f)
}

/**
 * The music as in the design: AUDIO and the player's name in the label row,
 * the title in dots, who sings it and the time in mono, the bar equaliser,
 * and the three round keys where there is the room (else a tap plays or
 * pauses). Without media access, a quiet module says so.
 */
@Composable
private fun GlyphMedia(env: SkinTileEnv) {
    val ms = env.mediaState
    val context = env.context
    if (!env.hasMediaAccess) {
        GlyphQuiet(
            stringResource(R.string.glyph_label_audio), stringResource(R.string.info_media_access_needed), stringResource(R.string.horizon_tap_allow_notification_access), env.editing,
            if (!env.editing) ({ CarMediaController.openNotificationAccessSettings(context) }) else null
        )
        return
    }
    val hasTrack = ms.hasMedia && ms.title.isNotBlank()
    val title = if (hasTrack) ms.title else stringResource(R.string.info_nothing_playing)
    val artist = if (hasTrack) ms.artist.ifBlank { stringResource(R.string.horizon_unknown_artist) } else stringResource(R.string.horizon_play_something)
    val player = remember(ms.title, env.appsByPackage) {
        CarMediaController.getLastMediaPackage(context)?.let { env.appsByPackage[it]?.label }
    }
    val playLabel = stringResource(if (ms.isPlaying) R.string.horizon_cd_pause else R.string.horizon_cd_play)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val keys = maxHeight >= 250.dp && maxWidth >= 250.dp
        GlyphModuleBox(env.editing, playLabel, if (!keys && hasTrack) ({ env.mediaController.playPause() }) else null) {
            val keyH = if (keys) 64.dp else 0.dp
            val subH = 24.dp
            val eqRoom = maxHeight - LABEL_H - subH - keyH - 14.dp
            // As large as the room and the width allow, down to a floor where a long title is cut instead.
            val titlePitch = min(min(eqRoom.value * 0.55f / 7f, 6.5f), maxWidth.value / glyphColumns(title).coerceAtLeast(1)).coerceAtLeast(3.6f).dp
            val maxCols = (maxWidth / titlePitch).toInt()
            Column(Modifier.fillMaxSize()) {
                GlyphLabel(stringResource(R.string.glyph_label_audio), player)
                Spacer(Modifier.height(10.dp))
                GlyphDotText(title, titlePitch, if (hasTrack) DashColors.TextPrimary else DashColors.Muted, maxColumns = maxCols)
                Spacer(Modifier.height(6.dp))
                GlyphMediaLine(ms, env.mediaController, artist, hasTrack)
                GlyphEq(ms.isPlaying && hasTrack, Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp))
                if (keys) {
                    Row(Modifier.fillMaxWidth().height(keyH), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
                        val c = env.mediaController
                        val enabled = !env.editing
                        GlyphKey(null, Icons.Filled.SkipPrevious, stringResource(R.string.horizon_cd_previous_track), 56.dp, onClick = if (enabled) ({ c.previous() }) else null)
                        GlyphKey(null, if (ms.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, playLabel, 56.dp, red = false, onClick = if (enabled) ({ c.playPause() }) else null)
                        GlyphKey(null, Icons.Filled.SkipNext, stringResource(R.string.horizon_cd_next_track), 56.dp, onClick = if (enabled) ({ c.next() }) else null)
                    }
                }
            }
        }
    }
}

/** Who sings it and the track's time; in its own scope, so only it follows the position each second. */
@Composable
private fun GlyphMediaLine(ms: MediaState, controller: CarMediaController, artist: String, hasTrack: Boolean) {
    val time = if (hasTrack && ms.durationMs > 0L) {
        val position = rememberMediaPosition(ms, controller)
        "${formatTrackTime(position)} / ${formatTrackTime(ms.durationMs)}"
    } else null
    GlyphLine(if (time != null) artist + DOT + time else artist, glyphMono(15f, DashColors.TextSecondary))
}

/**
 * The bar equaliser: [EQ_BARS] bars in ink rising and falling while music
 * plays and the car is parked; while driving it holds the last levels, and
 * with effects off it stands still. Its own layer, so its steps redraw it alone.
 */
@Composable
private fun GlyphEq(playing: Boolean, modifier: Modifier) {
    val ink = DashColors.TextPrimary
    // Only a playing equaliser has a clock; a paused one is drawn once.
    val t = if (playing) rememberLoop(EQ_LOOP_MS) else null
    Spacer(
        modifier
            .graphicsLayer()
            .cachedDraw(ink, playing, t) {
                val gap = 5.dp.toPx()
                val barW = min(10.dp.toPx(), (size.width - gap * (EQ_BARS - 1)) / EQ_BARS)
                val maxH = min(size.height, 40.dp.toPx())
                val r = CornerRadius(2.dp.toPx())
                onDrawBehind {
                    val now = t?.value ?: 0f
                    for (i in 0 until EQ_BARS) {
                        val h = (maxH * glyphEqLevel(i, now, playing)).coerceAtLeast(2.dp.toPx())
                        drawRoundRect(ink, Offset(i * (barW + gap), size.height - h), Size(barW, h), r)
                    }
                }
            }
    )
}

// --- Fuel and range -----------------------------------------------------------------

/**
 * Fuel and range: with the room, the driver's car as a cloud of dots
 * (GlyphSkinCar.kt), the hero of the page. Otherwise a module: FUEL and the
 * level, the range in dots, a gauge of dots under it with the reserve in red.
 * Fuel not learned yet, on a small tile, keeps the standard tile (in a
 * module), which explains how to learn it.
 */
@Composable
private fun GlyphRange(item: DashboardItem, env: SkinTileEnv) {
    val fuel = rememberFuel()
    val units = LocalUnits.current
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer()) {
        val hero = maxWidth >= GLYPH_HERO_MIN_W && maxHeight >= GLYPH_HERO_MIN_H
        when {
            hero -> GlyphCarHero(env, fuel, Modifier.fillMaxSize().padding(GLYPH_INSET))
            fuel == null -> Box(Modifier.fillMaxSize().glyphModule(editing = env.editing)) { StandardSkinnedTile(item, env) }
            else -> GlyphModuleBox(env.editing) {
                val low = fuel.percent <= SKIN_LOW_FUEL_PCT
                val gaugeH = 14.dp
                val text = units.distance(fuel.rangeKm).toString()
                val pitch = glyphPitch(text, maxWidth * 0.7f, maxHeight - LABEL_H - gaugeH - 28.dp, max = 12f)
                Column(Modifier.fillMaxSize()) {
                    GlyphLabel(stringResource(R.string.glyph_label_fuel), "${fuel.percent} %", if (low) glyphRed else DashColors.Muted)
                    Spacer(Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.Bottom) {
                        GlyphDotText(text, pitch, if (low) glyphRed else DashColors.TextPrimary)
                        Spacer(Modifier.width(pitch * 1.5f))
                        GlyphLine(units.distanceUnit, glyphMono((pitch.value * 3f).coerceIn(15f, 22f), DashColors.TextSecondary, FontWeight.Medium))
                    }
                    Spacer(Modifier.weight(1f))
                    GlyphDotGauge(20, glyphFuelSegments(fuel.percent), glyphFuelSegments(SKIN_LOW_FUEL_PCT), Modifier.fillMaxWidth().height(gaugeH))
                }
            }
        }
    }
}

// --- Weather ------------------------------------------------------------------------

/**
 * WEATHER and the day's high and low in the label row, the temperature in
 * dots, the sky in words and rain to come under it; while there is nothing
 * yet, why (no GPS, offline).
 */
@Composable
private fun GlyphWeather(env: SkinTileEnv) {
    val w = rememberWeather()
    val wait = rememberWeatherWait()
    val units = LocalUnits.current
    val timeFmt = rememberTimeFormat()
    val nowMs = rememberWallClock(60_000L).longValue
    GlyphModuleBox(env.editing) {
        val label = stringResource(R.string.glyph_label_weather)
        if (w == null) {
            val title = when (wait) {
                WeatherWait.LOADING -> R.string.horizon_loading
                WeatherWait.NO_GPS -> R.string.info_waiting_gps
                WeatherWait.UNAVAILABLE -> R.string.info_weather_unavailable
            }
            val wait = glyphPitch("--°", maxWidth, maxHeight * 0.4f, max = 12f)
            Column(Modifier.fillMaxSize()) {
                GlyphLabel(label)
                Spacer(Modifier.weight(1f))
                GlyphDotText("--°", wait, glyphOff)
                Spacer(Modifier.height(8.dp))
                GlyphLine(stringResource(title), glyphMono(15f, DashColors.TextSecondary), maxLines = 2)
                Spacer(Modifier.weight(1f))
            }
            return@GlyphModuleBox
        }
        val rain = when {
            w.rainFromMs != null && w.rainUntilMs != null && w.rainFromMs <= nowMs -> stringResource(R.string.canvas_rain_until, timeFmt.format(Date(w.rainUntilMs)))
            w.rainFromMs != null -> stringResource(R.string.canvas_rain_from, timeFmt.format(Date(w.rainFromMs)))
            else -> null
        }
        val temp = "${units.temp(w.tempC)}°"
        val lines = if (rain != null) 2 else 1
        val pitch = glyphPitch(temp, maxWidth * 0.8f, maxHeight - LABEL_H - (lines * 22).dp - 24.dp, max = 14f)
        Column(Modifier.fillMaxSize()) {
            GlyphLabel(label, stringResource(R.string.glyph_high_low, units.temp(w.hiC), units.temp(w.loC)))
            Spacer(Modifier.weight(1f))
            GlyphDotText(temp, pitch, DashColors.TextPrimary)
            Spacer(Modifier.weight(1f))
            GlyphLine(w.condition, glyphMono(16f, DashColors.TextSecondary))
            if (rain != null) GlyphLine(rain, glyphMono(16f, DashColors.TextPrimary, FontWeight.Medium))
        }
    }
}

// --- Apps ---------------------------------------------------------------------------

/** App shortcut: a module with the app's icon on a round key and its name in mono under it (beside it on a wide tile). */
@Composable
private fun GlyphApp(item: DashboardItem.AppShortcut, env: SkinTileEnv) {
    val app = env.appsByPackage[item.packageName]
    val label = appLabel(app, item.packageName)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .glyphModule(editing = env.editing)
            .glyphTap(!env.editing, stringResource(R.string.horizon_open_app, label)) { env.onLaunchApp(item.packageName) }
            .semantics(mergeDescendants = true) { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        val row = maxWidth >= maxHeight * 1.5f && maxWidth >= 180.dp
        val icon = (min(maxWidth.value, maxHeight.value) * (if (row) 0.5f else 0.42f)).coerceIn(28f, 80f).dp
        if (row) {
            Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                GlyphAppGlyph(app, icon)
                Spacer(Modifier.width(14.dp))
                GlyphLine(label, glyphMono((icon.value * 0.3f).coerceIn(14f, 20f), DashColors.TextPrimary, FontWeight.Medium, caps = true), Modifier.weight(1f, fill = false))
            }
        } else {
            val named = maxHeight >= 100.dp
            Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                GlyphAppGlyph(app, icon)
                if (named) {
                    Spacer(Modifier.height(8.dp))
                    GlyphLine(label, glyphMono(14f, DashColors.TextPrimary, FontWeight.Medium, caps = true), align = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun GlyphAppGlyph(app: AppEntry?, size: Dp) {
    if (app != null) AppIcon(icon = app.icon, size = size)
    else Icon(Icons.Filled.Apps, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(size * 0.8f))
}

/**
 * Launch bar: a row of the panel's round keys, each with an app's icon, and
 * a key with the pencil at the end. Apps that would get less than a 56 dp
 * key are left out.
 */
@Composable
private fun GlyphLaunchBar(item: DashboardItem.LaunchBar, env: SkinTileEnv) {
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer()) {
        val key = (maxHeight.value - 12f).coerceIn(48f, 72f).dp
        val gap = 12.dp
        val fit = ((maxWidth + gap) / (key + gap)).toInt().coerceAtLeast(1) - 1
        val pkgs = item.packages.take(fit.coerceAtLeast(0))
        Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
            if (pkgs.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxHeight().glyphModule(editing = env.editing, screws = false).padding(horizontal = 20.dp), contentAlignment = Alignment.CenterStart) {
                    GlyphLine(stringResource(R.string.glyph_launch_bar_empty), glyphMono(15f, DashColors.TextSecondary), maxLines = 2)
                }
            } else {
                pkgs.forEach { pkg ->
                    val app = env.appsByPackage[pkg]
                    val name = appLabel(app, pkg)
                    GlyphKey(null, null, stringResource(R.string.horizon_open_app, name), key, onClick = if (!env.editing) ({ env.onLaunchApp(pkg) }) else null) {
                        GlyphAppGlyph(app, key * 0.52f)
                    }
                }
                Spacer(Modifier.weight(1f))
            }
            GlyphKey(null, Icons.Filled.Edit, stringResource(R.string.horizon_cd_edit_launch_bar), key, onClick = env.onEditLaunchBar)
        }
    }
}
