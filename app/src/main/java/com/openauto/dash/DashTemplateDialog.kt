package com.openauto.dash

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/** What the templates dialog settled on: the template, the pages it lays out, and whether those are replaced (else only the empty ones among them are filled). */
internal data class TemplateChoice(val template: DashTemplate, val pages: Set<Int>, val replace: Boolean)

/**
 * Picks a [DashTemplate]. Each card previews the template as it will really
 * be placed on [screen] (this layout, this adapter, this driver's side),
 * drawn as the cross of seven dashboards: the row of three by default, the
 * column above and below too when asked. With [onlyPage] (an empty page's
 * own Fill) the template goes on that page alone. [onApply] gets the
 * [TemplateChoice].
 */
@Composable
internal fun DashTemplateDialog(
    screen: TemplateScreen,
    onApply: (TemplateChoice) -> Unit,
    onDismiss: () -> Unit,
    onlyPage: Int? = null
) {
    var chosen by remember { mutableStateOf(DashTemplate.DAILY) }
    var column by remember { mutableStateOf(false) }
    val pages = onlyPage?.let { setOf(it) } ?: if (column) TemplatePlacer.ALL_PAGES else TemplatePlacer.ROW_PAGES
    AlertDialog(
        modifier = Modifier.fillMaxWidth(0.92f).keepClearOfWindows(),
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        onDismissRequest = onDismiss,
        containerColor = DashColors.Card,
        title = { Text(stringResource(R.string.templates_title), color = DashColors.TextPrimary) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DashTemplate.entries.forEach { template ->
                        // One page's own fill previews the whole template, with that page framed.
                        TemplateCard(template, screen, if (onlyPage == null) pages else null, onlyPage ?: DashboardStore.CENTER, template == chosen, Modifier.weight(1f)) { chosen = template }
                    }
                }
                Text(
                    if (onlyPage != null) stringResource(R.string.templates_fill_this_hint, pageName(onlyPage)) else stringResource(R.string.templates_hint),
                    color = DashColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
                if (onlyPage == null) {
                    SettingsToggle(
                        icon = Icons.Filled.SwapVert,
                        title = stringResource(R.string.templates_column),
                        detail = stringResource(R.string.templates_column_detail),
                        checked = column,
                        onChange = { column = it }
                    )
                }
                if (!screen.obdPaired) {
                    Text(
                        stringResource(R.string.templates_no_obd),
                        color = DashColors.Warning,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dash_cancel), color = DashColors.TextSecondary) }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onlyPage == null) {
                    TextButton(onClick = { onApply(TemplateChoice(chosen, pages, replace = false)) }) {
                        Text(stringResource(R.string.templates_fill_empty), color = DashColors.AccentInk)
                    }
                }
                Button(
                    onClick = { onApply(TemplateChoice(chosen, pages, replace = true)) },
                    colors = ButtonDefaults.buttonColors(containerColor = DashColors.Accent, contentColor = DashColors.OnAccent),
                    shape = DashShape.Small
                ) {
                    Text(stringResource(if (onlyPage != null) R.string.templates_fill else R.string.templates_replace_all))
                }
            }
        }
    )
}

/** [only]: the pages the template will lay out (the rest drawn empty); [framed] is the page outlined in the preview. */
@Composable
private fun TemplateCard(
    template: DashTemplate,
    screen: TemplateScreen,
    only: Set<Int>?,
    framed: Int,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val shape = DashShape.Medium
    val pages = remember(template, screen, only) { TemplatePlacer.pages(template, screen, only) }
    Column(
        modifier = modifier
            .border(if (selected) 2.dp else 1.dp, if (selected) DashColors.Accent else DashColors.CardHi, shape)
            .background(DashColors.CardHi.copy(alpha = DashColors.CardHi.alpha * if (selected) 0.65f else 0.35f), shape)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            stringResource(template.titleRes),
            color = DashColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleSmall
        )
        Text(
            stringResource(template.blurbRes),
            color = DashColors.TextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall
        )
        TemplatePreview(pages, screen, framed, Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}

/** Where each page sits in the cross preview, as (column, row) of a 3 x 5 grid. */
private val CROSS_CELL = mapOf(3 to (1 to 0), 4 to (1 to 1), 0 to (0 to 2), 1 to (1 to 2), 2 to (2 to 2), 5 to (1 to 3), 6 to (1 to 4))

/** The seven pages drawn as the cross they form, each tile a block in its category's colour; [framed] gets the accent frame. */
@Composable
private fun TemplatePreview(pages: List<List<DashboardItem>>, screen: TemplateScreen, framed: Int, modifier: Modifier) {
    val pageAspect = (screen.cellAspect * GRID_COLS / GRID_ROWS).toFloat().coerceIn(0.4f, 4f)
    val gapRatio = 0.08f
    // Width / height of the whole cross, in page widths.
    val crossW = 3f + 2f * gapRatio
    val crossH = 5f / pageAspect + 4f * gapRatio
    val pageBg = DashColors.CardHi
    val home = DashColors.Accent
    val colors = categoryColors()
    Canvas(modifier.aspectRatio(crossW / crossH)) {
        val pw = size.width / crossW
        val ph = pw / pageAspect
        val gap = pw * gapRatio
        CROSS_CELL.forEach { (page, cell) ->
            val origin = Offset(cell.first * (pw + gap), cell.second * (ph + gap))
            drawRoundRect(pageBg, origin, Size(pw, ph), CornerRadius(4f))
            if (page == framed) {
                drawRoundRect(home, origin, Size(pw, ph), CornerRadius(4f), style = Stroke(width = 2f))
            }
            pages.getOrNull(page).orEmpty().forEach { drawTile(it, origin, pw, ph, colors) }
        }
    }
}

internal fun DrawScope.drawTile(item: DashboardItem, origin: Offset, pw: Float, ph: Float, colors: TileColors) {
    val cw = pw / GRID_COLS
    val ch = ph / GRID_ROWS
    val inset = 1f
    val color = when (item) {
        is DashboardItem.BuiltinWidget -> colors.of(item.kind.category)
        else -> colors.dock
    }
    drawRoundRect(
        color,
        Offset(origin.x + item.x * cw + inset, origin.y + item.y * ch + inset),
        Size((item.w * cw - 2 * inset).coerceAtLeast(1f), (item.h * ch - 2 * inset).coerceAtLeast(1f)),
        CornerRadius(2f)
    )
}

internal class TileColors(
    val navigation: Color,
    val driving: Color,
    val vehicle: Color,
    val info: Color,
    val apps: Color,
    val dock: Color
) {
    fun of(category: WidgetCategory) = when (category) {
        WidgetCategory.NAVIGATION -> navigation
        WidgetCategory.DRIVING -> driving
        WidgetCategory.VEHICLE -> vehicle
        WidgetCategory.INFO -> info
        WidgetCategory.APPS -> apps
    }
}

/**
 * Fixed colours rather than the theme's: several themes share one hue across
 * accent / speed / warning, and the preview needs the categories apart.
 */
@Composable
internal fun categoryColors() = TileColors(
    navigation = Color(0xFF3B82F6),
    driving = Color(0xFF14B8A6),
    vehicle = Color(0xFFE0694A),
    info = DashColors.TextSecondary.copy(alpha = 0.7f),
    apps = Color(0xFF8B5CF6),
    dock = DashColors.Muted.copy(alpha = 0.6f)
)
