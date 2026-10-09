package com.openauto.dash

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
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Calendar
import java.util.Locale
import kotlin.math.min

/*
 * The Anime skin's tiles: every one a sticker (or a burst, or a speech
 * bubble) that grows with its tile, from a 3 x 2 cell to the whole screen,
 * across or upright. Text is sized from the tile and kept to its lines.
 */

/** Room round every tile's sticker for its lean and its hard shadow. */
private val TILE_PAD = 8.dp

/** Lean of the chips, in turn, like stickers slapped on in a hurry. */
private val ChipTilts = floatArrayOf(-4f, 3f, -2f, 2.5f, -3f)

/** A whole tile that does something on tap (with the tap feedback), or nothing when [enabled] is false. */
@Composable
private fun Modifier.animeTap(enabled: Boolean, label: String, onClick: () -> Unit): Modifier {
    val tap = rememberTapFeedback()
    if (!enabled) return this
    return clip(RoundedCornerShape(22.dp)).clickable(onClickLabel = label, role = Role.Button) {
        tap()
        onClick()
    }
}

/**
 * Anime renderer for app shortcuts, launch bars and the main widgets. Anything
 * else (and a state the skin does not redraw) keeps its standard renderer.
 */
@Composable
internal fun AnimeTile(item: DashboardItem, env: SkinTileEnv) {
    when (item) {
        is DashboardItem.AppShortcut -> AnimeApp(item, env)
        is DashboardItem.LaunchBar -> AnimeLaunchBar(item, env)
        is DashboardItem.BuiltinWidget -> when (item.kind) {
            BuiltinKind.TELEMETRY -> AnimeTelemetry(env)
            BuiltinKind.SPEED_HUD -> AnimeSpeed(env)
            BuiltinKind.MEDIA -> AnimeMedia(env)
            BuiltinKind.NAVIGATION -> AnimeDirections(env)
            BuiltinKind.CLOCK -> AnimeClock(env)
            BuiltinKind.WEATHER -> AnimeWeather()
            BuiltinKind.RANGE -> AnimeRange(item, env)
            else -> StandardSkinnedTile(item, env)
        }
        else -> StandardSkinnedTile(item, env)
    }
}

// --- Quiet states -----------------------------------------------------------------

/**
 * A state with nothing to show yet: a paper sticker with a comic title and
 * one line under it, the whole sticker tappable when [onTap] does something.
 * With [car] and room enough, the car waits parked above it.
 */
@Composable
private fun AnimeEmptySticker(title: String, hint: String, onTap: (() -> Unit)?, car: Boolean = false) {
    val t = animeTones
    BoxWithConstraints(Modifier.fillMaxSize().padding(TILE_PAD)) {
        val showCar = car && maxHeight >= 260.dp && maxWidth >= 260.dp
        Column {
            if (showCar) {
                AnimeCarScene(moving = false, modifier = Modifier.fillMaxWidth().weight(0.45f))
                Spacer(Modifier.height(8.dp))
            }
            BoxWithConstraints(Modifier.fillMaxWidth().weight(if (showCar) 0.55f else 1f)) {
                AnimeSticker(
                    Modifier.fillMaxSize(),
                    fill = t.paper,
                    corner = 22.dp,
                    rotation = animeTilt(-1.5f, maxWidth, maxHeight),
                    onClick = onTap,
                    clickLabel = hint
                ) {
                    BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
                        val titleSize = min(maxHeight.value * 0.24f, maxWidth.value / (title.length.coerceAtLeast(4) * 0.42f)).coerceIn(15f, 64f)
                        val hintSize = (titleSize * 0.38f).coerceIn(12f, 20f)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            AnimeText(title.uppercase(), animeComic(titleSize, t.onPaper), maxLines = 2, align = TextAlign.Center)
                            Spacer(Modifier.height(4.dp))
                            AnimeText(
                                hint,
                                animeRound(hintSize, if (onTap != null) t.onPaper else t.subOnPaper, if (onTap != null) FontWeight.Black else FontWeight.Bold),
                                maxLines = 2,
                                align = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}

// --- Chips ------------------------------------------------------------------------

/** One car-state chip: a big value, a small word under it, its fill; [pop] makes it pulse (an alert). */
private class AnimeChipSpec(val value: String, val caption: String, val fill: Color, val pop: Boolean = false)

/**
 * Chips laid out as big as the box allows at about 3:2, in the column
 * count that gives them the most room; every chip shares one value size,
 * so the row reads as a set.
 */
@Composable
private fun AnimeChipGrid(chips: List<AnimeChipSpec>, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val n = chips.size.coerceAtLeast(1)
        val gap = 12f
        var cols = 1
        var best = -1f
        for (c in 1..n) {
            val rows = (n + c - 1) / c
            val cw = (maxWidth.value - gap * (c - 1)) / c
            val ch = (maxHeight.value - gap * (rows - 1)) / rows
            val score = min(cw / 1.5f, ch)
            if (score > best) {
                best = score
                cols = c
            }
        }
        val rows = (n + cols - 1) / cols
        val cellW = (maxWidth.value - gap * (cols - 1)) / cols
        val cellH = (maxHeight.value - gap * (rows - 1)) / rows
        val chipW = min(cellW, cellH * 2.4f).coerceAtLeast(40f)
        val chipH = min(cellH, chipW * 0.95f).coerceAtLeast(40f)
        // Inside: less the shadow (5), the border (4 a side) and a little air.
        val innerW = (chipW - 5f - 8f - 10f).coerceAtLeast(16f).dp
        val innerH = (chipH - 5f - 8f - 6f).coerceAtLeast(16f).dp
        val probe = animeComic(100f, Color.Black)
        val valueSize = chips.minOf { animeFit(animeTemplate(it.value), probe, innerW, innerH * 0.6f, 10f, 140f) }
        val capSize = min((valueSize * 0.36f).coerceIn(10f, 22f), innerH.value * 0.26f).coerceAtLeast(9f)
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(gap.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            for (r in 0 until rows) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap.dp, Alignment.CenterHorizontally)) {
                    for (c in 0 until cols) {
                        val i = r * cols + c
                        if (i < chips.size) AnimeChip(chips[i], i, chipW.dp, chipH.dp, valueSize, capSize)
                    }
                }
            }
        }
    }
}

@Composable
private fun AnimeChip(spec: AnimeChipSpec, index: Int, w: Dp, h: Dp, valueSize: Float, capSize: Float) {
    val on = animeOn(spec.fill)
    // An alert chip pulses whatever the effects; the first chip breathes as decoration only.
    val pop = when {
        spec.pop -> rememberLoop(1_200, reverse = true, rest = 0f, status = true)
        index == 0 -> rememberLoop(2_400, reverse = true, rest = 0f)
        else -> null
    }
    AnimeSticker(
        Modifier
            .size(w, h)
            .graphicsLayer {
                val k = 1f + 0.05f * (pop?.value ?: 0f)
                scaleX = k
                scaleY = k
            }
            .semantics(mergeDescendants = true) { contentDescription = "${spec.value} ${spec.caption}" },
        fill = spec.fill,
        corner = 18.dp,
        rotation = animeTilt(ChipTilts[index % ChipTilts.size], w, h)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 5.dp)) {
            AnimeFigure(spec.value, animeComic(valueSize, on))
            AnimeText(spec.caption, animeRound(capSize, on), align = TextAlign.Center)
        }
    }
}

