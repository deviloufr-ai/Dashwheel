package com.openauto.dash.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FuelFillsTest {
    @Test
    fun consumptionIsWhatWentInAfterTheFirstOverTheDistance() {
        val fills = listOf(
            FuelFill(at = 1, liters = 40.0, odometerKm = 100_000),
            FuelFill(at = 2, liters = 30.0, odometerKm = 100_500),
            FuelFill(at = 3, liters = 35.0, odometerKm = 101_000)
        )
        assertEquals(6.5, FuelFills.litersPer100(fills)!!, 0.001)
    }

    @Test
    fun tooShortADistanceSaysNothingYet() {
        val fills = listOf(FuelFill(at = 1, liters = 40.0, odometerKm = 1_000), FuelFill(at = 2, liters = 10.0, odometerKm = 1_150))
        assertNull(FuelFills.litersPer100(fills))
    }

    @Test
    fun refuelsAndNoticesRoundTripThroughTheCodec() {
        val sync = FuelSync(listOf(FuelFill(1, 38.5, 1.789, "€", 123_456, "Total, Beauvais")))
        assertEquals(sync, LinkCodec.decode(LinkCodec.encode(sync)))
        val notices = CarNotices(listOf(CarNotice("upkeep-OIL-SOON", CarNotice.UPKEEP, "Oil coming up", "In 800 km", 5)))
        assertEquals(notices, LinkCodec.decode(LinkCodec.encode(notices)))
        listOf(PhoneAbilities(true), SendText("+33600000000", "On my way", 7), TextSent(7, true))
            .forEach { assertEquals(it, LinkCodec.decode(LinkCodec.encode(it))) }
    }

    @Test
    fun whatThePhonePlaysRoundTripsWithItsCover() {
        val playing = NowPlaying("Skål!", "Miracle of Sound", "Level 10", playing = true, durationMs = 201_000, artKey = 42, artJpeg = "/9j/4AAQ")
        assertEquals(playing, LinkCodec.decode(LinkCodec.encode(playing)))
        // An older head unit that has never heard of it skips it.
        assertEquals(NowPlaying(), LinkCodec.decode("""{"t":"now_playing"}""".encodeToByteArray()))
    }

    @Test
    fun mergeKeepsNewestFirstAndOneEntryPerRefuel() {
        val a = FuelFill(1, 10.0)
        val b = FuelFill(2, 20.0)
        assertEquals(listOf(b, a), FuelFills.merge(FuelFills.merge(emptyList(), a), b))
        assertEquals(listOf(b.copy(liters = 21.0), a), FuelFills.merge(listOf(b, a), b.copy(liters = 21.0)))
    }
}
