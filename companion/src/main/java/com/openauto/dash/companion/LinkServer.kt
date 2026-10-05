package com.openauto.dash.companion

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.openauto.dash.link.ActionResult
import com.openauto.dash.link.CallCommand
import com.openauto.dash.link.CarLocation
import com.openauto.dash.link.CarLog
import com.openauto.dash.link.CarLogAck
import com.openauto.dash.link.CarLookAck
import com.openauto.dash.link.DialResult
import com.openauto.dash.link.Dismiss
import com.openauto.dash.link.DriveReport
import com.openauto.dash.link.DriveSync
import com.openauto.dash.link.Hello
import com.openauto.dash.link.LINK_PORT
import com.openauto.dash.link.LinkMessage
import com.openauto.dash.link.LinkSession
import com.openauto.dash.link.MarkRead
import com.openauto.dash.link.CarNotices
import com.openauto.dash.link.FuelReport
import com.openauto.dash.link.FuelSync
import com.openauto.dash.link.PhoneAbilities
import com.openauto.dash.link.SendText
import com.openauto.dash.link.TextSent
import com.openauto.dash.link.ObdClose
import com.openauto.dash.link.ObdOpen
import com.openauto.dash.link.ObdTx
import com.openauto.dash.link.Ping
import com.openauto.dash.link.Pong
import com.openauto.dash.link.Reply
import com.openauto.dash.link.SecureChannel
import com.openauto.dash.link.TypeResult
import com.openauto.dash.link.TypingAccess
import com.openauto.dash.link.UnknownPairingException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

sealed interface LinkState {
    data object Off : LinkState
    /** Listening: waiting for the head unit to join the hotspot and dial in. */
    data object Waiting : LinkState
    /** The port could not be opened: nothing is listening, the car can't reach this phone. */
    data object Unavailable : LinkState
    data class Connected(val unitName: String) : LinkState
}

/**
 * The phone's end of the link: a TCP server on [LINK_PORT]. The head unit, on
 * this phone's hotspot, dials the hotspot gateway (this phone), proves it
 * holds a pairing secret, then receives the notifications and sends back
 * replies. Nothing gets further without that secret. One head unit at a time;
 * a new connection replaces the old one. Run by [LinkService], which keeps
 * the process alive.
 */
object LinkServer {
    private const val TAG = "LinkServer"
    private const val HANDSHAKE_TIMEOUT_MS = 10_000
    /** The head unit pings every 15 s; three missed pings and the link is dropped. */
    private const val IDLE_TIMEOUT_MS = 45_000
    /** Connections still proving their pairing at once; more are turned away. */
    private const val MAX_HANDSHAKES = 4

    private val _state = MutableStateFlow<LinkState>(LinkState.Off)
    val state: StateFlow<LinkState> = _state

    /**
     * What last happened on the link, as a short technical line under the
     * status: read out when the car does not connect, to tell where it stops.
     */
    private val _lastEvent = MutableStateFlow<String?>(null)
    val lastEvent: StateFlow<String?> = _lastEvent

