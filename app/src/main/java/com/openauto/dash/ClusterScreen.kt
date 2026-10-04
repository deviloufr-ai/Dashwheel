package com.openauto.dash

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.flow.StateFlow

/*
 * The second screen's cluster: a few of the dashboard's own widgets, big, one
 * page at a time, nothing to touch. Each page is laid out on the board in
 * Settings → Second screen (ClusterLayout), or as it comes. Drawn in ClusterPresentation on the
 * streamed display, so the Raspberry Pi shows exactly this.
 *
 * Its pages change only from outside (Settings, the tile, a steering-wheel
 * key). Nothing here animates on its own besides the page change: a still
 * picture costs the encoder, and the Wi-Fi, next to nothing.
 */

@Composable
internal fun ClusterScreen(page: StateFlow<ClusterPage>, pages: StateFlow<List<ClusterPage>>, overscanPct: Int) {
    val env = rememberClusterEnv()
    val config by SecondScreenStore.config.collectAsState()
    val shown by page.collectAsState()
    val all by pages.collectAsState()

    ClusterLook(config) { ClusterFrame(config, shown, all, overscanPct, env) }
}

@Composable
private fun ClusterFrame(config: SecondScreenConfig, shown: ClusterPage, all: List<ClusterPage>, overscanPct: Int, env: SkinTileEnv) {
    val palette = LocalClusterPalette.current
    BoxWithConstraints(Modifier.fillMaxSize().background(palette.Background)) {
        // A TV (composite) crops the edges it calls overscan.
        val inset: Dp = (minOf(maxWidth, maxHeight) * overscanPct / 100f) + 12.dp
        Box(Modifier.fillMaxSize().padding(inset)) {
            Crossfade(targetState = shown, animationSpec = tween(150), label = "cluster page") { p ->
                ClusterPageBody(config.layoutFor(p), env)
            }
        }
        if (all.size > 1) {
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = inset / 2),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                all.forEach {
                    Box(
                        Modifier.size(10.dp).clip(CircleShape)
                            .background(if (it == shown) palette.Accent else palette.Muted.copy(alpha = 0.5f))
                    )
                }
            }
        }
    }
}

/** The feeds the cluster's widgets read, as the dashboard's tiles do, with nothing to press. */
@Composable
internal fun rememberClusterEnv(): SkinTileEnv {
    val context = LocalContext.current
    val mediaController = remember { CarMediaController.shared(context) }
    val obd = ObdBluetoothManager.data.collectAsState()
    val obdConnection = ObdBluetoothManager.connectionState.collectAsState()
    val demo = DemoMode.active.collectAsState()
    val realMedia = mediaController.mediaState.collectAsState()
    val demoMedia = DemoMode.media.collectAsState()
    val media: State<MediaState> = remember { derivedStateOf { if (demo.value) demoMedia.value else realMedia.value } }
    return SkinTileEnv(
        editing = false,
        appsByPackage = emptyMap(),
        media = media,
        mediaController = mediaController,
        hasMediaAccess = remember { CarMediaController.hasNotificationAccess(context) },
        context = context,
        obd = obd,
        obdConnectionState = obdConnection,
        // Nothing on this screen can be touched.
        onConnectObd = {},
        onPickDevice = {},
        onLaunchApp = {},
        onEditLaunchBar = {}
    )
}

/**
 * One cluster page laid out as [layout] says. [overlay] draws over slot
 * [index] (the board's selection and taps); null on the second screen itself.
 * On the board's [preview] the 3D map is only named: a second live map in
 * Settings would cost as much as the one on the cluster.
 */
