package com.openauto.dash

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One app's picture, drawn in several places: an app inside a dashboard tile
 * draws on the relay's [input], and the relay draws each of its frames on the
 * tile ([setTile]) and on every copy ([addCopy]), the second screen's cluster.
 * Android gives a virtual display one picture only; this is how the same
 * Google Maps shows on the head unit and on the second screen at once.
 *
 * The frames stay on the graphics chip (an external texture drawn into each
 * picture), so a copy costs a draw, not a copy through memory. The tile gets
 * the frame as it is; a copy of another shape gets it cropped to fill, from
 * the middle.
 *
 * Everything GL lives on the relay's own thread. The calls below come from the
 * main thread and wait for the relay to let go of a picture before they
 * return, so the caller may hand that picture to someone else (the display)
 * or free it straight after.
 */
internal class PictureRelay {

    private val thread = HandlerThread("PictureRelay").apply { start() }
    private val handler = Handler(thread.looper)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    /** A 1x1 picture to make the context current on when there is nothing else to draw on. */
    private var idle: EGLSurface = EGL14.EGL_NO_SURFACE

    private var texture = 0
    private var program = 0
    private var inputTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    @Volatile private var inputWidth = 0
    @Volatile private var inputHeight = 0
    private val transform = FloatArray(16)

    /** A picture drawn on: its window surface once connected, and whether the frame is cropped to fill it. */
    private class Output(val surface: Surface, val crop: Boolean) {
        var egl: EGLSurface = EGL14.EGL_NO_SURFACE
    }

    private var tile: Output? = null
    private val copies = ArrayList<Output>()
    @Volatile private var dead = false
    /** Frames passed on since the last new picture, for the log. */
    private var frames = 0
    /** Whether a frame came in yet: the texture then holds the latest, to draw on a new picture at once. */
    private var hasFrame = false

    init {
        val ready = CountDownLatch(1)
        handler.post {
            runCatching { setUp() }.onFailure { Log.w(TAG, "no GL for the relay", it); dead = true }
            ready.countDown()
        }
        ready.await(2, TimeUnit.SECONDS)
    }

    /** Whether GL could be set up; when not, the caller does without the relay. */
    val works: Boolean get() = !dead

    /** What the app draws on, [width] x [height] pixels. Main thread. */
    fun input(width: Int, height: Int): Surface? {
        if (dead) return null
        if (width > 0 && height > 0 && (width != inputWidth || height != inputHeight)) {
            inputWidth = width
            inputHeight = height
            onRelay { inputTexture?.setDefaultBufferSize(width, height) }
        }
        return inputSurface
    }

    /** The tile's picture, or none (the tile went: the frames then only reach the copies). Main thread. */
    fun setTile(surface: Surface?) {
        if (tile?.surface === surface) return
        onRelay {
            tile?.let { drop(it) }
            tile = surface?.let { Output(it, crop = false) }
            tile?.let { showLast(it) }
        }
    }

    fun addCopy(surface: Surface) = onRelay {
        if (copies.none { it.surface === surface }) copies += Output(surface, crop = true).also { showLast(it) }
    }

    fun removeCopy(surface: Surface) = onRelay {
        copies.firstOrNull { it.surface === surface }?.let { drop(it); copies.remove(it) }
    }

    /** Lets go of every picture and of GL. Main thread; the relay is unusable afterwards. */
    fun release() {
        onRelay {
            tile?.let { drop(it) }
            tile = null
            copies.forEach { drop(it) }
            copies.clear()
            tearDown()
        }
        dead = true
        thread.quitSafely()
    }

    /** Runs [block] on the relay's thread and waits for it, a little at most. */
    private fun onRelay(block: () -> Unit) {
        if (dead) return
        val done = CountDownLatch(1)
        handler.post {
            runCatching(block).onFailure { Log.w(TAG, "relay call failed", it) }
            done.countDown()
        }
        if (!done.await(1, TimeUnit.SECONDS)) Log.w(TAG, "relay slow to answer")
    }

    // --- The relay's thread -------------------------------------------------

