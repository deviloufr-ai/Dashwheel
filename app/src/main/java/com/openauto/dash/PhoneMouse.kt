package com.openauto.dash

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import com.openauto.dash.link.PhonePointer
import com.openauto.dash.link.PointerAccess
import kotlin.math.hypot

/**
 * The phone as the car's mouse: the companion's touchpad ([PhonePointer])
 * moves an arrow over whatever the car's screen shows, and taps, holds and
 * scrolls where it points, through the accessibility service (the one typing
 * from the phone uses, [PhoneKeyboard]). The arrow is an accessibility
 * overlay that lets every touch through, so the taps reach the app under it;
 * it goes when the touchpad closes, or after a while without a move.
 */
internal object PhoneMouse {
    private const val TAG = "PhoneMouse"

    /** The arrow leaves the screen after this long without a word from the touchpad. */
    private const val HIDE_AFTER_MS = 20_000L
    private const val TAP_MS = 40L
    private const val SCROLL_MS = 90L
    /** Car pixels a scroll moves per phone dp the fingers moved, before the screen's density. */
    private const val SCROLL_GAIN = 2.2f

    private val main = Handler(Looper.getMainLooper())
    private val hide = Runnable { remove() }

    // Main thread only.
    private var arrow: Arrow? = null
    private var params: WindowManager.LayoutParams? = null
    /** The service the arrow was added through: a new one (it was turned off and on) needs a new arrow. */
    private var host: Context? = null
    private var x = -1f
    private var y = -1f

    fun handle(message: PhonePointer, answer: (PointerAccess) -> Unit) {
        main.post {
            val done = runCatching { act(message) }
                .onFailure { Log.w(TAG, "pointer from the phone not handled", it) }
                .getOrDefault(false)
            // The touchpad hears only when it opens, and when the car could not tap for it: not
            // for every move, which come dozens a second.
            val quiet = message.kind == PhonePointer.Kind.MOVE || message.kind == PhonePointer.Kind.HIDE
            if (message.kind == PhonePointer.Kind.SHOW || (!done && !quiet)) {
                answer(PointerAccess(done))
            }
        }
    }

    private fun act(message: PhonePointer): Boolean {
        if (message.kind == PhonePointer.Kind.HIDE) {
            remove()
            return true
        }
        val service = SplitAccessibilityService.overlayHost() ?: run { remove(); return false }
        if (!show(service)) return false
        main.removeCallbacks(hide)
        main.postDelayed(hide, HIDE_AFTER_MS)
        val density = service.resources.displayMetrics.density
        val (width, height) = screen(service)
        return when (message.kind) {
            PhonePointer.Kind.SHOW -> SplitAccessibilityService.canGesture()
            PhonePointer.Kind.MOVE -> {
                val gain = pointerGain(hypot(message.dx, message.dy)) * density
                x = (x + message.dx * gain).coerceIn(0f, width - 1f)
                y = (y + message.dy * gain).coerceIn(0f, height - 1f)
                place()
                true
            }
            PhonePointer.Kind.TAP -> press(TAP_MS)
            PhonePointer.Kind.HOLD -> press(ViewConfiguration.getLongPressTimeout() + 300L)
            PhonePointer.Kind.SCROLL -> {
                val gain = SCROLL_GAIN * density
                val toX = (x + message.dx * gain).coerceIn(0f, width - 1f)
                val toY = (y + message.dy * gain).coerceIn(0f, height - 1f)
                // A swipe shorter than the slop would land as a tap: none at all, rather (the edge of the screen).
                if (hypot(toX - x, toY - y) < ViewConfiguration.get(service).scaledTouchSlop * 1.5f) true
                else gesture(Path().apply { moveTo(x, y); lineTo(toX, toY) }, SCROLL_MS)
            }
            PhonePointer.Kind.BACK -> SplitAccessibilityService.globalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            PhonePointer.Kind.HOME -> SplitAccessibilityService.globalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            PhonePointer.Kind.HIDE -> true
        }
    }

