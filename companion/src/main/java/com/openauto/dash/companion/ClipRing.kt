package com.openauto.dash.companion

/*
 * The bookkeeping behind the road recorder ([RoadRecorder]): the camera
 * records the road in short segments, one file after another, and only the
 * last minute or two is kept, the oldest segment deleted as a new one ends.
 * A clip is the segments around a moment worth keeping (an impact, a hard
 * brake, a warning, a tap): the ones from [ClipRing.beforeMs] before it to
 * [ClipRing.afterMs] after it. Until a clip has all of its segments, and
 * until the recorder has copied them out ([ClipRing.release]), its segments
 * are safe from deletion. Plain Kotlin, tested on the JVM.
 */

/** One segment of the recording: its file's name, and when it ran (elapsed-realtime ms). */
data class Segment(val name: String, val startMs: Long, val endMs: Long) {
    fun overlaps(fromMs: Long, toMs: Long): Boolean = endMs > fromMs && startMs < toMs
}

/** A moment kept: the segments that cover it, [complete] once the ones after it have been recorded too. */
data class Clip(
    /** When it was asked for (elapsed-realtime ms); its id in the ring. */
    val atMs: Long,
    /** Why: "impact", "brake", "collision", "hazard", "button". */
    val trigger: String,
    val segments: List<Segment>,
    val complete: Boolean
)

class ClipRing(
    /** How much of the recording stays on disk in all. */
    private val ringMs: Long = 120_000L,
    val beforeMs: Long = 30_000L,
    val afterMs: Long = 30_000L
) {
    private val segments = ArrayList<Segment>()
    private val clips = LinkedHashMap<Long, Clip>()
    /** Clips built and copied out: their segments are the ring's to delete again. */
    private val released = HashSet<Long>()
    private var lastKeepAt = Long.MIN_VALUE / 2

    /** What came of a segment ending: the clips now complete, and the segments to delete, in that order. */
    data class Done(val completed: List<Clip>, val delete: List<Segment>)

    /** The segments on disk, oldest first. */
    val onDisk: List<Segment> get() = segments.toList()

    /** The clips asked for and not yet released, oldest first. */
    val pending: List<Clip> get() = clips.values.filter { it.atMs !in released }

    /**
     * A moment to keep. False when one was kept this moment already (two
     * triggers for the same event: the brake and then the impact), in which
     * case that clip stands for both.
     */
    fun keep(atMs: Long, trigger: String): Boolean {
        if (atMs - lastKeepAt < SAME_EVENT_MS) return false
        lastKeepAt = atMs
        clips[atMs] = clipAt(atMs, trigger)
        return true
    }

    /** The recorder finished [segment]: it joins the ring. */
    fun segmentDone(segment: Segment): Done {
        segments += segment
        segments.sortBy { it.startMs }
        val completed = ArrayList<Clip>()
        for ((at, clip) in clips) {
            if (clip.complete) continue
            val refreshed = clipAt(at, clip.trigger)
            clips[at] = refreshed
            if (refreshed.complete) completed += refreshed
        }
        return Done(completed, prune(segment.endMs))
    }

    /** The clip at [atMs] is copied out: its segments may go when their time comes. */
    fun release(atMs: Long) {
        if (atMs in clips) released += atMs
    }

    /**
     * The recording stops (the camera closed, the phone too hot): a clip still
     * waiting for its after-segments is complete with what it has.
     */
    fun stopped(): List<Clip> {
        val out = ArrayList<Clip>()
        for ((at, clip) in clips) {
            if (clip.complete || at in released) continue
            val closed = clip.copy(complete = true)
            clips[at] = closed
            if (closed.segments.isNotEmpty()) out += closed else released += at
        }
        return out
    }

    /** The clip around [atMs] from the segments on disk now. */
    private fun clipAt(atMs: Long, trigger: String): Clip {
        val from = atMs - beforeMs
        val to = atMs + afterMs
        val covering = segments.filter { it.overlaps(from, to) }
        val complete = segments.any { it.endMs >= to }
        return Clip(atMs, trigger, covering, complete)
    }

    /** The segments older than the ring, unless a clip still holds them. */
    private fun prune(newestEndMs: Long): List<Segment> {
        val held = clips.values.filter { it.atMs !in released }.flatMap { it.segments }.map { it.name }.toSet()
        val old = segments.filter { it.endMs < newestEndMs - ringMs && it.name !in held }
        segments.removeAll(old.toSet())
        // A released clip whose segments are all gone needs no more remembering.
        val names = segments.map { it.name }.toSet()
        released.filter { at -> clips[at]?.segments?.none { it.name in names } == true }.forEach { clips.remove(it); released.remove(it) }
        return old
    }

    private companion object {
        /** Triggers this close together are one event. */
        const val SAME_EVENT_MS = 10_000L
    }
}

