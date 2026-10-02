package com.openauto.dash.link

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayMessagesTest {

    private fun roundTrip(message: LinkMessage) = assertEquals(message, LinkCodec.decode(LinkCodec.encode(message)))

    @Test
    fun displayMessagesRoundTrip() {
        roundTrip(DisplayHello("Rear screen", "1.0", 1024, 600, model = "Raspberry Pi 3 Model B Rev 1.2"))
        roundTrip(DisplayMode(DisplayMode.Mode.VIDEO))
        roundTrip(VideoConfig(width = 1024, height = 600, fps = 30, csd = "AAAAAWdCwB4="))
        roundTrip(DisplayCommand(DisplayCommand.Action.KEYFRAME_PLEASE))
        roundTrip(DisplayStats(framesShown = 150, framesDropped = 2, kbps = 2400, periodMs = 5_000))
        roundTrip(DisplayPair(PairingOffer.create("Pi", kind = PairingOffer.Kind.DISPLAY).toUri()))
        roundTrip(
            ClusterState(
                clock = 1_700_000_000_000, speedKmh = 87, rpm = 2100, fuelPct = 40, rangeKm = 310,
                open = listOf("Boot"), obdConnected = true, night = true,
                media = ClusterState.Media("Song", "Artist", "Spotify", playing = true, positionMs = 30_000, durationMs = 200_000),
                nav = ClusterState.Nav("Turn right", "300 m", "Rue de Rivoli", "18:42")
            )
        )
    }

    @Test
    fun clusterStateLeavesUnknownValuesOut() {
        val json = LinkCodec.encode(ClusterState(clock = 1)).decodeToString()
        assertTrue(json, "speedKmh" !in json && "media" !in json)
        // A newer head unit's extra fields don't stop an older Pi.
        assertEquals(
            ClusterState(clock = 5, speedKmh = 12),
            LinkCodec.decode("""{"t":"cluster_state","clock":5,"speedKmh":12,"oilC":90}""".encodeToByteArray())
        )
    }

    @Test
    fun videoPacketsRoundTrip() {
        val data = byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 3)
        val back = VideoPacket.decode(VideoPacket(true, 123_456_789L, data).encode())!!
        assertTrue(back.keyFrame)
        assertEquals(123_456_789L, back.ptsUs)
        assertArrayEquals(data, back.data)
        val delta = VideoPacket.decode(VideoPacket(false, 5, data).encode())!!
        assertEquals(false, delta.keyFrame)
    }

    @Test
    fun unknownRawFramesAreRefused() {
        assertNull(VideoPacket.decode(byteArrayOf(2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 9)))
        assertNull(VideoPacket.decode(byteArrayOf(1, 0, 0)))
    }
}
