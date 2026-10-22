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

    @Test
    fun onlyTheRowsTheReadersLookAtCount() {
        val frame = IntArray(40 * 30) { it * 7919 }
        val waze = TileText.readRows(WazeScreen.PACKAGE, 30)
        val maps = TileText.readRows(MapsScreen.PACKAGE, 30)
        assertEquals(listOf(0 until 7, 20 until 30), waze)
        assertEquals(listOf(0 until 15), maps)
        // Waze's map in the middle moves all the time: a change there reads nothing again.
        val moved = frame.copyOf().also { it[12 * 40 + 3] = it[12 * 40 + 3] xor 1 }
        assertEquals(TileText.frameSignature(frame, 40, 30, waze), TileText.frameSignature(moved, 40, 30, waze))
        // Maps' preview is read in the top half: that change is seen there.
        assertNotEquals(TileText.frameSignature(frame, 40, 30, maps), TileText.frameSignature(moved, 40, 30, maps))
        // A distance ticking down at the top is seen by both.
        val ticked = frame.copyOf().also { it[2 * 40 + 3] = it[2 * 40 + 3] xor 1 }
        assertNotEquals(TileText.frameSignature(frame, 40, 30, waze), TileText.frameSignature(ticked, 40, 30, waze))
    }
}
