package com.openauto.dash

import android.content.Context
import androidx.annotation.StringRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the Signal Finder can teach Dashwheel: each with the action that turns it on and off. */
enum class CarSignal(@StringRes val title: Int, @StringRes val onPrompt: Int, @StringRes val offPrompt: Int) {
    FRONT_LEFT(R.string.signals_front_left, R.string.signals_on_front_left, R.string.signals_off_front_left),
    FRONT_RIGHT(R.string.signals_front_right, R.string.signals_on_front_right, R.string.signals_off_front_right),
    REAR_LEFT(R.string.signals_rear_left, R.string.signals_on_rear_left, R.string.signals_off_rear_left),
    REAR_RIGHT(R.string.signals_rear_right, R.string.signals_on_rear_right, R.string.signals_off_rear_right),
    TAILGATE(R.string.signals_tailgate, R.string.signals_on_tailgate, R.string.signals_off_tailgate),
    BONNET(R.string.signals_bonnet, R.string.signals_on_bonnet, R.string.signals_off_bonnet),
    REVERSE(R.string.signals_reverse, R.string.signals_on_reverse, R.string.signals_off_reverse),
    HEADLIGHTS(R.string.signals_headlights, R.string.signals_on_headlights, R.string.signals_off_headlights),
    DRIVER_BELT(R.string.signals_belt, R.string.signals_on_belt, R.string.signals_off_belt);

    val isDoor: Boolean get() = this in DOORS

    companion object {
        val DOORS = setOf(FRONT_LEFT, FRONT_RIGHT, REAR_LEFT, REAR_RIGHT, TAILGATE, BONNET)
    }
}

/** [learned]'s doors laid over the car's own [builtIn] ones: a learned door wins. */
internal fun mergeDoors(builtIn: McuReader.DoorState?, learned: Map<CarSignal, Boolean>): McuReader.DoorState? {
    if (learned.keys.none { it.isDoor }) return builtIn
    val base = builtIn ?: McuReader.DoorState()
    return base.copy(
        frontLeft = learned[CarSignal.FRONT_LEFT] ?: base.frontLeft,
        frontRight = learned[CarSignal.FRONT_RIGHT] ?: base.frontRight,
        rearLeft = learned[CarSignal.REAR_LEFT] ?: base.rearLeft,
        rearRight = learned[CarSignal.REAR_RIGHT] ?: base.rearRight,
        tailgate = learned[CarSignal.TAILGATE] ?: base.tailgate,
        bonnet = learned[CarSignal.BONNET] ?: base.bonnet
    )
}

/**
 * The signals the driver taught Dashwheel with the Signal Finder: which
 * piece of which value stands for each ([SignalProbe]), read a few times a
 * second and handed to what shows them: the doors to [McuReader], reverse to
 * [CarBox], the headlights to [UnitSignals], the belt to [BeltAlertOverlay].
 * Also the camera the reverse view opens.
 */
object LearnedSignals {
    private const val PREFS = "car_signals"
    private const val KEY_CAMERA = "camera_id"
    private const val READ_MS = 300L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var appContext: Context? = null
    private var job: Job? = null
    private var holdsCan = false

    private val _mapped = MutableStateFlow<Map<CarSignal, SignalProbe>>(emptyMap())
    /** What stands for each learned signal. */
    val mapped: StateFlow<Map<CarSignal, SignalProbe>> = _mapped

    private val _state = MutableStateFlow<Map<CarSignal, Boolean>>(emptyMap())
    /** Each learned signal's state now, once its value has been read. */
    val state: StateFlow<Map<CarSignal, Boolean>> = _state

    private val _cameraId = MutableStateFlow<String?>(null)
    /** The camera the reverse view opens, when the driver picked one; else the car app's. */
    val cameraId: StateFlow<String?> = _cameraId

    @Synchronized
    fun start(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _mapped.value = CarSignal.entries.mapNotNull { s -> SignalProbe.decode(p.getString(s.name, null))?.let { s to it } }.toMap()
        _cameraId.value = p.getString(KEY_CAMERA, null)
        follow()
    }

    fun assign(context: Context, signal: CarSignal, probe: SignalProbe) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(signal.name, probe.encode()).apply()
        _mapped.value = _mapped.value + (signal to probe)
        follow()
    }

    fun forget(context: Context, signal: CarSignal) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(signal.name).apply()
        _mapped.value = _mapped.value - signal
        _state.value = _state.value - signal
        publish(_state.value, signal)
        follow()
    }

    fun setCamera(context: Context, id: String?) {
        val e = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (id == null) e.remove(KEY_CAMERA) else e.putString(KEY_CAMERA, id)
        e.apply()
        _cameraId.value = id
    }

    /** Reads the learned signals while there are any; holds the car box's log while one comes from it. */
    @Synchronized
    private fun follow() {
        val app = appContext ?: return
        val probes = _mapped.value
        val wantsCan = probes.values.any { SignalSource.of(it.key) == SignalSource.CAN }
        if (wantsCan && !holdsCan) { McuReader.start(); holdsCan = true }
        if (!wantsCan && holdsCan) { McuReader.stop(); holdsCan = false }
        if (probes.values.any { SignalSource.of(it.key) == SignalSource.BROADCAST }) SignalSources.listen(app)
        if (probes.isEmpty()) {
            job?.cancel()
            job = null
            return
        }
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                val now = HashMap<CarSignal, Boolean>()
                for ((signal, probe) in _mapped.value) {
                    probe.isOn(SignalSources.current(app, probe.key))?.let { now[signal] = it }
                }
                val before = _state.value
                if (now != before) {
                    _state.value = now
                    for (s in CarSignal.entries) if (now[s] != before[s]) publish(now, s)
                }
                delay(READ_MS)
            }
        }
    }

    /** Hands [signal]'s new state in [now] to what shows it. */
    private fun publish(now: Map<CarSignal, Boolean>, signal: CarSignal) {
        when {
            signal.isDoor -> McuReader.learnedDoorsWrite(now.filterKeys { it.isDoor })
            signal == CarSignal.REVERSE -> now[signal]?.let { CarBox.reverseWrite(it) }
            signal == CarSignal.HEADLIGHTS -> now[signal]?.let { UnitSignals.headlightsWrite(it) }
            // The belt reminder reads [state] itself, with the car's speed.
            else -> Unit
        }
    }
}
