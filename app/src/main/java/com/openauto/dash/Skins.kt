package com.openauto.dash

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/*
 * Whole-design skins (Orbit, Cockpit, Horizon, Tape Deck, Canvas). The active skin's
 * file draws the page background, the top bar, the frame over a docked Maps
 * window and the main widgets; every other tile keeps its standard renderer on
 * the skin's (bare) palette.
 */

/**
 * What a skinned tile needs: the same values [TileContent] receives.
 *
 * The live readings arrive as [State]s and are only read through [obdData] /
 * [obdConnection] / [mediaState], so an OBD sample, a dial of the adapter or
 * a song change recomposes just the tiles that show it, not every tile on
 * the page.
 */
@Stable
internal class SkinTileEnv(
    val editing: Boolean,
    val appsByPackage: Map<String, AppEntry>,
    private val media: State<MediaState>,
    val mediaController: CarMediaController,
    val hasMediaAccess: Boolean,
    val context: Context,
    private val obd: State<ObdData>,
    private val obdConnectionState: State<ObdConnectionState>,
    val onConnectObd: () -> Unit,
    val onPickDevice: () -> Unit,
    val onLaunchApp: (String) -> Unit,
    val onEditLaunchBar: () -> Unit
) {
    val mediaState: MediaState get() = media.value
    val obdData: ObdData get() = obd.value
    val obdConnection: ObdConnectionState get() = obdConnectionState.value
}

/** Builtin widgets every skin redraws. App shortcuts and launch bars are redrawn too. */
internal val SKINNED_KINDS = setOf(
    BuiltinKind.TELEMETRY,
    BuiltinKind.SPEED_HUD,
    BuiltinKind.MEDIA,
    BuiltinKind.NAVIGATION,
    BuiltinKind.CLOCK,
    BuiltinKind.WEATHER,
    BuiltinKind.RANGE
)

/** True when the active skin draws [item] itself (via [SkinTile]). */
internal fun skinHandles(item: DashboardItem): Boolean = DashColors.Skin != DashSkin.STANDARD && when (item) {
    is DashboardItem.AppShortcut, is DashboardItem.LaunchBar -> true
    is DashboardItem.BuiltinWidget -> item.kind in SKINNED_KINDS
    else -> false
}

@Composable
internal fun SkinTile(item: DashboardItem, env: SkinTileEnv) {
    when (DashColors.Skin) {
        DashSkin.ORBIT -> OrbitTile(item, env)
        DashSkin.COCKPIT -> CockpitTile(item, env)
        DashSkin.HORIZON -> HorizonTile(item, env)
        DashSkin.TAPE_DECK -> TapeDeckTile(item, env)
        DashSkin.CANVAS -> CanvasTile(item, env)
        DashSkin.STANDARD -> Unit
    }
}

@Composable
internal fun SkinTopBar(m: TopBarModel) {
    when (DashColors.Skin) {
        DashSkin.ORBIT -> OrbitTopBar(m)
        DashSkin.COCKPIT -> CockpitTopBar(m)
        DashSkin.HORIZON -> HorizonTopBar(m)
        DashSkin.TAPE_DECK -> TapeDeckTopBar(m)
        // Canvas has no bar: its rail stands at the side (AutomotiveDashboard).
        DashSkin.CANVAS -> Unit
        DashSkin.STANDARD -> Unit
    }
}

/** The active skin's page background (whole screen, behind the bar and pages). */
@Composable
internal fun skinBackground(): Modifier = when (DashColors.Skin) {
    DashSkin.ORBIT -> orbitBackground()
    DashSkin.COCKPIT -> cockpitBackground()
    DashSkin.HORIZON -> horizonBackground()
    DashSkin.TAPE_DECK -> tapeDeckBackground()
    // The map is drawn under the whole dashboard instead (CanvasBackdrop).
    DashSkin.CANVAS -> Modifier
    DashSkin.STANDARD -> Modifier
}

