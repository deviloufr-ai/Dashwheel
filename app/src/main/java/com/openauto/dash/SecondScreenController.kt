package com.openauto.dash

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import com.openauto.dash.link.DisplayHello
import com.openauto.dash.link.DisplayMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What the second screen is being sent, for Settings and the tile. */
data class SecondScreenStatus(
    val output: SecondScreenOutput = SecondScreenOutput.NONE,
    val block: SecondScreenBlock = SecondScreenBlock.NONE,
    /** The display's own measure of the video it receives. */
    val kbps: Int = 0,
    /** How the display's board is doing, from its reports. */
    val health: DisplayHealth = DisplayHealth.UNKNOWN,
    val tempC: Int? = null,
    /** How far the picture was lightened because the display could not decode it in time (0: not at all). */
    val relief: Int = 0
)

/**
 * Puts on the second screen what Settings asks for, as far as the display,
 * the encoder and the road allow ([SecondScreenRules.output]):
 *
 * - the cluster, drawn here in a Presentation on a [StreamDisplay] and streamed;
 * - an app, its stack moved onto that display (and back when it is done);
 * - or, when video isn't possible, the readings for the display to draw
 *   ([ClusterFeed]).
 *
 * Process-wide like DisplayLink, so the second screen carries on while another
 * app covers the dashboard.
 */
internal object SecondScreenController {
    private const val TAG = "SecondScreen"
    /** A display gone this long (Wi-Fi hiccup, the Pi rebooting) before its stream is taken down. */
    private const val LINK_GRACE_MS = 30_000L
    /** Time for an app launched on the screen to show its window before it is moved. */
    private const val APP_LAUNCH_WAIT_MS = 1_500L
    private const val APP_LAUNCH_TRIES = 6
    private const val CLUSTER_FPS = 15

    /** Cluster slots whose picture moves all the time. */
    private val LIVE_KINDS = setOf(BuiltinKind.NAVMAP, BuiltinKind.MAPS_INSIDE)
    private const val APP_FPS = 30

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false
    private lateinit var appContext: Context

    private val _status = MutableStateFlow(SecondScreenStatus())
    val status: StateFlow<SecondScreenStatus> = _status

    /** Apps shown on the second screen: the dashboard's tiles leave their windows alone (see PipAnchor.isLent). */
    private val _heldPackages = MutableStateFlow<Set<String>>(emptySet())
    val heldPackages: StateFlow<Set<String>> = _heldPackages

    /** The cluster's page, and the pages it cycles through. */
    private val _page = MutableStateFlow(ClusterPage.DRIVE)
    val page: StateFlow<ClusterPage> = _page
    private val _pages = MutableStateFlow(ClusterPage.entries.toList())

    val keys = ClusterKeyTracker()

    /** The accessibility service sees every key first: the dashboard then leaves them to it, or each would count twice. */
    @Volatile var serviceFiltersKeys = false

    private val encoderFailed = MutableStateFlow(false)
    /** The picture lightened for a display that can't keep up ([SecondScreenRules.relief]); back to 0 with a new link. */
    private val relief = MutableStateFlow(0)
    private val moving = MutableStateFlow(false)
    /** Whether the car moves, by the second screen's own reckoning: video apps' copies wait for it to stop. */
    val isMoving: StateFlow<Boolean> = moving
    private val applying = Mutex()

    private var stream: StreamDisplay? = null
    /** What the encoder is set to now: the configured rate, or less while the Wi-Fi struggles, and the rate that last failed. */
    private var bitrate = SecondScreenRules.BitrateState(0)
    private var streamShape: List<Int>? = null
    private var presentation: ClusterPresentation? = null
    private var appShown: String? = null
    private var feed: Job? = null
    private var holdsFeeds = false
    private var teardown: Job? = null

    private data class Wanted(
        val output: SecondScreenOutput,
        val block: SecondScreenBlock,
        val config: SecondScreenConfig,
        val display: DisplayHello?,
        val relief: Int = 0
    )

