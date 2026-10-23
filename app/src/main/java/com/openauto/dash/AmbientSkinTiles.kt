package com.openauto.dash

import com.openauto.dash.vehicle.rememberReadings
import com.openauto.dash.vehicle.K
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Date
import java.util.Locale
import kotlin.math.min

/*
 * Ambient skin, the tiles (the light itself is in AmbientSkin.kt): no boxes,
 * a few words floating in the light, big and thin where they matter. The
 * speed is a huge ultra-thin numeral over tracked KM/H; the next turn is
 * "Left in 300 m" with the distance in the warm ink and the street under
 * it, its arrow in a glowing disc on the turn's side; the clock a small
 * "21:07 · 14°"; music the song and who sings it at the corner; weather,
 * engine and range a few plain lines. Other widgets get a faint veil.
 */

private const val DOT = " · "

/** Where the turn's words take the distance, in the skin's sentence strings. */
private const val MARK = "\u0001"

/** Corners of the veil and of a tile's outline while arranging. */
private val AM_CORNER = 26.dp

/**
 * Ambient renderer for app shortcuts, launch bars and the main widgets.
 * Anything else keeps its standard renderer.
 */
@Composable
internal fun AmbientTile(item: DashboardItem, env: SkinTileEnv) {
    when (item) {
        is DashboardItem.AppShortcut -> AmApp(item, env)
        is DashboardItem.LaunchBar -> AmLaunchBar(item, env)
        is DashboardItem.BuiltinWidget -> when (item.kind) {
            BuiltinKind.TELEMETRY -> AmTelemetry(env)
            BuiltinKind.SPEED_HUD -> AmSpeed(env)
            BuiltinKind.MEDIA -> AmMedia(env)
            BuiltinKind.NAVIGATION -> AmDirections(env)
            BuiltinKind.CLOCK -> AmClock(env)
            BuiltinKind.WEATHER -> AmWeather(env)
            BuiltinKind.RANGE -> AmRange(item, env)
            else -> StandardSkinnedTile(item, env)
        }
        else -> StandardSkinnedTile(item, env)
    }
}

/**
 * The faint veil behind a widget Ambient does not redraw: a breath of white
 * at night (of the page by day) and a hairline, so its words have something
 * under them and the light still shows through.
 */
@Composable
internal fun Modifier.ambientFallbackPanel(): Modifier {
    val shape = skinChrome().shapes.medium
    val plain = DashColors.Effects == DashEffects.NONE
    val fill = when {
        plain -> DashColors.Card
        DashColors.Light -> Color.White.copy(alpha = 0.55f)
        else -> Color.White.copy(alpha = 0.05f)
    }
    return clip(shape).background(fill, shape).border(1.dp, DashColors.Line, shape)
}

// --- Small pieces -----------------------------------------------------------------

/** While arranging, a bare tile shows its bounds as a dashed outline, so it reads as movable. */
@Composable
private fun Modifier.amEditing(editing: Boolean): Modifier {
    if (!editing) return this
    val ink = DashColors.TextSecondary
    return cachedDraw(ink) {
        val r = CornerRadius(AM_CORNER.toPx())
        val stroke = Stroke(1.5f * density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f * density, 7f * density)))
        val half = stroke.width / 2f
        onDrawBehind {
            drawRoundRect(ink.copy(alpha = 0.55f), Offset(half, half), Size(size.width - stroke.width, size.height - stroke.width), r, style = stroke)
        }
    }
}

/** Whole tile tappable with the tap feedback, or nothing when [enabled] is false (arranging, nothing to do). */
@Composable
private fun Modifier.amTap(enabled: Boolean, label: String, onClick: () -> Unit): Modifier {
    if (!enabled) return this
    val tap = rememberTapFeedback()
    return clip(RoundedCornerShape(AM_CORNER)).clickable(onClickLabel = label, role = Role.Button) { tap(); onClick() }
}

