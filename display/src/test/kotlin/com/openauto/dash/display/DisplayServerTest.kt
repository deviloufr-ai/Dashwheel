package com.openauto.dash.display

import com.openauto.dash.link.ClusterState
import com.openauto.dash.link.DisplayCommand
import com.openauto.dash.link.DisplayHello
import com.openauto.dash.link.DisplayMode
import com.openauto.dash.link.LinkSession
import com.openauto.dash.link.Ping
import com.openauto.dash.link.Pong
import com.openauto.dash.link.SecureChannel
import com.openauto.dash.link.UnknownPairingException
import com.openauto.dash.link.VideoConfig
import com.openauto.dash.link.VideoPacket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

class DisplayServerTest {

    @get:Rule val tmp = TemporaryFolder()

    private class FakeSink : VideoSink.Sink {
        val written = CopyOnWriteArrayList<ByteArray>()
        override val alive = true
        override fun offer(chunk: ByteArray) = written.add(chunk)
        override fun stop() = Unit
    }

    private fun waitFor(what: String, check: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!check()) {
            if (System.currentTimeMillis() > until) fail("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    @Test
    fun headUnitWithThePairingDrivesTheScreen() {
        val config = DisplayConfig(name = "Test display")
        val pairing = DisplayPairing(tmp.root, config.name)
        val hello = DisplayHello("Test display", "dev", 1024, 600)
        val sink = FakeSink()
        val server = DisplayServer(config, pairing, hello) { request ->
            Screen(config, ScreenMode(1024, 600), pairing, request, startVideo = { sink }, startFrames = { _, _ -> null })
        }
        val socket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        Thread { server.run(socket) }.apply { isDaemon = true }.start()

        // A stranger is turned away.
        try {
            val s = Socket(InetAddress.getLoopbackAddress(), socket.localPort)
            SecureChannel.client(s.getInputStream(), s.getOutputStream(), "0123456789abcdef", ByteArray(32), onClose = s::close)
            fail("unknown pairing accepted")
        } catch (e: UnknownPairingException) {
            // expected
        }
        assertEquals(false, pairing.used)

        // The head unit, with the offer the phone scanned.
        val offer = pairing.offer
        val s = Socket(InetAddress.getLoopbackAddress(), socket.localPort)
        s.soTimeout = 5_000
        val unit: LinkSession = SecureChannel.client(s.getInputStream(), s.getOutputStream(), offer.id, offer.secret, onClose = s::close)
        assertEquals(hello, unit.receive())
        waitFor("the pairing to be marked used") { pairing.used }

        unit.send(Ping)
        assertEquals(Pong, unit.receive())

        unit.send(ClusterState(clock = 1, speedKmh = 50))
        waitFor("data mode") { server.screen.showing == Screen.Showing.DATA }

        val csd = byteArrayOf(0, 0, 0, 1, 0x67)
        unit.send(DisplayMode(DisplayMode.Mode.VIDEO))
        unit.send(VideoConfig(width = 1024, height = 600, fps = 30, csd = Base64.getEncoder().encodeToString(csd)))
        // A delta frame first: dropped, and the display asks for a key frame.
        unit.sendBinary(VideoPacket(false, 0, byteArrayOf(0, 0, 0, 1, 0x41)).encode())
        assertEquals(DisplayCommand(DisplayCommand.Action.KEYFRAME_PLEASE), unit.receive())
        unit.sendBinary(VideoPacket(true, 1, byteArrayOf(0, 0, 0, 1, 0x65)).encode())
        waitFor("the key frame to reach the decoder") { sink.written.isNotEmpty() }
        assertArrayEquals(csd + byteArrayOf(0, 0, 0, 1, 0x65), sink.written.first())
        assertTrue(server.screen.showing == Screen.Showing.VIDEO)

        unit.close()
        waitFor("the idle screen") { server.screen.showing == Screen.Showing.IDLE }
        socket.close()
    }
}
