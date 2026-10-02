package com.openauto.dash.display

import com.openauto.dash.link.VideoConfig
import com.openauto.dash.link.VideoPacket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class VideoSinkTest {

    private class FakeSink(var room: Int = Int.MAX_VALUE) : VideoSink.Sink {
        val written = mutableListOf<ByteArray>()
        override var alive = true
        override fun offer(chunk: ByteArray): Boolean {
            if (written.size >= room) return false
            written += chunk
            return true
        }
        override fun stop() {
            alive = false
        }
    }

    private val csd = byteArrayOf(0, 0, 0, 1, 0x67, 0, 0, 0, 1, 0x68)
    private val key = VideoPacket(true, 0, byteArrayOf(0, 0, 0, 1, 0x65, 9))
    private val delta = VideoPacket(false, 1, byteArrayOf(0, 0, 0, 1, 0x41, 7))
    private fun config() = VideoConfig(width = 1024, height = 600, fps = 30, csd = Base64.getEncoder().encodeToString(csd))

    @Test
    fun startsOnAKeyFramePrecededByTheParameterSets() {
        val sinks = mutableListOf<FakeSink>()
        var requests = 0
        val video = VideoSink(start = { FakeSink().also(sinks::add) }, requestKeyFrame = { requests++ })
        video.configure(config())
        video.feed(delta)
        assertTrue(video.needsKeyFrame)
        video.feed(key)
        video.feed(delta)
        assertFalse(video.needsKeyFrame)
        val out = sinks.single().written
        assertEquals(2, out.size)
        assertArrayEquals(csd + key.data, out[0])
        assertArrayEquals(delta.data, out[1])
        assertEquals(Triple(2, 1, 18L), video.takeStats())
        // The delta frame before any key frame asked for one.
        assertEquals(1, requests)
    }

    @Test
    fun aDroppedFrameWaitsForTheNextKeyFrameAndAsksForIt() {
        val sink = FakeSink(room = 2)
        var requests = 0
        val video = VideoSink(start = { sink }, requestKeyFrame = { requests++ })
        video.configure(config())
        video.feed(key)
        video.feed(delta)
        video.feed(delta) // the decoder is full: dropped
        assertTrue(video.needsKeyFrame)
        assertEquals(1, requests)
        sink.room = 10
        video.feed(delta) // can't be decoded without the one lost
        assertEquals(2, sink.written.size)
        assertEquals(2, requests)
        video.feed(key)
        assertEquals(3, sink.written.size)
        assertArrayEquals(csd + key.data, sink.written.last())
    }

    @Test
    fun aDeadDecoderIsRestartedFromAKeyFrame() {
        val sinks = mutableListOf<FakeSink>()
        var requests = 0
        val video = VideoSink(start = { FakeSink().also(sinks::add) }, requestKeyFrame = { requests++ })
        video.configure(config())
        video.feed(key)
        sinks.last().alive = false
        video.feed(delta)
        assertEquals(2, sinks.size)
        // One for the restart, one for the delta frame it can't start on.
        assertEquals(2, requests)
        assertTrue(sinks.last().written.isEmpty())
        video.feed(key)
        assertEquals(1, sinks.last().written.size)
    }
}
