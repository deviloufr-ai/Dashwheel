package com.openauto.dash.roadwatch

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Road watch's eyes for the lane: the two lines of the car's own lane in a
 * frame from the phone's camera, found with plain image maths (no model, no
 * Android), so it keeps up with the camera on any phone and is tested on the
 * JVM with drawn roads.
 *
 * A lane marking is a bright stripe on a darker road, as wide as perspective
 * makes it at its row: each row of the road below the horizon gives the
 * points brighter than the road either side of them, and the straight lines
 * that most of those points sit on are the markings (a Hough vote over where
 * a line crosses the top and the bottom of the road). The car's lane is the
 * nearest line on each side of where the car is.
 */

/** A grey picture, one 0..255 value per pixel, row by row. */
class Luma(val w: Int, val h: Int, val px: IntArray = IntArray(w * h)) {
    init { require(px.size == w * h) { "size" } }

    operator fun get(x: Int, y: Int): Int = px[y * w + x]

    companion object {
        /**
         * The camera's RGBA frame ([rgba]: 4 bytes a pixel, rows [rowStride]
         * bytes apart) stood upright, [rotation] being the degrees it must turn
         * clockwise for that, and shrunk to [outW] wide. Each pixel is the mean
         * of a few of the frame's under it, so a thin far marking is not missed.
         */
        fun fromRgba(rgba: ByteBuffer, width: Int, height: Int, rowStride: Int, rotation: Int, outW: Int): Luma {
            val uprightW = Upright.width(width, height, rotation)
            val uprightH = Upright.height(width, height, rotation)
            val w = outW.coerceIn(1, uprightW)
            val h = max(1, (w.toLong() * uprightH / uprightW).toInt())
            val out = Luma(w, h)
            val blockW = uprightW.toFloat() / w
            val blockH = uprightH.toFloat() / h
            for (oy in 0 until h) {
                for (ox in 0 until w) {
                    var sum = 0
                    var n = 0
                    for (sy in 0 until SUBSAMPLES) {
                        val v = min(uprightH - 1, ((oy + (sy + 0.5f) / SUBSAMPLES) * blockH).toInt())
                        for (sx in 0 until SUBSAMPLES) {
                            val u = min(uprightW - 1, ((ox + (sx + 0.5f) / SUBSAMPLES) * blockW).toInt())
                            val i = Upright.index(u, v, width, height, rowStride, rotation)
                            val r = rgba.get(i).toInt() and 0xFF
                            val g = rgba.get(i + 1).toInt() and 0xFF
                            val b = rgba.get(i + 2).toInt() and 0xFF
                            sum += (77 * r + 150 * g + 29 * b) shr 8
                            n++
                        }
                    }
                    out.px[oy * w + ox] = sum / n
                }
            }
            return out
        }

        /** Points sampled across and down each output pixel. */
        private const val SUBSAMPLES = 3
    }
}

/** The camera's frames as they stand upright, whichever way the phone holds its sensor. */
object Upright {
    /** The upright frame's width for a [width] x [height] frame turned by [rotation]. */
    fun width(width: Int, height: Int, rotation: Int): Int = if (rotation == 90 || rotation == 270) height else width

    fun height(width: Int, height: Int, rotation: Int): Int = if (rotation == 90 || rotation == 270) width else height

    /**
     * Where in an RGBA frame ([width] x [height], rows [rowStride] bytes
     * apart) the upright frame's pixel ([u], [v]) is, as a byte index;
     * [rotation]: the degrees the frame turns clockwise to stand upright.
     */
    fun index(u: Int, v: Int, width: Int, height: Int, rowStride: Int, rotation: Int): Int {
        val x: Int
        val y: Int
        when (rotation) {
            90 -> { x = v; y = height - 1 - u }
            180 -> { x = width - 1 - u; y = height - 1 - v }
            270 -> { x = width - 1 - v; y = u }
            else -> { x = u; y = v }
        }
        return y * rowStride + x * 4
    }
}

