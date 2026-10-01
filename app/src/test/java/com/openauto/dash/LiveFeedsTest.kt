package com.openauto.dash

import com.openauto.dash.LocationFeed.SpeedReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveFeedsTest {

    @Test
    fun gpsFixPublishesItsSpeedInKmh() {
        assertEquals(SpeedReading.Kmh(90), LocationFeed.speedReading(hasFix = true, hasSpeed = true, speedMps = 25f, ageMs = 0L))
        assertEquals(SpeedReading.Kmh(0), LocationFeed.speedReading(hasFix = true, hasSpeed = true, speedMps = 0f, ageMs = 1_000L))
    }

    @Test
    fun networkFixWithoutSpeedLeavesTheReadoutAlone() {
        // A Wi-Fi / cell fix between two GPS fixes must not read as "0 km/h".
        assertEquals(SpeedReading.Keep, LocationFeed.speedReading(hasFix = true, hasSpeed = false, speedMps = 0f, ageMs = 0L))
    }

    @Test
    fun staleOrMissingFixClearsTheSpeed() {
        assertEquals(SpeedReading.None, LocationFeed.speedReading(hasFix = false, hasSpeed = false, speedMps = 0f, ageMs = Long.MAX_VALUE))
        assertEquals(SpeedReading.None, LocationFeed.speedReading(hasFix = true, hasSpeed = true, speedMps = 20f, ageMs = 5_000L))
        assertEquals(SpeedReading.None, LocationFeed.speedReading(hasFix = true, hasSpeed = false, speedMps = 0f, ageMs = 60_000L))
    }

    @Test
    fun parkedGpsDriftIsNotTravel() {
        // Seen on the unit: 100 m of drift while parked, 4 s "on the road", a 90 km/h average.
        val now = 1_000_000L
        val trip = TripState(startedAt = now - 60_000L, updatedAt = now - 60_000L)
        assertNull(LocationFeed.tripStep(trip, d = 8.0, dt = 1_000L, speedKmh = 1f, now = now))
        assertNull(LocationFeed.tripStep(trip, d = 1.0, dt = 1_000L, speedKmh = 30f, now = now))
    }

    @Test
    fun movingStepAddsDistanceAndTime() {
        val now = 1_000_000L
        val trip = TripState(startedAt = now - 1_000L, updatedAt = now - 1_000L)
        val next = LocationFeed.tripStep(trip, d = 25.0, dt = 1_000L, speedKmh = 90f, now = now)!!
        assertEquals(25.0, next.distanceM, 0.0)
        assertEquals(1_000L, next.movingMs)
        assertEquals(90f, next.maxSpeedKmh, 0f)
        assertEquals(90.0, next.avgSpeedKmh, 0.01)
    }
}
