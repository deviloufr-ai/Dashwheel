package com.openauto.dash

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.view.Gravity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/*
 * Dashwheel's reverse view, over the ROM's reversing camera (com.qf.backcar,
 * which keeps showing the picture): guide lines coloured by distance, lines
 * that bend with the steering wheel, the parking radar and the driver's car
 * from above. Drawn through the accessibility service, the only window above
 * the camera ([AlertWindow]); up while reverse is engaged, gone with it.
 *
 * The steering angle comes from the car app's own broadcast for its guide
 * lines ([CarBox.steering]), which it only sends while those lines are on.
 * With them hidden, it's read from the car app's log of the CAN box's frames
 * instead (root, see [StockLines]).
 */

internal enum class ReverseLayout(val titleRes: Int) {
    BOTH(R.string.reverse_layout_both),
    CAMERA(R.string.reverse_layout_camera),
    RADAR(R.string.reverse_layout_radar)
}

/**
 * Where the guide lines sit on the camera picture, set on the reverse screen
 * itself: [shift] moves them sideways (a share of the screen width), [bumper]
 * and [far] are the heights of the 0 m and 3 m lines, [width] the car's width
 * at the bumper; [mirror] bends the steering lines the other way.
 */
internal data class ReverseCalibration(
    val shift: Float = 0f,
    val bumper: Float = 0.94f,
    val far: Float = 0.50f,
    val width: Float = 0.60f,
    val mirror: Boolean = false
)

/** The car's shape for the lines, in metres: the user's C4 Picasso until the car profile carries it. */
internal object CarShape {
    const val WIDTH = 1.83f
    const val LENGTH = 4.59f
    const val WHEELBASE = 2.73f
    const val REAR_OVERHANG = 0.95f
    /** Steering wheel degrees per road wheel degree: 545° at full lock, about 33° at the wheels. */
    const val STEERING_RATIO = 16.5f
}

internal object ReverseView {
    private const val TAG = "ReverseView"
    private const val PREFS = "reverse_view"
    private const val PREVIEW_MS = 20_000L
    /** The C4 Picasso's tailgate, measured from its hinge to its edge: about 95 cm behind the bumper when open. */
    const val DEFAULT_TAILGATE_M = 0.95f

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false
    private var window: AlertWindow? = null
    private var previewJob: Job? = null

    private val _on = MutableStateFlow(false)
    val on: StateFlow<Boolean> = _on.asStateFlow()

    private val _layout = MutableStateFlow(ReverseLayout.BOTH)
    val layout: StateFlow<ReverseLayout> = _layout.asStateFlow()

    private val _calibration = MutableStateFlow(ReverseCalibration())
    val calibration: StateFlow<ReverseCalibration> = _calibration.asStateFlow()

    private val _ownCamera = MutableStateFlow(false)
    /** Experimental: Dashwheel shows the camera's picture itself ([ReverseCamera]). */
    val ownCamera: StateFlow<Boolean> = _ownCamera.asStateFlow()

    private val _hideStock = MutableStateFlow(false)
    /** The car app's own lines are off: the angle is read from its log. */
    val hideStock: StateFlow<Boolean> = _hideStock.asStateFlow()

    private val _tailgate = MutableStateFlow(DEFAULT_TAILGATE_M)
    /** Room the tailgate needs behind the bumper to open, in metres; 0 hides its line. */
    val tailgate: StateFlow<Float> = _tailgate.asStateFlow()

    /** The lines are being lined up on the reverse screen. */
    val adjusting = MutableStateFlow(false)

    private val previewSteering = MutableStateFlow<Float?>(null)
    private val previewRadar = MutableStateFlow<Radar?>(null)
    private val _preview = MutableStateFlow(false)
    /** Tried from the settings: made-up steering and sensors, no camera. */
    val preview: StateFlow<Boolean> = _preview.asStateFlow()

    /** The reverse view is wanted on screen now. */
    val wanted: StateFlow<Boolean> =
        combine(_on, CarBox.reversing, _preview) { on, reversing, preview -> preview || on && reversing }
            .stateIn(scope, SharingStarted.Eagerly, false)

    private val _covering = MutableStateFlow(false)
    /** The reverse view is up: the radar alert stays away ([RadarOverlay]). */
    val covering: StateFlow<Boolean> = _covering.asStateFlow()

