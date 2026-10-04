package com.openauto.dash.display

import com.openauto.dash.link.ClusterState
import com.openauto.dash.link.VideoPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A head unit that goes silent is not shown as if it were live. */
class ScreenSignalTest {

    @get:Rule val tmp = TemporaryFolder()

    private class FakeSink : VideoSink.Sink {
        var stopped = false
        override val alive get() = !stopped
        override fun offer(chunk: ByteArray) = true
        override fun stop() {
            stopped = true
        }
    }

    private var now = 0L
    private val sinks = mutableListOf<FakeSink>()

    private fun screen(pairing: DisplayPairing = DisplayPairing(tmp.root, "Test display")): Screen {
        val config = DisplayConfig(name = "Test display")
        return Screen(
            config, ScreenMode(1024, 600), pairing, requestKeyFrame = {},
            startVideo = { FakeSink().also { sinks += it } }, startFrames = { _, _ -> null }, logo = null, showOnConsole = {},
            uptimeMs = { now }
        )
    }

    @Test
    fun readingsGoOldWhenTheHeadUnitStopsSendingThem() {
        val screen = screen()
        screen.data(ClusterState(clock = 1, speedKmh = 87))
        now += Screen.DATA_SILENT_MS
        screen.refresh()
        assertFalse(screen.signalLost)
        now += 1
        screen.refresh()
        assertTrue(screen.signalLost)
        // The next readings bring them back.
        screen.data(ClusterState(clock = 2, speedKmh = 90))
        assertFalse(screen.signalLost)
    }

    @Test
    fun aNewSessionNeverShowsTheLastOnesReadings() {
        val screen = screen()
        screen.data(ClusterState(clock = 1, speedKmh = 87))
        screen.idle()
        now += Screen.DATA_SILENT_MS * 2
        // Data mode asked before the first readings: nothing old to call lost.
        screen.data(null)
        assertFalse(screen.signalLost)
    }

    @Test
    fun aSilentVideoGivesWayToNoSignalAndComesBack() {
        val screen = screen()
        screen.video()
        screen.feed(VideoPacket(keyFrame = true, ptsUs = 0, data = ByteArray(4)))
        now += Screen.VIDEO_SILENT_MS + 1
        screen.refresh()
        assertTrue(screen.signalLost)
        assertTrue(sinks.single().stopped)
        assertEquals(Screen.Showing.VIDEO, screen.showing)
        // Heard again: the decoder starts afresh.
        screen.heard()
        assertFalse(screen.signalLost)
        assertEquals(2, sinks.size)
    }

    @Test
    fun aStillPictureWithPingsStaysUp() {
        val screen = screen()
        screen.video()
        repeat(4) {
            now += 15_000
            screen.heard()
            screen.refresh()
        }
        assertFalse(screen.signalLost)
    }

    @Test
    fun aPairedDisplayOffersItsCodeAgainOnceNoHeadUnitComes() {
        val pairing = DisplayPairing(tmp.root, "Test display")
        val screen = screen(pairing)
        // Never paired: the code is there from the start.
        assertTrue(screen.pairingShown)
        pairing.markUsed()
        screen.heard()
        screen.idle()
        assertFalse(screen.pairingShown)
        // The head unit is expected back: the logo stays alone.
        now += Screen.PAIR_AGAIN_MS
        screen.refresh()
        assertFalse(screen.pairingShown)
        // Forgotten there, or gone for good: the code comes back by itself.
        now += 1
        screen.refresh()
        assertTrue(screen.pairingShown)
        // A head unit that still knows the display takes it away again.
        screen.heard()
        assertFalse(screen.pairingShown)
    }

    @Test
    fun aDisplayStartedWithoutItsHeadUnitWaitsBeforeOfferingItsCode() {
        val pairing = DisplayPairing(tmp.root, "Test display").apply { markUsed() }
        now = 5_000_000
        val screen = screen(pairing)
        now += Screen.PAIR_AGAIN_MS
        assertFalse(screen.pairingShown)
        now += 1
        assertTrue(screen.pairingShown)
        // Never over the head unit's readings.
        screen.data(ClusterState(clock = 1, speedKmh = 30))
        assertFalse(screen.pairingShown)
    }
}
