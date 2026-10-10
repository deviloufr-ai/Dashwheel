package com.openauto.dash

import com.openauto.dash.link.DriveSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** The trip computer's second trip, the fuel and money on both, and the drive history's week. */
class TripTotalsTest {

    private val t0 = 1_700_000_000_000L
    private val car = CarProfile.PRESET

    private fun trip(startedAt: Long, m: Double, movingMs: Long = 0L) = TripState(startedAt = startedAt, distanceM = m, movingMs = movingMs, updatedAt = startedAt)

    @Test
    fun fuelAndCostFollowTheDistance() {
        assertEquals(6.0, TripMath.liters(100.0, 6.0), 1e-9)
        assertEquals(1.5, TripMath.liters(25.0, 6.0), 1e-9)
        assertEquals(2.625, TripMath.cost(1.5, 1.75)!!, 1e-9)
        // No price, or a price left at zero: no cost rather than a free drive.
        assertNull(TripMath.cost(1.5, null))
        assertNull(TripMath.cost(1.5, 0.0))
        val f = TripMath.figures(30.0, 30 * 60_000L, 5.0, 2.0)
        assertEquals(60.0, f.avgKmh, 1e-9)
        assertEquals(1.5, f.liters, 1e-9)
        assertEquals(3.0, f.cost!!, 1e-9)
    }

    @Test
    fun theMeasuredConsumptionAndTheLastPriceWinOverTheProfile() {
        assertEquals(5.4, TripMath.use(5.4, car), 1e-9)
        assertEquals(car.typicalUse, TripMath.use(null, car), 1e-9)
        assertEquals(1.92, TripMath.price(1.92, car)!!, 1e-9)
        assertEquals(car.fuelPrice, TripMath.price(null, car)!!, 1e-9)
        assertNull(TripMath.price(null, car.copy(fuelPrice = 0.0)))
    }

    @Test
    fun theDistanceKeepsItsDecimalOnlyUnderTen() {
        assertEquals("9.4", TripMath.figure(9.44, Locale.US))
        assertEquals("12", TripMath.figure(12.4, Locale.US))
        assertEquals("1.4 L", TripMath.litersText(1.44, Locale.US))
    }

    @Test
    fun theSameTripAddsOnlyWhatItMovedSinceLastSeen() {
        var s = SinceFillState(fillAt = t0)
        s = SinceFillRules.follow(s, trip(t0, 1_000.0, 60_000L))
        s = SinceFillRules.follow(s, trip(t0, 1_600.0, 90_000L))
        assertEquals(1_600.0, s.distanceM, 1e-9)
        assertEquals(90_000L, s.movingMs)
        // A trip restored a little behind after a reboot takes nothing back.
        s = SinceFillRules.follow(s, trip(t0, 1_500.0, 85_000L))
        assertEquals(1_600.0, s.distanceM, 1e-9)
        s = SinceFillRules.follow(s, trip(t0, 1_700.0, 95_000L))
        assertEquals(1_800.0, s.distanceM, 1e-9)
    }

    @Test
    fun aNewTripAddsAllItHasAndTheCountCarriesOn() {
        var s = SinceFillRules.follow(SinceFillState(fillAt = t0), trip(t0, 12_000.0))
        // The drive closed and a fresh trip started from nothing.
        s = SinceFillRules.follow(s, trip(t0 + 3_600_000L, 0.0))
        s = SinceFillRules.follow(s, trip(t0 + 3_600_000L, 5_000.0))
        assertEquals(17.0, s.km, 1e-9)
    }

