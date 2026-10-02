package com.openauto.dash

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import kotlinx.coroutines.flow.StateFlow

/*
 * The second screen's cluster: a few of the dashboard's own widgets, big, one
 * page at a time, nothing to touch. Drawn in ClusterPresentation on the
 * streamed display, so the Raspberry Pi shows exactly this.
 *
 * Its pages change only from outside (Settings, the tile, a steering-wheel
 * key). Nothing here animates on its own besides the page change: a still
 * picture costs the encoder, and the Wi-Fi, next to nothing.
 */

@Composable
internal fun ClusterScreen(page: StateFlow<ClusterPage>, pages: StateFlow<List<ClusterPage>>, overscanPct: Int) {
    val context = LocalContext.current
    val mediaController = remember { CarMediaController.shared(context) }
    val obd = ObdBluetoothManager.data.collectAsState()
    val obdConnection = ObdBluetoothManager.connectionState.collectAsState()
    val demo = DemoMode.active.collectAsState()
    val realMedia = mediaController.mediaState.collectAsState()
    val demoMedia = DemoMode.media.collectAsState()
    val media: State<MediaState> = remember { derivedStateOf { if (demo.value) demoMedia.value else realMedia.value } }
    val env = SkinTileEnv(
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
    val shown by page.collectAsState()
    val all by pages.collectAsState()

    BoxWithConstraints(Modifier.fillMaxSize().background(DashColors.Background)) {
        // A TV (composite) crops the edges it calls overscan.
        val inset: Dp = (minOf(maxWidth, maxHeight) * overscanPct / 100f) + 12.dp
        val gap = 12.dp
        Box(Modifier.fillMaxSize().padding(inset)) {
            Crossfade(targetState = shown, animationSpec = tween(350), label = "cluster page") { p ->
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    when (p) {
                        ClusterPage.DRIVE -> {
                            Face(BuiltinKind.SPEED_HUD, WidgetDesign.ROAD_SIGN, env, 1.5f)
                            Side(gap) {
                                Face(BuiltinKind.CLOCK, WidgetDesign.HERO, env)
                                Face(BuiltinKind.RANGE, WidgetDesign.FUEL_TANK, env)
                            }
                        }
                        ClusterPage.MEDIA -> {
                            Face(BuiltinKind.MEDIA, WidgetDesign.HERO, env, 1.6f)
                            Side(gap) {
                                Face(BuiltinKind.SPEED_HUD, WidgetDesign.HERO, env)
                                Face(BuiltinKind.CLOCK, WidgetDesign.HERO, env)
                            }
                        }
                        ClusterPage.NAV -> {
                            Face(BuiltinKind.NAVIGATION, WidgetDesign.TURN_CARD, env, 1.6f)
                            Side(gap) {
                                Face(BuiltinKind.SPEED_HUD, WidgetDesign.HERO, env)
                                Face(BuiltinKind.CLOCK, WidgetDesign.HERO, env)
                            }
                        }
                        ClusterPage.OBD -> {
                            Face(BuiltinKind.TELEMETRY, WidgetDesign.TWIN_DIALS, env, 1.5f)
                            Side(gap) {
                                Face(BuiltinKind.OBD_ALL, WidgetDesign.PAPER, env)
                            }
                        }
                    }
                }
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
                            .background(if (it == shown) DashColors.Accent else DashColors.Muted.copy(alpha = 0.5f))
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.Face(kind: BuiltinKind, design: WidgetDesign, env: SkinTileEnv, weight: Float = 1f) {
    val face = rememberWidgetFace(kind, env) ?: return
    // Actions are buttons on the dashboard; here nothing can be pressed.
    DesignedFace(face.copy(actions = emptyList(), onClick = null), design, Modifier.weight(weight).fillMaxHeight())
}

@Composable
private fun ColumnScopeFace(kind: BuiltinKind, design: WidgetDesign, env: SkinTileEnv, modifier: Modifier) {
    val face = rememberWidgetFace(kind, env) ?: return
    DesignedFace(face.copy(actions = emptyList(), onClick = null), design, modifier)
}

/** The narrower column of a page, its faces stacked. */
@Composable
private fun RowScope.Side(gap: Dp, content: @Composable SideScope.() -> Unit) {
    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gap)) {
        SideScope(this).content()
    }
}

internal class SideScope(private val column: androidx.compose.foundation.layout.ColumnScope) {
    @Composable
    fun Face(kind: BuiltinKind, design: WidgetDesign, env: SkinTileEnv) {
        with(column) { ColumnScopeFace(kind, design, env, Modifier.weight(1f).fillMaxWidth()) }
    }
}
