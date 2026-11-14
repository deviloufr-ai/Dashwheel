package com.openauto.dash

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A native crash in a GL tile (the map, the 3D car, the Canvas map) or a bad
 * Android widget can't be caught, and takes Home down again at every start.
 * Each start of the dashboard is counted until it has held [HEALTHY_MS]; after
 * [SAFE_AFTER_STARTS] in a row that did not, this run is in safe mode: the GL
 * tiles ([gpuOk]) and the Android widgets ([appWidgetManager]) show their
 * stand-ins, and a banner offers to try again. Safe mode stays, start after
 * start, until the driver does.
 */
object SafeMode {
    private const val PREFS = "safe_mode"
    private const val KEY_STARTS = "unhealthy_starts"
    private const val HEALTHY_MS = 20_000L

    /** This run draws no GL tile and hosts no Android widget. Decided in [init], before anything is drawn. */
    @Volatile
    var on = false
        private set

    private val _banner = MutableStateFlow(false)
    /** The banner is up: safe mode, until dismissed. */
    val banner: StateFlow<Boolean> = _banner

    private val main = Handler(Looper.getMainLooper())
    private var counted = false
    /** "Try again" was tapped: its verdict is the timer's, not the old dashboard's stop. */
    private var retrying = false

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** From the application's start, before any tile exists. */
    fun init(context: Context) {
        val starts = prefs(context).getInt(KEY_STARTS, 0)
        on = safeModeAfter(starts)
        _banner.value = on
        if (on) DebugLog.note(context, "safe mode: $starts starts in a row did not hold; the maps, the 3D car and Android widgets are off")
    }

    /**
     * The dashboard is starting: counted once per process, written at once (the
     * crash this is for kills the process within seconds), and taken back once
     * it has held [HEALTHY_MS] after its first frame.
     */
    fun dashboardStarting(activity: Activity) {
        if (counted || on) return
        counted = true
        val app = activity.applicationContext
        val p = prefs(app)
        p.edit().putInt(KEY_STARTS, p.getInt(KEY_STARTS, 0) + 1).commit()
        activity.window.decorView.post { main.postDelayed({ healthy(app) }, HEALTHY_MS) }
    }

    /** The dashboard left the screen in one piece: what kills the app after that (the unit's sleep) is no crash. */
    fun dashboardStopped(context: Context) {
        if (!retrying) healthy(context)
    }

    private fun healthy(context: Context) {
        retrying = false
        if (!on) prefs(context).edit().remove(KEY_STARTS).apply()
    }

    fun dismiss() {
        _banner.value = false
    }

    /**
     * Everything back on, the dashboard built again. The count is kept until
     * this has held [HEALTHY_MS]: a crash now is safe mode again at the next start.
     */
    fun tryAgain(activity: Activity) {
        val app = activity.applicationContext
        on = false
        _banner.value = false
        retrying = true
        DebugLog.note(app, "safe mode: trying again with everything on")
        main.postDelayed({ healthy(app) }, HEALTHY_MS)
        activity.recreate()
    }
}

/** Starts in a row that did not hold before the next one is in safe mode. */
internal const val SAFE_AFTER_STARTS = 2

/** Whether a start is in safe mode, [unhealthyStarts] before it in a row not having held. */
internal fun safeModeAfter(unhealthyStarts: Int): Boolean = unhealthyStarts >= SAFE_AFTER_STARTS

/** Safe mode's word on the dashboard: what is off, "Retry" to put it back, and gone at a tap. */
@Composable
internal fun SafeModeBanner(modifier: Modifier = Modifier) {
    val shown by SafeMode.banner.collectAsState()
    if (!shown) return
    val activity = LocalContext.current as? Activity
    AlertSurface(AlertStyle.PILL, modifier = modifier.padding(top = 12.dp)) {
        Row(Modifier.padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.dash_safe_mode), color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { activity?.let(SafeMode::tryAgain) }) { Text(stringResource(R.string.ai_retry)) }
            TextButton(onClick = SafeMode::dismiss) { Text(stringResource(R.string.dash_dismiss)) }
        }
    }
}
