package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The drive log's rules: when a trip is over, which eco drive it gets and what is logged. */
class DriveLogRulesTest {

    private val car = CarProfile.PRESET
    private val t0 = 1_000_000_000L

    /** A 12.4 km trip started at [t0], last moved 25 minutes later. */
    private val trip = TripState(startedAt = t0, distanceM = 12_400.0, movingMs = 20 * 60_000L, maxSpeedKmh = 91.6f, updatedAt = t0 + 25 * 60_000L)

    @Test
    fun aTripIsOverAfterTenMinutesStandingStill() {
        assertFalse(DriveLogRules.isOver(trip, trip.updatedAt + DriveLogRules.STOP_MS - 1))
        assertTrue(DriveLogRules.isOver(trip, trip.updatedAt + DriveLogRules.STOP_MS))
    }

    @Test
    fun aTripThatWentNowhereStartsAfreshOnlyWhenTheCarMovesAgain() {
        val parked = TripState(startedAt = t0, updatedAt = t0)
        assertFalse(DriveLogRules.startsAfresh(parked, t0 + DriveLogRules.STOP_MS - 1))
        assertTrue(DriveLogRules.startsAfresh(parked, t0 + DriveLogRules.STOP_MS))
        // A real drive is closed and logged by the drive log, never wiped here.
        assertFalse(DriveLogRules.startsAfresh(trip, trip.updatedAt + 60 * 60_000L))
    }

    @Test
    fun aShuffleInTheCarParkIsNotLogged() {
        assertFalse(DriveLogRules.isWorthLogging(trip.copy(distanceM = 499.0)))
        assertTrue(DriveLogRules.isWorthLogging(trip.copy(distanceM = 500.0)))
    }

    @Test
    fun theEngineSwitchedOffEndsTheTripButADroppedAdapterDoesNot() {
        val now = trip.updatedAt + CareRules.ENGINE_OFF_MS
        // Readings went on until the engine had been off for a few minutes: the drive is over.
        assertTrue(DriveLogRules.endsTrip(Drive(startedAt = t0, lastAt = now - 500), trip, now))
        // No readings for five minutes: the adapter dropped out, the car may well still be moving.
        assertFalse(DriveLogRules.endsTrip(Drive(startedAt = t0, lastAt = now - CareRules.GAP_MS), trip, now))
        // A trip that began after the engine's last reading (a fresh trip after the unit woke) is not ended by it.
        assertFalse(DriveLogRules.endsTrip(Drive(startedAt = t0, lastAt = now - 500), trip.copy(startedAt = now - 100, updatedAt = now - 100), now))
    }

    @Test
    fun theEcoDriveThatCoversTheTripIsUsed() {
        val yesterday = Drive(startedAt = t0 - 86_400_000L, lastAt = t0 - 80_000_000L)
        val mine = Drive(startedAt = t0 + 10_000L, lastAt = t0 + 26 * 60_000L)
        assertNull(DriveLogRules.ecoFor(trip, CareState(lastDrive = yesterday)))
        assertSame(mine, DriveLogRules.ecoFor(trip, CareState(lastDrive = mine)))
        // The drive under way comes first when it overlaps the trip.
        assertSame(mine, DriveLogRules.ecoFor(trip, CareState(drive = mine, lastDrive = yesterday)))
        assertSame(mine, DriveLogRules.ecoFor(trip, CareState(drive = Drive(startedAt = trip.updatedAt + 1), lastDrive = mine)))
    }

    @Test
    fun theSummaryCarriesTheTripAndTheEcoFigures() {
        val eco = Drive(startedAt = t0, lastAt = trip.updatedAt, distanceKm = 10.0, movingMs = 600_000, sweetMs = 300_000, hardAccel = 5, hardBrake = 5, clutchHolds = 1)
        val s = DriveLogRules.summary(trip, eco, car, ongoing = true)
        assertEquals(t0, s.startedAt)
        assertEquals(trip.updatedAt, s.endedAt)
        assertEquals(12.4, s.distanceKm, 1e-9)
        assertEquals(37, s.avgSpeedKmh)
        assertEquals(92, s.maxSpeedKmh)
        assertEquals(65, s.ecoScore)
        assertEquals(50, s.sweetPercent)
        assertEquals(5, s.hardAccel)
        assertEquals(1, s.clutchHolds)
        assertEquals(12.4 * car.typicalUse / 100, s.fuelLiters!!, 1e-9)
        assertEquals(s.fuelLiters!! * car.fuelPrice, s.fuelCost!!, 1e-9)
        assertEquals(car.currency, s.currency)
        assertTrue(s.ongoing)
    }

    @Test
    fun withoutEngineDataTheSummaryHasNoEcoScore() {
        val s = DriveLogRules.summary(trip, null, car, ongoing = false)
        assertNull(s.ecoScore)
        assertNull(s.sweetPercent)
        assertEquals(0, s.hardBrake)
        assertFalse(s.ongoing)
    }
}
