package com.openauto.dash

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openauto.dash.BuiltinKind.CLOCK
import com.openauto.dash.BuiltinKind.MEDIA
import com.openauto.dash.BuiltinKind.NAVIGATION
import com.openauto.dash.BuiltinKind.SPEED_HUD
import com.openauto.dash.BuiltinKind.WEATHER
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.min

/*
 * Paper skin, the tiles (the look is in PaperSkin.kt, the junction diagram in
 * PaperSkinMap.kt). Ruled sections of plain facts: the time huge and bold,
 * the speed as a large figure beside its boxed limit, the rain as six
 * hatched hours, the song as one plain line with words for buttons, the car
 * as ruled rows of typewriter values. The next turn is a diagram of its one
 * junction. A tile refreshes like e-ink when its main value changes enough
 * to be worth a look (paperRefresh).
 */

private const val DOT = " · "

/** Room around a section's content, following the tile's smaller side. */
private fun padFor(w: Dp, h: Dp): Dp = (min(w.value, h.value) * 0.07f).coerceIn(10f, 22f).dp

/**
 * The Home Paper was drawn around (SkinLayouts.kt): the next turn large on
 * the left, full height; on the right the time across the top, the speed
 * and the rain side by side under it, the music at the foot.
 *
 * ```
 *   0 1 2 3 4 5 | 6 7 8 | 9 10 11
 * 0 N N N N N N | C C C   C  C  C
 * 1 N N N N N N | C C C   C  C  C
 * 2 N N N N N N | C C C   C  C  C
 * 3 N N N N N N | S S S | W  W  W
 * 4 N N N N N N | S S S | W  W  W
 * 5 N N N N N N | M M M   M  M  M
 * 6 N N N N N N | M M M   M  M  M
 * ```
 */
internal val PaperLayout = SkinLayout(
    home = TemplatePage(listOf(NAVIGATION, CLOCK, WEATHER, MEDIA, SPEED_HUD)),
    wide = listOf(
        DashboardItem.BuiltinWidget(NAVIGATION, x = 0, y = 0, w = 6, h = 7),
        DashboardItem.BuiltinWidget(CLOCK, x = 6, y = 0, w = 6, h = 3),
        DashboardItem.BuiltinWidget(SPEED_HUD, x = 6, y = 3, w = 3, h = 2),
        DashboardItem.BuiltinWidget(WEATHER, x = 9, y = 3, w = 3, h = 2),
        DashboardItem.BuiltinWidget(MEDIA, x = 6, y = 5, w = 6, h = 2)
    )
)

/** Paper's renderer for app shortcuts, launch bars and the main widgets; anything else keeps its standard one. */
@Composable
internal fun PaperTile(item: DashboardItem, env: SkinTileEnv) {
    when (item) {
        is DashboardItem.AppShortcut -> PaperApp(item, env)
        is DashboardItem.LaunchBar -> PaperLaunchBar(item, env)
        is DashboardItem.BuiltinWidget -> when (item.kind) {
            BuiltinKind.NAVIGATION -> PaperNavigation(env)
            BuiltinKind.CLOCK -> PaperClock(env)
            BuiltinKind.SPEED_HUD -> PaperSpeed(env)
            BuiltinKind.WEATHER -> PaperWeather(env)
            BuiltinKind.MEDIA -> PaperMedia(env)
            BuiltinKind.TELEMETRY -> PaperTelemetry(env)
            BuiltinKind.RANGE -> PaperRange(item, env)
            else -> StandardSkinnedTile(item, env)
        }
        else -> StandardSkinnedTile(item, env)
    }
}

// --- Small pieces -----------------------------------------------------------------

/** Largest size (dp, for [paperText]) within [minSize]..[maxSize] at which [sample] fits [maxW] x [maxH] on one line. */
@Composable
private fun paperFit(sample: String, mono: Boolean, weight: FontWeight, maxW: Dp, maxH: Dp, minSize: Float, maxSize: Float, hero: Boolean = false): Float {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = paperText(100f, weight = weight, mono = mono, hero = hero)
    return remember(sample, style, maxW, maxH, minSize, maxSize, density) {
        val probe = measurer.measure(sample, style.copy(fontSize = (100f / density.fontScale).sp), softWrap = false, maxLines = 1)
        val byW = with(density) { maxW.toPx() } * 100f / probe.size.width.coerceAtLeast(1)
        val byH = with(density) { maxH.toPx() } * 100f / probe.size.height.coerceAtLeast(1)
        min(byW, byH).coerceIn(minSize, maxSize.coerceAtLeast(minSize))
    }
}

/** The first of [candidates] (longest first) that fits [maxW] on one line at [size], else the last. */
@Composable
private fun firstFitting(candidates: List<String>, size: Float, maxW: Dp): String {
    val measurer = rememberTextMeasurer()
    val style = paperText(size)
    val px = with(LocalDensity.current) { maxW.toPx() }
    return remember(candidates, style, px, measurer) {
        candidates.firstOrNull { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width <= px } ?: candidates.last()
    }
}

/** Digits replaced by '8' so a readout keeps one size while its value changes. */
private fun template(text: String): String = text.map { if (it.isDigit()) '8' else it }.joinToString("")

/** A ruled row: the name on the left, the value in the typewriter face on the right ("Speed ... 48 km/h"). */
@Composable
private fun PaperRow(label: String, value: String, size: Float, warn: Boolean = false, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        PaperLine(label, paperText(size, DashColors.TextPrimary), Modifier.weight(1f).padding(end = 10.dp))
        PaperLine(value, paperText(size, if (warn) DashColors.Warning else DashColors.TextPrimary, FontWeight.Bold, mono = true))
    }
}

