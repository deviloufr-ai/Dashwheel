package com.openauto.dash

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.openauto.dash.link.CallState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/*
 * Calls through the head unit's own Bluetooth, on the QF firmware of ROCO
 * K706 units (worked out from the firmware, not a public API). Its Bluetooth
 * app, com.qf.bluetooth, broadcasts every call's state to anyone
 * ([ACTION_CALL_STATE]), and its TechBTService answers, declines and hangs up
 * for any app that binds it. That feeds the call card with no companion.
 *
 * Its own call pop-up steps aside while a phone-link app says it has the call
 * (the zlink broadcast, [ACTION_ZLINK]): only the window is skipped, the
 * steering wheel buttons, the sound and the microphone work as before. While
 * that is on, the car's seek buttons act as answer / hang up, so it is always
 * turned off again when the call ends.
 */
object HeadUnitPhone {
    private const val TAG = "HeadUnitPhone"
    const val BT_PACKAGE = "com.qf.bluetooth"

    private const val ACTION_CALL_STATE = "com.qf.action.bt.call.state"
    /** Sent by the Bluetooth app's call screen as it opens and closes. */
    private const val ACTION_CALL_START = "com.qf.action.PHONE_CALL_START"
    private const val ACTION_CALL_END = "com.qf.action.PHONE_CALL_END"
    private const val STATE_INCOMING = 1
    private const val STATE_OUTGOING = 2
    private const val STATE_ACTIVE = 3
    private const val STATE_ENDED = 4

    private const val ACTION_SERVICE = "com.qf.bluetooth.TechBTService"
    private const val DESCRIPTOR = "com.qf.bluetoothsdk.aidl.BluetoothBinder"
    private const val TX_REJECT = 1
    private const val TX_ACCEPT = 2
    private const val TX_HANG_UP = 3

    private const val ACTION_ZLINK = "com.zjinnova.zlink"
    /** The signal again this long after each call event: the call screen may not have been listening yet. */
    private val RESEND_MS = longArrayOf(400, 1_500, 4_000)
    /** At most this many signals per call, so nothing can make it loop. */
    private const val MAX_SIGNALS_PER_CALL = 8
    /** A call-screen "start" this soon after our own signal is its echo, not news. */
    private const val ECHO_MS = 1_500L
    private const val PREFS = "head_unit_phone"
    /** Set while the ROM's pop-up is told to step aside, so a crash mid-call can't leave the seek keys as phone keys. */
    private const val KEY_ROM_ASIDE = "rom_aside"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false
    @Volatile private var binder: IBinder? = null

    private val _call = MutableStateFlow<PhoneCall?>(null)
    /** The call on the head unit's Bluetooth, or null. */
    val call: StateFlow<PhoneCall?> = _call

    /** Whether this unit runs the QF Bluetooth app this talks to. */
    fun available(context: Context): Boolean = isPackageInstalled(context, BT_PACKAGE)

    fun start(context: Context) {
        if (started) return
        val app = context.applicationContext
        if (!available(app)) return
        started = true
        // Left aside by a run that ended mid-call: give the ROM its keys back.
        if (romAside(app) && systemProperty("sys.qf.call_state") != "true") bringRomBack(app)
        val filter = IntentFilter().apply {
            addAction(ACTION_CALL_STATE)
            addAction(ACTION_CALL_START)
            addAction(ACTION_CALL_END)
        }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        bind(app)
    }

    fun accept() = transact(TX_ACCEPT)
    fun decline() = transact(TX_REJECT)
    fun hangUp() = transact(TX_HANG_UP)

