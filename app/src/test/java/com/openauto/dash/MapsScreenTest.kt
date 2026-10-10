package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MapsScreenTest {

    private fun piece(text: String, top: Int, described: Boolean = false) = WazeScreen.Piece(text, top, top + 40, 0, described)

    @Test
    fun theDestinationIsTheTextUnderYourLocation() {
        val preview = listOf(
            piece("Search here", 0), piece("Your location", 120), piece("Tour Eiffel", 180),
            piece("18 min", 900), piece("6.4 km", 900), piece("Start", 1000)
        )
        assertEquals("Tour Eiffel", MapsScreen.destinationOf(preview, 1200))
        // In French, with the field's description instead of its text.
        val fr = listOf(piece("Votre position", 100), piece("Destination : Carrefour Market", 160, described = true), piece("Démarrer", 1000))
        assertEquals("Carrefour Market", MapsScreen.destinationOf(fr, 1200))
        // The start field's own words, figures and times are not a destination.
        val figures = listOf(piece("Your location", 100), piece("Your location", 101), piece("12:30", 150), piece("3.2 km", 160), piece("Gare de Lyon", 200))
        assertEquals("Gare de Lyon", MapsScreen.destinationOf(figures, 1200))
        // Navigating, or any other screen: nothing read.
        assertNull(MapsScreen.destinationOf(listOf(piece("Turn right", 50), piece("300 m", 50), piece("18:42", 1100)), 1200))
        assertNull(MapsScreen.destinationOf(preview, 0))
        // Below the top half it is the steps list, not the preview.
        assertNull(MapsScreen.destinationOf(listOf(piece("Your location", 800), piece("Somewhere", 850)), 1200))
    }

    @Test
    fun theDescriptionsWordsAreDropped() {
        assertEquals("Tour Eiffel", MapsScreen.cleaned("Destination: Tour Eiffel"))
        assertEquals("Tour Eiffel", MapsScreen.cleaned("Tour Eiffel, destination"))
        assertEquals("Gare du Nord", MapsScreen.cleaned("Arrivée : Gare du Nord"))
        assertEquals("Avenue de la Gare", MapsScreen.cleaned("Avenue de la Gare"))
    }
}
