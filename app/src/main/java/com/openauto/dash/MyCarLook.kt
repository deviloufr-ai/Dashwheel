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
import com.openauto.dash.carphoto.CarPart
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
 * A pack may also carry, for the view from above, each door, the tailgate
 * and the bonnet open (cut from a picture of the car with them open): the
 * door alert and the reverse view lay them on the car as they open.
 */

internal enum class CarLookStyle(@StringRes val titleRes: Int, @StringRes val hintRes: Int) {
    PHOTO(R.string.mycar_style_photo, R.string.mycar_style_photo_hint),
    TINTED(R.string.mycar_style_tinted, R.string.mycar_style_tinted_hint),
    OUTLINE(R.string.mycar_style_outline, R.string.mycar_style_outline_hint)
}

/** A part of the car open ([CarPart]): its picture and outline, and where it goes on its view ([box], fractions, beyond 0..1 where it sticks out). */
internal class CarLayer(val photo: ImageBitmap, val outline: ImageBitmap, val box: Rect)

/**
 * One view of the car: the picture, its outline (white, the edges in its
 * alpha) and where things are on it, as fractions of its width and height.
 * [wheels]: from above front left, front right, rear left, rear right; from
 * the side front then rear. [nose] and [tail]: the bumpers' middles.
 * [parts]: the parts that open, drawn open over the picture.
 */
