package com.openauto.dash

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.DrawResult
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * Draws a [WidgetFace] in a [WidgetDesign]: the classic layouts (the newer
 * ones live in WidgetFacesModern.kt), the materials
 * behind them, and the frame the view-hosting widgets (map, Maps window, 3D
 * car) get instead. Sizes scale with the tile, so a design reads the same on
 * a 3x2 clock and a 5x3 media card.
 */

/** Tile size in dp plus a "percent of the short side" unit, like CSS cqmin. */
internal class FaceMetrics(val w: Float, val h: Float, private val density: Density) {
    val u = min(w, h) / 100f
    val pad: Float = (u * 6.5f).coerceIn(10f, 18f)
    val wide: Boolean get() = w / h >= 1.4f
    fun dp(cq: Float): Dp = (u * cq).dp
    fun sp(dpValue: Float): TextUnit = with(density) { dpValue.dp.toSp() }
    val label: TextUnit get() = sp(max(u * 6.2f, 10f))
    val caption: TextUnit get() = sp(max(u * 7.2f, 11f))
    val body: TextUnit get() = sp(max(u * 6.6f, 11f))
}

/** A built-in widget drawn in [design] (never [WidgetDesign.STANDARD]; that is the widget's own renderer). */
@Composable
internal fun DesignedFace(face: WidgetFace, design: WidgetDesign, modifier: Modifier = Modifier) {
    if (design.isSignature) {
        SignatureFace(face, design, modifier)
        return
    }
    val look = faceLook(design.look)
    FaceSurface(look, modifier.then(face.onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier)) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val m = FaceMetrics(maxWidth.value, maxHeight.value, LocalDensity.current)
            when (design.layout ?: FaceLayout.HERO) {
                FaceLayout.HERO -> HeroLayout(face, look, m)
                FaceLayout.ARC -> ArcLayout(face, look, m)
                FaceLayout.RING -> RingLayout(face, look, m)
                FaceLayout.BARS -> BarsLayout(face, look, m)
                FaceLayout.STATS -> StatsLayout(face, look, m)
                FaceLayout.TERMINAL -> TerminalLayout(face, look, m)
                FaceLayout.DIAL -> DialLayout(face, look, m)
                FaceLayout.FLAP -> FlapLayout(face, look, m)
                FaceLayout.ORB -> OrbLayout(face, look, m)
                FaceLayout.LIQUID -> LiquidLayout(face, look, m)
                FaceLayout.DOTS -> DotsLayout(face, look, m)
                FaceLayout.POSTER -> PosterLayout(face, look, m)
                FaceLayout.DUO -> DuoLayout(face, look, m)
                FaceLayout.ISLAND -> IslandLayout(face, look, m)
                FaceLayout.COCKPIT -> CockpitLayout(face, look, m)
                FaceLayout.TRIANGLES -> TriLedLayout(face, look, m)
                FaceLayout.BLADE -> BladeLayout(face, look, m)
                FaceLayout.LIGHT_BAR -> LightBarLayout(face, look, m)
                FaceLayout.LANE -> LaneLayout(face, look, m)
                FaceLayout.TREND -> TrendLayout(face, look, m)
                FaceLayout.PULSE -> PulseLayout(face, look, m)
                FaceLayout.CONTOUR -> ContourLayout(face, look, m)
            }
        }
    }
}

// --- Material surface ------------------------------------------------------------

