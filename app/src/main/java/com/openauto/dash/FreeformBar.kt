package com.openauto.dash

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/*
 * No status bar over the dashboard while an app sits in one of its window
 * tiles ([PipAnchor]). Android shows the bars whenever a freeform window is on
 * screen, whatever the dashboard asks. The QF firmware (ROCO K706) has a
 * switch for its own launchers' map window (worked out from the firmware):
 * with sys.freeform.fullscreen at 1 a freeform window no longer forces them.
 * The same switch makes the firmware draw every freeform window with its top
 * edge at sys.freeform.locky (and its left edge at sys.freeform.lockx, when
 * set), so it is only on while the windows on screen share one top edge,
 * which locky then follows; windows on different rows get the bar back.
 * Written through the privileged shell the windows are docked with. System
 * properties last until the unit restarts, and the switch is put back to 0
 * once no window is left.
 *
 * The firmware moves every floating window that way, on any display: an app
 * inside a tile (EmbeddedApp) that the ROM left floating would slide down its
 * tile by locky. The switch stays off while one does. It is only used at all
 * when the user asks for it (Settings → Display): on some units the windows
 * then show lower than their tiles.
 */
object FreeformBar {
    private const val TAG = "FreeformBar"
    private const val SWITCH = "sys.freeform.fullscreen"
    private const val PREFS = "freeform_bar"
    private const val KEY_HIDE = "hide_unit_bar"

    /**
     * The bar is looked at every [CHECK_PAUSE_MS] once the switch is on, at
     * most [LOOKS] times; seen [CHECKS] times in a row, the unit has no switch.
     */
    private const val CHECKS = 3
    private const val LOOKS = 15
    private const val CHECK_PAUSE_MS = 2_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var started = false

    /**
     * Whether this is the QF firmware, the one with the switch. An emulator
     * counts too, so the setting and its shell calls can be tried there
     * without root (ro.qf.platform can only be set as root).
     */
    val supported: Boolean by lazy { !systemProperty("ro.qf.platform").isNullOrBlank() || isEmulator }

    private data class State(
        /** Top edge (screen px) of each window on the screen, by app. */
        val tops: Map<String, Int> = emptyMap(),
        /** The switch is on, with locky at [lockedTop]. */
        val on: Boolean = false,
        val lockedTop: Int? = null,
        /** The unit showed the bar with the switch on, or would not take it: left alone from then on. */
        val refused: Boolean = false,
        /** The user keeps the unit's bar on screen, as by default (Settings → Display). */
        val keep: Boolean = true,
        /** Apps floating inside their tile, which the switch would push down. */
        val insideFloating: Set<String> = emptySet()
    ) {
        /** The switch must stay off, whatever the windows on screen. */
        val held: Boolean get() = keep || insideFloating.isNotEmpty()
    }

    private val state = MutableStateFlow(State())

    /** True while docked windows bring the status bar up: the dashboard then lays out below it. */
    val forced: StateFlow<Boolean> = combine(PipAnchor.dockedPackages, state) { docked, s ->
        barForced(windows = docked.isNotEmpty(), hasSwitch = supported && !s.refused && !s.held, tops = s.tops.values, switchOn = s.on)
    }.stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * True while the switch keeps the bar off over a window on screen. That
     * window has the focus, and Android reads the bars to show from the window
     * in focus: the dashboard then asks with a flag of its own window, read
     * whatever has the focus.
     */
    val holding: StateFlow<Boolean> = combine(PipAnchor.dockedPackages, state) { docked, s -> s.on && s.tops.keys.any { it in docked } }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val _keepsBar = MutableStateFlow(true)

    /** Whether the user keeps the unit's top bar over app windows (Settings → Display). */
    val keepsBar: StateFlow<Boolean> = _keepsBar

    /** Set by the dashboard: only then does a bar on screen tell anything about the switch. */
    @Volatile
    var dashboardInFront = false

