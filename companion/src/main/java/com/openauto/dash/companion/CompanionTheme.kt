package com.openauto.dash.companion

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The companion's look: the launcher's night-drive colours (deep navy, its
 * blue and teal) on softly raised cards, so both apps read as one product.
 */
internal object CompanionColors {
    val Background = Color(0xFF0B1020)
    val Surface = Color(0xFF121A2C)
    val SurfaceHigh = Color(0xFF18223A)
    val Line = Color(0xFF26324F)
    val Blue = Color(0xFF6C9BFF)
    val Teal = Color(0xFF2DD4BF)
    val Amber = Color(0xFFF5B942)
    val Red = Color(0xFFF87171)
    val Text = Color(0xFFE8ECF6)
    val Muted = Color(0xFF93A0BC)
}

private val scheme = darkColorScheme(
    primary = CompanionColors.Blue,
    onPrimary = CompanionColors.Background,
    primaryContainer = Color(0xFF1C2E5A),
    onPrimaryContainer = CompanionColors.Text,
    secondary = CompanionColors.Teal,
    onSecondary = CompanionColors.Background,
    secondaryContainer = Color(0xFF123A3A),
    onSecondaryContainer = CompanionColors.Text,
    tertiary = CompanionColors.Amber,
    onTertiary = CompanionColors.Background,
    error = CompanionColors.Red,
    background = CompanionColors.Background,
    onBackground = CompanionColors.Text,
    surface = CompanionColors.Background,
    onSurface = CompanionColors.Text,
    surfaceVariant = CompanionColors.SurfaceHigh,
    onSurfaceVariant = CompanionColors.Muted,
    surfaceContainerLowest = CompanionColors.Background,
    surfaceContainerLow = CompanionColors.Surface,
    surfaceContainer = CompanionColors.Surface,
    surfaceContainerHigh = CompanionColors.SurfaceHigh,
    surfaceContainerHighest = CompanionColors.SurfaceHigh,
    outline = CompanionColors.Line,
    outlineVariant = CompanionColors.Line
)

private val shapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp)
)

@Composable
internal fun CompanionTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, shapes = shapes, content = content)
}

/** The companion's card: one raised surface, no border, large corners. */
@Composable
internal fun Panel(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = CardDefaults.cardColors(containerColor = CompanionColors.Surface)
    val shape = MaterialTheme.shapes.large
    if (onClick != null) Card(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = shape, colors = colors, content = content)
    else Card(modifier = modifier.fillMaxWidth(), shape = shape, colors = colors, content = content)
}
