package com.openauto.dash

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.input.InputManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Another app, Google Maps, running inside a dashboard tile: it is launched
 * onto a virtual display of the tile's size, whose picture is the tile's
 * [TextureView], and the tile's touches are sent on to it. The same way
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
 *
 * An app runs only once, so several tiles of it (Maps on two pages) share its
 * one display: the picture goes to the tile on screen ([pickTile]).
 *
 * Opening an app on a display puts that display in front, and Android sends
 * the unit's keys (the touch keys beside the screen: Home, Back...) to the
 * display in front: Home then looks for a home screen on the tile's display,
 * finds none and does nothing. So the keys are handed back to the main screen
 * after each launch there, and after each touch ([giveKeysBack]).
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

    /** How often we look where the app is while it starts or moves. */
    private const val POLL_MS = 150L

    /** How long a start may take to show before we stop looking for it. */
    private const val START_WAIT_MS = 5_000L

    /** Moves tried before giving up (a new task of the app may appear meanwhile). */
    private const val MOVE_ROUNDS = 5

    /** How long a closed window gets to go before the app is opened afresh. */
    private const val CLOSE_WAIT_MS = 500L

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

    // --- Touches ------------------------------------------------------------

    /** Where the tiles' touches are sent to their apps from, one after another. */
    private val touchHandler: Handler by lazy {
        Handler(HandlerThread("embedded-touch", Process.THREAD_PRIORITY_DISPLAY).apply { start() }.looper)
    }

    // Both @hide, looked up once: looking them up on every touch took long
    // enough to make taps late and drags stutter.
    private val setDisplayId: Method? by lazy {
        runCatching { HiddenApiBypass.getDeclaredMethod(InputEvent::class.java, "setDisplayId", Int::class.javaPrimitiveType) }
            .onFailure { Log.w(TAG, "no InputEvent.setDisplayId", it) }.getOrNull()
    }

    private val injectInputEvent: Method? by lazy {
        runCatching { HiddenApiBypass.getDeclaredMethod(InputManager::class.java, "injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType) }
            .onFailure { Log.w(TAG, "no InputManager.injectInputEvent", it) }.getOrNull()
    }

    // --- The unit's keys ---------------------------------------------------

    /** How often a tap in a tile checks whether the keys can come back (not while typing there). */
    private const val GIVE_BACK_MS = 1_500L

    /** How long a tap in a tile keeps checking before letting the keys be. */
    private const val GIVE_BACK_TRIES = 80

    /** A key's second chance, once the main screen is back in front. */
    private const val KEY_AGAIN_MS = 150L

    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** The dashboard while it is on screen: its task is what brings the main screen back in front. */
    @Volatile
    private var dashboard: WeakReference<Activity>? = null

    /** A tile's display may be in front, with the keys: set on a launch or a tap there. */
    @Volatile
    private var keysAway = false

    private var tileTouched = false
    private var givingBack: Job? = null

    private var raising: Job? = null

    /** Home or Back held back by [keyWhileAway], until let go. */
    private var heldKey = 0

    /** [activity] shows (onStart), or null once it no longer does (onStop). */
    fun dashboardShown(activity: Activity?) {
        dashboard = activity?.let { WeakReference(it) }
        // Something else came in front of the dashboard, on the main screen: it has the keys.
        if (activity == null) keysAway = false
    }

    /**
     * Puts the main screen back in front, and the keys with it, by raising the
     * task already in front there (the dashboard's, or a window floating over
     * it), so nothing moves on screen. Only while the dashboard shows. Main
     * thread; null when there was nothing to do.
     */
    fun giveKeysBack(): Job? {
        if (!keysAway) return null
        val activity = dashboard?.get() ?: return null
        keysAway = false
        givingBack?.cancel()
        // A newer look wins: the older one may have seen the app still on the screen, before its move.
        raising?.cancel()
        return mainScope.launch {
            val front = runCatching {
                DockShell.forgetListing()
                WindowListing.frontTask(DockShell.listStacks(activity))
            }.getOrNull()
            ensureActive()
            val taskId = front ?: activity.taskId
            runCatching {
                activity.getSystemService(ActivityManager::class.java)
                    .moveTaskToFront(taskId, ActivityManager.MOVE_TASK_NO_USER_ACTION)
            }.onSuccess { Log.i(TAG, "keys back to the main screen (task $taskId raised)") }
                .onFailure { Log.w(TAG, "can't give the keys back to the main screen", it) }
            DockShell.forgetListing()
        }.also { raising = it }
    }

    /** A touch on the dashboard begins ([MainActivity.dispatchTouchEvent]). */
    fun touchStarts() {
        tileTouched = false
        downOn = null
        givingBack?.cancel()
    }

    // --- A touch on a tile, straight from the window -------------------------
    //
    // The dashboard decides where a finger lands (so a menu drawn over a tile
    // keeps its touches), but once it lands on an app's tile the rest of that
    // touch goes to the app straight from the window: through the dashboard's
    // pages it reached the app as a press and a cancel, a whole drag as 4 touches.

    /** The tile's app the finger just landed on, and where on the tile. Main thread. */
    private var downOn: Host? = null
    private var downX = 0f
    private var downY = 0f

    /** The app the current touch goes to, and the tile's offset in the window. */
    private var routeTo: Host? = null
    private var routeDx = 0f
    private var routeDy = 0f

    /** True while a touch goes straight to a tile's app: the tile's own view leaves it alone. */
    val routing: Boolean get() = routeTo != null

    /** The finger landed on [host]'s tile at ([x], [y]) on it. */
    fun tileDown(host: Host, x: Float, y: Float) {
        downOn = host
        downX = x
        downY = y
    }

    /**
     * After the dashboard saw a finger go down at [down] (window coordinates):
     * true when it landed on an app's tile, the rest of the touch then going
     * there through [routeTouch].
     */
    fun takeTouch(down: MotionEvent): Boolean {
        val host = downOn ?: return false
        downOn = null
        routeTo = host
        routeDx = down.x - downX
        routeDy = down.y - downY
        return true
    }

    /** The rest of a touch taken by [takeTouch], moved onto the tile; false when none is. */
    fun routeTouch(ev: MotionEvent): Boolean {
        val host = routeTo ?: return false
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            // A new touch without the end of the last one: that one is over.
            routeTo = null
            return false
        }
        val copy = MotionEvent.obtain(ev)
        copy.offsetLocation(-routeDx, -routeDy)
        host.touch(copy)
        copy.recycle()
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            routeTo = null
            touchEnds()
        }
        return true
    }

    /** That touch landed on an app inside a tile. */
    fun tileTouched() {
        tileTouched = true
    }

    /**
     * The touch is over. Elsewhere on the dashboard: the keys come back now.
     * In a tile: the tap may have opened a screen of the app there, taking the
     * keys with it; they come back shortly, once no keyboard is up for it.
     */
    fun touchEnds() {
        if (!tileTouched) {
            giveKeysBack()
            return
        }
        if (held.isEmpty()) return
        keysAway = true
        givingBack?.cancel()
        givingBack = mainScope.launch {
            repeat(GIVE_BACK_TRIES) {
                delay(GIVE_BACK_MS)
                val activity = dashboard?.get() ?: return@launch
                if (!keyboardUp(activity)) {
                    giveKeysBack()
                    return@launch
                }
            }
        }
    }

    /** Whether the on-screen keyboard shows (for an app inside a tile, typing a search). */
    private fun keyboardUp(context: Context): Boolean = runCatching {
        // @hide, public on Android 10 and 11.
        val imm = context.getSystemService(InputMethodManager::class.java)
        (HiddenApiBypass.invoke(InputMethodManager::class.java, imm, "getInputMethodWindowVisibleHeight") as Int) > 0
    }.getOrDefault(false)

    /**
     * A key seen by the accessibility service before anyone else, while a
     * tile's display may still have the keys: Home and Back are held back, the
     * main screen brought in front, and the key done again there. True when
     * taken.
     */
    fun keyWhileAway(service: AccessibilityService, event: KeyEvent): Boolean {
        // A key held back when pressed is done again when let go, whatever happened meanwhile.
        val held = event.action == KeyEvent.ACTION_UP && event.keyCode == heldKey
        if (!held && (!keysAway || dashboard?.get() == null)) return false
        val action = when (event.keyCode) {
            KeyEvent.KEYCODE_HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            KeyEvent.KEYCODE_BACK -> AccessibilityService.GLOBAL_ACTION_BACK
            else -> {
                // Volume and the rest go through as they are; the next ones reach the main screen.
                mainScope.launch { giveKeysBack() }
                return false
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN) heldKey = event.keyCode
        if (event.action == KeyEvent.ACTION_UP) {
            heldKey = 0
            mainScope.launch {
                giveKeysBack()?.join()
                delay(KEY_AGAIN_MS)
                service.performGlobalAction(action)
            }
        }
        return true
    }

    /** A launch or a move just put a tile's display in front: the keys come back at once. */
    private fun launchedOnTile() {
        if (held.isEmpty()) return
        keysAway = true
        mainScope.launch { giveKeysBack() }
    }

    /**
     * Which of an app's tiles gets the picture: [current] while it is still on
     * screen, so a swipe halfway never flips it back and forth; else a tile on
     * screen; else, none showing (the pages beside, arranging), [current] as
     * long as it has a picture to draw on; else the latest tile with one.
     * [ready] are the tiles with a picture to draw on, oldest first.
     */
    fun <T> pickTile(current: T?, ready: List<T>, onScreen: Set<T>): T? =
        current?.takeIf { it in ready && it in onScreen }
            ?: ready.lastOrNull { it in onScreen }
            ?: current?.takeIf { it in ready }
            ?: ready.lastOrNull()

    /** Closes the displays of the apps not in [keep]; each app closes with its display. */
    fun releaseUnless(keep: Set<String>) {
        val gone = hosts.keys - keep
        gone.forEach { hosts.remove(it)?.release() }
    }

    /**
     * One app's display: made on the first picture, then only given a new one,
     * from whichever of the app's tiles is on screen. The tiles, and the calls
     * below, all live on the main thread.
     */
    class Host(private val context: Context, val packageName: String) {

        private val _status = MutableStateFlow(Status.STARTING)
        val status: StateFlow<Status> = _status.asStateFlow()

        @Volatile
        private var display: VirtualDisplay? = null
        @Volatile
        private var touchRefused = false
        private var settling: Job? = null

        /** One tile of the app: its picture, once it has one, and whether it is on screen. */
        private class Tile {
            var surface: Surface? = null
            var width = 0
            var height = 0
            var dpi = 0
            var onScreen = false
        }

        private val tiles = LinkedHashMap<Any, Tile>()

        /** The tile the display draws on, and the picture and size it was given. */
        private var shownOn: Any? = null
        private var shownSurface: Surface? = null
        @Volatile
        private var shownWidth = 0
        @Volatile
        private var shownHeight = 0
        private var shownDpi = 0

        /** [tile] shows [surface], [width] x [height] pixels at [dpi]. */
        fun attach(tile: Any, surface: Surface, width: Int, height: Int, dpi: Int) {
            val t = tiles.getOrPut(tile) { Tile() }
            t.surface = surface
            t.width = width
            t.height = height
            t.dpi = dpi
            route()
        }

        /** [tile] came on screen, or went off it (a page swipe). */
        fun onScreen(tile: Any, on: Boolean) {
            val t = tiles.getOrPut(tile) { Tile() }
            if (t.onScreen == on) return
            t.onScreen = on
            route()
        }

        /**
         * [tile] lost its picture (a page no longer kept, arranging): the
         * display moves to another tile of the app if there is one, else stays
         * without a picture, so the app keeps running.
         */
        fun detach(tile: Any) {
            tiles[tile]?.surface = null
            route()
        }

        /** [tile] is gone for good. */
        fun forget(tile: Any) {
            tiles.remove(tile)
            route()
        }

        /** Gives the display to the tile [pickTile] chooses, when it is not already there. */
        private fun route() {
            val ready = tiles.filterValues { it.surface != null }.keys.toList()
            val onScreen = tiles.filterValues { it.onScreen }.keys
            val next = pickTile(shownOn, ready, onScreen)
            val t = next?.let { tiles[it] }
            val surface = t?.surface
            if (t == null || surface == null) {
                shownOn = null
                shownSurface = null
                display?.surface = null
                return
            }
            if (next == shownOn && surface === shownSurface &&
                t.width == shownWidth && t.height == shownHeight && t.dpi == shownDpi
            ) return
            shownOn = next
            shownSurface = surface
            shownWidth = t.width
            shownHeight = t.height
            shownDpi = t.dpi
            show(surface, t.width, t.height, t.dpi)
        }

        @SuppressLint("WrongConstant") // DESTROY_CONTENT_ON_REMOVAL is a real flag, only @hide.
        private fun show(surface: Surface, width: Int, height: Int, dpi: Int) {
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

        fun release() {
            settling?.cancel()
            tiles.clear()
            shownOn = null
            shownSurface = null
            display?.let { vd ->
                WindowListing.embeddedDisplays = WindowListing.embeddedDisplays - vd.display.displayId
                vd.release()
            }
            held = held - packageName
            display = null
            _status.value = Status.STARTING
        }

        /**
         * Puts the app on the tile, full size, in the background. Already there:
         * nothing is done, so coming back to the page never pulls it out again.
         * Running full screen elsewhere: moved over. In a floating window (the
         * app's window tile, picture-in-picture): that window would keep its
         * size and spot once moved, a corner of the tile, so it is closed and
         * the app opened afresh. Not running: started, then moved over.
         */
        private fun launch(vd: VirtualDisplay) {
            if (settling?.isActive == true) return
            settling = scope.launch { place(vd) }
        }

        private suspend fun place(vd: VirtualDisplay) {
            val id = vd.display.displayId
            var found: List<WindowListing.AppStack> = listStacks() ?: run {
                // No shell to look with: the launch alone, which lands on the tile on most ROMs.
                start(vd)
                return
            }
            var fresh = false
            val filled = HashSet<Int>()
            repeat(MOVE_ROUNDS) {
                if (found.isEmpty()) {
                    if (fresh) return
                    start(vd)
                    fresh = true
                    if (_status.value != Status.SHOWN) return
                    // This ROM opens it full screen on the main screen whatever display is
                    // asked for: caught as soon as it shows, so it only flashes there.
                    found = awaitStacks()
                    if (found.isEmpty()) return
                }
                val floating = found.filter { floating(it) && it.stackId !in filled }
                if (found.all { it.displayId == id } && floating.isEmpty()) {
                    _status.value = Status.SHOWN
                    Log.i(TAG, "$packageName is on the tile")
                    return
                }
                val closing = !fresh && floating.isNotEmpty()
                for (stack in found) {
                    if (closing && stack in floating) {
                        shell("am stack remove ${stack.stackId}", "$packageName ${stack.mode} stack ${stack.stackId} on display ${stack.displayId} closed")
                        continue
                    }
                    if (stack.displayId != id) {
                        shell("am display move-stack ${stack.stackId} $id", "$packageName stack ${stack.stackId} (${stack.mode}) from display ${stack.displayId} to $id")
                        launchedOnTile()
                    }
                    if (stack in floating) {
                        // Even started afresh it floats (this ROM's choice): stretched to fill the tile.
                        shell("am stack resize ${stack.stackId} 0 0 $shownWidth $shownHeight", "$packageName ${stack.mode} stack ${stack.stackId} fills the tile")
                        filled += stack.stackId
                    }
                }
                delay(if (closing) CLOSE_WAIT_MS else POLL_MS)
                found = listStacks() ?: return
            }
            Log.w(TAG, "$packageName still off the tile: " + found.joinToString { "stack ${it.stackId} ${it.mode} on display ${it.displayId}" })
        }

        private fun floating(stack: WindowListing.AppStack) = stack.mode == "freeform" || stack.mode == "pinned"

        private suspend fun shell(cmd: String, what: String) {
            val out = runCatching { DockShell.shell(context, cmd) }.getOrElse { "failed: ${it.message}" }
            Log.i(TAG, "$what: ${out.trim()}")
        }

        private fun start(vd: VirtualDisplay) {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent == null) {
                _status.value = Status.MISSING
                return
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(vd.display.displayId)
            // Full size on the tile's display, not the floating window it may have had before.
            runCatching { HiddenApiBypass.invoke(ActivityOptions::class.java, options, "setLaunchWindowingMode", WINDOWING_MODE_FULLSCREEN) }
                .onFailure { Log.w(TAG, "no windowing mode for $packageName", it) }
            _status.value = runCatching { context.startActivity(intent, options.toBundle()) }
                .onFailure { Log.w(TAG, "$packageName refused on display ${vd.display.displayId}", it) }
                .fold({ Status.SHOWN }, { Status.BLOCKED })
            if (_status.value == Status.SHOWN) launchedOnTile()
        }

        /** The app's stacks once it has shown up somewhere, looking every [POLL_MS] for a few seconds. */
        private suspend fun awaitStacks(): List<WindowListing.AppStack> {
            repeat((START_WAIT_MS / POLL_MS).toInt()) {
                delay(POLL_MS)
                val now = listStacks() ?: return emptyList()
                if (now.isNotEmpty()) return now
            }
            return emptyList()
        }

        /** The app's stacks right now, or null (logged) when the shell cannot list them. */
        private suspend fun listStacks(): List<WindowListing.AppStack>? {
            DockShell.forgetListing()
            return runCatching { WindowListing.appStacks(DockShell.listStacks(context), packageName) }
                .onFailure { Log.w(TAG, "can't see where $packageName is", it) }
                .getOrNull()
        }

        /**
         * Sends a touch on the tile to the same spot on the app's display, from
         * [touchHandler]: in order, and never holding up the dashboard's own
         * thread (a drag sends dozens a second).
         */
        fun touch(event: MotionEvent) {
            val id = display?.display?.displayId ?: return
            if (touchRefused) return
            val copy = MotionEvent.obtain(event)
            touchHandler.post {
                try {
                    val setId = setDisplayId
                    val inject = injectInputEvent
                    if (setId == null || inject == null) {
                        refuseTouches(null)
                        return@post
                    }
                    setId.invoke(copy, id)
                    inject.invoke(context.getSystemService(InputManager::class.java), copy, INJECT_ASYNC)
                } catch (t: InvocationTargetException) {
                    // Without INJECT_EVENTS every touch is refused: say it once, then leave the app as a picture.
                    // Anything else (the display resized meanwhile...) only loses this one touch.
                    if (t.targetException is SecurityException) refuseTouches(t.targetException)
                    else Log.w(TAG, "a touch missed $packageName", t.targetException)
                } catch (t: Exception) {
                    Log.w(TAG, "a touch missed $packageName", t)
                } finally {
                    copy.recycle()
                }
            }
        }

        private fun refuseTouches(why: Throwable?) {
            touchRefused = true
            Log.w(TAG, "touches can't reach $packageName", why)
        }
    }
}

/** Google Maps itself inside the tile, see [EmbeddedApp]. */
@Composable
internal fun EmbeddedMapsCard(modifier: Modifier = Modifier) =
    EmbeddedAppCard(EmbeddedApp.MAPS_PACKAGE, "Google Maps", modifier)

/** Any app ([packageName], called [label]) running inside the tile, see [EmbeddedApp]. */
@Composable
internal fun EmbeddedAppCard(packageName: String, label: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val host = EmbeddedApp.host(context, packageName)
    val status by host.status.collectAsState()
    // This tile, among the app's others (Maps on another page too).
    val tile = remember { Any() }
    DisposableEffect(host, tile) {
        onDispose { host.forget(tile) }
    }
    // The tile's own zoom (TileZoom) sizes the app's text and buttons too.
    val dpi = (LocalDensity.current.density * 160).toInt()
    Box(
        modifier = modifier
            .background(DashColors.Card)
            // On screen once half of it shows: the pages beside the current one
            // are kept, off screen, and the app's picture belongs on the one seen.
            .onGloballyPositioned { coords ->
                val full = coords.size.width.toFloat() * coords.size.height
                val seen = coords.boundsInWindow()
                host.onScreen(tile, full > 0f && seen.width * seen.height * 2 >= full)
            }
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                // A TextureView, not a SurfaceView: a SurfaceView punches a hole
                // through the dashboard's window to show its picture, and on the
                // head unit that hole took the page's background and the other
                // tiles with it. A TextureView is drawn like any other view.
                TextureView(ctx).apply {
                    var surface: Surface? = null
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                            texture.setDefaultBufferSize(width, height)
                            val s = Surface(texture).also { surface = it }
                            if (width > 0 && height > 0) host.attach(tile, s, width, height, dpi)
                        }

                        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                            texture.setDefaultBufferSize(width, height)
                            val s = surface ?: return
                            if (width > 0 && height > 0) host.attach(tile, s, width, height, dpi)
                        }

                        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                            // The display lets go of the picture before it is freed.
                            host.detach(tile)
                            surface?.release()
                            surface = null
                            return true
                        }

                        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
                    }
                    setOnTouchListener { v, event ->
                        when {
                            event.actionMasked == MotionEvent.ACTION_DOWN -> {
                                // A drag on the map pans the map: the dashboard's pages must not take it.
                                v.parent?.requestDisallowInterceptTouchEvent(true)
                                EmbeddedApp.tileTouched()
                                // The rest of the touch comes straight from the window (EmbeddedApp.routeTouch).
                                EmbeddedApp.tileDown(host, event.x, event.y)
                                host.touch(event)
                            }
                            // Sent from the window already; the cancel that lets the dashboard go is not the app's.
                            EmbeddedApp.routing -> Unit
                            else -> host.touch(event)
                        }
                        true
                    }
                }
            }
        )
        // Over the picture, only while there is none to show.
        val message = when (status) {
            EmbeddedApp.Status.STARTING -> R.string.apps_embed_app_starting
            EmbeddedApp.Status.MISSING -> R.string.apps_embed_app_missing
            EmbeddedApp.Status.BLOCKED -> R.string.apps_embed_app_blocked
            EmbeddedApp.Status.SHOWN -> null
        }
        if (message != null) {
            Text(
                stringResource(message, label),
                color = DashColors.TextSecondary,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.Center).padding(16.dp)
            )
        }
    }
}
