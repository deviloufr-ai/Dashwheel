package com.openauto.dash

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/*
 * The "Original" theme: the launcher's first widget designs, before the Aurora
 * rework (flat cards, twin needle gauges, compact media row).
 * The current widgets delegate here when [DashColors.Original] is set, so every
 * tile keeps one entry point and the rest of the app is unaware of the switch.
 */

@Composable
internal fun OriginalMediaCard(
    mediaState: MediaState,
    controller: CarMediaController,
    hasAccess: Boolean,
    context: Context,
    modifier: Modifier = Modifier
) {
    // Wrapped once per cover, not on every recomposition.
    val art = remember(mediaState.artwork) { mediaState.artwork?.asImageBitmap() }

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
                        .size(72.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(DashColors.CardHi),
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
                            tint = DashColors.TextSecondary,
                            modifier = Modifier.size(38.dp)
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(if (!mediaState.isPlaying && mediaState.hasMedia && mediaState.title.isNotBlank()) R.string.info_paused else R.string.info_now_playing),
                        color = DashColors.AccentInk,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
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
                        text = mediaState.artist.ifBlank { if (hasAccess) "—" else tapToEnable },
                        color = DashColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            if (hasAccess) {
                if (mediaState.durationMs > 0L) {
                    Spacer(Modifier.height(14.dp))
                    OriginalMediaProgress(mediaState, controller)
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { controller.previous() }, modifier = Modifier.size(56.dp)) {
                        Icon(
                            imageVector = Icons.Filled.SkipPrevious,
                            contentDescription = stringResource(R.string.info_media_previous),
                            tint = DashColors.TextPrimary,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    FilledIconButton(
                        onClick = { controller.playPause() },
                        modifier = Modifier.size(68.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = DashColors.Accent,
                            contentColor = DashColors.Background
                        )
                    ) {
                        Icon(
                            imageVector = if (mediaState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = stringResource(R.string.info_media_play_pause),
                            modifier = Modifier.size(40.dp)
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    IconButton(onClick = { controller.next() }, modifier = Modifier.size(56.dp)) {
                        Icon(
                            imageVector = Icons.Filled.SkipNext,
                            contentDescription = stringResource(R.string.info_media_next),
                            tint = DashColors.TextPrimary,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
            } else {
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = { CarMediaController.openNotificationAccessSettings(context) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DashColors.Accent,
                        contentColor = DashColors.Background
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
private fun OriginalMediaProgress(mediaState: MediaState, controller: CarMediaController) {
    val positionMs = rememberMediaPosition(mediaState, controller)
    val fraction = (positionMs.toFloat() / mediaState.durationMs).coerceIn(0f, 1f)
    LinearProgressIndicator(
        progress = { fraction },
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(CircleShape),
        color = DashColors.Accent,
        trackColor = DashColors.CardHi
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(formatTrackTime(positionMs), color = DashColors.Muted, style = MaterialTheme.typography.labelSmall)
        Text(formatTrackTime(mediaState.durationMs), color = DashColors.Muted, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun OriginalObdCard(
    obdData: ObdData,
    connection: ObdConnectionState,
    onConnect: () -> Unit,
    onPickDevice: () -> Unit,
    modifier: Modifier = Modifier
) {
    val connected = connection == ObdConnectionState.CONNECTED
    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
            // Header: title + live connection status / connect button.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    stringResource(R.string.info_telemetry_title),
                    color = DashColors.AccentInk,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelMedium
                )
                if (connected) {
                    TextButton(onClick = onPickDevice, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                        Icon(Icons.Filled.BluetoothConnected, null, tint = DashColors.Good, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.info_obd_live), color = DashColors.Good, style = MaterialTheme.typography.labelSmall)
                    }
                } else {
                    Button(
                        onClick = onConnect,
                        enabled = connection != ObdConnectionState.CONNECTING,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DashColors.Accent,
                            contentColor = DashColors.Background
                        ),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Filled.Bluetooth, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (connection == ObdConnectionState.CONNECTING) "…" else stringResource(R.string.info_connect))
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // The two racing gauges fill most of the card, side by side.
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val u = LocalUnits.current
                val top = u.speedScale(220)
                OriginalAnalogGauge(
                    value = if (connected) u.speed(obdData.speedKmh).toFloat() else 0f,
                    maxValue = top.toFloat(),
                    valueText = if (connected) u.speed(obdData.speedKmh).toString() else "--",
                    label = stringResource(R.string.info_speed_title),
                    unit = u.speedUnit,
                    accent = DashColors.Accent,
                    redlineAccent = DashColors.Warning,
                    redlineFraction = u.speed(SPEED_WARNING_KMH) / top.toFloat(),
                    dimmed = !connected,
                    modifier = Modifier.weight(1.15f).fillMaxHeight()
                )
                OriginalAnalogGauge(
                    value = if (connected) obdData.rpm.toFloat() else 0f,
                    maxValue = 7000f,
                    valueText = if (connected) obdData.rpm.toString() else "--",
                    label = stringResource(R.string.info_gauge_rpm),
                    unit = stringResource(R.string.info_unit_rpm),
                    accent = DashColors.Secondary,
                    redlineAccent = DashColors.Warning,
                    redlineFraction = 0.82f,
                    dimmed = !connected,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
            }

            Spacer(Modifier.height(8.dp))

            // Secondary readouts as compact meter chips.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OriginalMeterChip(
                    label = stringResource(R.string.info_chip_coolant),
                    valueText = if (connected) "${LocalUnits.current.temp(obdData.coolantTempC)}°" else "--",
                    fraction = (obdData.coolantTempC / 120f),
                    color = coolantColor(obdData.coolantTempC),
                    dimmed = !connected,
                    modifier = Modifier.weight(1f)
                )
                OriginalMeterChip(
                    label = stringResource(R.string.info_chip_load),
                    valueText = if (connected) "${obdData.engineLoadPct}%" else "--",
                    fraction = obdData.engineLoadPct / 100f,
                    color = DashColors.Accent,
                    dimmed = !connected,
                    modifier = Modifier.weight(1f)
                )
                OriginalMeterChip(
                    label = stringResource(R.string.info_chip_battery),
                    valueText = if (connected) "%.1fV".format(obdData.voltage) else "--",
                    fraction = batteryFraction(obdData.voltage),
                    color = batteryColor(obdData.voltage),
                    dimmed = !connected,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
internal fun OriginalAnalogGauge(
    value: Float,
    maxValue: Float,
    valueText: String,
    label: String,
    unit: String,
    accent: Color,
    modifier: Modifier = Modifier,
    redlineAccent: Color = DashColors.Warning,
    redlineFraction: Float = 0.8f,
    dimmed: Boolean = false,
    majorTicks: Int = 9
) {
    val target = (value / maxValue).coerceIn(0f, 1f)
    // Read only inside the draw below: the 500 ms needle swing redraws the dial
    // without recomposing the gauge every frame.
    val sweep = animateFloatAsState(
        targetValue = if (dimmed) 0f else target,
        animationSpec = tween(durationMillis = 500),
        label = "gauge"
    )
    val startAngle = 135f      // 7:30 position (Compose: 0° = 3 o'clock, CW positive)
    val sweepTotal = 270f
    val muted = DashColors.Muted
    val trackColor = DashColors.CardHi
    val tickColor = DashColors.TextSecondary.copy(alpha = 0.6f)
    val hubColor = DashColors.Card

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        val gaugePx = min(maxWidth.value, maxHeight.value)
        val valueSize = (gaugePx * 0.20f).coerceIn(16f, 46f).sp
        val unitSize = (gaugePx * 0.075f).coerceIn(8f, 14f).sp
        val labelSize = (gaugePx * 0.085f).coerceIn(9f, 15f).sp

        Canvas(modifier = Modifier.fillMaxSize()) {
            val frac = sweep.value
            val sweepColor = if (frac >= redlineFraction) redlineAccent else accent
            val needleColor = if (dimmed) muted else sweepColor
            val stroke = size.minDimension * 0.085f
            val radius = (size.minDimension - stroke) / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            val topLeft = Offset(center.x - radius, center.y - radius)
            val arcSize = Size(radius * 2f, radius * 2f)

            // Base track.
            drawArc(
                color = trackColor,
                startAngle = startAngle,
                sweepAngle = sweepTotal,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            // Dim redline zone on the track.
            drawArc(
                color = redlineAccent.copy(alpha = 0.35f),
                startAngle = startAngle + sweepTotal * redlineFraction,
                sweepAngle = sweepTotal * (1f - redlineFraction),
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            // Active sweep (glow underlay + solid).
            if (frac > 0f) {
                drawArc(
                    color = sweepColor.copy(alpha = 0.25f),
                    startAngle = startAngle,
                    sweepAngle = sweepTotal * frac,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke * 1.9f, cap = StrokeCap.Round)
                )
                drawArc(
                    color = sweepColor,
                    startAngle = startAngle,
                    sweepAngle = sweepTotal * frac,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
            // Tick marks.
            val tickOuter = radius - stroke * 0.6f
            val tickInner = radius - stroke * 1.5f
            for (i in 0 until majorTicks) {
                val a = Math.toRadians((startAngle + sweepTotal * i / (majorTicks - 1)).toDouble())
                val ca = cos(a).toFloat()
                val sa = sin(a).toFloat()
                drawLine(
                    color = tickColor,
                    start = Offset(center.x + ca * tickInner, center.y + sa * tickInner),
                    end = Offset(center.x + ca * tickOuter, center.y + sa * tickOuter),
                    strokeWidth = stroke * 0.16f,
                    cap = StrokeCap.Round
                )
            }
            // Needle + hub.
            val needleA = Math.toRadians((startAngle + sweepTotal * frac).toDouble())
            val nx = cos(needleA).toFloat()
            val ny = sin(needleA).toFloat()
            val needleLen = radius - stroke * 0.4f
            drawLine(
                color = needleColor,
                start = Offset(center.x - nx * radius * 0.12f, center.y - ny * radius * 0.12f),
                end = Offset(center.x + nx * needleLen, center.y + ny * needleLen),
                strokeWidth = stroke * 0.35f,
                cap = StrokeCap.Round
            )
            drawCircle(color = hubColor, radius = stroke * 0.9f, center = center)
            drawCircle(color = needleColor, radius = stroke * 0.5f, center = center)
        }

        // Digital readout in the middle.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = gaugePx.times(0.10f).dp)
        ) {
            Text(
                text = valueText,
                color = if (dimmed) DashColors.Muted else DashColors.TextPrimary,
                fontSize = valueSize,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Text(text = unit, color = DashColors.Muted, fontSize = unitSize)
            Spacer(Modifier.height(2.dp))
            Text(text = label, color = accent, fontSize = labelSize, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
internal fun OriginalMeterChip(
    label: String,
    valueText: String,
    fraction: Float,
    color: Color,
    dimmed: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(DashColors.CardHi)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = DashColors.TextSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            Text(
                valueText,
                color = if (dimmed) DashColors.Muted else DashColors.TextPrimary,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(CircleShape)
                .background(DashColors.Background)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(if (dimmed) 0f else fraction01(fraction))
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}
