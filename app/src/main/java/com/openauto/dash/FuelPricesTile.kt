package com.openauto.dash

import android.Manifest
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/*
 * "Fuel nearby": the cheapest stations around the car for its fuel, one tap
 * to navigate there. Prices come from the French open data (FuelPrices.kt).
 */

/** Hands the destination to the navigation app ([NavHandoff]), and says so when the unit has none. */
internal fun navigateTo(context: Context, lat: Double, lng: Double, label: String) {
    if (!NavHandoff.start(context, lat, lng, label)) Toast.makeText(context, R.string.places_no_nav_app, Toast.LENGTH_SHORT).show()
}

/** What the fuel tile and its designed face share: the grade, the car's position and the ranked stations. */
internal class FuelNearby(val grade: FuelGrade, val lat: Double, val lng: Double, val ranked: List<RankedStation>)

/**
 * Keeps the station list fresh for the car's position and grade while the
 * tile is on screen; null until there's a position and an answer.
 */
@Composable
internal fun rememberFuelNearby(): FuelNearby? {
    UseLocationFeed()
    val location by LocationFeed.location.collectAsState()
    val car by CarProfileStore.profile.collectAsState()
    val stations by FuelPriceRepo.stations.collectAsState()
    val grades = remember(car) { FuelPrices.gradesFor(car) }
    val here = location ?: return null
    // Asked again once the car has moved about a kilometre (or the grade changed),
    // and each minute meanwhile so an old list still gets refreshed; the repo
    // skips any fetch it doesn't need. Not restarted on every GPS fix.
    LaunchedEffect((here.latitude * 100).roundToInt(), (here.longitude * 100).roundToInt(), grades.first()) {
        while (true) {
            FuelPriceRepo.refresh(here.latitude, here.longitude, grades.first())
            delay(60_000)
        }
    }
    val list = stations ?: return null
    // Ranked again when the list, the grade or the position (to ~100 m) changes,
    // not on every recomposition.
    return remember(list, grades, (here.latitude * 1000).roundToInt(), (here.longitude * 1000).roundToInt()) {
        // A petrol car falls back to SP95/98 where no E10 is sold.
        val grade = grades.firstOrNull { g -> list.any { it.prices.containsKey(g) } } ?: grades.first()
        FuelNearby(grade, here.latitude, here.longitude, FuelPrices.rank(list, grade, here.latitude, here.longitude))
    }
}

@Composable
internal fun FuelPricesCard(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val perm = rememberPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    val error by FuelPriceRepo.error.collectAsState()
    val location by LocationFeed.location.collectAsState()
    Card(modifier = modifier) {
        if (!perm.granted) {
            NeedsAccess(Icons.Filled.LocalGasStation, stringResource(R.string.fuel_title), stringResource(R.string.fuel_allow_location), perm.request)
            return@Card
        }
        val nearby = rememberFuelNearby()
        Column(modifier = Modifier.fillMaxSize().padding(DashSpace.Lg), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TileHeader(stringResource(R.string.fuel_title)) {
                nearby?.let { Text(it.grade.label, color = DashColors.Muted, style = MaterialTheme.typography.labelSmall) }
            }
            when {
                nearby == null && error != null -> Hint(stringResource(R.string.fuel_error))
                nearby == null -> Hint(stringResource(if (location == null) R.string.info_waiting_gps else R.string.fuel_loading))
                nearby.ranked.isEmpty() -> Hint(fuelNoneText())
                else -> {
                    val u = LocalUnits.current
                    Text(
                        pluralStringResource(
                            if (u.imperial) R.plurals.units_fuel_stations_mi else R.plurals.fuel_stations,
                            nearby.ranked.size, nearby.ranked.size, u.distance(FuelPrices.RADIUS_KM)
                        ) +
                            " · " + stringResource(R.string.fuel_navigate),
                        color = DashColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall
                    )
                    // As many whole stations as the tile holds, cheapest first: no half row, nothing to scroll.
                    WholeRows(modifier = Modifier.fillMaxWidth().weight(1f), gap = 2.dp) {
                        nearby.ranked.forEachIndexed { i, r ->
                            StationRow(r, cheapest = i == 0) {
                                navigateTo(context, r.station.lat, r.station.lng, r.station.label)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StationRow(r: RankedStation, cheapest: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                FuelPrices.formatPrice(r.price), color = if (cheapest) DashColors.Good else DashColors.TextPrimary,
                fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.width(3.dp))
            Text(
                FuelPrices.CURRENCY, color = if (cheapest) DashColors.Good else DashColors.TextSecondary,
                fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium
            )
        }
        Spacer(Modifier.width(10.dp))
        // The station's name over its town; the town alone when the name isn't known.
        Column(Modifier.weight(1f)) {
            val name = r.station.name
            Text(name.ifBlank { r.station.town }, color = DashColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
            if (name.isNotBlank()) {
                Text(r.station.town, color = DashColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(FuelPrices.formatDistance(r.distanceKm), color = DashColors.TextSecondary, style = MaterialTheme.typography.labelMedium)
    }
}

/** No station within the search radius, said in the driver's distance unit. */
@Composable
internal fun fuelNoneText(): String {
    val u = LocalUnits.current
    return stringResource(if (u.imperial) R.string.units_fuel_none_mi else R.string.fuel_none, u.distance(FuelPrices.RADIUS_KM))
}

@Composable
private fun Hint(text: String) {
    Text(text, color = DashColors.Muted, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
}

/** Stacks its children top down and leaves out every one that would not fit whole. */
@Composable
private fun WholeRows(modifier: Modifier = Modifier, gap: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content, modifier) { measurables, constraints ->
        val loose = constraints.copy(minHeight = 0)
        val gapPx = gap.roundToPx()
        val placeables = mutableListOf<androidx.compose.ui.layout.Placeable>()
        var used = 0
        for (m in measurables) {
            val p = m.measure(loose)
            val needed = used + (if (placeables.isEmpty()) 0 else gapPx) + p.height
            if (needed > constraints.maxHeight) break
            placeables += p
            used = needed
        }
        layout(constraints.maxWidth, if (constraints.hasBoundedHeight) constraints.maxHeight else used) {
            var y = 0
            placeables.forEach { p ->
                p.placeRelative(0, y)
                y += p.height + gapPx
            }
        }
    }
}
