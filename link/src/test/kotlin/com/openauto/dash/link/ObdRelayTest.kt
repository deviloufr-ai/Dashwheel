package com.openauto.dash.link

import org.junit.Assert.assertEquals
import org.junit.Test

class ObdRelayTest {
    @Test
    fun theRelayMessagesRoundTripThroughTheCodec() {
        listOf(ObdOffer("OBDII"), ObdOffer(null), ObdOpen, ObdClose, ObdRelayState(true), ObdRelayState(false, "refused"))
            .forEach { assertEquals(it, LinkCodec.decode(LinkCodec.encode(it))) }
    }

    @Test
    fun adapterBytesSurviveTheTrip() {
        // An ELM327 reply: carriage returns, the prompt, and any byte a clone may throw in.
        val raw = "41 0C 1A F8\r\r>" + String(ByteArray(256) { it.toByte() }, Charsets.ISO_8859_1)
        val back = LinkCodec.decode(LinkCodec.encode(ObdRx(raw))) as ObdRx
        assertEquals(raw, back.data)
        assertEquals(raw.toByteArray(Charsets.ISO_8859_1).toList(), back.data.toByteArray(Charsets.ISO_8859_1).toList())
        assertEquals(ObdTx("010C\r"), LinkCodec.decode(LinkCodec.encode(ObdTx("010C\r"))))
    }
}
