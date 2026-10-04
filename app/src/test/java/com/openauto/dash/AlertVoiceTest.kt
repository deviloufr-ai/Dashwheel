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
    fun movingHasHysteresis() {
        assertFalse(isMoving(null, wasMoving = true))
        assertFalse(isMoving(MOVING_KMH - 1, wasMoving = false))
        assertTrue(isMoving(MOVING_KMH, wasMoving = false))
        // Slowing in traffic stays "moving" until really stopped.
        assertTrue(isMoving(STOPPED_KMH + 1, wasMoving = true))
        assertFalse(isMoving(STOPPED_KMH, wasMoving = true))
    }
}
