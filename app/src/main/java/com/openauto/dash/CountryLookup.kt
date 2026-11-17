package com.openauto.dash

import android.util.Log
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale

/**
 * Which country a position is in, as a lower-case ISO code ("fr", "de"),
 * from OpenStreetMap's Nominatim (free, no key, one request a second at
 * most). The answer is kept per cell of a tenth of a degree, about 10 km,
 * so a drive asks a few times an hour, not at every fix. Null when the
 * lookup fails: the caller falls back to what it did before.
 */
internal object CountryLookup {
    private const val TAG = "CountryLookup"
    private const val REVERSE = "https://nominatim.openstreetmap.org/reverse"
    private const val CELLS_KEPT = 64

    /** Per cell: the country code and the province's ISO 3166-2 code ("ES-B"), or the region's where it has one province. */
    private val cache = object : LinkedHashMap<Pair<Int, Int>, Pair<String, String?>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Int, Int>, Pair<String, String?>>?) = size > CELLS_KEPT
    }

    /** The country at ([lat], [lng]); blocking, from an IO thread. */
    fun code(lat: Double, lng: Double): String? = place(lat, lng)?.first

    /** The province at ([lat], [lng]) as an ISO 3166-2 code ("ES-M"), null when unknown; blocking. */
    fun region(lat: Double, lng: Double): String? = place(lat, lng)?.second

    private fun place(lat: Double, lng: Double): Pair<String, String?>? {
        val key = cell(lat, lng)
        synchronized(cache) { cache[key]?.let { return it } }
        // Zoom 8 = province level: the same one request also names the province (Spain's fuel feed is per province).
        val url = String.format(Locale.US, "%s?format=json&zoom=8&lat=%.4f&lon=%.4f", REVERSE, lat, lng)
        val place = runCatching {
            val request = Request.Builder().url(url).header("User-Agent", OVERPASS_AGENT).build()
            Http.client.newCall(request).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                val address = JSONObject(r.body?.string() ?: "{}").optJSONObject("address") ?: JSONObject()
                val region = address.optString("ISO3166-2-lvl6").ifBlank { address.optString("ISO3166-2-lvl4") }
                address.optString("country_code").lowercase(Locale.ROOT) to region.ifBlank { null }
            }
        }.onFailure { Log.w(TAG, "country unknown at %.3f,%.3f: ${it.message}".format(Locale.US, lat, lng)) }
            .getOrNull()?.takeIf { it.first.isNotBlank() } ?: return null
        synchronized(cache) { cache[key] = place }
        return place
    }

    /** The cell a position falls in: a tenth of a degree each way. */
    internal fun cell(lat: Double, lng: Double): Pair<Int, Int> = (lat * 10).toInt() to (lng * 10).toInt()
}
