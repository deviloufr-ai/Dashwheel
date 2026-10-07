package com.openauto.dash.display

import java.io.File

/**
 * The shape kmssink takes the monitor's pixels to have, and so the shape the
 * video is said to have before it is shown, so that the two cancel out.
 *
 * kmssink works the pixel shape out from the physical size the monitor
 * reports (EDID) and keeps the picture's proportions by it. Cheap HDMI
 * boards report a made-up size: the 7" 1024×600 panel says 47 × 26 cm, a
 * 21" monitor's, so its pixels came out 16/15 wide and the picture 960 px
 * wide, in black bars, with 64 px of it cut off. Its pixels are square: the
 * video's frames are given that same 16/15, and fill the screen again.
 */
object PixelShape {

    /** What kmssink will assume for the connected monitor ([detect]); 1/1 until then. */
    @Volatile var kms: Pair<Int, Int> = 1 to 1
        private set

    @Volatile private var screen = 0 to 0
    @Volatile private var stream: Pair<Int, Int>? = null

    /** Reads the connected monitor's reported size under [drm] (`/sys/class/drm`), for a [width] × [height] screen. */
    fun detect(width: Int, height: Int, drm: File = File("/sys/class/drm")) {
        use(width, height, reportedSizeMm(drm)?.let { (w, h) -> deviceRatio(width, height, w, h) } ?: (1 to 1))
    }

    /**
     * Reads the monitor's reported size again, for the screen [detect] was
     * given: a monitor that powered up after the Pi (the car's ignition) had
     * none to read at start-up, and its picture came back narrowed.
     */
    fun refresh(drm: File = File("/sys/class/drm")) {
        val (w, h) = screen
        val size = reportedSizeMm(drm) ?: return
        val shape = deviceRatio(w, h, size.first, size.second)
        if (shape != kms) {
            log("the monitor reports a size that is not its shape: pixels taken for ${shape.first}/${shape.second}")
            kms = shape
        }
    }

    /** A [width] × [height] screen whose pixels kmssink takes for [kms]. */
    internal fun use(width: Int, height: Int, kms: Pair<Int, Int>) {
        screen = width to height
        this.kms = kms
        stream = null
    }

    /**
     * The shape to give the video so it fills the screen, edge to edge: the
     * stream the head unit last said it sends, else the one it sends for this
     * screen (its size, the height rounded to 16 for the encoder: 1024 × 608
     * for 1024 × 600). The 8 rows more are squeezed in, not left as bars.
     */
    fun forStream(): Pair<Int, Int> {
        val (w, h) = screen
        val (sw, sh) = stream ?: defaultStream(w, h)
        return fill(sw, sh, w, h, kms)
    }

    /**
     * What the head unit sends a [w] × [h] screen at its default quality
     * (SecondScreenRules.streamSize): the screen's shape, no taller than 720,
     * within 1920 × 1080, each side rounded to 16.
     */
    internal fun defaultStream(w: Int, h: Int): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return w to h
        val scale = minOf(1.0, 720.0 / h, 1920.0 / w, 1080.0 / h)
        fun align(v: Double) = (Math.round(v / 16.0) * 16).toInt().coerceAtLeast(16)
        return align(w * scale) to align(h * scale)
    }

    /** The shape to give the display's own [width] × [height] pictures so they fill the screen. */
    fun forFrames(width: Int, height: Int): Pair<Int, Int> {
        val (w, h) = screen
        return fill(width, height, w, h, kms)
    }

    /** The head unit sends [width] × [height]; true when that calls for another shape than the one in use. */
    fun streamIs(width: Int, height: Int): Boolean {
        val before = forStream()
        stream = width to height
        return forStream() != before
    }

    /**
     * The pixel shape that makes a [sw] × [sh] picture fill a [w] × [h]
     * screen whose pixels kmssink takes for [kms]: kmssink then keeps
     * proportions that are the screen's own.
     */
    fun fill(sw: Int, sh: Int, w: Int, h: Int, kms: Pair<Int, Int>): Pair<Int, Int> {
        if (sw <= 0 || sh <= 0 || w <= 0 || h <= 0) return kms
        var n = kms.first.toLong() * w * sh
        var d = kms.second.toLong() * h * sw
        val g = gcd(n, d)
        n /= g
        d /= g
        return n.toInt() to d.toInt()
    }

    private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

    /**
     * The physical size the first connected output's EDID gives (bytes 21 and
     * 22, in centimetres), in millimetres as DRM hands it to kmssink; null
     * when none is connected or it gives none (a projector, composite).
     */
    fun reportedSizeMm(drm: File): Pair<Int, Int>? {
        val outputs = drm.listFiles { f -> f.isDirectory && f.name.startsWith("card") && '-' in f.name }
            ?.sortedBy { if ("HDMI" in it.name) 0 else 1 }
            ?: return null
        for (output in outputs) {
            val status = runCatching { File(output, "status").readText().trim() }.getOrNull()
            if (status != "connected") continue
            val edid = runCatching { File(output, "edid").readBytes() }.getOrNull() ?: continue
            return edidSizeMm(edid)
        }
        return null
    }

    /** The size an EDID gives, in millimetres; null without one. */
    fun edidSizeMm(edid: ByteArray): Pair<Int, Int>? {
        if (edid.size < 128 || edid[0] != 0.toByte() || edid[1] != 0xFF.toByte()) return null
        val w = edid[21].toInt() and 0xFF
        val h = edid[22].toInt() and 0xFF
        if (w == 0 || h == 0) return null
        return w * 10 to h * 10
    }

    /**
     * GStreamer's own reckoning (`gst_video_calculate_device_ratio`, which
     * kmssink uses): the pixel shape from the size, snapped to the nearest of
     * a few known ones.
     */
    fun deviceRatio(width: Int, height: Int, widthMm: Int, heightMm: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0 || widthMm <= 0 || heightMm <= 0) return 1 to 1
        val ratio = (widthMm.toDouble() * height) / (heightMm.toDouble() * width)
        return KNOWN.minBy { (n, d) -> kotlin.math.abs(ratio - n.toDouble() / d) }
    }

    private val KNOWN = listOf(1 to 1, 16 to 15, 11 to 10, 54 to 59, 64 to 45, 45 to 64)
}
