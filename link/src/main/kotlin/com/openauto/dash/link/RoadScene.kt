package com.openauto.dash.link

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Phone → head unit, about ten times a second while Road watch runs on the
 * phone: what its camera sees of the road ahead (RoadWatch in the companion).
 * The head unit, which knows the car's own speed and its indicators, decides
 * from it what to warn about (RoadRules). It stops coming when Road watch is
 * closed; a car too old to know it skips it.
 */
@Serializable
@SerialName("road")
data class RoadScene(
    /**
     * Where the car is in its lane: 0 its usual place, about ±0.25 a wheel on
     * the line, positive to the right. Null while the lane isn't seen.
     */
    val offset: Float? = null,
    /** The lane's lines seen, 0 to 2. */
    val lines: Int = 0,
    /** About how far the vehicle ahead in the lane is, metres; null for none. */
    val leadM: Float? = null,
    /** How fast it comes nearer, m/s (negative: it pulls away); null while not yet known. */
    val closingMps: Float? = null,
    /** Seconds until the car reaches it at this rate; null when not closing. */
    val ttcS: Float? = null,
    /** What it is: "car", "truck", "bus", "motorcycle", "bicycle". */
    val leadKind: String? = null,
    /** The phone's GPS speed, for a car that doesn't give its own. */
    val speedKmh: Float? = null,
    /** The phone has the model that finds vehicles: without it, only the lane is watched. */
    val vehicles: Boolean = true,
    /**
     * A speed limit just read off a sign, km/h: sent for a couple of seconds
     * after it is read, null otherwise. The car holds it for the road ahead.
     */
    val signKmh: Int? = null,
    /**
     * The sign that ends a limit (the number struck through, or the bars of
     * "end of all restrictions") was just passed: true for a couple of
     * seconds after it is seen. The car drops the sign's limit and goes back
     * to the map's. A car too old to know it keeps the limit, as before.
     */
    val signEnd: Boolean = false
) : LinkMessage

/**
 * Head unit → phone, a few times a second while Road watch runs on the
 * phone: what the car says of itself, for the phone to write into its
 * recording of the road (RoadRecorder) and keep beside a clip. Only what the
 * car gives; a field the car doesn't say stays null. A phone too old to know
 * it skips it.
 */
@Serializable
@SerialName("car_telemetry")
data class CarTelemetry(
    /** The car's own speed (the OBD adapter, the CAN box), not the phone's GPS. */
    val speedKmh: Int? = null,
    val rpm: Int? = null,
    val turnLeft: Boolean = false,
    val turnRight: Boolean = false,
    val hazard: Boolean = false,
    /** The headlights (dipped or main beam) are on. */
    val lights: Boolean = false
) : LinkMessage

/**
 * Head unit → phone: keep a clip of the road around now (the hazard lights
 * went on, a button was pressed). [reason] names why, for the clip's notes:
 * "hazard", "button".
 */
@Serializable
@SerialName("keep_clip")
data class KeepClip(val reason: String) : LinkMessage
