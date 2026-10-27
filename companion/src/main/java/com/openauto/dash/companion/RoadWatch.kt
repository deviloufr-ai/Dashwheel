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
import com.openauto.dash.roadwatch.Mount
import com.openauto.dash.roadwatch.LaneState
import com.openauto.dash.roadwatch.LaneTracker
import com.openauto.dash.roadwatch.Lead
import com.openauto.dash.roadwatch.LeadTracker
import com.openauto.dash.roadwatch.Luma
import com.openauto.dash.roadwatch.RoadBand
import com.openauto.dash.roadwatch.RoadRules
import com.openauto.dash.roadwatch.RoadWarning
import com.openauto.dash.roadwatch.SignFinder
import com.openauto.dash.roadwatch.SignKind
import com.openauto.dash.roadwatch.Upright
import com.openauto.dash.roadwatch.Detection
import com.openauto.dash.roadwatch.withBefore
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
    val horizonSet: Boolean = true,
    /** The speed limit last read off a sign, km/h. */
    val signKmh: Int? = null,
    /** The sign ending the limit was just passed: shown for a moment in the limit's place. */
    val signEnd: Boolean = false,
    /** The camera's view across the picture's height, degrees: where gravity puts the horizon in it. */
    val vfovDeg: Float = 46f,
    /** While the driver places the phone: where the dashboard starts and how much of the side the pillar covers ([Mount]). */
    val dashboardTop: Float? = null,
    val pillar: Float? = null
)

class RoadWatch(context: Context) : ImageAnalysis.Analyzer {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile var band = RoadBand(horizonY = prefs.getFloat(KEY_HORIZON, 0.5f), bonnetY = prefs.getFloat(KEY_BONNET, RoadBand().bonnetY))
        private set
    /** The dashboard's top found when the phone was placed, not yet in [band], which the camera's thread alone writes. */
    @Volatile private var newBonnet: Float? = null
    /** The driver is placing the phone: the picture is looked at for the dashboard and the pillar. */
    @Volatile var placing = false
    private var dashboardTop: Float? = null
    private var pillar: Float? = null
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
    /** Speed-limit signs: found every few frames, their number read by ML Kit. */
    private val signs = SignReader(SignReader.dir(app))
    private var frameCount = 0L
    /** A sign was seen: until then, signs are looked for on every frame, not every few. */
    private var signBurstUntil = 0L
    /** How long each part of a frame takes, ms (running averages): for the speed report. */
    private val took = FloatArray(4)
    /** The detector looks at the horizon closely every other frame ([withBefore]); what it found on the frame before. */
    private var horizonLook = false
    private var lookedBefore: List<Detection> = emptyList()
    @Volatile private var detectorTried = false
    /**
     * The first frames time the vehicle finder on the graphics chip, the next
     * ones on the processor, and the faster stays: on the S25 in a warm car
     * the graphics chip took 106 ms a frame (2026-10-26), against 48 ms cool.
     */
    private var trial = Trial.GPU
    private var trialFrames = 0
    private var gpuMs = 0f
    private enum class Trial { GPU, CPU, DONE }

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

