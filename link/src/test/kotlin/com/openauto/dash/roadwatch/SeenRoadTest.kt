package com.openauto.dash.roadwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.FloatBuffer
import kotlin.math.abs

class SeenRoadTest {
    private val band = RoadBand(horizonY = 0.5f, bonnetY = 0.92f)

    @Test
    fun theModelsLinesAreTheLane() {
        val got = SeenLanes.find(seen(lines = listOf(straight(0.15f), straight(0.85f)), road = straight(-0.6f) to straight(1.6f)), band)
        assertNear(0.15f, got.left?.bottomX)
        assertNear(0.85f, got.right?.bottomX)
        assertNear(0.5f + (0.15f - 0.5f) * band.depth(band.topY), got.left?.topX)
    }

    @Test
    fun aBendOverACrestStillGivesBothLines() {
        // The road bends right and drops out of sight: its lines meet well below the horizon.
        val bend = { b: Float -> { t: Float -> 0.5f + (b - 0.5f) * t + 0.25f * (1 - t) * (1 - t) * 4f * (1 - t) } }
        val got = SeenLanes.find(seen(lines = listOf(bend(0.15f), bend(0.85f)), road = bend(0.1f) to bend(0.9f), fromT = 0.35f), band)
        assertNear(0.15f, got.left?.bottomX)
        assertNear(0.85f, got.right?.bottomX)
    }

    @Test
    fun aDashedLineIsFollowedAcrossItsGaps() {
        val dashed = { t: Float -> if (((1f / t) * 2f).toInt() % 3 == 0) 0.2f + 0.3f * (1 - t) else Float.NaN }
        val got = SeenLanes.find(seen(lines = listOf(dashed, straight(0.8f)), road = straight(-0.6f) to straight(1.6f)), band)
        assertNear(0.2f, got.left?.bottomX)
        assertNear(0.8f, got.right?.bottomX)
    }

    @Test
    fun aDashsGapAtTheBonnetDoesNotHandTheSideToTheNextLine() {
        // Changing lanes: the dashed line nearly under the car has a gap at the bonnet's row, where the next lane's line shows.
        val near = { t: Float -> if (t > 0.85f) Float.NaN else 0.5f + 0.12f * t }
        val got = SeenLanes.find(seen(lines = listOf(straight(0.15f), near, straight(0.98f)), road = straight(-0.6f) to straight(1.6f)), band)
        assertNear(0.62f, got.right?.bottomX)
    }

    @Test
    fun anUnmarkedRoadsEdgeStandsInForTheLine() {
        // A country road, no markings: its near edge on the right, its far side well beyond a lane away.
        val got = SeenLanes.find(seen(lines = emptyList(), road = straight(-0.6f) to straight(0.85f)), band)
        assertNear(0.85f, got.right?.bottomX)
        assertNull(got.left)
    }

    @Test
    fun aRoadsEdgePastTheShoulderIsNotTheLanesSide() {
        // The left line unseen; the road's edge lies a wide shoulder further out.
        val masks = seen(lines = listOf(straight(0.85f)), road = straight(-0.35f) to straight(1.6f))
        assertNear(-0.35f, SeenLanes.find(masks, band).left?.bottomX ?: -0.35f)
        assertNull(SeenLanes.find(masks, band, laneWidth = 0.7f).left)
        assertNear(0.85f, SeenLanes.find(masks, band, laneWidth = 0.7f).right?.bottomX)
    }

    @Test
    fun aLineBeatsTheRoadsEdgeBeyondIt() {
        val got = SeenLanes.find(seen(lines = listOf(straight(0.15f), straight(0.8f)), road = straight(-0.6f) to straight(0.95f)), band)
        assertNear(0.8f, got.right?.bottomX)
    }

    @Test
    fun aVehicleAheadIsNotALine() {
        // A van's back right ahead, its edges marked like lines: inside its box nothing counts.
        val van = Box(0.4f, 0.6f, 0.62f, 0.95f)
        val masks = seen(lines = listOf(straight(0.15f), straight(0.85f), { t -> if (t > 0.5f) 0.45f else Float.NaN }), road = straight(-0.6f) to straight(1.6f))
        assertNear(0.45f, SeenLanes.find(masks, band).left?.bottomX)
        assertNear(0.15f, SeenLanes.find(masks, band, vehicles = listOf(van)).left?.bottomX)
    }

