package com.openauto.dash.display

import com.openauto.dash.link.DisplayWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class WordsTest {

    // The words are the process's: back to English for the other tests.
    @org.junit.After
    fun english() {
        Words.set(Files.createTempDirectory("words").toFile(), DisplayWords())
    }

    @Test
    fun theHeadUnitsWordsAreKeptForTheNextStart() {
        val dir = Files.createTempDirectory("words").toFile()
        val french = DisplayWords("fr", "En attente de Dashwheel…", "Aucun signal de Dashwheel", "Connecté", "Scannez avec l'application")
        assertTrue(Words.set(dir, french))
        // The same words again change nothing.
        assertFalse(Words.set(dir, french))

        // A fresh start reads them back, accents and apostrophe included.
        Words.set(java.io.File(dir, "elsewhere"), DisplayWords())
        assertEquals("Waiting for Dashwheel…", Words.waiting)
        Words.load(dir)
        assertEquals("En attente de Dashwheel…", Words.waiting)
        assertEquals("Aucun signal de Dashwheel", Words.noSignal)
        assertEquals("Connecté", Words.connected)
        assertEquals("Scannez avec l'application", Words.scanApp)
    }

    @Test
    fun linesTheHeadUnitLeftOutStayEnglish() {
        val dir = Files.createTempDirectory("words").toFile()
        Words.set(dir, DisplayWords("de", waiting = "Warten auf Dashwheel…"))
        assertEquals("Warten auf Dashwheel…", Words.waiting)
        assertEquals("No signal from Dashwheel", Words.noSignal)
        assertEquals("Connected", Words.connected)
    }
}
