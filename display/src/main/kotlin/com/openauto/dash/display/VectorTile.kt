package com.openauto.dash.display

/**
 * One building from a vector tile: its outline as x, y pairs in fractions of
 * the tile (0..1, y down; a little outside where the tile's buffer reaches),
 * walls from [minHeight] to [height] metres.
 */
class Building(val ring: FloatArray, val height: Float, val minHeight: Float) {
    /** The middle of the outline and how far its corners reach from it, in tile fractions: for quick culling. */
    val cx: Float
    val cy: Float
    val reach: Float

    init {
        var sx = 0f
        var sy = 0f
        val n = ring.size / 2
        for (i in 0 until n) { sx += ring[2 * i]; sy += ring[2 * i + 1] }
        cx = sx / n
        cy = sy / n
        var r = 0f
        for (i in 0 until n) r = maxOf(r, Math.hypot((ring[2 * i] - cx).toDouble(), (ring[2 * i + 1] - cy).toDouble()).toFloat())
        reach = r
    }
}

/** The buildings of one vector tile. */
class BuildingTile(val buildings: List<Building>)

/**
 * The buildings of a Mapbox Vector Tile (the OpenMapTiles schema MapTiler
 * serves): the "building" layer's outer rings, with `render_height` and
 * `render_min_height`; holes are left out (a courtyard gets a roof), and
 * parts marked `hide_3d` too (their whole building comes as another part).
 * A small protobuf reader of its own: only what this needs.
 */
object VectorTile {
    const val LAYER = "building"
    /** A building without a height: about two floors. */
    const val DEFAULT_HEIGHT = 6f

    fun buildings(bytes: ByteArray): BuildingTile {
        val out = ArrayList<Building>()
        val tile = Reader(bytes, 0, bytes.size)
        while (tile.more()) {
            val (field, wire) = tile.tag()
            if (field == 3 && wire == 2) {
                val layer = tile.message()
                readLayer(layer, out)
            } else {
                tile.skip(wire)
            }
        }
        return BuildingTile(out)
    }

    private fun readLayer(layer: Reader, out: MutableList<Building>) {
        // The name comes first in practice, but features may come before the keys: kept as ranges till the end.
        var name: String? = null
        var extent = 4096
        val keys = ArrayList<String>()
        val values = ArrayList<Any?>()
        val features = ArrayList<Reader>()
        while (layer.more()) {
            val (field, wire) = layer.tag()
            when {
                field == 1 && wire == 2 -> name = layer.string()
                field == 2 && wire == 2 -> features += layer.message()
                field == 3 && wire == 2 -> keys += layer.string()
                field == 4 && wire == 2 -> values += readValue(layer.message())
                field == 5 && wire == 0 -> extent = layer.varint().toInt()
                else -> layer.skip(wire)
            }
            if (name != null && name != LAYER) return
        }
        if (name != LAYER || extent <= 0) return
        val heightKey = keys.indexOf("render_height")
        val minKey = keys.indexOf("render_min_height")
        val hideKey = keys.indexOf("hide_3d")
        for (f in features) readFeature(f, extent.toFloat(), heightKey, minKey, hideKey, values, out)
    }

    private fun readFeature(f: Reader, extent: Float, heightKey: Int, minKey: Int, hideKey: Int, values: List<Any?>, out: MutableList<Building>) {
        var type = 0
        var tags: IntArray? = null
        var geometry: IntArray? = null
        while (f.more()) {
            val (field, wire) = f.tag()
            when {
                field == 2 && wire == 2 -> tags = f.packed()
                field == 3 && wire == 0 -> type = f.varint().toInt()
                field == 4 && wire == 2 -> geometry = f.packed()
                else -> f.skip(wire)
            }
        }
        if (type != 3 || geometry == null) return
        var height = DEFAULT_HEIGHT
        var minHeight = 0f
        tags?.let { t ->
            var i = 0
            while (i + 1 < t.size) {
                val v = values.getOrNull(t[i + 1])
                when (t[i]) {
                    heightKey -> (v as? Number)?.let { height = it.toFloat() }
                    minKey -> (v as? Number)?.let { minHeight = it.toFloat() }
                    hideKey -> if (v == true) return
                }
                i += 2
            }
        }
        if (height <= minHeight) return
        rings(geometry, extent) { ring ->
            // Outer rings only: positive area (clockwise on screen, y down) by the tile's rule.
            if (ring.size >= 6 && area(ring) > 0f) out += Building(ring, height.coerceAtMost(600f), minHeight.coerceAtLeast(0f))
        }
    }

