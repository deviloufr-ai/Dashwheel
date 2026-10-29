package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which screen the wheel's dashboard buttons move. */
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
}