internal class CarView(
    val photo: ImageBitmap,
    val outline: ImageBitmap,
    val wheels: List<Offset> = emptyList(),
    val nose: Offset? = null,
    val tail: Offset? = null,
    val parts: Map<CarPart, CarLayer> = emptyMap()
) {
    val aspect: Float get() = photo.width.toFloat() / photo.height

    /** Everything that may be drawn, open parts included, as fractions of the picture. */
    val reach: Rect = parts.values.fold(Rect(0f, 0f, 1f, 1f)) { r, p ->
        Rect(min(r.left, p.box.left), min(r.top, p.box.top), max(r.right, p.box.right), max(r.bottom, p.box.bottom))
    }
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
            // The open parts: kept as they come (already cut out), placed on the trimmed picture.
            val parts = JSONObject()
            for ((key, part) in raw.parts) {
                val photo = decodeSized(part.bytes) ?: continue
                val outline = outlineOf(photo)
                File(next, "${view}_$key.png").outputStream().use { photo.compress(Bitmap.CompressFormat.PNG, 100, it) }
                File(next, "${view}_${key}_line.png").outputStream().use { outline.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val a = prepared.move(part.box.topLeft)
                val b = prepared.move(part.box.bottomRight)
                parts.put(key, JSONArray().put(a.x.toDouble()).put(a.y.toDouble()).put(b.x.toDouble()).put(b.y.toDouble()))
                photo.recycle()
                outline.recycle()
            }
            manifest.put(view, JSONObject().apply {
                put("wheels", JSONArray().apply { prepared.wheels.forEach { put(point(it)) } })
                prepared.nose?.let { put("nose", point(it)) }
                prepared.tail?.let { put("tail", point(it)) }
                if (parts.length() > 0) put("parts", parts)
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

    private class RawPart(val bytes: ByteArray, val box: Rect)

    private class RawView(
        val bytes: ByteArray,
        val wheels: List<Offset> = emptyList(),
        val nose: Offset? = null,
        val tail: Offset? = null,
        val parts: Map<String, RawPart> = emptyMap()
    )

    /** [move]: a point of the picture as it came (fractions) to the same point of [photo]. */
    private class Prepared(
        val photo: Bitmap, val outline: Bitmap, val wheels: List<Offset>, val nose: Offset?, val tail: Offset?,
        val move: (Offset) -> Offset
    )

    /**
     * A car pack: car.json names each view's picture and its anchor points
     * (fractions of that picture), and under "parts" the pictures of its open
     * parts with their place on it. Only the files car.json names are read,
     * each at most [MAX_FILE] bytes.
     */
    private fun readPack(bytes: ByteArray): Pair<String, Map<String, RawView>> {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var total = 0L
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                // Read up to what is still allowed, and no further: a small file
                // can unpack to gigabytes, and all of it was read before being weighed.
                val room = MAX_FILE * 3L - total
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = zip.read(buffer)
                    if (n < 0) break
                    require(out.size() + n <= room) { "too big" }
                    out.write(buffer, 0, n)
                }
                val data = out.toByteArray()
                total += data.size
                files[entry.name.substringAfterLast('/')] = data
            }
        }
        val json = JSONObject(files["car.json"]?.decodeToString() ?: error("no car.json"))
        val views = json.optJSONObject("views") ?: JSONObject()
        val anchors = json.optJSONObject("anchors") ?: JSONObject()
        val allParts = json.optJSONObject("parts") ?: JSONObject()
        val out = LinkedHashMap<String, RawView>()
        for (view in VIEWS) {
            val file = views.optString(view).takeIf { it.isNotBlank() } ?: continue
            val data = files[file.substringAfterLast('/')] ?: continue
            val a = anchors.optJSONObject(view)
            val wheels = a?.optJSONArray("wheels")?.let { arr -> (0 until arr.length()).mapNotNull { offset(arr.optJSONArray(it)) } }.orEmpty()
            val parts = LinkedHashMap<String, RawPart>()
            allParts.optJSONObject(view)?.let { p ->
                for (part in CarPart.entries) {
                    val entry = p.optJSONObject(part.key) ?: continue
                    val bytes = files[entry.optString("file").substringAfterLast('/')] ?: continue
                    val box = rect(entry.optJSONArray("box")) ?: continue
                    parts[part.key] = RawPart(bytes, box)
                }
            }
            out[view] = RawView(data, wheels, offset(a?.optJSONArray("nose")), offset(a?.optJSONArray("tail")), parts)
        }
        val name = json.optString("name").ifBlank { CarProfileStore.current.name }
        return name to out
    }

    /** Decoded no longer than [MAX_SIDE] a side. */
    private fun decodeSized(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return null
        val scale = MAX_SIDE.toFloat() / max(decoded.width, decoded.height)
        return if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).roundToInt(), (decoded.height * scale).roundToInt(), true)
                .also { if (it !== decoded) decoded.recycle() }
        } else decoded
    }

    /** Decoded small enough, trimmed to the car (its anchors moved along), and its outline drawn. */
    private fun prepare(raw: RawView): Prepared? {
        val sized = decodeSized(raw.bytes) ?: return null
        val w = sized.width
        val h = sized.height
        val px = IntArray(w * h)
        sized.getPixels(px, 0, w, 0, 0, w, h)
        val crop = opaqueBounds(px, w, h)
        val photo = Bitmap.createBitmap(sized, crop.left, crop.top, crop.width(), crop.height())
        if (photo !== sized) sized.recycle()
        fun moved(o: Offset) = Offset((o.x * w - crop.left) / crop.width(), (o.y * h - crop.top) / crop.height())
        return Prepared(photo, outlineOf(photo), raw.wheels.map(::moved), raw.nose?.let(::moved), raw.tail?.let(::moved), ::moved)
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
            val parts = HashMap<CarPart, CarLayer>()
            a.optJSONObject("parts")?.let { p ->
                for (part in CarPart.entries) {
                    val box = rect(p.optJSONArray(part.key)) ?: continue
                    val partPhoto = BitmapFactory.decodeFile(File(root, "${name}_${part.key}.png").path) ?: continue
                    val partLine = BitmapFactory.decodeFile(File(root, "${name}_${part.key}_line.png").path) ?: continue
                    parts[part] = CarLayer(partPhoto.asImageBitmap(), partLine.asImageBitmap(), box)
                }
            }
            return CarView(photo.asImageBitmap(), outline.asImageBitmap(), wheels, offset(a.optJSONArray("nose")), offset(a.optJSONArray("tail")), parts)
        }
        CarLook(manifest.optString("name"), view("side"), view("top"), view("hero")).takeIf { it.side != null || it.top != null || it.hero != null }
    }.getOrNull()

    private fun point(o: Offset) = JSONArray().put(o.x.toDouble()).put(o.y.toDouble())

    /** Left, top, right, bottom. */
    private fun rect(a: JSONArray?): Rect? {
        if (a == null || a.length() < 4) return null
        val v = (0 until 4).map { a.optDouble(it, Double.NaN).toFloat() }
        if (v.any { it.isNaN() } || v[2] <= v[0] || v[3] <= v[1]) return null
        return Rect(v[0], v[1], v[2], v[3])
    }

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

