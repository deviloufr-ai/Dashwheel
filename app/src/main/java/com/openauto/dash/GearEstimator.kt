package com.openauto.dash

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.combine
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/*
 * The gear the car is in. No feed says it outright: reverse comes from the
 * head unit's reversing signal (the camera the ROM brings up), and the forward
 * gears from the engine's revs against the road speed, each gear turning the
 * engine a set number of rpm per km/h. The table starts from the car's gearbox
 * (the C4 Picasso's BMP6 figures, else a typical six-speed spread) and settles
 * on the car's own ratios as it drives. Rolling with the engine at idle and no
 * gear matching reads as free-wheeling (neutral or the clutch open). A robotised
 * box's A/M mode is not in any feed Dashwheel reads.
 */

internal sealed interface Gear {
    data object Reverse : Gear
    /** Forward gear [n], from 1. */
    data class Forward(val n: Int) : Gear
    /** Moving with no gear engaged: neutral, or the clutch open. */
    data object Free : Gear
    data object Stopped : Gear
    /** No revs or no speed to tell by. */
    data object Unknown : Gear
}

internal data class GearReading(val gear: Gear, val rpm: Int?, val speedKmh: Int?, val gears: Int)

internal object GearEstimator {

    private const val PREFS = "gear_ratios"
    private const val KEY = "rpm_per_kmh"

    /** Below this, the car is standing (the gear can't be told). */
    const val STOPPED_KMH = 4

    /** Rpm per km/h within this share of a gear's figure reads as that gear. */
    private const val MATCH = 0.12

    /** Tighter still for a reading that teaches the table. */
    private const val LEARN_MATCH = 0.07

    /** How far one reading moves the table. */
    private const val LEARN_RATE = 0.02

    /** Revs at or under this with no gear matching: free-wheeling. */
    private const val IDLE_RPM = 1_100

    /** The C4 Picasso 1.6 HDi's BMP6, rpm per km/h in gears 1 to 6. */
    private val BMP6 = listOf(118.0, 64.0, 41.0, 30.0, 23.0, 19.0)

    /** The first table for a box of [gears]: the BMP6's for six gears, else a spread between the same ends. */
    fun seeds(gears: Int): List<Double> {
        val n = gears.coerceIn(4, 9)
        if (n == BMP6.size) return BMP6
        val first = BMP6.first()
        val last = BMP6.last()
        return List(n) { i -> first * (last / first).pow(i / (n - 1.0)) }
    }

    /** The gear [rpm] at [speedKmh] points to in [table]; null when none is close. */
    fun classify(rpm: Int, speedKmh: Int, table: List<Double>): Int? {
        if (speedKmh < STOPPED_KMH || rpm <= 0) return null
        val r = rpm.toDouble() / speedKmh
        val (i, off) = table.withIndex().map { (i, c) -> i to abs(ln(r / c)) }.minByOrNull { it.second } ?: return null
        return if (off <= MATCH) i + 1 else null
    }

    /** [table] nudged towards a steady reading in gear [gear], kept in order (each gear shorter than the one before). */
    fun learn(table: List<Double>, gear: Int, rpm: Int, speedKmh: Int): List<Double> {
        if (speedKmh < 15 || gear !in 1..table.size) return table
        val r = rpm.toDouble() / speedKmh
        val c = table[gear - 1]
        if (abs(ln(r / c)) > LEARN_MATCH) return table
        val next = table.toMutableList().also { it[gear - 1] = c + (r - c) * LEARN_RATE }
        val ordered = next.zipWithNext().all { (a, b) -> a > b }
        return if (ordered) next else table
    }

    /** What to show for one moment of the feeds. */
    fun read(reversing: Boolean, rpm: Int?, speedKmh: Int?, table: List<Double>): Gear = when {
        reversing -> Gear.Reverse
        speedKmh == null -> Gear.Unknown
        speedKmh < STOPPED_KMH -> Gear.Stopped
        rpm == null || rpm <= 0 -> Gear.Unknown
        else -> classify(rpm, speedKmh, table)?.let { Gear.Forward(it) } ?: if (rpm <= IDLE_RPM) Gear.Free else Gear.Unknown
    }

    private var table: List<Double>? = null

    fun table(context: Context, gears: Int): List<Double> {
        table?.takeIf { it.size == gears.coerceIn(4, 9) }?.let { return it }
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?.split(',')?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == gears.coerceIn(4, 9) }
        return (saved ?: seeds(gears)).also { table = it }
    }

    fun save(context: Context, next: List<Double>) {
        table = next
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, next.joinToString(",")).apply()
    }
}

/**
 * The gear, followed while the tile shows. A moment between gears (the
 * clutch slipping mid-shift) keeps the last gear shown for [HOLD_MS] rather
 * than flickering to nothing.
 */
@Composable
internal fun rememberGear(): State<GearReading> {
    val context = LocalContext.current
    val gears = CarProfileStore.current.gears ?: 6
    val state = remember { mutableStateOf(GearReading(Gear.Unknown, null, null, gears)) }
    LaunchedEffect(Unit) { CarBox.start(context) }
    LaunchedEffect(gears) {
        var table = GearEstimator.table(context, gears)
        var lastGear: Gear = Gear.Unknown
        var lastGearAt = 0L
        var learned = 0
        combine(ObdBluetoothManager.connectionState, ObdBluetoothManager.data, CarBox.body, CarBox.reversing) { conn, obd, _, reversing ->
            val box = CarBox.freshBody()
            val connected = conn == ObdConnectionState.CONNECTED
            val rpm = if (connected && obd.rpm > 0) obd.rpm else box?.rpm
            val speed = if (connected) obd.speedKmh else box?.speedKmh
            Triple(reversing, rpm, speed)
        }.collect { (reversing, rpm, speed) ->
            val now = System.currentTimeMillis()
            var gear = GearEstimator.read(reversing, rpm, speed, table)
            if (gear is Gear.Forward && rpm != null && speed != null) {
                table = GearEstimator.learn(table, gear.n, rpm, speed)
                // Kept now and then, not at every reading.
                if (++learned % 200 == 0) GearEstimator.save(context, table)
            }
            if (gear is Gear.Forward || gear is Gear.Reverse) {
                lastGear = gear
                lastGearAt = now
            } else if (gear is Gear.Unknown && now - lastGearAt < HOLD_MS) {
                gear = lastGear
            }
            state.value = GearReading(gear, rpm, speed, gears)
        }
    }
    return state
}

private const val HOLD_MS = 1_500L