/** Coolant as a chip: its colour turns amber then red at the engine's limits, its word follows the mood. */
@Composable
private fun coolantChip(tempC: Int, units: UnitSystem): AnimeChipSpec {
    val t = animeTones
    val hot = tempC >= COOLANT_WARNING_C
    val fill = when {
        tempC >= COOLANT_CRITICAL_C -> DashColors.Critical
        hot -> DashColors.Warning
        else -> t.blush
    }
    val mood = when {
        hot -> R.string.skin_anime_engine_hot
        tempC < 60 -> R.string.skin_anime_engine_cold
        else -> R.string.skin_anime_engine_happy
    }
    return AnimeChipSpec("${units.temp(tempC)}°", stringResource(mood), fill, pop = hot)
}

// --- Speed ------------------------------------------------------------------------

/**
 * Speed HUD: the comic burst, and on a big tile the car driving beside it
 * (across) or under it (upright). Without a reading the burst says so and a
 * tap connects the OBD adapter.
 */
@Composable
private fun AnimeSpeed(env: SkinTileEnv) {
    val speed = rememberSpeedKmh(env.obdData, env.obdConnection)
    val idle = env.obdConnection.isIdle
    val canConnect = speed == null && idle && !env.editing
    val units = LocalUnits.current
    val noSignal = stringResource(R.string.info_speed_no_signal)
    val said = speed?.let { "${units.speed(it)} ${units.speedUnit}" } ?: noSignal
    val caption = if (speed != null) units.speedUnit.uppercase() else noSignal.uppercase()
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .animeTap(canConnect, stringResource(R.string.horizon_connect_obd), env.onConnectObd)
            .semantics(mergeDescendants = true) { contentDescription = said }
            .padding(TILE_PAD),
        contentAlignment = Alignment.Center
    ) {
        val w = maxWidth
        val h = maxHeight
        val moving = (speed ?: 0) > 2
        val wide = w >= 420.dp && h >= 150.dp && w >= h * 1.55f
        val tall = h >= 340.dp && w >= 240.dp && h >= w * 0.85f
        when {
            wide -> Row(verticalAlignment = Alignment.CenterVertically) {
                val bw = min(h.value * ANIME_BURST_ASPECT, w.value * 0.46f)
                AnimeSpeedBurst(speed, caption, Modifier.size(bw.dp, (bw / ANIME_BURST_ASPECT).dp))
                AnimeCarScene(moving, Modifier.weight(1f).fillMaxHeight(0.8f))
            }
            tall -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val sceneH = h * 0.36f
                val bw = min(w.value, (h - sceneH).value * ANIME_BURST_ASPECT)
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    AnimeSpeedBurst(speed, caption, Modifier.size(bw.dp, (bw / ANIME_BURST_ASPECT).dp))
                }
                AnimeCarScene(moving, Modifier.fillMaxWidth().height(sceneH))
            }
            else -> {
                val bw = min(w.value, h.value * ANIME_BURST_ASPECT)
                AnimeSpeedBurst(speed, caption, Modifier.size(bw.dp, (bw / ANIME_BURST_ASPECT).dp))
            }
        }
    }
}

// --- Telemetry --------------------------------------------------------------------

/**
 * Telemetry: the car driving over a row of chips (speed, revs, engine
 * temperature, battery, and load on a wide tile); small tiles keep the
 * chips. Not connected, the car waits over a sticker that connects.
 */
