package com.openauto.dash.roadwatch

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/*
 * Road watch's eyes for the car ahead. The phone's detector (a small model)
 * finds the vehicles in each frame; this picks the one in the car's own lane
 * and nearest, follows it from frame to frame, and works out how far it is
 * and how soon the car would reach it.
 *
 * The distance comes from how wide the vehicle looks against how wide such a
 * vehicle is (a car about 1.8 m), so it is rough. The time to reach it does
 * not depend on that guess: a vehicle that looks twice as wide is half as
 * far, whatever its real size, so the trend of the distance over the last
 * second gives the closing speed and the time left as well as the camera can.
 */

/** What the detector can see that a car can run into, how wide and how tall each usually is (a rider included). */
enum class VehicleKind(val widthM: Float, val heightM: Float) {
    CAR(1.8f, 1.5f),
    TRUCK(2.5f, 3.2f),
    BUS(2.55f, 3.0f),
    MOTORCYCLE(0.8f, 1.4f),
    BICYCLE(0.6f, 1.7f);

    companion object {
        /**
         * The vehicle behind a class of the COCO list the detector was trained on, or null. People (0) are
         * left out: taken as leads, those walking beside the road set off 6 of 19 collision alarms on the
         * village drives (2026-10-27), even between two known lines, since a street without a centre line
         * gives a lane as wide as the road.
         */
        fun ofCoco(id: Int): VehicleKind? = when (id) {
            1 -> BICYCLE
            2 -> CAR
            3 -> MOTORCYCLE
            5 -> BUS
            7 -> TRUCK
            else -> null
        }
    }
}

/** A box in the upright frame, in fractions of its width and height. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2

    /** The point ([x], [y]) lies in this box. */
    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom

    /** How much [other] overlaps this one: 0 not at all, 1 the same box. */
    fun iou(other: Box): Float {
        val w = min(right, other.right) - max(left, other.left)
        val h = min(bottom, other.bottom) - max(top, other.top)
        if (w <= 0f || h <= 0f) return 0f
        val inter = w * h
        return inter / (width * height + other.width * other.height - inter)
    }
}

/** [id]: the same vehicle from frame to frame ([VehicleTracker]); 0 when not followed. */
data class Detection(val box: Box, val kind: VehicleKind, val score: Float, val id: Int = 0)

/**
 * The vehicle ahead in the car's lane: about [distanceM] away, coming
 * nearer at [closingMps] (null while that isn't known yet, negative when it
 * pulls away), reached in [ttcS] seconds at this rate (null when not closing,
 * or when it is passed beside), [lateralM] to the side of the camera's line
 * of travel (null until it has grown enough to tell).
 */
data class Lead(
    val box: Box,
    val kind: VehicleKind,
    val distanceM: Float,
    val closingMps: Float? = null,
    val ttcS: Float? = null,
    val lateralM: Float? = null
)

/**
 * [hfovDeg]: the camera's field of view across the upright frame, from the
 * phone's camera; [aspect]: the upright frame's height over its width.
 */
class LeadTracker(var hfovDeg: Float = 66f, var aspect: Float = 0.75f) {
    private class Sample(val at: Long, val distanceM: Float, val box: Box)

    private var box: Box? = null
    private var kind: VehicleKind? = null
    private var id = 0
    private var seenAt = 0L
    private val samples = ArrayDeque<Sample>()

    /**
     * The lead vehicle among [detections] (the frame at [now], ms), in the lane
     * [lane] when it is known, else in a corridor ahead of [carX].
     */
    fun update(detections: List<Detection>, lane: LaneState, band: RoadBand, carX: Float, now: Long): Lead? {
        val ahead = detections
            .filter { it.box.bottom > band.horizonY + 0.01f && onTheRoad(it.box, band) && inLane(it.box, lane, band, carX, hfovDeg) }
            .maxByOrNull { it.box.bottom }
        if (ahead == null) {
            if (now - seenAt > LOST_MS) forget()
            return null
        }
        val before = box
        // Another vehicle than the one followed (it changed lanes, one cut in): its own history.
        val same = if (ahead.id != 0) ahead.id == id else before != null && before.iou(ahead.box) >= SAME_IOU && kind == ahead.kind
        if (!same) samples.clear()
        box = ahead.box
        kind = ahead.kind
        id = ahead.id
        seenAt = now
        val distance = distanceM(ahead.box.width, ahead.kind)
        samples.addLast(Sample(now, distance, ahead.box))
        while (samples.isNotEmpty() && now - samples.first().at > WINDOW_MS) samples.removeFirst()
        return trend(ahead, distance, now)
    }

    fun forget() {
        box = null
        kind = null
        id = 0
        samples.clear()
    }

    /** How far a [kind] that looks [widthFraction] of the frame wide is. */
    fun distanceM(widthFraction: Float, kind: VehicleKind): Float {
        val half = tan(Math.toRadians(hfovDeg / 2.0)).toFloat()
        return kind.widthM / (2 * half * max(widthFraction, 0.005f))
    }

