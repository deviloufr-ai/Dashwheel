package com.openauto.dash.carphoto

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The driver's own car from three photos (side, front, back), each already cut
 * out: a transparent PNG, or the car on a flat background (the Gemini app
 * gives it on plain black). Builds what a car pack needs: the side picture
 * levelled, nose to the right, with its wheel centres, and a view from above
 * (nose up) put together from the three outlines and coloured from the side
 * photo. Plain image maths on ARGB int arrays, no Android, so the launcher and
 * the companion can both run it and it is tested on the JVM.
 *
 * Ported from the Python prototype (hull_topview.py): all lengths are "side
 * pixels"; the front and back outlines are scaled so their height matches the
 * side one, the one measure all three photos share.
 */

/** A picture as ARGB ints (not premultiplied), row by row. */
class Argb(val w: Int, val h: Int, val px: IntArray = IntArray(w * h)) {
    init { require(px.size == w * h) { "size" } }
}

/** A point as fractions of a picture's width and height. */
data class CarPoint(val x: Float, val y: Float)

/** Which way the car in the side photo points; AUTO reads it from the outline. */
enum class CarFacing { LEFT, RIGHT, AUTO }

/**
 * The side picture: levelled, nose to the right, trimmed. [wheels]: front then
 * rear wheel centre. [facedLeft]: the photo pointed left (it was mirrored).
 */
class CarSide(
    val image: Argb,
    val wheels: List<CarPoint>,
    val nose: CarPoint,
    val tail: CarPoint,
    val facedLeft: Boolean,
    val tiltDegrees: Float,
    /** Wheels actually found at the ground (2 normally); fewer means the anchors are guessed. */
    val wheelsFound: Int
)

/**
 * The view from above, nose up, trimmed to everything drawn (mirrors too).
 * [wheels]: front left, front right, rear left, rear right, as fractions of
 * [image] like [nose] and [tail]. [bodyWheels] are the same wheels as
 * fractions of the body alone (no mirrors), whose place in the picture is
 * [bodyBox] (left, top, right, bottom fractions).
 */
class CarTop(
    val image: Argb,
    val wheels: List<CarPoint>,
    val nose: CarPoint,
    val tail: CarPoint,
    val bodyWheels: List<CarPoint>,
    val bodyBox: List<Float>,
    /** The car's height in this picture's pixels: a metric scale if the real height is known. */
    val heightPx: Float,
    /** The car's middle in this picture's pixels. */
    val centrePx: CarPoint
)

/** Everything built from the photos; [back] is the back photo cleaned, kept for the reverse view. */
class CarPhotoResult(val name: String, val side: CarSide, val top: CarTop?, val back: Argb?) {

    /** The pictures of a car pack by file name, for the caller to encode as PNG. */
    fun packEntries(): Map<String, Argb> = LinkedHashMap<String, Argb>().apply {
        put("side.png", side.image)
        top?.let { put("top.png", it.image) }
        back?.let { put("back.png", it) }
    }

    /** car.json as the launcher's pack import reads it (anchors as fractions of each picture). */
    fun toCarJson(): String = buildJsonObject {
        put("v", 1)
        put("name", name)
        putJsonObject("views") {
            put("side", "side.png")
            if (top != null) put("top", "top.png")
            if (back != null) put("back", "back.png")
        }
        putJsonObject("anchors") {
            putJsonObject("side") {
                put("wheels", JsonArray(side.wheels.map(::point)))
                put("nose", point(side.nose))
                put("tail", point(side.tail))
            }
            top?.let { t ->
                putJsonObject("top") {
                    put("wheels", JsonArray(t.wheels.map(::point)))
                    put("nose", point(t.nose))
                    put("tail", point(t.tail))
                }
                putJsonObject("topBody") {
                    put("wheels", JsonArray(t.bodyWheels.map(::point)))
                    put("box", JsonArray(t.bodyBox.map { JsonPrimitive(round4(it)) }))
                }
            }
        }
    }.toString()

    private fun point(p: CarPoint) = JsonArray(listOf(JsonPrimitive(round4(p.x)), JsonPrimitive(round4(p.y))))

    private fun round4(v: Float): Double = (v * 10000.0).roundToInt() / 10000.0
}

object CarPhotoKit {
    /** Photos are worked on at most this long a side: enough for a tile, quick on a head unit. */
    const val MAX_SIDE = 1600

    /** The view from above is built from a side outline this long (its picture is about as tall). */
    const val TOP_LENGTH = 900

    /** A big photo is first searched for the car this small. */
    private const val LOCATE_SIDE = 800

    /** A flat background: border-connected pixels this close to the border's colour (0-255 RGB). */
    private const val KEY_DIST = 38

    /** ...and at least this close, on a perfectly flat one. */
    private const val KEY_MIN = 12

    /** Along a keyed outline this many pixels deep, edge pixels are part background... */
    private const val MATTE_BAND = 2

    /** ...fully car once this much further from the background's colour than the key... */
    private const val MATTE_RANGE = 70.0

    /** ...and dropped when less than this share of them is car. */
    private const val MATTE_DROP = 0.3

    /**
     * The whole car from its photos. [front] or [back] may be missing (the
     * other stands in for it); without both there is no view from above built
     * from the outlines. [above]: a picture of the car from directly above
     * (one the Gemini app drew from the photos, say), used as the view from
     * above instead, its wheels placed from the side photo ([topFrom]).
     */
    fun build(
        side: Argb,
        front: Argb?,
        back: Argb?,
        facing: CarFacing = CarFacing.AUTO,
        name: String = "",
        mirrors: Boolean = true,
        above: Argb? = null
    ): CarPhotoResult {
        val levelled = levelledSide(side, facing)
        val sideOut = sideOf(levelled)
        val frontCut = front?.let { cutOf(it) }
        val backCut = back?.let { cutOf(it) }
        val built = if (frontCut != null || backCut != null) {
            val small = if (levelled.cut.w > TOP_LENGTH) trim(scale(levelled.cut, TOP_LENGTH.toDouble() / levelled.cut.w)) else levelled.cut
            val s = analyseSide(small, levelled.tilt)
            val f = analyseEnd(frontCut ?: backCut!!, s.h)
            val b = if (backCut != null) analyseEnd(backCut, s.h) else f
            topView(small, s, f, b, mirrors)
        } else null
        val top = above?.let { topFrom(it, sideOut, built) } ?: built
        return CarPhotoResult(name, sideOut, top, backCut?.let { picture(it) })
    }

    /**
     * A picture of the car from above as its view from above: cut out, turned
     * nose up (the red rear lights go to the bottom), its wheels placed along
     * the car where the side photo has them and across it where the view
     * built from the outlines has them ([built]), else just inside the body.
     */
    fun topFrom(above: Argb, side: CarSide, built: CarTop? = null): CarTop {
        var c = cutOf(above)
        if (c.w > c.h) c = turn(c, quarter = true)
        if (redShare(c, top = true) > 1.5 * redShare(c, top = false) + 0.002) c = turn(c, quarter = false)
        val img = picture(c)
        // the body's sides: the middle of each row's extent, so mirrors (short, wide rows) don't count
        val lefts = ArrayList<Int>()
        val rights = ArrayList<Int>()
        var r0 = c.h
        var r1 = -1
        for (y in 0 until c.h) {
            var a = -1
            var b = -1
            for (x in 0 until c.w) if (c.m[y * c.w + x]) { if (a < 0) a = x; b = x }
            if (a < 0) continue
            if (y < r0) r0 = y
            r1 = y
            if (y > c.h * 0.15 && y < c.h * 0.85) { lefts.add(a); rights.add(b) }
        }
        require(r1 > r0 && lefts.isNotEmpty()) { "no car found in the picture" }
        lefts.sort()
        rights.sort()
        val bl = lefts[lefts.size / 2].toFloat()
        val br = rights[rights.size / 2] + 1f
        val bt = r0.toFloat()
        val bb = r1 + 1f
        // along the car: as in the side picture (nose right there, up here)
        val span = side.nose.x - side.tail.x
        fun along(w: CarPoint) = ((side.nose.x - w.x) / span).coerceIn(0f, 1f)
        val front = along(side.wheels[0])
        val rear = along(side.wheels.getOrElse(1) { side.wheels[0] })
        // across it: the built view's track, else wheels a tenth in from each side
        val inL = built?.bodyWheels?.get(0)?.x ?: 0.1f
        val inR = built?.bodyWheels?.get(1)?.x ?: 0.9f
        val bodyWheels = listOf(
            CarPoint(inL, front), CarPoint(inR, front), CarPoint(inL, rear), CarPoint(inR, rear)
        )
        fun inPicture(q: CarPoint) = CarPoint((bl + q.x * (br - bl)) / c.w, (bt + q.y * (bb - bt)) / c.h)
        val mid = (bl + br) / 2 / c.w
        return CarTop(
            img,
            bodyWheels.map(::inPicture),
            CarPoint(mid, bt / c.h), CarPoint(mid, bb / c.h),
            bodyWheels,
            listOf(bl / c.w, bt / c.h, br / c.w, bb / c.h),
            (bb - bt) * side.image.h / side.image.w.toFloat(),
            CarPoint((bl + br) / 2, (bt + bb) / 2)
        )
    }

