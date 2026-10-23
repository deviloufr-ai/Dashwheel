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
    val vehicles: Boolean = true
) : LinkMessage
