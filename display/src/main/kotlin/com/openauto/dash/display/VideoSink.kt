package com.openauto.dash.display

import com.openauto.dash.link.VideoConfig
import com.openauto.dash.link.VideoPacket
import java.util.Base64

/**
 * The head unit's stream to the hardware decoder.
 *
 * A decoder can only start on a key frame, preceded by the stream's parameter
 * sets ([VideoConfig.csd]). So after a (re)start, a [VideoConfig] or a dropped
 * packet, everything up to the next key frame is thrown away and [requestKeyFrame]
 * asks the head unit for one rather than waiting out its key-frame interval.
 * A packet is dropped rather than queued when the decoder falls behind: a
 * picture that arrives late is worth less than the next one.
 */
class VideoSink(
    private val start: () -> Sink,
    private val requestKeyFrame: () -> Unit
) {
    /** What the sink writes to: a [GstProcess] on the Pi, a fake in tests. */
    interface Sink {
        val alive: Boolean
        fun offer(chunk: ByteArray): Boolean
        fun stop()
    }

    private var sink: Sink? = null
    private var csd = ByteArray(0)
    private var waitingForKey = true

    private var shown = 0
    private var dropped = 0
    private var bytes = 0L

    @Synchronized
    fun configure(config: VideoConfig) {
        csd = runCatching { Base64.getDecoder().decode(config.csd) }.getOrDefault(ByteArray(0))
        waitingForKey = true
    }

    @Synchronized
    fun feed(packet: VideoPacket) {
        bytes += packet.data.size
        val running = sink?.takeIf { it.alive } ?: run {
            // Not started yet, or the decoder died: start afresh from a key frame.
            val restarted = sink != null
            sink?.stop()
            waitingForKey = true
            if (restarted) requestKeyFrame()
            start().also { sink = it }
        }
        if (waitingForKey) {
            if (!packet.keyFrame) {
                // Joined mid-stream or lost a frame: ask rather than wait out the key-frame interval.
                dropped++
                requestKeyFrame()
                return
            }
            if (!running.offer(csd + packet.data)) {
                dropped++
                requestKeyFrame()
                return
            }
            waitingForKey = false
            shown++
            return
        }
        if (running.offer(packet.data)) {
            shown++
        } else {
            // A reference frame lost: what follows can't be decoded until the next key frame.
            dropped++
            waitingForKey = true
            requestKeyFrame()
        }
    }

    /** Needs a key frame before anything more can be shown (just started, or lost a frame). */
    val needsKeyFrame: Boolean
        @Synchronized get() = waitingForKey

    @Synchronized
    fun stop() {
        sink?.stop()
        sink = null
        waitingForKey = true
    }

    /** Frames handed to the decoder, frames dropped and bytes received since the last call. */
    @Synchronized
    fun takeStats(): Triple<Int, Int, Long> = Triple(shown, dropped, bytes).also {
        shown = 0
        dropped = 0
        bytes = 0
    }
}

/** A [GstProcess] as a [VideoSink.Sink]. */
class GstVideoSink private constructor(private val process: GstProcess?) : VideoSink.Sink {
    override val alive get() = process?.alive == true
    override fun offer(chunk: ByteArray) = process?.offer(chunk) == true
    override fun stop() {
        process?.stop()
    }

    companion object {
        // About a second of video at 30 fps: more than that and the picture would lag.
        fun start(command: List<String>) = GstVideoSink(GstProcess.startOrNull(command, capacity = 30))
    }
}
