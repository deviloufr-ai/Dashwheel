package com.openauto.dash.companion

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.util.Size
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.BrightnessLow
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size as GeoSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.openauto.dash.roadwatch.RoadWarning
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Road watch's screen: the phone in its holder behind the windscreen,
 * sideways or upright, the camera's picture full screen with what it reads drawn over it (the lane,
 * the vehicle ahead and how far it is), a line to drag onto the horizon, and
 * a warning across the screen when no car is linked to give it. The screen
 * stays on; it can be dimmed.
 */
class RoadWatchActivity : ComponentActivity() {
    private lateinit var watch: RoadWatch
    private lateinit var frames: ExecutorService
    private var analysis: ImageAnalysis? = null

    private val displays = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        // Turned in its holder (sideways, upright, either way up): no restart, so the frames are told.
        override fun onDisplayChanged(displayId: Int) {
            analysis?.targetRotation = ContextCompat.getDisplayOrDefault(this@RoadWatchActivity).rotation
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        watch = RoadWatch(this)
        frames = Executors.newSingleThreadExecutor()
        getSystemService(DisplayManager::class.java)?.registerDisplayListener(displays, null)
        setContent {
            CompanionTheme {
                RoadWatchScreen(
                    watch,
                    onCamera = { view -> bind(view) },
                    onDim = { dim -> dim(dim) },
                    onModel = { onFrames { watch.modelArrived() } },
                    onClose = { finish() }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // On the frames' thread like stop, so a quick stop and start keep their order.
        onFrames { watch.start() }
    }

    override fun onStop() {
        // On the frames' own thread, after the frame being read.
        onFrames { watch.stop() }
        super.onStop()
    }

    override fun onDestroy() {
        getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displays)
        analysis?.clearAnalyzer()
        frames.execute { watch.close() }
        frames.shutdown()
        super.onDestroy()
    }

    /** [work] on the frames' thread, unless the screen is already gone. */
    private fun onFrames(work: () -> Unit) {
        if (!frames.isShutdown) frames.execute(work)
    }

    private fun dim(on: Boolean) {
        window.attributes = window.attributes.apply {
            screenBrightness = if (on) 0.02f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    private fun bind(view: PreviewView) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = runCatching { future.get() }.getOrNull() ?: return@addListener
            val fourByThree = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build()
            val preview = Preview.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()
                )
                .build()
                .also { it.setSurfaceProvider(view.surfaceProvider) }
            val reader = ImageAnalysis.Builder()
                .setResolutionSelector(fourByThree)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { it.setAnalyzer(frames, watch) }
            reader.targetRotation = ContextCompat.getDisplayOrDefault(this@RoadWatchActivity).rotation
            analysis = reader
            provider.unbindAll()
            val camera = runCatching {
                provider.bindToLifecycle(this@RoadWatchActivity, CameraSelector.DEFAULT_BACK_CAMERA, preview, reader)
            }.getOrNull() ?: return@addListener
            fieldOfView(camera)?.let { (wide, tall) -> watch.fieldOfView(wide, tall) }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * The camera's view across its long side and across its short one: the
     * upright frame's width with the phone sideways, then with it upright.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun fieldOfView(camera: Camera): Pair<Float, Float>? = runCatching {
        val info = Camera2CameraInfo.from(camera.cameraInfo)
        val focal = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return null
        val sensor = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        val wide = viewDegrees(max(sensor.width, sensor.height), focal) ?: return null
        val tall = viewDegrees(min(sensor.width, sensor.height), focal) ?: return null
        wide to tall
    }.getOrNull()

    private fun viewDegrees(sensorMm: Float, focalMm: Float): Float? =
        Math.toDegrees(2 * atan(sensorMm / (2.0 * focalMm))).toFloat().takeIf { it in 25f..120f }

    companion object {
        fun open(context: Context) {
            context.startActivity(Intent(context, RoadWatchActivity::class.java))
        }
    }
}

@Composable
private fun RoadWatchScreen(
    watch: RoadWatch,
    onCamera: (PreviewView) -> Unit,
    onDim: (Boolean) -> Unit,
    onModel: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            CameraBinder(onCamera)
            RoadDrawing(watch)
        } else {
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.road_camera_needed), color = Color.White)
                Spacer(Modifier.padding(6.dp))
                Button(onClick = { ask.launch(Manifest.permission.CAMERA) }) { Text(stringResource(R.string.road_allow_camera)) }
            }
        }
        RoadControls(watch, onDim, onModel, onClose)
    }
}

/** The camera's picture, bound once its view exists; the camera then follows the screen's life. */
@Composable
private fun CameraBinder(onCamera: (PreviewView) -> Unit) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx -> PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }.also(onCamera) }
    )
}

/** Where the upright frame is drawn on a [w] x [h] screen, fitted in the middle as the preview is. */
private fun frameRect(w: Float, h: Float, frameW: Int, frameH: Int): Rect {
    val scale = minOf(w / frameW, h / frameH)
    val fw = frameW * scale
    val fh = frameH * scale
    return Rect(Offset((w - fw) / 2, (h - fh) / 2), GeoSize(fw, fh))
}

