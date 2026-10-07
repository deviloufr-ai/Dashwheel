package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Template placement: every page covered, readable, and arranged around the driver. */
class DashTemplatesTest {

    private val full = TemplateScreen.of(1280f, 576f, obdPaired = true, driverOnRight = false, mapsDocked = false, dockApps = emptyList())
    private val half = TemplateScreen.of(640f, 576f, obdPaired = true, driverOnRight = false, mapsDocked = true, dockApps = emptyList())

    private fun widgets(page: List<DashboardItem>) = page.filterIsInstance<DashboardItem.BuiltinWidget>()

    private fun assertTiled(page: List<DashboardItem>, minCols: Int) {
        val covered = Array(GRID_ROWS) { IntArray(GRID_COLS) }
        page.forEach { t ->
            assertTrue("$t out of bounds", t.x >= 0 && t.y >= 0 && t.x + t.w <= GRID_COLS && t.y + t.h <= GRID_ROWS)
            if (t is DashboardItem.BuiltinWidget) {
                assertTrue("$t too narrow", t.w >= minCols)
                assertTrue("$t too short", t.h >= t.minH())
            }
            for (y in t.y until t.y + t.h) for (x in t.x until t.x + t.w) covered[y][x]++
        }
        if (page.isEmpty()) return
        covered.forEachIndexed { y, row -> row.forEachIndexed { x, n -> assertEquals("cell $x,$y", 1, n) } }
    }

    @Test
    fun aTemplateFillsTheRowByDefault_andTheColumnOnRequest() {
        val row = TemplatePlacer.pages(DashTemplate.DAILY, full, TemplatePlacer.ROW_PAGES)
        DashboardStore.ROW.forEach { assertTrue("page $it", row[it].isNotEmpty()) }
        (DashboardStore.COLUMN - DashboardStore.CENTER).forEach { assertTrue("page $it", row[it].isEmpty()) }
        val all = TemplatePlacer.pages(DashTemplate.DAILY, full, TemplatePlacer.ALL_PAGES)
        all.forEach { assertTrue(it.isNotEmpty()) }
        assertEquals(TemplatePlacer.pages(DashTemplate.DAILY, full), all)
        // Every page but Home has a name for the bar.
        for (template in DashTemplate.entries) {
            template.pages.forEach { (page, tp) -> if (page != DashboardStore.CENTER) assertTrue("$template $page", tp.preset != null) }
        }
    }

    @Test
    fun everyTemplateTilesEveryPage_fullAndHalf() {
        for (template in DashTemplate.entries) for (screen in listOf(full, half, full.copy(obdPaired = false))) {
            val pages = TemplatePlacer.pages(template, screen)
            assertEquals(DashboardStore.PAGE_COUNT, pages.size)
            pages.forEach { assertTiled(it, screen.minCols) }
        }
    }

    @Test
    fun everyTemplateTilesEveryPage_uprightScreen() {
        ScreenShape.vertical = true
        try {
            assertEquals(7, GRID_COLS)
            assertEquals(12, GRID_ROWS)
            val upright = TemplateScreen.of(720f, 1120f, obdPaired = true, driverOnRight = false, mapsDocked = false, dockApps = listOf("a", "b"))
            val underDock = TemplateScreen.of(720f, 560f, obdPaired = true, driverOnRight = false, mapsDocked = true, dockApps = emptyList())
            for (template in DashTemplate.entries) for (screen in listOf(upright, underDock)) {
                val pages = TemplatePlacer.pages(template, screen)
                pages.forEach { assertTiled(it, screen.minCols) }
                assertTrue(widgets(pages[DashboardStore.CENTER]).isNotEmpty())
            }
        } finally {
            ScreenShape.vertical = false
        }
    }

    @Test
    fun besideTheMapsDock_widgetsNeedMoreColumns_andTheMapIsLeftOut() {
        assertTrue(half.minCols > full.minCols)
        val home = TemplatePlacer.pages(DashTemplate.DAILY, half)[DashboardStore.CENTER]
        assertFalse(widgets(home).any { it.kind == BuiltinKind.NAVMAP })
        assertTrue(widgets(home).isNotEmpty())
    }

    @Test
    fun theMostImportantTileSitsOnTheDriversSide() {
        val lhd = widgets(TemplatePlacer.pages(DashTemplate.DAILY, full)[DashboardStore.CENTER])
        val rhd = widgets(TemplatePlacer.pages(DashTemplate.DAILY, full.copy(driverOnRight = true))[DashboardStore.CENTER])
        val mapL = lhd.single { it.kind == BuiltinKind.NAVMAP }
        val mapR = rhd.single { it.kind == BuiltinKind.NAVMAP }
        assertEquals(0, mapL.x)
        assertEquals(GRID_COLS, mapR.x + mapR.w)
        // The map is the biggest tile on Home.
        assertTrue(lhd.all { it === mapL || it.w * it.h < mapL.w * mapL.h })
    }

