package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WazeScreenTest {

    // Waze guiding in a 522 x 638 tile on the K706 (2026-10-09): the banner at
    // the top, the arrival panel at the bottom.
    private val guiding = listOf(
        WazeScreen.Piece("150 m", 20, 50, 90),
        WazeScreen.Piece("Grande Rue", 56, 86, 90),
        WazeScreen.Piece("puis", 112, 130, 10),
        WazeScreen.Piece("Villers-sur-Bonnières", 240, 258, 280),
        WazeScreen.Piece("1", 460, 490, 40),
        WazeScreen.Piece("16:07", 570, 600, 228),
        WazeScreen.Piece("6 min • 3.1 km", 604, 624, 198)
    )

    @Test
    fun theTurnAndTheArrivalAreReadOffTheBanner() {
        val nav = WazeScreen.fromScreen(guiding, 638)!!
        assertEquals("150 m", nav.distance)
        assertEquals("Grande Rue", nav.instruction)
        assertEquals("6 min · 3.1 km · 16:07", nav.eta)
        assertEquals("com.waze", nav.packageName)
        assertEquals(true, nav.active)
    }

    @Test
    fun theArrowsDescriptionLeadsWhenWazeGivesOne() {
        val nav = WazeScreen.fromScreen(guiding + WazeScreen.Piece("Tournez à gauche", 20, 80, 10, described = true), 638)!!
        assertEquals("Tournez à gauche, Grande Rue", nav.instruction)
    }

    @Test
    fun noStreetSaysContinue() {
        val nav = WazeScreen.fromScreen(listOf(WazeScreen.Piece("2.4 km", 20, 50, 90)), 638, "Continuer")!!
        assertEquals("Continuer", nav.instruction)
        assertEquals("", nav.eta)
    }

    @Test
    fun wazeOnItsMapIsNotGuiding() {
        // The map with the search panel: a speed, place names, no distance up top.
        val map = listOf(
            WazeScreen.Piece("Villers-sur-Bonnières", 30, 50, 290),
            WazeScreen.Piece("1", 460, 490, 40),
            WazeScreen.Piece("Rue Verte", 470, 500, 220),
            WazeScreen.Piece("Où va-t-on ?", 575, 595, 70)
        )
        assertNull(WazeScreen.fromScreen(map, 638))
        // A distance further down (a search result) isn't the next turn either.
        assertNull(WazeScreen.fromScreen(listOf(WazeScreen.Piece("2.7 km", 300, 320, 400)), 638))
    }
}
