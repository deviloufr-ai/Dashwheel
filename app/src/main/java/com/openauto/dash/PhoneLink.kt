package com.openauto.dash

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.openauto.dash.link.ActionResult
import com.openauto.dash.link.CallCommand
import com.openauto.dash.link.CallState
import com.openauto.dash.link.Dismiss
import com.openauto.dash.link.Hello
import com.openauto.dash.link.LINK_PORT
import com.openauto.dash.link.LinkMessage
import com.openauto.dash.link.LinkSession
import com.openauto.dash.link.MarkRead
import com.openauto.dash.link.NotificationPosted
import com.openauto.dash.link.NotificationRemoved
import com.openauto.dash.link.NotificationSync
import com.openauto.dash.link.PairingOffer
import com.openauto.dash.link.PairingStorage
import com.openauto.dash.link.Ping
import com.openauto.dash.link.Pong
import com.openauto.dash.link.Reply
import com.openauto.dash.link.SecureChannel
import com.openauto.dash.link.StoredPairing
import com.openauto.dash.link.UnknownPairingException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

/*
 * The phone link, head unit side. The driver's phone shares its connection
 * over Wi-Fi; the Dashwheel Companion app on it listens on the hotspot's
 * gateway address. This side finds that gateway, dials it, proves it holds
 * the secret from the pairing QR code, then shows the phone's notifications
 * in the Notifications card, sends back replies, and shows its calls
 * ([PhoneCallOverlay]) with answer / hang-up buttons.
 */

/** A phone the driver paired by scanning the QR code with the companion app. */
data class PairedPhone(
    val id: String,
    val secret: ByteArray,
    /** From the phone's [Hello]; empty until it first connected. */
    val name: String,
    val pairedAt: Long,
    /** The phone said it no longer knows this pairing: it must be paired again. */
    val forgotten: Boolean = false,
    /** Occasions a hotspot refused this pairing since the phone last connected (see [PhoneLink]). */
    val refusals: Int = 0,
    val lastRefusalAt: Long = 0
)

/** The phone's call as the head unit shows it. */
data class PhoneCall(
    val phase: CallState.Phase,
    val number: String?,
    val name: String?,
    val photo: Bitmap?,
    /** [SystemClock.elapsedRealtime] when it was answered, on this head unit's clock. */
    val answeredAt: Long,
    /** False: the companion may not answer / hang up, so the call is only shown. */
    val canControl: Boolean,
    /** The app the call is in ("WhatsApp"…); null for a phone call. */
    val app: String? = null
)

sealed interface PhoneLinkState {
    /** No phone paired. */
    data object Unpaired : PhoneLinkState
    /** Paired, waiting for the phone's hotspot and companion app. */
    data object Searching : PhoneLinkState
    data class Connected(val phoneName: String) : PhoneLinkState
}

object PhoneLink {
    private const val TAG = "PhoneLink"
    private const val PREFS = "phone_link"
    private const val KEY_PHONES = "phones"
    private const val CONNECT_TIMEOUT_MS = 3_000
    private const val HANDSHAKE_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 45_000
    private const val PING_EVERY_MS = 15_000L
    /** How often to look for the phone: quickly while a pairing code is on screen. */
    private const val RETRY_MS = 10_000L
    private const val RETRY_PAIRING_MS = 2_000L
    /**
     * Any other driver's companion refuses a pairing it never saw, so one
     * refusal proves nothing: only this many, on occasions this far apart, with
     * none of this unit's pairings known there, mean the phone removed the car.
     */
    private const val REFUSALS_TO_FORGET = 3
    private const val REFUSAL_OCCASION_MS = 30 * 60_000L
    private const val OUTBOX_SIZE = 64

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    private val _state = MutableStateFlow<PhoneLinkState>(PhoneLinkState.Unpaired)
    val state: StateFlow<PhoneLinkState> = _state

    private val _phones = MutableStateFlow<List<PairedPhone>>(emptyList())
    val phones: StateFlow<List<PairedPhone>> = _phones

    /** The pairing whose QR code is on screen, until a phone uses it. */
    private val _pending = MutableStateFlow<PairingOffer?>(null)
    val pending: StateFlow<PairingOffer?> = _pending

