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

    /** Pieces as the tile reader logged them on the K706 on 2026-10-10: "text@top | …". */
    private fun logged(line: String): List<WazeScreen.Piece> = line.split(" | ").map { part ->
        val at = part.lastIndexOf('@')
        piece(part.substring(0, at), part.substring(at + 1).toInt())
    }

    @Test
    fun theScreensReadOnTheUnitParseRight() {
        // The route preview: the pin before the address is read as a 9.
        val preview = logged("iller@10 | G@59 | t@357 | lanvoile@214 | Dau Baiees@2 | Your location@22 | 9 11 Rue des Cardonnettes@73 | Drive@436 | A13 min@481 | 13 min (10 km)@534 | A Start@603 | Mi sutThérain@237 | 14 min@253 | Savignies@351 | 37 min@481 | 13 min@120 | Herchies@285 | 2 hr 17@481 | Fastest route now due to traffic conditions@564 | Verderellès Sauqueuse@202 | |D901@326 | Fouquenies@361 | ¢ Add stops < Share@599 | H!@436 | dó 33 min@481 | Save@606 | 13 min@482 | D1001@313 | X@436")
        assertEquals("11 Rue des Cardonnettes", MapsScreen.destinationOf(preview, 640))
        val town = logged("en-Bray@196 | arché@277 | 9 Beauvais@71 | Your location@22 | aint-Germer-deFly-@248 | Songeons@115 | Drive@435 | A21 min@480 | A Start@603 | X@436")
        assertEquals("Beauvais", MapsScreen.destinationOf(town, 640))
        // The steps list under the preview: a step is not a destination.
        val steps = logged("Drive@38 | A13 min@82 | Your location@151 | ↑ Head toward Rue Verte@237 | 2.5 km@292 | 37 min@84 | 400 m@397 | Turn right onto Rue du Moulin des Forges@344 | Continue to follow D901@464 | 4.9 km@500 | A Start ¢ Add stops@602 | X@37 | 13 min@85")
        assertNull(MapsScreen.destinationOf(steps, 640))
        // The explore screen: nothing.
        val explore = logged("emé@65 | oile@190 | Try gas stations, ATMs@22 | Home@81 | Google Maps@542 | Explore@616 | Home,@128 | Beauvais@377 | You@617 | Contribute@615 | Le F@329 | -sur@483")
        assertNull(MapsScreen.destinationOf(explore, 640))
        // Scraps and the empty field's words, as the accessibility reader gave them.
        assertEquals(false, MapsScreen.isPlaceName("ois"))
        assertEquals(false, MapsScreen.isPlaceName("Choose destination"))
        assertEquals(false, MapsScreen.isPlaceName("↑ Head toward Rue Verte"))
        assertEquals(false, MapsScreen.isPlaceName("Tournez à droite sur la D901"))
        assertEquals(true, MapsScreen.isPlaceName("Carrefour Market"))
        assertEquals(true, MapsScreen.isPlaceName("11 Rue des Cardonnettes"))
        assertEquals("Beauvais", MapsScreen.cleaned("O Beauvais"))
    }

    @Test
    fun theDescriptionsWordsAreDropped() {
        assertEquals("Tour Eiffel", MapsScreen.cleaned("Destination: Tour Eiffel"))
        assertEquals("Tour Eiffel", MapsScreen.cleaned("Tour Eiffel, destination"))
        assertEquals("Gare du Nord", MapsScreen.cleaned("Arrivée : Gare du Nord"))
        assertEquals("Avenue de la Gare", MapsScreen.cleaned("Avenue de la Gare"))
    }
}
