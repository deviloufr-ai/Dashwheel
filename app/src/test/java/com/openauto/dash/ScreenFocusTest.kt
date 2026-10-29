package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The wheel's mode 1 and 2: which screen the page buttons move, which one is marked, and what a button does. */
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
        // One screen in use: mode 2 shows on the unit's own.
        assertEquals(1, ScreenFocus.mark(bound = true, chosenSecond = true, output = SecondScreenOutput.VIDEO_APP))
    }

    @Test
    fun inMode2AButtonDoesItsSecondActionWhereItHasOne() {
        val next = WheelAssignment.Preset(SteeringWheelAction.NEXT_DASHBOARD)
        val louder = WheelAssignment.Preset(SteeringWheelAction.VOLUME_UP)
        val both = WheelButton("a", null, assignment = next, second = louder)
        assertEquals(next, both.actionFor(mode2 = false))
        assertEquals(louder, both.actionFor(mode2 = true))
        // None set for mode 2: its own, which then follows the focus.
        assertEquals(next, WheelButton("b", null, assignment = next).actionFor(mode2 = true))
        // The mode button keeps switching, or mode 2 could never be left.
        val mode = WheelButton("c", null, assignment = WheelAssignment.Preset(SteeringWheelAction.SCREEN_FOCUS), second = louder)
        assertEquals(mode.assignment, mode.actionFor(mode2 = true))
    }

    @Test
    fun everyPageButtonFollowsTheFocusOnceAButtonSwitchesIt() {
        assertEquals(1, ScreenFocus.pageTarget(bound = true, onSecond = false))
        assertEquals(2, ScreenFocus.pageTarget(bound = true, onSecond = true))
        // No focus button: each page button keeps to the screen it names.
        assertNull(ScreenFocus.pageTarget(bound = false, onSecond = false))
    }
}
