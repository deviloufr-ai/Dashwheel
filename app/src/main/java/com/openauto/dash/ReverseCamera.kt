package com.openauto.dash

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * EXPERIMENTAL: the reversing camera's picture shown by Dashwheel itself,
 * instead of drawing over the ROM's camera app (com.qf.backcar).
 *
 * That app runs at the top priority (-800), so Android never hands its camera
 * to Dashwheel: it has to let go of it. It still opens first on every reverse
 * (it switches the video chip to the camera input and waits for the signal),
 * then Dashwheel, as root, plays it a reverse stop meant for it alone. It
 * ignores a stop while the system says reverse is engaged, so that flag
 * (sys.qf.backcar_state) is cleared for the moment of the stop. Its camera
 * closed, Dashwheel opens it (verified on the K706, 2026-10-01). If anything
 * fails, the ROM app is played a reverse start and shows its picture again.
 */
internal object ReverseCamera {
    private const val TAG = "ReverseCamera"
    private const val ROM_APP = "com.qf.backcar"
    /** The reversing camera: the ROM app's choice on the K706 (camera 0 is the other input). */
    /** The car app's reversing camera on the K706; the driver may pick another ([LearnedSignals.cameraId]). */
    private const val ROM_CAMERA_ID = "1"
    private val CAMERA_ID: String get() = LearnedSignals.cameraId.value ?: ROM_CAMERA_ID
    /** The ROM app's 1080p stream (AHD_1080P25). */
    private const val WIDTH = 1920
    private const val HEIGHT = 1080
    /** The video chip's input switch: 2 is the reversing camera. */
    private const val INPUT_SWITCH = "/sys/class/tp9950_class/tp9950_class_dev/auxvideo_backcvbs_switch"
    /** The ROM app opens the camera about 0.5 s after the reverse key; past this, it's stopped anyway. */
    private const val ROM_OPEN_WAIT_MS = 2_500L
    /** The ROM app closes its camera within about 0.1 s. */
    private const val CLOSE_WAIT_MS = 1_500L
    /** The ROM app's picture shows about this long after it opened the camera (its signal check). */
    private const val ROM_PICTURE_MS = 700L
    /** The camera's first frame came within a second on the K706; past this, it's not coming. */
    private const val FIRST_FRAME_MS = 3_500L
    /** The copy of the picture the ground map is read from. */
    private const val FRAME_W = 320
    private const val FRAME_H = 180
    private const val SAMPLE_MS = 200L
    /** The copy the second screen shows ([mirror]): enough for its stream, at its pace. */
    private const val MIRROR_W = 960
    private const val MIRROR_H = 540
    private const val MIRROR_MS = 80L

    /** TAKING: the car app's picture is still up; OPENING: it's gone, Dashwheel's not there yet. */
    enum class State { OFF, TAKING, OPENING, LIVE, FAILED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(State.OFF)
    val state: StateFlow<State> = _state.asStateFlow()

    private var thread: HandlerThread? = null
    // Written on the camera's thread, let go of on the main one.
    @Volatile private var device: CameraDevice? = null
    @Volatile private var session: CameraCaptureSession? = null
    @Volatile private var surface: Surface? = null
    /**
     * Counts the tries at the picture: [close] ends one, so an answer from the
     * camera that comes back later (its surface gone with the view) is told apart.
     */
    @Volatile private var attempt = 0
    private var opening: Job? = null
    private var watchdog: Job? = null
    private var sampler: Job? = null
    private var frame: Bitmap? = null
    private val _cameraFree = MutableStateFlow(true)
    private val _romPicture = MutableStateFlow(false)
    /** The ROM app's picture is on screen: the lines can go over it ([ReverseView]). */
    val romPicture: StateFlow<Boolean> = _romPicture.asStateFlow()
    private var romPictureJob: Job? = null
    private var watching = false

    private val mirrorWanted = MutableStateFlow(false)
    private val _mirror = MutableStateFlow<ImageBitmap?>(null)
    /** Dashwheel's picture copied for the second screen, about 12 times a second while it's wanted; null otherwise. */
    val mirror: StateFlow<ImageBitmap?> = _mirror.asStateFlow()
    private var mirrorJob: Job? = null
    private var liveView: TextureView? = null

    /** The second screen shows the camera ([ReverseView.secondScreenShown]): [mirror] is kept up to date. */
    fun setMirror(on: Boolean) {
        mirrorWanted.value = on
        if (on) liveView?.let { startMirror(it) } else stopMirror()
    }

    private fun startMirror(view: TextureView) {
        if (!mirrorWanted.value || mirrorJob?.isActive == true) return
        mirrorJob = scope.launch {
            // Three copies in turn: the one the second screen draws is never the one being written.
            val copies = Array(3) { Bitmap.createBitmap(MIRROR_W, MIRROR_H, Bitmap.Config.ARGB_8888) }
            var next = 0
            while (isActive && _state.value == State.LIVE) {
                val b = copies[next]
                next = (next + 1) % copies.size
                if (view.width > 0 && runCatching { view.getBitmap(b) }.isSuccess) _mirror.value = b.asImageBitmap()
                delay(MIRROR_MS)
            }
            _mirror.value = null
        }
    }

