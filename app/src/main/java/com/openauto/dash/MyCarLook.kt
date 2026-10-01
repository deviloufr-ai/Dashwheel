package com.openauto.dash

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.openauto.dash.link.CarLookAck
import com.openauto.dash.link.CarLookPart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.Base64
import java.util.zip.ZipInputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * EXPERIMENTAL: the driver's own car in place of the drawn one. A car pack
 * (.dwcar: a zip with car.json and a side, top and 3/4 picture, made from a
 * 3D model by tools/mycar/bake_car.py) or a single picture of the car from the
 * side comes from a file (a USB stick) or from the phone, in parts
 * ([CarLookPart]). Each picture is trimmed to the car and gets an outline made
 * from its edges; both are kept in the app's own folder. The car tiles then
 * draw it in one of three styles ([CarLookStyle]), the live signals (a wheel
 * in trouble, an alert) staying drawn on top at the pack's anchor points.
 */

internal enum class CarLookStyle(@StringRes val titleRes: Int, @StringRes val hintRes: Int) {
    PHOTO(R.string.mycar_style_photo, R.string.mycar_style_photo_hint),
    TINTED(R.string.mycar_style_tinted, R.string.mycar_style_tinted_hint),
    OUTLINE(R.string.mycar_style_outline, R.string.mycar_style_outline_hint)
}

/**
 * One view of the car: the picture, its outline (white, the edges in its
 * alpha) and where things are on it, as fractions of its width and height.
 * [wheels]: from above front left, front right, rear left, rear right; from
 * the side front then rear. [nose] and [tail]: the bumpers' middles.
 */
internal class CarView(
    val photo: ImageBitmap,
    val outline: ImageBitmap,
    val wheels: List<Offset> = emptyList(),
    val nose: Offset? = null,
    val tail: Offset? = null
) {
    val aspect: Float get() = photo.width.toFloat() / photo.height
}

internal class CarLook(val name: String, val side: CarView?, val top: CarView?, val hero: CarView?) {
    /** The best picture for a tile that shows the car whole: the 3/4 view, else the side. */
    val portrait: CarView? get() = hero ?: side
}

internal object MyCarLook {
    private const val PREFS = "my_car_look"
    private const val KEY_ON = "on"
    private const val KEY_STYLE = "style"
    private const val DIR = "mycar"
    private const val MANIFEST = "look.json"

    /** The longest side a picture is kept at: sharp on a full-screen tile, light in memory. */
    private const val MAX_SIDE = 1400

    /** The biggest file read: a pack of three large pictures. */
    private const val MAX_FILE = CarLookPart.MAX_BYTES

    private val VIEWS = listOf("side", "top", "hero")

    private var appContext: Context? = null

    private val _look = MutableStateFlow<CarLook?>(null)

    /** The imported car, shown or not. */
    val imported: StateFlow<CarLook?> = _look.asStateFlow()

    private val _on = MutableStateFlow(true)
    val on: StateFlow<Boolean> = _on.asStateFlow()

    private val _style = MutableStateFlow(CarLookStyle.PHOTO)
    val style: StateFlow<CarLookStyle> = _style.asStateFlow()

    private val _shown = MutableStateFlow<CarLook?>(null)

    /** The car the tiles draw: the imported one while it's switched on, else null (the drawn car). */
    val shown: StateFlow<CarLook?> = _shown.asStateFlow()

    /** Whether a car was imported, for code outside Compose (the add sheet). */
    val hasLook: Boolean get() = _look.value != null

    fun setContext(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _on.value = prefs.getBoolean(KEY_ON, true)
        _style.value = prefs.getString(KEY_STYLE, null)?.let { s -> CarLookStyle.entries.firstOrNull { it.name == s } } ?: CarLookStyle.PHOTO
        Thread({ publish(load(app)) }, "my-car-load").start()
    }

    fun setOn(context: Context, on: Boolean) {
        _on.value = on
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply()
        publish(_look.value)
    }