/** Largest size (dp, for [amText]) within [minSize]..[maxSize] at which [sample] fits [maxW] x [maxH] on one line. */
@Composable
private fun amFit(sample: String, weight: FontWeight, maxW: Dp, maxH: Dp, minSize: Float, maxSize: Float, figure: Boolean = false): Float {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = amText(100f, weight = weight, figure = figure)
    return remember(sample, style, maxW, maxH, minSize, maxSize, density) {
        val probe = measurer.measure(sample, style.copy(fontSize = (100f / density.fontScale).sp), softWrap = false, maxLines = 1)
        val byW = with(density) { maxW.toPx() } * 100f / probe.size.width.coerceAtLeast(1)
        val byH = with(density) { maxH.toPx() } * 100f / probe.size.height.coerceAtLeast(1)
        min(byW, byH).coerceIn(minSize, maxSize.coerceAtLeast(minSize))
    }
}

/** Digits replaced by '8' so a readout keeps one size while its value changes. */
private fun template(text: String): String = text.map { if (it.isDigit()) '8' else it }.joinToString("")

/** Roboto ships Thin and Light: really thin when big, light when smaller, so a stroke never gets too fine to read. */
private fun heroWeight(size: Float): FontWeight = if (size >= 150f) FontWeight.Thin else FontWeight.Light

/**
 * A quiet state in plain words: a title and one hint, the hint in the warm
 * ink when a tap does something, and the whole tile is the target.
 */
@Composable
private fun AmQuiet(title: String, hint: String, editing: Boolean, onTap: (() -> Unit)?) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer()
            .amEditing(editing)
            .amTap(onTap != null && !editing, hint) { onTap?.invoke() },
        contentAlignment = Alignment.Center
    ) {
        val titleSize = min(maxHeight.value * 0.14f, maxWidth.value / (title.length * 0.5f + 1f)).coerceIn(18f, 32f)
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AmLine(title, amText(titleSize, DashColors.TextPrimary, FontWeight.Light), maxLines = 2, align = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            AmLine(hint, amText((titleSize * 0.66f).coerceIn(15f, 20f), if (onTap != null) amWarmInk else DashColors.TextSecondary), maxLines = 2, align = TextAlign.Center)
        }
    }
}

/** A pill outlined in light, at least 56 dp tall, for the one thing to do on a quiet tile. */
@Composable
private fun AmTextButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .heightIn(min = DashSize.TouchPrimary)
            .clip(shape)
            .border(1.dp, DashColors.TextSecondary.copy(alpha = 0.5f), shape)
            .clickable(enabled = enabled, role = Role.Button) { tap(); onClick() }
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center
    ) {
        AmLine(text, amText(18f, DashColors.TextPrimary, FontWeight.Normal))
    }
}

// --- Speed --------------------------------------------------------------------------

/**
 * Speed: a huge ultra-thin numeral glowing softly at night, the unit under
 * it in tracked capitals and the road's limit in a thin red ring beside the
 * unit when known. Over the limit the numeral turns amber. Without a speed,
 * a dash; a tap connects the adapter while it is idle.
 */
@Composable
private fun AmSpeed(env: SkinTileEnv) {
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
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .amEditing(env.editing)
            .amTap(canConnect, stringResource(R.string.horizon_connect_obd), env.onConnectObd)
            .semantics(mergeDescendants = true) { contentDescription = said },
        contentAlignment = Alignment.Center
    ) {
        val unitSize = (maxHeight.value * 0.055f).coerceIn(14f, 22f)
        val ring = (unitSize * 2.2f).dp
        val numH = maxHeight - (unitSize * 1.6f).dp - 16.dp
        val numSize = amFit("188", FontWeight.Thin, maxWidth * 0.86f, numH, 40f, 320f, figure = true)
        val glowInk = if (over) DashColors.Warning else Color(0xFFAFC3FF)
        val glow = if (DashColors.Light) null else softTextShadow(glowInk.copy(alpha = 0.45f), (numSize * 0.16f).coerceIn(12f, 40f))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AmLine(
                speed?.let { units.speed(it).toString() } ?: "–",
                amText(if (speed != null) numSize else numSize * 0.6f, color, heroWeight(numSize), figure = true).copy(shadow = glow),
                overflow = TextOverflow.Clip
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                AmLine(units.speedUnit.uppercase(Locale.getDefault()), amText(unitSize, DashColors.TextSecondary, FontWeight.Normal, caps = true))
                limit?.let {
                    Spacer(Modifier.width(14.dp))
                    AmLimit(units.speed(it), ring)
                }
            }
        }
    }
}