/**
 * A word for a button, in spaced capitals: [primary] boxed in ink (the
 * paper's words on solid ink), else framed by a 2 dp rule. At least
 * [DashSize.TouchPrimary] tall; the touch area grows to the driving size.
 */
@Composable
private fun PaperWordButton(text: String, description: String, primary: Boolean, enabled: Boolean, size: Float = 17f, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .heightIn(min = DashSize.TouchPrimary)
            .widthIn(min = DashSize.TouchPrimary)
            .then(if (primary) Modifier.drawBehind { drawRect(paperInk) } else Modifier.paperFrame())
            .paperTap(enabled, description, onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        PaperLine(text, paperText(size, if (primary) paperPage else DashColors.TextPrimary, FontWeight.Bold, caps = true), caps = true)
    }
}

/**
 * A quiet state as a section: its title and one hint, the hint in bold when a
 * tap does something; the whole section is the target.
 */
@Composable
private fun PaperEmpty(title: String, hint: String, editing: Boolean, ruled: Boolean, onTap: (() -> Unit)?) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .then(if (ruled) Modifier.paperRuleTop() else Modifier)
            .paperEditing(editing)
            .paperTap(onTap != null && !editing, hint) { onTap?.invoke() },
        contentAlignment = Alignment.CenterStart
    ) {
        val pad = padFor(maxWidth, maxHeight)
        val titleSize = min(maxHeight.value * 0.16f, (maxWidth - pad * 2).value / (title.length * 0.5f + 1f)).coerceIn(17f, 30f)
        Column(Modifier.padding(pad)) {
            PaperLine(title, paperText(titleSize, DashColors.TextPrimary, FontWeight.Bold), maxLines = 2)
            Spacer(Modifier.height(4.dp))
            PaperLine(hint, paperText((titleSize * 0.7f).coerceIn(14f, 20f), DashColors.TextSecondary, if (onTap != null) FontWeight.Bold else FontWeight.Normal), maxLines = 2)
        }
    }
}

// --- Navigation: the junction ---------------------------------------------------------

/** The ETA's parts for the arrival row: the time first, then the distance and the duration left. */
private val CLOCK_PART = Regex("""^\d{1,2}[:h.]\d{2}(\s?[AaPp]\.?[Mm]\.?)?$""")

internal fun paperEta(parts: List<String>): String =
    parts.sortedBy { if (CLOCK_PART.matches(it)) 0 else 1 }.joinToString(DOT)

/**
 * The next turn. With the room, the diagram of its junction (PaperSkinMap.kt)
 * over a ruled panel of words: the turn's glyph, the distance large in the
 * typewriter face, the words of the turn, and a ruled row with the arrival.
 * A wide low tile puts the words beside the diagram; a small one keeps the
 * words alone. A new turn, or the distance crossing a step (500 m, 300 m,
 * 100 m...), refreshes the tile like e-ink, and the diagram's corner says
 * when. Without a route: the time large (the date under it), or, with a
 * clock tile already in sight, the words No route. Tap opens the navigation app.
 */
@Composable
private fun PaperNavigation(env: SkinTileEnv) {
    val nav by NavDirections.state.collectAsState()
    val context = env.context
    val canTap = !env.editing
    when {
        !env.hasMediaAccess -> PaperEmpty(
            stringResource(R.string.info_directions_access_title), stringResource(R.string.horizon_tap_allow_notification_access), env.editing, ruled = false,
            if (canTap) ({ CarMediaController.openNotificationAccessSettings(context) }) else null
        )
        !nav.active -> PaperNavIdle(env, nav)
        else -> PaperNavGuiding(env, nav)
    }
}

@Composable
private fun PaperNavGuiding(env: SkinTileEnv, nav: NavState) {
    val context = env.context
    val junction = remember(nav.maneuver, nav.instruction) { paperJunction(nav.maneuver, nav.instruction) }
    val street = remember(nav.instruction) { streetOf(nav.instruction) }
    val bucket = remember(nav.distance) { distanceBucket(nav.distance) }
    val key = Triple(nav.instruction, junction, bucket)
    val timeFmt = rememberTimeFormat()
    val refreshedAt = remember(key) { timeFmt.format(Date()) }
    val refreshedWord = stringResource(R.string.paper_refreshed, refreshedAt)
    val eta = remember(nav.eta) { paperEta(nav.etaParts) }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .paperRefresh(key)
            .paperFrame(editing = env.editing)
            .paperTap(!env.editing, stringResource(R.string.horizon_open_navigation)) { openNavigationApp(context, nav) }
    ) {
        val w = maxWidth
        val h = maxHeight
        val side = w >= 560.dp && w > h * 1.5f && h >= 220.dp
        val stacked = !side && w >= 280.dp && h >= 400.dp
        when {
            side -> Row(Modifier.fillMaxSize()) {
                PaperJunctionMap(junction, nav.instruction, street, refreshedWord.takeIf { h >= 300.dp }, Modifier.weight(1.15f).fillMaxHeight())
                Spacer(Modifier.width(PAPER_RULE).fillMaxHeight().drawBehind { drawRect(paperRule) })
                Box(Modifier.weight(1f).fillMaxHeight().drawBehind { drawRect(paperPanel) }) {
                    TurnWords(nav, junction, eta, w * 0.46f, h)
                }
            }
            stacked -> Column(Modifier.fillMaxSize()) {
                val wordsH = (h * 0.36f).coerceIn(170.dp, 250.dp)
                PaperJunctionMap(junction, nav.instruction, street, refreshedWord.takeIf { h - wordsH >= 220.dp }, Modifier.fillMaxWidth().weight(1f))
                Spacer(Modifier.height(PAPER_RULE).fillMaxWidth().drawBehind { drawRect(paperRule) })
                Box(Modifier.fillMaxWidth().height(wordsH).drawBehind { drawRect(paperPanel) }) {
                    TurnWords(nav, junction, eta, w, wordsH)
                }
            }
            else -> TurnWords(nav, junction, eta, w, h)
        }
    }
}

