package com.openauto.dash.roadwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.tan

class LeadTrackerTest {
    private val band = RoadBand(horizonY = 0.5f, bonnetY = 0.92f)
    private val noLane = LaneState()
    private val half = tan(Math.toRadians(33.0)).toFloat()

    /** A car [d] metres ahead, [dx] metres aside, as the detector boxes it. */
    private fun car(d: Float, dx: Float = 0f): Detection {
        val wide = 1.8f / (2 * half * d)
        val cx = 0.5f + dx / (2 * half * d)
        // Its wheels on the road, lower in the frame the nearer it is.
        val bottom = 0.5f + 0.42f * (6f / d).coerceAtMost(1.2f)
        return Detection(Box(cx - wide / 2, bottom - wide * 0.8f, cx + wide / 2, bottom), VehicleKind.CAR, 0.8f)
    }

    @Test
    fun aCarAheadIsAsFarAsItLooksWide() {
        val lead = LeadTracker().update(listOf(car(20f)), noLane, band, 0.5f, 0)
        assertEquals(20f, lead!!.distanceM, 0.1f)
        assertNull("one frame says nothing of its speed", lead.closingMps)
    }

    @Test
    fun closingInOnACarGivesTheTimeToReachIt() {
        val t = LeadTracker()
        var lead: Lead? = null
        // 10 m/s faster than the car ahead, from 30 m.
        for (i in 0..10) lead = t.update(listOf(car(30f - i)), noLane, band, 0.5f, i * 100L)
        assertNotNull(lead!!.closingMps)
        assertEquals(10f, lead.closingMps!!, 0.3f)
        assertEquals(20f, lead.distanceM, 0.3f)
        assertEquals(2f, lead.ttcS!!, 0.1f)
    }

    @Test
    fun aCarKeepingItsDistanceIsNoThreat() {
        val t = LeadTracker()
        var lead: Lead? = null
        for (i in 0..10) lead = t.update(listOf(car(25f)), noLane, band, 0.5f, i * 100L)
        assertEquals(0f, lead!!.closingMps!!, 0.1f)
        assertNull(lead.ttcS)
    }

    @Test
    fun aCarInTheNextLaneIsNotTheLead() {
        val lead = LeadTracker().update(listOf(car(15f, dx = 3.5f)), noLane, band, 0.5f, 0)
        assertNull(lead)
    }

    @Test
    fun theNearestInTheLaneLeads() {
        val lead = LeadTracker().update(listOf(car(40f), car(18f), car(10f, dx = -3.5f)), noLane, band, 0.5f, 0)
        assertEquals(18f, lead!!.distanceM, 0.1f)
    }

    @Test
    fun theLanesLinesSayWhatIsInTheLane() {
        // A lane drawn narrow to the left: the car straight ahead is outside it.
        val lane = LaneState(LaneLine(-0.3f, 0.4f), LaneLine(0.3f, 0.45f))
        assertNull(LeadTracker().update(listOf(car(15f)), lane, band, 0.5f, 0))
    }

    @Test
    fun aCarCuttingInStartsItsOwnHistory() {
        val t = LeadTracker()
        for (i in 0..10) t.update(listOf(car(30f - i)), noLane, band, 0.5f, i * 100L)
        // A car cuts in much nearer: no closing speed borrowed from the one before.
        val cut = t.update(listOf(car(8f)), noLane, band, 0.5f, 1_100)
        assertNull(cut!!.closingMps)
    }
}
