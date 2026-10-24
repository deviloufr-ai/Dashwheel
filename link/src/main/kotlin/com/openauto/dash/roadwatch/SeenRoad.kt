package com.openauto.dash.roadwatch

import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * What a learned model saw of the road, laid over LaneFinder's grey picture:
 * the chance each pixel is a lane line, and the chance it is the drivable
 * road. The idea is YOLOPv2's (one network, the lane lines and the drivable
 * area as two masks beside the vehicle boxes); the model on the phone is
 * TwinLiteNet, trained for those same two masks on the same drives, small
 * enough for a phone. See docs/road-watch.md.
 */

/** The model's two masks at [Luma]'s size: [lane] and [road] hold 0..255 chances per pixel. */
class SeenRoad(val lane: Luma, val road: Luma) {
    init { require(lane.w == road.w && lane.h == road.h) { "size" } }

    companion object {
        /**
         * The model's output ([chances]: the road's plane then the lanes',
         * each [mw] x [mh], 0..1) brought onto a [w] x [h] picture of the whole
         * frame, the model having looked at the frame's rows [top]..[top] + [height]
         * (fractions of its height). Each pixel takes the most likely of the
         * model's pixels under it, so a thin line survives the shrinking.
         */
        fun fromModel(chances: FloatBuffer, mw: Int, mh: Int, top: Float, height: Float, w: Int, h: Int): SeenRoad {
            val road = Luma(w, h)
            val lane = Luma(w, h)
            for (y in 0 until h) {
                val my0 = (((y.toFloat() / h) - top) / height * mh).toInt()
                val my1 = ((((y + 1f) / h) - top) / height * mh).toInt()
                if (my1 <= 0 || my0 >= mh) continue
                for (x in 0 until w) {
                    val mx0 = x * mw / w
                    val mx1 = max(mx0 + 1, (x + 1) * mw / w)
                    var r = 0f
                    var l = 0f
                    for (my in max(0, my0)..min(mh - 1, max(my0, my1 - 1))) {
                        for (mx in mx0 until min(mw, mx1)) {
                            r = max(r, chances.get(my * mw + mx))
                            l = max(l, chances.get(mw * mh + my * mw + mx))
                        }
                    }
                    road.px[y * w + x] = (r * 255).toInt()
                    lane.px[y * w + x] = (l * 255).toInt()
                }
            }
            return SeenRoad(lane, road)
        }
    }
}

/**
 * The car's lane from what the lane model saw. Not LaneFinder's straight
 * lines voted over the whole road: the model's lines are clean but bend with
 * the road, and over a crest they meet well below the horizon. So each side's
 * line is followed instead, from the line pixel nearest the car low in the
 * picture, row by row up the road, each row taking the line pixels nearest
 * where the line was heading (a gap allowed for a dash), and the car's lane
 * line is the straight line through the followed points near the car, where
 * the lane offset is read. A side with no line at all follows the road's
 * edge the same way (a country lane has no markings).
 */
