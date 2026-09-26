package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the adapter is dialled by itself, and which way of reaching it goes first. */
class ObdLinkTest {

    @Test
    fun redialsAllDriveLongWhateverIsInFront() {
        assertTrue(redials(inFront = true, ignition = true))
        // Behind CarPlay or a call screen: a dropped link still comes back.
        assertTrue(redials(inFront = false, ignition = true))
    }

    @Test
    fun leavesTheAdapterAloneWhileTheCarIsOff() {
        assertFalse(redials(inFront = true, ignition = false))
        assertFalse(redials(inFront = false, ignition = false))
    }

    @Test
    fun followsTheLauncherOnAUnitThatDoesNotSayTheIgnition() {
        assertTrue(redials(inFront = true, ignition = null))
        assertFalse(redials(inFront = false, ignition = null))
    }

    @Test
    fun triesTheChannelThatWorkedLastTimeFirst() {
        assertEquals(listOf(0, 1, 2), channelOrder(last = 0, count = 3))
        assertEquals(listOf(1, 0, 2), channelOrder(last = 1, count = 3))
        assertEquals(listOf(2, 0, 1), channelOrder(last = 2, count = 3))
    }

    @Test
    fun ignoresASavedChannelThatNoLongerExists() {
        assertEquals(listOf(0, 1, 2), channelOrder(last = 7, count = 3))
        assertEquals(listOf(0, 1, 2), channelOrder(last = -1, count = 3))
    }

    @Test
    fun findsTheAdapterInTheUnitsOwnList() {
        // As the head unit's Bluetooth app writes them: no colons, commas between.
        assertTrue(unitListsAdapter("001DA5689988,AABBCCDDEEFF", "aa:bb:cc:dd:ee:ff"))
        assertFalse(unitListsAdapter("001DA5689988", "AA:BB:CC:DD:EE:FF"))
        assertFalse(unitListsAdapter("", "AA:BB:CC:DD:EE:FF"))
        assertFalse(unitListsAdapter(null, "AA:BB:CC:DD:EE:FF"))
    }
}
