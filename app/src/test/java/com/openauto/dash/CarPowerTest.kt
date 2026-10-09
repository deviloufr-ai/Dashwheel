package com.openauto.dash

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What switching on and off does: a briefing after a real stop, the parking spot from a fresh fix. */
class CarPowerTest {

    private val min = 60_000L

    @Test
    fun briefsAfterARealStopOnly() {
        val now = 1_000 * min
        assertTrue(briefOnIgnition(offAt = now - 45 * min, now = now))
        assertTrue(briefOnIgnition(offAt = now - CarStart.OFF_GAP_MS, now = now))
        // A fuel stop: no second briefing.
        assertFalse(briefOnIgnition(offAt = now - 4 * min, now = now))
        // Never seen switched off: the heartbeat decides.
        assertFalse(briefOnIgnition(offAt = null, now = now))
    }

    @Test
    fun parksOnAFreshFixOnly() {
        val now = 1_000 * min
        assertTrue(parkFixUsable(fixTime = now - 20_000, now = now))
        assertFalse(parkFixUsable(fixTime = now - 10 * min, now = now))
        assertFalse(parkFixUsable(fixTime = 0, now = now))
        // A fix stamped in the future (clock change) isn't trusted.
        assertFalse(parkFixUsable(fixTime = now + 5 * min, now = now))
    }

    @Test
    fun actsOnASwitchOnMissedWhileNotRunning() {
        val now = 1_000 * min
        // Off at the last stop, the app back with the ignition on: woken up.
        assertTrue(startedBySwitchOn(ignitionOn = true, offAt = now - 60 * min, onAt = now - 90 * min))
        // Restarted mid-drive: the "on" was already acted on.
        assertFalse(startedBySwitchOn(ignitionOn = true, offAt = now - 90 * min, onAt = now - 60 * min))
        // Started while the car is off, or never seen switched off.
        assertFalse(startedBySwitchOn(ignitionOn = false, offAt = now - 60 * min, onAt = now - 90 * min))
        assertFalse(startedBySwitchOn(ignitionOn = true, offAt = 0, onAt = 0))
    }

    @Test
    fun aPowerUpIsTheSwitchOnOrTheUnitsStartNotAnAppRestart() {
        // The unit started a minute ago.
        assertTrue(poweredUpRecently(now = 1 * min, switchedOnAt = 0))
        // Switched on a minute ago, the unit up for hours (it slept).
        assertTrue(poweredUpRecently(now = 600 * min, switchedOnAt = 599 * min))
        // The app restarted an hour into the drive, or parked: no power-up.
        assertFalse(poweredUpRecently(now = 600 * min, switchedOnAt = 540 * min))
        assertFalse(poweredUpRecently(now = 600 * min, switchedOnAt = 0))
    }
}
