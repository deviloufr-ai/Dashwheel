package com.openauto.dash

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.PixelCopy
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

/*
 * A still picture of the dashboard over the main screen while an app is put
 * in its tile. On the K706 an app started for a tile opens full screen on the
 * main screen first whatever display is asked for (EmbeddedApp.opensOnMainScreen)
 * and is only then moved onto its tile: at every start of Dashwheel its splash
 * screens took the whole screen in turn, black, then the app's colour, then
 * the dashboard again. With this picture over them the dashboard just stays.
 * At Dashwheel's first start the driver's start-up animation (StartSplash)
 * covers instead, from the first frame until the tiles' apps are placed.
 */
internal object MainScreenCover {
    private const val TAG = "MainScreenCover"

    /** Longest the picture may stay, should a placing never say it is done. */
    private const val MAX_SHOWN_MS = 6_000L

    /** Kept a moment after the last app is placed, so its move has reached the screen. */
    private const val SETTLE_MS = 350L

    /** And this long after the dashboard is back in front or raised again: it was black for a frame then. */
    private const val REDRAWN_MS = 700L

    /** How long taking the picture may hold up an app's start. */
    private const val COPY_WAIT_MS = 900L

    /** The start-up animation plays this long at least, so it reaches its logo; and this long at most. */
    private const val SPLASH_MIN_MS = 3_000L
    private const val SPLASH_MAX_MS = 12_000L

    /** A try-out from Settings plays this long, or until it is tapped. */
    private const val TRY_MS = 5_000L

    /*
     * Everything here runs on its own thread, window included: at start-up the
     * main thread is busy drawing the dashboard, and waiting for it the
     * picture came too late.
     */
    private val worker: Handler by lazy { Handler(HandlerThread("main-screen-cover").apply { start() }.looper) }
    private var capturing = false
    private var activity = WeakReference<Activity>(null)
    @Volatile private var resumed = false
    @Volatile private var resumedAt = 0L
    @Volatile private var raisedAt = 0L
    @Volatile private var raisingNow = false
    private val placing = HashSet<String>()
    private var view: View? = null
    private var wm: WindowManager? = null
    private val giveUp = Runnable { fadeOut() }
    private var splashStarted = false
    private var splashUntil = 0L
    /** The apps placed under the start-up animation, in order, by package. */
    private val seen = LinkedHashSet<String>()
    private val labels = HashMap<String, String>()
    private val settle = object : Runnable {
        override fun run() {
            if (placing.isNotEmpty()) return
            // Behind the picture the apps' screens put the dashboard away: it goes once the dashboard is back and drawn.
            val now = SystemClock.elapsedRealtime()
            val wait = maxOf(
                if (!resumed || raisingNow) REDRAWN_MS else maxOf(resumedAt, raisedAt) + REDRAWN_MS - now,
                splashUntil - now
            )
            if (wait > 0) worker.postDelayed(this, wait) else fadeOut()
        }
    }

    /** The dashboard's activity, as it comes and goes (MainActivity). */
    fun attach(activity: Activity) {
        this.activity = WeakReference(activity)
    }

    /** The keys are being given back (EmbeddedApp): it raises a tile's task, then the dashboard's, and may again. */
    fun raising(on: Boolean) {
        raisingNow = on
        raisedAt = SystemClock.elapsedRealtime()
    }

    fun onResumed(on: Boolean) {
        if (on) resumedAt = SystemClock.elapsedRealtime()
        resumed = on
    }

    /**
     * Puts the dashboard's picture over the main screen while [packageName]
     * is started and moved onto its tile; only when the dashboard is what
     * shows. Each [cover] is matched by an [uncover].
     */
    suspend fun cover(packageName: String) {
        val act = activity.get()
        if (act == null || !resumed || act.isFinishing) {
            Log.i(TAG, "$packageName: the dashboard is not in front, nothing to cover")
            return
        }
        // One picture at a time; an app placed meanwhile shares it.
        val first = onWorker {
            placing.add(packageName)
            if (view is StartSplashView) seen.add(packageName)
            (view == null && !capturing).also { if (it) capturing = true }
        }
        if (!first) return
        val picture = withTimeoutOrNull(COPY_WAIT_MS) { snapshot(act) }
        onWorker { capturing = false }
        if (picture == null) {
            Log.w(TAG, "$packageName: no picture of the dashboard in time")
            return
        }
        onWorker {
            // Placed already while the picture was taken: nothing left to hide.
            if (placing.isEmpty() || view != null) return@onWorker
            show(act, picture)
        }
    }

    /** [packageName] is on its tile (or gave up): the picture goes once no app is left being placed. */
    fun uncover(packageName: String) {
        worker.post {
            placing.remove(packageName)
            if (placing.isEmpty() && view != null) {
                worker.removeCallbacks(settle)
                worker.postDelayed(settle, SETTLE_MS)
            }
        }
    }

