package com.openauto.dash

import org.junit.Assert.assertEquals
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
}
