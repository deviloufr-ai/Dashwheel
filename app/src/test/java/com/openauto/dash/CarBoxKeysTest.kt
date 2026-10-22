package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The CAN box's own wheel key codes: read off its stream, and only for the PSA protocol. */
class CarBoxKeysTest {

    private fun mcu(vararg v: Int) = ByteArray(v.size + 1) { if (it == 0) 0x41 else v[it - 1].toByte() }

    @Test
    fun aPressAndItsReleaseGiveTheCodeThenZero() {
        val r = BoxKeyReader()
        // Voice on the C4 Picasso, as the MCU hands it over: 41 fd 04 02 29 2f, then 02 00.
        assertEquals(listOf(0x29), r.push(mcu(0xfd, 0x04, 0x02, 0x29, 0x2f), 1))
        assertEquals(listOf(0), r.push(mcu(0xfd, 0x04, 0x02, 0x00, 0x06), 1))
    }

    @Test
    fun aRepeatOfTheHeldCodeAndOtherFramesGiveNothing() {
        val r = BoxKeyReader()
        assertEquals(listOf(0x30), r.push(mcu(0xfd, 0x04, 0x02, 0x30, 0x36), 1))
        assertEquals(emptyList<Int>(), r.push(mcu(0xfd, 0x04, 0x02, 0x30, 0x36), 1))
        assertEquals(emptyList<Int>(), r.push(mcu(0xfd, 0x04, 0x3a, 0x00, 0x3e), 1))
        // A wrong sum is no frame.
        assertEquals(emptyList<Int>(), r.push(mcu(0xfd, 0x04, 0x02, 0x00, 0x07), 1))
    }

    @Test
    fun aPressWhoseReleaseTheBoxDroppedCountsAgainAfterAWhile() {
        val r = BoxKeyReader(holdMaxMs = 3_000)
        assertEquals(listOf(0x29), r.push(mcu(0xfd, 0x04, 0x02, 0x29, 0x2f), 1, now = 1_000))
        // Held: the repeats of the code say nothing new.
        assertEquals(emptyList<Int>(), r.push(mcu(0xfd, 0x04, 0x02, 0x29, 0x2f), 1, now = 2_000))
        // The release never came; the same button pressed again later is a press.
        assertEquals(listOf(0x29), r.push(mcu(0xfd, 0x04, 0x02, 0x29, 0x2f), 1, now = 4_500))
        assertEquals(listOf(0), r.push(mcu(0xfd, 0x04, 0x02, 0x00, 0x06), 1, now = 4_600))
        // Let go stays let go, however long.
        assertEquals(emptyList<Int>(), r.push(mcu(0xfd, 0x04, 0x02, 0x00, 0x06), 1, now = 60_000))
    }

    @Test
    fun onlyARaiseBoxSetToAPsaCarSpeaksThisProtocol() {
        assertTrue(CarBoxKeys.psa("[Raise-2]-[Citroen]-[Quatre]-[08-16]"))
        assertTrue(CarBoxKeys.psa("[Raise-2]-[Peugeot]-[308]-[13-Newest]"))
        assertFalse(CarBoxKeys.psa("[Raise-2]-[Volkswagen]-[Sharan]-[10-15]"))
        assertFalse(CarBoxKeys.psa("[Hiworld]-[Citroen]-[C4]-[08-16]"))
        assertFalse(CarBoxKeys.psa(null))
    }

    @Test
    fun theMapNamesABoxKeyByItsCode() {
        assertEquals("0x29", WheelKey.vendor(CarBoxKeys.VENDOR, 0x29).label)
        assertEquals("v:box:41", WheelKey.vendor(CarBoxKeys.VENDOR, 0x29).id)
    }
}
