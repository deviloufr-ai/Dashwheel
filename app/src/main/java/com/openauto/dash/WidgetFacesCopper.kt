package com.openauto.dash

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.DrawResult
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * The copper set, after the latest Cupra cars (Tavascan, Formentor, Born,
 * Terramar): black or petrol blue with copper, corners cut on the diagonal
 * and triangles everywhere. Copper cockpit (the digital cluster's band of
 * slanted blades), Tri-LED (the triangular light signature), Copper blade
 * (oblique numerals over a tapering blade) and Light bar (the coast-to-coast
 * rear light, lit from the centre out). Same [WidgetFace] as the other
 * generic designs, so every widget can wear them.
 */

private fun String.upper() = uppercase(Locale.getDefault())

private fun Path.moveTo(p: Offset) = moveTo(p.x, p.y)
private fun Path.lineTo(p: Offset) = lineTo(p.x, p.y)

/** The set's header: a copper triangle mark, then the title in wide-spaced capitals. */
@Composable
private fun CopperHeader(f: WidgetFace, look: FaceLook, m: FaceMetrics, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TriMark(look, m.dp(6f).coerceIn(10.dp, 16.dp))
        Spacer(Modifier.width(m.dp(2.6f).coerceAtLeast(6.dp)))
        FaceText(f.title.upper(), look, m.label, Modifier.weight(1f), color = look.dim, weight = look.labelWeight, letterSpacing = 0.2.em)
        trailing?.invoke(this)
    }
}

/** A downward copper triangle with a dark notch at the top, like a lamp's signature seen head on. */
@Composable
private fun TriMark(look: FaceLook, side: Dp) {
    Spacer(
        Modifier.size(side).drawWithCache {
            val w = size.width
            val h = size.height
            val body = Path().apply { moveTo(0f, h * 0.12f); lineTo(w, h * 0.12f); lineTo(w / 2f, h * 0.92f); close() }
            val notch = Path().apply { moveTo(w * 0.5f, h * 0.12f); lineTo(w * 0.68f, h * 0.42f); lineTo(w * 0.32f, h * 0.42f); close() }
            val brush = Brush.verticalGradient(listOf(look.accent2, look.accent))
            onDrawBehind {
                drawPath(body, brush)
                drawPath(notch, Color.Black.copy(alpha = 0.55f))
            }
        }
    )
}

// --- Copper cockpit -------------------------------------------------------------------------

@Composable
internal fun CockpitLayout(f: WidgetFace, look: FaceLook, m: FaceMetrics) {
    val wide = m.wide
    Row(
        modifier = Modifier.fillMaxSize().padding(m.pad.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (wide) Arrangement.spacedBy(m.dp(5f)) else Arrangement.Center
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true)) {
            val g = maxWidth.value
            // Without a reading the band stays dark, with no marker at its first blade.
            val fraction = rememberUpdatedState(f.fraction.takeIf { !f.idle })
            val alert = f.alert
            Spacer(Modifier.fillMaxSize().drawWithCache { bladeBand(look, alert, fraction) })
            Column(
                modifier = Modifier.align(Alignment.Center).padding(horizontal = (g * 0.2f).dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                FaceText(f.title.upper(), look, m.sp(max(g * 0.045f, 9f)), color = look.dim, weight = look.labelWeight, letterSpacing = 0.2.em)
                FaceValue(f, look, m, g * 0.21f, stacked = true)
            }
            if (!wide) {
                // The band's open bottom holds the main controls, or else the caption.
                val bottom = Modifier.align(Alignment.BottomCenter).padding(horizontal = (g * 0.16f).dp)
                if (f.actions.isNotEmpty() && g >= 140f) {
                    val n = (g * 0.6f / 56f).toInt().coerceIn(1, 3)
                    Box(bottom) { FaceActions(f.copy(actions = keyActions(f.actions, n)), look, m, small = true) }
                } else {
                    FaceCaption(f, look, m, bottom.padding(bottom = (g * 0.03f).dp), align = TextAlign.Center)
                }
            }
        }
        if (wide) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(m.dp(2.6f), Alignment.CenterVertically)
            ) {
                CopperHeader(f, look, m)
                FaceCaption(f, look, m)
                if (m.h >= 130f) SlashRule(look, m)
                when {
                    f.stats.isNotEmpty() -> f.stats.take(if (f.actions.isEmpty()) 3 else 2).forEach { FaceStatBlock(it, look, m) }
                    f.rows.isNotEmpty() -> FaceRows(f, look, m, 2)
                }
                FaceActions(f, look, m, small = true)
            }
        }
    }
}