    @Test
    fun withoutAnAdapter_carDataIsHeldBack_andTelemetryBecomesTrip() {
        val noObd = full.copy(obdPaired = false)
        // The car page keeps what the CAN box and the servicing planner give.
        val carPage = TemplatePlacer.pages(DashTemplate.DAILY, noObd)[2]
        assertEquals(setOf(BuiltinKind.RANGE, BuiltinKind.SERVICE), widgets(carPage).map { it.kind }.toSet())
        val obdKinds = setOf(
            BuiltinKind.TELEMETRY, BuiltinKind.OBD_DTC, BuiltinKind.OBD_ALL, BuiltinKind.BATTERY,
            BuiltinKind.WARMUP, BuiltinKind.FILTER_CARE, BuiltinKind.ECO_DRIVE, BuiltinKind.BREAK_TIMER
        )
        for (template in DashTemplate.entries) {
            assertFalse(TemplatePlacer.pages(template, noObd).flatten().any { it is DashboardItem.BuiltinWidget && it.kind in obdKinds })
        }
        val engine = TemplatePage(listOf(BuiltinKind.TELEMETRY, BuiltinKind.WARMUP))
        assertEquals(listOf(BuiltinKind.TRIP), TemplatePlacer.kindsFor(engine, noObd))
    }

    @Test
    fun theFuelTilesNeedTheCanBoxOrTheAdapter() {
        val page = TemplatePage(listOf(BuiltinKind.RANGE, BuiltinKind.FUEL_TO_DEST, BuiltinKind.SERVICE))
        assertEquals(page.kinds, TemplatePlacer.kindsFor(page, full.copy(obdPaired = false)))
        assertEquals(page.kinds, TemplatePlacer.kindsFor(page, full.copy(canbox = false)))
        assertEquals(listOf(BuiltinKind.SERVICE), TemplatePlacer.kindsFor(page, full.copy(obdPaired = false, canbox = false)))
    }

    @Test
    fun theTyresNeedTheTpmsApp() {
        for (template in DashTemplate.entries) {
            val kinds = TemplatePlacer.pages(template, full.copy(tyres = false)).flatten().let(::widgets).map { it.kind }
            assertFalse("$template offers the tyres without the TPMS app", BuiltinKind.TYRES in kinds)
        }
    }

    @Test
    fun templatesLeaveToolsAndEmptyTilesToTheAddSheet() {
        val tools = setOf(BuiltinKind.GFORCE, BuiltinKind.OBD_ALL, BuiltinKind.CAN_MON, BuiltinKind.QUICK_DIAL, BuiltinKind.PARKING)
        for (template in DashTemplate.entries) {
            assertTrue("$template", template.pages.values.flatMap { it.kinds }.none { it in tools })
        }
        // And every template brings the car-care tiles that carry the spoken warnings.
        for (template in DashTemplate.entries) {
            val kinds = template.pages.values.flatMap { it.kinds }.toSet()
            assertTrue("$template", kinds.containsAll(setOf(BuiltinKind.OBD_DTC, BuiltinKind.BATTERY, BuiltinKind.SERVICE)))
        }
    }

    @Test
    fun homeGetsADockAlongTheBottom_keepingTheUsersApps() {
        val mine = listOf(listOf(DashboardItem.AppShortcut("com.example.radio")), emptyList())
        val apps = TemplatePlacer.dockApps(mine, installed = setOf("com.waze"))
        assertEquals(listOf("com.example.radio"), apps)
        assertEquals(listOf("com.waze"), TemplatePlacer.dockApps(emptyList(), installed = setOf("com.waze", "x.y")))

        val home = TemplatePlacer.pages(DashTemplate.ROAD_TRIP, full.copy(dockApps = apps))[DashboardStore.CENTER]
        val dock = home.filterIsInstance<DashboardItem.LaunchBar>().single()
        assertEquals(listOf(GRID_ROWS - 1, GRID_COLS, 1), listOf(dock.y, dock.w, dock.h))
        assertTiled(home, full.minCols)
    }

    @Test
    fun withoutRoot_theCanboxTilesAreLeftOut_andNothingStandsInForThem() {
        val noRoot = full.copy(canbox = false)
        for (template in DashTemplate.entries) {
            val kinds = TemplatePlacer.pages(template, noRoot).flatten().let(::widgets).map { it.kind }
            assertFalse("$template offers the doors without root", BuiltinKind.DOORS in kinds)
            assertFalse("$template offers the CAN monitor without root", BuiltinKind.CAN_MON in kinds)
        }
        // With root the same page keeps them, so the rule is what removes them.
        val page = TemplatePage(listOf(BuiltinKind.TELEMETRY, BuiltinKind.DOORS, BuiltinKind.CAN_MON))
        assertEquals(page.kinds, TemplatePlacer.kindsFor(page, full))
        assertEquals(listOf(BuiltinKind.TELEMETRY), TemplatePlacer.kindsFor(page, noRoot))
        // The pages still tile the screen without them.
        for (template in DashTemplate.entries) TemplatePlacer.pages(template, noRoot).forEach { assertTiled(it, noRoot.minCols) }
    }
}
