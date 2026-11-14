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

    @Test
    fun aQuietEngineComputerOnlyMeansStoppedAfterAStandstill() {
        assertTrue(stoppedSpeedKnown(0))
        // Never answered on this link, or went quiet at speed: left to the GPS.
        assertFalse(stoppedSpeedKnown(null))
        assertFalse(stoppedSpeedKnown(87))
    }

    @Test
    fun fastReadingsShareARequestOnlyOnCanWithKnownPids() {
        val car = SupportedPids(setOf(0x04, 0x0C, 0x0D, 0x11), knownUpTo = 0xFF)
        val plan = setOf(Pid.SPEED, Pid.RPM, Pid.THROTTLE, Pid.COOLANT)
        assertEquals(listOf(0x0D, 0x0C, 0x11), combinedPids(plan, car, 11))
        assertEquals(listOf(0x0D, 0x0C), combinedPids(setOf(Pid.SPEED, Pid.RPM), car, 29))
        // An older bus, a bus or a PID list that isn't known, a single fast reading: one request each, as before.
        assertEquals(emptyList<Int>(), combinedPids(plan, car, 0))
        assertEquals(emptyList<Int>(), combinedPids(plan, car, null))
        assertEquals(emptyList<Int>(), combinedPids(plan, null, 11))
        assertEquals(emptyList<Int>(), combinedPids(setOf(Pid.SPEED, Pid.COOLANT), car, 11))
        // What the car does not serve stays out.
        assertEquals(listOf(0x0D, 0x11), combinedPids(plan, SupportedPids(setOf(0x0D, 0x11), knownUpTo = 0xFF), 11))
        assertTrue(FAST_PID_BYTES.keys.containsAll(combinedPids(Pid.entries.toSet(), car, 11)))
    }

    @Test
    fun aLinkThatComesBackSoonIsNotScannedAgain() {
        assertTrue(scanDue(lastOkAt = 0L, now = 5_000L))
        assertFalse(scanDue(lastOkAt = 60_000L, now = 60_000L + RESCAN_GAP_MS - 1))
        assertTrue(scanDue(lastOkAt = 60_000L, now = 60_000L + RESCAN_GAP_MS))
    }
}
