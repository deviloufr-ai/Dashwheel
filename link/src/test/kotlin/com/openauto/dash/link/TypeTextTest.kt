package com.openauto.dash.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TypeTextTest {
    @Test
    fun roundTripsThroughTheCodec() {
        val sent = TypeText("12 rue de la Paix, Paris", TypeText.Mode.REPLACE, enter = true, id = 42)
        assertEquals(sent, LinkCodec.decode(LinkCodec.encode(sent)))
        val answer = TypeResult(42, TypeResult.Outcome.COPIED)
        assertEquals(answer, LinkCodec.decode(LinkCodec.encode(answer)))
    }

    @Test
    fun keepsAccentsAndEmoji() {
        val sent = TypeText("Café à Saint-Étienne 🚗")
        assertEquals(sent, LinkCodec.decode(LinkCodec.encode(sent)))
    }

    @Test
    fun theLongestTextFitsInOneFrame() {
        // Worst case: every character escaped in the JSON.
        val sent = TypeText("\"".repeat(TypeText.MAX_CHARS), TypeText.Mode.REPLACE, enter = true, id = Long.MAX_VALUE)
        assert(LinkCodec.encode(sent).size <= LinkSession.MAX_MESSAGE)
    }

    @Test
    fun anOlderPeerSkipsItInsteadOfDroppingTheLink() {
        assertNull(LinkCodec.decode("""{"t":"type_text_v2","text":"x"}""".encodeToByteArray()))
    }
}
