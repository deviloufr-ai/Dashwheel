package com.openauto.dash.companion

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat
import com.openauto.dash.link.RoadScene
import com.openauto.dash.roadwatch.HorizonLearner
import com.openauto.dash.roadwatch.LaneFinder
import com.openauto.dash.roadwatch.LaneState
import com.openauto.dash.roadwatch.LaneTracker
import com.openauto.dash.roadwatch.Lead
import com.openauto.dash.roadwatch.LeadTracker
import com.openauto.dash.roadwatch.Luma
import com.openauto.dash.roadwatch.RoadBand
import com.openauto.dash.roadwatch.RoadRules
import com.openauto.dash.roadwatch.RoadWarning
import com.openauto.dash.roadwatch.Upright
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/*
 * Road watch on the phone: the camera's frames, read one at a time as they
 * come ([analyze], on the camera's own thread), turned into what the road
 * ahead holds (the lane from LaneFinder, the vehicle ahead from the detector
 * and LeadTracker), shown on the phone's screen and sent to the linked car
 * about ten times a second as a [RoadScene]. The car decides what to warn
 * about; with no car linked, the phone warns on its own screen, with a beep.
 */

/** What the phone's screen draws over the camera's picture. */
data class RoadView(
    /** The upright frame's shape, to place the drawing over the picture. */
    val frameW: Int = 4,
    val frameH: Int = 3,
    val band: RoadBand = RoadBand(),
    val lane: LaneState = LaneState(),
    val lead: Lead? = null,
    val warning: RoadWarning? = null,
    val vehicles: Boolean = false,
    val speedKmh: Float? = null,
    /** Frames read a second. */
    val fps: Int = 0,
    /** The driver has put the horizon line where it belongs, once: the hint to do so is no longer shown. */
    val horizonSet: Boolean = true
)

class RoadWatch(context: Context) : ImageAnalysis.Analyzer {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile var band = RoadBand(horizonY = prefs.getFloat(KEY_HORIZON, 0.5f))
        private set
    /** The horizon, learned from where the lane's lines meet while the car drives on; the driver's drag sets where it starts. */
    private val horizon = HorizonLearner(band.horizonY)
    /** A drag not yet handed to [horizon], which the camera's thread alone touches. */
    @Volatile private var dragged: Float? = null
    /**
     * The frame stands taller than wide: the phone upright in its holder.
     * Its width then spans the camera's narrower side, and the car sits
     * elsewhere in it, so the upright phone has its own view and its own
     * learned place in the lane.
     */
    private var tall = false
    private var lanes = LaneTracker(prefs.getFloat(KEY_CAR_X, 0.5f))
    private val leads = LeadTracker(prefs.getFloat(KEY_HFOV, DEFAULT_HFOV))
    @Volatile private var hfovWide = prefs.getFloat(KEY_HFOV, DEFAULT_HFOV)
    @Volatile private var hfovTall = prefs.getFloat(KEY_HFOV_TALL, DEFAULT_HFOV_TALL)
    private val rules = RoadRules()
    private var detector: RoadDetector? = null
    @Volatile private var detectorTried = false

    private val _view = MutableStateFlow(RoadView())
    val view: StateFlow<RoadView> = _view

    /** The road recorder running beside it, when the driver turned it on: it gets the position, the frames' rotation, and the collision warning. */
    @Volatile var recorder: RoadRecorder? = null

    private var sentAt = 0L
    private var frames = 0
    private var framesSince = 0L
    private var fps = 0
    private var beeper: ToneGenerator? = null
    private var shownWarning: RoadWarning? = null

    @Volatile private var speedKmh: Float? = null
    @Volatile private var speedAt = 0L
    private var locations: LocationManager? = null
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            speedKmh = if (location.hasSpeed()) location.speed * 3.6f else null
            speedAt = SystemClock.elapsedRealtime()
            recorder?.location(location)
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    /** The camera's view across its long side ([wideDeg]) and its short one ([tallDeg]), once the camera is known. */
    fun fieldOfView(wideDeg: Float, tallDeg: Float) {
        hfovWide = wideDeg
        hfovTall = tallDeg
        prefs.edit().putFloat(KEY_HFOV, wideDeg).putFloat(KEY_HFOV_TALL, tallDeg).apply()
    }

    /** The driver moved the horizon line on the screen. */
    fun setHorizon(y: Float) {
        band = band.copy(horizonY = y.coerceIn(0.25f, 0.7f))
        dragged = band.horizonY
        horizonSet = true
        prefs.edit().putFloat(KEY_HORIZON, band.horizonY).putBoolean(KEY_HORIZON_SET, true).apply()
    }

    @Volatile private var horizonSet = prefs.getBoolean(KEY_HORIZON_SET, false)