    @Test
    fun threeLanesTheCarInTheMiddle() {
        val masks = seen(lines = listOf(0.05f, 0.35f, 0.65f, 0.95f).map(::straight), road = straight(0.04f) to straight(0.96f))
        assertEquals(LanePlace(3, 2), LaneCount.find(masks, band, carX = 0.5f, laneWidth = 0.3f))
    }

    @Test
    fun aWornLineStillSplitsTheRoad() {
        // Two lanes, the line between them gone: the road is two of the car's lanes wide.
        val masks = seen(lines = listOf(straight(0.15f), straight(0.85f)), road = straight(0.14f) to straight(0.86f))
        assertEquals(LanePlace(2, 1), LaneCount.find(masks, band, carX = 0.32f, laneWidth = 0.35f))
    }

    @Test
    fun aRampIsOneLane() {
        val masks = seen(lines = listOf(straight(0.15f), straight(0.85f)), road = straight(0.14f) to straight(0.86f))
        assertEquals(LanePlace(1, 1), LaneCount.find(masks, band, carX = 0.5f, laneWidth = 0.7f))
    }

    @Test
    fun aCarInTheNextLaneHidesNoLane() {
        val masks = seen(lines = listOf(0.05f, 0.35f, 0.65f, 0.95f).map(::straight), road = straight(0.04f) to straight(0.96f))
        val van = Box(0.6f, 0.55f, 0.8f, 0.95f)
        assertEquals(LanePlace(3, 2), LaneCount.find(masks, band, carX = 0.5f, laneWidth = 0.3f, vehicles = listOf(van)))
    }

    @Test
    fun theLaneHeldSteadyThroughAStrayFrame() {
        val t = LanePlaceTracker()
        repeat(10) { t.update(LanePlace(3, 2), it * 100L) }
        assertEquals(LanePlace(3, 2), t.update(LanePlace(3, 1), 1_000))
        assertEquals(LanePlace(3, 2), t.update(null, 1_100))
    }

    @Test
    fun theModelsPicturePlacedOnTheFrame() {
        // A 4 x 2 output over the frame's lower half, onto a 2 x 4 picture: the top half stays empty, each pixel the most likely under it.
        val road = floatArrayOf(0.1f, 0.9f, 0.2f, 0.3f, 1f, 0f, 0f, 0f)
        val lane = floatArrayOf(0f, 0f, 0f, 0.6f, 0f, 0.4f, 0f, 0f)
        val got = SeenRoad.fromModel(FloatBuffer.wrap(road + lane), mw = 4, mh = 2, top = 0.5f, height = 0.5f, w = 2, h = 4)
        assertEquals(listOf(0, 0, 0, 0, 229, 76, 255, 0), got.road.px.toList())
        assertEquals(listOf(0, 0, 0, 0, 0, 153, 102, 0), got.lane.px.toList())
    }

    /** A line running to the middle of the horizon, crossing the bonnet's row at [b]: its x (a fraction of the width) at depth t. */
    private fun straight(b: Float) = { t: Float -> 0.5f + (b - 0.5f) * t }

    /**
     * The model's masks: [lines] (x as a fraction of the width at depth t, NaN
     * where a dash is missing), the drivable road between the two edges of
     * [road], all seen from depth [fromT] down to the bonnet's row.
     */
    private fun seen(lines: List<(Float) -> Float>, road: Pair<(Float) -> Float, (Float) -> Float>, fromT: Float = 0f, w: Int = 160, h: Int = 120): SeenRoad {
        val lane = Luma(w, h)
        val drivable = Luma(w, h)
        val yh = band.horizonY * h
        val yb = band.bonnetY * h
        for (y in 0 until h) {
            val t = (y + 0.5f - yh) / (yb - yh)
            if (t <= fromT || y > yb) continue
            for (x in 0 until w) {
                val fx = (x + 0.5f) / w
                if (fx >= road.first(t) && fx <= road.second(t)) drivable.px[y * w + x] = 230
                if (lines.any { abs(fx - it(t)) * w <= maxOf(0.6f, 1.5f * t) }) lane.px[y * w + x] = 220
            }
        }
        return SeenRoad(lane, drivable)
    }

    private fun assertNear(expected: Float, got: Float?) {
        assertNotNull("no line found", got)
        assertTrue("expected $expected, got $got", abs(expected - got!!) < 0.03f)
    }
}
