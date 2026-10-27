package com.openauto.dash.roadwatch

import kotlin.math.max

/*
 * Road watch's memory of the vehicles in view, between the detector's
 * frames. The detector sees each frame on its own: a car it misses for a
 * frame is gone, a box it invents for a frame is a car, and every box
 * shakes a little. This follows each vehicle from frame to frame (the idea
 * of ByteTrack, Zhang et al. 2022): a vehicle is one it has seen twice; one
 * it misses for a moment goes on where it was heading; and the detector's
 * doubtful boxes (score under [HIGH]) count only where a vehicle already
 * is, so the far cars they are mostly are kept without the things they
 * invent. Each box is smoothed with its own motion (an alpha-beta filter),
 * so the growth the time to impact is read from is the car's, not the
 * box's shaking.
 */
class VehicleTracker {
    private class Track(val id: Int, var box: Box, var score: Float, var seenAt: Long) {
        /** How fast each side moves, a share of the frame a second. */
        val speed = FloatArray(4)
        val kinds = IntArray(VehicleKind.entries.size)
        var hits = 0

        /** Where the box is at [now], carried on from the last sighting. */
        fun predicted(now: Long): Box {
            val dt = (now - seenAt).coerceIn(0L, COAST_MS) / 1000f
            return Box(box.left + speed[0] * dt, box.top + speed[1] * dt, box.right + speed[2] * dt, box.bottom + speed[3] * dt)
        }

        fun measure(d: Detection, now: Long) {
            val dt = (now - seenAt) / 1000f
            val p = predicted(now)
            val seen = floatArrayOf(d.box.left, d.box.top, d.box.right, d.box.bottom)
            val was = floatArrayOf(p.left, p.top, p.right, p.bottom)
            for (k in 0..3) {
                val r = seen[k] - was[k]
                was[k] += ALPHA * r
                if (dt >= MIN_DT_S) speed[k] += BETA * r / dt
            }
            box = Box(was[0], was[1], was[2], was[3])
            score = d.score
            seenAt = now
            hits++
            kinds[d.kind.ordinal]++
        }

        val kind: VehicleKind get() = VehicleKind.entries[kinds.indices.maxBy { kinds[it] }]
    }

    private val tracks = ArrayList<Track>()
    private var nextId = 1

    /**
     * The vehicles in view at [now] (ms) given what the detector [found] on
     * this frame, down to its lowest score: those seen on two frames, where
     * they are now, with a steady [Detection.id] each.
     */
    fun update(found: List<Detection>, now: Long): List<Detection> {
        tracks.removeAll { now - it.seenAt > LOST_MS }
        val taken = BooleanArray(found.size)
        val matched = HashSet<Track>()
        // The sure boxes first, against every vehicle; then the doubtful ones, only against what is still unmatched.
        for (pass in 0..1) {
            val pairs = ArrayList<Triple<Float, Track, Int>>()
            for (t in tracks) {
                if (t in matched) continue
                val p = t.predicted(now)
                for (i in found.indices) {
                    if (taken[i]) continue
                    val sure = found[i].score >= HIGH
                    if (sure != (pass == 0)) continue
                    val o = overlap(p, found[i].box)
                    if (o > 0f) pairs += Triple(o, t, i)
                }
            }
            pairs.sortByDescending { it.first }
            for ((_, t, i) in pairs) {
                if (t in matched || taken[i]) continue
                matched += t
                taken[i] = true
                t.measure(found[i], now)
            }
        }
        for (i in found.indices) {
            if (taken[i] || found[i].score < HIGH) continue
            tracks += Track(nextId++, found[i].box, found[i].score, now).also { it.measure(found[i], now) }
        }
        return tracks.filter { it.hits >= CONFIRM_HITS }.map { Detection(it.predicted(now), it.kind, it.score, it.id) }
    }

    fun forget() {
        tracks.clear()
    }

    /** How well [a] and [b] are the same box: their overlap, or 1 when each holds the other's centre (a far car's box is tiny). */
    private fun overlap(a: Box, b: Box): Float {
        val iou = a.iou(b)
        if (iou >= MATCH_IOU) return iou
        val inside = a.centerX in b.left..b.right && (a.top + a.bottom) / 2 in b.top..b.bottom &&
            b.centerX in a.left..a.right && (b.top + b.bottom) / 2 in a.top..a.bottom
        return if (inside) max(iou, MATCH_IOU) else 0f
    }

    companion object {
        /** A box this sure starts a vehicle; a less sure one only goes on with a vehicle already seen. */
        const val HIGH = 0.4f
        /** The least sure box worth having from the detector: below this they are noise on any frame. */
        const val LOW = 0.2f
        /** Seen on this many frames before it counts. */
        const val CONFIRM_HITS = 2
        /** A vehicle unseen this long (a few missed frames) is let go. */
        const val LOST_MS = 500L
        /** How far ahead a box is carried on its own motion. */
        const val COAST_MS = 500L
        const val MATCH_IOU = 0.3f
        /** How much of each sighting goes into the box, and into its motion. */
        const val ALPHA = 0.5f
        const val BETA = 0.05f
        const val MIN_DT_S = 0.02f
    }
}
