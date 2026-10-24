package com.openauto.dash

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Whether a next / previous reached the unit's radio ([stepHeard]): its tuner, not its RDS text. */
class RadioStepTest {

    private val tuned = HeadUnitMedia.RadioStation(freq = 9990, band = 0, name = null)

    @Test
    fun theSameFrequencyIsAStepUnheard() {
        assertFalse(stepHeard(tuned, tuned.copy()))
        // A station's name scrolling by on RDS is no step.
        assertFalse(stepHeard(tuned, tuned.copy(name = "NRJ")))
    }

    @Test
    fun anotherFrequencyOrBandIsAStepHeard() {
        assertTrue(stepHeard(tuned, tuned.copy(freq = 10040)))
        assertTrue(stepHeard(tuned, tuned.copy(band = 1)))
        // The first station the tuner reports at all.
        assertTrue(stepHeard(null, tuned))
    }
}
