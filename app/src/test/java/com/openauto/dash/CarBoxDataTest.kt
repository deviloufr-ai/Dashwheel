package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The CAN box data the QF car app shares, decoded from its byte layouts. */
class CarBoxDataTest {

    private fun bytes(size: Int, vararg set: Pair<Int, Int>): ByteArray =
        ByteArray(size).also { b -> set.forEach { (i, v) -> b[i] = v.toByte() } }

    @Test
    fun rejectsOtherMessages() {
        assertNull(shareType(null))
        assertNull(shareType(byteArrayOf(0x2E)))
        assertNull(shareType(byteArrayOf(0x10, 2)))
        assertNull(parseCarBody(bytes(79, 0 to 0x2E, 1 to SHARE_AC)))
        assertNull(parseClimate(bytes(21, 0 to 0x2E, 1 to SHARE_BODY)))
    }

    @Test
    fun carBodyNumbersAndFlags() {
        val d = bytes(
            79, 0 to 0x2E, 1 to SHARE_BODY,
            2 to 0b1000_0110, // high beam, left turn, hazard
            5 to 0b0000_1000, // driver belt unfastened
            6 to 0b1001_0000, // ignition, handbrake
            7 to 0x00, 8 to 0x5A, // 90 km/h
            9 to 0x0B, 10 to 0xB8, // 3000 rpm
            11 to 0x01, 12 to 0x9A, // 41.0
            15 to 0x01, 16 to 0xE2, 17 to 0x40, // 12345.6
            18 to 0x14, 19 to 0x50 // 520.0
        )
        val body = parseCarBody(d)!!
        assertEquals(90, body.speedKmh)
        assertEquals(3000, body.rpm)
        assertEquals(41.0f, body.fuelLeft!!, 0.001f)
        assertEquals(12345.6f, body.odometer!!, 0.01f)
        assertEquals(520.0f, body.range!!, 0.001f)
        assertTrue(body.highBeam)
        assertTrue(body.turnLeft)
        assertTrue(body.hazard)
        assertFalse(body.turnRight)
        assertTrue(body.driverBeltUnfastened)
        assertFalse(body.passengerBeltUnfastened)
        assertTrue(body.ignition)
        assertTrue(body.handbrake)
        assertFalse(body.gearFlag)
    }

    @Test
    fun valuesTheCarDoesNotSendAreNull() {
        val d = bytes(79, 0 to 0x2E, 1 to SHARE_BODY, 7 to 0xFF, 8 to 0xFF, 15 to 0xFF, 16 to 0xFF, 17 to 0xFF)
        val body = parseCarBody(d)!!
        assertNull(body.speedKmh)
        assertNull(body.odometer)
    }

    @Test
    fun climate() {
        val d = bytes(
            21, 0 to 0x2E, 1 to SHARE_AC,
            2 to 0b1101_0000, // power, a/c, recirculation
            3 to 0b1000_0000, // front defrost
            4 to 0b0100_0011, // air to the face, fan 3
            7 to 43, // 21.5
            8 to 0xFF, // HI
            10 to 0x20 // seat heat left 2
        )
        val c = parseClimate(d)!!
        assertTrue(c.power)
        assertTrue(c.ac)
        assertTrue(c.recirculation)
        assertFalse(c.auto)
        assertTrue(c.frontDefrost)
        assertTrue(c.airFace)
        assertEquals(3, c.fan)
        assertEquals(ClimateTemp.Degrees(21.5f), c.left)
        assertEquals(ClimateTemp.High, c.right)
        assertEquals(2, c.seatHeatLeft)
        assertEquals(0, c.seatHeatRight)
        assertEquals(ClimateTemp.Low, parseClimate(bytes(21, 0 to 0x2E, 1 to SHARE_AC, 7 to 0))!!.left)
        assertEquals(ClimateTemp.None, parseClimate(bytes(21, 0 to 0x2E, 1 to SHARE_AC, 7 to 0xFE))!!.left)
    }

    @Test
    fun radarLevels() {
        val d = ByteArray(42) { 0xFF.toByte() }.also {
            it[0] = 0x2E; it[1] = SHARE_RADAR.toByte()
            // Rear sensors present: nothing, 4, 1 (closest), 7, nothing, nothing.
            listOf(0, 4, 1, 7, 0, 0).forEachIndexed { i, v -> it[8 + i] = v.toByte() }
        }
        val r = parseRadar(d)!!
        assertEquals(listOf<Int?>(null, null, null, null, null, null), r.front)
        assertEquals(listOf<Int?>(0, 4, 1, 7, 0, 0), r.rear)
        assertTrue(r.present)
        assertTrue(r.active)
        assertEquals(1, r.closest)
    }

    @Test
    fun radarWithNothingNear() {
        val d = ByteArray(42).also { it[0] = 0x2E; it[1] = SHARE_RADAR.toByte() }
        val r = parseRadar(d)!!
        assertTrue(r.present)
        assertFalse(r.active)
        assertNull(r.closest)
    }

    @Test
    fun doorBitsAsTheCarAppPacksThem() {
        // Front left and tailgate open.
        assertEquals(0x88, parseDoorBits(byteArrayOf(0x2E, SHARE_DOORS.toByte(), 0x88.toByte())))
        assertNull(parseDoorBits(byteArrayOf(0x2E, SHARE_DOORS.toByte())))
        assertNull(parseDoorBits(byteArrayOf(0x2E, SHARE_AC.toByte(), 0x88.toByte())))
    }

    private fun body(vararg set: Pair<Int, Int>) = parseCarBody(bytes(79, 0 to 0x2E, 1 to SHARE_BODY, *set))!!

    @Test
    fun lightsAsTheDriverWouldSayThem() {
        assertEquals(emptyList<CarLight>(), lightsOn(body()))
        // Dipped beam includes the sidelights: only the strongest is named.
        assertEquals(listOf(CarLight.DIPPED), lightsOn(body(2 to 0b0100_0000, 3 to 0b1000_0000)))
        assertEquals(listOf(CarLight.MAIN_BEAM, CarLight.REAR_FOG), lightsOn(body(2 to 0b1101_0000)))
        assertEquals(listOf(CarLight.HAZARD, CarLight.SIDELIGHTS), lightsOn(body(2 to 0b0000_0010, 3 to 0b1000_0000)))
    }
}
