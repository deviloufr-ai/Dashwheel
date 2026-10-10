package com.openauto.dash.display

import com.openauto.dash.link.ClusterState
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The display's own map seen from behind the car: turned with it, tilted,
 * the camera above and behind the arrow, as a navigation app shows it.
 *
 * Plain Java2D, no OpenGL. The ground is painted row by row straight from
 * the raster tiles: a screen row is a line across the road at one distance,
 * read from the tiles of the zoom whose pixels are about the row's own size
 * (sharp near the car, lighter far away). The route lies on the road as a
 * ribbon, the buildings (from vector tiles, [BuildingSource]) stand up as
 * walls and roofs drawn far to near, and the car is a raised chevron.
 *
 * Between two GPS fixes (about one a second) the car goes on at its speed
 * and the camera eases after it, so the picture moves smoothly when the
 * screen draws more often than the fixes come ([moving]).
 */
internal class ChaseMap {
    /** The car moves or the camera is still catching up: another picture soon would show it. */
    var moving = false
        private set

    /** Where the camera looks, degrees from north: for the compass. */
    val headingDeg: Double get() = Math.toDegrees(camHeading)

    // The last fix and how the car was going then.
    private var fixLat = Double.NaN
    private var fixLon = Double.NaN
    private var fixHeadingDeg: Int? = null
    private var fixAtNs = 0L
    private var fixX = 0.0
    private var fixY = 0.0
    private var fixSpeed = 0.0
    /** The latitude of the place the car is drawn at (a stopped car's wandering fixes left out). */
    private var placeLat = 0.0
    /** Metres the car went on from the fix since it came. */
    private var fixAhead = 0.0
    private var goHeading = 0.0

    // What was drawn last: the car's place (world pixels at [Z0]) and the camera's and car's headings.
    private var shownX = Double.NaN
    private var shownY = Double.NaN
    private var camHeading = 0.0
    private var carHeading = 0.0
    private var paintedAtNs = 0L

    // What the pictures cost, by part (ground, route and haze, buildings, car, onto the page), and how far apart the fixes come.
    private val spentNs = LongArray(6)
    private var pictures = 0
    private var fixes = 0
    private var fixGapsNs = 0L

    /** Time spent on what is drawn over the map (the turn card, the compass), for [takeStats]. */
    fun overlaysTook(ns: Long) { overlaysNs += ns }
    private var overlaysNs = 0L

    /** What the pictures since the last call cost, by part, and how often fixes came; null when none was drawn. */
    fun takeStats(): String? {
        if (pictures == 0) return null
        val ms = spentNs.map { it / pictures / 1_000_000 }
        val over = overlaysNs / pictures / 1_000_000
        overlaysNs = 0
        val spread = spreadNs / pictures / 1_000_000
        spreadNs = 0
        val looked = "${lookups / pictures} tile lookups ${lookupNs / pictures / 1_000_000} ms"
        lookups = 0
        lookupNs = 0
        val gaps = if (fixes > 0) " · a fix every ${"%.1f".format(fixGapsNs / fixes / 1e9)} s" else ""
        spentNs.fill(0)
        pictures = 0
        fixes = 0
        fixGapsNs = 0
        return "ground ${ms[0]} (spread $spread, $looked), route ${ms[1]}, haze ${ms[5]}, buildings ${ms[2]}, car ${ms[3]}, onto the page ${ms[4]}, over it $over ms$gaps"
    }

