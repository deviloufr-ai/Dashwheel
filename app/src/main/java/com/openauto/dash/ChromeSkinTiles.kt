package com.openauto.dash

import com.openauto.dash.vehicle.any
import com.openauto.dash.vehicle.rememberPanelGauges
import com.openauto.dash.vehicle.K
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Date
import java.util.Locale
import kotlin.math.min

/*
 * Chrome skin, the tiles (the look itself is in ChromeSkin.kt, the drop in
 * ChromeSkinDrop.kt). The turn, the speed and the time stand bare on the
 * page in liquid chrome lettering; the range, the weather and the engine's
 * readings are holographic chips; the music is a pill in a chrome bezel with
 * an iridescent disc, under the liquid drop when the tile has the room.
 */

private const val DOT = " · "

/** Up to this height a chip or the music pill keeps the foot row's height and sits at the tile's foot. */
private val FOOT_TILE = 210.dp

/** Largest size (dp, for [chromeType]) within [minSize]..[maxSize] at which [sample] fits [maxW] x [maxH] on one line in [face]. */
@Composable
private fun chromeFit(sample: String, face: ChromeFace, maxW: Dp, maxH: Dp, minSize: Float, maxSize: Float): Float {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = chromeType(100f, face)
    return remember(sample, style, maxW, maxH, minSize, maxSize, density) {
        val probe = measurer.measure(sample, style, softWrap = false, maxLines = 1)
        val byW = with(density) { maxW.toPx() } * 100f / probe.size.width.coerceAtLeast(1)
        val byH = with(density) { maxH.toPx() } * 100f / probe.size.height.coerceAtLeast(1)
        min(byW, byH).coerceIn(minSize, maxSize.coerceAtLeast(minSize))
    }
}

/** Digits replaced by '8' so a readout keeps one size while its value changes. */
private fun template(text: String): String = text.map { if (it.isDigit()) '8' else it }.joinToString("")

/** A faint dashed outline while arranging, so a tile standing bare on the page reads as movable. */
@Composable
private fun Modifier.chromeEditOutline(editing: Boolean): Modifier {
    if (!editing) return this
    val ink = DashColors.TextSecondary
    return cachedDraw(ink) {
        val stroke = Stroke(1.5f * density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f * density, 7f * density)))
        val r = CHROME_CHIP_R.toPx()
        onDrawBehind { drawRoundRect(ink.copy(alpha = 0.55f), cornerRadius = CornerRadius(r), style = stroke) }
    }
}

/**
 * Chrome renderer for app shortcuts, launch bars and the main widgets.
 * Anything else keeps its standard renderer.
 */
@Composable
internal fun ChromeTile(item: DashboardItem, env: SkinTileEnv) {
    when (item) {
        is DashboardItem.AppShortcut -> ChromeApp(item, env)
        is DashboardItem.LaunchBar -> ChromeLaunchBar(item, env)
        is DashboardItem.BuiltinWidget -> when (item.kind) {
            BuiltinKind.NAVIGATION -> ChromeNav(env)
            BuiltinKind.SPEED_HUD -> ChromeSpeed(env)
            BuiltinKind.CLOCK -> ChromeClock(env)
            BuiltinKind.MEDIA -> ChromeMedia(env)
            BuiltinKind.RANGE -> ChromeRange(item, env)
            BuiltinKind.WEATHER -> ChromeWeather(env)
            BuiltinKind.TELEMETRY -> ChromeTelemetry(env)
            else -> StandardSkinnedTile(item, env)
        }
        else -> StandardSkinnedTile(item, env)
    }
}

// --- Holographic chips --------------------------------------------------------------

/**
 * Where a chip goes in its tile: a low tile keeps the foot row's height at
 * its foot (so chips and the music pill line up along the bottom of Home); a
 * taller one fills the tile.
 */
@Composable
private fun ChipSlot(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer()) {
        if (maxHeight <= FOOT_TILE) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(min(maxHeight.value, CHROME_CHIP_H.value).dp)) { content() }
        } else {
            content()
        }
    }
}

/**
 * A holographic chip: the label in spaced capitals over the value in the
 * wide face, the value as large as the chip allows; [icon] before the value,
 * [below] under it when there is room. The whole chip is the tap target.
 */
