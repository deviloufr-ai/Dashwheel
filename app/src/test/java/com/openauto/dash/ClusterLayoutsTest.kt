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
        assertTrue(BuiltinKind.NAVMAP !in ClusterLayouts.KINDS)
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
}
