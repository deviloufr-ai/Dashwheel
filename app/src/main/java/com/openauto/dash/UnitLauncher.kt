package com.openauto.dash

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The QF firmware's own launcher choice, kept on Dashwheel while Dashwheel is
 * Android's default Home.
 *
 * That firmware never asks Android which app is Home: every Home it starts
 * (at boot, for the Home key, and when the app in front closes or leaves the
 * screen) is the package named in `persist.sys.qf.launcher`, set from the
 * unit's own launcher picker. With Dashwheel only Android's default, the
 * stock launcher came up at boot before Dashwheel took the screen back, and
 * again whenever an app inside a tile came off the main screen (Waze after a
 * screen of its own). Its CarSettings service sets that choice for any app
 * sending `com.qf.action.SET_LAUNCHER` with the package name, as its picker
 * does; no root needed. Once per process; other units have no such property.
 */
internal object UnitLauncher {
    private const val TAG = "UnitLauncher"
    private const val PROP = "persist.sys.qf.launcher"
    private const val ACTION = "com.qf.action.SET_LAUNCHER"
    private const val EXTRA_PACKAGE = "packageName"

    /** How long CarSettings is given before the choice is read back, for the log. */
    private const val SETTLE_MS = 2_000L

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    @Volatile private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        val current = systemProperty(PROP)
        // Not a QF unit, or already Dashwheel.
        if (current.isNullOrEmpty()) {
            DebugLog.note(app, "unit has no launcher choice of its own ($PROP)")
            return
        }
        if (current == app.packageName) return
        // The driver chose another Home in Android: theirs to keep.
        if (!isDefaultHome(app)) return
        runCatching { app.sendBroadcast(Intent(ACTION).putExtra(EXTRA_PACKAGE, app.packageName)) }
            .onFailure { Log.w(TAG, "can't ask the unit to start Dashwheel as its launcher", it); return }
        scope.launch {
            delay(SETTLE_MS)
            val now = systemProperty(PROP)
            val line = if (now == app.packageName) "unit's launcher was $current, now Dashwheel"
            else "unit's launcher is still $now after asking for Dashwheel"
            Log.i(TAG, line)
            DebugLog.note(app, line)
        }
    }

    private fun isDefaultHome(context: Context): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == context.packageName
    }

    @SuppressLint("PrivateApi")
    private fun systemProperty(name: String): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as String
    }.getOrNull()
}
