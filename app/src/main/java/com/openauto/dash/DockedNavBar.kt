package com.openauto.dash

import android.content.Context
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/*
 * No navigation bar over the dashboard while an app sits in one of its window
 * tiles ([PipAnchor]). That app's window takes the focus, and Android shows
 * the bars the focused window asks for, which Dashwheel can't change on
 * another app's window. Android 10's own override does it: the global setting
 * policy_control, "immersive.navigation=<apps>", hides the navigation bar for
 * those apps (a swipe from the edge still brings it back). Written through
 * the privileged shell the windows are docked with, while windows are docked,
 * and put back as it was when the last one goes.
 */
@OptIn(FlowPreview::class)
object DockedNavBar {
    private const val TAG = "DockedNavBar"
    private const val KEY = "policy_control"
    private const val PREFS = "docked_nav_bar"
    /** What policy_control held before Dashwheel changed it ("" for nothing), kept until it's put back. */
    private const val KEY_BEFORE = "before"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        scope.launch {
            // Windows are docked and moved in bursts: settle first.
            PipAnchor.dockedPackages.debounce(800).distinctUntilChanged().collect { docked -> apply(app, docked) }
        }
    }

    private suspend fun apply(context: Context, docked: Set<String>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val changed = prefs.contains(KEY_BEFORE)
        runCatching {
            if (docked.isEmpty()) {
                if (!changed) return
                // What was saved goes into a root command: only a plain policy value
                // does (the file can come from a setup restored from another unit).
                val before = prefs.getString(KEY_BEFORE, "").orEmpty().takeIf(::isPlainPolicy).orEmpty()
                DockShell.shell(context, if (before.isEmpty()) "settings delete global $KEY" else "settings put global $KEY '$before'")
                prefs.edit().remove(KEY_BEFORE).apply()
                Log.i(TAG, "navigation bar policy put back")
            } else {
                if (!changed) prefs.edit().putString(KEY_BEFORE, current(context)).apply()
                val value = immersivePolicy(docked + context.packageName)
                DockShell.shell(context, "settings put global $KEY $value")
                Log.i(TAG, "navigation bar hidden for $value")
            }
        }.onFailure { Log.w(TAG, "could not set the navigation bar policy", it) }
    }

    private fun current(context: Context): String =
        runCatching { Settings.Global.getString(context.contentResolver, KEY) }.getOrNull()
            ?.takeIf { it.isNotBlank() && it != "null" }.orEmpty()
}

/** A policy_control value as Android writes them ("immersive.full=apps,-com.example"): nothing a shell would read as more. */
internal fun isPlainPolicy(value: String): Boolean = value.isNotEmpty() && value.all { it.isLetterOrDigit() || it in "._=,*:-" }

/** Android 10's policy_control value hiding the navigation bar for [packages], in a stable order. */
internal fun immersivePolicy(packages: Set<String>): String =
    "immersive.navigation=" + packages.filter { it.isNotBlank() }.sorted().joinToString(",")
