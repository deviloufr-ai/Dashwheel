package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which of an app's tiles (Maps on two pages) gets its one display, and when the app runs twice. */
class EmbeddedAppTest {

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
}
