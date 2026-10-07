package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneGpsRulesTest {
    @Test
    fun aSharpFreshPhoneFixIsUsed() {
        assertTrue(PhoneGpsRules.usable(accuracyM = 4f, ageMs = 150))
        assertTrue(PhoneGpsRules.usable(accuracyM = 50f, ageMs = 3_000))
    }

    @Test
    fun aWideOrLateFixIsNot() {
        // Indoors or in a bag: hundreds of metres wide, no better than the unit's.
        assertFalse(PhoneGpsRules.usable(accuracyM = 120f, ageMs = 100))
        // The link stalled: the car would be shown where it was seconds ago.
        assertFalse(PhoneGpsRules.usable(accuracyM = 4f, ageMs = 5_000))
        // Nonsense from a broken peer.
        assertFalse(PhoneGpsRules.usable(accuracyM = -1f, ageMs = 100))
        assertFalse(PhoneGpsRules.usable(accuracyM = 4f, ageMs = -10))
    }

    @Test
    fun theUnitsOwnGpsComesBackAfterFourQuietSeconds() {
        assertFalse(PhoneGpsRules.stale(lastFixAtMs = 10_000, nowMs = 13_000))
        assertFalse(PhoneGpsRules.stale(lastFixAtMs = 10_000, nowMs = 14_000))
        assertTrue(PhoneGpsRules.stale(lastFixAtMs = 10_000, nowMs = 14_001))
    }

    @Test
    fun theFixIsDatedOnThisUnitsClocksByItsAge() {
        // 250 ms old: a quarter second before now on both clocks, whatever the phone's own say.
        assertEquals(9_750_000_000L to 1_790_000_000_000L - 250, PhoneGpsRules.fixTimes(250, 10_000_000_000L, 1_790_000_000_000L))
        // Never before the unit started.
        assertEquals(0L, PhoneGpsRules.fixTimes(5_000, 1_000_000_000L, 0).first)
        // A negative age (clock trouble on the phone) counts as none.
        assertEquals(10L to 20L, PhoneGpsRules.fixTimes(-30, 10, 20))
    }
}
