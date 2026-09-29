package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Test

/** Video titles as the media tiles show them. */
class MediaTitleTest {

    @Test
    fun artistPrefixAndVideoNoiseGo() {
        assertEquals(
            "Waiting For Your Love",
            MediaTitle.clean("Jamie Bower - Waiting For Your Love (Official Music Video)", "Jamie Bower")
        )
        assertEquals("Tout va bien", MediaTitle.clean("Orelsan – Tout va bien [Clip Officiel]", "Orelsan"))
        assertEquals("Song", MediaTitle.clean("Song (Lyrics) [HD]", "Someone"))
    }

    @Test
    fun meaningfulPartsStay() {
        assertEquals("Song (Live at Wembley)", MediaTitle.clean("Song (Live at Wembley)", "Band"))
        assertEquals("Song (feat. X)", MediaTitle.clean("Song (feat. X)", "Band"))
        assertEquals("Song - Remastered 2011", MediaTitle.clean("Song - Remastered 2011", "Band"))
        // A song named after its artist, or an artist name that only starts the title.
        assertEquals("Queen", MediaTitle.clean("Queen", "Queen"))
        assertEquals("Muse Of Fire", MediaTitle.clean("Muse Of Fire", "Muse"))
    }

    @Test
    fun nothingLeftKeepsTheOriginal() {
        assertEquals("(Official Video)", MediaTitle.clean("(Official Video)", ""))
        assertEquals("", MediaTitle.clean("", "Artist"))
    }
}
