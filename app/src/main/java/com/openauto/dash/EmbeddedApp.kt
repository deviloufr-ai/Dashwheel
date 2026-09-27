package com.openauto.dash

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.input.InputManager
import android.util.Log
import android.view.InputEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Another app, Google Maps, running inside a dashboard tile: it is launched
 * onto a virtual display of the tile's size, whose picture is the tile's
 * [SurfaceView], and the tile's touches are sent on to it. The same way
 * Android Auto shows Maps on a car's screen.
 *
 * Android lets an app do this only with two permissions it keeps for the
 * firmware's own apps: INTERNAL_SYSTEM_WINDOW to open another app on its
 * display, INJECT_EVENTS to touch it. On the K706 they are granted once
 * PMPatch3 runs in Magisk (it makes every signature check pass), so the tile
 * is only offered where Android has granted the first one ([allowed]).
 *
 * One display per app, kept for as long as a tile shows it: a page swipe
 * only takes the picture away, so a navigation goes on and comes back as it
 * was. The display is closed, and the app with it, once no tile is left
 * ([releaseUnless]).
 */
internal object EmbeddedApp {

    private const val TAG = "EmbeddedApp"

    /** Google Maps, the one app the tile shows for now. */
    const val MAPS_PACKAGE = "com.google.android.apps.maps"

    // DisplayManager.VIRTUAL_DISPLAY_FLAG_*: the app's content only, never a
    // mirror, and the app closed with the display instead of thrown onto the
    // main screen (DESTROY_CONTENT_ON_REMOVAL is @hide).
    private const val FLAG_PUBLIC = 1 shl 0
    private const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
    private const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8

    // InputManager.INJECT_INPUT_EVENT_MODE_ASYNC (@hide).
    private const val INJECT_ASYNC = 0

    // WindowConfiguration.WINDOWING_MODE_FULLSCREEN (@hide).
    private const val WINDOWING_MODE_FULLSCREEN = 1

    /** How long the window manager gets to act on a launch or a move before we look. */
    private const val SETTLE_DELAY_MS = 700L

    enum class Status {
        /** The display is being made or the app launched. */
        STARTING,
        /** The app was launched on the display. */
        SHOWN,
        /** The app is not installed. */
        MISSING,
        /** Android refused the display or the launch. */
        BLOCKED
    }

    /** Whether Android lets Dashwheel open another app on its own display. */
    fun allowed(context: Context): Boolean = granted(context, "android.permission.INTERNAL_SYSTEM_WINDOW")

    /** Whether the tile's touches can be sent on to the app. */
    fun canTouch(context: Context): Boolean = granted(context, "android.permission.INJECT_EVENTS")

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private val hosts = HashMap<String, Host>()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun host(context: Context, packageName: String): Host =
        hosts.getOrPut(packageName) { Host(context.applicationContext, packageName) }

    /** Apps with a display of their own right now; read from the window tiles' threads. */
    @Volatile
    private var held: Set<String> = emptySet()

    /** True while [packageName] runs inside a tile, on its own display. */
    fun holds(packageName: String): Boolean = packageName in held

    /** Closes the displays of the apps not in [keep]; each app closes with its display. */
    fun releaseUnless(keep: Set<String>) {
        val gone = hosts.keys - keep
        gone.forEach { hosts.remove(it)?.release() }
    }

    /** One app's display: made on the first picture, then only given a new one. */
    class Host(private val context: Context, val packageName: String) {

        private val _status = MutableStateFlow(Status.STARTING)
        val status: StateFlow<Status> = _status.asStateFlow()

        @Volatile
        private var display: VirtualDisplay? = null
        private var touchRefused = false
        private var settling: Job? = null

        /** The tile shows [surface], [width] x [height] pixels at [dpi]. */
        @SuppressLint("WrongConstant") // DESTROY_CONTENT_ON_REMOVAL is a real flag, only @hide.
        fun attach(surface: Surface, width: Int, height: Int, dpi: Int) {
            val vd = display
            if (vd != null) {
                vd.resize(width, height, dpi)
                vd.surface = surface
                // Brought back to the front, started again if it was closed meanwhile,
                // or opened now if it was installed since. A refusal stays refused.
                if (_status.value != Status.BLOCKED) launch(vd)
                return
            }
            val made = runCatching {
                val dm = context.getSystemService(DisplayManager::class.java)
                dm.createVirtualDisplay(
                    "Dashwheel:$packageName", width, height, dpi, surface,
                    FLAG_PUBLIC or FLAG_OWN_CONTENT_ONLY or FLAG_DESTROY_CONTENT_ON_REMOVAL
                )
            }.onFailure { Log.w(TAG, "no display for $packageName", it) }.getOrNull()
            if (made == null) {
                _status.value = Status.BLOCKED
                return
            }
            display = made
            // The window tiles now leave this app and this display alone.
            WindowListing.embeddedDisplays = WindowListing.embeddedDisplays + made.display.displayId
            held = held + packageName
            Log.i(TAG, "display ${made.display.displayId} for $packageName, ${width}x$height at $dpi dpi")
            launch(made)
        }

        /**
         * The tile went (a page swipe, arranging): the display stays, without a
         * picture, so the app keeps running.
         */
        fun detach() {
            display?.surface = null
        }

        fun release() {
            settling?.cancel()
            display?.let { vd ->
                WindowListing.embeddedDisplays = WindowListing.embeddedDisplays - vd.display.displayId
                vd.release()
            }
            held = held - packageName
            display = null
            _status.value = Status.STARTING
        }

