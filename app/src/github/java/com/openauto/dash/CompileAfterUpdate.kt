package com.openauto.dash

import android.content.Context
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import kotlin.concurrent.thread

/**
 * Once per installed version, asks the system to compile the app ahead of
 * time from the startup profiles its libraries ship (Compose's above all).
 * Android only does that by itself in an idle-and-charging maintenance window
 * a head unit rarely has, so until then the dashboard runs through the slow
 * JIT on the unit's modest CPU. The priv-app install does it right away
 * (PrivApp.compileInBackground); this covers updates from the in-app
 * updater, which restart the app on the new version.
 */
internal object CompileAfterUpdate {
    private const val PREFS = "compile_after_update"
    private const val KEY_DONE_FOR = "version"
    /** Lets the profile installer write the profile on the first start first. */
    private const val DELAY_MS = 90_000L

    fun schedule(context: Context) {
        val app = context.applicationContext
        val version = runCatching {
            PackageInfoCompat.getLongVersionCode(app.packageManager.getPackageInfo(app.packageName, 0))
        }.getOrNull() ?: return
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getLong(KEY_DONE_FOR, -1L) == version) return
        // Only where the app already talks to adbd (docked windows, the system
        // install): its key is accepted there, so no "allow debugging" prompt
        // can pop up while driving.
        if (!File(app.filesDir, "adb/adbkey").exists()) return
        thread(isDaemon = true, name = "compile-after-update") {
            Thread.sleep(DELAY_MS)
            // One try per version, whatever happens: never a long compile at every start.
            prefs.edit().putLong(KEY_DONE_FOR, version).apply()
            // The shell user may run `cmd package compile`; no root needed, only adbd.
            val port = AdbInstaller.listeningPort() ?: return@thread
            runCatching {
                AdbInstaller.connect(app, port, readTimeoutMs = PrivApp.COMPILE_TIMEOUT_MS).use {
                    it.shell("cmd package compile -m speed-profile -f ${app.packageName}").allOutput
                }
            }
                .onSuccess { Log.i(TAG, "version $version: ${it.trim()}") }
                .onFailure { Log.w(TAG, "compile failed", it) }
        }
    }

    private const val TAG = "CompileAfterUpdate"
}
