package com.openauto.dash

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/*
 * Gemini Live, the spoken conversation with Google's AI, as the dashboard's
 * companion: opened from the ⋮ menu or a steering-wheel button, in a side
 * panel dressed like the alerts. Gemini's own Live screen runs on a display
 * of its own (EmbeddedApp) shown in the panel, so its captions and buttons
 * work there. A second press, or the panel's close button, ends it.
 *
 * Live has no public way in: Gemini's home-screen widget opens it through an
 * activity of Gemini's that only the system or root may start, asking for
 * the "liveconv" feature. Without root, or without the right to show apps
 * inside Dashwheel, Gemini opens full screen as usual.
 */
internal object GeminiLive {
    const val GEMINI_PACKAGE = "com.google.android.apps.bard"

    /** Where Gemini sends its screens: closed to end Live for sure (it can go on in the background). */
    private const val GOOGLE_APP = "com.google.android.googlequicksearchbox"
    private const val LIVE = "am start -n $GEMINI_PACKAGE/.widget.RobinWidgetEntryPointActivity --es feature liveconv"
    private const val TAG = "GeminiLive"

    /** How long Gemini gets to show on the panel before Live is asked for. */
    private const val SHOW_WAIT_MS = 15_000L
    /** Gemini's first screen hands off to the Google app's ([EmbeddedApp]): left to settle first. */
    private const val SETTLE_MS = 2_500L
    /** Live's screen may open on the main screen: put back on the panel after this. */
    private const val PLACE_AFTER_MS = 1_500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var window: AlertWindow? = null
    private var starting: Job? = null
    /** The panel, as one of Gemini's tiles ([EmbeddedApp.Host.attach]). */
    private val panel = Any()

    fun available(context: Context): Boolean = isPackageInstalled(context, GEMINI_PACKAGE)

    /** Opens Live, or ends it when it is open: one button does both. Main thread. */
    fun toggle(context: Context) {
        if (window != null) end(context) else open(context)
    }

    private fun open(context: Context) {
        val app = context.applicationContext
        if (!available(app)) {
            HandsFree.say(app, R.string.ai_gemini_live_missing)
            return
        }
        val root = SystemInstaller.isRootAvailable()
        val w = AlertWindow(app, "gemini live", Gravity.TOP or Gravity.END)
        if (!root || !EmbeddedApp.allowed(app) || !w.canShow()) {
            fullScreen(app, root)
            return
        }
        val host = EmbeddedApp.host(app, GEMINI_PACKAGE)
        if (!w.show(AlertStyle.PANEL) { GeminiLivePanel(host, panel) { end(app) } }) {
            fullScreen(app, root)
            return
        }
        window = w
        starting = scope.launch { startLive(app, host) }
    }

    /** Gemini on the panel first, then Live on Gemini's display, then Live's screen kept there. */
    private suspend fun startLive(context: Context, host: EmbeddedApp.Host) {
        val id = withTimeoutOrNull(SHOW_WAIT_MS) {
            while (host.status.value != EmbeddedApp.Status.SHOWN || host.displayId == null) delay(200)
            host.displayId
        } ?: run {
            Log.w(TAG, "Gemini did not show on the panel: ${host.status.value}")
            return
        }
        delay(SETTLE_MS)
        shell(context, "$LIVE --display $id")
        delay(PLACE_AFTER_MS)
        host.bringBack()
    }

    /** Closes the panel and the conversation with it. */
    fun end(context: Context) {
        val app = context.applicationContext
        starting?.cancel()
        starting = null
        window?.hide()
        window = null
        val host = EmbeddedApp.host(app, GEMINI_PACKAGE)
        val elsewhere = host.heldBesides(panel)
        host.forget(panel)
        scope.launch {
            // Live goes on in the background once its screen is gone: the Google app is closed.
            shell(app, "am force-stop $GOOGLE_APP")
            // A Gemini tile on a dashboard opens it again; with none, its display goes.
            if (elsewhere) host.bringBack() else host.release()
        }
    }

    /** No panel possible: Live full screen with root, else Gemini as its icon opens it. */
    private fun fullScreen(context: Context, root: Boolean) {
        if (root) {
            scope.launch { shell(context, LIVE) }
            return
        }
        val intent = context.packageManager.getLaunchIntentForPackage(GEMINI_PACKAGE) ?: return
        context.launchSafely(intent)
    }

    private suspend fun shell(context: Context, cmd: String) {
        try {
            DockShell.shell(context, cmd)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$cmd failed", e)
        }
    }
}

/** The side panel: Live's own screen under a title row with the close button. */
@Composable
private fun GeminiLivePanel(host: EmbeddedApp.Host, panel: Any, onEnd: () -> Unit) {
    DisposableEffect(host, panel) {
        onDispose { host.forget(panel) }
    }
    val dpi = (LocalDensity.current.density * 160).toInt()
    AlertSurface(AlertStyle.PANEL) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = DashColors.Accent)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.ai_gemini_live), style = MaterialTheme.typography.titleMedium)
                }
                val end = stringResource(R.string.ai_gemini_live_end)
                Box(
                    modifier = Modifier.size(56.dp).clip(CircleShape).clickable(onClickLabel = end, onClick = onEnd),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Close, contentDescription = end)
                }
            }
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { ctx ->
                    // A TextureView, as on the dashboard's tiles (EmbeddedAppCard).
                    TextureView(ctx).apply {
                        var surface: Surface? = null
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                                texture.setDefaultBufferSize(width, height)
                                val s = Surface(texture).also { surface = it }
                                if (width > 0 && height > 0) {
                                    host.attach(panel, s, width, height, dpi)
                                    host.onScreen(panel, true)
                                }
                            }

                            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                                texture.setDefaultBufferSize(width, height)
                                val s = surface ?: return
                                if (width > 0 && height > 0) host.attach(panel, s, width, height, dpi)
                            }

                            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                                host.detach(panel)
                                surface?.release()
                                surface = null
                                return true
                            }

                            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                        }
                        // Live's own buttons (mute, end) take the touches; the dashboard's window is not involved.
                        setOnTouchListener { _, event ->
                            host.touch(event)
                            true
                        }
                    }
                }
            )
        }
    }
}
