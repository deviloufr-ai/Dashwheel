package com.openauto.dash

import androidx.annotation.StringRes
import com.openauto.dash.vehicle.Indicators
import com.openauto.dash.vehicle.K
import com.openauto.dash.vehicle.Key
import com.openauto.dash.vehicle.Publisher
import kotlin.math.roundToInt

/*
 * What one of the driver's readings stands for in the car: its speed, its
 * fuel level, its handbrake... A reading set to feed one goes onto the
 * vehicle bus as that element, so the gauges, the car status, the doors
 * alert and the rest show it as they would the car's own. Asked for by a
 * Smart 451 driver who found the fuel level, doors, lights and indicators on
 * the car's CAN bus with a CANable, where OBD gave none of them.
 *
 * The on/off ones are on when the reading is not 0: "bit(A, 1)" for a bit,
 * "A == 2" for a value.
 */
enum class ReadingFeed(@StringRes val label: Int, val onOff: Boolean = false) {
    SPEED(R.string.mine_feed_speed),
    RPM(R.string.mine_feed_rpm),
    THROTTLE(R.string.mine_feed_throttle),
    ENGINE_LOAD(R.string.mine_feed_load),
    COOLANT(R.string.mine_feed_coolant),
    INTAKE(R.string.mine_feed_intake),
    FUEL_LEVEL(R.string.mine_feed_fuel),
    RANGE(R.string.mine_feed_range),
    VOLTS(R.string.mine_feed_volts),
    OUTSIDE_TEMP(R.string.mine_feed_outside),
    CONSUMPTION(R.string.mine_feed_consumption),
    HANDBRAKE(R.string.mine_feed_handbrake, onOff = true),
    REVERSE(R.string.signals_reverse, onOff = true),
    HEADLIGHTS(R.string.signals_headlights, onOff = true),
    INDICATOR_LEFT(R.string.mine_feed_left, onOff = true),
    INDICATOR_RIGHT(R.string.mine_feed_right, onOff = true),
    BELT_UNFASTENED(R.string.mine_feed_belt, onOff = true),
    DOOR_FRONT_LEFT(R.string.signals_front_left, onOff = true),
    DOOR_FRONT_RIGHT(R.string.signals_front_right, onOff = true),
    DOOR_REAR_LEFT(R.string.signals_rear_left, onOff = true),
    DOOR_REAR_RIGHT(R.string.signals_rear_right, onOff = true),
    TAILGATE(R.string.signals_tailgate, onOff = true),
    BONNET(R.string.signals_bonnet, onOff = true);

    /** The key it is put as; the doors and the indicators share theirs. */
    val key: Key<*>
        get() = when (this) {
            SPEED -> K.VEHICLE_SPEED
            RPM -> K.RPM
            THROTTLE -> K.THROTTLE
            ENGINE_LOAD -> K.ENGINE_LOAD
            COOLANT -> K.COOLANT_TEMP
            INTAKE -> K.INTAKE_TEMP
            FUEL_LEVEL -> K.FUEL_LEVEL
            RANGE -> K.RANGE
            VOLTS -> K.BATTERY_VOLTS
            OUTSIDE_TEMP -> K.OUTSIDE_TEMP
            CONSUMPTION -> K.FUEL_CONSUMPTION
            HANDBRAKE -> K.HANDBRAKE
            REVERSE -> K.REVERSE
            HEADLIGHTS -> K.HEADLIGHTS
            INDICATOR_LEFT, INDICATOR_RIGHT -> K.INDICATORS
            BELT_UNFASTENED -> K.DRIVER_BELT
            DOOR_FRONT_LEFT, DOOR_FRONT_RIGHT, DOOR_REAR_LEFT, DOOR_REAR_RIGHT, TAILGATE, BONNET -> K.DOORS
        }

    companion object {
        fun of(name: String?): ReadingFeed? = entries.firstOrNull { it.name == name }
    }
}

