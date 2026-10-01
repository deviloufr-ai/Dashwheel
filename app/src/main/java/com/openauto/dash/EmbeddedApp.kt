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
import android.os.SystemClock
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    /**
     * How long a freshly started app's first screen gets to hand off to
     * another app's ([WindowListing.handedTo]) before it counts as on the tile.
     */
    private const val HAND_OFF_MS = 1_000L

    /** How long a closed window gets to go before the app is opened afresh. */
    private const val CLOSE_WAIT_MS = 500L

    /** The shortest time between two closings of an app found running twice. */
    private const val RESTART_COOLDOWN_MS = 5 * 60_000L

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

    private val hosts = java.util.concurrent.ConcurrentHashMap<String, Host>()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun host(context: Context, packageName: String): Host =
        hosts.getOrPut(packageName) { Host(context.applicationContext, packageName) }

    /** Apps with a display of their own right now; read from the window tiles' threads. */
    @Volatile
    private var held: Set<String> = emptySet()

    /** The apps the other tiles show ([Host.windowPackage]): never taken for [host]'s hand-off. */
    private fun claimedBesides(host: Host): Set<String> =
        hosts.values.filter { it !== host }.flatMap { listOf(it.packageName, it.windowPackage) }.toSet()

    /** True while [packageName] runs inside a tile, on its own display. */
    fun holds(packageName: String): Boolean = packageName in held

    /** Opens [intent] in [packageName]'s tile when it runs in one ([Host.open]); false otherwise. */
    fun openInside(packageName: String, intent: Intent): Boolean =
        holds(packageName) && hosts[packageName]?.open(intent) == true

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
        if (activity == null) {
            // Something else came in front of the dashboard, on the main screen: it has the keys.
            keysAway = false
            // Not the user's doing: maybe the unit opening a tile's app full screen by itself.
            if (SystemClock.elapsedRealtime() - userActedAt > USER_OPENS_MS) watchFront(COVERED_WATCH_MS)
            return
        }
        watching?.cancel()
        dashboardBack()
    }

    /**
     * The dashboard is in front again: started, resumed (Home pressed while a
     * window over it never stopped it) or given the focus back. An app opened
     * full screen meanwhile (YouTube Music from the app list...) was taken off
     * its tile's display, leaving the tile black. Each tile's app is put back;
     * one still there is left alone.
     */
    fun dashboardBack() {
        if (dashboard?.get() == null) return
        hosts.values.forEach { it.bringBack() }
    }

    // --- Apps opened full screen by the unit itself ------------------------------
    //
    // The QF firmware reopens the navigation app full screen at power-up when
    // it was in front at switch-off (Waze inside its tile): it keeps that app
    // in Settings.System [NAVI_TO_RESTORE] at ACC off and starts it a few
    // seconds after ACC on. Coming on top of the one inside the tile, it made
    // two copies of the app: one covering the dashboard, where menus did
    // nothing, the map no longer turned with the car and Home could not get
    // rid of it, and one inside the tile. So that record is wiped when it names
    // an app inside a tile (the tile brings it back itself); and, should the
    // unit open it anyway, for a while after the ignition comes on, and after
    // the dashboard gets covered without a touch or a key, a tile's app found
    // full screen in front is put back into its tile. What the user opens full
    // screen themselves is left there.

    /** Where the firmware keeps the navigation app to reopen at power-up ("package/class"). */
    private const val NAVI_TO_RESTORE = "navi_activity_before_sleep"

    /** The value by which it reopens none. */
    private const val NO_NAVI = "not_restore_navi"

    /** How long the firmware is given to save its record at switch-off before it is looked at. */
    private const val NAVI_SAVED_MS = 2_000L

    /** How long after a touch on the dashboard, or a key, what opens full screen is the user's doing. */
    private const val USER_OPENS_MS = 3_000L

    /** How long the unit's own opening of an app is watched for, after the ignition comes on. */
    private const val POWER_UP_WATCH_MS = 60_000L

    /** How long after the ignition the dashboard is put back in front of an app the unit reopened. */
    private const val HOME_AFTER_POWER_UP_MS = 20_000L

    /** How long it is watched for once the dashboard got covered without the user. */
    private const val COVERED_WATCH_MS = 10_000L

    /** How often, meanwhile. */
    private const val WATCH_EVERY_MS = 1_000L

    /** When the user last touched the dashboard or pressed a key of the unit (elapsedRealtime). */
    @Volatile
    private var userActedAt = 0L

    private var watching: Job? = null

    /** The user touched the dashboard or pressed a key: what opens next is theirs. */
    fun userActed() {
        userActedAt = SystemClock.elapsedRealtime()
    }

    /** The ignition went off: the unit is not to reopen a tile's app full screen at the next start. */
    fun carStopped(context: Context) {
        scope.launch {
            delay(NAVI_SAVED_MS)
            forgetNaviToRestore(context)
        }
    }

    /**
     * The ignition came on: the unit reads its record a few seconds from now,
     * so it is wiped again first, then watched in case it opens the app anyway.
     */
    fun carStarted(context: Context) {
        scope.launch { forgetNaviToRestore(context) }
        mainScope.launch { watchFront(POWER_UP_WATCH_MS) }
        mainScope.launch { homeAfterPowerUp(context) }
    }

    /**
     * The unit wakes on the app that was in front at switch-off (any app, not
     * only the navigation one): for [HOME_AFTER_POWER_UP_MS] after the ignition, as
     * long as the user has not touched or pressed anything, the dashboard is
     * put back in front whenever it is not. Main thread.
     */
    private suspend fun homeAfterPowerUp(context: Context) {
        val since = userActedAt
        val until = SystemClock.elapsedRealtime() + HOME_AFTER_POWER_UP_MS
        while (SystemClock.elapsedRealtime() < until) {
            delay(WATCH_EVERY_MS)
            if (userActedAt != since) return
            if (dashboard?.get() != null) continue
            val home = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setPackage(context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            runCatching { context.startActivity(home) }
                .onSuccess {
                    Log.i(TAG, "power-up: an app came up in front of the dashboard, Home brought back")
                    DebugLog.note(context, "power-up: an app was in front, Home brought back")
                }
                .onFailure { Log.w(TAG, "power-up: can't bring the dashboard back to the front", it) }
        }
    }

    /** Wipes the firmware's app to reopen at power-up when it is one inside a tile. */
    private suspend fun forgetNaviToRestore(context: Context) {
        if (held.isEmpty()) return
        try {
            val saved = DockShell.shell(context, "settings get system $NAVI_TO_RESTORE").trim()
            if (saved.substringBefore('/') !in held) return
            DockShell.shell(context, "settings put system $NAVI_TO_RESTORE $NO_NAVI")
            Log.i(TAG, "the unit won't reopen $saved full screen at power-up: it lives inside a tile")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "can't stop the unit reopening the navigation app at power-up", e)
        }
    }

    /**
     * For [forMs], puts back into its tile every tile's app found full screen
     * in front of the main screen; stops once the user touches or presses a key.
     * Main thread.
     */
    private fun watchFront(forMs: Long) {
        if (hosts.isEmpty()) return
        watching?.cancel()
        val since = userActedAt
        val until = SystemClock.elapsedRealtime() + forMs
        watching = mainScope.launch {
            while (SystemClock.elapsedRealtime() < until) {
                delay(WATCH_EVERY_MS)
                if (userActedAt != since) return@launch
                // One fresh listing per look, shared by every tile's app.
                DockShell.forgetListing()
                hosts.values.toList().forEach { it.bringBackFromFront() }
            }
        }
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
        userActed()
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
        if (event.action == KeyEvent.ACTION_DOWN) userActed()
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

    // --- A tile left black ----------------------------------------------------
    //
    // Putting the app back where the listing says it is not was not enough on
    // the head unit: back from the app opened full screen, the tile stayed
    // black. So the tile is looked at again a while after each placing: where
    // the app is, and whether the tile's picture is still one flat black. What
    // is tried then grows ([remedy]): placed again, the picture handed to the
    // display again, and last the app closed and opened afresh on the tile,
    // the way it gets there when the dashboard starts.

    /** How long after a placing, then after each look, the tile is looked at: 1.5, 3, 6 and 10 seconds in. */
    private val LOOK_AFTER_MS = longArrayOf(1_500L, 1_500L, 3_000L, 4_000L)

    /** An app started that recently is not closed to be opened afresh: it may still be loading. */
    private const val FRESH_MS = 30_000L

    /** The shortest time between two closings of an app whose tile stays black. */
    private const val REOPEN_EVERY_MS = 60_000L

    /** How long the display draws on its spare picture before the tile's is handed back. */
    private const val REFRESH_MS = 300L

    /** What a look at the tile calls for. */
    enum class Remedy {
        /** The app shows on its tile. */
        DONE,
        /** Nothing yet: the app may still be drawing its first picture. */
        WAIT,
        /** Put on the tile again. */
        PLACE,
        /** The tile's picture handed to the display again. */
        REFRESH,
        /** Closed, and opened afresh on the tile. */
        REOPEN,
        /** Nothing left to try. */
        GIVE_UP
    }

    /**
     * What to do at look number [look] (from 0): the app is [onTile] or not by
     * the listing, the tile's picture is [black] or not. An app elsewhere is
     * placed again twice before it is closed; one on its tile with a black
     * picture gets time, then its picture again, and is closed last, after
     * ten seconds of black. Closing only when [mayReopen].
     */
    fun remedy(look: Int, onTile: Boolean, black: Boolean, mayReopen: Boolean): Remedy = when {
        onTile && !black -> Remedy.DONE
        !onTile && look < 2 -> Remedy.PLACE
        onTile && look == 1 -> Remedy.REFRESH
        onTile && look < 3 -> Remedy.WAIT
        mayReopen -> Remedy.REOPEN
        else -> Remedy.GIVE_UP
    }

    /** True when a picture's [pixels] are one flat black, or nothing at all: no app is drawn there. */
    fun allBlack(pixels: IntArray): Boolean =
        pixels.isNotEmpty() && pixels.all { it == 0 || it == 0xFF000000.toInt() }

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

    /**
     * True when an app's [stacks] show it running twice, as the unit makes it
     * at power-up: once on its tile's display [tileDisplay] and once more full
     * screen elsewhere. A floating window elsewhere does not count, it is closed
     * the usual way.
     */
    fun runsTwice(stacks: List<WindowListing.AppStack>, tileDisplay: Int): Boolean =
        stacks.any { it.displayId == tileDisplay } &&
            stacks.any { it.displayId != tileDisplay && it.mode != "freeform" && it.mode != "pinned" }

    /**
     * The apps whose display stays open: those inside a tile of the
     * arrangement on screen ([here]), and those inside a tile of the other
     * one ([other]) unless the arrangement on screen shows them in a window
     * ([windows], Maps docked beside the pages). An app runs once: held inside
     * a tile nobody sees, it never reached that window, which stayed empty
     * while the app floated wherever Android put it.
     */
    fun keptInside(here: Set<String>, other: Set<String>, windows: Set<String>): Set<String> =
        here + (other - windows)

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
        /**
         * The app whose windows show this one: [packageName] itself, or the
         * app its icon hands off to ([WindowListing.handedTo]).
         */
        @Volatile
        internal var windowPackage = packageName
            private set

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
            /** Whether the tile's picture is all black right now. */
            var black: () -> Boolean = { false }
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

        /**
         * A picture nobody looks at, for the display while no tile can take
         * it (arranging, a page no longer kept): with no picture at all
         * Android 10 switches the display off and the app sleeps, and on the
         * head unit Google Maps wakes black.
         */
        private var spareTexture: SurfaceTexture? = null
        private var spare: Surface? = null

        /** The spare picture, [width] x [height] pixels. Main thread. */
        private fun spare(width: Int, height: Int): Surface {
            val texture = spareTexture ?: SurfaceTexture(false).also { spareTexture = it }
            if (width > 0 && height > 0) texture.setDefaultBufferSize(width, height)
            return spare ?: Surface(texture).also { spare = it }
        }

        /** [tile] shows [surface], [width] x [height] pixels at [dpi]; [black] tells whether its picture is all black. */
        fun attach(tile: Any, surface: Surface, width: Int, height: Int, dpi: Int, black: () -> Boolean = { false }) {
            val t = tiles.getOrPut(tile) { Tile() }
            t.black = black
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
         * display moves to another tile of the app if there is one, else draws
         * on its spare picture, so the app keeps running.
         */
        fun detach(tile: Any) {
            tiles[tile]?.surface = null
            route()
        }

        /** The app's own display while it has one: for a command sent to it ([GeminiLive]). */
        val displayId: Int? get() = display?.display?.displayId

        /** Whether a tile other than [tile] shows this app too. */
        fun heldBesides(tile: Any): Boolean = tiles.keys.any { it !== tile }

        /** [tile] is gone for good. */
        fun forget(tile: Any) {
            tiles.remove(tile)
            route()
        }

        /**
         * Gives the display to the tile [pickTile] chooses, when it is not
         * already there. A tile on a page beside the one on screen keeps its
         * picture, and with no tile left to draw on the display gets its
         * [spare] one: it is never left without, see there.
         */
        private fun route() {
            val ready = tiles.filterValues { it.surface != null }.keys.toList()
            val onScreen = tiles.filterValues { it.onScreen }.keys
            val next = pickTile(shownOn, ready, onScreen)
            val t = next?.let { tiles[it] }
            val surface = t?.surface
            if (t == null || surface == null) {
                shownOn = null
                shownSurface = null
                display?.let { it.surface = spare(shownWidth, shownHeight) }
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
            // The dashboard's own context, so it also takes the focus back that a docked window had.
            PipAnchor.takenInside(dashboard?.get() ?: context, packageName)
            Log.i(TAG, "display ${made.display.displayId} for $packageName, ${width}x$height at $dpi dpi")
            launch(made)
        }

        /**
         * Hands [intent] (guidance to a place...) to the app on its tile, on
         * the tile's display, rather than full screen over the dashboard;
         * then makes sure it is still on the tile (some ROMs open on the main
         * screen whatever display is asked for). False when the tile's app
         * isn't running there, or Android refused.
         */
        fun open(intent: Intent): Boolean {
            val vd = display ?: return false
            if (_status.value != Status.SHOWN) return false
            intent.setPackage(packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(vd.display.displayId)
            runCatching { HiddenApiBypass.invoke(ActivityOptions::class.java, options, "setLaunchWindowingMode", WINDOWING_MODE_FULLSCREEN) }
            val ok = runCatching { context.startActivity(intent, options.toBundle()) }
                .onFailure { Log.w(TAG, "$packageName refused the intent on display ${vd.display.displayId}", it) }
                .isSuccess
            if (ok) {
                launchedOnTile()
                // Placed again even while a placing runs: the intent may have opened a new task elsewhere.
                settling?.cancel()
                launch(vd)
            }
            return ok
        }

        /** Puts the app back on the tile if it was taken elsewhere meanwhile; nothing when it is still there. */
        fun bringBack() {
            val vd = display ?: return
            if (shownOn == null || _status.value == Status.BLOCKED) return
            launch(vd)
        }

        /**
         * Full screen in front of the main screen: put back on the tile. Main
         * thread. The caller forgets the shell's listing first ([watchFront]):
         * one listing serves every app looked at together.
         */
        suspend fun bringBackFromFront() {
            val vd = display ?: return
            if (shownOn == null || _status.value == Status.BLOCKED || settling?.isActive == true) return
            val listing = try {
                DockShell.listStacks(context)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return
            }
            val front = WindowListing.fullscreenInFront(listing, windowPackage, context.packageName) ?: return
            Log.i(TAG, "$packageName came full screen by itself (task ${front.taskId}): back onto its tile")
            launch(vd)
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
            spare?.release()
            spare = null
            spareTexture?.release()
            spareTexture = null
            held = held - packageName
            scope.launch { FreeformBar.insideFloats(context, packageName, false) }
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
            settling = scope.launch {
                place(vd)
                watch(vd)
            }
        }

        /** When the app was last started, and last closed for a tile left black (elapsedRealtime, 0 if never). */
        @Volatile
        private var startedAt = 0L
        private var reopenedAt = 0L

        /**
         * Looks at the tile again after a placing, and acts while the app does
         * not show there, see [remedy]. Only while the dashboard is in front
         * with this tile's picture on the display: an app the user opens full
         * screen meanwhile is left there. An app opened afresh is looked at
         * once more: it is not closed a second time ([REOPEN_EVERY_MS]), only
         * placed again and given its picture again.
         */
        private suspend fun watch(vd: VirtualDisplay) {
            if (looks(vd)) looks(vd)
        }

        /** One round of looks at the tile; true when it ended with the app opened afresh. */
        private suspend fun looks(vd: VirtualDisplay): Boolean {
            val id = vd.display.displayId
            for ((look, wait) in LOOK_AFTER_MS.withIndex()) {
                delay(wait)
                if (_status.value != Status.SHOWN || display !== vd || dashboard?.get() == null) return false
                val black = withContext(Dispatchers.Main) { if (shownSurface == null) null else pictureBlack() } ?: return false
                val found = listStacks() ?: return false
                val onTile = found.isNotEmpty() && found.all { it.displayId == id && it.visible }
                val now = SystemClock.elapsedRealtime()
                val mayReopen = now - startedAt > FRESH_MS && (reopenedAt == 0L || now - reopenedAt > REOPEN_EVERY_MS)
                val remedy = remedy(look, onTile, black, mayReopen)
                if (remedy == Remedy.DONE) {
                    if (look > 0) Log.i(TAG, "$packageName shows on its tile")
                    return false
                }
                val where = if (found.isEmpty()) "not running" else found.joinToString {
                    "stack ${it.stackId} ${it.mode} on display ${it.displayId}" + if (it.visible) "" else " hidden"
                }
                Log.w(TAG, "$packageName does not show on its tile (display $id), look ${look + 1}: $where" +
                    (if (black) ", black picture" else "") + ": $remedy")
                when (remedy) {
                    Remedy.PLACE -> place(vd)
                    Remedy.REFRESH -> withContext(Dispatchers.Main) { refresh(vd) }
                    Remedy.REOPEN -> {
                        reopen(vd)
                        return true
                    }
                    Remedy.GIVE_UP -> return false
                    else -> Unit
                }
            }
            return false
        }

        /** Whether the tile on screen shows one flat black. Main thread. */
        private fun pictureBlack(): Boolean {
            val tile = shownOn?.let { tiles[it] } ?: return false
            return tile.onScreen && tile.black()
        }

        /**
         * Hands the display its [spare] picture for a moment, then the tile's
         * again: the system draws the whole display afresh for it. Never no
         * picture at all, which switches the display off. Main thread.
         */
        private suspend fun refresh(vd: VirtualDisplay) {
            if (display !== vd || shownSurface == null) return
            vd.surface = spare(shownWidth, shownHeight)
            delay(REFRESH_MS)
            // The tile may have changed meanwhile, or gone: the display was seen to then.
            val surface = shownSurface ?: return
            if (display !== vd) return
            vd.resize(shownWidth, shownHeight, shownDpi)
            vd.surface = surface
        }

        /** Closes the app and opens it afresh on the tile, the way it gets there when the dashboard starts. */
        private suspend fun reopen(vd: VirtualDisplay) {
            reopenedAt = SystemClock.elapsedRealtime()
            _status.value = Status.STARTING
            shell("am force-stop $packageName", "$packageName closed, to open it afresh on its tile")
            delay(CLOSE_WAIT_MS)
            place(vd)
        }

        private suspend fun place(vd: VirtualDisplay) {
            val id = vd.display.displayId
            var found: List<WindowListing.AppStack> = listStacks() ?: run {
                // No shell to look with: the launch alone, which lands on the tile on most ROMs.
                start(vd)
                return
            }
            if (runningTwice(found, id)) {
                // Moved onto the tile beside the first, the second copy would only hide
                // it, both still broken: the app is closed, then opened once, on the tile.
                restartedAt = SystemClock.elapsedRealtime()
                shell("am force-stop $packageName", "$packageName runs twice, on its tile and on display " +
                    found.first { it.displayId != id }.displayId + ": closed, to open it once")
                delay(CLOSE_WAIT_MS)
                found = listStacks() ?: return
            }
            var fresh = false
            // The listing before the start, to see what it handed off to.
            var before: String? = null
            var settled = false
            val filled = HashSet<Int>()
            repeat(MOVE_ROUNDS) {
                // Its first screen showed and went: what it opened instead belongs to another app.
                if (found.isEmpty() && before != null) found = awaitHandOff(before!!)
                if (found.isEmpty()) {
                    if (fresh) return
                    before = rawListing()
                    start(vd)
                    fresh = true
                    if (_status.value != Status.SHOWN) return
                    // This ROM opens it full screen on the main screen whatever display is
                    // asked for: caught as soon as it shows, so it only flashes there.
                    found = awaitStacks()
                    if (found.isEmpty()) found = before?.let { awaitHandOff(it) } ?: emptyList()
                    if (found.isEmpty()) return
                }
                val floating = found.filter { floating(it) && it.stackId !in filled }
                if (found.all { it.displayId == id } && floating.isEmpty()) {
                    val handOff = before
                    if (fresh && !settled && handOff != null) {
                        // Gemini's first screen reaches the tile and stays there hidden, while
                        // the Google app's opens full screen: looked at once more a moment later.
                        settled = true
                        delay(HAND_OFF_MS)
                        val now = rawListing() ?: return
                        // Its own screen in view is no hand-off, whatever else came up meanwhile.
                        val own = WindowListing.appStacks(now, windowPackage)
                        val other = if (own.any { it.visible }) null
                            else WindowListing.handedTo(handOff, now, packageName, context.packageName, claimedBesides(this))
                        found = if (other != null) follow(other, now) else own
                        if (found.isEmpty() || found.any { it.displayId != id }) return@repeat
                    }
                    _status.value = Status.SHOWN
                    Log.i(TAG, "$packageName is on the tile")
                    FreeformBar.insideFloats(context, packageName, found.any { floating(it) })
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
                        // Floating, the firmware's switch for the status bar would draw it lower down.
                        FreeformBar.insideFloats(context, packageName, true)
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

        /** When the app was last closed for running twice (elapsedRealtime), 0 if never. */
        private var restartedAt = 0L

        /**
         * [runsTwice], once in [RESTART_COOLDOWN_MS] at most, so an app that
         * opens a second screen of its own on purpose is not closed over and over.
         */
        private fun runningTwice(found: List<WindowListing.AppStack>, id: Int): Boolean =
            runsTwice(found, id) && (restartedAt == 0L || SystemClock.elapsedRealtime() - restartedAt > RESTART_COOLDOWN_MS)

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
            if (_status.value == Status.SHOWN) {
                startedAt = SystemClock.elapsedRealtime()
                launchedOnTile()
            }
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

        /**
         * The stacks of the app [packageName] handed its screen to since
         * [before] ([WindowListing.handedTo]), followed from then on; empty
         * when nothing else came up within [START_WAIT_MS].
         */
        private suspend fun awaitHandOff(before: String): List<WindowListing.AppStack> {
            repeat((START_WAIT_MS / POLL_MS).toInt()) {
                val now = rawListing() ?: return emptyList()
                val other = WindowListing.handedTo(before, now, packageName, context.packageName, claimedBesides(this))
                if (other != null) return follow(other, now)
                delay(POLL_MS)
            }
            return emptyList()
        }

        /** From now on the tile shows [other]'s windows: its stacks in [listing]. */
        private fun follow(other: String, listing: String): List<WindowListing.AppStack> {
            if (other != windowPackage) Log.i(TAG, "$packageName opens its screen in $other: the tile follows $other")
            windowPackage = other
            return WindowListing.appStacks(listing, other)
        }

        /** A fresh `am stack list`, or null (logged) when the shell cannot list. */
        private suspend fun rawListing(): String? {
            DockShell.forgetListing()
            return runCatching { DockShell.listStacks(context) }
                .onFailure { Log.w(TAG, "can't list the windows", it) }
                .getOrNull()
        }

        /** The app's stacks right now, or null (logged) when the shell cannot list them. */
        private suspend fun listStacks(): List<WindowListing.AppStack>? {
            DockShell.forgetListing()
            return runCatching { WindowListing.appStacks(DockShell.listStacks(context), windowPackage) }
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
    // The app's first picture has arrived: from then on the picture covers the
    // tile, and the card colour under it would only be painted for nothing.
    var painted by remember { mutableStateOf(false) }
    // The tile's own zoom (TileZoom) sizes the app's text and buttons too.
    val dpi = (LocalDensity.current.density * 160).toInt()
    Box(
        modifier = modifier
            .then(if (painted && status == EmbeddedApp.Status.SHOWN) Modifier else Modifier.background(DashColors.Card))
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
                            if (width > 0 && height > 0) host.attach(tile, s, width, height, dpi) { pictureBlack(this@apply) }
                        }

                        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                            texture.setDefaultBufferSize(width, height)
                            val s = surface ?: return
                            if (width > 0 && height > 0) host.attach(tile, s, width, height, dpi) { pictureBlack(this@apply) }
                        }

                        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                            // The display lets go of the picture before it is freed.
                            host.detach(tile)
                            surface?.release()
                            surface = null
                            painted = false
                            return true
                        }

                        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
                            if (!painted) painted = true
                        }
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

/** True when [view] shows one flat black, or nothing: no app is drawn on it. Main thread. */
private fun pictureBlack(view: TextureView): Boolean {
    if (!view.isAvailable || view.width <= 0 || view.height <= 0) return false
    val copy = runCatching { view.bitmap }.getOrNull() ?: return false
    val pixels = IntArray(copy.width * copy.height)
    copy.getPixels(pixels, 0, copy.width, 0, 0, copy.width, copy.height)
    copy.recycle()
    return EmbeddedApp.allBlack(pixels)
}
