package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which of an app's tiles (Maps on two pages) gets its one display. */
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
}
