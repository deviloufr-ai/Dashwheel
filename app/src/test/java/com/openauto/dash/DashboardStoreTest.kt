package com.openauto.dash

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Grid placement rules and the layout JSON, both pure Kotlin over [DashboardItem]. */
class DashboardStoreTest {

    private fun widget(kind: BuiltinKind, x: Int, y: Int, w: Int, h: Int) =
        DashboardItem.BuiltinWidget(kind, x, y, w, h)

    private fun noOverlaps(items: List<DashboardItem>) {
        for (i in items.indices) for (j in i + 1 until items.size) {
            assertFalse("${items[i]} overlaps ${items[j]}", items[i].overlaps(items[j]))
        }
    }

    @Before
    fun resetRetained() {
        // The store is a singleton; make sure no retained tiles leak between tests.
        DashboardStore.parsePages("[]")
    }

    // --- placement -----------------------------------------------------------

    @Test
    fun firstFreeCellScansRowByRow() {
        assertEquals(0 to 0, DashboardStore.firstFreeCell(emptyList(), 3, 2))
        val page = listOf(widget(BuiltinKind.CLOCK, 0, 0, 3, 2))
        assertEquals(3 to 0, DashboardStore.firstFreeCell(page, 3, 2))
        // Nothing fits a full-width span next to it: goes to the next row.
        assertEquals(0 to 2, DashboardStore.firstFreeCell(page, GRID_COLS, 2))
    }

    @Test
    fun canPlaceRespectsBoundsAndOtherTiles() {
        val page = listOf(widget(BuiltinKind.CLOCK, 0, 0, 3, 2))
        assertFalse(DashboardStore.canPlace(page, null, 1, 0, 3, 2))
        assertTrue(DashboardStore.canPlace(page, 0, 1, 0, 3, 2)) // ignoring itself
        assertTrue(DashboardStore.canPlace(page, null, 3, 0, 3, 2))
        assertFalse(DashboardStore.canPlace(page, null, GRID_COLS - 2, 0, 3, 2))
        assertFalse(DashboardStore.canPlace(page, null, -1, 0, 3, 2))
    }

    @Test
    fun moveToFreeCellJustMoves() {
        val page = listOf(widget(BuiltinKind.CLOCK, 0, 0, 3, 2), widget(BuiltinKind.AUDIO, 3, 0, 3, 2))
        val result = DashboardStore.moveResolving(page, 0, 0, 4)
        assertNotNull(result)
        assertEquals(widget(BuiltinKind.CLOCK, 0, 4, 3, 2), result!![0])
        assertEquals(page[1], result[1])
    }

    @Test
    fun moveOntoSingleSameSizedTileSwaps() {
        val page = listOf(widget(BuiltinKind.CLOCK, 0, 0, 3, 2), widget(BuiltinKind.AUDIO, 3, 0, 3, 2))
        val result = DashboardStore.moveResolving(page, 0, 3, 0)!!
        assertEquals(widget(BuiltinKind.CLOCK, 3, 0, 3, 2), result[0])
        assertEquals(widget(BuiltinKind.AUDIO, 0, 0, 3, 2), result[1])
    }

    @Test
    fun moveOntoSeveralTilesNudgesThemAside() {
        val page = listOf(
            widget(BuiltinKind.CLOCK, 0, 0, 3, 2),
            widget(BuiltinKind.AUDIO, 3, 0, 3, 2),
            widget(BuiltinKind.WEATHER, 6, 0, 3, 2)
        )
        val result = DashboardStore.moveResolving(page, 0, 4, 0)
        assertNotNull(result)
        assertEquals(4, result!![0].x)
        assertEquals(0, result[0].y)
        assertEquals(3, result.size)
        noOverlaps(result)
    }

    @Test
    fun moveOutOfBoundsIsRefused() {
        val page = listOf(widget(BuiltinKind.CLOCK, 0, 0, 3, 2))
        assertNull(DashboardStore.moveResolving(page, 0, GRID_COLS - 1, 0))
        assertNull(DashboardStore.moveResolving(page, 5, 0, 0))
    }

