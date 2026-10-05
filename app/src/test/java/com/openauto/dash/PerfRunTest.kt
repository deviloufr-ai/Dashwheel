package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PerfRunTest {

    /** Readings every 100 ms of [speed] (km/h at that time in ms) from [fromMs] to [toMs]. */
    private fun PerfRun.feed(fromMs: Long, toMs: Long, speed: (Long) -> Double) {
        var t = fromMs
        while (t <= toMs) { reading(t, speed(t)); t += 100 }
    }

    @Test
    fun aStandingStartIsTimed() {
        val run = PerfRun()
        // Still for 2 s: armed.
        run.feed(0, 2_000) { 0.0 }
        assertEquals(PerfRun.Phase.READY, run.phase)
        // Then 10 km/h a second, from 2.0 s: 50 at 7 s in, 100 at 12 s in.
        run.feed(2_100, 30_000) { t -> (t - 2_000) / 100.0 }
        val r = run.result
        assertEquals(5_000.0, r.to50!!.toDouble(), 150.0)
        assertEquals(10_000.0, r.to100!!.toDouble(), 150.0)
        // 400 m at a constant 10 km/h/s: v·t/2 = 400 → t ≈ 17 s
        assertEquals(17_000.0, r.quarter!!.toDouble(), 400.0)
        assertEquals(PerfRun.Phase.DONE, run.phase)
    }

    @Test
    fun movingOffWithoutStoppingFirstIsNotTimed() {
        val run = PerfRun()
        run.feed(0, 5_000) { 60.0 }
        assertEquals(PerfRun.Phase.WAITING, run.phase)
        assertNull(run.result.to100)
    }

    @Test
    fun aStopBeforeTheEndEndsTheRun() {
        val run = PerfRun()
        run.feed(0, 2_000) { 0.0 }
        run.feed(2_100, 6_000) { t -> (t - 2_000) / 100.0 }
        run.feed(6_100, 9_000) { 0.0 }
        assertEquals(PerfRun.Phase.READY, run.phase)
        assertNotNull(run.result.to50.let { null } ?: run.result)
    }
}
