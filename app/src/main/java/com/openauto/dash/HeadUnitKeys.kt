package com.openauto.dash

import android.annotation.SuppressLint
import android.content.Context
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log
import android.view.KeyEvent

/*
 * The head unit's own keys (steering wheel and panel) on the QF firmware
 * (ROCO K706), as its key service hands them to any app that listens: the
 * firmware's own keys (NAVI, MODE, PHONE… codes 290 and up) never reach an
 * app any other way. Worked out from the firmware and spoken at the binder
 * level ("util_service", android.qf.util.IUtilEventManager): the listener is
 * a plain Binder reading the key off the parcel, so Dashwheel never touches a
 * vendor class. Learned buttons ([SteeringWheelStore]) come through here
 * without root and without reading the CAN stream.
 *
 * Listened to only while it is needed ([want]): the learning screen, or a
 * unit key bound to something. On a K706, all the touch keys beside the
 * screen (power, home, back, volume) stopped working, everywhere, while
 * Dashwheel listened from every start: the service seems to hand the keys to
 * one listener, and Dashwheel's took the firmware's place until a restart.
 */
object HeadUnitKeys {
    private const val TAG = "HeadUnitKeys"
    private const val SERVICE = "util_service"
    private const val MANAGER = "android.qf.util.IUtilEventManager"
    private const val LISTENER = "android.qf.util.IUtilEventListener"
    private const val TX_REGISTER = 2
    private const val TX_ON_KEY = 1
    /** The firmware's service restarts: listen again after this. */
    private const val RETRY_MS = 2_000L

    /** The firmware's own keys start here; the ones below are Android's and reach the launcher anyway. */
    const val FIRST_VENDOR_KEY = 290

    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var service: IBinder? = null

    private val listener = object : Binder() {
        init {
            attachInterface(null, LISTENER)
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != TX_ON_KEY) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(LISTENER)
            val keyCode = data.readInt()
            val event = if (data.readInt() != 0) {
                data.readInt()
                @Suppress("DEPRECATION")
                data.readParcelable<KeyEvent>(KeyEvent::class.java.classLoader)
            } else {
                null
            }
            // The service waits for the reply: answer at once, act on the main thread.
            reply?.writeNoException()
            main.post { onKey(keyCode, event) }
            return true
        }
    }

    /** Whether something needs the unit's keys right now ([want]). */
    @Volatile
    private var wanted = false

    private val died = IBinder.DeathRecipient {
        service = null
        main.postDelayed({ if (wanted) register() }, RETRY_MS)
    }

    fun start(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
    }

    /**
     * Listens to the key service while [on]: the learning screen is open, or a
     * unit key is bound. Once listening, it lasts until Dashwheel restarts:
     * there is no known way to stop.
     */
    fun want(on: Boolean) {
        wanted = on
        if (on && service == null) main.post { if (wanted && service == null) register() }
    }

    /** Whether the unit's key service is being listened to. */
    val listening: Boolean get() = service != null

    private fun register() {
        val binder = serviceBinder() ?: return
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(MANAGER)
            data.writeStrongBinder(listener)
            binder.transact(TX_REGISTER, data, reply, 0)
            reply.readException()
            binder.linkToDeath(died, 0)
            service = binder
            Log.i(TAG, "listening to the unit's keys")
        } catch (e: Exception) {
            Log.w(TAG, "could not listen to the unit's keys", e)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun onKey(keyCode: Int, event: KeyEvent?) {
        // Android's own keys come to the launcher anyway; taking them here too would run them twice.
        if (keyCode < FIRST_VENDOR_KEY) return
        val context = appContext ?: return
        SteeringWheelStore.onUnitKey(context, keyCode, event)
    }

    /** The key service's binder, from the ServiceManager (a system class apps may reach by reflection). */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun serviceBinder(): IBinder? = runCatching {
        Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, SERVICE) as IBinder?
    }.onFailure { Log.w(TAG, "no key service", it) }.getOrNull()

    /** The firmware's own keys by name (QFApi.getKeyCode), for the learning screen. */
    val nameRes: Map<Int, Int> = mapOf(
        290 to R.string.wheel_unit_power, 291 to R.string.wheel_unit_mute, 292 to R.string.wheel_unit_mode,
        293 to R.string.wheel_unit_volume_up, 294 to R.string.wheel_unit_volume_down, 298 to R.string.wheel_unit_navi,
        299 to R.string.wheel_unit_previous, 300 to R.string.wheel_unit_next, 301 to R.string.wheel_unit_phone,
        302 to R.string.wheel_unit_hang_up, 303 to R.string.wheel_unit_radio, 304 to R.string.wheel_unit_voice,
        307 to R.string.wheel_unit_play_pause, 315 to R.string.wheel_unit_backlight, 316 to R.string.wheel_unit_settings,
        321 to R.string.wheel_unit_apps, 325 to R.string.wheel_unit_knob
    )

    /** The numbered custom keys KEY1..KEY15 (codes 354..368), by their number; null for any other code. */
    fun customNumber(keyCode: Int): Int? = (keyCode - 353).takeIf { it in 1..15 }
}