@Composable
private fun RoadDrawing(watch: RoadWatch) {
    val view by watch.view.collectAsState()
    var horizon by remember { mutableFloatStateOf(watch.band.horizonY) }
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = with(density) { maxWidth.toPx() }
        val h = with(density) { maxHeight.toPx() }
        val frame = frameRect(w, h, view.frameW, view.frameH)
        fun at(x: Float, y: Float) = Offset(frame.left + x * frame.width, frame.top + y * frame.height)
        val warn = view.warning
        Canvas(
            Modifier.fillMaxSize().pointerInput(frame) {
                detectVerticalDragGestures(onDragEnd = { watch.setHorizon(horizon) }) { _, dy ->
                    horizon = (horizon + dy / frame.height).coerceIn(0.25f, 0.7f)
                }
            }
        ) {
            // The horizon, to drag where the road meets the sky.
            val hy = frame.top + horizon * frame.height
            drawLine(
                Color.White.copy(alpha = 0.7f), Offset(frame.left, hy), Offset(frame.right, hy), strokeWidth = 3f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f))
            )
            val band = view.band
            val laneColor = when (warn) {
                RoadWarning.LANE_LEFT, RoadWarning.LANE_RIGHT -> CompanionColors.Amber
                else -> CompanionColors.Teal
            }
            listOfNotNull(view.lane.left, view.lane.right).forEach { line ->
                drawLine(laneColor, at(line.topX, band.topY), at(line.bottomX, band.bonnetY), strokeWidth = 10f)
            }
            view.lead?.let { lead ->
                val b = lead.box
                val color = if (warn == RoadWarning.COLLISION || warn == RoadWarning.TOO_CLOSE) CompanionColors.Red else Color.White
                drawRect(
                    color, topLeft = at(b.left, b.top),
                    size = GeoSize(b.width * frame.width, b.height * frame.height), style = Stroke(width = 6f)
                )
            }
            if (warn == RoadWarning.COLLISION) drawRect(CompanionColors.Red.copy(alpha = 0.28f), frame.topLeft, frame.size)
        }
        view.lead?.let { lead ->
            val p = at(lead.box.left, lead.box.bottom)
            Surface(
                color = Color.Black.copy(alpha = 0.6f), shape = RoundedCornerShape(8.dp),
                modifier = Modifier.padding(
                    start = with(density) { p.x.coerceAtLeast(0f).toDp() },
                    top = with(density) { (p.y + 6f).coerceAtMost(h - 120f).toDp() }
                )
            ) {
                Text(
                    stringResource(R.string.road_lead_m, lead.distanceM.roundToInt()), color = Color.White,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
        warn?.let { RoadWarningBanner(it, Modifier.align(Alignment.Center)) }
    }
}

@Composable
private fun RoadWarningBanner(warning: RoadWarning, modifier: Modifier) {
    val text = stringResource(
        when (warning) {
            RoadWarning.COLLISION -> R.string.road_warn_collision
            RoadWarning.LANE_LEFT, RoadWarning.LANE_RIGHT -> R.string.road_warn_lane
            RoadWarning.TOO_CLOSE -> R.string.road_warn_close
            RoadWarning.MOVED_OFF -> R.string.road_warn_moved_off
        }
    )
    val tint = if (warning == RoadWarning.COLLISION) CompanionColors.Red else CompanionColors.Amber
    Surface(color = tint, shape = RoundedCornerShape(18.dp), modifier = modifier) {
        Text(
            text, color = CompanionColors.Background, style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 28.dp, vertical = 14.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoadControls(watch: RoadWatch, onDim: (Boolean) -> Unit, onModel: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val view by watch.view.collectAsState()
    val link by LinkServer.state.collectAsState()
    val model by RoadModel.state.collectAsState()
    var dimmed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { RoadModel.check(context) }
    Box(Modifier.fillMaxSize().padding(16.dp)) {
        // What it reads, top left: the car it warns, the lane, the vehicle finder.
        Surface(color = Color.Black.copy(alpha = 0.55f), shape = RoundedCornerShape(12.dp), modifier = Modifier.align(Alignment.TopStart)) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                val l = link
                Text(
                    if (l is LinkState.Connected) stringResource(R.string.road_linked, l.unitName) else stringResource(R.string.road_not_linked),
                    color = Color.White, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold
                )
                Text(
                    if (view.lane.lines > 0) stringResource(R.string.road_lane_seen) else stringResource(R.string.road_lane_looking),
                    color = CompanionColors.Muted, style = MaterialTheme.typography.bodySmall
                )
                view.speedKmh?.let {
                    Text(stringResource(R.string.road_speed, it.roundToInt()), color = CompanionColors.Muted, style = MaterialTheme.typography.bodySmall)
                }
                if (view.frameW > 4) Text(stringResource(R.string.road_horizon_hint), color = CompanionColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        // The buttons wrap onto a second line on an upright phone.
        FlowRow(
            Modifier.align(Alignment.BottomEnd),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when (val m = model) {
                RoadModel.State.Ready -> Unit
                is RoadModel.State.Getting -> Text(
                    stringResource(R.string.road_model_getting, (m.done * 100).roundToInt()), color = Color.White,
                    style = MaterialTheme.typography.bodySmall
                )
                else -> FilledTonalButton(onClick = { scope.launch { if (RoadModel.get(context)) onModel() } }) {
                    Icon(Icons.Filled.DirectionsCar, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (m == RoadModel.State.Failed) R.string.road_model_retry else R.string.road_model_get))
                }
            }
            FilledTonalButton(onClick = { dimmed = !dimmed; onDim(dimmed) }) {
                Icon(if (dimmed) Icons.Filled.BrightnessHigh else Icons.Filled.BrightnessLow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (dimmed) R.string.road_bright else R.string.road_dim))
            }
            Button(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.road_stop))
            }
        }
    }
}