    /**
     * The last try, as a short technical line for the pairing dialog and
     * Settings: the address dialled and what came back. Not translated: it is
     * read out when the link does not come up, to tell where it stops.
     */
    private val _lastAttempt = MutableStateFlow<String?>(null)
    val lastAttempt: StateFlow<String?> = _lastAttempt

    /** How the phone carried out a reply / mark-as-read / dismiss. */
    private val _results = MutableSharedFlow<ActionResult>(extraBufferCapacity = 8)
    val results: SharedFlow<ActionResult> = _results

    /** The phone's call; null when there is none (or no phone). */
    private val _call = MutableStateFlow<PhoneCall?>(null)
    val call: StateFlow<PhoneCall?> = _call

    /** Bumped when the network changes or the phones change, to retry right away. */
    private val wake = MutableStateFlow(0)
    @Volatile private var session: LinkSession? = null
    // Everything sent goes through one coroutine, so it reaches the phone in the order it was sent.
    private val outbox = Channel<Pair<LinkSession, LinkMessage>>(OUTBOX_SIZE)

    /** A link for the pending pairing, opened while another phone's link was up. */
    private class Handover(val gateway: InetAddress, val link: LinkSession, val phone: PairedPhone)
    private val handover = AtomicReference<Handover?>(null)

    private enum class Attempt { LINKED, REFUSED, UNREACHABLE }