@Composable
private fun HoloChip(
    label: String,
    value: String,
    editing: Boolean,
    modifier: Modifier = Modifier,
    valueColor: Color = DashColors.TextPrimary,
    icon: ImageVector? = null,
    tapLabel: String? = null,
    onTap: (() -> Unit)? = null,
    belowHeight: Dp = 0.dp,
    below: (@Composable () -> Unit)? = null
) {
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .chromeHolo(editing = editing)
            .chromeTap(onTap != null && !editing, tapLabel ?: label) { onTap?.invoke() }
            .semantics(mergeDescendants = true) {}
    ) {
        val padH = (maxWidth.value * 0.06f).coerceIn(14f, 24f).dp
        val padV = (maxHeight.value * 0.12f).coerceIn(10f, 20f).dp
        val innerW = maxWidth - padH * 2
        val innerH = maxHeight - padV * 2
        val labelSize = (innerH.value * 0.16f).coerceIn(14f, 17f)
        val showBelow = below != null && innerH >= belowHeight + 80.dp
        val valueH = innerH - (labelSize * 1.3f + 6f).dp - if (showBelow) belowHeight + 8.dp else 0.dp
        val iconW = if (icon != null) (valueH * 0.8f).coerceIn(22.dp, 64.dp) else 0.dp
        val valueSize = chromeFit(template(value), ChromeFace.WIDE, innerW - iconW - (if (icon != null) 10.dp else 0.dp), valueH, 18f, 96f)
        Column(Modifier.fillMaxSize().padding(horizontal = padH, vertical = padV), verticalArrangement = Arrangement.Center) {
            ChromeLabel(label, size = labelSize)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(min(iconW.value, valueSize * 1.1f).dp))
                    Spacer(Modifier.width(10.dp))
                }
                ChromeLine(value, chromeType(valueSize, ChromeFace.WIDE, valueColor))
            }
            if (showBelow) {
                Spacer(Modifier.height(8.dp))
                Box(Modifier.height(belowHeight)) { below?.invoke() }
            }
        }
    }
}

/** A pill button of the skin, at least [DashSize.TouchPrimary] tall: [filled] in polished chrome for the one thing to do, else holographic. */
@Composable
private fun ChromePillButton(text: String, filled: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    Box(
        modifier = Modifier
            .heightIn(min = DashSize.TouchPrimary)
            .then(
                if (filled) Modifier
                    .cachedDraw(Unit) { onDrawBehind { drawRoundRect(ChromeButtonFace, cornerRadius = CornerRadius(size.height / 2f)) } }
                    .clip(RoundedCornerShape(50))
                else Modifier.chromeHolo(100.dp)
            )
            .clickable(enabled = enabled, role = Role.Button) { tap(); onClick() }
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center
    ) {
        ChromeLine(text, chromeType(17f, ChromeFace.SANS, if (filled) ChromeOnMetal else DashColors.TextPrimary, FontWeight.SemiBold))
    }
}

/**
 * A quiet state standing bare on the page: the label, a title in the wide
 * face and one hint, the hint in the accent's ink when a tap does something;
 * the whole tile is the target.
 */
@Composable
private fun ChromeQuiet(label: String, title: String, hint: String, editing: Boolean, onTap: (() -> Unit)?) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .chromeEditOutline(editing)
            .clip(RoundedCornerShape(CHROME_CHIP_R))
            .chromeTap(onTap != null && !editing, hint) { onTap?.invoke() },
        contentAlignment = Alignment.CenterStart
    ) {
        val pad = (min(maxWidth.value, maxHeight.value) * 0.06f).coerceIn(8f, 18f).dp
        val titleSize = min(maxHeight.value * 0.14f, (maxWidth.value - pad.value * 2) / (title.length * 0.5f + 1f)).coerceIn(18f, 34f)
        Column(Modifier.padding(pad)) {
            ChromeLabel(label)
            Spacer(Modifier.height(8.dp))
            ChromeText(title, chromeType(titleSize, ChromeFace.WIDE), maxLines = 2)
            Spacer(Modifier.height(6.dp))
            ChromeLine(hint, chromeType((titleSize * 0.72f).coerceIn(15f, 20f), ChromeFace.SANS, if (onTap != null) DashColors.AccentInk else DashColors.TextSecondary), maxLines = 2)
        }
    }
}

// --- Navigation -----------------------------------------------------------------------

private val CLOCK_PART = Regex("""^\d{1,2}[:h.]\d{2}(\s?[AaPp]\.?[Mm]\.?)?$""")

/** The arrival chip's label and value from the navigation's ETA line: the arrival time when it gives one, else what is left. */
private fun etaChip(nav: NavState): Pair<Boolean, String>? {
    val parts = nav.etaParts
    if (parts.isEmpty()) return null
    val clock = parts.firstOrNull { CLOCK_PART.matches(it) }
    return if (clock != null) true to clock else false to parts.first()
}

/**
 * The next turn, bare on the page as in the design: the side in spaced
 * capitals ("LEFT IN") beside a small arrow, the distance in wide black
 * chrome, the street in the clean sans under it. A tall tile keeps the foot
 * row for the arrival as a holographic chip. Tap opens the navigation app.
 * Without a route (or notification access) it says so and how to start.
 */