    fun setStyle(context: Context, style: CarLookStyle) {
        _style.value = style
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_STYLE, style.name).apply()
    }

    fun remove(context: Context) {
        File(context.filesDir, DIR).deleteRecursively()
        publish(null)
    }

    private fun publish(look: CarLook?) {
        _look.value = look
        _shown.value = look?.takeIf { _on.value }
    }

    // --- Import ---------------------------------------------------------------------

    /** Reads a car pack or a picture picked by the driver; blocking, call off the main thread. */
    fun importUri(context: Context, uri: Uri): Result<CarLook> = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                require(out.size() <= MAX_FILE) { "too big" }
            }
            out.toByteArray()
        } ?: error("unreadable")
        importBytes(context, bytes)
    }

    /** The file a unit without a file picker reads instead (beside the setup backup's). */
    fun fallbackFile(context: Context): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "my-car.dwcar")

    /** A car pack (zip) or a single picture of the car from the side. */
    fun importBytes(context: Context, bytes: ByteArray): CarLook {
        setContext(context)
        val isZip = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        val (name, views) = if (isZip) readPack(bytes) else (CarProfileStore.current.name to mapOf("side" to RawView(bytes)))
        require(views.isNotEmpty()) { "no picture" }

        // Built beside the current one, then swapped in whole: a failed import leaves the old car.
        val root = File(context.filesDir, DIR)
        val next = File(context.filesDir, "$DIR.new").apply { deleteRecursively(); mkdirs() }
        val manifest = JSONObject().put("name", name)
        for ((view, raw) in views) {
            val prepared = prepare(raw) ?: continue
            File(next, "$view.png").outputStream().use { prepared.photo.compress(Bitmap.CompressFormat.PNG, 100, it) }
            File(next, "${view}_line.png").outputStream().use { prepared.outline.compress(Bitmap.CompressFormat.PNG, 100, it) }
            manifest.put(view, JSONObject().apply {
                put("wheels", JSONArray().apply { prepared.wheels.forEach { put(point(it)) } })
                prepared.nose?.let { put("nose", point(it)) }
                prepared.tail?.let { put("tail", point(it)) }
            })
            prepared.photo.recycle()
            prepared.outline.recycle()
        }
        require(VIEWS.any { manifest.has(it) }) { "no picture" }
        File(next, MANIFEST).writeText(manifest.toString())
        root.deleteRecursively()
        check(next.renameTo(root)) { "could not keep it" }
        val look = load(context) ?: error("could not read it back")
        publish(look)
        return look
    }

    private class RawView(val bytes: ByteArray, val wheels: List<Offset> = emptyList(), val nose: Offset? = null, val tail: Offset? = null)

    private class Prepared(val photo: Bitmap, val outline: Bitmap, val wheels: List<Offset>, val nose: Offset?, val tail: Offset?)

    /**
     * A car pack: car.json names each view's picture and its anchor points
     * (fractions of that picture). Only the files car.json names are read,
     * each at most [MAX_FILE] bytes.
     */
    private fun readPack(bytes: ByteArray): Pair<String, Map<String, RawView>> {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var total = 0L
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val data = zip.readBytes()
                total += data.size
                require(total <= MAX_FILE * 3L) { "too big" }
                files[entry.name.substringAfterLast('/')] = data
            }
        }
        val json = JSONObject(files["car.json"]?.decodeToString() ?: error("no car.json"))
        val views = json.optJSONObject("views") ?: JSONObject()
        val anchors = json.optJSONObject("anchors") ?: JSONObject()
        val out = LinkedHashMap<String, RawView>()
        for (view in VIEWS) {
            val file = views.optString(view).takeIf { it.isNotBlank() } ?: continue
            val data = files[file.substringAfterLast('/')] ?: continue
            val a = anchors.optJSONObject(view)
            val wheels = a?.optJSONArray("wheels")?.let { arr -> (0 until arr.length()).mapNotNull { offset(arr.optJSONArray(it)) } }.orEmpty()
            out[view] = RawView(data, wheels, offset(a?.optJSONArray("nose")), offset(a?.optJSONArray("tail")))
        }
        val name = json.optString("name").ifBlank { CarProfileStore.current.name }
        return name to out
    }

    /** Decoded small enough, trimmed to the car (its anchors moved along), and its outline drawn. */
    private fun prepare(raw: RawView): Prepared? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw.bytes, 0, raw.bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(raw.bytes, 0, raw.bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return null
        val scale = MAX_SIDE.toFloat() / max(decoded.width, decoded.height)
        val sized = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).roundToInt(), (decoded.height * scale).roundToInt(), true)
                .also { if (it !== decoded) decoded.recycle() }
        } else decoded

        val w = sized.width
        val h = sized.height
        val px = IntArray(w * h)
        sized.getPixels(px, 0, w, 0, 0, w, h)
        val crop = opaqueBounds(px, w, h)
        val photo = Bitmap.createBitmap(sized, crop.left, crop.top, crop.width(), crop.height())
        if (photo !== sized) sized.recycle()
        fun moved(o: Offset) = Offset((o.x * w - crop.left) / crop.width(), (o.y * h - crop.top) / crop.height())
        return Prepared(photo, outlineOf(photo), raw.wheels.map(::moved), raw.nose?.let(::moved), raw.tail?.let(::moved))
    }

    /** The box around the car's own pixels, with a little room for the outline's glow; the whole picture without transparency. */
    private fun opaqueBounds(px: IntArray, w: Int, h: Int): android.graphics.Rect {
        var left = w
        var top = h
        var right = -1
        var bottom = -1
        for (y in 0 until h) for (x in 0 until w) {
            if ((px[y * w + x] ushr 24) > 8) {
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        if (right < 0) return android.graphics.Rect(0, 0, w, h)
        val pad = (max(w, h) * 0.01f).roundToInt()
        return android.graphics.Rect(max(0, left - pad), max(0, top - pad), min(w, right + 1 + pad), min(h, bottom + 1 + pad))
    }

    /**
     * The car as a line drawing: its silhouette's edge at full strength, the
     * lines inside it (doors, glass, lights, wheels) from the picture's own
     * contrast, a little softer. White, the drawing in the alpha, so a tile
     * tints it any colour.
     */
    private fun outlineOf(photo: Bitmap): Bitmap {
        val w = photo.width
        val h = photo.height
        val px = IntArray(w * h)
        photo.getPixels(px, 0, w, 0, 0, w, h)
        val lum = FloatArray(w * h)
        val solid = BooleanArray(w * h)
        val hasAlpha = px.any { (it ushr 24) < 250 }
        for (i in px.indices) {
            val c = px[i]
            lum[i] = 0.3f * ((c shr 16) and 0xFF) + 0.59f * ((c shr 8) and 0xFF) + 0.11f * (c and 0xFF)
            solid[i] = !hasAlpha || (c ushr 24) > 128
        }
        val out = IntArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            if (!solid[i]) continue
            // The silhouette: a solid pixel beside a clear one.
            val edge = hasAlpha && (!solid[i - 1] || !solid[i + 1] || !solid[i - w] || !solid[i + w])
            val gx = (lum[i - w + 1] + 2 * lum[i + 1] + lum[i + w + 1]) - (lum[i - w - 1] + 2 * lum[i - 1] + lum[i + w - 1])
            val gy = (lum[i + w - 1] + 2 * lum[i + w] + lum[i + w + 1]) - (lum[i - w - 1] + 2 * lum[i - w] + lum[i - w + 1])
            val inner = ((sqrt(gx * gx + gy * gy) - 85f) * 1.6f).coerceIn(0f, 190f)
            val a = if (edge) 255 else inner.roundToInt()
            if (a > 0) out[i] = (a shl 24) or 0xFFFFFF
        }
        // The silhouette one pixel thicker, so it holds at a tile's size.
        if (hasAlpha) {
            val grown = out.copyOf()
            for (y in 1 until h - 1) for (x in 1 until w - 1) {
                val i = y * w + x
                if ((out[i] ushr 24) == 255) {
                    for (j in intArrayOf(i - 1, i + 1, i - w, i + w)) if ((grown[j] ushr 24) < 200) grown[j] = (200 shl 24) or 0xFFFFFF
                }
            }
            return Bitmap.createBitmap(grown, w, h, Bitmap.Config.ARGB_8888)
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun load(context: Context): CarLook? = runCatching {
        val root = File(context.filesDir, DIR)
        val manifest = JSONObject(File(root, MANIFEST).takeIf { it.exists() }?.readText() ?: return null)
        fun view(name: String): CarView? {
            val a = manifest.optJSONObject(name) ?: return null
            val photo = BitmapFactory.decodeFile(File(root, "$name.png").path) ?: return null
            val outline = BitmapFactory.decodeFile(File(root, "${name}_line.png").path) ?: return null
            val wheels = a.optJSONArray("wheels")?.let { arr -> (0 until arr.length()).mapNotNull { offset(arr.optJSONArray(it)) } }.orEmpty()
            return CarView(photo.asImageBitmap(), outline.asImageBitmap(), wheels, offset(a.optJSONArray("nose")), offset(a.optJSONArray("tail")))
        }
        CarLook(manifest.optString("name"), view("side"), view("top"), view("hero")).takeIf { it.side != null || it.top != null || it.hero != null }
    }.getOrNull()

    private fun point(o: Offset) = JSONArray().put(o.x.toDouble()).put(o.y.toDouble())

    private fun offset(a: JSONArray?): Offset? {
        if (a == null || a.length() < 2) return null
        val x = a.optDouble(0, Double.NaN)
        val y = a.optDouble(1, Double.NaN)
        if (x.isNaN() || y.isNaN()) return null
        return Offset(x.toFloat(), y.toFloat())
    }

    // --- From the phone ---------------------------------------------------------------

    private var receiving: Long = 0
    private var expected = 0

    /**
     * One part of a file sent from the phone. The last one is imported off the
     * link's thread (it takes a moment), then [reply] answers the phone; so
     * does the first part that can't be taken.
     */
    fun receive(context: Context, part: CarLookPart, reply: (CarLookAck) -> Unit) {
        setContext(context)
        val file = File(context.cacheDir, "car-look.part")
        try {
            if (part.index == 0) {
                require(part.count in 1..(CarLookPart.MAX_BYTES / CarLookPart.PART_BYTES + 1)) { "too many parts" }
                receiving = part.id
                expected = 0
                file.delete()
            }
            require(part.id == receiving && part.index == expected) { "out of order" }
            val data = Base64.getDecoder().decode(part.data)
            RandomAccessFile(file, "rw").use { f ->
                require(f.length() + data.size <= CarLookPart.MAX_BYTES) { "too big" }
                f.seek(f.length())
                f.write(data)
            }
            expected++
            if (expected < part.count) return
            receiving = 0
            Thread({
                val ok = runCatching { importBytes(context, file.readBytes()) }.isSuccess
                file.delete()
                reply(CarLookAck(part.id, ok))
            }, "my-car-import").start()
        } catch (e: Exception) {
            if (receiving == part.id || part.index == 0) {
                receiving = 0
                file.delete()
                reply(CarLookAck(part.id, false))
            }
        }
    }
}

// --- Drawing --------------------------------------------------------------------------

/** Where [view] sits in [area], whole and centred. */
internal fun fitCar(view: CarView, area: Rect): Rect {
    val w = min(area.width, area.height * view.aspect)
    val h = w / view.aspect
    return Rect(Offset(area.center.x - w / 2f, area.center.y - h / 2f), Size(w, h))
}

/**
 * Where a view from above sits on the car's real [footprint] (nose up): its
 * nose on the footprint's front and its tail on the back, centred across, so
 * the bumpers meet whatever is drawn around them (the reverse view's
 * distance rings). Mirrors stick out beside it. Without its bumpers, [fitCar].
 */
internal fun fitCarOn(view: CarView, footprint: Rect): Rect {
    val nose = view.nose ?: return fitCar(view, footprint)
    val tail = view.tail ?: return fitCar(view, footprint)
    val span = tail.y - nose.y
    if (span < 0.2f) return fitCar(view, footprint)
    val h = footprint.height / span
    val w = h * view.aspect
    return Rect(Offset(footprint.center.x - w * nose.x, footprint.top - h * nose.y), Size(w, h))
}

/** A point of [view] (fractions) in the [placed] rectangle. */
internal fun Rect.at(fraction: Offset): Offset = Offset(left + width * fraction.x, top + height * fraction.y)

/**
 * The car in [style] in [dst]: [ink] is the line or tint colour (the look's
 * accent), [alert] turns it to [warn]: the outline over the photo, the tint
 * or the lines themselves.
 */
internal fun DrawScope.drawCarView(view: CarView, style: CarLookStyle, dst: Rect, ink: Color, warn: Color, alert: Boolean = false) {
    val offset = IntOffset(dst.left.roundToInt(), dst.top.roundToInt())
    val size = IntSize(dst.width.roundToInt().coerceAtLeast(1), dst.height.roundToInt().coerceAtLeast(1))
    val tone = if (alert) warn else ink
    when (style) {
        CarLookStyle.PHOTO -> {
            drawImage(view.photo, dstOffset = offset, dstSize = size)
            if (alert) drawImage(view.outline, dstOffset = offset, dstSize = size, colorFilter = ColorFilter.tint(warn, BlendMode.SrcIn))
        }
        CarLookStyle.TINTED -> drawImage(view.photo, dstOffset = offset, dstSize = size, colorFilter = tintRamp(tone))
        CarLookStyle.OUTLINE -> drawImage(view.outline, dstOffset = offset, dstSize = size, colorFilter = ColorFilter.tint(tone, BlendMode.SrcIn))
    }
}

/** The picture's brightness laid on a ramp from near black to [light]: one colour, its shading kept. */
private fun tintRamp(light: Color): ColorFilter {
    val dark = Color(0xFF070B10)
    fun row(d: Float, l: Float): FloatArray {
        val k = l - d
        return floatArrayOf(0.3f * k, 0.59f * k, 0.11f * k, 0f, d * 255f)
    }
    val r = row(dark.red, light.red)
    val g = row(dark.green, light.green)
    val b = row(dark.blue, light.blue)
    return ColorFilter.colorMatrix(ColorMatrix(r + g + b + floatArrayOf(0f, 0f, 0f, 1f, 0f)))
}
