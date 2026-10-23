package com.openauto.dash

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/*
 * Whole-design skins (Orbit, Cockpit, Horizon, Tape Deck, Canvas, and Futuristic,
 * Simple, Modern, Motorsport, Anime, Racing, Panorama, and Liquid, Expressive,
 * Kinetic, Glyph, Windshield, Terrain, and the car-free Ambient, Brutal, Clay,
 * Chrome, Spatial, Paper). The active skin's
 * file draws the page background, the top bar, the frame over a docked Maps
 * window and the main widgets; every other tile keeps its standard renderer on
 * the skin's (bare) palette.
 */

/**
 * What a skinned tile needs: the same values [TileContent] receives.
 *
 * The live readings arrive as [State]s and are only read through
 * [obdConnection] / [mediaState], so a dial of the adapter or a song change
 * recomposes just the tiles that show it, not every tile on the page. The car's
 * gauges are not here: a tile reads the keys it shows (rememberGauges), which
 * is also what makes the OBD poll ask for them.
 */
@Stable
internal class SkinTileEnv(
    val editing: Boolean,
    val appsByPackage: Map<String, AppEntry>,
    private val media: State<MediaState>,
    val mediaController: CarMediaController,
    val hasMediaAccess: Boolean,
    val context: Context,
    private val obdConnectionState: State<ObdConnectionState>,
    val onConnectObd: () -> Unit,
    val onPickDevice: () -> Unit,
    val onLaunchApp: (String) -> Unit,
    val onEditLaunchBar: () -> Unit
) {
    val mediaState: MediaState get() = media.value
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
        DashSkin.FUTURISTIC -> FuturisticTile(item, env)
        DashSkin.SIMPLE -> SimpleTile(item, env)
        DashSkin.MODERN -> ModernTile(item, env)
        DashSkin.MOTORSPORT -> MotorsportTile(item, env)
        DashSkin.ANIME -> AnimeTile(item, env)
        DashSkin.RACING -> RacingTile(item, env)
        DashSkin.PANORAMA -> PanoramaTile(item, env)
        DashSkin.LIQUID -> LiquidTile(item, env)
        DashSkin.EXPRESSIVE -> ExpressiveTile(item, env)
        DashSkin.KINETIC -> KineticTile(item, env)
        DashSkin.GLYPH -> GlyphTile(item, env)
        DashSkin.WINDSHIELD -> WindshieldTile(item, env)
        DashSkin.TERRAIN -> TerrainTile(item, env)
        DashSkin.AMBIENT -> AmbientTile(item, env)
        DashSkin.BRUTAL -> BrutalTile(item, env)
        DashSkin.CLAY -> ClayTile(item, env)
        DashSkin.CHROME -> ChromeTile(item, env)
        DashSkin.SPATIAL -> SpatialTile(item, env)
        DashSkin.PAPER -> PaperTile(item, env)
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
        // Canvas's own place for the buttons is the side rail (AutomotiveDashboard), which never
        // asks for a bar; with the rail turned off it gets the standard one, else Apps and ⋮ are gone.
        DashSkin.CANVAS -> StandardTopBar(m)
        DashSkin.FUTURISTIC -> FuturisticTopBar(m)
        DashSkin.SIMPLE -> SimpleTopBar(m)
        DashSkin.MODERN -> ModernTopBar(m)
        DashSkin.MOTORSPORT -> MotorsportTopBar(m)
        DashSkin.ANIME -> AnimeTopBar(m)
        DashSkin.RACING -> RacingTopBar(m)
        DashSkin.PANORAMA -> PanoramaTopBar(m)
        DashSkin.LIQUID -> LiquidTopBar(m)
        DashSkin.EXPRESSIVE -> ExpressiveTopBar(m)
        DashSkin.KINETIC -> KineticTopBar(m)
        DashSkin.GLYPH -> GlyphTopBar(m)
        DashSkin.WINDSHIELD -> WindshieldTopBar(m)
        DashSkin.TERRAIN -> TerrainTopBar(m)
        DashSkin.AMBIENT -> AmbientTopBar(m)
        DashSkin.BRUTAL -> BrutalTopBar(m)
        DashSkin.CLAY -> ClayTopBar(m)
        DashSkin.CHROME -> ChromeTopBar(m)
        DashSkin.SPATIAL -> SpatialTopBar(m)
        DashSkin.PAPER -> PaperTopBar(m)
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
    DashSkin.FUTURISTIC -> futuristicBackground()
    DashSkin.SIMPLE -> simpleBackground()
    DashSkin.MODERN -> modernBackground()
    DashSkin.MOTORSPORT -> motorsportBackground()
    DashSkin.ANIME -> animeBackground()
    DashSkin.RACING -> racingBackground()
    DashSkin.PANORAMA -> panoramaBackground()
    DashSkin.LIQUID -> liquidBackground()
    DashSkin.EXPRESSIVE -> expressiveBackground()
    DashSkin.KINETIC -> kineticBackground()
    DashSkin.GLYPH -> glyphBackground()
    DashSkin.WINDSHIELD -> windshieldBackground()
    DashSkin.TERRAIN -> terrainBackground()
    DashSkin.AMBIENT -> ambientBackground()
    DashSkin.BRUTAL -> brutalBackground()
    DashSkin.CLAY -> clayBackground()
    DashSkin.CHROME -> chromeBackground()
    DashSkin.SPATIAL -> spatialBackground()
    DashSkin.PAPER -> paperBackground()
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
        DashSkin.FUTURISTIC -> FuturisticWindowFrame(modifier)
        DashSkin.SIMPLE -> SimpleWindowFrame(modifier)
        DashSkin.MODERN -> ModernWindowFrame(modifier)
        DashSkin.MOTORSPORT -> MotorsportWindowFrame(modifier)
        DashSkin.ANIME -> AnimeWindowFrame(modifier)
        DashSkin.RACING -> RacingWindowFrame(modifier)
        DashSkin.PANORAMA -> PanoramaWindowFrame(modifier)
        DashSkin.LIQUID -> LiquidWindowFrame(modifier)
        DashSkin.EXPRESSIVE -> ExpressiveWindowFrame(modifier)
        DashSkin.KINETIC -> KineticWindowFrame(modifier)
        DashSkin.GLYPH -> GlyphWindowFrame(modifier)
        DashSkin.WINDSHIELD -> WindshieldWindowFrame(modifier)
        DashSkin.TERRAIN -> TerrainWindowFrame(modifier)
        DashSkin.AMBIENT -> AmbientWindowFrame(modifier)
        DashSkin.BRUTAL -> BrutalWindowFrame(modifier)
        DashSkin.CLAY -> ClayWindowFrame(modifier)
        DashSkin.CHROME -> ChromeWindowFrame(modifier)
        DashSkin.SPATIAL -> SpatialWindowFrame(modifier)
        DashSkin.PAPER -> PaperWindowFrame(modifier)
        DashSkin.STANDARD -> Unit
    }
}

