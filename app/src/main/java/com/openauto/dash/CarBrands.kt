package com.openauto.dash

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** A car make, with the file name ([slug]) its logos go by in the logo dataset. */
internal data class CarBrand(val name: String, val slug: String) {
    /** Lower case without accents, so "citroen" finds "Citroën". */
    val key: String = searchKey(name)
}

internal fun searchKey(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(ACCENTS, "").lowercase()

// Compiled once: every brand's key is built with it when the picker opens.
private val ACCENTS = Regex("\\p{Mn}+")

/**
 * Every car make, from `assets/car_brands.json` (names and slugs of the
 * open car-logos-dataset on GitHub). Only the list ships with the app; the
 * logos themselves are downloaded when shown ([CarLogos]).
 */
internal object CarBrands {
    @Volatile private var cached: List<CarBrand>? = null

    fun all(context: Context): List<CarBrand> = cached ?: run {
        val json = context.assets.open("car_brands.json").bufferedReader().use { it.readText() }
        val array = JSONArray(json)
        List(array.length()) { i ->
            val o = array.getJSONObject(i)
            CarBrand(o.getString("name"), o.getString("slug"))
        }.sortedBy { it.key }.also { cached = it }
    }

    fun find(context: Context, slug: String?): CarBrand? = slug?.let { s -> all(context).firstOrNull { it.slug == s } }

    fun search(brands: List<CarBrand>, query: String): List<CarBrand> {
        val q = searchKey(query.trim())
        if (q.isEmpty()) return brands
        // Brands starting with the query first, then any that contain it.
        val (starts, contains) = brands.filter { q in it.key }.partition { it.key.startsWith(q) }
        return starts + contains
    }
}

/**
 * Downloads car logos (transparent PNGs) and keeps them on disk, so a brand's
 * logo only comes over the network once. Thumbnails (256 px) for the picker,
 * the large version (up to 2048 px) for the boot animation.
 */
internal object CarLogos {
    private const val BASE = "https://raw.githubusercontent.com/filippofilip95/car-logos-dataset/master/logos"

    private val client by lazy {
        Http.client.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    private val thumbs = LruCache<String, Bitmap>(120)
    // A scrolling grid asks for dozens at once; a few downloads at a time is plenty.
    private val gate = Semaphore(4)
    /**
     * Logos that could not be fetched, by path, with when (elapsed realtime):
     * a brand the dataset lacks, or no network. Without this, every cell of
     * the picker asked again at every scroll, offline too.
     */
    private val failedAt = ConcurrentHashMap<String, Long>()
    private const val RETRY_MS = 5 * 60_000L

    fun cachedThumb(slug: String): Bitmap? = thumbs.get(slug)

    suspend fun thumb(context: Context, slug: String): Bitmap? {
        thumbs.get(slug)?.let { return it }
        return load(context, "thumb", slug, maxPx = 256)?.also { thumbs.put(slug, it) }
    }

    /** The large logo, scaled down to at most [maxPx] on its longer side. */
    suspend fun full(context: Context, slug: String, maxPx: Int): Bitmap? = load(context, "optimized", slug, maxPx)

    private suspend fun load(context: Context, size: String, slug: String, maxPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, "car_logos/$size/$slug.png")
        if (!file.exists()) {
            val path = "$size/$slug"
            val failed = failedAt[path]
            if (failed == null || SystemClock.elapsedRealtime() - failed >= RETRY_MS) {
                gate.withPermit {
                    runCatching { download("$BASE/$path.png", file) }
                        .onSuccess { failedAt.remove(path) }
                        .onFailure { failedAt[path] = SystemClock.elapsedRealtime() }
                }
            }
        }
        if (file.exists()) decode(file, maxPx) else null
    }

    private fun download(url: String, file: File) {
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code}" }
            file.parentFile?.mkdirs()
            // Two tiles may fetch the same logo at once: each writes its own file,
            // and the finished one is moved into place whole.
            val tmp = File.createTempFile(file.name, ".part", file.parentFile)
            try {
                tmp.outputStream().use { out -> resp.body!!.byteStream().copyTo(out) }
                tmp.renameTo(file)
            } finally {
                tmp.delete()
            }
        }
    }

    private fun decode(file: File, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) {
            file.delete() // a broken download; try again next time
            return null
        }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