/**
 * The turn in words, for a [w] x [h] panel: the glyph and the distance large
 * in the typewriter face, the words of the turn under the distance, then a
 * ruled row with the arrival and what is left when there is the room.
 */
@Composable
private fun TurnWords(nav: NavState, junction: PaperJunction, eta: String, w: Dp, h: Dp) {
    val pad = padFor(w, h) + 4.dp
    val innerW = w - pad * 2
    val innerH = h - pad * 2
    val etaRow = eta.isNotEmpty() && innerH >= 120.dp
    val rowSize = (innerW.value / 22f).coerceIn(15f, 22f)
    val wordsH = innerH - if (etaRow) (rowSize * 2.2f).dp else 0.dp
    val glyph = (wordsH * 0.62f).coerceIn(40.dp, 96.dp).coerceAtMost(innerW * 0.26f)
    val textW = innerW - glyph - 18.dp
    val (value, unit) = nav.distanceParts
    val distText = if (unit.isEmpty()) value else "$value $unit"
    val streetSize = (min(textW.value / 13f, wordsH.value * 0.16f)).coerceIn(15f, 24f)
    val distSize = paperFit(template(distText.ifEmpty { "888 m" }), mono = true, weight = FontWeight.Bold, maxW = textW, maxH = wordsH - (streetSize * 2.6f).dp, minSize = 22f, maxSize = 76f)
    Column(Modifier.fillMaxSize().padding(pad), verticalArrangement = Arrangement.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PaperTurnGlyph(nav, junction, glyph)
            Spacer(Modifier.width(18.dp))
            Column {
                if (distText.isNotEmpty()) {
                    PaperLine(distText, paperText(distSize, DashColors.TextPrimary, FontWeight.Bold, mono = true, hero = true), overflow = TextOverflow.Clip)
                    Spacer(Modifier.height(4.dp))
                    PaperLine(nav.instruction, paperText(streetSize, DashColors.TextPrimary), maxLines = 2)
                } else {
                    PaperLine(nav.instruction, paperText((streetSize * 1.35f).coerceAtMost(32f), DashColors.TextPrimary, FontWeight.Bold), maxLines = 3)
                }
            }
        }
        if (etaRow) {
            Spacer(Modifier.height(10.dp))
            PaperRow(stringResource(R.string.paper_arrival), eta, rowSize)
        }
    }
}

/**
 * No route: the time large in the middle of the page's light dither, the
 * date under it and a line saying how to start, on a framed sheet. With a
 * clock tile in sight the sheet says No route instead of a second clock.
 */
@Composable
private fun PaperNavIdle(env: SkinTileEnv, nav: NavState) {
    val context = env.context
    val clockShown = ClockInSight.on.value
    val ink = DashColors.TextPrimary
    val page = DashColors.Background
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .cachedDraw(ink, page) {
                val base = PaperTextures.dither(paperTownInk(page.luminance() > 0.5f, ink, page), page, 2, paperDot())
                onDrawBehind {
                    drawRect(page)
                    drawRect(base)
                }
            }
            .paperFrame(editing = env.editing)
            .paperTap(!env.editing, stringResource(R.string.horizon_start_navigation)) { openNavigationApp(context, nav) },
        contentAlignment = Alignment.Center
    ) {
        val sheetW = (maxWidth * 0.78f).coerceAtMost(520.dp)
        val sheetH = (maxHeight * 0.6f).coerceAtMost(300.dp)
        val pad = padFor(sheetW, sheetH) + 6.dp
        val innerW = sheetW - pad * 2
        val innerH = sheetH - pad * 2
        Column(
            Modifier.widthIn(max = sheetW).paperFrame(fill = true).padding(pad),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val hintSize = (innerW.value / 20f).coerceIn(14f, 20f)
            if (clockShown) {
                val title = stringResource(R.string.paper_no_route)
                val size = paperFit(title, mono = false, weight = FontWeight.Bold, maxW = innerW, maxH = innerH * 0.4f, minSize = 20f, maxSize = 44f)
                PaperLine(title, paperText(size, DashColors.TextPrimary, FontWeight.Bold), align = TextAlign.Center)
            } else {
                IdleTime(innerW, innerH - (hintSize * 1.4f + 8f).dp)
            }
            Spacer(Modifier.height(8.dp))
            PaperLine(stringResource(R.string.horizon_start_navigation), paperText(hintSize, DashColors.TextSecondary, FontWeight.Bold), align = TextAlign.Center, maxLines = 2)
        }
    }
}

/** The idle sheet's time and date; in its own scope, so only it follows the minutes. */
@Composable
private fun IdleTime(maxW: Dp, maxH: Dp) {
    val now = rememberNow(60_000L)
    val units = LocalUnits.current
    val locale = Locale.getDefault()
    val timeFmt = rememberDateFormat(units.digitsPattern())
    val dateFmt = rememberDateFormat(PAPER_LONG_DATE, best = true)
    val time = timeFmt.format(now)
    val dateSize = (maxW.value / 18f).coerceIn(15f, 22f)
    val size = paperFit(template(time), mono = false, weight = FontWeight.Bold, maxW = maxW, maxH = maxH - (dateSize * 1.3f).dp, minSize = 28f, maxSize = 120f, hero = true)
    PaperLine(time, paperText(size, DashColors.TextPrimary, FontWeight.Bold, hero = true), overflow = TextOverflow.Clip)
    PaperLine(dateFmt.format(now).replaceFirstChar { it.titlecase(locale) }, paperText(dateSize, DashColors.TextPrimary), align = TextAlign.Center)
}