/**
 * The frame the skin draws over a docked app window (Google Maps), filling
 * [modifier]'s box, which covers the window exactly. It never takes touches:
 * it only masks and decorates the edges and leaves the middle clear.
 */
@Composable
internal fun SkinWindowFrame(modifier: Modifier) {
    when (DashColors.Skin) {
        DashSkin.ORBIT -> OrbitWindowFrame(modifier)
        DashSkin.COCKPIT -> CockpitWindowFrame(modifier)
        DashSkin.HORIZON -> HorizonWindowFrame(modifier)
        DashSkin.TAPE_DECK -> TapeDeckWindowFrame(modifier)
        DashSkin.CANVAS -> Unit
        DashSkin.STANDARD -> Unit
    }
}

/**
 * The standard renderer for a skinned item: what a skin shows for a state it
 * does not redraw (for example fuel that has not been learned yet).
 */
@Composable
internal fun StandardSkinnedTile(item: DashboardItem, env: SkinTileEnv) {
    val full = Modifier.fillMaxSize()
    when (item) {
        is DashboardItem.AppShortcut -> Box(modifier = full, contentAlignment = Alignment.Center) {
            AppShortcutTile(
                app = env.appsByPackage[item.packageName],
                packageName = item.packageName,
                editing = env.editing,
                onClick = { env.onLaunchApp(item.packageName) }
            )
        }
        is DashboardItem.LaunchBar -> LaunchBarTile(item, env.appsByPackage, env.editing, env.onLaunchApp, env.onEditLaunchBar, full)
        is DashboardItem.BuiltinWidget -> when (item.kind) {
            BuiltinKind.TELEMETRY -> ObdCard(env.obdData, env.obdConnection, env.onConnectObd, env.onPickDevice, full)
            BuiltinKind.SPEED_HUD -> SpeedHudCard(env.obdData, env.obdConnection == ObdConnectionState.CONNECTED, full)
            BuiltinKind.MEDIA -> MediaCard(env.mediaState, env.mediaController, env.hasMediaAccess, env.context, full)
            BuiltinKind.NAVIGATION -> DirectionsCard(env.hasMediaAccess, env.context, full)
            BuiltinKind.CLOCK -> ClockCard(full)
            BuiltinKind.WEATHER -> WeatherCard(full)
            BuiltinKind.RANGE -> RangeCard(env.obdData, env.obdConnection, env.onConnectObd, full)
            else -> Unit
        }
        else -> Unit
    }
}

/**
 * The skin's chrome: what the shared pieces (dialogs, menus, the tiles being
 * arranged) borrow from it, so a skin does not end at its tiles. [shapes]
 * goes into MaterialTheme, so every stock dialog and menu takes the skin's
 * corners, and a tile's resize bracket follows its corner.
 */
@androidx.compose.runtime.Immutable
internal class SkinChrome(
    val shapes: androidx.compose.material3.Shapes
)

private fun shapes(large: Int, medium: Int, small: Int) = androidx.compose.material3.Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(small.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(small.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(medium.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(large.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(large.dp)
)

private val StandardShapes = shapes(24, 16, 10)
private val OrbitShapes = shapes(28, 22, 16)
private val CockpitShapes = shapes(8, 6, 4)
private val HorizonShapes = shapes(20, 14, 10)
private val TapeDeckShapes = shapes(4, 4, 2)
private val CanvasShapes = shapes(28, 20, 14)

/** The active skin's chrome, in the palette on screen now. */
internal fun skinChrome(): SkinChrome = when (DashColors.Skin) {
    DashSkin.ORBIT -> SkinChrome(OrbitShapes)
    DashSkin.COCKPIT -> SkinChrome(CockpitShapes)
    DashSkin.HORIZON -> SkinChrome(HorizonShapes)
    DashSkin.TAPE_DECK -> SkinChrome(TapeDeckShapes)
    DashSkin.CANVAS -> SkinChrome(CanvasShapes)
    DashSkin.STANDARD -> SkinChrome(StandardShapes)
}
