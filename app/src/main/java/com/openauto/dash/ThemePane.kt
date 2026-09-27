package com.openauto.dash

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Tonality
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt

/*
 * The Look settings: the themes as a gallery of small dashboards drawn with
 * each theme's own palette, so the choice is made on what the screen will
 * look like rather than on a name; then the day and night switch and the
 * effects switch. The bottom bar's auto-hide is here too, shown under Display.
 */

/** Day and night: which version of the theme shows, or the car's light sensor deciding. */
@Composable
internal fun AppearanceSetting(theme: ThemeState) {
    SettingsSection(stringResource(R.string.settings_appearance_title))
    SegmentedSwitch(
        options = DashAppearance.entries,
        chosen = theme.appearance,
        icon = { option ->
            when (option) {
                DashAppearance.AUTO -> Icons.Filled.BrightnessAuto
                DashAppearance.DARK -> Icons.Filled.DarkMode
                DashAppearance.LIGHT -> Icons.Filled.LightMode
            }
        },
        title = { stringResource(it.titleRes) },
        onChoose = theme.onAppearance
    )
    SwitchHint(
        stringResource(
            when (theme.appearance) {
                DashAppearance.AUTO -> R.string.dash_appearance_auto_hint
                DashAppearance.DARK -> R.string.dash_appearance_dark_hint
                DashAppearance.LIGHT -> R.string.dash_appearance_light_hint
            }
        )
    )
}

/**
 * Effects: how much halo and glass a theme draws. Off is the
 * high-legibility setting for a dim screen in full sun.
 */
@Composable
internal fun EffectsSetting(theme: ThemeState) {
    SettingsSection(stringResource(R.string.dash_effects_title))
    SegmentedSwitch(
        options = DashEffects.entries,
        chosen = theme.effects,
        icon = { option ->
            when (option) {
                DashEffects.NONE -> Icons.Filled.VisibilityOff
                DashEffects.REDUCED -> Icons.Filled.Tonality
                DashEffects.FULL -> Icons.Filled.AutoAwesome
            }
        },
        title = { stringResource(it.titleRes) },
        onChoose = theme.onEffects
    )
    SwitchHint(stringResource(theme.effects.hintRes))
}

/** The themes: three up front (the one in use first), the other eleven behind one tap. */
@Composable
internal fun ThemeGallery(theme: ThemeState) {
    val lead = (listOf(theme.mode) + StarterThemes).distinct().take(3)
    var showAll by remember { mutableStateOf(false) }
    ThemeGroup(stringResource(R.string.dash_theme_picker_title), lead, theme)
    val tap = rememberTapFeedback()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(DashShape.Medium)
            .clickable { tap(); showAll = !showAll }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (showAll) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null, tint = DashColors.Accent, modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(if (showAll) R.string.dash_theme_show_fewer else R.string.dash_theme_show_all, DashThemeMode.entries.size),
            color = DashColors.Accent, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium
        )
    }
    if (showAll) {
        Spacer(Modifier.height(12.dp))
        val skins = DashThemeMode.entries.filter { paletteFor(it, false).Skin != DashSkin.STANDARD && it !in lead }
        val colours = DashThemeMode.entries.filter { it !in lead && paletteFor(it, false).Skin == DashSkin.STANDARD }
        ThemeGroup(stringResource(R.string.dash_theme_group_colours), colours, theme)
        ThemeGroup(stringResource(R.string.dash_theme_group_skins), skins, theme)
    }
}

/**
 * The bottom bar: always there, or hiding itself once unused for the chosen
 * 0 to 20 seconds and coming back with a swipe up (BarAutoHide.kt).
 */
