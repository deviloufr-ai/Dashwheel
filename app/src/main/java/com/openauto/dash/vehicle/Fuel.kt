package com.openauto.dash.vehicle

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.openauto.dash.CarProfile
import com.openauto.dash.CarProfileStore
import com.openauto.dash.FuelInfo
import com.openauto.dash.fuelInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlin.math.roundToInt

/*
 * The fuel and the range as the screens read them: [fuelInfo] over the two keys. The level is the
 * resolved FUEL_LEVEL (the learned CANbox byte, else the car app's litres over the profile's tank, else
 * the engine computer's PID, by rank), the range is RANGE (the car's own trip computer, learned or shared).
 */

/**
 * How long the car's own fuel level and range are believed without a new message. The car app and the
 * CANbox say them on a change and a parked car says nothing, while the tile has always shown the last one
 * until the next: the key's 2 minutes is far too short, so these providers put with this ttl.
 */
const val FUEL_HOLD_MS = 6 * 3_600_000L

/** The info for [level] (FUEL_LEVEL's resolved sample) and [range] (km); the adapter's 0 % is no reading, as it always was. */
internal fun fuelInfoOf(level: Sample<Double>?, range: Double?, car: CarProfile = CarProfileStore.current): FuelInfo? {
    val fromObd = level?.source?.kind == SourceKind.OBD
    val pct = level?.value?.roundToInt()?.takeUnless { fromObd && it == 0 }
    return fuelInfo(pct, range?.roundToInt(), car.tank, car.typicalUse, fromObd)
}

/** The fuel and range now; null when nothing is known. Hold [K.FUEL_LEVEL] while you rely on it. */
internal fun fuelInfoNow(): FuelInfo? =
    fuelInfoOf(Vehicle.bus.sample(K.FUEL_LEVEL).value, Vehicle.bus.now(K.RANGE))

/** [fuelInfoNow] as it changes; collecting it is demand for the fuel level. */
internal fun fuelInfoFlow(): Flow<FuelInfo?> =
    combine(Vehicle.bus.sample(K.FUEL_LEVEL), Vehicle.bus.sample(K.RANGE), CarProfileStore.profile) { level, range, car ->
        fuelInfoOf(level, range?.value, car)
    }

/** The fuel and range for a screen; null when nothing is known. */
@Composable
internal fun rememberFuelInfo(): FuelInfo? {
    val flow = remember { fuelInfoFlow() }
    val info by flow.collectAsState(remember { fuelInfoNow() })
    return info
}