/** The road's limit as a thin red ring with the number in it. */
@Composable
private fun AmLimit(limit: Int, d: Dp) {
    val said = stringResource(R.string.ambient_speed_limit_cd, limit)
    val red = DashColors.Critical
    Box(
        modifier = Modifier
            .size(d)
            .semantics { contentDescription = said }
            .cachedDraw(red) {
                val w = 2f * density
                onDrawBehind { drawCircle(red, size.minDimension / 2f - w / 2f, style = Stroke(w)) }
            },
        contentAlignment = Alignment.Center
    ) {
        AmLine(limit.toString(), amText((d.value * if (limit >= 100) 0.32f else 0.4f).coerceAtLeast(12f), DashColors.TextPrimary, FontWeight.Medium), overflow = TextOverflow.Clip)
    }
}

// --- Directions ---------------------------------------------------------------------

/**
 * The next turn: "Left in 300 m" with the distance in the warm ink, the
 * navigation's words under it and the arrival line; the arrow in a softly
 * glowing disc on the turn's side (above the words on a narrow tile). Tap
 * opens the navigation app. Without a route, or without notification access,
 * plain words say so and how to start.
 */
@Composable
private fun AmDirections(env: SkinTileEnv) {
    val nav by NavDirections.state.collectAsState()
    val context = env.context
    val canTap = !env.editing
    when {
        !env.hasMediaAccess -> AmQuiet(
            stringResource(R.string.info_directions_access_title), stringResource(R.string.horizon_tap_allow_notification_access), env.editing,
            if (canTap) ({ CarMediaController.openNotificationAccessSettings(context) }) else null
        )
        !nav.active -> AmQuiet(
            stringResource(R.string.info_directions_no_route), stringResource(R.string.horizon_start_navigation), env.editing,
            if (canTap) ({ openNavigationApp(context, nav) }) else null
        )
        else -> AmTurn(nav, env)
    }
}

@Composable
private fun AmTurn(nav: NavState, env: SkinTileEnv) {
    val context = env.context
    val side = nav.turnSide()
    val (value, unit) = nav.distanceParts
    val distance = if (unit.isEmpty()) value else "$value $unit"
    val pattern = stringResource(
        when (side) {
            TurnSide.LEFT -> R.string.ambient_turn_left
            TurnSide.RIGHT -> R.string.ambient_turn_right
            TurnSide.AHEAD -> R.string.ambient_turn_in
        },
        MARK
    )
    val warm = amWarmInk
    val headline = remember(pattern, distance, nav.instruction, warm) {
        buildAnnotatedString {
            if (distance.isEmpty()) {
                append(nav.instruction)
            } else {
                append(pattern.substringBefore(MARK))
                withStyle(SpanStyle(color = warm, fontWeight = FontWeight.SemiBold)) { append(distance) }
                append(pattern.substringAfter(MARK, ""))
            }
        }
    }
    val street = if (distance.isEmpty()) "" else nav.instruction
    val eta = remember(nav.eta) { nav.etaParts.joinToString(DOT) }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .amEditing(env.editing)
            .amTap(!env.editing, stringResource(R.string.horizon_open_navigation)) { openNavigationApp(context, nav) }
    ) {
        val disc = min(maxHeight.value * 0.44f, maxWidth.value * 0.2f).coerceIn(56f, 130f).dp
        // The disc's glow reaches 0.35 of its size past it: the disc keeps that far from the tile's edge, or the glow is cut square.
        val inset = disc * 0.36f
        val beside = maxWidth >= (disc + inset) * 2 + 280.dp
        val textW = if (beside) maxWidth - (disc + inset + 20.dp) * 2 else maxWidth - 16.dp
        val streetSize = min(maxHeight.value * 0.085f, maxWidth.value / 26f).coerceIn(16f, 28f)
        val etaSize = (streetSize * 0.72f).coerceIn(14f, 20f)
        val wordsH = (if (beside) maxHeight else maxHeight - disc - 12.dp) -
            ((if (street.isNotEmpty()) streetSize * 1.3f else 0f) + (if (eta.isNotEmpty()) etaSize * 1.3f else 0f) + 16f).dp
        val sample = if (distance.isEmpty()) "Left in 888 m" else pattern.replace(MARK, template(distance))
        val headSize = amFit(sample, FontWeight.Normal, textW, wordsH, 20f, 60f)
        val words: @Composable () -> Unit = {
            Column(Modifier.widthIn(max = textW), horizontalAlignment = Alignment.CenterHorizontally) {
                AmLine(headline, amText(headSize, DashColors.TextPrimary, FontWeight.Normal), maxLines = if (distance.isEmpty()) 2 else 1, align = TextAlign.Center)
                if (street.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    AmLine(street, amText(streetSize, DashColors.TextSecondary, FontWeight.Light), align = TextAlign.Center)
                }
                if (eta.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    AmLine(eta, amText(etaSize, DashColors.TextSecondary, FontWeight.Light), align = TextAlign.Center)
                }
            }
        }
        if (beside) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.align(Alignment.Center)) { words() }
                AmTurnDisc(nav, side, disc, Modifier.align(if (side == TurnSide.RIGHT) Alignment.CenterEnd else Alignment.CenterStart).padding(horizontal = inset))
            }
        } else {
            Column(
                Modifier.fillMaxSize().padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                AmTurnDisc(nav, side, disc, Modifier)
                Spacer(Modifier.height(12.dp))
                words()
            }
        }
    }
}