@Composable
private fun AnimeTelemetry(env: SkinTileEnv) {
    val state = env.obdConnection
    if (state != ObdConnectionState.CONNECTED) {
        val hint = stringResource(
            when (state) {
                ObdConnectionState.CONNECTING -> R.string.horizon_obd_connecting
                ObdConnectionState.ERROR -> R.string.horizon_obd_error_retry
                else -> R.string.horizon_obd_tap_connect
            }
        )
        AnimeEmptySticker(
            stringResource(R.string.dash_obd_short), hint,
            if (state.isIdle && !env.editing) env.onConnectObd else null,
            car = true
        )
        return
    }
    val t = animeTones
    val d = env.obdData
    val units = LocalUnits.current
    val rpmWord = stringResource(R.string.skin_anime_rpm)
    val batteryWord = stringResource(R.string.skin_anime_battery)
    val loadWord = stringResource(R.string.skin_anime_load)
    val coolant = coolantChip(d.coolantTempC, units)
    BoxWithConstraints(Modifier.fillMaxSize().padding(TILE_PAD)) {
        val withCar = maxHeight >= 250.dp && maxWidth >= 300.dp
        val withLoad = maxWidth >= 600.dp
        val chips = buildList {
            add(AnimeChipSpec("${units.speed(d.speedKmh)}", units.speedUnit, t.lemon))
            add(AnimeChipSpec(groupThousands(d.rpm), rpmWord, t.sky))
            add(coolant)
            if (d.voltage > 0.0) {
                val v = d.voltage
                val fill = when (v) {
                    in BATTERY_OK_V -> t.mint
                    in BATTERY_WARNING_V -> DashColors.Warning
                    else -> DashColors.Critical
                }
                add(AnimeChipSpec("%.1f V".format(v), batteryWord, fill, pop = v !in BATTERY_WARNING_V))
            }
            if (withLoad) add(AnimeChipSpec("${d.engineLoadPct}%", loadWord, t.paper))
        }
        if (withCar) {
            Column {
                Box(Modifier.fillMaxWidth().weight(0.42f)) {
                    AnimeCarScene(d.speedKmh > 2, Modifier.fillMaxSize())
                    AnimeLiveTag(env, Modifier.align(Alignment.TopStart))
                }
                Spacer(Modifier.height(10.dp))
                AnimeChipGrid(chips, Modifier.fillMaxWidth().weight(0.58f))
            }
        } else {
            AnimeChipGrid(chips, Modifier.fillMaxSize())
        }
    }
}

/** "Live" with a green dot: tapping it picks another OBD adapter, as the standard tile allows. */
@Composable
private fun AnimeLiveTag(env: SkinTileEnv, modifier: Modifier) {
    val t = animeTones
    val label = stringResource(R.string.tape_cd_choose_adapter)
    AnimeSticker(
        modifier
            .height(52.dp)
            .widthIn(min = 52.dp)
            .semantics(mergeDescendants = true) { contentDescription = label },
        fill = t.paper,
        pill = true,
        shadow = 4.dp,
        border = 3.dp,
        rotation = -3f,
        onClick = if (env.editing) null else env.onPickDevice,
        clickLabel = label
    ) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(DashColors.Good, CircleShape))
            Spacer(Modifier.width(6.dp))
            AnimeText(stringResource(R.string.info_obd_live), animeRound(15f, t.onPaper))
        }
    }
}

// --- Fuel and range ---------------------------------------------------------------

/**
 * Fuel and range: tilted chips (fuel %, distance to go, and the engine's mood
 * when OBD is live and there is room) and, on a tall tile, a fuel strip.
 * Low fuel turns its chip amber and makes it pulse. Unknown fuel falls back
 * to the standard tile, which explains how to learn it.
 */
@Composable
private fun AnimeRange(item: DashboardItem, env: SkinTileEnv) {
    val fuel = rememberFuel(env.obdData, env.obdConnection)
    if (fuel == null) {
        StandardSkinnedTile(item, env)
        return
    }
    val t = animeTones
    val units = LocalUnits.current
    val low = fuel.percent <= SKIN_LOW_FUEL_PCT
    val fuelWord = stringResource(if (low) R.string.skin_anime_fuel_low else R.string.skin_anime_fuel)
    val toGo = stringResource(R.string.skin_anime_to_go)
    val connected = env.obdConnection == ObdConnectionState.CONNECTED
    val coolant = if (connected) coolantChip(env.obdData.coolantTempC, units) else null
    BoxWithConstraints(Modifier.fillMaxSize().padding(TILE_PAD)) {
        val chips = buildList {
            add(AnimeChipSpec((if (fuel.percentEstimated) "≈" else "") + "${fuel.percent}%", fuelWord, if (low) DashColors.Warning else t.mint, pop = low))
            add(AnimeChipSpec("${units.distance(fuel.rangeKm)} ${units.distanceUnit.uppercase()}", toGo, t.sky))
            if (coolant != null && (maxWidth >= 480.dp || maxHeight >= 300.dp)) add(coolant)
        }
        val gauge = maxHeight >= 230.dp
        val stripH = (maxHeight.value * 0.14f).coerceIn(30f, 52f).dp
        Column {
            AnimeChipGrid(chips, Modifier.fillMaxWidth().weight(1f))
            if (gauge) {
                Spacer(Modifier.height(12.dp))
                AnimeFuelStrip(fuel.percent / 100f, low, Modifier.fillMaxWidth().height(stripH))
            }
        }
    }
}

/** A fuel strip as a sticker: ink outline, the level in mint (amber when low), quarter ticks, a hard shadow. */
@Composable
private fun AnimeFuelStrip(fraction: Float, low: Boolean, modifier: Modifier) {
    val t = animeTones
    val level = if (low) DashColors.Warning else t.mint
    Spacer(
        modifier.cachedDraw(fraction, low, t, level) {
            val sh = 4.dp.toPx()
            val bw = 3.5.dp.toPx()
            val body = Size(size.width - sh, size.height - sh)
            val r = CornerRadius(body.height / 2f)
            val clip = Path().apply { addRoundRect(RoundRect(0f, 0f, body.width, body.height, r)) }
            onDrawBehind {
                drawRoundRect(t.ink, Offset(sh, sh), body, r)
                drawRoundRect(t.paper, Offset.Zero, body, r)
                clipPath(clip) {
                    drawRect(level, size = Size(body.width * fraction.coerceIn(0f, 1f), body.height))
                }
                for (q in 1..3) {
                    val x = body.width * q / 4f
                    drawLine(t.ink, Offset(x, body.height * 0.25f), Offset(x, body.height * 0.75f), bw * 0.6f)
                }
                drawRoundRect(t.ink, Offset(bw / 2f, bw / 2f), Size(body.width - bw, body.height - bw), CornerRadius(body.height / 2f - bw / 2f), style = Stroke(bw))
            }
        }
    )
}

