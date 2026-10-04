package com.openauto.dash

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Point
import android.view.WindowManager
import androidx.annotation.StringRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/** Which way the dashboard stands: Auto follows the screen the unit has. */
enum class ScreenOrientation(@StringRes val titleRes: Int) {
    AUTO(R.string.settings_screen_auto),
    HORIZONTAL(R.string.settings_screen_horizontal),
    VERTICAL(R.string.settings_screen_vertical)
}

/** What a try-out's wait does next ([tryOutStep]). */
internal enum class TryOutStep { ASK, KEEP, REVERT }

/**
 * A new screen direction on trial, with [secondsLeft] of its wait to go. The
 * question shows once the screen has [turned], and unanswered the screen goes
 * back. A unit that does not turn for the new choice has nothing to undo: the
 * choice stays.
 */
internal fun tryOutStep(turned: Boolean, secondsLeft: Int): TryOutStep = when {
    secondsLeft > 0 -> TryOutStep.ASK
    turned -> TryOutStep.REVERT
    else -> TryOutStep.KEEP
}

/**
 * Wide head units (1280x720) and upright ones (Tesla-style, 768x1024...) get
 * a dashboard made for their shape. The shape is read from the display, so a
 * unit that is upright is laid out upright without being told; Horizontal and
 * Vertical turn the launcher that way, when the unit lets an app turn it.
 *
 * An upright screen has its own grid ([GRID_COLS] x [GRID_ROWS] swap) and its
 * own saved arrangement ([layoutPrefix]): tile positions made for one shape
 * make no sense on the other.
 */
object ScreenShape {
    private const val PREFS = "dashboard_layout"
    private const val KEY = "orientation"

    /** How long a new screen direction waits to be kept before it goes back. */
    internal const val KEEP_SECONDS = 15

    private val _choice = MutableStateFlow(ScreenOrientation.AUTO)
    val choice: StateFlow<ScreenOrientation> = _choice

    /**
     * True while the dashboard is laid out for a tall screen. Set by
     * [MainActivity] from the display before the dashboard composes, and the
     * dashboard is rebuilt whenever it flips, so the grid never changes shape
     * under a layout loaded for the other one.
     */
    @Volatile
    var vertical = false
        set(value) {
            val flipped = field != value
            field = value
            // The screen has just turned under a try-out: the wait to keep it starts from here.
            if (flipped && tryingFrom != null) countDown()
        }

    /**
     * A new direction being tried out: the choice to go back to unless the
     * driver keeps the new one (asked once the screen has turned). Nothing is
     * saved until then. Null while nothing is being tried.
     */
    private var tryingFrom: ScreenOrientation? = null

    /** The screen's shape when the try-out began. */
    private var verticalBefore = false

    /** The screen has turned since the try-out began: there is something to keep or undo. */
    private val turned: Boolean get() = vertical != verticalBefore

    /**
     * Seconds left to keep the direction on trial before it goes back by
     * itself; null while there is nothing to ask. Counted here, for the whole
     * process, so it runs on whatever the driver opens or closes meanwhile.
     */
    private val _keepSecondsLeft = MutableStateFlow<Int?>(null)
    val keepSecondsLeft: StateFlow<Int?> = _keepSecondsLeft

    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
    private var countdown: Job? = null

    /** The activity to turn back when the wait runs out, and where the choice is saved. */
    private var host = WeakReference<Activity>(null)
    private var app: Context? = null

    /** Set when a turn is undone: the dashboard the turn rebuilds opens Settings again. */
    @Volatile
    private var reopen = false

    /** Settings screens in composition: two for a moment, while a turn rebuilds the dashboard. */
    private var settingsOpen = 0

    /** Told by the Settings screen as it comes and goes. */
    fun settingsShown(shown: Boolean) {
        settingsOpen += if (shown) 1 else -1
    }

    /**
     * Whether the dashboard being built should open Settings on the screen's
     * own page: a turn is being tried out, or was just undone, and Settings
     * were open when the screen turned. With Settings closed the dashboard
     * asks instead. Asked once per build.
     */
    fun settingsWanted(): Boolean {
        val turning = tryingFrom != null || reopen
        reopen = false
        return turning && settingsOpen > 0
    }

    /** Saved layouts for an upright screen are kept under their own names. */
    val layoutPrefix: String get() = if (vertical) "_v" else ""

    fun load(context: Context) {
        app = context.applicationContext
        // A direction on trial is not saved yet: it stands until kept or undone.
        if (tryingFrom != null) return
        _choice.value = runCatching {
            ScreenOrientation.valueOf(
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY, ScreenOrientation.AUTO.name) ?: ScreenOrientation.AUTO.name
            )
        }.getOrDefault(ScreenOrientation.AUTO)
    }

    /**
     * Turns the screen to [orientation] on trial: [keep] makes it stay and
     * saves it, [revert] undoes it, and so does the wait running out
     * ([keepSecondsLeft]). A screen turned the wrong way is never saved.
     */
    fun tryOut(activity: Activity, orientation: ScreenOrientation) {
        if (orientation == _choice.value) return
        // Several changes in a row go back to where the first one started.
        val from = tryingFrom ?: _choice.value
        if (orientation == from) {
            revert()
            return
        }
        if (tryingFrom == null) {
            tryingFrom = from
            verticalBefore = vertical
        }
        _choice.value = orientation
        apply(activity)
        countDown()
    }

    /** The wait of a try-out, from the top ([tryOutStep]). */
    private fun countDown() {
        countdown?.cancel()
        countdown = scope.launch {
            var left = KEEP_SECONDS
            while (tryOutStep(turned, left) == TryOutStep.ASK) {
                _keepSecondsLeft.value = left.takeIf { turned }
                delay(1_000)
                left--
            }
            if (tryOutStep(turned, left) == TryOutStep.REVERT) revert() else keep()
        }
    }

    private fun endTryOut() {
        tryingFrom = null
        _keepSecondsLeft.value = null
        countdown?.cancel()
        countdown = null
    }

    /** The direction on screen stays, and is saved. */
    fun keep() {
        if (tryingFrom == null) return
        endTryOut()
        app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putString(KEY, _choice.value.name)?.apply()
    }

    /** Back to the direction the try-out started from. */
    fun revert() {
        val before = tryingFrom ?: return
        reopen = turned
        endTryOut()
        _choice.value = before
        host.get()?.let { apply(it) }
    }

    /** Asks for the chosen orientation; Auto leaves the screen as the unit has it. */
    fun apply(activity: Activity) {
        host = WeakReference(activity)
        activity.requestedOrientation = when (_choice.value) {
            ScreenOrientation.AUTO -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            ScreenOrientation.HORIZONTAL -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            ScreenOrientation.VERTICAL -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }

    /**
     * Whether the whole display (not our window: half of a wide screen in
     * split-screen is taller than wide) is taller than it is wide, as it
     * stands now.
     */
    fun displayIsVertical(context: Context): Boolean {
        val size = Point()
        @Suppress("DEPRECATION")
        context.getSystemService(WindowManager::class.java)?.defaultDisplay?.getRealSize(size) ?: return false
        return size.x < size.y
    }
}
