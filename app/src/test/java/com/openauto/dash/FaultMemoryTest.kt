package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaultMemoryTest {
    private val day = 24L * 60 * 60 * 1000
    private val t0 = 1_791_500_000_000L

    @Test
    fun aScanThatMissedTheCodeDoesNotMakeItNewAgain() {
        // P1352 found, then a scan with nothing (adapter half connected, a code that comes and goes), then P1352 again.
        var known = AiMechanic.remembered(listOf("P1352"), emptyMap(), t0)
        known = AiMechanic.remembered(emptyList(), known, t0 + 60_000)
        assertTrue(AiMechanic.newCodes(listOf("P1352"), known, t0 + 120_000).isEmpty())
    }

    @Test
    fun aCodeUnseenForDaysIsNewAgain() {
        val known = AiMechanic.remembered(listOf("P1352"), emptyMap(), t0)
        assertEquals(listOf("P1352"), AiMechanic.newCodes(listOf("P1352"), known, t0 + 4 * day))
        // And it is dropped from memory once that old.
        assertTrue(AiMechanic.remembered(emptyList(), known, t0 + 4 * day).isEmpty())
    }

    @Test
    fun aDifferentCodeIsNew() {
        val known = AiMechanic.remembered(listOf("P1352"), emptyMap(), t0)
        assertEquals(listOf("P0380"), AiMechanic.newCodes(listOf("P1352", "P0380"), known, t0 + 60_000))
    }

    @Test
    fun codesSavedBeforeTheDatesAreTakenAsSeenNow() {
        val known = AiMechanic.parseKnown("P1352,P0380", t0)
        assertEquals(mapOf("P1352" to t0, "P0380" to t0), known)
        assertEquals(known, AiMechanic.parseKnown(AiMechanic.formatKnown(known), t0 + day))
    }
}