@Composable
private fun ChromeNav(env: SkinTileEnv) {
    val nav by NavDirections.state.collectAsState()
    val context = env.context
    val canTap = !env.editing
    val navLabel = stringResource(R.string.chrome_navigation)
    when {
        !env.hasMediaAccess -> ChromeQuiet(
            navLabel, stringResource(R.string.info_directions_access_title), stringResource(R.string.horizon_tap_allow_notification_access), env.editing,
            if (canTap) ({ CarMediaController.openNotificationAccessSettings(context) }) else null
        )
        !nav.active -> ChromeQuiet(
            navLabel, stringResource(R.string.info_directions_no_route), stringResource(R.string.horizon_start_navigation), env.editing,
            if (canTap) ({ openNavigationApp(context, nav) }) else null
        )
        else -> BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .graphicsLayer()
                .chromeEditOutline(env.editing)
                .clip(RoundedCornerShape(CHROME_CHIP_R))
                .chromeTap(canTap, stringResource(R.string.horizon_open_navigation)) { openNavigationApp(context, nav) }
        ) {
            val eta = etaChip(nav)
            val tall = maxHeight >= 300.dp
            val pad = (min(maxWidth.value, maxHeight.value) * 0.03f).coerceIn(6f, 14f).dp
            val heroH = if (tall) maxHeight - CHROME_CHIP_H - 16.dp else maxHeight
            val heroW = maxWidth - pad * 2
            Box(Modifier.fillMaxWidth().height(heroH).padding(horizontal = pad), contentAlignment = Alignment.CenterStart) {
                NavHero(nav, heroW, heroH - pad * 2, etaLine = if (!tall) eta else null)
            }
            if (tall && eta != null) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(CHROME_CHIP_H)) {
                    HoloChip(
                        stringResource(if (eta.first) R.string.chrome_arrival else R.string.chrome_to_go), eta.second, env.editing,
                        tapLabel = stringResource(R.string.horizon_open_navigation), onTap = if (canTap) ({ openNavigationApp(context, nav) }) else null
                    )
                }
            }
        }
    }
}

/** The turn's label, the distance in chrome and the street; [etaLine] under them on a tile too low for the chip. */
@Composable
private fun NavHero(nav: NavState, innerW: Dp, innerH: Dp, etaLine: Pair<Boolean, String>?) {
    val (value, unit) = nav.distanceParts
    val side = nav.turnSide()
    val labelSize = (innerH.value * 0.065f).coerceIn(14f, 18f)
    val streetSize = min(innerW.value / 15f, innerH.value * 0.1f).coerceIn(16f, 26f)
    val streetLines = if (innerH >= 250.dp && value.isNotEmpty()) 2 else 1
    val etaSize = (streetSize * 0.8f).coerceIn(14f, 19f)
    val label = stringResource(
        when {
            value.isEmpty() -> R.string.chrome_navigation
            side == TurnSide.LEFT -> R.string.chrome_left_in
            side == TurnSide.RIGHT -> R.string.chrome_right_in
            else -> R.string.chrome_next_in
        }
    )
    val unitCaps = unit.uppercase(Locale.getDefault())
    val distH = innerH - (labelSize * 1.3f + 10f).dp - (streetSize * 1.25f * streetLines + 8f).dp -
        if (etaLine != null) (etaSize * 1.3f + 6f).dp else 0.dp
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChromeTurnArrow(side, (labelSize * 1.7f).dp)
            Spacer(Modifier.width(10.dp))
            ChromeLabel(label, size = labelSize)
        }
        Spacer(Modifier.height(10.dp))
        if (value.isNotEmpty()) {
            val distSize = chromeFit(template(value) + unitCaps, ChromeFace.HEAVY, innerW, distH, 28f, 150f)
            Row {
                ChromeText(value, chromeType(distSize, ChromeFace.HEAVY), Modifier.alignByBaseline())
                if (unitCaps.isNotEmpty()) {
                    Spacer(Modifier.width((distSize * 0.04f).dp))
                    ChromeText(unitCaps, chromeType(distSize * 0.6f, ChromeFace.HEAVY), Modifier.alignByBaseline())
                }
            }
            Spacer(Modifier.height(8.dp))
            ChromeLine(nav.instruction, chromeType(streetSize, ChromeFace.SANS, DashColors.TextPrimary, FontWeight.Medium), maxLines = streetLines)
        } else {
            val size = (distH.value / 3f).coerceIn(20f, 40f)
            ChromeText(nav.instruction, chromeType(size, ChromeFace.WIDE), maxLines = 3)
        }
        if (etaLine != null) {
            Spacer(Modifier.height(6.dp))
            val word = stringResource(if (etaLine.first) R.string.chrome_arrival else R.string.chrome_to_go)
            ChromeLine("$word ${etaLine.second}", chromeType(etaSize, ChromeFace.SANS, DashColors.TextSecondary))
        }
    }
}

