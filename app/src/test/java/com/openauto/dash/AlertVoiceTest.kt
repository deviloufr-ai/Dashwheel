package com.openauto.dash

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the door alert speaks: only when it matters, never at every stop. */
class AlertVoiceTest {

    @Test
    fun parkedDoorsStaySilent() {
        assertNull(doorsToSay(before = emptySet(), open = setOf("fl"), wasMoving = false, moving = false))
        assertNull(doorsToSay(before = setOf("fl"), open = setOf("fl", "tailgate"), wasMoving = false, moving = false))
    }

    @Test
    fun aDoorOpeningOnTheMoveIsSaid() {
        assertEquals(setOf("rl"), doorsToSay(before = emptySet(), open = setOf("rl"), wasMoving = true, moving = true))
        // Only the new one, not the one already said.
        assertEquals(setOf("tailgate"), doorsToSay(before = setOf("rl"), open = setOf("rl", "tailgate"), wasMoving = true, moving = true))
    }

    @Test
    fun settingOffWithADoorOpenIsSaidOnce() {
        assertEquals(setOf("fr", "bonnet"), doorsToSay(before = setOf("fr", "bonnet"), open = setOf("fr", "bonnet"), wasMoving = false, moving = true))
        // Still driving with it open: not again.
        assertNull(doorsToSay(before = setOf("fr"), open = setOf("fr"), wasMoving = true, moving = true))
    }

    @Test
    fun doorsShuttingSayNothing() {
        assertNull(doorsToSay(before = setOf("fl", "rr"), open = setOf("fl"), wasMoving = true, moving = true))
        assertNull(doorsToSay(before = setOf("fl"), open = emptySet(), wasMoving = false, moving = true))
    }

    @Test
    fun slowingDownInTrafficIsNotSettingOffAgain() = runBlocking {
        // 5 km/h is between the two thresholds: no news, so the second 30 is the same drive.
        val speeds = flowOf(0, 30, 5, 30, 0, 5, 0, 50)
        assertEquals(listOf(false, true, false, true), speeds.setOffOrStopped().toList())
    }

    @Test
    fun overTheLimitIsSaidOncePerLimitValueNotPerRoadPiece() {
        val nag = LimitNag(tolerance = 5, overMs = 4_000L, againMs = 180_000L, underMs = 30_000L)
        // Over 80 for a moment: nothing; held for 4 s: said once.
        assertFalse(nag.step(80, 90, 0L))
        assertFalse(nag.step(80, 90, 3_000L))
        assertTrue(nag.step(80, 90, 4_000L))
        // The same limit on the next piece of road, still over: silence for three minutes.
        assertFalse(nag.step(80, 92, 60_000L))
        assertFalse(nag.step(80, 92, 179_000L))
        // Still over after three minutes: said again.
        assertTrue(nag.step(80, 92, 184_001L))
    }

    @Test
    fun droppingUnderTheLimitForHalfAMinuteArmsTheWarningAgain() {
        val nag = LimitNag(tolerance = 5, overMs = 4_000L, againMs = 180_000L, underMs = 30_000L)
        assertFalse(nag.step(80, 90, 0L))
        assertTrue(nag.step(80, 90, 4_000L))
        // Under 80 for 30 s, then over again for 4 s: said again, well within the three minutes.
        assertFalse(nag.step(80, 78, 10_000L))
        assertFalse(nag.step(80, 78, 40_000L))
        assertFalse(nag.step(80, 90, 41_000L))
        assertTrue(nag.step(80, 90, 45_000L))
        // Within the tolerance but not under the limit doesn't count as dropping under.
        assertFalse(nag.step(80, 83, 50_000L))
        assertFalse(nag.step(80, 83, 90_000L))
        assertFalse(nag.step(80, 90, 91_000L))
        assertFalse(nag.step(80, 90, 95_000L))
    }

    @Test
    fun aNewLimitValueIsSaidAfterItsOwnFewSeconds() {
        val nag = LimitNag(tolerance = 5, overMs = 4_000L, againMs = 180_000L, underMs = 30_000L)
        assertFalse(nag.step(80, 90, 0L))
        assertTrue(nag.step(80, 90, 4_000L))
        // Into a 50 zone at 90: over at once, but said only after 4 s over the new value.
        assertFalse(nag.step(50, 90, 10_000L))
        assertFalse(nag.step(50, 90, 13_000L))
        assertTrue(nag.step(50, 90, 14_000L))
        // No limit known: nothing.
        assertFalse(nag.step(null, 120, 20_000L))
    }

    @Test
    fun movingHasHysteresis() {
        assertFalse(isMoving(null, wasMoving = true))
        assertFalse(isMoving(MOVING_KMH - 1, wasMoving = false))
        assertTrue(isMoving(MOVING_KMH, wasMoving = false))
        // Slowing in traffic stays "moving" until really stopped.
        assertTrue(isMoving(STOPPED_KMH + 1, wasMoving = true))
        assertFalse(isMoving(STOPPED_KMH, wasMoving = true))
    }
}