    private fun stopMirror() {
        mirrorJob?.cancel()
        mirrorJob = null
        _mirror.value = null
    }

    /**
     * Follows who has the reversing camera: when the ROM app opens it,
     * Dashwheel can take it straight away, and the ROM app's picture is
     * known to be up a moment later.
     */
    fun watch(context: Context) {
        if (watching) return
        watching = true
        val manager = context.applicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        runCatching {
            manager.registerAvailabilityCallback(object : CameraManager.AvailabilityCallback() {
                override fun onCameraAvailable(id: String) {
                    if (id != CAMERA_ID) return
                    _cameraFree.value = true
                    romPictureJob?.cancel()
                    _romPicture.value = false
                }

                override fun onCameraUnavailable(id: String) {
                    if (id != CAMERA_ID) return
                    _cameraFree.value = false
                    if (device != null) return
                    romPictureJob?.cancel()
                    romPictureJob = scope.launch {
                        delay(ROM_PICTURE_MS)
                        if (device == null) _romPicture.value = true
                    }
                }
            }, Handler(android.os.Looper.getMainLooper()))
        }.onFailure { Log.w(TAG, "can't follow the camera", it) }
    }

    /** The ROM app was stopped this reverse: on a failure, it has to be started again. */
    private var romStopped = false

    fun hasPermission(context: Context) =
        context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /** Reverse is over: the next reverse tries again. */
    fun reverseEnded() {
        romStopped = false
        if (_state.value == State.FAILED) _state.value = State.OFF
    }

