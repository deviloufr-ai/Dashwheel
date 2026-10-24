package com.openauto.dash

import com.openauto.dash.vehicle.levelFromRangePct
import com.openauto.dash.vehicle.rangeFromLevelKm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fuel & range: formatting the fuel level with the range (the estimates are the computed.range provider's), and finding the range word. */
class FuelRangeTest {

    @Test
    fun nothingKnownIsNull() {
        assertNull(fuelInfo(fuelPct = null, rangeKm = null))
    }

    @Test
    fun fuelAloneIsNoInfoUntilTheRangeIsEstimated() {
        assertNull(fuelInfo(fuelPct = 50, rangeKm = null))
        // 30 L at 6.5 L/100 km: what the computed.range provider publishes, taken as the estimate it is.
        assertEquals(461.0, rangeFromLevelKm(50, TANK_LITERS, AVG_L_PER_100KM), 0.0)
        val f = fuelInfo(fuelPct = 50, rangeKm = 461, rangeEstimated = true)!!
        assertEquals(461, f.rangeKm)
        assertFalse(f.rangeFromCar)
        assertEquals(AVG_L_PER_100KM, f.avgUse, 0.0)
        assertEquals("CANbox", f.source)
    }


    @Test
    fun theCarsRangeWinsAndGivesTheRealConsumption() {
        // 44% of 60 L = 26.4 L; the car says 520 km -> it's counting on ~5.1 L/100 km.
        val f = fuelInfo(fuelPct = 44, rangeKm = 520)!!
        assertEquals(520, f.rangeKm)
        assertEquals(44, f.percent)
        assertTrue(f.rangeFromCar)
        assertFalse(f.percentEstimated)
        assertEquals(5.08, f.avgUse, 0.01)
    }

    @Test
    fun anImplausibleConsumptionFallsBackToTheUsualOne() {
        val f = fuelInfo(fuelPct = 5, rangeKm = 600)!!
        assertEquals(AVG_L_PER_100KM, f.avgUse, 0.0)
        assertEquals(600, f.rangeKm)
    }

    @Test
    fun rangeAloneWorksTheLevelBack() {
        assertNull(fuelInfo(fuelPct = null, rangeKm = 520))
        assertEquals(56.0, levelFromRangePct(520, TANK_LITERS, AVG_L_PER_100KM), 0.0) // 33.8 L of 60
        val f = fuelInfo(fuelPct = 56, rangeKm = 520, percentEstimated = true)!!
        assertEquals(520, f.rangeKm)
        assertEquals(56, f.percent)
        assertTrue(f.percentEstimated)
        assertEquals("CANbox", f.source)
    }

    @Test
    fun anEmptyTankGivesNoRange() {
        assertEquals(0.0, rangeFromLevelKm(0, TANK_LITERS, AVG_L_PER_100KM), 0.0)
        assertEquals(0, fuelInfo(fuelPct = 0, rangeKm = 0, rangeEstimated = true)!!.percent)
    }

    @Test
    fun obdFuelWithTheCarsRange() {
        val f = fuelInfo(fuelPct = 50, rangeKm = 400, fromObd = true)!!
        assertEquals("OBD", f.source)
        assertEquals(400, f.rangeKm)
        assertEquals(7.5, f.avgUse, 0.01)
    }

    @Test
    fun theSourceIsOnlyObdWhenTheLevelIsKnown() {
        // The adapter's level was not a reading: the range alone speaks, and it is the car's.
        assertEquals("CANbox", fuelInfo(fuelPct = 56, rangeKm = 400, fromObd = true, percentEstimated = true)!!.source)
    }

    @Test
    fun anEmptyTankWithRangeLeftIsNotPublishedAsALevel() {
        // The car app sends 0 L while the trip computer says 330 km: that is no level at all (the provider drops it).
        assertNull(fuelPercentToPublish(0, 330))
        assertEquals(0, fuelPercentToPublish(0, null))
        assertEquals(0, fuelPercentToPublish(0, 0))
        assertEquals(5, fuelPercentToPublish(5, 330))
        assertNull(fuelPercentToPublish(null, 330))
        // ...so the level is worked back from the range, as before.
        assertEquals(36.0, levelFromRangePct(330, TANK_LITERS, AVG_L_PER_100KM), 0.0)
        val f = fuelInfo(fuelPct = 36, rangeKm = 330, percentEstimated = true)!!
        assertEquals(330, f.rangeKm)
        assertEquals(36, f.percent) // 21.5 L of 60
        assertEquals(21.45, f.liters, 0.01)
        assertTrue(f.percentEstimated)
    }

    @Test
    fun findsTheRangeWordInWholeKm() {
        val frames = mapOf(
            "51" to listOf(0x33, 0x12, 0x02, 0x08, 0x00),
            "65.05" to listOf(0x41, 0xFD, 0x05, 0x10, 0x20)
        )
        val found = RangeMapping.candidates(frames, 520)
        assertEquals(RangeMapping("51", 2, bigEndian = true, scale = 1), found.first())
        assertEquals(520, found.first().decode(frames.getValue("51")))
    }

    @Test
    fun findsTheRangeWordInTenthsAndLittleEndian() {
        val frames = mapOf("52" to listOf(0x34, 0x50, 0x14)) // 5200 little-endian
        val found = RangeMapping.candidates(frames, 520)
        assertEquals(listOf(RangeMapping("52", 1, bigEndian = false, scale = 10)), found)
    }

    @Test
    fun exactHitsRankAheadOfNearOnes() {
        val frames = mapOf("a" to listOf(0x02, 0x09), "b" to listOf(0x02, 0x08))
        assertEquals("b", RangeMapping.candidates(frames, 520).first().key)
    }

    @Test
    fun fillerValuesAreNotARange() {
        val m = RangeMapping("51", 0, bigEndian = true, scale = 1)
        assertNull(m.decode(listOf(0xFF, 0xFF)))
        assertNull(m.decode(listOf(0x02)))
        assertEquals(0, m.decode(listOf(0x00, 0x00)))
    }
}