@Composable
internal fun BarAutoHideSetting(theme: ThemeState) {
    SettingsSection(stringResource(R.string.dash_bar_title))
    SettingsToggle(
        icon = Icons.Filled.VerticalAlignBottom,
        title = stringResource(R.string.dash_bar_auto_hide),
        detail = stringResource(R.string.dash_bar_auto_hide_detail),
        checked = theme.barAutoHide,
        onChange = theme.onBarAutoHide
    )
    if (!theme.barAutoHide) return
    // Follows the thumb while dragging; saved once it is let go.
    var seconds by remember(theme.barHideSeconds) { mutableFloatStateOf(theme.barHideSeconds.toFloat()) }
    val whole = seconds.roundToInt()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Timer, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Text(
            if (whole == 0) stringResource(R.string.dash_bar_hide_now) else stringResource(R.string.dash_bar_hide_after, whole),
            color = DashColors.TextPrimary,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            modifier = Modifier.widthIn(min = 170.dp)
        )
        Spacer(Modifier.width(12.dp))
        Slider(
            value = seconds,
            onValueChange = { seconds = it },
            onValueChangeFinished = { theme.onBarHideSeconds(seconds.roundToInt()) },
            valueRange = 0f..MAX_BAR_HIDE_SECONDS.toFloat(),
            steps = MAX_BAR_HIDE_SECONDS - 1,
            colors = SliderDefaults.colors(
                thumbColor = if (DashColors.Light) DashColors.Accent else Color.White,
                activeTrackColor = DashColors.Accent,
                inactiveTrackColor = DashColors.CardHi,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent
            ),
            modifier = Modifier.weight(1f)
        )
    }
    SwitchHint(stringResource(R.string.dash_bar_swipe_up_detail))
}

