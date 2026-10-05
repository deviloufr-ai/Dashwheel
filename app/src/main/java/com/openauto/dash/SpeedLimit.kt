package com.openauto.dash

import android.content.Context
import android.location.Location
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/** One road near the car: its limit and its shape. */
internal class LimitedRoad(val limitKmh: Int, val points: List<Pair<Double, Double>>)

/** OpenStreetMap's speed limit text in km/h: "80", "50 mph", or a country's implied ones ("FR:urban"); null for none. */
internal fun parseMaxspeed(tag: String?): Int? {
    val t = tag?.trim()?.lowercase() ?: return null
    t.takeWhile { it.isDigit() }.toIntOrNull()?.let { n -> return if ("mph" in t) (n * 1.609).toInt() else n }
    return when (t) {
        "fr:urban", "de:urban", "be:urban", "it:urban", "es:urban", "nl:urban" -> 50
        "fr:rural" -> 80
        "de:rural", "be-wal:rural", "it:rural", "es:rural", "nl:rural" -> 90
        "fr:motorway", "it:motorway", "be:motorway", "nl:motorway" -> 130
        "es:motorway" -> 120
        "walk", "fr:walk" -> 6
        "fr:zone30", "de:zone30" -> 30
        else -> null
    }
}

/**
 * The road the car is on, by its position and its course, among [roads]:
 * the nearest within [MAX_M] that runs the way the car goes; null off any.
 */
internal fun roadAt(lat: Double, lng: Double, bearing: Float?, roads: List<LimitedRoad>): LimitedRoad? {
    val mPerLat = 111_320.0
    val mPerLng = 111_320.0 * cos(Math.toRadians(lat))
    var best: LimitedRoad? = null
    var bestD = SpeedLimit.MAX_M
    for (r in roads) {
        for (i in 0 until r.points.size - 1) {
            val (aLat, aLng) = r.points[i]
            val (bLat, bLng) = r.points[i + 1]
            val ax = (aLng - lng) * mPerLng; val ay = (aLat - lat) * mPerLat
            val bx = (bLng - lng) * mPerLng; val by = (bLat - lat) * mPerLat
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val d = sqrt((ax + t * dx) * (ax + t * dx) + (ay + t * dy) * (ay + t * dy))
            if (d >= bestD) continue
            if (bearing != null && len2 > 1.0) {
                val seg = Math.toDegrees(Math.atan2(dx, dy)).toFloat()
                val diff = abs(((seg - bearing + 540) % 360) - 180)
                // Either way along the road: most ways carry traffic both ways.
                if (diff > 45 && diff < 135) continue
            }
            bestD = d
            best = r
        }
    }
    return best
}

/**
 * The speed limit of the road the car is on, from OpenStreetMap (Overpass):
 * the roads around with a limit are read again every [REFETCH_M], the one the
 * car is on is found by position and course. Over the limit for a few seconds,
 * the driver is told once (spoken warnings on by default; a tap on the tile
 * silences them). Legal everywhere, unlike camera warnings.
 */
internal object SpeedLimit {
    private const val TAG = "SpeedLimit"
    private const val PREFS = "speed_limit"
    private const val KEY_SPEAK = "speak"
    private const val OVERPASS = "https://overpass-api.de/api/interpreter"
    private const val RADIUS_M = 1_500
    private const val REFETCH_M = 1_000f
    const val MAX_M = 30.0
    /** Over by more than this, for [OVER_MS], is said out loud. */
    private const val TOLERANCE_KMH = 5
    private const val OVER_MS = 4_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var users = 0
    @Volatile private var roads: List<LimitedRoad> = emptyList()
    @Volatile private var fetchedAt: Location? = null
    @Volatile private var fetching = false
    private var overSince = 0L
    private var warnedFor: LimitedRoad? = null

    private val _limit = MutableStateFlow<Int?>(null)
    val limit: StateFlow<Int?> = _limit
    private val _speak = MutableStateFlow(true)
    val speak: StateFlow<Boolean> = _speak

