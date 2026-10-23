package com.openauto.dash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.openauto.dash.unit.PlainAdapters
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

    /** The head unit's broadcasts worth listening to: its firmware's car, power and camera news ([SignalCatalogue]). */
    private val actions: Set<String> get() = UnitPlatform.adapter.catalogue.broadcasts

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Last heard, kept from the moment the receiver is up: they come only when they change.
    private val shared = ConcurrentHashMap<String, String>()
    private val broadcasts = ConcurrentHashMap<String, String>()
    /** Values another family's bus pushed ([UnitBus]), under their own source keys. */
    private val bus = ConcurrentHashMap<String, String>()

    /** A raw value from a vehicle bus, for the finder: "fyt:7:1031#0" = "57". */
    internal fun busValue(key: String, value: String) {
        bus[key] = value
    }

    /** The bus went quiet or its feed was lost: what it last said is no longer the car (a learned signal reads nothing, and so withdraws). */
    internal fun clearBus() = bus.clear()
    @Volatile private var props: Map<String, String> = emptyMap()
    @Volatile private var globals: Map<String, String> = emptyMap()
    @Volatile private var systems: Map<String, String> = emptyMap()
    @Volatile private var secures: Map<String, String> = emptyMap()

    private val _values = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Every value, while [start]ed: refreshed four times a second. */
    val values: StateFlow<Map<String, String>> = _values

    private val _canReadable = MutableStateFlow(false)
    /** The car box's frames can be read: the unit gave Dashwheel root. */
    val canReadable: StateFlow<Boolean> = _canReadable

    private var receiving = false
    private var sniffingAll = false
    private var appContext: Context? = null
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
        appContext = context.applicationContext
        val wanted = actions
        if (wanted.isEmpty()) return
        val filter = IntentFilter().apply { wanted.forEach(::addAction) }
        ContextCompat.registerReceiver(context.applicationContext, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = heard(intent)
    }

    /** The other families' broadcasts, only while the finder is open (a receiver of its own, so it can be let go alone). */
    private val sniffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = heard(intent)
    }

    private fun heard(intent: Intent) {
        val action = intent.action ?: return
        val extras = runCatching { intent.extras }.getOrNull()
        if (extras == null || extras.isEmpty) {
            // A message with nothing in it: when it came is all it says.
            broadcasts[SignalSource.BROADCAST.key(action)] = System.currentTimeMillis().toString()
            return
        }
        for ((name, text) in flattenExtras(extras)) broadcasts[SignalSource.BROADCAST.key("$action#$name")] = text
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
            SignalSource.SYSTEM -> runCatching { Settings.System.getString(context.contentResolver, name) }.getOrNull()
            SignalSource.SECURE -> runCatching { Settings.Secure.getString(context.contentResolver, name) }.getOrNull()
            SignalSource.FYT, null -> bus[key]
        }
    }

    /** Starts reading every value ([values]); each [start] wants its [stop]. */
    @Synchronized
    fun start(context: Context) {
        val app = context.applicationContext
        listen(app)
        if (users++ > 0) return
        // A bus that has to ask for each value (FYT) asks for everything while the finder is open.
        UnitPlatform.adapter.bus?.sniff(true)
        // And every family's broadcasts are heard meanwhile: a unit taken for the wrong family still shows its own.
        val others = PlainAdapters.ALL_BROADCASTS - actions
        if (others.isNotEmpty()) {
            val filter = IntentFilter().apply { others.forEach(::addAction) }
            runCatching { ContextCompat.registerReceiver(app, sniffReceiver, filter, ContextCompat.RECEIVER_EXPORTED); sniffingAll = true }
        }
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
                    val catalogue = UnitPlatform.adapter.catalogue
                    systems = if (catalogue.settingsSystem) readTable(app, Settings.System.CONTENT_URI, systems) else emptyMap()
                    secures = if (catalogue.settingsSecure) readTable(app, Settings.Secure.CONTENT_URI, secures) else emptyMap()
                }
                _values.value = snapshot()
                delay(TICK_MS)
            }
        }
    }

    @Synchronized
    fun stop() {
        if (users == 0 || --users > 0) return
        UnitPlatform.adapter.bus?.sniff(false)
        if (sniffingAll) {
            runCatching { appContext?.unregisterReceiver(sniffReceiver) }
            sniffingAll = false
        }
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
        out.putAll(bus)
        for ((k, v) in props) out[SignalSource.FLAG.key(k)] = v
        for ((k, v) in globals) out[SignalSource.SETTING.key(k)] = v
        for ((k, v) in systems) out[SignalSource.SYSTEM.key(k)] = v
        for ((k, v) in secures) out[SignalSource.SECURE.key(k)] = v
        return out
    }

    /** The system properties, but the read-only ones (they never change), apart from the prefixes this firmware's catalogue keeps. */
    private fun readProps(): Map<String, String> = runCatching {
        val keep = UnitPlatform.adapter.catalogue.propPrefixes
        val p = Runtime.getRuntime().exec(arrayOf("getprop"))
        val out = HashMap<String, String>()
        p.inputStream.bufferedReader().useLines { lines ->
            for (line in lines) {
                val m = PROP_LINE.find(line) ?: continue
                val name = m.groupValues[1]
                val readOnly = name.startsWith("ro.") || name.startsWith("init.svc.") || name.startsWith("ro_")
                if (readOnly && keep.none { name.startsWith(it) }) continue
                out[name] = m.groupValues[2]
            }
        }
        p.waitFor()
        out
    }.getOrDefault(props)

    private val PROP_LINE = Regex("""^\[([^\]]+)]: \[(.*)]$""")

    private fun readGlobals(context: Context): Map<String, String> = readTable(context, Settings.Global.CONTENT_URI, globals)

    /** Every row of a settings table, or [fallback] when it can't be read. */
    private fun readTable(context: Context, uri: android.net.Uri, fallback: Map<String, String>): Map<String, String> = runCatching {
        val out = HashMap<String, String>()
        context.contentResolver.query(uri, arrayOf("name", "value"), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                out[name] = c.getString(1) ?: ""
            }
        }
        out
    }.getOrDefault(fallback)

    private const val TICK_MS = 250L

    private fun hex(data: ByteArray): String = data.joinToString(" ") { SignalProbe.hex2(it.toInt() and 0xFF) }

    private fun systemProperty(name: String): String? = SysProps.get(name)
}
