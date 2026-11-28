package com.openauto.dash.roadwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

class RoadMapTest {
    private val lat = 48.0
    private val lon = 2.0
    private val unit = 1 / (RoadMap.EARTH_M * cos(Math.toRadians(lat)))

    /** World units of a place [east] and [north] metres from the test's origin. */
    private fun xs(vararg east: Double) = DoubleArray(east.size) { RoadMap.worldX(lon) + east[it] * unit }
    private fun ys(vararg north: Double) = DoubleArray(north.size) { RoadMap.worldY(lat) - north[it] * unit }

    // A road north, going on into a bend to the right; a side road east at 60 m; a house on the right at 30..40 m.
    private val map = RoadMap(
        listOf(
            MapRoad(xs(0.0, 0.0), ys(-50.0, 100.0), 3f),
            MapRoad(xs(0.0, 15.0, 40.0), ys(100.0, 150.0, 200.0), 3f),
            MapRoad(xs(0.0, 80.0), ys(60.0, 60.0), 2f)
        ),
        listOf(MapBuilding(xs(10.0, 20.0, 20.0, 10.0), ys(30.0, 30.0, 40.0, 40.0), 12f))
    )

    /** The car [east] metres off the road's middle, at the origin. */
    private fun around(bearing: Float?, east: Double = 1.0) = map.around(lat, lon + east * unit * 360, bearing)

    @Test
    fun theRoadAheadFollowsTheBendOntoTheNextRoad() {
        val s = assertNotNullAnd(around(0f))
        // From the car, on the road's middle whatever the GPS says across it.
        assertEquals(listOf(0f, 0f), s.path.take(2))
        assertEquals(0f, s.path[2], 0.2f)
        assertEquals(100f, s.path[3], 0.2f)
        assertEquals(40f, s.path[s.path.size - 2], 0.2f)
        assertEquals(200f, s.path.last(), 0.2f)
    }

    @Test
    fun theSideRoadAndTheHouseAreWhereTheyStand() {
        val s = assertNotNullAnd(around(0f))
        val side = s.roads.single()
        assertEquals(listOf(0f, 60f, 80f, 60f), side.line)
        val house = s.buildings.single()
        assertEquals(12f, house.height, 0f)
        assertEquals(listOf(10f, 30f, 20f, 30f, 20f, 40f, 10f, 40f), house.ring)
    }

    @Test
    fun headingTheOtherWayTheMapTurnsRound() {
        val s = assertNotNullAnd(around(180f))
        // Fifty metres of road left that way, and the house now behind: not drawn.
        assertEquals(50f, s.path.last(), 0.2f)
        assertTrue(s.buildings.isEmpty())
        // The side road's start is behind the car, on its left now.
        assertTrue(s.roads.isEmpty() || s.roads.all { r -> r.line.filterIndexed { i, _ -> i % 2 == 1 }.all { it >= -15f } })
    }

    @Test
    fun acrossTheRoadIsNotTheCarsRoad() {
        // Heading east at the origin: the road north is not the car's, and the side road is 60 m away.
        assertNull(around(90f))
    }

    @Test
    fun farFromAnyRoadThereIsNoMap() {
        assertNull(around(0f, east = 500.0))
        assertNotNull(around(null))
    }

    private fun assertNotNullAnd(s: Surroundings?): Surroundings {
        assertNotNull(s)
        return s!!
    }
}
