package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which screen the wheel's dashboard buttons move, and which one is marked. */
class ScreenFocusTest {

    @Test
    fun theSecondScreenHasTheFocusOnlyWhileItShowsTheCluster() {
        assertEquals(2, ScreenFocus.focused(true, SecondScreenOutput.VIDEO_CLUSTER))
        assertEquals(2, ScreenFocus.focused(true, SecondScreenOutput.DATA))
        // An app on it, or no display at all: the buttons move the unit's own dashboards.
        assertEquals(1, ScreenFocus.focused(true, SecondScreenOutput.VIDEO_APP))
        assertEquals(1, ScreenFocus.focused(true, SecondScreenOutput.NONE))
    }

    @Test
    fun theUnitsScreenKeepsTheFocusUntilTheDriverSwitches() {
        SecondScreenOutput.entries.forEach { assertEquals(1, ScreenFocus.focused(false, it)) }
    }

    @Test
    fun aScreenIsMarkedOnlyWithAButtonToSwitchAndASecondScreenToSwitchTo() {
        assertEquals(1, ScreenFocus.mark(bound = true, chosenSecond = false, output = SecondScreenOutput.DATA))
        assertEquals(2, ScreenFocus.mark(bound = true, chosenSecond = true, output = SecondScreenOutput.VIDEO_CLUSTER))
        assertNull(ScreenFocus.mark(bound = false, chosenSecond = true, output = SecondScreenOutput.DATA))
        assertNull(ScreenFocus.mark(bound = true, chosenSecond = false, output = SecondScreenOutput.NONE))
        assertNull(ScreenFocus.mark(bound = true, chosenSecond = true, output = SecondScreenOutput.VIDEO_APP))
    }

    @Test
    fun everyPageButtonFollowsTheFocusOnceAButtonSwitchesIt() {
        assertEquals(1, ScreenFocus.pageTarget(bound = true, onSecond = false))
        assertEquals(2, ScreenFocus.pageTarget(bound = true, onSecond = true))
        // No focus button: each page button keeps to the screen it names.
        assertNull(ScreenFocus.pageTarget(bound = false, onSecond = false))
    }
}