    /** The share of the car's pixels in its top or bottom fifth that are rear-light red. */
    private fun redShare(c: Cut, top: Boolean): Double {
        val ys = if (top) 0 until c.h / 5 else (c.h - c.h / 5) until c.h
        var red = 0
        var all = 0
        for (y in ys) for (x in 0 until c.w) {
            val i = y * c.w + x
            if (!c.m[i]) continue
            all++
            val p = c.px[i]
            val r = (p shr 16) and 255
            val g = (p shr 8) and 255
            val b = p and 255
            if (r > 120 && r > 1.8 * g && r > 1.8 * b) red++
        }
        return if (all == 0) 0.0 else red.toDouble() / all
    }

    /** Turned a quarter clockwise, or half way round. */
    private fun turn(c: Cut, quarter: Boolean): Cut {
        val w = if (quarter) c.h else c.w
        val h = if (quarter) c.w else c.h
        val px = IntArray(w * h)
        val m = BooleanArray(w * h)
        for (y in 0 until c.h) for (x in 0 until c.w) {
            val o = if (quarter) x * w + (c.h - 1 - y) else (c.h - 1 - y) * c.w + (c.w - 1 - x)
            px[o] = c.px[y * c.w + x]
            m[o] = c.m[y * c.w + x]
        }
        return Cut(w, h, px, m)
    }

    /** The side picture alone. */
    fun side(photo: Argb, facing: CarFacing = CarFacing.AUTO): CarSide = sideOf(levelledSide(photo, facing))

    /** A photo with its background keyed out (unless it has its own) and trimmed to the car. */
    fun cutOut(photo: Argb): Argb = picture(cutOf(photo))

    /** A zip of [entries] (a car pack once the pictures are encoded). */
    /** Which photo picked is which, as indexes into the list picked ([sortShots]). */
    data class Shots(val side: Int, val front: Int?, val back: Int?, val above: Int?)

    /**
     * Which of several photos picked at once is the side, the front, the back
     * and the view from above: by file name first (in the launcher's
     * languages), else by shape: a tall one is the view from above, the
     * widest the side, and the others follow in the order picked. [aspects]
     * are each photo's width over height.
     */
    fun sortShots(names: List<String>, aspects: List<Float>): Shots {
        require(names.isNotEmpty() && names.size == aspects.size) { "no photos" }
        val left = names.indices.toMutableList()
        fun named(words: List<String>) = left.firstOrNull { i ->
            val n = names[i].lowercase()
            words.any { w -> Regex("(^|[^\\p{L}])$w($|[^\\p{L}])").containsMatchIn(n) }
        }?.also { left.remove(it) }
        val above = named(ABOVE_WORDS)
        val side = named(SIDE_WORDS)
        val front = named(FRONT_WORDS)
        val back = named(BACK_WORDS)
        val a = above ?: left.filter { aspects[it] < TALL }.minByOrNull { aspects[it] }?.takeIf { left.size > 1 }?.also { left.remove(it) }
        val s = side ?: left.maxByOrNull { aspects[it] }!!.also { left.remove(it) }
        val f = front ?: left.firstOrNull()?.also { left.remove(it) }
        val b = back ?: left.firstOrNull()
        return Shots(s, f, b, a)
    }

    /** A picture this much taller than wide is the car seen from above (nose up). */
    private const val TALL = 0.75f

    // Words a photo's name may carry, in the launcher's 8 languages (accents both ways).
    private val SIDE_WORDS = listOf("side", "cote", "côté", "profil", "seite", "lateral", "lato", "lado", "zijkant", "zij", "bok")
    private val FRONT_WORDS = listOf("front", "avant", "face", "vorne", "delante", "frontal", "davanti", "frente", "voorkant", "voor", "przod", "przód")
    private val BACK_WORDS = listOf("back", "rear", "arriere", "arrière", "hinten", "heck", "detras", "detrás", "trasera", "dietro", "retro", "traseira", "achterkant", "achter", "tyl", "tył")
    private val ABOVE_WORDS = listOf("top", "above", "dessus", "haut", "oben", "draufsicht", "arriba", "encima", "sopra", "alto", "cima", "boven", "bovenaanzicht", "gora", "góra", "gory", "góry")

