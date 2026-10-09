package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppLanguageTest {

    @Test
    fun aDeviceInALanguageWithoutTextSpeaksEnglish() {
        // Hungarian: the text is English, so the voice and the numbers must be too.
        assertEquals("en", AppLanguage.textLanguage(chosenTag = null, systemLanguage = "hu"))
    }

    @Test
    fun aDeviceInALanguageWithTextIsLeftAlone() {
        assertNull(AppLanguage.textLanguage(chosenTag = null, systemLanguage = "fr"))
        assertNull(AppLanguage.textLanguage(chosenTag = null, systemLanguage = "en"))
    }

    @Test
    fun aChosenLanguageWins() {
        assertEquals("de", AppLanguage.textLanguage(chosenTag = "de", systemLanguage = "hu"))
        assertEquals("fr", AppLanguage.textLanguage(chosenTag = "fr", systemLanguage = "en"))
    }
}
