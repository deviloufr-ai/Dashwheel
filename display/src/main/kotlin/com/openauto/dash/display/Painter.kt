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
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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
            g.drawString("Scan with the Dashwheel phone app", x, y + size + (6 * unit).roundToInt())
        }
    }

    /** The cluster from data, on the page the head unit chose. [now] is the head unit's clock. */
    fun paintCluster(state: ClusterState, now: Long) = draw { g ->
        val bg = if (state.night) BG_NIGHT else BG_DAY
        val fg = if (state.night) TEXT else TEXT_DAY
        val muted = if (state.night) MUTED else MUTED_DAY
        val accent = Color(state.accent.toInt(), false)
        val text = ClusterText(state)
        g.color = bg
        g.fillRect(0, 0, width, height)
        val left = inset + (5 * unit).roundToInt()
        val right = width - inset - (5 * unit).roundToInt()
        val top = inset + (5 * unit).roundToInt()

        // An alert up on the head unit takes the whole screen while it lasts.
        state.alert?.let { alert ->
            paintAlert(g, alert, text.clock(now), left, right, top, fg, muted, accent, state.night)
            return@draw
        }

        // Top line on every page: the clock, and what the car is warning about.
        g.color = muted
        g.font = font(Font.BOLD, 7f)
        val clock = text.clock(now)
        g.drawString(clock, left, top + (6 * unit).roundToInt())
        text.open?.let { open ->
            // A long list of doors is cut short of the clock, never drawn over it.
            val room = right - left - g.fontMetrics.stringWidth(clock) - (4 * unit).roundToInt()
            g.color = if (state.night) WARN else WARN_DAY
            drawRight(g, clipped(g, open, room), right, top + (6 * unit).roundToInt())
        }

        when (state.page) {
            "MEDIA" -> paintMedia(g, state, text, left, right, fg, muted, accent)
            "NAV" -> paintNav(g, state, text, left, right, fg, muted, accent)
            else -> paintDrive(g, state, text, left, right, fg, muted, accent)
        }
    }

    /** [alert] full screen: the clock, a frame and a stripe in its colour, its title big, its detail under it. */
    private fun paintAlert(
        g: Graphics2D, alert: ClusterState.Alert, clock: String, left: Int, right: Int, top: Int,
        fg: Color, muted: Color, accent: Color, night: Boolean
    ) {
        val tone = when (alert.level) {
            ClusterState.Alert.CRITICAL -> if (night) CRITICAL else CRITICAL_DAY
            ClusterState.Alert.WARN -> if (night) WARN else WARN_DAY
            else -> accent
        }
        g.color = muted
        g.font = font(Font.BOLD, 7f)
        g.drawString(clock, left, top + (6 * unit).roundToInt())
        // A frame in the alert's colour, inside the overscan.
        val frame = (1.5f * unit).roundToInt().coerceAtLeast(2)
        g.color = tone
        g.stroke = BasicStroke(frame.toFloat())
        g.drawRoundRect(inset + frame, inset + frame, width - 2 * (inset + frame), height - 2 * (inset + frame), (8 * unit).roundToInt(), (8 * unit).roundToInt())
        val cx = width / 2
        val room = right - left
        g.color = tone
        g.fillRoundRect(cx - (10 * unit).roundToInt(), height / 2 - (22 * unit).roundToInt(), (20 * unit).roundToInt(), (2 * unit).roundToInt(), (2 * unit).roundToInt(), (2 * unit).roundToInt())
        g.color = fg
        g.font = font(Font.BOLD, 16f)
        drawCentered(g, clipped(g, alert.title, room), cx, height / 2)
        if (alert.detail.isNotEmpty()) {
            g.color = tone
            g.font = font(Font.BOLD, 8f)
            drawCentered(g, clipped(g, alert.detail, room), cx, height / 2 + (16 * unit).roundToInt())
        }
    }

    private fun paintDrive(g: Graphics2D, s: ClusterState, text: ClusterText, left: Int, right: Int, fg: Color, muted: Color, accent: Color) {
        val cx = width / 2
        g.color = fg
        g.font = font(Font.BOLD, 42f)
        drawCentered(g, text.speed, cx, (height * 0.58).roundToInt())
        g.color = muted
        g.font = font(Font.PLAIN, 7f)
        drawCentered(g, text.speedUnit, cx, (height * 0.70).roundToInt())

        // Bottom row: whatever the car reports.
        val readings = text.readings
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

    private fun paintMedia(g: Graphics2D, s: ClusterState, text: ClusterText, left: Int, right: Int, fg: Color, muted: Color, accent: Color) {
        val media = s.media
        val mid = height / 2
        g.color = fg
        g.font = font(Font.BOLD, 11f)
        drawClipped(g, media?.title ?: text.nothingPlaying, left, mid, right - left)
        if (media != null) {
            g.color = muted
            g.font = font(Font.PLAIN, 7f)
            drawClipped(g, listOf(media.artist, media.app).filter { it.isNotBlank() }.joinToString("  ·  "), left, mid + (11 * unit).roundToInt(), right - left)
            if (media.durationMs > 0) {
                bar(g, left, mid + (20 * unit).roundToInt(), right - left, media.positionMs.toFloat() / media.durationMs, accent, muted)
            }
        }
    }

    private fun paintNav(g: Graphics2D, s: ClusterState, text: ClusterText, left: Int, right: Int, fg: Color, muted: Color, accent: Color) {
        val nav = s.nav
        if (nav == null) {
            g.color = muted
            g.font = font(Font.PLAIN, 9f)
            drawCentered(g, text.noRoute, width / 2, height / 2)
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
        if (nav.eta.isNotBlank()) drawRight(g, text.arrive(nav.eta), right, height - inset - (8 * unit).roundToInt())
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

    private fun drawClipped(g: Graphics2D, text: String, x: Int, baseline: Int, maxWidth: Int) =
        g.drawString(clipped(g, text, maxWidth), x, baseline)

    /** [text] cut to [maxWidth] in the current font, ending in an ellipsis when it was. */
    private fun clipped(g: Graphics2D, text: String, maxWidth: Int): String {
        var shown = text
        while (shown.length > 1 && g.fontMetrics.stringWidth(shown) > maxWidth) shown = shown.dropLast(2) + "…"
        return shown
    }

    /** Font size in hundredths of the screen's short side. */
    private fun font(style: Int, size: Float) = Font(Font.SANS_SERIF, style, 1).deriveFont(style, size * unit)

    private inline fun draw(block: (Graphics2D) -> Unit): BufferedImage {
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
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

    private companion object {
        val BG_NIGHT = Color(0x000000)
        val BG_DAY = Color(0xEEF1F4)
        val TEXT = Color(0xF2F5F8)
        val TEXT_DAY = Color(0x111418)
        val MUTED = Color(0x8A96A3)
        val MUTED_DAY = Color(0x5B6570)
        val WARN = Color(0xFFB300)
        val CRITICAL = Color(0xFF5252)
        val CRITICAL_DAY = Color(0xC62828)
        /** Amber is lost on the day palette's near-white: a burnt orange reads there. */
        val WARN_DAY = Color(0xA84300)
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

    val speed: String get() = s.speedKmh?.let { distance(it).toString() } ?: "--"

    val speedUnit: String get() = s.speedUnit ?: if (s.imperial) "mph" else "km/h"

    /** The bottom row: each figure the car reports, over its caption. */
    val readings: List<Pair<String, String>>
        get() = buildList {
            s.rpm?.let { add("$it" to (labels?.rpm ?: "rpm")) }
            s.coolantC?.let { add((if (s.fahrenheit) "${(it * 1.8 + 32).roundToInt()}°F" else "$it°") to (labels?.coolant ?: "coolant")) }
            s.fuelPct?.let { add("$it%" to (labels?.fuel ?: "fuel")) }
            s.rangeKm?.let { add(distance(it).toString() to (labels?.range ?: if (s.imperial) "mi range" else "km range")) }
        }

    val nothingPlaying: String get() = labels?.nothingPlaying ?: "Nothing playing"

    val noRoute: String get() = labels?.noRoute ?: "No route"

    fun arrive(eta: String): String = fill(labels?.arrive, "Arrive %s", eta)

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
