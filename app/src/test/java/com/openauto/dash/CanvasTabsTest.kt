package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The dashboard bar: its dashboards, their triggers, places and pages. */
class CanvasTabsTest {

    @Test
    fun dashboardsSurviveASaveAndARead() {
        val tabs = listOf(
            CanvasTab(0, TabPreset.MAP),
            CanvasTab(4, TabPreset.MEDIA, name = "Tunes", trigger = TabTrigger.PARKED),
            CanvasTab(2, null, name = "Mine", trigger = TabTrigger.ENGINE_FAULT)
        )
        assertEquals(tabs, CanvasTabs.parse(CanvasTabs.serialize(tabs)))
    }

    @Test
    fun unreadableDashboardsFallBack() {
        assertNull(CanvasTabs.parse("not json"))
        // A preset or trigger from a newer build is dropped, the dashboard kept.
        val read = CanvasTabs.parse("""[{"g":3,"p":"ROCKET","n":"Go","w":"SUNRISE"}]""")
        assertEquals(listOf(CanvasTab(3, null, "Go", null)), read)
        // Pages out of range, and a second dashboard on one page, are dropped.
        assertEquals(listOf(CanvasTab(1)), CanvasTabs.parse("""[{"g":1},{"g":9},{"g":1}]"""))
    }

    @Test
    fun tabsSavedBeforeTheyHadAPageKeepTheirPlace() {
        val read = CanvasTabs.parse("""[{"p":"MAP"},{"p":"MEDIA","w":"DRIVING"}]""")
        assertEquals(listOf(CanvasTab(0, TabPreset.MAP), CanvasTab(1, TabPreset.MEDIA, trigger = TabTrigger.DRIVING)), read)
    }

    @Test
    fun theCrossStartsWithItsPagesAndKeepsThemInReach() {
        val pages = listOf(listOf("a"), emptyList(), emptyList(), emptyList(), listOf("b"), emptyList(), emptyList())
        // One list per screen direction, nothing else.
        val wide = CanvasTabs.keyFor("")
        val upright = CanvasTabs.keyFor("_v")
        assertFalse(wide == upright)
        // Home first, then the pages with tiles round it.
        assertEquals(listOf(1, 0, 4), CanvasTabs.tabsFor(wide, emptyMap(), pages).map { it.page })
        // Saved ones win, with Home and every page holding tiles kept in reach.
        val saved = listOf(CanvasTab(1), CanvasTab(5, name = "Mine"), CanvasTab(0), CanvasTab(4))
        assertEquals(saved, CanvasTabs.tabsFor(wide, mapOf(wide to saved), pages))
        val partial = listOf(CanvasTab(5, name = "Mine"))
        assertEquals(listOf(1, 5, 0, 4), CanvasTabs.tabsFor(wide, mapOf(wide to partial), pages).map { it.page })
    }

    @Test
    fun aTemplateNamesThePageItFills_andLeavesTheRestAlone() {
        val tabs = listOf(CanvasTab(1), CanvasTab(0, name = "Mine"))
        // A page already in the bar takes the template's name; one typed by the driver goes with the old tiles.
        val named = CanvasTabs.named(tabs, 0, TabPreset.MEDIA)
        assertEquals(CanvasTab(0, TabPreset.MEDIA), named[1])
        // A page not yet in the bar joins it, named.
        assertEquals(CanvasTab(2, TabPreset.CAR), CanvasTabs.named(tabs, 2, TabPreset.CAR).last())
        // Home keeps its name: the templates pass null for it.
        assertEquals(tabs, CanvasTabs.named(tabs, 1, null))
    }

    @Test
    fun aTriggerBelongsToOneDashboard() {
        val tabs = CanvasTabs.legacyTabs()
        val placed = CanvasTabs.placed(tabs, tabs[1].copy(trigger = TabTrigger.ENGINE_FAULT), 1)
        assertEquals(1, CanvasTabs.tabFor(placed, TabTrigger.ENGINE_FAULT)?.page)
        assertNull(placed.first { it.page == 2 }.trigger)
        assertNull(CanvasTabs.tabFor(placed, TabTrigger.DRIVING))
    }

    @Test
    fun aDashboardGoesWhereItIsPlaced() {
        val tabs = CanvasTabs.legacyTabs()
        // Car moved first.
        assertEquals(listOf(2, 0, 1, 3), CanvasTabs.placed(tabs, tabs[2], 0).map { it.page })
        // A new one at the end.
        assertEquals(listOf(0, 1, 2, 3, 5), CanvasTabs.placed(tabs, CanvasTab(5), 9).map { it.page })
    }

    @Test
    fun aNewDashboardTakesAnEmptyPageFirst() {
        val tabs = listOf(CanvasTab(0), CanvasTab(1))
        val pages = listOf(emptyList(), emptyList(), listOf("x"), emptyList<String>(), emptyList(), emptyList(), emptyList())
        assertEquals(3, CanvasTabs.freePage(tabs, pages))
        val all = (0 until DashboardStore.PAGE_COUNT).map { CanvasTab(it) }
        assertNull(CanvasTabs.freePage(all, pages))
    }

    @Test
    fun tradingPlacesInTheCrossSwapsTheirPages() {
        val tabs = listOf(CanvasTab(1, name = "Home"), CanvasTab(4, name = "Car"))
        assertEquals(listOf(4, 1), CanvasTabs.swapped(tabs, 1, 4).map { it.page })
        assertEquals(listOf(6, 4), CanvasTabs.swapped(tabs, 1, 6).map { it.page })
    }

    @Test
    fun appsMoveInTheBar() {
        assertEquals(listOf("b", "a", "c"), CanvasTabs.moved(listOf("a", "b", "c"), 0, 1))
        assertEquals(listOf("b", "c", "a"), CanvasTabs.moved(listOf("a", "b", "c"), 0, 5))
    }

    @Test
    fun theWheelGoesRoundTheTabs() {
        assertEquals(1, CanvasTabs.stepped(0, 1, 4))
        assertEquals(0, CanvasTabs.stepped(3, 1, 4))
        assertEquals(3, CanvasTabs.stepped(0, -1, 4))
        assertEquals(0, CanvasTabs.stepped(2, 1, 0))
    }

    @Test
    fun onlyTheKeptDashboardsAreSwiped() {
        // Home alone: one page each way, nothing to swipe.
        val alone = DashboardStore.shownCross(emptyList())
        assertEquals(listOf(DashboardStore.CENTER), alone.row)
        assertEquals(listOf(DashboardStore.CENTER), alone.column)
        assertEquals(DashboardStore.CENTER, alone.pageAt(0, 0))
        // Left of Home and the one just below it.
        val some = DashboardStore.shownCross(listOf(0, 5))
        assertEquals(listOf(0, 1), some.row)
        assertEquals(listOf(1, 5), some.column)
        assertEquals(0, some.pageAt(0, 0))
        assertEquals(5, some.pageAt(some.rowHome, 1))
        assertTrue(5 in some)
        assertFalse(2 in some)
        // A page that went: the pagers start on Home.
        assertEquals(some.rowHome, some.rowIndexOf(2))
        assertEquals(some.columnHome, some.columnIndexOf(2))
    }
}
