package com.openauto.dash

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
    private const val CAMERA_ID = "1"
    /** The ROM app's 1080p stream (AHD_1080P25). */
    private const val WIDTH = 1920
    private const val HEIGHT = 1080
    /** The video chip's input switch: 2 is the reversing camera. */
    private const val INPUT_SWITCH = "/sys/class/tp9950_class/tp9950_class_dev/auxvideo_backcvbs_switch"
    /** The ROM app has its picture up by then (about 1.8 s after the reverse key), so it's stopped and not raced. */
    private const val TAKE_AFTER_MS = 1_500L
    /** Time for the ROM app to close its camera. */
    private const val CLOSE_MS = 400L
    /** The camera's first frame came within a second on the K706; past this, it's not coming. */
    private const val FIRST_FRAME_MS = 3_500L

    /** TAKING: the car app's picture is still up; OPENING: it's gone, Dashwheel's not there yet. */
    enum class State { OFF, TAKING, OPENING, LIVE, FAILED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(State.OFF)
    val state: StateFlow<State> = _state.asStateFlow()

    private var thread: HandlerThread? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var surface: Surface? = null
    private var opening: Job? = null
    private var watchdog: Job? = null
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
                    delay(TAKE_AFTER_MS)
                    if (!stopRomCamera(app)) return@launch fail(app, "the car app's camera could not be stopped")
                    delay(CLOSE_MS)
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
    }.onFailure { Log.w(TAG, "could not stop the car app's camera", it) }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    private fun open(context: Context, texture: SurfaceTexture, retry: Boolean) {
        if (!hasPermission(context)) return fail(context, "no camera permission")
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = runCatching { manager.cameraIdList.let { ids -> if (CAMERA_ID in ids) CAMERA_ID else ids.lastOrNull() } }.getOrNull()
            ?: return fail(context, "no camera")
        val t = thread ?: HandlerThread("ReverseCamera").also { it.start(); thread = it }
        val handler = Handler(t.looper)
        _state.value = State.OPENING
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(FIRST_FRAME_MS)
            if (_state.value == State.OPENING) fail(context, "no picture")
        }
        runCatching {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    val s = Surface(texture).also { surface = it }
                    runCatching {
                        @Suppress("DEPRECATION")
                        camera.createCaptureSession(listOf(s), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(cs: CameraCaptureSession) {
                                session = cs
                                runCatching {
                                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(s) }.build()
                                    cs.setRepeatingRequest(request, null, handler)
                                }.onFailure { scope.launch { fail(context, "preview refused: $it") } }
                            }

                            override fun onConfigureFailed(cs: CameraCaptureSession) {
                                scope.launch { fail(context, "session failed") }
                            }
                        }, handler)
                    }.onFailure { scope.launch { fail(context, "session refused: $it") } }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    scope.launch { if (device === camera || device == null) fail(context, "camera taken back") }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    scope.launch {
                        val busy = error == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE || error == CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE
                        // The ROM app hadn't opened yet when it was stopped, and has since: once more.
                        if (busy && retry && device == null) {
                            if (stopRomCamera(context)) {
                                delay(CLOSE_MS)
                                open(context, texture, retry = false)
                            } else fail(context, "the car app's camera could not be stopped")
                        } else fail(context, "camera error $error")
                    }
                }
            }, handler)
        }.onFailure { fail(context, "open refused: $it") }
    }

    /**
     * Gives up: the reverse view goes back to drawing over the ROM app's
     * picture, the ROM app started again if it was stopped.
     */
    private fun fail(context: Context, why: String) {
        if (_state.value == State.FAILED) return
        Log.w(TAG, "own picture given up: $why")
        close()
        _state.value = State.FAILED
        if (romStopped && CarBox.reversing.value) {
            romStopped = false
            scope.launch {
                runCatching { DockShell.shell(context, "am broadcast -a com.qf.action.BACKCAR_START -p $ROM_APP") }
                    .onFailure { Log.w(TAG, "could not restart the car app's camera", it) }
            }
        }
    }

    private fun close() {
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
