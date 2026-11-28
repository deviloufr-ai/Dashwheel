package com.openauto.dash.roadwatch

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.tan

/*
 * The road ahead as a drawing rather than the camera's picture (issue 179):
 * what the detector's boxes are, where each stands on the road in metres, and
 * the whole of it as a [RoadPicture] for [RoadPainter] to draw. Experimental:
 * one camera gives rough distances, and nothing beside or behind the car.
 */

/** What the drawn road can show, and about how big each is: the size a box is measured against. */
enum class ThingKind(val widthM: Float, val heightM: Float) {
    CAR(1.8f, 1.5f),
    TRUCK(2.5f, 3.3f),
    BUS(2.55f, 3.1f),
    MOTORCYCLE(0.8f, 1.6f),
    BICYCLE(0.6f, 1.6f),
    PERSON(0.5f, 1.7f),
    /** The housing of three lamps, not its pole. */
    TRAFFIC_LIGHT(0.35f, 1f),
    STOP_SIGN(0.75f, 0.75f);

    val vehicle: Boolean get() = ordinal <= BICYCLE.ordinal

    companion object {
        fun of(kind: VehicleKind): ThingKind = valueOf(kind.name)

        /** What stands at the roadside behind a class of the detector's list (0-based, 11 unused), or null: the vehicles are [VehicleKind.ofCoco]'s. */
        fun roadsideOfCoco(id: Int): ThingKind? = when (id) {
            0 -> PERSON
            9 -> TRAFFIC_LIGHT
            12 -> STOP_SIGN
            else -> null
        }
    }
}

enum class LightColour {
    RED, AMBER, GREEN;

    companion object {
        /**
         * Which lamp of a traffic light is lit, from the frame's colours inside
         * its box: [rgb] gives the colour (0xRRGGBB) at a place 0..1 across and
         * down the box. Null when no lamp is plainly lit (the light's back, a
         * lamp washed out to white).
         */
        fun of(rgb: (Float, Float) -> Int): LightColour? {
            val votes = IntArray(3)
            for (j in 0 until ROWS) for (i in 0 until COLS) {
                val c = rgb((i + 0.5f) / COLS, (j + 0.5f) / ROWS)
                val r = c shr 16 and 255
                val g = c shr 8 and 255
                val b = c and 255
                if (max(r, max(g, b)) < LIT) continue
                when {
                    g > r * 1.2f && g > b * 0.9f -> votes[2]++
                    r >= g * 1.1f && g > b * 1.5f && g > r * 0.45f -> votes[1]++
                    r > g * 1.5f && r > b * 1.5f -> votes[0]++
                }
            }
            val best = votes.indices.maxByOrNull { votes[it] } ?: return null
            return entries[best].takeIf { votes[best] >= MIN_VOTES }
        }

        private const val COLS = 8
        private const val ROWS = 16
        /** A lit lamp is at least this bright in its strongest colour. */
        private const val LIT = 170
        private const val MIN_VOTES = 3
    }
}

/** A box of the detector's and what it is; [light]: a traffic light's lit lamp, when one is plain. */
data class Seen(val box: Box, val kind: ThingKind, val light: LightColour? = null)

/** Where a thing stands: [x] metres right of the car's lane's middle, [z] metres ahead; not [sure] when its two measures disagree. */
data class Placed(val x: Float, val z: Float, val sure: Boolean = true)

/**
 * A box in the frame to a place on the road. How far: from the thing's known
 * size against how big it looks, and, for what stands on the road, from how
 * far under the horizon its foot is (the camera about [CAMERA_HEIGHT_M] up).
 * The two together when they agree; when they don't, the size alone, marked
 * unsure. How far aside: from where it is in the frame against the lane's
 * middle at its row, or the car's own place while the lane isn't seen.
 */
object GroundPlace {
    const val CAMERA_HEIGHT_M = 1.3f

