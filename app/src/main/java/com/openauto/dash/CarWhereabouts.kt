package com.openauto.dash

import com.openauto.dash.vehicle.Vehicle
import android.content.Context
import android.location.Location
import com.openauto.dash.link.CarLocation
import com.openauto.dash.vehicle.K
import com.openauto.dash.vehicle.speedKmhNow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tells the linked phone where the car is, so its companion app can lead the
 * driver back to it without anything to tap: every place the car comes to a
 * stop (the last one before it is switched off is where it was parked), the
 * spot saved on the Parking tile, and where it stands when the link comes up.
 * Only while a phone is linked, and never the demo's made-up drive.
 */
internal object CarWhereabouts {
    /** Moving from this speed up; only a stop after moving is reported. */
    private const val MOVING_KMH = 10
    private const val STOPPED_KMH = 3
    /** Stopped this long: a halt, not a slow roll. Short, as a key turned off cuts the link at once. */
    private const val STOPPED_FOR_MS = 3_000L
    /** A fix older than this says nothing about where the car is now. */
    private const val FRESH_FIX_MS = 2 * 60_000L

    /** Runs for as long as the link is up (cancel it when the link ends), handing each report to [send]. */
    suspend fun report(context: Context, send: (CarLocation) -> Unit) {
        // The GPS feed is counted on the main thread, as the tiles do.
        val acquired = withContext(Dispatchers.Main) { LocationFeed.acquire(context) }
        // The car's speed is read by snapshot per fix: it stays wanted while the link is up.
        val hold = Vehicle.bus.hold(K.VEHICLE_SPEED)
        try {
            coroutineScope {
                launch {
                    ParkingStore.load(context)
                    ParkingStore.spot.filterNotNull().collect { spot ->
                        if (!DemoMode.isOn) send(CarLocation(spot.lat, spot.lng, spot.savedAt, saved = true))
                    }
                }
                // Where the car stands as the link comes up (just started, or parked with the phone near).
                LocationFeed.location.value
                    ?.takeIf { !DemoMode.isOn && System.currentTimeMillis() - it.time < FRESH_FIX_MS }
                    ?.let { send(it.toCarLocation()) }

                var moving = false
                var stoppedSince = 0L
                LocationFeed.location.filterNotNull().collect { fix ->
                    val kmh = speedKmh(fix) ?: return@collect
                    if (DemoMode.isOn) return@collect
                    when {
                        kmh >= MOVING_KMH -> {
                            moving = true
                            stoppedSince = 0L
                        }
                        moving && kmh <= STOPPED_KMH -> {
                            if (stoppedSince == 0L) {
                                stoppedSince = fix.time
                            } else if (fix.time - stoppedSince >= STOPPED_FOR_MS) {
                                send(fix.toCarLocation())
                                moving = false
                            }
                        }
                    }
                }
            }
        } finally {
            hold.release()
            if (acquired) withContext(NonCancellable + Dispatchers.Main) { LocationFeed.release() }
        }
    }

    /**
     * The car's speed at [fix]: the car's own when a source has it, else the fix's
     * own. Null for a fix without a speed (a network position), which can't
     * tell a stop from a drive.
     */
    private fun speedKmh(fix: Location): Int? =
        speedKmhNow(K.VEHICLE_SPEED) ?: if (fix.hasSpeed()) Math.round(fix.speed * 3.6f) else null

    private fun Location.toCarLocation() = CarLocation(latitude, longitude, time)
}
