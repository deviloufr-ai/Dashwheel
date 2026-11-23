package com.openauto.dash.roadwatch

import kotlin.math.max
import kotlin.math.tan

/** A vehicle put on the drawn road: metres ahead of the car and to its right (negative: its left). */
data class PlacedVehicle(val id: Int, val kind: VehicleKind, val aheadM: Float, val sideM: Float, val lead: Boolean)

/**
 * Road watch drawn instead of filmed (experimental): where each vehicle the
 * camera found stands on the road, from its box alone. As far as its kind's
 * usual width makes it look, as LeadTracker reckons it, and as far to the
 * side as its middle is off the car's own in the picture.
 */
object DrawnRoad {
    /** Nearer or further than this, a vehicle is left out: a box that wide or that small is no vehicle to draw. */
    const val NEAR_M = 2f
    const val FAR_M = 120f

    /** The sure vehicles of [seen] below the horizon, the furthest first, as they are drawn. */
    fun place(seen: List<Detection>, lead: Lead?, band: RoadBand, carX: Float, hfovDeg: Float): List<PlacedVehicle> {
        val half = tan(Math.toRadians(hfovDeg / 2.0)).toFloat()
        return seen
            .filter { it.score >= VehicleTracker.HIGH && it.box.bottom > band.horizonY }
            .map { d ->
                val isLead = lead != null && d.box == lead.box
                val ahead = if (lead != null && d.box == lead.box) lead.distanceM else d.kind.widthM / (2 * half * max(d.box.width, 0.005f))
                PlacedVehicle(d.id, d.kind, ahead, ahead * 2 * half * (d.box.centerX - carX), isLead)
            }
            .filter { it.aheadM in NEAR_M..FAR_M }
            .sortedByDescending { it.aheadM }
    }
}
