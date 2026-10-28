package com.openauto.dash.companion

import android.content.Context
import com.openauto.dash.link.FuelFill
import com.openauto.dash.link.FuelFills
import com.openauto.dash.link.FuelPrice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The car's refuels, as the head unit reports them ([FuelFill]): the litres,
 * the price where the pump was known, the mileage. Kept like [DriveJournal],
 * newest first, for the real consumption and the fuel spending.
 *
 * The price paid at a refuel can be typed here ([setPrice]); it stays waiting
 * until the head unit has it too (it echoes the refuel back, no longer a
 * guess), and goes again each time the link comes up meanwhile.
 */
object FuelJournal {
    private const val PREFS = "fuel_journal"
    private const val KEY = "fills"
    private const val PENDING = "pending_prices"

    private val _fills = MutableStateFlow<List<FuelFill>>(emptyList())
    val fills: StateFlow<List<FuelFill>> = _fills
    private var loaded = false

    @Synchronized
    fun load(context: Context): List<FuelFill> {
        if (!loaded) {
            _fills.value = prefs(context).getString(KEY, null)?.let { FuelFills.decode(it) }.orEmpty()
            loaded = true
        }
        return _fills.value
    }

    @Synchronized
    fun update(context: Context, fill: FuelFill) {
        if (!fill.priceGuessed) forget(context, fill.at)
        write(context, withPending(context, FuelFills.merge(load(context), fill)))
    }

    /** Everything the head unit knows, as the link comes up; the prices typed meanwhile go to it. */
    @Synchronized
    fun sync(context: Context, fills: List<FuelFill>) {
        // Priced on the head unit, or no longer kept there: nothing left to send.
        fills.filter { !it.priceGuessed }.forEach { forget(context, it.at) }
        if (fills.isNotEmpty()) pending(context).keys.filter { at -> fills.none { it.at == at } }.forEach { forget(context, it) }
        write(context, withPending(context, FuelFills.mergeAll(load(context), fills)))
        pending(context).forEach { (at, price) -> LinkServer.send(FuelPrice(at, price)) }
    }

    /** The driver typed what they paid a litre at the refuel made at [at]. */
    @Synchronized
    fun setPrice(context: Context, at: Long, pricePerL: Double) {
        if (pricePerL <= 0) return
        prefs(context).edit().putString(PENDING, encode(pending(context) + (at to pricePerL))).apply()
        write(context, withPending(context, load(context)))
        LinkServer.send(FuelPrice(at, pricePerL))
    }

    private fun withPending(context: Context, fills: List<FuelFill>): List<FuelFill> =
        pending(context).entries.fold(fills) { acc, (at, price) -> FuelFills.priced(acc, at, price) ?: acc }

    private fun forget(context: Context, at: Long) {
        val pending = pending(context)
        if (at in pending) prefs(context).edit().putString(PENDING, encode(pending - at)).apply()
    }

    /** "at=price;at=price". */
    private fun pending(context: Context): Map<Long, Double> =
        prefs(context).getString(PENDING, null).orEmpty().split(';').mapNotNull { pair ->
            val (at, price) = pair.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
            (at.toLongOrNull() ?: return@mapNotNull null) to (price.toDoubleOrNull() ?: return@mapNotNull null)
        }.toMap()

    private fun encode(pending: Map<Long, Double>): String = pending.entries.joinToString(";") { "${it.key}=${it.value}" }

    private fun write(context: Context, fills: List<FuelFill>) {
        if (fills == _fills.value) return
        _fills.value = fills
        prefs(context).edit().putString(KEY, FuelFills.encode(fills)).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