object SeenLanes {
    /** [vehicles]: the detector's boxes; nothing inside them is a line or the road's edge. */
    fun find(
        seen: SeenRoad,
        band: RoadBand = RoadBand(),
        carX: Float = 0.5f,
        hfovDeg: Float = REFERENCE_HFOV,
        vehicles: List<Box> = emptyList(),
        /** The car's lane at the bonnet's row as learned (LaneTracker's), 0 while unknown: a road's edge much further off is not its side. */
        laneWidth: Float = 0f
    ): LaneSighting {
        val w = seen.lane.w
        val h = seen.lane.h
        val y0 = (band.topY * h).roundToInt().coerceIn(0, h - 2)
        val y1 = (band.bonnetY * h).roundToInt().coerceIn(y0 + 1, h - 1)
        if (y1 - y0 < 8 || w < 32) return LaneSighting()
        val car = (carX * w).toInt().coerceIn(1, w - 2)
        val reach = widthShare(MAX_LINE_DISTANCE, hfovDeg) * w
        fun hidden(x: Int, y: Int): Boolean {
            val fx = (x + 0.5f) / w
            val fy = (y + 0.5f) / h
            return vehicles.any { fx >= it.left && fx <= it.right && fy >= it.top && fy <= it.bottom }
        }
        // The middle of each run of line pixels on [side] of the car.
        fun lines(y: Int, side: Int): List<Float> {
            val out = ArrayList<Float>()
            var x = 0
            while (x < w) {
                if (seen.lane[x, y] < SEEN || hidden(x, y)) { x++; continue }
                val from = x
                while (x < w && seen.lane[x, y] >= SEEN && !hidden(x, y)) x++
                val mid = (from + x - 1) / 2f
                if ((mid - car) * side > 0) out += mid
            }
            return out
        }
        // Where the road ends on [side], walking out from the car; none when the car's own spot is not road.
        fun edges(y: Int, side: Int): List<Float> {
            if (seen.road[car, y] < SEEN || hidden(car, y)) return emptyList()
            var x = car
            while (x + side in 0 until w && seen.road[x + side, y] >= SEEN && !hidden(x + side, y)) x += side
            return if (x + side in 0 until w) listOf(x.toFloat()) else emptyList()
        }

        val leftLine = follow(::lines, -1, y0, y1, car, reach, band, w, h)
        val rightLine = follow(::lines, 1, y0, y1, car, reach, band, w, h)
        var left = leftLine ?: follow(::edges, -1, y0, y1, car, reach, band, w, h)
        var right = rightLine ?: follow(::edges, 1, y0, y1, car, reach, band, w, h)
        // The road's edge stands in for a line only as one side of a lane of about its width: past a shoulder it is not.
        if (laneWidth > 0f && left != null && right != null && right.line.bottomX - left.line.bottomX > WIDE * laneWidth) {
            if (leftLine == null) left = null else if (rightLine == null) right = null
        }
        var l = left
        var r = right
        // Narrower than any lane: two marks of one line, or a line and its own edge. Keep the better backed.
        if (l != null && r != null && r.line.bottomX - l.line.bottomX < MIN_WIDTH) {
            if (l.support >= r.support) r = null else l = null
        }
        val meet = if (left != null && right != null) HorizonLearner.meetY(left.line, right.line, band) else null
        return LaneSighting(l?.line, r?.line, l?.support ?: 0f, r?.support ?: 0f, meet)
    }

    private class Followed(val line: LaneLine, val support: Float)

    /**
     * One side's line, followed up the road from the bonnet's row [y1] to
     * [y0], or null when too little of it is seen. [candidates] gives a row's
     * possible points on [side] (-1 left, 1 right of [car]), in pixels of a
     * picture [w] x [h].
     */
    private fun follow(
        candidates: (Int, Int) -> List<Float>,
        side: Int,
        y0: Int,
        y1: Int,
        car: Int,
        reach: Float,
        band: RoadBand,
        w: Int,
        h: Int
    ): Followed? {
        val rows = y1 - y0
        // The start: of the line's points in the lower half of the road, the nearest the car for its depth (a lane narrows up
        // the road), no further than a lane and a half: a dash's gap at the bonnet must not hand the side to the next lane's line.
        var startY = -1
        var startX = 0f
        var best = Float.MAX_VALUE
        for (y in y1 downTo (y0 + y1) / 2) {
            val depth = band.depth((y + 0.5f) / h).coerceAtLeast(0.05f)
            for (x in candidates(y, side)) {
                val d = abs(x - car) / depth
                if (abs(x - car) <= reach * depth + 1 && d < best) {
                    best = d
                    startY = y
                    startX = x
                }
            }
        }
        if (startY < 0) return null
        // From there up the road, and back down to the bonnet.
        val points = trace(candidates, side, startY, startX, y1, 1).drop(1).reversed() + trace(candidates, side, startY, startX, y0, -1)
        val ys = points.map { it.first }
        val xs = points.map { it.second }
        if (xs.size < max(MIN_POINTS, (rows * MIN_SUPPORT).toInt())) return null
        // A curve through the near part, x = a + b s + c s² (s being 0 at y0 and 1 at y1): where it meets the bonnet's row is
        // where the lane offset is read, and the car's lane line runs from there the way the curve heads there.
        val near = xs.indices.filter { (ys[it] - y0).toFloat() / rows >= NEAR_S }.takeIf { it.size >= MIN_POINTS } ?: xs.indices.toList()
        val fit = fitCurve(near.map { (ys[it] - y0).toFloat() / rows }, near.map { xs[it] }) ?: return null
        val bottom = fit[0] + fit[1] + fit[2]
        val heading = fit[1] + 2 * fit[2]
        return Followed(LaneLine((bottom + 0.5f) / w, (bottom - heading + 0.5f) / w), min(1f, xs.size.toFloat() / rows))
    }

