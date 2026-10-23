package com.openauto.dash

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import androidx.compose.ui.graphics.toArgb
import com.openauto.dash.link.ClusterArt
import com.openauto.dash.link.ClusterFace
import com.openauto.dash.link.ClusterState
import com.openauto.dash.vehicle.Gauges
import com.openauto.dash.vehicle.GAUGE_KEYS
import com.openauto.dash.vehicle.Hold
import com.openauto.dash.vehicle.K
import com.openauto.dash.vehicle.Key
import com.openauto.dash.vehicle.Vehicle
import com.openauto.dash.vehicle.displaySpeedKmh
import com.openauto.dash.vehicle.fuelLevelPctNow
import com.openauto.dash.vehicle.gaugesNow
import com.openauto.dash.vehicle.holdAll
import kotlin.math.roundToInt
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The second screen's readings, for when it draws the cluster itself
 * (SecondScreenOutput.DATA): the same feeds the dashboard's widgets read,
 * sent over DisplayLink whenever something shown changes (see [ClusterThrottle]).
 */
internal object ClusterFeed {
    private const val SAMPLE_MS = 200L
    private const val WORDS_MS = 30_000L

    fun run(context: Context, scope: CoroutineScope, page: StateFlow<ClusterPage>): Job = scope.launch {
        val throttle = ClusterThrottle()
        // The words in Dashwheel's language, one picked inside it included (the app's own
        // context stays in the unit's); looked up again now and then for a new choice.
        var words = AppLanguage.wrap(context)
        var wordsAt = System.currentTimeMillis()
        // The snapshot reads the bus by value: what it sends stays wanted while it runs, and the
        // Measures page's readings only while a page or a board slot shows them.
        val always = Vehicle.bus.holdAll(SENT_KEYS)
        var measuresHold: Hold? = null
        try {
            while (isActive) {
                val showsMeasures = measuresShown(page.value)
                if (showsMeasures && measuresHold == null) measuresHold = Vehicle.bus.holdAll(MEASURE_KEYS)
                else if (!showsMeasures) measuresHold = measuresHold?.let { it.release(); null }
                if (System.currentTimeMillis() - wordsAt > WORDS_MS) {
                    words = AppLanguage.wrap(context)
                    wordsAt = System.currentTimeMillis()
                }
                sendArtIfNew(context)
                val state = snapshot(words, page.value)
                // The clock, the playing position and the fix's age move on their own: they don't make a change.
                val key = state.copy(clock = 0, media = state.media?.copy(positionMs = 0), position = state.position?.copy(ageMs = null))
                if (throttle.shouldSend(key, System.currentTimeMillis())) DisplayLink.send(state)
                // A new GPS fix goes out at once rather than at the next sample: the display's
                // map carries the car on from it, and every millisecond it waits shows as a jolt.
                val fix = LocationFeed.location.value
                withTimeoutOrNull(SAMPLE_MS) { LocationFeed.location.first { it !== fix } }
            }
        } finally {
            measuresHold?.release()
            always.release()
        }
    }

    /** What every sample carries: the speed, the revs, the coolant and the fuel level. */
    private val SENT_KEYS: List<Key<*>> = listOf(K.SPEED, K.RPM, K.COOLANT_TEMP, K.FUEL_LEVEL)

    /** What the Measures page lists. */
    private val MEASURE_KEYS: List<Key<*>> = GAUGE_KEYS - K.FUEL_FLOW

    /** Whether [page], or a slot on the board, shows the Measures face. */
    private fun measuresShown(page: ClusterPage): Boolean {
        val layout = SecondScreenRules.layoutMessage(SecondScreenStore.config.value.layoutFor(page, simple = true))
        return page == ClusterPage.OBD || layout?.faces?.contains(ClusterFace.MEASURES) == true
    }

    /** A fix older than this is sent without its age: something stalled, and the display is better off not trusting it. */
    private const val MAX_FIX_AGE_MS = 5_000L

    /**
     * How old a fix taken at [fixElapsedNs] is at [nowElapsedNs], both on the
     * elapsed-realtime clock; null when the fix carries no time of its own
     * (0) or the age makes no sense.
     */
    internal fun fixAgeMs(fixElapsedNs: Long, nowElapsedNs: Long): Int? {
        if (fixElapsedNs <= 0L) return null
        val ms = (nowElapsedNs - fixElapsedNs) / 1_000_000L
        return ms.takeIf { it in 0L..MAX_FIX_AGE_MS }?.toInt()
    }

    /** The cover last sent: the bitmap it was made from, its key, and the display it went to. */
    @Volatile private var artKey: String? = null
    private var artBitmap: Bitmap? = null
    private var artDisplay: Any? = null

