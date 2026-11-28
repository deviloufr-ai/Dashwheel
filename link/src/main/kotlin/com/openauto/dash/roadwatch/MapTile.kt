package com.openauto.dash.roadwatch

import kotlin.math.floor

/**
 * The roads and buildings of one vector map tile (Mapbox Vector Tile bytes in
 * OpenMapTiles' layout, as OpenFreeMap serves them): the `transportation`
 * layer's lines a car drives on, and the `building` layer's outlines with
 * their heights. Only what the drawn road needs is read; the display's own
 * VectorTile reads buildings alone, in its own units.
 */
object MapTile {
    /** Buildings come in from this zoom on, and a tile is about 2.4 km across at the equator: one covers the road ahead. */
    const val ZOOM = 14

    fun tileX(lon: Double, zoom: Int = ZOOM): Int = floor(RoadMap.worldX(lon) * (1 shl zoom)).toInt().coerceIn(0, (1 shl zoom) - 1)
    fun tileY(lat: Double, zoom: Int = ZOOM): Int = floor(RoadMap.worldY(lat) * (1 shl zoom)).toInt().coerceIn(0, (1 shl zoom) - 1)

    /** Tile [x], [y] at [zoom]; a tile that isn't one gives an empty map. */
    fun parse(bytes: ByteArray, x: Int, y: Int, zoom: Int = ZOOM): RoadMap {
        val roads = ArrayList<MapRoad>()
        val buildings = ArrayList<MapBuilding>()
        runCatching {
            val tile = Reader(bytes, 0, bytes.size)
            while (tile.more()) {
                val tag = tile.varint().toInt()
                if (tag == LAYER) layer(tile.sub(), x, y, zoom, roads, buildings) else tile.skip(tag and 7)
            }
        }
        return RoadMap(roads, buildings)
    }

    private fun layer(r: Reader, x: Int, y: Int, zoom: Int, roads: MutableList<MapRoad>, buildings: MutableList<MapBuilding>) {
        var name = ""
        var extent = 4096
        val features = ArrayList<Reader>()
        val keys = ArrayList<String>()
        val values = ArrayList<Any?>()
        while (r.more()) {
            val tag = r.varint().toInt()
            when (tag shr 3) {
                1 -> name = r.string()
                2 -> features += r.sub()
                3 -> keys += r.string()
                4 -> values += value(r.sub())
                5 -> extent = r.varint().toInt()
                else -> r.skip(tag and 7)
            }
        }
        if (name != "transportation" && name != "building") return
        val tiles = (1 shl zoom).toDouble()
        for (f in features) {
            var type = 0
            var geometry: Reader? = null
            val tags = HashMap<String, Any?>()
            while (f.more()) {
                val tag = f.varint().toInt()
                when (tag shr 3) {
                    2 -> { val t = f.sub(); while (t.more()) { val k = t.varint().toInt(); val v = t.varint().toInt(); tags[keys.getOrNull(k) ?: continue] = values.getOrNull(v) } }
                    3 -> type = f.varint().toInt()
                    4 -> geometry = f.sub()
                    else -> f.skip(tag and 7)
                }
            }
            val g = geometry ?: continue
            if (name == "transportation") {
                val half = HALF_WIDTH_M[tags["class"]] ?: continue
                if (type != LINE) continue
                for (line in paths(g)) if (line.size >= 4) roads += MapRoad(world(line, 0, x, extent, tiles), world(line, 1, y, extent, tiles), half)
            } else {
                if (type != POLYGON || tags["hide_3d"] == true) continue
                val height = (tags["render_height"] as? Number)?.toFloat() ?: DEFAULT_HEIGHT_M
                // The outlines only (clockwise on the tile), not the courtyards cut out of them.
                for (ring in paths(g)) if (ring.size >= 6 && area(ring) > 0) buildings += MapBuilding(world(ring, 0, x, extent, tiles), world(ring, 1, y, extent, tiles), height)
            }
        }
    }

