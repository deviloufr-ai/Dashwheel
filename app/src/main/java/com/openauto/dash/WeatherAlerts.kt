package com.openauto.dash

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import org.json.JSONObject
import java.text.Normalizer
import java.time.OffsetDateTime
import java.util.Locale

/**
 * A weather warning in force somewhere: [level] 1 yellow, 2 orange, 3 red;
 * [hazard] MeteoAlarm's type in lower case ("thunderstorm", "rain-flood"),
 * [event] its English wording, for a type this build doesn't name.
 */
internal data class WeatherWarning(val area: String, val hazard: String, val event: String, val level: Int, val onset: Long, val expires: Long)

internal object WeatherWarnings {
    private val entry = Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL)
    private val titleForm = Regex("^(Yellow|Orange|Red) (.+?) Warning", RegexOption.IGNORE_CASE)

    private fun tag(xml: String, name: String): String? =
        Regex("<$name[^>]*>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.trim()

    private fun time(text: String?): Long = text?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() } ?: 0L

    /** MeteoAlarm's Atom feed: the warnings above green, past ones left out. */
    fun parse(xml: String, now: Long): List<WeatherWarning> = entry.findAll(xml).mapNotNull { m ->
        val e = m.groupValues[1]
        // "Orange Thunderstorm Warning issued for France - Drôme": the colour is the level.
        val title = titleForm.find(tag(e, "title").orEmpty())
        val level = when (title?.groupValues?.get(1)?.lowercase() ?: tag(e, "cap:severity")?.lowercase()) {
            "yellow", "moderate" -> 1
            "orange", "severe" -> 2
            "red", "extreme" -> 3
            else -> return@mapNotNull null
        }
        val area = tag(e, "cap:areaDesc") ?: return@mapNotNull null
        val expires = time(tag(e, "cap:expires"))
        if (expires in 1 until now) return@mapNotNull null
        val event = tag(e, "cap:event") ?: tag(e, "title") ?: ""
        WeatherWarning(area, title?.groupValues?.get(2)?.lowercase(Locale.ROOT).orEmpty(), event, level, time(tag(e, "cap:onset")), expires)
    }.toList()

    /** "Drôme" and "drome", "Landkreis Kassel" and "Kassel" are the same place. */
    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), " ").trim()

    /** Words areas carry in one list and not the other ("Landkreis" here, "Kreis" there). */
    private val generic = setOf("landkreis", "kreis", "stadt", "kreisfreie", "provincia", "province", "provincie", "departement", "de", "du", "di", "del")

    private fun key(s: String): String = normalize(s).split(' ').filter { it !in generic }.joinToString(" ")

    fun inArea(warning: WeatherWarning, places: Collection<String>): Boolean {
        val a = key(warning.area)
        return a.isNotEmpty() && places.any { key(it) == a }
    }

    /** MeteoAlarm's feed name for a country (its ISO code), null for one it does not cover. */
    fun feedFor(countryCode: String): String? = mapOf(
        "fr" to "france", "be" to "belgium", "de" to "germany", "es" to "spain", "it" to "italy", "pt" to "portugal",
        "nl" to "netherlands", "lu" to "luxembourg", "ch" to "switzerland", "at" to "austria", "pl" to "poland",
        "ie" to "ireland", "dk" to "denmark", "cz" to "czechia", "si" to "slovenia", "hr" to "croatia", "gr" to "greece"
    )[countryCode.lowercase(Locale.ROOT)]
}

/**
 * Weather warnings (MeteoAlarm, which carries Météo-France's vigilance) where
 * the car is and where the guidance is taking it. Read every [REFRESH_MS];
 * a new orange or red one is said once.
 */
internal object WeatherAlerts {
    private const val TAG = "WeatherAlerts"
    private const val REFRESH_MS = 30 * 60_000L
    private const val FEED = "https://feeds.meteoalarm.org/feeds/meteoalarm-legacy-atom-"
    private const val REVERSE = "https://nominatim.openstreetmap.org/reverse"

    /** One place looked up: its country and the names its warnings may go by (department, region). */
    private data class Area(val country: String, val names: List<String>)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var users = 0
    private val said = HashSet<String>()
    private var areaCache: Pair<Pair<Int, Int>, Area>? = null

    private val _here = MutableStateFlow<List<WeatherWarning>>(emptyList())
    /** In force where the car is, the worst first. */
    val here: StateFlow<List<WeatherWarning>> = _here
    private val _ahead = MutableStateFlow<List<WeatherWarning>>(emptyList())
    /** In force at the destination being guided to (none without one). */
    val ahead: StateFlow<List<WeatherWarning>> = _ahead

    @Synchronized
    fun start(context: Context) {
        users++
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch {
            while (isActive) {
                runCatching { refresh(app) }.onFailure { Log.w(TAG, "warnings unread: ${it.message}") }
                delay(REFRESH_MS)
            }
        }
    }

    @Synchronized
    fun stop() {
        if (users == 0 || --users > 0) return
        job?.cancel()
        job = null
    }

