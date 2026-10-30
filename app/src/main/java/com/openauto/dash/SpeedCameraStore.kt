package com.openauto.dash

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * The speed cameras kept on the unit, for the drives when no Overpass
 * server answers ([SpeedCameras]): the public servers go down for hours at
 * a time, and a tile that went blank mid-drive was the tester's complaint
 * (#112). Two kinds are kept, in one file ([SpeedCameraStore]):
 *
 * - every answer read on a drive, as a **patch**: the cameras within
 *   [SpeedCameras.RADIUS_M] of where they were asked. A new patch stands in
 *   for the older ones asked from nearby ([PATCH_APART_M]), so a camera taken
 *   down leaves the unit with the next drive past it, and only the last
 *   [MAX_PATCHES] are kept, the roads driven most recently;
 * - the whole set of a **country** the driver asked for in Settings, read
 *   again when older than [SpeedCameraStore.REFRESH_MS].
 *
 * Pure, so it is unit-tested; the file and the servers are the store's.
 */
internal class KeptCameras {
    class Country(val code: String, val at: Long, val cameras: List<SpeedCamera>)
    class Patch(val lat: Double, val lng: Double, val at: Long, val cameras: List<SpeedCamera>)

    private val countries = LinkedHashMap<String, Country>()
    private val patches = ArrayDeque<Patch>()

    val countryList: List<Country> get() = countries.values.toList()
    val patchCount: Int get() = patches.size
    /** How many cameras the drives left, each counted once. */
    val drivenCount: Int get() = patches.flatMap { it.cameras }.distinct().size

    /** An answer read at ([lat], [lng]): kept as a patch, in place of the ones asked from nearby. */
    fun remember(lat: Double, lng: Double, cameras: List<SpeedCamera>, now: Long) {
        patches.removeAll { metresBetween(it.lat, it.lng, lat, lng) < PATCH_APART_M }
        patches.addLast(Patch(lat, lng, now, cameras))
        while (patches.size > MAX_PATCHES) patches.removeFirst()
    }

    fun keepCountry(code: String, cameras: List<SpeedCamera>, now: Long) {
        val key = code.lowercase(Locale.ROOT)
        countries.remove(key)
        countries[key] = Country(key, now, cameras)
    }

    fun dropCountry(code: String) {
        countries.remove(code.lowercase(Locale.ROOT))
    }

    /** The countries read more than [maxAgeMs] before [now], oldest first. */
    fun stale(now: Long, maxAgeMs: Long): List<String> =
        countries.values.filter { now - it.at > maxAgeMs }.sortedBy { it.at }.map { it.code }

    /** The kept cameras within [radiusM] of ([lat], [lng]): the newest patches first, then the countries, each camera once. */
    fun near(lat: Double, lng: Double, radiusM: Int): List<SpeedCamera> {
        val out = LinkedHashSet<SpeedCamera>()
        for (p in patches.asReversed()) {
            if (metresBetween(p.lat, p.lng, lat, lng) > radiusM + SpeedCameras.RADIUS_M) continue
            p.cameras.filterTo(out) { metresBetween(it.lat, it.lng, lat, lng) <= radiusM }
        }
        for (c in countries.values) c.cameras.filterTo(out) { metresBetween(it.lat, it.lng, lat, lng) <= radiusM }
        return out.toList()
    }

    fun toJson(): JSONObject = JSONObject()
        .put("v", 1)
        .put("countries", JSONArray().apply {
            countries.values.forEach { put(JSONObject().put("code", it.code).put("at", it.at).put("cams", camerasJson(it.cameras))) }
        })
        .put("patches", JSONArray().apply {
            patches.forEach { put(JSONObject().put("lat", it.lat).put("lng", it.lng).put("at", it.at).put("cams", camerasJson(it.cameras))) }
        })

    companion object {
        /** A patch asked from closer than this to a new one is the same stretch of road, read again. */
        const val PATCH_APART_M = 4_000
        /** Patches are asked every [SpeedCameras.REFETCH_M]: this many is the last 1 500 km or so of driving. */
        const val MAX_PATCHES = 200

        fun fromJson(json: JSONObject): KeptCameras {
            val kept = KeptCameras()
            val countries = json.optJSONArray("countries") ?: JSONArray()
            for (i in 0 until countries.length()) {
                val c = countries.optJSONObject(i) ?: continue
                val code = c.optString("code").takeIf { it.isNotBlank() } ?: continue
                kept.countries[code] = Country(code, c.optLong("at"), cameras(c.optJSONArray("cams")))
            }
            val patches = json.optJSONArray("patches") ?: JSONArray()
            for (i in 0 until patches.length()) {
                val p = patches.optJSONObject(i) ?: continue
                kept.patches.addLast(Patch(p.optDouble("lat"), p.optDouble("lng"), p.optLong("at"), cameras(p.optJSONArray("cams"))))
            }
            return kept
        }

        private fun camerasJson(cameras: List<SpeedCamera>) = JSONArray().apply {
            cameras.forEach { put(JSONArray().put(it.lat).put(it.lng).put(it.limitKmh ?: JSONObject.NULL)) }
        }

        private fun cameras(array: JSONArray?): List<SpeedCamera> {
            if (array == null) return emptyList()
            return (0 until array.length()).mapNotNull { i ->
                val c = array.optJSONArray(i) ?: return@mapNotNull null
                if (c.length() < 2) return@mapNotNull null
                SpeedCamera(c.optDouble(0), c.optDouble(1), if (c.isNull(2)) null else c.optInt(2))
            }
        }
    }
}

/** The distance between two positions in metres, near enough over the few tens of kilometres this is used for. */
internal fun metresBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val dy = (lat2 - lat1) * 111_320.0
    val dx = (lng2 - lng1) * 111_320.0 * cos(Math.toRadians((lat1 + lat2) / 2))
    return sqrt(dx * dx + dy * dy)
}