@Composable
internal fun FaceSurface(look: FaceLook, modifier: Modifier, content: @Composable () -> Unit) {
    val bg = look.background
    if (bg == null) {
        Card(modifier = modifier) { content() }
        return
    }
    val shape = surfaceShape(look)
    val border = look.border
    val decoration = look.decoration
    Box(
        modifier = modifier
            .then(
                // A soft halo outside the rim; the grid leaves a few dp around each tile.
                if (decoration == LookDecoration.NEON) Modifier.drawBehind {
                    drawRoundRect(
                        color = look.accent.copy(alpha = 0.28f),
                        cornerRadius = CornerRadius(look.radius.toPx()),
                        style = Stroke(width = 6.dp.toPx())
                    )
                } else Modifier
            )
            .clip(shape)
            .background(bg)
            .then(
                when {
                    border == null -> Modifier
                    decoration == LookDecoration.CHROME -> Modifier.border(
                        look.borderWidth,
                        Brush.linearGradient(listOf(Color(0xFFEEF0F3), Color(0xFF7C838C), Color(0xFFD9DDE2), Color(0xFF5D636B))),
                        shape
                    )
                    else -> Modifier.border(look.borderWidth, border, shape)
                }
            )
    ) {
        // The material's pattern (weave, grid, glows, scanlines) sits in layers of
        // its own: a reading that ticks redraws the numbers, not the hundred lines
        // behind or over them.
        if (decoration in BACKDROP_DECORATIONS) {
            Spacer(Modifier.matchParentSize().graphicsLayer().drawBehind { drawDecoration(look) })
        }
        content()
        if (decoration == LookDecoration.SCANLINES) {
            Spacer(
                Modifier.matchParentSize().graphicsLayer().drawBehind {
                    val step = 3.dp.toPx()
                    var y = 0f
                    while (y < size.height) {
                        drawLine(Color.Black.copy(alpha = 0.22f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                        y += step
                    }
                }
            )
        }
    }
}

/** Decorations drawn behind the content (scanlines go over it). */
private val BACKDROP_DECORATIONS = setOf(LookDecoration.CARBON, LookDecoration.DOTS, LookDecoration.GLASS, LookDecoration.NEON, LookDecoration.TRIANGLES,
    LookDecoration.PINSTRIPE, LookDecoration.LEAF, LookDecoration.EDGE_LIGHT)

/** A material's card outline: rounded, or for the angular ones two opposite corners cut on the diagonal. */
internal fun surfaceShape(look: FaceLook, r: Dp = look.radius): Shape =
    if (look.angular) CutCornerShape(topStart = r, bottomEnd = r) else RoundedCornerShape(r)

private fun DrawScope.drawDecoration(look: FaceLook) {
    val w = size.width
    val h = size.height
    when (look.decoration) {
        LookDecoration.CARBON -> {
            // Twill weave, then the red racing stripe in the top-right corner.
            val step = 6.dp.toPx()
            var x = -h
            while (x < w) {
                drawLine(Color.White.copy(alpha = 0.035f), Offset(x, 0f), Offset(x + h, h), strokeWidth = step / 2f)
                x += step
            }
            val s = min(w, h) * 0.30f
            drawLine(look.accent, Offset(w - s, 0f), Offset(w, s), strokeWidth = s * 0.11f)
            drawLine(look.accent, Offset(w - s * 0.62f, 0f), Offset(w, s * 0.62f), strokeWidth = s * 0.05f)
        }
        LookDecoration.DOTS -> {
            // A faint pegboard of dots, like a phone's glyph matrix switched off.
            val step = 9.dp.toPx()
            val r = 0.9.dp.toPx()
            var y = step / 2f
            while (y < h) {
                var x = step / 2f
                while (x < w) { drawCircle(Color.White.copy(alpha = 0.05f), r, Offset(x, y)); x += step }
                y += step
            }
        }
        LookDecoration.GLASS -> {
            val r = max(w, h) * 0.6f
            drawCircle(Brush.radialGradient(listOf(Color(0x555AD0FF), Color.Transparent), Offset(w * 0.15f, h * 0.2f), r), r, Offset(w * 0.15f, h * 0.2f))
            drawCircle(Brush.radialGradient(listOf(Color(0x669B7BFF), Color.Transparent), Offset(w * 0.9f, h * 0.9f), r), r, Offset(w * 0.9f, h * 0.9f))
            drawLine(
                Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.45f), Color.Transparent)),
                Offset(w * 0.15f, 1f), Offset(w * 0.85f, 1f), 1.5f
            )
        }
        LookDecoration.TRIANGLES -> {
            // Copper edges along the two cut corners, then a scatter of small
            // triangles fading out of the bottom-right one, like a grille pattern.
            val cut = look.radius.toPx()
            val edge = 2.dp.toPx()
            drawLine(look.accent, Offset(0f, cut), Offset(cut, 0f), edge)
            drawLine(look.accent.copy(alpha = 0.5f), Offset(w - cut, h), Offset(w, h - cut), edge)
            val b = min(w, h) * 0.075f
            val th = b * 0.866f
            val cols = 9
            val rows = 5
            val tri = Path()
            for (row in 0 until rows) for (col in 0 until cols) {
                val fade = 1f - (row + col * 0.6f) / (rows + cols * 0.6f - 1f)
                if (fade <= 0.15f) continue
                val x = w - cut - (col + 2) * b / 2f
                val y = h - cut * 0.5f - (row + 1) * th
                val up = (row + col) % 2 == 0
                tri.rewind()
                if (up) {
                    tri.moveTo(x + b * 0.1f, y + th * 0.9f); tri.lineTo(x + b * 0.9f, y + th * 0.9f); tri.lineTo(x + b / 2f, y + th * 0.1f)
                } else {
                    tri.moveTo(x + b * 0.1f, y + th * 0.1f); tri.lineTo(x + b * 0.9f, y + th * 0.1f); tri.lineTo(x + b / 2f, y + th * 0.9f)
                }
                tri.close()
                drawPath(tri, look.accent.copy(alpha = 0.12f * fade * fade))
            }
        }
        LookDecoration.NEON -> drawRoundRect(
            Brush.radialGradient(listOf(look.accent.copy(alpha = 0.10f), Color.Transparent), Offset(w * 0.8f, 0f), max(w, h)),
            cornerRadius = CornerRadius(look.radius.toPx())
        )
        else -> drawCabinDecoration(look)
    }
}

// --- Shared pieces ----------------------------------------------------------------

@Composable
internal fun FaceText(
    text: String,
    look: FaceLook,
    size: TextUnit,
    modifier: Modifier = Modifier,
    color: Color = look.ink,
    weight: FontWeight = FontWeight.Normal,
    family: FontFamily = look.font,
    italic: Boolean = false,
    glow: Boolean = false,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    maxLines: Int = 1,
    align: TextAlign? = null,
    /** Shrinks the text (down to [MIN_FIT_SCALE]) before cutting it: for labels a word too long would lose. */
    fit: Boolean = false
) {
    val halo = look.glow
    var scale by remember(text, size) { mutableFloatStateOf(1f) }
    val shrinking = fit && scale > MIN_FIT_SCALE
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = size * scale,
        lineHeight = size * scale * 1.12f,
        fontWeight = weight,
        fontFamily = family,
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        letterSpacing = letterSpacing,
        maxLines = maxLines,
        overflow = if (shrinking) TextOverflow.Clip else TextOverflow.Ellipsis,
        textAlign = align,
        style = if (glow && halo != null) TextStyle(shadow = Shadow(halo, blurRadius = size.value * 0.6f)) else TextStyle.Default,
        onTextLayout = { if (shrinking && it.hasVisualOverflow) scale = (scale - 0.08f).coerceAtLeast(MIN_FIT_SCALE) }
    )
}

private const val MIN_FIT_SCALE = 0.72f

@Composable
internal fun FaceHeader(f: WidgetFace, look: FaceLook, m: FaceMetrics, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(f.icon, contentDescription = null, tint = look.accent, modifier = Modifier.size(m.dp(8.5f).coerceIn(14.dp, 22.dp)))
        Spacer(Modifier.width(6.dp))
        FaceText(
            f.title.uppercase(Locale.getDefault()), look, m.label, Modifier.weight(1f),
            color = look.dim, weight = look.labelWeight, letterSpacing = 0.12.em, fit = true
        )
        trailing?.invoke(this)
    }
}