    /** Every other number of [points] from [from] (the xs, or the ys), from tile units to world units. */
    private fun world(points: IntArray, from: Int, tile: Int, extent: Int, tiles: Double): DoubleArray =
        DoubleArray(points.size / 2) { (tile + points[it * 2 + from].toDouble() / extent) / tiles }

    /** A geometry's lines or rings, each as x, y pairs in tile units. */
    private fun paths(g: Reader): List<IntArray> {
        val out = ArrayList<IntArray>()
        var current = ArrayList<Int>()
        var x = 0
        var y = 0
        while (g.more()) {
            val command = g.varint().toInt()
            val count = command shr 3
            when (command and 7) {
                MOVE_TO, LINE_TO -> repeat(count) {
                    if (command and 7 == MOVE_TO && current.isNotEmpty()) { out += current.toIntArray(); current = ArrayList() }
                    x += zigzag(g.varint())
                    y += zigzag(g.varint())
                    current += x; current += y
                }
                else -> Unit
            }
        }
        if (current.isNotEmpty()) out += current.toIntArray()
        return out
    }

    private fun area(ring: IntArray): Long {
        var sum = 0L
        val n = ring.size / 2
        for (i in 0 until n) {
            val j = (i + 1) % n
            sum += ring[i * 2].toLong() * ring[j * 2 + 1] - ring[j * 2].toLong() * ring[i * 2 + 1]
        }
        return sum
    }

    private fun value(r: Reader): Any? {
        var out: Any? = null
        while (r.more()) {
            val tag = r.varint().toInt()
            out = when (tag shr 3) {
                1 -> r.string()
                2 -> java.lang.Float.intBitsToFloat(r.fixed32())
                3 -> java.lang.Double.longBitsToDouble(r.fixed64())
                4, 5 -> r.varint()
                6 -> zigzag(r.varint()).toLong()
                7 -> r.varint() != 0L
                else -> { r.skip(tag and 7); out }
            }
        }
        return out
    }

    private fun zigzag(v: Long): Int = ((v ushr 1) xor -(v and 1)).toInt()

    /** Protocol buffers, as much as a tile uses. */
    private class Reader(private val b: ByteArray, private var at: Int, private val end: Int) {
        fun more() = at < end

        fun varint(): Long {
            var out = 0L
            var shift = 0
            while (true) {
                val byte = b[at++].toInt()
                out = out or ((byte and 0x7F).toLong() shl shift)
                if (byte and 0x80 == 0) return out
                shift += 7
            }
        }

        fun fixed32(): Int {
            val v = (b[at].toInt() and 255) or (b[at + 1].toInt() and 255 shl 8) or (b[at + 2].toInt() and 255 shl 16) or (b[at + 3].toInt() and 255 shl 24)
            at += 4
            return v
        }

        fun fixed64(): Long {
            val low = fixed32().toLong() and 0xFFFFFFFFL
            return low or (fixed32().toLong() shl 32)
        }

        /** The length-prefixed field here, as a reader of its own. */
        fun sub(): Reader {
            val n = varint().toInt()
            require(n >= 0 && at + n <= end) { "length" }
            return Reader(b, at, at + n).also { at += n }
        }

        fun string(): String {
            val n = varint().toInt()
            require(n >= 0 && at + n <= end) { "length" }
            return String(b, at, n, Charsets.UTF_8).also { at += n }
        }

        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> at += 8
                2 -> at += varint().toInt()
                5 -> at += 4
                else -> error("wire type")
            }
        }
    }

    private const val LAYER = 3 shl 3 or 2
    private const val LINE = 2
    private const val POLYGON = 3
    private const val MOVE_TO = 1
    private const val LINE_TO = 2
    private const val DEFAULT_HEIGHT_M = 6f

    /** What a car drives on, by OpenMapTiles' class, and half how wide each is drawn where it isn't the car's own road. */
    private val HALF_WIDTH_M = mapOf<Any?, Float>(
        "motorway" to 5.5f, "trunk" to 5f, "primary" to 4.5f, "secondary" to 4f, "tertiary" to 3.5f,
        "minor" to 3f, "service" to 2f, "busway" to 3f
    )
}
