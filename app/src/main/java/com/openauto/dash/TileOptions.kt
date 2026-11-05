package com.openauto.dash

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FlipToBack
import androidx.compose.material.icons.filled.FlipToFront
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * A tile's panel while arranging (a tap on the tile): its design, its text
 * size and removing it, all on one level beside the
 * page, so each change shows on the tile itself. It opens on the driver's
 * side, or on the other one when the tile is under it there; an upright
 * screen has it along the bottom, or the top. Resizing stays on the tile's
 * corner, and the edit bar's Undo takes back any of it.
 *
 * Its Layer tab is the tile's frame (TileFrame): over the other tiles or in
 * the grid with them, on top of or under the other tiles over the grid, its
 * opacity, its border and the border's colour, and the tile's place and size
 * a cell at a time, for setting one tile over another exactly.
 */

/** Where the tile's panel stands on the pages. */
internal enum class PanelSide { LEFT, RIGHT, TOP, BOTTOM }

/** The panel's two tabs: the tile itself (design, text size, remove) and its layer (TileFrame). */
private enum class PanelSection(val icon: ImageVector, @androidx.annotation.StringRes val label: Int) {
    TILE(Icons.Filled.Widgets, R.string.frame_tab_tile),
    LAYER(Icons.Filled.Layers, R.string.frame_tab_layer)
}

/** The side panel's width. */
internal val PANEL_WIDTH = 460.dp

/**
 * Below this height a side panel can't stack a widget's designs over its
 * controls: the controls alone took it all and the designs were left no room
 * at all (a short, wide head unit). It widens instead and sets them side by
 * side, as the upright sheet does.
 */
internal val PANEL_SHORT_HEIGHT = 560.dp

/** The widened side panel's width, at most [PANEL_WIDE_SHARE] of the pages. */
internal val PANEL_WIDE_WIDTH = 600.dp
internal const val PANEL_WIDE_SHARE = 0.75f

/**
 * The side panel's width for pages [pagesWidth] by [pagesHeight]: the narrow
 * one, or, for a tile with designs on short pages, the wide one.
 */
internal fun sidePanelWidth(pagesWidth: Dp, pagesHeight: Dp, hasDesigns: Boolean): Dp =
    if (hasDesigns && pagesHeight < PANEL_SHORT_HEIGHT) {
        maxOf(PANEL_WIDTH, minOf(PANEL_WIDE_WIDTH, pagesWidth * PANEL_WIDE_SHARE))
    } else PANEL_WIDTH

