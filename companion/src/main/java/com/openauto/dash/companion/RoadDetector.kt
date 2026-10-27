package com.openauto.dash.companion

import android.content.Context
import android.util.Log
import com.openauto.dash.roadwatch.Box
import com.openauto.dash.roadwatch.Detection
import com.openauto.dash.roadwatch.Upright
import com.openauto.dash.roadwatch.VehicleKind
import com.openauto.dash.roadwatch.VehicleTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import android.os.SystemClock
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The vehicle finder Road watch uses: EfficientDet-Lite2, a detector
 * trained on everyday photos (Apache 2.0, from TensorFlow's model garden),
 * fetched once on the driver's say-so rather than shipped in every phone's
 * app. Checked against its known fingerprint before it is used. It replaced
 * Lite0: on recorded drives it found the car ahead at 50 to 80 m 60 % of
 * the time against 21 % (with the horizon look, [RoadDetector.detect]).
 */
object RoadModel {
    private const val URL_MODEL =
        "https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/object_detection/android/lite-model_efficientdet_lite2_detection_metadata_1.tflite"
    private const val SHA256 = "6fd32c84ab1eb0f7e7f3a7a20a20d7df1530daa8378728f7c79571096286bd52"
    const val BYTES = 7_557_887L

    sealed interface State {
        data object Missing : State
        /** [done]: 0..1. */
        data class Getting(val done: Float) : State
        data object Ready : State
        data object Failed : State
    }

    private val _state = MutableStateFlow<State>(State.Missing)
    val state: StateFlow<State> = _state

    fun file(context: Context): File = File(context.noBackupFilesDir, "road_vehicles.tflite")

    /** Whether the model is on the phone; [state] follows. */
    fun check(context: Context): Boolean {
        val ok = file(context).length() == BYTES
        if (_state.value !is State.Getting) _state.value = if (ok) State.Ready else State.Missing
        return ok
    }

    /** A phone that fetched the earlier finder gets this one by itself: the driver already said yes to it. */
    suspend fun upgrade(context: Context) {
        val f = file(context)
        if (f.exists() && f.length() != BYTES) get(context)
    }

    /** Fetches the model, unless it is here or on its way. True once it is on the phone, checked. */
    suspend fun get(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (check(context)) return@withContext true
        if (_state.value is State.Getting) return@withContext false
        _state.value = State.Getting(0f)
        val target = file(context)
        val part = File(target.path + ".part")
        val ok = try {
            download(part)
            part.renameTo(target)
        } catch (e: IOException) {
            Log.w("RoadModel", "could not get the model", e)
            false
        } finally {
            part.delete()
        }
        _state.value = if (ok) State.Ready else State.Failed
        ok
    }

    private fun download(to: File) {
        val connection = URL(URL_MODEL).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode}")
            val digest = MessageDigest.getInstance("SHA-256")
            var read = 0L
            connection.inputStream.use { input ->
                to.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        read += n
                        if (read > BYTES) throw IOException("bigger than the model")
                        digest.update(buf, 0, n)
                        output.write(buf, 0, n)
                        _state.value = State.Getting(read.toFloat() / BYTES)
                    }
                }
            }
            val sum = digest.digest().joinToString("") { "%02x".format(it) }
            if (sum != SHA256) throw IOException("not the model: $sum")
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * The vehicles in a camera frame, from [RoadModel]. The detector looks at a
 * square of the upright frame, as wide as its shorter side and set on the
 * road ahead (or the middle of it around the horizon, at twice the
 * detail), so a wide or a tall frame isn't squashed; the boxes come back
 * in the whole upright frame's fractions. Not thread-safe: one frame at a time.
 */
class RoadDetector private constructor(private val interpreter: Interpreter, private val gpu: GpuDelegate?) {
    /** The model runs on the phone's graphics chip, not its processor. */
    val onGpu: Boolean get() = gpu != null

    /** How long the model took on the last frames, ms (a running average). */
    @Volatile var runMs = 0f
        private set
    private val input = ByteBuffer.allocateDirect(SIZE * SIZE * 3).order(ByteOrder.nativeOrder())
    private val boxes = Array(1) { Array(MAX) { FloatArray(4) } }
    private val classes = Array(1) { FloatArray(MAX) }
    private val scores = Array(1) { FloatArray(MAX) }
    private val count = FloatArray(1)

