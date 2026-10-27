package com.openauto.dash.replay

import com.openauto.dash.link.RoadScene
import com.openauto.dash.roadwatch.Detection
import com.openauto.dash.roadwatch.HorizonLearner
import com.openauto.dash.roadwatch.LaneCount
import com.openauto.dash.roadwatch.LaneFinder
import com.openauto.dash.roadwatch.LanePlace
import com.openauto.dash.roadwatch.LanePlaceTracker
import com.openauto.dash.roadwatch.LaneState
import com.openauto.dash.roadwatch.LaneTracker
import com.openauto.dash.roadwatch.Lead
import com.openauto.dash.roadwatch.LeadTracker
import com.openauto.dash.roadwatch.Luma
import com.openauto.dash.roadwatch.REFERENCE_HFOV
import com.openauto.dash.roadwatch.RoadBand
import com.openauto.dash.roadwatch.RoadRules
import com.openauto.dash.roadwatch.RoadWarning
import com.openauto.dash.roadwatch.SeenLanes
import com.openauto.dash.roadwatch.SeenRoad
import com.openauto.dash.roadwatch.VehicleTracker
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import kotlin.math.abs

/*
 * The replay itself, frame by frame, with no video or file I/O: the same
 * calls, in the same order, as RoadWatch.read() on the phone, so what this
 * reports is what the phone would have done with the same frames. Main.kt
 * feeds it frames and writes its report; ReplayTest feeds it drawn roads.
 */

/** What the phone knows beside the picture at one frame: its speed, and whether an indicator is on. */
data class Telemetry(val speedKmh: Float?, val indicating: Boolean = false)

/** What the pipeline made of one frame. */
data class FrameResult(
    val index: Int,
    val timeMs: Long,
    val lane: LaneState,
    val lead: Lead?,
    val warning: RoadWarning?,
    val speedKmh: Float?,
    /** How many lanes and which the car is in, from the lane model; null without it or while unsure. */
    val place: LanePlace? = null,
    /** Where the horizon was at this frame, learned or set. */
    val horizonY: Float? = null,
    /** Where the car is across the frame, as learned. */
    val carX: Float? = null
)

/** The lane model's output for one frame (lanes.py): its planes ([w] x [h], 0..1), over the frame's rows [top]..[top] + [height]. */
class ModelFrame(val chances: FloatBuffer, val w: Int, val h: Int, val top: Float, val height: Float)

/** A hand-labelled frame, to measure against: where each line crosses the bonnet's row, and how far the car ahead is. */
data class Label(val frame: Int, val leftX: Float?, val rightX: Float?, val leadM: Float?)

class ReplaySession(
    /** Where the road is; its horizon is where the learning starts, as the driver's drag on the phone. */
    private val start: RoadBand = RoadBand(),
    carX: Float = 0.5f,
    val hfovDeg: Float = REFERENCE_HFOV,
    /** The lane is looked for in a picture this wide, as on the phone. */
    private val lumaWidth: Int = 160,
    /** Off: the horizon stays where it was set, as before the phone learned it. */
    private val learnHorizon: Boolean = true
) {
    private val lanes = LaneTracker(carX)
    private val leads = LeadTracker(hfovDeg)
    private val rules = RoadRules()
    private val horizon = HorizonLearner(start.horizonY)
    var band = start
        private set
    private val places = LanePlaceTracker()
    private val _results = ArrayList<FrameResult>()
    val results: List<FrameResult> get() = _results

    private val tracks = VehicleTracker()

    /** What the lane model saw last: like the phone, it looks at every other frame, and the frames between keep this. */
    var seen: SeenRoad? = null
        private set

    /**
     * One frame: RGBA bytes, rows [rowStride] apart, standing upright; [detections] from the model for the same frame;
     * [lanes] what the lane model saw in it, null for a frame it skipped.
     */
    fun frame(index: Int, timeMs: Long, rgba: ByteBuffer, width: Int, height: Int, rowStride: Int, detections: List<Detection>, telemetry: Telemetry, lanes: ModelFrame? = null): FrameResult {
        leads.aspect = height.toFloat() / width
        val luma = Luma.fromRgba(rgba, width, height, rowStride, 0, lumaWidth)
        return frame(index, timeMs, luma, detections, telemetry, lanes?.let { SeenRoad.fromModel(it.chances, it.w, it.h, it.top, it.height, luma.w, luma.h) })
    }

    fun frame(index: Int, timeMs: Long, luma: Luma, found: List<Detection>, telemetry: Telemetry, fresh: SeenRoad? = null): FrameResult {
        if (fresh != null) seen = fresh
        // As the phone: the vehicles followed over the frames (detect.py takes the two looks in turns, as the phone).
        val detections = tracks.update(found, timeMs)
        val speed = telemetry.speedKmh
        val steady = speed != null && speed >= LEARN_KMH
        // Only the sure vehicles hide the road from the lane finder: a doubtful box would take a line with it.
        val boxes = detections.filter { it.score >= VehicleTracker.HIGH }.map { it.box }
        val sighting = seen?.let { SeenLanes.find(it, band, lanes.carX, hfovDeg, boxes, lanes.laneWidth) } ?: LaneFinder.find(luma, band, lanes.carX, hfovDeg, boxes)
        val lane = lanes.update(sighting, timeMs, learn = steady && lanes.laneWidth > 0f)
        if (learnHorizon) band = band.copy(horizonY = horizon.update(sighting, timeMs, learn = steady))
        val lead = leads.update(detections, lane, band, lanes.carX, timeMs)
        val scene = RoadScene(
            offset = lane.offset, lines = lane.lines,
            leadM = lead?.distanceM, closingMps = lead?.closingMps, ttcS = lead?.ttcS,
            leadKind = lead?.kind?.name?.lowercase(), speedKmh = speed
        )
        val warning = rules.update(scene, null, telemetry.indicating, timeMs)
        val place = places.update(seen?.let { LaneCount.find(it, band, lanes.carX, lanes.laneWidth, boxes) }, timeMs)
        val result = FrameResult(index, timeMs, lane, lead, warning, speed, place, band.horizonY, lanes.carX)
        _results += result
        return result
    }

    /** The counts over the frames so far. */
    fun report(labels: List<Label> = emptyList()): Report = Report.of(_results, labels).copy(horizonFrom = start.horizonY, horizonTo = band.horizonY)

    private companion object {
        const val LEARN_KMH = 50f
    }
}

