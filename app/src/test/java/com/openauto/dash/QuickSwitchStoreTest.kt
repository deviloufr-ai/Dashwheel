package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Quick switches tile's pick: the saved comma list, what the unit can do, and the drawing order. */
class QuickSwitchStoreTest {

    @Test
    fun nothingSavedIsTheFiveItAlwaysShowed() {
        assertEquals(
            setOf(QuickSwitch.QUIET, QuickSwitch.MUTE, QuickSwitch.LOOK, QuickSwitch.DND, QuickSwitch.WIFI, QuickSwitch.BLUETOOTH),
            QuickSwitchStore.parse(null)
        )
    }

    @Test
    fun savedListRoundTrips() {
        val pick = setOf(QuickSwitch.SCREEN_OFF, QuickSwitch.MUTE, QuickSwitch.MOBILE_DATA)
        val saved = QuickSwitchStore.encode(pick)
        assertEquals("mute,mobile_data,screen_off", saved)
        assertEquals(pick, QuickSwitchStore.parse(saved))
    }

    @Test
    fun anEmptyListStaysEmpty() {
        assertEquals("", QuickSwitchStore.encode(emptySet()))
        assertTrue(QuickSwitchStore.parse("").isEmpty())
    }

    @Test
    fun unknownNamesAreDropped() {
        assertEquals(setOf(QuickSwitch.WIFI), QuickSwitchStore.parse("torch, wifi ,"))
    }

    @Test
    fun screenOffNeedsTheQfFirmwareAndAShell() {
        assertTrue(QuickSwitch.SCREEN_OFF in QuickSwitchStore.available(qf = true, shell = true, telephony = false))
        assertFalse(QuickSwitch.SCREEN_OFF in QuickSwitchStore.available(qf = true, shell = false, telephony = false))
        assertFalse(QuickSwitch.SCREEN_OFF in QuickSwitchStore.available(qf = false, shell = true, telephony = false))
    }

    @Test
    fun mobileDataNeedsASimSlot() {
        assertTrue(QuickSwitch.MOBILE_DATA in QuickSwitchStore.available(qf = false, shell = false, telephony = true))
        assertFalse(QuickSwitch.MOBILE_DATA in QuickSwitchStore.available(qf = true, shell = true, telephony = false))
    }

    @Test
    fun theOldFiveAreAlwaysAvailable() {
        assertTrue(QuickSwitchStore.available(qf = false, shell = false, telephony = false).containsAll(QuickSwitchStore.DEFAULT))
    }

    @Test
    fun shownKeepsTheTileOrderAndSkipsWhatTheUnitCannotDo() {
        val chosen = setOf(QuickSwitch.SCREEN_OFF, QuickSwitch.MOBILE_DATA, QuickSwitch.MUTE)
        val noScreenKey = QuickSwitchStore.available(qf = false, shell = false, telephony = true)
        assertEquals(listOf(QuickSwitch.MUTE, QuickSwitch.MOBILE_DATA), QuickSwitchStore.shown(chosen, noScreenKey))
    }
}
