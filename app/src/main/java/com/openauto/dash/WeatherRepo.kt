package com.openauto.dash

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Current conditions at the car, from Open-Meteo (free, no key, no account). */
data class Weather(
    val tempC: Double,
    val feelsC: Double,
    val code: Int,
    val windKmh: Double,
    val hiC: Double,
    val loC: Double,
    val fetchedAt: Long,
    /** Start of the next wet hour in the coming [RAIN_HORIZON_H] hours (now's hour if it rains already); null if dry. */
    val rainFromMs: Long? = null,
    /** End of that wet spell. */
    val rainUntilMs: Long? = null
) {
    /** Plain-language condition for a WMO weather code, as a string resource. */
    @get:StringRes
    val conditionRes: Int
        get() = when (code) {
            0 -> R.string.info_wx_clear
            1 -> R.string.info_wx_mostly_clear
            2 -> R.string.info_wx_partly_cloudy
            3 -> R.string.info_wx_overcast
            45, 48 -> R.string.info_wx_fog
            51, 53, 55 -> R.string.info_wx_drizzle
            56, 57 -> R.string.info_wx_freezing_drizzle
            61, 63, 65 -> R.string.info_wx_rain
            66, 67 -> R.string.info_wx_freezing_rain
            71, 73, 75, 77 -> R.string.info_wx_snow
            80, 81, 82 -> R.string.info_wx_showers
            85, 86 -> R.string.info_wx_snow_showers
            95 -> R.string.info_wx_thunderstorm
            96, 99 -> R.string.info_wx_hail_storm
            else -> R.string.info_wx_unknown
        }

    /** [conditionRes] in the current UI language (read from composables). */
    val condition: String
        @Composable get() = stringResource(conditionRes)
}

object WeatherRepo {
    private val _weather = MutableStateFlow<Weather?>(null)
    val weather: StateFlow<Weather?> = _weather

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val client by lazy {
        Http.client.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private var lastLat = Double.NaN
    private var lastLng = Double.NaN
    private var lastFetch = 0L

    // The real answer, kept while the demo shows its own and put back when it ends.
    @Volatile private var realWeather: Weather? = null
    @Volatile private var realError: String? = null

    /** One refresh interval: an answer older than this is shown with its time. */
    internal const val REFRESH_MS = 15 * 60_000L
    private const val MOVE_DEG = 0.05   // ~5 km: refresh sooner when the car has moved on

    /** [DemoMode]'s weather. */
    internal fun demoWrite(weather: Weather?, error: String?) {
        _weather.value = weather
        _error.value = error
    }

    /** The demo is over: the real weather back, including an answer that came in meanwhile. */
    internal fun endDemo() {
        _weather.value = realWeather
        _error.value = realError
    }

    private fun publish(weather: Weather?, error: String?) {
        realWeather = weather
        realError = error
        // A fetch that was already on its way when the demo started must not replace the demo's weather.
        if (DemoMode.isOn) return
        _weather.value = weather
        _error.value = error
    }

    /** Fetches when the last result is stale or the car has moved; cheap to call often. */
    suspend fun refresh(lat: Double, lng: Double, force: Boolean = false) {
        // The demo's position is made up; asking about it would only overwrite its weather.
        if (DemoMode.isOn) return
        val now = System.currentTimeMillis()
        val moved = abs(lat - lastLat) > MOVE_DEG || abs(lng - lastLng) > MOVE_DEG
        if (!force && !moved && now - lastFetch < REFRESH_MS) return
        lastFetch = now
        lastLat = lat
        lastLng = lng

        val url = String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m" +
                "&daily=temperature_2m_max,temperature_2m_min" +
                "&hourly=precipitation,precipitation_probability&timezone=auto&forecast_days=2",
            lat, lng
        )
        withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val json = JSONObject(resp.body?.string().orEmpty())
                    val cur = json.getJSONObject("current")
                    val daily = json.getJSONObject("daily")
                    val rain = json.optJSONObject("hourly")?.let { rainSpell(it, json.optInt("utc_offset_seconds", 0), now) }
                    Weather(
                        tempC = cur.getDouble("temperature_2m"),
                        feelsC = cur.optDouble("apparent_temperature", cur.getDouble("temperature_2m")),
                        code = cur.optInt("weather_code", -1),
                        windKmh = cur.optDouble("wind_speed_10m", 0.0),
                        hiC = daily.getJSONArray("temperature_2m_max").optDouble(0, Double.NaN),
                        loC = daily.getJSONArray("temperature_2m_min").optDouble(0, Double.NaN),
                        fetchedAt = now,
                        rainFromMs = rain?.first,
                        rainUntilMs = rain?.second
                    )
                }
            }.onSuccess {
                publish(it, null)
            }.onFailure {
                // Technical detail only; the UI adds the localized "Weather unavailable".
                publish(realWeather, it.message.orEmpty())
                // Allow a retry before the normal interval.
                lastFetch = now - REFRESH_MS + 60_000L
            }
        }
    }
}

/**
 * Whether a tile must say how old its answer is ("As of 14:05"): once it is
 * older than one refresh ([intervalMs]), or as soon as a refresh [failed],
 * the one asked by hand included.
 */
internal fun showsAge(fetchedAt: Long, now: Long, intervalMs: Long, failed: Boolean): Boolean =
    failed || now - fetchedAt > intervalMs

/** How far ahead the forecast is searched for rain, in hours. */
internal const val RAIN_HORIZON_H = 12

/**
 * The first wet spell in Open-Meteo's [hourly] block from [nowMs] on: an hour
 * counts as wet from 0.3 mm or a 60 % chance. Times come as local
 * "2026-09-28T14:00" at [offsetSeconds] from UTC. Null when dry or unreadable.
 */
internal fun rainSpell(hourly: JSONObject, offsetSeconds: Int, nowMs: Long): Pair<Long, Long>? = runCatching {
    val times = hourly.getJSONArray("time")
    val mm = hourly.optJSONArray("precipitation")
    val chance = hourly.optJSONArray("precipitation_probability")
    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
    val hour = 3_600_000L
    var from: Long? = null
    var until: Long? = null
    for (i in 0 until times.length()) {
        val start = (fmt.parse(times.getString(i)) ?: continue).time - offsetSeconds * 1000L
        if (start + hour <= nowMs) continue
        if (start > nowMs + RAIN_HORIZON_H * hour) break
        val wet = (mm?.optDouble(i, 0.0) ?: 0.0) >= 0.3 || (chance?.optInt(i, 0) ?: 0) >= 60
        if (wet) {
            if (from == null) from = start
            until = start + hour
        } else if (from != null) break
    }
    from?.let { it to (until ?: it + hour) }
}.getOrNull()
