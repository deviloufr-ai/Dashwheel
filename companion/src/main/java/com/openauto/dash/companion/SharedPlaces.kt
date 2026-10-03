package com.openauto.dash.companion

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.Locale

/** A place shared from a map app, as far as the shared text itself tells. */
internal sealed interface SharedPlace {
    /** Ready for the car: a position, or [query] for its navigation app to look up, or both. */
    data class Found(val label: String, val lat: Double? = null, val lng: Double? = null, val query: String? = null) : SharedPlace {
        val hasPosition: Boolean get() = lat != null && lng != null
    }

    /**
     * A Google Maps short link (what the Maps app shares): only its long form
     * says where, so it is followed first ([SharedPlaces.resolve]). [fallback]
     * is the share's own text as a query, for when that fails (offline).
     */
    data class ShortLink(val url: String, val label: String, val fallback: Found?) : SharedPlace
}

/*
 * Telling a shared place from any other shared text, for "Send to car": a
 * geo: link, a map app's link (Google Maps, Waze, Apple Maps) or a bare
 * position. Pure, apart from [SharedPlaces.resolve], which follows a short
 * link's redirects.
 */
internal object SharedPlaces {
    private const val MAX_LABEL = 100
    private const val MAX_REDIRECTS = 6
    private const val TIMEOUT_MS = 4_000

    private val HOST = Regex("""^[a-z][a-z0-9+.-]*://([^/?#:@]+)""", RegexOption.IGNORE_CASE)
    private val GOOGLE = Regex("""(www\.)?google\.[a-z.]+""")
    private val URL_IN_TEXT =Regex("""(?:https?://|geo:)\S+""", RegexOption.IGNORE_CASE)
    /** A position typed or copied as text: both numbers with their decimals, or "12,5" and "19,99" (a French decimal, a price) were sent to the car as places. */
    private val LAT_LNG = Regex("""^\s*(-?\d{1,2}\.\d+)\s*,\s*(-?\d{1,3}\.\d+)\s*$""")
    /** A place's own position in a Google Maps link's data: `!3d<lat>!4d<lng>`. */
    private val PLACE_DATA = Regex("""!3d(-?\d+(?:\.\d+)?)!4d(-?\d+(?:\.\d+)?)""")
    /** Where the map was centred: `/@<lat>,<lng>,<zoom>z`. Near the place, not always on it. */
    private val VIEWPORT = Regex("""/@(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)""")
    /** `geo:0,0?q=48.85,2.29(Tour Eiffel)`. */
    private val GEO_QUERY = Regex("""^\s*(-?\d{1,2}(?:\.\d+)?)\s*,\s*(-?\d{1,3}(?:\.\d+)?)\s*(?:\((.*)\))?\s*$""")

    /** Query keys that may carry the place, most telling first. */
    private val PLACE_KEYS = listOf("destination", "daddr", "q", "query", "ll")

