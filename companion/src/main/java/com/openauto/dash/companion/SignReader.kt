package com.openauto.dash.companion

import android.graphics.Bitmap
import android.os.SystemClock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.openauto.dash.roadwatch.SignConfirm
import com.openauto.dash.roadwatch.SignSpot
import com.openauto.dash.roadwatch.Upright
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads the number on a round sign [com.openauto.dash.roadwatch.SignFinder]
 * found: its middle copied out of the camera's frame, made bigger, and read by
 * ML Kit's text recognizer on the phone, no network. One sign at a time:
 * while one is being read, the next waits for a later frame. What it reads
 * goes to [RoadSigns], believed once read twice ([SignConfirm]).
 */
class SignReader {
    private val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val busy = AtomicBoolean(false)
    private val confirm = SignConfirm()

    /** Reads [spot] in the RGBA frame [rgba] (see [Upright.index]); the pixels are copied before this returns. */
    fun read(rgba: ByteBuffer, width: Int, height: Int, rowStride: Int, rotation: Int, spot: SignSpot) {
        if (!busy.compareAndSet(false, true)) return
        val bitmap = middle(rgba, width, height, rowStride, rotation, spot)
        client.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { text ->
                val kmh = SignConfirm.limitIn(text.text) ?: return@addOnSuccessListener
                synchronized(confirm) { confirm.read(kmh, SystemClock.elapsedRealtime()) }?.let { RoadSigns.seen(it) }
            }
            .addOnCompleteListener {
                bitmap.recycle()
                busy.set(false)
            }
    }

    fun close() = client.close()

    /** The sign's face inside its ring, [READ_SIDE] pixels across: the recognizer reads small text badly. */
    private fun middle(rgba: ByteBuffer, width: Int, height: Int, rowStride: Int, rotation: Int, spot: SignSpot): Bitmap {
        val uw = Upright.width(width, height, rotation)
        val uh = Upright.height(width, height, rotation)
        val b = spot.box
        val cx = (b.left + b.right) / 2 * uw
        val cy = (b.top + b.bottom) / 2 * uh
        val half = spot.sidePx * FACE / 2
        val pixels = IntArray(READ_SIDE * READ_SIDE)
        for (y in 0 until READ_SIDE) for (x in 0 until READ_SIDE) {
            val u = (cx - half + x * 2 * half / READ_SIDE).toInt().coerceIn(0, uw - 1)
            val v = (cy - half + y * 2 * half / READ_SIDE).toInt().coerceIn(0, uh - 1)
            val i = Upright.index(u, v, width, height, rowStride, rotation)
            pixels[y * READ_SIDE + x] = (0xFF shl 24) or ((rgba.get(i).toInt() and 0xFF) shl 16) or
                ((rgba.get(i + 1).toInt() and 0xFF) shl 8) or (rgba.get(i + 2).toInt() and 0xFF)
        }
        return Bitmap.createBitmap(pixels, READ_SIDE, READ_SIDE, Bitmap.Config.ARGB_8888)
    }

    private companion object {
        const val READ_SIDE = 160
        /** The face inside the ring, a share of the sign's width: the number with a little white round it. */
        const val FACE = 0.74f
    }
}

/**
 * The speed limit last read off a sign, for the car ([com.openauto.dash.link.RoadScene.signKmh]),
 * the recorder's notes and the speed report.
 */
object RoadSigns {
    @Volatile var kmh: Int? = null
        private set
    /** When it was read (elapsedRealtime, ms). */
    @Volatile var at = 0L
        private set

    fun seen(kmh: Int) {
        this.kmh = kmh
        at = SystemClock.elapsedRealtime()
    }

    /** The limit read in the last [withinMs], or null. */
    fun fresh(withinMs: Long, now: Long = SystemClock.elapsedRealtime()): Int? = kmh?.takeIf { now - at < withinMs }

    fun clear() {
        kmh = null
        at = 0L
    }
}
