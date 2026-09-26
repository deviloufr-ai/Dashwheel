package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The status bar over docked app windows: when the firmware's switch can keep it off. */
class FreeformBarTest {

    @Test
    fun windowsOnOneRowShareTheirTopEdge() {
        assertEquals(96, sharedTopEdge(listOf(96)))
        assertEquals(96, sharedTopEdge(listOf(96, 96)))
        assertNull(sharedTopEdge(listOf(96, 400)))
        assertNull(sharedTopEdge(emptyList()))
    }

    @Test
    fun noWindowNoBar() {
        assertFalse(barForced(windows = false, hasSwitch = false, tops = emptyList(), switchOn = false))
        assertFalse(barForced(windows = false, hasSwitch = true, tops = emptyList(), switchOn = true))
    }

    @Test
    fun anyWindowBringsTheBarUpWithoutTheSwitch() {
        assertTrue(barForced(windows = true, hasSwitch = false, tops = emptyList(), switchOn = false))
        assertTrue(barForced(windows = true, hasSwitch = false, tops = listOf(96), switchOn = false))
    }

    @Test
    fun theSwitchKeepsTheBarOffOverOneRowOfWindows() {
        assertFalse(barForced(windows = true, hasSwitch = true, tops = listOf(96, 96), switchOn = true))
        // Parked on the hidden display: nothing on screen brings it up.
        assertFalse(barForced(windows = true, hasSwitch = true, tops = emptyList(), switchOn = false))
    }

    @Test
    fun theBarComesBackForWindowsTheSwitchCannotCover() {
        assertTrue(barForced(windows = true, hasSwitch = true, tops = listOf(96, 400), switchOn = true))
        // The shell did not answer: the switch is not on yet.
        assertTrue(barForced(windows = true, hasSwitch = true, tops = listOf(96), switchOn = false))
    }

    @Test
    fun switchLineSetsTheTopEdgeAndReadsTheSwitchBack() {
        assertEquals(
            "setprop sys.freeform.locky 96; setprop sys.freeform.lockx ''; " +
                "setprop sys.freeform.fullscreen 1; getprop sys.freeform.fullscreen",
            freeformSwitchOn(96)
        )
    }

    // As `dumpsys window displays | grep -A 2 BarController.StatusBar` prints it on Android 10.
    private fun dump(state: String, transient: String = "TRANSIENT_BAR_NONE") =
        "    BarController.StatusBar\r\n      mState=$state\r\n      mTransientBar=$transient\r\n"

    @Test
    fun readsTheBarFromTheWindowDump() {
        assertEquals(true, statusBarShowing(dump("WINDOW_STATE_SHOWING")))
        assertEquals(false, statusBarShowing(dump("WINDOW_STATE_HIDDEN")))
        assertEquals(false, statusBarShowing(dump("WINDOW_STATE_HIDING")))
    }

    @Test
    fun aBarSwipedInOrADumpWithoutItSaysNothing() {
        assertNull(statusBarShowing(dump("WINDOW_STATE_SHOWING", transient = "TRANSIENT_BAR_SHOWING")))
        assertNull(statusBarShowing(""))
        assertNull(statusBarShowing("    BarController.NavigationBar\n      mState=WINDOW_STATE_SHOWING\n"))
    }
}