/** What the readings that feed the car add up to, key by key. Pure, unit-tested. */
object ReadingFeedRules {
    /**
     * Each key's value from the [feeds] that have one now: a number, true/false,
     * the doors, the indicators. A door or indicator nobody feeds is closed or
     * off while another is fed; a key none feeds is left out.
     */
    fun values(feeds: Map<ReadingFeed, Double>): Map<Key<*>, Any> {
        val out = LinkedHashMap<Key<*>, Any>()
        fun on(f: ReadingFeed) = feeds[f]?.let { it != 0.0 }
        for ((feed, v) in feeds) {
            when {
                feed.key == K.DOORS || feed.key == K.INDICATORS -> Unit
                feed.onOff -> out[feed.key] = v != 0.0
                else -> out[feed.key] = v
            }
        }
        val doors = listOf(ReadingFeed.DOOR_FRONT_LEFT, ReadingFeed.DOOR_FRONT_RIGHT, ReadingFeed.DOOR_REAR_LEFT, ReadingFeed.DOOR_REAR_RIGHT, ReadingFeed.TAILGATE, ReadingFeed.BONNET)
        if (doors.any { it in feeds }) {
            out[K.DOORS] = McuReader.DoorState(
                frontLeft = on(ReadingFeed.DOOR_FRONT_LEFT) == true, frontRight = on(ReadingFeed.DOOR_FRONT_RIGHT) == true,
                rearLeft = on(ReadingFeed.DOOR_REAR_LEFT) == true, rearRight = on(ReadingFeed.DOOR_REAR_RIGHT) == true,
                tailgate = on(ReadingFeed.TAILGATE) == true, bonnet = on(ReadingFeed.BONNET) == true
            )
        }
        if (ReadingFeed.INDICATOR_LEFT in feeds || ReadingFeed.INDICATOR_RIGHT in feeds) {
            out[K.INDICATORS] = Indicators(left = on(ReadingFeed.INDICATOR_LEFT) == true, right = on(ReadingFeed.INDICATOR_RIGHT) == true)
        }
        return out
    }
}

/**
 * Puts the fed values on the bus through [publisher]: a measurement every
 * time (it has a clock and expires when the reading stops), a state only when
 * it changed, as the other state sources do. A key no reading feeds any more,
 * or whose reading has no value, is withdrawn.
 */
internal class ReadingFeedPublisher(private val publisher: Publisher, private val correctSpeed: (Double) -> Double) {
    private val feeds = HashMap<ReadingFeed, Double>()
    private val put = HashMap<Key<*>, Any>()

    /** Every fed value now, replacing what was there. */
    @Synchronized
    fun setAll(now: Map<ReadingFeed, Double>) {
        feeds.clear()
        feeds.putAll(now)
        push(null)
    }

    /** One fed value just heard (a CAN frame), at once rather than at the next second. */
    @Synchronized
    fun set(feed: ReadingFeed, value: Double) {
        feeds[feed] = value
        push(feed.key)
    }

    /** Everything withdrawn: the readings went, or the demo took over. */
    @Synchronized
    fun clear() {
        feeds.clear()
        push(null)
    }

    /** Puts [only] that key, or every key when null. */
    private fun push(only: Key<*>?) {
        val values = ReadingFeedRules.values(feeds)
        val keys = if (only != null) setOf(only) else values.keys + put.keys
        for (key in keys) {
            val v = values[key]
            if (v == null) {
                if (put.remove(key) != null) withdraw(key)
                continue
            }
            val measurement = key is Key.Measurement<*>
            if (!measurement && put[key] == v) continue
            put[key] = v
            send(key, v)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun send(key: Key<*>, v: Any) {
        when (key) {
            K.VEHICLE_SPEED -> publisher.put(K.VEHICLE_SPEED, correctSpeed(v as Double))
            K.DOORS -> publisher.put(K.DOORS, v as McuReader.DoorState)
            K.INDICATORS -> publisher.put(K.INDICATORS, v as Indicators)
            is Key.Measurement<*> -> publisher.put(key as Key.Measurement<Double>, v as Double)
            is Key.State<*> -> publisher.put(key as Key.State<Boolean>, v as Boolean)
            else -> Unit
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun withdraw(key: Key<*>) {
        publisher.put(key as Key<Any>, null)
    }

    companion object {
        /** The driver's speed correction, as every car-speed source applies it. */
        fun corrected(kmh: Double): Double = SpeedCorrection.corrected(kmh.roundToInt()).toDouble()
    }
}
