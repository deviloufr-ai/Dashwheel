package com.openauto.dash.display

import com.openauto.dash.link.ClusterState
import com.openauto.dash.link.VideoConfig
import com.openauto.dash.link.VideoPacket

/**
 * What the monitor shows: the head unit's video, the display's own cluster
 * drawn from data, or its idle screen. Only one GStreamer process holds the
 * screen at a time, so switching between video and drawn pictures stops one
 * before starting the other (about a second of black).
 *
 * Readings and video are only shown while the head unit is heard: when it
 * goes silent (the hotspot dropped, it froze) the last speed must not stay up
 * as if it were live, so the screen says there is no signal until it is back.
 */
class Screen(
    private val config: DisplayConfig,
    mode: ScreenMode,
    private val pairing: DisplayPairing,
    requestKeyFrame: () -> Unit,
    private val startVideo: () -> VideoSink.Sink = { BootLogo.release(); GstVideoSink.start(Pipelines.video(config, Rotation.upsideDown)) },
    private val startFrames: (Int, Int) -> GstProcess? = { w, h ->
        BootLogo.release()
        GstProcess.startOrNull(Pipelines.frames(config, w, h), capacity = 1)
    },
    logo: java.awt.image.BufferedImage? = BootLogo.image,
    private val showOnConsole: (java.awt.image.BufferedImage) -> Unit = ConsoleFrameBuffer::show,
    /** Time that only moves forward: the Pi's wall clock jumps when the network sets it. */
    private val uptimeMs: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    enum class Showing { IDLE, DATA, VIDEO }

    private val painter = Painter(mode.width, mode.height, config.overscanPct, logo).also { it.upsideDown = Rotation.upsideDown }
    // The last drawn picture stays up while the decoder starts (see ConsoleFrameBuffer).
    private val video = VideoSink(start = { showOnConsole(painter.image); stopFrames(); startVideo() }, requestKeyFrame = requestKeyFrame)
    private var frames: GstProcess? = null

    @Volatile var showing = Showing.IDLE
        private set
    private var status = WAITING
    private var cluster: ClusterState? = null
    // When the head unit last sent readings, and when it last sent anything (uptime);
    // none heard yet counts from the start, which leaves it the time to boot and dial.
    private var clusterAt = 0L
    private var heardAt = uptimeMs()
    // The clock as the head unit last asked for it, for the pictures drawn without its readings.
    private var clock12 = false

    /** The head unit went silent: "no signal" shows in place of its readings or its video. */
    val signalLost: Boolean
        @Synchronized get() = when (showing) {
            Showing.IDLE -> false
            Showing.DATA -> cluster != null && uptimeMs() - clusterAt > DATA_SILENT_MS
            Showing.VIDEO -> videoLost
        }
    private var videoLost = false

    /**
     * The idle screen carries the pairing code until a head unit has used it,
     * and again once none has come for a while: a display forgotten on the
     * head unit can be paired again without pulling its SD card. Sooner than
     * that the head unit is expected back, and the logo stays alone.
     */
    internal val pairingShown: Boolean
        @Synchronized get() = showing == Showing.IDLE && (!pairing.used || uptimeMs() - heardAt > PAIR_AGAIN_MS)

    /** Head unit clock minus ours: the Pi has no clock of its own and may have no network time. */
    private var clockOffset = 0L

    private val ticker = Thread({ tick() }, "screen-ticker").apply { isDaemon = true }

    @Synchronized
    fun start() {
        redraw()
        ticker.start()
    }

    @Synchronized
    fun idle(status: String = WAITING) {
        this.status = status
        if (showing == Showing.VIDEO) video.stop()
        // The next session starts from its own readings, never the last one's.
        cluster = null
        videoLost = false
        showing = Showing.IDLE
        redraw()
    }

    @Synchronized
    fun data(state: ClusterState?) {
        if (state != null) {
            cluster = state
            clusterAt = uptimeMs()
            clockOffset = state.clock - System.currentTimeMillis()
            clock12 = state.clock12
        }
        if (showing == Showing.VIDEO) video.stop()
        videoLost = false
        showing = Showing.DATA
        redraw()
    }

    @Synchronized
    fun video() {
        if (showing == Showing.VIDEO) return
        showing = Showing.VIDEO
        heardAt = uptimeMs()
        log("video: asked")
        video.prepare()
    }

    /** The head unit sent something, whatever it was: it is there. */
    @Synchronized
    fun heard() {
        heardAt = uptimeMs()
        if (videoLost && showing == Showing.VIDEO) {
            videoLost = false
            log("video: the head unit is back")
            video.prepare()
        }
    }

    fun configureVideo(config: VideoConfig) = video.configure(config)

    /** The head unit's "Turn the picture": kept for the next start, shown at once. */
    @Synchronized
    fun turn(upsideDown: Boolean) {
        if (!Rotation.set(pairing.dir, upsideDown)) return
        log("picture turned: ${if (upsideDown) "upside down" else "upright"}")
        painter.upsideDown = upsideDown
        if (showing == Showing.VIDEO) {
            // The decoder's chain changes: started afresh, from the next key frame.
            video.stop()
            video.prepare()
        } else {
            redraw()
        }
    }

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
            refresh()
        }
    }

    /**
     * Once a second: the clock moves (the video draws its own), and a head
     * unit that went silent stops being shown as if it were live.
     */
    @Synchronized
    internal fun refresh() {
        if (showing == Showing.VIDEO && !videoLost && uptimeMs() - heardAt > VIDEO_SILENT_MS) {
            videoLost = true
            log("video: the head unit went silent")
            video.stop()
        }
        if (showing != Showing.VIDEO || videoLost) redraw()
    }

    private fun redraw() {
        val now = System.currentTimeMillis() + clockOffset
        when (showing) {
            Showing.VIDEO -> if (videoLost) painter.paintIdle(config.name, NO_SIGNAL, now, null, clock12) else return
            Showing.IDLE -> painter.paintIdle(config.name, status, now, pairing.offer.toUri().takeIf { pairingShown }, clock12)
            Showing.DATA -> cluster?.takeIf { !signalLost }?.let { painter.paintCluster(it, now) }
                ?: painter.paintIdle(config.name, if (signalLost) NO_SIGNAL else status, now, null, clock12)
        }
        val process = frames?.takeIf { it.alive } ?: run {
            frames?.stop()
            // The same picture underneath until the new process shows its first.
            showOnConsole(painter.image)
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
        const val NO_SIGNAL = "No signal from Dashwheel"
        /** The head unit sends its readings every 5 s at least: two missed and they are old. */
        const val DATA_SILENT_MS = 12_000L
        /** With a still picture only its ping is heard, every 15 s: one missed. */
        const val VIDEO_SILENT_MS = 20_000L
        /** No head unit for this long and the pairing code is offered again. */
        const val PAIR_AGAIN_MS = 180_000L
    }
}
