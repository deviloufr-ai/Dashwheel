package com.openauto.dash

import android.location.Location
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openauto.dash.link.DriveSummary

/** The drives to one place: newest first, minutes each. */
internal data class Commute(val minutes: List<Int>) {
    val average: Int? get() = if (minutes.isEmpty()) null else minutes.average().toInt()
    val best: Int? get() = minutes.minOrNull()
}

internal object Commutes {
    /** A drive ending this close to a place went there. */
    const val NEAR_M = 300f
    /** A detour or an errand, not the commute. */
    private const val MAX_MIN = 180
    const val KEEP = 10

    /** The [drives] (newest first) that ended within [NEAR_M] of [lat], [lng], in minutes door to door. */
    fun to(drives: List<DriveSummary>, lat: Double, lng: Double, distance: (Double, Double, Double, Double) -> Float = ::metres): Commute =
        Commute(
            drives.asSequence()
                .filter { !it.ongoing && it.endLat != null && it.endLng != null }
                .filter { distance(it.endLat!!, it.endLng!!, lat, lng) <= NEAR_M }
                .map { ((it.endedAt - it.startedAt) / 60_000L).toInt() }
                .filter { it in 1..MAX_MIN }
                .take(KEEP).toList()
        )

    private fun metres(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Float {
        val out = FloatArray(1)
        Location.distanceBetween(aLat, aLng, bLat, bLng, out)
        return out[0]
    }
}

/**
 * How long the drive home and the drive to work take, day after day: the
 * last trips as bars, with the average and the best. Drives are matched by
 * where they ended, so nothing has to be started or tagged.
 */
@Composable
internal fun CommuteCard(modifier: Modifier = Modifier) {
    val drives by DriveLog.drives.collectAsState()
    val places by PlacesStore.places.collectAsState()
    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(DashSpace.Lg), verticalArrangement = Arrangement.spacedBy(DashSpace.Sm)) {
            TileHeader(stringResource(R.string.widgets_commute))
            if (places.home == null && places.work == null) {
                Text(stringResource(R.string.widgets_hw_empty), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            places.home?.let { CommuteRow(Icons.Filled.Home, Commutes.to(drives, it.lat, it.lng), Modifier.weight(1f)) }
            places.work?.let { CommuteRow(Icons.Filled.Work, Commutes.to(drives, it.lat, it.lng), Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun CommuteRow(icon: ImageVector, c: Commute, modifier: Modifier) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DashSpace.Md)) {
        Icon(icon, contentDescription = null, tint = DashColors.Accent)
        if (c.minutes.isEmpty()) {
            Text(stringResource(R.string.widgets_commute_none), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            return@Row
        }
        // Oldest on the left, so the bars read like a calendar.
        val top = c.minutes.max().coerceAtLeast(1)
        Row(modifier = Modifier.weight(1f).fillMaxHeight(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            c.minutes.reversed().forEachIndexed { i, m ->
                val latest = i == c.minutes.size - 1
                Box(
                    Modifier.width(10.dp).fillMaxHeight(m.toFloat() / top).clip(DashShape.Small)
                        .background(if (latest) DashColors.Accent else DashColors.Muted.copy(alpha = 0.5f))
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(stringResource(R.string.info_map_duration_min, c.minutes.first()), color = DashColors.TextPrimary, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.widgets_commute_stats, c.average ?: 0, c.best ?: 0),
                color = DashColors.TextSecondary, style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
internal fun commuteFace(): WidgetFace {
    val drives by DriveLog.drives.collectAsState()
    val places by PlacesStore.places.collectAsState()
    val home = places.home?.let { Commutes.to(drives, it.lat, it.lng) }
    val work = places.work?.let { Commutes.to(drives, it.lat, it.lng) }
    val main = home?.takeIf { it.minutes.isNotEmpty() } ?: work
    return WidgetFace(
        icon = Icons.Filled.Home,
        title = BuiltinKind.COMMUTE.label,
        value = main?.minutes?.firstOrNull()?.toString() ?: "--",
        unit = "min",
        caption = main?.takeIf { it.minutes.isNotEmpty() }?.let { stringResource(R.string.widgets_commute_stats, it.average ?: 0, it.best ?: 0) }
            ?: stringResource(R.string.widgets_commute_none),
        stats = listOfNotNull(
            home?.average?.let { FaceStat(stringResource(R.string.widgets_hw_home), stringResource(R.string.info_map_duration_min, it)) },
            work?.average?.let { FaceStat(stringResource(R.string.widgets_hw_work), stringResource(R.string.info_map_duration_min, it)) }
        )
    )
}
