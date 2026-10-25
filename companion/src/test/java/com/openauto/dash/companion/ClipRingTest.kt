package com.openauto.dash.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipRingTest {
    private fun seg(i: Int, length: Long = 20_000L) = Segment("s$i", i * length, (i + 1) * length)

    @Test
    fun theRingKeepsOnlyTheLastMinutes() {
        val ring = ClipRing(ringMs = 60_000L)
        val deleted = ArrayList<String>()
        for (i in 0 until 8) deleted += ring.segmentDone(seg(i)).delete.map { it.name }
        // 160 s recorded, 60 s kept: s0..s3 ended more than a minute before the newest end (160 s); s4 ends on the minute and stays.
        assertEquals(listOf("s0", "s1", "s2", "s3"), deleted)
        assertEquals(listOf("s4", "s5", "s6", "s7"), ring.onDisk.map { it.name })
    }

    @Test
    fun aClipTakesTheSegmentsAroundItsMoment() {
        val ring = ClipRing(ringMs = 120_000L, beforeMs = 30_000L, afterMs = 30_000L)
        for (i in 0 until 4) ring.segmentDone(seg(i))
        // At 75 s: the window is 45..105 s, over s2 (40-60), s3 (60-80), s4 (80-100), s5 (100-120).
        assertTrue(ring.keep(75_000L, "impact"))
        assertEquals(listOf("s2", "s3"), ring.pending.single().segments.map { it.name })
        assertFalse(ring.pending.single().complete)
        assertTrue(ring.segmentDone(seg(4)).completed.isEmpty())
        val done = ring.segmentDone(seg(5)).completed.single()
        assertTrue(done.complete)
        assertEquals(listOf("s2", "s3", "s4", "s5"), done.segments.map { it.name })
    }

    @Test
    fun aPendingClipsSegmentsAreNotDeleted() {
        val ring = ClipRing(ringMs = 40_000L, beforeMs = 30_000L, afterMs = 30_000L)
        for (i in 0 until 3) ring.segmentDone(seg(i))
        ring.keep(50_000L, "brake")
        // s1 (20-40) is in the window and would otherwise be pruned as the ring rolls on.
        val deleted = (3 until 8).flatMap { ring.segmentDone(seg(it)).delete }.map { it.name }
        assertFalse("s1" in deleted)
        assertTrue("s0" in deleted)
        // Released, it goes with the next segment.
        ring.release(50_000L)
        assertTrue("s1" in ring.segmentDone(seg(8)).delete.map { it.name })
    }

    @Test
    fun twoTriggersForOneEventMakeOneClip() {
        val ring = ClipRing()
        ring.segmentDone(seg(0))
        assertTrue(ring.keep(15_000L, "brake"))
        assertFalse(ring.keep(16_500L, "impact"))
        assertEquals(1, ring.pending.size)
        assertTrue(ring.keep(40_000L, "button"))
    }

    @Test
    fun stoppingClosesAClipWithWhatItHas() {
        val ring = ClipRing()
        for (i in 0 until 3) ring.segmentDone(seg(i))
        ring.keep(55_000L, "impact")
        val closed = ring.stopped().single()
        assertTrue(closed.complete)
        assertEquals(listOf("s1", "s2"), closed.segments.map { it.name })
    }

    @Test
    fun aKnockIsAnImpactOnceAndAPotholeIsNot() {
        // Without a gravity sensor a knock is kept at once; with one, ImpactDetectorTest.
        val d = ImpactDetector(hasGravity = false)
        assertFalse(d.acceleration(0f, 9f, 3f, 1_000L))
        assertTrue(d.acceleration(0f, 24f, 5f, 2_000L))
        assertFalse("the same knock, the next sample", d.acceleration(0f, 25f, 0f, 2_050L))
        assertTrue(d.acceleration(22f, 0f, 0f, 20_000L))
    }

    @Test
    fun aHardBrakeIsReadFromTheSpeed() {
        val d = ImpactDetector()
        assertFalse(d.speed(90f, 0L))
        assertFalse("firm, not hard", d.speed(78f, 1_000L))
        assertTrue("25 km/h in a second: 6.9 m/s²", d.speed(53f, 2_000L))
        assertFalse("a second slowing of the same stop", d.speed(28f, 3_000L))
        // Creeping to a halt in town is no brake.
        assertFalse(d.speed(20f, 20_000L))
        assertFalse(d.speed(0f, 21_000L))
    }
}