    @Test
    fun nearestFreeCellPrefersTheClosestSpot() {
        val page = listOf(widget(BuiltinKind.CLOCK, 0, 0, 3, 2))
        assertEquals(3 to 0, DashboardStore.nearestFreeCell(page, 3, 2, 2, 0))
        assertEquals(0 to 3, DashboardStore.nearestFreeCell(page, 3, 2, 0, 3)) // free right there
        assertEquals(0 to 2, DashboardStore.nearestFreeCell(page, 3, 2, 0, 1)) // one row down beats four across
        assertNull(DashboardStore.nearestFreeCell(page, GRID_COLS, GRID_ROWS, 0, 0))
    }

    @Test
    fun repairOverlapsKeepsTheFirstAndRelocatesTheRest() {
        val page = listOf(widget(BuiltinKind.CLOCK, 0, 0, 3, 2), widget(BuiltinKind.AUDIO, 0, 0, 3, 2))
        val fixed = DashboardStore.repairOverlaps(page)
        assertEquals(2, fixed.size)
        assertEquals(page[0], fixed[0])
        noOverlaps(fixed)
    }

    // --- JSON ----------------------------------------------------------------

    @Test
    fun layoutRoundTripsThroughJson() {
        val pages = listOf(
            listOf(
                widget(BuiltinKind.NAVMAP, 0, 0, 5, 3),
                DashboardItem.AppShortcut("com.example.a", 5, 0, 2, 2),
                DashboardItem.SplitPair("com.example.a", "com.example.b", 7, 0, 2, 2),
                DashboardItem.LaunchBar(listOf("com.example.a", "com.example.b"), 0, 3, 8, 1),
                DashboardItem.SystemWidget(42, 0, 4, 5, 3),
                DashboardItem.AppWindow("com.google.android.apps.youtube.music", 5, 3, 5, 3)
            ),
            emptyList(),
            listOf(widget(BuiltinKind.CLOCK, 0, 0, 3, 2))
        )
        val json = DashboardStore.serializePages(pages)
        assertTrue(json.startsWith("{"))
        assertEquals(pages, DashboardStore.parsePages(json))
    }

    @Test
    fun legacyBareArrayStillLoads() {
        val legacy = """[[{"t":"builtin","k":"CLOCK","gx":1,"gy":2,"gw":3,"gh":2}],[],[]]"""
        val pages = DashboardStore.parsePages(legacy)!!
        assertEquals(3, pages.size)
        assertEquals(listOf(widget(BuiltinKind.CLOCK, 1, 2, 3, 2)), pages[0])
    }

    @Test
    fun legacyTileWithoutCoordinatesGetsItsKindDefaultSize() {
        val pages = DashboardStore.parsePages("""[[{"t":"builtin","k":"CLOCK"}]]""")!!
        val clock = pages[0].single()
        assertEquals(-1, clock.x) // unplaced sentinel: load() flows it in
        assertEquals(BuiltinKind.CLOCK.defaultW, clock.w)
        assertEquals(BuiltinKind.CLOCK.defaultH, clock.h)
    }

    @Test
    fun unknownTilesAreCarriedThroughToTheNextSave() {
        val fromNewerBuild = """{"v":9,"pages":[[
            {"t":"hologram","gx":0,"gy":0,"gw":3,"gh":2},
            {"t":"builtin","k":"WARP_DRIVE","gx":3,"gy":0,"gw":3,"gh":2},
            {"t":"app","pkg":"com.example.a","gx":6,"gy":0,"gw":2,"gh":2}
        ]]}"""
        val pages = DashboardStore.parsePages(fromNewerBuild)!!
        assertEquals(listOf(DashboardItem.AppShortcut("com.example.a", 6, 0, 2, 2)), pages[0])

        val saved = DashboardStore.serializePages(List(DashboardStore.PAGE_COUNT) { pages.getOrElse(it) { emptyList() } })
        assertTrue(saved.contains("hologram"))
        assertTrue(saved.contains("WARP_DRIVE"))
        assertTrue(saved.contains("com.example.a"))
    }