/** The headline value with its unit, beside it or (when [stacked]) under it. LCD designs add unlit "8" segments behind. */
@Composable
internal fun FaceValue(f: WidgetFace, look: FaceLook, m: FaceMetrics, sizeDp: Float, modifier: Modifier = Modifier, stacked: Boolean = false) {
    val size = m.sp(if (f.textValue) sizeDp * 0.45f else sizeDp)
    val numeral: @Composable (Modifier) -> Unit = { mod ->
        Box(modifier = mod) {
            val ghost = look.ghost
            if (ghost != null && !f.textValue) {
                FaceText(f.value.map { if (it.isDigit()) '8' else it }.joinToString(""), look, size,
                    color = ghost, weight = look.numWeight, family = look.numFont)
            }
            FaceText(
                f.value, look, size, color = if (f.alert && !f.textValue) look.warn else look.ink,
                weight = if (f.textValue) FontWeight.Bold else look.numWeight,
                family = if (f.textValue) look.font else look.numFont, italic = look.numItalic, glow = !f.textValue,
                letterSpacing = if (f.textValue) TextUnit.Unspecified else (-0.02).em,
                align = if (stacked) TextAlign.Center else null
            )
        }
    }
    val unit: @Composable (Modifier) -> Unit = { mod ->
        if (f.unit.isNotEmpty()) FaceText(f.unit, look, size * (if (f.textValue) 0.6f else 0.3f), mod, color = look.dim, weight = FontWeight.Medium)
    }
    if (stacked) {
        Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            numeral(Modifier)
            unit(Modifier)
        }
    } else {
        Row(modifier = modifier) {
            numeral(Modifier.weight(1f, fill = false).alignByBaseline())
            if (f.unit.isNotEmpty()) {
                Spacer(Modifier.width(m.dp(1.8f)))
                unit(Modifier.alignByBaseline())
            }
        }
    }
}

@Composable
internal fun FaceCaption(f: WidgetFace, look: FaceLook, m: FaceMetrics, modifier: Modifier = Modifier, align: TextAlign? = null) {
    if (f.caption.isEmpty()) return
    FaceText(f.caption, look, m.caption, modifier, color = if (f.alert) look.warn else look.dim, align = align)
}

internal fun controlShape(look: FaceLook): Shape = when {
    look.angular -> CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp)
    look.squareControls -> RoundedCornerShape(6.dp)
    else -> CircleShape
}

@Composable
internal fun FaceActions(f: WidgetFace, look: FaceLook, m: FaceMetrics, max: Int = 3, small: Boolean = false) {
    if (f.actions.isEmpty()) return
    // Never under the driving minimum, whatever the face's scale: these are
    // tapped at speed (play, next, connect).
    val s = if (small) DashSize.Touch else m.dp(15f).coerceIn(DashSize.Touch, DashSize.TouchPrimary)
    val shape = controlShape(look)
    Row(horizontalArrangement = Arrangement.spacedBy(m.dp(2.6f).coerceAtLeast(4.dp)), verticalAlignment = Alignment.CenterVertically) {
        f.actions.take(max).forEach { a ->
            val fill = if (a.primary) look.accent else look.fill
            Box(
                modifier = Modifier
                    .size(s)
                    .clip(shape)
                    .background(fill)
                    .then(if (!a.primary && look.fill.alpha < 0.05f) Modifier.border(1.dp, look.dim.copy(alpha = 0.6f), shape) else Modifier)
                    .clickable(enabled = a.enabled, role = Role.Button, onClick = a.onClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    a.icon, contentDescription = a.label,
                    tint = (if (a.primary) look.onAccent else look.ink).copy(alpha = if (a.enabled) 1f else 0.4f),
                    modifier = Modifier.size(s * 0.55f)
                )
            }
        }
    }
}

@Composable
internal fun FaceStatBlock(stat: FaceStat, look: FaceLook, m: FaceMetrics, modifier: Modifier = Modifier, align: Alignment.Horizontal = Alignment.Start) {
    Column(modifier = modifier, horizontalAlignment = align) {
        FaceText(stat.label.uppercase(Locale.getDefault()), look, m.sp(max(m.u * 5.2f, 9f)), color = look.dim, weight = look.labelWeight, letterSpacing = 0.1.em, fit = true)
        FaceText(stat.value, look, m.sp(max(m.u * 8.2f, 12f)), weight = look.numWeight.coerceAtLeast(FontWeight.Medium), family = look.numFont, italic = look.numItalic)
    }
}

@Composable
internal fun FaceRows(f: WidgetFace, look: FaceLook, m: FaceMetrics, max: Int, modifier: Modifier = Modifier) {
    if (f.rows.isEmpty()) return
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(m.dp(1.8f).coerceAtLeast(3.dp))) {
        f.rows.take(max).forEachIndexed { i, r ->
            // A notification goes with a swipe; its key keeps the next row from taking over the swiped one's state.
            key(r.key ?: i) {
                FaceRowSwipe(r) {
                    val shape = RoundedCornerShape(look.radius * 0.45f)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(look.fill)
                            .then(r.onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier)
                            .padding(horizontal = m.dp(2.6f).coerceAtLeast(6.dp), vertical = m.dp(1.8f).coerceAtLeast(4.dp)),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val badge = r.badge
                        if (badge != null) {
                            Box(
                                modifier = Modifier.size(m.dp(9f).coerceAtLeast(22.dp)).clip(CircleShape)
                                    .background(Brush.linearGradient(listOf(look.accent, look.accent2))),
                                contentAlignment = Alignment.Center
                            ) { FaceText(badge, look, m.sp(max(m.u * 3.6f, 9f)), color = look.onAccent, weight = FontWeight.Bold) }
                        } else {
                            Box(Modifier.width(3.dp).height(m.dp(5f).coerceAtLeast(12.dp)).clip(CircleShape).background(if (r.alert) look.warn else look.accent))
                        }
                        Spacer(Modifier.width(m.dp(2.6f).coerceAtLeast(6.dp)))
                        FaceText(r.title, look, m.body, Modifier.weight(1f), weight = FontWeight.Medium)
                        Spacer(Modifier.width(6.dp))
                        FaceText(r.detail, look, m.body, color = if (r.alert) look.warn else look.dim)
                    }
                }
            }
        }
    }
}