/**
 * The turn's arrow in a disc of warm light: a glow around it at night, a
 * faint fill and a thin rim. The navigation's own glyph when it gives one,
 * else the design's arrow drawn for the side.
 */
@Composable
private fun AmTurnDisc(nav: NavState, side: TurnSide, size: Dp, modifier: Modifier) {
    val warm = DashColors.Accent
    val ink = DashColors.TextPrimary
    val glowK = amGlow
    val light = DashColors.Light
    val bitmap = remember(nav.icon) { nav.icon?.asImageBitmap() }
    Box(
        modifier
            .size(size)
            .semantics { contentDescription = nav.instruction }
            .cachedDraw(warm, glowK, light) {
                val r = this.size.minDimension / 2f
                val c = Offset(this.size.width / 2f, this.size.height / 2f)
                val glow = Brush.radialGradient(
                    0f to warm.copy(alpha = 0.5f * glowK), 0.6f to warm.copy(alpha = 0.22f * glowK), 1f to Color.Transparent,
                    center = c, radius = r * 1.7f
                )
                val fill = warm.copy(alpha = if (light) 0.16f else 0.18f)
                val rim = warm.copy(alpha = if (light) 0.55f else 0.42f)
                val stroke = Stroke(1f * density)
                onDrawBehind {
                    if (glowK > 0f) drawCircle(glow, r * 1.7f, c)
                    drawCircle(fill, r, c)
                    drawCircle(rim, r - stroke.width / 2f, c, style = stroke)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, contentScale = ContentScale.Fit, colorFilter = ColorFilter.tint(ink), modifier = Modifier.fillMaxSize(0.52f))
        } else {
            Spacer(
                Modifier
                    .fillMaxSize(0.5f)
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
                                    val m = side == TurnSide.LEFT
                                    fun x(v: Float) = (if (m) 24f - v else v) * s
                                    moveTo(x(7f), 21f * s); lineTo(x(7f), 11f * s)
                                    cubicTo(x(7f), 8.8f * s, x(8.8f), 7f * s, x(11f), 7f * s)
                                    lineTo(x(19f), 7f * s)
                                    moveTo(x(15f), 3f * s); lineTo(x(19f), 7f * s); lineTo(x(15f), 11f * s)
                                }
                            }
                        }
                        val stroke = Stroke(2.2f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        onDrawBehind { drawPath(path, ink, style = stroke) }
                    }
            )
        }
    }
}

// --- Media --------------------------------------------------------------------------

/**
 * Music as plain words at the tile's lower right: the song, who sings it,
 * and with the room three thin keys under them (play / pause ringed in
 * light). A low tile is the words alone, and a tap on it plays or pauses.
 */
