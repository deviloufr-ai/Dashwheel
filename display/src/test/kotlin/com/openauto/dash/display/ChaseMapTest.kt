package com.openauto.dash.display

import com.openauto.dash.link.ClusterState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream

class ChaseMapTest {

    init {
        System.setProperty("java.awt.headless", "true")
    }

    // --- A vector tile written by hand ------------------------------------------------------------

    private class Proto {
        val out = ByteArrayOutputStream()
        fun varint(v: Long) { var x = v; while (x and 0x7F.inv().toLong() != 0L) { out.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 }; out.write(x.toInt()) }
        fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())
        fun bytes(field: Int, b: ByteArray) { tag(field, 2); varint(b.size.toLong()); out.write(b) }
        fun string(field: Int, s: String) = bytes(field, s.toByteArray())
        fun packed(field: Int, values: List<Int>) = bytes(field, Proto().also { p -> values.forEach { p.varint(it.toLong() and 0xFFFFFFFFL) } }.out.toByteArray())
        fun uint(field: Int, v: Long) { tag(field, 0); varint(v) }
        fun toByteArray(): ByteArray = out.toByteArray()
    }

    private fun zz(n: Int) = (n shl 1) xor (n shr 31)

    /** A square ring from (x, y), [side] across, the way MVT writes it: clockwise on screen, or not when [hole]. */
    private fun square(x: Int, y: Int, side: Int, hole: Boolean, fromX: Int, fromY: Int): List<Int> {
        val pts = if (!hole) listOf(x to y, x + side to y, x + side to y + side, x to y + side) else listOf(x to y, x to y + side, x + side to y + side, x + side to y)
        val g = ArrayList<Int>()
        g += (1 or (1 shl 3)); g += zz(pts[0].first - fromX); g += zz(pts[0].second - fromY)
        g += (2 or (3 shl 3))
        for (k in 1..3) { g += zz(pts[k].first - pts[k - 1].first); g += zz(pts[k].second - pts[k - 1].second) }
        g += 7 or (1 shl 3)
        return g
    }

    private fun feature(tags: List<Int>, geometry: List<Int>, type: Int = 3) = Proto().apply {
        packed(2, tags); uint(3, type.toLong()); packed(4, geometry)
    }.toByteArray()

    private fun value(f: (Proto) -> Unit) = Proto().also(f).toByteArray()

    private fun tile(): ByteArray {
        // keys: render_height, render_min_height, hide_3d; values: 24 (double), 3 (int), true.
        val layer = Proto().apply {
            string(1, "building")
            // A 24 m tower with a courtyard (the hole is left out), from 3 m up.
            bytes(2, feature(listOf(0, 0, 1, 1), square(1000, 1000, 400, false, 0, 0) + square(1100, 1100, 100, true, 1000, 1400)))
            // A part hidden in 3D: left out.
            bytes(2, feature(listOf(2, 2), square(3000, 3000, 100, false, 0, 0)))
            // No height: the default.
            bytes(2, feature(emptyList(), square(2000, 2000, 200, false, 0, 0)))
            // A line, not a building.
            bytes(2, feature(emptyList(), listOf(1 or (1 shl 3), 0, 0, 2 or (1 shl 3), 10, 10), type = 2))
            string(3, "render_height"); string(3, "render_min_height"); string(3, "hide_3d")
            bytes(4, value { it.tag(3, 1); repeat(8) { k -> it.out.write((java.lang.Double.doubleToLongBits(24.0) ushr (8 * k)).toInt() and 0xFF) } })
            bytes(4, value { it.uint(4, 3) })
            bytes(4, value { it.uint(7, 1) })
            uint(5, 4096)
        }
        val roads = Proto().apply { string(1, "transportation"); uint(5, 4096) }
        return Proto().apply { bytes(3, roads.toByteArray()); bytes(3, layer.toByteArray()) }.toByteArray()
    }

    @Test
    fun theBuildingsOfAVectorTileComeWithTheirHeights() {
        val b = VectorTile.buildings(tile()).buildings
        assertEquals(2, b.size)
        val tower = b[0]
        assertEquals(24f, tower.height)
        assertEquals(3f, tower.minHeight)
        assertEquals(8, tower.ring.size)
        assertEquals(1000 / 4096f, tower.ring[0], 1e-6f)
        assertEquals(1200 / 4096f, tower.cx, 1e-6f)
        assertEquals(VectorTile.DEFAULT_HEIGHT, b[1].height)
        // Bytes that aren't a tile give no buildings, not a crash.
        assertTrue(runCatching { VectorTile.buildings(byteArrayOf(0x1A, 0x7F)) }.isFailure || VectorTile.buildings(byteArrayOf(0x1A, 0x7F)).buildings.isEmpty())
    }

    // --- The tilted map ----------------------------------------------------------------------------

    private class Tiles(override val zoom: Int = 16) : TileSource {
        val asked = HashSet<TileKey>()
        val prefetched = HashSet<TileKey>()
        val image = BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB).also {
            it.createGraphics().apply { color = java.awt.Color(0x224466); fillRect(0, 0, 256, 256); dispose() }
        }
        override fun tile(z: Int, x: Int, y: Int): BufferedImage { asked += TileKey(z, x, y); return image }
        override fun prefetch(keys: Collection<TileKey>) { prefetched += keys }
    }

    private val notreDame = ClusterState.Position(48.8530, 2.3499, headingDeg = 90)

    private fun state(pos: ClusterState.Position = notreDame, look: ClusterState.MapView? = null) = ClusterState(
        clock = 0, page = "NAV", night = true, speedKmh = 50, position = pos, mapView = look,
        layout = ClusterState.Layout("ONE", listOf("MAP"))
    )

    @Test
    fun theTiltedMapIsTheDefaultAndDrawsTheTilesTheRouteAndTheCar() {
        val painter = Painter(1024, 600, overscanPct = 0)
        val tiles = Tiles()
        painter.tiles = tiles
        // East along the street the car is on.
        painter.route = listOf(48.8530, 2.3480, 48.8530, 2.3499, 48.8530, 2.3600)
        // By day: the tiles show as they are (by night without night tiles they are dimmed).
        val image = painter.paintCluster(state().copy(night = false), 0)
        val tilted = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        val pixels = tilted.map { it and 0xFFFFFF }.toSet()
        assertTrue(pixels.contains(0x224466))
        assertTrue(pixels.contains(0x4FC3F7))
        // Sharper tiles near the car than far away, one zoom above the flat map's at most.
        val zooms = tiles.asked.map { it.z }.toSet()
        assertTrue(zooms.size >= 2)
        assertEquals(17, zooms.max())
        // The car's own tile at the sharp zoom.
        val x17 = WebMercator.tileOf(WebMercator.x(notreDame.lon, 17))
        val y17 = WebMercator.tileOf(WebMercator.y(notreDame.lat, 17))
        assertTrue(TileKey(17, x17, y17) in tiles.asked)
        // Heading east: the tiles fetched ahead lie east of the car, never sharper than drawn.
        assertTrue(tiles.prefetched.isNotEmpty())
        assertTrue(tiles.prefetched.all { it.z <= 17 })
        assertTrue(tiles.prefetched.all { it.x >= WebMercator.tileOf(WebMercator.x(notreDame.lon, it.z)) })
        // The flat map stays on offer.
        assertFalse(painter.paintCluster(state(look = ClusterState.MapView(tilted = false)).copy(night = false), 0).let { it.getRGB(0, 0, 1024, 600, null, 0, 1024) }
            .contentEquals(tilted))
    }

    private class Blocks(val tile: BuildingTile, val at: Pair<Int, Int>) : BuildingSource {
        override val zoom = 14
        var asked = 0
        override fun buildings(x: Int, y: Int): BuildingTile? { asked++; return if (x to y == at) tile else null }
    }

    @Test
    fun buildingsStandUpAlongTheRoadAndCanBeLeftFlat() {
        // A 30 m block 150 m east of the car, beside the road.
        val bx = WebMercator.x(2.3499 + 0.0020, 14) / 256
        val by = WebMercator.y(48.8530 - 0.0003, 14) / 256
        val tx = bx.toInt()
        val ty = by.toInt()
        val fx = (bx - tx).toFloat()
        val fy = (by - ty).toFloat()
        val s = 0.004f
        val block = Building(floatArrayOf(fx, fy, fx + s, fy, fx + s, fy + s, fx, fy + s), 30f, 0f)
        val source = Blocks(BuildingTile(listOf(block)), tx to ty)
        val painter = Painter(1024, 600, overscanPct = 0)
        painter.tiles = Tiles()
        painter.buildings = source
        val flat = painter.paintCluster(state(look = ClusterState.MapView(buildings = false)), 0).let { it.getRGB(0, 0, 1024, 600, null, 0, 1024) }
        assertEquals(0, source.asked)
        val standing = painter.paintCluster(state(), 0).let { it.getRGB(0, 0, 1024, 600, null, 0, 1024) }
        assertTrue(source.asked > 0)
        // The block changes the picture above the road's horizon line: it stands up.
        val changedRows = (0 until 600).filter { y -> (0 until 1024).any { x -> flat[y * 1024 + x] != standing[y * 1024 + x] } }
        assertTrue(changedRows.isNotEmpty())
    }

    @Test
    fun betweenTwoFixesTheCarGoesOnAndThenStops() {
        val chase = ChaseMap()
        val box = Rectangle(0, 0, 400, 300)
        val canvas = BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB)
        val look = ClusterState.MapView()
        fun paint(t: Double, speed: Int?) = canvas.createGraphics().let { g ->
            chase.paint(g, box, state().copy(speedKmh = speed), notreDame, look, emptyList(), null, false, null, java.awt.Color.CYAN, true, 12, 17, (t * 1e9).toLong())
            g.dispose()
            canvas.getRGB(0, 0, 400, 300, null, 0, 400)
        }
        paint(1.0, 50)
        val a = paint(1.3, 50)
        assertTrue(chase.moving)
        val b = paint(1.6, 50)
        // The ground slides under the car between fixes.
        assertNotEquals(a.toList(), b.toList())
        // Long after the fix, with no new one, the car stops where it went.
        paint(4.0, 50)
        val c = paint(6.0, 50)
        val d = paint(8.0, 50)
        assertEquals(c.toList(), d.toList())
        // Parked: nothing moves.
        paint(9.0, 0)
        paint(12.0, 0)
        assertFalse(chase.moving)
    }

    @Test
    fun aParkedCarStaysPutWhileItsGpsWanders() {
        val chase = ChaseMap()
        val box = Rectangle(0, 0, 200, 150)
        val canvas = BufferedImage(200, 150, BufferedImage.TYPE_INT_RGB)
        fun paint(t: Double, pos: ClusterState.Position) = canvas.createGraphics().let { g ->
            chase.paint(g, box, state(pos).copy(speedKmh = 0), pos, ClusterState.MapView(), emptyList(), null, false, null, java.awt.Color.CYAN, true, 12, 17, (t * 1e9).toLong())
            g.dispose()
            canvas.getRGB(0, 0, 200, 150, null, 0, 200).toList()
        }
        paint(0.0, notreDame)
        val still = paint(2.0, notreDame)
        // A metre or two off each second, as a parked GPS goes: the same picture, nothing to draw again.
        val a = paint(3.0, notreDame.copy(lat = notreDame.lat + 0.00001))
        val b = paint(4.0, notreDame.copy(lon = notreDame.lon - 0.00002, headingDeg = 95))
        assertEquals(still, a)
        assertEquals(still, b)
        assertFalse(chase.moving)
    }

    @Test
    fun aKilometreOfRoadAsksForAFewDozenTiles() {
        // Driving east at 50 km/h, four pictures a second, on the 7-inch screen's map slot.
        val tiles = Tiles()
        val chase = ChaseMap()
        val box = Rectangle(0, 0, 640, 520)
        val canvas = BufferedImage(640, 520, BufferedImage.TYPE_INT_RGB)
        val metresPerDegree = 111_320 * Math.cos(Math.toRadians(48.853))
        var t = 0.0
        while (t < 72.0) {
            val pos = ClusterState.Position(48.853, 2.3499 + (t.toInt() * 13.9) / metresPerDegree, 90)
            val g = canvas.createGraphics()
            chase.paint(g, box, state(pos), pos, ClusterState.MapView(), emptyList(), tiles, false, null, java.awt.Color.CYAN, true, 12, 17, (t * 1e9).toLong())
            g.dispose()
            t += 0.25
        }
        val all = tiles.asked + tiles.prefetched
        println("1 km: ${all.size} tiles, " + all.groupBy { it.z }.mapValues { it.value.size })
        assertTrue("${all.size} tiles", all.size < 120)
    }
}
