package com.openauto.dash

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/*
 * Everything the head unit shows Dashwheel about the car, as keyed values
 * ([SignalSource]) for the Signal Finder: the car box's frames ([McuReader],
 * root), the car app's shared data and the head unit's broadcasts (no root),
 * its system properties and its global settings.
 *
 * Reading one value ([current]) is cheap and always works. Reading them all
 * ([start]) runs while the finder or the sniffer is open: the properties and
 * settings are listed again every second.
 */
object SignalSources {

    /** The head unit's broadcasts worth listening to: the QF firmware's car, power and camera news. */
    private val ACTIONS = listOf(
        "com.qf.action.BACKCAR_START", "com.qf.action.BACKCAR_STOP",
        "com.qf.action.headlight_state", "com.qf.action.ACC_ON", "com.qf.action.ACC_OFF",
        "com.qf.action.READY_GO_SLEEP_PRE", "com.qf.action.VOLUME_CHANGED",
        "com.qf.vehicle.action.swa", "com.qf.vehicle.action.popup_enable",
        "com.qf.action.BT.MUSIC.INFO", "TPMS_APP_STATE_SEND", "com.zjinnova.zlink"
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Last heard, kept from the moment the receiver is up: they come only when they change.
    private val shared = ConcurrentHashMap<String, String>()
    private val broadcasts = ConcurrentHashMap<String, String>()
    @Volatile private var props: Map<String, String> = emptyMap()
    @Volatile private var globals: Map<String, String> = emptyMap()

    private val _values = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Every value, while [start]ed: refreshed four times a second. */
    val values: StateFlow<Map<String, String>> = _values

    private val _canReadable = MutableStateFlow(false)
    /** The car box's frames can be read: the unit gave Dashwheel root. */
    val canReadable: StateFlow<Boolean> = _canReadable

    private var receiving = false
    private var users = 0
    private var job: Job? = null
    private var holdsCan = false

    /** The car app's shared data, as it comes ([CarBox]): one value per type. */
    internal fun shared(data: ByteArray?) {
        val type = shareType(data) ?: return
        shared[SignalSource.SHARED.key(type.toString())] = hex(data!!)
    }

    /** Starts hearing the head unit's broadcasts, for good: they are rare and cheap. */
    @Synchronized
    fun listen(context: Context) {
        if (receiving) return
        receiving = true
        val filter = IntentFilter().apply { ACTIONS.forEach(::addAction) }
        ContextCompat.registerReceiver(context.applicationContext, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            val extras = runCatching { intent.extras }.getOrNull()
            if (extras == null || extras.isEmpty) {
                // A message with nothing in it: when it came is all it says.
                broadcasts[SignalSource.BROADCAST.key(action)] = System.currentTimeMillis().toString()
                return
            }
            for ((name, text) in flattenExtras(extras)) broadcasts[SignalSource.BROADCAST.key("$action#$name")] = text
        }
    }

    private fun flattenExtras(extras: Bundle): List<Pair<String, String>> = runCatching {
        extras.keySet().mapNotNull { name ->
            @Suppress("DEPRECATION")
            when (val v = extras.get(name)) {
                null -> null
                is ByteArray -> "$name[]" to hex(v)
                is String, is Number, is Boolean, is Char -> name to v.toString()
                is IntArray -> name to v.joinToString(",")
                else -> null
            }
        }
    }.getOrDefault(emptyList())

    /** The value under [key] now, or null when there is none (a frame not seen, a property not set). */
    fun current(context: Context, key: String): String? {
        val name = SignalSource.name(key)
        return when (SignalSource.of(key)) {
            SignalSource.CAN -> McuReader.entries.value.firstOrNull { it.key == name }?.hex
            SignalSource.SHARED -> shared[key]
            SignalSource.BROADCAST -> broadcasts[key]
            SignalSource.FLAG -> systemProperty(name)?.takeIf { it.isNotEmpty() }
            SignalSource.SETTING -> runCatching { Settings.Global.getString(context.contentResolver, name) }.getOrNull()
            null -> null
        }
    }

    /** Starts reading every value ([values]); each [start] wants its [stop]. */
    @Synchronized
    fun start(context: Context) {
        val app = context.applicationContext
        listen(app)
        if (users++ > 0) return
        job = scope.launch {
            val root = SystemInstaller.isRootAvailable()
            _canReadable.value = root
            if (root) synchronized(this@SignalSources) {
                if (users > 0 && !holdsCan) { McuReader.start(); holdsCan = true }
            }
            var tick = 0
            while (isActive) {
                // The lists cost a process or a query: once a second; the rest is in memory.
                if (tick++ % 4 == 0) {
                    props = readProps()
                    globals = readGlobals(app)
                }
                _values.value = snapshot()
                delay(TICK_MS)
            }
        }
    }

    @Synchronized
    fun stop() {
        if (users == 0 || --users > 0) return
        job?.cancel()
        job = null
        if (holdsCan) { McuReader.stop(); holdsCan = false }
    }

    /** Every value now, by key. */
    private fun snapshot(): Map<String, String> {
        val out = HashMap<String, String>(512)
        for (e in McuReader.entries.value) out[SignalSource.CAN.key(e.key)] = e.hex
        out.putAll(shared)
        out.putAll(broadcasts)
        for ((k, v) in props) out[SignalSource.FLAG.key(k)] = v
        for ((k, v) in globals) out[SignalSource.SETTING.key(k)] = v
        return out
    }

    /** The system properties, but the read-only ones: they never change. */
    private fun readProps(): Map<String, String> = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("getprop"))
        val out = HashMap<String, String>()
        p.inputStream.bufferedReader().useLines { lines ->
            for (line in lines) {
                val m = PROP_LINE.find(line) ?: continue
                val name = m.groupValues[1]
                if (name.startsWith("ro.") || name.startsWith("init.svc.") || name.startsWith("ro_")) continue
                out[name] = m.groupValues[2]
            }
        }
        p.waitFor()
        out
    }.getOrDefault(props)

    private val PROP_LINE = Regex("""^\[([^\]]+)]: \[(.*)]$""")

    private fun readGlobals(context: Context): Map<String, String> = runCatching {
        val out = HashMap<String, String>()
        context.contentResolver.query(Settings.Global.CONTENT_URI, arrayOf("name", "value"), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                out[name] = c.getString(1) ?: ""
            }
        }
        out
    }.getOrDefault(globals)

    private const val TICK_MS = 250L

    private fun hex(data: ByteArray): String = data.joinToString(" ") { SignalProbe.hex2(it.toInt() and 0xFF) }

    @SuppressLint("PrivateApi")
    private fun systemProperty(name: String): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as String
    }.getOrNull()
}
