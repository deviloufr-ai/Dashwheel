package com.openauto.dash

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.openauto.dash.link.DISPLAY_BEACON_PORT
import com.openauto.dash.link.DISPLAY_BEACON_PREFIX
import com.openauto.dash.link.DISPLAY_PORT
import com.openauto.dash.link.DISPLAY_PROTOCOL
import com.openauto.dash.link.DISPLAY_SERVICE_TYPE
import com.openauto.dash.link.DisplayCommand
import com.openauto.dash.link.DisplayHello
import com.openauto.dash.link.DisplayStats
import com.openauto.dash.link.DisplayTime
import com.openauto.dash.link.VideoAck
import com.openauto.dash.link.VideoWindow
import com.openauto.dash.link.Hello
import com.openauto.dash.link.Incoming
import com.openauto.dash.link.LinkMessage
import com.openauto.dash.link.LinkSession
import com.openauto.dash.link.PairingOffer
import com.openauto.dash.link.PairingStorage
import com.openauto.dash.link.Ping
import com.openauto.dash.link.Pong
import com.openauto.dash.link.SecureChannel
import com.openauto.dash.link.StoredPairing
import com.openauto.dash.link.UnknownPairingException
import com.openauto.dash.link.VideoPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/*
 * The second screen's link, head unit side. The display (a Raspberry Pi wired
 * to a monitor, see tools/pi/README.md) is on the phone's hotspot like this
 * unit; the driver paired it by scanning the code on its screen with the
 * phone app, which handed the code over the phone link ([PhoneLink]). This
 * side finds the display on the hotspot, dials it with the same encrypted
 * channel as the phone's, and sends it what SecondScreenController decides:
 * H.264 video, or cluster readings.
 *
 * Finding it: the address it last answered on, then its DNS-SD announcement,
 * then, as a last resort, every address of the hotspot's network.
 */

/** A display the driver paired, from the code on its screen. */
data class PairedDisplay(val id: String, val secret: ByteArray, val name: String, val pairedAt: Long)

sealed interface DisplayLinkState {
    data object Unpaired : DisplayLinkState
    data object Searching : DisplayLinkState
    data class Connected(val display: DisplayHello, val address: String) : DisplayLinkState
}

object DisplayLink {
    private const val TAG = "DisplayLink"
    private const val PREFS = "display_link"
    private const val KEY_DISPLAYS = "displays"
    private const val KEY_LAST_ADDRESS = "last_address"
    private const val CONNECT_TIMEOUT_MS = 2_000
    private const val PROBE_TIMEOUT_MS = 350
    private const val PROBE_PARALLEL = 32
    /**
     * The whole hotspot is scanned at most this often while no display is linked: often
     * enough to find one that joined after the last scan, rare enough for an empty car.
     */
    private const val PROBE_EVERY_MS = 15_000L
    private const val HANDSHAKE_TIMEOUT_MS = 10_000
    /**
     * The display answers each ping, and sends its stats every 5 s on its own:
     * nothing heard for this long and the link is dead. Short, so "no signal"
     * and the redial come within seconds of a Wi-Fi drop, not three quarters of a minute.
     */
    private const val READ_TIMEOUT_MS = 15_000
    private const val PING_EVERY_MS = 5_000L
    /** A send that takes this long is stuck in a socket nobody reads any more: the link is closed rather than waited for. */
    private const val SEND_STALL_MS = 3_000L
    /** One dial at the known addresses: cheap, and the display is found soon after its Wi-Fi joins. */
    private const val RETRY_MS = 2_000L
    /** Messages waiting to go (a mode, the words, the readings): never behind the video, see [control]. */
    private const val CONTROL_QUEUE = 64
    /** Video frames queue here, a few at most ([VIDEO_BACKLOG]); the rest are dropped (see [sendVideo]). */
    private const val VIDEO_QUEUE = 8

    /** Frames waiting to go at most: a third of a second at the cluster's 15 frames a second. */
    private const val VIDEO_BACKLOG = 5

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    private val _state = MutableStateFlow<DisplayLinkState>(DisplayLinkState.Unpaired)
    val state: StateFlow<DisplayLinkState> = _state

    private val _displays = MutableStateFlow<List<PairedDisplay>>(emptyList())
    val displays: StateFlow<List<PairedDisplay>> = _displays

