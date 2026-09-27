package com.openauto.dash

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * A tile's panel while arranging (a tap on the tile): its design, its text
 * size, the page to move it to, and removing it, all on one level beside the
 * page, so each change shows on the tile itself. It opens on the driver's
 * side, or on the other one when the tile is under it there; an upright
 * screen has it along the bottom, or the top. Resizing stays on the tile's
 * corner, and the edit bar's Undo takes back any of it.
 */

/** Where the tile's panel stands on the pages. */
internal enum class PanelSide { LEFT, RIGHT, TOP, BOTTOM }

/** The side panel's width. */
internal val PANEL_WIDTH = 360.dp

/** The upright sheet's tallest: it stays under half the pages, so the tile keeps room above or below it. */
internal val PANEL_SHEET_MAX = 460.dp

/**
 * The driver's side (the left unless [driverOnRight]) when the panel leaves
 * the tile there, else whichever side covers less of it. [tile] and [area]
 * are in the same coordinates; [panelPx] is the panel's width, or height when
 * [vertical].
 */
internal fun panelSide(tile: Rect, area: Rect, panelPx: Float, vertical: Boolean, driverOnRight: Boolean): PanelSide {
    fun cover(a0: Float, a1: Float, b0: Float, b1: Float) = max(0f, min(a1, b1) - max(a0, b0))
    if (vertical) {
        val bottom = cover(tile.top, tile.bottom, area.bottom - panelPx, area.bottom)
        val top = cover(tile.top, tile.bottom, area.top, area.top + panelPx)
        return if (bottom <= top) PanelSide.BOTTOM else PanelSide.TOP
    }
    val left = cover(tile.left, tile.right, area.left, area.left + panelPx)
    val right = cover(tile.left, tile.right, area.right - panelPx, area.right)
    return if (driverOnRight) {
        if (right <= left) PanelSide.RIGHT else PanelSide.LEFT
    } else {
        if (left <= right) PanelSide.LEFT else PanelSide.RIGHT
    }
}

@Composable
internal fun TilePanel(
    item: DashboardItem,
    /** What the tile is: the widget, or the app by its name. */
    title: String,
    page: Int,
    side: PanelSide,
    /** The design shelf for a tile that has designs, given the room left for it; null otherwise. */
    designs: (@Composable (Modifier) -> Unit)?,
    onZoom: (Float) -> Unit,
    onMoveTo: (Int) -> Unit,
    onRemove: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val upright = side == PanelSide.TOP || side == PanelSide.BOTTOM
    SolidCard(modifier = modifier.keepClearOfWindows()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    color = DashColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                SquareButton(Icons.Filled.Close, stringResource(R.string.dash_close), onClick = onClose)
            }
            if (upright) {
                // Along the bottom: the shelf takes the width, the rest stands beside it.
                Row(
                    modifier = if (designs != null) Modifier.weight(1f) else Modifier,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (designs != null) {
                        designs(Modifier.weight(1f).fillMaxHeight())
                        VerticalDivider(color = DashColors.Line)
                    }
                    Column(modifier = Modifier.width(220.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Controls(item, page, stacked = true, onZoom, onMoveTo, onRemove)
                    }
                }
            } else {
                if (designs != null) {
                    designs(Modifier.weight(1f))
                    HorizontalDivider(color = DashColors.Line)
                }
                Controls(item, page, stacked = false, onZoom, onMoveTo, onRemove)
            }
        }
    }
}

/**
 * Text size, then the page cross to move to with Remove beside it; kept
 * short, so the design shelf above has the room. [stacked] is the narrow
 * column of the upright sheet: the text size's label over its stepper, and
 * Remove under the cross.
 */
