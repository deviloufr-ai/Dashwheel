package com.openauto.dash.roadwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class VehicleTrackerTest {
    private fun car(x: Float, y: Float = 0.6f, w: Float = 0.1f, score: Float = 0.8f, kind: VehicleKind = VehicleKind.CAR) =
        Detection(Box(x - w / 2, y - w * 0.4f, x + w / 2, y + w * 0.4f), kind, score)

    @Test
    fun aVehicleCountsOnceSeenTwice() {
        val t = VehicleTracker()
        assertEquals(emptyList<Detection>(), t.update(listOf(car(0.5f)), 0))
        val seen = t.update(listOf(car(0.5f)), 100)
        assertEquals(1, seen.size)
        assertNotEquals(0, seen[0].id)
    }

    @Test
    fun aDoubtfulBoxNeverStartsAVehicleButKeepsOneGoing() {
        val t = VehicleTracker()
        t.update(listOf(car(0.5f, score = 0.3f)), 0)
        assertEquals("doubtful twice: still nothing", emptyList<Detection>(), t.update(listOf(car(0.5f, score = 0.3f)), 100))
        t.update(listOf(car(0.5f)), 200)
        val id = t.update(listOf(car(0.5f)), 300)[0].id
        // Sure for two frames, then only doubtful boxes for a second: the vehicle goes on, with the same id.
        var seen: List<Detection> = emptyList()
        for (i in 4..12) seen = t.update(listOf(car(0.5f, score = 0.25f)), i * 100L)
        assertEquals(id, seen.single().id)
    }

    @Test
    fun aVehicleMissedForAFrameGoesOnWhereItWasHeading() {
        val t = VehicleTracker()
        // Moving right 0.1 of the frame a second.
        for (i in 0..9) t.update(listOf(car(0.3f + i * 0.01f)), i * 100L)
        val coasted = t.update(emptyList(), 1_100)
        assertEquals(1, coasted.size)
        assertEquals(0.41f, coasted[0].box.centerX, 0.02f)
        assertEquals("gone after half a second", 0, t.update(emptyList(), 1_700).size)
    }

    @Test
    fun aNewVehicleGetsItsOwnIdAndTheOldOneKeepsIts() {
        val t = VehicleTracker()
        t.update(listOf(car(0.3f)), 0)
        val first = t.update(listOf(car(0.3f)), 100)[0].id
        t.update(listOf(car(0.3f), car(0.7f)), 200)
        val both = t.update(listOf(car(0.3f), car(0.7f)), 300)
        assertEquals(first, both.first { it.box.centerX < 0.5f }.id)
        assertNotEquals(first, both.first { it.box.centerX > 0.5f }.id)
    }

    @Test
    fun aVehicleIsWhatItWasSeenAsMostOften() {
        val t = VehicleTracker()
        for (i in 0..5) t.update(listOf(car(0.5f, kind = if (i == 2) VehicleKind.TRUCK else VehicleKind.CAR)), i * 100L)
        assertEquals(VehicleKind.CAR, t.update(listOf(car(0.5f, kind = VehicleKind.TRUCK)), 600)[0].kind)
    }

    @Test
    fun aShakingBoxComesOutSteadier() {
        val t = VehicleTracker()
        var seen: List<Detection> = emptyList()
        for (i in 0..20) seen = t.update(listOf(car(0.5f, w = 0.1f + if (i % 2 == 0) 0.01f else -0.01f)), i * 100L)
        assertTrue(abs(seen[0].box.width - 0.1f) < 0.008f)
    }

    @Test
    fun aFarCarsTinyBoxStillMatchesItself() {
        val t = VehicleTracker()
        t.update(listOf(car(0.5f, w = 0.012f)), 0)
        // Shifted by most of its width: little overlap, but each box holds the other's centre.
        val seen = t.update(listOf(car(0.506f, w = 0.012f)), 100)
        assertEquals(1, seen.size)
    }
}
