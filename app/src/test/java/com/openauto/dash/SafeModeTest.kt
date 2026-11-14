package com.openauto.dash

import com.openauto.dash.unit.fyt.FytToolkit
import com.openauto.dash.unit.fyt.fytRetryMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Safe mode's counting rule, and the small decisions of the same pass for units Dashwheel was not born on. */
class SafeModeTest {

    @Test
    fun twoStartsInARowThatDidNotHoldAreSafeModeAtTheThird() {
        assertFalse(safeModeAfter(0))
        // One crash is given a second go with everything on.
        assertFalse(safeModeAfter(1))
        assertTrue(safeModeAfter(2))
        assertTrue(safeModeAfter(7))
    }

    @Test
    fun androidWidgetsAreHostedOnlyWithTheSystemsServiceAndOutOfSafeMode() {
        assertTrue(widgetsHostable(safeMode = false, hasFeature = true))
        assertFalse(widgetsHostable(safeMode = true, hasFeature = true))
        // A build without android.software.app_widgets: no service to host with.
        assertFalse(widgetsHostable(safeMode = false, hasFeature = false))
    }

    @Test
    fun theFytToolkitIsAskedLessAndLessOftenWhileItStaysAway() {
        val waits = generateSequence(FytToolkit.RETRY_MS) { fytRetryMs(it) }.take(12).toList()
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L), waits.take(4))
        // Never longer than a few minutes, and it stays there.
        assertEquals(FytToolkit.RETRY_MAX_MS, waits.last())
        assertTrue(waits.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun aScreenLightTheUnitPutBackDidNotTake() {
        assertTrue(brightnessTook(wrote = 120, readBack = 120))
        assertFalse(brightnessTook(wrote = 120, readBack = 255))
        // The setting can't be read: nothing to say.
        assertTrue(brightnessTook(wrote = 120, readBack = null))
    }
}
