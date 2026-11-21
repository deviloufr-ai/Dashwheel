package com.openauto.dash.link

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingOfferTest {

    @Test
    fun roundTripsThroughItsUri() {
        val offer = PairingOffer.create("C4 Picasso — Autoradio & co")
        val back = PairingOffer.parse(offer.toUri())!!
        assertEquals(offer.id, back.id)
        assertEquals(offer.unitName, back.unitName)
        assertArrayEquals(offer.secret, back.secret)
    }

    @Test
    fun refusesAnythingElse() {
        val good = PairingOffer.create("Unit").toUri()
        assertNull(PairingOffer.parse("https://example.com/?v=1"))
        assertNull(PairingOffer.parse(good.replace("v=1", "v=2")))
        assertNull(PairingOffer.parse(good.replace(Regex("k=[^&]+"), "k=AAAA")))
        assertNull(PairingOffer.parse(good.replace(Regex("id=[^&]+"), "id=xyz")))
        assertNull(PairingOffer.parse(good.replace(Regex("&n=[^&]+"), "")))
        assertNull(PairingOffer.parse("not a uri at all %%"))
    }

    @Test
    fun survivesAwkwardHeadUnitNames() {
        listOf("rk3566_r", "Jean's car *2*", "Autoradio (T3L) 10\"", "車載ユニット 🚗", "a+b=c&d?e#f/g", "  spaced  ").forEach { name ->
            repeat(50) {
                val offer = PairingOffer.create(name)
                val back = PairingOffer.parse(offer.toUri())
                assertEquals(name, offer.unitName.trim(), back?.unitName)
            }
        }
    }

    @Test
    fun displayOffersKeepTheirKind() {
        val offer = PairingOffer.create("Rear screen", kind = PairingOffer.Kind.DISPLAY)
        assertTrue(offer.toUri().startsWith("dashwheel://display?"))
        val back = PairingOffer.parse(offer.toUri())!!
        assertEquals(PairingOffer.Kind.DISPLAY, back.kind)
        assertEquals(offer.id, back.id)
        assertArrayEquals(offer.secret, back.secret)
        // A head unit's code stays what it always was.
        assertEquals(PairingOffer.Kind.HEAD_UNIT, PairingOffer.parse(PairingOffer.create("Unit").toUri())!!.kind)
        assertNull(PairingOffer.parse(offer.toUri().replace("://display?", "://screen?")))
    }

    @Test
    fun aTypedCodeGivesTheSameOffer() {
        repeat(50) {
            val offer = PairingOffer.create("Unit")
            val code = offer.code!!
            assertTrue(code, Regex("([0-9A-HJKMNP-TV-Z]{4}-){5}[0-9A-HJKMNP-TV-Z]{4}").matches(code))
            // Typed in lower case, without dashes, with an O for a 0 and an l for a 1.
            val typed = code.lowercase().replace("-", " ").replace('0', 'o').replace('1', 'l')
            val back = PairingOffer.parse(typed)!!
            assertEquals(offer.id, back.id)
            assertArrayEquals(offer.secret, back.secret)
            assertEquals(PairingOffer.TYPED_NAME, back.unitName)
            // The QR of the same offer still reads as before.
            assertEquals(offer.id, PairingOffer.parse(offer.toUri())!!.id)
        }
        assertNull(PairingOffer.parse("ABCD-EFGH"))
        assertNull(PairingOffer.parse("UUUU-UUUU-UUUU-UUUU-UUUU-UUUU"))
    }
}