    fun start(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        SecondScreenStore.load(appContext)
        scope.launch {
            SecondScreenStore.config.collect { c ->
                _pages.value = c.pages
                if (_page.value !in c.pages) _page.value = c.pages.first()
            }
        }
        scope.launch { followSpeed() }
        SecondScreenBrightness.start(scope, appContext)
        SecondScreenWords.start(scope, appContext)
        scope.launch {
            combine(SecondScreenStore.config, DisplayLink.state, moving, encoderFailed, relief) { config, link, isMoving, failed, lighter ->
                val display = (link as? DisplayLinkState.Connected)?.display
                val canStream = display != null && com.openauto.dash.link.CODEC_H264 in display.decoders && !failed
                val (output, block) = SecondScreenRules.output(
                    config, connected = display != null, canStream = canStream, moving = isMoving,
                    appIsVideo = config.appPackage?.let(::isVideoApp) == true,
                    canMoveApps = SecondScreenRules.appsMovable(android.os.Build.VERSION.SDK_INT)
                )
                Wanted(output, block, config, display, lighter)
            }
                .distinctUntilChanged()
                .conflate()
                .collect { wanted -> applying.withLock { apply(wanted) } }
        }
        // An encoder that failed gets another chance when the display comes back or the picture asked for changes.
        scope.launch {
            DisplayLink.state.filterIsInstance<DisplayLinkState.Connected>().collect {
                encoderFailed.value = false
                // A new link may be a different Wi-Fi, or a display with a better supply: the old
                // ceiling and the lighter picture no longer say anything.
                bitrate = bitrate.copy(ceilingKbps = null, cleanSinceMs = null, settleUntilMs = SystemClock.elapsedRealtime() + SecondScreenRules.BITRATE_SETTLE_MS)
                relief.value = 0
            }
        }
        scope.launch {
            SecondScreenStore.config.map { listOf(it.video, it.maxHeight, it.bitrateKbps, it.mode) }.distinctUntilChanged().collect {
                encoderFailed.value = false
                relief.value = 0
            }
        }
        scope.launch { DisplayLink.keyFrameRequests.collect { stream?.requestKeyFrame() } }
        scope.launch {
            var lastHealth = DisplayHealth.UNKNOWN
            DisplayLink.stats.collect { report ->
                val health = SecondScreenRules.health(report)
                _status.update { it.copy(kbps = report?.kbps ?: 0, health = health, tempC = report?.tempC) }
                if (health != lastHealth) {
                    lastHealth = health
                    if (health != DisplayHealth.UNKNOWN && health != DisplayHealth.OK) {
                        DebugLog.note(appContext, "second screen board: $health" + (report?.tempC?.let { ", $it °C" } ?: "") + ", flags 0x${Integer.toHexString(report?.throttled ?: 0)}")
                    }
                }
                val s = stream ?: return@collect
                if (report == null) return@collect
                val now = SystemClock.elapsedRealtime()
                // The display itself too slow for the stream: fewer frames and pixels, not fewer bits.
                val lighter = SecondScreenRules.relief(relief.value, report, settled = now >= bitrate.settleUntilMs)
                if (lighter != relief.value) {
                    Log.i(TAG, "display behind (${report.droppedLate} of ${report.framesShown + report.framesDropped} frames late): lighter picture, step $lighter")
                    DebugLog.note(appContext, "second screen could not keep up (${report.droppedLate} frames late, $health): lighter picture, step $lighter")
                    relief.value = lighter
                }
                // Too much for the Wi-Fi: lighter frames rather than frames lost. Only the frames the
                // link lost count: those the display had no room for are its own (above), plus the ones
                // refused on this side because the link was behind.
                val dropped = SecondScreenRules.networkDrops(report, DisplayLink.takeRefusedFrames())
                val next = SecondScreenRules.adaptBitrate(
                    bitrate, SecondScreenStore.config.value.bitrateKbps, report.framesShown, dropped, now
                )
                if (next.kbps != bitrate.kbps || next.ceilingKbps != bitrate.ceilingKbps) {
                    Log.i(TAG, "bitrate ${bitrate.kbps} -> ${next.kbps} kbit/s, ceiling ${next.ceilingKbps} ($dropped of ${report.framesShown + dropped} frames dropped)")
                }
                if (next.kbps != bitrate.kbps) s.setBitrate(next.kbps)
                bitrate = next
            }
        }
    }

    /** True while the second screen shows the cluster, whose page [turnPage] changes. */
    val showsCluster: Boolean
        get() = started && _status.value.output.let { it == SecondScreenOutput.VIDEO_CLUSTER || it == SecondScreenOutput.DATA }

    /** Next (or, with a negative [step], previous) cluster page; from the tile, Settings or a key. */
    fun turnPage(step: Int = 1) {
        val next = SecondScreenRules.nextPage(_pages.value, _page.value, step)
        _page.value = next
    }

    /**
     * [onKey] ahead of the steering wheel's button map, which keeps every key
     * while it is learning them (SteeringWheelStore.mapping).
     */
    fun onWheelKey(event: KeyEvent): Boolean =
        !SteeringWheelStore.mapping.value && runCatching { onKey(event) }.getOrDefault(false)