/**
 * What a bare skin puts behind a widget it does not redraw, so a page keeps
 * one look and the widget's words have something under them instead of the
 * skin's scenery: the skin's own panel where it has one, else its card colour
 * in its corners. Orbit and Horizon, whose widgets float on the scene, get a
 * lighter veil of it.
 */
@Composable
internal fun SkinFallbackPanel(content: @Composable () -> Unit) {
    val panel = when (DashColors.Skin) {
        DashSkin.RACING -> return RacingFallbackPanel(content)
        DashSkin.ANIME -> return AnimeFallbackPanel(content)
        DashSkin.FUTURISTIC -> Modifier.futPanel(DashColors.Accent).padding(6.dp)
        DashSkin.SIMPLE -> Modifier.simpleGlass()
        DashSkin.PANORAMA -> Modifier.panoFallbackPanel()
        DashSkin.LIQUID -> Modifier.liquidFallbackPanel()
        DashSkin.EXPRESSIVE -> Modifier.expressiveFallbackPanel()
        DashSkin.KINETIC -> Modifier.kineticFallbackPanel()
        DashSkin.GLYPH -> Modifier.glyphFallbackPanel()
        DashSkin.WINDSHIELD -> Modifier.windshieldFallbackPanel()
        DashSkin.TERRAIN -> Modifier.terrainFallbackPanel()
        DashSkin.AMBIENT -> Modifier.ambientFallbackPanel()
        DashSkin.BRUTAL -> Modifier.brutalFallbackPanel()
        DashSkin.CLAY -> Modifier.clayFallbackPanel()
        DashSkin.CHROME -> Modifier.chromeFallbackPanel()
        DashSkin.SPATIAL -> Modifier.spatialFallbackPanel()
        DashSkin.PAPER -> Modifier.paperFallbackPanel()
        DashSkin.MODERN -> Modifier.modernGlass()
        DashSkin.MOTORSPORT -> Modifier.msPanel()
        DashSkin.TAPE_DECK -> Modifier.tapeDeckPanel().padding(4.dp)
        DashSkin.ORBIT, DashSkin.HORIZON -> Modifier.plainSkinPanel(alpha = 0.55f)
        DashSkin.COCKPIT -> Modifier.plainSkinPanel(alpha = 0.92f)
        DashSkin.CANVAS, DashSkin.STANDARD -> Modifier
    }
    Box(Modifier.fillMaxSize().then(panel)) { content() }
}