/** A titled group of themes, three to a row. */
@Composable
private fun ThemeGroup(title: String, modes: List<DashThemeMode>, theme: ThemeState) {
    SettingsSection(title)
    modes.chunked(3).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            row.forEach { mode ->
                ThemeCard(mode, mode == theme.mode, Modifier.weight(1f)) { theme.onMode(mode) }
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * One theme: a stamp of its dashboard in the version (day or night) on
 * screen now, its name and its one-line description. The stamp is drawn
 * by hand per skin (Orbit's ring, Cockpit's dial, Horizon's sky, Tape Deck's
 * cassette), so the fourteen cards can be told apart at arm's length.
 */
@Composable
internal fun ThemeCard(mode: DashThemeMode, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = DashShape.Medium
    val tap = rememberTapFeedback()
    val palette = paletteFor(mode, light = DashColors.Light)
    Column(
        modifier = modifier
            .clip(shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) DashColors.Accent else DashColors.Line, shape)
            .background(DashColors.CardHi.copy(alpha = DashColors.CardHi.alpha * if (selected) 0.65f else 0.3f))
            .clickable(role = Role.Button) { tap(); onClick() }
            .padding(8.dp)
    ) {
        ThemeStamp(palette, Modifier.fillMaxWidth().aspectRatio(2f).clip(DashShape.Small))
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(mode.titleRes),
                color = DashColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (selected) Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.dash_selected), tint = DashColors.Accent, modifier = Modifier.size(20.dp))
        }
        Text(
            stringResource(mode.descriptionRes),
            color = DashColors.TextSecondary,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** A dashboard the size of a stamp in [p]'s colours and the skin's own shapes. */
@Composable
private fun ThemeStamp(p: DashPalette, modifier: Modifier) {
    val digits = rememberTextMeasurer()
    val heroFont = if (p.Font == DashFont.CONDENSED) CondensedFamily else FontFamily.Default
    Canvas(modifier = modifier) {
        drawRect(Brush.linearGradient(p.BackgroundStops, start = Offset.Zero, end = Offset(size.width, size.height)))
        val w = size.width
        val h = size.height
        fun number(text: String, x: Float, y: Float, sizeSp: Float, color: Color, family: FontFamily = heroFont, weight: FontWeight = p.HeroWeight) {
            val layout = digits.measure(text, TextStyle(fontSize = sizeSp.sp, fontWeight = weight, fontFamily = family, color = color))
            drawText(layout, topLeft = Offset(x - layout.size.width / 2f, y - layout.size.height / 2f))
        }
        when (p.Skin) {
            DashSkin.ORBIT -> {
                // A ring gauge on the right, a record on the left, the pill bar below.
                val r = h * 0.30f
                drawCircle(p.CardHi, r, Offset(w * 0.70f, h * 0.42f), style = Stroke(r * 0.22f))
                drawArc(p.Accent, 135f, 200f, false, Offset(w * 0.70f - r, h * 0.42f - r), Size(r * 2, r * 2), style = Stroke(r * 0.22f, cap = StrokeCap.Round))
                number("87", w * 0.70f, h * 0.42f, 13f, p.TextPrimary)
                val disc = h * 0.26f
                drawCircle(Color(0xFF0E1015), disc, Offset(w * 0.28f, h * 0.44f))
                drawCircle(p.Muted.copy(alpha = 0.35f), disc * 0.7f, Offset(w * 0.28f, h * 0.44f), style = Stroke(1f))
                drawCircle(p.Accent2, disc * 0.28f, Offset(w * 0.28f, h * 0.44f))
                drawRoundRect(p.CardHi.copy(alpha = 0.9f), Offset(w * 0.30f, h * 0.80f), Size(w * 0.40f, h * 0.13f), CornerRadius(h * 0.07f))
            }
            DashSkin.COCKPIT -> {
                // Stitched leather: a chrome-ringed dial and an amber LCD strip.
                val r = h * 0.34f
                val c = Offset(w * 0.72f, h * 0.46f)
                drawCircle(Color(0xFF15120E), r, c)
                drawCircle(Color(0xFFC9CCD1), r, c, style = Stroke(r * 0.14f))
                for (i in 0..8) {
                    val a = Math.toRadians((135 + i * 33.75).toDouble())
                    val inner = r * 0.72f; val outer = r * 0.86f
                    drawLine(p.Accent, Offset(c.x + inner * Math.cos(a).toFloat(), c.y + inner * Math.sin(a).toFloat()), Offset(c.x + outer * Math.cos(a).toFloat(), c.y + outer * Math.sin(a).toFloat()), 1.5f)
                }
                val na = Math.toRadians(250.0)
                drawLine(p.Critical, c, Offset(c.x + r * 0.75f * Math.cos(na).toFloat(), c.y + r * 0.75f * Math.sin(na).toFloat()), 2.5f, StrokeCap.Round)
                drawCircle(Color(0xFFDDDDDD), r * 0.1f, c)
                drawRoundRect(Color(0xFF1A1206), Offset(w * 0.06f, h * 0.20f), Size(w * 0.36f, h * 0.30f), CornerRadius(3f))
                number("87", w * 0.24f, h * 0.35f, 12f, p.Accent, FontFamily.Monospace, FontWeight.Bold)
                drawRoundRect(Color(0xFF1A1206), Offset(w * 0.06f, h * 0.60f), Size(w * 0.36f, h * 0.16f), CornerRadius(3f))
                drawRoundRect(p.Accent.copy(alpha = 0.7f), Offset(w * 0.09f, h * 0.66f), Size(w * 0.22f, h * 0.04f), CornerRadius(2f))
            }
            DashSkin.HORIZON -> {
                // No cards: the sky, a horizon line, the speed in the air.
                drawLine(p.TextPrimary.copy(alpha = 0.5f), Offset(0f, h * 0.68f), Offset(w, h * 0.68f), 1.5f)
                drawRect(Color.Black.copy(alpha = if (p.Light) 0.08f else 0.35f), Offset(0f, h * 0.68f), Size(w, h * 0.32f))
                drawCircle(p.Accent2.copy(alpha = 0.55f), h * 0.16f, Offset(w * 0.78f, h * 0.30f))
                number("87", w * 0.30f, h * 0.40f, 18f, p.TextPrimary)
                drawLine(p.Accent, Offset(w * 0.14f, h * 0.84f), Offset(w * 0.86f, h * 0.84f), 2f, StrokeCap.Round)
            }
            DashSkin.TAPE_DECK -> {
                // A grid floor, a cassette, seven-segment digits.
                val grid = p.Accent.copy(alpha = 0.35f)
                for (i in 0..6) drawLine(grid, Offset(w * i / 6f, h * 0.55f), Offset(w * (0.5f + (i / 6f - 0.5f) * 2.4f), h), 1f)
                for (i in 0..3) { val y = h * (0.55f + 0.45f * (i / 3f) * (i / 3f)); drawLine(grid, Offset(0f, y), Offset(w, y), 1f) }
                drawRoundRect(Color(0xFFE8DEC8), Offset(w * 0.08f, h * 0.14f), Size(w * 0.42f, h * 0.34f), CornerRadius(3f))
                drawRoundRect(Color(0xFF1B1520), Offset(w * 0.14f, h * 0.26f), Size(w * 0.30f, h * 0.14f), CornerRadius(h * 0.07f))
                drawCircle(Color(0xFFE8DEC8), h * 0.05f, Offset(w * 0.21f, h * 0.33f))
                drawCircle(Color(0xFFE8DEC8), h * 0.05f, Offset(w * 0.37f, h * 0.33f))
                drawRoundRect(Color(0xFF120D18), Offset(w * 0.56f, h * 0.14f), Size(w * 0.36f, h * 0.34f), CornerRadius(3f))
                number("87", w * 0.74f, h * 0.31f, 14f, p.Accent, FontFamily.Monospace, FontWeight.Bold)
            }
            DashSkin.STANDARD -> {
                // The standard bar and two tiles; glass themes show through, bare ones draw no card.
                val cardFill = when {
                    p.Bare -> Color.Transparent
                    p.Glass -> p.Card.copy(alpha = 0.55f)
                    else -> p.Card
                }
                val barH = h * 0.16f
                drawRoundRect(if (p.Bar.alpha < 0.05f) Color.Transparent else p.Bar, Offset(w * 0.04f, h * 0.05f), Size(w * 0.92f, barH), CornerRadius(3f))
                if (p.BarStyle == DashBarStyle.CLUSTER) number("87", w * 0.5f, h * 0.13f, 8f, p.Accent, heroFont, FontWeight.Bold)
                else number("12:34", w * 0.5f, h * 0.13f, 6f, p.TextPrimary, FontFamily.Default, FontWeight.Bold)
                drawCircle(p.Good, 2f, Offset(w * 0.91f, h * 0.13f))
                val top = h * 0.27f
                val tileH = h * 0.66f
                val corner = CornerRadius(h * 0.06f)
                drawRoundRect(cardFill, Offset(w * 0.04f, top), Size(w * 0.46f, tileH), corner)
                drawRoundRect(p.Line, Offset(w * 0.04f, top), Size(w * 0.46f, tileH), corner, style = Stroke(1f))
                if (p.Glow > 0f) drawCircle(p.Accent.copy(alpha = 0.18f * p.Glow), h * 0.28f, Offset(w * 0.27f, top + tileH * 0.5f))
                number("87", w * 0.27f, top + tileH * 0.46f, 15f, p.Accent)
                drawRoundRect(cardFill, Offset(w * 0.54f, top), Size(w * 0.42f, tileH), corner)
                drawRoundRect(p.Line, Offset(w * 0.54f, top), Size(w * 0.42f, tileH), corner, style = Stroke(1f))
                val meters = listOf(p.Secondary to 0.7f, p.Tacho to 0.45f, p.Good to 0.85f)
                meters.forEachIndexed { i, (colour, f) ->
                    val y = top + tileH * (0.25f + 0.25f * i)
                    drawRoundRect(p.CardHi, Offset(w * 0.59f, y), Size(w * 0.32f, 3f), CornerRadius(2f))
                    drawRoundRect(colour, Offset(w * 0.59f, y), Size(w * 0.32f * f, 3f), CornerRadius(2f))
                }
            }
        }
    }
}

/** One line under a switch saying what the chosen segment does. */
@Composable
internal fun SwitchHint(text: String) {
    Text(
        text,
        color = DashColors.TextSecondary,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(start = 12.dp, top = 6.dp)
    )
}

/** Segmented switch over [options]; the chosen segment wears the accent gradient. */
@Composable
internal fun <T> SegmentedSwitch(
    options: List<T>,
    chosen: T,
    icon: (T) -> ImageVector,
    title: @Composable (T) -> String,
    onChoose: (T) -> Unit
) {
    val shape = DashShape.Medium
    val tap = rememberTapFeedback()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, DashColors.Line, shape)
            .background(DashColors.CardHi.copy(alpha = DashColors.CardHi.alpha * 0.5f), shape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEach { option ->
            val picked = option == chosen
            val segment = DashShape.Small
            val ink = if (picked) DashColors.OnAccent else DashColors.TextPrimary
            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = DashSize.TouchPrimary)
                    .clip(segment)
                    .then(if (picked) Modifier.background(DashColors.AccentBrush, segment) else Modifier)
                    .clickable { tap(); onChoose(option) }
                    .padding(horizontal = 6.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon(option), contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text(
                    title(option), color = ink, fontWeight = if (picked) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                )
            }
        }
    }
}