    /** Which way gravity pointed in the phone when the driver last placed it; null until placed once. */
    fun placedUp(): FloatArray? =
        prefs.getString(KEY_PLACED_UP, null)?.split(',')?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 3 }?.toFloatArray()

    /** The driver placed the phone with gravity pointing [up] in it: turned from there, it has moved. */
    fun placed(up: FloatArray) {
        prefs.edit().putString(KEY_PLACED_UP, up.joinToString(",")).apply()
    }

    /** Where the dashboard starts in the picture, found when the phone was placed: the road's lines stop there. */
    fun setBonnet(y: Float) {
        newBonnet = y.coerceIn(0.6f, 0.95f)
        prefs.edit().putFloat(KEY_BONNET, newBonnet!!).apply()
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
        PhoneHeading.start(app)
    }

    /** The camera stopped: nothing more goes to the car, and the car's warnings end with the scenes. */
    fun stop() {
        locations?.let { runCatching { it.removeUpdates(listener) } }
        locations = null
        RoadSigns.clear()
        PhoneHeading.stop(app)
        // The horizon only once learned: a few seconds of single sightings would start the next drive
        // there, and a wrong start keeps away the lines that would correct it. A drag saves itself.
        val edit = prefs.edit().putFloat(carXKey(tall), lanes.carX)
        if (horizon.settled) edit.putFloat(KEY_HORIZON, band.horizonY)
        edit.apply()
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
        signs.close()
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
        val started = SystemClock.elapsedRealtimeNanos()
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rotation = image.imageInfo.rotationDegrees
        val w = image.width
        val h = image.height
        newBonnet?.let {
            band = band.copy(bonnetY = it)
            newBonnet = null
        }
        val band = band
        if (!detectorTried) {
            detectorTried = true
            detector?.close()
            detector = RoadDetector.open(app)
            trial = if (detector?.onGpu == true) Trial.GPU else Trial.DONE
            trialFrames = 0
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
        leads.aspect = uprightH.toFloat() / uprightW
        recorder?.rotation(rotation)

        val luma = Luma.fromRgba(buffer, w, h, plane.rowStride, rotation, LUMA_WIDTH)
        if (placing && frameCount % PLACING_EVERY == 0L) {
            dashboardTop = Mount.dashboardTop(luma, band.horizonY)
            pillar = Mount.pillar(luma, band.horizonY)
        } else if (!placing) {
            dashboardTop = null
            pillar = null
        }
        val speed = speedKmh?.takeIf { now - speedAt < SPEED_FRESH_MS }
        val steady = speed != null && speed >= LEARN_KMH
        val vehicles = detector
        val beforeVehicles = SystemClock.elapsedRealtimeNanos()
        // The vehicles first: the lane is looked for around them, not on them.
        val found = vehicles?.detect(buffer, w, h, plane.rowStride, rotation, (band.horizonY + band.bonnetY) / 2, band.horizonY.takeIf { horizonLook })
            ?.let { now -> withBefore(now, lookedBefore).also { lookedBefore = now } }
        horizonLook = !horizonLook
        if (vehicles != null && trial != Trial.DONE && ++trialFrames >= TRIAL_FRAMES) {
            trialFrames = 0
            if (trial == Trial.GPU) {
                gpuMs = vehicles.runMs
                vehicles.close()
                detector = RoadDetector.open(app, onGpu = false)
                trial = Trial.CPU
            } else {
                // The processor's time against the graphics chip's: back to the chip if it was faster.
                if (gpuMs < vehicles.runMs) {
                    vehicles.close()
                    detector = RoadDetector.open(app, onGpu = true)
                }
                RoadStats.trial(gpuMs, vehicles.runMs)
                trial = Trial.DONE
            }
        }
        val beforeSigns = SystemClock.elapsedRealtimeNanos()
        // A round red sign big enough to read: the biggest one goes to the reader. Once one is seen, every
        // frame is looked at for a while: at 80 km/h a sign is big enough to read for under a second.
        // The sign that ends a limit needs no reading: seen, it goes to the reader's count straight away.
        if (frameCount++ % SIGN_EVERY == 0L || now < signBurstUntil) {
            val spots = SignFinder.find(buffer, w, h, plane.rowStride, rotation, band.bonnetY)
                .filter { it.kind == SignKind.END || it.sidePx >= SignFinder.MIN_SIDE_PX }
            if (spots.isNotEmpty()) signBurstUntil = now + SIGN_BURST_MS
            spots.filter { it.kind == SignKind.LIMIT }.maxByOrNull { it.sidePx }?.let { signs.read(buffer, w, h, plane.rowStride, rotation, it) }
            if (spots.any { it.kind == SignKind.END }) signs.sawEnd()
        }
        val beforeLane = SystemClock.elapsedRealtimeNanos()
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
            vehicles = vehicles != null,
            signKmh = RoadSigns.fresh(SIGN_SEND_MS, now),
            signEnd = RoadSigns.endFresh(SIGN_SEND_MS, now)
        )
        if (now - sentAt >= SEND_EVERY_MS) {
            sentAt = now
            LinkServer.send(scene)
        }
        val warning = rules.update(scene, null, false, now)
        warnHere(warning)

        val done = SystemClock.elapsedRealtimeNanos()
        val parts = floatArrayOf((beforeSigns - beforeVehicles) / 1e6f, (beforeLane - beforeSigns) / 1e6f, (done - beforeLane) / 1e6f, (done - started) / 1e6f)
        for (k in took.indices) took[k] = if (took[k] == 0f) parts[k] else took[k] * 0.9f + parts[k] * 0.1f
        frames++
        if (now - framesSince >= 1_000L) {
            fps = frames
            frames = 0
            framesSince = now
            RoadStats.update(fps, vehicles, took)
        }
        _view.value = RoadView(
            frameW = uprightW, frameH = uprightH,
            band = band, lane = lane, lead = lead, warning = warning,
            vehicles = vehicles != null, speedKmh = speed, fps = fps, horizonSet = horizonSet,
            signKmh = RoadSigns.kmh,
            signEnd = RoadSigns.endFresh(SIGN_SHOW_MS, now),
            vfovDeg = if (tall) hfovWide else hfovTall,
            dashboardTop = dashboardTop,
            pillar = pillar
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
        const val KEY_BONNET = "bonnet"
        const val KEY_PLACED_UP = "placed_up"
        /** While the phone is being placed, the picture is looked at on every this many frames. */
        const val PLACING_EVERY = 5L
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
        /** Signs are looked for on every this many frames: a sign stays in view for a second or more. */
        const val SIGN_EVERY = 3L
        /** A sign's limit goes to the car for this long after it is read; so does the end of one. */
        const val SIGN_SEND_MS = 2_000L
        /** The end sign stays on the phone's screen this long, where the limit was. */
        const val SIGN_SHOW_MS = 4_000L
        /** Once a sign is seen, every frame is looked at for this long. */
        const val SIGN_BURST_MS = 1_500L
        /** Frames the vehicle finder is timed on each of the graphics chip and the processor. */
        const val TRIAL_FRAMES = 40
    }
}