/** A short copper rule that thins out to the right, cut on the slant at its start. */
@Composable
private fun SlashRule(look: FaceLook, m: FaceMetrics) {
    Spacer(
        Modifier.fillMaxWidth(0.6f).height(m.dp(1.4f).coerceIn(2.dp, 4.dp)).drawWithCache {
            val lean = size.height * 1.5f
            val bar = Path().apply {
                moveTo(lean, 0f); lineTo(size.width, 0f); lineTo(size.width, size.height); lineTo(0f, size.height); close()
            }
            val brush = Brush.horizontalGradient(listOf(look.accent, look.accent.copy(alpha = 0f)))
            onDrawBehind { drawPath(bar, brush) }
        }
    )
}

/**
 * The cluster's 270° band: slanted blades that thicken towards the top of the
 * scale, lit copper to champagne with a white leading blade, a hairline ring
 * inside and a triangle pointer. The blades are built once per size; only
 * [fraction] is read at draw time.
 */
private fun CacheDrawScope.bladeBand(look: FaceLook, alert: Boolean, fraction: State<Float?>): DrawResult {
    val n = 30
    val c = Offset(size.width / 2f, size.height / 2f)
    val r = size.minDimension / 2f
    val step = 270f / n
    val lean = step * 0.45f
    val outer = r * 0.97f
    val blades = List(n) { i ->
        val inner = outer - r * (0.07f + 0.08f * i / (n - 1f))
        val a = -135f + step * i + step * 0.14f
        val b = a + step * 0.72f
        Path().apply {
            moveTo(polarPoint(c, outer, a)); lineTo(polarPoint(c, outer, b))
            lineTo(polarPoint(c, inner, b - lean)); lineTo(polarPoint(c, inner, a - lean)); close()
        }
    }
    val shades = List(n) { i -> lerp(look.accent, look.accent2, i / (n - 1f)) }
    val halo = Stroke(r * 0.035f, join = StrokeJoin.Round)
    val ringR = r * 0.74f
    val ringTopLeft = Offset(c.x - ringR, c.y - ringR)
    val ringSize = Size(ringR * 2f, ringR * 2f)
    val ring = Stroke(1.dp.toPx())
    val marker = Path()
    return onDrawBehind {
        val v = fraction.value
        val fr = fraction01(v)
        val lit = (fr * n).roundToInt()
        blades.forEachIndexed { i, p ->
            val color = when {
                i >= lit -> look.track
                alert -> look.warn
                i == lit - 1 -> look.ink
                else -> shades[i]
            }
            drawPath(p, color)
        }
        if (lit > 0) drawPath(blades[lit - 1], (if (alert) look.warn else look.accent2).copy(alpha = 0.35f), style = halo)
        drawArc(look.accent.copy(alpha = 0.4f), 135f, 270f, false, ringTopLeft, ringSize, style = ring)
        if (v != null) {
            val a = -135f + 270f * fr
            marker.rewind()
            marker.moveTo(polarPoint(c, r * 0.83f, a))
            marker.lineTo(polarPoint(c, r * 0.745f, a - 4f))
            marker.lineTo(polarPoint(c, r * 0.745f, a + 4f))
            marker.close()
            drawPath(marker, if (alert) look.warn else look.ink)
        }
    }
}

// --- Tri-LED ----------------------------------------------------------------------------------

