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

/** What the detector can see that a car can run into, and how wide each usually is. */
enum class VehicleKind(val widthM: Float) {
    CAR(1.8f),
    TRUCK(2.5f),
    BUS(2.55f),
    MOTORCYCLE(0.8f),
    BICYCLE(0.6f);

    companion object {
        /** The vehicle behind a class of the COCO list the detector was trained on (0 = person), or null. */
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

    /** How much [other] overlaps this one: 0 not at all, 1 the same box. */
    fun iou(other: Box): Float {
        val w = min(right, other.right) - max(left, other.left)
        val h = min(bottom, other.bottom) - max(top, other.top)
        if (w <= 0f || h <= 0f) return 0f
        val inter = w * h
        return inter / (width * height + other.width * other.height - inter)
    }
}

data class Detection(val box: Box, val kind: VehicleKind, val score: Float)

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

/** [hfovDeg]: the camera's field of view across the upright frame, from the phone's camera. */
class LeadTracker(var hfovDeg: Float = 66f) {
    private class Sample(val at: Long, val distanceM: Float, val width: Float, val centerX: Float, val headingRad: Float?)

    private var box: Box? = null
    private var kind: VehicleKind? = null
    private var seenAt = 0L
    private val samples = ArrayDeque<Sample>()

    /**
     * The lead vehicle among [detections] (the frame at [now], ms), in the lane
     * [lane] when it is known, else in a corridor ahead of [carX]. [headingRad]:
     * which way the car points, turning clockwise as it turns right (a
     * compass's sense, from any start), when the phone knows it.
     */
    fun update(detections: List<Detection>, lane: LaneState, band: RoadBand, carX: Float, now: Long, headingRad: Float? = null): Lead? {
        val ahead = detections
            .filter { it.box.bottom > band.horizonY + 0.01f && onTheRoad(it.box, band) && inLane(it.box, lane, band, carX, hfovDeg) }
            .maxByOrNull { it.box.bottom }
        if (ahead == null) {
            if (now - seenAt > LOST_MS) forget()
            return null
        }
        val before = box
        // Another vehicle than the one followed (it changed lanes, one cut in): its own history.
        if (before == null || before.iou(ahead.box) < SAME_IOU || kind != ahead.kind) samples.clear()
        box = ahead.box
        kind = ahead.kind
        seenAt = now
        val distance = distanceM(ahead.box.width, ahead.kind)
        samples.addLast(Sample(now, distance, ahead.box.width, ahead.box.centerX, headingRad))
        while (samples.isNotEmpty() && now - samples.first().at > WINDOW_MS) samples.removeFirst()
        return trend(ahead, distance, now)
    }

    fun forget() {
        box = null
        kind = null
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
        val lateral = lateralM(ahead.kind)
        val beside = lateral != null && abs(lateral) >= ahead.kind.widthM / 2 + OWN_HALF_WIDTH_M
        return Lead(ahead.box, ahead.kind, distance, closing, ttc.takeUnless { beside }, lateral)
    }

    /**
     * How far to the side of the camera's line of travel the vehicle is, from
     * the last second: one standing in the car's way stays put in the picture
     * as it grows, one beside its path slides outward, its middle moving
     * [lateralM] / its width for every bit it widens, whatever the camera's
     * aim or place in the car. A street without lines lets the lane take in a
     * car parked at its edge or one coming the other way in its own half; the
     * car drives past those (the user's village drives, 2026-10-24/25: 14 of
     * 20 replayed false alarms 1.8 m or more aside). Null while the vehicle
     * hasn't grown enough to tell, then the warning stands.
     *
     * On a bend everything slides across the picture as the car turns, a
     * parked car on the inside of it slipping towards the middle: with the
     * car's heading known, that turn is taken out of each sample first.
     */
    private fun lateralM(kind: VehicleKind): Float? {
        if (samples.size < MIN_SAMPLES) return null
        val widest = samples.maxOf { it.width }
        val narrowest = samples.minOf { it.width }
        if (narrowest <= 0f || widest / narrowest < MIN_GROWTH) return null
        // A turn of one radian moves what is ahead this share of the picture's width.
        val perRad = 1.0 / (2 * tan(Math.toRadians(hfovDeg / 2.0)))
        val start = samples.first().headingRad
        val turned = start != null && samples.all { it.headingRad != null }
        fun x(s: Sample) = if (turned) s.centerX + (s.headingRad!! - start!!) * perRad else s.centerX.toDouble()
        val mw = samples.sumOf { it.width.toDouble() } / samples.size
        val mx = samples.sumOf { x(it) } / samples.size
        var cov = 0.0
        var vw = 0.0
        for (s in samples) {
            cov += (s.width - mw) * (x(s) - mx)
            vw += (s.width - mw) * (s.width - mw)
        }
        return (cov / vw * kind.widthM).toFloat()
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
        /** Half the car's own width: a vehicle further aside than this and its own half width is passed, not hit. */
        const val OWN_HALF_WIDTH_M = 0.9f
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
 * The phone's detector looks at the road in turns: the whole square on one
 * frame, the horizon at twice the detail on the next. A frame's vehicles
 * are its own and those of the frame [before] that none of its own
 * overlaps, so a far car seen only at the horizon isn't lost every other
 * frame. The car ahead at 30 to 50 m was found 84 % of the time this way,
 * against 22 % from the square alone (the replay against a big model,
 * 2026-10-26).
 */
fun withBefore(now: List<Detection>, before: List<Detection>): List<Detection> =
    now + before.filter { b -> now.none { it.box.iou(b.box) > SAME_LOOK_IOU } }

/** A vehicle seen by both looks overlaps itself at least this much. */
private const val SAME_LOOK_IOU = 0.3f
