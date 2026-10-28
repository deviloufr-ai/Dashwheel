package com.openauto.dash.companion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.openauto.dash.roadwatch.Mount
import kotlin.math.hypot
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
 *   Road watch holds its warnings. In the notes as tiltDeg. Gravity alone also
 *   leans with the car (11° under hard braking, 8-10° for the seconds after a
 *   stop, 10° in a bend, on the drive of 2026-10-27), so Road watch starts the
 *   hold only once [turnedDeg] saw the phone itself turn.
 * - [turnedDeg]: how much the gyroscope saw the phone nod or roll over the last
 *   couple of seconds, fading like [pitchDeg]: braking leans gravity but turns
 *   nothing, a hand on the phone does.
 *
 * Null without a gyroscope, or while Road watch is off.
 */
object PhoneHeading {
    @Volatile var radians: Float? = null
        private set
    @Volatile var pitchDeg: Float? = null
        private set
    @Volatile private var rollDeg: Float? = null
    val turnedDeg: Float?
        get() = pitchDeg?.let { p -> rollDeg?.let { r -> hypot(p, r) } }
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
                    // The line of sight: across gravity and the picture's horizontal (g × h).
                    val roll = if (hn > 0.1f) (e.values[0] * -g[2] * g[0] + e.values[1] * -g[2] * g[1] + e.values[2] * hn * hn) / (hn * norm) else 0f
                    if (lastNs != 0L) {
                        val dt = (e.timestamp - lastNs) / 1e9f
                        radians = (radians ?: 0f) + yaw * dt
                        pitchDeg = faded((pitchDeg ?: 0f) + Math.toDegrees(nod.toDouble()).toFloat() * dt, dt)
                        rollDeg = faded((rollDeg ?: 0f) + Math.toDegrees(roll.toDouble()).toFloat() * dt, dt)
                    }
                    lastNs = e.timestamp
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** Fading: what is left is the last couple of seconds' turn, not the drift of the gyroscope. */
    private fun faded(deg: Float, dt: Float): Float = deg * (1f - (dt / PITCH_FADE_S).coerceAtMost(1f))

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
        rollDeg = null
        tiltDeg = null
        up = null
        lastNs = 0L
    }

    /** The nod fades over this long: a bump is gone before the next, a slope is not kept. */
    private const val PITCH_FADE_S = 2f
}
