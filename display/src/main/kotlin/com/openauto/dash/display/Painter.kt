package com.openauto.dash.display

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.openauto.dash.link.ClusterState
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The pictures the display draws itself, with plain Java2D: the idle screen,
 * the pairing code, and the cluster it shows from [ClusterState] when the head
 * unit sends data instead of video. Big type, flat colours and strokes of at
 * least 2 px, so it stays legible on a composite (RCA) monitor too.
 */
class Painter(val width: Int, val height: Int, private val overscanPct: Int) {

    /** TYPE_INT_RGB is 0x00RRGGBB per pixel: little-endian, the bytes are B,G,R,x (GStreamer's "bgrx"). */
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)

    private val inset = (min(width, height) * overscanPct / 100.0).roundToInt()
    private val unit = min(width, height) / 100f
    private val clockFormat = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

    /** Nothing to show yet: the clock, a status line, and the pairing code while no head unit has used it. */
    fun paintIdle(name: String, status: String, now: Long, pairingUri: String?) = draw { g ->
        g.color = BG_NIGHT
        g.fillRect(0, 0, width, height)
        val left = inset + (6 * unit).roundToInt()
        g.color = TEXT
        g.font = font(Font.BOLD, 22f)
        g.drawString(clockFormat.format(Instant.ofEpochMilli(now)), left, inset + (26 * unit).roundToInt())
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
            g.drawString("Scan with the Dashwheel phone app", x, y + size + (6 * unit).roundToInt())
        }
    }

    /** The cluster from data, on the page the head unit chose. [now] is the head unit's clock. */
    fun paintCluster(state: ClusterState, now: Long) = draw { g ->
        val bg = if (state.night) BG_NIGHT else BG_DAY
        val fg = if (state.night) TEXT else TEXT_DAY
        val muted = if (state.night) MUTED else MUTED_DAY
        val accent = Color(state.accent.toInt(), false)
        g.color = bg
        g.fillRect(0, 0, width, height)
        val left = inset + (5 * unit).roundToInt()
        val right = width - inset - (5 * unit).roundToInt()
        val top = inset + (5 * unit).roundToInt()

        // Top line on every page: the clock, and what the car is warning about.
        g.color = muted
        g.font = font(Font.BOLD, 7f)
        g.drawString(clockFormat.format(Instant.ofEpochMilli(now)), left, top + (6 * unit).roundToInt())
        if (state.open.isNotEmpty()) {
            g.color = WARN
            drawRight(g, "Open: " + state.open.joinToString(", "), right, top + (6 * unit).roundToInt())
        }

        when (state.page) {
            "MEDIA" -> paintMedia(g, state, left, right, fg, muted, accent)
            "NAV" -> paintNav(g, state, left, right, fg, muted, accent)
            else -> paintDrive(g, state, left, right, fg, muted, accent)
        }
    }

    private fun paintDrive(g: Graphics2D, s: ClusterState, left: Int, right: Int, fg: Color, muted: Color, accent: Color) {
        val speed = s.speedKmh?.let { if (s.imperial) (it / 1.609).roundToInt() else it }
        val cx = width / 2
        g.color = fg
        g.font = font(Font.BOLD, 42f)
        drawCentered(g, speed?.toString() ?: "--", cx, (height * 0.58).roundToInt())
        g.color = muted
        g.font = font(Font.PLAIN, 7f)
        drawCentered(g, if (s.imperial) "mph" else "km/h", cx, (height * 0.70).roundToInt())

        // Bottom row: whatever the car reports.
        val readings = buildList {
            s.rpm?.let { add("$it" to "rpm") }
            s.coolantC?.let { add("$it°" to "coolant") }
            s.fuelPct?.let { add("$it%" to "fuel") }
            s.rangeKm?.let { add((if (s.imperial) (it / 1.609).roundToInt() else it).toString() to if (s.imperial) "mi range" else "km range") }
        }
        if (readings.isNotEmpty()) {
            val y = height - inset - (8 * unit).roundToInt()
            val slot = (right - left) / readings.size
            readings.forEachIndexed { i, (value, label) ->
                val x = left + slot * i + slot / 2
                g.color = fg
                g.font = font(Font.BOLD, 9f)
                drawCentered(g, value, x, y - (5 * unit).roundToInt())
                g.color = muted
                g.font = font(Font.PLAIN, 4.5f)
                drawCentered(g, label, x, y + (1 * unit).roundToInt())
            }
        }
        s.fuelPct?.let { pct -> bar(g, left, (height * 0.76).roundToInt(), right - left, pct / 100f, accent, muted) }
    }

    private fun paintMedia(g: Graphics2D, s: ClusterState, left: Int, right: Int, fg: Color, muted: Color, accent: Color) {
        val media = s.media
        val mid = height / 2
        g.color = fg
        g.font = font(Font.BOLD, 11f)
        drawClipped(g, media?.title ?: "Nothing playing", left, mid, right - left)
        if (media != null) {
            g.color = muted
            g.font = font(Font.PLAIN, 7f)
            drawClipped(g, listOf(media.artist, media.app).filter { it.isNotBlank() }.joinToString("  ·  "), left, mid + (11 * unit).roundToInt(), right - left)
            if (media.durationMs > 0) {
                bar(g, left, mid + (20 * unit).roundToInt(), right - left, media.positionMs.toFloat() / media.durationMs, accent, muted)
            }
        }
    }

    private fun paintNav(g: Graphics2D, s: ClusterState, left: Int, right: Int, fg: Color, muted: Color, accent: Color) {
        val nav = s.nav
        if (nav == null) {
            g.color = muted
            g.font = font(Font.PLAIN, 9f)
            drawCentered(g, "No route", width / 2, height / 2)
            return
        }
        g.color = accent
        g.font = font(Font.BOLD, 20f)
        drawClipped(g, nav.distance, left, (height * 0.42).roundToInt(), right - left)
        g.color = fg
        g.font = font(Font.BOLD, 9f)
        drawClipped(g, nav.instruction, left, (height * 0.58).roundToInt(), right - left)
        g.color = muted
        g.font = font(Font.PLAIN, 7f)
        drawClipped(g, nav.street, left, (height * 0.70).roundToInt(), right - left)
        if (nav.eta.isNotBlank()) drawRight(g, "Arrive ${nav.eta}", right, height - inset - (8 * unit).roundToInt())
    }

    private fun bar(g: Graphics2D, x: Int, y: Int, w: Int, fraction: Float, fill: Color, track: Color) {
        val h = max(4, (1.6f * unit).roundToInt())
        g.color = track
        g.fillRect(x, y, w, h)
        g.color = fill
        g.fillRect(x, y, (w * fraction.coerceIn(0f, 1f)).roundToInt(), h)
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

    private fun drawClipped(g: Graphics2D, text: String, x: Int, baseline: Int, maxWidth: Int) {
        var shown = text
        while (shown.length > 1 && g.fontMetrics.stringWidth(shown) > maxWidth) shown = shown.dropLast(2) + "…"
        g.drawString(shown, x, baseline)
    }

    /** Font size in hundredths of the screen's short side. */
    private fun font(style: Int, size: Float) = Font(Font.SANS_SERIF, style, 1).deriveFont(style, size * unit)

    private inline fun draw(block: (Graphics2D) -> Unit): BufferedImage {
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.stroke = BasicStroke(max(2f, unit / 2))
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

    private companion object {
        val BG_NIGHT = Color(0x0B0F14)
        val BG_DAY = Color(0xEEF1F4)
        val TEXT = Color(0xF2F5F8)
        val TEXT_DAY = Color(0x111418)
        val MUTED = Color(0x8A96A3)
        val MUTED_DAY = Color(0x5B6570)
        val WARN = Color(0xFFB300)
    }
}