    @Test
    fun aRefuelStartsTheCountAgainFromTheTripAsItStands() {
        var s = SinceFillRules.follow(SinceFillState(fillAt = t0), trip(t0, 40_000.0))
        // The same refuel seen again changes nothing.
        assertSame(s, SinceFillRules.refuel(s, t0))
        assertSame(s, SinceFillRules.refuel(s, null))
        s = SinceFillRules.refuel(s, t0 + 1_000L)
        assertEquals(0.0, s.distanceM, 1e-9)
        assertEquals(t0 + 1_000L, s.fillAt)
        // The same trip drives on from the pump: only what comes after counts.
        s = SinceFillRules.follow(s, trip(t0, 41_200.0))
        assertEquals(1_200.0, s.distanceM, 1e-9)
    }

    @Test
    fun theFirstCountIsTheDrivesLoggedSinceTheRefuel() {
        val drives = listOf(
            drive(t0 + 2 * 3_600_000L, 20.0, 25),
            drive(t0 + 3_600_000L, 10.0, 15),
            drive(t0 - 3_600_000L, 99.0, 60)
        )
        val s = SinceFillRules.seed(t0, drives)
        assertEquals(30.0, s.km, 1e-9)
        assertEquals(40 * 60_000L, s.movingMs)
        assertNull(SinceFillRules.seed(null, drives).fillAt)
    }

    @Test
    fun theWeekStartsOnTheCalendarsFirstDay() {
        val paris = TimeZone.getTimeZone("Europe/Paris")
        val fr = Calendar.getInstance(paris, Locale.FRANCE)
        // Thursday 8 October 2026, 15:20 in Paris: the week began on Monday the 5th at midnight.
        val now = Calendar.getInstance(paris).apply { clear(); set(2026, Calendar.OCTOBER, 8, 15, 20) }.timeInMillis
        val monday = Calendar.getInstance(paris).apply { clear(); set(2026, Calendar.OCTOBER, 5, 0, 0) }.timeInMillis
        assertEquals(monday, DriveHistory.weekStart(now, fr))
        // A US calendar starts on Sunday the 4th.
        val us = Calendar.getInstance(paris, Locale.US)
        val sunday = Calendar.getInstance(paris).apply { clear(); set(2026, Calendar.OCTOBER, 4, 0, 0) }.timeInMillis
        assertEquals(sunday, DriveHistory.weekStart(now, us))
        // On the first day itself, from its midnight.
        assertEquals(monday, DriveHistory.weekStart(monday + 60_000L, fr))
    }

    @Test
    fun theWeekSumsItsDrivesAndTheirCost() {
        val from = t0
        val drives = listOf(
            drive(t0 + 7_200_000L, 20.0, 25, cost = 2.0),
            drive(t0 + 3_600_000L, 10.0, 15, cost = null),
            drive(t0 - 1L, 99.0, 60, cost = 9.0)
        )
        val w = DriveHistory.week(drives, from)
        assertEquals(30.0, w.km, 1e-9)
        assertEquals(2, w.drives)
        assertEquals(2.0, w.cost!!, 1e-9)
        assertEquals("€", w.currency)
        assertNull(DriveHistory.week(listOf(drive(t0, 5.0, 10, cost = null)), from).cost)
    }

    @Test
    fun theDriveUnderWayComesFirstAndOnlyOnce() {
        val logged = listOf(drive(t0, 10.0, 15), drive(t0 - 86_400_000L, 5.0, 10))
        val now = drive(t0 + 3_600_000L, 3.0, 5).copy(ongoing = true)
        val list = DriveHistory.recent(logged, now)
        assertEquals(listOf(now.startedAt, t0, t0 - 86_400_000L), list.map { it.startedAt })
        // Reported again under the same start: still one row.
        assertEquals(3, DriveHistory.recent(logged + now.copy(ongoing = false), now).size)
        assertEquals(2, DriveHistory.recent(logged, null).size)
    }

    private fun drive(startedAt: Long, km: Double, minutes: Int, cost: Double? = 1.0) = DriveSummary(
        startedAt = startedAt, endedAt = startedAt + minutes * 60_000L, distanceKm = km, movingMs = minutes * 60_000L,
        maxSpeedKmh = 90, fuelCost = cost, currency = if (cost != null) "€" else null
    )
}
