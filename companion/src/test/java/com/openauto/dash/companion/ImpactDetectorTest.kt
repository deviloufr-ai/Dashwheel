package com.openauto.dash.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class ImpactDetectorTest {
    /** Gravity every 50 ms from [from] to [to], the phone tilted back [degAt] degrees at each moment. */
    private fun ImpactDetector.tilt(from: Long, to: Long, degAt: (Long) -> Double): Long? {
        var kept: Long? = null
        var t = from
        while (t <= to) {
            val r = Math.toRadians(degAt(t))
            gravity(0f, (9.81 * cos(r)).toFloat(), (9.81 * sin(r)).toFloat(), t)?.let { kept = it }
            t += 50
        }
        return kept
    }

    @Test
    fun aKnockWithThePhoneStillInItsHolderIsAnImpact() {
        val d = ImpactDetector()
        d.tilt(0, 2_000) { 0.0 }
        assertFalse("not sure until the phone is seen to stay put", d.acceleration(0f, 25f, 0f, 2_000))
        assertEquals(2_000L, d.tilt(2_050, 3_500) { 3.0 })
    }

    @Test
    fun aKnockWhileThePhoneIsHandledIsNot() {
        val d = ImpactDetector()
        d.tilt(0, 2_000) { 0.0 }
        assertFalse(d.acceleration(0f, 25f, 0f, 2_000))
        // Taken out of the holder: it turns 60° over the next second.
        assertNull(d.tilt(2_050, 3_500) { t -> ((t - 2_000) / 1_000.0 * 60).coerceAtMost(60.0) })
    }

    @Test
    fun aVeryHardKnockIsAnImpactWhateverThePhoneDid() {
        val d = ImpactDetector()
        assertTrue(d.acceleration(0f, 45f, 0f, 1_000))
        assertNull("not told twice", d.tilt(1_050, 2_500) { 90.0 })
    }

    @Test
    fun withoutAGravitySensorAKnockIsKeptAtOnce() {
        assertTrue(ImpactDetector(hasGravity = false).acceleration(0f, 25f, 0f, 1_000))
    }

    @Test
    fun aPotholeIsNoImpact() {
        val d = ImpactDetector()
        assertFalse(d.acceleration(0f, 9.81f, 0f, 1_000))
        assertNull(d.tilt(1_050, 2_500) { 0.0 })
    }
}
