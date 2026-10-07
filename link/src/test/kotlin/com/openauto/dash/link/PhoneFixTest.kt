package com.openauto.dash.link

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneFixTest {
    @Test
    fun roundTripsThroughTheCodec() {
        val sent = PhoneFix(
            lat = 48.8566, lng = 2.3522, accuracyM = 3.5f, ageMs = 120,
            speedMps = 13.9f, bearingDeg = 271f, altitudeM = 41.0, satellites = 38, seen = 46
        )
        assertEquals(sent, LinkCodec.decode(LinkCodec.encode(sent)))
    }

    @Test
    fun aFixWithoutSpeedOrHeadingStaysWithout() {
        // Standing still the phone gives neither: the car must not read 0 km/h heading north.
        val sent = PhoneFix(lat = 1.0, lng = 2.0, accuracyM = 8f)
        val got = LinkCodec.decode(LinkCodec.encode(sent)) as PhoneFix
        assertEquals(null, got.speedMps)
        assertEquals(null, got.bearingDeg)
    }
}
