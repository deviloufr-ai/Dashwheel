package com.openauto.dash

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/*
 * The places the driver goes to: home, work and the last destinations, so a
 * destination is one tap or one steering wheel button away instead of typed
 * each time. Guidance itself is the navigation app's ([NavHandoff]).
 */

/** Somewhere to be guided to. [name] is what the driver typed, or the address found for a spot on the map. */
internal data class Place(val name: String, val lat: Double, val lng: Double) {
    /** The same spot, give or take the width of a street. */
    fun sameSpot(other: Place): Boolean = distanceM(other.lat, other.lng) < SAME_SPOT_M

    fun distanceM(toLat: Double, toLng: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(toLat - lat)
        val dLng = Math.toRadians(toLng - lng)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(toLat)) * Math.sin(dLng / 2) * Math.sin(dLng / 2)
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    companion object {
        const val SAME_SPOT_M = 150.0
    }
}

internal data class Places(val home: Place? = null, val work: Place? = null, val recent: List<Place> = emptyList()) {

    /**
     * With [place] as the latest destination: first of the list, once, and
     * not at all when it is home or work, which have their own buttons.
     */
    fun visited(place: Place): Places {
        if (home?.sameSpot(place) == true || work?.sameSpot(place) == true) return this
        return copy(recent = (listOf(place) + recent.filterNot { it.sameSpot(place) }).take(MAX_RECENT))
    }

    companion object {
        const val MAX_RECENT = 6
    }
}

internal object PlacesStore {
    private const val PREFS = "places"
    private const val KEY = "places"

    private val _places = MutableStateFlow(Places())
    val places: StateFlow<Places> = _places.asStateFlow()
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return
        runCatching { _places.value = decode(raw) }
    }

    fun setHome(context: Context, place: Place?) = update(context) { it.copy(home = place) }

    fun setWork(context: Context, place: Place?) = update(context) { it.copy(work = place) }

    /** Guidance was started to [place]: it heads the last destinations. */
    fun visited(context: Context, place: Place) = update(context) { it.visited(place) }

    fun forgetRecent(context: Context) = update(context) { it.copy(recent = emptyList()) }

    @Synchronized
    private fun update(context: Context, change: (Places) -> Places) {
        load(context)
        val next = change(_places.value)
        if (next == _places.value) return
        _places.value = next
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, encode(next)).apply()
    }

    internal fun encode(p: Places): String = JSONObject().apply {
        p.home?.let { put("home", json(it)) }
        p.work?.let { put("work", json(it)) }
        put("recent", JSONArray().apply { p.recent.forEach { put(json(it)) } })
    }.toString()

    internal fun decode(raw: String): Places {
        val o = JSONObject(raw)
        val recent = o.optJSONArray("recent")
        return Places(
            home = o.optJSONObject("home")?.let(::place),
            work = o.optJSONObject("work")?.let(::place),
            recent = if (recent == null) emptyList() else (0 until recent.length()).mapNotNull { i -> recent.optJSONObject(i)?.let(::place) }
        )
    }

    private fun json(p: Place) = JSONObject().put("name", p.name).put("lat", p.lat).put("lng", p.lng)

    private fun place(o: JSONObject): Place? {
        val lat = o.optDouble("lat", Double.NaN)
        val lng = o.optDouble("lng", Double.NaN)
        if (lat.isNaN() || lng.isNaN()) return null
        return Place(o.optString("name"), lat, lng)
    }
}

/** Addresses to positions and back, from OpenStreetMap's Nominatim (free, no key). Call off the main thread. */
internal object PlaceSearch {
    private const val SEARCH_URL = "https://nominatim.openstreetmap.org/search"
    private const val REVERSE_URL = "https://nominatim.openstreetmap.org/reverse"
    private const val USER_AGENT = "OpenAutoDash/1.0 (car launcher)"