    /** The last try, as a short technical line for Settings (not translated, like the phone link's). */
    private val _lastAttempt = MutableStateFlow<String?>(null)
    val lastAttempt: StateFlow<String?> = _lastAttempt

    /** The display lost or joined the stream and needs a key frame. */
    private val _keyFrameRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val keyFrameRequests: SharedFlow<Unit> = _keyFrameRequests

    /** How the display keeps up with the video, every few seconds while it plays. */
    private val _stats = MutableStateFlow<DisplayStats?>(null)
    val stats: StateFlow<DisplayStats?> = _stats

    private val wake = MutableStateFlow(0)
    @Volatile private var session: LinkSession? = null

    private sealed class Out {
        class Message(val message: LinkMessage) : Out()
        class Raw(val bytes: ByteArray) : Out()
    }

    /**
     * Two queues, one sender, messages first: a mode change, the words or a
     * brightness level never wait behind video frames, and a video config
     * still reaches the display before the frames it describes (they are
     * queued after it, and frames only go when no message waits).
     */
    private val control = Channel<Pair<LinkSession, LinkMessage>>(CONTROL_QUEUE)
    private val video = Channel<Pair<LinkSession, ByteArray>>(VIDEO_QUEUE)

    /** The link a send has been stuck in since when (uptime), for the stall watchdog; null between sends. */
    @Volatile private var sending: Pair<LinkSession, Long>? = null

    /**
     * Video frames in the outbox, not yet sent. Over a phone's hotspot the
     * link can fall a little behind the stream: frames then waited in the
     * outbox and the socket by the second, and the screen ran five seconds
     * late. Past [VIDEO_BACKLOG] a frame is refused instead: the encoder
     * starts again from a key frame, and the display, seeing frames dropped,
     * has the bitrate lowered until the link keeps up.
     */
    private val videoQueued = java.util.concurrent.atomic.AtomicInteger()
    private val videoRefused = java.util.concurrent.atomic.AtomicInteger()

    /**
     * How far ahead of the display's confirmations ([VideoAck]) the video may
     * run: with a film on a phone hotspot, the phone and the radios held
     * seconds of it, out of reach of [videoQueued].
     */
    private val window = VideoWindow()

    /** Frames refused since the last call ([VIDEO_BACKLOG]): the link fell behind, as the display's dropped ones say it did. */
    fun takeRefusedFrames(): Int = videoRefused.getAndSet(0)

    /** Addresses the display announced itself on (DNS-SD, beacons). */
    private val announced = ConcurrentHashMap.newKeySet<String>()
    /** The address a display's beacon came from last. */
    @Volatile private var heardAt: String? = null
    private var lastProbeAt = 0L
    private var wifiLock: WifiManager.WifiLock? = null

    val connected: Boolean get() = _state.value is DisplayLinkState.Connected