/**
 * The [KeptCameras] on the unit: one JSON file in the app's files, read once
 * and written back after each change, and what Settings shows of it
 * ([summary]). Reads and writes block: from an IO thread.
 */
internal object SpeedCameraStore {
    private const val TAG = "SpeedCameraStore"
    private const val FILE = "speed_cameras.json"
    /** A country's set is read again after this long, on the next drive with a server answering. */
    const val REFRESH_MS = 7 * 24 * 3_600_000L

    class CountryKept(val code: String, val count: Int, val at: Long)
    class Summary(val countries: List<CountryKept> = emptyList(), val driven: Int = 0) {
        val isEmpty: Boolean get() = countries.isEmpty() && driven == 0
    }

    private val lock = Any()
    private var kept: KeptCameras? = null

    private val _summary = MutableStateFlow(Summary())
    /** What is kept, for the Settings row and sheet; filled once the file was read. */
    val summary: StateFlow<Summary> = _summary
    private val _downloading = MutableStateFlow<String?>(null)
    /** The country being read right now, lower-case code; null for none. */
    val downloading: StateFlow<String?> = _downloading

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE)

    private fun load(context: Context): KeptCameras = synchronized(lock) {
        kept ?: run {
            val f = file(context)
            val read = if (f.exists()) runCatching { KeptCameras.fromJson(JSONObject(f.readText())) }
                .onFailure { Log.w(TAG, "kept cameras unread: ${it.message}") }.getOrNull() else null
            (read ?: KeptCameras()).also { kept = it; publish(it) }
        }
    }

    /** Reads the file if it hasn't been, so [summary] is right; from an IO thread. */
    fun warm(context: Context) {
        load(context)
    }

    private fun publish(k: KeptCameras) {
        _summary.value = Summary(k.countryList.map { CountryKept(it.code, it.cameras.size, it.at) }, k.drivenCount)
    }

    private fun save(context: Context, k: KeptCameras) {
        val f = file(context)
        val tmp = File(f.parentFile, "$FILE.tmp")
        runCatching {
            tmp.writeText(k.toJson().toString())
            if (!tmp.renameTo(f)) error("rename failed")
        }.onFailure { Log.w(TAG, "kept cameras unsaved: ${it.message}"); tmp.delete() }
        publish(k)
    }

    /** The kept cameras within [radiusM] of the car. */
    fun near(context: Context, lat: Double, lng: Double, radiusM: Int): List<SpeedCamera> =
        synchronized(lock) { load(context).near(lat, lng, radiusM) }

    /** An answer read on the drive at ([lat], [lng]), kept for the next time the servers are down. */
    fun remember(context: Context, lat: Double, lng: Double, cameras: List<SpeedCamera>) {
        synchronized(lock) {
            val k = load(context)
            k.remember(lat, lng, cameras, System.currentTimeMillis())
            save(context, k)
        }
    }

    /** Whether [code] is a country code the servers can be asked for: two letters. */
    fun usableCode(code: String): Boolean = code.trim().length == 2 && code.trim().all { it.isLetter() }

    /**
     * Reads every fixed camera of the country [code] (ISO 3166-1, "fr") from
     * the first Overpass server that answers and keeps the set; gives back how
     * many. Throws when no server answers. One country at a time: a second
     * call while one runs throws too.
     */
    fun download(context: Context, code: String): Int {
        val key = code.trim().lowercase(Locale.ROOT)
        require(usableCode(key)) { "not a country code: $code" }
        synchronized(lock) {
            check(_downloading.value == null) { "already reading ${_downloading.value}" }
            _downloading.value = key
        }
        try {
            val iso = key.uppercase(Locale.ROOT)
            val query = "[out:json][timeout:150];area[\"ISO3166-1\"=\"$iso\"][admin_level=2]->.c;node(area.c)[highway=speed_camera];out;"
            val cameras = SpeedCameras.parse(Overpass.query(query, patient = true))
            synchronized(lock) {
                val k = load(context)
                k.keepCountry(key, cameras, System.currentTimeMillis())
                save(context, k)
            }
            Log.i(TAG, "$iso: ${cameras.size} cameras kept")
            return cameras.size
        } finally {
            _downloading.value = null
        }
    }

    fun drop(context: Context, code: String) {
        synchronized(lock) {
            val k = load(context)
            k.dropCountry(code)
            save(context, k)
        }
    }

    /** Reads again the countries older than [REFRESH_MS], one after the other; a failure waits for the next call. */
    fun refreshStale(context: Context) {
        val stale = synchronized(lock) { load(context).stale(System.currentTimeMillis(), REFRESH_MS) }
        for (code in stale) {
            runCatching { download(context, code) }.onFailure { Log.w(TAG, "$code not refreshed: ${it.message}") }
        }
    }
}