/** Below this height the panel's title, tabs and Close share one line. */
internal val PANEL_CRAMPED_HEIGHT = 360.dp

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
    side: PanelSide,
    /** The design shelf for a tile that has designs, given the room left for it; null otherwise. */
    designs: (@Composable (Modifier) -> Unit)?,
    onZoom: (Float) -> Unit,
    onRemove: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** A side panel widened for short pages (see [sidePanelWidth]): the shelf and the controls side by side. */
    wide: Boolean = false,
    /** The Dashboard bar's own actions, shown above Remove. */
    barActions: (@Composable () -> Unit)? = null,
    /** The tile's frame changed: its opacity, its border, the border's colour (TileFrame). */
    onFrame: (TileFrame) -> Unit = {},
    /** Lifts the tile over the other tiles, or lays it back into the grid. */
    onOverlap: (Boolean) -> Unit = {},
    /** Among the tiles over the others: on top of them (true), or under them. */
    onLayer: (Boolean) -> Unit = {},
    /** Moves the tile by whole cells, across then down. */
    onNudge: (Int, Int) -> Unit = { _, _ -> },
    /** Grows (or shrinks) the tile by whole cells, across then down. */
    onResize: (Int, Int) -> Unit = { _, _ -> }
) {
    val upright = side == PanelSide.TOP || side == PanelSide.BOTTOM
    // Short pages: the side panel has widened to hold the shelf beside the controls.
    val besideShelf = upright || (designs != null && wide)
    // The tile's own settings, the same page Settings opens (TileSettings.kt).
    val settings = (item as? DashboardItem.BuiltinWidget)?.let { tileSettings(it.kind) }
    // A floating window has no layer to set: its tab is left out.
    val framed = item.canFrame(rememberInsideAllowed())
    var section by remember { mutableStateOf(PanelSection.TILE) }
    SolidCard(modifier = modifier.keepClearOfWindows()) {
      BoxWithConstraints(propagateMinConstraints = true) {
        // A very short panel (a wide, low screen): the title, the tabs and Close
        // share one line, so the designs and Remove keep the height.
        val cramped = maxHeight < PANEL_CRAMPED_HEIGHT
        val gap = if (cramped) 8.dp else 10.dp
        Column(modifier = Modifier.padding(if (cramped) 10.dp else 14.dp), verticalArrangement = Arrangement.spacedBy(gap)) {
            val tabs: @Composable (Modifier) -> Unit = { m ->
                SegmentedSwitch(
                    options = PanelSection.entries,
                    chosen = section,
                    icon = { it.icon },
                    title = { stringResource(it.label) },
                    modifier = m,
                    onChoose = { section = it }
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (cramped) 8.dp else 0.dp)) {
                Text(
                    title,
                    color = DashColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(if (cramped && framed) 0.4f else 1f)
                )
                if (cramped && framed) tabs(Modifier.weight(1f))
                SquareButton(Icons.Filled.Close, stringResource(R.string.dash_close), onClick = onClose)
            }
            if (framed && !cramped) tabs(Modifier)
            if (framed && section == PanelSection.LAYER) {
                // Along the bottom, or the widened side panel: the layer beside the place.
                LayerControls(item, twoColumns = upright || wide, onFrame, onOverlap, onLayer, onNudge, onResize, Modifier.weight(1f))
                return@Column
            }
            if (settings != null) TileSettingsButton(settings)
            if (besideShelf) {
                // Along the bottom, or short pages: the shelf takes the width, the rest stands beside it.
                Row(
                    modifier = if (designs != null) Modifier.weight(1f) else Modifier,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (designs != null) {
                        designs(Modifier.weight(1f).fillMaxHeight())
                        VerticalDivider(color = DashColors.Line)
                    }
                    // Scrolls when the panel is shorter than the controls (a short screen).
                    Column(
                        modifier = Modifier.width(220.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Controls(item, stacked = true, onZoom, onRemove, barActions)
                    }
                }
            } else {
                if (designs != null) {
                    designs(Modifier.weight(1f))
                    HorizontalDivider(color = DashColors.Line)
                }
                Controls(item, stacked = false, onZoom, onRemove, barActions)
            }
        }
      }
    }
}

/**
 * Text size, then Remove on a line of its own, well apart from the stepper's +
 * so a tap meant for it never removes the tile; kept short, so the design
 * shelf above has the room. [stacked] is the narrow column of the upright
 * sheet: the text size's label over its stepper.
 */
@Composable
private fun ColumnScope.Controls(
    item: DashboardItem,
    stacked: Boolean,
    onZoom: (Float) -> Unit,
    onRemove: () -> Unit,
    barActions: (@Composable () -> Unit)?
) {
    if (item.canZoom(rememberInsideAllowed())) {
        if (stacked) {
            PanelLabel(stringResource(R.string.zoom_button))
            SizeStepper(item.zoom, onZoom)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PanelLabel(stringResource(R.string.zoom_button), Modifier.weight(1f))
                SizeStepper(item.zoom, onZoom)
            }
        }
        // A window has the screen's own size: zoomed, the app moves inside the tile.
        if (item is DashboardItem.AppWindow && !item.inside) PanelLabel(stringResource(R.string.zoom_window_inside))
    }
    // An app stuck on its tile (a splash that never goes, a map that stopped):
    // closed and opened afresh there, rather than taken full screen to wake it.
    if (item is DashboardItem.AppWindow && item.inside && EmbeddedApp.holds(item.packageName) &&
        PrivilegedShell.access.collectAsState().value.shell
    ) {
        RestartButton { EmbeddedApp.restartInTile(item.packageName) }
    }
    barActions?.invoke()
    Spacer(Modifier.height(6.dp))
    RemoveButton(onRemove)
}

/**
 * The Layer tab: the tile over the others or in the grid, and when over
 * them on top or under the rest of them; its opacity; its border and the
 * border's colour; then its place and size a cell at a time. Each change
 * shows on the tile at once. One scrolling column, or two side by side
 * ([twoColumns]) where the panel is wide and short.
 */