@Composable
internal fun TriLedLayout(f: WidgetFace, look: FaceLook, m: FaceMetrics) {
    val side = m.wide && m.h >= 110f && (f.stats.isNotEmpty() || f.rows.isNotEmpty())
    Column(modifier = Modifier.fillMaxSize().padding(m.pad.dp), verticalArrangement = Arrangement.spacedBy(m.dp(2.4f))) {
        val n = ((m.w - 90f) / 56f).toInt().coerceIn(1, 3)
        CopperHeader(f, look, m) { FaceActions(f.copy(actions = keyActions(f.actions, n)), look, m, small = true) }
        Row(modifier = Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(modifier = Modifier.weight(1f)) {
                FaceValue(f, look, m, min(m.h * 0.34f, m.w * 0.22f))
                FaceCaption(f, look, m)
            }
            if (side) {
                Column(
                    modifier = Modifier.padding(start = m.dp(4f)),
                    verticalArrangement = Arrangement.spacedBy(m.dp(1.6f)),
                    horizontalAlignment = Alignment.End
                ) {
                    if (f.stats.isNotEmpty()) f.stats.take(2).forEach { FaceStatBlock(it, look, m, align = Alignment.End) }
                    else FaceRows(f, look, m, 2, Modifier.width((m.w * 0.42f).dp))
                }
            }
        }
        val fraction = rememberUpdatedState(f.fraction)
        val alert = f.alert
        Spacer(Modifier.fillMaxWidth().height(m.dp(11f).coerceIn(14.dp, 30.dp)).drawWithCache { triangleBand(look, alert, fraction) })
    }
}

/**
 * A strip of triangles, alternately pointing up and down, that lights from the
 * left with the reading (white at the leading one). Without a reading it
 * rests on the signature: the last three lit, like a headlamp.
 */
private fun CacheDrawScope.triangleBand(look: FaceLook, alert: Boolean, fraction: State<Float?>): DrawResult {
    val h = size.height
    val b = h * 1.155f
    val step = b / 2f
    val n = max(3, ((size.width - b) / step).toInt() + 1)
    val x0 = (size.width - ((n - 1) * step + b)) / 2f
    val tris = List(n) { i ->
        val x = x0 + i * step
        val up = i % 2 == 0
        val cx = x + b / 2f
        val cy = if (up) h * 2f / 3f else h / 3f
        // Each triangle shrunk about its centre, leaving dark seams between them.
        fun p(px: Float, py: Float) = Offset(cx + (px - cx) * 0.78f, cy + (py - cy) * 0.78f)
        Path().apply {
            if (up) { moveTo(p(x, h)); lineTo(p(x + b, h)); lineTo(p(cx, 0f)) }
            else { moveTo(p(x, 0f)); lineTo(p(x + b, 0f)); lineTo(p(cx, h)) }
            close()
        }
    }
    val shades = List(n) { i -> lerp(look.accent, look.accent2, i / (n - 1f)) }
    val glow = Stroke(h * 0.12f, join = StrokeJoin.Round)
    val outline = Stroke(1.dp.toPx(), join = StrokeJoin.Round)
    val unlitEdge = look.accent.copy(alpha = 0.18f)
    return onDrawBehind {
        val v = fraction.value
        val lit = if (v == null) -1 else (fraction01(v) * n).roundToInt()
        tris.forEachIndexed { i, p ->
            val on = if (lit < 0) i >= n - 3 else i < lit
            if (on) {
                val color = when {
                    alert -> look.warn
                    i == lit - 1 -> look.ink
                    else -> shades[i]
                }
                drawPath(p, color.copy(alpha = 0.28f), style = glow)
                drawPath(p, color)
            } else {
                drawPath(p, look.track)
                drawPath(p, unlitEdge, style = outline)
            }
        }
    }
}

// --- Copper blade -------------------------------------------------------------------------------

/** A parallelogram leaning forward, for stat chips. */
private val SkewShape = GenericShape { size, _ ->
    val d = size.height * 0.28f
    moveTo(d, 0f); lineTo(size.width, 0f); lineTo(size.width - d, size.height); lineTo(0f, size.height); close()
}

