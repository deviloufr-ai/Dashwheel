package com.openauto.dash

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

/*
 * What the reverse view remembers through a manoeuvre: where the car has
 * gone since it began ([ParkingMotion]), what the parking sensors saw on the
 * way ([ObstacleMemory]: a pillar stays on screen once it's beside the car,
 * where the C4 Picasso has no sensor), and the ground the camera filmed
 * ([GroundMemory]: the kerb now beside the rear wheel, pieced together from
 * one camera). A manoeuvre starts with reverse and ends once the car drives
 * off or has been out of reverse a while.
 */

/**
 * Where the car is since the manoeuvre began: [x], [y] in metres (y the way
 * the car faced at the start), [heading] in radians, right positive. The
 * car's own points are in metres right of its middle and ahead of its rear axle.
 */
internal data class Pose(val x: Float = 0f, val y: Float = 0f, val heading: Float = 0f) {
    fun toWorld(cx: Float, cy: Float) = Offset(
        x + cx * cos(heading) + cy * sin(heading),
        y - cx * sin(heading) + cy * cos(heading)
    )

    fun toCar(w: Offset): Offset {
        val dx = w.x - x
        val dy = w.y - y
        return Offset(dx * cos(heading) - dy * sin(heading), dx * sin(heading) + dy * cos(heading))
    }

    /** [ds] metres along the car (negative backwards) with the wheel at [steeringDeg]: the rear axle follows its arc. */
    fun moved(ds: Float, steeringDeg: Float): Pose {
        val delta = Math.toRadians((steeringDeg / CarShape.STEERING_RATIO).toDouble())
        val turn = (ds * tan(delta) / CarShape.WHEELBASE).toFloat()
        val mid = heading + turn / 2f
        return Pose(x + ds * sin(mid), y + ds * cos(mid), heading + turn)
    }
}

internal object ParkingMotion {
    /** Out of reverse this long, the manoeuvre is over. */
    private const val KEEP_MS = 90_000L
    /** Faster than this, the car is driving off: the manoeuvre is over. */
    private const val DRIVING_KMH = 15
    private const val TICK_MS = 100L
    /** The camera's measure is trusted over the speed this long after its last step. */
    private const val CAMERA_FRESH_MS = 700L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false
    private val _pose = MutableStateFlow<Pose?>(null)
    /** The car's pose in the current manoeuvre, null outside one. */
    val pose: StateFlow<Pose?> = _pose.asStateFlow()

    private var lastReverseAt = 0L
    private var cameraAt = 0L

    fun start() {
        if (started) return
        started = true
        scope.launch {
            CarBox.reversing.collect { reversing ->
                if (reversing) {
                    lastReverseAt = SystemClock.elapsedRealtime()
                    if (_pose.value == null) begin()
                }
            }
        }
        // What the sensors see, remembered where the car is.
        scope.launch {
            ReverseView.radar.collect { radar ->
                val p = _pose.value ?: return@collect
                if (radar != null) ObstacleMemory.update(radar, p)
            }
        }
        scope.launch {
            var last = SystemClock.elapsedRealtime()
            while (true) {
                delay(TICK_MS)
                val now = SystemClock.elapsedRealtime()
                val dt = (now - last) / 1000f
                last = now
                if (_pose.value == null || ReverseView.preview.value) continue
                val reversing = CarBox.reversing.value
                val kmh = speedKmh()
                if (reversing) lastReverseAt = now
                else if (now - lastReverseAt > KEEP_MS || (kmh ?: 0f) > DRIVING_KMH) {
                    end()
                    continue
                }
                if (now - cameraAt < CAMERA_FRESH_MS) continue
                val v = (kmh ?: 0f) / 3.6f
                if (v > 0f) move(v * dt * if (reversing) -1f else 1f)
            }
        }
    }

    private fun begin() {
        ObstacleMemory.clear()
        GroundMemory.clear()
        _pose.value = Pose()
    }

    private fun end() {
        _pose.value = null
        ObstacleMemory.clear()
        GroundMemory.clear()
    }

    /** The settings' try-out: a manoeuvre of its own, moved by [previewStep]. */
    fun previewBegin() = begin()

