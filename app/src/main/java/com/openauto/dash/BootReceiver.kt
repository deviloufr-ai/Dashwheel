package com.openauto.dash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * The unit started: Dashwheel takes the screen when it is the default Home.
 * Many head unit firmwares start their own launcher at boot whatever Android's
 * default Home is, and only the QF firmware says when the ignition comes on
 * ([CarPower]); elsewhere the stock launcher stayed in front until the Home
 * key was pressed. Here, as at the QF's ignition on, the dashboard is put back
 * in front for the first seconds, until the driver touches or presses
 * something ([EmbeddedApp.bootedUp]).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in BOOT_ACTIONS) return
        // Which launcher came up first, and who started it, for a bug report: gone from the log soon after.
        DebugLog.snapshotLater(context, "a minute after boot", BOOT_SNAPSHOT_MS)
        if (!isDefaultHome(context)) {
            DebugLog.note(context, "boot: Dashwheel is not Android's default Home")
            return
        }
        DebugLog.note(context, "boot: Dashwheel is the default Home, taking the screen")
        EmbeddedApp.bootedUp(context.applicationContext)
        // A QF unit then starts Dashwheel itself from the next boot on.
        UnitLauncher.start(context)
    }

    private fun isDefaultHome(context: Context): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == context.packageName
    }

    private companion object {
        const val BOOT_SNAPSHOT_MS = 60_000L


        /** Android's own, and the "fast boot" some units send when they wake rather than start. */
        val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )
    }
}
