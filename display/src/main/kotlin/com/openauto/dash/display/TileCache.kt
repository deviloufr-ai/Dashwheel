package com.openauto.dash.display

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.imageio.ImageIO

/** Where the map's tiles come from, as the Painter sees it. */
interface TileSource {
    val zoom: Int

    /** The tile, if it is at hand; null while it is being fetched (the source redraws the screen once it is). */
    fun tile(z: Int, x: Int, y: Int): BufferedImage?

    /** Tiles wanted soon, in order of need. */
    fun prefetch(keys: Collection<TileKey>)
}

data class TileKey(val z: Int, val x: Int, val y: Int)

/**
 * Raster map tiles for the display's own map: fetched from [urlTemplate]
 * ({z}, {x}, {y}) over whatever network the Pi has (the phone's hotspot),
 * kept decoded in memory for the tiles on screen, and on the card under
 * [dir] for the next drive (on a read-only card that folder is in memory
 * too, and starts empty at each power-up). A tile that can't be fetched is
 * left alone for a minute, so a Pi without internet doesn't hammer the link.
 */
class TileCache(
    private val dir: File?,
    private val urlTemplate: String,
    override val zoom: Int,
    private val userAgent: String,
    /** A tile arrived: the picture is worth drawing again. */
    private val onLoaded: () -> Unit,
    private val fetch: (String, String) -> ByteArray? = ::download,
    workers: Int = 2
) : TileSource {
    private val lock = Object()
    private val memory = object : LinkedHashMap<TileKey, BufferedImage>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TileKey, BufferedImage>?): Boolean = size > MEMORY_TILES
    }
    private val queue = LinkedHashSet<TileKey>()
    private val fetching = HashSet<TileKey>()
    private val failedUntil = HashMap<TileKey, Long>()
    private var writes = 0

    init {
        dir?.let { runCatching { it.mkdirs() } }
        repeat(workers) { i -> Thread({ work() }, "tile-fetch-$i").apply { isDaemon = true; start() } }
    }

    override fun tile(z: Int, x: Int, y: Int): BufferedImage? {
        val key = wrap(z, x, y) ?: return null
        synchronized(lock) {
            memory[key]?.let { return it }
        }
        // On the card from an earlier drive: decoded on the drawing thread, a few milliseconds.
        fileOf(key)?.takeIf { it.isFile }?.let { file ->
            val image = runCatching { ImageIO.read(file) }.getOrNull()
            if (image != null) {
                synchronized(lock) { memory[key] = image }
                return image
            }
            runCatching { file.delete() }
        }
        enqueue(listOf(key), first = true)
        return null
    }

    override fun prefetch(keys: Collection<TileKey>) {
        enqueue(keys.mapNotNull { wrap(it.z, it.x, it.y) }, first = false)
    }

    /** Queued for fetching, unless at hand, being fetched, or recently refused. */
    private fun enqueue(keys: List<TileKey>, first: Boolean) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val wanted = keys.filter { it !in memory && it !in fetching && (failedUntil[it] ?: 0) < now && fileOf(it)?.isFile != true }
            if (wanted.isEmpty()) return
            if (first) {
                val rest = queue.toList()
                queue.clear()
                queue += wanted
                queue += rest
            } else {
                queue += wanted
            }
            while (queue.size > MAX_QUEUE) queue.remove(queue.last())
            lock.notifyAll()
        }
    }

    private fun work() {
        while (true) {
            val key = synchronized(lock) {
                while (queue.isEmpty()) {
                    try {
                        lock.wait()
                    } catch (_: InterruptedException) {
                        return
                    }
                }
                queue.first().also { queue.remove(it); fetching += it }
            }
            val bytes = runCatching { fetch(urlFor(key), userAgent) }.getOrNull()
            val image = bytes?.let { runCatching { ImageIO.read(ByteArrayInputStream(it)) }.getOrNull() }
            synchronized(lock) {
                fetching -= key
                if (image == null) {
                    failedUntil[key] = System.currentTimeMillis() + RETRY_MS
                } else {
                    memory[key] = image
                }
            }
            if (image != null) {
                bytes.let { save(key, it) }
                onLoaded()
            }
        }
    }

    private fun save(key: TileKey, bytes: ByteArray) {
        val file = fileOf(key) ?: return
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".part")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) tmp.delete()
        }
        synchronized(lock) {
            if (++writes % PRUNE_EVERY != 0) return
        }
        prune()
    }

    /** The oldest tiles go when the card holds more than [MAX_FILES]. */
    private fun prune() {
        val root = dir ?: return
        val files = runCatching { root.walkTopDown().filter { it.isFile && it.name.endsWith(".png") }.toList() }.getOrDefault(emptyList())
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_FILES).forEach { runCatching { it.delete() } }
    }

    private fun fileOf(key: TileKey): File? = dir?.let { File(it, "${key.z}/${key.x}/${key.y}.png") }

    internal fun urlFor(key: TileKey): String =
        urlTemplate.replace("{z}", key.z.toString()).replace("{x}", key.x.toString()).replace("{y}", key.y.toString())

    /** [x] wrapped round the world; null for a row outside it. */
    private fun wrap(z: Int, x: Int, y: Int): TileKey? {
        val n = 1 shl z
        if (y < 0 || y >= n) return null
        return TileKey(z, Math.floorMod(x, n), y)
    }

    companion object {
        /** Decoded tiles kept in memory: a 1024×600 screen shows about 20, with a ring around them. */
        const val MEMORY_TILES = 96
        const val MAX_QUEUE = 120
        /** Tiles on the card at most, about 10 KB each: a region of a few thousand square kilometres. */
        const val MAX_FILES = 20_000
        const val PRUNE_EVERY = 200
        const val RETRY_MS = 60_000L
        private const val TIMEOUT_MS = 6_000
        private const val MAX_BYTES = 1_000_000

        /** The tile's bytes, or null when the server (or the network) says no. */
        fun download(url: String, userAgent: String): ByteArray? {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", userAgent)
                if (connection.responseCode != 200) return null
                return connection.inputStream.use { it.readNBytes(MAX_BYTES) }
            } finally {
                connection.disconnect()
            }
        }
    }
}
