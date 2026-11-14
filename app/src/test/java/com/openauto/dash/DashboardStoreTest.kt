package com.openauto.dash

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import org.json.JSONObject
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
                DashboardItem.AppWindow("com.google.android.apps.youtube.music", 5, 3, 5, 3),
                DashboardItem.AppWindow("com.waze", 10, 3, 2, 3, inside = true)
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
        // Its cells were the coarse grid's: three of today's each way.
        assertEquals(listOf(widget(BuiltinKind.CLOCK, 1 * GRID_SCALE, 2 * GRID_SCALE, 3 * GRID_SCALE, 2 * GRID_SCALE)), pages[0])
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
        val fromNewerBuild = """{"schema":9,"pages":[[
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
        val saved = """{"schema":1,"pages":[[
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
        val full = """{"schema":9,"pages":[[{"t":"hologram","gx":0,"gy":0,"gw":3,"gh":2}]]}"""
        val half = """{"schema":9,"pages":[[{"t":"jetpack","gx":0,"gy":0,"gw":3,"gh":2}]]}"""
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
            """{"schema":1,"pages":[[{"t":"builtin","k":"CLOCK","d":"HOLOGRAM","gx":0,"gy":0,"gw":3,"gh":2},
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
            """{"schema":1,"pages":[[{"t":"builtin","k":"MEDIA","d":"RING","gx":0,"gy":0,"gw":4,"gh":2}]]}"""
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
        // Inside its tile an app is drawn at the tile's density, so zooming it works.
        assertTrue(DashboardItem.AppWindow("maps", inside = true).canZoom())
        assertTrue(zoomed.canZoom())
        // Where apps can run inside, a zoomed window tile moves inside to get its own density.
        assertTrue(DashboardItem.AppWindow("waze").canZoom(insideAllowed = true))
        assertFalse(DashboardItem.AppWindow("waze").runsInside(insideAllowed = true))
        assertTrue(DashboardItem.AppWindow("waze").withZoom(1.3f).let { it as DashboardItem.AppWindow }.runsInside(insideAllowed = true))
        assertFalse(DashboardItem.AppWindow("waze").withZoom(1.3f).let { it as DashboardItem.AppWindow }.runsInside(insideAllowed = false))
    }

    @Test
    fun appInsideItsTileIsSavedAsSuch() {
        val inside = DashboardItem.AppWindow("com.google.android.apps.youtube.music", 0, 0, 5, 3, inside = true)
        val json = DashboardStore.serializePages(listOf(listOf(inside), emptyList(), emptyList()))
        assertTrue(json.contains("\"in\":true"))
        assertEquals(inside, DashboardStore.parsePages(json)!!.first().first())
        // A window tile saved before this option stays a window.
        val old = """[[{"t":"appwin","pkg":"com.waze","gx":0,"gy":0,"gw":5,"gh":3}],[],[]]"""
        assertEquals(false, (DashboardStore.parsePages(old)!!.first().first() as DashboardItem.AppWindow).inside)
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
    fun cabinSetReachesEveryWidgetItCanShow() {
        val cabins = listOf(WidgetDesign.LANE_VIEW, WidgetDesign.TREND, WidgetDesign.PULSE, WidgetDesign.CONTOUR)
        val charts = listOf(WidgetDesign.TREND, WidgetDesign.PULSE, WidgetDesign.CONTOUR)
        assertTrue(cabins.none { it.isSignature })
        // The three charts draw no rows: a list widget would show its headline and nothing to read or tap.
        val lists = setOf(
            BuiltinKind.QUICK_DIAL, BuiltinKind.NOTIFICATIONS, BuiltinKind.CALENDAR, BuiltinKind.OBD_DTC, BuiltinKind.FUEL_PRICES,
            BuiltinKind.DRIVE_HISTORY
        )
        // Time charted over the last minutes is a straight line.
        val clocks = setOf(BuiltinKind.CLOCK, BuiltinKind.BREAK_TIMER)
        BuiltinKind.entries.forEach { kind ->
            val offered = WidgetDesign.offeredFor(kind, framed = false)
            assertTrue("$kind", WidgetDesign.LANE_VIEW in offered)
            charts.forEach { assertEquals("$kind $it", kind !in lists && !(it == WidgetDesign.TREND && kind in clocks), it in offered) }
        }
        // A tile saved with one of them before is still read back; it is drawn as Standard (DesignedTile).
        assertEquals(WidgetDesign.TREND, WidgetDesign.fromName("TREND"))
        assertFalse(WidgetDesign.TREND.appliesTo(BuiltinKind.OBD_DTC))
        assertTrue(WidgetDesign.PULSE.appliesTo(BuiltinKind.CLOCK))
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
        assertEquals(80f, faceNumber(face.copy(value = "Song", textValue = true, fraction = 0.8f))!!, 0.001f)
        assertEquals(42f, faceNumber(face.copy(number = 42f))!!, 0.001f)
        assertNull(faceNumber(face.copy(value = "Song", textValue = true)))
        // No reading yet: the "--" and its fraction of 0 are not a 0 for the chart.
        assertTrue(face.copy(value = "--").idle)
        assertFalse(face.idle)
        assertNull(faceNumber(face.copy(value = "--", fraction = 0f)))
        assertNull(faceNumber(face.copy(value = "--", fraction = 0.8f, number = 0f)))
    }

    @Test
    fun widgetSpecificDesignsOnlyReachTheirWidgets() {
        val framed = setOf(BuiltinKind.NAVMAP, BuiltinKind.PIP_ANCHOR, BuiltinKind.MAPS_INSIDE, BuiltinKind.MY_CAR, BuiltinKind.CAR_3D)
        // Every redrawn widget gets at least two designs made for it; live views get none,
        // and the dashboard bar, all buttons, has no designs at all.
        // The speed keeps one: the road sign went, a number in a red ring reads as the limit.
        BuiltinKind.entries.filter { it !in framed && it != BuiltinKind.DASH_BAR }.forEach { kind ->
            val least = if (kind == BuiltinKind.SPEED_HUD) 1 else 2
            assertTrue("$kind", WidgetDesign.entries.count { it.isSignature && it.appliesTo(kind) } >= least)
        }
        assertFalse(WidgetDesign.ROAD_SIGN.appliesTo(BuiltinKind.SPEED_HUD))
        // A retired design's tiles wear its successor.
        assertEquals(WidgetDesign.FACES, WidgetDesign.fromName("ROTARY_PHONE"))
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

    // --- tiles over the others (TileFrame) -----------------------------------

    private fun over(kind: BuiltinKind, x: Int, y: Int, w: Int, h: Int) =
        widget(kind, x, y, w, h).withFrame(TileFrame(overlap = true))

    @Test
    fun aTileOverTheOthersGoesAnywhereAndMovesNothing() {
        val map = widget(BuiltinKind.NAVMAP, 0, 0, 6, 4)
        val speed = over(BuiltinKind.SPEED_HUD, 8, 0, 2, 2)
        val page = listOf(map, speed)
        // Onto the map: allowed, and the map stays.
        assertTrue(DashboardStore.canPlace(page, 1, 1, 1, 2, 2))
        val moved = DashboardStore.moveResolving(page, 1, 1, 1)!!
        assertEquals(map, moved[0])
        assertEquals(speed.withCell(1, 1, 2, 2), moved[1])
        // Never out of the grid, though.
        assertFalse(DashboardStore.canPlace(page, 1, GRID_COLS - 1, 0, 2, 2))
        assertNull(DashboardStore.moveResolving(page, 1, GRID_COLS - 1, 0))
        // Resized over the map too.
        assertTrue(DashboardStore.canPlace(page, 1, 8, 0, 4, 4))
    }

    @Test
    fun tilesOverTheOthersAreNeverInTheGridsWay() {
        val speed = over(BuiltinKind.SPEED_HUD, 3, 0, 2, 2)
        val clock = widget(BuiltinKind.CLOCK, 0, 0, 3, 2)
        val page = listOf(speed, clock)
        // The clock moves under the speed without a swap or a nudge.
        assertTrue(DashboardStore.canPlace(page, 1, 3, 0, 3, 2))
        val moved = DashboardStore.moveResolving(page, 1, 3, 0)!!
        assertEquals(speed, moved[0])
        assertEquals(clock.withCell(3, 0, 3, 2), moved[1])
        // Its cells are free for a new tile, and the page has room there.
        assertEquals(0 to 0, DashboardStore.firstFreeCell(listOf(speed), 2, 2))
        assertEquals(3 to 0, DashboardStore.firstFreeCell(listOf(speed, clock), 3, 2))
        // A nudge of a grid tile never lands on cells only a tile over the others holds... it may: they are free.
        val audio = widget(BuiltinKind.AUDIO, 6, 0, 3, 2)
        val weather = widget(BuiltinKind.WEATHER, 9, 0, 3, 2)
        val nudged = DashboardStore.moveResolving(listOf(speed, clock, audio, weather), 1, 7, 0)!!
        noOverlaps(nudged.filter { it.inGrid() })
        assertEquals(speed, nudged[0])
    }

    @Test
    fun loadingKeepsTilesOverTheOthersWhereTheyAre() {
        val map = widget(BuiltinKind.NAVMAP, 0, 0, 6, 4)
        val speed = over(BuiltinKind.SPEED_HUD, 1, 1, 2, 2)
        val clock = over(BuiltinKind.CLOCK, 1, 1, 3, 2)
        // Meant to overlap: not moved apart, not dropped, in their order.
        assertEquals(listOf(map, speed, clock), DashboardStore.repairOverlaps(listOf(map, speed, clock)))
        // A grid tile on the same cells is still repaired, around the grid's tiles only.
        val audio = widget(BuiltinKind.AUDIO, 1, 1, 3, 2)
        val repaired = DashboardStore.repairOverlaps(listOf(map, speed, audio))
        assertEquals(speed, repaired[1])
        assertFalse(repaired[2].overlaps(map))
        // One off the grid's edge is pulled in, not thrown out.
        val far = over(BuiltinKind.GEAR, GRID_COLS - 1, GRID_ROWS - 1, 3, 3)
        val pulled = DashboardStore.repairOverlaps(listOf(far)).single()
        assertTrue(pulled.x + pulled.w <= GRID_COLS && pulled.y + pulled.h <= GRID_ROWS)
        assertFalse(pulled.inGrid())
    }

    @Test
    fun theFrameRoundTripsThroughTheLayoutJson() {
        val plain = widget(BuiltinKind.CLOCK, 0, 0, 3, 2)
        val framed = widget(BuiltinKind.SPEED_HUD, 1, 1, 2, 2)
            .withFrame(TileFrame(overlap = true, opacity = 0.6f, border = 3, borderColor = TileBorderColor.RED))
        val bordered = DashboardItem.AppShortcut("a.b", 5, 5, 2, 2).withFrame(TileFrame(border = 2))
        val pages = listOf(listOf(plain, framed, bordered), emptyList(), emptyList())
        val saved = DashboardStore.serializePages(pages)
        assertEquals(pages, DashboardStore.parsePages(saved))
        // Read back as JSON: org.json keeps no key order on the JVM, so the text itself is not searched.
        val tiles = JSONObject(saved).getJSONArray("pages").getJSONArray(0)
        // A plain tile writes none of it, so an older build reads its layout as before.
        val clockJson = tiles.getJSONObject(0)
        assertFalse(clockJson.has("ov") || clockJson.has("op") || clockJson.has("bw") || clockJson.has("bc"))
        // The accent is the default colour and is left out; a colour is kept only with a border.
        val shortcutJson = tiles.getJSONObject(2)
        assertEquals(2, shortcutJson.getInt("bw"))
        assertFalse(shortcutJson.has("bc"))
        val framedJson = tiles.getJSONObject(1)
        assertTrue(framedJson.getBoolean("ov"))
        assertEquals("RED", framedJson.getString("bc"))
        assertEquals(3, framedJson.getInt("bw"))
        // Out-of-range values read back in range; an unknown colour is the accent.
        val odd = """{"schema":2,"pages":[[{"t":"builtin","k":"CLOCK","gx":0,"gy":0,"gw":3,"gh":2,"op":0.01,"bw":99,"bc":"PINK"}]]}"""
        val read = DashboardStore.parsePages(odd)!![0].single()
        assertEquals(TileFrame(opacity = OPACITY_MIN, border = BORDER_MAX, borderColor = TileBorderColor.ACCENT), read.frame)
        // The frame survives being resized or zoomed.
        assertEquals(framed.frame, framed.withCell(0, 0, 2, 2).withZoom(1.5f).frame)
    }

    @Test
    fun opacityStepsOnTenthsAndStopsAtTheEnds() {
        assertEquals(0.9f, opacityStep(1f, -1), 0.0001f)
        assertEquals(0.5f, opacityStep(0.6f, -1), 0.0001f)
        assertEquals(1f, opacityStep(1f, 1), 0.0001f)
        assertEquals(OPACITY_MIN, opacityStep(OPACITY_MIN, -1), 0.0001f)
        assertEquals(OPACITY_MIN, opacityStep(0.25f, -1), 0.0001f)
    }

    @Test
    fun reorderingPutsATileOverTheOthersOnTopOrUnderThem() {
        val map = widget(BuiltinKind.NAVMAP, 0, 0, 6, 4)
        val speed = over(BuiltinKind.SPEED_HUD, 1, 1, 2, 2)
        val clock = over(BuiltinKind.CLOCK, 1, 1, 3, 2)
        val page = listOf(map, speed, clock)
        val (front, atFront) = DashboardStore.reorder(page, 1, toFront = true)
        assertEquals(listOf(map, clock, speed), front)
        assertEquals(2, atFront)
        val (back, atBack) = DashboardStore.reorder(page, 2, toFront = false)
        assertEquals(listOf(clock, map, speed), back)
        assertEquals(0, atBack)
        // A grid tile has no layer to change.
        assertEquals(page to 0, DashboardStore.reorder(page, 0, toFront = true))
    }

    @Test
    fun backIntoTheGridWhereItIsOrAtTheNearestFreeCells() {
        val map = widget(BuiltinKind.NAVMAP, 0, 0, 6, 4)
        val speed = over(BuiltinKind.SPEED_HUD, 8, 0, 2, 2)
        // Its cells are free: it lands where it is.
        val landed = DashboardStore.intoGrid(listOf(map, speed), 1)!!
        assertEquals(widget(BuiltinKind.SPEED_HUD, 8, 0, 2, 2), landed[1])
        // Over the map: the nearest free cells beside it.
        val onMap = DashboardStore.intoGrid(listOf(map, speed.withCell(1, 1, 2, 2)), 1)!!
        assertTrue(onMap[1].inGrid())
        noOverlaps(onMap)
        // Under the map's bottom edge, three cells away, rather than beside it, five away.
        assertEquals(1 to 4, onMap[1].x to onMap[1].y)
        // A full page: nowhere to land, so it stays over the others.
        val full = listOf(widget(BuiltinKind.NAVMAP, 0, 0, GRID_COLS, GRID_ROWS), speed)
        assertNull(DashboardStore.intoGrid(full, 1))
        // Another tile over the others does not take the cells.
        val twoOver = listOf(map, over(BuiltinKind.CLOCK, 8, 0, 3, 2), speed)
        assertEquals(widget(BuiltinKind.SPEED_HUD, 8, 0, 2, 2), DashboardStore.intoGrid(twoOver, 2)!![2])
    }

    @Test
    fun turningTheScreenKeepsTilesOverTheOthers() {
        val wide = List(DashboardStore.PAGE_COUNT) { if (it == DashboardStore.CENTER) listOf(widget(BuiltinKind.NAVMAP, 0, 0, 12, 7), over(BuiltinKind.SPEED_HUD, 9, 0, 3, 2)) else emptyList() }
        ScreenShape.vertical = true
        try {
            val upright = DashboardStore.reflow(wide)
            val home = upright[DashboardStore.CENTER]
            assertEquals(2, home.size)
            val speed = home.single { it is DashboardItem.BuiltinWidget && it.kind == BuiltinKind.SPEED_HUD }
            assertFalse(speed.inGrid())
            assertTrue(speed.x + speed.w <= GRID_COLS && speed.y + speed.h <= GRID_ROWS)
        } finally {
            ScreenShape.vertical = false
        }
    }

    // --- minimum sizes -------------------------------------------------------

    @Test
    fun everyKindsDefaultSizeIsAtLeastItsMinimum() {
        for (kind in BuiltinKind.entries) {
            val (w, h) = kind.minSize()
            assertTrue("$kind default ${kind.defaultW}x${kind.defaultH} under its minimum ${w}x$h", kind.defaultW >= w && kind.defaultH >= h)
            assertTrue("$kind minimum wider than the grid", w <= GRID_COLS && h <= GRID_ROWS)
        }
        // The kinds read at speed never shrink to one cell; a list keeps three columns.
        assertEquals(2 to 2, BuiltinKind.SPEED_HUD.coarseMinSize())
        assertEquals(3 to 2, BuiltinKind.QUICK_DIAL.coarseMinSize())
        assertEquals(3 to 3, BuiltinKind.NAVMAP.coarseMinSize())
        // In the grid's own cells, three to a coarse one.
        assertEquals(2 * GRID_SCALE to 2 * GRID_SCALE, BuiltinKind.SPEED_HUD.minSize())
        assertEquals(5 * GRID_SCALE to 3 * GRID_SCALE, BuiltinKind.NAVMAP.defaultW to BuiltinKind.NAVMAP.defaultH)
    }

    @Test
    fun aSavedTileSmallerThanItsMinimumStillLoadsAtItsSize() {
        // A one-cell speed tile from an older build, at the grid's far corner: kept, not grown into a neighbour or dropped.
        val small = widget(BuiltinKind.SPEED_HUD, GRID_COLS - 1, GRID_ROWS - 1, 1, 1)
        val neighbour = widget(BuiltinKind.CLOCK, GRID_COLS - 3, GRID_ROWS - 2, 2, 1)
        val pages = listOf(listOf(small, neighbour), emptyList(), emptyList())
        val back = DashboardStore.parsePages(DashboardStore.serializePages(pages))!!
        assertEquals(listOf(small, neighbour), back[0])
        assertEquals(listOf(small, neighbour), DashboardStore.repairOverlaps(back[0]))
        // Where it sits there is no room for its kind's minimum, so the resize floor is what is left.
        assertEquals(1, small.minW())
        assertEquals(1, small.minH())
        // In the open its minimum is its kind's.
        assertEquals(2 * GRID_SCALE, widget(BuiltinKind.SPEED_HUD, 0, 0, 1, 1).minW())
        // A tile that is not a widget stops at one coarse cell.
        assertEquals(GRID_SCALE, DashboardItem.AppShortcut("a.b", 0, 0, 1, 1).minW())
        // withCell keeps a saved span; the placer passes the minimum itself.
        assertEquals(1, widget(BuiltinKind.SPEED_HUD, 0, 0, 1, 1).withCell(0, 0, 1, 1).w)
    }

    @Test
    fun aLayoutFromTheCoarseGridIsScaledUpOnLoad() {
        assertEquals(COARSE_COLS * GRID_SCALE, GRID_COLS)
        assertEquals(COARSE_ROWS * GRID_SCALE, GRID_ROWS)
        // v1 and v2 placed tiles on the 12 x 7 grid: their cells are multiplied on load.
        val v1 = """{"schema":1,"pages":[[{"t":"builtin","k":"CLOCK","gx":1,"gy":2,"gw":3,"gh":2}]]}"""
        assertEquals(listOf(widget(BuiltinKind.CLOCK, 3, 6, 9, 6)), DashboardStore.parsePages(v1)!![0])
        val v2 = """{"schema":2,"pages":[[{"t":"app","pkg":"a.b","gx":2,"gy":0,"gw":2,"gh":2,"ov":true}]]}"""
        assertEquals(listOf(DashboardItem.AppShortcut("a.b", 6, 0, 6, 6).withFrame(TileFrame(overlap = true))), DashboardStore.parsePages(v2)!![0])
        // The bare array from before v1 too.
        val bare = """[[{"t":"app","pkg":"a.b","gx":2,"gy":0,"gw":2,"gh":2}]]"""
        assertEquals(listOf(DashboardItem.AppShortcut("a.b", 6, 0, 6, 6)), DashboardStore.parsePages(bare)!![0])
        // A tile saved without cells gets its kind's default span, not three times it, and is placed on load.
        val unplaced = DashboardStore.parsePages("""{"schema":1,"pages":[[{"t":"builtin","k":"CLOCK"}]]}""")!![0].single()
        assertEquals(-1, unplaced.x)
        assertEquals(BuiltinKind.CLOCK.defaultW to BuiltinKind.CLOCK.defaultH, unplaced.w to unplaced.h)
        // What this build saves says v3 and reads back as it is.
        val page = listOf(widget(BuiltinKind.CLOCK, 3, 6, 9, 6), DashboardItem.AppShortcut("a.b", 12, 0, 6, 6))
        val saved = DashboardStore.serializePages(listOf(page))
        assertEquals(3, JSONObject(saved).getInt("schema"))
        assertEquals(page, DashboardStore.parsePages(saved)!![0])
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
        assertEquals("pages_v", DashboardStore.pagesKey("_v"))
        assertEquals("pages_backup_v", DashboardStore.backupKey("_v"))
        // The legacy copies' keys, read once by the merge.
        assertEquals("pages_half", DashboardStore.pagesKey(DashboardStore.LEGACY_HALF))
        // A variant's backup must never collide with another variant's main key.
        assertFalse(DashboardStore.backupKey("") == DashboardStore.pagesKey("_v"))
    }

    // --- one arrangement per screen direction --------------------------------

    @Test
    fun theMergeKeepsTheArrangementLastUsed_whenItDiffersFromTheBase() {
        // The copy the user was on, edited: it becomes the one arrangement.
        assertEquals("half", DashboardStore.chooseKept(base = "base", lastUsed = "half"))
        // A copy never edited (still the base's twin), or none at all: the base stays.
        assertEquals("base", DashboardStore.chooseKept(base = "base", lastUsed = "base"))
        assertEquals("base", DashboardStore.chooseKept(base = "base", lastUsed = null))
        // Only ever used the copy: it is kept.
        assertEquals("canvas", DashboardStore.chooseKept<String>(base = null, lastUsed = "canvas"))
        assertNull(DashboardStore.chooseKept<String>(base = null, lastUsed = null))
    }

    @Test
    fun theLegacySuffixFollowsTheLayoutAndTheme() {
        assertEquals("", DashboardStore.legacySuffix(canvas = false, overApp = false, docked = false))
        assertEquals("_half", DashboardStore.legacySuffix(canvas = false, overApp = false, docked = true))
        assertEquals("_canvas", DashboardStore.legacySuffix(canvas = true, overApp = false, docked = false))
        assertEquals("_canvas_half", DashboardStore.legacySuffix(canvas = true, overApp = false, docked = true))
        assertEquals("_tabs", DashboardStore.legacySuffix(canvas = true, overApp = true, docked = true))
        // An app under the dashboards only counted with Canvas.
        assertEquals("_half", DashboardStore.legacySuffix(canvas = false, overApp = true, docked = true))
    }

    @Test
    fun turningTheScreenReflowsEveryTileIntoTheNewGrid_droppingNone() {
        // The Daily template on a wide screen (12 x 7), on every page.
        val wide = TemplatePlacer.pages(
            DashTemplate.DAILY,
            TemplateScreen.of(1280f, 576f, obdPaired = true, driverOnRight = false, mapsDocked = false, dockApps = listOf("a.b", "c.d"))
        )
        val before = wide.sumOf { it.size }
        ScreenShape.vertical = true
        try {
            // Upright the grid is 7 x 12: every tile finds a place, in bounds, apart from the others.
            val upright = DashboardStore.reflow(wide)
            assertEquals(DashboardStore.PAGE_COUNT, upright.size)
            assertEquals(before, upright.sumOf { it.size })
            upright.forEach { page ->
                page.forEach { t ->
                    assertTrue("$t out of the 7x12 grid", t.x >= 0 && t.y >= 0 && t.x + t.w <= GRID_COLS && t.y + t.h <= GRID_ROWS)
                    assertTrue("$t under its minimum", t.w >= t.minW() && t.h >= t.minH())
                }
                noOverlaps(page)
            }
            // A tile keeps its page when its page has room; the Home page's kinds are still Home's.
            val homeKinds = { pages: List<List<DashboardItem>> -> pages[DashboardStore.CENTER].filterIsInstance<DashboardItem.BuiltinWidget>().map { it.kind }.toSet() }
            assertEquals(homeKinds(wide), homeKinds(upright))
            // The dock keeps its apps.
            assertEquals(listOf("a.b", "c.d"), upright[DashboardStore.CENTER].filterIsInstance<DashboardItem.LaunchBar>().single().packages)
        } finally {
            ScreenShape.vertical = false
        }
    }

    @Test
    fun reflowSendsATileWithNoRoomLeftToTheNearestPage() {
        // A page packed with 1x1 tiles beyond what the grid holds the other way up cannot happen,
        // but a page of wide tiles can: twelve 12x1 strips on a wide screen are too many for 7 rows... as 7x1 strips they need 12 rows.
        // Here: eight full-width strips of a kind whose minimum is 2x1 become 7-wide strips; 12 rows hold them all on their page.
        val strips = List(8) { i -> DashboardItem.BuiltinWidget(BuiltinKind.AUDIO, 0, i.coerceAtMost(GRID_ROWS - 1), GRID_COLS, 1) }
        val pages = List(DashboardStore.PAGE_COUNT) { if (it == 0) strips else emptyList() }
        ScreenShape.vertical = true
        try {
            val out = DashboardStore.reflow(pages)
            assertEquals(8, out.sumOf { it.size })
            // Fourteen tall 1x7 columns on the wide grid: upright (7 across) only seven fit at full height, and the
            // placer shrinks the rest to what fits rather than losing one; none ends up dropped.
            val columns = List(14) { i -> DashboardItem.BuiltinWidget(BuiltinKind.AUDIO, i.coerceAtMost(GRID_COLS - 1), 0, 1, 7) }
            val tall = DashboardStore.reflow(List(DashboardStore.PAGE_COUNT) { if (it == 2) columns else emptyList() })
            assertEquals(14, tall.sumOf { it.size })
            tall.forEach(::noOverlaps)
        } finally {
            ScreenShape.vertical = false
        }
    }

    @Test
    fun parsingOneVariantDoesNotLeakUnknownTilesIntoAnother() {
        val withStranger = """{"schema":1,"pages":[[{"t":"hologram","gx":0,"gy":0,"gw":3,"gh":2}],[],[]]}"""
        DashboardStore.parsePages(withStranger)
        // The next layout parsed (another variant) starts clean.
        DashboardStore.parsePages("""{"schema":1,"pages":[[],[],[]]}""")
        val saved = DashboardStore.serializePages(List(DashboardStore.PAGE_COUNT) { emptyList() })
        assertFalse(saved.contains("hologram"))
    }

    @Test
    fun peekingAtALayoutChangesNothing() {
        // The tiles kept for this layout's next save stay as they were...
        DashboardStore.parsePages("""{"schema":1,"pages":[[{"t":"hologram","gx":0,"gy":0,"gw":3,"gh":2}],[],[]]}""")
        val before = DashboardStore.placedKinds.value
        val peeked = DashboardStore.peekItems("""{"schema":1,"pages":[[{"t":"builtin","k":"CLOCK","gx":0,"gy":0,"gw":3,"gh":2},{"t":"stranger"}],[],[]]}""")
        assertEquals(listOf(BuiltinKind.CLOCK), peeked?.filterIsInstance<DashboardItem.BuiltinWidget>()?.map { it.kind })
        // ...and so do the kinds the add sheet marks as placed.
        assertEquals(before, DashboardStore.placedKinds.value)
        val saved = DashboardStore.serializePages(List(DashboardStore.PAGE_COUNT) { emptyList() })
        assertTrue(saved.contains("hologram"))
        assertFalse(saved.contains("stranger"))
        assertNull(DashboardStore.peekItems("not a layout"))
    }
}