    /**
     * The cover of the track playing, sent once per cover ([ClusterArt]):
     * when the player hands a new bitmap, and again when another display
     * links. Cheap when nothing changed: a reference check per sample.
     */
    private fun sendArtIfNew(context: Context) {
        val display = (DisplayLink.state.value as? DisplayLinkState.Connected)?.display ?: run { artDisplay = null; return }
        val media = if (DemoMode.isOn) DemoMode.media.value else CarMediaController.shared(context).mediaState.value
        val bitmap = media.artwork?.takeIf { !it.isRecycled }
        if (bitmap === artBitmap && display === artDisplay) return
        artBitmap = bitmap
        artDisplay = display
        if (bitmap == null) { artKey = null; return }
        val jpeg = encodeArt(bitmap) ?: run { artKey = null; return }
        val key = jpeg.second
        artKey = key
        DisplayLink.send(ClusterArt(key, Base64.encodeToString(jpeg.first, Base64.NO_WRAP)))
    }

    /** [bitmap] shrunk to [ClusterArt.SIDE_PX] and JPEG-encoded, with a key from its bytes; null when too big or unreadable. */
    internal fun encodeArt(bitmap: Bitmap): Pair<ByteArray, String>? = runCatching {
        val scale = ClusterArt.SIDE_PX.toFloat() / maxOf(bitmap.width, bitmap.height)
        val small = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
        val out = ByteArrayOutputStream()
        if (!small.compress(Bitmap.CompressFormat.JPEG, 80, out)) return null
        val bytes = out.toByteArray()
        if (bytes.size > ClusterArt.MAX_BYTES) return null
        bytes to artKey(bytes)
    }.getOrNull()

    /** A short key from the picture's bytes: the same cover gets the same key on every unit. */
    internal fun artKey(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).take(8).joinToString("") { "%02x".format(it) }

    private fun snapshot(context: Context, page: ClusterPage): ClusterState {
        val display = (DisplayLink.state.value as? DisplayLinkState.Connected)?.display
        // A display that reads the words sent once (SecondScreenWords) needn't get them five times a second.
        val wordsOnce = display != null && SecondScreenRules.readsWordsOnce(display.protocol)
        val config = SecondScreenStore.config.value
        val obdLive = ObdBluetoothManager.connectionState.value == ObdConnectionState.CONNECTED || DemoMode.isOn
        // The car's speed comes corrected already; the GPS's only once moving
        // (SpeedCorrection.correctedGps): a stopped car's 1 km/h of GPS drift must not read 9.
        val speed = displaySpeedKmh(Vehicle.bus.sample(K.SPEED).value)
        val fuel = carFuelInfo(McuReader.fuelPercent.value, fuelLevelPctNow(), McuReader.rangeKm.value)
        val doors = McuReader.doorState.value
        val open = if (doors == null) emptyList() else listOfNotNull(
            R.string.vehicle_door_front_left.takeIf { doors.frontLeft },
            R.string.vehicle_door_front_right.takeIf { doors.frontRight },
            R.string.vehicle_door_rear_left.takeIf { doors.rearLeft },
            R.string.vehicle_door_rear_right.takeIf { doors.rearRight },
            R.string.vehicle_door_tailgate.takeIf { doors.tailgate },
            R.string.vehicle_door_bonnet.takeIf { doors.bonnet }
        ).map { context.getString(it) }

        val controller = CarMediaController.shared(context)
        val media = if (DemoMode.isOn) DemoMode.media.value else controller.mediaState.value
        // The navigation app's own turn; else the one on the shadow route (ShadowRoute), when there is one.
        val nav = NavDirections.state.value.takeIf { it.active && it.instruction.isNotBlank() } ?: ShadowRoute.turn.value ?: NavState()
        val units = Units.current.value
        // The page as laid out on the board, so the display draws the same widgets in the same places.
        val layout = SecondScreenRules.layoutMessage(config.layoutFor(page, simple = true))
        // The second screen's own colours (ClusterLook), as the board's preview draws them: not the dashboard's accent.
        val background = SecondScreenRules.background(config, day = DashColors.Light)
        return ClusterState(
            clock = System.currentTimeMillis(),
            page = page.name,
            speedKmh = speed,
            rpm = Vehicle.bus.now(K.RPM)?.roundToInt(),
            coolantC = Vehicle.bus.now(K.COOLANT_TEMP)?.roundToInt(),
            // As the dashboard's bar reads it (carFuelInfo): a car that sends 0 % with a range
            // left sends no level at all, and the level is worked out from the range.
            fuelPct = fuel?.percent,
            rangeKm = fuel?.rangeKm ?: McuReader.rangeKm.value,
            open = open,
            doorsKnown = doors != null,
            // The date under the clock, as the clock widget writes it, in Dashwheel's language.
            date = dateLine(context),
            // A navigation app running with nothing to say: the directions face says so instead of "no route".
            navApp = if (nav.active) null else runningNavAppName(),
            obdConnected = obdLive,
            imperial = units.imperial,
            alert = SecondScreenAlerts.dataAlert(context),
            speedUnit = units.speedUnit,
            fahrenheit = units.fahrenheit,
            clock12 = !units.clock24,
            // The display draws in its own colours: the second screen's background, not the dashboard's look.
            night = background != ClusterBackground.LIGHT,
            accent = clusterPalette(background).Accent.toArgb().toLong() and 0xFFFFFFFFL,
            media = media.takeIf { it.hasMedia && it.title.isNotBlank() }?.let {
                ClusterState.Media(
                    title = it.title,
                    artist = it.artist,
                    playing = it.isPlaying,
                    positionMs = controller.positionMs(),
                    durationMs = it.durationMs,
                    artKey = artKey
                )
            },
            nav = nav.takeIf { it.active && it.instruction.isNotBlank() }?.let {
                ClusterState.Nav(instruction = it.instruction, distance = it.distance, eta = it.eta)
            },
            labels = if (wordsOnce) null else SecondScreenWords.labels(context, units),
            design = config.design.name,
            // Only for a page that shows them (the Measures page, or a board slot with the face): five
            // times a second, the rest of the time, for nothing.
            measures = if (obdLive && (page == ClusterPage.OBD || layout?.faces?.contains(ClusterFace.MEASURES) == true)) measures(context, gaugesNow(), units) else emptyList(),
            // The turn as the navigation knows it (in-app), so the display needn't read the words.
            maneuver = nav.maneuver,
            // Readings only go to a screen that draws its own pages: the simple display's.
            layout = layout,
            // Where the car is, for the display's own map: rounded so a parked car sends nothing new.
            position = LocationFeed.location.value?.let { l ->
                ClusterState.Position(
                    Math.round(l.latitude * 1e5) / 1e5, Math.round(l.longitude * 1e5) / 1e5,
                    LocationFeed.headingDeg.value?.let { Math.round(it) },
                    fixAgeMs(l.elapsedRealtimeNanos, android.os.SystemClock.elapsedRealtimeNanos())
                )
            },
            // How the display's own map is seen.
            mapView = ClusterState.MapView(config.mapTilted, config.mapTilt.name, config.mapDistance.name, config.mapBuildings)
        )
    }