@Composable
internal fun BladeLayout(f: WidgetFace, look: FaceLook, m: FaceMetrics) {
    val oblique = look.copy(numItalic = true)
    Column(modifier = Modifier.fillMaxSize().padding(m.pad.dp), verticalArrangement = Arrangement.spacedBy(m.dp(2.4f))) {
        CopperHeader(f, look, m)
        Column(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center) {
            FaceValue(f, oblique, m, min(m.h * 0.36f, m.w * 0.24f))
            val fraction = rememberUpdatedState(f.fraction)
            val alert = f.alert
            Spacer(Modifier.fillMaxWidth().height(m.dp(6f).coerceIn(8.dp, 16.dp)).drawWithCache { taperedBlade(look, alert, fraction) })
            Spacer(Modifier.height(m.dp(2f)))
            FaceCaption(f, look, m)
        }
        if (f.stats.isEmpty() && f.rows.isNotEmpty() && m.h >= 150f) FaceRows(f, look, m, if (m.h >= 220f) 2 else 1)
        val chips = if (m.h >= 120f) f.stats.take(if (m.w >= 300f) 3 else 2) else emptyList()
        if (chips.isNotEmpty() || f.actions.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.dp(2.6f).coerceAtLeast(6.dp))) {
                chips.forEach { SkewChip(it, look, m, Modifier.weight(1f, fill = false)) }
                Spacer(Modifier.weight(1f))
                FaceActions(f, look, m, small = true)
            }
        }
    }
}

/**
 * The blade under the number: hair-thin at the start, full height at a
 * slanted tip, lit copper up to the reading with a white edge where it stops.
 * With no reading it is lit all the way, a plain copper underline.
 */
private fun CacheDrawScope.taperedBlade(look: FaceLook, alert: Boolean, fraction: State<Float?>): DrawResult {
    val w = size.width
    val h = size.height
    val lean = h * 0.8f
    val wedge = Path().apply {
        moveTo(0f, h * 0.78f); lineTo(w, 0f); lineTo(w - lean, h); lineTo(0f, h); close()
    }
    val brush = if (alert) Brush.horizontalGradient(listOf(look.warn, look.warn))
    else Brush.horizontalGradient(listOf(look.accent.copy(alpha = 0.5f), look.accent, look.accent2))
    val edge = 2.dp.toPx()
    return onDrawBehind {
        val v = fraction.value
        val x = if (v == null) w else w * fraction01(v)
        drawPath(wedge, look.track)
        if (x > 0f) {
            clipRect(right = x) { drawPath(wedge, brush) }
            if (v != null) drawLine(look.ink, Offset(x, 0f), Offset(x - lean * 0.5f, h), edge)
        }
    }
}

@Composable
private fun SkewChip(stat: FaceStat, look: FaceLook, m: FaceMetrics, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(SkewShape)
            .background(look.fill)
            .border(1.dp, look.accent.copy(alpha = 0.35f), SkewShape)
            .padding(horizontal = m.dp(4.5f).coerceAtLeast(12.dp), vertical = m.dp(1.4f).coerceAtLeast(3.dp))
    ) {
        FaceText(stat.label.upper(), look, m.sp(max(m.u * 4.6f, 9f)), color = look.dim, weight = look.labelWeight, letterSpacing = 0.12.em)
        FaceText(stat.value, look, m.sp(max(m.u * 7.4f, 12f)), weight = FontWeight.SemiBold, family = look.numFont, italic = true)
    }
}

// --- Light bar --------------------------------------------------------------------------------