    @SuppressLint("MissingPermission")
    fun start() {
        val ok = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val lm = app.getSystemService(LocationManager::class.java)
        if (ok && lm != null && locations == null) {
            runCatching { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, listener, Looper.getMainLooper()) }
                .onSuccess { locations = lm }
        }
    }

    /** The camera stopped: nothing more goes to the car, and the car's warnings end with the scenes. */
    fun stop() {
        locations?.let { runCatching { it.removeUpdates(listener) } }
        locations = null
        prefs.edit().putFloat(carXKey(tall), lanes.carX).putFloat(KEY_HORIZON, band.horizonY).apply()
        rules.clear()
        lanes.reset()
        leads.forget()
        beeper?.release()
        beeper = null
        shownWarning = null
    }

    /** Called again (the camera's thread) once the model arrives: the next frame opens it. */
    fun modelArrived() {
        detectorTried = false
    }

    fun close() {
        stop()
        detector?.close()
        detector = null
    }

    override fun analyze(image: ImageProxy) {
        try {
            read(image)
        } finally {
            image.close()
        }
    }

    private fun read(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rotation = image.imageInfo.rotationDegrees
        val w = image.width
        val h = image.height
        val band = band
        if (!detectorTried) {
            detectorTried = true
            detector?.close()
            detector = RoadDetector.open(app)
        }
        val uprightW = Upright.width(w, h, rotation)
        val uprightH = Upright.height(w, h, rotation)
        // The phone turned upright, or back: the other view, and the other learned place in the lane.
        val isTall = uprightH > uprightW
        if (isTall != tall) {
            prefs.edit().putFloat(carXKey(tall), lanes.carX).apply()
            tall = isTall
            lanes = LaneTracker(prefs.getFloat(carXKey(tall), 0.5f))
            leads.forget()
        }
        val hfov = if (tall) hfovTall else hfovWide
        leads.hfovDeg = hfov
        recorder?.rotation(rotation)

        val luma = Luma.fromRgba(buffer, w, h, plane.rowStride, rotation, LUMA_WIDTH)
        val speed = speedKmh?.takeIf { now - speedAt < SPEED_FRESH_MS }
        val steady = speed != null && speed >= LEARN_KMH
        val vehicles = detector
        // The vehicles first: the lane is looked for around them, not on them.
        val found = vehicles?.detect(buffer, w, h, plane.rowStride, rotation, (band.horizonY + band.bonnetY) / 2)
        val sighting = LaneFinder.find(luma, band, lanes.carX, hfov, found.orEmpty().map { it.box })
        val lane = lanes.update(sighting, now, learn = steady && lanes.laneWidth > 0f)
        dragged?.let {
            horizon.set(it)
            dragged = null
        }
        this.band = band.copy(horizonY = horizon.update(sighting, now, learn = steady))
        // Learned: the driver no longer needs telling to drag the line.
        if (horizon.settled && !horizonSet) {
            horizonSet = true
            prefs.edit().putFloat(KEY_HORIZON, band.horizonY).putBoolean(KEY_HORIZON_SET, true).apply()
        }
        val lead = found?.let { leads.update(it, lane, band, lanes.carX, now) }

        val scene = RoadScene(
            offset = lane.offset,
            lines = lane.lines,
            leadM = lead?.distanceM,
            closingMps = lead?.closingMps,
            ttcS = lead?.ttcS,
            leadKind = lead?.kind?.name?.lowercase(),
            speedKmh = speed,
            vehicles = vehicles != null
        )
        if (now - sentAt >= SEND_EVERY_MS) {
            sentAt = now
            LinkServer.send(scene)
        }
        val warning = rules.update(scene, null, false, now)
        warnHere(warning)

        frames++
        if (now - framesSince >= 1_000L) {
            fps = frames
            frames = 0
            framesSince = now
        }
        _view.value = RoadView(
            frameW = uprightW, frameH = uprightH,
            band = band, lane = lane, lead = lead, warning = warning,
            vehicles = vehicles != null, speedKmh = speed, fps = fps, horizonSet = horizonSet
        )
    }

    /** No car linked: the phone beeps for itself as a warning comes up. A linked car sounds its own. */
    private fun warnHere(warning: RoadWarning?) {
        val before = shownWarning
        shownWarning = warning
        if (warning == null || warning == before) return
        // A collision warning is a moment the recorder keeps, whoever sounds it.
        if (warning == RoadWarning.COLLISION) recorder?.keep("collision")
        if (LinkServer.state.value is LinkState.Connected) return
        val tone = beeper ?: runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 90) }.getOrNull()?.also { beeper = it } ?: return
        when (warning) {
            RoadWarning.COLLISION -> tone.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 1_200)
            RoadWarning.LANE_LEFT, RoadWarning.LANE_RIGHT -> tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 300)
            RoadWarning.TOO_CLOSE, RoadWarning.MOVED_OFF -> tone.startTone(ToneGenerator.TONE_PROP_ACK, 300)
        }
    }

    /** Where the car's place in the lane is kept: the upright phone's apart from the sideways one's. */
    private fun carXKey(tall: Boolean) = if (tall) KEY_CAR_X_TALL else KEY_CAR_X

    private companion object {
        const val PREFS = "road_watch"
        const val KEY_HORIZON = "horizon"
        const val KEY_HORIZON_SET = "horizon_set"
        const val KEY_CAR_X = "car_x"
        const val KEY_CAR_X_TALL = "car_x_tall"
        const val KEY_HFOV = "hfov"
        const val KEY_HFOV_TALL = "hfov_tall"
        /** A phone's main camera across its wide side, and its short one, near enough until the camera says. */
        const val DEFAULT_HFOV = 66f
        const val DEFAULT_HFOV_TALL = 51f
        /** The lane is looked for in a picture this wide: plenty for lines, and quick. */
        const val LUMA_WIDTH = 160
        const val SEND_EVERY_MS = 100L
        const val SPEED_FRESH_MS = 3_000L
        /** Driving steadily on: where the car is in its lane is where the driver keeps it. */
        const val LEARN_KMH = 50f
    }
}
