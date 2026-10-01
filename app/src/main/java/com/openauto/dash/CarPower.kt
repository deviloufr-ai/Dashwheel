package com.openauto.dash

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/*
 * The ignition, as the QF firmware (ROCO K706) announces it to every app:
 * ACC on when the key turns, ACC off (then "ready to sleep") when it's
 * switched off. With Dashwheel on the unit's sleep whitelist it lives through
 * the off time, so it can act on both ends:
 *  - on: the adapter reconnects at once, and the startup briefing speaks
 *    right then (only after a real stop, see [briefOnIgnition]);
 *  - off: the car's spot is kept as where it's parked (the Parking tile, the
 *    companion's "where's my car"), and the OBD link is closed cleanly and
 *    left closed until the key turns again ([VehicleMonitor]).
 * Other units have no such broadcast; the briefing's heartbeat stands in there.
 */
object CarPower {
    private const val TAG = "CarPower"
    private const val ACTION_ACC_ON = "com.qf.action.ACC_ON"
    private const val ACTION_ACC_OFF = "com.qf.action.ACC_OFF"
    private const val ACTION_SLEEP_SOON = "com.qf.action.READY_GO_SLEEP_PRE"
    /** "true" / "false", written by the firmware at every turn of the key, before its broadcast. */
    private const val PROP_ACC = "sys.qf.is.acc.on"

    private const val PREFS = "car_power"
    private const val KEY_OFF_AT = "off_at"
    private const val KEY_ON_AT = "on_at"
    private const val KEY_BOOT = "boot"
    private const val KEY_LOST = "sleep_lost"
    private const val KEY_LOST_KEEP = 10

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false
    private var appContext: Context? = null

    private val _ignition = MutableStateFlow<Boolean?>(null)
    /** Ignition on / off as the unit last said it; null where the unit says nothing. */
    val ignition: StateFlow<Boolean?> = _ignition

