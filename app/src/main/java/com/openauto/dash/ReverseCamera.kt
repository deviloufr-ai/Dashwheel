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
import android.os.SystemClock
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
 * instead of drawing over the ROM's camera app (com.qf.backcar). That app
 * still opens the camera first on every reverse (it switches the video chip
 * to the camera input); Dashwheel opens the same camera a moment later, which
 * takes it over: the ROM app then just closes its preview (its
 * onDisconnected), it doesn't retry. If no picture comes within a few
 * seconds, Dashwheel lets go and has the ROM app start its camera again, so
 * there is always a picture.
 */
internal object ReverseCamera {
    private const val TAG = "ReverseCamera"
    /** The reversing camera: the ROM app's choice on the K706 (camera 0 is the other input). */
    private const val CAMERA_ID = "1"
    /** The ROM app's 1080p stream (AHD_1080P25). */
    private const val WIDTH = 1920
    private const val HEIGHT = 1080
    /** Long enough for the ROM app to have opened the camera and powered the chip, so it's taken over and not raced. */
    private const val OPEN_AFTER_MS = 600L
    /** The ROM app waits about 1.5 s for the video signal to lock; past this, it's not coming. */
    private const val FIRST_FRAME_MS = 3_500L
    /** The pretend stop and start sent to bring the ROM app back are not a real end of reverse. */
    private const val RESTART_GRACE_MS = 4_000L

    enum class State { OFF, OPENING, LIVE, FAILED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(State.OFF)
    val state: StateFlow<State> = _state.asStateFlow()

    private var thread: HandlerThread? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var surface: Surface? = null
    private var opening: Job? = null
    private var watchdog: Job? = null
    /** The camera was ours, so the ROM app lost it and has to be started again on a failure. */
    private var tookOver = false
    private var restartedAt = 0L

    fun hasPermission(context: Context) =
        context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /** Reverse is over (a real one): the next reverse tries again. */
    fun reverseEnded() {
        if (SystemClock.elapsedRealtime() - restartedAt < RESTART_GRACE_MS) return
        if (_state.value == State.FAILED) _state.value = State.OFF
    }

    /** The picture's view is on screen: its surface opens the camera. */
    fun attach(context: Context, view: TextureView) {
        if (_state.value == State.FAILED) return
        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                texture.setDefaultBufferSize(WIDTH, HEIGHT)
                opening?.cancel()
                opening = scope.launch {
                    delay(OPEN_AFTER_MS)
                    open(context.applicationContext, texture)
                }
            }

            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                close()
                return true
            }

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
                if (_state.value == State.OPENING) {
                    _state.value = State.LIVE
                    watchdog?.cancel()
                }
            }
        }
    }

    /** The picture's view is gone: the camera goes with it. */
    fun detach() {
        close()
        if (_state.value != State.FAILED) _state.value = State.OFF
    }

    @SuppressLint("MissingPermission")
    private fun open(context: Context, texture: SurfaceTexture) {
        if (!hasPermission(context)) return fail(context, "no camera permission")
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = runCatching { manager.cameraIdList.let { ids -> if (CAMERA_ID in ids) CAMERA_ID else ids.lastOrNull() } }.getOrNull()
            ?: return fail(context, "no camera")
        val t = thread ?: HandlerThread("ReverseCamera").also { it.start(); thread = it }
        val handler = Handler(t.looper)
        _state.value = State.OPENING
        tookOver = false
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(FIRST_FRAME_MS)
            if (_state.value == State.OPENING) fail(context, "no picture")
        }
        runCatching {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    tookOver = true
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
                    // Taken back (the ROM app, or anything with more right to it): it has the picture again.
                    tookOver = false
                    camera.close()
                    scope.launch { if (device === camera) fail(context, "camera taken back") }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    scope.launch { fail(context, "camera error $error") }
                }
            }, handler)
        }.onFailure { fail(context, "open refused: $it") }
    }

    /**
     * Gives the camera back: the reverse view goes back to drawing over the
     * ROM app's picture, which is started again if Dashwheel had taken it.
     */
    private fun fail(context: Context, why: String) {
        if (_state.value == State.FAILED) return
        Log.w(TAG, "own picture given up: $why")
        val restart = tookOver
        close()
        _state.value = State.FAILED
        if (restart) {
            restartedAt = SystemClock.elapsedRealtime()
            scope.launch {
                // The ROM app only starts its camera on a reverse start; as root, play it a stop and a start.
                runCatching {
                    DockShell.shell(context, "am broadcast -a com.qf.action.BACKCAR_STOP; sleep 0.3; am broadcast -a com.qf.action.BACKCAR_START")
                }.onFailure { Log.w(TAG, "could not restart the ROM camera", it) }
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
        tookOver = false
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
