package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Test

/** The second screen's day/night: a switch moves the backlight only once it has held. */
class SettledDayTest {

    private val hold = 120_000L

    @Test
    fun theFirstAnswerIsTakenAtOnce() {
        assertEquals(false, SettledDay(hold).next(false, 0))
        assertEquals(true, SettledDay(hold).next(true, 0))
    }

    @Test
    fun aTunnelOrAFlashOfTheBeamsLeavesTheScreenAsItWas() {
        val day = SettledDay(hold)
        day.next(true, 0)
        // Headlights on for a minute, then off again.
        assertEquals(true, day.next(false, 10_000))
        assertEquals(true, day.next(false, 70_000))
        assertEquals(true, day.next(true, 70_500))
        // On again: the wait starts over, not from the first time.
        assertEquals(true, day.next(false, 100_000))
        assertEquals(true, day.next(false, 100_000 + hold - 1))
    }

    @Test
    fun aSwitchThatHoldsIsTaken() {
        val day = SettledDay(hold)
        day.next(true, 0)
        assertEquals(true, day.next(false, 10_000))
        assertEquals(false, day.next(false, 10_000 + hold))
        // And back to day the same way.
        assertEquals(false, day.next(true, 500_000))
        assertEquals(true, day.next(true, 500_000 + hold))
    }
}
