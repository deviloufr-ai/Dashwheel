package com.openauto.dash.roadwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.tan

class DrawnRoadTest {
    private val band = RoadBand(horizonY = 0.5f, bonnetY = 0.92f)
    private val half = tan(Math.toRadians(33.0)).toFloat()

    /** A [kind] [d] metres ahead and [dx] metres to the right, as the detector boxes it. */
    private fun seen(kind: VehicleKind, d: Float, dx: Float, score: Float = 0.8f, id: Int = 0): Detection {
        val mid = 0.5f + dx / (2 * half * d)
        val w = kind.widthM / (2 * half * d)
        return Detection(Box(mid - w / 2, 0.55f, mid + w / 2, 0.7f), kind, score, id)
    }

    @Test
    fun aVehicleStandsWhereItsBoxSaysFurthestFirst() {
        val placed = DrawnRoad.place(
            listOf(seen(VehicleKind.CAR, 20f, 0f, id = 1), seen(VehicleKind.TRUCK, 60f, 3.5f, id = 2)),
            lead = null, band = band, carX = 0.5f, hfovDeg = 66f
        )
        assertEquals(listOf(2, 1), placed.map { it.id })
        assertEquals(60f, placed[0].aheadM, 0.5f)
        assertEquals(3.5f, placed[0].sideM, 0.1f)
        assertEquals(20f, placed[1].aheadM, 0.2f)
        assertEquals(0f, placed[1].sideM, 0.05f)
    }

    @Test
    fun theLeadKeepsItsTrackedDistanceAndDoubtfulBoxesAreLeftOut() {
        val ahead = seen(VehicleKind.CAR, 30f, 0f)
        val placed = DrawnRoad.place(
            listOf(ahead, seen(VehicleKind.CAR, 40f, -3.5f, score = 0.2f), seen(VehicleKind.CAR, 500f, 0f)),
            lead = Lead(ahead.box, VehicleKind.CAR, distanceM = 28f), band = band, carX = 0.5f, hfovDeg = 66f
        )
        assertEquals(1, placed.size)
        assertTrue(placed[0].lead)
        assertEquals(28f, placed[0].aheadM, 0.01f)
    }
}