    fun previewStep(ds: Float) = move(ds)

    fun previewEnd() = end()

    /** The camera saw the ground move by [ds] metres (negative backwards). */
    fun cameraStep(ds: Float) {
        cameraAt = SystemClock.elapsedRealtime()
        move(ds)
    }

    private fun move(ds: Float) {
        val p = _pose.value ?: return
        _pose.value = p.moved(ds, ReverseView.steering.value ?: 0f)
    }

    /** The car's speed from the best source there is: the car, the OBD adapter, then GPS. */
    private fun speedKmh(): Float? =
        CarBox.freshBody()?.speedKmh?.toFloat()
            ?: ObdBluetoothManager.data.value.speedKmh.takeIf { ObdBluetoothManager.connectionState.value == ObdConnectionState.CONNECTED }?.toFloat()
            ?: LocationFeed.freshSpeedKmh.value?.toFloat()
}

/** One thing a sensor saw: where in the manoeuvre, and when last. */
internal class Seen(val at: Offset, var seenAt: Long)

internal object ObstacleMemory {
    /** Forgotten after this long without being seen again. */
    private const val FORGET_MS = 120_000L
    /** Closer than this to a remembered point, it's the same thing. */
    private const val SAME_M = 0.15f
    /** A sensor's beam, each side of its axis. */
    private const val HALF_BEAM_DEG = 18f

    private val points = mutableListOf<Seen>()
    private val _version = MutableStateFlow(0)
    /** Bumped at each change, for the screen. */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun clear() {
        points.clear()
        _version.value++
    }

    fun snapshot(): List<Seen> = points.toList()

    /**
     * A sensor level as a distance: the car app's levels run 1 (closest)
     * to 10, about 30 cm to 1.5 m on the C4 Picasso. Rough until the
     * sensors are measured in the car.
     */
    fun metres(level: Int): Float = 0.3f + (level - 1) * 0.13f

    /** Where each sensor sits on the car and where it looks, for [levels] left to right. */
    fun sensors(levels: List<Int?>, rear: Boolean): List<Triple<Offset, Offset, Int?>> {
        val present = levels.indices.filter { levels[it] != null }
        val n = present.size
        val bumperY = if (rear) -CarShape.REAR_OVERHANG else CarShape.LENGTH - CarShape.REAR_OVERHANG
        return present.mapIndexed { k, i ->
            val t = if (n == 1) 0.5f else k / (n - 1f)
            val x = (t - 0.5f) * CarShape.WIDTH * 0.9f
            // The corners curve back towards the car.
            val y = bumperY + (if (rear) 1f else -1f) * 0.12f * abs(2f * t - 1f)
            val a = Math.toRadians(((t - 0.5f) * 100f).toDouble())
            val dir = Offset(sin(a).toFloat(), (if (rear) -1f else 1f) * cos(a).toFloat())
            Triple(Offset(x, y), dir, levels[i])
        }
    }

    /** A new sensor reading at [pose]: what each sensor sees is remembered, what it no longer sees is dropped. */
    fun update(radar: Radar, pose: Pose) {
        val now = SystemClock.elapsedRealtime()
        points.removeAll { now - it.seenAt > FORGET_MS }
        for ((levels, rear) in listOf(radar.rear to true, radar.front to false)) {
            for ((pos, dir, level) in sensors(levels, rear)) {
                val near = level != null && level in 1..Radar.MAX_LEVEL
                val reach = if (near) metres(level!!) - 0.12f else 1.4f
                // In this sensor's beam and closer than what it reads: not there any more.
                points.removeAll { s ->
                    val c = pose.toCar(s.at) - pos
                    val along = c.x * dir.x + c.y * dir.y
                    val across = abs(c.x * dir.y - c.y * dir.x)
                    along > 0f && along < reach && across < along * tan(Math.toRadians(HALF_BEAM_DEG.toDouble())).toFloat()
                }
                if (near) {
                    val d = metres(level!!)
                    val w = pose.toWorld(pos.x + dir.x * d, pos.y + dir.y * d)
                    val same = points.firstOrNull { hypot(it.at.x - w.x, it.at.y - w.y) < SAME_M }
                    if (same != null) same.seenAt = now else points.add(Seen(w, now))
                }
            }
        }
        _version.value++
    }