@Composable
private fun LayerControls(
    item: DashboardItem,
    twoColumns: Boolean,
    onFrame: (TileFrame) -> Unit,
    onOverlap: (Boolean) -> Unit,
    onLayer: (Boolean) -> Unit,
    onNudge: (Int, Int) -> Unit,
    onResize: (Int, Int) -> Unit,
    modifier: Modifier
) {
    val frame = item.frame
    val layer: @Composable ColumnScope.() -> Unit = {
        OverlapSwitch(frame.overlap, onOverlap)
        if (frame.overlap) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PanelButton(Icons.Filled.FlipToFront, stringResource(R.string.frame_to_front), Modifier.weight(1f)) { onLayer(true) }
                PanelButton(Icons.Filled.FlipToBack, stringResource(R.string.frame_to_back), Modifier.weight(1f)) { onLayer(false) }
            }
        }
        LabelledStepper(
            label = stringResource(R.string.frame_opacity),
            value = stringResource(R.string.zoom_percent, (frame.opacity * 100).roundToInt()),
            downLabel = stringResource(R.string.frame_fainter), upLabel = stringResource(R.string.frame_more_solid),
            canDown = frame.opacity > OPACITY_MIN, canUp = frame.opacity < 1f,
            onDown = { onFrame(frame.copy(opacity = opacityStep(frame.opacity, -1))) },
            onUp = { onFrame(frame.copy(opacity = opacityStep(frame.opacity, 1))) }
        )
        LabelledStepper(
            label = stringResource(R.string.frame_border),
            value = if (frame.border == 0) stringResource(R.string.frame_border_off) else frame.border.toString(),
            downLabel = stringResource(R.string.frame_thinner), upLabel = stringResource(R.string.frame_thicker),
            canDown = frame.border > 0, canUp = frame.border < BORDER_MAX,
            onDown = { onFrame(frame.copy(border = frame.border - 1)) },
            onUp = { onFrame(frame.copy(border = frame.border + 1)) }
        )
        if (frame.border > 0) {
            PanelLabel(stringResource(R.string.frame_colour))
            ColourSwatches(frame.borderColor) { onFrame(frame.copy(borderColor = it)) }
        }
    }
    val place: @Composable ColumnScope.() -> Unit = {
        PanelLabel(stringResource(R.string.frame_place))
        NudgeCross(item, onNudge, Modifier.align(Alignment.CenterHorizontally))
        PanelLabel(stringResource(R.string.frame_size))
        LabelledStepper(
            label = stringResource(R.string.frame_width),
            value = item.w.toString(),
            downLabel = stringResource(R.string.frame_narrower), upLabel = stringResource(R.string.frame_wider),
            canDown = item.w > item.minW(), canUp = item.x + item.w < GRID_COLS,
            onDown = { onResize(-1, 0) }, onUp = { onResize(1, 0) }
        )
        LabelledStepper(
            label = stringResource(R.string.frame_height),
            value = item.h.toString(),
            downLabel = stringResource(R.string.frame_shorter), upLabel = stringResource(R.string.frame_taller),
            canDown = item.h > item.minH(), canUp = item.y + item.h < GRID_ROWS,
            onDown = { onResize(0, -1) }, onUp = { onResize(0, 1) }
        )
    }
    if (twoColumns) {
        Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) { layer() }
            VerticalDivider(color = DashColors.Line)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) { place() }
        }
    } else {
        Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            layer()
            HorizontalDivider(color = DashColors.Line)
            place()
        }
    }
}

/** Over the other tiles, or in the grid with them: the switch, with a line on what it means. */
@Composable
private fun OverlapSwitch(over: Boolean, onOverlap: (Boolean) -> Unit) {
    val tap = rememberTapFeedback()
    Row(
        modifier = Modifier
            .heightIn(min = DashSize.Touch)
            .clip(DashShape.Small)
            .border(1.dp, if (over) DashColors.Accent else DashColors.Line, DashShape.Small)
            .clickable(role = Role.Switch) { tap(); onOverlap(!over) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Layers, contentDescription = null, tint = if (over) DashColors.Accent else DashColors.TextSecondary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.frame_overlap), color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(if (over) R.string.frame_overlap_on else R.string.frame_overlap_off),
                color = DashColors.TextSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = over,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = DashColors.OnAccent,
                checkedTrackColor = DashColors.Accent,
                uncheckedThumbColor = DashColors.TextSecondary,
                uncheckedTrackColor = DashColors.CardHi,
                uncheckedBorderColor = DashColors.Line
            )
        )
    }
}

/** A labelled action in the panel's row style: its icon and its words. */
@Composable
private fun PanelButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    Row(
        modifier = modifier
            .heightIn(min = DashSize.Touch)
            .clip(DashShape.Small)
            .background(DashColors.CardHi)
            .clickable(role = Role.Button) { tap(); onClick() }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, tint = DashColors.TextPrimary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A setting's name with its stepper beside it: minus, the value, plus. */
@Composable
private fun LabelledStepper(
    label: String,
    value: String,
    downLabel: String,
    upLabel: String,
    canDown: Boolean,
    canUp: Boolean,
    onDown: () -> Unit,
    onUp: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PanelLabel(label, Modifier.weight(1f))
        Stepper(value, downLabel, upLabel, canDown, canUp, onDown, onUp)
    }
}