    private fun refresh(context: Context) {
        val loc = LocationFeed.location.value ?: return
        val hereArea = area(loc.latitude, loc.longitude, cache = true) ?: return
        val dest = InAppNav.guidance.value?.destination
        val destArea = dest?.let { area(it.latitude(), it.longitude(), cache = false) }
        val feeds = HashMap<String, List<WeatherWarning>>()
        fun warnings(a: Area): List<WeatherWarning> = feeds.getOrPut(a.country) {
            val slug = WeatherWarnings.feedFor(a.country) ?: return@getOrPut emptyList()
            val request = Request.Builder().url(FEED + slug).header("User-Agent", OVERPASS_AGENT).build()
            Http.client.newCall(request).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                WeatherWarnings.parse(r.body?.string().orEmpty(), System.currentTimeMillis())
            }
        }
        fun worst(list: List<WeatherWarning>) = list.sortedByDescending { it.level }.distinctBy { it.hazard to it.area }
        val now = worst(warnings(hereArea).filter { WeatherWarnings.inArea(it, hereArea.names) })
        _here.value = now
        _ahead.value = destArea?.let { a -> worst(warnings(a).filter { WeatherWarnings.inArea(it, a.names) }) }.orEmpty()
        (now + _ahead.value).filter { it.level >= 2 }.forEach { w ->
            if (said.add("${w.area}|${w.event}|${w.level}|${w.onset}")) {
                val app = AppLanguage.wrap(context)
                val colour = app.getString(if (w.level >= 3) R.string.widgets_wx_red else R.string.widgets_wx_orange)
                CarVoice.speak(app.getString(R.string.widgets_wx_say, colour, hazardName(app, w), w.area), InAppNav.locale(context))
            }
        }
    }

    /** The country and area names at a point; the car's own is kept while it stays within about 10 km. */
    private fun area(lat: Double, lng: Double, cache: Boolean): Area? {
        val key = (lat * 10).toInt() to (lng * 10).toInt()
        if (cache) areaCache?.takeIf { it.first == key }?.let { return it.second }
        val url = String.format(Locale.US, "%s?format=json&zoom=8&lat=%.4f&lon=%.4f", REVERSE, lat, lng)
        val request = Request.Builder().url(url).header("User-Agent", OVERPASS_AGENT).build()
        val a = Http.client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) return null
            JSONObject(r.body?.string() ?: "{}").optJSONObject("address") ?: return null
        }
        val names = listOf("county", "state_district", "state", "region").mapNotNull { k -> a.optString(k).takeIf { it.isNotBlank() } }
        val area = Area(a.optString("country_code"), names)
        if (cache) areaCache = key to area
        return area
    }
}

/** The warning's hazard in the driver's language. */
internal fun hazardName(context: Context, w: WeatherWarning): String {
    val res = when (w.hazard) {
        "wind" -> R.string.widgets_wx_h_wind
        "snow-ice", "snow" -> R.string.widgets_wx_h_snow
        "thunderstorm", "thunderstorms" -> R.string.widgets_wx_h_storm
        "fog" -> R.string.widgets_wx_h_fog
        "high-temperature", "extreme high temperature" -> R.string.widgets_wx_h_heat
        "low-temperature", "extreme low temperature" -> R.string.widgets_wx_h_cold
        "coastal event", "coastalevent" -> R.string.widgets_wx_h_coast
        "forest fire", "forest-fire" -> R.string.widgets_wx_h_fire
        "avalanches", "avalanche" -> R.string.widgets_wx_h_avalanche
        "rain" -> R.string.widgets_wx_h_rain
        "flooding", "flood", "rain-flood" -> R.string.widgets_wx_h_flood
        else -> return w.event
    }
    return context.getString(res)
}

private fun levelColor(level: Int): Color = when (level) {
    3 -> Color(0xFFE53935)
    2 -> Color(0xFFFB8C00)
    else -> Color(0xFFFDD835)
}

@Composable
internal fun WeatherAlertsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    DisposableEffect(Unit) { WeatherAlerts.start(context); onDispose { WeatherAlerts.stop() } }
    val here by WeatherAlerts.here.collectAsState()
    val ahead by WeatherAlerts.ahead.collectAsState()
    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(DashSpace.Lg), verticalArrangement = Arrangement.spacedBy(DashSpace.Sm)) {
            TileHeader(stringResource(R.string.widgets_wx))
            if (here.isEmpty() && ahead.isEmpty()) {
                Text(stringResource(R.string.widgets_wx_none), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            here.take(3).forEach { WarningRow(it) }
            if (ahead.isNotEmpty()) {
                Text(stringResource(R.string.widgets_wx_ahead), color = DashColors.Muted, style = MaterialTheme.typography.labelSmall)
                ahead.take(2).forEach { WarningRow(it) }
            }
        }
    }
}

@Composable
private fun WarningRow(w: WeatherWarning) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DashSpace.Sm)) {
        Box(Modifier.size(12.dp).background(levelColor(w.level), CircleShape))
        Text(
            hazardName(LocalContext.current, w), color = DashColors.TextPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
        )
        Text(w.area, color = DashColors.TextSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
internal fun weatherAlertsFace(): WidgetFace {
    val context = LocalContext.current
    DisposableEffect(Unit) { WeatherAlerts.start(context); onDispose { WeatherAlerts.stop() } }
    val here by WeatherAlerts.here.collectAsState()
    val ahead by WeatherAlerts.ahead.collectAsState()
    val top = (here + ahead).maxByOrNull { it.level }
    return WidgetFace(
        icon = Icons.Filled.Warning,
        title = BuiltinKind.WEATHER_ALERTS.label,
        value = top?.let { hazardName(context, it) } ?: stringResource(R.string.widgets_wx_none),
        textValue = true,
        caption = top?.area ?: "",
        alert = top != null && top.level >= 2,
        rows = (here + ahead).take(4).map { FaceRow(hazardName(context, it), it.area, alert = it.level >= 2) }
    )
}