    @Test
    fun droppedKindsLeaveTheLayoutInsteadOfBeingRetained() {
        // The 3D car widget was removed; a layout saved with one loses that tile
        // for good, while tiles from a newer build are still carried through.
        val saved = """{"v":1,"pages":[[
            {"t":"builtin","k":"CAR3D","gx":0,"gy":0,"gw":4,"gh":3},
            {"t":"builtin","k":"WARP_DRIVE","gx":4,"gy":0,"gw":3,"gh":2},
            {"t":"builtin","k":"CLOCK","gx":7,"gy":0,"gw":3,"gh":2}
        ]]}"""
        val pages = DashboardStore.parsePages(saved)!!
        assertEquals(listOf(BuiltinKind.CLOCK), pages[0].map { (it as DashboardItem.BuiltinWidget).kind })

        val again = DashboardStore.serializePages(List(DashboardStore.PAGE_COUNT) { pages.getOrElse(it) { emptyList() } })
        assertFalse(again.contains("CAR3D"))
        assertTrue(again.contains("WARP_DRIVE"))
    }

    @Test
    fun unknownTilesStayWithTheirOwnLayout() {
        val full = """{"v":9,"pages":[[{"t":"hologram","gx":0,"gy":0,"gw":3,"gh":2}]]}"""
        val half = """{"v":9,"pages":[[{"t":"jetpack","gx":0,"gy":0,"gw":3,"gh":2}]]}"""
        val fullPages = DashboardStore.parsePages(full, "")!!
        // Reading the other arrangement (as a page swipe does) must not swap its unknown tiles in.
        DashboardStore.parsePages(half, "_half")

        val saved = DashboardStore.serializePages(List(DashboardStore.PAGE_COUNT) { fullPages.getOrElse(it) { emptyList() } }, "")
        assertTrue(saved.contains("hologram"))
        assertFalse(saved.contains("jetpack"))
    }

    // --- tile designs ---------------------------------------------------------

    @Test
    fun tileDesignRoundTripsAndSurvivesMovesAndResizes() {
        val gauge = widget(BuiltinKind.SPEED_HUD, 0, 0, 3, 2).copy(design = WidgetDesign.NEON)
        val pages = listOf(listOf(gauge, widget(BuiltinKind.CLOCK, 3, 0, 3, 2)), emptyList(), emptyList())
        assertEquals(pages, DashboardStore.parsePages(DashboardStore.serializePages(pages)))

        val moved = DashboardStore.moveResolving(pages[0], 0, 6, 3)!!
        assertEquals(WidgetDesign.NEON, (moved[0] as DashboardItem.BuiltinWidget).design)
        assertEquals(WidgetDesign.NEON, (gauge.withCell(0, 0, 5, 3) as DashboardItem.BuiltinWidget).design)
    }

    @Test
    fun standardDesignIsNotWrittenSoOlderBuildsSeeTheSameJson() {
        val json = DashboardStore.serializePages(listOf(listOf(widget(BuiltinKind.CLOCK, 0, 0, 3, 2)), emptyList(), emptyList()))
        assertFalse(json.contains("\"d\""))
        val styled = DashboardStore.serializePages(
            listOf(listOf(widget(BuiltinKind.CLOCK, 0, 0, 3, 2).copy(design = WidgetDesign.FLAP)), emptyList(), emptyList())
        )
        assertTrue(styled.contains("\"d\":\"FLAP\""))
    }

    @Test
    fun unknownOrMissingDesignFallsBackToStandard() {
        val pages = DashboardStore.parsePages(
            """{"v":1,"pages":[[{"t":"builtin","k":"CLOCK","d":"HOLOGRAM","gx":0,"gy":0,"gw":3,"gh":2},
               {"t":"builtin","k":"WEATHER","gx":3,"gy":0,"gw":4,"gh":2}]]}"""
        )!!
        assertEquals(WidgetDesign.STANDARD, (pages[0][0] as DashboardItem.BuiltinWidget).design)
        assertEquals(WidgetDesign.STANDARD, (pages[0][1] as DashboardItem.BuiltinWidget).design)
        assertEquals(WidgetDesign.STANDARD, WidgetDesign.fromName(null))
        assertEquals(WidgetDesign.NEON, WidgetDesign.fromName("NEON"))
    }

    @Test
    fun retiredDesignsBecomeTheirClosestSibling() {
        assertEquals(WidgetDesign.HERO, WidgetDesign.fromName("MINIMAL"))
        assertEquals(WidgetDesign.NEON, WidgetDesign.fromName("GAUGE"))
        assertEquals(WidgetDesign.CHRONO, WidgetDesign.fromName("BLUEPRINT"))
        val pages = DashboardStore.parsePages(
            """{"v":1,"pages":[[{"t":"builtin","k":"MEDIA","d":"RING","gx":0,"gy":0,"gw":4,"gh":2}]]}"""
        )!!
        assertEquals(WidgetDesign.GLASS, (pages[0][0] as DashboardItem.BuiltinWidget).design)
    }

