package com.openauto.dash.roadwatch

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.tan

/*
 * How the phone sits in its holder, for the guide that helps the driver place
 * it: level, aimed so the horizon is a little above the middle, not much
 * dashboard in the picture, the windscreen's pillar out of it. The first two
 * come from gravity, so they work parked with no lane in sight; the last two
 * from the picture.
 */

/**
 * [rollDeg]: how far the picture leans, + when the phone's right end is high;
 * [pitchDeg]: how far the camera aims below level; [horizonY]: where the
 * horizon is in the picture for that aim, a fraction of its height.
 */
data class MountReading(val rollDeg: Float, val pitchDeg: Float, val horizonY: Float) {
    val level: Boolean get() = kotlin.math.abs(rollDeg) <= Mount.MAX_ROLL_DEG
    val aimed: Boolean get() = horizonY in Mount.HORIZON_TOP..Mount.HORIZON_BOTTOM
}

object Mount {
    const val MAX_ROLL_DEG = 3f
    /** The horizon belongs between these, a fraction of the picture's height: the road below fills most of it. */
    const val HORIZON_TOP = 0.33f
    const val HORIZON_BOTTOM = 0.5f
    /** At most this much of the picture's height is dashboard. */
    const val MAX_DASHBOARD = 0.22f
    /** At most this much of its width is the windscreen's pillar, on either side. */
    const val MAX_PILLAR = 0.08f
    /** The phone turned this much from where it was placed has moved in its holder. */
    const val MOVED_DEG = 6f

    /**
     * The phone's lean and aim from gravity's sensor [up] (pointing up, in the
     * phone's axes), the screen turned [displayDeg] (0, 90, 180, 270, as
     * Android's display rotation) and the camera's view [vfovDeg] across the
     * picture's height.
     */
    fun read(up: FloatArray, displayDeg: Int, vfovDeg: Float): MountReading {
        val n = sqrt(up[0] * up[0] + up[1] * up[1] + up[2] * up[2]).coerceAtLeast(1e-3f)
        val x = up[0] / n
        val y = up[1] / n
        val z = up[2] / n
        // The screen's up and right in the phone's axes, as the display is turned.
        val (screenUp, screenRight) = when (((displayDeg % 360) + 360) % 360) {
            90 -> x to -y
            180 -> -y to -x
            270 -> -x to y
            else -> y to x
        }
        // Gravity leaning to the screen's right: the phone turned anticlockwise, its right end high.
        val roll = Math.toDegrees(atan2(screenRight, screenUp).toDouble()).toFloat()
        // The back camera looks along -z: "up" along +z means it aims below level.
        val pitch = Math.toDegrees(asin(z.coerceIn(-1f, 1f).toDouble())).toFloat()
        val half = tan(Math.toRadians(vfovDeg / 2.0)).toFloat()
        val horizonY = 0.5f - tan(Math.toRadians(pitch.toDouble())).toFloat() / (2 * half)
        return MountReading(roll, pitch, horizonY)
    }

    /** The angle between two of gravity's readings, degrees: how far the phone turned in its holder. */
    fun turnedDeg(a: FloatArray, b: FloatArray): Float {
        val dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        val na = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
        val nb = sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2])
        if (na <= 0f || nb <= 0f) return 0f
        return Math.toDegrees(kotlin.math.acos((dot / (na * nb)).coerceIn(-1f, 1f).toDouble())).toFloat()
    }

    /**
     * Where the dashboard starts in [img], a fraction of its height (1: none
     * in the picture), from the dark band along its bottom; null when the
     * picture is too dark to tell (night, a garage).
     */
    fun dashboardTop(img: Luma, horizonY: Float): Float? {
        val rows = FloatArray(img.h) { y -> var s = 0L; for (x in 0 until img.w) s += img[x, y]; s.toFloat() / img.w }
        val from = ((horizonY + 0.05f) * img.h).toInt().coerceIn(0, img.h - 1)
        val to = ((horizonY + 0.2f) * img.h).toInt().coerceIn(from + 1, img.h)
        val road = rows.copyOfRange(from, to).sorted()[(to - from) / 2]
        if (road < MIN_LIGHT) return null
        var top = img.h
        while (top > from && rows[top - 1] < DARK * road) top--
        return top.toFloat() / img.h
    }

    /**
     * How much of [img]'s width the windscreen's pillar covers, the larger of
     * either side: the dark columns running in from an edge, above the road;
     * null when the picture is too dark to tell.
     */
    fun pillar(img: Luma, horizonY: Float): Float? {
        val y0 = (0.15f * img.h).toInt()
        val y1 = ((horizonY + 0.1f) * img.h).toInt().coerceIn(y0 + 1, img.h)
        val cols = FloatArray(img.w) { x -> var s = 0L; for (y in y0 until y1) s += img[x, y]; s.toFloat() / (y1 - y0) }
        val middle = cols.copyOfRange((0.35f * img.w).toInt(), (0.65f * img.w).toInt()).sorted()
        val scene = middle[middle.size / 2]
        if (scene < MIN_LIGHT) return null
        var left = 0
        while (left < img.w / 2 && cols[left] < DARK * scene) left++
        var right = 0
        while (right < img.w / 2 && cols[img.w - 1 - right] < DARK * scene) right++
        return max(left, right).toFloat() / img.w
    }

    /** Darker than this share of the road (or the scene) is the dashboard (or the pillar). */
    private const val DARK = 0.55f
    /** A picture darker than this tells nothing. */
    private const val MIN_LIGHT = 40f
}