/**
 * Where [view] sits in [area] with room for its parts open ([CarView.reach]),
 * so the car keeps its place and size as its doors open and shut.
 */
internal fun fitCarOpen(view: CarView, area: Rect): Rect {
    val reach = view.reach
    val aspect = view.aspect * reach.width / reach.height
    val w = min(area.width, area.height * aspect)
    val h = w / aspect
    val pw = w / reach.width
    val ph = h / reach.height
    return Rect(Offset(area.center.x - w / 2f - reach.left * pw, area.center.y - h / 2f - reach.top * ph), Size(pw, ph))
}

/** A point of [view] (fractions) in the [placed] rectangle. */
internal fun Rect.at(fraction: Offset): Offset = Offset(left + width * fraction.x, top + height * fraction.y)

/**
 * The car in [style] in [dst]: [ink] is the line or tint colour (the look's
 * accent), [alert] turns it to [warn]: the outline over the photo, the tint
 * or the lines themselves. [open]: how far each part is open (0 shut, 1
 * open); an open part the view has a picture of is drawn open over the car
 * in [warn], one it hasn't turns the whole car to [warn].
 */
internal fun DrawScope.drawCarView(
    view: CarView, style: CarLookStyle, dst: Rect, ink: Color, warn: Color, alert: Boolean = false,
    open: Map<CarPart, Float> = emptyMap()
) {
    val shown = open.filterValues { it > 0.01f }
    val warned = alert || shown.keys.any { it !in view.parts }
    drawPicture(view.photo, view.outline, style, dst, if (warned) warn else ink, warned, warn)
    for ((part, amount) in shown) {
        val layer = view.parts[part] ?: continue
        drawPicture(layer.photo, layer.outline, style, Rect(dst.at(layer.box.topLeft), dst.at(layer.box.bottomRight)), warn, true, warn, amount.coerceAtMost(1f))
    }
}

/** One picture in [style]: [alert] puts the outline in [warn] over the photo. */
private fun DrawScope.drawPicture(
    photo: ImageBitmap, outline: ImageBitmap, style: CarLookStyle, dst: Rect, tone: Color, alert: Boolean, warn: Color, alpha: Float = 1f
) {
    val offset = IntOffset(dst.left.roundToInt(), dst.top.roundToInt())
    val size = IntSize(dst.width.roundToInt().coerceAtLeast(1), dst.height.roundToInt().coerceAtLeast(1))
    when (style) {
        CarLookStyle.PHOTO -> {
            drawImage(photo, dstOffset = offset, dstSize = size, alpha = alpha)
            if (alert) drawImage(outline, dstOffset = offset, dstSize = size, alpha = alpha, colorFilter = ColorFilter.tint(warn, BlendMode.SrcIn))
        }
        CarLookStyle.TINTED -> drawImage(photo, dstOffset = offset, dstSize = size, alpha = alpha, colorFilter = tintRamp(tone))
        CarLookStyle.OUTLINE -> drawImage(outline, dstOffset = offset, dstSize = size, alpha = alpha, colorFilter = ColorFilter.tint(tone, BlendMode.SrcIn))
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
