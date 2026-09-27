package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fuel & range: combining the fuel level with the car's own range, and finding the range word. */
class FuelRangeTest {

    @Test
    fun nothingKnownIsNull() {
        assertNull(fuelInfo(canFuel = null, obdFuel = 0, canRange = null))
    }

    @Test
    fun fuelAloneEstimatesTheRange() {
        val f = fuelInfo(canFuel = 50, obdFuel = 0, canRange = null)!!
        assertEquals(461, f.rangeKm) // 30 L at 6.5 L/100 km
        assertFalse(f.rangeFromCar)
        assertEquals("CANbox", f.source)
    }

    @Test
    fun theCarsRangeWinsAndGivesTheRealConsumption() {
        // 44% of 60 L = 26.4 L; the car says 520 km → it's counting on ~5.1 L/100 km.
        val f = fuelInfo(canFuel = 44, obdFuel = 0, canRange = 520)!!
        assertEquals(520, f.rangeKm)
        assertEquals(44, f.percent)
        assertTrue(f.rangeFromCar)
        assertFalse(f.percentEstimated)
        assertEquals(5.08, f.avgUse, 0.01)
    }

    @Test
    fun anImplausibleConsumptionFallsBackToTheUsualOne() {
        val f = fuelInfo(canFuel = 5, obdFuel = 0, canRange = 600)!!
        assertEquals(AVG_L_PER_100KM, f.avgUse, 0.0)
        assertEquals(600, f.rangeKm)
    }

    @Test
    fun rangeAloneWorksTheLevelBack() {
        val f = fuelInfo(canFuel = null, obdFuel = 0, canRange = 520)!!
        assertEquals(520, f.rangeKm)
        assertEquals(56, f.percent) // 33.8 L of 60
        assertTrue(f.percentEstimated)
        assertEquals("CANbox", f.source)
    }

    @Test
    fun anEmptyTankWithRangeLeftIsNoLevelAtAll() {
        // The car app sends 0 L while the trip computer says 330 km: worked back from the range.
        val f = fuelInfo(canFuel = 0, obdFuel = 0, canRange = 330)!!
        assertEquals(330, f.rangeKm)
        assertEquals(36, f.percent) // 21.5 L of 60
        assertEquals(21.45, f.liters, 0.01)
        assertTrue(f.percentEstimated)
        assertEquals("CANbox", f.source)
        // The OBD's level, when there is one, is used instead.
        assertEquals(50, fuelInfo(canFuel = 0, obdFuel = 50, canRange = 330)!!.percent)
        // Without a range, an empty tank is taken as it reads.
        assertEquals(0, fuelInfo(canFuel = 0, obdFuel = 0, canRange = null)!!.percent)
    }

    @Test
    fun obdFuelWithTheCarsRange() {
        val f = fuelInfo(canFuel = null, obdFuel = 50, canRange = 400)!!
        assertEquals("OBD", f.source)
        assertEquals(400, f.rangeKm)
        assertEquals(7.5, f.avgUse, 0.01)
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
