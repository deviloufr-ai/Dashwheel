package com.openauto.dash.display

import com.openauto.dash.link.ClusterState
import com.openauto.dash.link.VideoConfig
import com.openauto.dash.link.VideoPacket

/**
 * What the monitor shows: the head unit's video, the display's own cluster
 * drawn from data, or its idle screen. Only one GStreamer process holds the
 * screen at a time, so switching between video and drawn pictures stops one
 * before starting the other (about a second of black).
 */
class Screen(
    private val config: DisplayConfig,
    mode: ScreenMode,
    private val pairing: DisplayPairing,
    requestKeyFrame: () -> Unit,
    private val startVideo: () -> VideoSink.Sink = { GstVideoSink.start(Pipelines.video(config)) },
    private val startFrames: (Int, Int) -> GstProcess? = { w, h -> GstProcess.startOrNull(Pipelines.frames(config, w, h), capacity = 1) }
) {
    enum class Showing { IDLE, DATA, VIDEO }

    private val painter = Painter(mode.width, mode.height, config.overscanPct)
    private val video = VideoSink(start = { stopFrames(); startVideo() }, requestKeyFrame = requestKeyFrame)
    private var frames: GstProcess? = null

    @Volatile var showing = Showing.IDLE
        private set
    private var status = WAITING
    private var cluster: ClusterState? = null
    /** Head unit clock minus ours: the Pi has no clock of its own and may have no network time. */
    private var clockOffset = 0L

    private val ticker = Thread({ tick() }, "screen-ticker").apply { isDaemon = true }

    fun start() {
        redraw()
        ticker.start()
    }

    @Synchronized
    fun idle(status: String = WAITING) {
        this.status = status
        if (showing == Showing.VIDEO) video.stop()
        showing = Showing.IDLE
        redraw()
    }

    @Synchronized
    fun data(state: ClusterState?) {
        if (state != null) {
            cluster = state
            clockOffset = state.clock - System.currentTimeMillis()
        }
        if (showing == Showing.VIDEO) video.stop()
        showing = Showing.DATA
        redraw()
    }

    @Synchronized
    fun video() {
        showing = Showing.VIDEO
    }

    fun configureVideo(config: VideoConfig) = video.configure(config)

    // Under the screen's lock like everything else here, so the lock order is
    // always screen then decoder (starting the decoder stops the drawn pictures).
    @Synchronized
    fun feed(packet: VideoPacket) {
        if (showing == Showing.VIDEO) video.feed(packet)
    }

    fun takeVideoStats() = video.takeStats()

    @Synchronized
    fun stop() {
        video.stop()
        stopFrames()
    }

    private fun tick() {
        while (true) {
            try {
                Thread.sleep(1_000)
            } catch (_: InterruptedException) {
                return
            }
            // The clock moves; the video draws its own.
            synchronized(this) { if (showing != Showing.VIDEO) redraw() }
        }
    }

    private fun redraw() {
        val now = System.currentTimeMillis() + clockOffset
        when (showing) {
            Showing.VIDEO -> return
            Showing.IDLE -> painter.paintIdle(config.name, status, now, pairing.offer.toUri().takeUnless { pairing.used })
            Showing.DATA -> cluster?.let { painter.paintCluster(it, now) }
                ?: painter.paintIdle(config.name, status, now, null)
        }
        val process = frames?.takeIf { it.alive } ?: run {
            frames?.stop()
            startFrames(painter.width, painter.height).also { frames = it }
        } ?: return
        // Only the newest picture matters.
        process.clearQueue()
        process.offer(painter.bgrx())
    }

    private fun stopFrames() {
        frames?.stop()
        frames = null
    }

    companion object {
        const val WAITING = "Waiting for Dashwheel…"
    }
}