/** Skeleton of weekday, day and month ("Tuesday 13 October"), in the locale's order. */
private const val PAPER_LONG_DATE = "EEEEdMMMM"
private const val PAPER_SHORT_DATE = "EEEdMMM"

// --- Clock -------------------------------------------------------------------------

/**
 * The time huge and bold, the date and the outside temperature on one line
 * under it ("Tuesday 13 October · 14 °C", shortened where it does not fit).
 * A new hour refreshes it like e-ink; a new minute only changes its digits.
 * Tap opens the alarms.
 */
@Composable
private fun PaperClock(env: SkinTileEnv) {
    val now = rememberNow(60_000L)
    val locale = Locale.getDefault()
    val units = LocalUnits.current
    val timeFmt = rememberDateFormat(units.digitsPattern())
    val longDate = rememberDateFormat(PAPER_LONG_DATE, best = true)
    val shortDate = rememberDateFormat(PAPER_SHORT_DATE, best = true)
    val temp = rememberOutsideTempC()
    val hour = remember(now) { Calendar.getInstance().apply { time = now }.let { it.get(Calendar.DAY_OF_YEAR) * 24 + it.get(Calendar.HOUR_OF_DAY) } }
    val context = env.context
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .paperRefresh(hour)
            .paperEditing(env.editing)
            .paperTap(!env.editing, stringResource(R.string.horizon_open_clock)) { openClockApp(context) },
        contentAlignment = Alignment.CenterStart
    ) {
        val pad = padFor(maxWidth, maxHeight) + 8.dp
        val innerW = maxWidth - pad * 2
        val innerH = maxHeight - pad * 2
        val dateSize = min(innerW.value / 18f, innerH.value * 0.13f).coerceIn(15f, 24f)
        val tempText = temp?.let { units.tempText(it) }
        fun line(date: String) = if (tempText != null) "$date$DOT$tempText" else date
        val dateLine = firstFitting(
            listOf(
                line(longDate.format(now).replaceFirstChar { it.titlecase(locale) }),
                line(shortDate.format(now).replaceFirstChar { it.titlecase(locale) }),
                shortDate.format(now).replaceFirstChar { it.titlecase(locale) }
            ),
            dateSize, innerW
        )
        val amPm = units.amPm(now, locale)
        val timeText = timeFmt.format(now)
        val timeSize = paperFit(
            template(timeText), mono = false, weight = FontWeight.Bold,
            maxW = if (amPm != null) innerW * 0.82f else innerW, maxH = innerH - (dateSize * 1.3f + 10f).dp,
            minSize = 28f, maxSize = 160f, hero = true
        )
        Column(Modifier.padding(pad)) {
            Row(verticalAlignment = Alignment.Bottom) {
                PaperLine(timeText, paperText(timeSize, DashColors.TextPrimary, FontWeight.Bold, hero = true), overflow = TextOverflow.Clip)
                if (amPm != null) {
                    Spacer(Modifier.width((timeSize * 0.06f).dp))
                    PaperLine(amPm, paperText((timeSize * 0.22f).coerceAtLeast(14f), DashColors.TextPrimary, FontWeight.Bold))
                }
            }
            Spacer(Modifier.height(10.dp))
            PaperLine(dateLine, paperText(dateSize, DashColors.TextPrimary))
        }
    }
}

// --- Speed -------------------------------------------------------------------------

/**
 * The speed as a section. A low tile is one ruled row ("Speed ... 48 km/h ·
 * limit 50"); otherwise the speed large in the typewriter face with its
 * unit, and the road's limit in a box beside it, the box inked solid once
 * the speed is over it. A new limit refreshes the tile like e-ink. Without
 * a speed it says so; a tap connects the adapter while the link is idle.
 */
@Composable
private fun PaperSpeed(env: SkinTileEnv) {
    val context = env.context
    DisposableEffect(Unit) {
        SpeedLimit.start(context)
        onDispose { SpeedLimit.stop() }
    }
    val speed = rememberSpeedKmh()
    val limit by SpeedLimit.limit.collectAsState()
    val units = LocalUnits.current
    val over = speedOver(speed)
    val canConnect = speed == null && env.obdConnection.isIdle && !env.editing
    val said = speed?.let { "${units.speed(it)} ${units.speedUnit}" } ?: stringResource(R.string.info_speed_no_signal)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .paperRefresh(limit)
            .paperRuleTop()
            .paperEditing(env.editing)
            .paperTap(canConnect, stringResource(R.string.horizon_connect_obd), env.onConnectObd)
            .semantics(mergeDescendants = true) { contentDescription = said }
    ) {
        val pad = padFor(maxWidth, maxHeight) + 4.dp
        val innerW = maxWidth - pad * 2
        val innerH = maxHeight - pad * 2
        val number = speed?.let { units.speed(it).toString() } ?: "–"
        if (innerH < 110.dp || maxWidth > maxHeight * 3.4f) {
            val value = buildString {
                append(speed?.let { "${units.speed(it)} ${units.speedUnit}" } ?: "--")
                limit?.let { append(DOT).append(stringResource(R.string.paper_limit, units.speed(it))) }
            }
            Box(Modifier.fillMaxSize().padding(horizontal = pad), contentAlignment = Alignment.CenterStart) {
                PaperRow(stringResource(R.string.paper_speed), value, (innerH.value * 0.32f).coerceIn(15f, 24f), warn = over)
            }
            return@BoxWithConstraints
        }
        val box = (min(innerH.value * 0.62f, innerW.value * 0.34f)).coerceIn(52f, 120f).dp
        val numW = innerW - (if (limit != null) box + 16.dp else 0.dp)
        val unitSize = (innerH.value * 0.12f).coerceIn(14f, 22f)
        val numSize = paperFit("188", mono = true, weight = FontWeight.Bold, maxW = numW * 0.9f, maxH = innerH - (unitSize * 1.4f).dp, minSize = 30f, maxSize = 150f, hero = true)
        Row(Modifier.fillMaxSize().padding(pad), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                PaperLine(
                    number,
                    paperText(if (speed != null) numSize else numSize * 0.6f, when {
                        speed == null -> DashColors.Muted
                        over -> DashColors.Warning
                        else -> DashColors.TextPrimary
                    }, FontWeight.Bold, mono = true, hero = true),
                    overflow = TextOverflow.Clip
                )
                PaperLine(if (speed != null) units.speedUnit else stringResource(R.string.info_speed_no_signal), paperText(unitSize, DashColors.TextSecondary))
            }
            limit?.let { LimitBox(units.speed(it), over, box) }
        }
    }
}

