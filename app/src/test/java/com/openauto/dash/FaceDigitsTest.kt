package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Test

/** The designs that spell a figure digit by digit read it as its language writes it. */
class FaceDigitsTest {

    @Test
    fun theOdometerSplitsOnTheLanguagesDecimalMark() {
        // French: 12,4 km, and a narrow space between thousands.
        assertEquals("12" to "4", odometerDigits("12,4", ','))
        assertEquals("187432" to "", odometerDigits("187 432", ','))
        // German groups with the dot.
        assertEquals("187432" to "", odometerDigits("187.432", ','))
        // English: the other way round.
        assertEquals("12" to "4", odometerDigits("12.4", '.'))
        assertEquals("187432" to "", odometerDigits("187,432", '.'))
        assertEquals("0" to "", odometerDigits("--", ','))
    }

    @Test
    fun theFlapBoardNeverDropsADigit() {
        assertEquals("187432", flapChars("187 432", text = false))
        assertEquals("187.432", flapChars("187.432", text = false))
        assertEquals("45m12s", flapChars("45m 12s", text = false))
        assertEquals("Radio Nova", flapChars("Radio Nova FM", text = true))
    }
}