    /** The call card no longer replaces the ROM's: its pop-up comes back, even mid-call. */
    fun releaseRomScreen(context: Context) {
        if (romAside(context)) bringRomBack(context)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val app = context.applicationContext
            when (intent.action) {
                ACTION_CALL_STATE -> onCallState(app, intent)
                // Its window is opening right now: the moment to set it aside.
                ACTION_CALL_START -> if (SystemClock.elapsedRealtime() - lastSignalAt > ECHO_MS) setAside(app)
                ACTION_CALL_END -> callOver(app)
            }
        }
    }

    private fun onCallState(context: Context, intent: Intent) {
        run {
            val state = intent.getIntExtra("callState", -1)
            val number = intent.getStringExtra("callNumber")?.takeIf { it.isNotBlank() }
            val name = intent.getStringExtra("callName")?.takeIf { it.isNotBlank() && it != number }
            val before = _call.value
            val now = SystemClock.elapsedRealtime()
            _call.value = when (state) {
                STATE_INCOMING -> PhoneCall(CallState.Phase.RINGING, number, name, null, now, canControl = true, viaHeadUnit = true)
                STATE_OUTGOING -> PhoneCall(CallState.Phase.ACTIVE, number, name, null, now, canControl = true, viaHeadUnit = true, dialing = true)
                STATE_ACTIVE -> PhoneCall(
                    CallState.Phase.ACTIVE, number ?: before?.number, name ?: before?.name, null,
                    // Answered once: the duration keeps counting through repeated updates.
                    answeredAt = if (before?.phase == CallState.Phase.ACTIVE && before.dialing == false) before.answeredAt else now,
                    canControl = true, viaHeadUnit = true
                )
                STATE_ENDED -> null
                else -> return
            }
            Log.d(TAG, "call state $state")
            if (_call.value != null) setAside(context) else callOver(context)
        }
    }

    // The set-aside signal, per call (main thread: broadcasts and the handler).
    private val main = Handler(Looper.getMainLooper())
    private var inCall = false
    private var signals = 0
    private var lastSignalAt = 0L

    /**
     * Sets the ROM's call screen aside for this call: now, and again a
     * moment later. The signal removes its window if it's up (and then no
     * longer holds, but the screen isn't shown twice in one call) or keeps it
     * from coming up; repeating it covers a signal sent before the Bluetooth
     * app's call screen existed to hear it, or before the overlay permission
     * came through. Every call event sets it again, up to [MAX_SIGNALS_PER_CALL].
     */
    private fun setAside(context: Context) {
        inCall = true
        signalAside(context)
        RESEND_MS.forEach { delay -> main.postDelayed({ if (inCall) signalAside(context) }, delay) }
    }

    private fun signalAside(context: Context) {
        // Only while Dashwheel's own card can show over other apps: never no call screen at all.
        if (RomPopups.Kind.CALL !in RomPopups.replaced.value || !Settings.canDrawOverlays(context)) return
        if (signals >= MAX_SIGNALS_PER_CALL) return
        signals++
        lastSignalAt = SystemClock.elapsedRealtime()
        setRomAside(context, true)
    }

    private fun callOver(context: Context) {
        inCall = false
        signals = 0
        main.removeCallbacksAndMessages(null)
        if (romAside(context)) bringRomBack(context)
    }

    private fun bringRomBack(context: Context) = setRomAside(context, false)

    /** Tells the Bluetooth app a phone-link app has the call ([ACTION_ZLINK]), or no longer. */
    private fun setRomAside(context: Context, aside: Boolean) {
        context.sendBroadcast(Intent(ACTION_ZLINK).putExtra("status", if (aside) "PHONE_CALL_ON" else "PHONE_CALL_OFF"))
        // Kept only to give the ROM its keys back after a run that ended mid-call ([start]).
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ROM_ASIDE, aside).apply()
        Log.i(TAG, if (aside) "ROM call screen set aside" else "ROM call screen back")
    }

    private fun romAside(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ROM_ASIDE, false)

    private fun bind(context: Context) {
        val intent = Intent(ACTION_SERVICE).setPackage(BT_PACKAGE)
        val ok = runCatching { context.bindService(intent, connection, Context.BIND_AUTO_CREATE) }
            .onFailure { Log.w(TAG, "bind failed", it) }
            .getOrDefault(false)
        if (!ok) Log.w(TAG, "the Bluetooth service can't be bound: calls are shown, not controlled")
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service
            Log.i(TAG, "Bluetooth service bound")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // The system binds it again when the service comes back.
            binder = null
        }
    }

    /** One call into the Bluetooth app's service; it does nothing unless a call is on. */
    private fun transact(code: Int) {
        scope.launch {
            val b = binder ?: run { Log.w(TAG, "Bluetooth service not bound"); return@launch }
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                b.transact(code, data, reply, 0)
                reply.readException()
            } catch (e: Exception) {
                Log.w(TAG, "call command $code failed", e)
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
    }

    @SuppressLint("PrivateApi")
    private fun systemProperty(name: String): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as String
    }.getOrNull()
}