    fun start(context: Context) {
        if (started) return
        val app = context.applicationContext
        val acc = systemProperty(PROP_ACC)
        // Only on the QF firmware: it sets this property from the MCU at boot.
        if (acc.isNullOrBlank()) return
        started = true
        appContext = app
        _ignition.value = acc == "true"
        noteBoot(app, acc == "true")
        val filter = IntentFilter().apply {
            addAction(ACTION_ACC_ON)
            addAction(ACTION_ACC_OFF)
            addAction(ACTION_SLEEP_SOON)
        }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    /**
     * A full boot right after a switch-off is a deep sleep that was lost. It
     * is logged and kept in the prefs (last [KEY_LOST_KEEP] of them, newest
     * first) so a driver can report when it happens and what came before.
     */
    private fun noteBoot(context: Context, ignitionOn: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val boot = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(-1)
        val lastBoot = prefs.getInt(KEY_BOOT, -1)
        val offAt = prefs.getLong(KEY_OFF_AT, 0L)
        val now = System.currentTimeMillis()
        val edit = prefs.edit().putInt(KEY_BOOT, boot)
        val reason = listOfNotNull(systemProperty("sys.boot.reason"), systemProperty("ro.boot.bootreason")).filter { it.isNotBlank() }
        DebugLog.note(
            context,
            "app start, boot $boot (last seen $lastBoot), uptime ${android.os.SystemClock.elapsedRealtime() / 1000} s, " +
                "ignition ${if (ignitionOn) "on" else "off"}, boot reason $reason"
        )
        // A boot comes up with the ignition already on, and no "on" broadcast
        // follows: it is marked here, or the next reboot of the same drive
        // would count as another lost sleep.
        val lost = boot != -1 && lastBoot != -1 && boot != lastBoot && offAt > 0 && offAt > prefs.getLong(KEY_ON_AT, 0L)
        if (ignitionOn) edit.putLong(KEY_ON_AT, now)
        if (lost) {
            val note = "boot $lastBoot->$boot, off ${java.util.Date(offAt)}, back ${java.util.Date(now)}, " +
                "${(now - offAt) / 60_000} min later, uptime ${android.os.SystemClock.elapsedRealtime() / 1000} s"
            Log.w(TAG, "deep sleep lost: $note")
            DebugLog.note(context, "deep sleep lost: $note")
            val kept = (listOf(note) + prefs.getString(KEY_LOST, "").orEmpty().split('\n').filter { it.isNotBlank() })
                .take(KEY_LOST_KEEP)
            edit.putString(KEY_LOST, kept.joinToString("\n"))
        }
        edit.apply()
    }

    /** Whether this unit announces its ignition (the QF firmware), so deep sleep is the unit's to lose. */
    val available: Boolean get() = started

    /** The recorded lost deep sleeps, newest first ([noteBoot]). */
    fun sleepLost(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LOST, "").orEmpty()
            .split('\n').filter { it.isNotBlank() }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_ACC_ON -> if (_ignition.value != true) switchedOn(context.applicationContext)
                // "Ready to sleep" comes after ACC off; either is the car switched off.
                ACTION_ACC_OFF, ACTION_SLEEP_SOON -> if (_ignition.value != false) switchedOff(context.applicationContext)
            }
        }
    }

    /**
     * Asks the unit itself, in case a broadcast was missed (the app asleep
     * with the unit): the adapter is not dialled while the ignition reads off,
     * so an "on" that never came would leave it undialled for the whole drive.
     */
    fun refresh() {
        val context = appContext ?: return
        val on = when (systemProperty(PROP_ACC)) {
            "true" -> true
            "false" -> false
            else -> return
        }
        if (on == _ignition.value) return
        if (on) switchedOn(context) else switchedOff(context)
    }

    private fun switchedOn(context: Context) {
        _ignition.value = true
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val offAt = prefs.getLong(KEY_OFF_AT, 0L).takeIf { it > 0 }
        prefs.edit().putLong(KEY_ON_AT, System.currentTimeMillis()).apply()
        Log.i(TAG, "ignition on")
        DebugLog.note(context, "ignition on, off since ${offAt?.let { (System.currentTimeMillis() - it) / 60_000 }} min")
        // Quiet was asked for the drive that ended.
        CarVoice.quiet = false
        scope.launch {
            kotlinx.coroutines.delay(20_000)
            DebugLog.snapshot(context, "20 s after ignition on")
        }
        EmbeddedApp.carStarted(context)
        MediaResume.carStarted(context)
        VehicleMonitor.connectSaved()
        if (briefOnIgnition(offAt, System.currentTimeMillis())) StartupBriefing.carStarted(context)
    }

    private fun switchedOff(context: Context) {
        _ignition.value = false
        val now = System.currentTimeMillis()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_OFF_AT, now).apply()
        Log.i(TAG, "ignition off")
        DebugLog.note(context, "ignition off")
        DebugLog.snapshot(context, "ignition off")
        EmbeddedApp.carStopped(context)
        MediaResume.carStopped(context)
        // A conversation with Gemini doesn't go on, microphone open, while the car sleeps.
        scope.launch { GeminiLive.end(context) }
        if (!DemoMode.isOn) parkingFix(context)?.takeIf { parkFixUsable(it.time, now) }?.let { ParkingStore.save(context, it) }
        scope.launch { runCatching { ObdBluetoothManager.disconnect() } }
    }

    /** Where the car is: the dashboard's GPS feed if it's running, else the system's last fix. */
    @SuppressLint("MissingPermission")
    private fun parkingFix(context: Context): Location? {
        LocationFeed.location.value?.let { return it }
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
    }

    @SuppressLint("PrivateApi")
    private fun systemProperty(name: String): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as String
    }.getOrNull()
}

/**
 * Whether switching on is a car start worth a briefing: after the car was
 * off at least [CarStart.OFF_GAP_MS] (a fuel stop doesn't re-brief), or when
 * it's not known how long it was off (the briefing's heartbeat decides then).
 */
internal fun briefOnIgnition(offAt: Long?, now: Long): Boolean =
    offAt != null && now - offAt >= CarStart.OFF_GAP_MS

/** A GPS fix older than this at switch-off isn't where the car stopped. */
internal const val PARK_FIX_MAX_AGE_MS = 3 * 60_000L

/** A fix is where the car stopped if it's recent at switch-off. */
internal fun parkFixUsable(fixTime: Long, now: Long): Boolean =
    fixTime > 0 && now - fixTime in 0..PARK_FIX_MAX_AGE_MS
