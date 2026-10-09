package com.openauto.dash.display

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.openauto.dash.link.ClusterState
import com.openauto.dash.link.PairingOffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PainterTest {

    init {
        System.setProperty("java.awt.headless", "true")
    }

    @Test
    fun idleScreenCarriesAReadablePairingCode() {
        val uri = PairingOffer.create("Rear screen", kind = PairingOffer.Kind.DISPLAY).toUri()
        val painter = Painter(1024, 600, overscanPct = 5)
        val image = painter.paintIdle("Rear screen", "Waiting", 0, uri)
        val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        val read = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(image.width, image.height, pixels))))
        assertEquals(uri, read.text)
        assertEquals(PairingOffer.Kind.DISPLAY, PairingOffer.parse(read.text)?.kind)
    }

    @Test
    fun pairedIdleScreenShowsTheBootLogoWherePlymouthDrewIt() {
        // A 300×200 red logo: on 1920×1080 it stays full size, centred.
        val logo = java.awt.image.BufferedImage(300, 200, java.awt.image.BufferedImage.TYPE_INT_RGB)
        logo.createGraphics().apply { color = java.awt.Color.RED; fillRect(0, 0, 300, 200); dispose() }
        val image = Painter(1920, 1080, overscanPct = 0, logo = logo).paintIdle("Car", "Waiting", 0, pairingUri = null)
        assertEquals(0xFF0000, image.getRGB(960, 540) and 0xFFFFFF)
        assertEquals(0xFF0000, image.getRGB(960 - 149, 540 - 99) and 0xFFFFFF)
        assertEquals(0x000000, image.getRGB(960 - 152, 540) and 0xFFFFFF)

        // Not paired yet: the pairing code comes first, not the logo.
        val uri = PairingOffer.create("Car", kind = PairingOffer.Kind.DISPLAY).toUri()
        val pairing = Painter(1920, 1080, overscanPct = 0, logo = logo).paintIdle("Car", "Waiting", 0, uri)
        assertTrue(pairing.getRGB(960, 540) and 0xFFFFFF != 0xFF0000)
    }

    @Test
    fun theBoardDrawsEveryArrangementAndFaceInEveryDesign() {
        val full = ClusterState(
            clock = 1_700_000_000_000, speedKmh = 87, rpm = 2100, coolantC = 90, fuelPct = 40, rangeKm = 310,
            open = listOf("Boot"), obdConnected = true,
            media = ClusterState.Media("Song", "Artist", "Spotify", true, 30_000, 200_000),
            nav = ClusterState.Nav("Turn right", "300 m", "Rue de Rivoli", "18:42"), maneuver = "RIGHT",
            measures = listOf(ClusterState.Measure("Coolant", "90 °C"))
        )
        val faces = com.openauto.dash.link.ClusterFace.ALL + "" + "SOMETHING_NEW"
        val arrangements = listOf("ONE" to 1, "BIG_SIDE" to 2, "HALVES" to 2, "THREE" to 3, "BIG_STACK" to 3, "GRID" to 4)
        for ((w, h) in listOf(720 to 480, 1024 to 600)) {
            val painter = Painter(w, h, overscanPct = 0)
            for (design in Design.entries) for ((arrangement, slots) in arrangements) {
                // Every face gets a turn in every slot size, with and without readings.
                for (first in faces.indices step 3) {
                    val chosen = (0 until slots).map { faces[(first + it) % faces.size] }
                    val layout = ClusterState.Layout(arrangement, chosen)
                    painter.paintCluster(full.copy(design = design.name, layout = layout), full.clock)
                    painter.paintCluster(ClusterState(clock = 0, night = false, design = design.name, layout = layout), 0)
                }
            }
            // A board the display doesn't know (a newer arrangement): the page as it comes, not nothing.
            val image = painter.paintCluster(full.copy(layout = ClusterState.Layout("HEXAGON", listOf("SPEED"))), full.clock)
            assertTrue(image.getRGB(0, 0, w, h, null, 0, w).any { it and 0xFFFFFF != 0 })
        }
        // The boxes cover the body, the big one first.
        val boxes = Board.boxes("BIG_STACK", java.awt.Rectangle(0, 0, 1000, 500), 10)!!
        assertEquals(3, boxes.size)
        assertTrue(boxes[0].width > boxes[1].width)
        assertEquals(1000, boxes[1].x + boxes[1].width)
        assertEquals(500, boxes[2].y + boxes[2].height)
        assertNull(Board.boxes("HEXAGON", java.awt.Rectangle(0, 0, 10, 10), 1))
    }

    @Test
    fun theClockIsWrittenInTheHeadUnitsZoneAndTheTurnAsItNamesIt() {
        val noon = 1_700_000_000_000L // 2023-11-14 22:13:20 UTC
        val painter = Painter(400, 200, overscanPct = 0)
        painter.zone = java.time.ZoneId.of("Europe/Paris")
        assertEquals("23:13", clockText(noon, false, painter.zone))
        assertEquals("22:13", clockText(noon, false, java.time.ZoneOffset.UTC))
        // The head unit's name for the turn wins over its words; an unknown name falls back to them.
        assertEquals(Maneuver.LEFT, Maneuver.named("LEFT"))
        assertNull(Maneuver.named("SIDEWAYS"))
        // Words sent once stand in for the ones an older head unit sent with each reading.
        val text = ClusterText(ClusterState(clock = 0, fuelPct = 50), words = ClusterState.Labels(fuel = "carburant"))
        assertEquals("carburant", text.card(ClusterText.Kind.FUEL).label)
        assertEquals("--", text.card(ClusterText.Kind.RPM).value)
    }

    @Test
    fun everyClusterPageDrawsOnSmallAndLargeScreens() {
        val full = ClusterState(
            clock = 1_700_000_000_000, speedKmh = 87, rpm = 2100, coolantC = 90, fuelPct = 40, rangeKm = 310,
            open = listOf("Boot"), obdConnected = true,
            media = ClusterState.Media("A very long song title that will not fit on a small screen at all", "Artist", "Spotify", true, 30_000, 200_000),
            nav = ClusterState.Nav("Turn right onto the main road", "300 m", "Rue de Rivoli", "18:42")
        )
        for ((w, h) in listOf(720 to 480, 1024 to 600, 1920 to 1080)) {
            val painter = Painter(w, h, overscanPct = 5)
            for (page in listOf("DRIVE", "MEDIA", "NAV", "UNKNOWN")) {
                for (state in listOf(full.copy(page = page), ClusterState(clock = 0, page = page, night = true))) {
                    val image = painter.paintCluster(state, state.clock)
                    // Something besides the background was drawn.
                    val colours = image.getRGB(0, 0, w, h, null, 0, w).toSet()
                    assertTrue("$w×$h $page", colours.size > 2)
                }
            }
        }
    }

    @Test
    fun anOlderHeadUnitGetsMetricAnd24HoursInEnglish() {
        val text = ClusterText(
            ClusterState(clock = 0, speedKmh = 100, rpm = 2100, coolantC = 90, fuelPct = 40, rangeKm = 310, open = listOf("Boot", "Bonnet"))
        )
        assertEquals("100", text.speed)
        assertEquals("km/h", text.speedUnit)
        assertEquals(listOf("2100" to "rpm", "90°" to "coolant", "40%" to "fuel", "310" to "km range"), text.readings)
        assertEquals("Open: Boot, Bonnet", text.open)
        assertEquals("Nothing playing", text.nothingPlaying)
        assertEquals("No route", text.noRoute)
        assertEquals("Arrive 18:42", text.arrive("18:42"))
        assertEquals("14:05", text.clock(14 * 3_600_000L + 5 * 60_000L, java.time.ZoneOffset.UTC))
        assertEquals("--", ClusterText(ClusterState(clock = 0)).speed)
        assertNull(ClusterText(ClusterState(clock = 0)).open)
    }

    @Test
    fun theHeadUnitsUnitsClockAndWordsAreTheOnesShown() {
        val text = ClusterText(
            ClusterState(
                clock = 0, speedKmh = 100, rpm = 2100, coolantC = 90, fuelPct = 40, rangeKm = 310, open = listOf("Coffre"),
                imperial = true, speedUnit = "mph", fahrenheit = true, clock12 = true,
                labels = ClusterState.Labels(
                    open = "Ouvert : %s", nothingPlaying = "Aucune lecture", noRoute = "Aucun itinéraire", arrive = "Arrivée %s",
                    rpm = "tr/min", coolant = "refroid.", fuel = "carburant", range = "mi d'autonomie"
                )
            )
        )
        assertEquals("62", text.speed)
        assertEquals("mph", text.speedUnit)
        assertEquals(listOf("2100" to "tr/min", "194°F" to "refroid.", "40%" to "carburant", "193" to "mi d'autonomie"), text.readings)
        assertEquals("Ouvert : Coffre", text.open)
        assertEquals("Aucune lecture", text.nothingPlaying)
        assertEquals("Aucun itinéraire", text.noRoute)
        assertEquals("Arrivée 6:42 PM", text.arrive("6:42 PM"))
        assertEquals("2:05 PM", text.clock(14 * 3_600_000L + 5 * 60_000L, java.time.ZoneOffset.UTC))
        // Only some words sent, or a line that lost its %s: English fills in.
        val partial = ClusterText(ClusterState(clock = 0, open = listOf("Boot"), fuelPct = 40, labels = ClusterState.Labels(open = "Ouvert", fuel = "carburant")))
        assertEquals("Open: Boot", partial.open)
        assertEquals(listOf("40%" to "carburant"), partial.readings)
        assertEquals("No route", partial.noRoute)
    }

    @Test
    fun aLongListOfOpenDoorsStopsShortOfTheClock() {
        val doors = listOf("Front left door", "Front right door", "Rear left door", "Rear right door", "Tailgate", "Bonnet")
        for ((w, h) in listOf(720 to 480, 1024 to 600)) {
            val painter = Painter(w, h, overscanPct = 5)
            // The top band on the speed page (above the ring) holds the clock and the warning, nothing else.
            fun band(open: List<String>): List<IntArray> {
                val image = painter.paintCluster(ClusterState(clock = 0, open = open), 0)
                return (0 until w).map { x -> image.getRGB(x, 0, 1, h / 5, null, 0, 1) }
            }
            val alone = band(emptyList())
            val background = alone[0]
            val clockEnd = alone.indexOfLast { !it.contentEquals(background) }
            val crowded = band(doors)
            // The warning was cut (it would not fit whole), and the clock with a little air after it is untouched.
            val air = minOf(w, h) / 50
            for (x in 0..clockEnd + air) assertArrayEquals("$w×$h column $x", alone[x], crowded[x])
            assertTrue("$w×$h", (clockEnd + air + 1 until w).any { !crowded[it].contentEquals(background) })
        }
    }

    @Test
    fun theDoorWarningIsDarkerOnTheDayPalette() {
        val painter = Painter(1024, 600, overscanPct = 5)
        fun colours(night: Boolean): Set<Int> {
            val image = painter.paintCluster(ClusterState(clock = 0, open = listOf("Boot"), night = night), 0)
            return image.getRGB(0, 0, 1024, 600, null, 0, 1024).map { it and 0xFFFFFF }.toSet()
        }
        val day = colours(night = false)
        assertTrue(0xA84300 in day)
        assertFalse(0xFFB300 in day)
        assertTrue(0xFFB300 in colours(night = true))
    }

    @Test
    fun theTurnSignFollowsTheWordsInEveryLanguage() {
        assertEquals(Maneuver.RIGHT, Maneuver.of("Turn right onto Rue de Rivoli"))
        assertEquals(Maneuver.RIGHT, Maneuver.of("Keep right to continue on A6"))
        assertEquals(Maneuver.LEFT, Maneuver.of("Tournez à gauche"))
        assertEquals(Maneuver.RIGHT, Maneuver.of("Biegen Sie rechts ab"))
        assertEquals(Maneuver.STRAIGHT, Maneuver.of("Continuez tout droit"))
        assertEquals(Maneuver.STRAIGHT, Maneuver.of("Rechtdoor rijden"))
        assertEquals(Maneuver.ROUNDABOUT, Maneuver.of("Au rond-point, prenez la 2e sortie à droite"))
        assertEquals(Maneuver.UTURN, Maneuver.of("Faites demi-tour"))
        assertEquals(Maneuver.LEFT, Maneuver.of("Поверните налево"))
        assertNull(Maneuver.of("Take exit 12"))
    }

    @Test
    fun bgrxBytesAreBlueGreenRedPadding() {
        val painter = Painter(160, 120, 0)
        painter.image.setRGB(0, 0, 0x123456)
        val bytes = painter.bgrx()
        assertEquals(160 * 120 * 4, bytes.size)
        assertEquals(listOf(0x56, 0x34, 0x12, 0), bytes.take(4).map { it.toInt() and 0xFF })
    }
}
