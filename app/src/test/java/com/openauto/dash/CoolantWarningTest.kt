package com.openauto.dash

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The coolant warning and the destination kept across a kill: small rules with a memory. */
class CoolantWarningTest {
    private val hot = 90
    private val limit = coolantWarningC(hot)

    @Test
    fun theWarningComesAtTheLimitAndGoesThreeDegreesUnder() {
        assertFalse(coolantWarns(limit - 1, hot, up = false))
        assertTrue(coolantWarns(limit, hot, up = false))
        // Hovering at the limit: it stays up.
        assertTrue(coolantWarns(limit - 1, hot, up = true))
        assertTrue(coolantWarns(limit - COOLANT_CLEAR_C, hot, up = true))
        assertFalse(coolantWarns(limit - COOLANT_CLEAR_C - 1, hot, up = true))
        // And comes back only at the limit.
        assertFalse(coolantWarns(limit - 1, hot, up = false))
    }

    @Test
    fun aGuidanceGoesOnAfterAShortStopOnly() {
        val at = 1_700_000_000_000L
        assertTrue(guidanceResumes(at, at + 20 * 60_000L))
        assertFalse(guidanceResumes(at, at + GUIDANCE_KEPT_MS))
        // A clock behind the saved time is not set: nothing says the stop was short.
        assertFalse(guidanceResumes(at, at - 1))
        assertFalse(guidanceResumes(0L, at))
    }
}
