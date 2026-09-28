package com.openauto.dash.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedPlacesTest {
    private fun found(text: String, subject: String? = null): SharedPlace.Found =
        SharedPlaces.parse(text, subject) as SharedPlace.Found

    @Test
    fun plainTextIsNotAPlace() {
        assertNull(SharedPlaces.parse("Can you pick up bread on the way?"))
        assertNull(SharedPlaces.parse("12 rue de Rivoli, Paris"))
        assertNull(SharedPlaces.parse("Look at this https://www.example.com/maps/place/x"))
        assertNull(SharedPlaces.parse("   "))
    }

    @Test
    fun geoLinks() {
        found("geo:48.8583,2.2945").let {
            assertEquals(48.8583, it.lat!!, 1e-9)
            assertEquals(2.2945, it.lng!!, 1e-9)
            assertNull(it.query)
        }
        found("geo:0,0?q=48.8583,2.2945(Tour%20Eiffel)").let {
            assertEquals("Tour Eiffel", it.label)
            assertEquals(48.8583, it.lat!!, 1e-9)
        }
        found("geo:0,0?q=10+Downing+Street,+London").let {
            assertFalse(it.hasPosition)
            assertEquals("10 Downing Street, London", it.query)
        }
        // A position and a name to look up there.
        found("geo:48.85,2.29?q=Café+de+Flore&z=17").let {
            assertTrue(it.hasPosition)
            assertEquals("Café de Flore", it.query)
        }
        assertNull(SharedPlaces.fromUrl("geo:0,0"))
    }

    @Test
    fun aPlaceLinkPrefersThePlaceOverTheMapCentre() {
        val url = "https://www.google.com/maps/place/Tour+Eiffel/@48.8583701,2.2918998,17z/data=!3m1!4b1!4m6!3m5!1s0x47e66e2964e34e2d:0x8ddca9ee380ef7e0!8m2!3d48.8583701!4d2.2944813!16zL20vMDJqODE"
        val f = SharedPlaces.fromUrl(url)!!
        assertEquals("Tour Eiffel", f.label)
        assertEquals(48.8583701, f.lat!!, 1e-9)
        assertEquals(2.2944813, f.lng!!, 1e-9)
    }

    @Test
    fun queryParameters() {
        SharedPlaces.fromUrl("https://www.google.com/maps/search/?api=1&query=47.5951518,-122.3316393")!!.let {
            assertEquals(47.5951518, it.lat!!, 1e-9)
            assertEquals(-122.3316393, it.lng!!, 1e-9)
        }
        SharedPlaces.fromUrl("https://maps.google.com/?q=48.85,2.29")!!.let { assertEquals(2.29, it.lng!!, 1e-9) }
        SharedPlaces.fromUrl("https://maps.google.fr/maps?ll=45.76,4.83&z=14")!!.let { assertEquals(45.76, it.lat!!, 1e-9) }
        SharedPlaces.fromUrl("https://www.google.com/maps/dir/?api=1&destination=Gare+de+Lyon%2C+Paris")!!.let {
            assertFalse(it.hasPosition)
            assertEquals("Gare de Lyon, Paris", it.query)
        }
        SharedPlaces.fromUrl("https://www.google.com/maps/dir/?api=1&destination=43.2965,5.3698")!!.let { assertEquals(43.2965, it.lat!!, 1e-9) }
        SharedPlaces.fromUrl("https://waze.com/ul?ll=48.8566,2.3522&navigate=yes")!!.let { assertEquals(48.8566, it.lat!!, 1e-9) }
        SharedPlaces.fromUrl("https://maps.apple.com/?ll=50.894967,4.341626&q=Atomium")!!.let {
            assertEquals(50.894967, it.lat!!, 1e-9)
            assertEquals("Atomium", it.query)
        }
    }

    @Test
    fun pathForms() {
        SharedPlaces.fromUrl("https://www.google.com/maps/place/48.8583,2.2945")!!.let { assertEquals(2.2945, it.lng!!, 1e-9) }
        SharedPlaces.fromUrl("https://www.google.com/maps/dir/Paris/Lyon,+France/@46.5,3.6,7z")!!.let {
            assertEquals("Lyon, France", it.query)
            // The route's overview is not where it goes.
            assertFalse(it.hasPosition)
        }
        SharedPlaces.fromUrl("https://www.google.com/maps/search/pharmacie+de+garde")!!.let { assertEquals("pharmacie de garde", it.query) }
        // The map centre alone is still better than nothing.
        SharedPlaces.fromUrl("https://www.google.com/maps/@48.8566,2.3522,15z")!!.let { assertEquals(48.8566, it.lat!!, 1e-9) }
        // A link naming nothing (a place id): left to the share's own text.
        assertNull(SharedPlaces.fromUrl("https://maps.google.com/?cid=10220263347432136672"))
    }

    @Test
    fun theEuConsentPageCarriesTheLink() {
        val consent = "https://consent.google.com/m?continue=https://www.google.com/maps/place/Louvre/data%3D!4m2!3m1!3d48.8606!4d2.3376&gl=FR"
        val f = SharedPlaces.fromUrl(consent)!!
        assertEquals(48.8606, f.lat!!, 1e-9)
        assertEquals(2.3376, f.lng!!, 1e-9)
    }

    @Test
    fun aMapsShareWithAShortLinkIsFollowedWithItsTextAsFallback() {
        val share = "Tour Eiffel\nAv. Gustave Eiffel, 75007 Paris, France\nhttps://maps.app.goo.gl/AbCdEf123?g_st=ic"
        val place = SharedPlaces.parse(share) as SharedPlace.ShortLink
        assertEquals("https://maps.app.goo.gl/AbCdEf123?g_st=ic", place.url)
        assertEquals("Tour Eiffel", place.label)
        assertEquals("Tour Eiffel, Av. Gustave Eiffel, 75007 Paris, France", place.fallback!!.query)
        // The link alone: nothing to fall back on.
        val bare = SharedPlaces.parse("https://maps.app.goo.gl/AbCdEf123") as SharedPlace.ShortLink
        assertNull(bare.fallback)
        assertTrue(SharedPlaces.isShortLink("https://goo.gl/maps/xyz"))
        assertFalse(SharedPlaces.isShortLink("https://goo.gl/abc"))
    }

    @Test
    fun aShareWithALongLinkKeepsItsName() {
        val f = found("Chez Paul\nhttps://www.google.com/maps/place/Chez+Paul/@48.853,2.369,17z/data=!3d48.8531!4d2.3698")
        assertEquals("Chez Paul", f.label)
        assertEquals(48.8531, f.lat!!, 1e-9)
        // The subject names it when the text doesn't.
        assertEquals("Chez Paul", found("https://maps.google.com/?q=48.8531,2.3698", subject = "Chez Paul").label)
    }

    @Test
    fun aBarePosition() {
        found("48.8583, 2.2945").let {
            assertEquals(48.8583, it.lat!!, 1e-9)
            assertEquals(2.2945, it.lng!!, 1e-9)
        }
        assertNull(SharedPlaces.parse("0, 0"))
        assertNull(SharedPlaces.parse("95.1, 2.3"))
    }
}