/** The limit boxed in a 2 dp ink frame with LIMIT over it; inked solid, the figure in paper, when the speed is over it. */
@Composable
private fun LimitBox(limit: Int, over: Boolean, size: Dp) {
    val said = stringResource(R.string.paper_limit, limit)
    Column(
        Modifier
            .size(size)
            .then(if (over) Modifier.drawBehind { drawRect(paperInk) } else Modifier.paperFrame())
            .semantics { contentDescription = said },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val ink = if (over) paperPage else DashColors.TextPrimary
        PaperLine(stringResource(R.string.paper_limit_label), paperText((size.value * 0.15f).coerceIn(12f, 16f), ink, FontWeight.Bold, caps = true), caps = true)
        PaperLine(limit.toString(), paperText(size.value * if (limit >= 100) 0.34f else 0.44f, ink, FontWeight.Bold, mono = true, hero = true), overflow = TextOverflow.Clip)
    }
}

// --- Weather: the rain to come ---------------------------------------------------------

/** How many hours the rain section shows. */
internal const val PAPER_RAIN_HOURS = 6

/**
 * Which of the [hours] hours from [nowMs]'s hour on are wet, from the rain
 * spell the forecast found ([fromMs]..[untilMs], null when dry): an hour is
 * wet when it overlaps the spell.
 */
internal fun wetHours(nowMs: Long, fromMs: Long?, untilMs: Long?, hours: Int = PAPER_RAIN_HOURS, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): BooleanArray {
    if (fromMs == null) return BooleanArray(hours)
    val until = untilMs ?: (fromMs + HOUR_MS)
    val start = hourStart(nowMs, zone)
    return BooleanArray(hours) { i ->
        val a = start + i * HOUR_MS
        a < until && a + HOUR_MS > fromMs
    }
}

private const val HOUR_MS = 3_600_000L

/** The start of [ms]'s hour in [zone]. */
internal fun hourStart(ms: Long, zone: java.util.TimeZone): Long {
    val off = zone.getOffset(ms)
    return Math.floorDiv(ms + off, HOUR_MS) * HOUR_MS - off
}

/**
 * Rain, as in the mockup: "Rain, next 6 h" and when it comes ("from 21:40",
 * or "until 22:00" while it rains), six hourly bars, hatched where the hour
 * is wet, and the hours under them in the typewriter face. The forecast
 * gives the first wet spell, not how much falls, so a wet hour is a full
 * bar and a dry one a flat line. With the room the temperature and the sky
 * in words above. A change in the spell refreshes it like e-ink.
 */
@Composable
private fun PaperWeather(env: SkinTileEnv) {
    val w = rememberWeather()
    val wait = rememberWeatherWait()
    val units = LocalUnits.current
    val timeFmt = rememberTimeFormat()
    val nowMs = rememberWallClock(60_000L).longValue
    val hourFmt = rememberDateFormat(if (units.clock24) "HH" else "ha")
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .paperRefresh(w?.rainFromMs to w?.rainUntilMs)
            .paperRuleTop()
            .paperEditing(env.editing)
    ) {
        val pad = padFor(maxWidth, maxHeight) + 4.dp
        val innerW = maxWidth - pad * 2
        val innerH = maxHeight - pad * 2
        val rowSize = (min(innerW.value / 16f, innerH.value * 0.13f)).coerceIn(15f, 22f)
        if (w == null) {
            val title = when (wait) {
                WeatherWait.LOADING -> R.string.horizon_loading
                WeatherWait.NO_GPS -> R.string.info_waiting_gps
                WeatherWait.UNAVAILABLE -> R.string.info_weather_unavailable
            }
            Column(Modifier.fillMaxSize().padding(pad), verticalArrangement = Arrangement.Center) {
                PaperLine("--°", paperText((rowSize * 2f).coerceAtMost(innerH.value * 0.4f), DashColors.Muted, FontWeight.Bold, mono = true))
                PaperLine(stringResource(title), paperText(rowSize, DashColors.TextSecondary), maxLines = 2)
            }
            return@BoxWithConstraints
        }
        val wet = remember(nowMs / HOUR_MS, w.rainFromMs, w.rainUntilMs) { wetHours(nowMs, w.rainFromMs, w.rainUntilMs) }
        val start = remember(nowMs / HOUR_MS) { hourStart(nowMs, java.util.TimeZone.getDefault()) }
        val labels = remember(start, hourFmt) { List(PAPER_RAIN_HOURS) { hourFmt.format(Date(start + it * HOUR_MS)).lowercase(Locale.getDefault()).removeSuffix("m") } }
        val raining = w.rainFromMs != null && w.rainFromMs <= nowMs
        val title = stringResource(if (wet.any { it }) R.string.paper_rain_next else R.string.paper_dry_next, PAPER_RAIN_HOURS)
        val whenText = when {
            w.rainFromMs == null -> units.tempText(w.tempC)
            raining && w.rainUntilMs != null -> stringResource(R.string.paper_rain_until, timeFmt.format(Date(w.rainUntilMs)))
            else -> stringResource(R.string.paper_rain_from, timeFmt.format(Date(w.rainFromMs)))
        }
        val top = innerH >= 200.dp
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (top) {
                Row(verticalAlignment = Alignment.Bottom) {
                    PaperLine(units.tempText(w.tempC), paperText(rowSize * 1.9f, DashColors.TextPrimary, FontWeight.Bold, mono = true), Modifier.alignByBaseline())
                    Spacer(Modifier.width(12.dp))
                    PaperLine(w.condition, paperText(rowSize, DashColors.TextPrimary), Modifier.alignByBaseline().weight(1f))
                }
                Spacer(Modifier.height(8.dp))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PaperLine(title, paperText(rowSize, DashColors.TextPrimary), Modifier.weight(1f).padding(end = 8.dp))
                PaperLine(whenText, paperText(rowSize, DashColors.TextPrimary, FontWeight.Bold, mono = true))
            }
            Spacer(Modifier.height(8.dp))
            RainBars(wet, Modifier.fillMaxWidth().weight(1f))
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth()) {
                labels.forEach { PaperLine(it, paperText(13f, DashColors.TextPrimary, mono = true), Modifier.weight(1f), align = TextAlign.Start) }
            }
        }
    }
}

