package com.openauto.dash.roadwatch

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/*
 * The map under the drawn road (issue 179, stage 2): the roads and buildings
 * of the map tiles around the car, and from them what the drawing needs, in
 * the car's own frame: how the road it is on bends ahead, the roads that meet
 * it, the buildings beside it. Positions are kept in world units: the whole
 * Web Mercator map 0..1 across and down.
 */

/** A road's middle line, in world units. */
class MapRoad(val xs: DoubleArray, val ys: DoubleArray, val halfWidthM: Float)

/** A building's outline, in world units, and its height. */
class MapBuilding(val xs: DoubleArray, val ys: DoubleArray, val heightM: Float)

/** A road meeting or passing the car's own: its middle [line] as x, z pairs (metres right of the car, metres ahead). */
@Serializable
data class SideRoad(val line: List<Float>, val halfWidth: Float)

/** A building: its outline [ring] as x, z pairs. */
@Serializable
data class Block(val ring: List<Float>, val height: Float)

/**
 * The map around the car, in its frame (x metres right, z metres ahead, the
 * car heading along its road): [path], the middle of its road ahead from
 * (0, 0), as x, z pairs; the other [roads]; the [buildings], nearest first.
 */
@Serializable
data class Surroundings(val path: List<Float> = emptyList(), val roads: List<SideRoad> = emptyList(), val buildings: List<Block> = emptyList())

class RoadMap(val roads: List<MapRoad>, val buildings: List<MapBuilding>) {
    /**
     * The map as the car at [lat], [lon] sees it, heading [bearingDeg]
     * (clockwise from north; null while not known, and the nearest road is
     * taken as it comes). Null when no road is near enough to be the car's.
     */
    fun around(lat: Double, lon: Double, bearingDeg: Float?): Surroundings? {
        val x0 = worldX(lon)
        val y0 = worldY(lat)
        val scale = EARTH_M * cos(Math.toRadians(lat))
        val reach = REACH_M / scale
        fun east(x: Double) = ((x - x0) * scale).toFloat()
        fun north(y: Double) = (-(y - y0) * scale).toFloat()
        fun near(xs: DoubleArray, ys: DoubleArray) = xs.indices.any { abs(xs[it] - x0) < reach && abs(ys[it] - y0) < reach }
        val close = roads.filter { near(it.xs, it.ys) }

        // The car's road: the nearest stretch that runs the way the car is heading.
        val heading = bearingDeg?.let { Math.toRadians(it.toDouble()) }
        var best: MapRoad? = null
        var bestAt = 0
        var bestScore = Float.MAX_VALUE
        for (road in close) for (i in 0 until road.xs.size - 1) {
            val ax = east(road.xs[i]); val ay = north(road.ys[i])
            val bx = east(road.xs[i + 1]); val by = north(road.ys[i + 1])
            val away = distanceToSegment(ax, ay, bx, by)
            if (away > MATCH_M) continue
            var turn = 0f
            if (heading != null) {
                // Either way along it: the map doesn't say which way the car drives it.
                val d = abs(wrap(atan2((bx - ax).toDouble(), (by - ay).toDouble()) - heading))
                turn = Math.toDegrees(if (d > PI / 2) PI - d else d).toFloat()
                if (turn > MATCH_DEG) continue
            }
            val score = away + turn * M_PER_DEG
            if (score < bestScore) { bestScore = score; best = road; bestAt = i }
        }
        val own = best ?: return null

        // Forward: along that stretch, the way the car heads.
        var fx = east(own.xs[bestAt + 1]) - east(own.xs[bestAt])
        var fy = north(own.ys[bestAt + 1]) - north(own.ys[bestAt])
        val len = hypot(fx, fy).coerceAtLeast(0.01f)
        fx /= len; fy /= len
        var forwards = true
        if (heading != null && fx * sin(heading) + fy * cos(heading) < 0) { fx = -fx; fy = -fy; forwards = false }
        // The car on the road's middle, not where the GPS puts it across the road.
        val t = -(east(own.xs[bestAt]) * (if (forwards) fx else -fx) + north(own.ys[bestAt]) * (if (forwards) fy else -fy))
        val ox = east(own.xs[bestAt]) + (if (forwards) fx else -fx) * t.coerceIn(0f, len)
        val oy = north(own.ys[bestAt]) + (if (forwards) fy else -fy) * t.coerceIn(0f, len)
        fun carX(x: Double, y: Double) = (east(x) - ox) * fy - (north(y) - oy) * fx
        fun carZ(x: Double, y: Double) = (east(x) - ox) * fx + (north(y) - oy) * fy

        // Its middle line ahead, on along whichever road carries straightest on where one ends.
        val path = arrayListOf(0f, 0f)
        val used = HashSet<MapRoad>()
        var road = own
        var at = if (forwards) bestAt + 1 else bestAt
        var step = if (forwards) 1 else -1
        var run = 0f
        while (run < PATH_M && used.add(road)) {
            while (at in road.xs.indices && run < PATH_M) {
                val x = carX(road.xs[at], road.ys[at])
                val z = carZ(road.xs[at], road.ys[at])
                run += hypot(x - path[path.size - 2], z - path[path.size - 1])
                path += x; path += z
                at += step
            }
            if (path.size < 4) break
            val ex = path[path.size - 2]; val ez = path[path.size - 1]
            val hx = ex - path[path.size - 4]; val hz = ez - path[path.size - 3]
            var next: MapRoad? = null
            var nextTurn = MAX_TURN
            for (r in close) {
                if (r in used || r.xs.size < 2) continue
                for (end in intArrayOf(0, r.xs.size - 1)) {
                    if (hypot(carX(r.xs[end], r.ys[end]) - ex, carZ(r.xs[end], r.ys[end]) - ez) > JOIN_M) continue
                    val to = if (end == 0) 1 else r.xs.size - 2
                    val turn = abs(wrap(atan2((carX(r.xs[to], r.ys[to]) - ex).toDouble(), (carZ(r.xs[to], r.ys[to]) - ez).toDouble()) - atan2(hx.toDouble(), hz.toDouble()))).toFloat()
                    if (turn < nextTurn) { nextTurn = turn; next = r; at = to; step = if (end == 0) 1 else -1 }
                }
            }
            road = next ?: break
        }

        val sides = ArrayList<SideRoad>()
        for (r in close) {
            if (r in used) continue
            // Cut to what the drawing shows: from just behind the car to the haze.
            var line = ArrayList<Float>()
            fun flush() { if (line.size >= 4) sides += SideRoad(line, r.halfWidthM); line = ArrayList() }
            for (i in 0 until r.xs.size - 1) {
                var ax = carX(r.xs[i], r.ys[i]); var az = carZ(r.xs[i], r.ys[i])
                var bx = carX(r.xs[i + 1], r.ys[i + 1]); var bz = carZ(r.xs[i + 1], r.ys[i + 1])
                if ((az < BEHIND_M && bz < BEHIND_M) || (az > AHEAD_M && bz > AHEAD_M)) { flush(); continue }
                fun cut(limit: Float) {
                    if ((az < limit) != (bz < limit)) {
                        val k = (limit - az) / (bz - az)
                        val cx = ax + (bx - ax) * k
                        if ((limit == BEHIND_M) == (az < limit)) { ax = cx; az = limit } else { bx = cx; bz = limit }
                    }
                }
                cut(BEHIND_M); cut(AHEAD_M)
                if (line.isEmpty() || line[line.size - 2] != round(ax) || line[line.size - 1] != round(az)) { flush(); line += round(ax); line += round(az) }
                line += round(bx); line += round(bz)
            }
            flush()
        }

        val blocks = buildings.filter { near(it.xs, it.ys) }.mapNotNull { b ->
            val ring = ArrayList<Float>(b.xs.size * 2)
            var nearest = Float.MAX_VALUE
            for (i in b.xs.indices) {
                val x = carX(b.xs[i], b.ys[i]); val z = carZ(b.xs[i], b.ys[i])
                // Not one that reaches behind the eye: its walls would turn inside out.
                if (z < BEHIND_M) return@mapNotNull null
                nearest = minOf(nearest, hypot(x, z))
                ring += round(x); ring += round(z)
            }
            if (nearest > BUILDINGS_M) null else nearest to Block(ring, b.heightM)
        }.sortedBy { it.first }.take(MAX_BUILDINGS).map { it.second }

        return Surroundings(path.map(::round), sides, blocks)
    }

