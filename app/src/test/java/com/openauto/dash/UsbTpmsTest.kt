package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbTpmsTest {

    private fun hex(s: String): ByteArray = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun aFrameIsDecoded() {
        // Front left, 152 × 3.44 kPa, 68 − 50 °C (from a real receiver).
        val frame = tpmsFrameAt(hex("55 AA 08 00 98 44 00 2B"), 0)!!
        assertEquals(TyrePos.FRONT_LEFT, frame.tyre)
        assertEquals(523, frame.kPa)
        assertEquals(18, frame.celsius)
        assertFalse(frame.leak)
    }

    @Test
    fun theTyresAreTheAxleThenTheSide() {
        assertEquals(TyrePos.FRONT_LEFT, usbTpmsPosition(0x00))
        assertEquals(TyrePos.FRONT_RIGHT, usbTpmsPosition(0x01))
        assertEquals(TyrePos.REAR_LEFT, usbTpmsPosition(0x10))
        assertEquals(TyrePos.REAR_RIGHT, usbTpmsPosition(0x11))
        // The spare slot reports made-up values with no sensor.
        assertNull(usbTpmsPosition(0x05))
    }

    @Test
    fun aWrongChecksumOrStartIsNoFrame() {
        assertNull(tpmsFrameAt(hex("55 AA 08 00 98 44 00 FF"), 0))
        assertNull(tpmsFrameAt(hex("55 AA 07 00 98 44 00 2C"), 0))
        assertNull(tpmsFrameAt(hex("55 AA 08 00 98 44 00"), 0))
    }

    @Test
    fun statusEightIsALeak() {
        // 55 AA 08 10 40 46 08, then their XOR.
        val bytes = hex("55 AA 08 10 40 46 08 00")
        bytes[7] = bytes.take(7).fold(0) { x, b -> x xor (b.toInt() and 0xFF) }.toByte()
        val frame = tpmsFrameAt(bytes, 0)!!
        assertEquals(TyrePos.REAR_LEFT, frame.tyre)
        assertTrue(frame.leak)
        assertTrue(frame.toTyre(0).leak)
        // The byte as sent goes along, for the sensors sheet: its other bits are not known yet.
        assertEquals(8, frame.toTyre(0).status)
    }

    @Test
    fun framesAreFoundInTheStreamAndAHalfOneWaits() {
        // A stream joined part way: the tail of a frame, three whole ones, the start of the next.
        val stream = hex("aa0855aa08009844002b55aa08019b45002855aa0805495300e855aa0800")
        val reader = TpmsFrameReader()
        val frames = reader.feed(stream)
        assertEquals(listOf(0x00, 0x01, 0x05), frames.map { it.position })
        assertEquals(533, frames[1].kPa)
        assertEquals(19, frames[1].celsius)
        // The rest of the last one comes in the next USB packet.
        val next = reader.feed(hex("9844002b"))
        assertEquals(1, next.size)
        assertEquals(TyrePos.FRONT_LEFT, next[0].tyre)
    }

    @Test
    fun aFrameSplitAnywhereIsStillFound() {
        val frame = hex("55 AA 08 11 A0 4B 00 00")
        frame[7] = frame.take(7).fold(0) { x, b -> x xor (b.toInt() and 0xFF) }.toByte()
        for (cut in 1 until frame.size) {
            val reader = TpmsFrameReader()
            val first = reader.feed(frame.copyOfRange(0, cut))
            val second = reader.feed(frame.copyOfRange(cut, frame.size))
            assertEquals("cut at $cut", 0, first.size)
            assertEquals("cut at $cut", TyrePos.REAR_RIGHT, second.single().tyre)
        }
    }

    @Test
    fun noiseIsSkipped() {
        val reader = TpmsFrameReader()
        assertTrue(reader.feed(hex("00 FF 55 13 AA 55 AA 08 00 98 44 00 FF")).isEmpty())
        assertEquals(1, reader.feed(hex("55 AA 08 00 98 44 00 2B")).size)
    }

    @Test
    fun onlyThePartOfTheBufferReadCounts() {
        val buffer = ByteArray(64)
        hex("55 AA 08 00 98 44 00 2B").copyInto(buffer)
        assertEquals(1, TpmsFrameReader().feed(buffer, 8).size)
    }
}