    /** A touch where the arrow points, held [ms]; the arrow dips so the driver sees it land. */
    private fun press(ms: Long): Boolean {
        arrow?.animate()?.scaleX(0.8f)?.scaleY(0.8f)?.setDuration(80)?.withEndAction {
            arrow?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(120)
        }
        return gesture(Path().apply { moveTo(x, y) }, ms)
    }

    private fun gesture(path: Path, ms: Long): Boolean {
        val stroke = GestureDescription.StrokeDescription(path, 0, ms)
        return SplitAccessibilityService.gesture(GestureDescription.Builder().addStroke(stroke).build())
    }

    /** The arrow on screen, added through [service] if it is not already; false when it could not be. */
    @SuppressLint("RtlHardcoded")
    private fun show(service: Context): Boolean {
        if (arrow != null && host === service) return true
        remove()
        val (width, height) = screen(service)
        if (x < 0f || x >= width || y < 0f || y >= height) {
            x = width / 2f
            y = height / 2f
        }
        val view = Arrow(service)
        val layout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            // Left, not start: the arrow points up and left, in a right-to-left language too.
            gravity = Gravity.TOP or Gravity.LEFT
            this.x = this@PhoneMouse.x.toInt() - view.tip
            this.y = this@PhoneMouse.y.toInt() - view.tip
        }
        return runCatching { service.getSystemService(WindowManager::class.java).addView(view, layout) }
            .onSuccess {
                arrow = view
                params = layout
                host = service
            }
            .onFailure { Log.w(TAG, "pointer not shown", it) }
            .isSuccess
    }

    private fun place() {
        val view = arrow ?: return
        val layout = params ?: return
        layout.x = x.toInt() - view.tip
        layout.y = y.toInt() - view.tip
        runCatching { host?.getSystemService(WindowManager::class.java)?.updateViewLayout(view, layout) }
    }

    private fun remove() {
        main.removeCallbacks(hide)
        val view = arrow ?: return
        runCatching { host?.getSystemService(WindowManager::class.java)?.removeView(view) }
        arrow = null
        params = null
        host = null
    }

    /** The whole screen, bars included: where the arrow can go and a gesture can land. */
    @Suppress("DEPRECATION")
    private fun screen(context: Context): Pair<Int, Int> {
        val size = Point()
        context.getSystemService(WindowManager::class.java).defaultDisplay.getRealSize(size)
        return size.x to size.y
    }

    /** A plain pointer arrow, white with a dark edge, larger than a desktop's: it is read from the driver's seat. */
    private class Arrow(context: Context) : View(context) {
        private val unit = context.resources.displayMetrics.density * 1.5f
        private val edge = unit * 1.2f
        private val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(0f, 17f * unit)
            lineTo(4.5f * unit, 13f * unit)
            lineTo(7.5f * unit, 19.5f * unit)
            lineTo(10f * unit, 18.5f * unit)
            lineTo(7.2f * unit, 12.2f * unit)
            lineTo(12.5f * unit, 12.2f * unit)
            close()
            offset(edge, edge)
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.FILL }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF111111.toInt()
            style = Paint.Style.STROKE
            strokeWidth = edge
            strokeJoin = Paint.Join.ROUND
        }

        /** How far in from the window's corner the tip is drawn: the window sits that much up and left of the point. */
        val tip: Int = edge.toInt()

        init {
            pivotX = tip.toFloat()
            pivotY = tip.toFloat()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension((12.5f * unit + 2 * edge).toInt() + 1, (19.5f * unit + 2 * edge).toInt() + 1)
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawPath(path, fill)
            canvas.drawPath(path, line)
        }
    }
}

/**
 * How far the pointer goes per phone dp, for a step of [stepDp] (the move
 * the touchpad sent at once): slow moves are precise, a quick swipe crosses
 * the car's wide screen. Pure, for the tests.
 */
internal fun pointerGain(stepDp: Float): Float = 1.2f + (stepDp / 10f).coerceIn(0f, 1.8f)