// --- Music ------------------------------------------------------------------------

/**
 * Music: a white halftone card leaning a little, a record that spins while
 * playing (the album art as its label), an ink "NOW PLAYING" tag, the title
 * and artist in round black type, a thick progress bar and sticker buttons.
 * Across: record left, words and buttons right; upright: stacked. Short
 * tiles move the buttons beside the words.
 */
@Composable
private fun AnimeMedia(env: SkinTileEnv) {
    val t = animeTones
    val ms = env.mediaState
    val access = env.hasMediaAccess
    val hasTrack = ms.hasMedia && ms.title.isNotBlank()
    val context = env.context
    if (!access) {
        AnimeEmptySticker(
            stringResource(R.string.info_media_access_needed),
            stringResource(R.string.horizon_tap_allow_notification_access),
            if (env.editing) null else ({ CarMediaController.openNotificationAccessSettings(context) })
        )
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize().padding(TILE_PAD)) {
        AnimeSticker(
            Modifier.fillMaxSize(),
            fill = t.paper,
            corner = 22.dp,
            rotation = animeTilt(2f, maxWidth, maxHeight),
            dots = true
        ) {
            BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                val w = maxWidth
                val h = maxHeight
                val upright = h > w * 1.1f && h >= 300.dp
                val short = !upright && h < 150.dp
                val ctrl = (min(h.value * if (upright) 0.13f else 0.27f, 64f)).coerceAtLeast(48f).dp
                // Three round stickers (each ctrl plus its shadow) and two gaps.
                val buttonsW = ctrl * 3.44f + 12.dp
                val gap = 14.dp
                var recordSize: Dp
                var textW: Dp
                var stacked = false
                var onlyPlay = false
                when {
                    upright -> {
                        recordSize = min(w.value * 0.62f, h.value * 0.36f).dp
                        textW = w
                    }
                    short -> {
                        recordSize = min(h.value * 0.86f, w.value * 0.25f).dp
                        val room = w - recordSize - gap
                        onlyPlay = room - buttonsW - 12.dp < 90.dp
                        textW = room - (if (onlyPlay) ctrl + 4.dp else buttonsW) - 12.dp
                    }
                    else -> {
                        recordSize = min(h.value * 0.86f, w.value * 0.3f).dp
                        textW = w - recordSize - gap
                        if (textW < buttonsW) {
                            // No room for the buttons beside the record: they go under both.
                            stacked = true
                            recordSize = min((h - ctrl - 18.dp).value * 0.95f, w.value * 0.3f).dp
                            textW = w - recordSize - gap
                        }
                    }
                }
                textW = textW.coerceAtLeast(40.dp)
                val buttonsInWords = !short && !stacked
                val titleSize = min(h.value * if (upright) 0.07f else 0.15f, textW.value / 9f).coerceIn(15f, 44f)
                val artistSize = (titleSize * 0.66f).coerceIn(12f, 26f)
                val tagSize = (titleSize * 0.46f).coerceIn(11f, 18f)
                val titleLines = if (upright || h >= 260.dp) 2 else 1
                // What the words need; the tag gives way first, then the bar, where they would not fit.
                val wordsRoom = when {
                    upright -> h - recordSize - 12.dp
                    stacked -> h - ctrl - 18.dp
                    else -> h
                }
                val buttonsH = if (buttonsInWords) (ctrl + 4.dp + 10.dp).value else 0f
                val coreH = titleSize * 1.2f * titleLines + artistSize * 1.2f + buttonsH
                val tagH = tagSize * 1.1f + 10f
                val barH = 22f
                val hasBar = ms.durationMs > 0L && !short
                val showTag = coreH + tagH + (if (hasBar) barH else 0f) <= wordsRoom.value
                val showBar = hasBar && coreH + barH <= wordsRoom.value
                val align = if (upright) Alignment.CenterHorizontally else Alignment.Start
                val words: @Composable () -> Unit = {
                    Column(horizontalAlignment = align, modifier = Modifier.width(textW)) {
                        if (showTag) {
                            AnimeText(
                                stringResource(
                                    when {
                                        ms.isPlaying -> R.string.horizon_now_playing_caps
                                        hasTrack -> R.string.horizon_paused_caps
                                        else -> R.string.horizon_music_caps
                                    }
                                ),
                                animeComic(tagSize, t.yellow),
                                Modifier
                                    .graphicsLayer { rotationZ = -2f }
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(t.ink)
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                        AnimeText(
                            if (hasTrack) ms.title else stringResource(R.string.info_nothing_playing),
                            animeRound(titleSize, t.onPaper),
                            maxLines = titleLines,
                            align = if (upright) TextAlign.Center else null
                        )
                        AnimeText(
                            if (hasTrack) ms.artist.ifBlank { stringResource(R.string.horizon_unknown_artist) }
                            else stringResource(R.string.horizon_play_something),
                            animeRound(artistSize, t.subOnPaper, FontWeight.Bold),
                            align = if (upright) TextAlign.Center else null
                        )
                        if (showBar) {
                            Spacer(Modifier.height(8.dp))
                            AnimeProgress(env, Modifier.fillMaxWidth().height(14.dp))
                        }
                        if (buttonsInWords) {
                            Spacer(Modifier.height(10.dp))
                            AnimeMediaButtons(env, ctrl)
                        }
                    }
                }
                when {
                    upright -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        AnimeRecord(ms.isPlaying, ms.artwork, recordSize)
                        Spacer(Modifier.height(12.dp))
                        words()
                    }
                    stacked -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            AnimeRecord(ms.isPlaying, ms.artwork, recordSize)
                            Spacer(Modifier.width(gap))
                            words()
                        }
                        Spacer(Modifier.height(10.dp))
                        AnimeMediaButtons(env, ctrl)
                    }
                    else -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        AnimeRecord(ms.isPlaying, ms.artwork, recordSize)
                        Spacer(Modifier.width(gap))
                        words()
                        if (short) {
                            Spacer(Modifier.weight(1f))
                            AnimeMediaButtons(env, ctrl, onlyPlay)
                        }
                    }
                }
            }
        }
    }
}