/** The Driving settings row: which countries are kept, or what keeping is for. */
@Composable
internal fun KeptCamerasRow(onOpen: () -> Unit) {
    val context = LocalContext.current
    val summary by SpeedCameraStore.summary.collectAsState()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { SpeedCameraStore.warm(context) } }
    SettingsRow(
        Icons.Filled.CameraAlt, stringResource(R.string.cams_kept_title),
        if (summary.countries.isEmpty()) stringResource(R.string.cams_kept_row_detail)
        else summary.countries.joinToString(", ") { countryName(it.code) }
    ) { onOpen() }
}

/** Settings → Driving → Speed cameras on the unit: a country's cameras to keep, and what is kept. */
@Composable
internal fun KeptCamerasSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val summary by SpeedCameraStore.summary.collectAsState()
    val downloading by SpeedCameraStore.downloading.collectAsState()
    var code by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    val failed = stringResource(R.string.cams_kept_failed)
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { SpeedCameraStore.warm(context) }
        // The country the car is in, or the unit's own, offered first.
        val at = LocationFeed.location.value
        val found = if (at != null) withContext(Dispatchers.IO) { CountryLookup.code(at.latitude, at.longitude) } else null
        val offered = (found ?: Locale.getDefault().country).uppercase(Locale.ROOT)
        if (code.isEmpty() && SpeedCameraStore.usableCode(offered)) code = offered
    }

    fun download() {
        val c = code.trim()
        if (!SpeedCameraStore.usableCode(c) || downloading != null) return
        keyboard?.hide()
        problem = null
        scope.launch {
            val got = withContext(Dispatchers.IO) { runCatching { SpeedCameraStore.download(context, c) } }
            if (got.isFailure) problem = failed
        }
    }

    SettingsSheet(
        title = stringResource(R.string.cams_kept_title),
        onDismiss = onDismiss,
        actions = { SheetButton(stringResource(R.string.ai_done), onClick = onDismiss) }
    ) {
        Text(stringResource(R.string.cams_kept_explanation), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
        Label(stringResource(R.string.cams_kept_country))
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.filter { ch -> ch.isLetter() }.take(2).uppercase(Locale.ROOT); problem = null },
            placeholder = { Text("FR", color = DashColors.Muted) },
            singleLine = true,
            enabled = downloading == null,
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { download() }, onDone = { download() })
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SheetButton(stringResource(if (downloading != null) R.string.cams_kept_downloading else R.string.cams_kept_download)) { download() }
        }
        problem?.let { Text(it, color = DashColors.Warning, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(8.dp))
        Label(stringResource(R.string.cams_kept_section))
        if (summary.countries.isEmpty()) {
            Text(stringResource(R.string.cams_kept_no_country), color = DashColors.Muted, style = MaterialTheme.typography.bodyMedium)
        }
        for (c in summary.countries) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(countryName(c.code), color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        pluralStringResource(R.plurals.cams_kept_count, c.count, c.count) + ", " + DateFormat.getDateInstance(DateFormat.SHORT).format(Date(c.at)),
                        color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { scope.launch(Dispatchers.IO) { SpeedCameraStore.drop(context, c.code) } }) {
                    Text(stringResource(R.string.cams_kept_remove), color = DashColors.Accent)
                }
            }
        }
        Text(
            stringResource(R.string.cams_kept_driven, pluralStringResource(R.plurals.cams_kept_count, summary.driven, summary.driven)),
            color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall
        )
    }
}

/** The country's name in the app's language, from its code; the code itself when Android has no name for it. */
internal fun countryName(code: String): String {
    val upper = code.uppercase(Locale.ROOT)
    val name = runCatching { Locale.Builder().setRegion(upper).build().getDisplayCountry(Locale.getDefault()) }.getOrNull()
    return name?.takeIf { it.isNotBlank() && it != upper } ?: upper
}