@Composable
private fun ColumnScope.Controls(
    item: DashboardItem,
    page: Int,
    stacked: Boolean,
    onZoom: (Float) -> Unit,
    onMoveTo: (Int) -> Unit,
    onRemove: () -> Unit
) {
    if (item.canZoom()) {
        if (stacked) {
            PanelLabel(stringResource(R.string.zoom_button))
            SizeStepper(item.zoom, onZoom)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PanelLabel(stringResource(R.string.zoom_button), Modifier.weight(1f))
                SizeStepper(item.zoom, onZoom)
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(modifier = Modifier.weight(1f).height(PAGE_CROSS_HEIGHT)) {
            PanelLabel(stringResource(R.string.dash_tile_move))
            if (!stacked) {
                Spacer(Modifier.weight(1f))
                RemoveButton(onRemove)
            }
        }
        PageCrossPicker(current = page, onPick = onMoveTo)
    }
    // Beside the cross there is no room for it in the narrow column.
    if (stacked) RemoveButton(onRemove)
}

@Composable
private fun PanelLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, color = DashColors.TextSecondary, style = MaterialTheme.typography.labelMedium, maxLines = 2, modifier = modifier)
}

/** Smaller, the percentage, bigger: the tile's text and icons, as on the edit bar for the whole page. */
@Composable
private fun SizeStepper(zoom: Float, onZoom: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SquareButton(Icons.Filled.Remove, stringResource(R.string.zoom_out), enabled = zoom > ZOOM_MIN) { onZoom(zoomStep(zoom, -1)) }
        Text(
            stringResource(R.string.zoom_percent, (zoom * 100).roundToInt()),
            color = DashColors.TextPrimary, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, modifier = Modifier.width(56.dp)
        )
        SquareButton(Icons.Filled.Add, stringResource(R.string.zoom_in), enabled = zoom < ZOOM_MAX) { onZoom(zoomStep(zoom, 1)) }
    }
}

@Composable
private fun SquareButton(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    Box(
        modifier = Modifier
            .size(DashSize.Touch)
            .clip(DashShape.Small)
            .background(DashColors.CardHi)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label) { tap(); onClick() }
            .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = label, tint = DashColors.TextPrimary, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun RemoveButton(onRemove: () -> Unit) {
    val tap = rememberTapFeedback()
    Row(
        modifier = Modifier
            .heightIn(min = DashSize.Touch)
            .clip(DashShape.Small)
            .border(1.dp, DashColors.Critical.copy(alpha = 0.5f), DashShape.Small)
            .clickable(role = Role.Button) { tap(); onRemove() }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Delete, contentDescription = null, tint = DashColors.Critical, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.dash_tile_remove), color = DashColors.Critical, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

private val PAGE_CELL = 24.dp
private val PAGE_GAP = 4.dp
private val PAGE_CROSS_HEIGHT = PAGE_CELL * 5 + PAGE_GAP * 4

/**
 * The seven pages as the cross they make (the page indicator's shape): the
 * tile's own page filled in, a tap on another sends the tile there.
 */
@Composable
private fun PageCrossPicker(current: Int, onPick: (Int) -> Unit) {
    val centreCol = DashboardStore.ROW.indexOf(DashboardStore.CENTER)
    val cells = DashboardStore.COLUMN.mapIndexed { row, p -> Triple(p, centreCol, row) } +
        DashboardStore.ROW.mapIndexedNotNull { col, p -> if (p == DashboardStore.CENTER) null else Triple(p, col, DashboardStore.COLUMN_HOME) }
    Box(modifier = Modifier.size(width = PAGE_CELL * 3 + PAGE_GAP * 2, height = PAGE_CROSS_HEIGHT)) {
        cells.forEach { (p, col, row) -> PageCell(p, p == current, col, row) { onPick(p) } }
    }
}

@Composable
private fun PageCell(page: Int, here: Boolean, col: Int, row: Int, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    val shape = RoundedCornerShape(6.dp)
    val name = stringResource(DashboardStore.nameRes(page))
    Box(
        modifier = Modifier
            .offset(x = (PAGE_CELL + PAGE_GAP) * col, y = (PAGE_CELL + PAGE_GAP) * row)
            .size(PAGE_CELL)
            .clip(shape)
            .then(
                if (here) Modifier.background(DashColors.Accent).semantics { contentDescription = name }
                else Modifier
                    .border(1.5.dp, DashColors.TextSecondary, shape)
                    .clickable(role = Role.Button, onClickLabel = name) { tap(); onClick() }
                    .semantics { contentDescription = name }
            )
    )
}
