@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.openauto.dash

import android.content.Context
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/*
 * Now-playing tile with album art, seek bar and transport controls.
 */

/**
 * Current playback position for the progress bar. Polls twice a second only
 * while a track with a known duration is actually playing; paused or idle
 * sessions are read once per state change and then left alone, so an idle
 * media tile no longer recomposes at 2 Hz. On a page off screen it is read
 * once and left alone too ([LocalPageActive]).
 */
@Composable
internal fun rememberMediaPosition(mediaState: MediaState, controller: CarMediaController): Long {
    var positionMs by remember { mutableLongStateOf(0L) }
    val active = LocalPageActive.current
    LaunchedEffect(mediaState.isPlaying, mediaState.title, mediaState.durationMs, active) {
        positionMs = controller.positionMs()
        if (!active || !mediaState.isPlaying || mediaState.durationMs <= 0L) return@LaunchedEffect
        while (true) {
            delay(500)
            positionMs = controller.positionMs()
        }
    }
    return positionMs
}

@Composable
internal fun MediaCard(
    mediaState: MediaState,
    controller: CarMediaController,
    hasAccess: Boolean,
    context: Context,
    modifier: Modifier = Modifier
) {
    if (DashColors.Original) {
        OriginalMediaCard(mediaState, controller, hasAccess, context, modifier)
        return
    }
    val artwork = mediaState.artwork
    val accent = DashColors.Accent
    val accent2 = DashColors.Accent2
    val glow = DashColors.Glow
    // The cover's dominant colour bleeds out beneath it, like light off a screen.
    // Both are worked out once per cover, not on every recomposition.
    val bleed = remember(artwork) { artwork?.averageColor() } ?: accent
    val art = remember(artwork) { artwork?.asImageBitmap() }
    val artShape = DashShape.Large

    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .drawBehind {
                            val c = Offset(size.width * 0.5f, size.height * 0.8f)
                            val r = size.maxDimension * 1.05f
                            drawCircle(
                                brush = Brush.radialGradient(
                                    listOf(bleed.copy(alpha = 0.25f + 0.35f * glow), Color.Transparent),
                                    center = c, radius = r
                                ),
                                radius = r, center = c
                            )
                        }
                        .clip(artShape)
                        .background(Brush.linearGradient(listOf(accent, accent2)))
                        .border(1.dp, Color.White.copy(alpha = 0.22f), artShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (art != null) {
                        Image(
                            bitmap = art,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.MusicNote,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.92f),
                            modifier = Modifier.size(42.dp)
                        )
                    }
                    // Glossy sheen over the top-left corner.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.linearGradient(
                                    listOf(Color.White.copy(alpha = 0.28f), Color.Transparent),
                                    start = Offset.Zero,
                                    end = Offset(240f, 240f)
                                )
                            )
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (mediaState.isPlaying) {
                            Box(
                                Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(DashColors.Good)
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            text = stringResource(if (!mediaState.isPlaying && mediaState.hasMedia && mediaState.title.isNotBlank()) R.string.info_paused else R.string.info_now_playing),
                            color = DashColors.Accent,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = when {
                            mediaState.hasMedia && mediaState.title.isNotBlank() -> mediaState.title
                            hasAccess -> stringResource(R.string.info_nothing_playing)
                            else -> stringResource(R.string.info_media_access_needed)
                        },
                        color = DashColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium
                    )
                    val tapToEnable = stringResource(R.string.info_media_tap_to_enable)
                    Text(
                        text = mediaState.artist.ifBlank { if (hasAccess) "\u2014" else tapToEnable },
                        color = DashColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            if (hasAccess) {
                if (mediaState.durationMs > 0L) {
                    Spacer(Modifier.height(10.dp))
                    MediaTimeline(mediaState, controller)
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlassRoundButton(
                        icon = Icons.Filled.SkipPrevious,
                        contentDescription = stringResource(R.string.info_media_previous),
                        size = 56.dp,
                        iconSize = 32.dp,
                        onClick = { controller.previous() }
                    )
                    Spacer(Modifier.width(18.dp))
                    GradientRoundButton(
                        icon = if (mediaState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(R.string.info_media_play_pause),
                        size = 70.dp,
                        iconSize = 38.dp,
                        onClick = { controller.playPause() }
                    )
                    Spacer(Modifier.width(18.dp))
                    GlassRoundButton(
                        icon = Icons.Filled.SkipNext,
                        contentDescription = stringResource(R.string.info_media_next),
                        size = 56.dp,
                        iconSize = 32.dp,
                        onClick = { controller.next() }
                    )
                }
            } else {
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = { CarMediaController.openNotificationAccessSettings(context) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DashColors.Accent,
                        contentColor = DashColors.OnAccent
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.info_media_grant_access))
                }
            }
        }
    }
}

/** Seek bar and times: the only part of the card that follows the 2 Hz position poll. */
@Composable
private fun MediaTimeline(mediaState: MediaState, controller: CarMediaController) {
    val positionMs = rememberMediaPosition(mediaState, controller)
    MediaProgress(fraction = (positionMs.toFloat() / mediaState.durationMs).coerceIn(0f, 1f))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(formatTrackTime(positionMs), color = DashColors.Muted, style = MaterialTheme.typography.labelSmall)
        Text(formatTrackTime(mediaState.durationMs), color = DashColors.Muted, style = MaterialTheme.typography.labelSmall)
    }
}

/** Seek bar: recessed track, accent-gradient fill with a glow, bright knob at the playhead. */
@Composable
internal fun MediaProgress(fraction: Float, modifier: Modifier = Modifier) {
    val accent = DashColors.Accent
    val accent2 = DashColors.Accent2
    val glow = DashColors.Glow
    val track = if (DashColors.Glass) DashColors.well(0.35f) else DashColors.CardHi
    // The position ticks twice a second; the fill gradient is built once per
    // width and the fraction read in the draw, so a tick only redraws the bar.
    val shown = rememberUpdatedState(fraction)
    Spacer(
        modifier = modifier
            .fillMaxWidth()
            .height(16.dp)
            .drawWithCache {
                val fill = Brush.horizontalGradient(listOf(accent, accent2), endX = size.width)
                val h = 5.dp.toPx()
                val y = size.height / 2f
                val r = h / 2f
                val knobHalo = 8.dp.toPx()
                val knob = 5.dp.toPx()
                onDrawBehind {
                    drawRoundRect(
                        color = track,
                        topLeft = Offset(0f, y - r),
                        size = Size(size.width, h),
                        cornerRadius = CornerRadius(r)
                    )
                    val w = size.width * shown.value.coerceIn(0f, 1f)
                    if (w > 0f) {
                        if (glow > 0f) {
                            drawRoundRect(
                                color = accent.copy(alpha = 0.30f * glow),
                                topLeft = Offset(0f, y - h),
                                size = Size(w, h * 2f),
                                cornerRadius = CornerRadius(h)
                            )
                        }
                        drawRoundRect(
                            brush = fill,
                            topLeft = Offset(0f, y - r),
                            size = Size(w, h),
                            cornerRadius = CornerRadius(r)
                        )
                    }
                    drawCircle(color = accent.copy(alpha = 0.35f), radius = knobHalo, center = Offset(w, y))
                    drawCircle(color = Color.White, radius = knob, center = Offset(w, y))
                }
            }
    )
}
