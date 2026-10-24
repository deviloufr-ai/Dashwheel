package com.openauto.dash.roadwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HorizonLearnerTest {
    /** What a frame shows when the true horizon is at [trueY]: lines at 0.15 and 0.85 at the bonnet, running to (0.5, trueY). */
    private fun sighting(band: RoadBand, trueY: Float): LaneSighting {
        val t = (band.topY - trueY) / (band.bonnetY - trueY)
        val l = LaneLine(0.15f, 0.5f - 0.35f * t)
        val r = LaneLine(0.85f, 0.5f + 0.35f * t)
        return LaneSighting(l, r, meetY = HorizonLearner.meetY(l, r, band))
    }

    private fun drive(h: HorizonLearner, trueY: Float, seconds: Int, from: Long = 0L) {
        for (f in 0 until seconds * 10) {
            val band = RoadBand(horizonY = h.horizonY)
            h.update(sighting(band, trueY), from + f * 100L, learn = true)
        }
    }

    @Test
    fun whereTheLinesMeetIsTheHorizon() {
        val band = RoadBand(horizonY = 0.5f)
        assertEquals(0.59f, sighting(band, trueY = 0.59f).meetY!!, 0.001f)
        // Set too high, the real lines cross under it: still where they meet.
        val high = RoadBand(horizonY = 0.4f)
        assertEquals(0.59f, sighting(high, trueY = 0.59f).meetY!!, 0.001f)
    }

    @Test
    fun itFindsTheHorizonFromAboveAndFromBelow() {
        for (start in listOf(0.4f, 0.68f)) {
            val h = HorizonLearner(start)
            drive(h, trueY = 0.59f, seconds = 30)
            assertEquals("from $start", 0.59f, h.horizonY, 0.01f)
            assertTrue(h.settled)
        }
    }

    @Test
    fun onceSettledACrestHardlyMovesIt() {
        val h = HorizonLearner(0.59f)
        drive(h, trueY = 0.59f, seconds = 30)
        // Five seconds over a crest, where the lines meet lower.
        drive(h, trueY = 0.66f, seconds = 5, from = 30_000L)
        assertTrue("moved to ${h.horizonY}", h.horizonY < 0.6f)
    }

    @Test
    fun onlyWhileDrivingSteadily() {
        val h = HorizonLearner(0.5f)
        val band = RoadBand(horizonY = 0.5f)
        for (f in 0 until 100) h.update(sighting(band, trueY = 0.6f), f * 100L, learn = false)
        assertEquals(0.5f, h.horizonY, 0f)
        assertFalse(h.settled)
    }

    @Test
    fun nearParallelLinesSayNothing() {
        assertNull(HorizonLearner.meetY(LaneLine(0.15f, 0.2f), LaneLine(0.85f, 0.82f), RoadBand()))
    }

    @Test
    fun theDriversDragStartsItAgain() {
        val h = HorizonLearner(0.5f)
        drive(h, trueY = 0.55f, seconds = 30)
        assertTrue(h.settled)
        h.set(0.45f)
        assertEquals(0.45f, h.horizonY, 0f)
        assertFalse(h.settled)
    }
}