    /**
     * The narrowest gap on each side of the car to anything remembered beside
     * it (between the bumpers), in metres: (left, right), null when nothing.
     */
    fun sideGaps(pose: Pose): Pair<Float?, Float?> {
        var left: Float? = null
        var right: Float? = null
        val rearY = -CarShape.REAR_OVERHANG
        val frontY = CarShape.LENGTH - CarShape.REAR_OVERHANG
        for (s in points) {
            val c = pose.toCar(s.at)
            if (c.y < rearY || c.y > frontY) continue
            val gap = abs(c.x) - CarShape.WIDTH / 2f
            if (gap < 0f || gap > 1.2f) continue
            if (c.x < 0) left = minOf(left ?: gap, gap) else right = minOf(right ?: gap, gap)
        }
        return left to right
    }

    /** The closest remembered thing behind the rear bumper, in metres. */
    fun behind(pose: Pose): Float? = points.mapNotNull { s ->
        val c = pose.toCar(s.at)
        (-CarShape.REAR_OVERHANG - c.y).takeIf { it > 0f && abs(c.x) < CarShape.WIDTH / 2f + 0.1f }
    }.minOrNull()
}

/**
 * The ground the camera has filmed, from above: each picture's ground is
 * laid onto a map of the manoeuvre (2.5 cm a pixel, 10 m a side), so what the
 * car has rolled over stays visible around it. The same ground, compared
 * between pictures, says how far the car moved: finer than the car's speed
 * at walking pace.
 */
internal object GroundMemory {
    const val RES = 0.025f
    const val SIZE = 400
    /** The ground taken from each picture: across the car's width and a bit, from the bumper out to 2.75 m. */
    private const val COLS = 112
    private const val ROWS = 100
    private const val X0 = -1.4f
    private const val Z0 = 0.25f
    /** How far the camera's ground pattern is searched for, between two pictures (cells). */
    private const val SHIFT_MIN = -3
    private const val SHIFT_MAX = 18

    private val pixels = IntArray(SIZE * SIZE)
    private val bitmap: Bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
    val image: ImageBitmap = bitmap.asImageBitmap()
    /** The world point of the map's top left pixel; x grows right, y up the map. */
    @Volatile var originX = -SIZE * RES / 2f
        private set
    @Volatile var originY = SIZE * RES / 2f
        private set
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()
    private val _filled = MutableStateFlow(false)
    /** Some ground is on the map. */
    val filled: StateFlow<Boolean> = _filled.asStateFlow()

    private var previous: IntArray? = null
    private var lookup: IntArray? = null
    private var lookupKey: Any? = null

    @Synchronized
    fun clear() {
        pixels.fill(0)
        bitmap.eraseColor(0)
        previous = null
        originX = -SIZE * RES / 2f
        originY = SIZE * RES / 2f
        _filled.value = false
        _version.value++
    }

    /**
     * A camera picture ([frame], [fw] x [fh], filling a [viewW] x [viewH]
     * view with the guide lines' calibration [cal]): how far the car moved
     * since the last one, in metres (negative backwards), null when the
     * ground has too little texture to tell, goes to [moved], which answers
     * where the car is now, for the picture to be laid on the map.
     */
    @Synchronized
    fun onFrame(frame: IntArray, fw: Int, fh: Int, viewW: Float, viewH: Float, cal: ReverseCalibration, moved: (Float?) -> Pose?) {
        val map = lookupFor(fw, fh, viewW, viewH, cal)
        val luma = IntArray(COLS * ROWS)
        for (i in luma.indices) {
            val idx = map[i]
            luma[i] = if (idx < 0) -1 else frame[idx].let { ((it shr 16 and 0xFF) * 3 + (it shr 8 and 0xFF) * 6 + (it and 0xFF)) / 10 }
        }
        val step = previous?.let { shiftBetween(it, luma) }
        previous = luma
        val pose = moved(step) ?: return

        // Laid onto the map where the car is now (re-centred when it nears the edge).
        val centre = pose.toWorld(0f, -CarShape.REAR_OVERHANG - 1.5f)
        if (abs(centre.x - (originX + SIZE * RES / 2f)) > 3.5f || abs(centre.y - (originY - SIZE * RES / 2f)) > 3.5f) {
            pixels.fill(0)
            originX = centre.x - SIZE * RES / 2f
            originY = centre.y + SIZE * RES / 2f
        }
        for (r in 0 until ROWS) {
            val z = Z0 + r * RES
            for (c in 0 until COLS) {
                val idx = map[r * COLS + c]
                if (idx < 0) continue
                val w = pose.toWorld(X0 + c * RES, -CarShape.REAR_OVERHANG - z)
                val u = ((w.x - originX) / RES).toInt()
                val v = ((originY - w.y) / RES).toInt()
                if (u in 0 until SIZE && v in 0 until SIZE) pixels[v * SIZE + u] = frame[idx] or (0xFF shl 24)
            }
        }
        bitmap.setPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        _filled.value = true
        _version.value++
    }