    /**
     * The line's points from ([fromX], [fromY]) row by row towards [toY]
     * ([dir] 1 down, -1 up), each row taking the point nearest where the line
     * is heading; it stops after [MAX_GAP] rows without one.
     */
    private fun trace(candidates: (Int, Int) -> List<Float>, side: Int, fromY: Int, fromX: Float, toY: Int, dir: Int): List<Pair<Int, Float>> {
        val points = arrayListOf(fromY to fromX)
        var gap = 0
        var y = fromY + dir
        while (if (dir > 0) y <= toY else y >= toY) {
            val n = points.size
            val (lastY, lastX) = points[n - 1]
            val (backY, backX) = points[max(0, n - 4)]
            val slope = if (n >= 2) (lastX - backX) / abs(lastY - backY).coerceAtLeast(1) else 0f
            val ahead = lastX + slope * abs(y - lastY)
            val tolerance = (if (n >= 2) 2f else 4f) + abs(slope)
            val pick = candidates(y, side).filter { abs(it - ahead) <= tolerance }.minByOrNull { abs(it - ahead) }
            if (pick != null) {
                points += y to pick
                gap = 0
            } else if (++gap > MAX_GAP) break
            y += dir
        }
        return points
    }

    /** a, b, c of the least-squares x = a + b s + c s², or a straight line (c = 0) when the points span too little to bend. */
    private fun fitCurve(s: List<Float>, x: List<Float>): FloatArray? {
        val n = s.size.toDouble()
        var s1 = 0.0; var s2 = 0.0; var s3 = 0.0; var s4 = 0.0; var x0 = 0.0; var x1 = 0.0; var x2 = 0.0
        for (i in s.indices) {
            val v = s[i].toDouble()
            s1 += v; s2 += v * v; s3 += v * v * v; s4 += v * v * v * v
            x0 += x[i]; x1 += v * x[i]; x2 += v * v * x[i]
        }
        fun det(a: Double, b: Double, c: Double, d: Double, e: Double, f: Double, g: Double, h: Double, k: Double) =
            a * (e * k - f * h) - b * (d * k - f * g) + c * (d * h - e * g)
        val span = (s.maxOrNull() ?: 0f) - (s.minOrNull() ?: 0f)
        val d = det(n, s1, s2, s1, s2, s3, s2, s3, s4)
        if (span >= MIN_BEND_SPAN && abs(d) > 1e-9) {
            return floatArrayOf(
                (det(x0, s1, s2, x1, s2, s3, x2, s3, s4) / d).toFloat(),
                (det(n, x0, s2, s1, x1, s3, s2, x2, s4) / d).toFloat(),
                (det(n, s1, x0, s1, s2, x1, s2, s3, x2) / d).toFloat()
            )
        }
        val d2 = n * s2 - s1 * s1
        if (abs(d2) < 1e-9) return null
        val b = (n * x1 - s1 * x0) / d2
        return floatArrayOf(((x0 - b * s1) / n).toFloat(), b.toFloat(), 0f)
    }

    private const val SEEN = 128
    /** The car's own line is at most this far from the car at the bonnet's row (a share of the width at [REFERENCE_HFOV]), as LaneFinder's. */
    private const val MAX_LINE_DISTANCE = 0.55f
    /** Rows a line may go missing for (a dash's gap) before it is no longer followed. */
    private const val MAX_GAP = 6
    private const val MIN_POINTS = 6
    /** A line needs points on this share of the road's rows. */
    private const val MIN_SUPPORT = 0.18f
    /** The fit takes the followed points from this far down the road (0 its top, 1 the bonnet's row). */
    private const val NEAR_S = 0.4f
    /** No lane is narrower at the bonnet than this share of the width. */
    private const val MIN_WIDTH = 0.25f
    /** A lane with a road's edge for a side is no wider than this many times the lane learned. */
    private const val WIDE = 1.4f
    /** Points spanning less of the road than this are fitted with a straight line: too short to tell a bend. */
    private const val MIN_BEND_SPAN = 0.3f
}

/** How many lanes the road ahead has, as far as the lane model sees it, and which the car is in, 1 being the leftmost. */
data class LanePlace(val lanes: Int, val lane: Int)

