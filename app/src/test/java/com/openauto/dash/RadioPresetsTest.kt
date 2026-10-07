package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioPresetsTest {
    @Test
    fun onlyFmStationsAreFm() {
        // FM in 10 kHz units: 88.8, 92.7, and Eastern Europe's 65.9 MHz.
        assertTrue(isFm(8880))
        assertTrue(isFm(9270))
        assertTrue(isFm(6590))
        // The AM presets the radio keeps in kHz, once shown as "5.2" to "16.2".
        listOf(522, 603, 999, 1404, 1620).forEach { assertFalse(isFm(it)) }
        assertEquals("88.8", RadioStation(8880, "").mhz)
    }
}