/**
 * Where the road is in the frame, in fractions of its height: the horizon,
 * and the bottom of the road seen above the bonnet. The driver sets the
 * horizon on the phone once the phone is in its holder.
 */
data class RoadBand(val horizonY: Float = 0.5f, val bonnetY: Float = 0.92f) {
    /** The top of the road searched: a little under the horizon, where the lines have not yet run together. */
    val topY: Float get() = min(bonnetY - 0.1f, horizonY + TOP_GAP)

    /** Where row [y] (a fraction of the height) is between [topY] (0) and [bonnetY] (1). */
    fun s(y: Float): Float = (y - topY) / (bonnetY - topY)

    /** How far row [y] is below the horizon, the bonnet's row being 1: a lane is that much of its width at the bonnet. */
    fun depth(y: Float): Float = ((y - horizonY) / (bonnetY - horizonY)).coerceAtLeast(0f)

    companion object {
        const val TOP_GAP = 0.04f
    }
}

/**
 * One lane line, where it crosses the top ([topX]) and the bottom ([bottomX])
 * of the [RoadBand], in fractions of the frame's width.
 */
data class LaneLine(val bottomX: Float, val topX: Float) {
    /** Where it is at [s]: 0 the top of the band, 1 its bottom (more is below it, extended). */
    fun xAt(s: Float): Float = topX + (bottomX - topX) * s
}

/** What one frame shows of the car's lane: its line on either side, and how many points of the road back each. */
data class LaneSighting(
    val left: LaneLine? = null,
    val right: LaneLine? = null,
    /** The share of the road's rows on the line, 0..1: a solid line near 1, a dashed one less. */
    val leftSupport: Float = 0f,
    val rightSupport: Float = 0f
)

object LaneFinder {
    /** A marking at the bonnet's row, as a share of the frame's width (15 cm of a lane that fills most of it). */
    private const val MARK_WIDTH = 0.035f
    /** Rows nearer the top than this take no part in the vote: there a point fits any line. */
    private const val MIN_S = 0.1f
    /** A row with more bright stripes than this is gravel, leaves or writing, not lane lines. */
    private const val MAX_POINTS_PER_ROW = 10
    /** A line needs points on this share of the road's rows. */
    private const val MIN_SUPPORT = 0.18f