@Composable
internal fun LightBarLayout(f: WidgetFace, look: FaceLook, m: FaceMetrics) {
    val fraction = rememberUpdatedState(f.fraction)
    val alert = f.alert
    val barH = m.dp(2.2f).coerceIn(3.dp, 6.dp)
    val compact = m.h < 150f
    Box(modifier = Modifier.fillMaxSize()) {
        // The strip across the top and the light it throws down the tile.
        Spacer(Modifier.fillMaxSize().drawWithCache { lightBar(look, alert, fraction, barH.toPx(), m.pad.dp.toPx()) })
        Column(
            modifier = Modifier.fillMaxSize().padding(start = m.pad.dp, end = m.pad.dp, top = m.pad.dp + barH * 3, bottom = m.pad.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(m.dp(2f))
        ) {
            FaceText(f.title.upper(), look, m.label, color = look.dim, weight = look.labelWeight, letterSpacing = 0.3.em, align = TextAlign.Center)
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                FaceValue(f, look, m, min(m.h * 0.36f, m.w * 0.24f))
            }
            if (compact) {
                if (f.caption.isNotEmpty() || f.actions.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FaceCaption(f, look, m, Modifier.weight(1f))
                        FaceActions(f.copy(actions = keyActions(f.actions, 2)), look, m, small = true)
                    }
                }
            } else {
                FaceCaption(f, look, m, align = TextAlign.Center)
                if (f.stats.isNotEmpty() && m.h >= 170f) StatStrip(f.stats.take(if (m.w >= 280f) 3 else 2), look, m)
                FaceActions(f.copy(actions = keyActions(f.actions, 3)), look, m, small = true)
            }
        }
    }
}

/** Stats side by side, parted by thin slanted copper strokes. */
@Composable
private fun StatStrip(stats: List<FaceStat>, look: FaceLook, m: FaceMetrics) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.dp(3f))) {
        stats.forEachIndexed { i, s ->
            if (i > 0) {
                Spacer(
                    Modifier.width(m.dp(3f).coerceAtLeast(6.dp)).height(m.dp(9f).coerceAtLeast(18.dp)).drawBehind {
                        drawLine(look.accent, Offset(size.width, 0f), Offset(0f, size.height), 1.5.dp.toPx())
                    }
                )
            }
            FaceStatBlock(s, look, m, align = Alignment.CenterHorizontally)
        }
    }
}

/**
 * The coast-to-coast light: a strip across the top lit from the centre out to
 * the reading (all of it when there is none), a white-hot triangle at its
 * centre and a warm glow thrown down the tile, brighter as the strip grows.
 */
private fun CacheDrawScope.lightBar(look: FaceLook, alert: Boolean, fraction: State<Float?>, barH: Float, inset: Float): DrawResult {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val y = inset * 0.7f
    val half = (w / 2f - inset).coerceAtLeast(1f)
    val color = if (alert) look.warn else look.accent
    val lit = Brush.horizontalGradient(
        listOf(color.copy(alpha = 0.55f), look.accent2, look.ink, look.accent2, color.copy(alpha = 0.55f)),
        startX = cx - half, endX = cx + half
    )
    val bloom = color.copy(alpha = 0.22f)
    val glowR = max(w * 0.6f, 1f)
    val thrown = Brush.radialGradient(listOf(color.copy(alpha = 0.26f), Color.Transparent), Offset(cx, y), glowR)
    val s = barH * 2.6f
    val emblem = Path().apply {
        moveTo(cx - s, y - barH * 0.6f); lineTo(cx + s, y - barH * 0.6f); lineTo(cx, y + barH + s * 1.1f); close()
    }
    val emblemGlow = Stroke(barH * 1.2f, join = StrokeJoin.Round)
    val corner = CornerRadius(barH / 2f)
    val bloomCorner = CornerRadius(barH * 1.5f)
    return onDrawBehind {
        val v = fraction.value
        val fr = if (v == null) 1f else fraction01(v)
        val reach = half * fr
        drawOval(thrown, Offset(cx - glowR, y - h * 0.1f), Size(glowR * 2f, h * 1.05f), alpha = 0.35f + 0.65f * fr)
        drawRoundRect(look.track, Offset(cx - half, y), Size(half * 2f, barH), corner)
        if (reach > 0f) {
            drawRoundRect(bloom, Offset(cx - reach, y - barH), Size(reach * 2f, barH * 3f), bloomCorner)
            drawRoundRect(lit, Offset(cx - reach, y), Size(reach * 2f, barH), corner)
        }
        drawPath(emblem, color.copy(alpha = 0.35f), style = emblemGlow)
        drawPath(emblem, look.ink)
    }
}