    /** Steering wheel degrees, right positive, mirrored if asked. */
    val steering: StateFlow<Float?> =
        combine(CarBox.steering, previewSteering, _preview, _calibration) { real, fake, preview, cal ->
            (if (preview) fake else real)?.let { if (cal.mirror) -it else it }
        }.stateIn(scope, SharingStarted.Eagerly, null)

    val radar: StateFlow<Radar?> =
        combine(CarBox.radar, previewRadar, _preview) { real, fake, preview -> if (preview) fake else real }
            .stateIn(scope, SharingStarted.Eagerly, null)

    fun start(context: Context) {
        if (started) return
        started = true
        val app = AppLanguage.wrap(context.applicationContext)
        load(app)
        ParkingMotion.start()
        ReverseCamera.watch(app)
        scope.launch {
            wanted.collect { want ->
                if (want) {
                    val w = window ?: AlertWindow(app, "reverse", Gravity.TOP or Gravity.START, aboveCamera = { true }).also { window = it }
                    _covering.value = w.canShow() && w.show(AlertStyle.FULL) { ReverseScreen() }
                } else {
                    adjusting.value = false
                    ReverseCamera.reverseEnded()
                    window?.hide()
                    _covering.value = false
                }
            }
        }
        // The car app's log is only read while it's needed: reversing with its lines hidden.
        scope.launch {
            combine(CarBox.reversing, _hideStock, _on) { r, hide, on -> r && hide && on }.collect { read ->
                if (read) StockLines.readAngles(app) else StockLines.stopReading()
            }
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(context: Context) {
        val p = prefs(context)
        _on.value = p.getBoolean("on", false)
        _layout.value = runCatching { ReverseLayout.valueOf(p.getString("layout", null) ?: "") }.getOrDefault(ReverseLayout.BOTH)
        _hideStock.value = p.getBoolean("hide_stock", false)
        _ownCamera.value = p.getBoolean("own_camera", false)
        _tailgate.value = p.getFloat("tailgate", DEFAULT_TAILGATE_M)
        val d = ReverseCalibration()
        _calibration.value = ReverseCalibration(
            shift = p.getFloat("shift", d.shift),
            bumper = p.getFloat("bumper", d.bumper),
            far = p.getFloat("far", d.far),
            width = p.getFloat("width", d.width),
            mirror = p.getBoolean("mirror", d.mirror)
        )
    }

    fun setOn(context: Context, on: Boolean) {
        _on.value = on
        prefs(context).edit().putBoolean("on", on).apply()
        // The radar arrives with the car app's sharing, which this switch may be the first to need.
        if (on) CarBox.register(context)
    }

    fun setTailgate(context: Context, metres: Float) {
        _tailgate.value = metres
        prefs(context).edit().putFloat("tailgate", metres).apply()
    }

    fun setOwnCamera(context: Context, own: Boolean) {
        _ownCamera.value = own
        prefs(context).edit().putBoolean("own_camera", own).apply()
    }

    fun setLayout(context: Context, layout: ReverseLayout) {
        _layout.value = layout
        prefs(context).edit().putString("layout", layout.name).apply()
    }

    fun setCalibration(context: Context, c: ReverseCalibration) {
        val fixed = c.copy(
            shift = c.shift.coerceIn(-0.3f, 0.3f),
            bumper = c.bumper.coerceIn(0.5f, 1.1f),
            far = c.far.coerceIn(0.1f, c.bumper.coerceIn(0.5f, 1.1f) - 0.05f),
            width = c.width.coerceIn(0.2f, 1.2f)
        )
        _calibration.value = fixed
        prefs(context).edit()
            .putFloat("shift", fixed.shift).putFloat("bumper", fixed.bumper).putFloat("far", fixed.far)
            .putFloat("width", fixed.width).putBoolean("mirror", fixed.mirror)
            .apply()
    }

    /** Hides or brings back the car app's lines; false when it couldn't (no root). */
    suspend fun setHideStock(context: Context, hide: Boolean): Boolean {
        val ok = StockLines.set(context, hide)
        if (ok) {
            _hideStock.value = hide
            prefs(context).edit().putBoolean("hide_stock", hide).apply()
        }
        return ok
    }

    /** Twenty seconds of a made-up parking manoeuvre, without the camera. */
    fun tryIt() {
        previewJob?.cancel()
        _preview.value = true
        ParkingMotion.previewBegin()
        previewJob = scope.launch {
            val t0 = System.currentTimeMillis()
            while (isActive) {
                val t = (System.currentTimeMillis() - t0).toFloat()
                if (t > PREVIEW_MS) break
                previewSteering.value = 380f * sin(t / 8_000f * 2f * PI.toFloat())
                // Backing up 3 m in the 20 s, slowly.
                ParkingMotion.previewStep(-0.015f)
                // Something closing in behind, slightly right of the middle.
                val level = (10f - t / PREVIEW_MS * 9.5f).toInt().coerceIn(1, 10)
                previewRadar.value = Radar(
                    front = List(6) { null },
                    rear = listOf(null, (level + 4).clear(), (level + 1).clear(), level, (level + 3).clear(), null),
                    left = List(4) { null },
                    right = List(4) { null }
                )
                delay(100)
            }
            stopTrying()
        }
    }

    fun stopTrying() {
        previewJob?.cancel()
        previewJob = null
        if (_preview.value) ParkingMotion.previewEnd()
        _preview.value = false
        previewSteering.value = null
        previewRadar.value = null
    }

    /** Beyond the farthest level a sensor sees nothing. */
    private fun Int.clear(): Int = if (this > Radar.MAX_LEVEL) 0 else this

    internal fun log(msg: String, e: Throwable? = null) = Log.w(TAG, msg, e)
}

/**
 * The car app's own guide lines on the camera: its moving lines (the global
 * KeyTrackSwitch, which also stops its steering broadcast) and its fixed ones
 * (persist.sys.trace_enable). Hidden, the angle is read from the car app's
 * log, where it writes every frame from the CAN box: 0x29 (0.1°, left
 * positive) around reverse, 0x7d 08 (whole degrees, right positive) whenever
 * the wheel turns. Reading another app's log takes READ_LOGS, granted here
 * through the root shell.
 */
internal object StockLines {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reader: Job? = null
    private var process: Process? = null

    private val FRAMES = Regex("""fd 0?5 29 ([0-9a-f]{2}) ([0-9a-f]{2})|fd 0?6 7d 08 ([0-9a-f]{2}) ([0-9a-f]{2})""")

    suspend fun set(context: Context, hide: Boolean): Boolean = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        runCatching {
            if (hide) {
                DockShell.shell(app, "pm grant ${app.packageName} ${Manifest.permission.READ_LOGS}")
                check(app.checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED) { "no log access" }
            }
            DockShell.shell(app, "setprop persist.sys.trace_enable ${!hide}")
            check(RomPopups.writeGlobals(app, mapOf("KeyTrackSwitch" to if (hide) 0 else 1))) { "switch unchanged" }
        }.onFailure { ReverseView.log("could not ${if (hide) "hide" else "show"} the car app's lines", it) }.isSuccess
    }

    fun readAngles(context: Context) {
        if (reader?.isActive == true) return
        if (context.checkSelfPermission(Manifest.permission.READ_LOGS) != PackageManager.PERMISSION_GRANTED) return
        reader = scope.launch {
            runCatching {
                // Only what's written from now on, only the car app's frames.
                val p = ProcessBuilder("logcat", "-v", "raw", "-T", "1", "[VehicleService]:D", "*:S").redirectErrorStream(true).start()
                process = p
                BufferedReader(InputStreamReader(p.inputStream)).use { lines ->
                    while (isActive) {
                        val line = lines.readLine() ?: break
                        if (!line.contains("readdata")) continue
                        // A line can hold several frames: the last angle wins.
                        var deg: Float? = null
                        for (m in FRAMES.findAll(line)) {
                            val g = m.groupValues
                            deg = if (g[1].isNotEmpty()) -int16(g[1], g[2]) / 10f else int16(g[3], g[4]).toFloat()
                        }
                        deg?.let { CarBox.steeringWrite(it) }
                    }
                }
            }.onFailure { ReverseView.log("log reader stopped", it) }
        }
    }

    fun stopReading() {
        reader?.cancel()
        reader = null
        process?.destroy()
        process = null
    }

    private fun int16(lo: String, hi: String): Int = ((hi.toInt(16) shl 8) or lo.toInt(16)).toShort().toInt()
}

// --- Geometry ---------------------------------------------------------------------------

/** How far the guide lines reach behind the bumper, in metres. */
private const val FAR_M = 3f
/** A made-up camera distance that gives the lines their perspective. */
private const val Z0 = 1f

/**
 * The ground behind the car on the camera picture: [at] puts a point [x]
 * metres right of the car's middle and [z] metres behind the bumper on screen.
 */
internal class Ground(w: Float, h: Float, c: ReverseCalibration) {
    private val cx = w * (0.5f + c.shift)
    private val bottom = h * c.bumper
    private val horizon: Float
    private val halfWidth = w * c.width / 2f

