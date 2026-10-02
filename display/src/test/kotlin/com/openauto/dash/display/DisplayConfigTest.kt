package com.openauto.dash.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Properties

class DisplayConfigTest {

    private fun parse(text: String) = DisplayConfig.parse(Properties().apply { load(text.reader()) })

    @Test
    fun emptyFileGivesDefaults() {
        assertEquals(DisplayConfig(), parse(""))
    }

    @Test
    fun readsEveryKey() {
        val c = parse(
            """
            name=Rear screen
            overscan=5
            width=720
            height=576
            sink=auto
            port=48000
            video_pipeline=fdsrc ! h264parse ! fakesink
            """.trimIndent()
        )
        assertEquals("Rear screen", c.name)
        assertEquals(5, c.overscanPct)
        assertEquals(720, c.width)
        assertEquals(576, c.height)
        assertEquals(DisplayConfig.Sink.AUTO, c.sink)
        assertEquals(48000, c.port)
        assertEquals("fdsrc ! h264parse ! fakesink", c.videoPipeline)
    }

    @Test
    fun nonsenseFallsBackToDefaults() {
        val c = parse("overscan=90\nwidth=12\nsink=hdmi\nport=80\nname=")
        assertEquals(15, c.overscanPct)
        assertNull(c.width)
        assertEquals(DisplayConfig.Sink.KMS, c.sink)
        assertEquals(DisplayConfig().port, c.port)
        assertEquals(DisplayConfig().name, c.name)
    }

    @Test
    fun pipelinesReadStdin() {
        val video = Pipelines.video(DisplayConfig())
        assertEquals(listOf("gst-launch-1.0", "-q", "fdsrc", "fd=0"), video.take(4))
        assert("v4l2h264dec" in video && "kmssink" in video)
        val desktop = Pipelines.video(DisplayConfig(sink = DisplayConfig.Sink.AUTO))
        assert("avdec_h264" in desktop && "autovideosink" in desktop)
        val frames = Pipelines.frames(DisplayConfig(), 1024, 600)
        assert("blocksize=${1024 * 600 * 4}" in frames && "format=bgrx" in frames)
        assertEquals(
            listOf("gst-launch-1.0", "-q", "fdsrc", "!", "fakesink"),
            Pipelines.video(DisplayConfig(videoPipeline = " fdsrc  ! fakesink "))
        )
    }
}
