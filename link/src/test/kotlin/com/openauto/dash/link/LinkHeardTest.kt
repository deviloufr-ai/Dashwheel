package com.openauto.dash.link

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkHeardTest {
    @Test
    fun roundTripsThroughTheCodec() {
        val sent = LinkHeard(bytes = 1234, messages = 7, pings = 2, handling = "DriveSync 3s")
        assertEquals(sent, LinkCodec.decode(LinkCodec.encode(sent)))
    }

    @Test
    fun nothingBusyIsLeftOut() {
        assertEquals(LinkHeard(0, 0, 0), LinkCodec.decode("""{"t":"link_heard","bytes":0,"messages":0,"pings":0}""".encodeToByteArray()))
    }
}
