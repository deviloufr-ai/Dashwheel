package com.openauto.dash

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.maplibre.navigation.core.location.Location
import org.maplibre.navigation.core.location.engine.LocationEngine
import org.maplibre.navigation.core.location.engine.MapLibreLocationEngine
import org.maplibre.navigation.core.location.toLocation

/**
 * The guidance's GPS feed. The library's own engine registers a lambda that only has
 * onLocationChanged; Android 10 has no default for the other three, so losing the GPS
 * crashed the app with AbstractMethodError (issues #156, #160). This listener has all four.
 */
internal class NavLocationEngine(context: Context, private val looper: Looper) :
    MapLibreLocationEngine(context, looper) {

    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @SuppressLint("MissingPermission")
    override fun listenToLocation(request: LocationEngine.Request): Flow<Location> = callbackFlow {
        val listener = object : LocationListener {
            override fun onLocationChanged(location: android.location.Location) {
                trySend(location.toLocation())
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        manager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            request.minIntervalMilliseconds,
            request.minUpdateDistanceMeters,
            listener,
            looper,
        )
        awaitClose { manager.removeUpdates(listener) }
    }
}