@Composable
private fun Stepper(value: String, downLabel: String, upLabel: String, canDown: Boolean, canUp: Boolean, onDown: () -> Unit, onUp: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SquareButton(Icons.Filled.Remove, downLabel, enabled = canDown, onClick = onDown)
        Text(
            value,
            color = DashColors.TextPrimary, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.width(56.dp)
        )
        SquareButton(Icons.Filled.Add, upLabel, enabled = canUp, onClick = onUp)
    }
}

/** The border's colours as swatches, four to a row so they fit the narrow column too, the chosen one ringed in the accent. */
@Composable
private fun ColourSwatches(chosen: TileBorderColor, onPick: (TileBorderColor) -> Unit) {
    val tap = rememberTapFeedback()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TileBorderColor.entries.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { colour ->
                    val name = stringResource(colour.labelRes)
                    val picked = colour == chosen
                    Box(
                        modifier = Modifier
                            .size(SWATCH)
                            .clip(RoundedCornerShape(50))
                            .border(if (picked) 3.dp else 1.dp, if (picked) DashColors.Accent else DashColors.Line, RoundedCornerShape(50))
                            .padding(if (picked) 5.dp else 3.dp)
                            .clip(RoundedCornerShape(50))
                            .background(colour.color())
                            .clickable(role = Role.RadioButton, onClickLabel = name) { tap(); onPick(colour) }
                            .semantics { contentDescription = name }
                    )
                }
            }
        }
    }
}

private val SWATCH = 34.dp

/** Four arrows around the tile's cell: a tap moves it one cell that way, as far as the grid goes. */
@Composable
private fun NudgeCross(item: DashboardItem, onNudge: (Int, Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SquareButton(Icons.Filled.KeyboardArrowUp, stringResource(R.string.frame_up), enabled = item.y > 0) { onNudge(0, -1) }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            SquareButton(Icons.Filled.KeyboardArrowLeft, stringResource(R.string.frame_left), enabled = item.x > 0) { onNudge(-1, 0) }
            // The tile's top-left cell, counted from one, as the grid shows it.
            Text(
                "${item.x + 1},${item.y + 1}",
                color = DashColors.TextSecondary, style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.width(DashSize.Touch)
            )
            SquareButton(Icons.Filled.KeyboardArrowRight, stringResource(R.string.frame_right), enabled = item.x + item.w < GRID_COLS) { onNudge(1, 0) }
        }
        SquareButton(Icons.Filled.KeyboardArrowDown, stringResource(R.string.frame_down), enabled = item.y + item.h < GRID_ROWS) { onNudge(0, 1) }
    }
}

@Composable
private fun PanelLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, color = DashColors.TextSecondary, style = MaterialTheme.typography.labelMedium, maxLines = 2, modifier = modifier)
}

/** Smaller, the percentage, bigger: the tile's text and icons, as on the edit bar for the whole page. */
@Composable
private fun SizeStepper(zoom: Float, onZoom: (Float) -> Unit) {
    Stepper(
        value = stringResource(R.string.zoom_percent, (zoom * 100).roundToInt()),
        downLabel = stringResource(R.string.zoom_out), upLabel = stringResource(R.string.zoom_in),
        canDown = zoom > ZOOM_MIN, canUp = zoom < ZOOM_MAX,
        onDown = { onZoom(zoomStep(zoom, -1)) }, onUp = { onZoom(zoomStep(zoom, 1)) }
    )
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
private fun RestartButton(onRestart: () -> Unit) {
    val tap = rememberTapFeedback()
    Row(
        modifier = Modifier
            .heightIn(min = DashSize.Touch)
            .clip(DashShape.Small)
            .background(DashColors.CardHi)
            .clickable(role = Role.Button) { tap(); onRestart() }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Refresh, contentDescription = null, tint = DashColors.TextPrimary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.dash_tile_restart_app), color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun RemoveButton(onRemove: () -> Unit) {
    val tap = rememberTapFeedback()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = DashSize.Touch)
            .clip(DashShape.Small)
            .border(1.dp, DashColors.Critical.copy(alpha = 0.5f), DashShape.Small)
            .clickable(role = Role.Button) { tap(); onRemove() }
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Delete, contentDescription = null, tint = DashColors.Critical, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.dash_tile_remove), color = DashColors.Critical, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

