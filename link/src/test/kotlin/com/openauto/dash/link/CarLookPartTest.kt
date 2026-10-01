package com.openauto.dash.link

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarLookPartTest {
    @Test
    fun fullPartFitsInOneFrame() {
        val data = Base64.getEncoder().encodeToString(ByteArray(CarLookPart.PART_BYTES) { it.toByte() })
        val bytes = LinkCodec.encode(CarLookPart(id = Long.MAX_VALUE, index = 95, count = 96, name = "c4-picasso.dwcar", data = data))
        assertTrue("${bytes.size} bytes", bytes.size <= LinkSession.MAX_MESSAGE)
    }

    @Test
    fun roundTrip() {
        val part = CarLookPart(7, 0, 1, "car.png", "AAEC")
        assertEquals(part, LinkCodec.decode(LinkCodec.encode(part)))
        assertEquals(CarLookAck(7, true), LinkCodec.decode(LinkCodec.encode(CarLookAck(7, true))))
    }
}
