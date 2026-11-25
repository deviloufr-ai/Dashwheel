package com.openauto.dash

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnitSilentForPhoneTest {
    @Test fun `phone music reaching the unit with no source is silent`() {
        assertTrue(unitSilentForPhone(true, "1", "nothing"))
        assertTrue(unitSilentForPhone(true, "1", null))
    }
    @Test fun `Bluetooth music or the radio already has the sound`() {
        assertFalse(unitSilentForPhone(true, "1", "com.qf.bluetooth"))
        assertFalse(unitSilentForPhone(true, "1", "com.android.fmradio.ext"))
    }
    @Test fun `a paused phone or one not heard by the unit is left alone`() {
        assertFalse(unitSilentForPhone(false, "1", "nothing"))
        assertFalse(unitSilentForPhone(true, "2", "nothing"))
        assertFalse(unitSilentForPhone(true, null, "nothing"))
    }
}
