package com.openauto.dash.companion

import android.content.Context
import com.openauto.dash.link.FuelFill
import com.openauto.dash.link.FuelFills
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The car's refuels, as the head unit reports them ([FuelFill]): the litres,
 * the price where the pump was known, the mileage. Kept like [DriveJournal],
 * newest first, for the real consumption and the fuel spending.
 */
object FuelJournal {
    private const val PREFS = "fuel_journal"
    private const val KEY = "fills"

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
    fun update(context: Context, fill: FuelFill) = write(context, FuelFills.merge(load(context), fill))

    /** Everything the head unit knows, as the link comes up. */
    @Synchronized
    fun sync(context: Context, fills: List<FuelFill>) = write(context, FuelFills.mergeAll(load(context), fills))

    private fun write(context: Context, fills: List<FuelFill>) {
        if (fills == _fills.value) return
        _fills.value = fills
        prefs(context).edit().putString(KEY, FuelFills.encode(fills)).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