/** The turn's side as a small arrow in the first ink: round the corner to the side, or straight up. */
@Composable
private fun ChromeTurnArrow(side: TurnSide, size: Dp) {
    val ink = DashColors.TextPrimary
    Spacer(
        Modifier
            .size(size)
            .cachedDraw(side, ink) {
                val s = this.size.minDimension / 24f
                val path = Path().apply {
                    when (side) {
                        TurnSide.AHEAD -> {
                            moveTo(12f * s, 21f * s); lineTo(12f * s, 4f * s)
                            moveTo(6.5f * s, 9.5f * s); lineTo(12f * s, 4f * s); lineTo(17.5f * s, 9.5f * s)
                        }
                        else -> {
                            val m = side == TurnSide.LEFT
                            fun x(v: Float) = (if (m) 24f - v else v) * s
                            moveTo(x(7f), 21f * s); lineTo(x(7f), 11f * s)
                            cubicTo(x(7f), 8.8f * s, x(8.8f), 7f * s, x(11f), 7f * s)
                            lineTo(x(19f), 7f * s)
                            moveTo(x(14.5f), 2.5f * s); lineTo(x(19f), 7f * s); lineTo(x(14.5f), 11.5f * s)
                        }
                    }
                }
                val stroke = Stroke(2.6f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
                onDrawBehind { drawPath(path, ink, style = stroke) }
            }
    )
}

// --- Speed --------------------------------------------------------------------------

/**
 * Speed, bare on the page and set to the right as in the design: "KM/H ·
 * LIMIT 50" in spaced capitals over the speed in thin wide chrome, as large
 * as the tile allows. Over the limit the figure and the limit turn amber.
 * Without a speed, a dash and why; a tap then connects the OBD adapter.
 */
@Composable
private fun ChromeSpeed(env: SkinTileEnv) {
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
    val limitWord = limit?.let { stringResource(R.string.chrome_limit, units.speed(it)) }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .chromeEditOutline(env.editing)
            .clip(RoundedCornerShape(CHROME_CHIP_R))
            .chromeTap(canConnect, stringResource(R.string.horizon_connect_obd), env.onConnectObd)
            .semantics(mergeDescendants = true) { contentDescription = said },
        contentAlignment = Alignment.CenterEnd
    ) {
        val pad = (min(maxWidth.value, maxHeight.value) * 0.04f).coerceIn(6f, 14f).dp
        val innerW = maxWidth - pad * 2
        val innerH = maxHeight - pad * 2
        val labelSize = (innerH.value * 0.065f).coerceIn(14f, 18f)
        val hintSize = 16f
        val numH = innerH - (labelSize * 1.3f + 8f).dp - if (speed == null) (hintSize * 1.3f + 4f).dp else 0.dp
        val sample = if ((speed?.let { units.speed(it) } ?: 0) >= 100) "188" else "88"
        val numSize = chromeFit(sample, ChromeFace.THIN, innerW, numH, 36f, 260f)
        Column(Modifier.padding(pad), horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChromeLabel(units.speedUnit, size = labelSize)
                if (limitWord != null) ChromeLabel(DOT + limitWord, size = labelSize, color = if (over) DashColors.Warning else DashColors.TextSecondary)
            }
            Spacer(Modifier.height(8.dp))
            ChromeText(
                speed?.let { units.speed(it).toString() } ?: "–",
                chromeType(if (speed != null) numSize else numSize * 0.6f, ChromeFace.THIN),
                plain = when {
                    speed == null -> DashColors.Muted
                    over -> DashColors.Warning
                    else -> null
                }
            )
            if (speed == null) {
                Spacer(Modifier.height(4.dp))
                ChromeLine(
                    stringResource(if (canConnect) R.string.horizon_connect_obd else R.string.info_speed_no_signal),
                    chromeType(hintSize, ChromeFace.SANS, if (canConnect) DashColors.AccentInk else DashColors.TextSecondary)
                )
            }
        }
    }
}

// --- Clock --------------------------------------------------------------------------

/**
 * Clock, bare on the page: the time in wide chrome with the day and the
 * outside temperature in spaced capitals beside it ("TUE 13 · 14°"), or
 * under it on a squarer tile. Tap opens the alarms.
 */
@Composable
private fun ChromeClock(env: SkinTileEnv) {
    val now = rememberNow(60_000L)
    val units = LocalUnits.current
    val locale = Locale.getDefault()
    val timeFmt = rememberDateFormat(units.digitsPattern())
    val dayFmt = rememberDateFormat("EEEd", best = true)
    val temp = rememberOutsideTempC()
    val context = env.context
    val timeText = timeFmt.format(now)
    val amPm = units.amPm(now, locale)
    val date = dayFmt.format(now) + (temp?.let { "$DOT${units.temp(it)}°" } ?: "")
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .chromeEditOutline(env.editing)
            .clip(RoundedCornerShape(CHROME_CHIP_R))
            .chromeTap(!env.editing, stringResource(R.string.horizon_open_clock)) { openClockApp(context) },
        contentAlignment = Alignment.CenterStart
    ) {
        val pad = (min(maxWidth.value, maxHeight.value) * 0.06f).coerceIn(8f, 18f).dp
        val innerW = maxWidth - pad * 2
        val innerH = maxHeight - pad * 2
        val inline = maxWidth >= maxHeight * 2.2f
        val dateSize = (innerH.value * if (inline) 0.12f else 0.1f).coerceIn(15f, 22f)
        val sample = template(timeText) + (amPm ?: "")
        val timeSize = if (inline) chromeFit(sample, ChromeFace.WIDE, innerW * 0.52f, innerH * 0.8f, 26f, 120f)
        else chromeFit(sample, ChromeFace.WIDE, innerW, innerH - (dateSize * 1.3f + 6f).dp, 26f, 200f)
        val time: @Composable (Modifier) -> Unit = { mod ->
            Row(mod) {
                ChromeText(timeText, chromeType(timeSize, ChromeFace.WIDE), Modifier.alignByBaseline())
                if (amPm != null) {
                    Spacer(Modifier.width((timeSize * 0.06f).dp))
                    ChromeLine(amPm, chromeType((timeSize * 0.3f).coerceAtLeast(14f), ChromeFace.LABEL, DashColors.TextSecondary), Modifier.alignByBaseline())
                }
            }
        }
        if (inline) {
            Row(Modifier.padding(pad), verticalAlignment = Alignment.CenterVertically) {
                time(Modifier)
                Spacer(Modifier.width((timeSize * 0.22f).coerceIn(10f, 24f).dp))
                ChromeLabel(date, Modifier.weight(1f, fill = false), size = dateSize)
            }
        } else {
            Column(Modifier.padding(pad)) {
                time(Modifier)
                Spacer(Modifier.height(6.dp))
                ChromeLabel(date, size = dateSize)
            }
        }
    }
}

