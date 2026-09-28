package com.openauto.dash

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.widget.Toast
import androidx.annotation.StringRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/*
 * What a steering wheel button does with nothing to look at or tap: its
 * answer is said out loud (and shown for a moment, for a unit with no voice),
 * so a button never seems dead.
 */
internal object HandsFree {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** How long the station list may take: the driver is waiting for an answer. */
    private const val STATIONS_WAIT_MS = 10_000L

    /** Says [text] in the launcher's language, asked for or not by a switch: the driver pressed a button. */
    fun say(context: Context, text: String) {
        val app = context.applicationContext
        CarVoice.setContext(app)
        CarVoice.speak(text, AppLanguage.wrap(app).resources.configuration.locales[0])
        Toast.makeText(app, text, Toast.LENGTH_SHORT).show()
    }

    fun say(context: Context, @StringRes res: Int, vararg args: Any) =
        say(context, AppLanguage.wrap(context.applicationContext).resources.getString(res, *args))

    /**
     * Guidance to the cheapest station around for the car's fuel, named out
     * loud first. Where the prices aren't known (outside France, offline, no
     * position yet) it says so instead.
     */
    fun navigateToFuel(context: Context) {
        val app = context.applicationContext
        scope.launch {
            val here = position(app)
            if (here == null) {
                say(app, R.string.voice_no_position)
                return@launch
            }
            val best = if (DemoMode.isOn) null else withTimeoutOrNull(STATIONS_WAIT_MS) { FuelPriceRepo.cheapest(here.latitude, here.longitude) }
            if (best == null) {
                say(app, R.string.voice_no_fuel_prices)
                return@launch
            }
            val station = best.station
            if (!NavHandoff.start(app, station.lat, station.lng, station.label)) {
                say(app, R.string.places_no_nav_app)
                return@launch
            }
            val km = best.distanceKm.roundToInt().coerceAtLeast(1)
            val resources = AppLanguage.wrap(app).resources
            say(app, resources.getQuantityString(R.plurals.voice_fuel_station, km, station.name.ifBlank { station.town }, km))
        }
    }

    /** Where the car is: the dashboard's GPS feed if it's running, else the system's last fix. */
    @SuppressLint("MissingPermission")
    fun position(context: Context): Location? {
        LocationFeed.location.value?.let { return it }
        if (!hasLocationPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return runCatching {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        }.getOrNull()
    }
}