/** The progress bar, in its own scope so only it follows the playback position. */
@Composable
private fun AnimeProgress(env: SkinTileEnv, modifier: Modifier) {
    val t = animeTones
    val fraction = rememberMediaFraction(env.mediaState, env.mediaController)
    Spacer(
        modifier.cachedDraw(t, fraction) {
            val bw = 2.5.dp.toPx()
            val r = CornerRadius(size.height / 2f)
            onDrawBehind {
                drawRoundRect(t.blush, cornerRadius = r)
                drawRoundRect(t.pink, size = Size((size.width * fraction).coerceAtLeast(size.height), size.height), cornerRadius = r)
                drawRoundRect(t.ink, Offset(bw / 2f, bw / 2f), Size(size.width - bw, size.height - bw), CornerRadius(size.height / 2f - bw / 2f), style = Stroke(bw))
            }
        }
    )
}

/** Previous, play / pause (pink) and next as round stickers, [size] each (48 dp at least); play / pause alone with [onlyPlay]. */
@Composable
private fun AnimeMediaButtons(env: SkinTileEnv, size: Dp, onlyPlay: Boolean = false) {
    val t = animeTones
    val playing = env.mediaState.isPlaying
    val enabled = !env.editing
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(size * 0.22f)) {
        if (!onlyPlay) {
            AnimeRoundButton(Icons.Filled.SkipPrevious, stringResource(R.string.horizon_cd_previous_track), size, t.paper, enabled) {
                env.mediaController.previous()
            }
        }
        AnimeRoundButton(
            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            stringResource(if (playing) R.string.horizon_cd_pause else R.string.horizon_cd_play),
            size, t.pink, enabled
        ) { env.mediaController.playPause() }
        if (!onlyPlay) {
            AnimeRoundButton(Icons.Filled.SkipNext, stringResource(R.string.horizon_cd_next_track), size, t.paper, enabled) {
                env.mediaController.next()
            }
        }
    }
}

@Composable
private fun AnimeRoundButton(icon: ImageVector, label: String, size: Dp, fill: Color, enabled: Boolean, onClick: () -> Unit) {
    val on = if (fill == animeTones.pink) Color.White else animeOn(fill)
    AnimeSticker(
        Modifier.size(size + 4.dp).semantics(mergeDescendants = true) { contentDescription = label },
        fill = fill,
        pill = true,
        shadow = 4.dp,
        border = 3.dp,
        onClick = if (enabled) onClick else null,
        clickLabel = label
    ) {
        Icon(icon, contentDescription = null, tint = on, modifier = Modifier.size(size * 0.5f))
    }
}

// --- Directions -------------------------------------------------------------------

/**
 * Directions: a big comic speech bubble, as if the car were talking: the
 * turn glyph in pink beside "IN 300 M!" in comic letters, then the street and
 * the arrival line. Tap opens the navigation app. Without a route the bubble
 * asks "Where to?"; with a navigation app running whose turns can't be read,
 * it names the app.
 */
