package com.openauto.dash

import kotlin.math.abs
import kotlin.math.roundToInt

// Citroën C4 Picasso rough figures, used where the car profile doesn't say better.
internal const val TANK_LITERS = 60.0

internal const val AVG_L_PER_100KM = 6.5

/**
 * Fuel level and the range it gives, and where the level came from ("CANbox" or
 * "OBD"). [rangeFromCar] means [rangeKm] is the car's own trip-computer figure
 * rather than our estimate; [percentEstimated] means the level was worked back
 * from that range because no fuel signal is known.
 */
internal class FuelInfo(
    val percent: Int,
    val rangeKm: Int,
    val source: String,
    val liters: Double = percent / 100.0 * TANK_LITERS,
    val avgUse: Double = AVG_L_PER_100KM,
    val rangeFromCar: Boolean = false,
    val percentEstimated: Boolean = false,
    val tankL: Double = TANK_LITERS
)

/** Plausible average consumption; outside it the fuel reading and the range disagree. */
private val SANE_L_PER_100KM = 3.0..20.0

/**
 * Combines what's known: the learned CANbox fuel level [canFuel], the OBD fuel
 * PID [obdFuel] (0 = none), and the car's own distance to empty [canRange].
 * [tankL] and [typicalUse] are the car profile's tank and everyday consumption.
 * Pure, so it's unit-tested. null when nothing is known yet.
 */
internal fun fuelInfo(
    canFuel: Int?,
    obdFuel: Int,
    canRange: Int?,
    tankL: Double = TANK_LITERS,
    typicalUse: Double = AVG_L_PER_100KM
): FuelInfo? {
    // An empty tank while the car still counts on driving on: the car sends no
    // level at all (its car app reads 0 L on some models), so it's left out.
    val can = canFuel?.takeUnless { it == 0 && canRange != null && canRange > 0 }
    val measured = can ?: obdFuel.takeIf { it > 0 }
    val source = if (can != null || measured == null) "CANbox" else "OBD"
    if (canRange == null) {
        val pct = measured ?: return null
        val liters = pct / 100.0 * tankL
        return FuelInfo(pct, (liters / typicalUse * 100).toInt(), source, liters, typicalUse, tankL = tankL)
    }
    if (measured != null) {
        // The car's range over what's in the tank is the consumption it's counting on.
        val liters = measured / 100.0 * tankL
        val implied = if (canRange > 0) liters / canRange * 100 else null
        val avg = implied?.takeIf { it in SANE_L_PER_100KM } ?: typicalUse
        return FuelInfo(measured, canRange, source, liters, avg, rangeFromCar = true, tankL = tankL)
    }
    // Only the range: work the level back from it at the usual consumption.
    val liters = (canRange * typicalUse / 100).coerceAtMost(tankL)
    val pct = (liters / tankL * 100).roundToInt().coerceIn(0, 100)
    return FuelInfo(pct, canRange, source, liters, typicalUse, rangeFromCar = true, percentEstimated = true, tankL = tankL)
}

/** [fuelInfo] with the saved car's tank and consumption. */
internal fun carFuelInfo(canFuel: Int?, obdFuel: Int, canRange: Int?): FuelInfo? =
    CarProfileStore.current.let { fuelInfo(canFuel, obdFuel, canRange, it.tank, it.typicalUse) }

/**
 * Where the car's distance to empty sits in the CANbox stream: a 16-bit word at
 * [index] (and index + 1) of frame [key], in [bigEndian] order, in units of
 * 1/[scale] km (the head unit shows "520.0km", so tenths are possible).
 */
internal data class RangeMapping(val key: String, val index: Int, val bigEndian: Boolean, val scale: Int) {

    /** The range in km from this frame's bytes, or null when absent or implausible. */
    fun decode(bytes: List<Int>): Int? {
        if (bytes.size <= index + 1) return null
        val hi = if (bigEndian) bytes[index] else bytes[index + 1]
        val lo = if (bigEndian) bytes[index + 1] else bytes[index]
        val raw = hi shl 8 or lo
        // 0xFFFF / 0x7FFF are the usual "not available" fillers.
        if (raw == 0xFFFF || raw == 0x7FFF) return null
        val km = (raw.toDouble() / scale).roundToInt()
        return km.takeIf { it <= MAX_RANGE_KM }
    }

    companion object {
        const val MAX_RANGE_KM = 2000

        /**
         * Every 16-bit word in [frames] that reads [km] (±1) in either byte order,
         * as km or tenths of a km. Exact hits first, then the usual encoding
         * (whole km, big-endian, as PSA frames are) ahead of the rest.
         */
        fun candidates(frames: Map<String, List<Int>>, km: Int): List<RangeMapping> {
            if (km !in 1..MAX_RANGE_KM) return emptyList()
            val out = ArrayList<Pair<RangeMapping, Double>>()
            for ((key, bytes) in frames) {
                for (i in 0 until bytes.size - 1) {
                    for (be in listOf(true, false)) {
                        for (scale in listOf(1, 10)) {
                            val m = RangeMapping(key, i, be, scale)
                            val hi = if (be) bytes[i] else bytes[i + 1]
                            val lo = if (be) bytes[i + 1] else bytes[i]
                            val raw = hi shl 8 or lo
                            if (raw == 0 || raw == 0xFFFF) continue
                            val err = abs(raw.toDouble() / scale - km)
                            if (err <= 1.0) out.add(m to err)
                        }
                    }
                }
            }
            return out.sortedWith(
                compareBy<Pair<RangeMapping, Double>>({ it.second }, { it.first.scale }, { !it.first.bigEndian })
            ).map { it.first }.take(12)
        }
    }
}