@Composable
private fun AmMedia(env: SkinTileEnv) {
    val ms = env.mediaState
    val context = env.context
    if (!env.hasMediaAccess) {
        AmQuiet(
            stringResource(R.string.info_media_access_needed), stringResource(R.string.horizon_tap_allow_notification_access), env.editing,
            if (!env.editing) ({ CarMediaController.openNotificationAccessSettings(context) }) else null
        )
        return
    }
    val hasTrack = ms.hasMedia && ms.title.isNotBlank()
    val title = if (hasTrack) ms.title else stringResource(R.string.info_nothing_playing)
    val sub = if (hasTrack) ms.artist.ifBlank { stringResource(R.string.horizon_unknown_artist) } else stringResource(R.string.horizon_play_something)
    val playLabel = stringResource(if (ms.isPlaying) R.string.horizon_cd_pause else R.string.horizon_cd_play)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .amEditing(env.editing),
        contentAlignment = Alignment.BottomEnd
    ) {
        val keys = maxHeight >= 150.dp && maxWidth >= 200.dp
        val titleSize = min(maxWidth.value / 12f, maxHeight.value * 0.14f).coerceIn(18f, 28f)
        val subSize = (titleSize * 0.76f).coerceIn(15f, 22f)
        Column(
            Modifier
                .then(if (keys) Modifier else Modifier.fillMaxSize().amTap(!env.editing, playLabel) { env.mediaController.playPause() })
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.Bottom
        ) {
            AmLine(title, amText(titleSize, if (hasTrack) DashColors.TextPrimary else DashColors.TextSecondary, FontWeight.Medium), maxLines = 2, align = TextAlign.End)
            Spacer(Modifier.height(2.dp))
            AmLine(sub, amText(subSize, DashColors.TextSecondary, FontWeight.Light), maxLines = if (hasTrack) 1 else 2, align = TextAlign.End)
            if (keys) {
                Spacer(Modifier.height(10.dp))
                AmKeys(env)
            }
        }
    }
}

@Composable
private fun AmKeys(env: SkinTileEnv) {
    val playing = env.mediaState.isPlaying
    val enabled = !env.editing
    val c = env.mediaController
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        AmKey(Icons.Outlined.SkipPrevious, stringResource(R.string.horizon_cd_previous_track), false, enabled) { c.previous() }
        AmKey(
            if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
            stringResource(if (playing) R.string.horizon_cd_pause else R.string.horizon_cd_play), true, enabled
        ) { c.playPause() }
        AmKey(Icons.Outlined.SkipNext, stringResource(R.string.horizon_cd_next_track), false, enabled) { c.next() }
    }
}

/** A round transport key with no plate; [main] gets a thin ring of light and a soft glow. */
@Composable
private fun AmKey(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, main: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    val rim = DashColors.TextSecondary
    val glowInk = DashColors.Accent2
    val glowK = amGlow
    Box(
        modifier = Modifier
            .size(56.dp)
            .then(
                if (main) Modifier.cachedDraw(rim, glowInk, glowK) {
                    val r = size.minDimension / 2f
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val glow = Brush.radialGradient(listOf(glowInk.copy(alpha = 0.35f * glowK), Color.Transparent), c, r * 1.5f)
                    val stroke = Stroke(1f * density)
                    onDrawBehind {
                        if (glowK > 0f) drawCircle(glow, r * 1.5f, c)
                        drawCircle(rim.copy(alpha = 0.55f), r - stroke.width, c, style = stroke)
                    }
                } else Modifier
            )
            .clip(CircleShape)
            .clickable(enabled = enabled, onClickLabel = label, role = Role.Button) { tap(); onClick() }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = if (main) DashColors.TextPrimary else DashColors.TextSecondary, modifier = Modifier.size(28.dp))
    }
}

// --- Clock and weather ----------------------------------------------------------------

/**
 * Clock: small at the top left, "21:07 · 14°" (the outside temperature
 * when known), the date under it on a taller tile. Tap opens the alarms.
 */
