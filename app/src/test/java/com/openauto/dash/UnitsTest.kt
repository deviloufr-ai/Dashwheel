package com.openauto.dash

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** The driver's units: conversions, formatting, the clock and what Auto means where. */
class UnitsTest {

    private val metric = UnitSystem()
    private val us = UnitSystem(imperial = true, fahrenheit = true, economy = Economy.MPG_US, clock24 = false)
    private val uk = UnitSystem(imperial = true, fahrenheit = false, economy = Economy.MPG_UK, clock24 = true)
    private lateinit var locale: Locale

    @Before
    fun setUp() {
        // Decimal separators follow the default locale: pin it so the expected text is stable.
        locale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() {
        Locale.setDefault(locale)
    }

    private fun at(hour: Int, minute: Int): Date =
        Calendar.getInstance().apply { set(2026, Calendar.SEPTEMBER, 28, hour, minute, 0) }.time

    @Test
    fun speedsConvertToMphRounded() {
        assertEquals(62, us.speed(100))
        assertEquals(0, us.speed(0))
        assertEquals(81, us.speed(130))
        assertEquals(81, us.speed(130.0f))
        assertEquals(19, us.speed(30.5))
        assertEquals(100, metric.speed(100))
        assertEquals("62 mph", us.speedText(100))
        assertEquals("100 km/h", metric.speedText(100))
    }

    @Test
    fun dialScalesRoundUpToTheNextTenMph() {
        assertEquals(150, us.speedScale(240))
        assertEquals(140, us.speedScale(220))
        assertEquals(120, us.speedScale(180))
        assertEquals(240, metric.speedScale(240))
    }

    @Test
    fun distancesConvertBothWays() {
        assertEquals(9, us.distance(15))
        assertEquals(298, us.distance(480))
        assertEquals(6.2137, us.distance(10.0), 0.0001)
        assertEquals(16.09344, us.km(10.0), 1e-9)
        assertEquals(10.0, metric.km(10.0), 0.0)
        assertEquals("298 mi", us.distanceText(480))
    }

    @Test
    fun temperaturesConvertToFahrenheit() {
        assertEquals(32, us.temp(0))
        assertEquals(212, us.temp(100))
        assertEquals(-40, us.temp(-40))
        assertEquals(71, us.temp(21.5))
        assertEquals(21, metric.temp(21.4))
        assertEquals("203 °F", us.tempText(95))
        assertEquals("68°F", us.tempTight(20.0))
        assertEquals(68.0, us.tempExact(20.0), 1e-9)
    }

    @Test
    fun fuelEconomyInEveryUnit() {
        assertEquals(5.0, metric.economy(5.0)!!, 0.0)
        assertEquals(56.5, uk.economy(5.0)!!, 0.01)
        assertEquals(47.04, us.economy(5.0)!!, 0.01)
        assertEquals(20.0, UnitSystem(economy = Economy.KM_PER_L).economy(5.0)!!, 1e-9)
        // Coasting burns nothing: no mpg to give.
        assertNull(us.economy(0.0))
        assertEquals("5.4 L/100 km", metric.economyText(5.4))
        assertEquals("56.5 mpg", uk.economyText(5.0))
        assertEquals("-- mpg", us.economyText(0.0))
        assertEquals("20.0 km/L", UnitSystem(economy = Economy.KM_PER_L).economyText(5.0))
    }

    @Test
    fun shortDistancesGoFromFeetToMiles() {
        assertEquals("350 m", metric.shortDistance(350.0))
        assertEquals("1.2 km", metric.shortDistance(1234.0))
        assertEquals("330 ft", us.shortDistance(100.0))
        assertEquals("0.1 mi", us.shortDistance(170.0))
        assertEquals("1.0 mi", us.shortDistance(1609.344))
    }

    @Test
    fun theClockFollowsTheChoice() {
        val t = at(14, 5)
        assertEquals("14:05", metric.time(t, Locale.US))
        assertEquals("2:05 PM", us.time(t, Locale.US))
        assertEquals("2:05", java.text.SimpleDateFormat(us.digitsPattern(), Locale.US).format(t))
        assertEquals("PM", us.amPm(t, Locale.US))
        assertNull(metric.amPm(t, Locale.US))
        // No leading zero on a 12-hour clock, midnight reads 12.
        assertEquals("9:30 AM", us.time(at(9, 30), Locale.US))
        assertEquals("12:00 AM", us.time(at(0, 0), Locale.US))
        assertEquals("09:30", metric.time(at(9, 30), Locale.US))
    }

    @Test
    fun aClockSplitsIntoDigitsAndMarker() {
        assertEquals("2:05" to "PM", splitClock("2:05 PM"))
        assertEquals("14:05" to null, splitClock("14:05"))
    }

    @Test
    fun theSpeedCorrectionReadsInEitherUnit() {
        assertEquals("+3 km/h", speedOffsetText(3, metric))
        assertEquals("+1.9 mph", speedOffsetText(3, us))
        assertEquals("−0.6 mph", speedOffsetText(-1, us))
        assertEquals("0 mph", speedOffsetText(0, us))
    }

    @Test
    fun autoFollowsTheCountry() {
        val usa = Units.defaultsFor("US", null)
        assertTrue(usa.imperial)
        assertTrue(usa.fahrenheit)
        assertEquals(Economy.MPG_US, usa.economy)
        assertFalse(usa.clock24)

        val gb = Units.defaultsFor("GB", null)
        assertTrue(gb.imperial)
        assertFalse(gb.fahrenheit)
        assertEquals(Economy.MPG_UK, gb.economy)
        assertTrue(gb.clock24)

        for (country in listOf("FR", "DE", "ES", "IT", "PT", "NL", "PL", "BE", "")) {
            assertEquals(country, UnitSystem(), Units.defaultsFor(country, null))
        }
        assertEquals(usa, Units.defaultsFor("us", null))
    }

    @Test
    fun aUnitLeftInEnUsKeepsANonEnglishDriverMetric() {
        assertEquals("US", Units.autoCountry("US", "en"))
        assertEquals("GB", Units.autoCountry("GB", "en"))
        assertEquals(UnitSystem(), Units.defaultsFor(Units.autoCountry("US", "fr"), true))
        assertEquals(UnitSystem(), Units.defaultsFor(Units.autoCountry("GB", "de"), true))
    }

    @Test
    fun theClockAutoFollowsTheUnitsSwitch() {
        assertTrue(Units.defaultsFor("US", true).clock24)
        assertFalse(Units.defaultsFor("FR", false).clock24)
        assertTrue(Units.defaultsFor("FR", true).clock24)
    }

    @Test
    fun aChoiceOverridesAuto() {
        val chosen = UnitChoices(DistanceChoice.METRIC, TempChoice.FAHRENHEIT, EconomyChoice.KM_PER_L, ClockChoice.H12)
        val u = Units.resolve(chosen, "GB", true)
        assertFalse(u.imperial)
        assertTrue(u.fahrenheit)
        assertEquals(Economy.KM_PER_L, u.economy)
        assertFalse(u.clock24)
        assertEquals(Units.defaultsFor("US", false), Units.resolve(UnitChoices(), "US", false))
        assertEquals(uk, Units.resolve(UnitChoices(clock = ClockChoice.H24), "GB", false))
    }

    /** The main user: France, Auto everywhere. Everything must read exactly as before units existed. */
    @Test
    fun frenchDefaultsFormatLikeToday() {
        Locale.setDefault(Locale.FRANCE)
        val fr = Units.resolve(UnitChoices(), "FR", true)
        assertEquals(metric, fr)
        assertEquals("km/h", fr.speedUnit)
        assertEquals("90 km/h", fr.speedText(90))
        assertEquals(88, fr.speed(88.4f))
        assertEquals("95 °C", fr.tempText(95))
        assertEquals("20°C", fr.tempTight(20.4))
        assertEquals("480 km", fr.distanceText(480))
        assertEquals(12.5, fr.distance(12.5), 0.0)
        assertEquals("5,4 L/100 km", fr.economyText(5.4))
        assertEquals("350 m", fr.shortDistance(350.0))
        assertEquals("1,2 km", fr.shortDistance(1234.0))
        assertEquals("14:05", fr.time(at(14, 5), Locale.FRANCE))
        assertEquals("09:30", fr.time(at(9, 30), Locale.FRANCE))
        assertNull(fr.amPm(at(14, 5), Locale.FRANCE))
        assertEquals("+3 km/h", speedOffsetText(3, fr))
        assertEquals(240, fr.speedScale(240))
        assertEquals("3,5 km", FuelPrices.formatDistance(3.456, fr))
        assertEquals("12 km", FuelPrices.formatDistance(12.7, fr))
        assertEquals("350 m", formatDistance(350f, fr))
        assertEquals("1,2 km", formatDistance(1234f, fr))
        assertEquals("14:05", formatClock(at(14, 5).time, fr))
    }

    @Test
    fun anUpdatedInstallKeepsWhatItShowedEvenOnAUsUnit() {
        // A French driver on a head unit left in US English with a 12-hour clock.
        val kept = Units.resolve(Units.UPGRADE_CHOICES, "US", system24 = false)
        assertEquals(false, kept.imperial)
        assertEquals(false, kept.fahrenheit)
        assertEquals(Economy.L_PER_100KM, kept.economy)
        assertEquals(true, kept.clock24)
    }
}