    /** The date as the clock widget writes it ("Jeudi 15 octobre"), in [context]'s language. */
    private fun dateLine(context: Context): String {
        val locale = context.resources.configuration.locales[0]
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM")
        return SimpleDateFormat(pattern, locale).format(Date()).replaceFirstChar { it.uppercase() }
    }

    /** The navigation app running now, by the name the directions tile gives it; null with none. */
    private fun runningNavAppName(): String? {
        val pkg = NavDirections.running.value ?: NavDirections.PACKAGES.firstOrNull { it in EmbeddedApp.hosted.value } ?: return null
        return navAppName(pkg)
    }

    /** The Measures page's readings: those of the dashboard's "All readings" tile, in its words and units. */
    private fun measures(context: Context, g: Gauges, u: UnitSystem): List<ClusterState.Measure> = listOfNotNull(
        g.rpm?.let { ClusterState.Measure(context.getString(R.string.info_unit_rpm), it.toString()) },
        g.speedKmh?.let { ClusterState.Measure(context.getString(R.string.vehicle_speed), u.speedText(it)) },
        g.coolantTempC?.let { ClusterState.Measure(context.getString(R.string.vehicle_coolant), u.tempText(it)) },
        g.intakeTempC?.let { ClusterState.Measure(context.getString(R.string.vehicle_intake_air), u.tempText(it)) },
        g.throttlePct?.let { ClusterState.Measure(context.getString(R.string.vehicle_throttle), "$it %") },
        g.engineLoadPct?.let { ClusterState.Measure(context.getString(R.string.vehicle_engine_load), "$it %") },
        g.voltage?.let { ClusterState.Measure(context.getString(R.string.vehicle_battery), String.format(java.util.Locale.getDefault(), "%.1f V", it)) },
        g.fuelLevelPct?.takeIf { it > 0 }?.let { ClusterState.Measure(context.getString(R.string.vehicle_fuel_level), "$it %") }
    )

}
