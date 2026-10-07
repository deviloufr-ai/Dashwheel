package com.openauto.dash.companion

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.openauto.dash.link.PhoneFix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The phone's GPS for the car: a phone holds 40 satellites within seconds
 * where a head unit takes minutes to find 15. While a car is linked, each GPS
 * fix goes to it as a [PhoneFix], about once a second; the car uses it in
 * place of its own receiver. Needs the location allowed all the time, since
 * the sharing starts in the background when the car's Bluetooth connects.
 */
object PhoneGpsShare {
    private const val INTERVAL_MS = 1_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watch: Job? = null
    private var manager: LocationManager? = null
    private var used = 0
    private var seen = 0

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = send(location)
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    private val satellites = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            seen = status.satelliteCount
            used = (0 until status.satelliteCount).count { status.usedInFix(it) }
        }
    }

    /** Location allowed, all the time: what the car's position needs. */
    fun allowed(context: Context): Boolean =
        granted(context, Manifest.permission.ACCESS_FINE_LOCATION) && granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    /** Only the first half allowed: Android asks for "all the time" in a second step. */
    fun allowedWhileInUse(context: Context): Boolean = granted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    fun start(context: Context) {
        if (watch != null) return
        val app = context.applicationContext
        watch = scope.launch {
            LinkServer.state.collect { state -> if (state is LinkState.Connected) listen(app) else quiet() }
        }
    }

    fun stop() {
        watch?.cancel()
        watch = null
        quiet()
    }

    /** The permission was just given: start at once if a car is linked. */
    fun recheck(context: Context) {
        if (LinkServer.state.value is LinkState.Connected) listen(context.applicationContext)
    }

    @SuppressLint("MissingPermission")
    private fun listen(context: Context) {
        if (manager != null || !allowed(context)) return
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        val main = Looper.getMainLooper()
        val ok = runCatching { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, INTERVAL_MS, 0f, listener, main) }.isSuccess
        if (!ok) return
        runCatching { lm.registerGnssStatusCallback(satellites, Handler(main)) }
        manager = lm
    }

    private fun quiet() {
        val lm = manager ?: return
        runCatching { lm.removeUpdates(listener) }
        runCatching { lm.unregisterGnssStatusCallback(satellites) }
        manager = null
        used = 0
        seen = 0
    }

    private fun send(l: Location) {
        LinkServer.send(
            PhoneFix(
                lat = l.latitude,
                lng = l.longitude,
                accuracyM = if (l.hasAccuracy()) l.accuracy else 999f,
                ageMs = ((SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000).coerceAtLeast(0),
                speedMps = l.speed.takeIf { l.hasSpeed() },
                bearingDeg = l.bearing.takeIf { l.hasBearing() },
                altitudeM = l.altitude.takeIf { l.hasAltitude() },
                satellites = used,
                seen = seen
            )
        )
    }

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