    /**
     * What [text] (a share's text, or a geo: link opened here) points at; null
     * when it isn't a place, to type it on the car as any other text. [subject]
     * is the share's subject, which some apps fill with the place's name.
     */
    fun parse(text: String, subject: String? = null): SharedPlace? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        LAT_LNG.matchEntire(trimmed)?.let { m ->
            val (lat, lng) = position(m.groupValues[1], m.groupValues[2]) ?: return null
            return SharedPlace.Found(trimmed, lat, lng)
        }
        val url = URL_IN_TEXT.findAll(trimmed).map { cleanUrl(it.value) }.firstOrNull { isMapLink(it) } ?: return null
        // A Maps share: the name on the first line, the address on the next, then the link.
        val lines = trimmed.replace(url, "").lines().map { it.trim() }.filter { it.isNotEmpty() && URL_IN_TEXT.find(it) == null }
        val label = (subject?.trim()?.takeIf { it.isNotEmpty() } ?: lines.firstOrNull()).orEmpty().take(MAX_LABEL)
        val query = lines.take(2).joinToString(", ").takeIf { it.isNotEmpty() }
        val fallback = query?.let { SharedPlace.Found(label.ifEmpty { it.take(MAX_LABEL) }, query = it) }
        if (isShortLink(url)) return SharedPlace.ShortLink(url, label, fallback)
        val found = fromUrl(url) ?: return fallback
        return found.copy(
            label = label.ifEmpty { found.label },
            // The share's own words when the link only has a position: the car can say them.
            query = found.query ?: query
        )
    }

    /** The place in a map app's long link or a geo: link; null when it names none. */
    fun fromUrl(url: String): SharedPlace.Found? {
        if (url.startsWith("geo:", ignoreCase = true)) return fromGeo(url)
        val params = queryParams(url)
        // The EU consent page a redirect may stop at carries the real link.
        params["continue"]?.let { next -> if (next != url) fromUrl(next)?.let { return it } }
        val path = pathOf(url)

        var lat: Double? = null
        var lng: Double? = null
        var query: String? = null
        fun take(p: Pair<Double, Double>?) {
            if (p != null && lat == null) {
                lat = p.first
                lng = p.second
            }
        }

        PLACE_DATA.find(url)?.let { take(position(it.groupValues[1], it.groupValues[2])) }
        for (key in PLACE_KEYS) {
            val value = params[key]?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val p = LAT_LNG.matchEntire(value)?.let { position(it.groupValues[1], it.groupValues[2]) }
            if (p != null) take(p) else if (key != "ll" && query == null) query = value
        }
        // /maps/place/<name or position>/..., /maps/search/<text>, /maps/dir/<from>/<to>/...
        val segments = path.split('/').filter { it.isNotEmpty() }.map(::decode)
        var name: String? = null
        val at = segments.indexOfFirst { it == "place" || it == "search" }
        if (at >= 0) segments.getOrNull(at + 1)?.takeIf { !it.startsWith("@") && !it.startsWith("data=") }?.let { name = it }
        val dir = segments.indexOf("dir")
        if (dir >= 0 && name == null) {
            name = segments.drop(dir + 1).takeWhile { !it.startsWith("@") && !it.startsWith("data=") }.lastOrNull { it.isNotBlank() }
        }
        name?.let { n ->
            val p = LAT_LNG.matchEntire(n)?.let { position(it.groupValues[1], it.groupValues[2]) }
            if (p != null) {
                take(p)
                name = null
            } else if (query == null) {
                query = n
            }
        }
        // The map's centre is the place on a place's page; on a route or a search it is only the area shown.
        val onPlace = at >= 0 && segments[at] == "place"
        if (query == null || onPlace) VIEWPORT.find(path)?.let { take(position(it.groupValues[1], it.groupValues[2])) }

        if (lat == null && query == null) return null
        val label = (name ?: query ?: coordinates(lat!!, lng!!)).take(MAX_LABEL)
        return SharedPlace.Found(label, lat, lng, query)
    }

    /** `geo:lat,lng`, `geo:lat,lng?q=...`, `geo:0,0?q=an address` or `geo:0,0?q=lat,lng(Label)`. */
    private fun fromGeo(url: String): SharedPlace.Found? {
        val body = url.substringAfter(':')
        val at = body.substringBefore('?').substringBefore(';')
        val q = queryParams(url)["q"]?.trim()?.takeIf { it.isNotEmpty() }
        q?.let { GEO_QUERY.matchEntire(it) }?.let { m ->
            val p = position(m.groupValues[1], m.groupValues[2])
            if (p != null) {
                val label = m.groupValues[3].trim().ifEmpty { coordinates(p.first, p.second) }
                return SharedPlace.Found(label.take(MAX_LABEL), p.first, p.second)
            }
        }
        val p = LAT_LNG.matchEntire(at)?.let { position(it.groupValues[1], it.groupValues[2]) }
        return when {
            q != null -> SharedPlace.Found(q.take(MAX_LABEL), p?.first, p?.second, q)
            p != null -> SharedPlace.Found(coordinates(p.first, p.second), p.first, p.second)
            else -> null
        }
    }

    /** Links a map app shares. */
    fun isMapLink(url: String): Boolean {
        if (url.startsWith("geo:", ignoreCase = true)) return true
        val host = hostOf(url) ?: return false
        val path = pathOf(url)
        return isShortLink(url) ||
            host.startsWith("maps.google.") ||
            (host.matches(GOOGLE) && path.startsWith("/maps")) ||
            host == "waze.com" || host.endsWith(".waze.com") ||
            host == "maps.apple.com"
    }

    /** Maps' short links, which say where only once followed. */
    fun isShortLink(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return host == "maps.app.goo.gl" || (host == "goo.gl" && pathOf(url).startsWith("/maps"))
    }

    /**
     * Follows [link] from one redirect to the next (not loading any page) until
     * one names the place. Blocking, with short timeouts: call off the main thread.
     */
    fun resolve(link: SharedPlace.ShortLink): SharedPlace.Found? {
        var url = link.url
        repeat(MAX_REDIRECTS) {
            val next = runCatching { redirectOf(url) }.getOrNull() ?: return null
            fromUrl(next)?.let { found ->
                return found.copy(label = link.label.ifEmpty { found.label }, query = found.query ?: link.fallback?.query)
            }
            url = next
        }
        return null
    }

    private fun redirectOf(url: String): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = "GET"
            if (connection.responseCode !in 300..399) return null
            connection.getHeaderField("Location")?.let { URL(URL(url), it).toString() }
        } finally {
            connection.disconnect()
        }
    }

    // ---- Small helpers ----

    /** A link as written in a sentence: without the full stop after it, nor a bracket closing around it. */
    private fun cleanUrl(raw: String): String {
        var url = raw.trimEnd('.', ',', ';')
        while (url.endsWith(')') && url.count { it == ')' } > url.count { it == '(' }) url = url.dropLast(1).trimEnd('.', ',', ';')
        return url
    }

    /** A position that can be one; (0, 0) is what an app writes when it has none. */
    private fun position(lat: String, lng: String): Pair<Double, Double>? {
        val a = lat.toDoubleOrNull() ?: return null
        val b = lng.toDoubleOrNull() ?: return null
        if (a !in -90.0..90.0 || b !in -180.0..180.0 || (a == 0.0 && b == 0.0)) return null
        return a to b
    }

    private fun coordinates(lat: Double, lng: Double): String = String.format(Locale.US, "%.5f, %.5f", lat, lng)

    // A regex rather than URI: map links carry characters URI refuses (|, unescaped accents...).
    private fun hostOf(url: String): String? =
        HOST.find(url)?.groupValues?.get(1)?.lowercase(Locale.ROOT)

    private fun pathOf(url: String): String =
        url.substringAfter("://", "").substringAfter('/', "").substringBefore('?').substringBefore('#').let { "/$it" }

    private fun queryParams(url: String): Map<String, String> {
        val query = url.substringAfter('?', "").substringBefore('#')
        if (query.isEmpty()) return emptyMap()
        return query.split('&').mapNotNull { part ->
            val key = part.substringBefore('=').lowercase(Locale.ROOT)
            if (key.isEmpty() || !part.contains('=')) null else key to decode(part.substringAfter('='))
        }.toMap()
    }

    private fun decode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}
