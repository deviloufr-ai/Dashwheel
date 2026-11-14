package com.openauto.dash.unit

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Parcel
import android.os.HandlerThread
import android.os.Message
import android.util.Log
import com.openauto.dash.Cap
import com.openauto.dash.DebugLog
import com.openauto.dash.HiddenApi
import com.openauto.dash.UnitPlatform
import com.openauto.dash.VehicleBusSource
import com.openauto.dash.vehicle.K
import com.openauto.dash.vehicle.Publisher
import com.openauto.unitproto.topway.TopwayProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.lang.reflect.Method

/**
 * The Topway TS firmware's MCU link ([TopwayProtocol]), read-only: the reverse
 * the car app hears, and the canbox's frames for the Signal Finder to learn
 * the doors, the lights and the rest from. Through the framework's own TWUtil,
 * the way the firmware's Bluetooth, EQ and car apps each open it; a unit that
 * keeps it from Dashwheel (a refused open, a hidden-API block) leaves a line
 * in the debug log and the bus silent. Unconfirmed on a unit.
 *
 * And the firmware's car info service (com.tw.carinfoservice), bound the way
 * the car app binds it, with a callback of Dashwheel's own: every value it
 * sends (speed, revs, turn signals, handbrake, headlights, radar, trip...) goes
 * to the Signal Finder as it comes, under the callback's number. Which number
 * is which is only partly known, so nothing of it reaches the dashboard yet.
 */
internal object TopwayBus : VehicleBusSource {
    private const val TAG = "TopwayBus"
    private const val HANDLER_TAG = "Dashwheel"

    private val _listening = MutableStateFlow(false)
    override val listening: StateFlow<Boolean> = _listening

    /**
     * The reverse channel and the info service's callbacks speak only when something changes: a door
     * learned from them must not be forgotten for a quiet minute. A lost feed still says so ([listening]).
     */
    override val silenceMs: Long = 24 * 3_600_000L

    private var out: Publisher? = null
    private var raw: ((String, String) -> Unit)? = null
    private var tw: Any? = null
    private var thread: HandlerThread? = null
    private var frames = 0
    private var app: Context? = null
    private var infoBound = false
    private var callbacks = 0

    override fun start(context: Context, publisher: Publisher, raw: (String, String) -> Unit) {
        if (out != null) return
        out = publisher
        this.raw = raw
        val app = context.applicationContext
        this.app = app
        bindInfo(app)
        val t = HandlerThread(TAG).also { it.start() }
        thread = t
        val handler = Handler(t.looper) { msg -> onMessage(msg); true }
        runCatching {
            val cls = Class.forName(TopwayProtocol.TWUTIL_CLASS)
            val util = runCatching { cls.getConstructor().newInstance() }.getOrElse { HiddenApi.newInstance(cls) }
            val opened = method(cls, "open", ShortArray::class.java).invoke(util, TopwayProtocol.LISTENED) as Int
            if (opened != 0) error("open answered $opened")
            method(cls, "start").invoke(util)
            method(cls, "addHandler", String::class.java, Handler::class.java).invoke(util, HANDLER_TAG, handler)
            tw = util
            _listening.value = true
            Log.i(TAG, "listening to the MCU through TWUtil")
            DebugLog.note(app, "Topway MCU: listening through TWUtil (reverse, canbox frames)")
        }.onFailure {
            Log.w(TAG, "TWUtil is not there for Dashwheel", it)
            DebugLog.note(app, "Topway MCU: TWUtil refused (${it.cause ?: it})")
            t.quitSafely()
            thread = null
        }
    }

    override fun stop() {
        unbindInfo()
        val util = tw
        tw = null
        if (util != null) runCatching {
            val cls = util.javaClass
            method(cls, "removeHandler", String::class.java).invoke(util, HANDLER_TAG)
            method(cls, "stop").invoke(util)
            method(cls, "close").invoke(util)
        }
        thread?.quitSafely()
        thread = null
        out = null
        raw = null
        _listening.value = false
    }

    /** The car info service: bound once; Android binds it again by itself after it restarts. */
    private fun bindInfo(app: Context) {
        val intent = Intent(TopwayProtocol.INFO_BIND_ACTION).setPackage(TopwayProtocol.INFO_PACKAGE)
        infoBound = runCatching { app.bindService(intent, info, Context.BIND_AUTO_CREATE) }
            .onFailure { Log.w(TAG, "the car info service can't be bound", it) }
            .getOrDefault(false)
        if (!infoBound) DebugLog.note(app, "Topway car info service: not there to bind")
    }

    private fun unbindInfo() {
        if (!infoBound) return
        infoBound = false
        runCatching { app?.unbindService(info) }
    }

    private val info = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder) {
            val ok = runCatching { transact(service, TopwayProtocol.TX_REGISTER) }
                .onFailure { Log.w(TAG, "the car info service refused the callback", it) }
                .isSuccess
            app?.let { DebugLog.note(it, if (ok) "Topway car info service: callback registered" else "Topway car info service: callback refused") }
            if (ok) _listening.value = true
        }

        override fun onServiceDisconnected(name: ComponentName?) = Unit
    }

    private fun transact(service: IBinder, code: Int) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(TopwayProtocol.INFO_DESCRIPTOR)
            data.writeStrongBinder(callback)
            service.transact(code, data, reply, 0)
            reply.readException()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /** Dashwheel's side of the service's CarServiceCallBack: each call read by its types ([TopwayProtocol.CALLBACK_ARGS]). */
    private val callback = object : Binder() {
        init { attachInterface(null, TopwayProtocol.CALLBACK_DESCRIPTOR) }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            val types = TopwayProtocol.CALLBACK_ARGS[code] ?: return super.onTransact(code, data, reply, flags)
            runCatching {
                data.enforceInterface(TopwayProtocol.CALLBACK_DESCRIPTOR)
                val args = types.map { t ->
                    when (t) {
                        'i' -> data.readInt()
                        'f' -> data.readFloat()
                        else -> data.readString()
                    }
                }
                if (code != TopwayProtocol.CB_PREAMBLE) {
                    UnitPlatform.markUsed(Cap.VEHICLE_BUS)
                    if (callbacks++ == 0) Log.i(TAG, "the car info service speaks (callback $code)")
                    val r = raw
                    if (r != null) for ((k, v) in TopwayProtocol.callbackValues(code, args)) r(k, v)
                }
            }.onFailure { Log.w(TAG, "callback $code not read", it) }
            reply?.writeNoException()
            return true
        }
    }

    /** Public members first; the hidden-API way only when Android keeps them from Dashwheel. */
    private fun method(cls: Class<*>, name: String, vararg types: Class<*>): Method =
        runCatching { cls.getMethod(name, *types) }.getOrElse { HiddenApi.getDeclaredMethod(cls, name, *types) }

    private fun onMessage(msg: Message) {
        val o = out ?: return
        when {
            TopwayProtocol.isReverse(msg.what) -> {
                val on = TopwayProtocol.reverse(msg.arg1) ?: return
                UnitPlatform.markUsed(Cap.REVERSE)
                o.put(K.REVERSE, on)
            }
            TopwayProtocol.carriesFrames(msg.what) -> {
                val bytes = msg.obj as? ByteArray ?: return
                UnitPlatform.markUsed(Cap.VEHICLE_BUS)
                if (frames++ == 0) Log.i(TAG, "canbox frames are coming (channel ${msg.what}, type ${msg.arg1}, ${bytes.size} bytes)")
                raw?.invoke(TopwayProtocol.frameKey(msg.what, msg.arg1), TopwayProtocol.hex(bytes))
            }
        }
    }
}