    /** A key from the dashboard or the accessibility service; true when it was the cluster's. */
    fun onKey(event: KeyEvent): Boolean {
        if (!started) return false
        val showing = showsCluster
        val decision = keys.onKey(event.keyCode, event.action == KeyEvent.ACTION_DOWN, event.repeatCount, SecondScreenStore.config.value, showing)
        when (decision.action) {
            ClusterKeyAction.NEXT_PAGE -> turnPage(1)
            ClusterKeyAction.PREVIOUS_PAGE -> turnPage(-1)
            ClusterKeyAction.SKIP_NEXT -> CarMediaController.shared(appContext).next()
            ClusterKeyAction.SKIP_PREVIOUS -> CarMediaController.shared(appContext).previous()
            null -> Unit
        }
        return decision.consume
    }

    private suspend fun apply(wanted: Wanted) {
        _status.update { it.copy(output = wanted.output, block = wanted.block, relief = wanted.relief) }
        val connected = wanted.display != null
        if (!connected && wanted.config.mode != SecondScreenMode.OFF && (stream != null || appShown != null)) {
            // Gone for now: keep the app where it is for a while, it will likely be back.
            if (teardown?.isActive != true) {
                teardown = scope.launch {
                    delay(LINK_GRACE_MS)
                    applying.withLock {
                        tearDown()
                        holdFeeds(false)
                    }
                }
            }
            stopFeed()
            return
        }
        teardown?.cancel()
        teardown = null

        val display = wanted.display
        val videoOut = wanted.output == SecondScreenOutput.VIDEO_CLUSTER || wanted.output == SecondScreenOutput.VIDEO_APP
        if (!videoOut || display == null) {
            tearDown()
        } else {
            val maxHeight = SecondScreenRules.reliefHeight(wanted.config.maxHeight, wanted.relief)
            val (w, h) = SecondScreenRules.streamSize(display.width, display.height, maxHeight, display.maxWidth, display.maxHeight)
            // A page with a live map or an app's copy moves like video: smoother, and each frame waits less.
            // Decided for all the pages at once, so turning one never restarts the stream.
            val live = wanted.config.pages.any { p -> wanted.config.layoutFor(p).slots.any { it.kind in LIVE_KINDS } }
            val fps = SecondScreenRules.reliefFps(if (wanted.output == SecondScreenOutput.VIDEO_APP || live) APP_FPS else CLUSTER_FPS, wanted.relief)
            val shape = listOf(w, h, fps, wanted.config.bitrateKbps)
            if (streamShape != shape) {
                tearDown()
                stream = makeStream(w, h, fps, wanted.config.bitrateKbps) ?: return
                streamShape = shape
                bitrate = bitrate.restart(wanted.config.bitrateKbps, SystemClock.elapsedRealtime())
                if (bitrate.kbps != wanted.config.bitrateKbps) stream?.setBitrate(bitrate.kbps)
            }
        }

        when (wanted.output) {
            SecondScreenOutput.VIDEO_CLUSTER -> {
                releaseApp()
                showCluster(display?.overscanPct ?: 0)
                startVideo()
            }
            SecondScreenOutput.VIDEO_APP -> {
                dismissCluster()
                val pkg = wanted.config.appPackage
                if (pkg != null && appShown != pkg) {
                    releaseApp()
                    showApp(pkg)
                }
                startVideo()
            }
            SecondScreenOutput.DATA -> {
                DisplayLink.send(DisplayMode(DisplayMode.Mode.DATA, wanted.config.upsideDown))
                startFeed()
            }
            SecondScreenOutput.NONE -> if (connected) DisplayLink.send(DisplayMode(DisplayMode.Mode.IDLE, wanted.config.upsideDown))
        }
        if (wanted.output != SecondScreenOutput.DATA) stopFeed()
        holdFeeds(wanted.output != SecondScreenOutput.NONE)
    }

    /** Tells the display to play, with the stream's parameter sets and a fresh key frame (also after a reconnect). */
    private fun startVideo() {
        val s = stream ?: return
        DisplayLink.send(DisplayMode(DisplayMode.Mode.VIDEO, SecondScreenStore.config.value.upsideDown))
        s.videoConfig?.let(DisplayLink::send)
        s.requestKeyFrame()
    }

    private suspend fun makeStream(w: Int, h: Int, fps: Int, kbps: Int): StreamDisplay? {
        val made = withContext(Dispatchers.Default) {
            StreamDisplay.create(
                appContext, w, h, SecondScreenRules.streamDensity(h), fps, kbps,
                onConfig = { DisplayLink.send(it) },
                // With no display to send to (a hiccup), frames are simply let go: asking the
                // encoder for key frames meanwhile would only waste it. startVideo() asks
                // for one when the display is back.
                onPacket = { !DisplayLink.connected || DisplayLink.sendVideo(it) },
                onFailed = { scope.launch { encoderFailed.value = true } }
            )
        }
        if (made == null) {
            // Taken by a camera, say: the display draws the cluster itself.
            encoderFailed.value = true
            Log.w(TAG, "no encoder; falling back to readings")
        }
        return made
    }

