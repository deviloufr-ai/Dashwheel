package com.openauto.dash

import android.app.AppOpsManager
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.openauto.dash.link.PhoneFix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The linked phone's GPS in place of this unit's: a phone holds 40 satellites
 * within seconds where the K706 takes minutes to find 15. Each [PhoneFix] from
 * the companion is handed to Android as the "gps" provider's position (a test
 * provider, allowed through the privileged shell), so Maps, Waze and every
 * tile use it without knowing. When the fixes stop (the phone left, the link
 * dropped, no sky) the unit's own receiver is given back.
 */
object PhoneGps {
    private const val TAG = "PhoneGps"
    private const val PREFS = "phone_gps"
    private const val KEY_USE = "use"
    private const val GPS = LocationManager.GPS_PROVIDER

    /** The satellites behind the phone's fix in use: [used] in it, [seen] in view. */
    data class Satellites(val used: Int, val seen: Int)

    private val _enabled = MutableStateFlow(true)
    /** Settings → Phone: use the phone's GPS when it sends one. On unless turned off. */
    val enabled: StateFlow<Boolean> = _enabled

    private val _inUse = MutableStateFlow<Satellites?>(null)
    /** The phone's GPS stands in for this unit's right now; null while the unit's own is used. */
    val inUse: StateFlow<Satellites?> = _inUse

    private val _refused = MutableStateFlow(false)
    /** This unit would not let the position be replaced (no root, no ADB to allow it). */
    val refused: StateFlow<Boolean> = _refused

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private var appContext: Context? = null
    @Volatile private var lastFixAt = 0L
    @Volatile private var providerUp = false
    private var grantTried = false

    fun start(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        _enabled.value = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_USE, true)
        scope.launch {
            // A provider left from before a crash would hold the GPS on a frozen position.
            lock.withLock { removeProvider(app) }
            while (true) {
                delay(1_000)
                if (providerUp && PhoneGpsRules.stale(lastFixAt, SystemClock.elapsedRealtime())) {
                    Log.i(TAG, "no fix from the phone: the unit's own GPS again")
                    giveBack()
                }
            }
        }
    }

    fun setEnabled(context: Context, on: Boolean) {
        _enabled.value = on
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_USE, on).apply()
        if (!on) scope.launch { giveBack() }
    }

    /** A fix from the linked phone. */
    fun feed(fix: PhoneFix) {
        val app = appContext ?: return
        if (!_enabled.value || DemoMode.isOn || _refused.value) return
        if (!PhoneGpsRules.usable(fix.accuracyM, fix.ageMs)) return
        lastFixAt = SystemClock.elapsedRealtime()
        scope.launch {
            lock.withLock {
                if (!providerUp && !takeOver(app)) return@withLock
                push(app, fix)
            }
        }
    }

    /** The phone is gone: its last position must not stay. */
    fun linkDown() {
        scope.launch { giveBack() }
    }

    private suspend fun giveBack() = lock.withLock {
        val app = appContext ?: return@withLock
        if (providerUp) removeProvider(app)
    }

    @Suppress("DEPRECATION")
    private suspend fun takeOver(context: Context): Boolean {
        if (!mockAllowed(context)) {
            if (grantTried) return false
            grantTried = true
            // The Play edition has no shell: the driver picks Dashwheel as the mock location app in the developer options.
            val out = if (Edition.play) "no shell in the Play edition"
            else runCatching { DockShell.shell(context, "appops set ${context.packageName} android:mock_location allow") }
                .getOrElse { "failed: ${it.message}" }
            if (!mockAllowed(context)) {
                Log.w(TAG, "mock location not allowed: ${out.trim()}")
                _refused.value = true
                return false
            }
        }
        val lm = context.getSystemService(LocationManager::class.java) ?: return false
        return try {
            lm.addTestProvider(
                GPS, false, true, false, false, true, true, true,
                // Plain ints, inlined: the same values Android 10 knew as Criteria's.
                ProviderProperties.POWER_USAGE_LOW, ProviderProperties.ACCURACY_FINE
            )
            lm.setTestProviderEnabled(GPS, true)
            providerUp = true
            Log.i(TAG, "the phone's GPS stands in for this unit's")
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not stand in for the GPS", e)
            _refused.value = e is SecurityException
            false
        }
    }

    private fun push(context: Context, fix: PhoneFix) {
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        val (elapsedNs, wallMs) = PhoneGpsRules.fixTimes(fix.ageMs, SystemClock.elapsedRealtimeNanos(), System.currentTimeMillis())
        val location = Location(GPS).apply {
            latitude = fix.lat
            longitude = fix.lng
            accuracy = fix.accuracyM
            time = wallMs
            elapsedRealtimeNanos = elapsedNs
            fix.speedMps?.let { speed = it }
            fix.bearingDeg?.let { bearing = it }
            fix.altitudeM?.let { altitude = it }
            // Where Android itself puts the count, for apps that show it.
            extras = Bundle().apply { putInt("satellites", fix.satellites) }
        }
        try {
            lm.setTestProviderLocation(GPS, location)
            _inUse.value = Satellites(fix.satellites, fix.seen)
        } catch (e: RuntimeException) {
            // The provider went (another app, a revoked permission): taken over again on the next fix.
            Log.w(TAG, "fix not taken", e)
            providerUp = false
            _inUse.value = null
        }
    }

    private fun removeProvider(context: Context) {
        val lm = context.getSystemService(LocationManager::class.java)
        if (lm != null && mockAllowed(context)) runCatching { lm.removeTestProvider(GPS) }
        providerUp = false
        _inUse.value = null
    }

    private fun mockAllowed(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        return runCatching {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
    }
}

/** The decisions behind [PhoneGps], apart from Android. */
internal object PhoneGpsRules {
    /** A phone fix wider than this (indoors, in a bag) is no better than the unit's. */
    const val MAX_ACCURACY_M = 50f

    /** A fix older than this on arrival (the link stalled) would put the car behind itself. */
    const val MAX_AGE_MS = 3_000L

    /** No fix from the phone for this long: the unit's own GPS takes back over. */
    const val STALE_MS = 4_000L

    fun usable(accuracyM: Float, ageMs: Long): Boolean = accuracyM in 0f..MAX_ACCURACY_M && ageMs in 0..MAX_AGE_MS

    fun stale(lastFixAtMs: Long, nowMs: Long): Boolean = nowMs - lastFixAtMs > STALE_MS

    /**
     * The fix's time on this unit's two clocks, since start and on the wall,
     * from its age: the phone's clocks are not this unit's.
     */
    fun fixTimes(ageMs: Long, nowElapsedNs: Long, nowWallMs: Long): Pair<Long, Long> {
        val age = ageMs.coerceAtLeast(0)
        return (nowElapsedNs - age * 1_000_000L).coerceAtLeast(0) to nowWallMs - age
    }
}
