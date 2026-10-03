package com.openauto.dash.display

import com.openauto.dash.link.VideoConfig
import com.openauto.dash.link.VideoPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** A decoder that dies as soon as it starts is not started again for every packet. */
class VideoSinkRestartTest {
    private class DeadSink : VideoSink.Sink {
        override val alive = false
        override fun offer(chunk: ByteArray) = false
        override fun stop() = Unit
    }

    private val key = VideoPacket(true, 0, byteArrayOf(1))

    @Test
    fun aDecoderThatKeepsDyingIsGivenARest() {
        var now = 0L
        var starts = 0
        val video = VideoSink(start = { starts++; DeadSink() }, requestKeyFrame = {}, clock = { now })
        video.configure(VideoConfig(width = 1024, height = 600, fps = 30, csd = Base64.getEncoder().encodeToString(byteArrayOf(0))))

        // A second of video at 30 packets a second: the first start, one more try at once, then a rest.
        repeat(30) {
            video.feed(key)
            now += 33
        }
        assertEquals(2, starts)

        // Ten more seconds: a try every few seconds, not three hundred.
        repeat(300) {
            video.feed(key)
            now += 33
        }
        assertTrue("started $starts times", starts in 3..6)
    }

    @Test
    fun theWaitGrowsAndStopsGrowing() {
        assertEquals(0L, VideoSink.restartWait(0))
        assertEquals(0L, VideoSink.restartWait(1))
        assertTrue(VideoSink.restartWait(2) < VideoSink.restartWait(3))
        assertEquals(VideoSink.restartWait(4), VideoSink.restartWait(40))
    }
}