    fun start(context: Context) {
        val app = context.applicationContext
        synchronized(this) {
            if (started) return
            started = true
        }
        _phones.value = readPhones(app)
        refreshIdleState()
        app.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = wake.update { it + 1 }
                override fun onLost(network: Network) = wake.update { it + 1 }
            }
        )
        scope.launch { for ((link, message) in outbox) link.sendOrClose(message) }
        scope.launch { run(app) }
    }

    /** A fresh pairing code to show; replaces any previous one. */
    fun beginPairing(context: Context): PairingOffer {
        val offer = PairingOffer.create(unitName(context))
        _pending.value = offer
        wake.update { it + 1 }
        return offer
    }

    fun cancelPairing() {
        _pending.value = null
    }

    fun forget(context: Context, id: String) {
        updatePhones(context) { phones -> phones.filter { it.id != id } }
        session?.takeIf { it.pairingId == id }?.close()
        refreshIdleState()
    }

    /** Sends to the connected phone; false when no phone is connected. */
    fun send(message: LinkMessage): Boolean {
        val current = session ?: return false
        return outbox.trySend(current to message).isSuccess
    }

    fun reply(key: String, text: String) = send(Reply(NotificationFeed.phoneKey(key), text))
    fun markRead(key: String) = send(MarkRead(NotificationFeed.phoneKey(key)))
    fun dismiss(key: String) = send(Dismiss(NotificationFeed.phoneKey(key)))
    fun callCommand(action: CallCommand.Action) = send(CallCommand(action))

    private suspend fun run(context: Context) {
        var last = wake.value
        while (scope.isActive) {
            handover.getAndSet(null)?.let { runSession(context, it.gateway, it.link, it.phone, isPending = true) }
            val pending = _pending.value
            val candidates = listOfNotNull(pending?.let { PairedPhone(it.id, it.secret, "", 0) }) +
                _phones.value.filter { !it.forgotten }
            val gateway = if (candidates.isEmpty()) null else hotspotGateway(context)
            if (gateway != null) dial(context, gateway, candidates, pending?.id)
            else if (candidates.isNotEmpty()) note("no Wi-Fi gateway: not on a phone hotspot")
            refreshIdleState()
            val wait = if (_pending.value != null) RETRY_PAIRING_MS else RETRY_MS
            withTimeoutOrNull(wait) { wake.first { it != last } }
            last = wake.value
        }
    }

    /** Tries each pairing on the phone at [gateway] until one links (and that link has ended). */
    private fun dial(context: Context, gateway: InetAddress, candidates: List<PairedPhone>, pendingId: String?) {
        val refused = mutableListOf<String>()
        for (phone in candidates) {
            val isPending = phone.id == pendingId
            when (tryPhone(context, gateway, phone, isPending)) {
                Attempt.LINKED -> return
                // A pairing code the phone hasn't accepted yet: the paired phones still get their turn.
                Attempt.REFUSED -> if (!isPending) refused += phone.id
                Attempt.UNREACHABLE -> Unit
            }
        }
        if (refused.isNotEmpty()) noteRefusals(context, refused)
    }

    /** One attempt with one pairing. */
    private fun tryPhone(context: Context, gateway: InetAddress, phone: PairedPhone, isPending: Boolean): Attempt {
        val what = "${gateway.hostAddress}:$LINK_PORT ${if (isPending) "new code" else "paired"}"
        val link = try {
            connect(gateway, phone)
        } catch (e: UnknownPairingException) {
            note("$what: phone does not know this code")
            return Attempt.REFUSED
        } catch (e: IOException) {
            note("$what: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
            return Attempt.UNREACHABLE
        } catch (e: Exception) {
            // A crypto provider missing an algorithm on this unit, say: say so rather than retry blind.
            note("$what: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
            Log.w(TAG, "link attempt failed", e)
            return Attempt.UNREACHABLE
        }
        note("$what: linked")
        runSession(context, gateway, link, phone, isPending)
        return Attempt.LINKED
    }

    private fun note(line: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())
        _lastAttempt.value = "$time  ${line.trim().take(140)}"
        Log.i(TAG, line)
    }

    private fun connect(gateway: InetAddress, phone: PairedPhone): LinkSession {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(gateway, LINK_PORT), CONNECT_TIMEOUT_MS)
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            socket.tcpNoDelay = true
            val link = SecureChannel.client(
                socket.getInputStream(), socket.getOutputStream(), phone.id, phone.secret,
                onClose = { runCatching { socket.close() } }
            )
            socket.soTimeout = READ_TIMEOUT_MS
            return link
        } catch (e: IOException) {
            runCatching { socket.close() }
            throw e
        }
    }

    /** Runs an open link until it ends. */
    private fun runSession(context: Context, gateway: InetAddress, link: LinkSession, phone: PairedPhone, isPending: Boolean) {
        if (!link.sendOrClose(Hello(unitName(context), BuildConfig.VERSION_NAME))) return
        session = link
        val pinger = scope.launch {
            while (isActive) {
                delay(PING_EVERY_MS)
                send(Ping)
            }
        }
        // While a pairing code is on screen, keep asking the phone whether it now knows it.
        val prober = if (isPending) null else scope.launch { probePending(gateway, link) }
        // Where the car stops, for the phone's "where's my car".
        val whereabouts = scope.launch { CarWhereabouts.report(context) { send(it) } }
        // The drives it logged, and the one under way, for the phone's drive journal.
        val drives = scope.launch { DriveLog.report { send(it) } }
        try {
            while (true) {
                val message = link.receive() ?: continue
                handle(context, phone, isPending, message)
            }
        } catch (e: IOException) {
            Log.i(TAG, "link ended: ${e.message}")
        } finally {
            pinger.cancel()
            prober?.cancel()
            whereabouts.cancel()
            drives.cancel()
            link.close()
            if (session === link) session = null
            NotificationFeed.phoneClear()
            _call.value = null
        }
    }

    /**
     * Once the phone accepts the pending pairing, its link takes over from
     * [current] (the phone keeps one link at a time anyway).
     */
    private suspend fun probePending(gateway: InetAddress, current: LinkSession) {
        while (true) {
            val offer = _pending.filterNotNull().first()
            val phone = PairedPhone(offer.id, offer.secret, "", 0)
            val link = try {
                connect(gateway, phone)
            } catch (e: IOException) {
                null
            }
            if (link != null) {
                if (!currentCoroutineContext().isActive) {
                    link.close()
                    return
                }
                handover.getAndSet(Handover(gateway, link, phone))?.link?.close()
                current.close()
                wake.update { it + 1 }
                return
            }
            delay(RETRY_PAIRING_MS)
        }
    }

    private fun handle(context: Context, phone: PairedPhone, isPending: Boolean, message: LinkMessage) {
        when (message) {
            is Hello -> {
                if (isPending) {
                    // The QR code was used: this phone is now paired.
                    _pending.value = null
                    val paired = PairedPhone(phone.id, phone.secret, message.deviceName, System.currentTimeMillis())
                    updatePhones(context) { phones -> phones.filter { it.id != phone.id } + paired }
                } else {
                    // It answered, so earlier refusals were some other phone's.
                    updatePhones(context) { phones ->
                        phones.map {
                            if (it.id == phone.id && (it.name != message.deviceName || it.refusals != 0)) {
                                it.copy(name = message.deviceName, refusals = 0, lastRefusalAt = 0)
                            } else {
                                it
                            }
                        }
                    }
                }
                _state.value = PhoneLinkState.Connected(message.deviceName)
            }
            Ping -> send(Pong)
            is NotificationSync -> NotificationFeed.phoneSync(message.notifications)
            is NotificationPosted -> NotificationFeed.phonePosted(message.notification)
            is NotificationRemoved -> NotificationFeed.phoneRemoved(message.key)
            is ActionResult -> _results.tryEmit(message)
            is CallState -> _call.value = toPhoneCall(context, message)
            else -> Unit
        }
    }

    private fun toPhoneCall(context: Context, state: CallState): PhoneCall? {
        if (state.phase == CallState.Phase.IDLE) return null
        // The same app on this head unit (a WhatsApp linked to the phone's) rings here
        // by itself, with its own screen: no card over it.
        if (state.packageName?.let { isInstalled(context, it) } == true) return null
        val before = _call.value
        val sameCaller = before != null && before.number == state.number && before.name == state.name && before.app == state.app
        val photo = if (before != null && state.photoPng != null && sameCaller) before.photo
        else state.photoPng?.let { png ->
            runCatching {
                val bytes = Base64.getDecoder().decode(png)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        }
        return PhoneCall(
            phase = state.phase,
            number = state.number,
            name = state.name,
            photo = photo,
            answeredAt = SystemClock.elapsedRealtime() - state.activeForMs,
            canControl = state.canControl,
            app = state.app
        )
    }

    private fun isInstalled(context: Context, packageName: String): Boolean =
        runCatching { context.packageManager.getApplicationInfo(packageName, 0).enabled }.getOrDefault(false)

    /** Pairings every one of which the hotspot's phone refused; counted once per occasion. */
    private fun noteRefusals(context: Context, ids: List<String>) {
        val now = System.currentTimeMillis()
        updatePhones(context) { phones ->
            phones.map {
                if (it.id !in ids || now - it.lastRefusalAt in 0 until REFUSAL_OCCASION_MS) {
                    it
                } else {
                    val refusals = it.refusals + 1
                    it.copy(refusals = refusals, lastRefusalAt = now, forgotten = refusals >= REFUSALS_TO_FORGET)
                }
            }
        }
    }

    private fun refreshIdleState() {
        if (session != null && _state.value is PhoneLinkState.Connected) return
        _state.value = if (_phones.value.isEmpty() && _pending.value == null) PhoneLinkState.Unpaired else PhoneLinkState.Searching
    }

    /**
     * The phone's address when this head unit is on its hotspot: the Wi-Fi
     * network's default gateway. Android 11+ picks a random hotspot subnet,
     * so it is read from the network rather than assumed to be 192.168.43.1.
     */
    private fun hotspotGateway(context: Context): InetAddress? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION") // allNetworks: the Wi-Fi network may not be the default one.
        val networks = listOfNotNull(cm.activeNetwork) + cm.allNetworks
        return networks.distinct().firstNotNullOfOrNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@firstNotNullOfOrNull null
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@firstNotNullOfOrNull null
            cm.getLinkProperties(network)?.routes
                ?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
                ?.gateway
        }
    }

    private fun unitName(context: Context): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: Build.MODEL?.takeIf { it.isNotBlank() }
            ?: "Dashwheel"

    private fun readPhones(context: Context): List<PairedPhone> =
        PairingStorage.decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PHONES, null)).map {
            PairedPhone(it.id, it.secret, it.name, it.pairedAt, it.forgotten, it.refusals, it.lastRefusalAt)
        }

    /** Changes the paired phones and saves them, as one step: the link, the UI and pairing all change them. */
    @Synchronized
    private fun updatePhones(context: Context, change: (List<PairedPhone>) -> List<PairedPhone>) {
        val before = _phones.value
        val phones = change(before)
        if (phones == before) return
        val stored = phones.map { StoredPairing(it.id, it.secret, it.name, it.pairedAt, it.forgotten, it.refusals, it.lastRefusalAt) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PHONES, PairingStorage.encode(stored)).apply()
        _phones.value = phones
        wake.update { it + 1 }
    }
}
