package com.openauto.dash

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor

/**
 * The second screen's own colours: black unless the driver picks another
 * background, and the same day and night unless they asked it to follow
 * (SecondScreenConfig). The dashboard's look and its day / night switch stay off it.
 */
internal val LocalClusterPalette = staticCompositionLocalOf { clusterPalette(ClusterBackground.BLACK) }

internal fun clusterPalette(background: ClusterBackground): DashPalette = when (background) {
    ClusterBackground.BLACK -> paletteFor(DashThemeMode.AUTO, light = false).copy(
        Background = Color.Black,
        BackgroundStops = listOf(Color.Black, Color.Black),
        Card = Color(0xFF101215),
        CardHi = Color(0xFF1B1E23)
    )
    ClusterBackground.DARK -> paletteFor(DashThemeMode.AUTO, light = false)
    ClusterBackground.LIGHT -> paletteFor(DashThemeMode.AUTO, light = true)
}

/** The tiles' theme look in [p]'s colours, on cards of their own. */
private fun clusterFaceLook(p: DashPalette): FaceLook = faceLook(FaceLookKind.THEME).copy(
    background = SolidColor(p.Card),
    ink = p.TextPrimary, dim = p.TextSecondary, accent = p.Accent, accent2 = p.Accent2,
    warn = p.Warning,
    track = if (p.Light) Color.Black.copy(alpha = 0.07f) else Color.White.copy(alpha = 0.10f),
    fill = p.CardHi,
    onAccent = if (p.Light) Color.White else p.Background,
    glow = null
)

/** [content] in the second screen's colours, as its settings say now. */
@Composable
internal fun ClusterLook(config: SecondScreenConfig, content: @Composable () -> Unit) {
    // Asked always (it is a composable), used only when the cluster follows day and night.
    val day = DashAppearance.AUTO.isLight()
    val background = SecondScreenRules.background(config, day)
    val palette = remember(background) { clusterPalette(background) }
    val look = remember(palette) { clusterFaceLook(palette) }
    CompositionLocalProvider(LocalClusterPalette provides palette, LocalThemeFaceLook provides look, content = content)
}
