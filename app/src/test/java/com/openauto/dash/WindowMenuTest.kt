package com.openauto.dash

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class WindowMenuTest {
    // An upright 768x1024 unit whose firmware bars leave the dashboard 230..884.
    private val dash = IntRect(0, 230, 768, 884)
    private val menu = IntSize(274, 325)

    @Test fun opensBelowWhenItFits() {
        val anchor = IntRect(100, 280, 150, 330)
        assertEquals(IntOffset(100, 330), menuOffset(anchor, dash, LayoutDirection.Ltr, menu, 8))
    }

    @Test fun opensAboveTheBottomBarsButton() {
        // The ⋮ button at the dashboard's bottom right: above it, end-aligned.
        val anchor = IntRect(692, 822, 752, 878)
        assertEquals(IntOffset(752 - 274, 822 - 325), menuOffset(anchor, dash, LayoutDirection.Ltr, menu, 8))
    }

    @Test fun neverPassesTheDashboardsEdges() {
        val anchor = IntRect(100, 500, 150, 550)
        val tall = IntSize(300, 500)
        val y = menuOffset(anchor, dash, LayoutDirection.Ltr, tall, 8).y
        assertEquals(884 - 8 - 500, y)
    }

    @Test fun rightToLeftStartsAtTheAnchorsRight() {
        val anchor = IntRect(400, 280, 450, 330)
        assertEquals(IntOffset(450 - 274, 330), menuOffset(anchor, dash, LayoutDirection.Rtl, menu, 8))
    }
}