@Composable
internal fun ClusterPageBody(
    layout: ClusterLayout,
    env: SkinTileEnv,
    modifier: Modifier = Modifier,
    preview: Boolean = false,
    overlay: (@Composable BoxScope.(index: Int) -> Unit)? = null
) {
    val gap = 12.dp
    val slots = layout.slots
    @Composable
    fun Slot(index: Int, m: Modifier) {
        val slot = slots.getOrNull(index) ?: return
        Box(m) {
            when (slot.kind) {
                BuiltinKind.NAVMAP -> if (preview || isEmulator) PictureStandIn(Icons.Filled.Navigation, stringResource(BuiltinKind.NAVMAP.labelRes)) else ClusterMap()
                BuiltinKind.MAPS_INSIDE -> AppCopy(slot.app ?: ClusterLayouts.DEFAULT_APP)
                else -> Face(slot.kind, slot.design, env, Modifier.fillMaxSize())
            }
            overlay?.invoke(this, index)
        }
    }
    val big = 1.55f
    when (layout.arrangement) {
        ClusterArrangement.ONE -> Slot(0, modifier.fillMaxSize())
        ClusterArrangement.BIG_SIDE, ClusterArrangement.HALVES -> Row(modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            Slot(0, Modifier.weight(if (layout.arrangement == ClusterArrangement.BIG_SIDE) big else 1f).fillMaxHeight())
            Slot(1, Modifier.weight(1f).fillMaxHeight())
        }
        ClusterArrangement.BIG_STACK -> Row(modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            Slot(0, Modifier.weight(big).fillMaxHeight())
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gap)) {
                Slot(1, Modifier.weight(1f).fillMaxWidth())
                Slot(2, Modifier.weight(1f).fillMaxWidth())
            }
        }
        ClusterArrangement.THREE -> Row(modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            for (i in 0 until 3) Slot(i, Modifier.weight(1f).fillMaxHeight())
        }
        ClusterArrangement.GRID -> Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
            for (row in 0 until 2) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    Slot(row * 2, Modifier.weight(1f).fillMaxHeight())
                    Slot(row * 2 + 1, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

/**
 * The dashboard's own 3D map, drawn afresh for the cluster: the car, the
 * guided route and the next turn, nothing to touch (the map as a wallpaper).
 */
@Composable
private fun ClusterMap() {
    Box(Modifier.fillMaxSize().clip(DashShape.Medium).background(LocalClusterPalette.current.Card)) {
        MapLibrePanel(Modifier.fillMaxSize(), wallpaper = true)
        val nav by NavDirections.state.collectAsState()
        if (nav.active) {
            DirectionsBanner(nav = nav, modifier = Modifier.align(Alignment.TopCenter).padding(10.dp))
        }
    }
}

/**
 * A copy of [packageName]'s picture from its tile on the dashboard
 * ([EmbeddedApp.showCopy]): the same app, seen in both places, used on the
 * head unit. Says what is missing while there is nothing to copy, and keeps a
 * video app's picture away from the driver while the car moves.
 */
@Composable
private fun AppCopy(packageName: String) {
    val context = LocalContext.current
    val hosted by EmbeddedApp.hosted.collectAsState()
    val config by SecondScreenStore.config.collectAsState()
    val moving by SecondScreenController.isMoving.collectAsState()
    val label = remember(packageName) {
        runCatching { context.packageManager.run { getApplicationLabel(getApplicationInfo(packageName, 0)).toString() } }.getOrDefault(packageName)
    }
    val video = remember(packageName) {
        val category = runCatching { context.packageManager.getApplicationInfo(packageName, 0).category }.getOrNull()
        VideoApps.isVideo(packageName, category)
    }
    val held = packageName in hosted
    val waits = video && moving && !config.rearSeat
    Box(Modifier.fillMaxSize().clip(DashShape.Medium).background(LocalClusterPalette.current.Card)) {
        if (held && !waits) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    TextureView(ctx).apply {
                        var surface: Surface? = null
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                                texture.setDefaultBufferSize(width, height)
                                surface = Surface(texture).also { EmbeddedApp.showCopy(packageName, it) }
                            }

                            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                                texture.setDefaultBufferSize(width, height)
                            }

                            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                                // The relay lets go of the picture before it is freed.
                                surface?.let { EmbeddedApp.hideCopy(packageName, it); it.release() }
                                surface = null
                                return true
                            }

                            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                        }
                    }
                }
            )
        } else {
            PictureStandIn(
                Icons.Filled.Apps,
                stringResource(if (waits) R.string.second_screen_copy_waits else R.string.second_screen_copy_no_tile, label)
            )
        }
    }
}

/** What a live picture's slot shows instead of it: an icon and a line. */
@Composable
private fun PictureStandIn(icon: ImageVector, text: String) {
    Column(
        Modifier.fillMaxSize().clip(DashShape.Medium).background(LocalClusterPalette.current.Card).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, tint = LocalClusterPalette.current.Accent, modifier = Modifier.size(56.dp))
        Text(
            text, color = LocalClusterPalette.current.TextSecondary, textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp)
        )
    }
}

@Composable
private fun Face(kind: BuiltinKind, design: WidgetDesign, env: SkinTileEnv, modifier: Modifier) {
    val face = rememberWidgetFace(kind, env) ?: return
    // Actions are buttons on the dashboard; here nothing can be pressed.
    DesignedFace(face.copy(actions = emptyList(), onClick = null), design, modifier)
}