    /** The straight line through the last second's distances: where it is now, and how fast it closes. */
    private fun trend(ahead: Detection, latest: Float, now: Long): Lead {
        val n = samples.size
        if (n < MIN_SAMPLES || now - samples.first().at < MIN_SPAN_MS) return Lead(ahead.box, ahead.kind, latest)
        var st = 0.0
        var sd = 0.0
        for (s in samples) {
            st += (s.at - now) / 1000.0
            sd += s.distanceM
        }
        val mt = st / n
        val md = sd / n
        var cov = 0.0
        var vt = 0.0
        for (s in samples) {
            val t = (s.at - now) / 1000.0 - mt
            cov += t * (s.distanceM - md)
            vt += t * t
        }
        if (vt <= 0.0) return Lead(ahead.box, ahead.kind, latest)
        val slope = cov / vt
        val distance = max(0.5, md - slope * mt).toFloat()
        val closing = (-slope).toFloat()
        val ttc = if (closing >= MIN_CLOSING_MPS) min(MAX_TTC_S, distance / closing) else null
        val edges = edgesM(ahead.kind)
        // Its side nearer the path: one car's width in from its outer side, which is its back corner. The inner
        // side of the box is its far front corner, the flank between them: further off, it reads too near the path.
        val middle = edges?.let { (it.first + it.second) / 2 }
        val inner = edges?.let { if (middle!! > 0f) it.second - ahead.kind.widthM else it.first + ahead.kind.widthM }
        // Wholly to one side of the car's path: driven past, not into.
        val beside = inner != null && (middle!! > 0f && inner >= PATH_HALF_M || middle < 0f && inner <= -PATH_HALF_M)
        return Lead(ahead.box, ahead.kind, distance, closing, ttc.takeUnless { beside }, middle)
    }

    /**
     * Where the vehicle's left and right sides are, metres to the side of the
     * camera's line of travel (+ right), from the last second: what stands in
     * the car's way stays put in the picture as it grows, what is beside its
     * path slides outward, each side moving its distance aside over the
     * vehicle's height for every bit the vehicle grows taller, whatever the
     * camera's aim or place in the car. Its height, not its width: a car
     * parked at the edge shows its flank as well as its back, so its box is
     * wider than the car, which read it nearer the path than it was (the
     * user's village drives, 2026-10-24/26: parked cars read 1 to 1.5 m
     * aside). The box's inner side is the flank's far end: only the outer
     * side is the vehicle's back, at the depth its height says.
     * Null while the vehicle hasn't grown enough to tell, then the warning stands.
     */
    private fun edgesM(kind: VehicleKind): Pair<Float, Float>? {
        if (samples.size < MIN_SAMPLES) return null
        val tallest = samples.maxOf { it.box.height }
        val shortest = samples.minOf { it.box.height }
        if (shortest <= 0f || tallest / shortest < MIN_GROWTH) return null
        val mh = samples.sumOf { it.box.height.toDouble() } / samples.size
        val ml = samples.sumOf { it.box.left.toDouble() } / samples.size
        val mr = samples.sumOf { it.box.right.toDouble() } / samples.size
        var covL = 0.0
        var covR = 0.0
        var vh = 0.0
        for (s in samples) {
            val dh = s.box.height - mh
            covL += dh * (s.box.left - ml)
            covR += dh * (s.box.right - mr)
            vh += dh * dh
        }
        // x moves (side / height) * aspect for each share of the frame's height the vehicle grows.
        val perHeight = kind.heightM / aspect
        return (covL / vh * perHeight).toFloat() to (covR / vh * perHeight).toFloat()
    }

    /**
     * Nothing on the road ahead reaches below the bonnet or fills most of the
     * picture's height (a truck that close is already being warned of): a box
     * that does is in the car, a finger on the lens or the dashboard while the
     * phone is moved, and its growth would read as a vehicle closing in.
     */
    private fun onTheRoad(box: Box, band: RoadBand) = box.bottom <= band.bonnetY + BELOW_BONNET && box.height <= MAX_HEIGHT

    private companion object {
        /** A vehicle unseen this long (the detector missed it in a few frames) is let go. */
        const val LOST_MS = 500L
        /** The same vehicle from one frame to the next overlaps itself at least this much. */
        const val SAME_IOU = 0.3f
        /** The distances the trend is drawn through: enough to smooth the boxes' jitter, short enough to catch a brake. */
        const val WINDOW_MS = 1_000L
        const val MIN_SAMPLES = 5
        const val MIN_SPAN_MS = 400L
        /** Slower than this, the boxes' jitter, not a closing vehicle. */
        const val MIN_CLOSING_MPS = 1f
        const val MAX_TTC_S = 10f
        /** The widening over the last second that tells a vehicle's side from the boxes' jitter. */
        const val MIN_GROWTH = 1.1f
        /** Half the car's own path: its half width and a little room. A vehicle wholly further aside is passed, not hit. */
        const val PATH_HALF_M = 1.1f
        /** How far below the bonnet's row a box's bottom may reach, a share of the height: a near car's box is a little loose. */
        const val BELOW_BONNET = 0.03f
        /** The most of the picture's height a vehicle ahead fills. */
        const val MAX_HEIGHT = 0.75f
    }
}

/**
 * Whether [box] stands in the car's lane: its middle between the lane's two
 * lines at its bottom row, or, without both lines, in a corridor as wide as
 * a lane in front of [carX].
 */
internal fun inLane(box: Box, lane: LaneState, band: RoadBand, carX: Float, hfovDeg: Float = REFERENCE_HFOV): Boolean {
    val y = box.bottom
    val l = lane.left
    val r = lane.right
    if (l != null && r != null) {
        val s = band.s(y)
        return box.centerX in l.xAt(s)..r.xAt(s)
    }
    val half = widthShare(CORRIDOR_HALF, hfovDeg) * band.depth(y)
    return abs(box.centerX - carX) <= half
}

/** Half a lane at the bonnet's row, as a share of the frame's width at [REFERENCE_HFOV]. */
private const val CORRIDOR_HALF = 0.32f

/**
 * Whether frame [frame] takes the horizon look (the middle of the square at
 * twice the detail) rather than the square: every other frame. Taking it two
 * frames in three while nothing was near was tried on 2026-10-27: the car
 * ahead was seen 57.1 -> 57.5 % of the time, with three more false alarms.
 */
fun horizonLookAt(frame: Long): Boolean = frame % 2 == 1L