    fun start(context: Context) {
        if (started || !supported) return
        started = true
        val app = context.applicationContext
        val keep = !app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_HIDE, false)
        _keepsBar.value = keep
        state.value = state.value.copy(keep = keep)
        if (keep) scope.launch { lock.withLock { apply(app, state.value) } }
        scope.launch {
            // A window closed, by the tile or by the user, is no longer on screen.
            var hadWindows = false
            PipAnchor.dockedPackages.collect { docked ->
                // None yet is not none left: a tile may be about to open its window.
                if (docked.isNotEmpty() || hadWindows) lock.withLock {
                    val s = state.value
                    apply(app, s.copy(tops = s.tops.filterKeys { it in docked }), lastWindowGone = docked.isEmpty())
                }
                hadWindows = docked.isNotEmpty()
            }
        }
    }

    /**
     * [packageName]'s window is on the screen with its top edge at [top], or
     * about to be moved there: called before the move, so the window is never
     * drawn at the old edge in its new place.
     */
    suspend fun onScreen(context: Context, packageName: String, top: Int) {
        if (!supported) return
        lock.withLock {
            val s = state.value
            // The stock launcher's process sets the switch whenever it starts.
            val undone = s.on && systemProperty(SWITCH) == "0"
            apply(context.applicationContext, s.copy(tops = s.tops + (packageName to top), on = s.on && !undone))
        }
    }

    /** [packageName]'s window left the screen (parked on the hidden display). */
    suspend fun offScreen(context: Context, packageName: String) {
        if (!supported) return
        lock.withLock {
            val s = state.value
            if (packageName in s.tops) apply(context.applicationContext, s.copy(tops = s.tops - packageName))
        }
    }

    /** The user keeps the unit's top bar over app windows ([keep]), or lets it go. */
    fun keepBar(context: Context, keep: Boolean) {
        val app = context.applicationContext
        _keepsBar.value = keep
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_HIDE, !keep).apply()
        if (!supported) return
        scope.launch {
            lock.withLock {
                Log.i(TAG, if (keep) "the user keeps the status bar" else "the user lets the status bar go")
                apply(app, state.value.copy(keep = keep))
            }
        }
    }

    /**
     * [packageName], inside its tile, sits in a floating window ([floats]),
     * or no longer does: the switch would draw it lower in its tile.
     */
    suspend fun insideFloats(context: Context, packageName: String, floats: Boolean) {
        if (!supported) return
        lock.withLock {
            val s = state.value
            if ((packageName in s.insideFloating) == floats) return
            if (floats) Log.i(TAG, "$packageName floats inside its tile: the switch stays off")
            val inside = if (floats) s.insideFloating + packageName else s.insideFloating - packageName
            apply(context.applicationContext, s.copy(insideFloating = inside))
        }
    }

    /** Only from inside [lock]. Brings the switch in line with [wanted] and publishes it. */
    private suspend fun apply(context: Context, wanted: State, lastWindowGone: Boolean = false) {
        val top = sharedTopEdge(wanted.tops.values)
        state.value = try {
            when {
                // Off, even when the stock launcher turned it on as it started.
                wanted.held -> {
                    if (wanted.on || systemProperty(SWITCH) == "1") {
                        DockShell.shell(context, "setprop $SWITCH 0")
                        Log.i(TAG, "status bar left to Android: " + if (wanted.keep) "kept by the user" else "an app floats inside its tile")
                    }
                    wanted.copy(on = false, lockedTop = null)
                }
                wanted.refused -> wanted
                // One top edge for every window on screen: the bar can go.
                top != null -> if (wanted.on && wanted.lockedTop == top) wanted else {
                    val out = DockShell.shell(context, freeformSwitchOn(top)).trim()
                    if (out == "1") {
                        Log.i(TAG, "status bar kept off, windows' top edge at $top")
                        checkLater(context)
                        wanted.copy(on = true, lockedTop = top)
                    } else {
                        Log.w(TAG, "$SWITCH reads '$out' once set: the status bar is left to Android")
                        wanted.copy(on = false, lockedTop = null, refused = true)
                    }
                }
                // Windows on different rows, or none left at all: as Android has it.
                wanted.on && (wanted.tops.isNotEmpty() || lastWindowGone) -> {
                    DockShell.shell(context, "setprop $SWITCH 0")
                    Log.i(TAG, "status bar left to Android (${wanted.tops.size} windows on screen)")
                    wanted.copy(on = false, lockedTop = null)
                }
                else -> wanted
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The shell did not answer: the switch stays as it was, and the next poll asks again.
            Log.w(TAG, "could not set the freeform switch", e)
            wanted
        }
    }

    private var checking: Job? = null
    private var checked = false

    /**
     * Looks at the bar once the switch is on: a firmware without the switch
     * shows it regardless, over the top of the dashboard. Seen [CHECKS] times
     * in a row, the switch is given up and the dashboard lays out below the
     * bar as before. A unit that does not say counts as one that has it.
     */
    private fun checkLater(context: Context) {
        if (checked || checking?.isActive == true) return
        checking = scope.launch {
            var seen = 0
            repeat(LOOKS) {
                delay(CHECK_PAUSE_MS)
                // Nothing to judge by: no window on screen yet, or another app in front.
                if (!holding.value || !dashboardInFront) {
                    seen = 0
                    return@repeat
                }
                val dump = runCatching { DockShell.shell(context, "dumpsys window displays | grep -A 2 BarController.StatusBar") }
                    .getOrNull() ?: return@repeat
                when (statusBarShowing(dump)) {
                    true -> seen++
                    false -> {
                        checked = true
                        return@launch
                    }
                    // Up for a moment after a swipe: neither here nor there.
                    null -> if ("mState=" in dump) seen = 0 else {
                        checked = true
                        return@launch
                    }
                }
                if (seen >= CHECKS) {
                    checked = true
                    lock.withLock {
                        Log.w(TAG, "the status bar shows with $SWITCH on: left to Android from now on")
                        runCatching { DockShell.shell(context, "setprop $SWITCH 0") }
                        state.value = state.value.copy(on = false, lockedTop = null, refused = true)
                    }
                    return@launch
                }
            }
        }
    }

    @SuppressLint("PrivateApi")
    private fun systemProperty(name: String): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as String
    }.getOrNull()
}