    /**
     * The vehicles in the RGBA frame [rgba] (see [Upright.index] for the
     * other arguments). [centreY]: where the road ahead is, a fraction of the
     * upright frame's height, for a tall frame to be looked at there rather
     * than in its middle.
     */
    fun detect(rgba: ByteBuffer, width: Int, height: Int, rowStride: Int, rotation: Int, centreY: Float = 0.5f, horizonY: Float? = null): List<Detection> {
        val uw = Upright.width(width, height, rotation)
        val uh = Upright.height(width, height, rotation)
        // With [horizonY], the middle of the square around the horizon at twice the detail: far vehicles.
        val side = if (horizonY == null) min(uw, uh) else min(uw, uh) / 2
        val left = (uw - side) / 2
        val top = (if (horizonY == null) (centreY * uh).roundToInt() - side / 2 else (horizonY * uh - side / 3f).roundToInt()).coerceIn(0, uh - side)
        input.rewind()
        for (y in 0 until SIZE) {
            val v = top + y * side / SIZE
            for (x in 0 until SIZE) {
                val i = Upright.index(left + x * side / SIZE, v, width, height, rowStride, rotation)
                input.put(rgba.get(i))
                input.put(rgba.get(i + 1))
                input.put(rgba.get(i + 2))
            }
        }
        input.rewind()
        val started = SystemClock.elapsedRealtimeNanos()
        interpreter.runForMultipleInputsOutputs(arrayOf<Any>(input), mapOf<Int, Any>(0 to boxes, 1 to classes, 2 to scores, 3 to count))
        val took = (SystemClock.elapsedRealtimeNanos() - started) / 1e6f
        runMs = if (runMs == 0f) took else runMs * 0.9f + took * 0.1f
        val n = min(MAX, count[0].toInt())
        val found = ArrayList<Detection>()
        for (k in 0 until n) {
            val score = scores[0][k]
            if (score < MIN_SCORE) continue
            val kind = VehicleKind.ofCoco(classes[0][k].toInt()) ?: continue
            val (ymin, xmin, ymax, xmax) = boxes[0][k]
            // Cut off by the horizon look's edges: a near vehicle, whose width would read wrong; the square has it whole.
            if (horizonY != null && (xmin < EDGE || ymin < EDGE || xmax > 1 - EDGE || ymax > 1 - EDGE)) continue
            found += Detection(
                Box(
                    (left + xmin * side) / uw, (top + ymin * side) / uh,
                    (left + xmax * side) / uw, (top + ymax * side) / uh
                ),
                kind, score
            )
        }
        return found
    }

    fun close() {
        interpreter.close()
        gpu?.close()
    }

    companion object {
        private const val SIZE = 448
        private const val EDGE = 0.01f
        private const val MAX = 25
        /** Down to the doubtful boxes: [VehicleTracker] keeps those only where a vehicle already is (0.4 started a vehicle outright before). */
        private const val MIN_SCORE = VehicleTracker.LOW

        /** The detector, or null without the model (or if this phone can't run it). */
        /**
         * The detector on the graphics chip where it can run there and [onGpu] asks for it, on four
         * processor threads otherwise. Which is faster depends on the phone and how hot it is:
         * Road watch times both ([RoadWatch]).
         */
        fun open(context: Context, onGpu: Boolean = true): RoadDetector? {
            if (!RoadModel.check(context)) return null
            val gpu = if (!onGpu) null else runCatching {
                val phone = CompatibilityList()
                if (phone.isDelegateSupportedOnThisDevice) GpuDelegate(phone.bestOptionsForThisDevice) else null
            }.getOrNull()
            if (gpu != null) {
                runCatching { RoadDetector(Interpreter(RoadModel.file(context), Interpreter.Options().addDelegate(gpu)), gpu) }
                    .onFailure { Log.w("RoadDetector", "the graphics chip can't run the model", it); gpu.close() }
                    .getOrNull()?.let { return it }
            }
            return runCatching {
                RoadDetector(Interpreter(RoadModel.file(context), Interpreter.Options().setNumThreads(4)), null)
            }.onFailure { Log.w("RoadDetector", "could not open the model", it) }.getOrNull()
        }
    }
}