@Composable
private fun AmClock(env: SkinTileEnv) {
    val now = rememberNow(60_000L)
    val locale = Locale.getDefault()
    val units = LocalUnits.current
    val timeFmt = rememberDateFormat(units.digitsPattern())
    val dateFmt = rememberDateFormat("EEEEdMMMM", best = true)
    val temp = rememberOutsideTempC()
    val context = env.context
    val ink = DashColors.TextPrimary
    val soft = DashColors.TextSecondary
    val time = timeFmt.format(now) + (units.amPm(now, locale)?.let { " $it" } ?: "")
    val line = remember(time, temp, units, ink, soft) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = ink, fontWeight = FontWeight.Medium)) { append(time) }
            if (temp != null) withStyle(SpanStyle(color = soft, fontWeight = FontWeight.Light)) { append(DOT + "${units.temp(temp)}°") }
        }
    }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .amEditing(env.editing)
            .amTap(!env.editing, stringResource(R.string.horizon_open_clock)) { openClockApp(context) },
        contentAlignment = Alignment.TopStart
    ) {
        val size = min(maxHeight.value * 0.16f, maxWidth.value / 9f).coerceIn(18f, 32f)
        val withDate = maxHeight >= 200.dp
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            AmLine(line, amText(size, ink, FontWeight.Light))
            if (withDate) {
                AmLine(dateFmt.format(now).replaceFirstChar { it.titlecase(locale) }, amText((size * 0.62f).coerceAtLeast(14f), soft, FontWeight.Light))
            }
        }
    }
}

/**
 * Weather as a few plain lines: the temperature large and thin, the sky in
 * words, the feel and the wind, rain to come in the warm ink. While there is
 * nothing yet, it says why (no GPS, offline).
 */
@Composable
private fun AmWeather(env: SkinTileEnv) {
    val w = rememberWeather()
    val wait = rememberWeatherWait()
    val units = LocalUnits.current
    val timeFmt = rememberTimeFormat()
    val nowMs = rememberWallClock(60_000L).longValue
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer().amEditing(env.editing), contentAlignment = Alignment.CenterStart) {
        val tempSize = min(maxHeight.value * 0.32f, maxWidth.value * 0.26f).coerceIn(26f, 88f)
        val lineSize = (tempSize * 0.28f).coerceIn(15f, 22f)
        val roomy = maxHeight >= 150.dp
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            if (w == null) {
                val why = when (wait) {
                    WeatherWait.LOADING -> R.string.horizon_loading
                    WeatherWait.NO_GPS -> R.string.info_waiting_gps
                    WeatherWait.UNAVAILABLE -> R.string.info_weather_unavailable
                }
                AmLine("--°", amText(tempSize, DashColors.Muted, heroWeight(tempSize)), overflow = TextOverflow.Clip)
                AmLine(stringResource(why), amText(lineSize, DashColors.TextSecondary), maxLines = 2)
                return@Column
            }
            val rain = when {
                w.rainFromMs != null && w.rainUntilMs != null && w.rainFromMs <= nowMs -> stringResource(R.string.canvas_rain_until, timeFmt.format(Date(w.rainUntilMs)))
                w.rainFromMs != null -> stringResource(R.string.canvas_rain_from, timeFmt.format(Date(w.rainFromMs)))
                else -> null
            }
            AmLine("${units.temp(w.tempC)}°", amText(tempSize, DashColors.TextPrimary, heroWeight(tempSize)), overflow = TextOverflow.Clip)
            AmLine(w.condition, amText(lineSize, DashColors.TextSecondary))
            if (roomy) {
                val feels = stringResource(R.string.horizon_feels, units.temp(w.feelsC))
                val wind = stringResource(if (units.imperial) R.string.units_horizon_wind_mph else R.string.horizon_wind, units.speed(w.windKmh))
                AmLine(feels + DOT + wind, amText(lineSize, DashColors.TextSecondary))
            }
            if (rain != null) AmLine(rain, amText(lineSize, amWarmInk, FontWeight.Normal))
        }
    }
}

// --- Engine and range -----------------------------------------------------------------

/**
 * Engine readings as plain lines: the revs large and thin, then the
 * coolant, the battery and the load as the room allows, amber when out of
 * range. Without the link, its state and the button to connect.
 */
