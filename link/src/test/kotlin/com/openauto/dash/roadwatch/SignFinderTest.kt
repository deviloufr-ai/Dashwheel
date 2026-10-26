package com.openauto.dash.roadwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.math.hypot
import kotlin.random.Random

class SignFinderTest {
    private val w = 640
    private val h = 480

    /** A grey street, RGBA. */
    private fun street(): ByteBuffer {
        val buf = ByteBuffer.allocate(w * h * 4)
        val rnd = Random(3)
        for (i in 0 until w * h) {
            val v = 110 + rnd.nextInt(-10, 11)
            buf.put(v.toByte()).put(v.toByte()).put((v + 5).toByte()).put(255.toByte())
        }
        return buf
    }

    private fun set(buf: ByteBuffer, x: Int, y: Int, r: Int, g: Int, b: Int) {
        if (x !in 0 until w || y !in 0 until h) return
        val i = (y * w + x) * 4
        buf.put(i, r.toByte()); buf.put(i + 1, g.toByte()); buf.put(i + 2, b.toByte())
    }

    /** A speed-limit sign [d] pixels across at ([cx], [cy]): a red ring, a white face, two black digits. */
    private fun sign(buf: ByteBuffer, cx: Int, cy: Int, d: Int) {
        val r = d / 2f
        for (y in cy - d..cy + d) for (x in cx - d..cx + d) {
            val e = hypot((x - cx).toFloat(), (y - cy).toFloat())
            when {
                e <= 0.78f * r -> {
                    val digit = kotlin.math.abs(y - cy) < 0.4f * r && (kotlin.math.abs(x - cx + 0.25f * r) < 0.12f * r || kotlin.math.abs(x - cx - 0.25f * r) < 0.12f * r)
                    if (digit) set(buf, x, y, 30, 30, 30) else set(buf, x, y, 225, 225, 220)
                }
                e <= r -> set(buf, x, y, 200, 30, 40)
            }
        }
    }

    @Test
    fun aSpeedLimitSignIsFound() {
        val buf = street()
        sign(buf, 500, 150, 40)
        val got = SignFinder.find(buf, w, h, w * 4, 0)
        assertEquals(1, got.size)
        assertEquals(500f / w, got[0].box.centerX, 0.01f)
        assertTrue("about 40 px across: ${got[0].sidePx}", got[0].sidePx in 36..44)
    }

    @Test
    fun aSignTooSmallToReadIsLeft() {
        val buf = street()
        sign(buf, 500, 150, 10)
        assertEquals(0, SignFinder.find(buf, w, h, w * 4, 0).size)
    }

    @Test
    fun aBrickWallIsNoSign() {
        val buf = street()
        val rnd = Random(5)
        // Bricks: red-brown blocks with light mortar between them.
        for (y in 100 until 300) for (x in 380 until 620) {
            val mortar = y % 12 < 2 || (x + (y / 12 % 2) * 12) % 24 < 2
            if (mortar) set(buf, x, y, 170, 165, 155) else set(buf, x, y, 160 + rnd.nextInt(-15, 16), 70, 55)
        }
        assertEquals(0, SignFinder.find(buf, w, h, w * 4, 0).size)
    }

    @Test
    fun aRedDiscIsNoSpeedLimit() {
        // No entry: a red disc with a white bar, red in its middle.
        val buf = street()
        for (y in 130 until 170) for (x in 480 until 520) {
            if (hypot(x - 500f, y - 150f) <= 20f) {
                if (kotlin.math.abs(y - 150) < 4 && kotlin.math.abs(x - 500) < 14) set(buf, x, y, 230, 230, 230) else set(buf, x, y, 200, 30, 40)
            }
        }
        assertEquals(0, SignFinder.find(buf, w, h, w * 4, 0).size)
    }

    @Test
    fun theNumberIsBelievedOnceReadTwice() {
        val c = SignConfirm()
        assertNull(c.read(30, 0))
        assertEquals(30, c.read(30, 400))
        // A blur read as 80 once doesn't change it.
        assertNull(c.read(80, 800))
        // Seconds apart, two reads don't make one.
        val d = SignConfirm()
        assertNull(d.read(50, 0))
        assertNull(d.read(50, 5_000))
    }

    @Test
    fun onlyRealLimitsAreRead() {
        assertEquals(30, SignConfirm.limitIn("30"))
        assertEquals(90, SignConfirm.limitIn("9O"))
        assertEquals(110, SignConfirm.limitIn("1 10"))
        assertNull(SignConfirm.limitIn("STOP"))
        assertNull(SignConfirm.limitIn("35"))
        assertNull(SignConfirm.limitIn("3.5t"))
        // Read upside down, as ML Kit sometimes does.
        assertEquals(90, SignConfirm.limitIn("06"))
        assertEquals(80, SignConfirm.limitIn("08"))
        assertEquals(110, SignConfirm.limitIn("011"))
        assertNull(SignConfirm.limitIn("07"))
    }
}