/** A warning as it came up: its first frame and time. */
data class WarningEvent(val warning: RoadWarning, val frame: Int, val timeMs: Long)

data class Report(
    val frames: Int,
    /** The horizon at the start and at the end (learned while driving), fractions of the height. */
    val horizonFrom: Float? = null,
    val horizonTo: Float? = null,
    val bothLines: Int,
    val oneLine: Int,
    val noLine: Int,
    val leadSeen: Int,
    /** Frames by the number of lanes seen (the lane model only). */
    val places: Map<Int, Int> = emptyMap(),
    val warnings: List<WarningEvent>,
    /** Mean error of the lines' place at the bonnet's row against the labels, as a share of the width; null without labels. */
    val lineErrorX: Float?,
    /** Share of the labelled frames whose labelled lines were both found. */
    val lineRecall: Float?,
    /** Mean error of the distance to the car ahead against the labels, in metres. */
    val leadErrorM: Float?,
    val leadRecall: Float?
) {
    fun summary(): String = buildString {
        appendLine("frames: $frames")
        if (horizonFrom != null && horizonTo != null) appendLine("horizon: ${"%.2f".format(horizonFrom)} at the start, ${"%.2f".format(horizonTo)} at the end")
        appendLine("lane: both lines ${pct(bothLines)}, one ${pct(oneLine)}, none ${pct(noLine)}")
        appendLine("car ahead seen: ${pct(leadSeen)}")
        if (places.isNotEmpty()) appendLine("lanes: " + places.entries.sortedBy { it.key }.joinToString(", ") { "${it.key} lane${if (it.key > 1) "s" else ""} ${pct(it.value)}" } + ", unsure ${pct(frames - places.values.sum())}")
        appendLine("warnings: ${warnings.size}")
        warnings.forEach { appendLine("  ${it.warning} at ${"%.1f".format(it.timeMs / 1000.0)} s (frame ${it.frame})") }
        if (lineErrorX != null) appendLine("lines vs labels: found ${"%.0f".format(lineRecall!! * 100)} %, mean error ${"%.3f".format(lineErrorX)} of the width")
        if (leadErrorM != null) appendLine("car ahead vs labels: found ${"%.0f".format(leadRecall!! * 100)} %, mean error ${"%.1f".format(leadErrorM)} m")
    }

    private fun pct(n: Int) = if (frames == 0) "0 %" else "${n * 100 / frames} %"

    companion object {
        fun of(results: List<FrameResult>, labels: List<Label>): Report {
            val events = ArrayList<WarningEvent>()
            var before: RoadWarning? = null
            for (r in results) {
                if (r.warning != null && r.warning != before) events += WarningEvent(r.warning, r.index, r.timeMs)
                before = r.warning
            }
            val byFrame = results.associateBy { it.index }
            val lineErrors = ArrayList<Float>()
            var lineLabelled = 0
            var lineFound = 0
            val leadErrors = ArrayList<Float>()
            var leadLabelled = 0
            var leadFound = 0
            for (l in labels) {
                val r = byFrame[l.frame] ?: continue
                if (l.leftX != null && l.rightX != null) {
                    lineLabelled++
                    val left = r.lane.left
                    val right = r.lane.right
                    if (left != null && right != null) {
                        lineFound++
                        lineErrors += abs(left.bottomX - l.leftX)
                        lineErrors += abs(right.bottomX - l.rightX)
                    }
                }
                if (l.leadM != null) {
                    leadLabelled++
                    r.lead?.let {
                        leadFound++
                        leadErrors += abs(it.distanceM - l.leadM)
                    }
                }
            }
            return Report(
                frames = results.size,
                bothLines = results.count { it.lane.lines == 2 },
                oneLine = results.count { it.lane.lines == 1 },
                noLine = results.count { it.lane.lines == 0 },
                leadSeen = results.count { it.lead != null },
                places = results.mapNotNull { it.place?.lanes }.groupingBy { it }.eachCount(),
                warnings = events,
                lineErrorX = lineErrors.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
                lineRecall = if (lineLabelled > 0) lineFound.toFloat() / lineLabelled else null,
                leadErrorM = leadErrors.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
                leadRecall = if (leadLabelled > 0) leadFound.toFloat() / leadLabelled else null
            )
        }
    }
}
