package com.openauto.dash

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/*
 * Whether the sun is up where the car is, for the Auto appearance. Uses the
 * last GPS fix when any tile has one, else the last place the car was seen;
 * with neither, the clock alone with a fixed daytime window, which is right
 * most of the year in Europe. Worked out on board: no internet needed.
 */

/** Civil twilight: the sun this far below the horizon is still "day" for the screen. */
private const val TWILIGHT_DEG = -6.0
/** Without a position: day from 7:00 to 19:59 local time. */
internal const val FALLBACK_DAY_START_H = 7
internal const val FALLBACK_DAY_END_H = 20

/** What decides day and night for the Auto look. */
enum class AutoLightBy(@StringRes val titleRes: Int) {
    /** The car's night mode, its headlights and dusk: dark as soon as any says night. */
    CAR(R.string.dash_auto_by_car),
    /** Sunrise and sunset where the car is, nothing else. */
    SUN(R.string.dash_auto_by_sun),
    /** Two times the driver sets. */
    TIMES(R.string.dash_auto_by_times)
}

/**
 * The Auto look's settings: what decides ([by]) and, for [AutoLightBy.TIMES],
 * the minute of the day the light version starts ([from]) and the dark one
 * ([to]). Also remembers the last place the car was, so sunrise and sunset
 * stay right while no tile has the GPS on.
 */