    @Test
    fun anyTileCanShrinkToOneCell() {
        val clock = widget(BuiltinKind.CLOCK, 2, 2, 3, 2).withCell(2, 2, 1, 1)
        assertEquals(1, clock.w)
        assertEquals(1, clock.h)
        val bar = DashboardItem.LaunchBar(listOf("a"), 0, 6, 8, 1).withCell(0, 6, 1, 1)
        assertEquals(1, bar.w)
    }

    @Test
    fun zoomStepsOnWholeTenthsWithinItsRange() {
        assertEquals(1.1f, zoomStep(1f, 1), 0f)
        assertEquals(0.9f, zoomStep(1f, -1), 0f)
        // Ten taps up and ten down come back exactly, never 0.9999.
        var z = 1f
        repeat(10) { z = zoomStep(z, 1) }
        repeat(10) { z = zoomStep(z, -1) }
        assertEquals(1f, z, 0f)
        assertEquals(ZOOM_MAX, zoomStep(ZOOM_MAX, 1), 0f)
        assertEquals(ZOOM_MIN, zoomStep(ZOOM_MIN, -1), 0f)
        assertEquals(ZOOM_MAX, widget(BuiltinKind.CLOCK, 0, 0, 3, 2).withZoom(9f).zoom, 0f)
    }

    @Test
    fun zoomRoundTripsAndSurvivesMovesAndResizes() {
        val zoomed = widget(BuiltinKind.CLOCK, 0, 0, 3, 2).withZoom(1.4f)
        val pages = listOf(listOf(zoomed, DashboardItem.AppShortcut("a", 4, 0).withZoom(0.7f)), emptyList(), emptyList())
        assertEquals(pages, DashboardStore.parsePages(DashboardStore.serializePages(pages)))
        assertEquals(1.4f, zoomed.withCell(5, 3, 2, 1).zoom, 0f)
        // The usual size is not written, so older builds see the same JSON.
        assertFalse(DashboardStore.serializePages(listOf(listOf(widget(BuiltinKind.CLOCK, 0, 0, 3, 2)), emptyList(), emptyList())).contains("\"z\""))
        assertFalse(DashboardItem.AppWindow("maps").canZoom())
        assertTrue(zoomed.canZoom())
    }

    @Test
    fun everyWidgetHasAtLeastTenDesigns() {
        val generic = WidgetDesign.entries.filter { !it.isSignature }
        // The standard renderer plus the generic faces apply to every built-in kind.
        assertTrue(generic.size >= 11)
        assertEquals(1, generic.count { it.layout == null })
        // No two generic designs are the same layout in the same material.
        assertEquals(generic.size, generic.map { it.layout to it.look }.toSet().size)
        BuiltinKind.entries.forEach { kind -> assertTrue("$kind", WidgetDesign.offeredFor(kind, framed = false).size >= 10) }
    }

    @Test
    fun copperSetReachesEveryWidget() {
        val copper = listOf(WidgetDesign.COPPER_COCKPIT, WidgetDesign.TRI_LED, WidgetDesign.COPPER_BLADE, WidgetDesign.LIGHT_BAR)
        // Generic designs: every redrawn widget offers them, and they are saved by name like the rest.
        assertTrue(copper.none { it.isSignature })
        BuiltinKind.entries.forEach { kind -> assertTrue("$kind", WidgetDesign.offeredFor(kind, framed = false).containsAll(copper)) }
        copper.forEach { assertEquals(it, WidgetDesign.fromName(it.name)) }
        // Two materials, each worn by two different layouts.
        assertEquals(setOf(FaceLookKind.COPPER, FaceLookKind.PETROL), copper.map { it.look }.toSet())
        assertEquals(copper.size, copper.map { it.layout }.toSet().size)
    }

