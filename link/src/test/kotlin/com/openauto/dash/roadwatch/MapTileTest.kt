package com.openauto.dash.roadwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class MapTileTest {
    // Protocol buffers written by hand: a tile as a map server sends it.
    private fun varint(v: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var x = v
        while (true) {
            if (x and 0x7FL.inv() == 0L) { out.write(x.toInt()); break }
            out.write((x and 0x7F).toInt() or 0x80)
            x = x ushr 7
        }
        return out.toByteArray()
    }

    private fun field(n: Int, body: ByteArray) = varint((n shl 3 or 2).toLong()) + varint(body.size.toLong()) + body
    private fun number(n: Int, v: Long) = varint((n shl 3).toLong()) + varint(v)
    private fun zig(v: Int) = varint(((v shl 1) xor (v shr 31)).toLong())
    private fun packed(vararg parts: ByteArray) = parts.fold(ByteArray(0)) { a, b -> a + b }
    private fun command(id: Int, count: Int) = varint((count shl 3 or id).toLong())

    private fun layer(name: String, keys: List<String>, values: List<ByteArray>, vararg features: ByteArray) =
        field(3, packed(field(1, name.toByteArray()), *features.map { field(2, it) }.toTypedArray(),
            *keys.map { field(3, it.toByteArray()) }.toTypedArray(), *values.map { field(4, it) }.toTypedArray(), number(5, 4096)))

    private fun feature(type: Int, tags: List<Int>, geometry: ByteArray) =
        packed(field(2, packed(*tags.map { varint(it.toLong()) }.toTypedArray())), number(3, type.toLong()), field(4, geometry))

    @Test
    fun aTilesRoadsAndBuildingsAreRead() {
        // A primary road across the tile's middle and a footpath; a house 20 high, a courtyard cut out of it, and a part hidden from 3D.
        val line = packed(command(1, 1), zig(0), zig(2048), command(2, 1), zig(4096), zig(0))
        val square = packed(command(1, 1), zig(1024), zig(1024), command(2, 3), zig(100), zig(0), zig(0), zig(100), zig(-100), zig(0), command(7, 1))
        val hole = packed(command(1, 1), zig(20), zig(-80), command(2, 3), zig(0), zig(60), zig(60), zig(0), zig(0), zig(-60), command(7, 1))
        val roads = layer(
            "transportation", listOf("class"), listOf(field(1, "primary".toByteArray()), field(1, "path".toByteArray())),
            feature(2, listOf(0, 0), line), feature(2, listOf(0, 1), line)
        )
        val houses = layer(
            "building", listOf("render_height", "hide_3d"), listOf(number(5, 20), number(7, 1)),
            feature(3, listOf(0, 0), packed(square, hole)), feature(3, listOf(0, 0, 1, 1), square)
        )
        val map = MapTile.parse(packed(layer("water", emptyList(), emptyList()), roads, houses), 8, 5, zoom = 4)

        val road = map.roads.single()
        assertEquals(4.5f, road.halfWidthM, 0f)
        assertEquals(8 / 16.0, road.xs[0], 1e-9)
        assertEquals(9 / 16.0, road.xs[1], 1e-9)
        assertEquals(5.5 / 16.0, road.ys[0], 1e-9)
        val house = map.buildings.single()
        assertEquals(20f, house.heightM, 0f)
        assertEquals(4, house.xs.size)
        assertEquals((8 + 1124 / 4096.0) / 16.0, house.xs[1], 1e-9)
    }

    @Test
    fun whatIsNotATileIsAnEmptyMap() {
        val map = MapTile.parse("<html>not found</html>".toByteArray(), 0, 0)
        assertTrue(map.roads.isEmpty() && map.buildings.isEmpty())
    }

    @Test
    fun aPlaceIsInItsTile() {
        // Paris, at zoom 14.
        assertEquals(8299, MapTile.tileX(2.3522))
        assertEquals(5636,MapTile.tileY(48.8566))
    }
}