    private var view: BufferedImage? = null
    private var routeSource: List<Double>? = null
    private var routeWorld = DoubleArray(0)
    private val pixelCache = object : LinkedHashMap<BufferedImage, IntArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<BufferedImage, IntArray>?) = size > 80
    }

    /** The view's camera for one picture, all in metres and box pixels. */
    private class Camera(
        val w: Int, val h: Int,
        /** Focal length, the horizon's row and the camera's height. */
        val f: Double, val horizon: Double, val height: Double,
        /** How far behind the car the camera is, and the farthest ground drawn. */
        val back: Double, val far: Double,
        /** Where it is (world pixels at Z0) and where it looks. */
        val x: Double, val y: Double, val heading: Double,
        /** Metres per world pixel at Z0 here. */
        val mpp: Double
    ) {
        val cosH = cos(heading)
        val sinH = sin(heading)
        /** The nearest ground drawn, at the bottom row; nothing nearer is drawn. */
        val near = height * f / (h - horizon)
        val clip = max(1.0, near * 0.8)

        /** World pixels at Z0 to the camera's ground: metres to the right, metres ahead. */
        fun lateral(wx: Double, wy: Double) = ((wx - x) * cosH + (wy - y) * sinH) * mpp
        fun ahead(wx: Double, wy: Double) = ((wx - x) * sinH - (wy - y) * cosH) * mpp
        fun sx(lat: Double, fwd: Double) = w / 2.0 + lat * f / fwd
        fun sy(fwd: Double, up: Double) = horizon + (height - up) * f / fwd
    }

    fun paint(
        g: Graphics2D, box: Rectangle, s: ClusterState, pos: ClusterState.Position, look: ClusterState.MapView,
        route: List<Double>, tiles: TileSource?, dimTiles: Boolean, buildings: BuildingSource?,
        accent: Color, night: Boolean, minZoom: Int, maxZoom: Int, nowNs: Long
    ) {
        val w = box.width
        val h = box.height
        if (w < 8 || h < 8) return
        follow(pos, s.speedKmh, nowNs)

        val (top, carRow) = when (look.tilt) {
            ClusterState.MapView.LOW -> 0.05 to 0.70
            ClusterState.MapView.HIGH -> 0.26 to 0.81
            else -> 0.15 to 0.78
        }
        // How much road the camera takes in across the screen, at the car.
        val across = when (look.distance) {
            ClusterState.MapView.NORMAL -> 190.0
            ClusterState.MapView.FAR -> 330.0
            else -> 110.0
        }
        val f = h * 0.95
        val horizon = h * top
        val back = across * f / w
        val height = (h * carRow - horizon) * back / f
        val mpp = WebMercator.metersPerPixel(placeLat, Z0)
        val cam = Camera(
            w, h, f, horizon, height, back, back * 12,
            shownX - sin(camHeading) * back / mpp, shownY + cos(camHeading) * back / mpp, camHeading, mpp
        )

        val image = view?.takeIf { it.width == w && it.height == h } ?: BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).also { view = it }
        val v = image.createGraphics()
        try {
            val sky = if (night) SKY_NIGHT else SKY_DAY
            val fog = if (night) FOG_NIGHT else FOG_DAY
            val farRow = cam.horizon + cam.height * cam.f / cam.far
            val px = (image.raster.dataBuffer as DataBufferInt).data
            // The sky, row by row straight into the pixels (a gradient paint costs a Pi 3B several milliseconds).
            val skyRows = min(h, ceil(farRow).toInt() + 1)
            for (y in 0 until skyRows) java.util.Arrays.fill(px, y * w, y * w + w, mix(sky, fog, y / max(1f, farRow.toFloat())).rgb and 0xFFFFFF)
            var t = System.nanoTime()
            fun lap(part: Int) { val now = System.nanoTime(); spentNs[part] += now - t; t = now }
            ground(image, cam, placeLat, tiles, minZoom, maxZoom, night)
            if (dimTiles && tiles != null) {
                v.color = Color(0, 0, 0, 120)
                v.fillRect(0, farRow.toInt(), w, h - farRow.toInt())
            }
            lap(0)
            // The route under the haze, so it fades away with the road.
            v.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            routeRibbon(v, cam, route, across, accent, night)
            lap(1)
            // Haze over the far edge, thinning toward the car.
            haze(px, w, h, farRow, h * 0.24, fog.rgb and 0xFFFFFF)
            lap(5)

            v.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
            if (buildings != null) buildings(v, cam, buildings, night, fog)
            lap(2)
            v.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            car(v, cam, accent, carHeading - camHeading, pos.headingDeg != null || fixHeadingDeg != null)
            lap(3)
        } finally {
            v.dispose()
        }
        val onto = System.nanoTime()
        g.drawImage(image, box.x, box.y, null)
        spentNs[4] += System.nanoTime() - onto
        pictures++
        prefetch(tiles, cam, placeLat, minZoom, maxZoom)
    }

    // --- Following the car between fixes -----------------------------------------------------------

    private fun follow(pos: ClusterState.Position, speedKmh: Int?, nowNs: Long) {
        val x = WebMercator.x(pos.lon, Z0)
        val y = WebMercator.y(pos.lat, Z0)
        val mpp = WebMercator.metersPerPixel(pos.lat, Z0)
        if (pos.lat != fixLat || pos.lon != fixLon || pos.headingDeg != fixHeadingDeg) {
            // A new fix: its speed is the car's when the head unit sends one, else what the last two fixes say.
            val seconds = (nowNs - fixAtNs) / 1e9
            if (!fixLat.isNaN() && seconds < 10) { fixes++; fixGapsNs += nowNs - fixAtNs }
            val moved = if (fixLat.isNaN()) 0.0 else hypot(x - fixX, y - fixY) * mpp
            fixSpeed = speedKmh?.let { it / 3.6 } ?: if (seconds in 0.2..5.0) moved / seconds else 0.0
            fixLat = pos.lat
            fixLon = pos.lon
            fixHeadingDeg = pos.headingDeg
            // A stopped car's GPS wanders a metre or two: the car stays put (and the Pi idle).
            val wander = speedKmh == 0 && moved < WANDER_M && !shownX.isNaN()
            if (!wander) {
                placeLat = pos.lat
                fixAtNs = nowNs
                fixX = x
                fixY = y
                fixAhead = 0.0
                pos.headingDeg?.let { goHeading = Math.toRadians(it.toDouble()) }
            }
        } else if (speedKmh != null) {
            fixSpeed = speedKmh / 3.6
        }
        val speed = if (fixSpeed < 0.5) 0.0 else fixSpeed
        // Where the car should be now: on from the fix at its speed, for a second and a half at most;
        // added up as it goes, so a car that slows down before the next fix stays where it got to.
        val since = ((nowNs - fixAtNs) / 1e9).coerceIn(0.0, MAX_AHEAD_S)
        val sinceLast = if (paintedAtNs == 0L) since else ((paintedAtNs - fixAtNs) / 1e9).coerceIn(0.0, MAX_AHEAD_S)
        fixAhead += speed * max(0.0, since - sinceLast)
        val ahead = fixAhead / mpp
        val targetX = fixX + sin(goHeading) * ahead
        val targetY = fixY - cos(goHeading) * ahead
        val dt = if (paintedAtNs == 0L) 1.0 else ((nowNs - paintedAtNs) / 1e9).coerceIn(0.0, 1.0)
        paintedAtNs = nowNs
        val off = hypot(targetX - shownX, targetY - shownY) * mpp
        if (shownX.isNaN() || off > SNAP_M) {
            shownX = targetX
            shownY = targetY
            camHeading = goHeading
            carHeading = goHeading
        } else if (off < SETTLED_M) {
            // Close enough: there, so a parked car's picture stops changing.
            shownX = targetX
            shownY = targetY
        } else {
            val k = 1 - exp(-dt / 0.3)
            shownX += (targetX - shownX) * k
            shownY += (targetY - shownY) * k
        }
        val turnCam = angle(goHeading - camHeading)
        val turnCar = angle(goHeading - carHeading)
        camHeading = if (abs(turnCam) < SETTLED_RAD) goHeading else angle(camHeading + turnCam * (1 - exp(-dt / 0.6)))
        carHeading = if (abs(turnCar) < SETTLED_RAD) goHeading else angle(carHeading + turnCar * (1 - exp(-dt / 0.2)))
        moving = speed > 0 || off >= SETTLED_M || abs(turnCam) >= SETTLED_RAD || abs(turnCar) >= SETTLED_RAD
    }

    // --- The ground --------------------------------------------------------------------------------

    private var half = IntArray(0)

    /**
     * The ground below the far edge, from the tiles, worked out at half the
     * screen's resolution each way and then spread over it smoothly: a
     * quarter of the samples, which are what costs on a Pi (each one a read
     * somewhere in a tile, rarely next to the last), for a ground that is
     * stretched tiles near the car anyway.
     */
    private fun ground(image: BufferedImage, cam: Camera, lat: Double, tiles: TileSource?, minZoom: Int, maxZoom: Int, night: Boolean) {
        val out = (image.raster.dataBuffer as DataBufferInt).data
        val w = cam.w
        val h = cam.h
        val hw = w / 2 + 1
        val hh = h / 2 + 1
        if (half.size < hw * hh) half = IntArray(hw * hh)
        frameTiles.clear()
        val first = max(0, ceil(cam.horizon + cam.height * cam.f / cam.far).toInt())
        val firstHalf = first / 2
        for (j in firstHalf until hh) sampleRow(half, j * hw, hw, 2.0 * j + 0.5, cam, lat, tiles, minZoom, maxZoom, night)
        val spreadFrom = System.nanoTime()
        // Spread: even rows and columns are the samples, odd ones the average of their two neighbours.
        if (mid.size < hw) mid = IntArray(hw)
        for (y in first until h) {
            val j = y / 2
            val a = j * hw
            val src: IntArray
            val from: Int
            if (y and 1 == 1 && j + 1 < hh) {
                val b = a + hw
                for (i in 0 until hw) mid[i] = avg(half[a + i], half[b + i])
                src = mid
                from = 0
            } else {
                src = half
                from = a
            }
            var o = y * w
            val end = o + w
            var i = from
            var c = src[i]
            while (o < end) {
                out[o++] = c
                if (o == end) break
                val d = src[++i]
                out[o++] = avg(c, d)
                c = d
            }
        }
        spreadNs += System.nanoTime() - spreadFrom
    }

    private var spreadNs = 0L

    private var mid = IntArray(0)

    /** The fog laid over [band] rows from [from], all of it at the top, none at the bottom: the far ground and the route fade away. */
    private fun haze(px: IntArray, w: Int, h: Int, from: Double, band: Double, fog: Int) {
        val y0 = max(0, from.toInt() - 1)
        val y1 = min(h, (from + band).toInt() + 1)
        val fogRb = fog and 0xFF00FF
        val fogG = fog and 0xFF00
        for (y in y0 until y1) {
            // Out of 256: the fog's share, thinning faster near its end.
            val t = ((y - from) / band).coerceIn(0.0, 1.0)
            val a = ((1 - t) * (1 - t) * 256).toInt()
            if (a <= 0) continue
            val keep = 256 - a
            var o = y * w
            val end = o + w
            while (o < end) {
                val c = px[o]
                val rb = (((c and 0xFF00FF) * keep + fogRb * a) ushr 8) and 0xFF00FF
                val g = (((c and 0xFF00) * keep + fogG * a) ushr 8) and 0xFF00
                px[o++] = rb or g
            }
        }
    }

    private fun avg(a: Int, b: Int): Int = ((a and 0xFEFEFE) + (b and 0xFEFEFE)) ushr 1

    /**
     * One row of samples at screen row [y] (in full-size pixels), [count]
     * samples two screen pixels apart: the row's metres per sample pick the
     * zoom (the one whose pixels are nearest), then the row walks across the
     * tiles in fixed point, blending four pixels where the tiles are
     * stretched (near the car), taking one where they are not.
     */
    private fun sampleRow(
        out: IntArray, start: Int, count: Int, y: Double, cam: Camera, lat: Double, tiles: TileSource?,
        minZoom: Int, maxZoom: Int, night: Boolean
    ) {
        val z = (cam.height * cam.f / max(0.5, y - cam.horizon)).coerceAtMost(cam.far)
        val step = 2.0
        val metresPerSample = z / cam.f * step
        val m0 = WebMercator.metersPerPixel(lat, 0)
        val zoom = zoomFor(m0, metresPerSample, minZoom, maxZoom)
        val toZoom = Math.scalb(1.0, zoom - Z0)
        val stepWorld = metresPerSample / cam.mpp
        val fwdX = sin(cam.heading)
        val fwdY = -cos(cam.heading)
        val rightX = cos(cam.heading)
        val rightY = sin(cam.heading)
        // The row's first sample (at screen x = 0.5) and the step between samples, in world pixels at the row's zoom.
        val cx = cam.x + fwdX * z / cam.mpp
        val cy = cam.y + fwdY * z / cam.mpp
        val fromMiddle = (0.5 - cam.w / 2.0) / step
        val x0 = (cx + rightX * stepWorld * fromMiddle) * toZoom
        val y0 = (cy + rightY * stepWorld * fromMiddle) * toZoom
        val dx = rightX * stepWorld * toZoom
        val dy = rightY * stepWorld * toZoom
        val plain = if (night) PLAIN_NIGHT else PLAIN_DAY
        if (tiles == null) {
            // No tiles: squares of 100 m, so the movement shows.
            val plainAlt = if (night) PLAIN_NIGHT_ALT else PLAIN_DAY_ALT
            val sq = 100.0 / cam.mpp * toZoom
            for (i in 0 until count) {
                val gx = floor((x0 + dx * i) / sq).toLong()
                val gy = floor((y0 + dy * i) / sq).toLong()
                out[start + i] = if ((gx + gy) and 1L == 0L) plain else plainAlt
            }
            return
        }
        val stretched = WebMercator.metersPerPixel(lat, zoom) > metresPerSample * 1.25
        var xf = (x0 * FIX).toLong()
        var yf = (y0 * FIX).toLong()
        val dxf = (dx * FIX).toLong()
        val dyf = (dy * FIX).toLong()
        var lastTx = Int.MIN_VALUE
        var lastTy = Int.MIN_VALUE
        var px: IntArray? = null
        var shift = 0
        for (i in 0 until count) {
            val ix = (xf shr 16).toInt()
            val iy = (yf shr 16).toInt()
            val tx = ix shr 8
            val ty = iy shr 8
            if (tx != lastTx || ty != lastTy) {
                lastTx = tx
                lastTy = ty
                // The tile, else a coarser one already at hand while it comes.
                shift = 0
                px = pixels(tiles, zoom, tx, ty, fetch = true)
                while (px == null && shift < 3 && zoom - shift - 1 >= 0) {
                    shift++
                    px = pixels(tiles, zoom - shift, tx shr shift, ty shr shift, fetch = false)
                }
            }
            val p = px
            out[start + i] = when {
                p == null -> plain
                shift > 0 -> p[at((ix shr shift) and 255, (iy shr shift) and 255)]
                stretched -> {
                    val sx = ix and 255
                    val sy = iy and 255
                    val sx1 = if (sx == 255) 255 else sx + 1
                    val sy1 = if (sy == 255) 255 else sy + 1
                    val fx = ((xf shr 8) and 255).toInt()
                    val fy = ((yf shr 8) and 255).toInt()
                    blend(p[at(sx, sy)], p[at(sx1, sy)], p[at(sx, sy1)], p[at(sx1, sy1)], fx, fy)
                }
                else -> p[at(ix and 255, iy and 255)]
            }
            xf += dxf
            yf += dyf
        }
    }

    /** The tiles' pixels looked up this picture (null: not at hand), so each is asked for once a picture. */
    private val frameTiles = HashMap<Long, IntArray?>()

    /** A tile's pixels, looked up once a picture; [fetch]: ask for it when it isn't at hand. */
    private var lookups = 0
    private var lookupNs = 0L

    private fun pixels(tiles: TileSource, z: Int, x: Int, y: Int, fetch: Boolean): IntArray? {
        val from = System.nanoTime()
        try {
            return pixelsNow(tiles, z, x, y, fetch)
        } finally {
            lookups++
            lookupNs += System.nanoTime() - from
        }
    }

    private fun pixelsNow(tiles: TileSource, z: Int, x: Int, y: Int, fetch: Boolean): IntArray? {
        val frame = frameTiles
        val key = (z.toLong() shl 56) or ((x.toLong() and 0xFFFFFFF) shl 28) or (y.toLong() and 0xFFFFFFF)
        if (frame.containsKey(key)) return frame[key]
        val n = 1 shl z
        if (y < 0 || y >= n) return null.also { frame[key] = null }
        val image = (if (fetch) tiles.tile(z, Math.floorMod(x, n), y) else tiles.tileAtHand(z, Math.floorMod(x, n), y))
        val px = image?.let(::pixelsOf)
        // A missing coarser tile may come while the picture is drawn: only the fetched ones are kept.
        if (fetch || px != null) frame[key] = px
        return px
    }

    /**
     * A tile's pixels in blocks of 4×4 ([at]): the ground walks across a tile
     * in whatever direction the car faces, and in rows of 256 pixels a walk
     * east or west read a new stretch of memory at almost every step (half a
     * microsecond each on a Pi 3B). In blocks, every few steps stay in one.
     */
    private fun pixelsOf(image: BufferedImage): IntArray? {
        if (image.width != WebMercator.TILE || image.height != WebMercator.TILE) return null
        return pixelCache.getOrPut(image) {
            val rows = image.getRGB(0, 0, WebMercator.TILE, WebMercator.TILE, null, 0, WebMercator.TILE)
            val blocks = IntArray(rows.size)
            for (y in 0 until WebMercator.TILE) for (x in 0 until WebMercator.TILE) blocks[at(x, y)] = rows[(y shl 8) or x]
            blocks
        }
    }

    /** Where pixel ([x], [y]) of a tile is in its blocks: 64 blocks of 16 a row of blocks, 4 pixels a row in a block. */
    private fun at(x: Int, y: Int): Int = ((y shr 2) shl 10) or ((x shr 2) shl 4) or ((y and 3) shl 2) or (x and 3)

    /** Four neighbouring pixels weighed by where the point falls between them ([fx], [fy] out of 256). */
    private fun blend(a: Int, b: Int, c: Int, d: Int, fx: Int, fy: Int): Int {
        val ix = 256 - fx
        val iy = 256 - fy
        // Red and blue together, green apart: two channels per multiply, 8 bits of room between them.
        val rbTop = ((a and 0xFF00FF) * ix + (b and 0xFF00FF) * fx) ushr 8
        val rbBottom = ((c and 0xFF00FF) * ix + (d and 0xFF00FF) * fx) ushr 8
        val rb = (((rbTop and 0xFF00FF) * iy + (rbBottom and 0xFF00FF) * fy) ushr 8) and 0xFF00FF
        val gTop = ((a and 0xFF00) * ix + (b and 0xFF00) * fx) ushr 8
        val gBottom = ((c and 0xFF00) * ix + (d and 0xFF00) * fx) ushr 8
        val gg = (((gTop and 0xFF00) * iy + (gBottom and 0xFF00) * fy) ushr 8) and 0xFF00
        return rb or gg
    }

    /**
     * The tiles a few hundred metres down the road, so they are there before
     * the car: each at the zoom that stretch of road will be drawn with when
     * the car is a little closer, never a sharper one (they all count against
     * the provider's allowance).
     */
    private fun prefetch(tiles: TileSource?, cam: Camera, lat: Double, minZoom: Int, maxZoom: Int) {
        if (tiles == null || !moving) return
        val m0 = WebMercator.metersPerPixel(lat, 0)
        val keys = LinkedHashSet<TileKey>()
        var d = cam.back + 100.0
        while (d <= cam.back + 500.0) {
            // Drawn from about 100 m nearer than where it is now.
            val zoom = zoomFor(m0, (d - 100.0) / cam.f * 2, minZoom, maxZoom)
            val toZoom = Math.scalb(1.0, zoom - Z0)
            for (side in -1..1) {
                val wx = cam.x + (sin(cam.heading) * d + cos(cam.heading) * side * 60.0) / cam.mpp
                val wy = cam.y + (-cos(cam.heading) * d + sin(cam.heading) * side * 60.0) / cam.mpp
                keys += TileKey(zoom, WebMercator.tileOf(wx * toZoom), WebMercator.tileOf(wy * toZoom))
            }
            d += 100.0
        }
        tiles.prefetch(keys)
    }

    /** The zoom whose pixels are nearest [metresPerPx] (in octaves): sharp enough, without fetching tiles finer than the screen shows. */
    private fun zoomFor(m0: Double, metresPerPx: Double, minZoom: Int, maxZoom: Int): Int =
        floor(ln(m0 / metresPerPx) / ln(2.0) + 0.5).toInt().coerceIn(minZoom, maxZoom)

    // --- The route ---------------------------------------------------------------------------------

    /** The route as a ribbon on the road: a dark casing, the accent on it, cut where it passes under the camera. */
    private fun routeRibbon(v: Graphics2D, cam: Camera, route: List<Double>, across: Double, accent: Color, night: Boolean) {
        if (route.size < 4) return
        if (route !== routeSource) {
            routeSource = route
            routeWorld = DoubleArray(route.size / 2 * 2)
            for (i in 0 until route.size / 2) {
                routeWorld[2 * i] = WebMercator.x(route[2 * i + 1], Z0)
                routeWorld[2 * i + 1] = WebMercator.y(route[2 * i], Z0)
            }
        }
        val n = routeWorld.size / 2
        val lat = DoubleArray(n)
        val fwd = DoubleArray(n)
        for (i in 0 until n) {
            lat[i] = cam.lateral(routeWorld[2 * i], routeWorld[2 * i + 1])
            fwd[i] = cam.ahead(routeWorld[2 * i], routeWorld[2 * i + 1])
        }
        val fill = across * 0.05
        val casing = Color(mix(if (night) Color.BLACK else Color.WHITE, accent, 0.35f).rgb)
        for ((width, color) in listOf(fill * 1.5 to casing, fill to accent)) {
            v.color = color
            for (i in 0 until n - 1) {
                var aL = lat[i]; var aF = fwd[i]; var bL = lat[i + 1]; var bF = fwd[i + 1]
                if (aF < cam.clip && bF < cam.clip) continue
                if (aF > cam.far && bF > cam.far) continue
                // Cut at the camera's near edge.
                if (aF < cam.clip) { val t = (cam.clip - aF) / (bF - aF); aL += (bL - aL) * t; aF = cam.clip }
                if (bF < cam.clip) { val t = (cam.clip - bF) / (aF - bF); bL += (aL - bL) * t; bF = cam.clip }
                // And at the far edge: beyond it there is only sky.
                if (aF > cam.far) { val t = (aF - cam.far) / (aF - bF); aL += (bL - aL) * t; aF = cam.far }
                if (bF > cam.far) { val t = (bF - cam.far) / (bF - aF); bL += (aL - bL) * t; bF = cam.far }
                val len = hypot(bL - aL, bF - aF)
                if (len < 0.01) continue
                val nl = -(bF - aF) / len * width / 2
                val nf = (bL - aL) / len * width / 2
                quad(v, cam, aL + nl, aF + nf, bL + nl, bF + nf, bL - nl, bF - nf, aL - nl, aF - nf)
                if (i + 1 < n - 1) disc(v, cam, bL, bF, width / 2)
            }
        }
    }

    private val qx = IntArray(4)
    private val qy = IntArray(4)

    private fun quad(v: Graphics2D, cam: Camera, l0: Double, f0: Double, l1: Double, f1: Double, l2: Double, f2: Double, l3: Double, f3: Double) {
        val ls = doubleArrayOf(l0, l1, l2, l3)
        val fs = doubleArrayOf(f0, f1, f2, f3)
        for (k in 0 until 4) {
            val f = max(fs[k], cam.clip * 0.5)
            qx[k] = cam.sx(ls[k], f).roundToInt()
            qy[k] = cam.sy(f, 0.0).roundToInt()
        }
        v.fillPolygon(qx, qy, 4)
    }

    private val dx = IntArray(12)
    private val dy = IntArray(12)

    /** A round patch on the road at a bend, so the ribbon's pieces join. */
    private fun disc(v: Graphics2D, cam: Camera, l: Double, f: Double, r: Double) {
        if (f - r < cam.clip) return
        for (k in 0 until 12) {
            val a = k * PI / 6
            dx[k] = cam.sx(l + cos(a) * r, f + sin(a) * r).roundToInt()
            dy[k] = cam.sy(f + sin(a) * r, 0.0).roundToInt()
        }
        v.fillPolygon(dx, dy, 12)
    }

    // --- The buildings -----------------------------------------------------------------------------

    private class Placed(val b: Building, val tileX: Double, val tileY: Double, val distance: Double)

    /**
     * The buildings around the road ahead, nearest [MAX_BUILDINGS] at most,
     * drawn from the farthest: for each, its walls that face the camera (the
     * farther first), then its roof. Lit from the south-west, so the streets
     * read as the car turns; faded into the haze far away, and see-through
     * right by the camera so they never hide the car.
     */
    private fun buildings(v: Graphics2D, cam: Camera, source: BuildingSource, night: Boolean, fog: Color) {
        val reach = min(cam.far, max(cam.back * 6, 450.0)).coerceAtMost(MAX_BUILDING_M)
        val toTile = Math.scalb(1.0, source.zoom - Z0) / WebMercator.TILE
        val fromTile = WebMercator.TILE / Math.scalb(1.0, source.zoom - Z0)
        // The tiles under the view's ground, from the camera to [reach].
        val halfFar = reach * cam.w / 2 / cam.f + 60
        val corners = listOf(-60.0 to -40.0, 60.0 to -40.0, -halfFar to reach, halfFar to reach)
        var minTx = Int.MAX_VALUE; var maxTx = Int.MIN_VALUE; var minTy = Int.MAX_VALUE; var maxTy = Int.MIN_VALUE
        for ((l, f) in corners) {
            val wx = cam.x + (cam.cosH * l + cam.sinH * f) / cam.mpp
            val wy = cam.y + (cam.sinH * l - cam.cosH * f) / cam.mpp
            val tx = floor(wx * toTile).toInt()
            val ty = floor(wy * toTile).toInt()
            minTx = min(minTx, tx); maxTx = max(maxTx, tx); minTy = min(minTy, ty); maxTy = max(maxTy, ty)
        }
        if ((maxTx - minTx + 1) * (maxTy - minTy + 1) > 9) return
        val placed = ArrayList<Placed>()
        for (ty in minTy..maxTy) for (tx in minTx..maxTx) {
            val tile = source.buildings(tx, ty) ?: continue
            for (b in tile.buildings) {
                val wx = (tx + b.cx) * fromTile
                val wy = (ty + b.cy) * fromTile
                val l = cam.lateral(wx, wy)
                val f = cam.ahead(wx, wy)
                val r = b.reach * fromTile * cam.mpp
                if (f + r < cam.clip || f - r > reach) continue
                if (abs(l) - r > max(f, 1.0) * cam.w / 2 / cam.f + 4) continue
                placed += Placed(b, tx.toDouble(), ty.toDouble(), hypot(l, f))
            }
        }
        if (placed.isEmpty()) return
        placed.sortBy { it.distance }
        val drawn = if (placed.size > MAX_BUILDINGS) placed.subList(0, MAX_BUILDINGS) else placed
        val wall = if (night) WALL_NIGHT else WALL_DAY
        val wallLit = if (night) WALL_NIGHT_LIT else WALL_DAY_LIT
        val roof = if (night) ROOF_NIGHT else ROOF_DAY
        val edge = if (night) EDGE_NIGHT else EDGE_DAY
        // Toward the light (south-west, the world's x east, y south) in the camera's ground.
        val lightL = cam.cosH * -0.6 + cam.sinH * 0.8
        val lightF = cam.sinH * -0.6 - cam.cosH * 0.8
        val oldComposite = v.composite
        v.stroke = BasicStroke(1f)
        for (k in drawn.indices.reversed()) {
            val p = drawn[k]
            val haze = ((p.distance - reach * 0.45) / (reach * 0.55)).coerceIn(0.0, 1.0).toFloat() * 0.85f
            val see = ((p.distance - cam.back * 0.35) / (cam.back * 0.5)).coerceIn(0.3, 1.0).toFloat()
            v.composite = if (see < 1f) AlphaComposite.getInstance(AlphaComposite.SRC_OVER, see) else oldComposite
            building(v, cam, p, fromTile, wall, wallLit, roof, edge, fog, haze, lightL, lightF)
        }
        v.composite = oldComposite
    }

    private var bl = DoubleArray(64)
    private var bf = DoubleArray(64)
    private var real = BooleanArray(64)
    private var cl = DoubleArray(64)
    private var cf = DoubleArray(64)
    private var creal = BooleanArray(64)
    private var px = IntArray(64)
    private var py = IntArray(64)
    private val wx = IntArray(4)
    private val wy = IntArray(4)
    private val walls = ArrayList<Wall>()

    private class Wall(val a: Int, val b: Int, val distance: Double, val lit: Double)

    private fun building(
        v: Graphics2D, cam: Camera, p: Placed, fromTile: Double, wall: Color, wallLit: Color, roof: Color, edge: Color,
        fog: Color, haze: Float, lightL: Double, lightF: Double
    ) {
        val ring = p.b.ring
        val n = ring.size / 2
        if (bl.size < n * 2 + 4) {
            bl = DoubleArray(n * 2 + 4); bf = DoubleArray(n * 2 + 4); real = BooleanArray(n * 2 + 4)
            cl = DoubleArray(n * 2 + 4); cf = DoubleArray(n * 2 + 4); creal = BooleanArray(n * 2 + 4)
            px = IntArray(n * 2 + 4); py = IntArray(n * 2 + 4)
        }
        for (i in 0 until n) {
            val tx = ring[2 * i]
            val ty = ring[2 * i + 1]
            val wxw = (p.tileX + tx) * fromTile
            val wyw = (p.tileY + ty) * fromTile
            bl[i] = cam.lateral(wxw, wyw)
            bf[i] = cam.ahead(wxw, wyw)
            // An edge along the tile's border is where the tile cut the building: no wall there.
            val j = (i + 1) % n
            val ux = ring[2 * j]
            val uy = ring[2 * j + 1]
            real[i] = !((tx <= EDGE && ux <= EDGE) || (tx >= 1 - EDGE && ux >= 1 - EDGE) ||
                (ty <= EDGE && uy <= EDGE) || (ty >= 1 - EDGE && uy >= 1 - EDGE))
        }
        // Cut at the camera's near edge (Sutherland–Hodgman), each corner keeping whether the wall after it is real.
        var m = 0
        for (i in 0 until n) {
            val j = (i + 1) % n
            val aIn = bf[i] >= cam.clip
            val bIn = bf[j] >= cam.clip
            if (aIn) { cl[m] = bl[i]; cf[m] = bf[i]; creal[m] = real[i]; m++ }
            if (aIn != bIn) {
                val t = (cam.clip - bf[i]) / (bf[j] - bf[i])
                cl[m] = bl[i] + (bl[j] - bl[i]) * t
                cf[m] = cam.clip
                creal[m] = !aIn && real[i]
                m++
            }
        }
        if (m < 3) return
        // Which way round it goes, here: walls face out from that.
        var area = 0.0
        for (i in 0 until m) { val j = (i + 1) % m; area += cl[i] * cf[j] - cl[j] * cf[i] }
        val ccw = area > 0
        val lo = p.b.minHeight.toDouble()
        val hi = p.b.height.toDouble()
        walls.clear()
        for (i in 0 until m) {
            if (!creal[i]) continue
            val j = (i + 1) % m
            val el = cl[j] - cl[i]
            val ef = cf[j] - cf[i]
            val len = hypot(el, ef)
            if (len < 0.05) continue
            val nl = (if (ccw) ef else -ef) / len
            val nf = (if (ccw) -el else el) / len
            val midL = (cl[i] + cl[j]) / 2
            val midF = (cf[i] + cf[j]) / 2
            if (nl * -midL + nf * -midF <= 0) continue
            walls += Wall(i, j, hypot(midL, midF), max(0.0, nl * lightL + nf * lightF))
        }
        walls.sortByDescending { it.distance }
        for (wl in walls) {
            wx[0] = cam.sx(cl[wl.a], cf[wl.a]).roundToInt(); wy[0] = cam.sy(cf[wl.a], lo).roundToInt()
            wx[1] = cam.sx(cl[wl.b], cf[wl.b]).roundToInt(); wy[1] = cam.sy(cf[wl.b], lo).roundToInt()
            wx[2] = wx[1]; wy[2] = cam.sy(cf[wl.b], hi).roundToInt()
            wx[3] = wx[0]; wy[3] = cam.sy(cf[wl.a], hi).roundToInt()
            v.color = mix(mix(wall, wallLit, wl.lit.toFloat()), fog, haze)
            v.fillPolygon(wx, wy, 4)
        }
        for (i in 0 until m) {
            px[i] = cam.sx(cl[i], cf[i]).roundToInt()
            py[i] = cam.sy(cf[i], hi).roundToInt()
        }
        v.color = mix(roof, fog, haze)
        v.fillPolygon(px, py, m)
        if (haze < 0.6f) {
            v.color = mix(edge, fog, haze)
            v.drawPolygon(px, py, m)
        }
    }

    // --- The car -----------------------------------------------------------------------------------

    /**
     * The car on the road: a soft light under it, its shadow, and a chevron
     * standing a little off the ground with lit sides, as big on screen
     * whatever the distance; a disc when its heading isn't known yet.
     */
    private fun car(v: Graphics2D, cam: Camera, accent: Color, rel: Double, hasHeading: Boolean) {
        val s = 0.062 * cam.h * cam.back / cam.f
        val cr = cos(rel)
        val sr = sin(rel)
        fun proj(lx: Double, ly: Double, up: Double): Pair<Int, Int> {
            val lat = lx * cr + ly * sr
            val fwd = cam.back + (-lx * sr + ly * cr)
            return cam.sx(lat, fwd).roundToInt() to cam.sy(fwd, up).roundToInt()
        }
        fun ring(r: Double, up: Double, count: Int = 20): Pair<IntArray, IntArray> {
            val xs = IntArray(count)
            val ys = IntArray(count)
            for (k in 0 until count) {
                val a = k * 2 * PI / count
                val (x, y) = proj(cos(a) * r, sin(a) * r, up)
                xs[k] = x; ys[k] = y
            }
            return xs to ys
        }
        for ((r, alpha) in listOf(2.3 to 40, 1.5 to 55, 0.9 to 70)) {
            val (xs, ys) = ring(s * r, 0.0)
            v.color = Color(accent.red, accent.green, accent.blue, alpha)
            v.fillPolygon(xs, ys, xs.size)
        }
        if (!hasHeading) {
            val (xs, ys) = ring(s * 0.45, s * 0.25)
            v.color = accent
            v.fillPolygon(xs, ys, xs.size)
            v.color = Color.WHITE
            v.stroke = BasicStroke(max(2f, cam.h / 300f))
            v.drawPolygon(xs, ys, xs.size)
            return
        }
        val shape = arrayOf(0.0 to s, 0.72 * s to -0.65 * s, 0.0 to -0.22 * s, -0.72 * s to -0.65 * s)
        val base = 0.12 * s
        val top = base + 0.3 * s
        // The shadow, a little off to the side.
        val sx = IntArray(4)
        val sy = IntArray(4)
        shape.forEachIndexed { k, (x, y) -> val (a, b) = proj(x * 1.1 + 0.12 * s, y * 1.1 - 0.1 * s, 0.0); sx[k] = a; sy[k] = b }
        v.color = Color(0, 0, 0, 100)
        v.fillPolygon(sx, sy, 4)
        // The sides, the farther first, lit by how they face.
        data class Side(val xs: IntArray, val ys: IntArray, val depth: Double, val lit: Double)
        val sides = shape.indices.map { k ->
            val (ax, ay) = shape[k]
            val (bx, by) = shape[(k + 1) % shape.size]
            val p0 = proj(ax, ay, base); val p1 = proj(bx, by, base); val p2 = proj(bx, by, top); val p3 = proj(ax, ay, top)
            val nx = by - ay
            val ny = -(bx - ax)
            val len = sqrt(nx * nx + ny * ny)
            val lit = 0.55 + 0.45 * max(0.0, (nx * 0.5 - ny * 0.8) / len)
            val depth = -((ay + by) / 2 * cr - (ax + bx) / 2 * sr)
            Side(intArrayOf(p0.first, p1.first, p2.first, p3.first), intArrayOf(p0.second, p1.second, p2.second, p3.second), depth, lit)
        }.sortedByDescending { it.depth }
        val dark = mix(accent, Color.BLACK, 0.55f)
        for (side in sides) {
            v.color = mix(dark, accent, side.lit.toFloat() * 0.7f)
            v.fillPolygon(side.xs, side.ys, 4)
        }
        val tx = IntArray(4)
        val ty = IntArray(4)
        shape.forEachIndexed { k, (x, y) -> val (a, b) = proj(x, y, top); tx[k] = a; ty[k] = b }
        val (tipX, tipY) = proj(0.0, s, top)
        val (backX, backY) = proj(0.0, -0.4 * s, top)
        v.paint = GradientPaint(backX.toFloat(), backY.toFloat(), accent, tipX.toFloat(), tipY.toFloat(), mix(accent, Color.WHITE, 0.45f))
        v.fillPolygon(tx, ty, 4)
        v.color = Color.WHITE
        v.stroke = BasicStroke(max(2f, cam.h / 320f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        v.drawPolygon(tx, ty, 4)
    }

    companion object {
        /** World pixels for positions: zoom 20, a few centimetres each. */
        const val Z0 = 20
        private const val FIX = 65536.0
        /** Farther than this from where it should be, the car jumps there (a new drive, a GPS jump). */
        private const val SNAP_M = 250.0
        /** How far a stopped car's position may wander and still be the same place. */
        private const val WANDER_M = 4.0
        /** The car goes on from a fix this long at most: a lost GPS doesn't drive it away. */
        private const val MAX_AHEAD_S = 1.5
        /** Nearer than this to where it should be (metres, radians), the car is there. */
        private const val SETTLED_M = 0.05
        private const val SETTLED_RAD = 0.002
        const val MAX_BUILDINGS = 700
        private const val MAX_BUILDING_M = 900.0
        private const val EDGE = 1e-4f

        private val SKY_NIGHT = Color(3, 6, 11)
        private val FOG_NIGHT = Color(27, 36, 48)
        private val SKY_DAY = Color(127, 178, 223)
        private val FOG_DAY = Color(226, 232, 237)
        private val PLAIN_NIGHT = 0x161A20
        private val PLAIN_NIGHT_ALT = 0x1B2028
        private val PLAIN_DAY = 0xE6E2DA
        private val PLAIN_DAY_ALT = 0xDDD8CF
        private val WALL_NIGHT = Color(44, 52, 66)
        private val WALL_NIGHT_LIT = Color(84, 98, 122)
        private val ROOF_NIGHT = Color(70, 82, 102)
        private val EDGE_NIGHT = Color(110, 128, 156)
        private val WALL_DAY = Color(176, 168, 156)
        private val WALL_DAY_LIT = Color(230, 224, 214)
        private val ROOF_DAY = Color(240, 236, 229)
        private val EDGE_DAY = Color(168, 158, 146)

        private fun angle(a: Double): Double = atan2(sin(a), cos(a))

        fun mix(a: Color, b: Color, t: Float): Color {
            val u = t.coerceIn(0f, 1f)
            return Color(
                (a.red + (b.red - a.red) * u).roundToInt(),
                (a.green + (b.green - a.green) * u).roundToInt(),
                (a.blue + (b.blue - a.blue) * u).roundToInt()
            )
        }
    }
}
