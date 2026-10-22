package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which of an app's tiles (Maps on two pages) gets its one display, and when the app runs twice. */
class EmbeddedAppTest {

    @Test
    fun aPressToldTwiceIsOnePress() {
        val routed = EmbeddedApp.RoutedDown(downTime = 1_000, eventTime = 1_000, x = 150f, y = 568f)
        // The K706 told a finger's press again a millisecond later, same start.
        assertTrue(EmbeddedApp.samePress(routed, downTime = 1_000, eventTime = 1_001, x = 150f, y = 568f))
        // Told again with a fresh start time, but at once and on the same spot.
        assertTrue(EmbeddedApp.samePress(routed, downTime = 1_002, eventTime = 1_002, x = 152f, y = 569f))
        // A second finger elsewhere, or a new tap later, is a new touch.
        assertFalse(EmbeddedApp.samePress(routed, downTime = 1_040, eventTime = 1_040, x = 400f, y = 300f))
        assertFalse(EmbeddedApp.samePress(routed, downTime = 1_400, eventTime = 1_400, x = 150f, y = 568f))
    }

    @Test
    fun onlyATapIsToldAgain() {
        // A quick press that stayed put: told again whole when the app lost the keys meanwhile.
        assertTrue(EmbeddedApp.isTap(dx = 3f, dy = -4f, ms = 90, wandered = false, slop = 16, longPressMs = 400))
        // A drag on the map, a pinch, a long press: left as they went.
        assertFalse(EmbeddedApp.isTap(dx = 30f, dy = 0f, ms = 90, wandered = false, slop = 16, longPressMs = 400))
        assertFalse(EmbeddedApp.isTap(dx = 0f, dy = 0f, ms = 90, wandered = true, slop = 16, longPressMs = 400))
        assertFalse(EmbeddedApp.isTap(dx = 0f, dy = 0f, ms = 600, wandered = false, slop = 16, longPressMs = 400))
    }

    @Test
    fun theTileOnScreenGetsThePicture() {
        // Page A's tile had it; the swipe put page B's on screen.
        assertEquals("b", EmbeddedApp.pickTile("a", listOf("a", "b"), setOf("b")))
    }

    @Test
    fun keepsItWhileItsTileIsStillOnScreen() {
        // Halfway through a swipe both show: no flipping back and forth.
        assertEquals("a", EmbeddedApp.pickTile("a", listOf("a", "b"), setOf("a", "b")))
    }

    @Test
    fun noneOnScreenKeepsTheCurrentOne() {
        assertEquals("a", EmbeddedApp.pickTile("a", listOf("a", "b"), emptySet()))
    }

    @Test
    fun aTileWithoutPictureNeverGetsIt() {
        assertEquals("a", EmbeddedApp.pickTile("b", listOf("a"), setOf("b")))
    }

    @Test
    fun theLatestTileWhenTheCurrentOneIsGone() {
        assertEquals("c", EmbeddedApp.pickTile("a", listOf("b", "c"), emptySet()))
        assertNull(EmbeddedApp.pickTile("a", emptyList<String>(), setOf("a")))
    }

    @Test
    fun onTheTileAndFullScreenIsRunningTwice() {
        // Waze reopened full screen by the unit at power-up, beside the one in its tile (display 7).
        val stacks = listOf(WindowListing.AppStack(12, 7, "fullscreen"), WindowListing.AppStack(20, 0, "fullscreen"))
        assertTrue(EmbeddedApp.runsTwice(stacks, 7))
    }

    @Test
    fun oneCopyAnywhereIsNotRunningTwice() {
        assertFalse(EmbeddedApp.runsTwice(listOf(WindowListing.AppStack(12, 7, "fullscreen")), 7))
        // Full screen on the main screen only: simply moved onto the tile.
        assertFalse(EmbeddedApp.runsTwice(listOf(WindowListing.AppStack(20, 0, "fullscreen")), 7))
        // A floating window beside the tile's copy is closed the usual way.
        assertFalse(EmbeddedApp.runsTwice(listOf(WindowListing.AppStack(12, 7, "fullscreen"), WindowListing.AppStack(5, 0, "freeform")), 7))
    }

    @Test
    fun theOtherLayoutsTileLetsGoOfAnAppDockedHere() {
        val maps = EmbeddedApp.MAPS_PACKAGE
        val music = "app.music"
        // On "Map on the left": Maps docked beside the pages, the grid's tile no longer holds it.
        assertEquals(setOf(music), EmbeddedApp.keptInside(emptySet(), setOf(maps, music), setOf(maps)))
        // Back on the grid, its own tile has it.
        assertEquals(setOf(maps, music), EmbeddedApp.keptInside(setOf(maps, music), emptySet(), setOf("other.window")))
        // An app inside a tile of the layout on screen stays, whatever else shows it.
        assertEquals(setOf(maps), EmbeddedApp.keptInside(setOf(maps), emptySet(), setOf(maps)))
    }
}
