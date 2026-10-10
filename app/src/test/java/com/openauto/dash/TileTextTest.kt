package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Test

class TileTextTest {

    @Test
    fun onlyTheTilesWorthReadingAreRead() {
        val both = setOf(WazeScreen.PACKAGE, MapsScreen.PACKAGE)
        // Waze guiding in its tile: read. Maps up but not guiding: read for its preview.
        assertEquals(both, TileText.wanted(both, running = WazeScreen.PACKAGE, navHasTurn = true))
        // Maps guiding with its turns in its notification: nothing left to read on it.
        assertEquals(emptySet<String>(), TileText.wanted(setOf(MapsScreen.PACKAGE), running = MapsScreen.PACKAGE, navHasTurn = true))
        // Maps guiding but its notification unread: its tile is read.
        assertEquals(setOf(MapsScreen.PACKAGE), TileText.wanted(setOf(MapsScreen.PACKAGE), running = MapsScreen.PACKAGE, navHasTurn = false))
        // Waze in a tile but not guiding: nothing to read.
        assertEquals(emptySet<String>(), TileText.wanted(setOf(WazeScreen.PACKAGE), running = null, navHasTurn = false))
        // Nothing hosted: nothing read, whatever runs.
        assertEquals(emptySet<String>(), TileText.wanted(emptySet(), running = WazeScreen.PACKAGE, navHasTurn = false))
    }
}