/** [content] for [row], swipeable away when the row has an [FaceRow.onDismiss]. */
@Composable
private fun FaceRowSwipe(row: FaceRow, content: @Composable () -> Unit) {
    val dismiss = row.onDismiss
    if (dismiss == null) content() else SwipeAway(onDismiss = dismiss) { content() }
}

@Composable
internal fun FaceProgress(fraction: Float, look: FaceLook, m: FaceMetrics, modifier: Modifier = Modifier, alert: Boolean = false) {
    Box(
        modifier = modifier
            .height(m.dp(1.6f).coerceAtLeast(3.dp))
            .clip(CircleShape)
            .background(look.track)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction01(fraction))
                .clip(CircleShape)
                .background(if (alert) Brush.horizontalGradient(listOf(look.warn, look.warn)) else Brush.horizontalGradient(listOf(look.accent, look.accent2)))
        )
    }
}

/**
 * [v] as a 0..1 share of a bar or sweep. A missing, NaN or infinite reading
 * (0 / 0 when a range is still 0) draws as empty instead of crashing layout
 * or roundToInt.
 */
internal fun fraction01(v: Float?): Float = if (v == null || !v.isFinite()) 0f else v.coerceIn(0f, 1f)

/** Point on a circle, angle in degrees clockwise from 12 o'clock. */
internal fun polarPoint(c: Offset, r: Float, deg: Float): Offset {
    val t = Math.toRadians(deg.toDouble())
    return Offset(c.x + r * sin(t).toFloat(), c.y - r * cos(t).toFloat())
}

// --- Hero (also LCD) ------------------------------------------------------

@Composable
private fun HeroLayout(f: WidgetFace, look: FaceLook, m: FaceMetrics) {
    Column(
        modifier = Modifier.fillMaxSize().padding(m.pad.dp),
        verticalArrangement = Arrangement.spacedBy(m.dp(2.4f))
    ) {
        FaceHeader(f, look, m)
        Column(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center) {
            FaceValue(f, look, m, min(m.h * 0.40f, m.w * 0.28f))
            FaceCaption(f, look, m)
            if (m.h >= 190f && f.rows.isNotEmpty()) {
                Spacer(Modifier.height(m.dp(2.5f)))
                FaceRows(f, look, m, 2)
            }
        }
        val fraction = f.fraction
        if (fraction != null || f.actions.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.dp(4f))) {
                if (fraction != null) FaceProgress(fraction, look, m, Modifier.weight(1f), f.alert) else Spacer(Modifier.weight(1f))
                FaceActions(f, look, m)
            }
        }
    }
}

// --- Gauge (240° arc, or a full ring for compass / seconds) -------------------------

