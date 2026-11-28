package com.openauto.dash.companion

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.openauto.dash.roadwatch.MapTile
import com.openauto.dash.roadwatch.RoadMap
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.cos

/**
 * The map under the drawn road: OpenFreeMap's vector tiles (OpenStreetMap's
 * data, no key, the head unit's own map source), fetched as the car comes to
 * them and kept in the phone's cache. A tile is a few hundred kB, a city
 * centre's about a megabyte, and covers some two kilometres; without a
 * network the road is drawn straight, with no buildings.
 */
object RoadTiles {
    private const val TILE_JSON = "https://tiles.openfreemap.org/planet"
    private const val MAX_BYTES = 6_000_000
    /** Tiles kept read in memory, and kept on the phone. */
    private const val MAX_READ = 6
    private const val MAX_FILES = 40
    private const val RETRY_MS = 30_000L
    /** A tile is wanted once the car is this near its edge. */
    private const val MARGIN_M = 350.0

    private val fetcher = Executors.newSingleThreadExecutor()
    private val read = LinkedHashMap<Long, RoadMap>(8, 0.75f, true)
    private val busy = HashSet<Long>()
    private val failedAt = HashMap<Long, Long>()
    private var joined: Pair<Set<Long>, RoadMap>? = null
    /** Where the tiles are now: the address changes with each week's map, so it is asked for. */
    @Volatile private var template: String? = null

    /**
     * The map about [lat], [lon], as much of it as is on the phone; the rest
     * is being fetched, and comes with a later call. Null until the first
     * tile is here.
     */
    @Synchronized
    fun around(context: Context, lat: Double, lon: Double): RoadMap? {
        val tiles = 1 shl MapTile.ZOOM
        val fx = RoadMap.worldX(lon) * tiles
        val fy = RoadMap.worldY(lat) * tiles
        val margin = MARGIN_M / (RoadMap.EARTH_M * cos(Math.toRadians(lat)) / tiles)
        val wanted = LinkedHashSet<Long>()
        for (dx in -1..1) for (dy in -1..1) {
            val x = Math.floor(fx + dx * margin).toInt()
            val y = Math.floor(fy + dy * margin).toInt()
            if (x in 0 until tiles && y in 0 until tiles) wanted += key(x, y)
        }
        val now = SystemClock.elapsedRealtime()
        val app = context.applicationContext
        for (k in wanted) {
            if (k in read || k in busy || now - (failedAt[k] ?: -RETRY_MS) < RETRY_MS) continue
            busy += k
            fetcher.execute { load(app, k) }
        }
        val have = wanted.filterTo(LinkedHashSet()) { read[it] != null }
        if (have.isEmpty()) return null
        joined?.takeIf { it.first == have }?.let { return it.second }
        val maps = have.map { read.getValue(it) }
        return (if (maps.size == 1) maps[0] else RoadMap(maps.flatMap { it.roads }, maps.flatMap { it.buildings })).also { joined = have to it }
    }

    private fun key(x: Int, y: Int): Long = x.toLong() shl 32 or y.toLong()

    private fun load(context: Context, k: Long) {
        val x = (k shr 32).toInt()
        val y = k.toInt()
        val map = runCatching {
            val dir = File(context.cacheDir, "road_tiles").apply { mkdirs() }
            val file = File(dir, "${MapTile.ZOOM}_${x}_$y.pbf")
            if (!file.exists()) {
                val part = File(file.path + ".part")
                part.writeBytes(fetch(x, y))
                if (!part.renameTo(file)) throw IOException("could not keep the tile")
                dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(MAX_FILES)?.forEach { it.delete() }
            }
            MapTile.parse(file.readBytes(), x, y)
        }.onFailure { Log.w("RoadTiles", "no tile $x/$y", it) }.getOrNull()
        synchronized(this) {
            busy -= k
            if (map == null) failedAt[k] = SystemClock.elapsedRealtime() else {
                read[k] = map
                while (read.size > MAX_READ) read.remove(read.keys.first())
            }
        }
    }

    private fun fetch(x: Int, y: Int): ByteArray {
        val address = template ?: Regex("\"tiles\"\\s*:\\s*\\[\\s*\"([^\"]+)\"").find(String(get(TILE_JSON)))?.groupValues?.get(1)
            ?.takeIf { it.startsWith("https://") }?.also { template = it } ?: throw IOException("no tile address")
        return try {
            get(address.replace("{z}", MapTile.ZOOM.toString()).replace("{x}", x.toString()).replace("{y}", y.toString()))
        } catch (e: IOException) {
            // Last week's address, perhaps: asked for again next time.
            template = null
            throw e
        }
    }

    private fun get(address: String): ByteArray {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode}")
            return connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (out.size() + n > MAX_BYTES) throw IOException("bigger than a tile")
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }
}
