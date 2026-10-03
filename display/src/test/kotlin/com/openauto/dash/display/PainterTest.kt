package com.openauto.dash.display

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.openauto.dash.link.ClusterState
import com.openauto.dash.link.PairingOffer
import org.junit.Assert.assertEquals
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
        assertEquals(0x0B0F14, image.getRGB(960 - 152, 540) and 0xFFFFFF)

        // Not paired yet: the pairing code comes first, not the logo.
        val uri = PairingOffer.create("Car", kind = PairingOffer.Kind.DISPLAY).toUri()
        val pairing = Painter(1920, 1080, overscanPct = 0, logo = logo).paintIdle("Car", "Waiting", 0, uri)
        assertTrue(pairing.getRGB(960, 540) and 0xFFFFFF != 0xFF0000)
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
    fun bgrxBytesAreBlueGreenRedPadding() {
        val painter = Painter(160, 120, 0)
        painter.image.setRGB(0, 0, 0x123456)
        val bytes = painter.bgrx()
        assertEquals(160 * 120 * 4, bytes.size)
        assertEquals(listOf(0x56, 0x34, 0x12, 0), bytes.take(4).map { it.toInt() and 0xFF })
    }
}