/** Six hourly bars on a base rule: a wet hour a full hatched bar in its 2 dp ink frame (no floor), a dry hour a short flat stub. */
@Composable
private fun RainBars(wet: BooleanArray, modifier: Modifier) {
    val ink = DashColors.TextPrimary
    Spacer(
        modifier.cachedDraw(wet.contentHashCode(), ink) {
            val n = wet.size
            val gap = 6.dp.toPx()
            val barW = (size.width - gap * (n - 1)) / n
            val rule = PAPER_RULE.toPx()
            val dot = paperDot()
            val hatch = PaperTextures.hatch(ink, Color.Transparent, 5, 2, dot)
            val stub = 4.dp.toPx()
            onDrawBehind {
                for (i in 0 until n) {
                    val x = i * (barW + gap)
                    val h = if (wet[i]) size.height else stub
                    val y = size.height - h
                    if (wet[i]) drawRect(hatch, Offset(x, y), Size(barW, h))
                    // The frame without its floor, as in the mockup.
                    drawRect(ink, Offset(x, y), Size(barW, rule))
                    drawRect(ink, Offset(x, y), Size(rule, h))
                    drawRect(ink, Offset(x + barW - rule, y), Size(rule, h))
                }
            }
        }
    )
}

// --- Media -------------------------------------------------------------------------

/**
 * Music as one plain line, the song in bold and who sings it after a dot
 * ("Nightcall · Kavinsky"), and words for buttons under it: PREV, PLAY or
 * PAUSE boxed in ink, NEXT. A new song refreshes the tile like e-ink. No
 * progress bar: it would change every second.
 */
@Composable
private fun PaperMedia(env: SkinTileEnv) {
    val ms = env.mediaState
    val context = env.context
    if (!env.hasMediaAccess) {
        PaperEmpty(
            stringResource(R.string.info_media_access_needed), stringResource(R.string.horizon_tap_allow_notification_access), env.editing, ruled = true,
            if (!env.editing) ({ CarMediaController.openNotificationAccessSettings(context) }) else null
        )
        return
    }
    val hasTrack = ms.hasMedia && ms.title.isNotBlank()
    val title = if (hasTrack) ms.title else stringResource(R.string.info_nothing_playing)
    val sub = if (hasTrack) ms.artist.ifBlank { ms.station } else stringResource(R.string.horizon_play_something)
    val c = env.mediaController
    val enabled = !env.editing
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .paperRefresh(if (hasTrack) ms.title + "\u0000" + ms.artist else null)
            .paperRuleTop()
            .paperEditing(env.editing)
    ) {
        val pad = padFor(maxWidth, maxHeight) + 4.dp
        val innerW = maxWidth - pad * 2
        val innerH = maxHeight - pad * 2
        val oneRow = innerH < 120.dp
        val titleSize = (min(innerW.value / 16f, innerH.value * (if (oneRow) 0.3f else 0.2f))).coerceIn(17f, 30f)
        val words: @Composable (Modifier) -> Unit = { m ->
            Row(m, verticalAlignment = Alignment.Bottom) {
                PaperLine(title, paperText(titleSize, DashColors.TextPrimary, FontWeight.Bold), Modifier.alignByBaseline().weight(1f, fill = false))
                if (sub.isNotBlank()) {
                    PaperLine(DOT + sub, paperText(titleSize * 0.78f, DashColors.TextPrimary), Modifier.alignByBaseline().weight(1f, fill = false))
                }
            }
        }
        val keys: @Composable () -> Unit = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (innerW >= 300.dp) PaperWordButton(stringResource(R.string.paper_prev), stringResource(R.string.horizon_cd_previous_track), false, enabled) { c.previous() }
                PaperWordButton(
                    stringResource(if (ms.isPlaying) R.string.paper_pause else R.string.paper_play),
                    stringResource(if (ms.isPlaying) R.string.horizon_cd_pause else R.string.horizon_cd_play),
                    true, enabled
                ) { c.playPause() }
                PaperWordButton(stringResource(R.string.paper_next), stringResource(R.string.horizon_cd_next_track), false, enabled) { c.next() }
            }
        }
        if (oneRow) {
            Row(Modifier.fillMaxSize().padding(horizontal = pad), verticalAlignment = Alignment.CenterVertically) {
                words(Modifier.weight(1f).padding(end = 12.dp))
                keys()
            }
        } else {
            Column(Modifier.fillMaxSize().padding(pad), verticalArrangement = Arrangement.SpaceBetween) {
                words(Modifier.fillMaxWidth())
                keys()
            }
        }
    }
}

