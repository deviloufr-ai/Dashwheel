package com.openauto.dash.vehicle

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.openauto.dash.SpeedCorrection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

/*
 * Speed on the bus (see "Decisions" in docs/vehicle-bus.md).
 *
 * VEHICLE_SPEED is the car's speed after the driver's correction: every
 * provider of it applies SpeedCorrection.corrected. GPS_SPEED is raw. What
 * consumers read is K.SPEED (the car's, else the GPS's), or a specific key
 * where the meaning needs it; the bar and the second screen show it through
 * the display helper below, which corrects a GPS speed the way they always did.
 */

/**
 * The speed to show for [sample] (K.SPEED's resolved sample), whole km/h; null while unknown.
 * A car speed comes corrected already; a GPS one gets [SpeedCorrection.correctedGps]
 * (the correction only once the car really moves, so a stopped car's drift stays 0).
 */
fun displaySpeedKmh(sample: Sample<Double>?, correctGps: (Int) -> Int = SpeedCorrection::correctedGps): Int? {
    val kmh = sample?.value?.roundToInt() ?: return null
    return if (sample.source.kind == SourceKind.GPS) correctGps(kmh) else kmh
}

/** The speed to show ([displaySpeedKmh]); recomposes when the speed or the driver's correction changes. */
@Composable
fun rememberDisplaySpeedKmh(): Int? {
    val flow = remember { Vehicle.bus.sample(K.SPEED) }
    val sample by flow.collectAsState()
    // Followed, so a change in Settings shows at once.
    val offset by SpeedCorrection.offsetKmh.collectAsState()
    return remember(sample, offset) { displaySpeedKmh(sample) }
}

/** [key]'s speed in whole km/h as it changes; null while unknown (map it at the edge if you need an Int). */
fun speedKmhFlow(key: Key<Double> = K.SPEED): Flow<Int?> =
    Vehicle.bus.value(key).map { it?.roundToInt() }.distinctUntilChanged()

/** [key]'s speed in whole km/h now, for code that is not a flow; null while unknown. */
fun speedKmhNow(key: Key<Double> = K.SPEED): Int? = Vehicle.bus.now(key)?.roundToInt()
