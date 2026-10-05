package com.openauto.dash.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoWindowTest {

    @Test
    fun aDisplayThatNeverConfirmsIsNeverHeldBack() {
        val w = VideoWindow()
        for (i in 0 until 100) {
            assertTrue(w.mayQueue())
            w.queued(i * 33_333L)
        }
    }

    @Test
    fun noMoreThanAThirdOfASecondAhead() {
        val w = VideoWindow()
        w.queued(0)
        w.acked(0)
        // 30 fps: frames go until 0.3 s are out unconfirmed
        var t = 0L
        while (w.mayQueue()) { t += 33_333; w.queued(t) }
        assertTrue("held back at $t", t in 300_000L..340_000L)
        // the network gives one back: room for one more
        w.acked(66_666)
        assertTrue(w.mayQueue())
    }

    @Test
    fun aNewLinkOrStreamStartsFree() {
        val w = VideoWindow()
        w.queued(0); w.acked(0); w.queued(1_000_000)
        assertFalse(w.mayQueue())
        w.reset()
        assertTrue(w.mayQueue())
        // an encoder started again: timestamps from far back count as the new start
        w.queued(50_000_000); w.acked(50_000_000); w.queued(50_100_000)
        w.acked(1_000)
        w.queued(1_100)
        assertTrue(w.mayQueue())
    }

    @Test
    fun theAckTravelsAsAMessage() {
        val back = LinkCodec.decode(LinkCodec.encode(VideoAck(123_456L)))
        assertEquals(VideoAck(123_456L), back)
    }
}