@Composable
private fun AnimeDirections(env: SkinTileEnv) {
    val nav by NavDirections.state.collectAsState()
    val running by NavDirections.running.collectAsState()
    val t = animeTones
    val context = env.context
    val canTap = !env.editing
    val title: String
    val lines: List<String>
    val headline: Boolean
    when {
        !env.hasMediaAccess -> {
            title = stringResource(R.string.info_directions_access_title)
            lines = listOf(stringResource(R.string.horizon_tap_allow_notification_access))
            headline = false
        }
        !nav.active && running != null -> {
            title = navAppName(running!!)
            lines = listOf(stringResource(R.string.info_directions_running_hint))
            headline = false
        }
        !nav.active -> {
            title = stringResource(R.string.info_map_where_to)
            lines = listOf(stringResource(R.string.horizon_start_navigation))
            headline = false
        }
        else -> {
            title = if (nav.distance.isNotEmpty()) stringResource(R.string.skin_anime_turn_in, nav.distance) else nav.instruction
            lines = listOfNotNull(
                nav.instruction.takeIf { nav.distance.isNotEmpty() && it.isNotBlank() },
                nav.etaParts.joinToString(" · ").takeIf { it.isNotEmpty() }
            )
            headline = true
        }
    }
    val tapLabel = stringResource(if (env.hasMediaAccess) R.string.horizon_open_navigation else R.string.horizon_tap_allow_notification_access)
    BoxWithConstraints(Modifier.fillMaxSize().padding(TILE_PAD)) {
        AnimeBubble(
            Modifier
                .fillMaxSize()
                .animeTap(canTap, tapLabel) {
                    if (!env.hasMediaAccess) CarMediaController.openNotificationAccessSettings(context) else openNavigationApp(context, nav)
                },
            tail = (maxHeight.value * 0.16f).coerceIn(16f, 40f).dp
        ) {
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                val w = maxWidth
                val h = maxHeight
                val lineCount = lines.size
                val lineSize = (min(w.value / 22f, h.value * 0.13f)).coerceIn(12f, 26f)
                // A hint may take two lines; the street and the arrival keep to one each under a headline.
                val lineLines = if (headline) 1 else 2
                val linesH = (lineSize * 1.25f * lineCount * lineLines + if (lineCount > 0) 6f else 0f).dp
                val glyph = if (headline) (min(h.value * 0.55f, w.value * 0.2f)).coerceAtLeast(28f).dp else 0.dp
                val headW = w - glyph - (if (headline) 12.dp else 0.dp)
                val text = title.uppercase(Locale.getDefault())
                val headSize = if (headline) {
                    animeFit(animeTemplate(text), animeComic(100f, t.onPaper), headW, (h - linesH) * 0.92f, 14f, 140f)
                } else {
                    min((h - linesH).value * 0.42f, headW.value / (text.length.coerceAtLeast(4) * 0.4f)).coerceIn(14f, 72f)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (headline) {
                        AnimeManeuver(nav, glyph)
                        Spacer(Modifier.width(12.dp))
                    }
                    Column {
                        // The headline carries the distance: shrunk to fit, never cut; a title in words may wrap.
                        if (headline) AnimeFigure(text, animeComic(headSize, t.onPaper))
                        else AnimeText(text, animeComic(headSize, t.onPaper), maxLines = 2)
                        if (lineCount > 0) Spacer(Modifier.height(6.dp))
                        lines.forEachIndexed { i, line ->
                            AnimeText(
                                line,
                                animeRound(if (i == 0) lineSize else lineSize * 0.86f, if (i == 0) t.onPaper else t.subOnPaper, if (i == 0) FontWeight.Black else FontWeight.Bold),
                                maxLines = lineLines
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The turn glyph from the navigation notification in pink; a generic arrow without one. */
@Composable
private fun AnimeManeuver(nav: NavState, size: Dp) {
    val t = animeTones
    val bitmap = remember(nav.icon) { nav.icon?.asImageBitmap() }
    if (bitmap != null) {
        Image(bitmap, nav.instruction, Modifier.size(size), contentScale = ContentScale.Fit, colorFilter = ColorFilter.tint(t.pink))
    } else {
        Icon(Icons.Filled.Directions, contentDescription = nav.instruction, tint = t.pink, modifier = Modifier.size(size))
    }
}

/**
 * The speech bubble: a paper body with a thick ink border and a hard ink
 * shadow, and its tail [tail] tall at the lower left, cut as one shape so the
 * border runs round the tail. The content sits inside the body.
 */
@Composable
private fun AnimeBubble(modifier: Modifier, tail: Dp, content: @Composable () -> Unit) {
    val t = animeTones
    val shadow = 7.dp
    val border = 5.dp
    Box(
        modifier.cachedDraw(t, tail) {
            val sh = shadow.toPx()
            val bw = border.toPx()
            val th = tail.toPx()
            val bodyW = size.width - sh
            val bodyH = size.height - sh - th
            val r = min(32.dp.toPx(), bodyH * 0.3f)
            val body = Path().apply { addRoundRect(RoundRect(bw / 2f, bw / 2f, bodyW - bw / 2f, bodyH, CornerRadius(r))) }
            val baseX = (bodyW * 0.18f).coerceAtLeast(r)
            val tip = Path().apply {
                moveTo(baseX, bodyH - bw * 2f)
                lineTo(baseX + th * 1.15f, bodyH - bw * 2f)
                lineTo(baseX - th * 0.35f, bodyH + th - bw / 2f)
                close()
            }
            val shape = Path().apply { op(body, tip, PathOperation.Union) }
            val shadowShape = Path().apply {
                addPath(shape, Offset(sh, sh))
            }
            val edge = Stroke(bw, join = StrokeJoin.Round)
            onDrawBehind {
                drawPath(shadowShape, t.ink)
                drawPath(shape, t.paper)
                drawPath(shape, t.ink, style = edge)
            }
        }
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(start = border + 18.dp, top = border + 8.dp, end = shadow + border + 14.dp, bottom = shadow + tail + border + 6.dp)
        ) { content() }
    }
}

// --- Clock ------------------------------------------------------------------------

/**
 * Clock: the time in big comic numerals with a pink misprint shadow on a
 * paper sticker, the date on an ink tag under it, and on roomy tiles the
 * outside temperature on a round lemon sticker stuck to the corner. Tap
 * opens the alarms.
 */
@Composable
private fun AnimeClock(env: SkinTileEnv) {
    val t = animeTones
    val now = rememberNow(60_000L)
    val locale = Locale.getDefault()
    val units = LocalUnits.current
    val timeFmt = rememberDateFormat(units.digitsPattern())
    val dateFmt = rememberDateFormat("EEEdMMM", best = true)
    val weather by WeatherRepo.weather.collectAsState()
    val context = env.context
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .animeTap(!env.editing, stringResource(R.string.horizon_open_clock)) { openClockApp(context) }
            .padding(TILE_PAD)
    ) {
        val w = weather
        val badge = if (w != null && maxHeight >= 150.dp && maxWidth >= 260.dp) (min(maxWidth.value, maxHeight.value) * 0.28f).coerceIn(48f, 104f).dp else null
        val inset = badge?.let { it * 0.32f } ?: 0.dp
        val lean = animeTilt(-1.5f, maxWidth, maxHeight)
        Box(Modifier.fillMaxSize().padding(top = inset, end = inset)) {
            AnimeSticker(
                Modifier.fillMaxSize(),
                fill = t.paper,
                corner = 24.dp,
                rotation = lean
            ) {
                BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                    val dateSize = (maxWidth.value / 20f).coerceIn(12f, 24f)
                    val dateH = (dateSize * 1.6f + 8f).dp
                    val amPm = units.amPm(now, locale)
                    val time = timeFmt.format(now)
                    val clockW = maxWidth - (badge?.let { it * 0.5f } ?: 0.dp)
                    // A little under the room: the pink misprint sits a few percent off the ink.
                    val timeSize = animeFit(if (amPm != null) "00:00 $amPm" else "00:00", animeComic(100f, t.onPaper), clockW * 0.94f, (maxHeight - dateH) * 0.94f, 18f, 320f)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box {
                            val shift = (timeSize * 0.04f).coerceAtLeast(2f).dp
                            val text = if (amPm != null) "$time $amPm" else time
                            AnimeFigure(text, animeComic(timeSize, t.pink), Modifier.padding(start = shift, top = shift))
                            AnimeFigure(text, animeComic(timeSize, t.onPaper), Modifier.padding(end = shift, bottom = shift))
                        }
                        Spacer(Modifier.height(4.dp))
                        AnimeText(
                            dateFmt.format(now).replaceFirstChar { it.titlecase(locale) },
                            animeRound(dateSize, t.yellow),
                            Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(t.ink)
                                .padding(horizontal = 10.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
        if (badge != null && w != null) {
            AnimeSticker(
                Modifier.size(badge).align(Alignment.TopEnd),
                fill = t.lemon,
                pill = true,
                shadow = 4.dp,
                border = 3.5.dp,
                rotation = 8f
            ) {
                AnimeFigure("${units.temp(w.tempC)}°", animeComic(badge.value * 0.34f, animeOn(t.lemon)))
            }
        }
    }
}

// --- Weather ----------------------------------------------------------------------

/**
 * Weather: a sky-blue sticker with the weather drawn in cel style (sun or
 * moon, clouds, rain, snow, a bolt, fog), the temperature in comic numerals,
 * the condition in round black type and the feels / wind line. Across: the
 * picture beside the words; upright: above them.
 */
@Composable
private fun AnimeWeather() {
    val w = rememberWeather()
    val wait = rememberWeatherWait()
    if (w == null) {
        val title = when (wait) {
            WeatherWait.LOADING -> R.string.horizon_loading
            WeatherWait.NO_GPS -> R.string.info_waiting_gps
            WeatherWait.UNAVAILABLE -> R.string.info_weather_unavailable
        }
        AnimeEmptySticker(stringResource(title), stringResource(R.string.horizon_weather_at_car), null)
        return
    }
    val t = animeTones
    val units = LocalUnits.current
    val now = rememberNow(60_000L)
    val hour = remember(now) { Calendar.getInstance().apply { time = now }.get(Calendar.HOUR_OF_DAY) }
    val night = hour < 7 || hour >= 20
    val on = animeOn(t.sky)
    // White numerals in ink by day; yellow at night, where the paper is dark.
    val tempFill = if (t.light) Color.White else t.yellow
    val condition = w.condition
    val feels = stringResource(R.string.horizon_feels, units.temp(w.feelsC))
    val wind = stringResource(if (units.imperial) R.string.units_horizon_wind_mph else R.string.horizon_wind, units.speed(w.windKmh))
    BoxWithConstraints(Modifier.fillMaxSize().padding(TILE_PAD)) {
        AnimeSticker(
            Modifier.fillMaxSize(),
            fill = t.sky,
            corner = 22.dp,
            rotation = animeTilt(1.5f, maxWidth, maxHeight)
        ) {
            BoxWithConstraints(Modifier.fillMaxSize().padding(10.dp), contentAlignment = Alignment.Center) {
                val wide = maxWidth > maxHeight * 1.25f
                val temp = "${units.temp(w.tempC)}°"
                val condSize = (min(maxWidth.value, maxHeight.value * 2f) / 16f).coerceIn(13f, 30f)
                val lineSize = (condSize * 0.72f).coerceIn(11f, 20f)
                val showLine = maxHeight >= 150.dp
                val below = (condSize * 1.3f + (if (showLine) lineSize * 1.3f else 0f) + 6f).dp
                if (wide) {
                    val glyph = min(maxHeight.value, maxWidth.value * 0.4f).dp
                    val textW = maxWidth - glyph - 12.dp
                    val tempSize = animeFit(animeTemplate(temp), animeComic(100f, on), textW, maxHeight - below, 18f, 220f)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AnimeWeatherGlyph(w.code, night, Modifier.size(glyph))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.width(textW)) {
                            AnimeFigure(temp, animeComic(tempSize, tempFill), ink = t.ink, outline = 0.04f)
                            AnimeText(condition, animeRound(condSize, on))
                            if (showLine) AnimeText("$feels · $wind", animeRound(lineSize, on, FontWeight.Bold))
                        }
                    }
                } else {
                    val glyph = (maxHeight.value * 0.42f).coerceAtMost(maxWidth.value * 0.8f).dp
                    val tempSize = animeFit(animeTemplate(temp), animeComic(100f, on), maxWidth, maxHeight - glyph - below, 16f, 200f)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        AnimeWeatherGlyph(w.code, night, Modifier.size(glyph))
                        AnimeFigure(temp, animeComic(tempSize, tempFill), ink = t.ink, outline = 0.04f)
                        AnimeText(condition, animeRound(condSize, on), align = TextAlign.Center)
                        if (showLine) AnimeText("$feels · $wind", animeRound(lineSize, on, FontWeight.Bold), align = TextAlign.Center)
                    }
                }
            }
        }
    }
}

// --- Apps -------------------------------------------------------------------------

/** A sticker colour and lean for an app, the same each time for the same app. */
private fun appLook(pkg: String, fills: List<Color>): Pair<Color, Float> {
    val k = pkg.hashCode() and 0x7FFFFFFF
    return fills[k % fills.size] to floatArrayOf(-2f, 1.5f, -1f, 2f)[(k / 7) % 4]
}

/**
 * App shortcut: a coloured sticker (a pill on a wide tile, a rounded square
 * otherwise) with the app's icon and name, a thick ink outline and a hard
 * shadow it presses into. While arranging, its border goes dashed and a tap
 * does nothing.
 */
@Composable
private fun AnimeApp(item: DashboardItem.AppShortcut, env: SkinTileEnv) {
    val t = animeTones
    val app = env.appsByPackage[item.packageName]
    val label = appLabel(app, item.packageName)
    val (fill, lean) = appLook(item.packageName, t.fills)
    val on = animeOn(fill)
    val open = stringResource(R.string.horizon_open_app, label)
    BoxWithConstraints(Modifier.fillMaxSize().padding(TILE_PAD), contentAlignment = Alignment.Center) {
        val wide = maxWidth >= maxHeight * 1.7f
        val pillH = min(maxHeight.value, 120f).dp
        AnimeSticker(
            (if (wide) Modifier.fillMaxWidth().height(pillH) else Modifier.fillMaxSize())
                .semantics(mergeDescendants = true) { contentDescription = label },
            fill = fill,
            corner = 24.dp,
            pill = wide,
            rotation = animeTilt(lean, maxWidth, if (wide) pillH else maxHeight),
            dashed = env.editing,
            onClick = if (env.editing) null else ({ env.onLaunchApp(item.packageName) }),
            clickLabel = open
        ) {
            BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                if (wide) {
                    val icon = (maxHeight.value * 0.62f).coerceIn(22f, 64f).dp
                    val labelSize = (icon.value * 0.4f).coerceIn(13f, 24f)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AnimeAppIcon(app, icon)
                        Spacer(Modifier.width(10.dp))
                        AnimeText(label, animeRound(labelSize, on))
                    }
                } else {
                    val icon = (min(maxWidth.value, maxHeight.value) * 0.5f).coerceIn(24f, 88f).dp
                    val labelSize = (icon.value * 0.26f).coerceIn(12f, 22f)
                    val named = maxHeight >= icon + 30.dp
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        AnimeAppIcon(app, icon)
                        if (named) {
                            Spacer(Modifier.height(6.dp))
                            AnimeText(label, animeRound(labelSize, on), align = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}

/** An app's own launcher icon, or a plain grid glyph while apps load. */
@Composable
private fun AnimeAppIcon(app: AppEntry?, size: Dp) {
    if (app != null) {
        AppIcon(icon = app.icon, size = size)
    } else {
        Icon(Icons.Filled.Apps, contentDescription = null, tint = animeTones.onPaper, modifier = Modifier.size(size * 0.8f))
    }
}

/**
 * Launch bar: a row of tilted sticker pills in turn of colour, each an app
 * (its name beside the icon where there is room, under it on a tall bar),
 * and a round pencil sticker at the end to edit the bar.
 */
@Composable
private fun AnimeLaunchBar(item: DashboardItem.LaunchBar, env: SkinTileEnv) {
    val t = animeTones
    val editLabel = stringResource(R.string.horizon_cd_edit_launch_bar)
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = TILE_PAD, vertical = 4.dp)) {
        val tall = maxHeight >= 130.dp
        val pencil = 56.dp
        val room = (maxWidth - pencil - 10.dp).value
        // Never a pill under 64 dp wide (a 48 dp target and its lean).
        val pkgs = item.packages.take((room / 72f).toInt().coerceAtLeast(1))
        val per = room / pkgs.size.coerceAtLeast(1)
        val labels = if (tall) per >= 84f else per >= 130f
        val pillH = min(maxHeight.value - 8f, if (tall) 150f else 72f).coerceAtLeast(48f).dp
        val icon = if (tall) (pillH.value * 0.42f).coerceIn(24f, 56f).dp else (pillH.value * 0.5f).coerceIn(22f, 40f).dp
        val labelSize = if (tall) (icon.value * 0.32f).coerceIn(12f, 18f) else (pillH.value * 0.24f).coerceIn(13f, 18f)
        Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            if (pkgs.isEmpty()) {
                AnimeSticker(
                    Modifier.weight(1f).height(pillH),
                    fill = t.paper,
                    pill = true,
                    rotation = -1f,
                    onClick = env.onEditLaunchBar,
                    clickLabel = editLabel
                ) {
                    AnimeText(stringResource(R.string.horizon_launch_bar_empty), animeRound(labelSize, t.onPaper), Modifier.padding(horizontal = 16.dp))
                }
            } else {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    pkgs.forEachIndexed { i, pkg ->
                        val app = env.appsByPackage[pkg]
                        val name = appLabel(app, pkg)
                        val fill = t.fills[i % t.fills.size]
                        val on = animeOn(fill)
                        AnimeSticker(
                            Modifier
                                .weight(1f)
                                .height(pillH)
                                .semantics(mergeDescendants = true) { contentDescription = name },
                            fill = fill,
                            pill = !tall,
                            corner = 22.dp,
                            shadow = 4.dp,
                            border = 3.5.dp,
                            rotation = if (i % 2 == 0) -1.5f else 1.5f,
                            dashed = env.editing,
                            onClick = if (env.editing) null else ({ env.onLaunchApp(pkg) }),
                            clickLabel = stringResource(R.string.horizon_open_app, name)
                        ) {
                            if (tall) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
                                    AnimeAppIcon(app, icon)
                                    if (labels) {
                                        Spacer(Modifier.height(4.dp))
                                        AnimeText(name, animeRound(labelSize, on), align = TextAlign.Center)
                                    }
                                }
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp)) {
                                    AnimeAppIcon(app, icon)
                                    if (labels) {
                                        Spacer(Modifier.width(8.dp))
                                        AnimeText(name, animeRound(labelSize, on))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            AnimeSticker(
                Modifier
                    .size(pencil)
                    .heightIn(min = 48.dp)
                    .semantics(mergeDescendants = true) { contentDescription = editLabel },
                fill = t.pink,
                pill = true,
                shadow = 4.dp,
                border = 3.dp,
                rotation = 6f,
                onClick = env.onEditLaunchBar,
                clickLabel = editLabel
            ) {
                Icon(Icons.Filled.Edit, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
    }
}