    /** The geometry's closed rings, in tile fractions. */
    private inline fun rings(g: IntArray, extent: Float, each: (FloatArray) -> Unit) {
        var x = 0
        var y = 0
        var i = 0
        var ring = FloatList()
        while (i < g.size) {
            val command = g[i] and 7
            val count = g[i] ushr 3
            i++
            when (command) {
                1, 2 -> repeat(count) {
                    if (i + 1 >= g.size) return
                    x += zigzag(g[i]); y += zigzag(g[i + 1]); i += 2
                    if (command == 1 && ring.size > 0) ring = FloatList()
                    ring.add(x / extent); ring.add(y / extent)
                }
                7 -> { if (ring.size >= 6) each(ring.toArray()); ring = FloatList() }
                else -> return
            }
        }
    }

    /** Twice the signed area, y down: positive for a ring clockwise on screen. */
    fun area(ring: FloatArray): Float {
        var a = 0f
        val n = ring.size / 2
        for (k in 0 until n) {
            val j = (k + 1) % n
            a += ring[2 * k] * ring[2 * j + 1] - ring[2 * j] * ring[2 * k + 1]
        }
        return a
    }

    private fun zigzag(n: Int): Int = (n ushr 1) xor -(n and 1)

    private fun readValue(v: Reader): Any? {
        var value: Any? = null
        while (v.more()) {
            val (field, wire) = v.tag()
            value = when {
                field == 1 && wire == 2 -> v.string()
                field == 2 && wire == 5 -> Float.fromBits(v.fixed32())
                field == 3 && wire == 1 -> Double.fromBits(v.fixed64())
                field == 4 && wire == 0 -> v.varint()
                field == 5 && wire == 0 -> v.varint()
                field == 6 && wire == 0 -> v.varint().let { (it ushr 1) xor -(it and 1) }
                field == 7 && wire == 0 -> v.varint() != 0L
                else -> { v.skip(wire); value }
            }
        }
        return value
    }

    private class FloatList {
        private var a = FloatArray(16)
        var size = 0
            private set
        fun add(f: Float) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = f }
        fun toArray(): FloatArray = a.copyOf(size)
    }

    /** Protobuf fields over [bytes] from [start] to [end]. */
    private class Reader(private val bytes: ByteArray, start: Int, private val end: Int) {
        private var pos = start

        fun more() = pos < end

        fun tag(): Pair<Int, Int> {
            val t = varint().toInt()
            return (t ushr 3) to (t and 7)
        }

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (pos < end) {
                val b = bytes[pos++].toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift > 63) break
            }
            throw IllegalArgumentException("bad varint")
        }

        fun fixed32(): Int {
            check(pos + 4 <= end)
            var v = 0
            for (k in 0 until 4) v = v or ((bytes[pos + k].toInt() and 0xFF) shl (8 * k))
            pos += 4
            return v
        }

        fun fixed64(): Long {
            check(pos + 8 <= end)
            var v = 0L
            for (k in 0 until 8) v = v or ((bytes[pos + k].toLong() and 0xFF) shl (8 * k))
            pos += 8
            return v
        }

        private fun length(): Int {
            val n = varint().toInt()
            require(n >= 0 && pos + n <= end) { "bad length" }
            return n
        }

        fun message(): Reader {
            val n = length()
            return Reader(bytes, pos, pos + n).also { pos += n }
        }

        fun string(): String {
            val n = length()
            return String(bytes, pos, n, Charsets.UTF_8).also { pos += n }
        }

        fun packed(): IntArray {
            val r = message()
            var out = IntArray(16)
            var n = 0
            while (r.more()) {
                if (n == out.size) out = out.copyOf(n * 2)
                out[n++] = r.varint().toInt()
            }
            return out.copyOf(n)
        }

        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> pos += 8
                2 -> { val n = length(); pos += n }
                5 -> pos += 4
                else -> throw IllegalArgumentException("wire type $wire")
            }
            require(pos <= end)
        }
    }
}
