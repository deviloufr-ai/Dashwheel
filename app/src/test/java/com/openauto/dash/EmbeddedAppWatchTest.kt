package com.openauto.dash

import com.openauto.dash.EmbeddedApp.Remedy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What is done about an app that does not show on its tile after it was put there. */
class EmbeddedAppWatchTest {

    @Test
    fun anAppShowingOnItsTileIsLeftAlone() {
        for (look in 0..3) assertEquals(Remedy.DONE, EmbeddedApp.remedy(look, onTile = true, black = false, mayReopen = true))
    }

    @Test
    fun anAppElsewhereIsPlacedAgainThenOpenedAfresh() {
        // Back from YouTube Music opened full screen: still on the main screen, the tile black.
        assertEquals(Remedy.PLACE, EmbeddedApp.remedy(0, onTile = false, black = true, mayReopen = true))
        assertEquals(Remedy.PLACE, EmbeddedApp.remedy(1, onTile = false, black = true, mayReopen = true))
        assertEquals(Remedy.REOPEN, EmbeddedApp.remedy(2, onTile = false, black = true, mayReopen = true))
    }

    @Test
    fun aBlackPictureGetsTimeThenItsPictureAgainAndIsClosedLast() {
        assertEquals(Remedy.WAIT, EmbeddedApp.remedy(0, onTile = true, black = true, mayReopen = true))
        assertEquals(Remedy.REFRESH, EmbeddedApp.remedy(1, onTile = true, black = true, mayReopen = true))
        assertEquals(Remedy.WAIT, EmbeddedApp.remedy(2, onTile = true, black = true, mayReopen = true))
        assertEquals(Remedy.REOPEN, EmbeddedApp.remedy(3, onTile = true, black = true, mayReopen = true))
    }

    @Test
    fun anAppJustStartedIsNeverClosed() {
        // Still loading, or closed a minute ago already: closing it again would go on for ever.
        assertEquals(Remedy.GIVE_UP, EmbeddedApp.remedy(2, onTile = false, black = true, mayReopen = false))
        assertEquals(Remedy.GIVE_UP, EmbeddedApp.remedy(3, onTile = true, black = true, mayReopen = false))
    }

    @Test
    fun onlyOneFlatBlackIsABlackPicture() {
        val black = 0xFF000000.toInt()
        assertTrue(EmbeddedApp.allBlack(IntArray(16) { black }))
        // Nothing drawn at all yet.
        assertTrue(EmbeddedApp.allBlack(IntArray(16)))
        // A dark app is not a black tile: one pixel of it is enough.
        assertFalse(EmbeddedApp.allBlack(IntArray(16) { if (it == 9) 0xFF030303.toInt() else black }))
        assertFalse(EmbeddedApp.allBlack(IntArray(0)))
    }

    @Test
    fun theListingTellsATaskHiddenOnItsTile() {
        val listing = """
            Stack id=12 bounds=[0,0][522,362] displayId=7 userId=0
             configuration={ winConfig={ mWindowingMode=fullscreen mActivityType=standard} }
              taskId=80: com.google.android.apps.youtube.music/.activities.MusicActivity bounds=[0,0][522,362] userId=0 visible=false
            Stack id=20 bounds=[0,0][1280,720] displayId=0 userId=0
             configuration={ winConfig={ mWindowingMode=fullscreen mActivityType=standard} }
              taskId=81: com.waze/.MainActivity bounds=[0,0][1280,720] userId=0 visible=true
        """.trimIndent()
        assertEquals(
            listOf(WindowListing.AppStack(12, 7, "fullscreen", visible = false)),
            WindowListing.appStacks(listing, "com.google.android.apps.youtube.music")
        )
        assertEquals(listOf(WindowListing.AppStack(20, 0, "fullscreen")), WindowListing.appStacks(listing, "com.waze"))
    }
}