@Composable
private fun ArcLayout(f: WidgetFace, look: FaceLook, m: FaceMetrics) {
    val wide = m.wide
    val gauge: @Composable (Modifier) -> Unit = { mod ->
        BoxWithConstraints(modifier = mod.aspectRatio(1f, matchHeightConstraintsFirst = true)) {
            val g = maxWidth.value
            val fraction = rememberUpdatedState(f.fraction)
            val full = f.fullCircle
            val alert = f.alert
            Spacer(Modifier.fillMaxSize().drawWithCache { gaugeArc(look, full, alert, fraction) })
            Column(
                modifier = Modifier.align(Alignment.Center).padding(horizontal = (g * 0.2f).dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                FaceValue(f, look, m, g * 0.2f, stacked = true)
                if (!wide) FaceText(f.title.uppercase(Locale.getDefault()), look, m.sp(max(g * 0.05f, 9f)), color = look.dim, weight = look.labelWeight, letterSpacing = 0.1.em)
            }
        }
    }
    // A square tile has no room beside the gauge: what the reading means
    // (a battery "not charging") and the tile's buttons go under it instead
    // of being dropped.
    if (!wide && m.h >= 180f && (f.actions.isNotEmpty() || f.caption.isNotEmpty())) {
        Column(
            modifier = Modifier.fillMaxSize().padding(m.pad.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(m.dp(2.4f))
        ) {
            gauge(Modifier.weight(1f))
            FaceCaption(f, look, m)
            FaceActions(f, look, m, small = true)
        }
        return
    }
    Row(
        modifier = Modifier.fillMaxSize().padding(m.pad.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (wide) Arrangement.spacedBy(m.dp(4f)) else Arrangement.Center
    ) {
        gauge(Modifier.fillMaxHeight())
        if (wide) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(m.dp(3f), Alignment.CenterVertically)) {
                FaceHeader(f, look, m)
                FaceCaption(f, look, m)
                f.stats.take(if (f.actions.isEmpty()) 3 else 2).forEach { FaceStatBlock(it, look, m) }
                FaceActions(f, look, m, small = true)
            }
        }
    }
}

/**
 * The gauge's arc. Geometry, brush and Neon's tick ring are built once per
 * size; only [fraction] is read at draw time, so a new reading redraws the
 * arc without rebuilding any of it.
 */
private fun CacheDrawScope.gaugeArc(look: FaceLook, full: Boolean, alert: Boolean, fraction: State<Float?>): DrawResult {
    val stroke = size.minDimension * 0.07f
    val inset = stroke / 2f + size.minDimension * 0.04f
    val topLeft = Offset(inset, inset)
    val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
    val start = if (full) -90f else 150f
    val span = if (full) 360f else 240f
    val line = Stroke(stroke, cap = StrokeCap.Round)
    val halo = Stroke(stroke * 2.2f, cap = StrokeCap.Round)
    val brush = if (alert) Brush.linearGradient(listOf(look.warn, look.warn))
    else Brush.linearGradient(listOf(look.accent, look.accent2), Offset(0f, size.height), Offset(size.width, 0f))
    val neon = look.decoration == LookDecoration.NEON
    val tickColor = look.accent2.copy(alpha = 0.8f)
    val ticks = if (!neon) emptyList() else {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension / 2f
        val a0 = if (full) 0f else -120f
        (0..(if (full) 23 else 24)).map { i ->
            val a = a0 + span * i / 24f
            Triple(polarPoint(c, r * 0.99f, a), polarPoint(c, r * (if (i % 6 == 0) 0.9f else 0.95f), a), if (i % 6 == 0) 2f else 1f)
        }
    }
    return onDrawBehind {
        drawArc(look.track, start, span, false, topLeft, arcSize, style = line)
        val fr = fraction01(fraction.value)
        if (fr > 0f) {
            if (neon) drawArc(look.accent.copy(alpha = 0.35f), start, span * fr, false, topLeft, arcSize, style = halo)
            drawArc(brush, start, span * fr, false, topLeft, arcSize, style = line)
        }
        ticks.forEach { (a, b, w) -> drawLine(tickColor, a, b, strokeWidth = w) }
    }
}

// --- Ring ------------------------------------------------------------------------------

@Composable
private fun RingLayout(f: WidgetFace, look: FaceLook, m: FaceMetrics) {
    Row(
        modifier = Modifier.fillMaxSize().padding(m.pad.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.dp(6f))
    ) {
        val ring = min(m.h - m.pad * 2f, m.w * 0.46f).coerceAtLeast(24f)
        Box(modifier = Modifier.size(ring.dp), contentAlignment = Alignment.Center) {
            val fraction = rememberUpdatedState(f.fraction)
            val alert = f.alert
            Spacer(
                Modifier.fillMaxSize().drawWithCache {
                    val stroke = size.minDimension * 0.08f
                    val inset = Offset(stroke / 2f, stroke / 2f)
                    val arcSize = Size(size.width - stroke, size.height - stroke)
                    val track = Stroke(stroke)
                    val line = Stroke(stroke, cap = StrokeCap.Round)
                    val brush = if (alert) Brush.linearGradient(listOf(look.warn, look.warn)) else Brush.linearGradient(listOf(look.accent, look.accent2))
                    onDrawBehind {
                        drawArc(look.track, 0f, 360f, false, inset, arcSize, style = track)
                        val fr = fraction01(fraction.value)
                        if (fr > 0f) drawArc(brush, -90f, 360f * fr, false, inset, arcSize, style = line)
                    }
                }
            )
            Box(
                modifier = Modifier.fillMaxSize().padding((ring * 0.17f).dp).clip(CircleShape),
                contentAlignment = Alignment.Center
            ) {
                val art = f.art
                val clock = f.clock
                when {
                    art != null -> Image(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    clock != null -> FaceText(clock.third.toString().padStart(2, '0'), look, m.sp(ring * 0.22f), weight = look.numWeight, family = look.numFont)
                    else -> Icon(f.icon, contentDescription = null, tint = look.accent, modifier = Modifier.size((ring * 0.32f).dp))
                }
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(m.dp(2.2f), Alignment.CenterVertically)) {
            FaceHeader(f, look, m)
            FaceValue(f, look, m, min(m.h * 0.24f, m.w * 0.14f))
            FaceCaption(f, look, m)
            if (f.stats.isNotEmpty() && m.h >= 120f) {
                Row(horizontalArrangement = Arrangement.spacedBy(m.dp(5f))) {
                    f.stats.take(2).forEach { FaceStatBlock(it, look, m, Modifier.weight(1f, fill = false)) }
                }
            }
            FaceActions(f, look, m, small = true)
        }
    }
}

// --- Level bars ------------------------------------------------------------------------

@Composable
private fun BarsLayout(f: WidgetFace, look: FaceLook, m: FaceMetrics) {
    Column(modifier = Modifier.fillMaxSize().padding(m.pad.dp), verticalArrangement = Arrangement.spacedBy(m.dp(2.4f))) {
        FaceHeader(f, look, m) { FaceActions(f, look, m, small = true) }
        Column(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Bottom) {
            FaceValue(f, look, m, min(m.h * 0.34f, m.w * 0.22f))
            FaceCaption(f, look, m)
        }
        if (f.fraction != null) {
            val carbon = look.kind == FaceLookKind.CARBON
            val hotFrom = if (carbon) 0.8f else 2f
            val fraction = rememberUpdatedState(f.fraction)
            val alert = rememberUpdatedState(f.alert)
            Spacer(
                Modifier.fillMaxWidth().height(m.dp(7f).coerceIn(8.dp, 18.dp)).drawWithCache {
                    val n = 24
                    val gap = size.width * 0.007f
                    val segW = (size.width - gap * (n - 1)) / n
                    val skew = if (carbon) size.height * 0.35f else 0f
                    val corner = CornerRadius(min(segW, size.height) * 0.25f)
                    // Carbon's slanted segments are built once per size, not on every reading.
                    val slanted = if (skew == 0f) emptyList() else List(n) { i ->
                        val x = i * (segW + gap)
                        Path().apply {
                            moveTo(x + skew, 0f); lineTo(x + segW + skew, 0f)
                            lineTo(x + segW, size.height); lineTo(x, size.height); close()
                        }
                    }
                    onDrawBehind {
                        val lit = (fraction01(fraction.value) * n).roundToInt()
                        for (i in 0 until n) {
                            val color = when {
                                i >= lit -> look.track
                                alert.value || i >= n * hotFrom -> look.warn
                                else -> look.accent
                            }
                            if (skew == 0f) {
                                drawRoundRect(color, Offset(i * (segW + gap), 0f), Size(segW, size.height), corner)
                            } else {
                                drawPath(slanted[i], color)
                            }
                        }
                    }
                }
            )
        }
        if (f.stats.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(m.dp(2.5f))) {
                f.stats.take(3).forEach { s ->
                    FaceStatBlock(
                        s, look, m,
                        Modifier.weight(1f, fill = false)
                            .clip(controlShape(look))
                            .background(look.fill)
                            .padding(horizontal = m.dp(3f).coerceAtLeast(8.dp), vertical = m.dp(1.6f).coerceAtLeast(3.dp))
                    )
                }
            }
        }
    }
}

// --- Stats grid (also E-ink) ---------------------------------------------------------------

@Composable
private fun StatsLayout(f: WidgetFace, look: FaceLook, m: FaceMetrics) {
    val paper = look.kind == FaceLookKind.PAPER
    val gap = if (paper) m.dp(1.2f) else m.dp(2.2f).coerceAtLeast(4.dp)
    val cells: List<FaceStat> = (f.stats.take(4) + f.rows.map { FaceStat(it.detail, it.title) }).take(4)
    val cellShape = RoundedCornerShape(look.radius * 0.45f)
    val cellMod: Modifier = if (paper) {
        Modifier.drawBehind { drawLine(look.ink.copy(alpha = 0.25f), Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
    } else Modifier.clip(cellShape).background(look.fill)

    @Composable
    fun Cell(stat: FaceStat?, modifier: Modifier) {
        if (stat == null) { Spacer(modifier); return }
        Column(
            modifier = modifier.fillMaxSize().then(cellMod).padding(m.dp(3f).coerceAtLeast(6.dp)),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            FaceText(stat.label.uppercase(Locale.getDefault()), look, m.sp(max(m.u * 5.2f, 9f)), color = look.dim, weight = look.labelWeight, letterSpacing = 0.1.em, fit = true)
            FaceText(stat.value, look, m.sp(max(m.u * 8.5f, 12f)), weight = look.numWeight, family = look.numFont, italic = look.numItalic)
        }
    }

    @Composable
    fun Primary(modifier: Modifier) {
        Column(
            modifier = modifier.fillMaxSize()
                .then(
                    if (paper) Modifier.drawBehind { drawLine(look.ink, Offset(0f, 0f), Offset(size.width, 0f), 2.dp.toPx()) }
                    else Modifier.clip(cellShape).background(look.fill)
                )
                .padding(m.dp(3.4f).coerceAtLeast(8.dp)),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            FaceHeader(f, look, m)
            Column {
                FaceValue(f, look, m, min(m.h * 0.26f, m.w * 0.16f))
                FaceCaption(f, look, m)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(m.pad.dp), verticalArrangement = Arrangement.spacedBy(gap)) {
        if (m.w < m.h || cells.isEmpty()) {
            Primary(Modifier.weight(1.4f))
            if (cells.isNotEmpty()) Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(gap)) {
                Cell(cells.getOrNull(0), Modifier.weight(1f)); Cell(cells.getOrNull(1), Modifier.weight(1f))
            }
            if (cells.size > 2) Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(gap)) {
                Cell(cells.getOrNull(2), Modifier.weight(1f)); Cell(cells.getOrNull(3), Modifier.weight(1f))
            }
        } else {
            Row(Modifier.weight(2f), horizontalArrangement = Arrangement.spacedBy(gap)) {
                Primary(Modifier.weight(2f))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(gap)) {
                    Cell(cells.getOrNull(0), Modifier.weight(1f)); Cell(cells.getOrNull(1), Modifier.weight(1f))
                }
            }
            if (cells.size > 2) Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(gap)) {
                Cell(cells.getOrNull(2), Modifier.weight(1f)); Cell(cells.getOrNull(3), Modifier.weight(1f))
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomEnd) { FaceActions(f, look, m, 2, small = true) }
            } else if (f.actions.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) { FaceActions(f, look, m, small = true) }
            }
        }
    }
}

