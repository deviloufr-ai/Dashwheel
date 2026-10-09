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
    fun unitsAndLabelsReachThePiAndAnOlderSideStillReads() {
        roundTrip(
            ClusterState(
                clock = 1, speedKmh = 87, imperial = true, speedUnit = "mph", fahrenheit = true, clock12 = true,
                labels = ClusterState.Labels(open = "Ouvert : %s", noRoute = "Aucun itinéraire", range = "mi d'autonomie")
            )
        )
        // Metric, 24 h and no labels are left out: an older Pi gets the message it always got.
        val json = LinkCodec.encode(ClusterState(clock = 1, speedKmh = 87)).decodeToString()
        assertTrue(json, listOf("speedUnit", "fahrenheit", "clock12", "labels", "imperial").none { it in json })
        // An older head unit says none of it: metric, 24 h, the Pi's own English.
        val old = LinkCodec.decode("""{"t":"cluster_state","clock":5,"speedKmh":12}""".encodeToByteArray()) as ClusterState
        assertEquals(false, old.imperial || old.fahrenheit || old.clock12)
        assertNull(old.speedUnit)
        assertNull(old.labels)
        // A newer head unit's labels this Pi doesn't know are skipped, the known ones kept.
        val newer = LinkCodec.decode(
            """{"t":"cluster_state","clock":5,"labels":{"fuel":"carburant","oil":"huile"}}""".encodeToByteArray()
        ) as ClusterState
        assertEquals(ClusterState.Labels(fuel = "carburant"), newer.labels)
    }

    @Test
    fun protocolTwoMessagesRoundTripAndAnOlderSideStillReads() {
        roundTrip(DisplayTime(1_700_000_000_000, 120, "Europe/Paris"))
        roundTrip(DisplayStats(150, 5, 2400, 5_000, droppedLate = 4, throttled = 0x50005, tempC = 71))
        roundTrip(DisplayWords(language = "fr", waiting = "En attente…", labels = ClusterState.Labels(fuel = "carburant")))
        roundTrip(
            ClusterState(
                clock = 1, page = "NAV", maneuver = "ROUNDABOUT",
                layout = ClusterState.Layout("BIG_STACK", listOf("SPEED", "CLOCK", "RANGE"))
            )
        )
        // A display passes its protocol; written out, since it isn't the default (an older display's).
        val hello = DisplayHello("Pi", "1.0", 1024, 600, protocol = DISPLAY_PROTOCOL)
        assertTrue(""""protocol":$DISPLAY_PROTOCOL""" in LinkCodec.encode(hello).decodeToString())
        assertEquals(hello, LinkCodec.decode(LinkCodec.encode(hello)))
        // An older display never sends the new fields: the head unit reads what it always did.
        val old = LinkCodec.decode("""{"t":"display_stats","framesShown":150,"framesDropped":2,"kbps":2400,"periodMs":5000}""".encodeToByteArray()) as DisplayStats
        assertEquals(0, old.droppedLate)
        assertEquals(0, old.throttled)
        assertNull(old.tempC)
        val oldHello = LinkCodec.decode("""{"t":"display_hello","name":"Pi","appVersion":"1.0","width":1024,"height":600}""".encodeToByteArray()) as DisplayHello
        assertEquals(1, oldHello.protocol)
        // An older display skips the time message altogether, and the new fields of the readings.
        assertNull(LinkCodec.decode("""{"t":"display_time","clock":5,"zoneOffsetMin":60}""".encodeToByteArray())?.let { null })
        assertNull((LinkCodec.decode("""{"t":"cluster_state","clock":5}""".encodeToByteArray()) as ClusterState).layout)
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
