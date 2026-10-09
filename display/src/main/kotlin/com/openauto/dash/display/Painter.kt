package com.openauto.dash.display

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.openauto.dash.link.ClusterState
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.RadialGradientPaint
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The pictures the display draws itself, with plain Java2D: the idle screen,
 * the pairing code, and the cluster it shows from [ClusterState] when the head
 * unit sends data instead of video. Big type, flat colours and strokes of at
 * least 2 px, so it stays legible on a composite (RCA) monitor too.
 */
class Painter(val width: Int, val height: Int, private val overscanPct: Int, private val logo: BufferedImage? = null) {
    /** Drawn turned 180° (Rotation). */
    @Volatile var upsideDown = false


    /** TYPE_INT_RGB is 0x00RRGGBB per pixel: little-endian, the bytes are B,G,R,x (GStreamer's "bgrx"). */
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)

    private val inset = (min(width, height) * overscanPct / 100.0).roundToInt()
    private val unit = min(width, height) / 100f

    /**
     * Nothing to show yet: the clock, a status line, and the pairing code while it is on offer.
     * [clock12]: the clock as the head unit last asked for it.
     */
    fun paintIdle(name: String, status: String, now: Long, pairingUri: String?, clock12: Boolean = false) = draw { g ->
        mono = false
        g.color = BG_NIGHT
        g.fillRect(0, 0, width, height)
        val logo = logo
        if (pairingUri == null && logo != null) {
            // The boot picture again, where Plymouth left it (same size, same background):
            // the hand-over can't be seen, and it stays until the head unit sends its own.
            // Only the clock comes in over it.
            val scale = min(1.0, min(width * 0.5 / logo.width, height * 0.6 / logo.height))
            val w = (logo.width * scale).roundToInt()
            val h = (logo.height * scale).roundToInt()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(logo, (width - w) / 2, (height - h) / 2, w, h, null)
            // The time in the top right corner, clear of the logo (at most 60 % of the height, centred).
            g.color = TEXT
            g.font = font(Font.BOLD, 12f)
            drawRight(g, clockText(now, clock12), width - inset - (6 * unit).roundToInt(), inset + (16 * unit).roundToInt())
            g.color = MUTED
            g.font = font(Font.PLAIN, 4.5f)
            drawCentered(g, status, width / 2, height - inset - (8 * unit).roundToInt())
            return@draw
        }
        val left = inset + (6 * unit).roundToInt()
        g.color = TEXT
        g.font = font(Font.BOLD, 22f)
        g.drawString(clockText(now, clock12), left, inset + (26 * unit).roundToInt())
        g.color = MUTED
        g.font = font(Font.PLAIN, 6f)
        g.drawString(name, left, inset + (36 * unit).roundToInt())
        g.drawString(status, left, height - inset - (8 * unit).roundToInt())
        if (pairingUri != null) {
            val size = min(height - 2 * inset - (20 * unit).roundToInt(), width / 2 - inset)
            val x = width - inset - size - (4 * unit).roundToInt()
            val y = inset + (6 * unit).roundToInt()
            drawQr(g, pairingUri, x, y, size)
            g.color = MUTED
            g.font = font(Font.PLAIN, 4.5f)
            g.drawString(Words.scanApp, x, y + size + (6 * unit).roundToInt())
        }
    }

    /** The cluster from data, on the page the head unit chose. [now] is the head unit's clock. */
    fun paintCluster(state: ClusterState, now: Long) = draw { g ->
        val design = Design.of(state.design)
        val p = Palette(state.night, Color(state.accent.toInt(), false), design)
        mono = design == Design.RETRO
        val text = ClusterText(state)
        g.color = p.bg
        g.fillRect(0, 0, width, height)
        val left = inset + (5 * unit).roundToInt()
        val right = width - inset - (5 * unit).roundToInt()
        val top = inset + (5 * unit).roundToInt()
        val bottom = height - inset - (5 * unit).roundToInt()

        // An alert up on the head unit takes the whole screen while it lasts.
        state.alert?.let { alert ->
            paintAlert(g, alert, text.clock(now), left, right, top, p)
            return@draw
        }

        // Top line on every page: the clock, and what the car is warning about in an amber pill.
        val clockFont = font(Font.BOLD, 7f)
        val clock = text.clock(now)
        val clockBase = top + (6 * unit).roundToInt()
        g.color = p.fg
        g.font = clockFont
        g.drawString(clock, left, clockBase)
        text.open?.let { open ->
            g.font = font(Font.BOLD, 4.8f)
            // A long list of doors is cut short of the clock, never drawn over it.
            val padX = (3 * unit).roundToInt()
            val room = right - left - g.getFontMetrics(clockFont).stringWidth(clock) - (6 * unit).roundToInt() - 2 * padX
            val shown = clipped(g, open, room)
            val w = g.fontMetrics.stringWidth(shown) + 2 * padX
            val h = (8 * unit).roundToInt()
            val y = clockBase - (6.5f * unit).roundToInt()
            g.color = p.warn
            g.fillRoundRect(right - w, y, w, h, h, h)
            g.color = p.bg
            drawCentered(g, shown, right - w / 2, y + h / 2 + g.fontMetrics.ascent * 2 / 5)
        }

        val bodyTop = top + (12 * unit).roundToInt()
        val body = Rectangle(left, bodyTop, right - left, bottom - bodyTop)
        when (state.page) {
            "MEDIA" -> paintMedia(g, state, text, body, p)
            "NAV" -> paintNav(g, state, text, body, p)
            "OBD" -> paintMeasures(g, state, text, body, p)
            else -> when (design) {
                Design.DIALS -> paintDials(g, state, text, body, p)
                Design.LARGE -> paintLarge(g, state, text, body, p)
                Design.RETRO -> paintRetro(g, state, text, body, p)
                Design.CARDS -> paintDrive(g, state, text, body, p)
            }
        }
    }

    /** [alert] full screen: a wash and a frame in its colour, a sign, its title big, its detail under it. */
    private fun paintAlert(g: Graphics2D, alert: ClusterState.Alert, clock: String, left: Int, right: Int, top: Int, p: Palette) {
        val tone = when (alert.level) {
            ClusterState.Alert.CRITICAL -> p.critical
            ClusterState.Alert.WARN -> p.warn
            else -> p.accent
        }
        // The alert's colour washed over the background, strongest in the middle.
        g.paint = RadialGradientPaint(
            width / 2f, height / 2f, max(width, height) * 0.6f, floatArrayOf(0f, 1f),
            arrayOf(mix(p.bg, tone, if (p.night) 0.26f else 0.16f), mix(p.bg, tone, if (p.night) 0.04f else 0.03f))
        )
        g.fillRect(0, 0, width, height)
        g.color = p.muted
        g.font = font(Font.BOLD, 7f)
        g.drawString(clock, left, top + (6 * unit).roundToInt())
        // A frame in the alert's colour, inside the overscan.
        val frame = (1.5f * unit).roundToInt().coerceAtLeast(2)
        g.color = tone
        g.stroke = BasicStroke(frame.toFloat())
        g.drawRoundRect(inset + frame, inset + frame, width - 2 * (inset + frame), height - 2 * (inset + frame), (8 * unit).roundToInt(), (8 * unit).roundToInt())

        val cx = width / 2
        val room = right - left
        warningSign(g, cx.toFloat(), height * 0.5f - 34 * unit, 22 * unit, tone, p.bg)
        g.color = p.fg
        g.font = font(Font.BOLD, 15f)
        drawCentered(g, clipped(g, alert.title, room), cx, (height * 0.5f + 8 * unit).roundToInt())
        if (alert.detail.isNotEmpty()) {
            g.color = tone
            g.font = font(Font.BOLD, 7.5f)
            drawCentered(g, clipped(g, alert.detail, room), cx, (height * 0.5f + 22 * unit).roundToInt())
        }
    }

    /**
     * The speed in a ring that fills with it, the car's readings in cards beside it.
     * Without readings (no adapter) the ring stands alone in the middle.
     */
    private fun paintDrive(g: Graphics2D, s: ClusterState, text: ClusterText, body: Rectangle, p: Palette) {
        val cards = text.cards
        val gap = (4 * unit).roundToInt()
        val ringBox = if (cards.isEmpty()) body else Rectangle(body.x, body.y, (body.width * 0.46).roundToInt(), body.height)
        val radius = min(ringBox.width, ringBox.height) / 2f
        val cx = ringBox.centerX.toFloat()
        val cy = ringBox.centerY.toFloat() + radius * 0.06f
        val shown = text.speedShown
        val full = if (s.imperial) 130f else 200f
        speedRing(g, cx, cy, radius, (shown ?: 0) / full, p)

        g.color = if (shown == null) p.muted else p.fg
        // Sized for three digits, so the figure keeps its size from 99 to 100.
        g.font = fitFont(g, radius * 0.8f / unit, "888", (radius * 1.25f).roundToInt())
        drawCentered(g, text.speed, cx.roundToInt(), (cy + g.fontMetrics.ascent * 0.36f).roundToInt())
        g.color = p.muted
        g.font = font(Font.BOLD, 5.5f)
        drawCentered(g, text.speedUnit, cx.roundToInt(), (cy + radius * 0.78f).roundToInt())

        if (cards.isEmpty()) return
        val grid = Rectangle(ringBox.x + ringBox.width + gap, body.y, body.width - ringBox.width - gap, body.height)
        val rows = (cards.size + 1) / 2
        val cardH = (grid.height - gap * (rows - 1)) / rows
        cards.forEachIndexed { i, card ->
            // An odd last card takes the whole row.
            val alone = i == cards.size - 1 && cards.size % 2 == 1
            val cardW = if (alone) grid.width else (grid.width - gap) / 2
            val x = grid.x + if (alone) 0 else (i % 2) * (cardW + gap)
            readingCard(g, card, Rectangle(x, grid.y + (i / 2) * (cardH + gap), cardW, cardH), s, p)
        }
    }

    /** A 270° ring open at the bottom, ticked, filled in the accent up to [fraction]. */
    private fun speedRing(g: Graphics2D, cx: Float, cy: Float, r: Float, fraction: Float, p: Palette) {
        val w = r * 0.1f
        val arc = Arc2D.Float(cx - r + w / 2, cy - r + w / 2, 2 * r - w, 2 * r - w, 225f, -270f, Arc2D.OPEN)
        val round = BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.stroke = round
        g.color = p.track
        g.draw(arc)
        // Ticks inside the ring, a longer one every fifth.
        val outer = r - w * 1.7f
        for (t in 0..20) {
            val major = t % 5 == 0
            val a = Math.toRadians(225.0 - 270.0 * t / 20)
            val inner = outer - if (major) w * 0.9f else w * 0.45f
            g.stroke = BasicStroke(max(2f, w * if (major) 0.18f else 0.1f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.color = if (major) p.muted else p.track
            g.draw(Line2D.Float(cx + outer * cos(a).toFloat(), cy - outer * sin(a).toFloat(), cx + inner * cos(a).toFloat(), cy - inner * sin(a).toFloat()))
        }
        val f = fraction.coerceIn(0f, 1f)
        if (f <= 0f) return
        g.stroke = round
        g.color = p.accent
        arc.extent = -270f * f
        g.draw(arc)
    }

    private fun readingCard(g: Graphics2D, card: ClusterText.Reading, box: Rectangle, s: ClusterState, p: Palette) {
        card(g, box, p)
        val pad = (4 * unit).roundToInt()
        val inner = box.width - 2 * pad
        g.color = p.muted
        g.font = font(Font.BOLD, 4.2f)
        drawClipped(g, card.label.uppercase(), box.x + pad, box.y + pad + g.fontMetrics.ascent, inner)
        val tone = toneOf(card, s, p)
        g.color = tone ?: p.fg
        g.font = fitFont(g, min(13f, box.height / unit * 0.34f), card.value, inner)
        // Every figure on the same line, the fuel's gauge under it.
        g.drawString(card.value, box.x + pad, box.y + box.height - pad - (5 * unit).roundToInt())
        if (card.kind == ClusterText.Kind.FUEL) {
            val h = max(4, (1.6f * unit).roundToInt())
            bar(g, box.x + pad, box.y + box.height - pad - h, inner, (s.fuelPct ?: 0) / 100f, tone ?: p.accent, p.track)
        }
    }

    /** Two round gauges as in the car: speed, and revs when the car gives them; the rest in a line under them. */
    private fun paintDials(g: Graphics2D, s: ClusterState, text: ClusterText, body: Rectangle, p: Palette) {
        val rest = text.cards.filter { it.kind != ClusterText.Kind.RPM }
        val rowH = if (rest.isEmpty()) 0 else (12 * unit).roundToInt()
        val gap = (6 * unit).roundToInt()
        val area = Rectangle(body.x, body.y, body.width, body.height - rowH)
        val rpm = s.rpm
        val r = if (rpm != null) min((area.width - gap) / 4f, area.height / 2f) else min(area.width, area.height) / 2f
        val cy = area.centerY.toFloat() + r * 0.04f
        val speedCx = (if (rpm != null) area.centerX - gap / 2.0 - r else area.centerX).toFloat()
        val shown = text.speedShown
        val full = if (s.imperial) 140f else 220f
        dial(g, speedCx, cy, r, shown?.toFloat(), full, 20f, 10f, null, p)
        dialReadout(g, speedCx, cy, r, text.speed, text.speedUnit, shown != null, p)
        if (rpm != null) {
            val rpmCx = (area.centerX + gap / 2.0 + r).toFloat()
            dial(g, rpmCx, cy, r, rpm / 1000f, 7f, 1f, 0.5f, 5f, p)
            dialReadout(g, rpmCx, cy, r, "$rpm", text.rpmLabel, true, p)
        }
        if (rest.isEmpty()) return
        // The other readings in a line along the bottom, each its caption then its figure.
        val y = body.y + body.height - rowH / 2
        val slot = body.width / rest.size
        val small = font(Font.BOLD, 4.2f)
        val big = font(Font.BOLD, 7f)
        val space = (2 * unit).roundToInt()
        rest.forEachIndexed { i, card ->
            val label = card.label.uppercase()
            val tone = toneOf(card, s, p)
            val lw = g.getFontMetrics(small).stringWidth(label)
            val vw = g.getFontMetrics(big).stringWidth(card.value)
            val barW = if (card.kind == ClusterText.Kind.FUEL) (14 * unit).roundToInt() else 0
            val total = lw + space + vw + if (barW > 0) space + barW else 0
            var x = body.x + slot * i + (slot - total) / 2
            g.color = p.muted
            g.font = small
            g.drawString(label, x, y + g.fontMetrics.ascent * 2 / 5)
            x += lw + space
            g.color = tone ?: p.fg
            g.font = big
            g.drawString(card.value, x, y + g.fontMetrics.ascent * 2 / 5)
            if (barW > 0) bar(g, x + vw + space, y - (0.8f * unit).roundToInt(), barW, (s.fuelPct ?: 0) / 100f, tone ?: p.accent, p.track)
        }
    }

    /**
     * A round gauge from 0 to [max] over 270 degrees, open at the bottom: a numbered tick every [major],
     * a short one every [minor], red from [redFrom], and a needle at [value] (none when null).
     */
    private fun dial(
        g: Graphics2D, cx: Float, cy: Float, r: Float, value: Float?, max: Float, major: Float, minor: Float, redFrom: Float?, p: Palette
    ) {
        fun angle(v: Float) = Math.toRadians(225.0 - 270.0 * (v / max).coerceIn(0f, 1f))
        fun at(v: Float, radius: Float) = angle(v).let { a -> (cx + radius * cos(a).toFloat()) to (cy - radius * sin(a).toFloat()) }
        val w = r * 0.035f
        val ring = r - w
        fun arc(from: Float, to: Float) =
            Arc2D.Float(cx - ring, cy - ring, 2 * ring, 2 * ring, 225f - 270f * from / max, -270f * (to - from) / max, Arc2D.OPEN)
        g.stroke = BasicStroke(w, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)
        g.color = p.track
        g.draw(arc(0f, max))
        val thick = BasicStroke(w * 2.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)
        if (redFrom != null) {
            g.color = p.critical
            g.stroke = thick
            g.draw(arc(redFrom, max))
        }
        if (value != null && value > 0f) {
            g.color = p.accent
            g.stroke = thick
            g.draw(arc(0f, value.coerceAtMost(max)))
        }
        g.font = font(Font.BOLD, r / unit * 0.11f)
        val steps = (max / minor).roundToInt()
        val perMajor = (major / minor).roundToInt()
        for (i in 0..steps) {
            val v = i * minor
            val isMajor = i % perMajor == 0
            val red = redFrom != null && v >= redFrom
            val (x1, y1) = at(v, ring - w * 1.5f)
            val (x2, y2) = at(v, ring - w * if (isMajor) 4.5f else 3f)
            g.color = if (red) p.critical else if (isMajor) p.fg else p.muted
            g.stroke = BasicStroke(max(2f, w * if (isMajor) 0.9f else 0.5f), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)
            g.draw(Line2D.Float(x1, y1, x2, y2))
            if (isMajor) {
                val (lx, ly) = at(v, ring - w * 8f)
                g.color = if (red) p.critical else p.muted
                drawCentered(g, v.roundToInt().toString(), lx.roundToInt(), (ly + g.fontMetrics.ascent * 0.38f).roundToInt())
            }
        }
        if (value == null) return
        // The needle, tapered, from just behind the hub.
        val a = angle(value)
        val tip = ring - w * 2f
        val tail = r * 0.12f
        val half = r * 0.025f
        val dx = cos(a).toFloat()
        val dy = -sin(a).toFloat()
        g.color = p.needle
        g.fill(Path2D.Float().apply {
            moveTo(cx + dx * tip, cy + dy * tip)
            lineTo(cx - dx * tail - dy * half, cy - dy * tail + dx * half)
            lineTo(cx - dx * tail + dy * half, cy - dy * tail - dx * half)
            closePath()
        })
        val hub = r * 0.07f
        g.color = p.fg
        g.fill(Ellipse2D.Float(cx - hub, cy - hub, 2 * hub, 2 * hub))
    }

    /** The gauge's figure under its hub, its unit under that. */
    private fun dialReadout(g: Graphics2D, cx: Float, cy: Float, r: Float, value: String, caption: String, live: Boolean, p: Palette) {
        g.color = if (live) p.fg else p.muted
        g.font = font(Font.BOLD, r / unit * 0.2f)
        drawCentered(g, value, cx.roundToInt(), (cy + r * 0.52f).roundToInt())
        g.color = p.muted
        g.font = font(Font.BOLD, r / unit * 0.08f)
        drawCentered(g, caption, cx.roundToInt(), (cy + r * 0.72f).roundToInt())
    }

    /** One number as big as the screen allows, the readings in a ruled line under it. */
    private fun paintLarge(g: Graphics2D, s: ClusterState, text: ClusterText, body: Rectangle, p: Palette) {
        val cards = text.cards
        val rowH = if (cards.isEmpty()) 0 else (18 * unit).roundToInt()
        val cx = body.centerX.roundToInt()
        // The unit just above the line, the number as tall as the room over it allows.
        val unitBase = body.y + body.height - rowH - (4 * unit).roundToInt()
        val numberBase = unitBase - (9 * unit).roundToInt()
        g.color = if (text.speedShown == null) p.muted else p.fg
        g.font = fitFont(g, min(52f, (numberBase - body.y) / unit / 0.74f), "888", body.width)
        drawCentered(g, text.speed, cx, numberBase)
        g.color = p.muted
        g.font = font(Font.BOLD, 6f)
        drawCentered(g, text.speedUnit, cx, unitBase)
        if (cards.isEmpty()) return
        val top = body.y + body.height - rowH
        g.color = p.track
        g.stroke = BasicStroke(max(2f, unit / 3))
        g.draw(Line2D.Float(body.x.toFloat(), top.toFloat(), (body.x + body.width).toFloat(), top.toFloat()))
        val slot = body.width / cards.size
        cards.forEachIndexed { i, card ->
            val x = body.x + slot * i + slot / 2
            if (i > 0) {
                g.color = p.track
                g.draw(Line2D.Float((body.x + slot * i).toFloat(), top + 4 * unit, (body.x + slot * i).toFloat(), top + rowH - 2 * unit))
            }
            g.color = toneOf(card, s, p) ?: p.fg
            g.font = font(Font.BOLD, 9f)
            drawCentered(g, card.value, x, top + (11 * unit).roundToInt())
            g.color = p.muted
            g.font = font(Font.BOLD, 4f)
            drawCentered(g, card.label.uppercase(), x, top + (17 * unit).roundToInt())
        }
    }

    /**
     * An old digital dash: the revs as a row of segments, the speed in big digits over
     * their unlit "888", the readings as segment gauges and figures under it.
     */
    private fun paintRetro(g: Graphics2D, s: ClusterState, text: ClusterText, body: Rectangle, p: Palette) {
        val segH = (6 * unit).roundToInt()
        var top = body.y
        s.rpm?.let { rpm ->
            g.color = p.muted
            g.font = font(Font.BOLD, 4.2f)
            g.drawString(text.rpmLabel.uppercase(), body.x, top + g.fontMetrics.ascent)
            drawRight(g, "$rpm", body.x + body.width, top + g.fontMetrics.ascent)
            top += (6 * unit).roundToInt()
            segments(g, body.x, top, body.width, segH, 40, rpm / RETRO_RPM_FULL, 0.8f, p)
            top += segH + (2 * unit).roundToInt()
        }
        val rest = text.cards.filter { it.kind != ClusterText.Kind.RPM }
        val rowH = if (rest.isEmpty()) 0 else (16 * unit).roundToInt()
        // The digits right-aligned over their unlit 888, like a real display.
        val area = Rectangle(body.x, top, body.width, body.y + body.height - rowH - top)
        g.font = fitFont(g, min(46f, area.height / unit * 0.9f), "888", (area.width * 0.7f).roundToInt())
        val digitsW = g.fontMetrics.stringWidth("888")
        val unitFont = font(Font.BOLD, 7f)
        val unitGap = (3 * unit).roundToInt()
        val x = body.x + (body.width - digitsW - g.getFontMetrics(unitFont).stringWidth(text.speedUnit) - unitGap) / 2
        val base = area.y + area.height / 2 + g.fontMetrics.ascent * 2 / 5
        g.color = p.ghost
        g.drawString("888", x, base)
        g.color = if (text.speedShown == null) p.muted else p.fg
        g.drawString(text.speed.takeLast(3).padStart(3, ' '), x, base)
        g.color = p.muted
        g.font = unitFont
        g.drawString(text.speedUnit, x + digitsW + unitGap, base)
        if (rest.isEmpty()) return
        val rowTop = body.y + body.height - rowH
        val gap = (4 * unit).roundToInt()
        val slot = (body.width - gap * (rest.size - 1)) / rest.size
        rest.forEachIndexed { i, card ->
            val bx = body.x + i * (slot + gap)
            g.color = p.muted
            g.font = font(Font.BOLD, 4.2f)
            drawClipped(g, card.label.uppercase(), bx, rowTop + g.fontMetrics.ascent, slot)
            g.color = toneOf(card, s, p) ?: p.fg
            g.font = font(Font.BOLD, 8f)
            drawRight(g, card.value, bx + slot, rowTop + (8.5f * unit).roundToInt())
            val fraction = when (card.kind) {
                ClusterText.Kind.FUEL -> (s.fuelPct ?: 0) / 100f
                ClusterText.Kind.COOLANT -> ((s.coolantC ?: 0) - 40) / 80f
                ClusterText.Kind.RANGE -> (s.rangeKm ?: 0) / 800f
                ClusterText.Kind.RPM -> 0f
            }
            segments(g, bx, rowTop + (11 * unit).roundToInt(), slot, (3.5f * unit).roundToInt(), 12, fraction, null, p)
        }
    }

    /** [count] blocks across [w], lit up to [fraction]; from [hotFrom] of the way on, lit ones are red. */
    private fun segments(g: Graphics2D, x: Int, y: Int, w: Int, h: Int, count: Int, fraction: Float, hotFrom: Float?, p: Palette) {
        val gap = max(2, w / count / 4)
        val bw = (w - gap * (count - 1)) / count.toFloat()
        val lit = (fraction.coerceIn(0f, 1f) * count).roundToInt()
        for (i in 0 until count) {
            g.color = when {
                i >= lit -> p.ghost
                hotFrom != null && i >= count * hotFrom -> p.critical
                else -> p.fg
            }
            g.fill(Rectangle2D.Float(x + i * (bw + gap), y.toFloat(), bw, h.toFloat()))
        }
    }

    /** A figure out of the ordinary takes the colour of its warning; null for the usual colour. */
    private fun toneOf(card: ClusterText.Reading, s: ClusterState, p: Palette): Color? = when (card.kind) {
        ClusterText.Kind.COOLANT -> s.coolantC?.let { c -> if (c >= HOT_C) p.critical else if (c >= WARM_C) p.warn else if (c < COLD_C) p.cold else null }
        ClusterText.Kind.FUEL -> s.fuelPct?.takeIf { it <= LOW_FUEL_PCT }?.let { p.warn }
        else -> null
    }

    private fun paintMedia(g: Graphics2D, s: ClusterState, text: ClusterText, body: Rectangle, p: Palette) {
        val media = s.media
        if (media == null) {
            emptyState(g, body, text.nothingPlaying, p) { cx, cy, size -> musicNote(g, cx, cy, size, p.muted) }
            return
        }
        // The cover: no picture comes with the readings, so a tile in the accent with a note.
        val side = min(body.height - (4 * unit).roundToInt(), (body.width * 0.4).roundToInt())
        val cover = Rectangle(body.x, body.y + (body.height - side) / 2, side, side)
        val radius = (8 * unit).roundToInt()
        g.paint = GradientPaint(cover.x.toFloat(), cover.y.toFloat(), p.accent, (cover.x + side).toFloat(), (cover.y + side).toFloat(), mix(p.accent, Color.BLACK, 0.6f))
        g.fillRoundRect(cover.x, cover.y, side, side, radius, radius)
        musicNote(g, cover.centerX.toFloat(), cover.centerY.toFloat(), side * 0.42f, Color(255, 255, 255, 235))

        val x = cover.x + side + (7 * unit).roundToInt()
        val room = body.x + body.width - x
        var y = cover.y + (2 * unit).roundToInt()
        if (media.app.isNotBlank()) {
            g.color = p.accent
            g.font = font(Font.BOLD, 4.2f)
            y += g.fontMetrics.ascent
            drawClipped(g, media.app.uppercase(), x, y, room)
            y += (3 * unit).roundToInt()
        }
        g.color = p.fg
        g.font = font(Font.BOLD, 10f)
        for (line in wrap(g, media.title, room, 2)) {
            y += g.fontMetrics.ascent
            g.drawString(line, x, y)
            y += (1.5f * unit).roundToInt()
        }
        if (media.artist.isNotBlank()) {
            g.color = p.muted
            g.font = font(Font.PLAIN, 6.5f)
            y += g.fontMetrics.ascent + (1 * unit).roundToInt()
            drawClipped(g, media.artist, x, y, room)
        }

        // Playing or paused, and how far along, on the cover's bottom line.
        val icon = 6 * unit
        val iconY = cover.y + side - icon / 2 - unit
        playState(g, x + icon / 2, iconY, icon, media.playing, p.fg)
        if (media.durationMs > 0) {
            g.font = font(Font.PLAIN, 4.5f)
            val times = "${mmss(media.positionMs)} / ${mmss(media.durationMs)}"
            val barX = (x + icon + 4 * unit).roundToInt()
            val barW = body.x + body.width - barX - g.fontMetrics.stringWidth(times) - (4 * unit).roundToInt()
            bar(g, barX, (iconY - 0.8f * unit).roundToInt(), barW, media.positionMs.toFloat() / media.durationMs, p.accent, p.track)
            g.color = p.muted
            drawRight(g, times, body.x + body.width, (iconY + g.fontMetrics.ascent * 0.4f).roundToInt())
        }
    }

    private fun paintNav(g: Graphics2D, s: ClusterState, text: ClusterText, body: Rectangle, p: Palette) {
        val nav = s.nav
        if (nav == null) {
            emptyState(g, body, text.noRoute, p) { cx, cy, size -> navArrow(g, cx, cy, size, p.muted) }
            return
        }
        // Bottom strip: when we get there, and how fast we're going.
        val stripH = (13 * unit).roundToInt()
        val strip = Rectangle(body.x, body.y + body.height - stripH, body.width, stripH)

        // The next turn as a big sign in the accent, the distance to it beside it.
        val gap = (5 * unit).roundToInt()
        val side = min(strip.y - body.y - gap, (body.width * 0.34).roundToInt())
        val sign = Rectangle(body.x, body.y, side, side)
        val radius = (8 * unit).roundToInt()
        g.color = p.accent
        g.fillRoundRect(sign.x, sign.y, side, side, radius, radius)
        maneuver(g, Maneuver.of(nav.instruction), sign.centerX.toFloat(), sign.centerY.toFloat(), side * 0.6f, onAccent(p.accent), p.accent)

        val x = sign.x + side + (7 * unit).roundToInt()
        val room = body.x + body.width - x
        var y = sign.y
        g.color = p.fg
        g.font = fitFont(g, 18f, nav.distance, room)
        y += g.fontMetrics.ascent - (3 * unit).roundToInt()
        g.drawString(nav.distance, x, y)
        g.font = font(Font.BOLD, 7.5f)
        y += (4 * unit).roundToInt()
        for (line in wrap(g, nav.instruction, room, 2)) {
            y += g.fontMetrics.ascent
            g.drawString(line, x, y)
            y += (1 * unit).roundToInt()
        }
        // The street only when the instruction didn't already name it.
        if (nav.street.isNotBlank() && !nav.instruction.contains(nav.street, ignoreCase = true)) {
            g.color = p.muted
            g.font = font(Font.PLAIN, 6f)
            y += g.fontMetrics.ascent + (1 * unit).roundToInt()
            drawClipped(g, nav.street, x, y, room)
        }

        card(g, strip, p)
        val pad = (5 * unit).roundToInt()
        val mid = strip.y + strip.height / 2
        val small = font(Font.BOLD, 4.2f)
        val big = font(Font.BOLD, 7f)
        val smallBase = mid + g.getFontMetrics(small).ascent * 2 / 5
        val bigBase = mid + g.getFontMetrics(big).ascent * 2 / 5
        if (nav.eta.isNotBlank()) {
            val (label, value) = text.arriveParts(nav.eta)
            g.color = p.muted
            g.font = small
            g.drawString(label.uppercase(), strip.x + pad, smallBase)
            val labelW = if (label.isEmpty()) 0 else g.fontMetrics.stringWidth(label.uppercase()) + (2 * unit).roundToInt()
            g.color = p.fg
            g.font = big
            g.drawString(value, strip.x + pad + labelW, bigBase)
        }
        if (s.speedKmh != null) {
            g.color = p.muted
            g.font = small
            drawRight(g, text.speedUnit, strip.x + strip.width - pad, smallBase)
            val unitW = g.fontMetrics.stringWidth(text.speedUnit) + (2 * unit).roundToInt()
            g.color = p.fg
            g.font = big
            drawRight(g, text.speed, strip.x + strip.width - pad - unitW, bigBase)
        }
    }

    /**
     * The Measures page: every reading the head unit sends for it, in cards of
     * the same size, as many to a row as fit (four for eight readings). Without
     * them (no OBD adapter) it says so, as the media page does with no music.
     */
    private fun paintMeasures(g: Graphics2D, s: ClusterState, text: ClusterText, body: Rectangle, p: Palette) {
        val measures = s.measures
        if (measures.isEmpty()) {
            emptyState(g, body, text.measuresNone, p) { cx, cy, size -> gaugeSign(g, cx, cy, size, p.muted) }
            return
        }
        val gap = (4 * unit).roundToInt()
        val columns = when {
            measures.size > 6 -> 4
            measures.size > 4 -> 3
            else -> 2
        }
        val rows = (measures.size + columns - 1) / columns
        val cardW = (body.width - gap * (columns - 1)) / columns
        val cardH = (body.height - gap * (rows - 1)) / rows
        val pad = (4 * unit).roundToInt()
        measures.forEachIndexed { i, m ->
            val box = Rectangle(body.x + (i % columns) * (cardW + gap), body.y + (i / columns) * (cardH + gap), cardW, cardH)
            card(g, box, p)
            val inner = box.width - 2 * pad
            g.color = p.muted
            // On two lines: "Liquide de refroidissement" does not fit on one at four to a row;
            // smaller where one word alone is wider than the card ("ACCÉLÉRATEUR").
            val label = m.label.uppercase()
            g.font = fitFont(g, 4.2f, label.split(' ').maxBy { it.length }, inner - unit.roundToInt())
            wrap(g, label, inner, 2).forEachIndexed { line, words ->
                g.drawString(words, box.x + pad, box.y + pad + g.fontMetrics.ascent + line * g.fontMetrics.height)
            }
            g.color = p.fg
            g.font = fitFont(g, min(13f, box.height / unit * 0.34f), m.value, inner)
            g.drawString(m.value, box.x + pad, box.y + box.height - pad - (3 * unit).roundToInt())
        }
    }

    /** A small gauge: an open arc and its needle, for the Measures page with nothing to show. */
    private fun gaugeSign(g: Graphics2D, cx: Float, cy: Float, size: Float, color: Color) {
        val r = size * 0.55f
        g.color = color
        g.stroke = BasicStroke(size * 0.1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(Arc2D.Float(cx - r, cy - r, 2 * r, 2 * r, 210f, -240f, Arc2D.OPEN))
        val a = Math.toRadians(60.0)
        g.draw(Line2D.Float(cx, cy, cx + r * 0.8f * cos(a).toFloat(), cy - r * 0.8f * sin(a).toFloat()))
    }

    /** Nothing to show on this page: a faint sign in a disc, and its words under it. */
    private inline fun emptyState(g: Graphics2D, body: Rectangle, words: String, p: Palette, sign: (Float, Float, Float) -> Unit) {
        val r = 18 * unit
        val cx = body.centerX.toFloat()
        val cy = body.centerY.toFloat() - 8 * unit
        g.color = p.card
        g.fill(Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r))
        sign(cx, cy, r)
        g.color = p.muted
        g.font = font(Font.BOLD, 7f)
        drawCentered(g, words, cx.roundToInt(), (cy + r + 12 * unit).roundToInt())
    }

    private fun card(g: Graphics2D, box: Rectangle, p: Palette) {
        val r = (6 * unit).roundToInt()
        g.color = p.card
        g.fillRoundRect(box.x, box.y, box.width, box.height, r, r)
        g.color = p.cardEdge
        g.stroke = BasicStroke(max(1f, unit / 4))
        g.drawRoundRect(box.x, box.y, box.width - 1, box.height - 1, r, r)
    }

    // --- Signs: shapes, not pictures, so they stay sharp at any size ----------------------------

    /** The turn [m] in a [size] box centred on ([cx], [cy]); [under] is the colour behind it. */
    private fun maneuver(g: Graphics2D, m: Maneuver?, cx: Float, cy: Float, size: Float, color: Color, under: Color) {
        val h = size / 2
        g.color = color
        g.stroke = BasicStroke(size * 0.15f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)
        when (m) {
            Maneuver.LEFT, Maneuver.RIGHT -> {
                val d = if (m == Maneuver.RIGHT) 1f else -1f
                val stem = cx - d * h * 0.4f
                val bendY = cy - h * 0.35f
                g.draw(Path2D.Float().apply {
                    moveTo(stem, cy + h)
                    lineTo(stem, bendY + h * 0.4f)
                    quadTo(stem, bendY, stem + d * h * 0.4f, bendY)
                    lineTo(cx + d * h * 0.35f, bendY)
                })
                head(g, cx + d * h, bendY, if (d > 0) 0.0 else Math.PI, size * 0.55f)
            }
            Maneuver.UTURN -> {
                g.draw(Path2D.Float().apply {
                    moveTo(cx + h * 0.45f, cy + h)
                    lineTo(cx + h * 0.45f, cy - h * 0.25f)
                    curveTo(cx + h * 0.45f, cy - h, cx - h * 0.45f, cy - h, cx - h * 0.45f, cy - h * 0.25f)
                    lineTo(cx - h * 0.45f, cy + h * 0.1f)
                })
                head(g, cx - h * 0.45f, cy + h * 0.7f, Math.PI / 2, size * 0.55f)
            }
            Maneuver.ROUNDABOUT -> {
                // The ring faint, the way through it bold: in at the bottom, round the right, out at the top.
                val r = h * 0.48f
                val oy = cy + h * 0.08f
                val stroke = g.stroke
                g.color = mix(under, color, 0.45f)
                g.stroke = BasicStroke(size * 0.08f)
                g.draw(Ellipse2D.Float(cx - r, oy - r, 2 * r, 2 * r))
                g.color = color
                g.stroke = stroke
                g.draw(Path2D.Float().apply {
                    moveTo(cx, cy + h * 1.05f)
                    lineTo(cx, oy + r)
                    append(Arc2D.Float(cx - r, oy - r, 2 * r, 2 * r, 270f, 180f, Arc2D.OPEN), true)
                    lineTo(cx, cy - h * 0.6f)
                })
                head(g, cx, cy - h * 1.05f, -Math.PI / 2, size * 0.42f)
            }
            Maneuver.ARRIVE -> {
                // A pin with its hole in the colour behind it.
                val r = h * 0.62f
                val top = cy - h
                g.fill(Path2D.Float().apply {
                    moveTo(cx, cy + h)
                    curveTo(cx - r * 0.35f, cy + h * 0.45f, cx - r, cy + h * 0.05f, cx - r, top + r)
                    curveTo(cx - r, top + r * 0.45f, cx - r * 0.55f, top, cx, top)
                    curveTo(cx + r * 0.55f, top, cx + r, top + r * 0.45f, cx + r, top + r)
                    curveTo(cx + r, cy + h * 0.05f, cx + r * 0.35f, cy + h * 0.45f, cx, cy + h)
                    closePath()
                })
                g.color = under
                val hole = r * 0.42f
                g.fill(Ellipse2D.Float(cx - hole, top + r - hole, 2 * hole, 2 * hole))
            }
            Maneuver.STRAIGHT -> {
                g.draw(Line2D.Float(cx, cy + h, cx, cy - h * 0.2f))
                head(g, cx, cy - h, -Math.PI / 2, size * 0.55f)
            }
            null -> navArrow(g, cx, cy, size * 0.85f, color)
        }
    }

    /** A filled arrow head with its tip at ([tipX], [tipY]) pointing at [angle] (radians, y down). */
    private fun head(g: Graphics2D, tipX: Float, tipY: Float, angle: Double, size: Float) {
        val back = size * 0.62f
        val half = size * 0.48f
        val bx = tipX - back * cos(angle).toFloat()
        val by = tipY - back * sin(angle).toFloat()
        val px = -sin(angle).toFloat() * half
        val py = cos(angle).toFloat() * half
        g.fill(Path2D.Float().apply {
            moveTo(tipX, tipY)
            lineTo(bx + px, by + py)
            lineTo(bx - px, by - py)
            closePath()
        })
    }

    /** The navigation chevron, for a turn the words don't tell. */
    private fun navArrow(g: Graphics2D, cx: Float, cy: Float, size: Float, color: Color) {
        val h = size / 2
        g.color = color
        g.fill(Path2D.Float().apply {
            moveTo(cx, cy - h)
            lineTo(cx + h * 0.75f, cy + h)
            lineTo(cx, cy + h * 0.55f)
            lineTo(cx - h * 0.75f, cy + h)
            closePath()
        })
    }

    private fun musicNote(g: Graphics2D, cx: Float, cy: Float, size: Float, color: Color) {
        val h = size / 2
        val r = h * 0.3f
        val leftStem = cx - h * 0.45f
        val rightStem = cx + h * 0.65f
        g.color = color
        g.fill(Ellipse2D.Float(leftStem - 2 * r + h * 0.07f, cy + h * 0.6f - r * 0.8f, 2 * r, 1.6f * r))
        g.fill(Ellipse2D.Float(rightStem - 2 * r + h * 0.07f, cy + h * 0.4f - r * 0.8f, 2 * r, 1.6f * r))
        g.stroke = BasicStroke(h * 0.14f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER)
        g.draw(Line2D.Float(leftStem, cy + h * 0.6f, leftStem, cy - h * 0.45f))
        g.draw(Line2D.Float(rightStem, cy + h * 0.4f, rightStem, cy - h * 0.7f))
        g.fill(Path2D.Float().apply {
            moveTo(leftStem - h * 0.07f, cy - h * 0.6f)
            lineTo(rightStem + h * 0.07f, cy - h * 0.85f)
            lineTo(rightStem + h * 0.07f, cy - h * 0.55f)
            lineTo(leftStem - h * 0.07f, cy - h * 0.3f)
            closePath()
        })
    }

    private fun playState(g: Graphics2D, cx: Float, cy: Float, size: Float, playing: Boolean, color: Color) {
        val h = size / 2
        g.color = color
        if (playing) {
            // Playing: the pause bars, as on the head unit's own controls.
            val bw = h * 0.6f
            g.fill(RoundRectangle2D.Float(cx - h * 0.8f, cy - h, bw, 2 * h, bw / 2, bw / 2))
            g.fill(RoundRectangle2D.Float(cx + h * 0.8f - bw, cy - h, bw, 2 * h, bw / 2, bw / 2))
        } else {
            g.fill(Path2D.Float().apply {
                moveTo(cx - h * 0.7f, cy - h)
                lineTo(cx + h, cy)
                lineTo(cx - h * 0.7f, cy + h)
                closePath()
            })
        }
    }

    /** A rounded triangle in [tone] with its "!" in [ink], [size] wide, its top at [top]. */
    private fun warningSign(g: Graphics2D, cx: Float, top: Float, size: Float, tone: Color, ink: Color) {
        val h = size * 0.86f
        val tri = Path2D.Float().apply {
            moveTo(cx, top)
            lineTo(cx + size / 2, top + h)
            lineTo(cx - size / 2, top + h)
            closePath()
        }
        g.color = tone
        g.stroke = BasicStroke(size * 0.14f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(tri)
        g.fill(tri)
        g.color = ink
        g.stroke = BasicStroke(size * 0.1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(Line2D.Float(cx, top + h * 0.36f, cx, top + h * 0.64f))
        val dot = size * 0.11f
        g.fill(Ellipse2D.Float(cx - dot / 2, top + h * 0.82f - dot / 2, dot, dot))
    }

    private fun bar(g: Graphics2D, x: Int, y: Int, w: Int, fraction: Float, fill: Color, track: Color) {
        val h = max(4, (1.6f * unit).roundToInt())
        g.color = track
        g.fillRoundRect(x, y, w, h, h, h)
        val filled = (w * fraction.coerceIn(0f, 1f)).roundToInt()
        if (filled <= 0) return
        g.color = fill
        g.fillRoundRect(x, y, max(h, filled), h, h, h)
    }

    private fun drawQr(g: Graphics2D, text: String, x: Int, y: Int, size: Int) {
        val matrix = QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
        )
        // Whole pixels per module, on a white quiet zone: phones read it off a TV that way.
        val module = max(1, (size - 8 * unit.roundToInt()) / matrix.width)
        val side = module * matrix.width
        val quiet = (size - side) / 2
        g.color = Color.WHITE
        g.fillRect(x, y, size, size)
        g.color = Color.BLACK
        for (my in 0 until matrix.height) for (mx in 0 until matrix.width) {
            if (matrix[mx, my]) g.fillRect(x + quiet + mx * module, y + quiet + my * module, module, module)
        }
    }

    private fun drawCentered(g: Graphics2D, text: String, cx: Int, baseline: Int) =
        g.drawString(text, cx - g.fontMetrics.stringWidth(text) / 2, baseline)

    private fun drawRight(g: Graphics2D, text: String, right: Int, baseline: Int) =
        g.drawString(text, right - g.fontMetrics.stringWidth(text), baseline)

    private fun drawClipped(g: Graphics2D, text: String, x: Int, baseline: Int, maxWidth: Int) =
        g.drawString(clipped(g, text, maxWidth), x, baseline)

    /** [text] cut to [maxWidth] in the current font, ending in an ellipsis when it was. */
    private fun clipped(g: Graphics2D, text: String, maxWidth: Int): String {
        var shown = text
        while (shown.length > 1 && g.fontMetrics.stringWidth(shown) > maxWidth) shown = shown.dropLast(2).trimEnd() + "…"
        return shown
    }

    /** [text] over at most [lines] lines of [maxWidth] in the current font, the last one cut with an ellipsis. */
    private fun wrap(g: Graphics2D, text: String, maxWidth: Int, lines: Int): List<String> {
        val out = mutableListOf<String>()
        var rest = text.trim()
        while (out.size < lines - 1 && g.fontMetrics.stringWidth(rest) > maxWidth) {
            val words = rest.split(' ')
            var line = words[0]
            var used = 1
            while (used < words.size && g.fontMetrics.stringWidth(line + " " + words[used]) <= maxWidth) line += " " + words[used++]
            // One word wider than the line: it is cut on the last line instead.
            if (used == words.size || g.fontMetrics.stringWidth(line) > maxWidth) break
            out += line
            rest = words.drop(used).joinToString(" ")
        }
        if (rest.isNotEmpty()) out += clipped(g, rest, maxWidth)
        return out
    }

    /** Bold at [size], smaller if need be so [sample] fits [maxWidth]. */
    private fun fitFont(g: Graphics2D, size: Float, sample: String, maxWidth: Int): Font {
        val f = font(Font.BOLD, size)
        val w = g.getFontMetrics(f).stringWidth(sample)
        return if (w <= maxWidth || w == 0) f else f.deriveFont(f.size2D * maxWidth / w)
    }

    private fun mmss(ms: Long): String = (ms / 1000).let { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" }

    /** The Retro design's figures: drawn in a fixed-width face. */
    private var mono = false

    /** Font size in hundredths of the screen's short side. */
    private fun font(style: Int, size: Float) = Font(if (mono) MONO else Font.SANS_SERIF, style, 1).deriveFont(style, size * unit)

    private inline fun draw(block: (Graphics2D) -> Unit): BufferedImage {
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            g.stroke = BasicStroke(max(2f, unit / 2))
            // A monitor mounted upside down: everything is drawn turned half a turn.
            if (upsideDown) g.transform(java.awt.geom.AffineTransform(-1.0, 0.0, 0.0, -1.0, width.toDouble(), height.toDouble()))
            block(g)
        } finally {
            g.dispose()
        }
        return image
    }

    /** The picture as GStreamer "bgrx" bytes. */
    fun bgrx(): ByteArray {
        val pixels = (image.raster.dataBuffer as java.awt.image.DataBufferInt).data
        val out = ByteArray(pixels.size * 4)
        var o = 0
        for (p in pixels) {
            out[o++] = p.toByte()
            out[o++] = (p shr 8).toByte()
            out[o++] = (p shr 16).toByte()
            out[o++] = 0
        }
        return out
    }

    /** The cluster's colours by night (pure black: an IPS panel glows on anything lighter) and by day. */
    private class Palette(val night: Boolean, dashAccent: Color, design: Design) {
        // Retro is amber on black by night and a grey-green LCD by day, whatever the dashboard's accent.
        private val retro = design == Design.RETRO
        val accent = if (!retro) dashAccent else if (night) Color(0xFFB000) else Color(0x1A2310)
        val bg = pick(BG_NIGHT, Color(0xEEF1F4), BG_NIGHT, Color(0xB7C29C))
        val card = pick(Color(0x0E1217), Color(0xFFFFFF), Color(0x130C00), Color(0xADB892))
        val cardEdge = pick(Color(0x1A2129), Color(0xD9DFE5), Color(0x2A1B00), Color(0x98A47C))
        val track = pick(Color(0x1D252E), Color(0xD5DBE1), Color(0x2E2106), Color(0x9EAA82))
        /** Retro's unlit segments. */
        val ghost = pick(Color(0x1D252E), Color(0xD5DBE1), Color(0x181001), Color(0xAEBA94))
        val fg = pick(TEXT, Color(0x111418), Color(0xFFB000), Color(0x1A2310))
        val muted = pick(MUTED, Color(0x5B6570), Color(0x9A6A00), Color(0x4A5536))
        /** Amber is lost on the day palette's near-white: a burnt orange reads there. */
        val warn = pick(Color(0xFFB300), Color(0xA84300), Color(0xFF6D00), Color(0x8A3A00))
        val critical = pick(Color(0xFF5252), Color(0xC62828), Color(0xFF3D00), Color(0x9A1010))
        val cold = pick(Color(0x64B5F6), Color(0x1565C0), Color(0x4FC3F7), Color(0x0D3C7A))
        /** The Dials' needles: the warm orange of real ones. */
        val needle = if (night) Color(0xFF6E40) else Color(0xE64A19)

        private fun pick(night: Color, day: Color, retroNight: Color, retroDay: Color) =
            if (retro) (if (this.night) retroNight else retroDay) else if (this.night) night else day
    }

    private companion object {
        val BG_NIGHT = Color(0x000000)
        val TEXT = Color(0xF2F5F8)
        val MUTED = Color(0x8A96A3)

        /** A fixed-width face the Pi has (DejaVu), else one this machine has. */
        val MONO: String by lazy {
            val have = runCatching { java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet() }.getOrDefault(emptySet())
            listOf("DejaVu Sans Mono", "Consolas").firstOrNull { it in have } ?: Font.MONOSPACED
        }

        /** Retro's rev segments are all lit here. */
        const val RETRO_RPM_FULL = 5_000f

        /** Coolant below this is still warming up (blue), from these on warm (amber) and hot (red). */
        const val COLD_C = 60
        const val WARM_C = 105
        const val HOT_C = 110
        const val LOW_FUEL_PCT = 10

        /** [a] moved [t] of the way to [b]. */
        fun mix(a: Color, b: Color, t: Float) = Color(
            (a.red + (b.red - a.red) * t).roundToInt(),
            (a.green + (b.green - a.green) * t).roundToInt(),
            (a.blue + (b.blue - a.blue) * t).roundToInt()
        )

        /** Near-black or white, whichever reads on [accent]. */
        fun onAccent(accent: Color): Color =
            if (accent.red * 0.299 + accent.green * 0.587 + accent.blue * 0.114 > 170) Color(0x0B0F14) else Color.WHITE
    }
}

/** The cluster's look ([ClusterState.design]); an older head unit sends none and gets [CARDS]. */
internal enum class Design {
    /** A speed ring and the readings in cards. */
    CARDS,

    /** Round gauges with needles, speed and revs. */
    DIALS,

    /** One big number, the readings in a line under it. */
    LARGE,

    /** An old digital dash: segments and amber digits. */
    RETRO;

    companion object {
        fun of(name: String?): Design = entries.firstOrNull { it.name == name } ?: CARDS
    }
}

/**
 * The turn the navigation app's words describe, for the sign beside them.
 * The words come in the head unit's language, so each one is looked for in all
 * of Dashwheel's; null when none is found (the sign is then a plain arrow).
 */
internal enum class Maneuver {
    LEFT, RIGHT, STRAIGHT, UTURN, ROUNDABOUT, ARRIVE;

    companion object {
        private val ROUNDABOUT_WORDS = listOf("roundabout", "rond-point", "rond point", "giratoire", "kreisverkehr", "rotonda", "glorieta", "rotunda", "rotatória", "rotonde", "rondo", "кольц")
        private val UTURN_WORDS = listOf("u-turn", "u turn", "demi-tour", "wenden", "cambio de sentido", "inversione", "retorno", "keer om", "omkeren", "zawróć", "zawroc", "разворот")
        private val ARRIVE_WORDS = listOf("destination", "you have arrived", "arrivée", "vous êtes arrivé", "ziel", "destino", "bestemming", "cel podróży", "пункт назначения")
        private val LEFT_WORDS = listOf("left", "gauche", "links", "izquierda", "sinistra", "esquerda", "lewo", "налево", "левее", "влево")
        private val RIGHT_WORDS = listOf("right", "droite", "rechts", "derecha", "destra", "direita", "prawo", "направо", "правее", "вправо")
        private val STRAIGHT_WORDS = listOf("straight", "continue", "tout droit", "continuez", "geradeaus", "recto", "dritto", "em frente", "rechtdoor", "prosto", "прямо")

        fun of(instruction: String): Maneuver? {
            val s = instruction.lowercase()
            if (ROUNDABOUT_WORDS.any { it in s }) return ROUNDABOUT
            if (UTURN_WORDS.any { it in s }) return UTURN
            if (ARRIVE_WORDS.any { it in s }) return ARRIVE
            // The first direction named is the turn ("Keep right to continue on A6": right).
            return listOf(LEFT to LEFT_WORDS, RIGHT to RIGHT_WORDS, STRAIGHT to STRAIGHT_WORDS)
                .mapNotNull { (m, words) -> words.mapNotNull { w -> s.indexOf(w).takeIf { it >= 0 } }.minOrNull()?.let { m to it } }
                .minByOrNull { it.second }?.first
        }
    }
}

/**
 * What the cluster prints from a [ClusterState]: the figures in the driver's
 * units and the head unit's own words when it sent them, else metric, 24 h and
 * English as before (an older head unit says none of it).
 */
internal class ClusterText(private val s: ClusterState) {
    private val labels = s.labels

    fun clock(now: Long, zone: ZoneId = ZoneId.systemDefault()): String = clockText(now, s.clock12, zone)

    /** The doors' warning, or null with none open. */
    val open: String?
        get() = s.open.takeIf { it.isNotEmpty() }?.let { fill(labels?.open, "Open: %s", it.joinToString(", ")) }

    /** The speed in the driver's unit, or null when the head unit has none. */
    val speedShown: Int? get() = s.speedKmh?.let { distance(it) }

    val speed: String get() = speedShown?.toString() ?: "--"

    val speedUnit: String get() = s.speedUnit ?: if (s.imperial) "mph" else "km/h"

    enum class Kind { RPM, COOLANT, FUEL, RANGE }

    class Reading(val value: String, val label: String, val kind: Kind)

    /** Each figure the car reports, with its caption. */
    val cards: List<Reading>
        get() = buildList {
            s.rpm?.let { add(Reading("$it", rpmLabel, Kind.RPM)) }
            s.coolantC?.let { add(Reading(if (s.fahrenheit) "${(it * 1.8 + 32).roundToInt()}°F" else "$it°", labels?.coolant ?: "coolant", Kind.COOLANT)) }
            s.fuelPct?.let { add(Reading("$it%", labels?.fuel ?: "fuel", Kind.FUEL)) }
            s.rangeKm?.let { add(Reading(distance(it).toString(), labels?.range ?: if (s.imperial) "mi range" else "km range", Kind.RANGE)) }
        }

    /** [cards] as figure to caption. */
    val readings: List<Pair<String, String>> get() = cards.map { it.value to it.label }

    val rpmLabel: String get() = labels?.rpm ?: "rpm"

    val nothingPlaying: String get() = labels?.nothingPlaying ?: "Nothing playing"

    val noRoute: String get() = labels?.noRoute ?: "No route"

    val measuresNone: String get() = labels?.measuresNone ?: "Waiting for the OBD adapter"

    fun arrive(eta: String): String = fill(labels?.arrive, "Arrive %s", eta)

    /** The arrival line split into its words and the time, so the time can be drawn bigger. */
    fun arriveParts(eta: String): Pair<String, String> {
        val line = labels?.arrive?.takeIf { "%s" in it } ?: "Arrive %s"
        return line.replace("%s", "").trim().trimEnd(':').trim() to eta
    }

    private fun distance(km: Int): Int = if (s.imperial) (km / KM_PER_MILE).roundToInt() else km

    /** [value] in the head unit's line; a line without its %s would lose the value, so English then. */
    private fun fill(line: String?, english: String, value: String): String =
        (line?.takeIf { "%s" in it } ?: english).replace("%s", value)

    private companion object {
        const val KM_PER_MILE = 1.609344
    }
}

private val CLOCK_24 = DateTimeFormatter.ofPattern("HH:mm")
private val CLOCK_12 = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

/** "14:05", or "2:05 PM" on a 12-hour clock. */
internal fun clockText(now: Long, clock12: Boolean, zone: ZoneId = ZoneId.systemDefault()): String =
    (if (clock12) CLOCK_12 else CLOCK_24).withZone(zone).format(Instant.ofEpochMilli(now))