object AutoLight {
    private const val PREFS = "auto_light"
    private const val KEY_BY = "by"
    private const val KEY_FROM = "from"
    private const val KEY_TO = "to"
    private const val KEY_LAT = "lat"
    private const val KEY_LON = "lon"
    /** − / + move a time by this many minutes. */
    const val STEP_MIN = 15
    const val DEFAULT_FROM = FALLBACK_DAY_START_H * 60
    const val DEFAULT_TO = FALLBACK_DAY_END_H * 60
    /** A new place is written once the car is this far (degrees) from the remembered one. */
    private const val MOVED_DEG = 0.25

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    private val _by = MutableStateFlow(AutoLightBy.CAR)
    val by: StateFlow<AutoLightBy> = _by
    private val _from = MutableStateFlow(DEFAULT_FROM)
    val from: StateFlow<Int> = _from
    private val _to = MutableStateFlow(DEFAULT_TO)
    val to: StateFlow<Int> = _to
    private val _place = MutableStateFlow<Pair<Double, Double>?>(null)
    /** Where the car is now, or was last seen; null until it has had one GPS fix. */
    val place: StateFlow<Pair<Double, Double>?> = _place

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        val p = prefs(context)
        _by.value = runCatching { AutoLightBy.valueOf(p.getString(KEY_BY, null) ?: "") }.getOrDefault(AutoLightBy.CAR)
        _from.value = p.getInt(KEY_FROM, DEFAULT_FROM)
        _to.value = p.getInt(KEY_TO, DEFAULT_TO)
        if (p.contains(KEY_LAT) && p.contains(KEY_LON)) {
            _place.value = p.getFloat(KEY_LAT, 0f).toDouble() to p.getFloat(KEY_LON, 0f).toDouble()
        }
        val app = context.applicationContext
        scope.launch {
            LocationFeed.location.collect { l ->
                l ?: return@collect
                val was = _place.value
                _place.value = l.latitude to l.longitude
                if (was == null || kotlin.math.abs(was.first - l.latitude) > MOVED_DEG || kotlin.math.abs(was.second - l.longitude) > MOVED_DEG) {
                    prefs(app).edit().putFloat(KEY_LAT, l.latitude.toFloat()).putFloat(KEY_LON, l.longitude.toFloat()).apply()
                }
            }
        }
    }

    fun setBy(context: Context, value: AutoLightBy) {
        _by.value = value
        prefs(context).edit().putString(KEY_BY, value.name).apply()
    }

    /** Moves the start of the light version by [deltaMin], round the clock. */
    fun moveFrom(context: Context, deltaMin: Int) {
        _from.value = Math.floorMod(_from.value + deltaMin, MINUTES_A_DAY)
        prefs(context).edit().putInt(KEY_FROM, _from.value).apply()
    }

    /** Moves the start of the dark version by [deltaMin], round the clock. */
    fun moveTo(context: Context, deltaMin: Int) {
        _to.value = Math.floorMod(_to.value + deltaMin, MINUTES_A_DAY)
        prefs(context).edit().putInt(KEY_TO, _to.value).apply()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

private const val MINUTES_A_DAY = 24 * 60

/** True while the sun is up here, re-read each minute. Never acquires the GPS itself. */
@Composable
internal fun rememberSunUp(): Boolean {
    val context = LocalContext.current
    AutoLight.start(context)
    val now = rememberWallClock(60_000L).longValue
    val place by AutoLight.place.collectAsState()
    return remember(now, place) { sunUp(now, place?.first, place?.second) }
}

/**
 * Day for the Auto look, by the driver's choice ([AutoLight.by]): the car
 * (its night mode, its headlights and dusk), the sun alone, or two set times.
 */
@Composable
internal fun rememberAutoDay(): Boolean {
    val context = LocalContext.current
    AutoLight.start(context)
    val by by AutoLight.by.collectAsState()
    val from by AutoLight.from.collectAsState()
    val to by AutoLight.to.collectAsState()
    // All read every time, whatever the choice, so the composition keeps one shape.
    val systemDark = isSystemInDarkTheme()
    val headlights by UnitSignals.headlightsOn.collectAsState()
    val sun = rememberSunUp()
    val now = rememberWallClock(60_000L).longValue
    val byClock = remember(now, from, to) { dayByClock(now, from, to) }
    return when (by) {
        AutoLightBy.CAR -> !systemDark && !headlights && sun
        AutoLightBy.SUN -> sun
        AutoLightBy.TIMES -> byClock
    }
}

/**
 * Whether [timeMs] falls between [fromMin] (light from) and [toMin] (dark
 * from), both minutes of the local day; a window may run past midnight.
 * Equal times mean always light.
 */
internal fun dayByClock(timeMs: Long, fromMin: Int, toMin: Int, zone: TimeZone = TimeZone.getDefault()): Boolean {
    val cal = Calendar.getInstance(zone).apply { timeInMillis = timeMs }
    val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    return when {
        fromMin == toMin -> true
        fromMin < toMin -> minute in fromMin until toMin
        else -> minute >= fromMin || minute < toMin
    }
}

/**
 * The minutes of the local day of [timeMs] when the light version starts and
 * ends by the sun at ([lat], [lon]); null when the sun does not cross the
 * twilight line both ways that day (far north in summer or winter).
 */
internal fun sunWindow(timeMs: Long, lat: Double, lon: Double, zone: TimeZone = TimeZone.getDefault()): Pair<Int, Int>? {
    val midnight = Calendar.getInstance(zone).apply {
        timeInMillis = timeMs
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    var rise: Int? = null
    var set: Int? = null
    var wasUp = sunAltitudeDeg(midnight, lat, lon) > TWILIGHT_DEG
    for (m in 1 until MINUTES_A_DAY) {
        val up = sunAltitudeDeg(midnight + m * 60_000L, lat, lon) > TWILIGHT_DEG
        if (up && !wasUp && rise == null) rise = m
        if (!up && wasUp && set == null) set = m
        wasUp = up
    }
    return if (rise != null && set != null) rise to set else null
}

/**
 * Whether the sun is above civil twilight at [timeMs] at ([lat], [lon]); with
 * no position, whether the local hour is within the fallback day window.
 */
internal fun sunUp(timeMs: Long, lat: Double?, lon: Double?, zone: TimeZone = TimeZone.getDefault()): Boolean {
    if (lat == null || lon == null) {
        val cal = Calendar.getInstance(zone).apply { timeInMillis = timeMs }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        return hour in FALLBACK_DAY_START_H until FALLBACK_DAY_END_H
    }
    return sunAltitudeDeg(timeMs, lat, lon) > TWILIGHT_DEG
}

/**
 * The sun's altitude above the horizon in degrees (NOAA's low-precision
 * algorithm, good to a fraction of a degree, plenty for a day/night switch).
 */
internal fun sunAltitudeDeg(timeMs: Long, lat: Double, lon: Double): Double {
    // Julian centuries since J2000.0.
    val jd = timeMs / 86_400_000.0 + 2_440_587.5
    val t = (jd - 2_451_545.0) / 36_525.0
    val meanLon = (280.46646 + t * (36_000.76983 + t * 0.0003032)).mod(360.0)
    val meanAnom = 357.52911 + t * (35_999.05029 - 0.0001537 * t)
    val m = Math.toRadians(meanAnom)
    val centre = sin(m) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
        sin(2 * m) * (0.019993 - 0.000101 * t) + sin(3 * m) * 0.000289
    val trueLon = meanLon + centre
    val omega = Math.toRadians(125.04 - 1934.136 * t)
    val apparentLon = Math.toRadians(trueLon - 0.00569 - 0.00478 * sin(omega))
    val obliquity = Math.toRadians(
        23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0 + 0.00256 * cos(omega)
    )
    val declination = asin(sin(obliquity) * sin(apparentLon))
    // Equation of time, in minutes.
    val y = tan(obliquity / 2).let { it * it }
    val l0 = Math.toRadians(meanLon)
    val e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
    val eqTime = 4 * Math.toDegrees(
        y * sin(2 * l0) - 2 * e * sin(m) + 4 * e * y * sin(m) * cos(2 * l0) -
            0.5 * y * y * sin(4 * l0) - 1.25 * e * e * sin(2 * m)
    )
    // True solar time and hour angle.
    val minutesUtc = ((timeMs / 60_000.0) - floor(timeMs / 86_400_000.0) * 1_440.0)
    val trueSolarMinutes = (minutesUtc + eqTime + 4 * lon).mod(1_440.0)
    val hourAngle = Math.toRadians(trueSolarMinutes / 4 - 180.0)
    val phi = Math.toRadians(lat)
    val cosZenith = (sin(phi) * sin(declination) + cos(phi) * cos(declination) * cos(hourAngle)).coerceIn(-1.0, 1.0)
    return 90.0 - Math.toDegrees(acos(cosZenith))
}