/**
 * The lanes across the road from what the lane model saw. In a few rows near
 * the car: out from the car to the road's edges (vehicles in the other lanes
 * count as road), the lane lines between, and each gap between two of those
 * as many lanes as the car's own lane fits in it, so a worn or hidden line
 * still counts. The answer most rows agree on.
 */
object LaneCount {
    /** [laneWidth]: the car's own lane at the bonnet's row, a share of the width (LaneTracker's); null while unknown. */
    fun find(seen: SeenRoad, band: RoadBand, carX: Float, laneWidth: Float, vehicles: List<Box> = emptyList()): LanePlace? {
        if (laneWidth <= 0f) return null
        val w = seen.lane.w
        val h = seen.lane.h
        val car = (carX * w).toInt().coerceIn(0, w - 1)
        fun hidden(x: Int, y: Int): Boolean {
            val fx = (x + 0.5f) / w
            val fy = (y + 0.5f) / h
            return vehicles.any { fx >= it.left && fx <= it.right && fy >= it.top && fy <= it.bottom }
        }
        fun open(x: Int, y: Int) = seen.road[x, y] >= SEEN || seen.lane[x, y] >= SEEN || hidden(x, y)
        val votes = HashMap<LanePlace, Int>()
        val from = ((band.topY + FROM_S * (band.bonnetY - band.topY)) * h).toInt()
        val to = ((band.topY + TO_S * (band.bonnetY - band.topY)) * h).toInt().coerceAtMost(h - 1)
        for (y in from..to) {
            val lane = laneWidth * w * band.depth((y + 0.5f) / h)
            if (lane < MIN_LANE_PX || !open(car, y)) continue
            var left = car
            while (left > 0 && open(left - 1, y)) left--
            var right = car
            while (right < w - 1 && open(right + 1, y)) right++
            // The road running off the picture: its far side unknown, so neither is the count.
            if (left == 0 || right == w - 1) continue
            val marks = ArrayList<Float>()
            marks += left.toFloat()
            var x = left + 1
            while (x < right) {
                if (seen.lane[x, y] < SEEN || hidden(x, y)) { x++; continue }
                val start = x
                while (x < right && seen.lane[x, y] >= SEEN && !hidden(x, y)) x++
                marks += (start + x - 1) / 2f
            }
            marks += right.toFloat()
            // Two marks closer than half a lane are one boundary: a double line, a line beside the road's edge.
            val bounds = ArrayList<Float>()
            for (m in marks) if (bounds.isEmpty() || m - bounds.last() >= lane / 2) bounds += m else bounds[bounds.size - 1] = m
            if (bounds.size < 2) continue
            var lanes = 0
            var place = 0
            for (i in 0 until bounds.size - 1) {
                val gap = bounds[i + 1] - bounds[i]
                val inGap = max(1, (gap / lane).roundToInt())
                if (car >= bounds[i] && car < bounds[i + 1]) place = lanes + 1 + ((car - bounds[i]) / gap * inGap).toInt().coerceAtMost(inGap - 1)
                lanes += inGap
            }
            if (place == 0 || lanes > MAX_LANES) continue
            val p = LanePlace(lanes, place)
            votes[p] = (votes[p] ?: 0) + 1
        }
        val best = votes.maxByOrNull { it.value } ?: return null
        return best.key.takeIf { best.value * 2 >= votes.values.sum() && best.value >= MIN_ROWS }
    }

    private const val SEEN = 128
    /** The rows looked at, between these shares of the way down the road (0 its top, 1 the bonnet's row). */
    private const val FROM_S = 0.45f
    private const val TO_S = 0.9f
    /** A lane narrower than this many pixels in a row is too far to count in. */
    private const val MIN_LANE_PX = 6f
    private const val MIN_ROWS = 3
    private const val MAX_LANES = 6
}

/** [LaneCount]'s answers held steady: the one most frames of the last [windowMs] agree on. */
class LanePlaceTracker(private val windowMs: Long = 1_500L) {
    private val seen = ArrayDeque<Pair<Long, LanePlace?>>()

    fun update(place: LanePlace?, now: Long): LanePlace? {
        seen.addLast(now to place)
        while (seen.isNotEmpty() && now - seen.first().first > windowMs) seen.removeFirst()
        val counts = seen.mapNotNull { it.second }.groupingBy { it }.eachCount()
        val best = counts.maxByOrNull { it.value } ?: return null
        return best.key.takeIf { best.value * 2 > seen.size }
    }

    fun reset() = seen.clear()
}
