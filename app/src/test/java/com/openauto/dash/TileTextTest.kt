package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TileTextTest {

    @Test
    fun onlyTheTilesWorthReadingAreRead() {
        val both = setOf(WazeScreen.PACKAGE, MapsScreen.PACKAGE)
        // Waze guiding in its tile: read. Maps up but not guiding: read for its preview.
        assertEquals(both, TileText.wanted(both, running = WazeScreen.PACKAGE))
        // Maps guiding: its notification tells the turns, nothing left to read on its tile.
        assertEquals(emptySet<String>(), TileText.wanted(setOf(MapsScreen.PACKAGE), running = MapsScreen.PACKAGE))
        // Waze in a tile but not guiding: still read, for the destination on its preview.
        assertEquals(setOf(WazeScreen.PACKAGE), TileText.wanted(setOf(WazeScreen.PACKAGE), running = null))
        // Nothing hosted: nothing read, whatever runs.
        assertEquals(emptySet<String>(), TileText.wanted(emptySet(), running = WazeScreen.PACKAGE))
    }

    @Test
    fun anUnchangedPictureIsKnownAndAnyChangeIsSeen() {
        val frame = IntArray(40 * 30) { it * 7919 }
        val same = frame.copyOf()
        assertEquals(TileText.frameSignature(frame, 40, 30), TileText.frameSignature(same, 40, 30))
        // One pixel of a distance ticking down is enough to read the tile again.
        same[1234 % same.size] = same[1234 % same.size] xor 1
        assertNotEquals(TileText.frameSignature(frame, 40, 30), TileText.frameSignature(same, 40, 30))
        // Same pixels, another shape: another picture. Only the picture's own pixels count, not the buffer's tail.
        assertNotEquals(TileText.frameSignature(frame, 40, 30), TileText.frameSignature(frame, 30, 40))
        val longer = frame.copyOf(frame.size + 10).also { it.fill(-1, frame.size) }
        assertEquals(TileText.frameSignature(frame, 40, 30), TileText.frameSignature(longer, 40, 30))
    }
}