    /** The picture's view is on screen: once its surface is there, the camera is taken from the ROM app. */
    fun attach(context: Context, view: TextureView) {
        if (_state.value == State.FAILED) return
        val app = context.applicationContext
        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                texture.setDefaultBufferSize(WIDTH, HEIGHT)
                opening?.cancel()
                opening = scope.launch {
                    _state.value = State.TAKING
                    // As soon as the ROM app has the camera, it's asked to let go of it.
                    withTimeoutOrNull(ROM_OPEN_WAIT_MS) { _cameraFree.first { !it } }
                    if (!stopRomCamera(app)) return@launch fail(app, "the car app's camera could not be stopped")
                    withTimeoutOrNull(CLOSE_WAIT_MS) { _cameraFree.first { it } }
                    open(app, texture, retry = true)
                }
            }

            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                close()
                return true
            }

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
                if (_state.value == State.OPENING && device != null) {
                    _state.value = State.LIVE
                    watchdog?.cancel()
                    sample(view)
                    liveView = view
                    startMirror(view)
                }
            }
        }
    }

    /** The picture's view is gone (reverse is over): the camera goes with it. */
    fun detach() {
        close()
        if (_state.value != State.FAILED) _state.value = State.OFF
    }

    /**
     * Has the ROM app close its camera and its screen, as root; the input
     * left on the camera. The reverse flag is put back unless reverse really
     * ended meanwhile.
     */
    private suspend fun stopRomCamera(context: Context): Boolean = runCatching {
        DockShell.shell(context, "setprop sys.qf.backcar_state false; am broadcast -a com.qf.action.BACKCAR_STOP -p $ROM_APP")
        romStopped = true
        delay(300)
        val still = CarBox.reversing.value
        DockShell.shell(context, if (still) "setprop sys.qf.backcar_state true; echo 2 > $INPUT_SWITCH" else "true")
        still
    }.onFailure {
        // Reverse ended meanwhile: not a failure of this try.
        if (it is CancellationException) throw it
        Log.w(TAG, "could not stop the car app's camera", it)
    }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    private fun open(context: Context, texture: SurfaceTexture, retry: Boolean) {
        if (!hasPermission(context)) return fail(context, "no camera permission")
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = runCatching { manager.cameraIdList.let { ids -> if (CAMERA_ID in ids) CAMERA_ID else ids.lastOrNull() } }.getOrNull()
            ?: return fail(context, "no camera")
        val t = thread ?: HandlerThread("ReverseCamera").also { it.start(); thread = it }
        val handler = Handler(t.looper)
        _state.value = State.OPENING
        val mine = attempt
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(FIRST_FRAME_MS)
            if (_state.value == State.OPENING) fail(context, "no picture")
        }
        runCatching {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    // Reverse ended while the camera opened: its surface went with the view.
                    if (mine != attempt) return camera.close()
                    device = camera
                    runCatching {
                        // Inside the guard: a surface let go of this very moment throws here.
                        val s = Surface(texture).also { surface = it }
                        @Suppress("DEPRECATION")
                        camera.createCaptureSession(listOf(s), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(cs: CameraCaptureSession) {
                                if (mine != attempt) return cs.close()
                                session = cs
                                runCatching {
                                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(s) }.build()
                                    cs.setRepeatingRequest(request, null, handler)
                                }.onFailure { scope.launch { if (mine == attempt) fail(context, "preview refused: $it") } }
                            }

                            override fun onConfigureFailed(cs: CameraCaptureSession) {
                                scope.launch { if (mine == attempt) fail(context, "session failed") }
                            }
                        }, handler)
                    }.onFailure { scope.launch { if (mine == attempt) fail(context, "session refused: $it") } }
                    // Closed between the check and here: close() didn't see this camera.
                    if (mine != attempt) {
                        runCatching { camera.close() }
                        if (device === camera) device = null
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    scope.launch { if (mine == attempt && (device === camera || device == null)) fail(context, "camera taken back") }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    scope.launch {
                        if (mine != attempt) return@launch
                        val busy = error == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE || error == CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE
                        // The ROM app hadn't opened yet when it was stopped, and has since: once more.
                        if (busy && retry && device == null) openAgain(context, texture, mine)
                        else fail(context, "camera error $error")
                    }
                }
            }, handler)
        }.onFailure { e ->
            // Refused at once: the ROM app opened the camera again in between. Once more.
            if (retry && e is android.hardware.camera2.CameraAccessException && e.reason == android.hardware.camera2.CameraAccessException.CAMERA_IN_USE) {
                scope.launch { openAgain(context, texture, mine) }
            } else fail(context, "open refused: $e")
        }
    }

    /** The second and last try of [attempt] number [mine], unless reverse ended while the ROM app was stopped. */
    private suspend fun openAgain(context: Context, texture: SurfaceTexture, mine: Int) {
        val stopped = stopRomCamera(context)
        if (mine != attempt) return
        if (!stopped) return fail(context, "the car app's camera could not be stopped")
        withTimeoutOrNull(CLOSE_WAIT_MS) { _cameraFree.first { it } }
        if (mine == attempt) open(context, texture, retry = false)
    }

    /**
     * Gives up: the reverse view goes back to drawing over the ROM app's
     * picture, the ROM app started again if it was stopped.
     */
    private fun fail(context: Context, why: String) {
        if (_state.value == State.FAILED) return
        Log.w(TAG, "own picture given up: $why")
        close()
        // Given up for this reverse. Once reverse is over (it ended while the
        // camera was being taken) there is nothing to give up, and "failed"
        // would only be met by the next reverse, which then didn't even try.
        _state.value = if (CarBox.reversing.value) State.FAILED else State.OFF
        if (romStopped && CarBox.reversing.value) {
            romStopped = false
            scope.launch {
                runCatching { DockShell.shell(context, "am broadcast -a com.qf.action.BACKCAR_START -p $ROM_APP") }
                    .onFailure { Log.w(TAG, "could not restart the car app's camera", it) }
            }
        }
    }

    /**
     * A small copy of the picture five times a second while it's live, for
     * the ground map and the camera's measure of how far the car moved
     * ([GroundMemory], [ParkingMotion]).
     */
    private fun sample(view: TextureView) {
        sampler?.cancel()
        sampler = scope.launch {
            val pixels = IntArray(FRAME_W * FRAME_H)
            while (isActive && _state.value == State.LIVE) {
                delay(SAMPLE_MS)
                if (ParkingMotion.pose.value == null || view.width == 0) continue
                val b = frame ?: Bitmap.createBitmap(FRAME_W, FRAME_H, Bitmap.Config.ARGB_8888).also { frame = it }
                if (runCatching { view.getBitmap(b) }.isFailure) continue
                b.getPixels(pixels, 0, FRAME_W, 0, 0, FRAME_W, FRAME_H)
                val w = view.width.toFloat()
                val h = view.height.toFloat()
                val cal = ReverseView.calibration.value
                withContext(Dispatchers.Default) {
                    GroundMemory.onFrame(pixels, FRAME_W, FRAME_H, w, h, cal) { step ->
                        step?.let { ParkingMotion.cameraStep(it) }
                        ParkingMotion.pose.value
                    }
                }
            }
        }
    }

    private fun close() {
        attempt++
        stopMirror()
        liveView = null
        sampler?.cancel()
        sampler = null
        opening?.cancel()
        opening = null
        watchdog?.cancel()
        watchdog = null
        runCatching { session?.close() }
        session = null
        runCatching { device?.close() }
        device = null
        surface?.release()
        surface = null
    }
}

/** The camera's picture, full screen under the lines. */
@Composable
internal fun ReverseCameraPicture(modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx -> TextureView(ctx).also { ReverseCamera.attach(ctx, it) } },
        modifier = modifier,
        onRelease = { ReverseCamera.detach() }
    )
}
