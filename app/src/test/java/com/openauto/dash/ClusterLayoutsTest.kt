package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterLayoutsTest {

    @Test
    fun pagesComeAsBeforeUntilLaidOut() {
        val config = SecondScreenConfig()
        assertEquals(ClusterLayouts.default(ClusterPage.DRIVE), config.layoutFor(ClusterPage.DRIVE))
        ClusterPage.entries.forEach { page ->
            val l = ClusterLayouts.default(page)
            assertEquals(l.arrangement.slots, l.slots.size)
            assertEquals(l, ClusterLayouts.sanitize(l, page))
        }
    }

    @Test
    fun aBiggerLayoutKeepsTheWidgetsAndAddsNewOnes() {
        val drive = ClusterLayouts.default(ClusterPage.DRIVE)
        val grid = ClusterLayouts.arrange(drive, ClusterArrangement.GRID, ClusterPage.DRIVE)
        assertEquals(4, grid.slots.size)
        assertEquals(drive.slots, grid.slots.take(3))
        assertEquals(4, grid.slots.map { it.kind }.distinct().size)
        val one = ClusterLayouts.arrange(grid, ClusterArrangement.ONE, ClusterPage.DRIVE)
        assertEquals(listOf(drive.slots[0]), one.slots)
    }

    @Test
    fun aWidgetWearsOnlyDesignsThatSuitIt() {
        val drive = ClusterLayouts.default(ClusterPage.DRIVE)
        // Cover art is made for the media widget only.
        val clock = ClusterLayouts.withSlot(drive, 0, BuiltinKind.CLOCK, WidgetDesign.COVER_ART)
        assertEquals(BuiltinKind.CLOCK, clock.slots[0].kind)
        assertTrue(clock.slots[0].design in ClusterLayouts.designsFor(BuiltinKind.CLOCK))
        assertTrue(ClusterLayouts.designsFor(BuiltinKind.MEDIA).none { it == WidgetDesign.STANDARD })
        assertTrue(BuiltinKind.DASH_BAR !in ClusterLayouts.KINDS)
    }

    @Test
    fun layoutsSurviveTheRoundTrip() {
        val layouts = mapOf(
            ClusterPage.MEDIA to ClusterLayout(ClusterArrangement.HALVES, listOf(ClusterSlot(BuiltinKind.MEDIA, WidgetDesign.VINYL), ClusterSlot(BuiltinKind.CLOCK, WidgetDesign.FLAP))),
            ClusterPage.OBD to ClusterLayout(ClusterArrangement.ONE, listOf(ClusterSlot(BuiltinKind.GEAR, WidgetDesign.GEAR_GATE)))
        )
        assertEquals(layouts, SecondScreenCodec.decodeLayouts(SecondScreenCodec.encodeLayouts(layouts)))
    }

    @Test
    fun brokenLayoutsFallBack() {
        assertEquals(emptyMap<ClusterPage, ClusterLayout>(), SecondScreenCodec.decodeLayouts("NOPE=ONE:CLOCK/HERO;DRIVE=SPIRAL:CLOCK/HERO"))
        // An unknown widget is replaced, a missing slot filled, a retired design read as its successor.
        val read = SecondScreenCodec.decodeLayouts("NAV=HALVES:FUTURE_THING/HERO,CLOCK/GAUGE")[ClusterPage.NAV]!!
        assertEquals(2, read.slots.size)
        assertEquals(ClusterSlot(BuiltinKind.CLOCK, WidgetDesign.NEON), read.slots[0])
    }

    @Test
    fun livePicturesHaveNoDesignsAndKeepTheirApp() {
        val drive = ClusterLayouts.default(ClusterPage.DRIVE)
        val map = ClusterLayouts.withSlot(drive, 0, BuiltinKind.NAVMAP, WidgetDesign.NEON)
        assertEquals(ClusterSlot(BuiltinKind.NAVMAP, WidgetDesign.STANDARD), map.slots[0])
        val copy = ClusterLayouts.withSlot(drive, 1, BuiltinKind.MAPS_INSIDE, app = "com.waze")
        assertEquals(ClusterSlot(BuiltinKind.MAPS_INSIDE, WidgetDesign.STANDARD, "com.waze"), copy.slots[1])
        assertEquals(ClusterLayouts.DEFAULT_APP, ClusterLayouts.withSlot(drive, 1, BuiltinKind.MAPS_INSIDE).slots[1].app)
        // A widget never carries an app.
        assertEquals(null, ClusterLayouts.withSlot(copy, 1, BuiltinKind.CLOCK, app = "com.waze").slots[1].app)
        val layouts = mapOf(ClusterPage.NAV to ClusterLayouts.sanitize(copy, ClusterPage.NAV))
        assertEquals(layouts, SecondScreenCodec.decodeLayouts(SecondScreenCodec.encodeLayouts(layouts)))
        // An app copy saved without its app copies Google Maps.
        assertEquals(ClusterLayouts.DEFAULT_APP, SecondScreenCodec.decodeLayouts("NAV=ONE:MAPS_INSIDE/STANDARD")[ClusterPage.NAV]!!.slots[0].app)
    }

    @Test
    fun aCopyIsCroppedToFillItsSlot() {
        // A wide slot shows the frame's full width and its middle band.
        assertEquals(1f to 0.5f, PictureRelay.cropScale(800, 600, 1600, 600).let { it.first to Math.round(it.second * 100) / 100f })
        assertEquals(0.5f to 1f, PictureRelay.cropScale(1600, 600, 800, 600))
        assertEquals(1f to 1f, PictureRelay.cropScale(0, 0, 100, 100))
    }
}
