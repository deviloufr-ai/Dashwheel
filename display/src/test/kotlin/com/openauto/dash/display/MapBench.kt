package com.openauto.dash.display

import com.openauto.dash.link.ClusterState
import java.io.File

/**
 * How long the tilted map takes on the board itself, from the tiles already
 * in its cache (nothing fetched): run on the Pi with the display's jars and
 * the test classes on the class path, e.g.
 * `java -cp <the lib jars>:bench com.openauto.dash.display.MapBenchKt /var/cache/dashwheel-display/tiles 49.5306 1.9940 90`.
 */
fun main(args: Array<String>) {
    System.setProperty("java.awt.headless", "true")
    val cache = File(args.getOrElse(0) { DisplayConfig.DEFAULT_TILE_CACHE })
    val lat = args.getOrNull(1)?.toDouble() ?: 49.5306
    val lon = args.getOrNull(2)?.toDouble() ?: 1.9940
    val heading = args.getOrNull(3)?.toInt() ?: 90
    val width = args.getOrNull(4)?.toInt() ?: 1024
    val height = args.getOrNull(5)?.toInt() ?: 600
    val painter = Painter(width, height, overscanPct = 0)
    val offline: (String, String) -> ByteArray? = { _, _ -> null }
    val night = File(cache, "night").takeIf { it.isDirectory } ?: File(cache, "day")
    painter.tiles = TileCache(night, "http://offline/{z}/{x}/{y}.png", 16, "bench", onLoaded = {}, fetch = offline)
    painter.buildings = BuildingCache(File(cache, "buildings"), "http://offline/{z}/{x}/{y}.pbf", "bench", onLoaded = {}, fetch = offline)
    val base = ClusterState(
        clock = 0, page = "NAV", night = true, speedKmh = 50,
        nav = ClusterState.Nav("Tournez à droite", "250 m", "", "18:42"), maneuver = "RIGHT",
        layout = ClusterState.Layout("BIG_STACK", listOf("MAP", "SPEED", "CLOCK"))
    )
    val metresPerDegree = 111_320 * Math.cos(Math.toRadians(lat))
    // A new fix (and speed) every fourth picture, as a 1 Hz GPS at 4 pictures a second: the others only move the map.
    fun frame(i: Int): Pair<Long, Long> {
        val fix = i / 4
        val pos = ClusterState.Position(lat, lon + fix * 14.0 * Math.sin(Math.toRadians(heading.toDouble())) / metresPerDegree, heading)
        val t0 = System.nanoTime()
        painter.paintCluster(base.copy(position = pos, speedKmh = 48 + fix % 5), 0)
        val t1 = System.nanoTime()
        painter.bgrx()
        return (t1 - t0) to (System.nanoTime() - t1)
    }
    // Warm up (the JIT, the tiles read from the card), then measure.
    repeat(40) { frame(it) }
    painter.takeMapStats()
    var paint = 0L
    var convert = 0L
    var full = 0L
    var mapOnly = 0L
    val n = 60
    repeat(n) { i ->
        val (p, c) = frame(40 + i)
        paint += p; convert += c
        if ((40 + i) % 4 == 0) full += p else mapOnly += p
    }
    println("picture ${paint / n / 1_000_000} ms (a new reading ${full / (n / 4) / 1_000_000} ms, the map only ${mapOnly / (n - n / 4) / 1_000_000} ms), to bytes ${convert / n / 1_000_000} ms")
    println("map: ${painter.takeMapStats()}")
}
