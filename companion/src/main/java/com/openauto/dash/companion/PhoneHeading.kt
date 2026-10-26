package com.openauto.dash.companion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Which way the car points, from the phone's gyroscope turned about gravity:
 * radians, turning clockwise as the car turns right (a compass's sense), from
 * wherever Road watch started. The recorder writes it into a clip's notes
 * (headingDeg): replayed, it tells a parked car beside a bend from the slide
 * of the turn (draft PR #85), before the phone acts on it. Null without a
 * gyroscope, or while Road watch is off.
 */
object PhoneHeading {
    @Volatile var radians: Float? = null
        private set

    private var up: FloatArray? = null
    private var lastNs = 0L

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            when (e.sensor.type) {
                // Gravity's sensor points up, away from the ground.
                Sensor.TYPE_GRAVITY -> up = e.values.copyOf()
                Sensor.TYPE_GYROSCOPE -> {
                    val g = up ?: return
                    val norm = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2])
                    if (norm < 1f) return
                    // The turn about the vertical: the gyroscope's anticlockwise turn about "up", made clockwise.
                    val rate = -(e.values[0] * g[0] + e.values[1] * g[1] + e.values[2] * g[2]) / norm
                    if (lastNs != 0L) radians = (radians ?: 0f) + rate * (e.timestamp - lastNs) / 1e9f
                    lastNs = e.timestamp
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

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
        up = null
        lastNs = 0L
    }
}