        private fun launch(vd: VirtualDisplay) {
            start(vd)
            if (_status.value != Status.SHOWN) return
            // Android may accept the launch yet leave an instance that was already
            // open (a floating Maps window) where it was, in front of the
            // dashboard: it is then brought onto the tile by hand.
            if (settling?.isActive != true) settling = scope.launch { settle(vd.display.displayId) }
        }

        private fun start(vd: VirtualDisplay) {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent == null) {
                _status.value = Status.MISSING
                return
            }
            // No MULTIPLE_TASK: an instance already open elsewhere (the Maps
            // window, full screen) is moved onto the tile instead of doubled.
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(vd.display.displayId)
            // Full size on the tile's display. Without it, an app that was in a
            // floating window before (the Maps window tile) keeps that mode and
            // is taken for one of the dashboard's floating windows.
            runCatching { HiddenApiBypass.invoke(ActivityOptions::class.java, options, "setLaunchWindowingMode", WINDOWING_MODE_FULLSCREEN) }
                .onFailure { Log.w(TAG, "no windowing mode for $packageName", it) }
            _status.value = runCatching { context.startActivity(intent, options.toBundle()) }
                .onFailure { Log.w(TAG, "$packageName refused on display ${vd.display.displayId}", it) }
                .fold({ Status.SHOWN }, { Status.BLOCKED })
        }

        /**
         * Makes sure the app ended up on the tile's display [id], full size, and
         * nowhere else. A stack left on another display is moved over
         * (`am display move-stack`, which keeps the app as it was); if it is
         * still elsewhere, or only in a floating window on the tile, it is closed
         * and the app opened afresh on the tile.
         */
        private suspend fun settle(id: Int) {
            delay(SETTLE_DELAY_MS)
            val stacks = listStacks() ?: return
            val away = stacks.filter { it.displayId != id }
            if (away.isEmpty() && stacks.all { fullSize(it) }) return
            for (stack in away) {
                val out = runCatching { DockShell.shell(context, "am display move-stack ${stack.stackId} $id") }.getOrElse { "failed: ${it.message}" }
                Log.i(TAG, "$packageName stack ${stack.stackId} (${stack.mode}) from display ${stack.displayId} to $id: ${out.trim()}")
            }
            delay(SETTLE_DELAY_MS)
            val moved = listStacks() ?: return
            if (moved.isNotEmpty() && moved.all { it.displayId == id && fullSize(it) }) {
                Log.i(TAG, "$packageName is on the tile")
                return
            }
            // Still outside the tile, or in a floating window on it: a fresh start.
            for (stack in moved) {
                val out = runCatching { DockShell.shell(context, "am stack remove ${stack.stackId}") }.getOrElse { "failed: ${it.message}" }
                Log.i(TAG, "$packageName stack ${stack.stackId} (${stack.mode}) on display ${stack.displayId} closed: ${out.trim()}")
            }
            delay(SETTLE_DELAY_MS)
            withContext(Dispatchers.Main) { display?.takeIf { it.display.displayId == id }?.let { start(it) } }
            delay(SETTLE_DELAY_MS)
            val after = listStacks() ?: return
            Log.i(TAG, "$packageName now: " + after.joinToString { "stack ${it.stackId} ${it.mode} on display ${it.displayId}" })
        }

        /** The app's stacks right now, or null (logged) when the shell cannot list them. */
        private suspend fun listStacks(): List<WindowListing.AppStack>? {
            DockShell.forgetListing()
            return runCatching { WindowListing.appStacks(DockShell.listStacks(context), packageName) }
                .onFailure { Log.w(TAG, "can't see where $packageName is", it) }
                .getOrNull()
        }

        private fun fullSize(stack: WindowListing.AppStack) = stack.mode != "freeform" && stack.mode != "pinned"

        /** Sends a touch on the tile to the same spot on the app's display. */
        fun touch(event: MotionEvent) {
            val id = display?.display?.displayId ?: return
            if (touchRefused) return
            val copy = MotionEvent.obtain(event)
            try {
                // Both @hide: InputEvent.setDisplayId and InputManager.injectInputEvent.
                HiddenApiBypass.invoke(InputEvent::class.java, copy, "setDisplayId", id)
                val im = context.getSystemService(InputManager::class.java)
                HiddenApiBypass.invoke(InputManager::class.java, im, "injectInputEvent", copy, INJECT_ASYNC)
            } catch (t: Throwable) {
                // Without INJECT_EVENTS every touch would throw: say it once, then leave the map as a picture.
                touchRefused = true
                Log.w(TAG, "touches can't reach $packageName", t)
            } finally {
                copy.recycle()
            }
        }
    }
}

/** Google Maps itself inside the tile, see [EmbeddedApp]. */
@Composable
internal fun EmbeddedMapsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val host = EmbeddedApp.host(context, EmbeddedApp.MAPS_PACKAGE)
    val status by host.status.collectAsState()
    // The tile's own zoom (TileZoom) sizes the app's text and buttons too.
    val dpi = (LocalDensity.current.density * 160).toInt()
    Box(modifier = modifier.background(DashColors.Card)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {}

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            if (width > 0 && height > 0) host.attach(holder.surface, width, height, dpi)
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) = host.detach()
                    })
                    setOnTouchListener { _, event -> host.touch(event); true }
                }
            }
        )
        // Over the picture, only while there is none to show.
        val message = when (status) {
            EmbeddedApp.Status.STARTING -> R.string.apps_embed_starting
            EmbeddedApp.Status.MISSING -> R.string.apps_embed_missing
            EmbeddedApp.Status.BLOCKED -> R.string.apps_embed_blocked
            EmbeddedApp.Status.SHOWN -> null
        }
        if (message != null) {
            Text(
                stringResource(message),
                color = DashColors.TextSecondary,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.Center).padding(16.dp)
            )
        }
    }
}
