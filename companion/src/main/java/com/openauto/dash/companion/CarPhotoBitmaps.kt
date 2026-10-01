package com.openauto.dash.companion

import android.graphics.Bitmap
import com.openauto.dash.carphoto.Argb
import java.io.ByteArrayOutputStream

/** Between Android pictures and the car photo kit's plain ARGB ones. */

fun Bitmap.toArgb(): Argb {
    val src = if (config == Bitmap.Config.ARGB_8888) this else copy(Bitmap.Config.ARGB_8888, false)
    val px = IntArray(src.width * src.height)
    src.getPixels(px, 0, src.width, 0, 0, src.width, src.height)
    return Argb(src.width, src.height, px)
}

fun Argb.toBitmap(): Bitmap = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)

/** As a PNG file, for a car pack. */
fun Argb.toPng(): ByteArray {
    val bitmap = toBitmap()
    return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        .also { bitmap.recycle() }
}
