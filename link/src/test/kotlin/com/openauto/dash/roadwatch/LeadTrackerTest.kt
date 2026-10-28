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

    /**
     * A car [d] metres ahead, [dx] metres aside, as the detector boxes it in a 4:3 frame. Beside the
     * path its flank shows: the box reaches to its far front corner, [flankM] further off.
     */
    private fun car(d: Float, dx: Float = 0f, flankM: Float = 0f): Detection {
        fun x(lateral: Float, depth: Float) = 0.5f + lateral / (2 * half * depth)
        var left = x(dx - 0.9f, d)
        var right = x(dx + 0.9f, d)
        if (dx > 0f) left = x(dx - 0.9f, d + flankM) else if (dx < 0f) right = x(dx + 0.9f, d + flankM)
        val tall = 1.5f / (2 * half * d) / 0.75f
        // Its wheels on the road, lower in the frame the nearer it is.
        val bottom = 0.5f + 0.42f * (6f / d).coerceAtMost(1.2f)
        return Detection(Box(left, bottom - tall, right, bottom), VehicleKind.CAR, 0.8f)
    }

    @Test
    fun aFingerOnTheLensIsNoCar() {
        // The phone moved in its holder: a finger over the lens, boxed as a car from the top of the frame to below the bonnet.
        val finger = Detection(Box(0.24f, 0.02f, 0.78f, 0.97f), VehicleKind.CAR, 0.6f)
        assertNull(LeadTracker().update(listOf(finger), noLane, band, 0.5f, 0))
        // The same with the bonnet set lower than this car's dashboard: the box still fills nearly the whole height.
        val shorter = Detection(Box(0.22f, 0.05f, 0.75f, 0.95f), VehicleKind.CAR, 0.6f)
        assertNull(LeadTracker().update(listOf(shorter), noLane, RoadBand(horizonY = 0.5f, bonnetY = 0.95f), 0.5f, 0))
        assertNotNull("a car ahead still is", LeadTracker().update(listOf(finger, car(20f)), noLane, band, 0.5f, 0))
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
    fun theCorridorAheadIsWiderOnAnUprightPhone() {
        // Near the bonnet, 0.38 of the width off the middle: outside a lane seen at 66°, inside one seen at 51°.
        val box = Box(0.83f, 0.7f, 0.93f, 0.9f)
        assertEquals(false, inLane(box, noLane, band, 0.5f, 66f))
        assertEquals(true, inLane(box, noLane, band, 0.5f, 51f))
    }

    @Test
    fun aParkedCarBesideThePathIsPassedNotHit() {
        // A street without lines: the lane takes in a car parked 2.2 m to the side, closed on at 10 m/s.
        val t = LeadTracker()
        var lead: Lead? = null
        for (i in 0..10) lead = t.update(listOf(car(20f - i, dx = 2.2f)), noLane, band, 0.5f, i * 100L)
        assertEquals(2.2f, lead!!.lateralM!!, 0.1f)
        assertNotNull(lead.closingMps)
        assertNull("no time to impact: it is driven past", lead.ttcS)
    }

    @Test
    fun aParkedCarShowingItsFlankIsStillBeside() {
        // Parked 2.2 m aside: its flank widens its box towards the path, 4 m of it.
        val t = LeadTracker()
        var lead: Lead? = null
        for (i in 0..10) lead = t.update(listOf(car(20f - i, dx = 2.2f, flankM = 4f)), noLane, band, 0.5f, i * 100L)
        assertNull("its back corner says it is beside: passed, not hit", lead!!.ttcS)
    }

    @Test
    fun aStoppedCarInThePathStillHasItsTimeToImpact() {
        val t = LeadTracker()
        var lead: Lead? = null
        for (i in 0..10) lead = t.update(listOf(car(20f - i, dx = 0.5f)), noLane, band, 0.5f, i * 100L)
        assertEquals(0.5f, lead!!.lateralM!!, 0.1f)
        assertNotNull(lead.ttcS)
    }

    @Test
    fun aCarKeptAtTheSameDistanceHasNoSideToTell() {
        val t = LeadTracker()
        var lead: Lead? = null
        for (i in 0..10) lead = t.update(listOf(car(15f, dx = 2.2f)), noLane, band, 0.5f, i * 100L)
        assertNull(lead!!.lateralM)
    }

    @Test
    fun aCarCuttingInStartsItsOwnHistory() {
        val t = LeadTracker()
        for (i in 0..10) t.update(listOf(car(30f - i)), noLane, band, 0.5f, i * 100L)
        // A car cuts in much nearer: no closing speed borrowed from the one before.
        val cut = t.update(listOf(car(8f)), noLane, band, 0.5f, 1_100)
        assertNull(cut!!.closingMps)
    }

    @Test
    fun aVehicleReadAsAnotherKindStartsItsOwnHistory() {
        val t = LeadTracker()
        // The same far vehicle (id 7), taken for a truck, then for a car: the width it is scaled by shrinks.
        var lead: Lead? = null
        for (i in 0..10) lead = t.update(listOf(car(60f).copy(kind = VehicleKind.TRUCK, id = 7)), noLane, band, 0.5f, i * 100L)
        assertNotNull("steady so far", lead!!.closingMps)
        val asCar = t.update(listOf(car(60f).copy(id = 7)), noLane, band, 0.5f, 1_100)
        assertEquals(60f, asCar!!.distanceM, 0.5f)
        assertNull("the drop from 83 to 60 m is the kind, not a closing vehicle", asCar.closingMps)
    }
}