// --- Media --------------------------------------------------------------------------

/**
 * Music: a pill in a chrome bezel holding a spinning iridescent disc (the
 * cover, round, when the player gives one), the song and who sings it, and
 * a polished chrome play key (next beside it on a wide pill). With the room
 * above it, the liquid drop floats there and livens up while music plays.
 */
@Composable
private fun ChromeMedia(env: SkinTileEnv) {
    val ms = env.mediaState
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer()) {
        val pillH = min(maxHeight.value, CHROME_CHIP_H.value).dp
        val dropH = maxHeight - pillH - 16.dp
        val drop = dropH >= 150.dp && maxWidth >= 200.dp
        if (drop) {
            ChromeDrop(
                playing = env.hasMediaAccess && ms.hasMedia && ms.isPlaying,
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().height(dropH)
            )
        }
        val align = if (drop || maxHeight <= FOOT_TILE) Alignment.BottomCenter else Alignment.Center
        val pillW = maxWidth
        Box(Modifier.align(align).fillMaxWidth().height(pillH)) {
            ChromePill(env, pillW, pillH)
        }
    }
}

@Composable
private fun ChromePill(env: SkinTileEnv, w: Dp, h: Dp) {
    val ms = env.mediaState
    val context = env.context
    val padX = (h.value * 0.16f).coerceIn(10f, 22f).dp
    val disc = (h * 0.62f).coerceIn(36.dp, 84.dp)
    val withDisc = w >= 300.dp && h >= 64.dp
    val titleSize = (h.value * 0.2f).coerceIn(17f, 24f)
    val subSize = (h.value * 0.15f).coerceIn(14f, 18f)
    if (!env.hasMediaAccess) {
        val hint = stringResource(R.string.horizon_tap_allow_notification_access)
        Row(
            Modifier
                .fillMaxSize()
                .chromeBezel(3.dp, editing = env.editing)
                .chromeTap(!env.editing, hint) { CarMediaController.openNotificationAccessSettings(context) }
                .padding(horizontal = padX),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (withDisc) {
                Box(Modifier.size(disc).clip(CircleShape).chromeHolo(disc / 2), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.MusicNote, contentDescription = null, tint = DashColors.TextPrimary, modifier = Modifier.size(disc * 0.45f))
                }
                Spacer(Modifier.width(padX))
            }
            Column(Modifier.weight(1f)) {
                ChromeLine(stringResource(R.string.info_media_access_needed), chromeType(titleSize, ChromeFace.WIDE))
                ChromeLine(hint, chromeType(subSize, ChromeFace.SANS, DashColors.AccentInk))
            }
        }
        return
    }
    val hasTrack = ms.hasMedia && ms.title.isNotBlank()
    val title = if (hasTrack) ms.title else stringResource(R.string.info_nothing_playing)
    val sub = if (hasTrack) ms.artist.ifBlank { stringResource(R.string.horizon_unknown_artist) } else stringResource(R.string.horizon_play_something)
    val playing = ms.isPlaying
    val key = (h * 0.57f).coerceIn(48.dp, 72.dp)
    val c = env.mediaController
    Row(
        Modifier
            .fillMaxSize()
            .chromeBezel(3.dp, editing = env.editing)
            .padding(start = padX, end = (padX * 0.8f).coerceAtLeast(10.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (withDisc) {
            ChromeDisc(ms, disc)
            Spacer(Modifier.width(padX))
        }
        Column(Modifier.weight(1f)) {
            ChromeLine(title, chromeType(titleSize, ChromeFace.WIDE))
            ChromeLine(sub, chromeType(subSize, ChromeFace.SANS, DashColors.TextSecondary))
        }
        if (w >= 470.dp) {
            ChromeBareKey(Icons.Filled.SkipNext, stringResource(R.string.horizon_cd_next_track), !env.editing) { c.next() }
            Spacer(Modifier.width(6.dp))
        }
        Spacer(Modifier.width(8.dp))
        ChromeMetalButton(
            icon = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            description = stringResource(if (playing) R.string.horizon_cd_pause else R.string.horizon_cd_play),
            size = key,
            enabled = !env.editing
        ) { c.playPause() }
    }
}

/** A bare round key inside the pill: the icon alone in the first ink. */
@Composable
private fun ChromeBareKey(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    Box(
        Modifier
            .size(DashSize.TouchPrimary)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClickLabel = label, role = Role.Button) { tap(); onClick() }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = DashColors.TextPrimary, modifier = Modifier.size(30.dp))
    }
}