    init {
        val k = Z0 / (FAR_M + Z0)
        horizon = (h * c.far - bottom * k) / (1f - k)
    }

    fun at(x: Float, z: Float): Offset {
        val f = Z0 / (z.coerceAtLeast(0f) + Z0)
        return Offset(cx + x / (CarShape.WIDTH / 2f) * halfWidth * f, horizon + (bottom - horizon) * f)
    }
}

/**
 * Where the rear corner on [side] (-1 left, 1 right) goes as the car backs up
 * [reach] metres with the wheel at [steeringDeg]: (metres right, metres behind
 * the bumper where it started). The rear axle turns about a point level with
 * it, the wheelbase over the road wheels' angle away.
 */
private fun cornerPath(steeringDeg: Float, side: Int, reach: Float = 3.2f, n: Int = 32): List<Pair<Float, Float>> {
    val delta = Math.toRadians((steeringDeg / CarShape.STEERING_RATIO).toDouble())
    val a = side * CarShape.WIDTH / 2f
    val b = CarShape.REAR_OVERHANG
    return (0..n).map { i ->
        val s = reach * i / n
        if (abs(delta) < 1e-3) a to s
        else {
            val r = (CarShape.WHEELBASE / tan(delta)).toFloat()
            val phi = s / r
            val dx = a - r
            val x = r + dx * cos(phi) + b * sin(phi)
            val z = -dx * sin(phi) + b * cos(phi)
            x to z - b
        }
    }
}

// --- Drawing ----------------------------------------------------------------------------

/** Fixed colours: the camera picture is the same in every look. */
private object ReverseInk {
    val Red = Color(0xFFE5484D)
    val Amber = Color(0xFFF5A524)
    val Green = Color(0xFF46B36B)
    val Steer = Color(0xFFFFE14D)
    val Shadow = Color(0xB3000000)
    val Panel = Color(0xE6111315)
    val PanelSolid = Color(0xFF111315)
    val Text = Color(0xFFF1EFE8)
    val Dim = Color(0x40FFFFFF)
    val Chip = Color(0x99000000)
    val Selected = Color(0xFF378ADD)
    val Boot = Color(0xFFB794F6)
}

/** A sensor's colour like the radar alert's: red when close, amber, then green; null when clear. */
private fun levelInk(level: Int?): Color? = when {
    level == null || level !in 1..Radar.MAX_LEVEL -> null
    level <= 3 -> ReverseInk.Red
    level <= 6 -> ReverseInk.Amber
    else -> ReverseInk.Green
}

/** How many of a sensor's four bars are lit: all four when something is right there. */
private fun litBars(level: Int?): Int = when {
    level == null || level !in 1..Radar.MAX_LEVEL -> 0
    level <= 3 -> 4
    level <= 6 -> 3
    level <= 8 -> 2
    else -> 1
}

private fun DrawScope.outlined(path: Path, color: Color, width: Float) {
    drawPath(path, ReverseInk.Shadow, style = Stroke(width + 5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun pathOf(points: List<Offset>): Path = Path().apply {
    points.firstOrNull()?.let { moveTo(it.x, it.y) }
    points.drop(1).forEach { lineTo(it.x, it.y) }
}

/** The fixed lines: the car's width out to 3 m, red, amber then green, with a tick at each change. */
private fun DrawScope.fixedLines(g: Ground) {
    val half = CarShape.WIDTH / 2f
    val bands = listOf(Triple(0f, 0.5f, ReverseInk.Red), Triple(0.5f, 1.5f, ReverseInk.Amber), Triple(1.5f, FAR_M, ReverseInk.Green))
    val w = 4.dp.toPx()
    for (side in listOf(-1f, 1f)) {
        for ((from, to, color) in bands) outlined(pathOf(listOf(g.at(side * half, from), g.at(side * half, to))), color, w)
    }
    for ((z, color) in listOf(0.5f to ReverseInk.Red, 1.5f to ReverseInk.Amber, FAR_M to ReverseInk.Green)) {
        for (side in listOf(-1f, 1f)) {
            outlined(pathOf(listOf(g.at(side * half, z), g.at(side * (half - 0.35f), z))), color, w)
        }
    }
}

/** The steering lines: where the rear corners will go, thicker than the fixed ones. */
private fun DrawScope.steeringLines(g: Ground, steeringDeg: Float) {
    for (side in listOf(-1, 1)) {
        val points = cornerPath(steeringDeg, side).takeWhile { it.second <= FAR_M }.filter { it.second >= 0f }.map { (x, z) -> g.at(x, z) }
        if (points.size > 1) outlined(pathOf(points), ReverseInk.Steer, 6.dp.toPx())
    }
}

/**
 * The car from above, nose up, its parking sensors as bars around the
 * bumpers and, behind it, where the rear corners go with the wheel as it is.
 */
private fun DrawScope.fromAbove(radar: Radar?, steeringDeg: Float?, look: CarView?, lookStyle: CarLookStyle, above: Above) {
    // The scene in metres: the car with room around it for the bars (in front only when it has front sensors), more behind for the path.
    val ahead = if (radar?.front.orEmpty().any { it != null }) 1.4f else 0.3f
    val sceneW = CarShape.WIDTH + 2.6f
    val sceneH = CarShape.LENGTH + ahead + 2.4f
    val m = min(size.width / sceneW, size.height / sceneH)
    val cx = size.width / 2f
    val noseY = (size.height - sceneH * m) / 2f + ahead * m
    val tailY = noseY + CarShape.LENGTH * m
    val car = Rect(Offset(cx - CarShape.WIDTH / 2f * m, noseY), Size(CarShape.WIDTH * m, CarShape.LENGTH * m))
    val axleY = tailY - CarShape.REAR_OVERHANG * m
    /** A point of the car (metres right, metres ahead of the rear axle) on screen. */
    fun onScreen(c: Offset) = Offset(cx + c.x * m, axleY - c.y * m)

    above.pose?.let { pose ->
        if (above.ground) groundMap(pose, cx, axleY, m)
        // What the sensors saw on the way, where it is now around the car: red when close to the body.
        for (s in ObstacleMemory.snapshot()) {
            val c = pose.toCar(s.at)
            val dx = (abs(c.x) - CarShape.WIDTH / 2f).coerceAtLeast(0f)
            val dy = when {
                c.y < -CarShape.REAR_OVERHANG -> -CarShape.REAR_OVERHANG - c.y
                c.y > CarShape.LENGTH - CarShape.REAR_OVERHANG -> c.y - (CarShape.LENGTH - CarShape.REAR_OVERHANG)
                else -> 0f
            }
            val gap = kotlin.math.hypot(dx, dy)
            val ink = when {
                gap < 0.3f -> ReverseInk.Red
                gap < 0.6f -> ReverseInk.Amber
                else -> ReverseInk.Text.copy(alpha = 0.7f)
            }
            val at = onScreen(c)
            drawCircle(ReverseInk.Shadow, 0.13f * m, at)
            drawCircle(ink, 0.09f * m, at)
        }
    }

    // Where the rear corners go, faint so the bars stay first.
    steeringDeg?.let { deg ->
        for (side in listOf(-1, 1)) {
            val pts = cornerPath(deg, side, reach = 2f).map { (x, z) -> Offset(cx + x * m, tailY + z * m) }
            drawPath(pathOf(pts), ReverseInk.Steer.copy(alpha = 0.55f), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        }
    }

    sensorBars(radar?.rear.orEmpty(), Offset(cx, tailY - 0.55f * m), m, rear = true)
    sensorBars(radar?.front.orEmpty(), Offset(cx, noseY + 0.55f * m), m, rear = false)
    // Over the bars: it's a limit, not a reading.
    if (above.tailgate > 0f) {
        val y = tailY + above.tailgate * m
        drawLine(
            ReverseInk.Boot, Offset(car.left, y), Offset(car.right, y), 3.dp.toPx(),
            cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
        )
    }

    if (look != null) {
        drawCarView(look, lookStyle, fitCar(look, car), ReverseInk.Selected, ReverseInk.Red)
    } else {
        // The drawn car's body spans 35..105 x 35..225 of its 140 x 260 drawing.
        withTransform({
            translate(car.left - 35f * car.width / 70f, car.top - 35f * car.height / 190f)
            scale(car.width / 70f, car.height / 190f, Offset.Zero)
        }) {
            drawCar(Color(0xFF3A3D42), Color(0x4DF1EFE8), Color(0xFF9A9C9F), ReverseInk.Red, 0f, 0f, 0f, 0f, 0f, 0f)
        }
    }
}

/**
 * Four bars for each sensor the car has, fanned around [center] (behind the
 * car for the rear ones), left to right: lit from the outside in as something
 * gets closer, in its level's colour.
 */
private fun DrawScope.sensorBars(levels: List<Int?>, center: Offset, m: Float, rear: Boolean) {
    if (levels.isEmpty() || levels.all { it == null }) return
    val span = 120f
    val step = span / levels.size
    val thick = 0.2f * m
    levels.forEachIndexed { i, level ->
        if (level == null) return@forEachIndexed
        val start = if (rear) 90f + span / 2f - (i + 1) * step else 270f - span / 2f + i * step
        val lit = litBars(level)
        val ink = levelInk(level)
        for (bar in 0 until 4) {
            // Bar 0 is the outermost; the innermost hugs the bumper.
            val r = (0.95f + (3 - bar) * 0.27f) * m
            val on = bar < lit && ink != null
            drawArc(
                if (on) ink!! else ReverseInk.Dim, startAngle = start + 2f, sweepAngle = step - 4f, useCenter = false,
                topLeft = Offset(center.x - r, center.y - r), size = Size(r * 2, r * 2), style = Stroke(thick)
            )
        }
    }
}

/** What the view from above shows besides the sensors: the manoeuvre's memory and the tailgate's room. */
private class Above(val pose: Pose?, val version: Int, val ground: Boolean, val tailgate: Float)

/** The filmed ground under and around the car, turned with it, the car's rear axle at ([cx], [axleY]), [m] pixels a metre. */
private fun DrawScope.groundMap(pose: Pose, cx: Float, axleY: Float, m: Float) {
    val a = GroundMemory.originX - pose.x
    val b = GroundMemory.originY - pose.y
    val c = cos(pose.heading)
    val s = sin(pose.heading)
    withTransform({
        translate(cx + m * (a * c - b * s), axleY - m * (a * s + b * c))
        rotate(-Math.toDegrees(pose.heading.toDouble()).toFloat(), Offset.Zero)
        scale(m * GroundMemory.RES, m * GroundMemory.RES, Offset.Zero)
    }) {
        drawImage(GroundMemory.image, alpha = 0.9f)
    }
}

/** The tailgate's room on the camera picture: a dashed line where it would touch. */
private fun DrawScope.tailgateLine(g: Ground, metres: Float) {
    val half = CarShape.WIDTH / 2f
    val p = pathOf(listOf(g.at(-half, metres), g.at(half, metres)))
    drawPath(p, ReverseInk.Shadow, style = Stroke(6.dp.toPx(), cap = StrokeCap.Round))
    drawPath(p, ReverseInk.Boot, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f))))
}

/**
 * What the memory adds in words: the gap on a side the car has no sensor
 * for, once something is beside it, and whether the tailgate can still open.
 */
@Composable
private fun MemoryNotes(pose: Pose?, version: Int, tailgate: Float) {
    val p = pose ?: return
    val (left, right) = remember(p, version) { ObstacleMemory.sideGaps(p) }
    val behind = remember(p, version) { ObstacleMemory.behind(p) }
    val live = ReverseView.radar.collectAsState().value?.rear?.filterNotNull()?.filter { it in 1..Radar.MAX_LEVEL }?.minOrNull()?.let { ObstacleMemory.metres(it) }
    val closest = listOfNotNull(behind, live).minOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        left?.takeIf { it < SIDE_WARN_M }?.let { SideGap(stringResource(R.string.reverse_side_left, centimetres(it)), it) }
        right?.takeIf { it < SIDE_WARN_M }?.let { SideGap(stringResource(R.string.reverse_side_right, centimetres(it)), it) }
        if (tailgate > 0f && closest != null && closest < tailgate) {
            Note(stringResource(R.string.reverse_boot_blocked), ReverseInk.Boot)
        }
    }
}

/** Beside the car closer than this, the gap is shown. */
private const val SIDE_WARN_M = 0.8f

/** Rounded to 5 cm: the memory is no finer than that. */
private fun centimetres(m: Float): Int = ((m * 100f / 5f).toInt() * 5).coerceAtLeast(5)

@Composable
private fun SideGap(text: String, gap: Float) = Note(text, if (gap < 0.3f) ReverseInk.Red else if (gap < 0.6f) ReverseInk.Amber else ReverseInk.Text)

@Composable
private fun Note(text: String, ink: Color) {
    Row(
        Modifier.clip(RoundedCornerShape(14.dp)).background(ReverseInk.Chip).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(ink))
        Spacer(Modifier.width(10.dp))
        Text(text, color = ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

// --- Screen -----------------------------------------------------------------------------

@Composable
private fun ReverseScreen() {
    val layout by ReverseView.layout.collectAsState()
    val calibration by ReverseView.calibration.collectAsState()
    val steeringNow by ReverseView.steering.collectAsState()
    val radar by ReverseView.radar.collectAsState()
    val adjusting by ReverseView.adjusting.collectAsState()
    val preview by ReverseView.preview.collectAsState()
    val ownCamera by ReverseView.ownCamera.collectAsState()
    val camera by ReverseCamera.state.collectAsState()
    val romPicture by ReverseCamera.romPicture.collectAsState()
    val own = ownCamera && !preview && camera != ReverseCamera.State.FAILED
    // Nothing over an empty screen: the lines come with the picture, Dashwheel's or the car app's.
    val pictureUp = preview || layout == ReverseLayout.RADAR || (if (own) camera == ReverseCamera.State.LIVE else romPicture)
    val look by MyCarLook.shown.collectAsState()
    val lookStyle by MyCarLook.style.collectAsState()
    // The car app sends the angle in steps: glide between them.
    val steering by animateFloatAsState(steeringNow ?: 0f, tween(180), label = "steering")
    val hasSteering = steeringNow != null
    val tailgate by ReverseView.tailgate.collectAsState()
    val pose by ParkingMotion.pose.collectAsState()
    // Redrawn as the memory and the ground map change.
    val memoryVersion by ObstacleMemory.version.collectAsState()
    val groundVersion by GroundMemory.version.collectAsState()
    val ground by GroundMemory.filled.collectAsState()
    val above = Above(pose, memoryVersion + groundVersion, ground, tailgate)
    val top = look?.top

    Box(Modifier.fillMaxSize()) {
        if (layout == ReverseLayout.RADAR) {
            Canvas(Modifier.fillMaxSize().background(ReverseInk.PanelSolid).padding(24.dp)) {
                fromAbove(radar, steering.takeIf { hasSteering }, top, lookStyle, above)
            }
        } else {
            if (preview) Box(Modifier.fillMaxSize().background(Color(0xFF2B2C2E)))
            // Clear until its first frame: the ROM app's picture shows through meanwhile.
            else if (own) {
                // Black until Dashwheel's picture comes: the car app's screen never shows in between.
                if (camera != ReverseCamera.State.LIVE) Box(Modifier.fillMaxSize().background(Color.Black))
                ReverseCameraPicture(Modifier.fillMaxSize())
            }
            if (pictureUp) Canvas(Modifier.fillMaxSize()) {
                val g = Ground(size.width, size.height, calibration)
                fixedLines(g)
                if (hasSteering) steeringLines(g, steering)
                if (tailgate > 0f) tailgateLine(g, tailgate)
            }
            if (layout == ReverseLayout.BOTH && pictureUp && !adjusting) {
                // In the corner over the far end of the picture, the least useful part when backing up.
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    Canvas(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp)
                            .width(maxWidth * 0.26f)
                            .height(maxHeight * 0.62f)
                            .padding(10.dp)
                    ) { fromAbove(radar, steering.takeIf { hasSteering }, top, lookStyle, above) }
                }
            }
        }
        // Everything along the top, over the far end of the picture: the bottom, right behind the bumper, stays clear.
        if (pictureUp) Column(Modifier.align(Alignment.TopStart).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Chips(layout, adjusting, preview)
            Nearest(radar)
            MemoryNotes(pose, memoryVersion, tailgate)
            if (ownCamera && !preview && camera == ReverseCamera.State.FAILED) {
                Text(
                    stringResource(R.string.reverse_picture_failed), color = ReverseInk.Text, fontSize = 15.sp,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(ReverseInk.Chip).padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }
        // On the right, in the radar's place: the lines and the left of the picture stay in sight while lining them up.
        if (adjusting && pictureUp) Box(Modifier.align(Alignment.TopEnd).padding(16.dp)) { AdjustPanel(calibration) }
        if (preview) {
            Text(
                stringResource(R.string.reverse_preview_badge), color = ReverseInk.Text, fontSize = 15.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).clip(RoundedCornerShape(50)).background(ReverseInk.Chip)
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }
    }
}

/** The closest thing any sensor sees, in its colour: Stop, Close, or just there. */
@Composable
private fun Nearest(radar: Radar?) {
    val level = radar?.closest ?: return
    val ink = levelInk(level) ?: return
    val text = stringResource(
        when {
            level <= 3 -> R.string.reverse_near_stop
            level <= 6 -> R.string.reverse_near_close
            else -> R.string.reverse_near_far
        }
    )
    Row(
        Modifier.clip(RoundedCornerShape(18.dp)).background(ReverseInk.Chip).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(18.dp).clip(CircleShape).background(ink))
        Spacer(Modifier.width(12.dp))
        Text(text, color = ink, fontSize = 30.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Chips(layout: ReverseLayout, adjusting: Boolean, preview: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        ReverseLayout.entries.forEach { l ->
            Chip(stringResource(l.titleRes), selected = l == layout) { ReverseView.setLayout(context, l) }
        }
        if (layout != ReverseLayout.RADAR && !adjusting) {
            Chip(stringResource(R.string.reverse_adjust), selected = false, icon = Icons.Filled.Tune) { ReverseView.adjusting.value = true }
        }
        if (preview) Chip(stringResource(R.string.reverse_close), selected = false) { ReverseView.stopTrying() }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, icon: ImageVector? = null, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    Row(
        Modifier
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(50))
            .background(if (selected) ReverseInk.Selected else ReverseInk.Chip)
            .clickable { tap(); onClick() }
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon?.let {
            Icon(it, contentDescription = null, tint = ReverseInk.Text, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = ReverseInk.Text, fontSize = 18.sp, fontWeight = FontWeight.Medium)
    }
}

/** Lining the lines up with the road, on the camera picture itself. */
@Composable
private fun AdjustPanel(c: ReverseCalibration) {
    val context = androidx.compose.ui.platform.LocalContext.current
    fun set(n: ReverseCalibration) = ReverseView.setCalibration(context, n)
    Column(
        Modifier
            .widthIn(max = 340.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(ReverseInk.Panel)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Stepper(stringResource(R.string.reverse_shift), { set(c.copy(shift = c.shift - 0.01f)) }, { set(c.copy(shift = c.shift + 0.01f)) })
        Stepper(stringResource(R.string.reverse_bumper), { set(c.copy(bumper = c.bumper - 0.01f)) }, { set(c.copy(bumper = c.bumper + 0.01f)) })
        Stepper(stringResource(R.string.reverse_far), { set(c.copy(far = c.far - 0.01f)) }, { set(c.copy(far = c.far + 0.01f)) })
        Stepper(stringResource(R.string.reverse_width), { set(c.copy(width = c.width - 0.01f)) }, { set(c.copy(width = c.width + 0.01f)) })
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Chip(stringResource(R.string.reverse_mirror), selected = c.mirror) { set(c.copy(mirror = !c.mirror)) }
            Chip(stringResource(R.string.reverse_done), selected = true) { ReverseView.adjusting.value = false }
        }
    }
}

@Composable
private fun Stepper(label: String, less: () -> Unit, more: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = ReverseInk.Text, fontSize = 17.sp, modifier = Modifier.weight(1f))
        StepButton(Icons.Filled.Remove, less)
        Spacer(Modifier.width(8.dp))
        StepButton(Icons.Filled.Add, more)
    }
}

@Composable
private fun StepButton(icon: ImageVector, onStep: () -> Unit) {
    Box(
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .border(1.dp, ReverseInk.Text.copy(alpha = 0.4f), CircleShape)
            .clickable { onStep() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = ReverseInk.Text, modifier = Modifier.size(26.dp))
    }
}
