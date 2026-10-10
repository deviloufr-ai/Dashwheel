package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The speed warning and the next turn's side, shared by every look. */
class SpeedAndTurnTest {

    @Test
    fun speedWarnsFromTheFixedThresholdWithoutALimit() {
        assertFalse(isSpeedOver(null, null))
        assertFalse(isSpeedOver(SPEED_WARNING_KMH - 1, null))
        assertTrue(isSpeedOver(SPEED_WARNING_KMH, null))
    }

    @Test
    fun aKnownLimitWinsOverTheFixedThreshold() {
        // 125 on a 130 motorway is fine; 60 in a 50 town is not.
        assertFalse(isSpeedOver(125, 130))
        assertFalse(isSpeedOver(50 + SpeedLimit.TOLERANCE_KMH, 50))
        assertTrue(isSpeedOver(51 + SpeedLimit.TOLERANCE_KMH, 50))
        assertFalse(isSpeedOver(null, 50))
    }

    @Test
    fun theTurnComesFromWholeWords() {
        assertEquals(TurnSide.RIGHT, turnSideOf("Turn right onto Main St"))
        assertEquals(TurnSide.LEFT, turnSideOf("Tournez à gauche"))
        assertEquals(TurnSide.AHEAD, turnSideOf("Continue on Brighton Rd"))
        assertEquals(TurnSide.LEFT, turnSideOf("Turn left, then right"))
        assertEquals(TurnSide.RIGHT, turnSideOf("Поверните направо"))
    }

    @Test
    fun theNavigationsOwnManeuverWins() {
        assertEquals(TurnSide.RIGHT, NavState(instruction = "Turn left", maneuver = "RIGHT").turnSide())
        assertEquals(TurnSide.AHEAD, NavState(instruction = "Keep left", maneuver = "STRAIGHT").turnSide())
        // A roundabout names no side: the words decide.
        assertEquals(TurnSide.LEFT, NavState(instruction = "At the roundabout, take the exit on the left", maneuver = "ROUNDABOUT").turnSide())
    }
}