    fun zip(entries: Map<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { z ->
            for ((name, data) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(data)
                z.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    // --- Cut-outs ------------------------------------------------------------------------

    /** Colours and the car's mask, the same size. */
    internal class Cut(val w: Int, val h: Int, val px: IntArray, val m: BooleanArray)

    private class Levelled(val cut: Cut, val tilt: Float, val facedLeft: Boolean)

    private fun cutOf(photo: Argb): Cut {
        if (max(photo.w, photo.h) <= MAX_SIDE) return trim(matte(photo, maskOf(photo)))
        // a big photo: find the car on a small copy first, then cut it from the photo
        // around where it is (a phone screenshot keeps the car's detail)
        val probe = fit(photo, LOCATE_SIDE)
        val m = maskOf(probe)
        val f = photo.w.toDouble() / probe.w
        var x0 = probe.w; var x1 = -1; var y0 = probe.h; var y1 = -1
        for (y in 0 until probe.h) for (x in 0 until probe.w) if (m[y * probe.w + x]) {
            if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
        }
        var part = photo
        if (x1 >= 0 && max(x1 - x0, y1 - y0) < 0.8 * max(probe.w, probe.h)) {
            val pad = max(8.0, 0.04 * max(x1 - x0, y1 - y0)) * f
            val cx0 = max(0, (x0 * f - pad).toInt()); val cx1 = min(photo.w, ((x1 + 1) * f + pad).toInt())
            val cy0 = max(0, (y0 * f - pad).toInt()); val cy1 = min(photo.h, ((y1 + 1) * f + pad).toInt())
            part = Argb(cx1 - cx0, cy1 - cy0)
            for (y in cy0 until cy1) System.arraycopy(photo.px, y * photo.w + cx0, part.px, (y - cy0) * part.w, part.w)
        }
        val img = fit(part, MAX_SIDE)
        return trim(matte(img, maskOf(img)))
    }

    /**
     * The car's outline cleaned of its background: a keyed photo's edge pixels
     * are part car, part background (black from Gemini), which shows as a dark
     * fringe on a light tile. Along the outline each pixel's share of car is
     * read from how far it is from the background's colour; that share of
     * background is taken back out of its colour, and mostly-background ones
     * drop out. A photo with its own transparency is left as it is.
     */
    private fun matte(img: Argb, m: BooleanArray): Cut {
        val w = img.w
        val h = img.h
        var clear = 0
        for (p in img.px) if ((p ushr 24) < 128) clear++
        if (clear > w * h / 100) return Cut(w, h, img.px, m)
        val bg = borderMedian(img)
        val br = (bg shr 16) and 255
        val bgG = (bg shr 8) and 255
        val bb = bg and 255
        val lo = keyLimit(img, br, bgG, bb).toDouble()
        val hi = lo + MATTE_RANGE
        val inner = erode(m, w, h, MATTE_BAND, MATTE_BAND)
        val px = img.px.copyOf()
        val out = m.copyOf()
        for (i in 0 until w * h) {
            if (!m[i] || inner[i]) continue
            val p = px[i]
            val dr = ((p shr 16) and 255) - br
            val dg = ((p shr 8) and 255) - bgG
            val db = (p and 255) - bb
            val a = ((sqrt((dr * dr + dg * dg + db * db).toDouble()) - lo) / (hi - lo)).coerceIn(0.0, 1.0)
            if (a < MATTE_DROP) { out[i] = false; continue }
            if (a >= 1.0) continue
            val r = (br + dr / a).roundToInt().coerceIn(0, 255)
            val g = (bgG + dg / a).roundToInt().coerceIn(0, 255)
            val b = (bb + db / a).roundToInt().coerceIn(0, 255)
            px[i] = (p and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
        }
        return Cut(w, h, px, out)
    }

    /**
     * The car's pixels: the photo's own transparency when it has some, else
     * the flat background keyed out from the border. Only the biggest blob is
     * kept (with parts beside it), so a screenshot's status bar or buttons on
     * the background drop out; holes inside (dark glass, arch liners) are filled.
     */
    internal fun maskOf(img: Argb): BooleanArray {
        val w = img.w
        val h = img.h
        val n = w * h
        var clear = 0
        for (p in img.px) if ((p ushr 24) < 128) clear++
        var m: BooleanArray
        if (clear > n / 100) {
            m = BooleanArray(n) { (img.px[it] ushr 24) > 128 }
            m = keepCar(m, w, h)
        } else {
            val bg = borderMedian(img)
            val br = (bg shr 16) and 255
            val bgG = (bg shr 8) and 255
            val bb = bg and 255
            val lim = keyLimit(img, br, bgG, bb).let { it * it }
            val near = BooleanArray(n)
            for (i in 0 until n) {
                val p = img.px[i]
                val dr = ((p shr 16) and 255) - br
                val dg = ((p shr 8) and 255) - bgG
                val db = (p and 255) - bb
                near[i] = dr * dr + dg * dg + db * db < lim
            }
            val back = flood(near, w, h, true)
            m = BooleanArray(n) { !back[it] }
            m = dilate(erode(m, w, h, 1, 1), w, h, 1, 1)
            m = keepCar(m, w, h)
            val r = max(2, (max(w, h) * 0.004).roundToInt())
            m = erode(dilate(m, w, h, r, r), w, h, r, r)
        }
        fillHoles(m, w, h)
        return m
    }

    /**
     * How far from the border's colour still counts as background: tight on
     * a clean flat background (so near-black tyres on black stay), up to
     * [KEY_DIST] on a noisy one (a JPEG).
     */
    private fun keyLimit(img: Argb, r: Int, g: Int, b: Int): Int {
        val d = ArrayList<Int>()
        fun add(p: Int) {
            val dr = ((p shr 16) and 255) - r
            val dg = ((p shr 8) and 255) - g
            val db = (p and 255) - b
            d.add(sqrt((dr * dr + dg * dg + db * db).toDouble()).roundToInt())
        }
        for (x in 0 until img.w) { add(img.px[x]); add(img.px[(img.h - 1) * img.w + x]) }
        for (y in 1 until img.h - 1) { add(img.px[y * img.w]); add(img.px[y * img.w + img.w - 1]) }
        d.sort()
        val noise = d[(d.size * 0.6).toInt().coerceAtMost(d.size - 1)]
        return (KEY_MIN + 3 * noise).coerceAtMost(KEY_DIST)
    }

    /** The middle colour of the picture's border, channel by channel. */
    private fun borderMedian(img: Argb): Int {
        val hist = Array(3) { IntArray(256) }
        var count = 0
        fun add(p: Int) {
            hist[0][(p shr 16) and 255]++
            hist[1][(p shr 8) and 255]++
            hist[2][p and 255]++
            count++
        }
        for (x in 0 until img.w) { add(img.px[x]); add(img.px[(img.h - 1) * img.w + x]) }
        for (y in 1 until img.h - 1) { add(img.px[y * img.w]); add(img.px[y * img.w + img.w - 1]) }
        val c = IntArray(3)
        for (k in 0 until 3) {
            var acc = 0
            for (v in 0 until 256) { acc += hist[k][v]; if (acc * 2 >= count) { c[k] = v; break } }
        }
        return (0xFF shl 24) or (c[0] shl 16) or (c[1] shl 8) or c[2]
    }

    /** The picture of a cut: transparent outside, a one-pixel soft edge. */
    private fun picture(c: Cut): Argb {
        val out = IntArray(c.w * c.h)
        for (y in 0 until c.h) for (x in 0 until c.w) {
            val i = y * c.w + x
            if (!c.m[i]) continue
            val edge = x == 0 || y == 0 || x == c.w - 1 || y == c.h - 1 ||
                !c.m[i - 1] || !c.m[i + 1] || !c.m[i - c.w] || !c.m[i + c.w]
            out[i] = ((if (edge) 150 else 255) shl 24) or (c.px[i] and 0xFFFFFF)
        }
        return Argb(c.w, c.h, out)
    }

    // --- The side ------------------------------------------------------------------------

    private fun levelledSide(photo: Argb, facing: CarFacing): Levelled {
        val (cut, tilt) = level(cutOf(photo))
        val left = when (facing) {
            CarFacing.LEFT -> true
            CarFacing.RIGHT -> false
            CarFacing.AUTO -> facesLeft(cut)
        }
        return Levelled(if (left) flip(cut) else cut, tilt, left)
    }

    private fun sideOf(l: Levelled): CarSide {
        val c = l.cut
        val s = analyseSide(c, l.tilt)
        val hf = c.h.toFloat()
        val lf = c.w.toFloat()
        fun wheel(x: Double) = CarPoint((x / lf).toFloat(), ((c.h - s.rWheel) / hf).toFloat())
        return CarSide(
            picture(c),
            listOf(wheel(s.xFront), wheel(s.xRear)),
            CarPoint(1f, middleOf(c, c.w - 1) / hf),
            CarPoint(0f, middleOf(c, 0) / hf),
            l.facedLeft, l.tilt, s.wheelsFound
        )
    }

    /** The middle of the car's pixels in column [x]. */
    private fun middleOf(c: Cut, x: Int): Float {
        var t = -1
        var b = -1
        for (y in 0 until c.h) if (c.m[y * c.w + x]) { if (t < 0) t = y; b = y }
        return if (t < 0) c.h / 2f else (t + b + 1) / 2f
    }

    /** Turned so both tyre bottoms sit on the same row (phone shots are tilted). */
    private fun level(c: Cut): Pair<Cut, Float> {
        val w = c.w
        val h = c.h
        val bot = IntArray(w) { -1 }
        for (y in 0 until h) for (x in 0 until w) if (c.m[y * w + x]) bot[x] = y
        fun lowest(from: Int, to: Int): Pair<Int, Int> {
            var bx = from
            var by = -1
            for (x in from until to) if (bot[x] > by) { by = bot[x]; bx = x }
            return bx to by
        }
        val (xa, ya) = lowest(0, w / 2)
        val (xb, yb) = lowest(w / 2, w)
        val ang = Math.toDegrees(atan2((yb - ya).toDouble(), (xb - xa).toDouble()))
        if (abs(ang) <= 0.2) return c to ang.toFloat()
        val pad = (0.1 * w).toInt()
        val w2 = w + 2 * pad
        val h2 = h + 2 * pad
        val cx = w2 / 2.0
        val cy = h2 / 2.0
        val a = cos(Math.toRadians(ang))
        val b = sin(Math.toRadians(ang))
        val px = IntArray(w2 * h2)
        val m = BooleanArray(w2 * h2)
        for (y in 0 until h2) for (x in 0 until w2) {
            val sx = a * (x - cx) - b * (y - cy) + cx - pad
            val sy = b * (x - cx) + a * (y - cy) + cy - pad
            val nx = sx.roundToInt()
            val ny = sy.roundToInt()
            val o = y * w2 + x
            if (nx in 0 until w && ny in 0 until h) m[o] = c.m[ny * w + nx]
            if (m[o]) px[o] = bilinear(c, sx, sy)
        }
        return trim(Cut(w2, h2, px, m)) to ang.toFloat()
    }

    private fun bilinear(c: Cut, sx: Double, sy: Double): Int {
        val x0 = sx.toInt().coerceIn(0, c.w - 1)
        val y0 = sy.toInt().coerceIn(0, c.h - 1)
        val x1 = min(x0 + 1, c.w - 1)
        val y1 = min(y0 + 1, c.h - 1)
        val fx = (sx - x0).coerceIn(0.0, 1.0)
        val fy = (sy - y0).coerceIn(0.0, 1.0)
        val p00 = c.px[y0 * c.w + x0]
        val p10 = c.px[y0 * c.w + x1]
        val p01 = c.px[y1 * c.w + x0]
        val p11 = c.px[y1 * c.w + x1]
        val w00 = (1 - fx) * (1 - fy)
        val w10 = fx * (1 - fy)
        val w01 = (1 - fx) * fy
        val w11 = fx * fy
        var out = 0xFF shl 24
        var sh = 16
        while (sh >= 0) {
            val v = ((p00 shr sh) and 255) * w00 + ((p10 shr sh) and 255) * w10 + ((p01 shr sh) and 255) * w01 + ((p11 shr sh) and 255) * w11
            out = out or ((v + 0.5).toInt().coerceIn(0, 255) shl sh)
            sh -= 8
        }
        return out
    }

    /**
     * Whether the nose points left: the front is the end with the long slope
     * from the roof down to the bonnet (bonnet plus windscreen), the back
     * drops steeply (a hatch, or a short boot).
     */
    internal fun facesLeft(c: Cut): Boolean {
        val zt = IntArray(c.w) { -1 }
        for (x in 0 until c.w) for (y in 0 until c.h) if (c.m[y * c.w + x]) { zt[x] = c.h - 1 - y; break }
        val roof = zt.max()
        val lim = 0.85 * roof
        val first = zt.indexOfFirst { it >= lim }
        val last = zt.indexOfLast { it >= lim }
        return first > (c.w - 1 - last)
    }

    private class SideShape(
        val h: Int, val l: Int, val top: IntArray, val bot: IntArray,
        val xRear: Double, val xFront: Double, val rWheel: Double, val wheelsFound: Int, val tilt: Float
    )

    /** The outline per column and the two wheels (nose on the right). */
    private fun analyseSide(c: Cut, tilt: Float): SideShape {
        val hh = c.h
        val l = c.w
        val top = IntArray(l) { hh }
        val bot = IntArray(l) { -1 }
        for (y in 0 until hh) for (x in 0 until l) if (c.m[y * l + x]) {
            if (top[x] == hh) top[x] = y
            bot[x] = y
        }
        val ground = hh - 1
        // wheels: the runs in a thin band just above the ground
        var picked: List<IntArray> = emptyList()
        val lift0 = (0.02 * hh).toInt()
        for (lift in lift0..max(lift0, (0.10 * hh).toInt())) {
            val rr = runs(c.m, l, ground - lift)
            if (rr.size >= 2) { picked = rr.sortedByDescending { it[1] - it[0] }.take(2).sortedBy { it[0] }; break }
        }
        val centres = DoubleArray(2)
        val radii = DoubleArray(2)
        if (picked.size == 2) {
            for (k in 0..1) {
                val cx = (picked[k][0] + picked[k][1]) / 2.0
                centres[k] = cx
                // circle fit: half-width a at height z above the ground -> r = (a^2+z^2)/(2z)
                val rs = ArrayList<Double>()
                for (z in max(1, (0.012 * hh).toInt()) until (0.07 * hh).toInt()) {
                    val run = runs(c.m, l, ground - z).firstOrNull { it[0] <= cx && cx <= it[1] } ?: continue
                    val a = (run[1] - run[0]) / 2.0
                    rs.add((a * a + z * z) / (2.0 * z))
                }
                radii[k] = if (rs.isEmpty()) 0.18 * hh else median(rs.toDoubleArray())
            }
        } else {
            centres[0] = 0.2 * l; centres[1] = 0.8 * l
            radii[0] = 0.18 * hh; radii[1] = 0.18 * hh
        }
        return SideShape(hh, l, top, bot, centres[0], centres[1], (radii[0] + radii[1]) / 2, picked.size, tilt)
    }

    /** [start, end) runs of the mask on row [y]. */
    private fun runs(m: BooleanArray, w: Int, y: Int): List<IntArray> {
        val out = ArrayList<IntArray>()
        if (y < 0 || y * w >= m.size) return out
        var x = 0
        val base = y * w
        while (x < w) {
            if (m[base + x]) {
                val s = x
                while (x < w && m[base + x]) x++
                out.add(intArrayOf(s, x))
            } else x++
        }
        return out
    }

    // --- Front and back ------------------------------------------------------------------

    private class Mirror(val z0: Double, val z1: Double, val reach: Double)

    private class EndShape(val hw: DoubleArray, val track: Double, val tyreW: Double, val mirror: Mirror?, val w: Double)

    /** Half-width per height above the ground (side pixels), the tyres' track and the mirrors. */
    private fun analyseEnd(c: Cut, hs: Int): EndShape {
        val hf = c.h
        val wf = c.w
        val s = hs.toDouble() / hf
        // mirrors and other thin side bumps: a vertical opening drops anything shorter than
        // ~15 % of the car height (mirrors are ~6 %), keeps the body and the tyres
        val k = max(3, (0.15 * hf).toInt())
        val body = BooleanArray(c.m.size)
        for (x in 0 until wf) {
            var y = 0
            while (y < hf) {
                if (c.m[y * wf + x]) {
                    val s0 = y
                    while (y < hf && c.m[y * wf + x]) y++
                    if (y - s0 >= k) for (yy in s0 until y) body[yy * wf + x] = true
                } else y++
            }
        }
        val rr = runs(c.m, wf, hf - 1 - max(1, (0.02 * hf).toInt()))
        val lt: IntArray
        val rt: IntArray
        if (rr.size >= 2) { lt = rr.first(); rt = rr.last() } else {
            val all = rr.firstOrNull() ?: intArrayOf(0, wf)
            val tw = max(1, ((all[1] - all[0]) * 0.12).toInt())
            lt = intArrayOf(all[0], all[0] + tw); rt = intArrayOf(all[1] - tw, all[1])
        }
        val cxl = (lt[0] + lt[1]) / 2.0
        val cxr = (rt[0] + rt[1]) / 2.0
        val centre = (cxl + cxr) / 2
        val track = (cxr - cxl) * s
        val tyreW = ((lt[1] - lt[0]) + (rt[1] - rt[0])) / 2.0 * s
        val rowMin = IntArray(hf) { -1 }
        val rowMax = IntArray(hf) { -1 }
        var bw0 = wf
        var bw1 = -1
        for (y in 0 until hf) for (x in 0 until wf) if (body[y * wf + x]) {
            if (rowMin[y] < 0) rowMin[y] = x
            rowMax[y] = x
            if (x < bw0) bw0 = x
            if (x > bw1) bw1 = x
        }
        val hw = DoubleArray(hs + 1)
        for (zs in 0..hs) {
            val row = (hf - 1 - zs / s).roundToInt()
            if (row in 0 until hf && rowMin[row] >= 0) hw[zs] = max(centre - rowMin[row], rowMax[row] + 1 - centre) * s
        }
        // mirrors: small blobs left over by the opening, in the upper part, outside the body
        val extra = BooleanArray(c.m.size) { c.m[it] && !body[it] }
        val bl = blobs(extra, wf, hf)
        val cands = ArrayList<IntArray>()
        for (i in 1..bl.count) {
            val x = bl.x0[i]; val y = bl.y0[i]
            val w = bl.x1[i] - x + 1; val h = bl.y1[i] - y + 1
            if (bl.area[i] < 0.0003 * c.m.size || h > 0.15 * hf || (hf - 1 - (y + h)) < 0.4 * hf) continue
            if (x >= bw0 && x + w <= bw1 + 1) continue
            cands.add(intArrayOf(bl.area[i], x, y, w, h))
        }
        var mirror: Mirror? = null
        if (cands.isNotEmpty()) {
            val two = cands.sortedByDescending { it[0] }.take(2)
            val y0 = two.minOf { it[2] }; val y1 = two.maxOf { it[2] + it[4] }
            val x0 = two.minOf { it[1] }; val x1 = two.maxOf { it[1] + it[3] }
            mirror = Mirror((hf - y1) * s, (hf - y0) * s, max(centre - x0, x1 - centre) * s)
        }
        return EndShape(hw, track, tyreW, mirror, hw.max() * 2)
    }

    // --- The view from above ------------------------------------------------------------

    /**
     * Visual hull: for each point along the car and height above the ground
     * inside the side outline, a half-width blended from the back outline (at
     * the rear axle) to the front one (at the front axle). Seen from above,
     * each point across shows the highest height still that wide. Coloured
     * in three bands read off the side photo: body, glass and roof.
     */
    private fun topView(c: Cut, s: SideShape, f: EndShape, b: EndShape, mirrors: Boolean): CarTop {
        val hh = s.h
        val l = s.l
        // plan-view rounding of the ends: three outlines can't see it, so an elliptic corner
        // whose length follows the overhang
        val wHalf = max(f.w, b.w) / 2
        val ovF = l - s.xFront
        val ovR = s.xRear
        fun corner(d: Double, rx: Double, ry: Double): Double {
            val u = (1 - d / max(rx, 1e-3)).coerceIn(0.0, 1.0)
            return (wHalf - ry) + ry * sqrt(1 - u * u)
        }
        val mirror = if (mirrors) f.mirror else null
        val wi = ceil(max(wHalf, mirror?.reach ?: 0.0)).toInt() + 4
        val wn = 2 * wi + 1
        val hm = FloatArray(l * wn) { -1f }
        val mz = FloatArray(hh)
        for (x in 0 until l) {
            val t = smoothstep(s.xRear, s.xFront, x.toDouble())
            val cap = min(corner((l - 1 - x).toDouble(), 0.75 * ovF, 0.45 * wHalf), corner(x.toDouble(), 0.3 * ovR, 0.22 * wHalf))
            var any = false
            for (z in 0 until hh) {
                val inside = c.m[(hh - 1 - z) * l + x]
                val v = if (inside) min((1 - t) * b.hw[z] + t * f.hw[z], cap) else 0.0
                mz[z] = v.toFloat()
                if (v > 0) any = true
            }
            if (!any) continue
            for (z in hh - 2 downTo 0) if (mz[z + 1] > mz[z]) mz[z] = mz[z + 1]   // max over z' >= z
            // the highest z whose half-width covers |y|: Mz is non-increasing, count the prefix
            var cnt = hh
            for (d in 0..wi) {
                val ya = max(d.toDouble(), 0.5)
                while (cnt > 0 && mz[cnt - 1] < ya) cnt--
                val v = (cnt - 1).toFloat()
                hm[x * wn + wi + d] = v
                hm[x * wn + wi - d] = v
            }
        }
        return render(c, s, f, hm, wi, wn, wHalf, mirror)
    }

    private fun render(c: Cut, s: SideShape, f: EndShape, hm: FloatArray, wi: Int, wn: Int, wHalf: Double, mirror: Mirror?): CarTop {
        val hh = s.h
        val l = s.l
        val n = l * wn
        val sideN = hh * l
        // ---- colours: body / glass / roof read off the side photo
        val r = FloatArray(sideN); val g = FloatArray(sideN); val bl = FloatArray(sideN)
        median5(c.px, l, hh, r, g, bl)
        val lab = toLab(r, g, bl)
        val zt = IntArray(l) { hh - 1 - s.top[it] }
        val zb = IntArray(l) { hh - 1 - s.bot[it] }
        val xa = s.xRear.toInt()
        val xb = s.xFront.toInt()
        // body colour: door band 28-42 % of the height, between the axles
        val band = BooleanArray(sideN)
        var bandN = 0
        for (y in (hh * 0.58).toInt() until min(hh, (hh * 0.72).toInt())) for (x in max(0, xa) until min(l, xb)) {
            val i = y * l + x
            if (c.m[i]) { band[i] = true; bandN++ }
        }
        if (bandN == 0) for (i in 0 until sideN) if (c.m[i]) { band[i] = true; bandN++ }
        val bodyLab = FloatArray(3) { k -> medianOf(lab[k], band) }
        val dist = FloatArray(sideN)
        for (i in 0 until sideN) {
            val d0 = lab[0][i] - bodyLab[0]; val d1 = lab[1][i] - bodyLab[1]; val d2 = lab[2][i] - bodyLab[2]
            dist[i] = sqrt(d0 * d0 + d1 * d1 + d2 * d2)
        }
        val bandDist = FloatArray(bandN).also { var k = 0; for (i in 0 until sideN) if (band[i]) it[k++] = dist[i] }
        val thr = max(28.0, 1.3 * percentile(bandDist, 0.9))   // adapts to reflections on real paint
        var notBody = BooleanArray(sideN) { dist[it] > thr }
        notBody = dilate(erode(notBody, l, hh, 2, 2), l, hh, 2, 2)
        for (i in 0 until sideN) notBody[i] = notBody[i] && c.m[i]
        // belt line per column: lowest z above 45 % H where a >= 5 % H run of not-body starts
        val belt = DoubleArray(l) { Double.POSITIVE_INFINITY }
        val run = max(3, (0.05 * hh).toInt())
        val z0 = (0.45 * hh).toInt()
        for (x in 0 until l) {
            for (z in z0 until max(z0, zt[x] - 1)) {
                var all = true
                for (zz in z until min(z + run, hh)) if (!notBody[(hh - 1 - zz) * l + x]) { all = false; break }
                if (all) { belt[x] = z.toDouble(); break }
            }
        }
        val finite = belt.indices.filter { belt[it].isFinite() }
        val bcols: List<Int>
        if (finite.size > 0.2 * l) {
            val bm = median(DoubleArray(finite.size) { belt[finite[it]] })
            for (x in finite) belt[x] = belt[x].coerceIn(bm - 0.04 * hh, bm + 0.04 * hh)
            // fill the gaps inside the cabin span; the bonnet and boot stay all body
            var prev = finite.first()
            for (x in finite.drop(1)) {
                for (xx in prev + 1 until x) belt[xx] = belt[prev] + (belt[x] - belt[prev]) * (xx - prev) / (x - prev)
                prev = x
            }
            // the windscreen: on to the A-pillar's foot, where the outline comes down to the belt
            val end = belt[finite.last()]
            var x = finite.last() + 1
            while (x < l && zt[x] > end + 0.02 * hh) { belt[x] = end; x++ }
            bcols = finite
        } else {                                       // dark car: glass not separable, a typical ratio
            for (x in 0 until l) belt[x] = if (zt[x] > 0.75 * hh) 0.6 * hh else Double.POSITIVE_INFINITY
            bcols = belt.indices.filter { belt[it].isFinite() }
        }
        val palBody = FloatArray(3) { k -> medianOf(arrayOf(r, g, bl)[k], band) }
        // roof: a thin strip under the top edge over the cabin; glass: between the belt and the roof
        val roofPx = ArrayList<Int>()
        val glassPx = ArrayList<Int>()
        for (bi in bcols.indices step 2) {
            val x = bcols[bi]
            val t0 = s.top[x]
            for (y in t0 + 2 until min(hh, t0 + 2 + max(2, (0.02 * hh).toInt()))) roofPx.add(y * l + x)
            val bz = belt[x]
            if (bz.isFinite()) {
                val r0 = t0 + (0.05 * hh).toInt()
                val r1 = (hh - 1 - bz).toInt() - 2
                for (y in max(0, r0) until min(hh, r1)) if (notBody[y * l + x]) glassPx.add(y * l + x)
            }
        }
        val palRoof = roofColour(roofPx, r, g, bl) ?: palBody
        val palGlass = if (glassPx.isNotEmpty()) FloatArray(3) { k -> medianAt(arrayOf(r, g, bl)[k], glassPx) }
        else FloatArray(3) { palBody[it] * 0.35f }

        // ---- shading and classes from the height map (rows = along the car, columns = across)
        val occ = BooleanArray(n) { hm[it] >= 0 }
        val flat = FloatArray(n) { if (occ[it]) hm[it] else 0f }
        val hs = blur(flat, wn, l, 1.5, 1.5)
        val lv0 = -0.25; val lv1 = 0.35; val lv2 = 1.0
        val lvn = sqrt(lv0 * lv0 + lv1 * lv1 + lv2 * lv2)
        val shade = FloatArray(n)
        val dAcross = sobel(hs, wn, l, true)
        val dAlong = sobel(hs, wn, l, false)
        for (x in 0 until l) for (j in 0 until wn) {
            val i = x * wn + j
            val gx = dAcross[i] / 8.0
            val gy = dAlong[i] / 8.0
            val nn = sqrt(gx * gx + gy * gy + 1)
            val lam = ((-gx * lv0 - gy * lv1 + lv2) / nn / lvn).coerceIn(0.0, 1.0)
            val hn = (hm[i] / hh).toDouble().coerceIn(0.0, 1.0)
            shade[i] = ((0.6 + 0.4 * lam) * (0.85 + 0.15 * hn)).toFloat()
        }
        // classify on a smoother height map so a jagged photo outline doesn't stripe the glass
        val hs2 = blur(flat, wn, l, max(1.5, 0.008 * l), max(1.5, 0.008 * l))
        val above = BooleanArray(n)
        var glass = BooleanArray(n)
        val sAlong = sobel(hs2, wn, l, false)
        val sAcross = sobel(hs2, wn, l, true)
        for (x in 0 until l) for (j in 0 until wn) {
            val i = x * wn + j
            above[i] = hm[i] > belt[x]
            if (!above[i]) continue
            val slopeX = abs(sAlong[i] / 8)
            val slopeY = abs(sAcross[i] / 8)
            val nearTop = hm[i] >= zt[x] - 0.03 * hh
            glass[i] = slopeX > 0.3 || (slopeY > 0.5 && !nearTop)
        }
        val kx = max(3, (0.012 * l).toInt()) or 1
        glass = erode(dilate(glass, wn, l, 1, kx / 2), wn, l, 1, kx / 2)
        glass = dilate(erode(glass, wn, l, 1, 1), wn, l, 1, 1)
        // body keeps a little of the photo (lights, bumpers): the side photo at (x, height)
        val sr = FloatArray(n); val sg = FloatArray(n); val sb = FloatArray(n)
        for (x in 0 until l) for (j in 0 until wn) {
            val i = x * wn + j
            val z = min(max(hm[i], (zb[x] + 2).toFloat()), (zt[x] - 2).toFloat())
            val row = (hh - 1 - z).toInt().coerceIn(0, hh - 1)
            sr[i] = r[row * l + x]; sg[i] = g[row * l + x]; sb[i] = bl[row * l + x]
        }
        val base = arrayOf(sr, sg, sb).mapIndexed { k, ch ->
            val sm = blur(ch, wn, l, 0.6, 0.006 * l)
            for (i in 0 until n) {
                val gl = glass[i] && above[i]
                sm[i] = when {
                    gl -> palGlass[k]
                    above[i] -> palRoof[k]
                    else -> 0.55f * palBody[k] + 0.45f * sm[i]
                }
            }
            blur(sm, wn, l, 0.8, 0.8)
        }
        // ---- the picture, still tail at row 0
        val img = IntArray(n)
        val tyres = BooleanArray(n)
        val yc = f.track / 2
        val tw = f.tyreW
        val rwh = s.rWheel
        for (xw in doubleArrayOf(s.xRear, s.xFront)) for (sgn in intArrayOf(-1, 1)) {
            for (x in max(0, (xw - 0.9 * rwh).toInt()) until min(l, (xw + 0.9 * rwh).toInt()))
                for (j in max(0, (wi + sgn * yc - tw / 2).toInt()) until min(wn, (wi + sgn * yc + tw / 2).toInt())) {
                    img[x * wn + j] = argb(18f, 18f, 20f)
                    tyres[x * wn + j] = true
                }
        }
        for (i in 0 until n) if (occ[i]) {
            val k = if (tyres[i]) 0.72f * shade[i] else shade[i]   // a faint hint of the tyres through the body
            img[i] = argb(base[0][i] * k, base[1][i] * k, base[2][i] * k)
        }
        if (mirror != null) {
            // mirror stubs at the A-pillar's foot
            val zc = (mirror.z0 + mirror.z1) / 2
            val cand = (0 until l).firstOrNull { it > l / 2.0 && zt[it] < zc }
            val xm = if (cand != null) cand - 0.07 * l else 0.62 * l
            val col = argb(palBody[0] * 0.8f, palBody[1] * 0.8f, palBody[2] * 0.8f)
            for (sgn in intArrayOf(-1, 1)) {
                val e0 = wi + sgn * wHalf * 0.92
                val e1 = wi + sgn * mirror.reach
                for (x in max(0, (xm - 0.02 * l).toInt()) until min(l, (xm + 0.015 * l).toInt()))
                    for (j in max(0, min(e0, e1).toInt()) until min(wn, max(e0, e1).toInt()))
                        if (img[x * wn + j] == 0) img[x * wn + j] = col
            }
        }
        // nose up, and a darker outline for crispness
        val out = IntArray(n)
        for (x in 0 until l) System.arraycopy(img, x * wn, out, (l - 1 - x) * wn, wn)
        val solid = BooleanArray(n) { out[it] != 0 }
        val inner = erode(solid, wn, l, 1, 1)
        for (i in 0 until n) if (solid[i] && !inner[i]) {
            val p = out[i]
            out[i] = argb(((p shr 16) and 255) * 0.6f, ((p shr 8) and 255) * 0.6f, (p and 255) * 0.6f)
        }
        // trimmed to what's drawn, anchors as fractions of it
        var r0 = l; var r1 = -1; var c0 = wn; var c1 = -1
        for (y in 0 until l) for (x in 0 until wn) if (solid[y * wn + x]) {
            if (y < r0) r0 = y; if (y > r1) r1 = y; if (x < c0) c0 = x; if (x > c1) c1 = x
        }
        val tw2 = c1 - c0 + 1
        val th2 = r1 - r0 + 1
        val crop = IntArray(tw2 * th2)
        for (y in 0 until th2) System.arraycopy(out, (r0 + y) * wn + c0, crop, y * tw2, tw2)
        fun p(xcar: Double, ycar: Double) = CarPoint(((wi - ycar - c0) / tw2).toFloat(), ((l - 1 - xcar - r0) / th2).toFloat())
        fun q(xcar: Double, ycar: Double) = CarPoint(((wHalf - ycar) / (2 * wHalf)).toFloat(), ((l - 1 - xcar) / l).toFloat())
        val xf = s.xFront
        val xr = s.xRear
        return CarTop(
            Argb(tw2, th2, crop),
            listOf(p(xf, yc), p(xf, -yc), p(xr, yc), p(xr, -yc)),
            p(l - 1.0, 0.0), p(0.0, 0.0),
            listOf(q(xf, yc), q(xf, -yc), q(xr, yc), q(xr, -yc)),
            listOf(
                ((wi - wHalf - c0) / tw2).toFloat(), (-r0.toDouble() / th2).toFloat(),
                ((wi + wHalf - c0) / tw2).toFloat(), ((l - r0).toDouble() / th2).toFloat()
            ),
            hh.toFloat(),
            CarPoint((wi - c0).toFloat(), ((l - 1) / 2.0 - r0).toFloat())
        )
    }

    /**
     * The roof's colour from the strip under its edge: sky reflections tint
     * and lighten it, so only the less saturated, darker pixels count.
     */
    private fun roofColour(idx: List<Int>, r: FloatArray, g: FloatArray, b: FloatArray): FloatArray? {
        if (idx.isEmpty()) return null
        val sat = FloatArray(idx.size) { k ->
            val i = idx[k]
            max(r[i], max(g[i], b[i])) - min(r[i], min(g[i], b[i]))
        }
        val lim = median(DoubleArray(sat.size) { sat[it].toDouble() })
        val neutral = idx.filterIndexed { k, _ -> sat[k] <= lim }
        // and of those the darker half: the sky brightens it as well
        val light = DoubleArray(neutral.size) { val i = neutral[it]; (r[i] + g[i] + b[i]).toDouble() }
        val dark = median(light)
        val keep = neutral.filterIndexed { k, _ -> light[k] <= dark }
        return FloatArray(3) { k -> medianAt(arrayOf(r, g, b)[k], keep) }
    }

    // --- Small image tools ---------------------------------------------------------------

    private fun argb(r: Float, g: Float, b: Float): Int =
        (0xFF shl 24) or (r.roundToInt().coerceIn(0, 255) shl 16) or (g.roundToInt().coerceIn(0, 255) shl 8) or b.roundToInt().coerceIn(0, 255)

    private fun smoothstep(a: Double, b: Double, x: Double): Double {
        val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    /** Box-averaged down to at most [maxSide] a side (colours weighted by their opacity). */
    internal fun fit(img: Argb, maxSide: Int): Argb {
        val long = max(img.w, img.h)
        if (long <= maxSide) return img
        val nw = max(1, (img.w.toLong() * maxSide / long).toInt())
        val nh = max(1, (img.h.toLong() * maxSide / long).toInt())
        return Argb(nw, nh, resample(img.px, img.w, img.h, nw, nh))
    }

    private fun resample(px: IntArray, w: Int, h: Int, nw: Int, nh: Int): IntArray {
        val out = IntArray(nw * nh)
        for (oy in 0 until nh) {
            val y0 = (oy.toLong() * h / nh).toInt()
            val y1 = max(y0 + 1, ((oy + 1).toLong() * h / nh).toInt())
            for (ox in 0 until nw) {
                val x0 = (ox.toLong() * w / nw).toInt()
                val x1 = max(x0 + 1, ((ox + 1).toLong() * w / nw).toInt())
                var sa = 0L; var sr = 0L; var sg = 0L; var sb = 0L; var cnt = 0
                for (y in y0 until y1) for (x in x0 until x1) {
                    val p = px[y * w + x]
                    val a = p ushr 24
                    sa += a; sr += ((p shr 16) and 255) * a; sg += ((p shr 8) and 255) * a; sb += (p and 255) * a
                    cnt++
                }
                out[oy * nw + ox] = if (sa == 0L) 0 else
                    ((sa / cnt).toInt() shl 24) or ((sr / sa).toInt() shl 16) or ((sg / sa).toInt() shl 8) or (sb / sa).toInt()
            }
        }
        return out
    }

    /** A cut scaled by [f] (< 1): colours box-averaged, the mask where most of the box is car. */
    private fun scale(c: Cut, f: Double): Cut {
        val nw = max(1, (c.w * f).roundToInt())
        val nh = max(1, (c.h * f).roundToInt())
        val opaque = IntArray(c.px.size) { (0xFF shl 24) or (c.px[it] and 0xFFFFFF) }
        val px = resample(opaque, c.w, c.h, nw, nh)
        val m = BooleanArray(nw * nh)
        for (oy in 0 until nh) {
            val y0 = (oy.toLong() * c.h / nh).toInt()
            val y1 = max(y0 + 1, ((oy + 1).toLong() * c.h / nh).toInt())
            for (ox in 0 until nw) {
                val x0 = (ox.toLong() * c.w / nw).toInt()
                val x1 = max(x0 + 1, ((ox + 1).toLong() * c.w / nw).toInt())
                var on = 0
                for (y in y0 until y1) for (x in x0 until x1) if (c.m[y * c.w + x]) on++
                m[oy * nw + ox] = on * 2 >= (y1 - y0) * (x1 - x0)
            }
        }
        return Cut(nw, nh, px, m)
    }

    /** Trimmed to the mask's box. */
    internal fun trim(c: Cut): Cut {
        var x0 = c.w; var x1 = -1; var y0 = c.h; var y1 = -1
        for (y in 0 until c.h) for (x in 0 until c.w) if (c.m[y * c.w + x]) {
            if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
        }
        require(x1 >= 0) { "no car found in the picture" }
        val w = x1 - x0 + 1
        val h = y1 - y0 + 1
        val px = IntArray(w * h)
        val m = BooleanArray(w * h)
        for (y in 0 until h) {
            System.arraycopy(c.px, (y0 + y) * c.w + x0, px, y * w, w)
            System.arraycopy(c.m, (y0 + y) * c.w + x0, m, y * w, w)
        }
        return Cut(w, h, px, m)
    }

    private fun flip(c: Cut): Cut {
        val px = IntArray(c.px.size)
        val m = BooleanArray(c.m.size)
        for (y in 0 until c.h) for (x in 0 until c.w) {
            px[y * c.w + x] = c.px[y * c.w + c.w - 1 - x]
            m[y * c.w + x] = c.m[y * c.w + c.w - 1 - x]
        }
        return Cut(c.w, c.h, px, m)
    }

    /** Pixels reached from the border through [open] ones ([open] true) or through closed ones (false), 4-connected. */
    private fun flood(m: BooleanArray, w: Int, h: Int, open: Boolean): BooleanArray {
        val seen = BooleanArray(m.size)
        val stack = IntArray(m.size)
        var sp = 0
        fun push(i: Int) { if (!seen[i] && m[i] == open) { seen[i] = true; stack[sp++] = i } }
        for (x in 0 until w) { push(x); push((h - 1) * w + x) }
        for (y in 0 until h) { push(y * w); push(y * w + w - 1) }
        while (sp > 0) {
            val i = stack[--sp]
            val x = i % w
            if (x > 0) push(i - 1)
            if (x < w - 1) push(i + 1)
            if (i >= w) push(i - w)
            if (i + w < m.size) push(i + w)
        }
        return seen
    }

    /** Holes: background not connected to the border. */
    private fun fillHoles(m: BooleanArray, w: Int, h: Int) {
        val outside = flood(m, w, h, false)
        for (i in m.indices) if (!outside[i]) m[i] = true
    }

    internal class Blobs(val label: IntArray, val count: Int, val area: IntArray, val x0: IntArray, val y0: IntArray, val x1: IntArray, val y1: IntArray)

    /** 8-connected blobs, labelled from 1, with their area and box (inclusive). */
    internal fun blobs(m: BooleanArray, w: Int, h: Int): Blobs {
        val label = IntArray(m.size)
        val stack = IntArray(m.size)
        var area = IntArray(16); var x0 = IntArray(16); var y0 = IntArray(16); var x1 = IntArray(16); var y1 = IntArray(16)
        var count = 0
        for (start in m.indices) {
            if (!m[start] || label[start] != 0) continue
            count++
            if (count >= area.size) {
                val s = area.size * 2
                area = area.copyOf(s); x0 = x0.copyOf(s); y0 = y0.copyOf(s); x1 = x1.copyOf(s); y1 = y1.copyOf(s)
            }
            var a = 0; var bx0 = w; var by0 = h; var bx1 = -1; var by1 = -1
            var sp = 0
            label[start] = count
            stack[sp++] = start
            while (sp > 0) {
                val i = stack[--sp]
                val x = i % w
                val y = i / w
                a++
                if (x < bx0) bx0 = x; if (x > bx1) bx1 = x; if (y < by0) by0 = y; if (y > by1) by1 = y
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= h) continue
                    for (dx in -1..1) {
                        val nx = x + dx
                        if (nx < 0 || nx >= w) continue
                        val j = ny * w + nx
                        if (m[j] && label[j] == 0) { label[j] = count; stack[sp++] = j }
                    }
                }
            }
            area[count] = a; x0[count] = bx0; y0[count] = by0; x1[count] = bx1; y1[count] = by1
        }
        return Blobs(label, count, area, x0, y0, x1, y1)
    }

    /**
     * The biggest blob, plus detached parts beside it (mirrors on clean
     * cut-outs): big enough, within its height, near it across.
     */
    private fun keepCar(m: BooleanArray, w: Int, h: Int): BooleanArray {
        val b = blobs(m, w, h)
        if (b.count <= 1) return m
        var k = 1
        for (i in 2..b.count) if (b.area[i] > b.area[k]) k = i
        val keep = BooleanArray(b.count + 1)
        keep[k] = true
        val x = b.x0[k]; val y = b.y0[k]; val bw = b.x1[k] - x + 1; val bh = b.y1[k] - y + 1
        for (i in 1..b.count) {
            if (i == k) continue
            val wi = b.x1[i] - b.x0[i] + 1
            val hi = b.y1[i] - b.y0[i] + 1
            if (b.area[i] >= 0.002 * b.area[k] && b.y0[i] >= y && b.y0[i] + hi <= y + bh &&
                b.x0[i] + wi >= x - 0.15 * bw && b.x0[i] <= x + bw * 1.15) keep[i] = true
        }
        return BooleanArray(m.size) { keep[b.label[it]] }
    }

    /** Dilation by a (2rx+1) x (2ry+1) box; outside the picture counts as empty. */
    internal fun dilate(m: BooleanArray, w: Int, h: Int, rx: Int, ry: Int): BooleanArray = boxPass(m, w, h, rx, ry, false)

    /** Erosion by a (2rx+1) x (2ry+1) box; outside the picture doesn't erode. */
    internal fun erode(m: BooleanArray, w: Int, h: Int, rx: Int, ry: Int): BooleanArray = boxPass(m, w, h, rx, ry, true)

    private fun boxPass(m: BooleanArray, w: Int, h: Int, rx: Int, ry: Int, erode: Boolean): BooleanArray {
        val mid = BooleanArray(m.size)
        val pre = IntArray(max(w, h) + 1)
        for (y in 0 until h) {
            for (x in 0 until w) pre[x + 1] = pre[x] + if (m[y * w + x]) 1 else 0
            for (x in 0 until w) {
                val lo = max(0, x - rx); val hi = min(w - 1, x + rx)
                val cnt = pre[hi + 1] - pre[lo]
                mid[y * w + x] = if (erode) cnt == hi - lo + 1 else cnt > 0
            }
        }
        val out = BooleanArray(m.size)
        for (x in 0 until w) {
            for (y in 0 until h) pre[y + 1] = pre[y] + if (mid[y * w + x]) 1 else 0
            for (y in 0 until h) {
                val lo = max(0, y - ry); val hi = min(h - 1, y + ry)
                val cnt = pre[hi + 1] - pre[lo]
                out[y * w + x] = if (erode) cnt == hi - lo + 1 else cnt > 0
            }
        }
        return out
    }

    /** A 5-tap median across then down (a cheap stand-in for a 5x5 median), into float channels. */
    private fun median5(px: IntArray, w: Int, h: Int, r: FloatArray, g: FloatArray, b: FloatArray) {
        val tmp = IntArray(px.size)
        val v = IntArray(5)
        for (sh in intArrayOf(16, 8, 0)) {
            for (y in 0 until h) for (x in 0 until w) {
                for (k in 0 until 5) v[k] = (px[y * w + (x + k - 2).coerceIn(0, w - 1)] shr sh) and 255
                tmp[y * w + x] = mid5(v)
            }
            val dst = when (sh) { 16 -> r; 8 -> g; else -> b }
            for (y in 0 until h) for (x in 0 until w) {
                for (k in 0 until 5) v[k] = tmp[(y + k - 2).coerceIn(0, h - 1) * w + x]
                dst[y * w + x] = mid5(v).toFloat()
            }
        }
    }

    /** The middle of five (six comparisons). */
    private fun mid5(v: IntArray): Int {
        var a = v[0]; var b = v[1]; var c = v[2]; var d = v[3]; var e = v[4]
        var t: Int
        if (a > b) { t = a; a = b; b = t }
        if (c > d) { t = c; c = d; d = t }
        if (a > c) { t = a; a = c; c = t; t = b; b = d; d = t }
        // a is the smallest of four, so the median is the second smallest of b, c, d, e
        if (b > e) { t = b; b = e; e = t }
        if (b > c) { t = b; b = c; c = t; t = e; e = d; d = t }
        return min(c, e)
    }

    private val linear = FloatArray(256) { i ->
        val c = i / 255.0
        (if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)).toFloat()
    }

    /** CIE Lab scaled like OpenCV's 8-bit one (L 0..255), so distances match the prototype's. */
    private fun toLab(r: FloatArray, g: FloatArray, b: FloatArray): Array<FloatArray> {
        val n = r.size
        val l = FloatArray(n); val a = FloatArray(n); val bb = FloatArray(n)
        fun f(t: Double) = if (t > 0.008856) cbrt(t) else 7.787 * t + 16.0 / 116
        for (i in 0 until n) {
            val rl = linear[r[i].toInt().coerceIn(0, 255)].toDouble()
            val gl = linear[g[i].toInt().coerceIn(0, 255)].toDouble()
            val bl = linear[b[i].toInt().coerceIn(0, 255)].toDouble()
            val x = (0.412453 * rl + 0.357580 * gl + 0.180423 * bl) / 0.950456
            val y = 0.212671 * rl + 0.715160 * gl + 0.072169 * bl
            val z = (0.019334 * rl + 0.119193 * gl + 0.950227 * bl) / 1.088754
            val fy = f(y)
            l[i] = ((if (y > 0.008856) 116 * fy - 16 else 903.3 * y) * 2.55).toFloat()
            a[i] = (500 * (f(x) - fy)).toFloat()
            bb[i] = (200 * (fy - f(z))).toFloat()
        }
        return arrayOf(l, a, bb)
    }

    /** Gaussian blur, [sx] across and [sy] down; wide ones as three box passes. */
    private fun blur(src: FloatArray, w: Int, h: Int, sx: Double, sy: Double): FloatArray =
        blur1(blur1(src, w, h, sx, true), w, h, sy, false)

    private fun blur1(a: FloatArray, w: Int, h: Int, sigma: Double, across: Boolean): FloatArray {
        if (sigma <= 0.0) return a
        val len = if (across) w else h
        val lines = if (across) h else w
        val step = if (across) 1 else w
        val lineStep = if (across) w else 1
        val out = FloatArray(a.size)
        val buf = FloatArray(len)
        val tmp = FloatArray(len)
        val kernel: FloatArray?
        val box: Int
        if (sigma < 3) {
            val rad = ceil(3 * sigma).toInt()
            kernel = FloatArray(2 * rad + 1) { val d = it - rad; kotlin.math.exp(-d * d / (2 * sigma * sigma)).toFloat() }
            val sum = kernel.sum()
            for (i in kernel.indices) kernel[i] /= sum
            box = 0
        } else {
            kernel = null
            box = ((sqrt(12 * sigma * sigma / 3 + 1) - 1) / 2).roundToInt().coerceAtLeast(1)
        }
        for (ln in 0 until lines) {
            val base = ln * lineStep
            for (i in 0 until len) buf[i] = a[base + i * step]
            if (kernel != null) {
                val rad = kernel.size / 2
                for (i in 0 until len) {
                    var s = 0f
                    if (i >= rad && i + rad < len) for (k in kernel.indices) s += kernel[k] * buf[i + k - rad]
                    else for (k in kernel.indices) s += kernel[k] * buf[(i + k - rad).coerceIn(0, len - 1)]
                    tmp[i] = s
                }
            } else {
                boxLine(buf, tmp, len, box); boxLine(tmp, buf, len, box); boxLine(buf, tmp, len, box)
            }
            for (i in 0 until len) out[base + i * step] = tmp[i]
        }
        return out
    }

    private fun boxLine(src: FloatArray, dst: FloatArray, len: Int, r: Int) {
        var s = 0f
        for (k in -r..r) s += src[k.coerceIn(0, len - 1)]
        val inv = 1f / (2 * r + 1)
        for (i in 0 until len) {
            dst[i] = s * inv
            s += src[(i + r + 1).coerceIn(0, len - 1)] - src[(i - r).coerceIn(0, len - 1)]
        }
    }

    /** 3x3 Sobel, across (d/dx) or down (d/dy); the edge repeated outside. */
    private fun sobel(a: FloatArray, w: Int, h: Int, across: Boolean): FloatArray {
        val out = FloatArray(a.size)
        for (y in 0 until h) {
            val up = max(0, y - 1) * w
            val mid = y * w
            val dn = min(h - 1, y + 1) * w
            for (x in 0 until w) {
                val xl = max(0, x - 1)
                val xr = min(w - 1, x + 1)
                out[mid + x] = if (across)
                    (a[up + xr] + 2 * a[mid + xr] + a[dn + xr]) - (a[up + xl] + 2 * a[mid + xl] + a[dn + xl])
                else (a[dn + xl] + 2 * a[dn + x] + a[dn + xr]) - (a[up + xl] + 2 * a[up + x] + a[up + xr])
            }
        }
        return out
    }

    private fun median(v: DoubleArray): Double {
        if (v.isEmpty()) return 0.0
        val s = v.sortedArray()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }

    private fun percentile(v: FloatArray, p: Double): Double {
        if (v.isEmpty()) return 0.0
        val s = v.sortedArray()
        val pos = p * (s.size - 1)
        val i = pos.toInt()
        val f = pos - i
        return if (i + 1 < s.size) s[i] * (1 - f) + s[i + 1] * f else s[i].toDouble()
    }

    private fun medianOf(ch: FloatArray, where: BooleanArray): Float {
        var n = 0
        for (b in where) if (b) n++
        val v = DoubleArray(n)
        var k = 0
        for (i in ch.indices) if (where[i]) v[k++] = ch[i].toDouble()
        return median(v).toFloat()
    }

    private fun medianAt(ch: FloatArray, idx: List<Int>): Float = median(DoubleArray(idx.size) { ch[idx[it]].toDouble() }).toFloat()
}
