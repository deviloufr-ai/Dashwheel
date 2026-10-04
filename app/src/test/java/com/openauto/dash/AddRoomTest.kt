package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Room for a new tile, as checked when Add is tapped (AddRoom.kt). */
class AddRoomTest {

    private fun tile(x: Int, y: Int, w: Int, h: Int) = DashboardItem.BuiltinWidget(BuiltinKind.CLOCK, x, y, w, h)

    private val full = listOf(tile(0, 0, GRID_COLS, GRID_ROWS))

    // --- one page ------------------------------------------------------------

    @Test
    fun aPageIsFullOnlyOnceNoCellIsLeft() {
        assertTrue(pageHasRoom(emptyList()))
        assertFalse(pageHasRoom(full))
        // A single cell left in the corner is still room.
        val nearly = listOf(tile(0, 0, GRID_COLS, GRID_ROWS - 1), tile(0, GRID_ROWS - 1, GRID_COLS - 1, 1))
        assertTrue(pageHasRoom(nearly))
    }

    @Test
    fun aTileThatFitsKeepsItsSize() {
        assertEquals(TileFit(0, 0, 5, 3), largestFit(emptyList(), 5, 3))
        assertEquals(TileFit(5, 0, 5, 3), largestFit(listOf(tile(0, 0, 5, 3)), 5, 3))
    }

    @Test
    fun aTileThatDoesNotFitGetsTheLargestSpanThatDoes() {
        // A 4 x 3 gap in the top right corner, the rest taken: not the single cell it used to get.
        val page = listOf(tile(0, 0, GRID_COLS - 4, GRID_ROWS), tile(GRID_COLS - 4, 3, 4, GRID_ROWS - 3))
        assertEquals(TileFit(GRID_COLS - 4, 0, 4, 3), largestFit(page, 5, 3))
    }

    @Test
    fun ofTwoSpansAsLargeTheOneNearestTheTilesShapeWins() {
        // Two gaps of four cells: a column of four on the left, a square in the bottom right corner.
        val page = listOf(
            tile(1, 0, GRID_COLS - 1, GRID_ROWS - 2),
            tile(0, 4, 1, GRID_ROWS - 4),
            tile(1, GRID_ROWS - 2, GRID_COLS - 3, 2)
        )
        assertEquals(TileFit(GRID_COLS - 2, GRID_ROWS - 2, 2, 2), largestFit(page, 4, 4))
        // A tall tile takes the column instead.
        assertEquals(TileFit(0, 0, 1, 4), largestFit(page, 1, 5))
    }

    @Test
    fun neverSmallerThanTheTilesMinimum() {
        val page = listOf(tile(0, 0, GRID_COLS, GRID_ROWS - 1), tile(0, GRID_ROWS - 1, GRID_COLS - 1, 1))
        assertEquals(TileFit(GRID_COLS - 1, GRID_ROWS - 1, 1, 1), largestFit(page, 5, 3))
        assertNull(largestFit(page, 5, 3, minW = 2, minH = 1))
        assertNull(largestFit(full, 5, 3))
    }

    // --- another dashboard ---------------------------------------------------

    /** Seven pages, all full but the [free] ones. */
    private fun pages(vararg free: Int) = List(DashboardStore.PAGE_COUNT) { if (it in free) emptyList() else full }

    // Home, left, right, above, below, in the bar's order.
    private val shown = listOf(DashboardStore.CENTER, 0, 2, 4, 5)

    @Test
    fun theNearestDashboardWithRoomIsOffered() {
        // From the left page, Home is one swipe away and the right page two.
        assertEquals(DashboardStore.CENTER, nearestPageWithRoom(pages(DashboardStore.CENTER, 2), 0, shown))
        // From two above Home, the page just above is nearer than Home.
        assertEquals(4, nearestPageWithRoom(pages(DashboardStore.CENTER, 4), 3, shown + 3))
        // As near: the bar's order decides.
        assertEquals(0, nearestPageWithRoom(pages(0, 2), DashboardStore.CENTER, shown))
    }

    @Test
    fun onlyTheDriversOtherDashboardsAreOffered() {
        // Room on the full page itself, or on a page that is not one of the dashboards, is no offer.
        assertNull(nearestPageWithRoom(pages(6), DashboardStore.CENTER, shown))
        assertNull(nearestPageWithRoom(pages(DashboardStore.CENTER), DashboardStore.CENTER, shown))
        assertNull(nearestPageWithRoom(pages(), DashboardStore.CENTER, shown))
    }

    @Test
    fun tabsCountPlacesAlongTheRail() {
        val tabs = listOf(0, 1, 2, 3)
        // The tab beside it in the rail, though the cross would have put the first one as near.
        assertEquals(2, nearestPageWithRoom(pages(0, 2), 3, tabs, tabbed = true))
        assertEquals(0, nearestPageWithRoom(pages(0, 2), 3, tabs))
    }
}
