package com.openauto.dash

import com.openauto.dash.link.CallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** While reversing, the camera comes first: calls shrink, speech waits. */
class ReverseAwareTest {

    private fun call(phase: CallState.Phase) = PhoneCall(phase, "0612", "Alex", null, 0, canControl = true)

    @Test
    fun reversingShrinksAnyCallToThePill() {
        AlertStyle.entries.forEach { chosen ->
            assertEquals(AlertStyle.PILL, callStyle(call(CallState.Phase.RINGING), chosen, reversing = true))
            assertEquals(AlertStyle.PILL, callStyle(call(CallState.Phase.ACTIVE), chosen, reversing = true))
        }
    }

    @Test
    fun outOfReverseTheChosenDesignIsBack() {
        assertEquals(AlertStyle.FULL, callStyle(call(CallState.Phase.RINGING), AlertStyle.FULL, reversing = false))
        assertEquals(AlertStyle.BANNER, callStyle(call(CallState.Phase.ACTIVE), AlertStyle.BANNER, reversing = false))
        // Panel and full screen are for ringing only.
        assertEquals(AlertStyle.CARD, callStyle(call(CallState.Phase.ACTIVE), AlertStyle.PANEL, reversing = false))
    }

    @Test
    fun aCallWithNoButtonsNeverCoversTheScreen() {
        val shownOnly = call(CallState.Phase.RINGING).copy(canControl = false)
        assertEquals(AlertStyle.CARD, callStyle(shownOnly, AlertStyle.FULL, reversing = false))
        assertEquals(AlertStyle.CARD, callStyle(shownOnly, AlertStyle.PANEL, reversing = false))
        // The small designs are as chosen, and reverse still makes it the pill.
        assertEquals(AlertStyle.BANNER, callStyle(shownOnly, AlertStyle.BANNER, reversing = false))
        assertEquals(AlertStyle.PILL, callStyle(shownOnly, AlertStyle.FULL, reversing = true))
    }

    @Test
    fun aCallTappedAwayStaysThePill() {
        assertEquals(AlertStyle.PILL, callStyle(call(CallState.Phase.RINGING), AlertStyle.FULL, reversing = false, shrunk = true))
        // Answered, it does not come back as the card.
        assertEquals(AlertStyle.PILL, callStyle(call(CallState.Phase.ACTIVE), AlertStyle.FULL, reversing = false, shrunk = true))
    }

    @Test
    fun reverseEndsWhenTheFlagStaysOff() {
        var reads = ReverseReads().next(on = true)
        assertFalse(reads.over)
        reads = reads.next(on = false).next(on = false)
        assertFalse(reads.over)
        assertTrue(reads.next(on = false).over)
    }

    @Test
    fun aFlagOffForAMomentEndsNothing() {
        // Dashwheel clears it itself while it takes the camera: it is back on at the next read.
        val reads = ReverseReads(seenOn = true).next(on = false).next(on = false).next(on = true).next(on = false)
        assertFalse(reads.over)
        assertEquals(1, reads.offInARow)
    }

    @Test
    fun aUnitThatNeverSetsTheFlagEndsNothing() {
        var reads = ReverseReads()
        repeat(10) { reads = reads.next(on = false) }
        assertFalse(reads.over)
    }

    @Test
    fun heldSpeechIsSaidInOrderOnceAndFresh() {
        val fr = Locale.FRENCH
        val held = listOf(
            HeldLine("old", fr, 0),
            HeldLine("Door open: Rear left", fr, 200_000),
            HeldLine("Service due", fr, 210_000),
            HeldLine("Door open: Rear left", fr, 215_000)
        )
        val said = linesToRelease(held, now = 220_000).map { it.text }
        assertEquals(listOf("Door open: Rear left", "Service due"), said)
    }

    @Test
    fun aHeldTurnIsSaidFirstOrNotAtAll() {
        val fr = Locale.FRENCH
        val held = listOf(
            HeldLine("Turn left", fr, 100_000, guidance = true),
            HeldLine("Service due", fr, 150_000),
            HeldLine("Turn right", fr, 215_000, guidance = true)
        )
        // Only the last turn, while it is seconds old, and ahead of the rest.
        assertEquals(listOf("Turn right", "Service due"), linesToRelease(held, now = 220_000).map { it.text })
        assertEquals(listOf("Service due"), linesToRelease(held, now = 240_000).map { it.text })
    }

    @Test
    fun nothingHeldNothingSaid() {
        assertEquals(emptyList<HeldLine>(), linesToRelease(emptyList(), now = 1_000))
    }
}
