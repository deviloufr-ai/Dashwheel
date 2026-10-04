package com.openauto.dash

import android.view.View
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/** Room kept between a menu and the edges of the dashboard's window. */
private val MENU_MARGIN = 8.dp

/**
 * A menu that stays inside the dashboard's own window. Material's DropdownMenu
 * places itself against the whole display, so on head units whose firmware
 * keeps bars of its own over the screen (an upright unit with a climate strip
 * at the bottom and a shortcut bar at the top) it opened under those bars and
 * its last rows, Settings among them, could not be reached. The dashboard
 * already ends where those bars start, so its own area is the limit here.
 */
@Composable
internal fun WindowMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = MaterialTheme.shapes.medium,
    containerColor: Color,
    border: BorderStroke? = null,
    content: @Composable () -> Unit
) {
    if (!expanded) return
    val view = LocalView.current
    val density = LocalDensity.current
    val marginPx = with(density) { MENU_MARGIN.roundToPx() }
    val position = remember(view, marginPx) { WindowBoundPosition(view, marginPx) }
    val maxHeight = with(density) { (view.height - 2 * marginPx).coerceAtLeast(0).toDp() }
    Popup(
        popupPositionProvider = position,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true)
    ) {
        Surface(
            modifier = modifier,
            shape = shape,
            color = containerColor,
            border = border,
            shadowElevation = 3.dp,
            tonalElevation = 0.dp
        ) {
            Column(
                Modifier
                    .heightIn(max = maxHeight)
                    .width(IntrinsicSize.Max)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp)
            ) {
                content()
            }
        }
    }
}

/**
 * Below the anchor, else above it, else against the bottom of the dashboard;
 * lined up with the anchor's start, else its end. The bounds are the
 * dashboard view's own place on the screen, never the display-wide
 * [windowSize] the pop-up machinery passes in. The anchor comes measured from
 * the window's corner but the pop-up is placed from the corner of the visible
 * screen area; on a unit with bars of its own those differ, so both go
 * through screen coordinates.
 */
internal class WindowBoundPosition(private val view: View, private val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        if (view.width <= 0 || view.height <= 0) {
            return menuOffset(anchorBounds, IntRect(0, 0, windowSize.width, windowSize.height), layoutDirection, popupContentSize, margin)
        }
        val window = IntArray(2).also { view.rootView.getLocationOnScreen(it) }
        val visible = android.graphics.Rect().also { view.getWindowVisibleDisplayFrame(it) }
        val dash = IntArray(2).also { view.getLocationOnScreen(it) }
        // Everything in the pop-up's own frame: screen minus the visible area's corner.
        val anchor = anchorBounds.translate(window[0] - visible.left, window[1] - visible.top)
        val bounds = IntRect(dash[0] - visible.left, dash[1] - visible.top, dash[0] - visible.left + view.width, dash[1] - visible.top + view.height)
        return menuOffset(anchor, bounds, layoutDirection, popupContentSize, margin)
    }
}

/** [WindowBoundPosition]'s placement, apart from the view so it can be tested. */
internal fun menuOffset(anchor: IntRect, bounds: IntRect, dir: LayoutDirection, menu: IntSize, margin: Int): IntOffset {
    val startX = if (dir == LayoutDirection.Ltr) anchor.left else anchor.right - menu.width
    val endX = if (dir == LayoutDirection.Ltr) anchor.right - menu.width else anchor.left
    val minX = bounds.left + margin
    val maxX = (bounds.right - margin - menu.width).coerceAtLeast(minX)
    val x = when {
        startX in minX..maxX -> startX
        endX in minX..maxX -> endX
        else -> startX.coerceIn(minX, maxX)
    }
    val minY = bounds.top + margin
    val maxY = (bounds.bottom - margin - menu.height).coerceAtLeast(minY)
    val below = anchor.bottom
    val above = anchor.top - menu.height
    val y = when {
        below in minY..maxY -> below
        above in minY..maxY -> above
        else -> maxY
    }
    return IntOffset(x, y)
}