    /** The first place found for [query], under the name typed; null when nothing matches. Throws when offline. */
    fun find(query: String): Place? {
        val url = "$SEARCH_URL?format=json&limit=1&q=" + URLEncoder.encode(query, "UTF-8")
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        Http.client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val arr = JsonParser.parseString(resp.body?.string() ?: return null).asJsonArray
            if (arr.size() == 0) return null
            val o = arr[0].asJsonObject
            return Place(query, o.get("lat").asString.toDouble(), o.get("lon").asString.toDouble())
        }
    }

    /** "12 Rue de Rivoli, Paris" for a spot picked on the map; null when it has no address or offline. */
    fun nameOf(lat: Double, lng: Double): String? = runCatching {
        val url = String.format(Locale.US, "%s?format=json&zoom=18&lat=%.6f&lon=%.6f", REVERSE_URL, lat, lng)
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        Http.client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            shortAddress(resp.body?.string() ?: return null)
        }
    }.getOrNull()

    /** House number and street, then the town, out of Nominatim's answer: short enough for a button. */
    internal fun shortAddress(json: String): String? {
        val a = JSONObject(json).optJSONObject("address") ?: return null
        fun first(vararg keys: String) = keys.firstNotNullOfOrNull { k -> a.optString(k).trim().takeIf { it.isNotEmpty() } }
        val street = first("road", "pedestrian", "footway", "square", "hamlet")
        val number = first("house_number")
        val town = first("city", "town", "village", "municipality", "county")
        val line = listOfNotNull(number, street).joinToString(" ")
        return listOf(line, town.orEmpty()).filter { it.isNotBlank() }.joinToString(", ").takeIf { it.isNotBlank() }
    }

    /** What to call a spot with no address: its position, as a map app writes it. */
    fun coordinates(lat: Double, lng: Double): String = String.format(Locale.US, "%.4f, %.4f", lat, lng)
}

/**
 * Hands a destination to the navigation app, which does the guidance: the app
 * the driver uses (the one the unit's NAVI key opens, on the QF firmware), else
 * Google Maps, else Waze, else whatever map app answers. Free intents, no key.
 */
internal object NavHandoff {
    const val MAPS = "com.google.android.apps.maps"
    const val WAZE = "com.waze"

    /** Which of the two is asked first: [preferred] when it is one of them and installed, else Maps, else Waze. */
    internal fun order(preferred: String?, mapsInstalled: Boolean, wazeInstalled: Boolean): List<String> {
        val installed = listOfNotNull(MAPS.takeIf { mapsInstalled }, WAZE.takeIf { wazeInstalled })
        return installed.sortedByDescending { it == preferred }
    }

    /** Starts guidance to ([lat], [lng]); false when no app on the unit can. */
    fun start(context: Context, lat: Double, lng: Double, label: String = ""): Boolean {
        KeyTargets.refresh()
        val preferred = KeyTargets.targets.value[KeyTargets.Key.NAVI]
        val apps = order(preferred, isPackageInstalled(context, MAPS), isPackageInstalled(context, WAZE))
        // The driver asked for it: the app opening full screen is not the unit's doing (EmbeddedApp, PipAnchor).
        EmbeddedApp.userActed()
        PipAnchor.noteUserTouch()
        for (app in apps) {
            val uri = if (app == WAZE) String.format(Locale.US, "waze://?ll=%.6f,%.6f&navigate=yes", lat, lng)
            else String.format(Locale.US, "google.navigation:q=%.6f,%.6f&mode=d", lat, lng)
            if (context.launchSafely(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(app))) return true
        }
        val geo = Uri.parse(String.format(Locale.US, "geo:%.6f,%.6f?q=%.6f,%.6f(%s)", lat, lng, lat, lng, Uri.encode(label)))
        return context.launchSafely(Intent(Intent.ACTION_VIEW, geo))
    }

    /** Guidance to [place], which then heads the last destinations. */
    fun start(context: Context, place: Place): Boolean {
        val started = start(context, place.lat, place.lng, place.name)
        if (started) PlacesStore.visited(context, place)
        return started
    }
}
