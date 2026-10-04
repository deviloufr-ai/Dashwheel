package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingActionTest {

    @Test
    fun theStripCountsThreeTwoOneThenActs() {
        assertEquals(3, pendingSecondsLeft(armedAt = 1_000L, now = 1_000L))
        assertEquals(3, pendingSecondsLeft(armedAt = 1_000L, now = 1_900L))
        assertEquals(2, pendingSecondsLeft(armedAt = 1_000L, now = 2_000L))
        assertEquals(1, pendingSecondsLeft(armedAt = 1_000L, now = 3_999L))
        assertEquals(0, pendingSecondsLeft(armedAt = 1_000L, now = 4_000L))
        assertEquals(0, pendingSecondsLeft(armedAt = 1_000L, now = 60_000L))
    }

    @Test
    fun aClockThatSteppedBackNeverShowsMoreThanTheWholeDelay() {
        assertEquals(3, pendingSecondsLeft(armedAt = 5_000L, now = 1_000L))
    }

    @Test
    fun theActionRunsOnceWhenItsCountdownEnds() {
        val state = PendingActionState()
        var calls = 0
        state.arm("Calling Alex") { calls++ }
        val action = state.pending!!
        assertEquals("Calling Alex", action.label)
        state.fire(action)
        assertEquals(1, calls)
        assertNull(state.pending)
        // A late second countdown end for the same action does nothing.
        state.fire(action)
        assertEquals(1, calls)
    }

    @Test
    fun cancelledMeansNothingRuns() {
        val state = PendingActionState()
        var ran = false
        state.arm("Sending to Alex") { ran = true }
        val action = state.pending!!
        state.cancel()
        state.fire(action)
        assertFalse(ran)
    }

    @Test
    fun aTapOnAnotherRowReplacesTheOneWaiting() {
        val state = PendingActionState()
        var first = false
        var second = false
        state.arm("Calling Alex") { first = true }
        val replaced = state.pending!!
        state.arm("Calling Sam") { second = true }
        state.fire(replaced)
        assertFalse(first)
        assertEquals("Calling Sam", state.pending?.label)
        state.fire(state.pending!!)
        assertTrue(second)
    }

    @Test
    fun quickDialShowsFewerFacesRatherThanNarrowOnes() {
        // 368 dp: four columns of 86 dp; a fifth would bring them down to 67 dp.
        assertEquals(4, quickDialColumns(368f, favourites = 8))
        assertEquals(3, quickDialColumns(368f, favourites = 3))
        assertEquals(2, quickDialColumns(152f, favourites = 8))
        // Never none, however narrow the tile, nor with an empty list.
        assertEquals(1, quickDialColumns(40f, favourites = 8))
        assertEquals(1, quickDialColumns(368f, favourites = 0))
    }

    @Test
    fun anAnswerShowsItsTimeOnceOlderThanOneRefreshOrAfterAFailure() {
        val interval = 15 * 60_000L
        val fetched = 1_000_000L
        assertFalse(showsAge(fetched, fetched + interval, interval, failed = false))
        assertTrue(showsAge(fetched, fetched + interval + 1, interval, failed = false))
        // A refresh asked by hand that failed shows at once, on a fresh answer too.
        assertTrue(showsAge(fetched, fetched + 60_000L, interval, failed = true))
    }

    @Test
    fun onlyADenialWithoutADialogCountsAsForGood() {
        // No dialog was shown: no rationale due before nor after.
        assertTrue(deniedForGood(granted = false, rationaleBefore = false, rationaleAfter = false))
        // First refusal in the dialog: Android will ask again.
        assertFalse(deniedForGood(granted = false, rationaleBefore = false, rationaleAfter = true))
        // "Don't ask again" ticked in the dialog just shown: the driver chose, the next tap is the dead one.
        assertFalse(deniedForGood(granted = false, rationaleBefore = true, rationaleAfter = false))
        assertFalse(deniedForGood(granted = true, rationaleBefore = false, rationaleAfter = false))
    }
}
