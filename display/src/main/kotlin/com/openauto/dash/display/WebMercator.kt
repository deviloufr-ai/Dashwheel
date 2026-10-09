package com.openauto.dash.display

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.tan

/**
 * The Web Mercator projection raster map tiles use: at zoom z the world is
 * 2^z × 2^z tiles of [TILE] pixels, x growing east from the antimeridian, y
 * growing south from 85° north.
 */
object WebMercator {
    const val TILE = 256
    private const val MAX_LAT = 85.05112878

    /** The world's width in pixels at [zoom]. */
    fun worldPx(zoom: Int): Double = TILE.toDouble() * (1 shl zoom)

    fun x(lon: Double, zoom: Int): Double = (lon + 180.0) / 360.0 * worldPx(zoom)

    fun y(lat: Double, zoom: Int): Double {
        val r = Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT))
        return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * worldPx(zoom)
    }

    /** The tile a world pixel coordinate falls in. */
    fun tileOf(px: Double): Int = Math.floor(px / TILE).toInt()

    /** How many metres one pixel covers at [lat] and [zoom]. */
    fun metersPerPixel(lat: Double, zoom: Int): Double = 156_543.03392 * cos(Math.toRadians(lat)) / (1 shl zoom)
}
