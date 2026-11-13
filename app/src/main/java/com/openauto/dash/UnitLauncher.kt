package com.openauto.dash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The firmware's own launcher choice, kept on Dashwheel while Dashwheel is
 * Android's default Home ([UnitPlatform.adapter]'s [LauncherTakeover]).
 *
 * The QF firmware never asks Android which app is Home: every Home it starts
 * (at boot, for the Home key, and when the app in front closes or leaves the
 * screen) is the package named in `persist.sys.qf.launcher`, set from the
 * unit's own launcher picker. With Dashwheel only Android's default, the
 * stock launcher came up at boot before Dashwheel took the screen back, and
 * again whenever an app inside a tile came off the main screen (Waze after a
 * screen of its own). Its CarSettings service sets that choice for any app
 * sending `com.qf.action.SET_LAUNCHER` with the package name, as its picker
 * does; no root needed (QfLauncher). Once per process; a unit whose firmware
 * has no such choice has no takeover.
 *
 * NWD units (com.nwd.*) have no such property: they start their stock
 * launcher at boot, then reopen the app last started before the switch-off,
 * which was the app Dashwheel last put in a tile (Waze). What their "app
 * started" broadcasts carry is noted for a bug report, to learn how to make
 * that app Dashwheel.
 */
internal object UnitLauncher {
    private const val TAG = "UnitLauncher"

    /** How long the firmware is given before the choice is read back, for the log. */
    private const val SETTLE_MS = 2_000L

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    @Volatile private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        watchNwd(app)
        val takeover = UnitPlatform.adapter.launcher
        val current = takeover?.ownChoice()
        // A firmware without a launcher choice of its own, or one that can't be read.
        if (takeover == null || current.isNullOrEmpty()) {
            DebugLog.note(app, "unit has no launcher choice of its own")
            return
        }
        if (current == app.packageName) return
        // The driver chose another Home in Android, or handed Home back: theirs to keep.
        if (!isDefaultHome(app) || HomeHandBack.released(app)) return
        HomeHandBack.rememberUnitLauncher(app, current)
        scope.launch {
            if (!takeover.claim(app)) return@launch
            UnitPlatform.markUsed(Cap.LAUNCHER_TAKEOVER)
            delay(SETTLE_MS)
            val now = takeover.ownChoice()
            val line = if (now == app.packageName) "unit's launcher was $current, now Dashwheel"
            else "unit's launcher is still $now after asking for Dashwheel"
            Log.i(TAG, line)
            DebugLog.note(app, line)
        }
    }

    /** The NWD firmware's broadcasts about the app started or shown, noted in the event log. */
    private val NWD_ACTIONS = listOf(
        "com.nwd.action.ACTION_START_ACTIVITY",
        "com.nwd.action.ACTION_START_NWD_ACTIVITY",
        "com.nwd.action.ACTION_APP_IN_OUT"
    )

    /** How many of them are noted per run: enough to see the pattern, not to fill the log. */
    private const val NWD_NOTES = 30

    private var nwdNoted = 0

    private fun watchNwd(app: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (nwdNoted >= NWD_NOTES) return
                nwdNoted++
                val extras = runCatching { intent.extras?.let { b -> b.keySet().joinToString { "$it=${b.get(it)}" } } }.getOrNull().orEmpty()
                val line = "nwd ${intent.action?.substringAfterLast('.')}: $extras"
                Log.i(TAG, line)
                DebugLog.note(context, line)
            }
        }
        val filter = IntentFilter().apply { NWD_ACTIONS.forEach(::addAction) }
        runCatching { ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED) }
    }

    private fun isDefaultHome(context: Context): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == context.packageName
    }
}
