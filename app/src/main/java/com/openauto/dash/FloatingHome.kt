package com.openauto.dash

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Which of Dashwheel's two home screens is the one: [MainActivity], or on a
 * unit that made the dashboard a floating window, [FullscreenHomeActivity],
 * which Android can't make one (it is not resizable). The switch is made once
 * and stays: the component's enabled state is kept by Android.
 */
internal object FloatingHome {
    private const val TAG = "FloatingHome"

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private fun fullscreen(context: Context) = ComponentName(context, FullscreenHomeActivity::class.java)

    /** Whether the not-resizable home screen is the one on this unit. */
    fun active(context: Context): Boolean = runCatching {
        context.packageManager.getComponentEnabledSetting(fullscreen(context)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }.getOrDefault(false)

    /** The home screen to start, to make Android's Home, or to name to the unit. */
    fun component(context: Context): ComponentName =
        if (active(context)) fullscreen(context) else ComponentName(context, MainActivity::class.java)

    /**
     * [activity] started as Home while the not-resizable one is the home
     * screen: that one starts instead, with the same intent. True when it did.
     */
    fun redirect(activity: Activity): Boolean {
        val intent = activity.intent ?: return false
        if (activity is FullscreenHomeActivity || !intent.hasCategory(Intent.CATEGORY_HOME) || !active(activity)) return false
        runCatching {
            activity.startActivity(Intent(intent).setComponent(fullscreen(activity)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.w(TAG, "the fullscreen home did not start", it); return false }
        activity.finishAndRemoveTask()
        return true
    }

    /**
     * The dashboard was found a floating window: from now on the
     * not-resizable home screen is the one. It starts as Home (closing this
     * one), then [MainActivity] is switched off: with two home screens in
     * one package Android asks which one at every Home press. Android's Home
     * is pointed at the new one through the privileged shell when there is
     * one; the firmware names Home by package, so it finds it by itself. The
     * floating home screen Android already made stays until the unit restarts.
     * Without a shell nothing is switched: Android drops the driver's Home
     * choice when its home screen is switched off, and only the shell can
     * point it at the new one.
     */
    fun switchOn(activity: MainActivity) {
        if (activity is FullscreenHomeActivity || active(activity)) return
        val app = activity.applicationContext
        val target = fullscreen(app)
        val pm = app.packageManager
        scope.launch {
            // At start the shell's probe may not have answered yet.
            val access = withTimeoutOrNull(PROBE_WAIT_MS) { PrivilegedShell.access.first { it != PrivilegedShell.Access.UNKNOWN } }
            if (active(app)) return@launch
            if (Edition.play || access?.shell != true) {
                DebugLog.note(app, "home is a floating window: no shell to hand Home over, left as it is")
                return@launch
            }
            val enabled = runCatching {
                pm.setComponentEnabledSetting(target, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            }.isSuccess
            if (!enabled) {
                DebugLog.note(app, "home is a floating window: the fullscreen home could not be switched on")
                return@launch
            }
            runCatching {
                app.startActivity(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setComponent(target)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure { Log.w(TAG, "the fullscreen home did not start", it) }
            // Once the new one has closed this one ([MainActivity.onlyOne]).
            delay(HAND_OVER_MS)
            val off = runCatching {
                pm.setComponentEnabledSetting(ComponentName(app, MainActivity::class.java), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            }.isSuccess
            val home = runCatching {
                DockShell.shell(app, "cmd package set-home-activity ${target.flattenToString()}").trim()
            }.getOrElse { "failed: ${it.message}" }
            DebugLog.note(app, "home is a floating window: switched to the fullscreen home (old one off: $off, Home: $home), restart the unit once")
        }
    }

    /** Time for the new home screen to come up and close the old one. */
    private const val HAND_OVER_MS = 3_000L

    /** How long the shell's probe gets to answer before the switch is given up. */
    private const val PROBE_WAIT_MS = 30_000L
}