/** The palette's card in the skin's tile corners, with its hairline rim. */
@Composable
private fun Modifier.plainSkinPanel(alpha: Float): Modifier {
    val shape = skinChrome().shapes.medium
    return clip(shape)
        .background(DashColors.Card.copy(alpha = DashColors.Card.alpha * alpha), shape)
        .border(1.dp, DashColors.Line, shape)
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
            BuiltinKind.TELEMETRY -> ObdCard(env.obdConnection, env.onConnectObd, env.onPickDevice, full)
            BuiltinKind.SPEED_HUD -> SpeedHudCard(env.obdConnection == ObdConnectionState.CONNECTED, full)
            BuiltinKind.MEDIA -> MediaCard(env.mediaState, env.mediaController, env.hasMediaAccess, env.context, full)
            BuiltinKind.NAVIGATION -> DirectionsCard(env.hasMediaAccess, env.context, full)
            BuiltinKind.CLOCK -> ClockCard(full)
            BuiltinKind.WEATHER -> WeatherCard(full)
            BuiltinKind.RANGE -> RangeCard(env.obdConnection, env.onConnectObd, full)
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

/** A skin's corners: [large] for sheets and big cards, [medium] for tiles, [small] for chips and pills. */
internal fun skinShapes(large: Int, medium: Int, small: Int) = shapes(large, medium, small)

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
    DashSkin.FUTURISTIC -> SkinChrome(FuturisticShapes)
    DashSkin.SIMPLE -> SkinChrome(SimpleShapes)
    DashSkin.MODERN -> SkinChrome(ModernShapes)
    DashSkin.MOTORSPORT -> SkinChrome(MotorsportShapes)
    DashSkin.ANIME -> SkinChrome(AnimeShapes)
    DashSkin.RACING -> SkinChrome(RacingShapes)
    DashSkin.PANORAMA -> SkinChrome(PanoramaShapes)
    DashSkin.LIQUID -> SkinChrome(LiquidShapes)
    DashSkin.EXPRESSIVE -> SkinChrome(ExpressiveShapes)
    DashSkin.KINETIC -> SkinChrome(KineticShapes)
    DashSkin.GLYPH -> SkinChrome(GlyphShapes)
    DashSkin.WINDSHIELD -> SkinChrome(WindshieldShapes)
    DashSkin.TERRAIN -> SkinChrome(TerrainShapes)
    DashSkin.AMBIENT -> SkinChrome(AmbientShapes)
    DashSkin.BRUTAL -> SkinChrome(BrutalShapes)
    DashSkin.CLAY -> SkinChrome(ClayShapes)
    DashSkin.CHROME -> SkinChrome(ChromeShapes)
    DashSkin.SPATIAL -> SkinChrome(SpatialShapes)
    DashSkin.PAPER -> SkinChrome(PaperShapes)
    DashSkin.STANDARD -> SkinChrome(StandardShapes)
}

/** Draws a number centred on (x, y) at sizeSp in the stamp's hero face (ThemePane's theme stamps). */
internal typealias StampNumber = (text: String, x: Float, y: Float, sizeSp: Float, color: androidx.compose.ui.graphics.Color) -> Unit