    /** How far the car (at 0, 0) is from the stretch a..b, metres. */
    private fun distanceToSegment(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val l2 = dx * dx + dy * dy
        val t = if (l2 <= 0f) 0f else (-(ax * dx + ay * dy) / l2).coerceIn(0f, 1f)
        return hypot(ax + dx * t, ay + dy * t)
    }

    companion object {
        const val EARTH_M = 40_075_016.686
        fun worldX(lon: Double): Double = lon / 360.0 + 0.5
        fun worldY(lat: Double): Double = 0.5 - ln(tan(PI / 4 + Math.toRadians(lat) / 2)) / (2 * PI)

        /** An angle brought into -π..π. */
        private fun wrap(a: Double): Double {
            var x = a % (2 * PI)
            if (x > PI) x -= 2 * PI
            if (x < -PI) x += 2 * PI
            return x
        }

        /** To the decimetre: plenty for a drawing, and short to send to the car. */
        private fun round(v: Float): Float = (v * 10).roundToInt() / 10f

        /** Only what is within this of the car is looked at. */
        private const val REACH_M = 320.0
        /** The car's road is no further from the GPS's place than this, and heads no more than [MATCH_DEG] off the car's heading. */
        private const val MATCH_M = 30f
        private const val MATCH_DEG = 40f
        /** A degree off the heading counts as this many metres away. */
        private const val M_PER_DEG = 0.5f
        private const val PATH_M = 260f
        /** Two roads whose ends are this near are one road going on. */
        private const val JOIN_M = 3f
        /** The road goes on along the one that turns least, and no more than this (radians): past that it is a junction's side road. */
        private const val MAX_TURN = 0.9f
        private const val BEHIND_M = -15f
        private const val AHEAD_M = 260f
        private const val BUILDINGS_M = 220f
        /** The nearest buildings drawn and sent to the car. */
        private const val MAX_BUILDINGS = 60
    }
}