/**
 * The pill's disc: an iridescent record (pink, ice blue, lime, amber) with
 * fine grooves, or the song's cover cut round, turning while music plays
 * and the car stands (SkinKit's [rememberSpin]), under a still sheen, a
 * chrome hub and a soft light around it at night.
 */
@Composable
private fun ChromeDisc(ms: MediaState, size: Dp) {
    val spin = rememberSpin(6_000, running = ms.hasMedia && ms.isPlaying)
    val bitmap = remember(ms.artwork) { ms.artwork?.asImageBitmap() }
    val glowK = chromeGlow
    val light = DashColors.Light
    Box(
        Modifier
            .size(size)
            .then(if (glowK > 0f) Modifier.glowHalo(Color(0x66B4C8FF).copy(alpha = 0.4f * glowK), 0.7f) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = spin.value }
                .clip(CircleShape)
                .cachedDraw(bitmap == null) {
                    val c = Offset(this.size.width / 2f, this.size.height / 2f)
                    val r = this.size.minDimension / 2f
                    val conic = Brush.sweepGradient(listOf(IrisPink, IrisBlue, IrisLime, IrisAmber, IrisPink), c)
                    val groove = Stroke(1f)
                    onDrawBehind {
                        if (bitmap == null) {
                            drawCircle(conic, r, c)
                            var g = r * 0.34f
                            while (g < r * 0.95f) {
                                drawCircle(Color.White.copy(alpha = 0.12f), g, c, style = groove)
                                g += r * 0.11f
                            }
                        }
                    }
                }
        ) {
            if (bitmap != null) Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Spacer(
            Modifier
                .fillMaxSize()
                .cachedDraw(light) {
                    val c = Offset(this.size.width / 2f, this.size.height / 2f)
                    val r = this.size.minDimension / 2f
                    val sheen = Brush.linearGradient(
                        listOf(Color.White.copy(alpha = 0.45f), Color.Transparent, Color.Transparent, Color.White.copy(alpha = 0.18f)),
                        Offset(c.x - r, c.y - r), Offset(c.x + r, c.y + r)
                    )
                    val hub = Brush.linearGradient(*chromeMetalStops(light), start = Offset(c.x - r * 0.16f, c.y - r * 0.16f), end = Offset(c.x + r * 0.16f, c.y + r * 0.16f))
                    onDrawBehind {
                        drawCircle(sheen, r, c)
                        drawCircle(hub, r * 0.16f, c)
                        drawCircle(Color(0xFF0B0B10), r * 0.06f, c)
                        drawCircle(Color.White.copy(alpha = if (light) 0.9f else 0.45f), r - density / 2f, c, style = Stroke(density))
                    }
                }
        )
    }
}

// --- Range ----------------------------------------------------------------------------

/**
 * Range as a holographic chip: RANGE over the distance left in the wide
 * face (amber in the reserve) and, with the room, the fuel as an
 * iridescent level with its share. Fuel not learned yet: the standard tile
 * on the holographic glass, which explains how to learn it.
 */
@Composable
private fun ChromeRange(item: DashboardItem, env: SkinTileEnv) {
    val fuel = rememberFuel()
    val units = LocalUnits.current
    if (fuel == null) {
        Box(Modifier.fillMaxSize().graphicsLayer().chromeHolo(editing = env.editing)) { StandardSkinnedTile(item, env) }
        return
    }
    val low = fuel.percent <= SKIN_LOW_FUEL_PCT
    ChipSlot {
        HoloChip(
            label = stringResource(R.string.chrome_range),
            value = "${units.distance(fuel.rangeKm)} ${units.distanceUnit.uppercase(Locale.getDefault())}",
            editing = env.editing,
            valueColor = if (low) DashColors.Warning else DashColors.TextPrimary,
            belowHeight = 22.dp
        ) {
            FuelLevel(fuel.percent, low)
        }
    }
}

/** The fuel's level as a thin iridescent bar with its share beside it. */
@Composable
private fun FuelLevel(percent: Int, low: Boolean) {
    val track = DashColors.Line
    val warn = DashColors.Warning
    val plain = DashColors.Effects == DashEffects.NONE
    val ink = DashColors.TextPrimary
    Row(verticalAlignment = Alignment.CenterVertically) {
        ChromeLabel("${stringResource(R.string.chrome_fuel)} $percent %", size = 14f)
        Spacer(Modifier.width(12.dp))
        Spacer(
            Modifier
                .weight(1f)
                .height(8.dp)
                .cachedDraw(percent, low, track, warn, plain, ink) {
                    val fw = size.width * (percent / 100f).coerceIn(0f, 1f)
                    val fill: Brush = when {
                        low -> androidx.compose.ui.graphics.SolidColor(warn)
                        plain -> androidx.compose.ui.graphics.SolidColor(ink)
                        else -> Brush.horizontalGradient(listOf(IrisPink, IrisBlue, IrisLime, IrisAmber), 0f, size.width)
                    }
                    val r = CornerRadius(size.height / 2f)
                    onDrawBehind {
                        drawRoundRect(track, cornerRadius = r)
                        if (fw > 0f) drawRoundRect(fill, size = Size(fw.coerceAtLeast(size.height), size.height), cornerRadius = r)
                    }
                }
        )
    }
}

