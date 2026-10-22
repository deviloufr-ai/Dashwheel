package com.openauto.dash

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/*
 * What covers the main screen while Dashwheel starts: an animation of the
 * driver's choice (Settings, Look) instead of a still picture of a dashboard
 * that is not drawn yet. MainScreenCover puts it up at the first start and
 * takes it down once the tiles' apps are in place, with each animation's own
 * way out. Drawn by hand on a Canvas, on the cover's own thread, so a busy
 * start cannot make it stutter.
 */

/** The start-up animations, in the order Settings shows them. */
internal enum class StartAnimation(@StringRes val titleRes: Int) {
    /** No animation: the dashboard's still picture, as before. */
    STILL(R.string.start_anim_still),
    WELCOME(R.string.start_anim_welcome),
    ASSEMBLE(R.string.start_anim_assemble),
    IGNITION(R.string.start_anim_ignition),
    ROAD(R.string.start_anim_road),
    BLUEPRINT(R.string.start_anim_blueprint),
    AURORA(R.string.start_anim_aurora)
}

internal object StartSplash {
    private const val PREFS = "start_splash"
    private const val KEY = "animation"

    private val _chosen = MutableStateFlow(StartAnimation.WELCOME)
    val chosen: StateFlow<StartAnimation> = _chosen

    /**
     * The visible page's tiles on the screen, for Blueprint. Only gathered
     * while [collecting]: a page being swiped lays out on every frame.
     */
    @Volatile var tiles: List<RectF> = emptyList()
    @Volatile var collecting = true

    fun load(context: Context) {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        _chosen.value = StartAnimation.entries.firstOrNull { it.name == name } ?: StartAnimation.WELCOME
    }

    fun save(context: Context, animation: StartAnimation) {
        _chosen.value = animation
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, animation.name).apply()
    }

    /** A grid of [items] laid out at [left], [top] on the screen, cells [cellW] x [cellH] px. */
    fun grid(left: Float, top: Float, cellW: Float, cellH: Float, items: List<DashboardItem>) {
        tiles = items.map { RectF(left + it.x * cellW, top + it.y * cellH, left + (it.x + it.w) * cellW, top + (it.y + it.h) * cellH) }
    }
}

/**
 * One start-up animation, drawn from its start. [apps] are the tiles' apps
 * as they are placed (name, placed yet) for Assemble; [frozenAt] draws one
 * still moment (in seconds) for the cards in Settings; [sample] stands in for
 * a dashboard that is not there (a card, a try-out).
 */