// --- Telemetry and range: ruled rows ------------------------------------------------------

/**
 * The engine as ruled rows in the typewriter face: coolant, battery, revs,
 * load, intake air, as many as fit; a reading out of range in amber.
 * Without the link the rows wait and words offer to connect.
 */
@Composable
private fun PaperTelemetry(env: SkinTileEnv) {
    val state = env.obdConnection
    val connected = state == ObdConnectionState.CONNECTED
    val units = LocalUnits.current
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .paperRuleTop()
            .paperEditing(env.editing)
    ) {
        val pad = padFor(maxWidth, maxHeight) + 4.dp
        val innerW = maxWidth - pad * 2
        val innerH = maxHeight - pad * 2
        val size = (innerW.value / 18f).coerceIn(15f, 22f)
        val rowH = size * 1.2f + 8f
        Column(Modifier.fillMaxSize().padding(pad), verticalArrangement = Arrangement.Center) {
            if (connected) {
                val d = env.obdData
                val rows = buildList {
                    add(Triple(stringResource(R.string.paper_coolant), if (d.coolantTempC > 0) units.tempText(d.coolantTempC) else "--", d.coolantTempC >= COOLANT_WARNING_C))
                    add(Triple(stringResource(R.string.paper_battery), if (d.voltage > 0.0) String.format(Locale.getDefault(), "%.1f V", d.voltage) else "--", d.voltage > 0.0 && d.voltage !in BATTERY_OK_V))
                    add(Triple(stringResource(R.string.paper_revs), "${groupThousands(d.rpm)} ${stringResource(R.string.paper_rpm)}", false))
                    add(Triple(stringResource(R.string.paper_load), "${d.engineLoadPct} %", false))
                    if (d.intakeTempC != 0) add(Triple(stringResource(R.string.paper_intake), units.tempText(d.intakeTempC), false))
                }
                val fit = (innerH.value / rowH).toInt().coerceIn(1, rows.size)
                rows.take(fit).forEach { (l, v, warn) -> PaperRow(l, v, size, warn) }
            } else {
                val fit = ((innerH.value - 64f) / rowH).toInt().coerceIn(0, 2)
                listOf(R.string.paper_coolant, R.string.paper_battery).take(fit).forEach { PaperRow(stringResource(it), "--", size) }
                if (fit > 0) Spacer(Modifier.height(8.dp))
                when (state) {
                    ObdConnectionState.CONNECTING -> PaperLine(stringResource(R.string.horizon_obd_connecting), paperText(size, DashColors.TextPrimary, FontWeight.Bold))
                    else -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PaperWordButton(stringResource(R.string.paper_connect), stringResource(R.string.horizon_connect_obd), true, !env.editing, onClick = env.onConnectObd)
                        if (state == ObdConnectionState.ERROR && innerW >= 320.dp) {
                            PaperWordButton(stringResource(R.string.tape_cd_choose_adapter), stringResource(R.string.tape_cd_choose_adapter), false, !env.editing, size = 15f, onClick = env.onPickDevice)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Fuel and range as ruled rows (the range, the level, the litres) over a
 * gauge: the tank as a 2 dp ink frame filled with a dense dither up to the
 * level. Fuel not learned yet falls back to the standard tile on a sheet,
 * which explains how to learn it.
 */
@Composable
private fun PaperRange(item: DashboardItem, env: SkinTileEnv) {
    val fuel = rememberFuel(env)
    if (fuel == null) {
        Box(Modifier.fillMaxSize().paperFallbackPanel()) { StandardSkinnedTile(item, env) }
        return
    }
    val units = LocalUnits.current
    val low = fuel.percent <= SKIN_LOW_FUEL_PCT
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .paperRuleTop()
            .paperEditing(env.editing)
    ) {
        val pad = padFor(maxWidth, maxHeight) + 4.dp
        val innerW = maxWidth - pad * 2
        val innerH = maxHeight - pad * 2
        val size = (innerW.value / 18f).coerceIn(15f, 22f)
        val rowH = size * 1.2f + 8f
        val gaugeH = (innerH * 0.18f).coerceIn(16.dp, 34.dp)
        val rows = buildList {
            add(Triple(stringResource(R.string.paper_range), "${groupThousands(units.distance(fuel.rangeKm))} ${units.distanceUnit}", low))
            add(Triple(stringResource(R.string.paper_fuel), "${fuel.percent} %", low))
            add(Triple(stringResource(R.string.paper_litres), String.format(Locale.getDefault(), "%s%.0f L", if (fuel.percentEstimated) "≈ " else "", fuel.liters), false))
        }
        val fit = ((innerH.value - gaugeH.value - 10f) / rowH).toInt().coerceIn(1, rows.size)
        Column(Modifier.fillMaxSize().padding(pad), verticalArrangement = Arrangement.Center) {
            rows.take(fit).forEach { (l, v, warn) -> PaperRow(l, v, size, warn) }
            Spacer(Modifier.height(10.dp))
            FuelGauge(fuel.percent, low, Modifier.fillMaxWidth().height(gaugeH))
        }
    }
}

/** The tank: a 2 dp ink frame, a dense dither up to [percent] (hatched in amber's place when [low]). */
@Composable
private fun FuelGauge(percent: Int, low: Boolean, modifier: Modifier) {
    val ink = DashColors.TextPrimary
    val warn = DashColors.Warning
    Spacer(
        modifier.cachedDraw(percent, low, ink, warn) {
            val rule = PAPER_RULE.toPx()
            val fillW = (size.width - 2 * rule) * (percent / 100f).coerceIn(0f, 1f)
            val brush = if (low) PaperTextures.hatch(warn, Color.Transparent, 5, 2, paperDot()) else PaperTextures.dither(ink, Color.Transparent, 11, paperDot())
            onDrawBehind {
                if (fillW > 0f) drawRect(brush, Offset(rule, rule), Size(fillW, size.height - 2 * rule))
                drawRect(ink, Offset(rule / 2f, rule / 2f), Size(size.width - rule, size.height - rule), style = Stroke(rule))
            }
        }
    )
}

// --- Apps --------------------------------------------------------------------------

/** An app's icon in greys, as e-paper would print it. */
@Composable
private fun PaperAppIcon(app: AppEntry?, size: Dp) {
    val px = with(LocalDensity.current) { size.roundToPx() }.coerceAtLeast(1)
    val icon: Drawable? = app?.icon
    val bitmap = remember(icon, px) { icon?.let { runCatching { drawableBitmap(it, px).asImageBitmap() }.getOrNull() } }
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, colorFilter = GreyFilter, modifier = Modifier.size(size))
    }
}

private val GreyFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

private fun drawableBitmap(d: Drawable, px: Int): android.graphics.Bitmap {
    val b = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(b)
    val old = d.copyBounds()
    d.setBounds(0, 0, px, px)
    d.draw(canvas)
    d.bounds = old
    return b
}

/** App shortcut: a framed sheet with the app's icon in greys and its name in capitals. */
@Composable
private fun PaperApp(item: DashboardItem.AppShortcut, env: SkinTileEnv) {
    val app = env.appsByPackage[item.packageName]
    val label = appLabel(app, item.packageName)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .paperFrame(editing = env.editing, fill = true)
            .paperTap(!env.editing, stringResource(R.string.horizon_open_app, label)) { env.onLaunchApp(item.packageName) }
            .semantics(mergeDescendants = true) { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        val row = maxWidth >= maxHeight * 1.5f && maxWidth >= 180.dp
        if (row) {
            val icon = (maxHeight.value * 0.48f).coerceIn(28f, 64f).dp
            Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                PaperAppIcon(app, icon)
                Spacer(Modifier.width(14.dp))
                PaperLine(label, paperText((icon.value * 0.36f).coerceIn(15f, 22f), DashColors.TextPrimary, FontWeight.Bold, caps = true), Modifier.weight(1f, fill = false), caps = true)
            }
        } else {
            val icon = (min(maxWidth.value, maxHeight.value) * 0.4f).coerceIn(28f, 88f).dp
            val named = maxHeight >= 96.dp
            Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                PaperAppIcon(app, icon)
                if (named) {
                    Spacer(Modifier.height(8.dp))
                    PaperLine(label, paperText((icon.value * 0.26f).coerceIn(14f, 19f), DashColors.TextPrimary, FontWeight.Bold, caps = true), align = TextAlign.Center, caps = true)
                }
            }
        }
    }
}