/** How Road watch last ran, for the driver's computer ([ClipServer], GET /roadwatch): its speed and where the model ran. */
object RoadStats {
    @Volatile private var fps = 0
    @Volatile private var runMs = 0f
    @Volatile private var gpu: Boolean? = null
    @Volatile private var at = 0L
    /** Vehicles, signs, lane and the rest, the whole frame, ms. */
    @Volatile private var took = FloatArray(4)
    /** The vehicle finder's time on the graphics chip and on the processor when Road watch started, ms. */
    @Volatile private var gpuMs = 0f
    @Volatile private var cpuMs = 0f
    /** The camera's zoom. */
    @Volatile var zoom = 1f

    fun trial(gpuMs: Float, cpuMs: Float) {
        this.gpuMs = gpuMs
        this.cpuMs = cpuMs
    }

    fun update(fps: Int, detector: RoadDetector?, took: FloatArray) {
        this.fps = fps
        runMs = detector?.runMs ?: 0f
        gpu = detector?.onGpu
        this.took = took.copyOf()
        at = System.currentTimeMillis()
    }

    fun json(): org.json.JSONObject = org.json.JSONObject()
        .put("fps", fps)
        .put("modelMs", runMs.toDouble())
        .put("gpu", gpu)
        .put("signKmh", RoadSigns.kmh)
        .put("signEndAgoMs", if (RoadSigns.endAt == 0L) null else SystemClock.elapsedRealtime() - RoadSigns.endAt)
        .put("vehiclesMs", took[0].toDouble())
        .put("signsMs", took[1].toDouble())
        .put("laneMs", took[2].toDouble())
        .put("frameMs", took[3].toDouble())
        .put("trialGpuMs", gpuMs.toDouble())
        .put("trialCpuMs", cpuMs.toDouble())
        .put("zoom", zoom.toDouble())
        .put("at", at)
}