// --- Weather --------------------------------------------------------------------------

/**
 * Weather as a holographic chip: the sky in spaced capitals over its icon
 * and the temperature; with the room, the feel and the wind, and rain to
 * come in the accent's ink. While there is nothing yet, it says why.
 */
@Composable
private fun ChromeWeather(env: SkinTileEnv) {
    val w = rememberWeather()
    val wait = rememberWeatherWait()
    val units = LocalUnits.current
    val timeFmt = rememberTimeFormat()
    val nowMs = rememberWallClock(60_000L).longValue
    ChipSlot {
        if (w == null) {
            val why = when (wait) {
                WeatherWait.LOADING -> R.string.horizon_loading
                WeatherWait.NO_GPS -> R.string.info_waiting_gps
                WeatherWait.UNAVAILABLE -> R.string.info_weather_unavailable
            }
            HoloChip(stringResource(R.string.chrome_weather), "--°", env.editing, valueColor = DashColors.Muted, belowHeight = 22.dp) {
                ChromeLine(stringResource(why), chromeType(15f, ChromeFace.SANS, DashColors.TextSecondary))
            }
            return@ChipSlot
        }
        val rain = when {
            w.rainFromMs != null && w.rainUntilMs != null && w.rainFromMs <= nowMs -> stringResource(R.string.canvas_rain_until, timeFmt.format(Date(w.rainUntilMs)))
            w.rainFromMs != null -> stringResource(R.string.canvas_rain_from, timeFmt.format(Date(w.rainFromMs)))
            else -> null
        }
        val feels = stringResource(R.string.horizon_feels, units.temp(w.feelsC))
        val wind = stringResource(if (units.imperial) R.string.units_horizon_wind_mph else R.string.horizon_wind, units.speed(w.windKmh))
        HoloChip(
            label = w.condition,
            value = "${units.temp(w.tempC)}°",
            editing = env.editing,
            icon = weatherIcon(w.code),
            belowHeight = if (rain != null) 44.dp else 22.dp
        ) {
            Column {
                ChromeLine("$feels$DOT$wind", chromeType(15f, ChromeFace.SANS, DashColors.TextSecondary))
                if (rain != null) ChromeLine(rain, chromeType(15f, ChromeFace.SANS, DashColors.AccentInk, FontWeight.SemiBold))
            }
        }
    }
}

// --- Telemetry ------------------------------------------------------------------------

/**
 * The engine's readings as holographic chips (revs, coolant, battery, load):
 * one row on a wide tile, two by two otherwise, a reading out of range in
 * amber. Without the link, one chip says so with the button to connect.
 */
