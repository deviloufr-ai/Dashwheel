package com.openauto.dash.companion

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * What a round red sign's face shows, from a small network trained on the
 * German sign benchmark and drawn French limits (`tools/roadwatch-replay/signs_model.py`):
 * a limit (20 to 130 km/h), the end of a limit, another sign, or no sign at
 * all. It reads a face half the size the text recognizer needs, in under a
 * millisecond, so a sign is read from twice as far and on every frame it is
 * in view. The text recognizer stays for the faces it isn't sure of.
 */
class SignClassifier(context: Context) {
    /** What the face shows ([CLASSES]) and how sure the network is (0..1). */
    class Reading(val label: String, val sure: Float) {
        /** The limit in km/h, when the face is a limit sign. */
        val kmh: Int? get() = label.toIntOrNull()
    }

    private val interpreter = Interpreter(
        context.assets.open(MODEL).use { it.readBytes() }.let { bytes ->
            ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).also { it.rewind() }
        },
        Interpreter.Options().setNumThreads(1)
    )
    private val input = ByteBuffer.allocateDirect(SIDE * SIDE * 3 * 4).order(ByteOrder.nativeOrder())
    private val output = Array(1) { FloatArray(CLASSES.size) }
    private val pixels = IntArray(SIDE * SIDE)

    /** Reads [face], the sign's face as [SignReader] cuts it (any size; it is shrunk to [SIDE]). */
    fun read(face: Bitmap): Reading {
        val small = Bitmap.createScaledBitmap(face, SIDE, SIDE, true)
        small.getPixels(pixels, 0, SIDE, 0, 0, SIDE, SIDE)
        if (small !== face) small.recycle()
        input.rewind()
        for (p in pixels) {
            input.putFloat(((p shr 16) and 0xFF) / 255f)
            input.putFloat(((p shr 8) and 0xFF) / 255f)
            input.putFloat((p and 0xFF) / 255f)
        }
        input.rewind()
        interpreter.run(input, output)
        val scores = output[0]
        var best = 0
        for (k in scores.indices) if (scores[k] > scores[best]) best = k
        return Reading(CLASSES[best], scores[best])
    }

    fun close() = interpreter.close()

    companion object {
        const val MODEL = "sign_faces.tflite"
        const val SIDE = 32
        /** In the network's order (signs_model.py CLASSES). */
        val CLASSES = listOf("20", "30", "40", "50", "60", "70", "80", "90", "100", "110", "120", "130", "end", "other", "nothing")
        /** Surer than this, the reading stands on its own; else the text recognizer has a look. */
        const val SURE = 0.8f
    }
}