/** The top edge every window on screen shares; null when they differ, or when none is on screen. */
internal fun sharedTopEdge(tops: Collection<Int>): Int? = tops.distinct().singleOrNull()

/**
 * Whether app windows bring the status bar up. Without the firmware's switch
 * ([hasSwitch]) any window does, docked or parked aside, as Android has it.
 * With it, only windows on screen ([tops], their top edges) that the switch
 * cannot cover: on different rows, or with the switch not on ([switchOn]).
 */
internal fun barForced(windows: Boolean, hasSwitch: Boolean, tops: Collection<Int>, switchOn: Boolean): Boolean = when {
    !windows -> false
    !hasSwitch -> true
    tops.isEmpty() -> false
    else -> sharedTopEdge(tops) == null || !switchOn
}

/**
 * The shell line that turns the firmware's switch on with every freeform
 * window's top edge at [top], and prints the switch as it then stands. The
 * left edge is left to each window (an empty lockx reads as "not set").
 */
internal fun freeformSwitchOn(top: Int): String =
    "setprop sys.freeform.locky $top; setprop sys.freeform.lockx ''; setprop sys.freeform.fullscreen 1; getprop sys.freeform.fullscreen"

/**
 * Whether the status bar is on screen by `dumpsys window displays` (its
 * "BarController.StatusBar" entry); null when the dump does not say, or while
 * the bar is only up for a moment after a swipe from the edge.
 */
internal fun statusBarShowing(dump: String): Boolean? {
    val lines = dump.lines().map { it.trim() }
    val at = lines.indexOfFirst { it == "BarController.StatusBar" }
    if (at < 0) return null
    val entry = lines.drop(at + 1).take(2)
    val state = entry.firstOrNull { it.startsWith("mState=") } ?: return null
    if (entry.any { it.startsWith("mTransientBar=") && it != "mTransientBar=TRANSIENT_BAR_NONE" }) return null
    return state == "mState=WINDOW_STATE_SHOWING"
}