@Composable
private fun ChromeTelemetry(env: SkinTileEnv) {
    val state = env.obdConnection
    val units = LocalUnits.current
    // Whatever the car answers is shown, adapter or not.
    val g = rememberPanelGauges(K.RPM, K.COOLANT_TEMP, K.ENGINE_LOAD, K.BATTERY_VOLTS)
    if (state != ObdConnectionState.CONNECTED && !g.any) {
        BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer().chromeHolo(editing = env.editing)) {
            val pad = (min(maxWidth.value, maxHeight.value) * 0.08f).coerceIn(12f, 22f).dp
            val roomy = maxWidth >= 380.dp
            Column(Modifier.fillMaxSize().padding(pad), verticalArrangement = Arrangement.Center) {
                ChromeLabel(stringResource(R.string.chrome_engine))
                Spacer(Modifier.height(10.dp))
                if (state == ObdConnectionState.CONNECTING) {
                    ChromeLine(stringResource(R.string.horizon_obd_connecting), chromeType(18f, ChromeFace.SANS, DashColors.AccentInk, FontWeight.SemiBold))
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ChromePillButton(stringResource(R.string.horizon_connect_obd), filled = true, enabled = !env.editing, onClick = env.onConnectObd)
                        if (state == ObdConnectionState.ERROR && roomy) {
                            ChromePillButton(stringResource(R.string.tape_cd_choose_adapter), filled = false, enabled = !env.editing, onClick = env.onPickDevice)
                        }
                    }
                }
            }
        }
        return
    }
    val readings = ArrayList<Triple<String, String, Boolean>>(4)
    g.rpm?.let { readings += Triple(stringResource(R.string.chrome_revs), groupThousands(it), false) }
    g.coolantTempC?.let { readings += Triple(stringResource(R.string.chrome_coolant), "${units.temp(it)}°", it >= COOLANT_WARNING_C) }
    g.voltage?.let { readings += Triple(stringResource(R.string.chrome_battery), String.format(Locale.getDefault(), "%.1f V", it), it !in BATTERY_OK_V) }
    g.engineLoadPct?.let { readings += Triple(stringResource(R.string.chrome_load), "$it %", false) }
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer()) {
        val gap = 10.dp
        // Chips at least 140 dp wide; one row on a wide or low tile, else two by two.
        val maxCols = ((maxWidth.value + gap.value) / 150f).toInt().coerceAtLeast(1)
        val rows = if (maxWidth >= maxHeight * 2.2f || maxHeight < 200.dp) 1 else 2
        val cols = min(maxCols, if (rows == 1) readings.size else 2)
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            readings.take(cols * rows).chunked(cols).forEach { row ->
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { (label, value, warn) ->
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            HoloChip(label, value, env.editing, valueColor = if (warn) DashColors.Warning else DashColors.TextPrimary)
                        }
                    }
                    // A short last row keeps its chips the same width as the rows above.
                    repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

// --- Apps -----------------------------------------------------------------------------

/** App shortcut: a holographic chip with the app's icon and its name, side by side on a wide tile, stacked on a square one. */
@Composable
private fun ChromeApp(item: DashboardItem.AppShortcut, env: SkinTileEnv) {
    val app = env.appsByPackage[item.packageName]
    val label = appLabel(app, item.packageName)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .chromeHolo(editing = env.editing)
            .chromeTap(!env.editing, stringResource(R.string.horizon_open_app, label)) { env.onLaunchApp(item.packageName) }
            .semantics(mergeDescendants = true) { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        val row = maxWidth >= maxHeight * 1.5f && maxWidth >= 180.dp
        if (row) {
            val icon = (maxHeight.value * 0.5f).coerceIn(30f, 72f).dp
            Row(Modifier.fillMaxSize().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                ChromeAppGlyph(app, icon)
                Spacer(Modifier.width(14.dp))
                ChromeLine(label, chromeType((icon.value * 0.36f).coerceIn(16f, 24f), ChromeFace.WIDE), Modifier.weight(1f, fill = false))
            }
        } else {
            val icon = (min(maxWidth.value, maxHeight.value) * 0.42f).coerceIn(30f, 96f).dp
            val named = maxHeight >= 96.dp
            Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                ChromeAppGlyph(app, icon)
                if (named) {
                    Spacer(Modifier.height(8.dp))
                    ChromeLine(label, chromeType((icon.value * 0.26f).coerceIn(14f, 20f), ChromeFace.WIDE), align = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun ChromeAppGlyph(app: AppEntry?, size: Dp) {
    if (app != null) AppIcon(icon = app.icon, size = size)
    else Icon(Icons.Filled.Apps, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(size * 0.8f))
}

/**
 * Launch bar: one pill in a chrome bezel holding the apps (icon, and name
 * where there is room) and the pencil at its end. Apps that would get less
 * than a 64 dp key are left out.
 */
@Composable
private fun ChromeLaunchBar(item: DashboardItem.LaunchBar, env: SkinTileEnv) {
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer()) {
        val pillH = min(maxHeight.value, 96f).dp
        val edit = (pillH - 12.dp).coerceIn(48.dp, 64.dp)
        val room = maxWidth - edit - 40.dp
        val fit = (room.value / 72f).toInt().coerceAtLeast(1)
        val pkgs = item.packages.take(fit)
        val per = if (pkgs.isEmpty()) 0f else room.value / pkgs.size
        val labels = per >= 150f
        val icon = (pillH.value * 0.46f).coerceIn(24f, 48f).dp
        val tap = rememberTapFeedback()
        val editLabel = stringResource(R.string.horizon_cd_edit_launch_bar)
        Row(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(pillH)
                .chromeBezel(3.dp, editing = env.editing)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (pkgs.isEmpty()) {
                ChromeLine(stringResource(R.string.chrome_launch_bar_empty), chromeType(16f, ChromeFace.SANS, DashColors.TextSecondary), Modifier.weight(1f), maxLines = 2)
            } else {
                pkgs.forEach { pkg ->
                    val app = env.appsByPackage[pkg]
                    val name = appLabel(app, pkg)
                    Row(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .widthIn(min = 64.dp)
                            .clip(RoundedCornerShape(50))
                            .chromeTap(!env.editing, stringResource(R.string.horizon_open_app, name)) { env.onLaunchApp(pkg) }
                            .semantics(mergeDescendants = true) { contentDescription = name }
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = if (labels) Arrangement.Start else Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ChromeAppGlyph(app, icon)
                        if (labels) {
                            Spacer(Modifier.width(10.dp))
                            ChromeLine(name, chromeType(16f, ChromeFace.SANS, DashColors.TextPrimary, FontWeight.Medium), Modifier.weight(1f, fill = false))
                        }
                    }
                }
            }
            Box(
                Modifier
                    .size(edit)
                    .clip(CircleShape)
                    .clickable(onClickLabel = editLabel, role = Role.Button) { tap(); env.onEditLaunchBar() }
                    .semantics { contentDescription = editLabel },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Edit, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(22.dp))
            }
        }
    }
}
