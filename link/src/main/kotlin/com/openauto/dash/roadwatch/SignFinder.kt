package com.openauto.dash.roadwatch

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * Road watch's eyes for speed-limit signs: round, a red ring round a light
 * middle that holds the number. This finds the rings with plain image maths;
 * the phone reads the number in the middle (SignReader, ML Kit) and
 * [SignConfirm] believes it once read twice. Red brick, tail lights and red
 * cars are red too, but none is a thin round ring round something light.
 */

/** A round sign with a red ring: [box] in the upright frame's fractions, [sidePx] across in its pixels. */
data class SignSpot(val box: Box, val sidePx: Int)

object SignFinder {
    /** Narrower than this (pixels of the frame) a sign's number can't be read. */
    const val MIN_SIDE_PX = 16
    /** Every this many pixels: the ring is wider than that. */
    private const val STEP = 2
    /** A ring's red covers this share of its square: more is a red patch, less a scatter. */
    private const val MIN_FILL = 0.2f
    private const val MAX_FILL = 0.65f
    /** Its middle is this much light and at most this much red. */
    private const val MIN_LIGHT_MIDDLE = 0.3f
    private const val MAX_RED_MIDDLE = 0.25f

    /**
     * The round red signs in the RGBA frame [rgba] (see [Upright.index] for
     * the other arguments), above [bottomY] of the upright frame's height
     * (below it is the bonnet).
     */
    fun find(rgba: ByteBuffer, width: Int, height: Int, rowStride: Int, rotation: Int, bottomY: Float = 1f): List<SignSpot> {
        val uw = Upright.width(width, height, rotation)
        val uh = Upright.height(width, height, rotation)
        val gw = uw / STEP
        val gh = (uh * bottomY.coerceIn(0f, 1f)).toInt() / STEP
        if (gw < 8 || gh < 8) return emptyList()
        val red = BooleanArray(gw * gh)
        val light = BooleanArray(gw * gh)
        for (gy in 0 until gh) for (gx in 0 until gw) {
            val i = Upright.index(gx * STEP, gy * STEP, width, height, rowStride, rotation)
            val r = rgba.get(i).toInt() and 0xFF
            val g = rgba.get(i + 1).toInt() and 0xFF
            val b = rgba.get(i + 2).toInt() and 0xFF
            red[gy * gw + gx] = isRed(r, g, b)
            light[gy * gw + gx] = isLight(r, g, b)
        }
        val seen = BooleanArray(gw * gh)
        val stack = IntArray(gw * gh)
        val out = ArrayList<SignSpot>()
        val minSide = MIN_SIDE_PX / STEP
        for (start in red.indices) {
            if (!red[start] || seen[start]) continue
            // The red pixels touching this one, and the square round them.
            var top = 0
            stack[top++] = start
            seen[start] = true
            var count = 0
            var x0 = gw; var y0 = gh; var x1 = -1; var y1 = -1
            while (top > 0) {
                val p = stack[--top]
                count++
                val x = p % gw
                val y = p / gw
                x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
                if (x > 0 && red[p - 1] && !seen[p - 1]) { seen[p - 1] = true; stack[top++] = p - 1 }
                if (x < gw - 1 && red[p + 1] && !seen[p + 1]) { seen[p + 1] = true; stack[top++] = p + 1 }
                if (y > 0 && red[p - gw] && !seen[p - gw]) { seen[p - gw] = true; stack[top++] = p - gw }
                if (y < gh - 1 && red[p + gw] && !seen[p + gw]) { seen[p + gw] = true; stack[top++] = p + gw }
            }
            val bw = x1 - x0 + 1
            val bh = y1 - y0 + 1
            if (bw < minSide || bh < minSide || bh > gh / 2 || bw > gw / 3) continue
            if (bw < 0.7f * bh || bw > 1.4f * bh) continue
            val fill = count.toFloat() / (bw * bh)
            if (fill < MIN_FILL || fill > MAX_FILL) continue
            // The middle third: light (the sign's face), not red (a red disc or a brick wall).
            var n = 0; var reds = 0; var lights = 0
            for (y in y0 + bh / 3..y1 - bh / 3) for (x in x0 + bw / 3..x1 - bw / 3) {
                n++
                if (red[y * gw + x]) reds++
                if (light[y * gw + x]) lights++
            }
            if (n == 0 || reds > MAX_RED_MIDDLE * n || lights < MIN_LIGHT_MIDDLE * n) continue
            out += SignSpot(
                Box(x0 * STEP / uw.toFloat(), y0 * STEP / uh.toFloat(), (x1 + 1) * STEP / uw.toFloat(), (y1 + 1) * STEP / uh.toFloat()),
                max(bw, bh) * STEP
            )
        }
        return out
    }

    /** A sign's red: red the strongest, well coloured, the hue within about 20° of red. */
    internal fun isRed(r: Int, g: Int, b: Int): Boolean {
        val lo = min(g, b)
        val range = r - lo
        return r > 60 && r >= g && r >= b && range > 0.35f * r && abs(g - b) * 3 < range
    }

    /** A sign's face: light and hardly coloured, white in the sun, grey in the shade. */
    internal fun isLight(r: Int, g: Int, b: Int): Boolean {
        val hi = max(r, max(g, b))
        val lo = min(r, min(g, b))
        return lo > 95 && hi - lo < 0.3f * hi
    }
}

/**
 * The speed limit read off the signs, believed once the same number was read
 * [READS] times within [WINDOW_MS]: one frame's misreading ("80" for "30" in a
 * blur) doesn't change it.
 */
class SignConfirm {
    private class Read(val kmh: Int, val at: Long)
    private val reads = ArrayDeque<Read>()

    /** The limit [kmh] read at [now] (ms): the limit now believed, or null while it isn't yet. */
    fun read(kmh: Int, now: Long): Int? {
        reads.addLast(Read(kmh, now))
        while (reads.isNotEmpty() && now - reads.first().at > WINDOW_MS) reads.removeFirst()
        return kmh.takeIf { reads.count { it.kmh == kmh } >= READS }
    }

    companion object {
        const val READS = 2
        const val WINDOW_MS = 3_000L
        /** The limits a sign can show; anything else read is a misreading. */
        val LIMITS = setOf(10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120, 130)

        /** The limit in the text [read] off a sign's middle, or null when it isn't one. */
        fun limitIn(read: String): Int? {
            // Digits read as the letters they look like: O for 0 is the usual one (not S for 5: STOP would read 50).
            val digits = read.map {
                when (it) {
                    'O', 'o', 'D', 'Q' -> '0'
                    'I', 'l', '|' -> '1'
                    else -> it
                }
            }.filter { it.isDigit() }.joinToString("")
            digits.toIntOrNull()?.takeIf { it in LIMITS }?.let { return it }
            // Read as if upside down: a 90 comes back "06" (seen on the road, 2026-10-26). Turned round, 6 and 9 swap.
            if (digits.isEmpty() || digits.any { it !in "01689" }) return null
            val turned = digits.reversed().map { if (it == '6') '9' else if (it == '9') '6' else it }.joinToString("")
            return turned.toIntOrNull()?.takeIf { it in LIMITS }
        }
    }
}
