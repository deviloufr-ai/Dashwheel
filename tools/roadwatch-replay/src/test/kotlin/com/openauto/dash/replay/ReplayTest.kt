package com.openauto.dash.replay

import com.openauto.dash.roadwatch.Box
import com.openauto.dash.roadwatch.Detection
import com.openauto.dash.roadwatch.RoadBand
import com.openauto.dash.roadwatch.RoadWarning
import com.openauto.dash.roadwatch.VehicleKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import kotlin.math.tan

/**
 * The harness over a drawn drive: a road painted with java.awt, as the real
 * tool paints the overlay, through the same RGBA path as a video's frames.
 */
class ReplayTest {
    private val w = 640
    private val h = 480
    private val band = RoadBand(horizonY = 0.5f, bonnetY = 0.92f)

    /** A road seen from the bonnet: sky, tarmac, the lane's two lines meeting at the horizon, dashes rolling by. */
    private fun road(phaseM: Float, offset: Float = 0f): ByteBuffer {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(90, 110, 140); g.fillRect(0, 0, w, h / 2)
        g.color = Color(70, 72, 76); g.fillRect(0, h / 2, w, h / 2)
        val hy = band.horizonY * h
        val by = band.bonnetY * h
        fun depth(d: Float) = minOf(1.3f, 6f / d)
        fun y(d: Float) = hy + (by - hy) * depth(d)
        fun x(xb: Float, d: Float) = w / 2f + (xb - w / 2f) * depth(d)
        val xl = w * (0.15f + offset)
        val xr = w * (0.85f + offset)
        g.color = Color(220, 222, 225)
        g.stroke = BasicStroke(3f)
        g.drawLine(x(xl, 300f).toInt(), y(300f).toInt(), xl.toInt(), y(6f).toInt())
        for (k in 0 until 40) {
            val d0 = k * 9f - phaseM % 9f
            val d1 = d0 + 3f
            if (d1 < 2.5f) continue
            val dd0 = maxOf(2.5f, d0)
            g.stroke = BasicStroke(maxOf(1.5f, 6f * depth(dd0)))
            g.drawLine(x(xr, dd0).toInt(), y(dd0).toInt(), x(xr, d1).toInt(), y(d1).toInt())
        }
        g.dispose()
        val px = IntArray(w * h)
        img.getRGB(0, 0, w, h, px, 0, w)
        val buf = ByteBuffer.allocate(w * h * 4)
        for (c in px) { buf.put((c shr 16).toByte()); buf.put((c shr 8).toByte()); buf.put(c.toByte()); buf.put(-1) }
        buf.rewind()
        return buf
    }

    /** A drawn frame through the same RGBA path as a video's. */
    private fun ReplaySession.frameOf(index: Int, timeMs: Long, rgba: ByteBuffer, detections: List<Detection>, telemetry: Telemetry): FrameResult =
        frame(index, timeMs, rgba, w, h, w * 4, detections, telemetry)

    /** A car [d] metres ahead, as the detector would box it (a 66° view). */
    private fun car(d: Float): Detection {
        val half = tan(Math.toRadians(33.0)).toFloat()
        val wide = 1.8f / (2 * half * d)
        val bottom = 0.5f + 0.42f * minOf(1.2f, 6f / d)
        return Detection(Box(0.5f - wide / 2, bottom - wide * 0.8f, 0.5f + wide / 2, bottom), VehicleKind.CAR, 0.8f)
    }

    @Test
    fun theLaneIsFoundOnADrawnRoad() {
        val s = ReplaySession(band)
        var phase = 0f
        repeat(60) { i ->
            phase += 25f / 30f
            s.frameOf(i, i * 33L, road(phase), emptyList(), Telemetry(90f))
        }
        val r = s.report()
        assertEquals(60, r.frames)
        assertTrue("both lines on most frames, got ${r.bothLines}", r.bothLines >= 50)
        val last = s.results.last()
        assertNotNull(last.lane.left); assertNotNull(last.lane.right)
        assertEquals(0.15f, last.lane.left!!.bottomX, 0.03f)
        assertEquals(0.85f, last.lane.right!!.bottomX, 0.03f)
    }

    @Test
    fun aCarBrakingAheadGivesTheCollisionWarning() {
        val s = ReplaySession(band)
        var phase = 0f
        var d = 40f
        repeat(90) { i ->
            phase += 25f / 30f
            // Closing at 12 m/s from frame 30.
            if (i >= 30) d -= 12f / 30f
            s.frameOf(i, i * 33L, road(phase), listOf(car(maxOf(d, 5f))), Telemetry(90f))
        }
        val r = s.report()
        assertTrue("the car was seen", r.leadSeen > 80)
        assertTrue("a collision warning came up: ${r.warnings}", r.warnings.any { it.warning == RoadWarning.COLLISION })
    }

    @Test
    fun labelsGiveErrors() {
        val s = ReplaySession(band)
        repeat(10) { i -> s.frameOf(i, i * 33L, road(i * 0.8f), listOf(car(20f)), Telemetry(80f)) }
        val r = s.report(listOf(Label(9, 0.15f, 0.85f, 20f)))
        assertNotNull(r.lineErrorX); assertTrue(r.lineErrorX!! < 0.03f)
        assertEquals(1f, r.lineRecall!!, 0f)
        assertNotNull(r.leadErrorM); assertTrue(r.leadErrorM!! < 1f)
    }

    @Test
    fun theOverlayDrawsWithoutComplaint() {
        val s = ReplaySession(band)
        // A line counts once seen three frames running: draw the fourth.
        var result = s.frameOf(0, 0L, road(0f), listOf(car(15f)), Telemetry(90f))
        repeat(3) { i -> result = s.frameOf(i + 1, (i + 1) * 33L, road((i + 1) * 0.8f), listOf(car(15f)), Telemetry(90f)) }
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        Overlay.draw(img, result, band, ruler = true)
        // The teal of a lane line is somewhere on the picture.
        var teal = 0
        for (y in 0 until h) for (x in 0 until w) {
            val c = img.getRGB(x, y)
            if ((c shr 8 and 0xFF) > 180 && (c and 0xFF) > 150 && (c shr 16 and 0xFF) < 120) teal++
        }
        assertTrue("lane lines drawn", teal > 100)
    }
}