    fun start(context: Context) {
        val app = context.applicationContext
        synchronized(this) {
            if (started) return
            started = true
        }
        _displays.value = readDisplays(app)
        refreshIdleState()
        app.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = wake.update { it + 1 }
                override fun onLost(network: Network) = wake.update { it + 1 }
            }
        )
        scope.launch {
            while (isActive) {
                // select takes the first clause that is ready: a message before a frame.
                val (link, out) = select<Pair<LinkSession, Out>> {
                    control.onReceive { (l, m) -> l to Out.Message(m) }
                    video.onReceive { (l, b) -> l to Out.Raw(b) }
                }
                sending = link to android.os.SystemClock.elapsedRealtime()
                val sent = try {
                    when (out) {
                        is Out.Message -> link.send(out.message)
                        is Out.Raw -> link.sendBinary(out.bytes)
                    }
                } catch (e: IOException) {
                    link.close()
                    false
                } finally {
                    sending = null
                    if (out is Out.Raw) videoQueued.decrementAndGet()
                }
                if (!sent && out is Out.Raw) _keyFrameRequests.tryEmit(Unit)
            }
        }
        discover(app)
        listenForBeacons()
        scope.launch { run(app) }
    }

    /**
     * Keeps the display whose code the phone scanned ([PairingOffer.toUri] of
     * kind DISPLAY). False for anything else.
     */
    fun pair(context: Context, uri: String): Boolean {
        val offer = PairingOffer.parse(uri)?.takeIf { it.kind == PairingOffer.Kind.DISPLAY } ?: return false
        val display = PairedDisplay(offer.id, offer.secret, offer.unitName, System.currentTimeMillis())
        updateDisplays(context) { list -> list.filter { it.id != offer.id } + display }
        note("paired with ${offer.unitName}")
        return true
    }

    fun forget(context: Context, id: String) {
        updateDisplays(context) { list -> list.filter { it.id != id } }
        session?.takeIf { it.pairingId == id }?.close()
        refreshIdleState()
    }

    /** Sends to the connected display; false when none is connected or the queue is full. */
    fun send(message: LinkMessage): Boolean {
        val current = session ?: return false
        val queued = control.trySend(current to message).isSuccess
        if (!queued) Log.w(TAG, "message dropped, the link is not keeping up: ${message::class.simpleName}")
        return queued
    }

    /** The head unit's clock and zone for the display ([DisplayTime]). */
    private fun timeMessage(): DisplayTime {
        val now = System.currentTimeMillis()
        val zone = java.util.TimeZone.getDefault()
        return DisplayTime(now, zone.getOffset(now) / 60_000, zone.id)
    }

    /**
     * Queues one encoded access unit. False when it could not be queued (no
     * display, or the link is behind): the caller should drop frames until the
     * next key frame, which the display can start from again.
     */
    fun sendVideo(packet: VideoPacket): Boolean {
        val current = session ?: return false
        if (packet.data.size > VideoPacket.MAX_DATA) return false
        if (videoQueued.get() >= VIDEO_BACKLOG || !window.mayQueue()) {
            videoRefused.incrementAndGet()
            return false
        }
        videoQueued.incrementAndGet()
        val queued = video.trySend(current to packet.encode()).isSuccess
        if (!queued) videoQueued.decrementAndGet() else window.queued(packet.ptsUs)
        return queued
    }

    private suspend fun run(context: Context) {
        var last = wake.value
        while (scope.isActive) {
            val displays = _displays.value
            if (displays.isNotEmpty()) {
                val tried = mutableSetOf<String>()
                for (address in quickAddresses(context)) {
                    if (!tried.add(address)) continue
                    if (dialAt(context, address, displays)) break
                }
                if (session == null && System.currentTimeMillis() - lastProbeAt > PROBE_EVERY_MS) {
                    lastProbeAt = System.currentTimeMillis()
                    for (address in probe(context)) {
                        if (!tried.add(address)) continue
                        if (dialAt(context, address, displays)) break
                    }
                }
            }
            refreshIdleState()
            withTimeoutOrNull(RETRY_MS) { wake.first { it != last } }
            last = wake.value
        }
    }

    /**
     * Where its beacon was last heard from (certainly current), then the address it last
     * answered on, then those it announced: a stale address costs a whole connect timeout.
     */
    private fun quickAddresses(context: Context): List<String> =
        listOfNotNull(heardAt, context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_ADDRESS, null)) +
            announced.toList()

    /** Tries each paired display at [address]; true once one linked (and that link has ended). */
    private fun dialAt(context: Context, address: String, displays: List<PairedDisplay>): Boolean {
        for (display in displays) {
            val what = "$address:$DISPLAY_PORT ${display.name}"
            val link = try {
                connect(address, display)
            } catch (e: UnknownPairingException) {
                // Another display, or this one paired afresh: try the next pairing.
                note("$what: display does not know this pairing")
                continue
            } catch (e: IOException) {
                note("$what: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
                return false
            } catch (e: Exception) {
                note("$what: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
                Log.w(TAG, "display attempt failed", e)
                return false
            }
            note("$what: linked")
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LAST_ADDRESS, address).apply()
            runSession(context, address, link, display)
            return true
        }
        return false
    }

    private fun connect(address: String, display: PairedDisplay): LinkSession {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(address, DISPLAY_PORT), CONNECT_TIMEOUT_MS)
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            socket.tcpNoDelay = true
            // Room for a key frame in flight, not for seconds of video waiting (see [videoQueued]).
            socket.sendBufferSize = 128 * 1024
            val link = SecureChannel.client(
                socket.getInputStream(), socket.getOutputStream(), display.id, display.secret,
                onClose = { runCatching { socket.close() } }
            )
            socket.soTimeout = READ_TIMEOUT_MS
            return link
        } catch (e: IOException) {
            runCatching { socket.close() }
            throw e
        }
    }

    private fun runSession(context: Context, address: String, link: LinkSession, display: PairedDisplay) {
        if (!link.sendOrClose(Hello(PhoneLink.unitName(context), BuildConfig.VERSION_NAME))) return
        window.reset()
        session = link
        holdWifi(context, true)
        // The time first, so the display's clock is right from its first picture, then with every ping.
        send(timeMessage())
        val pinger = scope.launch {
            while (isActive) {
                delay(PING_EVERY_MS)
                send(Ping)
                send(timeMessage())
            }
        }
        // A socket nobody reads any more takes a send with it: closed, so the writer and the reader both let go.
        val watchdog = scope.launch {
            while (isActive) {
                delay(1_000)
                val (stuckIn, since) = sending ?: continue
                if (stuckIn === link && android.os.SystemClock.elapsedRealtime() - since > SEND_STALL_MS) {
                    note("display link stalled: a send took over ${SEND_STALL_MS / 1000} s")
                    link.close()
                    break
                }
            }
        }
        try {
            while (true) {
                val frame = link.receiveAny() as? Incoming.Message ?: continue
                when (val message = frame.message) {
                    is DisplayHello -> {
                        if (message.name != display.name) {
                            updateDisplays(context) { list -> list.map { if (it.id == display.id) it.copy(name = message.name) else it } }
                        }
                        val versions = "display program ${message.appVersion}, protocol ${message.protocol} (this app: $DISPLAY_PROTOCOL)"
                        note(versions)
                        DebugLog.note(
                            context,
                            "second screen ${message.name} linked: $versions" + when (SecondScreenRules.versionGap(message.protocol)) {
                                SecondScreenVersionGap.DISPLAY_OLDER -> ", the display's card is older than this app"
                                SecondScreenVersionGap.DISPLAY_NEWER -> ", the display's card is newer than this app"
                                SecondScreenVersionGap.NONE -> ""
                            }
                        )
                        _state.value = DisplayLinkState.Connected(message, address)
                    }
                    is DisplayCommand -> if (message.action == DisplayCommand.Action.KEYFRAME_PLEASE) _keyFrameRequests.tryEmit(Unit)
                    is DisplayStats -> _stats.value = message
                    is VideoAck -> window.acked(message.ptsUs)
                    Ping -> send(Pong)
                    else -> Unit
                }
            }
        } catch (e: IOException) {
            note("display link ended: ${e.message.orEmpty()}")
        } finally {
            pinger.cancel()
            watchdog.cancel()
            link.close()
            if (session === link) session = null
            _stats.value = null
            holdWifi(context, false)
            _state.value = DisplayLinkState.Searching
        }
    }

    /**
     * Every host of the hotspot's network with the display's port open, and
     * of a wired network too (a USB Ethernet adapter straight to the Pi, which
     * hands this unit its address). The phone (the gateway) and this unit are skipped.
     */
    private suspend fun probe(context: Context): List<String> {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        @Suppress("DEPRECATION")
        val local = (listOfNotNull(cm.activeNetwork) + cm.allNetworks).distinct().filter { network ->
            cm.getNetworkCapabilities(network)?.let {
                it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            } == true
        }
        val hosts = local.flatMap { network ->
            val props = cm.getLinkProperties(network) ?: return@flatMap emptyList()
            val own = props.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return@flatMap emptyList()
            val gateway = props.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway
            SecondScreenRules.hostsToProbe(toInt(own.address), own.prefixLength, setOfNotNull(gateway?.let { toInt(it) }))
        }.distinct().ifEmpty {
            // This device hosts the hotspot itself: Android lists no Wi-Fi network for it.
            val hosted = hostedHotspot() ?: return emptyList()
            SecondScreenRules.hostsToProbe(toInt(hosted.address), hosted.networkPrefixLength.toInt())
        }
        note("looking for the display on ${hosts.size} addresses")
        val gate = Semaphore(PROBE_PARALLEL)
        return kotlinx.coroutines.coroutineScope {
            hosts.map { host ->
                async {
                    gate.withPermit {
                        val address = toAddress(host)
                        if (portOpen(address)) address.hostAddress else null
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    /** The address this device has on the hotspot it hosts, if it hosts one. */
    private fun hostedHotspot(): InterfaceAddress? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && HOTSPOT_INTERFACE.matches(it.name) }
            .flatMap { it.interfaceAddresses }
            .firstOrNull { it.address is Inet4Address && it.address.isSiteLocalAddress }
    }.getOrNull()

    private val HOTSPOT_INTERFACE = Regex("(swlan|ap|softap|wlan)\\d+")

    private fun portOpen(address: InetAddress): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(address, DISPLAY_PORT), PROBE_TIMEOUT_MS) }
        true
    } catch (e: IOException) {
        false
    }

    private fun toInt(address: InetAddress): Int = ByteBuffer.wrap(address.address).int
    private fun toAddress(value: Int): InetAddress = InetAddress.getByAddress(ByteBuffer.allocate(4).putInt(value).array())

    /**
     * Listens for the display's DNS-SD announcement for as long as the app
     * runs. Android 10 resolves one service at a time, so they are resolved in turn.
     */
    private fun discover(context: Context) {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return
        val toResolve = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        scope.launch {
            for (info in toResolve) {
                val done = Channel<Unit>(1)
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        done.trySend(Unit)
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        (serviceInfo.host as? Inet4Address)?.hostAddress?.let { address ->
                            if (announced.add(address)) wake.update { it + 1 }
                        }
                        done.trySend(Unit)
                    }
                })
                withTimeoutOrNull(5_000) { done.receive() }
            }
        }
        runCatching {
            nsd.discoverServices(DISPLAY_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, object : NsdManager.DiscoveryListener {
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    toResolve.trySend(serviceInfo)
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
                override fun onDiscoveryStarted(serviceType: String) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.w(TAG, "DNS-SD discovery failed: $errorCode")
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            })
        }.onFailure { Log.w(TAG, "DNS-SD unavailable", it) }
    }

    /**
     * A paired display's call-out (see DISPLAY_BEACON_PORT): dialled within a
     * second of it joining the hotspot, without waiting for DNS-SD or a scan.
     */
    private fun listenForBeacons() {
        scope.launch(Dispatchers.IO) {
            val socket = runCatching {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(DISPLAY_BEACON_PORT))
                }
            }.onFailure { Log.w(TAG, "no display beacons", it) }.getOrNull() ?: return@launch
            val buffer = ByteArray(128)
            socket.use {
                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (e: IOException) {
                        break
                    }
                    val text = String(packet.data, 0, packet.length, Charsets.US_ASCII)
                    if (!text.startsWith(DISPLAY_BEACON_PREFIX)) continue
                    val id = text.removePrefix(DISPLAY_BEACON_PREFIX)
                    if (_displays.value.none { it.id == id }) continue
                    val address = (packet.address as? Inet4Address)?.hostAddress ?: continue
                    announced.add(address)
                    if (heardAt != address) {
                        heardAt = address
                        wake.update { it + 1 }
                    }
                }
            }
        }
    }

    /** Wi-Fi power saving holds packets back for up to a beacon interval: not while streaming. */
    @Synchronized
    private fun holdWifi(context: Context, hold: Boolean) {
        if (hold) {
            if (wifiLock == null) {
                wifiLock = runCatching {
                    context.getSystemService(WifiManager::class.java)
                        ?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "dashwheel:display")
                        ?.apply { setReferenceCounted(false); acquire() }
                }.getOrNull()
            }
        } else {
            runCatching { wifiLock?.release() }
            wifiLock = null
        }
    }

    private fun note(line: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())
        _lastAttempt.value = "$time  ${line.trim().take(140)}"
        Log.i(TAG, line)
    }

    private fun refreshIdleState() {
        if (session != null && _state.value is DisplayLinkState.Connected) return
        _state.value = if (_displays.value.isEmpty()) DisplayLinkState.Unpaired else DisplayLinkState.Searching
    }

    private fun readDisplays(context: Context): List<PairedDisplay> =
        PairingStorage.decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DISPLAYS, null)).map {
            PairedDisplay(it.id, it.secret, it.name, it.pairedAt)
        }

    @Synchronized
    private fun updateDisplays(context: Context, change: (List<PairedDisplay>) -> List<PairedDisplay>) {
        val before = _displays.value
        val displays = change(before)
        if (displays == before) return
        val stored = displays.map { StoredPairing(it.id, it.secret, it.name, it.pairedAt) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_DISPLAYS, PairingStorage.encode(stored)).apply()
        _displays.value = displays
        refreshIdleState()
        wake.update { it + 1 }
    }
}