    @Test
    fun cabinSetReachesEveryWidget() {
        val cabins = listOf(WidgetDesign.LANE_VIEW, WidgetDesign.TREND, WidgetDesign.PULSE, WidgetDesign.CONTOUR)
        assertTrue(cabins.none { it.isSignature })
        BuiltinKind.entries.forEach { kind -> assertTrue("$kind", WidgetDesign.offeredFor(kind, framed = false).containsAll(cabins)) }
        cabins.forEach { assertEquals(it, WidgetDesign.fromName(it.name)) }
        // One cabin per material and per layout, all in their own picker family.
        assertEquals(cabins.size, cabins.map { it.look }.toSet().size)
        assertEquals(cabins.size, cabins.map { it.layout }.toSet().size)
        assertTrue(cabins.all { DesignFamily.of(it) == DesignFamily.CABINS })
        // The tyre map is only offered on the tyres.
        assertTrue(WidgetDesign.TYRE_MAP.appliesTo(BuiltinKind.TYRES))
        assertFalse(WidgetDesign.TYRE_MAP.appliesTo(BuiltinKind.MEDIA))
    }

    @Test
    fun faceNumberReadsTheHeadline() {
        val face = WidgetFace(Icons.Filled.Speed, "t", "24,5", "km")
        assertEquals(24.5f, faceNumber(face)!!, 0.001f)
        assertEquals(80f, faceNumber(face.copy(value = "--", fraction = 0.8f))!!, 0.001f)
        assertEquals(42f, faceNumber(face.copy(number = 42f))!!, 0.001f)
        assertNull(faceNumber(face.copy(value = "Song", textValue = true)))
    }

    @Test
    fun widgetSpecificDesignsOnlyReachTheirWidgets() {
        val framed = setOf(BuiltinKind.NAVMAP, BuiltinKind.PIP_ANCHOR, BuiltinKind.MY_CAR)
        // Every redrawn widget gets at least two designs made for it; live views get none.
        BuiltinKind.entries.filter { it !in framed }.forEach { kind ->
            assertTrue("$kind", WidgetDesign.entries.count { it.isSignature && it.appliesTo(kind) } >= 2)
        }
        framed.forEach { kind -> assertTrue(WidgetDesign.offeredFor(kind, framed = true).none { it.isSignature }) }
        assertTrue(WidgetDesign.THERMOMETER.appliesTo(BuiltinKind.WARMUP))
        assertFalse(WidgetDesign.THERMOMETER.appliesTo(BuiltinKind.MEDIA))
        // Picker order: Standard, the widget's own designs, then the generic ones.
        val offered = WidgetDesign.offeredFor(BuiltinKind.WARMUP, framed = false)
        assertEquals(WidgetDesign.STANDARD, offered.first())
        val firstGeneric = offered.indexOfFirst { !it.isSignature && it != WidgetDesign.STANDARD }
        assertTrue(offered.drop(1).take(firstGeneric - 1).all { it.isSignature })
        // A widget-specific design is saved and read back like any other.
        val tank = widget(BuiltinKind.RANGE, 0, 0, 3, 3).copy(design = WidgetDesign.FUEL_TANK)
        val pages = listOf(listOf(tank), emptyList(), emptyList())
        assertEquals(pages, DashboardStore.parsePages(DashboardStore.serializePages(pages)))
    }

    @Test
    fun garbageIsRejectedNotDefaulted() {
        assertNull(DashboardStore.parsePages("not json at all"))
        assertNull(DashboardStore.parsePages("{"))
    }

    // --- layout variants -----------------------------------------------------

    @Test
    fun variantsGetTheirOwnKeysAndBackups() {
        assertEquals("pages", DashboardStore.pagesKey(""))
        assertEquals("pages_backup", DashboardStore.backupKey(""))
        assertEquals("pages_half", DashboardStore.pagesKey("_half"))
        assertEquals("pages_backup_half", DashboardStore.backupKey("_half"))
        // A variant's backup must never collide with another variant's main key.
        assertFalse(DashboardStore.backupKey("") == DashboardStore.pagesKey("_half"))
    }

    @Test
    fun parsingOneVariantDoesNotLeakUnknownTilesIntoAnother() {
        val withStranger = """{"v":1,"pages":[[{"t":"hologram","gx":0,"gy":0,"gw":3,"gh":2}],[],[]]}"""
        DashboardStore.parsePages(withStranger)
        // The next layout parsed (another variant) starts clean.
        DashboardStore.parsePages("""{"v":1,"pages":[[],[],[]]}""")
        val saved = DashboardStore.serializePages(List(DashboardStore.PAGE_COUNT) { emptyList() })
        assertFalse(saved.contains("hologram"))
    }
}
