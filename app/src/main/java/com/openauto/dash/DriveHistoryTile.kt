package com.openauto.dash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openauto.dash.link.DriveSummary
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/*
 * The drive history: the last drives as the drive log kept them, which only
 * the phone showed until now, with this week's total on top. Read-only:
 * every drive logs itself ([DriveLog]), so there is nothing to tap.
 */

/** This week's driving: distance, number of drives and what the fuel cost (null when no drive has a cost). */
internal data class WeekTotals(val km: Double, val drives: Int, val cost: Double?, val currency: String?)

/** The history's sums, pure so they're unit-tested. */
internal object DriveHistory {
    /** The drive under way (once it has gone far enough to be reported) with the logged ones, newest first. */
    fun recent(drives: List<DriveSummary>, current: DriveSummary?): List<DriveSummary> =
        (listOfNotNull(current) + drives.filter { it.startedAt != current?.startedAt }).sortedByDescending { it.startedAt }

    /** Midnight on the first day of the week [now] falls in, by [calendar]'s own first day (Monday in most of Europe). */
    fun weekStart(now: Long, calendar: Calendar): Long {
        val c = calendar.clone() as Calendar
        c.timeInMillis = now
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        while (c.get(Calendar.DAY_OF_WEEK) != c.firstDayOfWeek) c.add(Calendar.DAY_OF_MONTH, -1)
        return c.timeInMillis
    }

    /** The [drives] that started from [from] on, summed. */
    fun week(drives: List<DriveSummary>, from: Long): WeekTotals {
        val these = drives.filter { it.startedAt >= from }
        val costs = these.mapNotNull { it.fuelCost }
        return WeekTotals(
            km = these.sumOf { it.distanceKm },
            drives = these.size,
            cost = if (costs.isEmpty()) null else costs.sum(),
            currency = these.firstNotNullOfOrNull { it.currency }
        )
    }
}

/** The drives to show, newest first: the log and the drive under way, or the demo's week during the demo. */
@Composable
private fun rememberDrives(): List<DriveSummary> {
    val drives by DriveLog.drives.collectAsState()
    val current by DriveLog.current.collectAsState()
    val demo by DemoMode.active.collectAsState()
    if (demo) {
        // The fake drive under way on top of a made-up week: the real log stays untouched.
        val trip by LocationFeed.trip.collectAsState()
        val car by CarProfileStore.profile.collectAsState()
        val week = remember(car) { DemoMode.drives(System.currentTimeMillis(), car) }
        return listOf(DriveLogRules.summary(trip, null, car, ongoing = true)) + week
    }
    return remember(drives, current) { DriveHistory.recent(drives, current) }
}

/** This week's totals, the week turning over by itself on its first day. */
@Composable
private fun rememberWeek(drives: List<DriveSummary>): WeekTotals {
    val hour = rememberWallClock(3_600_000L).longValue
    return remember(drives, hour) { DriveHistory.week(drives, DriveHistory.weekStart(System.currentTimeMillis(), Calendar.getInstance())) }
}

/** When a drive set off: the day and the clock within the week, the date before that. */
@Composable
private fun startLabel(at: Long): String {
    val u = LocalUnits.current
    return remember(at, u) {
        val date = Date(at)
        val locale = Locale.getDefault()
        val day = if (System.currentTimeMillis() - at < 6 * 86_400_000L) SimpleDateFormat("EEE", locale).format(date)
        else SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMM"), locale).format(date)
        "$day ${u.time(date, locale)}"
    }
}

/** Door to door: minutes, then hours and minutes. */
@Composable
private fun driveLength(ms: Long): String {
    val min = (ms / 60_000L).toInt()
    return if (min < 60) stringResource(R.string.info_map_duration_min, min) else stringResource(R.string.info_duration_hm, min / 60, min % 60)
}

/** A drive's figures on one line: distance, time, eco score where the adapter judged it, and the fuel's cost. */
@Composable
private fun driveDetail(d: DriveSummary): String {
    val u = LocalUnits.current
    return listOfNotNull(
        "${TripMath.figure(u.distance(d.distanceKm))} ${u.distanceUnit}",
        // A drive under way runs until now; its own figures stop at their last save.
        driveLength(if (d.ongoing) maxOf(d.elapsedMs, System.currentTimeMillis() - d.startedAt) else d.elapsedMs),
        d.ecoScore?.let { stringResource(R.string.widgets_history_eco, it) },
        d.fuelCost?.let { money(it, d.currency) }
    ).joinToString(", ")
}

@Composable
private fun weekCaption(w: WeekTotals): String =
    listOfNotNull(
        pluralStringResource(R.plurals.widgets_history_drives, w.drives, w.drives),
        w.cost?.let { money(it, w.currency) }
    ).joinToString(", ")

/** Rows of the list are this tall with their gap; the header and the week above them take [TOP]. */
private val ROW = 28.dp
private val TOP = 88.dp

/**
 * The last drives on the unit, newest first, under this week's distance,
 * drives and cost. As many rows as the tile has room for; the drive under
 * way, while there is one, comes first in the accent colour.
 */
@Composable
internal fun DriveHistoryCard(modifier: Modifier = Modifier) {
    val drives = rememberDrives()
    val week = rememberWeek(drives)
    val u = LocalUnits.current
    Card(modifier = modifier) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(DashSpace.Lg)) {
            val fit = ((maxHeight - TOP) / ROW).toInt().coerceAtLeast(0)
            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(DashSpace.Sm)) {
                TileHeader(stringResource(R.string.widgets_history))
                if (drives.isEmpty()) {
                    Text(stringResource(R.string.widgets_history_none), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                    return@Column
                }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.widgets_history_week), color = DashColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
                        Text(
                            "${TripMath.figure(u.distance(week.km))} ${u.distanceUnit}",
                            color = DashColors.TextPrimary, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge, maxLines = 1
                        )
                    }
                    Text(weekCaption(week), color = DashColors.TextSecondary, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
                drives.take(fit).forEach { d -> DriveRow(d) }
            }
        }
    }
}

@Composable
private fun DriveRow(d: DriveSummary) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            startLabel(d.startedAt), color = if (d.ongoing) DashColors.Accent else DashColors.TextPrimary,
            fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge, maxLines = 1
        )
        Spacer(Modifier.width(DashSpace.Md))
        Text(
            driveDetail(d), color = DashColors.TextSecondary, style = MaterialTheme.typography.labelMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
        )
    }
}

@Composable
internal fun driveHistoryFace(): WidgetFace {
    val drives = rememberDrives()
    val week = rememberWeek(drives)
    val u = LocalUnits.current
    if (drives.isEmpty()) {
        return WidgetFace(
            icon = Icons.Filled.History, title = BuiltinKind.DRIVE_HISTORY.label,
            value = NO_READING, unit = u.distanceUnit, caption = stringResource(R.string.widgets_history_none)
        )
    }
    return WidgetFace(
        icon = Icons.Filled.History,
        title = BuiltinKind.DRIVE_HISTORY.label,
        value = TripMath.figure(u.distance(week.km)),
        unit = u.distanceUnit,
        caption = stringResource(R.string.widgets_history_week_line, weekCaption(week)),
        stats = listOfNotNull(
            FaceStat(stringResource(R.string.widgets_history_week), pluralStringResource(R.plurals.widgets_history_drives, week.drives, week.drives)),
            week.cost?.let { FaceStat(stringResource(R.string.info_trip_cost), money(it, week.currency)) }
        ),
        rows = drives.take(5).map { d -> FaceRow(startLabel(d.startedAt), driveDetail(d), key = d.startedAt) }
    )
}
