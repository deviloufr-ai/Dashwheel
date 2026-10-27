package com.openauto.dash.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhonePointerTest {
    @Test
    fun everyKindRoundTripsThroughTheCodec() {
        for (kind in PhonePointer.Kind.entries) {
            val sent = PhonePointer(kind, 12.5f, -3.25f)
            assertEquals(sent, LinkCodec.decode(LinkCodec.encode(sent)))
        }
        assertEquals(PointerAccess(on = true), LinkCodec.decode(LinkCodec.encode(PointerAccess(on = true))))
    }

    @Test
    fun aTapNeedsNoDistance() {
        assertEquals(PhonePointer(PhonePointer.Kind.TAP), LinkCodec.decode("""{"t":"pointer","kind":"TAP"}""".encodeToByteArray()))
    }

    @Test
    fun anOlderPeerSkipsAKindItDoesNotKnow() {
        assertNull(LinkCodec.decode("""{"t":"pointer","kind":"PINCH","dx":1.0}""".encodeToByteArray()))
    }
}