    @Synchronized
    fun start(context: Context) {
        val app = context.applicationContext
        _speak.value = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SPEAK, true)
        users++
        if (job?.isActive == true) return
        job = scope.launch {
            var kmh = 0
            launch { carSpeedKmh().collect { kmh = it } }
            LocationFeed.location.collect { loc -> if (loc != null) onLocation(app, loc, kmh) }
        }
    }

    @Synchronized
    fun stop() {
        if (users == 0 || --users > 0) return
        job?.cancel()
        job = null
    }

    fun setSpeak(context: Context, on: Boolean) {
        _speak.value = on
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SPEAK, on).apply()
    }

    private suspend fun onLocation(context: Context, loc: Location, kmh: Int) {
        val last = fetchedAt
        if (!fetching && (last == null || last.distanceTo(loc) > REFETCH_M)) {
            fetching = true
            val got = withContext(Dispatchers.IO) { runCatching { fetch(loc.latitude, loc.longitude) } }
            fetching = false
            got.onSuccess { roads = it; fetchedAt = loc }.onFailure { Log.w(TAG, "limits unread: ${it.message}") }
        }
        val road = roadAt(loc.latitude, loc.longitude, if (loc.hasBearing() && loc.speed > 2f) loc.bearing else null, roads)
        _limit.value = road?.limitKmh
        val now = System.currentTimeMillis()
        if (road != null && kmh > road.limitKmh + TOLERANCE_KMH) {
            if (overSince == 0L) overSince = now
            if (now - overSince >= OVER_MS && warnedFor !== road && _speak.value) {
                warnedFor = road
                val app = AppLanguage.wrap(context)
                CarVoice.speak(app.getString(R.string.widgets_limit_say, Units.current.value.speed(road.limitKmh)), InAppNav.locale(context))
            }
        } else {
            overSince = 0L
        }
    }

    private fun fetch(lat: Double, lng: Double): List<LimitedRoad> {
        val query = "[out:json][timeout:25];way(around:$RADIUS_M,$lat,$lng)[highway][maxspeed];out tags geom;"
        val request = Request.Builder().url(OVERPASS)
            .header("User-Agent", OVERPASS_AGENT)
            .post(FormBody.Builder().add("data", query).build()).build()
        Http.client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val elements = JSONObject(resp.body?.string() ?: "{}").optJSONArray("elements") ?: return emptyList()
            return (0 until elements.length()).mapNotNull { i ->
                val e = elements.optJSONObject(i) ?: return@mapNotNull null
                val limit = parseMaxspeed(e.optJSONObject("tags")?.optString("maxspeed")) ?: return@mapNotNull null
                val geom = e.optJSONArray("geometry") ?: return@mapNotNull null
                val pts = (0 until geom.length()).mapNotNull { j -> geom.optJSONObject(j)?.let { it.optDouble("lat") to it.optDouble("lon") } }
                if (pts.size < 2) null else LimitedRoad(limit, pts)
            }
        }
    }
}

/** How Dashwheel names itself to OpenStreetMap's Overpass servers, as their usage policy asks. */
internal const val OVERPASS_AGENT = "Dashwheel/1.0 (https://github.com/deviloufr-ai/Dashwheel)"

@Composable
internal fun SpeedLimitCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    DisposableEffect(Unit) { SpeedLimit.start(context); onDispose { SpeedLimit.stop() } }
    val limit by SpeedLimit.limit.collectAsState()
    val speak by SpeedLimit.speak.collectAsState()
    val units by Units.current.collectAsState()
    val kmh by remember { carSpeedKmh() }.collectAsState(0)
    val over = limit != null && kmh > limit!! + 5
    Card(modifier = modifier) {
        Row(modifier = Modifier.fillMaxSize().padding(DashSpace.Md), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DashSpace.Md)) {
            // A round road sign: red ring, the number in black on white.
            Box(
                modifier = Modifier.fillMaxHeight().aspectRatio(1f).padding(DashSpace.Xs)
                    .background(Color.White, CircleShape)
                    .border(8.dp, Color(0xFFD32F2F), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    limit?.let { units.speed(it).toString() } ?: "--",
                    color = Color.Black, fontWeight = FontWeight.Black, style = MaterialTheme.typography.headlineMedium
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(if (limit == null) R.string.widgets_limit_unknown else if (over) R.string.widgets_limit_over else R.string.widgets_limit_ok),
                    color = if (over) DashColors.Critical else DashColors.TextSecondary, fontWeight = if (over) FontWeight.Bold else FontWeight.Normal,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            IconButton(onClick = { SpeedLimit.setSpeak(context, !speak) }, modifier = Modifier.size(DashSize.Touch)) {
                Icon(if (speak) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff, contentDescription = stringResource(R.string.widgets_limit_speak), tint = if (speak) DashColors.Accent else DashColors.Muted)
            }
        }
    }
}

@Composable
internal fun speedLimitFace(): WidgetFace {
    val context = LocalContext.current
    DisposableEffect(Unit) { SpeedLimit.start(context); onDispose { SpeedLimit.stop() } }
    val limit by SpeedLimit.limit.collectAsState()
    val units by Units.current.collectAsState()
    return WidgetFace(
        icon = Icons.Filled.Speed,
        title = BuiltinKind.SPEED_LIMIT.label,
        value = limit?.let { units.speed(it).toString() } ?: "--",
        unit = units.speedUnit,
        caption = stringResource(if (limit == null) R.string.widgets_limit_unknown else R.string.widgets_limit_ok)
    )
}