    private fun setUp() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) { "eglChooseConfig" }
        eglConfig = configs[0]
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
        idle = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(eglDisplay, idle, idle, eglContext)) { "eglMakeCurrent" }

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texture = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        program = makeProgram()

        inputTexture = SurfaceTexture(texture).also {
            it.setDefaultBufferSize(16, 16)
            it.setOnFrameAvailableListener({ onFrame() }, handler)
        }
        inputSurface = Surface(inputTexture)
    }

    private fun tearDown() {
        inputSurface?.release()
        inputSurface = null
        inputTexture?.release()
        inputTexture = null
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (idle != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, idle)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
    }

    /**
     * Connects to [out]'s picture. Refused while someone else still draws on
     * it (the display hands the tile's picture over a moment later): tried
     * again at the next frame.
     */
    private fun connect(out: Output) {
        if (out.egl != EGL14.EGL_NO_SURFACE || !out.surface.isValid) return
        val egl = runCatching {
            EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, out.surface, intArrayOf(EGL14.EGL_NONE), 0)
        }.getOrNull()
        if (egl == null || egl == EGL14.EGL_NO_SURFACE) {
            Log.i(TAG, "picture busy (0x${Integer.toHexString(EGL14.eglGetError())}), tried again shortly")
            return
        }
        out.egl = egl
        frames = 0
    }

    /**
     * Draws the latest frame on [out], a picture just given: an app that
     * isn't moving (Maps with no route) sends no new frame, and the picture
     * would stay black until it does. While someone else still draws on it,
     * tried again shortly, a second at most.
     */
    private fun showLast(out: Output, tries: Int = 20) {
        connect(out)
        if (out.egl != EGL14.EGL_NO_SURFACE) {
            if (hasFrame) draw(out)
            return
        }
        if (tries > 0) handler.postDelayed({ if (out === tile || out in copies) showLast(out, tries - 1) }, 50)
    }

    /** Lets go of [out]'s picture: whoever comes next may draw on it. */
    private fun drop(out: Output) {
        if (out.egl == EGL14.EGL_NO_SURFACE) return
        EGL14.eglMakeCurrent(eglDisplay, idle, idle, eglContext)
        EGL14.eglDestroySurface(eglDisplay, out.egl)
        out.egl = EGL14.EGL_NO_SURFACE
    }

    private fun onFrame() {
        val input = inputTexture ?: return
        EGL14.eglMakeCurrent(eglDisplay, idle, idle, eglContext)
        runCatching { input.updateTexImage() }.onFailure { return }
        hasFrame = true
        if (frames++ == 0) Log.i(TAG, "first frame, ${inputWidth}x$inputHeight, to ${if (tile != null) "the tile and " else ""}${copies.size} copies")
        input.getTransformMatrix(transform)
        tile?.let { draw(it) }
        copies.forEach { draw(it) }
    }

    private fun draw(out: Output) {
        connect(out)
        if (out.egl == EGL14.EGL_NO_SURFACE) return
        if (!EGL14.eglMakeCurrent(eglDisplay, out.egl, out.egl, eglContext)) {
            // The picture went away under us (its view was freed): connected again when it comes back.
            drop(out)
            return
        }
        // Never wait for a slow picture: the tile must not lag for the cluster's sake.
        EGL14.eglSwapInterval(eglDisplay, 0)
        val size = IntArray(2)
        EGL14.eglQuerySurface(eglDisplay, out.egl, EGL14.EGL_WIDTH, size, 0)
        EGL14.eglQuerySurface(eglDisplay, out.egl, EGL14.EGL_HEIGHT, size, 1)
        val (w, h) = size[0] to size[1]
        if (w <= 0 || h <= 0) return
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(program)
        val (sx, sy) = if (out.crop) cropScale(inputWidth, inputHeight, w, h) else 1f to 1f
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uScale"), sx, sy)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uST"), 1, false, transform, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        val pos = GLES20.glGetAttribLocation(program, "aPos")
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, QUAD)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        if (!EGL14.eglSwapBuffers(eglDisplay, out.egl)) drop(out)
    }

    private fun makeProgram(): Int {
        fun shader(type: Int, source: String) = GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, source)
            GLES20.glCompileShader(it)
        }
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, shader(GLES20.GL_VERTEX_SHADER, VERTEX))
            GLES20.glAttachShader(it, shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT))
            GLES20.glLinkProgram(it)
        }
    }

    companion object {
        private const val TAG = "PictureRelay"

        /**
         * How much of the frame a [outW] x [outH] picture shows, per axis, to
         * be filled by a [inW] x [inH] frame cropped from the middle: 1 on one
         * axis, less than 1 on the other.
         */
        fun cropScale(inW: Int, inH: Int, outW: Int, outH: Int): Pair<Float, Float> {
            if (inW <= 0 || inH <= 0 || outW <= 0 || outH <= 0) return 1f to 1f
            val frame = inW.toFloat() / inH
            val picture = outW.toFloat() / outH
            return if (picture > frame) 1f to frame / picture else picture / frame to 1f
        }

        private val QUAD: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            position(0)
        }

        private const val VERTEX = """
            attribute vec2 aPos;
            uniform mat4 uST;
            uniform vec2 uScale;
            varying vec2 vTex;
            void main() {
                vec2 tex = (aPos * 0.5) * uScale + 0.5;
                vTex = (uST * vec4(tex, 0.0, 1.0)).xy;
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """

        private const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTex;
            varying vec2 vTex;
            void main() { gl_FragColor = texture2D(uTex, vTex); }
        """
    }
}
