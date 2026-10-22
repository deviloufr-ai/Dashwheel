package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A CAN box press and the firmware's key for it, paired whichever comes first. */
class BoxPressPairingTest {

    private val voice = WheelKey.vendor(CarBoxKeys.VENDOR, 0x29)
    private val phone = WheelKey(301, 0)
    private val phoneKey = phone.id

    @Test
    fun theFirmwareKeyWhileHeldOrJustAfterReleaseBelongsToTheBoxPress() {
        val p = BoxPressPairing(followMs = 400, holdMaxMs = 3_000, lateMs = 1_000)
        assertNull(p.pending(1_000))
        assertNull(p.press(voice, 1_000))
        assertEquals(voice, p.pending(1_100))
        p.release(1_200)
        assertEquals(voice, p.pending(1_500))
        assertNull(p.pending(1_600))
    }

    @Test
    fun aHoldTheBoxNeverLetGoOfEndsByItself() {
        val p = BoxPressPairing(followMs = 400, holdMaxMs = 3_000, lateMs = 1_000)
        p.press(voice, 1_000)
        assertEquals(voice, p.pending(3_900))
        // The release frame was lost: Back, Home and the rest must get through again.
        assertNull(p.pending(4_000))
        assertNull(p.pending(60_000))
    }

    @Test
    fun onlyTheTakenKeysRepeatsAndReleaseFollowIt() {
        val p = BoxPressPairing()
        p.press(voice, 1_000)
        p.swallow(phoneKey)
        assertTrue(p.follows(phoneKey, release = false))
        assertFalse(p.follows(WheelKey(4, 0).id, release = false))
        assertTrue(p.follows(phoneKey, release = true))
        // Released: a later press of the same key is a new one.
        assertFalse(p.follows(phoneKey, release = false))
    }

    @Test
    fun aBoxCodeArrivingLateBelongsToTheFirmwarePressBeforeIt() {
        val p = BoxPressPairing(followMs = 400, holdMaxMs = 3_000, lateMs = 1_000)
        // The root log hands the stream over late: the firmware key ran its action first.
        p.firmwarePress(phone, 1_000, handled = true)
        val first = p.press(voice, 1_300)
        assertEquals(phone, first?.key)
        assertTrue(first!!.handled)
        // Nothing more is taken on that press's account.
        assertNull(p.pending(1_350))
        p.release(1_400)
        assertNull(p.pending(1_500))
    }

    @Test
    fun aLateBoxCodeStillRunsWhenTheFirmwareKeyDidNothing() {
        val p = BoxPressPairing(followMs = 400, holdMaxMs = 3_000, lateMs = 1_000)
        p.firmwarePress(phone, 1_000, handled = false)
        val first = p.press(voice, 1_300)
        assertEquals(phone, first?.key)
        assertFalse(first!!.handled)
        assertNull(p.pending(1_350))
    }

    @Test
    fun aStreamThatDoesNotLagNeverPairsWithTheKeyBeforeIt() {
        val p = BoxPressPairing(followMs = 400, holdMaxMs = 3_000, lateMs = 1_000)
        // The MCU service: Vol+ on the bezel, then Voice right after, are two presses.
        p.firmwarePress(WheelKey(24, 0), 1_000, handled = true)
        assertNull(p.press(voice, 1_300, late = false))
        assertEquals(voice, p.pending(1_350))
    }

    @Test
    fun aFirmwarePressLongBeforeIsAnotherPress() {
        val p = BoxPressPairing(followMs = 400, holdMaxMs = 3_000, lateMs = 1_000)
        p.firmwarePress(phone, 1_000, handled = true)
        assertNull(p.press(voice, 2_500))
        assertEquals(voice, p.pending(2_600))
        // The firmware press is spent: the next box press is its own.
        p.release(2_700)
        assertNull(p.press(voice, 2_800))
        assertEquals(voice, p.pending(2_900))
    }

    @Test
    fun aFirmwarePressIsSpentByTheBoxPressItPairsWith() {
        val p = BoxPressPairing(followMs = 400, holdMaxMs = 3_000, lateMs = 1_000)
        p.firmwarePress(phone, 1_000, handled = true)
        assertEquals(phone, p.press(voice, 1_200)?.key)
        p.release(1_300)
        // A second box press right after is not that firmware press's as well.
        assertNull(p.press(voice, 1_500))
        assertEquals(voice, p.pending(1_600))
    }

    @Test
    fun onlyAKeyDashwheelDidNothingWithLeavesTheFirmwareItsScreen() {
        val p = BoxPressPairing(followMs = 400, holdMaxMs = 3_000, lateMs = 1_000, screenMs = 2_500)
        assertFalse(p.leftToFirmware(1_000))
        // Bound or counted on the map: the phone app it opens is unasked.
        p.firmwarePress(phone, 1_000, handled = true)
        assertFalse(p.leftToFirmware(1_100))
        // Unbound: the firmware's own phone app is the driver's wish, for a while.
        p.firmwarePress(phone, 2_000, handled = false)
        assertTrue(p.leftToFirmware(4_400))
        assertFalse(p.leftToFirmware(4_600))
        // A box press taking its firmware key leaves nothing to the firmware.
        p.press(voice, 10_000)
        p.release(10_100)
        assertFalse(p.leftToFirmware(10_200))
    }

    @Test
    fun clearForgetsTheHoldAndTheTakenKeys() {
        val p = BoxPressPairing()
        p.press(voice, 1_000)
        p.swallow(phoneKey)
        p.clear()
        assertNull(p.pending(1_100))
        assertFalse(p.follows(phoneKey, release = false))
    }
}
