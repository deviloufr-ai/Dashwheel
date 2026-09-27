package com.openauto.dash

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Point
import android.view.WindowManager
import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Which way the dashboard stands: Auto follows the screen the unit has. */
enum class ScreenOrientation(@StringRes val titleRes: Int) {
    AUTO(R.string.settings_screen_auto),
    HORIZONTAL(R.string.settings_screen_horizontal),
    VERTICAL(R.string.settings_screen_vertical)
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

    /**
     * A new direction being tried out: the choice to go back to unless the
     * driver keeps the new one (Settings asks once the screen has turned).
     * Null while nothing is being tried.
     */
    private val _tryingFrom = MutableStateFlow<ScreenOrientation?>(null)
    val tryingFrom: StateFlow<ScreenOrientation?> = _tryingFrom

    /** The screen's shape when the try-out began. */
    private var verticalBefore = false

    /** The screen has turned since the try-out began: there is something to keep or undo. */
    val turned: Boolean get() = vertical != verticalBefore

    /** Set when a turn is undone: the dashboard the turn rebuilds opens Settings again. */
    @Volatile
    private var reopen = false

    /**
     * Whether the dashboard being built should open Settings on the screen's
     * own page: a turn is being tried out, or was just undone. Asked once per build.
     */
    fun settingsWanted(): Boolean = (_tryingFrom.value != null) or reopen.also { reopen = false }

    /** Saved layouts for an upright screen are kept under their own names. */
    val layoutPrefix: String get() = if (vertical) "_v" else ""

    fun load(context: Context) {
        _choice.value = runCatching {
            ScreenOrientation.valueOf(
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY, ScreenOrientation.AUTO.name) ?: ScreenOrientation.AUTO.name
            )
        }.getOrDefault(ScreenOrientation.AUTO)
    }

    fun save(activity: Activity, orientation: ScreenOrientation) {
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, orientation.name).apply()
        _choice.value = orientation
        apply(activity)
    }

    /** Turns the screen to [orientation] on trial: [keep] makes it stay, [revert] undoes it. */
    fun tryOut(activity: Activity, orientation: ScreenOrientation) {
        if (orientation == _choice.value) return
        // Several changes in a row go back to where the first one started.
        if (_tryingFrom.value == null) {
            _tryingFrom.value = _choice.value
            verticalBefore = vertical
        }
        save(activity, orientation)
    }

    /** The direction on screen stays. */
    fun keep() {
        _tryingFrom.value = null
    }

    /** Back to the direction the try-out started from. */
    fun revert(activity: Activity) {
        val before = _tryingFrom.value ?: return
        reopen = turned
        _tryingFrom.value = null
        save(activity, before)
    }

    /** Asks for the chosen orientation; Auto leaves the screen as the unit has it. */
    fun apply(activity: Activity) {
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
