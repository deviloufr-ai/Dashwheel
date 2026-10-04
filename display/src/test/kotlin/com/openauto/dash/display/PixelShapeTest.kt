package com.openauto.dash.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PixelShapeTest {

    @Test
    fun theSevenInchPanelSaysItIsAMonitor() {
        // The CUQI 1024x600 panel's EDID: 47 x 26 cm. kmssink took its pixels for 16/15 and showed 960 px.
        assertEquals(16 to 15, PixelShape.deviceRatio(1024, 600, 470, 260))
        // a size that matches the shape: square pixels, nothing given
        assertEquals(1 to 1, PixelShape.deviceRatio(1920, 1080, 510, 287))
        // a true 7" panel's own size: its pixels are a little wide too, and would be cut the same way
        assertEquals(16 to 15, PixelShape.deviceRatio(1024, 600, 154, 86))
        // no size at all (composite, a projector)
        assertEquals(1 to 1, PixelShape.deviceRatio(720, 576, 0, 0))
    }

    @Test
    fun theStreamFillsTheScreenEdgeToEdge() {
        // The head unit sends 1024x608 for 1024x600: kept in proportion it left 7 px bars each side.
        PixelShape.use(1024, 600, 16 to 15)
        val shape = PixelShape.forStream()
        assertEquals(1216 to 1125, shape)
        // what kmssink then shows (its sums): exactly the screen
        assertEquals(1024 to 600, kmsShows(1024, 608, shape, 16 to 15))
        // the same size said again: nothing to start over
        assertFalse(PixelShape.streamIs(1024, 608))
        // a lower quality setting, 960x560: started over, filling the screen too
        assertTrue(PixelShape.streamIs(960, 560))
        // (within kmssink's own rounding: a pixel)
        val other = kmsShows(960, 560, PixelShape.forStream(), 16 to 15)
        assertTrue("$other", other.first in 1023..1024 && other.second == 600)
        // a 1080p monitor with square pixels: the head unit sends it 1280x720, the same shape, nothing given
        PixelShape.use(1920, 1080, 1 to 1)
        assertEquals(1280 to 720, PixelShape.defaultStream(1920, 1080))
        assertEquals(1 to 1, PixelShape.forStream())
        assertEquals(1024 to 608, PixelShape.defaultStream(1024, 600))
    }

    /** kmssink's size for a [w] × [h] picture of pixel shape [par] on pixels it takes for [mpar] (gstkmssink.c set_caps). */
    private fun kmsShows(w: Int, h: Int, par: Pair<Int, Int>, mpar: Pair<Int, Int>): Pair<Int, Int> {
        var n = w.toLong() * par.first * mpar.second
        var d = h.toLong() * par.second * mpar.first
        tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)
        val g = gcd(n, d); n /= g; d /= g
        return when {
            h % d == 0L -> (h * n / d).toInt() to h
            w % n == 0L -> w to (w * d / n).toInt()
            else -> (h * n / d).toInt() to h
        }.let { (sw, sh) ->
            // then fitted into the 1024x600 screen, proportions kept
            val s = minOf(1024.0 / sw, 600.0 / sh)
            Math.round(sw * s).toInt() to Math.round(sh * s).toInt()
        }
    }

    @Test
    fun theSizeComesFromTheEdid() {
        val edid = ByteArray(128).also {
            it[1] = 0xFF.toByte(); it[2] = 0xFF.toByte(); it[3] = 0xFF.toByte()
            it[4] = 0xFF.toByte(); it[5] = 0xFF.toByte(); it[6] = 0xFF.toByte()
            it[21] = 47; it[22] = 26
        }
        assertEquals(470 to 260, PixelShape.edidSizeMm(edid))
        assertNull(PixelShape.edidSizeMm(edid.copyOf().also { it[21] = 0 }))
        assertNull(PixelShape.edidSizeMm(ByteArray(10)))
    }

    @Test
    fun theVideoIsGivenTheShapeJustBeforeTheScreen() {
        val plain = Pipelines.video(DisplayConfig()).joinToString(" ")
        assertFalse("capssetter" in plain)
        val given = Pipelines.video(DisplayConfig(), upsideDown = true, pixelShape = 16 to 15).joinToString(" ")
        assertTrue(given, "videoflip video-direction=180 ! capssetter caps=video/x-raw,pixel-aspect-ratio=(fraction)16/15 ! kmssink" in given)
    }
}