    /**
     * The car's lane in [img]. [carX]: where the car's middle is, as a
     * fraction of the width (the phone is seldom in the middle of the windscreen).
     */
    fun find(img: Luma, band: RoadBand = RoadBand(), carX: Float = 0.5f): LaneSighting {
        val w = img.w
        val h = img.h
        val y0 = (band.topY * h).roundToInt().coerceIn(0, h - 2)
        val y1 = (band.bonnetY * h).roundToInt().coerceIn(y0 + 1, h - 1)
        val rows = y1 - y0
        if (rows < 8 || w < 32) return LaneSighting()
        val horizon = band.horizonY * h

        // How much brighter than the road a marking must be: lit by headlights at night, it still stands out.
        var bandSum = 0L
        for (y in y0..y1) for (x in 0 until w) bandSum += img[x, y]
        val threshold = max(8, (bandSum / ((rows + 1L) * w) * 0.18).toInt())

        val topStep = 2f
        val topMin = 0.1f * w
        val nTop = ((0.8f * w) / topStep).toInt() + 1
        val botStep = 3f
        val botMin = -0.8f * w
        val nBot = ((2.6f * w) / botStep).toInt() + 1
        val votes = IntArray(nTop * nBot)
        val response = IntArray(w)

        for (y in y0..y1) {
            val s = (y - y0).toFloat() / rows
            if (s < MIN_S) continue
            val depth = ((y - horizon) / (y1 - horizon)).coerceAtLeast(0f)
            val mark = max(1, (MARK_WIDTH * w * depth).roundToInt())
            val d = mark + 1
            response.fill(0)
            for (x in d until w - d) {
                val c = img[x, y]
                val a = img[x - d, y]
                val b = img[x + d, y]
                if (c - a >= threshold && c - b >= threshold) response[x] = 2 * c - a - b
            }
            // One point per stripe: its brightest pixel.
            var found = 0
            var x = d
            while (x < w - d && found <= MAX_POINTS_PER_ROW) {
                val r = response[x]
                if (r > 0) {
                    var best = true
                    for (k in max(0, x - mark)..min(w - 1, x + mark)) {
                        if (response[k] > r || response[k] == r && k < x) { best = false; break }
                    }
                    if (best) {
                        found++
                        vote(votes, x + 0.5f, s, topMin, topStep, nTop, botMin, botStep, nBot)
                        x += mark
                    }
                }
                x++
            }
        }

        val minVotes = max(5, (rows * MIN_SUPPORT).toInt())
        val peaks = peaks(votes, nTop, nBot, minVotes)
        val car = carX * w
        fun line(p: Peak) = LaneLine((botMin + p.j * botStep) / w, (topMin + p.i * topStep) / w)
        // A lane's line leans in towards the horizon; of the strong ones on a side, the nearest to the car.
        fun nearest(candidates: List<Peak>, distance: (Peak) -> Float): Peak? {
            val strongest = candidates.maxOfOrNull { it.votes } ?: return null
            return candidates.filter { it.votes * 2 >= strongest }.minByOrNull(distance)
        }
        val left = nearest(peaks.filter { p ->
            val l = line(p)
            l.bottomX * w < car && l.topX >= l.bottomX
        }) { car - line(it).bottomX * w }
        val right = nearest(peaks.filter { p ->
            val l = line(p)
            l.bottomX * w > car && l.topX <= l.bottomX
        }) { line(it).bottomX * w - car }

        var l = left?.let(::line)
        var r = right?.let(::line)
        // Two lines that cross before the horizon are not one lane: keep the better backed.
        if (l != null && r != null && (r.topX < l.topX - 0.06f || r.bottomX - l.bottomX < 0.25f)) {
            if (left!!.votes >= right!!.votes) r = null else l = null
        }
        return LaneSighting(
            left = l,
            right = r,
            leftSupport = if (l != null) min(1f, left!!.votes.toFloat() / rows) else 0f,
            rightSupport = if (r != null) min(1f, right!!.votes.toFloat() / rows) else 0f
        )
    }

    private fun vote(votes: IntArray, x: Float, s: Float, topMin: Float, topStep: Float, nTop: Int, botMin: Float, botStep: Float, nBot: Int) {
        for (i in 0 until nTop) {
            val top = topMin + i * topStep
            val bottom = top + (x - top) / s
            val j = ((bottom - botMin) / botStep).roundToInt()
            if (j in 0 until nBot) votes[i * nBot + j]++
        }
    }

    private class Peak(val i: Int, val j: Int, val votes: Int)

    /** The cells that beat every other within two of them, from [min] votes up. */
    private fun peaks(votes: IntArray, nTop: Int, nBot: Int, min: Int): List<Peak> {
        val out = ArrayList<Peak>()
        for (i in 0 until nTop) {
            for (j in 0 until nBot) {
                val v = votes[i * nBot + j]
                if (v < min) continue
                var top = true
                loop@ for (di in -2..2) for (dj in -2..2) {
                    if (di == 0 && dj == 0) continue
                    val ii = i + di
                    val jj = j + dj
                    if (ii !in 0 until nTop || jj !in 0 until nBot) continue
                    val o = votes[ii * nBot + jj]
                    // Ties go to the first cell, so a flat top gives one peak.
                    if (o > v || o == v && (di < 0 || di == 0 && dj < 0)) { top = false; break@loop }
                }
                if (top) out += Peak(i, j, v)
            }
        }
        return out
    }
}

/** The car's lane as it is followed from frame to frame. */
data class LaneState(
    val left: LaneLine? = null,
    val right: LaneLine? = null,
    /**
     * Where the car is in its lane: 0 its usual place, about ±0.25 a wheel on
     * the line, positive to the right. Null while the lane isn't known.
     */
    val offset: Float? = null
) {
    /** The lines followed now, 0 to 2. */
    val lines: Int get() = (if (left != null) 1 else 0) + (if (right != null) 1 else 0)
}

