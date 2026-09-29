package com.openauto.dash

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Two things an update undoes, put back at start once a privileged shell is
 * known ([PrivilegedShell]), once per process:
 *
 * - Dashwheel as the default Home app. Android drops the choice whenever the
 *   set of home apps changes, which an update does; the unit's Home key still
 *   found it (the firmware names its launcher), but Android's own Home then
 *   asked which app to use.
 * - The Magisk module's copy of Dashwheel ([SystemInstaller.installAsSystemApp]),
 *   the one Android falls back to if the installed update is ever removed.
 *   It is what keeps Dashwheel a system app, which the unit's background
 *   cleaner never stops; kept the same as the running version, the fallback
 *   is never an old one.
 */
internal object SystemUpkeep {
    private const val TAG = "SystemUpkeep"

    /** The module's APK, mounted at /system/priv-app/OpenAutoDash. */
    private const val MODULE = "/data/adb/modules/openautodash"
    private const val MODULE_APK = "$MODULE/system/priv-app/OpenAutoDash/OpenAutoDash.apk"

    /** Copying 30 MB on the unit's flash is slow, but must not hang forever. */
    private const val COPY_TIMEOUT_S = 120L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        scope.launch {
            val access = PrivilegedShell.access.first { it != PrivilegedShell.Access.UNKNOWN }
            if (access.shell) runCatching { keepHome(app) }.onFailure { Log.w(TAG, "default Home not set: ${it.message}") }
            if (access.root) runCatching { refreshModuleCopy(app) }.onFailure { Log.w(TAG, "module copy not refreshed: ${it.message}") }
        }
    }

    private suspend fun keepHome(context: Context) {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val current = context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        if (current == context.packageName) return
        val me = ComponentName(context, MainActivity::class.java).flattenToString()
        val out = DockShell.shell(context, "cmd package set-home-activity $me").trim()
        Log.i(TAG, "default Home was $current, set back to Dashwheel: $out")
    }

    /**
     * The running APK written beside the module's and renamed over it: the
     * copy mounted now is never changed under the system, the new one is
     * mounted at the next start. With the same version as the installed
     * update, Android goes on running the update.
     */
    private fun refreshModuleCopy(context: Context) {
        val out = RootShell.su(refreshScript(context.applicationInfo.sourceDir, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong()), COPY_TIMEOUT_S)
        val result = out.all.trim()
        if (result.contains("REFRESHED")) Log.i(TAG, "module copy refreshed to ${BuildConfig.VERSION_NAME}")
        else if (out.exit != 0) Log.w(TAG, "module copy not refreshed (${out.exit}): $result")
    }

    /** The root script for [refreshModuleCopy]: nothing without the module, nothing when already the same. */
    fun refreshScript(apk: String, versionName: String, versionCode: Long): String = """
        A=$MODULE_APK
        [ -f "${'$'}A" ] || { echo NOMODULE; exit 0; }
        cmp -s '$apk' "${'$'}A" && { echo SAME; exit 0; }
        CTX=${'$'}(ls -Z "${'$'}A" | cut -d' ' -f1)
        cp '$apk' "${'$'}A.new" || exit 41
        chmod 644 "${'$'}A.new"
        [ -n "${'$'}CTX" ] && chcon "${'$'}CTX" "${'$'}A.new" 2>/dev/null
        mv -f "${'$'}A.new" "${'$'}A" || { rm -f "${'$'}A.new"; exit 42; }
        sed -i 's/^version=.*/version=$versionName/; s/^versionCode=.*/versionCode=$versionCode/' $MODULE/module.prop
        sync
        echo REFRESHED
    """.trimIndent()
}