/**
 * The moments the phone itself notices, from its sensors: a knock hard
 * enough to be an impact, and braking hard enough to be an emergency, by
 * the GPS speed. Thresholds as dashcams use them; a pothole stays under.
 */
class ImpactDetector(private val hasGravity: Boolean = true) {
    private var lastImpactAt = Long.MIN_VALUE / 2
    private var lastBrakeAt = Long.MIN_VALUE / 2
    private var speedAt = 0L
    private var speedKmh = Float.NaN

    private class Gravity(val at: Long, val x: Float, val y: Float, val z: Float)
    private val gravities = ArrayDeque<Gravity>()
    private var pendingAt: Long? = null

    /**
     * The phone's acceleration without gravity ([ax], [ay], [az] in m/s²) at
     * [atMs]: true for an impact kept at once (once per event). A knock that
     * isn't hard enough to be sure waits for [gravity] to tell whether the
     * phone turned around it.
     */
    fun acceleration(ax: Float, ay: Float, az: Float, atMs: Long): Boolean {
        val g = Math.sqrt((ax * ax + ay * ay + az * az).toDouble()) / 9.81
        if (g < IMPACT_G || atMs - lastImpactAt < REPEAT_MS) return false
        lastImpactAt = atMs
        if (g >= SURE_G || !hasGravity) return true
        pendingAt = atMs
        return false
    }

    /**
     * Gravity in the phone's axes at [atMs]: the time of a knock now known to
     * be an impact, or null. A phone taken from its holder, pressed into it or
     * tipping back turns as it is knocked; in a crash the holder keeps it
     * pointing at the road. Clips of the car parked with a hand on the phone
     * were most of the "impacts" on the road (2026-10-24/25).
     */
    fun gravity(x: Float, y: Float, z: Float, atMs: Long): Long? {
        gravities.addLast(Gravity(atMs, x, y, z))
        while (atMs - gravities.first().at > GRAVITY_KEEP_MS) gravities.removeFirst()
        val at = pendingAt ?: return null
        if (atMs - at < TURN_WINDOW_MS) return null
        pendingAt = null
        val before = gravities.firstOrNull { it.at >= at - TURN_WINDOW_MS } ?: return at
        val now = gravities.last()
        val dot = before.x * now.x + before.y * now.y + before.z * now.z
        val norms = Math.sqrt(((before.x * before.x + before.y * before.y + before.z * before.z) * (now.x * now.x + now.y * now.y + now.z * now.z)).toDouble())
        if (norms <= 0.0) return at
        val turnedDeg = Math.toDegrees(Math.acos((dot / norms).coerceIn(-1.0, 1.0)))
        return if (turnedDeg <= MAX_TURN_DEG) at else null
    }

    /** The GPS speed, once a second or so: true for a hard brake (once per event). */
    fun speed(kmh: Float, atMs: Long): Boolean {
        val before = speedKmh
        val dt = (atMs - speedAt) / 1000.0
        speedKmh = kmh
        speedAt = atMs
        if (before.isNaN() || dt <= 0.0 || dt > 3.0) return false
        val decel = (before - kmh) / 3.6 / dt
        if (decel < HARD_BRAKE_MPS2 || before < MIN_BRAKE_KMH || atMs - lastBrakeAt < REPEAT_MS) return false
        lastBrakeAt = atMs
        return true
    }

    private companion object {
        /** About what a dashcam's G-sensor on its middle setting fires at; a pothole gives about 1 g. */
        const val IMPACT_G = 2.2
        // shortcut: SURE_G and MAX_TURN_DEG are judged, not measured on a crash; revisit with the clips' sensor data.
        /** Harder than this, an impact whatever the phone did: a crash may throw it from its holder. */
        const val SURE_G = 4.0
        /** How far before and after a knock the phone's turn is measured. */
        const val TURN_WINDOW_MS = 1_000L
        const val GRAVITY_KEEP_MS = 3_000L
        /** A phone turned more than this around a knock was in a hand. */
        const val MAX_TURN_DEG = 20.0
        /** Emergency braking on dry tarmac is 7 to 9 m/s²; firm braking 3 to 4. */
        const val HARD_BRAKE_MPS2 = 6.0
        /** Slower than this, a stop is not a brake worth a clip. */
        const val MIN_BRAKE_KMH = 25f
        const val REPEAT_MS = 10_000L
    }
}
