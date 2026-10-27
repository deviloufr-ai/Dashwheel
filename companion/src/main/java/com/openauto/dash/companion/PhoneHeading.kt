package com.openauto.dash.companion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.openauto.dash.roadwatch.Mount
import kotlin.math.sqrt

/**
 * How the phone turns, from its gyroscope and gravity, while Road watch runs:
 *
 * - [radians]: which way the car points, the gyroscope turned about gravity;
 *   clockwise as the car turns right (a compass's sense), from wherever Road
 *   watch started. The recorder writes it into a clip's notes (headingDeg):
 *   replayed, it tells a parked car beside a bend from the slide of the turn
 *   (draft PR #85), before the phone acts on it.
 * - [pitchDeg]: the nod of the camera over the last couple of seconds (a
 *   bump, a crest), the gyroscope turned about the picture's horizontal, fading
 *   with 2 s: positive nose up. In the notes as pitchDeg, to judge shifting
 *   the horizon by it on recorded drives before the phone does.
 * - [tiltDeg]: how far the phone has turned in its holder since the driver
 *   placed it ([reference]): the picture no longer shows what was set up, so
 *   Road watch holds its warnings. In the notes as tiltDeg.
 *
 * Null without a gyroscope, or while Road watch is off.
 */
object PhoneHeading {
    @Volatile var radians: Float? = null
        private set
    @Volatile var pitchDeg: Float? = null
        private set
    @Volatile var tiltDeg: Float? = null
        private set
    /** Gravity in the phone when the driver placed it, from Road watch; null until placed. */
    @Volatile var reference: FloatArray? = null
        set(value) {
            field = value
            tiltDeg = tilt(up, value)
        }

    @Volatile private var up: FloatArray? = null
    private var lastNs = 0L

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            when (e.sensor.type) {
                // Gravity's sensor points up, away from the ground.
                Sensor.TYPE_GRAVITY -> {
                    val g = e.values.copyOf()
                    up = g
                    tiltDeg = tilt(g, reference)
                }
                Sensor.TYPE_GYROSCOPE -> {
                    val g = up ?: return
                    val norm = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2])
                    if (norm < 1f) return
                    // The turn about the vertical: the gyroscope's anticlockwise turn about "up", made clockwise.
                    val yaw = -(e.values[0] * g[0] + e.values[1] * g[1] + e.values[2] * g[2]) / norm
                    // The picture's horizontal: across gravity and the back camera's line of sight (the phone's -z),
                    // whichever way the phone sits in its holder.
                    val hx = -g[1]
                    val hy = g[0]
                    val hn = sqrt(hx * hx + hy * hy)
                    val nod = if (hn > 0.1f) (e.values[0] * hx + e.values[1] * hy) / hn else 0f
                    if (lastNs != 0L) {
                        val dt = (e.timestamp - lastNs) / 1e9f
                        radians = (radians ?: 0f) + yaw * dt
                        val p = (pitchDeg ?: 0f) + Math.toDegrees(nod.toDouble()).toFloat() * dt
                        // Fading: what is left is the last couple of seconds' nod, not the drift of the gyroscope.
                        pitchDeg = p * (1f - (dt / PITCH_FADE_S).coerceAtMost(1f))
                    }
                    lastNs = e.timestamp
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun tilt(now: FloatArray?, placed: FloatArray?): Float? =
        if (now != null && placed != null) Mount.turnedDeg(placed, now) else null

    fun start(context: Context) {
        val sensors = context.getSystemService(SensorManager::class.java) ?: return
        val gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: return
        val gravity = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: return
        sensors.registerListener(listener, gravity, SensorManager.SENSOR_DELAY_GAME)
        sensors.registerListener(listener, gyro, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop(context: Context) {
        context.getSystemService(SensorManager::class.java)?.unregisterListener(listener)
        radians = null
        pitchDeg = null
        tiltDeg = null
        up = null
        lastNs = 0L
    }

    /** The nod fades over this long: a bump is gone before the next, a slope is not kept. */
    private const val PITCH_FADE_S = 2f
}