// --- Amber terminal --------------------------------------------------------------------------

@Composable
private fun TerminalLayout(f: WidgetFace, look: FaceLook, m: FaceMetrics) {
    // Read only when drawing, so the blink repaints the cursor without recomposing the layout.
    val cursorOn = rememberBlink(530L)
    val fs = m.body
    Column(modifier = Modifier.fillMaxSize().padding(m.pad.dp)) {
        Column(modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds(), verticalArrangement = Arrangement.spacedBy(m.dp(1.2f))) {
            FaceText("> " + f.title.uppercase(Locale.getDefault()), look, fs, color = look.dim, glow = true)
            FaceValue(f, look, m, min(m.h * 0.22f, m.w * 0.15f))
            if (f.caption.isNotEmpty()) FaceText(f.caption, look, fs, color = if (f.alert) look.warn else look.dim, glow = true)
            f.stats.take(3).forEach { s ->
                Row {
                    FaceText(s.label.uppercase(Locale.getDefault()), look, fs, glow = true)
                    FaceText(" " + ".".repeat(60), look, fs, Modifier.weight(1f), color = look.dim)
                    FaceText(" " + s.value, look, fs, glow = true)
                }
            }
            f.rows.take(3).forEach { r ->
                FaceText("- ${r.title}  ${r.detail}", look, fs, color = if (r.alert) look.warn else look.ink, glow = true,
                    modifier = r.onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.dp(3f))) {
            f.actions.take(3).forEach { a ->
                FaceText(
                    "[${a.label.uppercase(Locale.getDefault())}]", look, fs,
                    Modifier.clip(RoundedCornerShape(3.dp)).clickable(enabled = a.enabled, role = Role.Button, onClick = a.onClick).padding(vertical = 8.dp),
                    color = if (a.enabled) look.ink else look.dim, glow = true
                )
            }
            Box(Modifier.width((fs.value * 0.6f).dp).height((fs.value * 1.1f).dp).drawBehind { if (cursorOn.value) drawRect(look.ink) })
        }
    }
}

// --- Analogue dial (Chronograph) -------------------------------------------------

@Composable
private fun DialLayout(f: WidgetFace, look: FaceLook, m: FaceMetrics) {
    val wide = m.wide
    val measurer = rememberTextMeasurer()
    val showValueInDial = !wide && f.clock == null && !f.compass
    val dialValue = if (f.textValue) "" else f.value
    val dialUnit = (f.unit.ifEmpty { f.title }).uppercase(Locale.getDefault())
    val letters = if (f.compass) {
        val n = stringResource(R.string.info_dir_n)
        val e = stringResource(R.string.info_dir_e)
        val s = stringResource(R.string.info_dir_s)
        val w = stringResource(R.string.info_dir_w)
        remember(n, e, s, w) { listOf(n, e, s, w) }
    } else emptyList()
    val full = f.fullCircle
    val a0 = if (full) 0f else -135f
    val span = if (full) 360f else 270f
    val chrome = look.decoration == LookDecoration.CHROME
    Row(
        modifier = Modifier.fillMaxSize().padding(m.pad.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (wide) Arrangement.spacedBy(m.dp(5f)) else Arrangement.Center
    ) {
        Box(modifier = Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true)) {
            // The dial itself (rim, ticks, red zone, letters) in its own layer, built
            // once per size: a new reading only redraws the hands above it.
            Spacer(
                Modifier.fillMaxSize().graphicsLayer().drawWithCache {
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val r = size.minDimension / 2f
                    val tickOuter = r * (if (chrome) 0.88f else 0.9f)
                    val labelStyle = TextStyle(fontFamily = look.numFont, fontWeight = FontWeight.Bold, fontSize = (r * 0.16f / density / fontScale).sp)
                    val labels = letters.mapIndexed { i, l -> measurer.measure(l, labelStyle.copy(color = if (i == 0) look.accent else look.ink)) }
                    val rim = if (chrome) Brush.linearGradient(listOf(Color(0xFFEEF0F3), Color(0xFF7C838C), Color(0xFFD9DDE2), Color(0xFF5D636B))) else null
                    val rimStroke = Stroke(1.2f)
                    onDrawBehind {
                        if (rim != null) {
                            drawCircle(rim, r, c)
                            drawCircle(Color(0xFF0D0F12), r * 0.93f, c)
                        } else {
                            drawCircle(look.ink, r * 0.97f, c, style = rimStroke)
                        }
                        val n = if (full) 60 else 40
                        for (i in 0..n) {
                            if (full && i == n) break
                            val a = a0 + span * i / n
                            val major = i % 5 == 0
                            drawLine(look.ink, polarPoint(c, tickOuter, a), polarPoint(c, tickOuter - r * (if (major) 0.14f else 0.07f), a), strokeWidth = if (major) 3f else 1.2f)
                        }
                        if (!full) {
                            val inset = c.x - (tickOuter - r * 0.03f)
                            drawArc(
                                look.warn, a0 + span * 0.8f - 90f, span * 0.2f, false,
                                Offset(inset, c.y - (tickOuter - r * 0.03f)), Size((tickOuter - r * 0.03f) * 2, (tickOuter - r * 0.03f) * 2),
                                style = Stroke(r * 0.05f)
                            )
                        }
                        labels.forEachIndexed { i, layout ->
                            val p = polarPoint(c, r * 0.56f, i * 90f)
                            drawText(layout, topLeft = Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f))
                        }
                    }
                }
            )
            Canvas(modifier = Modifier.fillMaxSize()) {
                val c = center
                val r = size.minDimension / 2f
                val clock = f.clock
                if (clock != null) {
                    val (h, mi, s) = clock
                    drawLine(look.ink, c, polarPoint(c, r * 0.45f, (h % 12 + mi / 60f) * 30f), strokeWidth = r * 0.06f, cap = StrokeCap.Round)
                    drawLine(look.ink, c, polarPoint(c, r * 0.68f, mi * 6f), strokeWidth = r * 0.04f, cap = StrokeCap.Round)
                    drawLine(look.accent, polarPoint(c, r * 0.15f, s * 6f + 180f), polarPoint(c, r * 0.76f, s * 6f), strokeWidth = r * 0.018f, cap = StrokeCap.Round)
                } else {
                    if (showValueInDial && dialValue.isNotEmpty()) {
                        val v = measurer.measure(dialValue, TextStyle(color = look.ink, fontFamily = look.numFont, fontWeight = FontWeight.Bold, fontSize = (r * 0.22f / density / fontScale).sp))
                        drawText(v, topLeft = Offset(c.x - v.size.width / 2f, c.y + r * 0.34f - v.size.height / 2f))
                        val u = measurer.measure(dialUnit, TextStyle(color = look.dim, fontFamily = look.font, fontSize = (r * 0.09f / density / fontScale).sp, letterSpacing = 0.1.em))
                        drawText(u, topLeft = Offset(c.x - u.size.width / 2f, c.y + r * 0.52f - u.size.height / 2f))
                    }
                    val a = a0 + span * fraction01(f.fraction)
                    drawLine(look.accent, polarPoint(c, r * 0.16f, a + 180f), polarPoint(c, r * 0.78f, a), strokeWidth = r * 0.04f, cap = StrokeCap.Round)
                }
                drawCircle(if (chrome) Color(0xFFC9CED5) else look.ink, r * 0.065f, c)
            }
        }
        if (wide) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(m.dp(2.6f), Alignment.CenterVertically)) {
                FaceHeader(f, look, m)
                FaceValue(f, look, m, min(m.h * 0.18f, m.w * 0.11f))
                FaceCaption(f, look, m)
                f.stats.take(2).forEach { FaceStatBlock(it, look, m) }
                FaceActions(f, look, m, small = true)
            }
        }
    }
}