/**
 * The lane over time: a line must be seen in a few frames running before it
 * counts, a dash missing for a moment doesn't lose it, and a line that jumps
 * aside (a lane change, a tar seam) is believed only once it stays there. It
 * also learns where the car usually drives in its lane ([carX]), so a phone
 * held off the middle of the windscreen still reads 0 there.
 */
class LaneTracker(carX: Float = 0.5f) {
    /** Where the car's middle is in the frame, a fraction of its width; learned while driving. */
    var carX: Float = carX.coerceIn(MIN_CAR_X, MAX_CAR_X)
        private set

    /** The lane's width at the bonnet, a fraction of the frame's; 0 until both lines were seen. */
    var laneWidth = 0f
        private set

    private class Side {
        var line: LaneLine? = null
        var hits = 0
        var seenAt = 0L
        var pending: LaneLine? = null
        var pendingHits = 0

        fun update(seen: LaneLine?, now: Long) {
            val current = line
            when {
                seen == null -> if (current != null && now - seenAt > COAST_MS) {
                    line = null
                    hits = 0
                }
                current == null || abs(seen.bottomX - current.bottomX) < JUMP && abs(seen.topX - current.topX) < JUMP -> {
                    line = if (current == null) seen else LaneLine(
                        current.bottomX + SMOOTH * (seen.bottomX - current.bottomX),
                        current.topX + SMOOTH * (seen.topX - current.topX)
                    )
                    if (current == null) hits = 0
                    hits++
                    seenAt = now
                    pending = null
                    pendingHits = 0
                }
                else -> {
                    val p = pending
                    if (p != null && abs(seen.bottomX - p.bottomX) < JUMP) pendingHits++ else pendingHits = 1
                    pending = seen
                    if (pendingHits >= CONFIRM) {
                        line = seen
                        hits = CONFIRM
                        seenAt = now
                        pending = null
                        pendingHits = 0
                    }
                }
            }
        }

        val confirmed: LaneLine? get() = line?.takeIf { hits >= CONFIRM }
    }

    private var left = Side()
    private var right = Side()

    /**
     * The lane with [sighting] (the frame at [now], ms) taken in. [learn]:
     * the car drives on at a steady speed, so where it is now is where it
     * usually is, a little.
     */
    fun update(sighting: LaneSighting, now: Long, learn: Boolean = false): LaneState {
        left.update(sighting.left, now)
        right.update(sighting.right, now)
        val l = left.confirmed
        val r = right.confirmed
        if (l != null && r != null) {
            val width = r.bottomX - l.bottomX
            laneWidth = if (laneWidth == 0f) width else laneWidth + WIDTH_SMOOTH * (width - laneWidth)
        }
        val centre = when {
            l != null && r != null -> (l.bottomX + r.bottomX) / 2
            l != null && laneWidth > 0f -> l.bottomX + laneWidth / 2
            r != null && laneWidth > 0f -> r.bottomX - laneWidth / 2
            else -> null
        }
        if (learn && l != null && r != null && centre != null) {
            carX = (carX + LEARN * (centre - carX)).coerceIn(MIN_CAR_X, MAX_CAR_X)
        }
        val offset = if (centre != null && laneWidth > 0f) (carX - centre) / laneWidth else null
        return LaneState(l, r, offset)
    }

    /** The lines forgotten (the camera was stopped); where the car drives in its lane stays. */
    fun reset() {
        left = Side()
        right = Side()
    }

    private companion object {
        /** A line unseen this long is gone (a dashed line's gap at town speed is shorter). */
        const val COAST_MS = 700L
        /** Further than this from one frame to the next, a line jumped: a share of the width. */
        const val JUMP = 0.08f
        /** Frames running a new line must be seen in. */
        const val CONFIRM = 3
        const val SMOOTH = 0.5f
        const val WIDTH_SMOOTH = 0.05f
        /** About a minute of steady driving to move the car's place all the way. */
        const val LEARN = 0.003f
        const val MIN_CAR_X = 0.3f
        const val MAX_CAR_X = 0.7f
    }
}
