package com.openauto.dash

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import com.openauto.dash.carphoto.Argb
import com.openauto.dash.carphoto.CarPhotoKit
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * EXPERIMENTAL: the driver's car from photos picked on the unit itself (a USB
 * stick), the same way the phone app builds it: the shared car photo kit makes
 * the side picture and the view from above, then [MyCarLook] imports them as a
 * car pack. Which photo is the side, the front and the back comes from their
 * names, else their shape ([CarPhotoKit.sortShots]).
 */
internal object CarPhotoImport {
    /** Photos are decoded about this long a side; the kit works at [CarPhotoKit.MAX_SIDE]. */
    private const val DECODE_SIDE = 2000

    /** Blocking, call off the main thread. */
    fun import(context: Context, uris: List<Uri>): Result<CarLook> = runCatching {
        val photos = uris.take(3).map { decode(context, it) }
        val names = uris.take(3).map { nameOf(context, it) }
        val (side, front, back) = CarPhotoKit.sortShots(names, photos.map { it.w.toFloat() / it.h })
        val result = CarPhotoKit.build(
            photos[side], front?.let { photos[it] }, back?.let { photos[it] },
            name = CarProfileStore.current.name
        )
        val files = LinkedHashMap<String, ByteArray>()
        files["car.json"] = result.toCarJson().toByteArray()
        for ((name, picture) in result.packEntries()) files[name] = png(picture)
        MyCarLook.importBytes(context, CarPhotoKit.zip(files))
    }

    /** Upright (a camera's rotation applied) and no bigger than needed. */
    private fun decode(context: Context, uri: Uri): Argb {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = max(info.size.width, info.size.height)
            if (longest > DECODE_SIDE) {
                val scale = DECODE_SIDE.toFloat() / longest
                decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
            }
        }
        val px = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return Argb(bitmap.width, bitmap.height, px).also { bitmap.recycle() }
    }

    private fun nameOf(context: Context, uri: Uri): String =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty()

    private fun png(picture: Argb): ByteArray {
        val bitmap = Bitmap.createBitmap(picture.px, picture.w, picture.h, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            .also { bitmap.recycle() }
    }
}