// --- Split-flap board ----------------------------------------------------------------------------

@Composable
private fun FlapLayout(f: WidgetFace, look: FaceLook, m: FaceMetrics) {
    Column(modifier = Modifier.fillMaxSize().padding(m.pad.dp), verticalArrangement = Arrangement.spacedBy(m.dp(2.4f))) {
        FaceHeader(f, look, m) { FaceActions(f, look, m, small = true) }
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
            val chars = f.value.uppercase(Locale.getDefault()).take(if (f.textValue) 10 else 6)
            val gap = max(m.u * 1.4f, 2f)
            val unitText = f.unit.uppercase(Locale.getDefault())
            var cellH = maxHeight.value * (if (f.textValue) 0.62f else 0.92f)
            var cellW = cellH * (if (f.textValue) 0.62f else 0.68f)
            val unitW = if (unitText.isEmpty()) 0f else unitText.length * cellH * 0.19f + cellH * 0.2f
            val total = chars.length * (cellW + gap) + unitW
            if (total > maxWidth.value && total > 0f) {
                val k = maxWidth.value / total
                cellH *= k; cellW *= k
            }
            Row(horizontalArrangement = Arrangement.spacedBy(gap.dp), verticalAlignment = Alignment.CenterVertically) {
                chars.forEach { ch -> FlapCell(ch.toString(), look, m, cellW.dp, cellH.dp, cellH * 0.7f, look.ink) }
                if (unitText.isNotEmpty()) FlapCell(unitText, look, m, null, (cellH * 0.5f).dp, cellH * 0.28f, look.accent)
            }
        }
        FaceCaption(f, look, m)
        if (f.stats.isNotEmpty() && m.h >= 130f) {
            Row(horizontalArrangement = Arrangement.spacedBy(m.dp(4f))) {
                f.stats.take(3).forEach { FaceStatBlock(it, look, m, Modifier.weight(1f, fill = false)) }
            }
        }
    }
}

