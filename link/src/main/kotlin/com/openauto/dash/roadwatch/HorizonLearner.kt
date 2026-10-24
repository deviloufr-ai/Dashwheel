package com.openauto.dash.roadwatch

import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * The horizon, learned while driving: a lane's two lines run to a point on
 * it, so where the lines on either side of the car meet is where it is.
 * Crests put that point low and dips high, sometimes for a minute of ramps,
 * so the horizon is the middle of where the lines met over the last couple
 * of minutes of driving (a fading histogram), not a running average a ramp
 * drags along. The driver's drag on the phone sets where it starts, worth a
 * few seconds of driving. Set right, it matters more than any lane finder:
 * a drive replayed with the horizon at 0.5 instead of its 0.59 kept both
 * lines 64 % of the time instead of 91 % (docs/road-watch.md).
 */
class HorizonLearner(horizonY: Float) {
    var horizonY = horizonY.coerceIn(MIN_Y, MAX_Y)
        private set

    /** Driving time that has gone into it since it was set, ms. */
    var learnedMs = 0L
        private set

    /** It is where the lines say, no longer where it started. */
    val settled: Boolean get() = learnedMs >= SETTLE_MS

    /** How long the lines met in each slice of the height, fading with [MEMORY_MS], ms. */
    private val seen = FloatArray(BINS)
    private var lastAt = -1L

    init {
        set(this.horizonY)
    }

    /** The driver put it at [y]: it starts again from there. */
    fun set(y: Float) {
        horizonY = y.coerceIn(MIN_Y, MAX_Y)
        learnedMs = 0L
        seen.fill(0f)
        seen[bin(horizonY)] = START_WEIGHT_MS
    }

    /**
     * The horizon with [sighting] (the frame at [now], ms) taken in. [learn]:
     * the car drives on at a steady speed, so the lines are the lane's, not a
     * junction's.
     */
    fun update(sighting: LaneSighting, now: Long, learn: Boolean): Float {
        val dt = if (lastAt < 0) 0L else (now - lastAt).coerceIn(0L, MAX_STEP_MS)
        lastAt = now
        val meet = sighting.meetY ?: return horizonY
        if (!learn || dt == 0L) return horizonY
        val fade = exp(-dt.toFloat() / MEMORY_MS)
        var total = 0f
        for (i in seen.indices) {
            seen[i] *= fade
            total += seen[i]
        }
        seen[bin(meet)] += dt.toFloat()
        total += dt
        learnedMs += dt
        // The middle: half of the time the lines met above it, half below.
        var below = 0f
        for (i in seen.indices) {
            below += seen[i]
            if (below >= total / 2) {
                horizonY = MIN_Y + (i + 0.5f) * (MAX_Y - MIN_Y) / BINS
                break
            }
        }
        return horizonY
    }

    private fun bin(y: Float) = ((y - MIN_Y) / (MAX_Y - MIN_Y) * BINS).roundToInt().coerceIn(0, BINS - 1)

    companion object {
        /** As far as the driver can drag it. */
        const val MIN_Y = 0.25f
        const val MAX_Y = 0.7f
        /** Slices of that range: half a hundredth of the height each. */
        private const val BINS = 90
        /** Lines closing less than this over the band are near parallel: where they meet says little. */
        private const val MIN_CLOSING = 0.15f
        /** How long the lines' meeting points are remembered: a couple of minutes of driving outweighs a minute of ramps. */
        private const val MEMORY_MS = 120_000f
        /** Where it was set counts as this much driving. */
        private const val START_WEIGHT_MS = 3_000f
        private const val SETTLE_MS = 20_000L
        /** A gap between frames counts no longer than this (the camera paused, the phone slept). */
        private const val MAX_STEP_MS = 200L

        /** Where [left] and [right] (lines on [band]) meet, a fraction of the height; null if near parallel or off the picture's middle. */
        fun meetY(left: LaneLine, right: LaneLine, band: RoadBand): Float? {
            // Going up the road, the left line runs right and the right one left: how much faster they close than stay apart.
            val closing = (left.topX - left.bottomX) + (right.bottomX - right.topX)
            if (closing < MIN_CLOSING) return null
            val meet = band.topY + (left.topX - right.topX) / closing * (band.bonnetY - band.topY)
            return meet.takeIf { it in MIN_Y..MAX_Y }
        }
    }
}