    private fun showCluster(overscanPct: Int) {
        val s = stream ?: return
        if (presentation != null) return
        presentation = runCatching {
            // In Dashwheel's language, one picked inside it included (the app's own context keeps the unit's).
            ClusterPresentation(AppLanguage.wrap(appContext), s.androidDisplay, _page, _pages, overscanPct, MainActivity.started).also { it.show() }
        }.onFailure { Log.w(TAG, "cluster presentation refused", it) }.getOrNull()
    }

    private fun dismissCluster() {
        presentation?.let { runCatching { it.dismiss() } }
        presentation = null
    }

    /**
     * Opens [pkg] and moves its stack onto the streamed display. An app can't
     * be launched straight onto another app's private display, but the shell
     * can move a stack there, as the tiles park windows (HiddenDisplay).
     */
    private suspend fun showApp(pkg: String) {
        val s = stream ?: return
        _heldPackages.update { it + pkg }
        if (!AppLauncher.launch(appContext, pkg)) {
            _heldPackages.update { it - pkg }
            return
        }
        repeat(APP_LAUNCH_TRIES) {
            delay(APP_LAUNCH_WAIT_MS / 2)
            DockShell.forgetListing()
            val listing = runCatching { DockShell.listStacks(appContext) }.getOrNull() ?: return@repeat
            val (stackId, displayId) = WindowListing.stackOf(listing, pkg) ?: return@repeat
            if (displayId == s.displayId) {
                appShown = pkg
                refocusDashboard()
                return
            }
            val out = runCatching { DockShell.shell(appContext, "am display move-stack $stackId ${s.displayId}") }
            Log.i(TAG, "$pkg (stack $stackId) -> second screen: ${out.getOrElse { it.message }}")
        }
        Log.w(TAG, "$pkg could not be moved onto the second screen")
        _heldPackages.update { it - pkg }
    }

    /** Back onto the screen, behind the dashboard; the tiles take it from there. */
    private suspend fun releaseApp() {
        val pkg = appShown ?: return
        appShown = null
        DockShell.forgetListing()
        val listing = runCatching { DockShell.listStacks(appContext) }.getOrNull()
        val stack = listing?.let { WindowListing.stackOf(it, pkg) }
        if (stack != null && stack.second != WindowListing.DEFAULT_DISPLAY) {
            runCatching { DockShell.shell(appContext, "am display move-stack ${stack.first} ${WindowListing.DEFAULT_DISPLAY}") }
            refocusDashboard()
        }
        _heldPackages.update { it - pkg }
    }

    /** The app moved over took the focus with it: steering keys and the keyboard belong to the head unit's screen. */
    private fun refocusDashboard() {
        runCatching {
            appContext.startActivity(
                Intent(appContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    .putExtra(MainActivity.EXTRA_REFOCUS, true)
            )
        }
    }

    private suspend fun tearDown() {
        stopFeed()
        releaseApp()
        dismissCluster()
        stream?.release()
        stream = null
        streamShape = null
    }

    private fun startFeed() {
        if (feed?.isActive == true) return
        feed = ClusterFeed.run(appContext, scope, _page)
    }

    private fun stopFeed() {
        feed?.cancel()
        feed = null
    }

    /** Speed (GPS), what's playing, the OBD link: kept up while the second screen shows anything. */
    private fun holdFeeds(hold: Boolean) {
        if (hold == holdsFeeds) return
        holdsFeeds = hold
        val media = CarMediaController.shared(appContext)
        if (hold) {
            LocationFeed.acquire(appContext)
            media.acquire()
        } else {
            LocationFeed.release()
            media.release()
        }
        VehicleMonitor.setSecondScreenShowing(hold)
    }

    /** Moving or stopped, as the drive lock decides it ([MOVING_KMH], [STOPPED_KMH]), for the video-app rule. */
    private suspend fun followSpeed() {
        combine(ObdBluetoothManager.connectionState, ObdBluetoothManager.data, LocationFeed.freshSpeedKmh, DemoMode.active) { connection, obd, gps, demo ->
            if (demo) return@combine false
            val speed = (if (connection == ObdConnectionState.CONNECTED) obd.speedKmh else gps) ?: 0
            when {
                speed >= MOVING_KMH -> true
                speed <= STOPPED_KMH -> false
                else -> null
            }
        }
            .distinctUntilChanged()
            .collectLatest { fast ->
                when (fast) {
                    true -> moving.value = true
                    false -> {
                        delay(STOPPED_HOLD_MS)
                        moving.value = false
                    }
                    null -> Unit
                }
            }
    }

    private fun isVideoApp(pkg: String): Boolean {
        val category = runCatching { appContext.packageManager.getApplicationInfo(pkg, 0).category }.getOrNull()
        return VideoApps.isVideo(pkg, category)
    }
}
