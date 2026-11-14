package com.openauto.dash

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/*
 * The bottom bar kept on screen over other apps (Settings › Look, Bottom bar).
 * Once an app covers the dashboard its bar is gone with it: the dashboard's
 * window is hidden, and nothing in it can draw over another app. A separate
 * overlay window at the screen's bottom edge can: it holds a slim bar of its
 * own — Back, the dashboard (Home), the clock and Recent apps — so the driver
 * always has a way back without hunting for the unit's keys. It sits over the
 * app's bottom edge (Android gives no way to shrink another app's window), is
 * taken away the moment the dashboard is back in front, and stays away while
 * the reverse view is up. Off until the driver turns it on; needs "display
 * over other apps", granted through the privileged shell where there is one,
 * else in the system settings.
 */
@OptIn(FlowPreview::class)
internal object BarOverApps {
    private const val TAG = "BarOverApps"
    private const val PREFS = "dash_bar"
    private const val KEY = "over_apps"

    private val _on = MutableStateFlow(false)
    val on: StateFlow<Boolean> = _on.asStateFlow()
    private var loaded = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false
    private var window: BarWindow? = null

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        _on.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
    }

    fun save(context: Context, on: Boolean) {
        _on.value = on
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }

    /** Follows the dashboard in and out of the front for as long as the app runs. */
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        load(app)
        scope.launch {
            combine(_on, MainActivity.started, ReverseView.covering) { on, shown, reversing -> on && !shown && !reversing }
                // A tap that opens an app stops the dashboard a moment later: no flash of the bar between.
                .debounce(300)
                .distinctUntilChanged()
                .collect { wanted -> apply(app, wanted) }
        }
    }

    private suspend fun apply(context: Context, wanted: Boolean) {
        if (!wanted) {
            window?.remove()
            return
        }
        if (!Settings.canDrawOverlays(context) && !PipAnchor.grantOverlayPermission(context)) {
            Log.w(TAG, "no permission to draw over other apps: the bar stays away")
            return
        }
        // The dashboard may have come back while the permission was granted.
        if (MainActivity.started.value || !_on.value) return
        val w = window ?: BarWindow(context).also { window = it }
        w.show()
    }

    /** Brings the dashboard back in front, on the page it was left on. */
    fun showDashboard(context: Context) {
        EmbeddedApp.userActed()
        val intent = Intent().setComponent(FloatingHome.component(context))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra(MainActivity.EXTRA_KEEP_PAGE, true)
        if (!context.launchSafely(intent)) Log.w(TAG, "the dashboard did not come back")
    }
}

/** The overlay window holding [OverAppsBar] at the bottom of the screen. */
private class BarWindow(private val context: Context) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null

    fun show() {
        if (view != null) return
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Taps on the bar only; the keys and every other touch stay with the app.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            title = "Dashwheel bottom bar"
        }
        // The window outlives any activity: it keeps its own lifecycle (ComposeOwners.kt), resumed while shown.
        val o = OverlayOwner()
        val v = ComposeView(context).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setContent { BarSize.Scaled { OverAppsBar() } }
        }
        runCatching { wm.addView(v, lp) }
            .onSuccess { view = v; owner = o; Log.i("BarOverApps", "bar shown over the app in front") }
            .onFailure { o.destroy(); Log.w("BarOverApps", "could not add the bar window", it) }
    }

    fun remove() {
        val v = view ?: return
        view = null
        runCatching { wm.removeViewImmediate(v) }.onFailure { Log.w("BarOverApps", "remove failed", it) }
        owner?.destroy()
        owner = null
    }
}

/** The slim bar over other apps: Back, the dashboard, the clock, Recent apps. */
@Composable
private fun OverAppsBar() {
    val context = LocalContext.current
    var clock by remember { mutableStateOf(currentClock()) }
    LaunchedEffect(Unit) {
        while (true) {
            clock = currentClock()
            // To the next minute's turn, so the time changes when the unit's own does.
            delay(60_000L - System.currentTimeMillis() % 60_000L)
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DashColors.Bar)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OverAppsButton(SteeringWheelAction.SYSTEM_BACK.icon, stringResource(R.string.wheel_action_back)) {
            if (!BarNavKeys.back()) BarOverApps.showDashboard(context)
        }
        OverAppsButton(Icons.Filled.Home, stringResource(R.string.wheel_action_home)) {
            BarOverApps.showDashboard(context)
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Text(
                clock,
                color = DashColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1
            )
        }
        OverAppsButton(SteeringWheelAction.RECENT_APPS.icon, stringResource(R.string.wheel_action_recents)) {
            if (!BarNavKeys.recents()) BarOverApps.showDashboard(context)
        }
    }
}

@Composable
private fun OverAppsButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    Box(
        modifier = Modifier
            .heightIn(min = DashSize.Bar)
            .widthIn(min = DashSize.Bar)
            .clip(DashShape.Medium)
            .clickable(role = Role.Button, onClickLabel = description) { tap(); onClick() }
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = DashColors.TextPrimary, modifier = Modifier.size(28.dp))
    }
}

/** Settings, Look, under the bar's heading: the bar kept over other apps. */
@Composable
internal fun BarOverAppsSetting() {
    val context = LocalContext.current
    BarOverApps.load(context)
    val on by BarOverApps.on.collectAsState()
    SettingsToggle(
        icon = Icons.Filled.PictureInPicture,
        title = stringResource(R.string.dash_bar_over_apps),
        detail = stringResource(R.string.dash_bar_over_apps_detail),
        checked = on
    ) { wanted ->
        BarOverApps.save(context, wanted)
        // No shell to grant it with (the Play edition): the driver allows it in the system settings.
        if (wanted && !Settings.canDrawOverlays(context) && Edition.play) {
            context.launchSafely(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