    /** [logged]: what goes to the system log, when [line] names someone (logs get shared in bug reports). */
    fun note(line: String, logged: String = line) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())
        _lastEvent.value = "$time  ${line.trim().take(140)}"
        Log.i(TAG, logged)
    }

    private val main = Handler(Looper.getMainLooper())
    // Everything written to the socket goes through one thread, in order, off the main thread.
    private val sender = Executors.newSingleThreadExecutor()
    private val handshakes = Semaphore(MAX_HANDSHAKES)
    private var server: ServerSocket? = null
    @Volatile private var session: LinkSession? = null

    @Synchronized
    fun start(context: Context) {
        if (server != null) return
        val app = context.applicationContext
        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(LINK_PORT))
            }
        } catch (e: IOException) {
            Log.w(TAG, "cannot listen on $LINK_PORT", e)
            note("cannot listen on port $LINK_PORT: ${e.message.orEmpty()}")
            _state.value = LinkState.Unavailable
            return
        }
        server = socket
        _state.value = LinkState.Waiting
        note("listening on port $LINK_PORT")
        Thread({ acceptLoop(app, socket) }, "link-accept").start()
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        session?.close()
        session = null
        // The session's own end no longer sees itself as the current one, and skips this.
        ObdRelay.close()
        _state.value = LinkState.Off
    }

    /** Ends the link of the pairing [id] now (the driver removed it), rather than at its next message. */
    fun forget(id: String) {
        val current = session ?: return
        if (current.pairingId == id) current.close()
    }

    /** Sends to the connected head unit, if any. Safe from any thread. */
    fun send(message: LinkMessage) {
        val current = session ?: return
        sender.execute { current.sendOrClose(message) }
    }

    /**
     * Sends to the connected head unit and waits until it is written, in order
     * with [send]. False when there is no link or it broke. Not from the main thread.
     */
    fun sendAndWait(message: LinkMessage): Boolean {
        val current = session ?: return false
        return runCatching { sender.submit<Boolean> { current.sendOrClose(message) }.get() }.getOrDefault(false)
    }

    private fun acceptLoop(context: Context, socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                break
            }
            val from = client.inetAddress?.hostAddress ?: "?"
            // The head unit dials from the phone's hotspot; any other network is not listened to.
            if (!HotspotGate.allows(context, client)) {
                note("$from: not on the hotspot, turned away")
                runCatching { client.close() }
                continue
            }
            // A flood of connections that never finish their handshake is turned away.
            if (!handshakes.tryAcquire()) {
                note("$from: busy, turned away")
                runCatching { client.close() }
                continue
            }
            note("$from: connected, checking the pairing")
            Thread({ serve(context, client) }, "link-session").start()
        }
    }

    private fun serve(context: Context, client: Socket) {
        val link = try {
            client.soTimeout = HANDSHAKE_TIMEOUT_MS
            client.tcpNoDelay = true
            SecureChannel.server(
                client.getInputStream(), client.getOutputStream(),
                secretFor = { PairedUnits.secretFor(context, it) },
                onClose = { runCatching { client.close() } }
            )
        } catch (e: UnknownPairingException) {
            note("${client.inetAddress?.hostAddress}: car's code not paired here")
            runCatching { client.close() }
            return
        } catch (e: Exception) {
            Log.i(TAG, "handshake failed", e)
            note("${client.inetAddress?.hostAddress}: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
            runCatching { client.close() }
            return
        } finally {
            handshakes.release()
        }
        client.soTimeout = IDLE_TIMEOUT_MS
        val unitName = PairedUnits.nameOf(context, link.pairingId) ?: "?"
        // Published under the lock, so a stop() during the handshake is never missed.
        val replaced: LinkSession?
        synchronized(this) {
            if (server == null) {
                link.close()
                return
            }
            replaced = session
            session = link
            _state.value = LinkState.Connected(unitName)
            note("${client.inetAddress?.hostAddress}: linked")
        }
        replaced?.close()

        send(Hello(deviceName(context), appVersion(context)))
        send(PhoneNotificationListener.syncMessage())
        send(PhoneCalls.snapshot())
        send(ObdRelay.offer(context))
        send(PhoneAbilities(sendsTexts = TextSender.canSend(context)))
        PhoneLists.linked()
        PhoneMedia.linked(context)
        PhoneBatteryWatch.current(context)?.let(::send)
        try {
            while (true) {
                val message = link.receive() ?: continue
                // One message that can't be handled is skipped, rather than ending the app.
                try {
                    handle(context, link, message)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "message not handled: ${message::class.simpleName}", e)
                }
            }
        } catch (e: IOException) {
            // Link gone: the head unit left the hotspot, stopped, or went quiet.
        } finally {
            link.close()
            synchronized(this) {
                if (session === link) {
                    session = null
                    if (server != null) _state.value = LinkState.Waiting
                    // No head unit to relay to: the OBD adapter is let go for the next start.
                    ObdRelay.close()
                }
            }
        }
    }

    private fun handle(context: Context, link: LinkSession, message: LinkMessage) {
        // A removed pairing ends the link it is using.
        if (PairedUnits.units.value.none { it.id == link.pairingId }) {
            link.close()
            return
        }
        when (message) {
            Ping -> send(Pong)
            is Reply -> onMain(message.key, ActionResult.Action.REPLY) { it.reply(message.key, message.text) }
            is MarkRead -> onMain(message.key, ActionResult.Action.MARK_READ) { it.markRead(message.key) }
            is Dismiss -> onMain(message.key, ActionResult.Action.DISMISS) { it.dismiss(message.key) }
            is CallCommand -> main.post {
                if (message.action == CallCommand.Action.DIAL) {
                    // The call itself then reaches the car as any other, through the phone's call state.
                    val number = message.number.orEmpty()
                    send(DialResult(number, PhoneCalls.dial(context, number)))
                } else if (!PhoneCalls.command(context, message.action)) {
                    // Refused (no permission, no call): tell the head unit what the call really is.
                    send(PhoneCalls.snapshot())
                }
            }
            is CarLocation -> CarSpot.update(context, message)
            is CarLog -> send(CarLogAck(message.id, CarLogStore.receive(context, message)))
            is CarLookAck -> CarLookSender.acked(message)
            is DriveSync -> DriveJournal.sync(context, message.drives)
            is DriveReport -> DriveJournal.update(context, message.drive)
            is TypeResult -> CarKeyboard.answered(message)
            is TypingAccess -> CarKeyboard.accessChanged(message)
            ObdOpen -> ObdRelay.open(context)
            ObdClose -> ObdRelay.close()
            is ObdTx -> ObdRelay.write(message.data)
            is FuelSync -> FuelJournal.sync(context, message.fills)
            is FuelReport -> FuelJournal.update(context, message.fill)
            is CarNotices -> main.post { CarNewsStore.update(context, message) }
            is SendText -> main.post { send(TextSent(message.id, TextSender.send(context, message.number, message.text))) }
            else -> Unit
        }
    }

    /** Notification actions run on the main thread, where the listener lives. */
    private fun onMain(key: String, action: ActionResult.Action, run: (PhoneNotificationListener) -> Boolean) {
        main.post {
            val ok = PhoneNotificationListener.instance?.let { runCatching { run(it) }.getOrDefault(false) } ?: false
            send(ActionResult(key, action, ok))
        }
    }

    private fun appVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"

    private fun deviceName(context: Context): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: Build.MODEL
}