    /** [aspect]: the upright frame's height over its width. */
    fun of(box: Box, kind: ThingKind, band: RoadBand, lane: LaneState, carX: Float, hfovDeg: Float, aspect: Float): Placed {
        // The focal length, in frame widths.
        val focal = 1f / (2 * tan(Math.toRadians(hfovDeg.coerceIn(20f, 150f) / 2.0)).toFloat())
        val onRoad = kind.vehicle || kind == ThingKind.PERSON
        val bySize = if (kind.vehicle) kind.widthM * focal / max(box.width, 0.005f) else kind.heightM * focal / max(box.height * aspect, 0.005f)
        val drop = (box.bottom - band.horizonY) * aspect
        val byFoot = if (onRoad && drop > 0.01f) CAMERA_HEIGHT_M * focal / drop else null
        // Cut by the frame's side, a vehicle looks narrower than it is: its foot says more.
        val cut = kind.vehicle && (box.left < 0.01f || box.right > 0.99f)
        var sure = true
        val z = when {
            byFoot == null -> bySize
            cut -> byFoot
            byFoot / bySize in 0.5f..2f -> sqrt(byFoot * bySize)
            else -> { sure = false; bySize }
        }.coerceIn(0.5f, 200f)
        val l = lane.left
        val r = lane.right
        val middle = if (l != null && r != null) {
            val s = band.s(if (onRoad) box.bottom else band.topY).coerceIn(0f, 1f)
            (l.xAt(s) + r.xAt(s)) / 2
        } else carX
        return Placed((box.centerX - middle) * z / focal, z, sure)
    }
}

/** A thing on the drawn road, [x] metres right of the car's lane's middle and [z] ahead. [lead]: the vehicle ahead in the car's lane, the one the warnings are about. */
@Serializable
data class Thing(
    val kind: ThingKind,
    val x: Float,
    val z: Float,
    val sure: Boolean = true,
    val light: LightColour? = null,
    val lead: Boolean = false
)

/**
 * The frames' sightings held steady for the drawing: each thing followed from
 * frame to frame by how its box overlaps the last, its place smoothed, and
 * kept a moment after the detector last saw it, so nothing flickers.
 */
class ThingTracker {
    private class Track(var box: Box, val kind: ThingKind, var x: Float, var z: Float, var sure: Boolean, var light: LightColour?, var seenAt: Long)

    private val tracks = ArrayList<Track>()

    /** [seen] with where each stands, in the frame at [now] (ms); [lead]: LeadTracker's, drawn at its own steadier distance. */
    fun update(seen: List<Pair<Seen, Placed>>, lead: Lead?, now: Long): List<Thing> {
        val free = tracks.toMutableList()
        for ((s, place) in seen) {
            val old = free.filter { it.kind == s.kind }.maxByOrNull { it.box.iou(s.box) }?.takeIf { it.box.iou(s.box) >= SAME_IOU }
            if (old == null) {
                tracks += Track(s.box, s.kind, place.x, place.z, place.sure, s.light, now)
            } else {
                free -= old
                old.box = s.box
                old.x += (place.x - old.x) * EASE
                old.z += (place.z - old.z) * EASE
                old.sure = place.sure
                // A lamp read in one frame and not the next (glare) stays as it was.
                old.light = s.light ?: old.light
                old.seenAt = now
            }
        }
        tracks.removeAll { now - it.seenAt > KEEP_MS }
        return tracks.map {
            val isLead = lead != null && it.box == lead.box
            Thing(it.kind, it.x, if (isLead) lead!!.distanceM else it.z, it.sure || isLead, it.light, isLead)
        }
    }

    fun reset() = tracks.clear()

    private companion object {
        const val SAME_IOU = 0.3f
        /** How far a place moves towards each new sighting. */
        const val EASE = 0.4f
        const val KEEP_MS = 500L
    }
}

/**
 * All the drawn road shows. [offset]: the car in its lane (LaneState's);
 * [travelledM]: how far the car has come, for the lane's dashes to pass under
 * it; [leadLabel]: the words over the vehicle ahead, the screen's to give;
 * [around]: the map about the car, when the phone has it. Sent to the car as
 * it is (RoadSketch), so only what a drawing needs.
 */
@Serializable
data class RoadPicture(
    val things: List<Thing> = emptyList(),
    val offset: Float? = null,
    val leftSeen: Boolean = false,
    val rightSeen: Boolean = false,
    val warning: RoadWarning? = null,
    val travelledM: Float = 0f,
    val leadLabel: String? = null,
    val around: Surroundings? = null
) {
    /** The lanes drawn left and right of the car's own: as many as a vehicle was seen in, and no more. */
    fun lanesBeside(side: Int): Int =
        things.filter { it.kind.vehicle && it.x * side > LANE_M / 2 }.maxOfOrNull { ((abs(it.x) + LANE_M / 2) / LANE_M).toInt() }?.coerceAtMost(3) ?: 0

    companion object {
        const val LANE_M = 3.5f
    }
}