@SuppressLint("ViewConstructor")
internal class StartSplashView(
    context: Context,
    private val style: StartAnimation,
    private val apps: () -> List<Pair<String, Boolean>> = { emptyList() },
    private val frozenAt: Float = -1f,
    private val sample: Boolean = false
) : View(context) {
    /** From the first frame drawn: a slow start must not skip the opening. */
    private var startedAt = 0L
    private var exitAt = 0L
    private var onExited: (() -> Unit)? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val bold = Typeface.create("sans-serif", Typeface.BOLD)
    private val light = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val m = Matrix()
    private val path = Path()
    private val seg = Path()
    private val rect = RectF()

    // The logo in its own 108-unit square (the launcher icon's), centred on (54, 54).
    private val arc = Path().apply {
        moveTo(49.5f, 34.75f); lineTo(54.5f, 34.75f)
        arcTo(RectF(35.25f, 34.75f, 73.75f, 73.25f), -90f, 180f); lineTo(49.5f, 73.25f)
    }
    private val arcLength = PathMeasure(arc, false).length
    private val rim = LinearGradient(0f, 34.75f, 0f, 73.25f, intArrayOf(TEAL, BLUE, VIOLET), null, Shader.TileMode.CLAMP)
    private val glow = RadialGradient(0f, 0f, 1f, intArrayOf(alpha(BLUE, 0.45f), 0), null, Shader.TileMode.CLAMP)
    private val soft = RadialGradient(0f, 0f, 1f, intArrayOf(Color.WHITE, 0), null, Shader.TileMode.CLAMP)

    private var w = 0f
    private var h = 0f
    private var cx = 0f
    private var cy = 0f
    /** One design pixel: the designs are for 1280 x 720, or 720 x 1280 upright. */
    private var s = 1f
    private var background: Shader? = null
    private var sheen: Shader? = null
    private var sheenWidth = 0f

    // Blueprint's tiles as outlines, rebuilt when the tiles change.
    private var tileSource: List<RectF>? = null
    private var tileRects: List<RectF> = emptyList()
    private var tilePaths: List<Pair<Path, Float>> = emptyList()
    private var tilesSince = -1f

    private val stars = java.util.Random(7).let { r -> FloatArray(120) { r.nextFloat() } }

    /** Plays the way out, then [done] (on this view's thread). */
    fun exit(done: () -> Unit) {
        if (exitAt != 0L) return
        exitAt = SystemClock.uptimeMillis()
        onExited = done
        invalidate()
    }

    override fun onSizeChanged(width: Int, height: Int, oldw: Int, oldh: Int) {
        w = width.toFloat(); h = height.toFloat(); cx = w / 2; cy = h / 2
        s = if (w >= h) min(w / 1280f, h / 720f) else min(w / 720f, h / 1280f)
        val far = hypot(cx, cy)
        background = when (style) {
            StartAnimation.ASSEMBLE -> RadialGradient(cx, cy * 0.88f, far, 0xFF1E2650.toInt(), 0xFF0A0D18.toInt(), Shader.TileMode.CLAMP)
            StartAnimation.IGNITION -> RadialGradient(cx, cy, far * 0.9f, 0xFF121729.toInt(), 0xFF04050A.toInt(), Shader.TileMode.CLAMP)
            StartAnimation.ROAD -> LinearGradient(0f, 0f, 0f, h * HORIZON, 0xFF0A0E22.toInt(), 0xFF151A3A.toInt(), Shader.TileMode.CLAMP)
            else -> null
        }
        text.typeface = bold; text.textSize = 96 * s; text.letterSpacing = 0.06f
        sheenWidth = text.measureText(BRAND)
        sheen = LinearGradient(0f, 0f, sheenWidth * 3, 0f, intArrayOf(Color.WHITE, Color.WHITE, 0xFF9FB3FF.toInt(), 0xFF3A4050.toInt(), 0xFF3A4050.toInt()),
            floatArrayOf(0f, 0.36f, 0.46f, 0.56f, 1f), Shader.TileMode.CLAMP)
        tileSource = null
    }

    override fun onDraw(c: Canvas) {
        if (w == 0f) return
        val now = SystemClock.uptimeMillis()
        if (startedAt == 0L) startedAt = now
        val t = if (frozenAt >= 0) frozenAt else (now - startedAt) / 1000f
        val out = if (exitAt == 0L) 0f else (now - exitAt) / 1000f
        val e = clamp(out * 1000f / EXIT_MS)
        if (e >= 1f) {
            onExited?.let { done -> onExited = null; post(done) }
            return
        }
        when (style) {
            StartAnimation.ASSEMBLE -> assemble(c, t, e)
            StartAnimation.IGNITION -> ignition(c, t, e)
            StartAnimation.ROAD -> road(c, t, e)
            StartAnimation.BLUEPRINT -> blueprint(c, t, e)
            StartAnimation.AURORA -> aurora(c, t, e)
            StartAnimation.WELCOME -> welcome(c, t, e)
            StartAnimation.STILL -> Unit
        }
        // About 30 frames a second: plenty for this, and half the work for a unit that is starting everything.
        if (frozenAt < 0) postInvalidateDelayed(FRAME_MS)
    }

    // ---------- Assemble: the logo builds itself, the tiles' apps tick off below ----------

    private fun assemble(c: Canvas, t: Float, e: Float) {
        val layer = fadeLayer(c, e, 1f + 0.25f * ease(e))
        fill.color = Color.BLACK; fill.shader = background; c.drawRect(0f, 0f, w, h, fill); fill.shader = null
        val breath = if (t > 2.3f) (1 - cos(2 * PI * (t - 2.3f) / 1.6f)).toFloat() / 2 else 0f
        mark(
            c, cx, cy - 70 * s, 300 * s,
            drops = FloatArray(3) { prog(t, 0.15f + 0.2f * it, 0.7f) },
            draw = ease(prog(t, 1f, 1f)),
            ring = back(prog(t, 1.8f, 0.5f)) * (1 + 0.18f * breath),
            glowAlpha = prog(t, 1.8f, 1.5f)
        )
        val word = ease(prog(t, 1.9f, 0.5f))
        drawText(c, BRAND, cx, cy + 150 * s + 24 * s * (1 - word), 64 * s, Color.WHITE, word, bold)

        // The apps being put in their tiles, lit once placed.
        val list = apps().take(5)
        val placed = list.count { it.second }
        if (list.isNotEmpty()) {
            text.typeface = medium; text.textSize = 19 * s; text.letterSpacing = 0f
            val pad = 18 * s; val dot = 12 * s; val gap = 18 * s; val ph = 44 * s
            val widths = list.map { text.measureText(it.first) + pad * 2 + dot + 10 * s }
            val total = widths.sum() + gap * (list.size - 1)
            val k = min(1f, w * 0.92f / total)
            c.save(); c.scale(k, k, cx, h - 100 * s)
            var x = cx - total / 2
            val y = h - 100 * s
            list.forEachIndexed { i, (name, done) ->
                rect.set(x, y - ph / 2, x + widths[i], y + ph / 2)
                fill.color = if (done) alpha(TEAL, 0.08f) else alpha(Color.WHITE, 0.05f)
                c.drawRoundRect(rect, ph / 2, ph / 2, fill)
                stroke.strokeWidth = 1.5f * s
                stroke.color = if (done) alpha(TEAL, 0.4f) else alpha(Color.WHITE, 0.08f)
                c.drawRoundRect(rect, ph / 2, ph / 2, stroke)
                val dx = x + pad + dot / 2
                if (done) {
                    fill.color = TEAL; c.drawCircle(dx, y, dot / 2, fill)
                } else {
                    stroke.color = alpha(Color.WHITE, 0.4f); stroke.strokeWidth = 2 * s
                    rect.set(dx - dot / 2, y - dot / 2, dx + dot / 2, y + dot / 2)
                    c.drawArc(rect, (t * 450) % 360, 270f, false, stroke)
                }
                text.textAlign = Paint.Align.LEFT
                text.color = if (done) INK else alpha(INK, 0.45f)
                c.drawText(name, dx + dot / 2 + 10 * s, y + 7 * s, text)
                text.textAlign = Paint.Align.CENTER
                x += widths[i] + gap
            }
            c.restore()
        }
        // The bar under them: the apps placed, or the time a start takes when there are none.
        val f = if (list.isNotEmpty()) placed / list.size.toFloat() else min(0.95f, ease(t / 4.5f))
        val bw = 340 * s; val by = h - 50 * s
        fill.color = alpha(Color.WHITE, 0.08f); rect.set(cx - bw / 2, by, cx + bw / 2, by + 3 * s); c.drawRoundRect(rect, 2 * s, 2 * s, fill)
        fill.color = Color.BLACK; fill.shader = LinearGradient(cx - bw / 2, 0f, cx + bw / 2, 0f, intArrayOf(TEAL, BLUE, VIOLET), null, Shader.TileMode.CLAMP)
        rect.right = cx - bw / 2 + bw * f; c.drawRoundRect(rect, 2 * s, 2 * s, fill); fill.shader = null
        if (layer) c.restore()
    }

    // ---------- Ignition: the cluster's self-test, needle and warning lamps ----------

    private fun ignition(c: Canvas, t: Float, e: Float) {
        c.save()
        if (e > 0) {
            // An iris closing on the dashboard.
            path.reset(); path.addCircle(cx, cy, (1 - ease(e)) * hypot(cx, cy), Path.Direction.CW); c.clipPath(path)
        }
        fill.color = Color.BLACK; fill.shader = background; c.drawRect(0f, 0f, w, h, fill); fill.shader = null
        val r = min(280 * s, h * 0.4f)
        val gy = cy - (if (w >= h) 0f else 60 * s)
        val p = prog(t, 0.3f, 2.6f)
        val f = if (p < 0.45f) ease(p / 0.45f) else 1 - ease((p - 0.45f) / 0.55f)
        val reached = if (p < 0.45f) f else 1f
        val dim = prog(t, 2.9f, 0.4f)
        stroke.strokeWidth = 4 * s
        for (i in 0..54) {
            val a = Math.toRadians(-225.0 + i * 5).toFloat()
            val red = i > 44
            val lit = t > 0.3f && i / 54f <= reached + 0.001f
            val on = if (lit) 1f + (0.35f - 1f) * dim else 0f
            stroke.color = if (red) alpha(RED, 0.3f + 0.7f * on) else alpha(if (lit) BLUE else Color.WHITE, if (lit) on else 0.14f)
            if (lit && dim < 1f) stroke.setShadowLayer(6 * s, 0f, 0f, stroke.color) else stroke.clearShadowLayer()
            val r1 = if (i % 6 == 0) r * 0.87f else r * 0.935f
            c.drawLine(cx + r1 * cos(a), gy + r1 * sin(a), cx + r * cos(a), gy + r * sin(a), stroke)
        }
        stroke.clearShadowLayer()
        // The needle, swept to the red line and back, then gone for the logo.
        val needle = 1 - prog(t, 2.8f, 0.4f)
        if (needle > 0) {
            c.save(); c.rotate(-135f + 270f * f, cx, gy)
            path.reset(); path.moveTo(cx - 4 * s, gy); path.lineTo(cx, gy - r * 0.93f); path.lineTo(cx + 4 * s, gy); path.close()
            fill.color = alpha(RED, needle); fill.setShadowLayer(8 * s, 0f, 0f, alpha(RED, needle))
            c.drawPath(path, fill); fill.clearShadowLayer()
            c.restore()
            fill.color = alpha(0xFF1A1E2C.toInt(), needle); c.drawCircle(cx, gy, 14 * s, fill)
            stroke.color = alpha(RED, needle); stroke.strokeWidth = 3 * s; c.drawCircle(cx, gy, 14 * s, stroke)
        }
        val logo = back(prog(t, 2.2f, 0.6f))
        if (logo > 0) mark(c, cx, gy - 10 * s, 110 * s * logo)
        drawText(c, BRAND, cx, gy + 90 * s, 34 * s, Color.WHITE, ease(prog(t, 2.6f, 0.6f)), bold)
        // The warning lamps, all on for a moment as at key-on.
        val bulb = prog(t, 0.4f, 1.6f)
        val lit = if (bulb < 0.1f) 0.12f + 8.8f * bulb else if (bulb < 0.7f) 1f else 1f - 0.88f * clamp((bulb - 0.7f) / 0.3f)
        val size = 40 * s; val step = 78 * s
        for (i in 0 until 6) lamp(c, i, cx + (i - 2.5f) * step, h - 60 * s, size, if (t > 0.4f) lit else 0.12f)
        c.restore()
    }

    private fun lamp(c: Canvas, which: Int, x: Float, y: Float, size: Float, a: Float) {
        val color = if (which < 3) RED else AMBER
        c.save(); c.translate(x - size / 2, y - size / 2); c.scale(size / 48f, size / 48f)
        stroke.strokeWidth = 3f; stroke.color = alpha(color, a)
        if (a > 0.5f) stroke.setShadowLayer(8f, 0f, 0f, stroke.color)
        path.reset()
        when (which) {
            0 -> { // battery
                path.addRoundRect(8f, 15f, 40f, 37f, 3f, 3f, Path.Direction.CW)
                path.moveTo(14f, 15f); path.lineTo(14f, 11f); path.lineTo(20f, 11f); path.lineTo(20f, 15f)
                path.moveTo(28f, 15f); path.lineTo(28f, 11f); path.lineTo(34f, 11f); path.lineTo(34f, 15f)
                path.moveTo(15f, 26f); path.lineTo(21f, 26f); path.moveTo(28f, 26f); path.lineTo(34f, 26f); path.moveTo(31f, 23f); path.lineTo(31f, 29f)
            }
            1 -> { // coolant
                path.moveTo(24f, 8f); path.lineTo(24f, 28f); path.addCircle(24f, 34f, 6f, Path.Direction.CW)
                path.moveTo(30f, 14f); path.lineTo(34f, 14f); path.moveTo(30f, 20f); path.lineTo(34f, 20f)
            }
            2 -> { // oil
                path.moveTo(6f, 30f); path.lineTo(28f, 30f); path.lineTo(38f, 22f); path.lineTo(42f, 22f)
                path.moveTo(10f, 30f); path.cubicTo(10f, 24f, 16f, 22f, 20f, 22f); path.lineTo(28f, 22f)
                path.moveTo(37f, 31f); path.lineTo(38f, 36f)
            }
            3 -> { // engine
                path.moveTo(10f, 18f); path.lineTo(16f, 18f); path.lineTo(19f, 14f); path.lineTo(29f, 14f); path.lineTo(32f, 18f)
                path.lineTo(38f, 18f); path.lineTo(38f, 34f); path.lineTo(10f, 34f); path.close()
                path.moveTo(6f, 22f); path.lineTo(6f, 30f); path.moveTo(42f, 22f); path.lineTo(42f, 30f)
            }
            4 -> path.addCircle(24f, 24f, 15f, Path.Direction.CW) // ABS
            else -> { // warning
                path.moveTo(24f, 7f); path.lineTo(42f, 39f); path.lineTo(6f, 39f); path.close()
                path.moveTo(24f, 19f); path.lineTo(24f, 29f); path.moveTo(24f, 34f); path.lineTo(24f, 34.5f)
            }
        }
        c.drawPath(path, stroke)
        if (which == 4) {
            text.typeface = bold; text.textSize = 11f; text.letterSpacing = 0f; text.color = stroke.color
            c.drawText("ABS", 24f, 28f, text)
        }
        stroke.clearShadowLayer()
        c.restore()
    }

    // ---------- Night road: driving into the logo ----------

    private fun road(c: Canvas, t: Float, e: Float) {
        val layer = fadeLayer(c, e, 1f + ease(e))
        val hy = h * HORIZON
        fill.color = Color.BLACK; fill.shader = background; c.drawRect(0f, 0f, w, hy, fill); fill.shader = null
        fill.color = 0xFF05060B.toInt(); c.drawRect(0f, hy, w, h, fill)
        for (i in 0 until 40) {
            fill.color = alpha(Color.WHITE, 0.35f + 0.45f * stars[i * 3 + 2])
            c.drawCircle(stars[i * 3] * w, stars[i * 3 + 1] * hy * 0.9f, (1f + stars[i * 3 + 2]) * s, fill)
        }
        softSpot(c, cx, hy, w * 0.4f, 0.35f, alpha(BLUE, 0.45f))
        // The road on the ground: a point X across (1 = the road's edge) and z ahead lands at these.
        val speed = 6f * (1 + 8 * e * e)
        val run = t * speed
        fun y(z: Float) = hy + (h - hy) / z
        fun x(across: Float, z: Float) = cx + across * w * 0.42f / z
        fun quad(a: Float, b: Float, z0: Float, z1: Float) {
            path.reset(); path.moveTo(x(a, z0), y(z0)); path.lineTo(x(a, z1), y(z1)); path.lineTo(x(b, z1), y(z1)); path.lineTo(x(b, z0), y(z0)); path.close()
            c.drawPath(path, fill)
        }
        fill.color = 0xFF0C0F1C.toInt(); quad(-1f, 1f, 0.8f, 80f)
        fill.color = alpha(TEAL, 0.9f); quad(-0.95f, -0.92f, 0.8f, 80f)
        fill.color = alpha(VIOLET, 0.9f); quad(0.92f, 0.95f, 0.8f, 80f)
        val dashes = run % 2.4f
        for (k in 0 until 30) {
            val z0 = max(0.8f, 0.8f + k * 2.4f - dashes)
            val z1 = 0.8f + k * 2.4f - dashes + 1.2f
            if (z1 <= z0) continue
            fill.color = alpha(Color.WHITE, 0.85f * (1 - z0 / 72f).coerceAtLeast(0f)); quad(-0.025f, 0.025f, z0, z1)
        }
        val posts = run % 4f
        stroke.strokeCap = Paint.Cap.BUTT
        for (k in 0 until 16) {
            val z = 0.9f + k * 4f - posts
            if (z < 0.9f) continue
            val fade = (1 - z / 64f).coerceAtLeast(0f)
            stroke.strokeWidth = 8 * s / z
            for ((side, color) in listOf(-1.35f to BLUE, 1.35f to VIOLET)) {
                stroke.color = alpha(color, 0.8f * fade)
                c.drawLine(x(side, z), y(z), x(side, z), y(z) - 0.5f * (h - hy) / z, stroke)
            }
        }
        stroke.strokeCap = Paint.Cap.ROUND
        softSpot(c, cx - w * 0.1f, h + 40 * s, w * 0.3f, 1.6f, 0x2EFFFFF0)
        softSpot(c, cx + w * 0.1f, h + 40 * s, w * 0.3f, 1.6f, 0x2EFFFFF0)
        // The logo coming toward the car, the name tightening above it.
        val come = prog(t, 0.2f, 0.9f)
        val size = 70 * s + 120 * s * prog(t, 0.2f, 4.6f).pow(2)
        if (come > 0) mark(c, cx, hy - 24 * s - size / 2, size, glowAlpha = come, alpha = come)
        val name = ease(prog(t, 0.8f, 1.4f))
        text.letterSpacing = 0.8f - 0.6f * name; text.textSize = 72 * s
        text.typeface = bold; val dash = text.measureText("DASH")
        text.typeface = light; val wheel = text.measureText("WHEEL")
        val left = cx - (dash + wheel) / 2
        text.textAlign = Paint.Align.LEFT; text.color = alpha(INK, name)
        text.typeface = bold; c.drawText("DASH", left, h * 0.22f, text)
        text.typeface = light; c.drawText("WHEEL", left + dash, h * 0.22f, text)
        text.textAlign = Paint.Align.CENTER
        // The way out: faster and faster, into a white flash.
        if (e > 0) { fill.color = alpha(Color.WHITE, 0.75f * sin(PI.toFloat() * e)); c.drawRect(0f, 0f, w, h, fill) }
        if (layer) c.restore()
    }

    // ---------- Blueprint: the dashboard's own tiles draw themselves, then open on it ----------

    private fun blueprint(c: Canvas, t: Float, e: Float) {
        val source = if (sample) sampleTiles() else StartSplash.tiles
        if (source !== tileSource) {
            tileSource = source
            val loc = IntArray(2).also { if (!sample) getLocationOnScreen(it) }
            tileRects = source.map { RectF(it.left - loc[0] + 4 * s, it.top - loc[1] + 4 * s, it.right - loc[0] - 4 * s, it.bottom - loc[1] - 4 * s) }
            tilePaths = tileRects.map { r ->
                val p = Path().apply { addRoundRect(r, 16 * s, 16 * s, Path.Direction.CW) }
                p to PathMeasure(p, false).length
            }
            if (tileRects.isNotEmpty() && tilesSince < 0) tilesSince = if (frozenAt >= 0) 0f else t
        }
        // Each tile, once open, shows the real dashboard through it.
        val out = e * EXIT_MS / 1000f
        c.save()
        tileRects.forEachIndexed { i, r ->
            val o = ease(clamp((out - i * 0.06f) / 0.3f))
            if (o > 0) c.clipOutRect(r.centerX() - r.width() / 2 * o, r.centerY() - r.height() / 2 * o, r.centerX() + r.width() / 2 * o, r.centerY() + r.height() / 2 * o)
        }
        val layer = fadeLayer(c, clamp((e - 0.55f) / 0.45f), 1f)
        fill.color = 0xFF070A14.toInt(); c.drawRect(0f, 0f, w, h, fill)
        stroke.strokeWidth = 1f; stroke.color = alpha(BLUE, 0.07f)
        val cell = 40 * s
        var gx = 0f; while (gx < w) { c.drawLine(gx, 0f, gx, h, stroke); gx += cell }
        var gy = 0f; while (gy < h) { c.drawLine(0f, gy, w, gy, stroke); gy += cell }
        tilePaths.forEachIndexed { i, (p, length) ->
            val d = ease(prog(t, tilesSince + 0.2f + 0.25f * i, 0.9f))
            if (d <= 0) return@forEachIndexed
            val r = tileRects[i]
            fill.color = alpha(BLUE, 0.04f * d); c.drawRoundRect(r, 16 * s, 16 * s, fill)
            // A light passing over the tile while its app is on its way.
            c.save(); c.clipRect(r)
            val band = r.left + ((t * 0.5f + i * 0.17f) % 1.4f - 0.2f) * r.width()
            fill.color = Color.BLACK; fill.shader = LinearGradient(band - 60 * s, 0f, band + 60 * s, 0f, intArrayOf(0, alpha(BLUE, 0.1f * d), 0), null, Shader.TileMode.CLAMP)
            c.drawRect(r, fill); fill.shader = null
            c.restore()
            seg.reset(); PathMeasure(p, false).getSegment(0f, length * d, seg, true)
            stroke.strokeWidth = 2 * s; stroke.color = BLUE; stroke.setShadowLayer(6 * s, 0f, 0f, alpha(BLUE, 0.6f))
            c.drawPath(seg, stroke); stroke.clearShadowLayer()
        }
        // The name in the middle, on a plate of its own over the outlines.
        val brand = ease(prog(t, 0.1f, 0.5f))
        if (brand > 0) {
            text.typeface = bold; text.textSize = 40 * s; text.letterSpacing = 0f
            val tw = text.measureText(BRAND); val lw = 64 * s; val total = lw + 16 * s + tw
            rect.set(cx - total / 2 - 28 * s, cy - 50 * s, cx + total / 2 + 28 * s, cy + 50 * s)
            fill.color = alpha(0xFF070A14.toInt(), 0.9f * brand); c.drawRoundRect(rect, 24 * s, 24 * s, fill)
            mark(c, cx - total / 2 + lw / 2, cy, lw, alpha = brand)
            text.textAlign = Paint.Align.LEFT; text.color = alpha(INK, brand)
            c.drawText(BRAND, cx - total / 2 + lw + 16 * s, cy + 14 * s, text)
            text.textAlign = Paint.Align.CENTER
        }
        if (layer) c.restore()
        c.restore()
    }

    /** A dashboard's tiles to stand in for one that is not there: the Kinetic look's. */
    private fun sampleTiles(): List<RectF> {
        val k = if (w >= h) floatArrayOf(w / 1280f, h / 720f) else floatArrayOf(w / 720f, h / 1280f)
        val layout = if (w >= h) SAMPLE_WIDE else SAMPLE_TALL
        return layout.map { (x, y, rw, rh) -> RectF(x * k[0], y * k[1], (x + rw) * k[0], (y + rh) * k[1]) }
    }

    // ---------- Aurora: slow light behind a breathing logo ----------

    private fun aurora(c: Canvas, t: Float, e: Float) {
        val layer = fadeLayer(c, e, 1f)
        fill.color = 0xFF06070D.toInt(); c.drawRect(0f, 0f, w, h, fill)
        fun drift(period: Float) = (1 - cos(2 * PI * t / period).toFloat()) / 2
        val d1 = drift(12f); val d2 = drift(14f); val d3 = drift(10f)
        softSpot(c, cx - 140 * s + 160 * s * d1, cy - 80 * s + 120 * s * d1, 520 * s * (1 + 0.2f * d1), 1f, alpha(TEAL, 0.55f))
        softSpot(c, cx + 200 * s - 200 * s * d2, cy + 140 * s - 60 * s * d2, 520 * s * (1 - 0.2f * d2), 1f, alpha(BLUE, 0.55f))
        softSpot(c, cx + 440 * s - 120 * s * d3, cy - 140 * s + 200 * s * d3, 520 * s * (1 + 0.1f * d3), 1f, alpha(VIOLET, 0.55f))
        fill.color = Color.BLACK; fill.shader = RadialGradient(cx, cy * 0.92f, hypot(cx, cy) * 0.8f, intArrayOf(0, alpha(0xFF06070D.toInt(), 0.6f)), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, fill); fill.shader = null
        val breath = sin(2 * PI * t / 2.4f).toFloat()
        mark(c, cx, cy - 70 * s, 150 * s * (1 + 0.03f * (1 + breath)), glowAlpha = 0.6f + 0.4f * breath)
        drawText(c, BRAND, cx, cy + 70 * s, 54 * s, Color.WHITE, 1f, medium)
        val lw = 100 * s; val ly = cy + 128 * s
        fill.color = alpha(Color.WHITE, 0.08f); rect.set(cx - lw / 2, ly, cx + lw / 2, ly + 2 * s); c.drawRoundRect(rect, s, s, fill)
        val at = ((t % 1.2f) / 1.2f).let { ease(it) } * 1.4f - 0.4f
        rect.set(max(cx - lw / 2, cx - lw / 2 + at * lw), ly, min(cx + lw / 2, cx - lw / 2 + (at + 0.4f) * lw), ly + 2 * s)
        if (rect.width() > 0) { fill.color = Color.WHITE; c.drawRoundRect(rect, s, s, fill) }
        if (layer) c.restore()
    }

    // ---------- Welcome lights: an LED strip from the centre out, the name, a greeting ----------

    private fun welcome(c: Canvas, t: Float, e: Float) {
        c.save()
        if (e > 0) { val k = ease(e); c.clipRect(0f, cy * k, w, h - (h - cy) * k) }
        fill.color = 0xFF030406.toInt(); c.drawRect(0f, 0f, w, h, fill)
        val sy = cy - 12 * s
        val stripW = min(1000 * s, w * 0.86f)
        softSpot(c, cx, sy, stripW * 0.5f, 100 * s / (stripW * 0.5f), alpha(BLUE, 0.25f * prog(t, 1.2f, 1f)))
        val n = 24; val gap = 6 * s; val segW = (stripW - gap * (n - 1)) / n
        for (i in 0 until n) {
            val on = prog(t, 0.2f + abs(i - (n - 1) / 2f) * 0.045f, 0.25f)
            val x = cx - stripW / 2 + i * (segW + gap)
            rect.set(x, sy - 3 * s, x + segW, sy + 3 * s)
            fill.color = mix(0xFF1A1D26.toInt(), mix(TEAL, BLUE, i / (n - 1f)), on)
            if (on > 0) fill.setShadowLayer(14 * s, 0f, 0f, alpha(BLUE, 0.8f * on))
            c.drawRoundRect(rect, 3 * s, 3 * s, fill); fill.clearShadowLayer()
        }
        // The name, with a light running across it.
        val name = prog(t, 1f, 0.7f)
        if (name > 0) {
            text.typeface = bold; text.textSize = 96 * s; text.letterSpacing = 0.06f
            m.setTranslate(cx - sheenWidth / 2 - 2 * sheenWidth * (1 - ease(prog(t, 1f, 2.4f))), 0f)
            sheen?.setLocalMatrix(m)
            text.shader = sheen; text.color = alpha(Color.WHITE, name)
            c.drawText(BRAND, cx, sy - 50 * s, text); text.shader = null
        }
        val greet = ease(prog(t, 2.4f, 0.6f))
        drawText(c, context.getString(greeting()), cx, sy + 80 * s + 24 * s * (1 - greet), 34 * s, INK, greet, light)
        // The time, and the temperature outside when the car says it.
        val facts = ease(prog(t, 2.8f, 0.6f))
        val units = Units.current.value
        val outside = UnitSignals.outsideTempC.value?.let { units.tempTight(it) }
        val time = units.time(java.util.Date())
        drawText(c, if (outside != null) "$time      $outside" else time, cx, sy + 136 * s + 24 * s * (1 - facts), 24 * s, alpha(INK, 0.6f), facts, medium)
        c.restore()
    }

    private fun greeting(): Int = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> R.string.start_greet_morning
        in 12..17 -> R.string.start_greet_afternoon
        else -> R.string.start_greet_evening
    }

    // ---------- drawing helpers ----------

    /**
     * The logo at ([x], [y]), [size] across. [drops] brings each tile in from
     * above, [draw] draws the D, [ring] sizes the wheel.
     */
    private fun mark(
        c: Canvas, x: Float, y: Float, size: Float,
        drops: FloatArray? = null, draw: Float = 1f, ring: Float = 1f, glowAlpha: Float = 0f, alpha: Float = 1f
    ) {
        val u = size / 72f
        c.save(); c.translate(x, y); c.scale(u, u); c.translate(-54f, -54f)
        if (glowAlpha > 0) {
            m.setScale(42f, 42f); m.postTranslate(54f, 54f); glow.setLocalMatrix(m)
            fill.shader = glow; fill.color = Color.BLACK; fill.alpha = (255 * glowAlpha * alpha).toInt(); c.drawCircle(54f, 54f, 42f, fill); fill.shader = null; fill.alpha = 255
        }
        for (i in 0 until 3) {
            val p = drops?.get(i) ?: 1f
            if (p <= 0) continue
            val top = 31f + i * 16.75f - (1 - back(p)) * 76f
            fill.color = alpha(TILE_COLORS[i], alpha * clamp(p * 3))
            rect.set(33f, top, 43f, top + 12.5f); c.drawRoundRect(rect, 3.2f, 3.2f, fill)
        }
        if (draw > 0) {
            seg.reset(); PathMeasure(arc, false).getSegment(0f, arcLength * draw, seg, true)
            stroke.color = Color.WHITE; stroke.alpha = (255 * alpha).toInt(); stroke.shader = rim; stroke.strokeWidth = 7.5f
            c.drawPath(seg, stroke); stroke.shader = null
        }
        if (ring > 0) {
            stroke.color = alpha(BLUE, alpha); stroke.strokeWidth = 3.2f
            c.drawCircle(56f, 54f, 4.6f * ring, stroke)
        }
        c.restore()
    }

    private fun drawText(c: Canvas, line: String, x: Float, y: Float, size: Float, color: Int, a: Float, face: Typeface) {
        if (a <= 0) return
        text.typeface = face; text.textSize = size; text.letterSpacing = 0f; text.color = alpha(color, a)
        c.drawText(line, x, y, text)
    }

    /** A soft round light at ([x], [y]), [r] across and [squash] as tall. */
    private fun softSpot(c: Canvas, x: Float, y: Float, r: Float, squash: Float, color: Int) {
        if (Color.alpha(color) == 0) return
        m.setScale(r, r * squash); m.postTranslate(x, y); soft.setLocalMatrix(m)
        fill.color = Color.BLACK; fill.shader = soft; fill.colorFilter = android.graphics.PorterDuffColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN)
        c.drawRect(x - r, y - r * squash, x + r, y + r * squash, fill)
        fill.shader = null; fill.colorFilter = null
    }

    /** Fades everything drawn next by [e], grown [scale] times; true when a layer must be restored. */
    private fun fadeLayer(c: Canvas, e: Float, scale: Float): Boolean {
        if (e <= 0f) return false
        c.saveLayerAlpha(0f, 0f, w, h, (255 * (1 - e)).toInt())
        c.scale(scale, scale, cx, cy)
        return true
    }

    companion object {
        const val BRAND = "Dashwheel"
        const val EXIT_MS = 800f
        private const val FRAME_MS = 33L
        private const val HORIZON = 0.52f
        private const val TEAL = 0xFF5CE1E6.toInt()
        private const val BLUE = 0xFF8FA8FF.toInt()
        private const val VIOLET = 0xFFC08CFF.toInt()
        private const val INK = 0xFFF4F6F8.toInt()
        private const val RED = 0xFFFF5A5F.toInt()
        private const val AMBER = 0xFFFFB84D.toInt()
        private val TILE_COLORS = intArrayOf(TEAL, BLUE, VIOLET)
        private val SAMPLE_WIDE = listOf(
            floatArrayOf(0f, 0f, 1280f, 58f), floatArrayOf(16f, 70f, 470f, 380f), floatArrayOf(880f, 30f, 384f, 240f),
            floatArrayOf(852f, 284f, 412f, 180f), floatArrayOf(16f, 470f, 820f, 170f), floatArrayOf(852f, 480f, 412f, 160f),
            floatArrayOf(0f, 654f, 720f, 66f)
        )
        private val SAMPLE_TALL = listOf(
            floatArrayOf(0f, 0f, 720f, 70f), floatArrayOf(16f, 86f, 688f, 420f), floatArrayOf(16f, 520f, 336f, 300f),
            floatArrayOf(368f, 520f, 336f, 300f), floatArrayOf(16f, 836f, 688f, 340f), floatArrayOf(0f, 1190f, 720f, 90f)
        )

        fun clamp(x: Float) = x.coerceIn(0f, 1f)
        fun prog(t: Float, start: Float, duration: Float) = clamp((t - start) / duration)
        fun ease(p: Float) = if (p < 0.5f) 4 * p * p * p else 1 - (-2 * p + 2).pow(3) / 2
        /** Overshoots a little before settling, as a thing that lands. */
        fun back(p: Float): Float {
            if (p <= 0f) return 0f
            val c1 = 2.2f; val q = p - 1
            return 1 + (c1 + 1) * q * q * q + c1 * q * q
        }
        fun alpha(color: Int, a: Float) = Color.argb((Color.alpha(color) * a.coerceIn(0f, 1f)).toInt(), Color.red(color), Color.green(color), Color.blue(color))
        fun mix(from: Int, to: Int, f: Float) = Color.argb(
            255, (Color.red(from) + (Color.red(to) - Color.red(from)) * f).toInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * f).toInt(), (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f).toInt()
        )
    }
}