    /** Which frame pixel each ground cell comes from, kept while the picture and the calibration stay the same. */
    private fun lookupFor(fw: Int, fh: Int, viewW: Float, viewH: Float, cal: ReverseCalibration): IntArray {
        val key = listOf(fw, fh, viewW, viewH, cal)
        lookup?.let { if (lookupKey == key) return it }
        val g = Ground(viewW, viewH, cal)
        val sx = fw / viewW
        val sy = fh / viewH
        val map = IntArray(COLS * ROWS) { i ->
            val p = g.at(X0 + (i % COLS) * RES, Z0 + (i / COLS) * RES)
            val x = (p.x * sx).toInt()
            val y = (p.y * sy).toInt()
            if (x in 0 until fw && y in 0 until fh) y * fw + x else -1
        }
        lookup = map
        lookupKey = key
        return map
    }

    /**
     * How far the ground moved between two pictures: a point [s] cells out
     * is [s] cells nearer the bumper once the car has backed up [s] cells.
     * The shift (with a little sideways slack for the turning) where both
     * pictures match best, if it clearly beats the others.
     */
    private fun shiftBetween(prev: IntArray, cur: IntArray): Float? {
        // Too even (night, a wet road's glare): nothing to follow.
        var sum = 0L
        var sq = 0L
        var n = 0
        for (v in cur) if (v >= 0) { sum += v; sq += v.toLong() * v; n++ }
        if (n < COLS * ROWS / 3) return null
        val mean = sum / n.toFloat()
        if (sq / n.toFloat() - mean * mean < 60f) return null

        val scores = FloatArray(SHIFT_MAX - SHIFT_MIN + 1) { Float.MAX_VALUE }
        for (s in SHIFT_MIN..SHIFT_MAX) {
            var best = Float.MAX_VALUE
            for (dx in -2..2) {
                var diff = 0L
                var count = 0
                for (r in 0 until ROWS step 2) {
                    val pr = r + s
                    if (pr !in 0 until ROWS) continue
                    for (c in 2 until COLS - 2 step 2) {
                        val a = cur[r * COLS + c]
                        val b = prev[pr * COLS + c + dx]
                        if (a < 0 || b < 0) continue
                        diff += abs(a - b)
                        count++
                    }
                }
                if (count > 200) best = minOf(best, diff / count.toFloat())
            }
            scores[s - SHIFT_MIN] = best
        }
        val i = scores.indices.minByOrNull { scores[it] } ?: return null
        val sorted = scores.filter { it < Float.MAX_VALUE }.sorted()
        if (sorted.size < 4 || scores[i] > sorted[sorted.size / 2] * 0.75f) return null
        // Between cells: the parabola through the best and its neighbours.
        var shift = (i + SHIFT_MIN).toFloat()
        if (i in 1 until scores.size - 1 && scores[i - 1] < Float.MAX_VALUE && scores[i + 1] < Float.MAX_VALUE) {
            val d = scores[i - 1] - 2 * scores[i] + scores[i + 1]
            if (d > 0f) shift += 0.5f * (scores[i - 1] - scores[i + 1]) / d
        }
        // Backing up moves the ground towards the bumper: a positive shift is the car going backwards.
        return -shift * RES
    }
}