@Composable
private fun AmTelemetry(env: SkinTileEnv) {
    val state = env.obdConnection
    val units = LocalUnits.current
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer().amEditing(env.editing), contentAlignment = Alignment.CenterStart) {
        val bigSize = min(maxHeight.value * 0.26f, maxWidth.value * 0.18f).coerceIn(24f, 72f)
        val lineSize = (bigSize * 0.34f).coerceIn(15f, 22f)
        val lines = ((maxHeight.value - bigSize * 1.1f - 20f) / (lineSize * 1.3f)).toInt().coerceIn(0, 3)
        val buttons = maxHeight >= 110.dp
        val twoButtons = maxWidth >= 420.dp
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            when (state) {
                ObdConnectionState.CONNECTED -> {
                    val d = rememberReadings(K.RPM, K.COOLANT_TEMP, K.ENGINE_LOAD, K.BATTERY_VOLTS)
                    Row {
                        AmLine(groupThousands(d.rpm), amText(bigSize, DashColors.TextPrimary, heroWeight(bigSize)), Modifier.alignByBaseline(), overflow = TextOverflow.Clip)
                        Spacer(Modifier.width(8.dp))
                        AmLine(stringResource(R.string.ambient_rpm), amText(lineSize, DashColors.TextSecondary), Modifier.alignByBaseline())
                    }
                    val rows = buildList {
                        if (d.coolantTempC > 0) add(stringResource(R.string.ambient_coolant, "${units.temp(d.coolantTempC)}°") to (d.coolantTempC >= COOLANT_WARNING_C))
                        if (d.voltage > 0.0) add(stringResource(R.string.ambient_battery, String.format(Locale.getDefault(), "%.1f V", d.voltage)) to (d.voltage !in BATTERY_OK_V))
                        add(stringResource(R.string.ambient_load, "${d.engineLoadPct} %") to false)
                    }
                    rows.take(lines).forEach { (text, warn) ->
                        AmLine(text, amText(lineSize, if (warn) DashColors.Warning else DashColors.TextSecondary, if (warn) FontWeight.Medium else FontWeight.Light))
                    }
                }
                else -> {
                    AmLine(obdStatusLabel(state), amText(lineSize * 1.2f, if (state == ObdConnectionState.CONNECTING) amWarmInk else DashColors.TextSecondary), maxLines = 2)
                    if (state != ObdConnectionState.CONNECTING && buttons) {
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            AmTextButton(stringResource(R.string.horizon_connect_obd), !env.editing, env.onConnectObd)
                            if (state == ObdConnectionState.ERROR && twoButtons) {
                                AmTextButton(stringResource(R.string.tape_cd_choose_adapter), !env.editing, env.onPickDevice)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Range as plain lines: the distance left large and thin, the fuel's share
 * under it, amber in the reserve. Fuel not learned yet: the standard tile on
 * the veil, which explains how to learn it.
 */
@Composable
private fun AmRange(item: DashboardItem, env: SkinTileEnv) {
    val fuel = rememberFuel()
    if (fuel == null) {
        Box(Modifier.fillMaxSize().ambientFallbackPanel()) { StandardSkinnedTile(item, env) }
        return
    }
    val units = LocalUnits.current
    val low = fuel.percent <= SKIN_LOW_FUEL_PCT
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer().amEditing(env.editing), contentAlignment = Alignment.CenterStart) {
        val numSize = min(maxHeight.value * 0.36f, maxWidth.value * 0.22f).coerceIn(26f, 96f)
        val lineSize = (numSize * 0.28f).coerceIn(15f, 22f)
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row {
                AmLine(
                    units.distance(fuel.rangeKm).toString(),
                    amText(numSize, if (low) DashColors.Warning else DashColors.TextPrimary, heroWeight(numSize)),
                    Modifier.alignByBaseline(), overflow = TextOverflow.Clip
                )
                Spacer(Modifier.width(8.dp))
                AmLine(units.distanceUnit, amText(lineSize, DashColors.TextSecondary), Modifier.alignByBaseline())
            }
            AmLine(
                stringResource(R.string.ambient_fuel, fuel.percent),
                amText(lineSize, if (low) DashColors.Warning else DashColors.TextSecondary, if (low) FontWeight.Medium else FontWeight.Light)
            )
        }
    }
}

// --- Apps ---------------------------------------------------------------------------

/** App shortcut: the app's icon in a soft pool of light, its name under it (beside it on a wide low tile). */
@Composable
private fun AmApp(item: DashboardItem.AppShortcut, env: SkinTileEnv) {
    val app = env.appsByPackage[item.packageName]
    val label = appLabel(app, item.packageName)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .amEditing(env.editing)
            .amTap(!env.editing, stringResource(R.string.horizon_open_app, label)) { env.onLaunchApp(item.packageName) }
            .semantics(mergeDescendants = true) { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        val row = maxWidth >= maxHeight * 1.6f && maxWidth >= 180.dp
        if (row) {
            val icon = (maxHeight.value * 0.5f).coerceIn(30f, 72f).dp
            Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                AmAppGlyph(app, icon)
                Spacer(Modifier.width(14.dp))
                AmLine(label, amText((icon.value * 0.38f).coerceIn(16f, 24f), DashColors.TextPrimary, FontWeight.Normal), Modifier.weight(1f, fill = false))
            }
        } else {
            val icon = (min(maxWidth.value, maxHeight.value) * 0.44f).coerceIn(30f, 96f).dp
            val named = maxHeight >= 96.dp
            Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                AmAppGlyph(app, icon)
                if (named) {
                    Spacer(Modifier.height(8.dp))
                    AmLine(label, amText((icon.value * 0.28f).coerceIn(14f, 20f), DashColors.TextPrimary, FontWeight.Normal), align = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun AmAppGlyph(app: AppEntry?, size: Dp) {
    Box(Modifier.size(size).glowHalo(DashColors.Accent2.copy(alpha = if (DashColors.Light) 0.18f else 0.32f), 0.85f), contentAlignment = Alignment.Center) {
        if (app != null) AppIcon(icon = app.icon, size = size)
        else Icon(Icons.Outlined.Apps, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(size * 0.8f))
    }
}

/**
 * Launch bar: the apps' icons bare in a row, each in its pool of light with
 * its name beside it where there is room, and a thin pencil at the end.
 * Apps that would get less than a 64 dp key are left out.
 */
@Composable
private fun AmLaunchBar(item: DashboardItem.LaunchBar, env: SkinTileEnv) {
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer().amEditing(env.editing)) {
        val gap = 10.dp
        val edit = 56.dp
        val room = maxWidth - edit - gap
        val tall = maxHeight >= 150.dp
        val fit = ((room + gap).value / (64f + gap.value)).toInt().coerceAtLeast(1)
        val pkgs = item.packages.take(fit)
        val per = if (pkgs.isEmpty()) 0f else (room.value - gap.value * (pkgs.size - 1)) / pkgs.size
        val labels = if (tall) per >= 90f else per >= 150f
        val icon = if (tall) (maxHeight.value * 0.34f).coerceIn(28f, 64f).dp else (maxHeight.value * 0.5f).coerceIn(24f, 48f).dp
        val labelSize = if (tall) (icon.value * 0.3f).coerceIn(14f, 19f) else (maxHeight.value * 0.2f).coerceIn(14f, 19f)
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
            if (pkgs.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                    AmLine(stringResource(R.string.ambient_launch_bar_empty), amText(labelSize, DashColors.TextSecondary), maxLines = 2)
                }
            } else {
                pkgs.forEach { pkg ->
                    val app = env.appsByPackage[pkg]
                    val name = appLabel(app, pkg)
                    val base = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .widthIn(min = 64.dp)
                        .amTap(!env.editing, stringResource(R.string.horizon_open_app, name)) { env.onLaunchApp(pkg) }
                        .semantics(mergeDescendants = true) { contentDescription = name }
                        .padding(horizontal = 8.dp)
                    if (tall) {
                        Column(base, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            AmAppGlyph(app, icon)
                            if (labels) {
                                Spacer(Modifier.height(8.dp))
                                AmLine(name, amText(labelSize, DashColors.TextPrimary, FontWeight.Normal), align = TextAlign.Center)
                            }
                        }
                    } else {
                        Row(base, horizontalArrangement = if (labels) Arrangement.Start else Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            AmAppGlyph(app, icon)
                            if (labels) {
                                Spacer(Modifier.width(10.dp))
                                AmLine(name, amText(labelSize, DashColors.TextPrimary, FontWeight.Normal), Modifier.weight(1f, fill = false))
                            }
                        }
                    }
                }
            }
            val tap = rememberTapFeedback()
            val editLabel = stringResource(R.string.horizon_cd_edit_launch_bar)
            Box(
                Modifier
                    .size(edit)
                    .clip(CircleShape)
                    .clickable(onClickLabel = editLabel, role = Role.Button) { tap(); env.onEditLaunchBar() }
                    .semantics { contentDescription = editLabel },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.Edit, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(22.dp))
            }
        }
    }
}
