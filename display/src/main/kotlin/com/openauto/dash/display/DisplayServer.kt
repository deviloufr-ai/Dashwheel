package com.openauto.dash.display

import com.openauto.dash.link.ClusterState
import com.openauto.dash.link.DisplayCommand
import com.openauto.dash.link.DisplayHello
import com.openauto.dash.link.DisplayMode
import com.openauto.dash.link.DisplayStats
import com.openauto.dash.link.Hello
import com.openauto.dash.link.Incoming
import com.openauto.dash.link.LinkSession
import com.openauto.dash.link.Ping
import com.openauto.dash.link.Pong
import com.openauto.dash.link.SecureChannel
import com.openauto.dash.link.VideoConfig
import com.openauto.dash.link.VideoPacket
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Answers the head unit. One session at a time: a new connection (the head
 * unit redialling after the hotspot dropped) replaces the old one, whose
 * socket may not have noticed yet.
 */
class DisplayServer(
    private val config: DisplayConfig,
    private val pairing: DisplayPairing,
    private val hello: DisplayHello,
    private val screenFor: (requestKeyFrame: () -> Unit) -> Screen = { request -> Screen(config, ScreenMode(hello.width, hello.height), pairing, request) }
) {
    @Volatile private var current: LinkSession? = null
    @Volatile private var lastKeyRequest = 0L
    val screen: Screen = screenFor(::requestKeyFrame)
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "display-stats").apply { isDaemon = true } }

    fun run(server: ServerSocket = ServerSocket(config.port)) {
        screen.start()
        timer.scheduleAtFixedRate(::sendStats, STATS_MS, STATS_MS, TimeUnit.MILLISECONDS)
        log("listening on port ${server.localPort}")
        server.use {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (e: IOException) {
                    if (server.isClosed) break else continue
                }
                Thread({ serve(socket) }, "display-session").apply { isDaemon = true }.start()
            }
        }
    }

    private fun serve(socket: Socket) {
        val session = try {
            socket.tcpNoDelay = true
            socket.soTimeout = READ_TIMEOUT_MS
            SecureChannel.server(socket.getInputStream(), socket.getOutputStream(), pairing::secretFor, onClose = socket::close)
        } catch (e: IOException) {
            log("refused ${socket.inetAddress.hostAddress}: ${e.message}")
            runCatching { socket.close() }
            return
        }
        synchronized(this) {
            current?.close()
            current = session
        }
        log("head unit connected from ${socket.inetAddress.hostAddress}")
        pairing.markUsed()
        try {
            session.send(hello)
            screen.idle("Connected")
            while (true) {
                when (val frame = session.receiveAny()) {
                    is Incoming.Binary -> VideoPacket.decode(frame.bytes)?.let(screen::feed)
                    is Incoming.Message -> handle(session, frame)
                    null -> Unit
                }
            }
        } catch (e: IOException) {
            log("head unit gone: ${e.message}")
        } finally {
            session.close()
            val wasCurrent = synchronized(this) {
                (current === session).also { if (it) current = null }
            }
            if (wasCurrent) screen.idle()
        }
    }

    private fun handle(session: LinkSession, frame: Incoming.Message) {
        when (val message = frame.message) {
            is DisplayMode -> when (message.mode) {
                DisplayMode.Mode.IDLE -> screen.idle("Connected")
                DisplayMode.Mode.DATA -> screen.data(null)
                DisplayMode.Mode.VIDEO -> screen.video()
            }
            is VideoConfig -> screen.configureVideo(message)
            is ClusterState -> screen.data(message)
            is Ping -> session.sendOrClose(Pong)
            is Hello -> log("head unit: ${message.deviceName} ${message.appVersion}")
            else -> Unit
        }
    }

    private fun requestKeyFrame() {
        val now = System.currentTimeMillis()
        // The decoder may ask on every dropped frame; one request per half second is plenty.
        if (now - lastKeyRequest < 500) return
        lastKeyRequest = now
        current?.sendOrClose(DisplayCommand(DisplayCommand.Action.KEYFRAME_PLEASE))
    }

    private fun sendStats() {
        val (shown, dropped, bytes) = screen.takeVideoStats()
        if (screen.showing != Screen.Showing.VIDEO) return
        current?.sendOrClose(DisplayStats(shown, dropped, (bytes * 8 / STATS_MS).toInt(), STATS_MS))
    }

    private companion object {
        const val STATS_MS = 5_000L
        /** The head unit pings every 15 s: three missed and the link is dead. */
        const val READ_TIMEOUT_MS = 45_000
    }
}

internal fun log(message: String) = println("[dashwheel-display] $message")