    /**
     * Dashwheel's first start in front: the chosen animation covers the main
     * screen while the dashboard is built and its tiles' apps are placed
     * ([cover] adds them to it), the still picture's job.
     */
    fun startUp(act: Activity) {
        if (splashStarted) return
        splashStarted = true
        val style = StartSplash.chosen.value
        if (style != StartAnimation.BLUEPRINT) StartSplash.collecting = false
        if (style == StartAnimation.STILL) return
        val apps = act.applicationContext
        worker.post {
            if (view != null) return@post
            val shown = addCover(act, SPLASH_MAX_MS) { host ->
                StartSplashView(host, style, apps = { seen.map { label(apps, it) to (it !in placing) } })
            }
            if (!shown) {
                StartSplash.collecting = false
                return@post
            }
            splashUntil = SystemClock.elapsedRealtime() + SPLASH_MIN_MS
            // Taken down once nothing is being placed; an app placed meanwhile puts that off.
            worker.postDelayed(settle, SPLASH_MIN_MS)
        }
    }

    /** [style] full screen for a few seconds, from Settings; a tap ends it. */
    fun tryOut(act: Activity, style: StartAnimation) {
        if (style == StartAnimation.STILL) return
        worker.post {
            removeNow()
            addCover(act, TRY_MS) { host ->
                StartSplashView(host, style, sample = true).apply { setOnClickListener { fadeOut() } }
            }
        }
    }

    private fun label(context: Context, packageName: String): String = labels.getOrPut(packageName) {
        runCatching { context.packageManager.run { getApplicationLabel(getApplicationInfo(packageName, 0)).toString() } }.getOrDefault(packageName)
    }

    private suspend fun snapshot(act: Activity): Bitmap? = suspendCancellableCoroutine { cont ->
        worker.post {
            val decor = act.window?.peekDecorView()
            if (decor == null || decor.width == 0 || decor.height == 0) {
                cont.resume(null)
                return@post
            }
            val bitmap = runCatching { Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888) }.getOrNull()
            if (bitmap == null) {
                cont.resume(null)
                return@post
            }
            // PixelCopy reads the screen's pixels, the map and the tiles' pictures included.
            runCatching {
                PixelCopy.request(act.window, bitmap, { result ->
                    if (cont.isActive) cont.resume(if (result == PixelCopy.SUCCESS) bitmap else null)
                }, worker)
            }.onFailure {
                Log.w(TAG, "no picture of the dashboard", it)
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    private fun show(act: Activity, picture: Bitmap) {
        addCover(act, MAX_SHOWN_MS, picture.width, picture.height) { host ->
            ImageView(host).apply {
                setImageBitmap(picture)
                scaleType = ImageView.ScaleType.FIT_XY
            }
        }
    }

    /** Puts [make]'s view over the main screen for [maxMs] at most, the whole screen unless [width] x [height]; false when it cannot be. */
    private fun addCover(act: Activity, maxMs: Long, width: Int = 0, height: Int = 0, make: (Context) -> View): Boolean {
        val host: Context = SplitAccessibilityService.overlayHost() ?: act.applicationContext
        val type = when {
            host !== act.applicationContext -> WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            Settings.canDrawOverlays(act) -> WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else -> return false
        }
        val manager = host.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val cover = make(host)
        // The real size: at the very start the system bars are still there, and the cover goes over them too.
        val screen = Point().also { @Suppress("DEPRECATION") manager.defaultDisplay.getRealSize(it) }
        val params = WindowManager.LayoutParams(
            if (width > 0) width else screen.x, if (height > 0) height else screen.y, type,
            // Takes the taps meanwhile: they would land on the app's screen passing through.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            // Translucent: on an opaque window the fade-out went through black, not to the dashboard.
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            windowAnimations = 0
            title = "Dashwheel cover"
        }
        return runCatching { manager.addView(cover, params) }
            .onSuccess {
                Log.i(TAG, "main screen covered while ${placing.joinToString()} are placed")
                view = cover
                wm = manager
                worker.postDelayed(giveUp, maxMs)
            }
            .onFailure { Log.w(TAG, "could not cover the main screen", it) }
            .isSuccess
    }

    private fun fadeOut() {
        when (val v = view ?: return) {
            // Each animation has its own way out.
            is StartSplashView -> v.exit { if (view === v) removeNow() }
            else -> v.animate().alpha(0f).setDuration(150L).withEndAction { removeNow() }.start()
        }
    }

    private fun removeNow() {
        worker.removeCallbacks(giveUp)
        worker.removeCallbacks(settle)
        placing.clear()
        seen.clear()
        splashUntil = 0L
        val v = view ?: return
        if (v is StartSplashView) StartSplash.collecting = false
        Log.i(TAG, "main screen uncovered")
        view = null
        runCatching { wm?.removeView(v) }
        wm = null
    }

    private suspend fun <T> onWorker(block: () -> T): T =
        if (Looper.myLooper() == worker.looper) block()
        else suspendCancellableCoroutine { cont -> worker.post { cont.resume(block()) } }
}
