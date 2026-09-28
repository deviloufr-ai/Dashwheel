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
    fun theCanvasTabsAndACrossStartDifferently() {
        val pages = listOf(listOf("a"), emptyList(), emptyList(), emptyList(), listOf("b"), emptyList(), emptyList())
        val tabsKey = CanvasTabs.keyFor(DashboardStore.TABS_VARIANT)
        assertEquals(CanvasTabs.defaultTabs(), CanvasTabs.tabsFor(tabsKey, emptyMap(), pages))
        // A cross: Home first, then the pages with tiles round it.
        val crossKey = CanvasTabs.keyFor("_canvas")
        assertEquals(listOf(1, 0, 4), CanvasTabs.tabsFor(crossKey, emptyMap(), pages).map { it.page })
        // Saved ones win.
        val saved = listOf(CanvasTab(5, name = "Mine"))
        assertEquals(saved, CanvasTabs.tabsFor(crossKey, mapOf(crossKey to saved), pages))
        assertFalse(tabsKey == crossKey)
    }

    @Test
    fun aTriggerBelongsToOneDashboard() {
        val tabs = CanvasTabs.defaultTabs()
        val placed = CanvasTabs.placed(tabs, tabs[1].copy(trigger = TabTrigger.ENGINE_FAULT), 1)
        assertEquals(1, CanvasTabs.tabFor(placed, TabTrigger.ENGINE_FAULT)?.page)
        assertNull(placed.first { it.page == 2 }.trigger)
        assertNull(CanvasTabs.tabFor(placed, TabTrigger.DRIVING))
    }

    @Test
    fun aDashboardGoesWhereItIsPlaced() {
        val tabs = CanvasTabs.defaultTabs()
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
    fun theTabsFirstPagesFitTheGridAndMatchTheTabs() {
        val pages = DashboardStore.tabsPages()
        assertEquals(DashboardStore.PAGE_COUNT, pages.size)
        // The Map tab is the map alone; the others have tiles.
        assertTrue(pages[0].isEmpty())
        CanvasTabs.defaultTabs().drop(1).forEach { assertTrue(pages[it.page].isNotEmpty()) }
        pages.forEach { page ->
            page.forEach { t -> assertTrue(t.x >= 0 && t.y >= 0 && t.x + t.w <= GRID_COLS && t.y + t.h <= GRID_ROWS) }
            page.forEachIndexed { i, a -> page.drop(i + 1).forEach { b -> assertFalse(a.overlaps(b)) } }
        }
    }
}
