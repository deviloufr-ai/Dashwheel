package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The first run for a stranger: dashboards without car tiles until an
 * adapter is chosen, the car tiles added afterwards, and a car profile that
 * never presents the preset as the driver's car.
 */
class FirstRunSetupTest {

    private val withObd = TemplateScreen.of(1280f, 576f, obdPaired = true, driverOnRight = false, mapsDocked = false, dockApps = emptyList())
    private val withoutObd = withObd.copy(obdPaired = false)

    private fun kinds(page: List<DashboardItem>) = page.filterIsInstance<DashboardItem.BuiltinWidget>().map { it.kind }.toSet()

    private fun isObd(kind: BuiltinKind) = TemplatePlacer.needsObd(kind)

    private fun noOverlaps(page: List<DashboardItem>) {
        for (i in page.indices) for (j in i + 1 until page.size) {
            assertFalse("${page[i]} overlaps ${page[j]}", page[i].overlaps(page[j]))
        }
        page.forEach { assertTrue("$it out of bounds", it.x >= 0 && it.y >= 0 && it.x + it.w <= GRID_COLS && it.y + it.h <= GRID_ROWS) }
    }

    // --- Defaults without an adapter ---------------------------------------------------------

    @Test
    fun theDefaultsWithoutAnAdapterHoldNoCarTile() {
        // What DashboardStore.defaultPages builds on a fresh install, where nothing can be dialled.
        val pages = TemplatePlacer.pages(DashTemplate.DAILY, withoutObd)
        assertTrue(pages.flatten().filterIsInstance<DashboardItem.BuiltinWidget>().none { isObd(it.kind) })
        // Still something on every page the template fills: the stand-ins and the rest.
        DashTemplate.DAILY.pages.keys.forEach { p -> assertTrue("page $p empty", pages[p].isNotEmpty()) }
    }

    // --- Adding the car tiles once an adapter is chosen --------------------------------------

    @Test
    fun untouchedDefaultPagesAreLaidOutAgainWithTheCarTiles() {
        val before = TemplatePlacer.pages(DashTemplate.DAILY, withoutObd)
        val after = TemplatePlacer.addCarTiles(before, DashTemplate.DAILY, withObd)
        assertEquals(TemplatePlacer.pages(DashTemplate.DAILY, withObd), after)
        assertTrue(after.flatten().filterIsInstance<DashboardItem.BuiltinWidget>().any { isObd(it.kind) })
    }

    @Test
    fun anEmptyPageIsFilledAndAnArrangedPageGetsTheCarTilesInItsFreeRoom() {
        val before = TemplatePlacer.pages(DashTemplate.DAILY, withoutObd).toMutableList()
        // Page 2 (range, fault codes, service, battery) cleared by the driver; page 3 with one small tile of their own.
        before[2] = emptyList()
        before[3] = listOf(DashboardItem.BuiltinWidget(BuiltinKind.CLOCK, 0, 0, 4, 2))
        val after = TemplatePlacer.addCarTiles(before, DashTemplate.DAILY, withObd)

        assertEquals(TemplatePlacer.page(DashTemplate.DAILY.pages.getValue(2), withObd), after[2])

        val page3 = after[3]
        noOverlaps(page3)
        assertTrue(BuiltinKind.CLOCK in kinds(page3))
        assertEquals(DashboardItem.BuiltinWidget(BuiltinKind.CLOCK, 0, 0, 4, 2), page3.first())
        // The template's car tiles for that page, as far as they fit.
        assertTrue(BuiltinKind.TELEMETRY in kinds(page3))
        assertTrue(BuiltinKind.WARMUP in kinds(page3))
        page3.filterIsInstance<DashboardItem.BuiltinWidget>().filter { isObd(it.kind) }.forEach {
            assertTrue("$it too narrow", it.w >= withObd.minCols)
            assertTrue("$it too short", it.h >= 2)
        }
    }

    @Test
    fun aFullPageOfTheDriversOwnIsLeftAlone() {
        val own = listOf(
            DashboardItem.BuiltinWidget(BuiltinKind.CLOCK, 0, 0, GRID_COLS, GRID_ROWS / 2),
            DashboardItem.BuiltinWidget(BuiltinKind.WEATHER, 0, GRID_ROWS / 2, GRID_COLS, GRID_ROWS - GRID_ROWS / 2)
        )
        val pages = List(DashboardStore.PAGE_COUNT) { own }
        assertEquals(pages, TemplatePlacer.addCarTiles(pages, DashTemplate.DAILY, withObd))
    }

    // --- The car profile ------------------------------------------------------------------

    @Test
    fun aFreshInstallHasNoCarAndNeverFallsBackToThePresetsName() {
        assertFalse(CarProfile.NONE.known)
        val back = CarProfile.fromJson(CarProfile.NONE.toJson())
        assertFalse(back.known)
        assertEquals("", back.name)
        // The derived figures still have safe values to run on.
        assertTrue(CarProfile.NONE.tank > 0)
        assertTrue(CarProfile.NONE.hotC > CarProfile.NONE.coldC)
        // And the AI prompt does not name the preset either.
        assertFalse(CarProfile.NONE.promptDescription().contains("Picasso"))
    }

    @Test
    fun theShortFormsFieldsRoundTripAndComposeTheName() {
        val car = CarProfile(name = CarProfile.composeName("Peugeot", " 308 ", 2016), make = "Peugeot", model = "308", year = 2016, fuel = FuelType.PETROL)
        assertEquals("Peugeot 308 2016", car.name)
        val back = CarProfile.fromJson(car.toJson())
        assertEquals(car.make, back.make)
        assertEquals(car.model, back.model)
        assertEquals(car.year, back.year)
        assertEquals(FuelType.PETROL, back.fuel)
        // Missing parts are simply left out.
        assertEquals("308", CarProfile.composeName("", "308", null))
        assertEquals("", CarProfile.composeName(" ", "", null))
    }

    @Test
    fun anElectricCarReadsBackAsOne() {
        val back = CarProfile.fromJson(CarProfile(name = "Zoe", fuel = FuelType.ELECTRIC).toJson())
        assertEquals(FuelType.ELECTRIC, back.fuel)
        assertFalse(back.diesel)
    }
}