@Composable
private fun FlapCell(text: String, look: FaceLook, m: FaceMetrics, w: Dp?, h: Dp, fontDp: Float, color: Color) {
    Box(
        modifier = Modifier
            .then(if (w != null) Modifier.width(w) else Modifier)
            .height(h)
            .clip(RoundedCornerShape(h * 0.07f))
            .background(Brush.verticalGradient(0f to Color(0xFF262626), 0.5f to Color(0xFF262626), 0.5f to Color(0xFF1C1C1C), 1f to Color(0xFF1C1C1C)))
            .then(if (w == null) Modifier.padding(horizontal = h * 0.2f) else Modifier)
            .drawWithContent {
                drawContent()
                drawLine(Color.Black, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), 2f)
            },
        contentAlignment = Alignment.Center
    ) {
        FaceText(text, look, m.sp(fontDp), color = color, weight = FontWeight.Bold, family = look.numFont)
    }
}

// --- Frames for live views (map, Maps window, 3D car) -----------------------------------------------

/**
 * [content] (a live map, the docked Maps window, the 3D car) inside [design]'s
 * frame: the material around it and a title strip beside it, never over it,
 * since a docked window would hide anything drawn on top.
 */
@Composable
internal fun DesignFrame(
    design: WidgetDesign,
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val look = faceLook(design.look)
    val labelAtBottom = design.layout == FaceLayout.BARS || design.layout == FaceLayout.STATS || design.layout == FaceLayout.TERMINAL
    val pad = when (look.kind) {
        FaceLookKind.CHROME -> 12.dp
        else -> 8.dp
    }
    val innerShape = surfaceShape(look, (look.radius - pad).coerceAtLeast(2.dp))
    FaceSurface(look, modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(pad), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!labelAtBottom) FrameLabel(icon, title, look, big = design.layout == FaceLayout.HERO)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(innerShape)
                    .then(
                        when (look.kind) {
                            FaceLookKind.THEME -> if (design.layout == FaceLayout.DUO) Modifier.border(2.dp, look.accent, innerShape) else Modifier
                            FaceLookKind.NEON -> Modifier.border(1.5.dp, look.accent2, innerShape)
                            else -> Modifier.border(1.dp, look.accent.copy(alpha = 0.6f), innerShape)
                        }
                    )
            ) { content() }
            if (labelAtBottom) FrameLabel(icon, title, look, big = false)
        }
    }
}

@Composable
private fun FrameLabel(icon: ImageVector, title: String, look: FaceLook, big: Boolean) {
    // Hours and minutes only: a tick on each minute is enough.
    val now = rememberNow(60_000L)
    val timeFmt = rememberTimeFormat()
    val time = remember(now, timeFmt) { timeFmt.format(now) }
    val size = if (big) 16.sp else 12.sp
    Row(
        modifier = Modifier
            .clip(controlShape(look))
            .background(if (look.kind == FaceLookKind.THEME) Color.Transparent else look.fill)
            .then(if (look.kind == FaceLookKind.DOTS) Modifier.border(1.dp, look.dim.copy(alpha = 0.5f), controlShape(look)) else Modifier)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = look.accent, modifier = Modifier.size(if (big) 18.dp else 14.dp))
        Spacer(Modifier.width(6.dp))
        FaceText(title.uppercase(Locale.getDefault()), look, size, weight = look.labelWeight, letterSpacing = 0.1.em, glow = true)
        Spacer(Modifier.width(6.dp))
        FaceText("· $time", look, size, color = look.dim, family = look.numFont)
    }
}
