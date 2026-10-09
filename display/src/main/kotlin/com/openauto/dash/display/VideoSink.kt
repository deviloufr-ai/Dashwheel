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
    private val requestKeyFrame: () -> Unit,
    /** Milliseconds that only go forward. */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }
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
    /** Of [dropped], those the decoder had no room for: this board was too slow, not the link. */
    private var droppedLate = 0
    private var bytes = 0L

    @Synchronized
    fun configure(config: VideoConfig) {
        csd = runCatching { Base64.getDecoder().decode(config.csd) }.getOrDefault(ByteArray(0))
        waitingForKey = true
    }

    /** Starts the decoder ahead of the first packet: it takes a couple of seconds on a Pi 3. */
    @Synchronized
    fun prepare() {
        if (sink?.alive != true) running()
    }

    private var startedAt = 0L
    private var quickDeaths = 0
    private var buried: Sink? = null

    /**
     * The decoder, started if need be; null while one that keeps dying at
     * once is given a rest. Without it (no hardware decoder on this board, a
     * pipeline that doesn't parse) a new process was started for every packet
     * that came in, thirty times a second, for as long as the head unit streamed.
     */
    private fun running(): Sink? {
        sink?.takeIf { it.alive }?.let { return it }
        val dead = sink
        val now = clock()
        if (dead != null) {
            if (dead !== buried) {
                buried = dead
                dead.stop()
                quickDeaths = if (now - startedAt < HEALTHY_MS) quickDeaths + 1 else 0
            }
            if (now - startedAt < restartWait(quickDeaths)) return null
        }
        // Not started yet, or the decoder died: start afresh from a key frame.
        waitingForKey = true
        if (dead != null) requestKeyFrame()
        log("video: decoder starting")
        startedAt = now
        return start().also { sink = it }
    }

    @Synchronized
    fun feed(packet: VideoPacket) {
        bytes += packet.data.size
        val running = running() ?: run {
            dropped++
            return
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
                droppedLate++
                requestKeyFrame()
                return
            }
            waitingForKey = false
            log("video: key frame, decoding")
            shown++
            return
        }
        if (running.offer(packet.data)) {
            shown++
        } else {
            // The decoder's queue is full: this board is behind, and a reference frame is
            // lost with it, so what follows can't be decoded until the next key frame.
            dropped++
            droppedLate++
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
        buried = null
        quickDeaths = 0
        waitingForKey = true
    }

    companion object {
        /** A decoder that ran this long had started properly. */
        const val HEALTHY_MS = 10_000L

        /** How long after its start a decoder is started again: at once the first time it dies, then less and less often. */
        fun restartWait(quickDeaths: Int): Long = when {
            quickDeaths <= 1 -> 0L
            quickDeaths == 2 -> 1_000L
            quickDeaths == 3 -> 2_000L
            else -> 5_000L
        }
    }

    /** What happened to the frames since the last call. */
    data class Stats(val shown: Int, val dropped: Int, val droppedLate: Int, val bytes: Long)

    /** Frames handed to the decoder, frames dropped (and how many of those for want of room) and bytes received since the last call. */
    @Synchronized
    fun takeStats(): Stats = Stats(shown, dropped, droppedLate, bytes).also {
        shown = 0
        dropped = 0
        droppedLate = 0
        bytes = 0
    }
}

/** A [GstProcess] as a [VideoSink.Sink]. */
class GstVideoSink private constructor(private val process: GstProcess?) : VideoSink.Sink {
    override val alive get() = process?.alive == true
    // An access unit delimiter after each frame: h264parse only lets a frame go once it
    // sees where the next one starts, so every frame used to wait one frame time.
    override fun offer(chunk: ByteArray) = process?.offer(chunk + END_OF_FRAME) == true
    override fun stop() {
        process?.stop()
    }

    companion object {
        /** An H.264 access unit delimiter (NAL type 9, any picture type). */
        private val END_OF_FRAME = byteArrayOf(0, 0, 0, 1, 9, 0xF0.toByte())

        // A few frames, a quarter to a third of a second: a decoder that falls behind drops
        // frames (and gets a lower bitrate) instead of showing the road seconds late.
        fun start(command: List<String>) = GstVideoSink(GstProcess.startOrNull(command, capacity = 5))
    }
}