/**
 * Launch bar: a ruled row of the apps' names in capitals, each framed (the
 * icon in greys beside it where there is room), and EDIT at the end. Apps
 * that would get less than a 64 dp key are left out.
 */
@Composable
private fun PaperLaunchBar(item: DashboardItem.LaunchBar, env: SkinTileEnv) {
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer()) {
        val gap = 10.dp
        val edit = 88.dp
        val room = maxWidth - edit - gap
        val fit = ((room + gap).value / (64f + gap.value)).toInt().coerceAtLeast(1)
        val pkgs = item.packages.take(fit)
        val per = if (pkgs.isEmpty()) 0f else (room.value - gap.value * (pkgs.size - 1)) / pkgs.size
        val icon = (maxHeight.value * 0.42f).coerceIn(22f, 40f).dp
        val textSize = (maxHeight.value * 0.2f).coerceIn(14f, 18f)
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
            if (pkgs.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxHeight().paperFrame(editing = env.editing).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                    PaperLine(stringResource(R.string.paper_launch_bar_empty), paperText(textSize, DashColors.TextSecondary), maxLines = 2)
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
                            .paperFrame(editing = env.editing, fill = true)
                            .paperTap(!env.editing, stringResource(R.string.horizon_open_app, name)) { env.onLaunchApp(pkg) }
                            .semantics(mergeDescendants = true) { contentDescription = name }
                            .padding(horizontal = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (per >= 110f || per < 90f) PaperAppIcon(app, icon)
                        if (per >= 90f) {
                            if (per >= 110f) Spacer(Modifier.width(10.dp))
                            PaperLine(name, paperText(textSize, DashColors.TextPrimary, FontWeight.Bold, caps = true), Modifier.weight(1f, fill = false), caps = true)
                        }
                    }
                }
            }
            PaperWordButton(
                stringResource(R.string.paper_edit), stringResource(R.string.horizon_cd_edit_launch_bar), false, true,
                size = 15f, modifier = Modifier.width(edit).fillMaxHeight(), onClick = env.onEditLaunchBar
            )
        }
    }
}
