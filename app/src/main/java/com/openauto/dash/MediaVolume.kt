package com.openauto.dash

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.StringRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/** How the volume is changed: found out by itself, always Android's volume, or always the volume keys. */
enum class VolumeWay(@StringRes val titleRes: Int, @StringRes val hintRes: Int) {
    AUTO(R.string.volume_way_auto, R.string.volume_way_auto_hint),
    ANDROID(R.string.volume_way_android, R.string.volume_way_android_hint),
    KEYS(R.string.volume_way_keys, R.string.volume_way_keys_hint)
}

/**
 * The media volume, changed the way this device listens to. Normally through
 * Android's own media volume. Many head units drive their amplifier from the
 * MCU, which only follows the volume keys: there the changes are made by
 * pressing those keys through the privileged shell (root or the internal ADB
 * socket, see [DockShell]), exactly like the knob does. Some keep Android's
 * volume fixed, which the first change finds out.
 *
 * QF firmware (the ROCO K706) is different again: Android's volume moves
 * without any effect on the sound, and injected volume keys are ignored too,
 * since its framework only hears the MCU's own keys. The sound follows the
 * unit's volume, 0..[UNIT_MAX] for the current source, which the framework
 * keeps in a system property and sends to the MCU (android.qf.os.VolumeState).
 * The same two steps are taken here through the privileged shell, so the knob
 * carries on from the new level. The driver can still force Android's volume
 * in Settings ([VolumeWay]).
 */
object MediaVolume {
    private const val TAG = "MediaVolume"
    private const val STREAM = AudioManager.STREAM_MUSIC
    private const val PREFS = "media_volume"
    private const val KEY_WAY = "way"

    /** The QF firmware's volume range. */
    const val UNIT_MAX = 32
    /** android.qf.mcu.IMcuManager: RPC_SetVolumeMute(boolean) and RPC_SetVolume(byte). */
    private const val MCU_SERVICE = "mcu_service"
    private const val TX_SET_MUTE = 6
    private const val TX_SET_VOLUME = 13
    /** Which source the knob currently changes (media_type, radio_type, btcall_type, aux_type). */
    private const val UNIT_TYPE_PROP = "sys.current.vol.type"
    private const val UNIT_MUTE_PROP = "sys.mute.state"
    /** The level a source starts from before it was ever changed. */
    private const val UNIT_DEFAULT_PROP = "persist.sys.main_volume"
    private const val UNIT_DEFAULT = 12

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** QF firmware, whose sound follows only the unit's own volume. */
    private val qfUnit: Boolean by lazy {
        (Build.BRAND.equals("ROCO", ignoreCase = true) && Build.DEVICE.equals("K706", ignoreCase = true)) ||
            sysProp(UNIT_TYPE_PROP).isNotEmpty() || hasService(MCU_SERVICE)
    }

    private val _way = MutableStateFlow(VolumeWay.AUTO)
    val way: StateFlow<VolumeWay> = _way.asStateFlow()
    private var loaded = false

    private val _onUnit = MutableStateFlow(false)
    /** The head unit's own volume is changed (QF firmware): the level is known, 0..[UNIT_MAX]. */
    val onUnit: StateFlow<Boolean> = _onUnit.asStateFlow()

    private val _byKeys = MutableStateFlow(false)
    /** Android's media volume has no effect here: the volume keys are pressed instead, and the level is unknown. */
    val byKeys: StateFlow<Boolean> = _byKeys.asStateFlow()

    private val _unavailable = MutableStateFlow(false)
    /** Neither way works: no effect from Android's volume and no shell to press the keys with. */
    val unavailable: StateFlow<Boolean> = _unavailable.asStateFlow()

    /** The level to come back to when unmuting a volume that was slid down to 0. */
    @Volatile private var lastAudible = 0

    /**
     * QF firmware: its own volume is the only one that works, so there is no
     * choice to offer (Settings hides it) and none is kept. Android's volume,
     * chosen there once, silenced every volume control.
     */
    val choiceOffered: Boolean get() = !qfUnit

    fun setContext(context: Context) {
        if (loaded) return
        loaded = true
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_WAY, null)
        val saved = VolumeWay.entries.firstOrNull { it.name == name } ?: VolumeWay.AUTO
        if (qfUnit && saved != VolumeWay.AUTO) saveWay(context, VolumeWay.AUTO) else _way.value = saved
        applyWay()
    }

    fun saveWay(context: Context, way: VolumeWay) {
        _way.value = way
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_WAY, way.name).apply()
        applyWay()
    }

    /** Starts over from the chosen way; in AUTO, a fixed volume is found out again on the next change. */
    private fun applyWay() {
        // Pressed keys never reach the QF volume, so the unit's own volume stands in for them too.
        _onUnit.value = qfUnit && _way.value != VolumeWay.ANDROID
        _byKeys.value = !_onUnit.value && _way.value == VolumeWay.KEYS
        _unavailable.value = false
    }

    fun audio(context: Context): AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun max(audio: AudioManager): Int = if (_onUnit.value) UNIT_MAX else audio.getStreamMaxVolume(STREAM).coerceAtLeast(1)

    /** The level heard: 0 while muted on the unit, as its own volume bar shows it. */
    fun level(audio: AudioManager): Int =
        if (_onUnit.value) unitState().let { if (it.muted) 0 else it.level } else audio.getStreamVolume(STREAM)

    /** Muted, or slid down to nothing. */
    fun isMuted(audio: AudioManager): Boolean =
        if (_onUnit.value) unitState().let { it.muted || it.level == 0 } else audio.isStreamMute(STREAM) || level(audio) == 0

    /** Checked once the audio tile shows: a fixed volume can't be changed through Android at all. */
    fun check(audio: AudioManager) {
        if (_way.value == VolumeWay.AUTO && !_onUnit.value && audio.isVolumeFixed) _byKeys.value = true
    }

    /** Sets the level (the slider). Android's or the unit's volume only: the keys can't reach a given level. */
    fun set(context: Context, level: Int) {
        if (_onUnit.value) {
            if (level > 0) lastAudible = level
            setUnit(context, UnitState(level.coerceIn(0, UNIT_MAX), muted = level == 0))
            return
        }
        val audio = audio(context)
        if (_byKeys.value) return
        if (level > 0) lastAudible = level
        val before = level(audio)
        runCatching { audio.setStreamVolume(STREAM, level, 0) }
        // Nothing moved at all: the ROM handles the volume itself. (A level merely
        // capped, e.g. by the safe-volume limit, still moved and doesn't count.)
        if (level != before && level(audio) == before && !audio.isStreamMute(STREAM)) useKeys()
    }

    fun raise(context: Context) = step(context, AudioManager.ADJUST_RAISE, KeyEvent.KEYCODE_VOLUME_UP)

    fun lower(context: Context) = step(context, AudioManager.ADJUST_LOWER, KeyEvent.KEYCODE_VOLUME_DOWN)

    fun toggleMute(context: Context) {
        if (_onUnit.value) {
            val now = unitState()
            if (now.muted || now.level == 0) {
                // Slid down to 0 rather than muted: unmuting alone would leave it silent.
                val back = if (now.level > 0) now.level else lastAudible.takeIf { it > 0 } ?: (UNIT_MAX / 3)
                setUnit(context, UnitState(back, muted = false))
            } else {
                lastAudible = now.level
                setUnit(context, now.copy(muted = true))
            }
            return
        }
        val audio = audio(context)
        if (_byKeys.value) {
            press(context, KeyEvent.KEYCODE_VOLUME_MUTE)
            return
        }
        if (isMuted(audio)) {
            if (audio.isStreamMute(STREAM)) runCatching { audio.adjustStreamVolume(STREAM, AudioManager.ADJUST_UNMUTE, 0) }
            // Slid down to 0 rather than muted: unmuting alone would leave it silent.
            if (level(audio) == 0) {
                val back = lastAudible.takeIf { it > 0 } ?: (max(audio) / 3).coerceAtLeast(1)
                runCatching { audio.setStreamVolume(STREAM, back, 0) }
            }
            if (isMuted(audio)) useKeys(context, KeyEvent.KEYCODE_VOLUME_MUTE)
        } else {
            lastAudible = level(audio)
            runCatching { audio.adjustStreamVolume(STREAM, AudioManager.ADJUST_MUTE, 0) }
            if (!isMuted(audio)) useKeys(context, KeyEvent.KEYCODE_VOLUME_MUTE)
        }
    }

    private fun step(context: Context, direction: Int, keyCode: Int) {
        if (_onUnit.value) {
            // Like the knob: one notch from the saved level, and any change unmutes.
            val now = unitState()
            val next = (now.level + if (direction == AudioManager.ADJUST_RAISE) 1 else -1).coerceIn(0, UNIT_MAX)
            if (next != now.level || now.muted) setUnit(context, UnitState(next, muted = next == 0))
            return
        }
        val audio = audio(context)
        if (_byKeys.value) {
            press(context, keyCode)
            return
        }
        val before = level(audio)
        val wasMuted = audio.isStreamMute(STREAM)
        // Already at the end of the range: nothing to change, nothing learnt.
        val atEnd = !wasMuted && if (direction == AudioManager.ADJUST_RAISE) before >= max(audio) else before <= 0
        runCatching { audio.adjustStreamVolume(STREAM, direction, 0) }
        val moved = level(audio) != before || audio.isStreamMute(STREAM) != wasMuted
        if (!moved && !atEnd) useKeys(context, keyCode)
    }

    /** From now on the keys are pressed; [keyCode] is the press the system just ignored. */
    private fun useKeys(context: Context? = null, keyCode: Int? = null) {
        if (_way.value == VolumeWay.ANDROID) return
        if (!_byKeys.value) Log.i(TAG, "Android's media volume has no effect here: using the volume keys")
        _byKeys.value = true
        if (context != null && keyCode != null) press(context, keyCode)
    }

    private fun press(context: Context, keyCode: Int) {
        val app = context.applicationContext
        scope.launch {
            runCatching { DockShell.shell(app, "input keyevent $keyCode") }
                .onSuccess { _unavailable.value = false }
                .onFailure {
                    Log.w(TAG, "volume key $keyCode not pressed: ${it.message}")
                    _unavailable.value = true
                }
        }
    }

    // --- The QF unit's own volume ------------------------------------------------------

    private data class UnitState(val level: Int, val muted: Boolean)

    /** A change asked for and not yet made: what the tile shows meanwhile. Only the latest one is made. */
    private val unitWanted = AtomicReference<UnitState?>(null)
    private val unitWrites = Channel<Unit>(Channel.CONFLATED)
    private var unitWriter = false

    private fun unitState(): UnitState = unitWanted.get() ?: UnitState(savedUnitLevel(), sysProp(UNIT_MUTE_PROP) == "true")

    /** Where the firmware saves the current source's level (android.qf.os.VolumeManager). */
    private fun unitLevelProp(): String = when (sysProp(UNIT_TYPE_PROP)) {
        "radio_type" -> "sys.radio.vol"
        "btcall_type" -> "sys.call.vol"
        "aux_type" -> "sys.aux.vol"
        else -> "sys.media.vol"
    }

    private fun savedUnitLevel(): Int =
        (sysProp(unitLevelProp()).toIntOrNull() ?: sysProp(UNIT_DEFAULT_PROP).toIntOrNull() ?: UNIT_DEFAULT).coerceIn(0, UNIT_MAX)

    private fun setUnit(context: Context, state: UnitState) {
        unitWanted.set(state)
        startUnitWriter(context.applicationContext)
        unitWrites.trySend(Unit)
    }

    @Synchronized
    private fun startUnitWriter(app: Context) {
        if (unitWriter) return
        unitWriter = true
        scope.launch {
            for (tick in unitWrites) {
                val want = unitWanted.get() ?: continue
                runCatching { writeUnit(app, want) }
                    .onSuccess { _unavailable.value = false }
                    .onFailure {
                        Log.w(TAG, "unit volume not set: ${it.message}")
                        _unavailable.value = true
                    }
                // A newer change keeps showing until it's made in turn.
                unitWanted.compareAndSet(want, null)
            }
        }
    }

    /** The two steps of the firmware's own VolumeState: save the level and mute state, then tell the MCU. */
    private suspend fun writeUnit(app: Context, want: UnitState) {
        val prop = unitLevelProp()
        val wasMuted = sysProp(UNIT_MUTE_PROP) == "true"
        val wasLevel = sysProp(prop).toIntOrNull()
        val cmds = buildList {
            if (want.muted != wasMuted) {
                add("setprop $UNIT_MUTE_PROP ${want.muted}")
                add("service call $MCU_SERVICE $TX_SET_MUTE i32 ${if (want.muted) 1 else 0}")
            }
            if (want.level != wasLevel) {
                add("setprop $prop ${want.level}")
                add("service call $MCU_SERVICE $TX_SET_VOLUME i32 ${want.level}")
            }
        }
        if (cmds.isEmpty()) return
        val out = DockShell.shell(app, cmds.joinToString("; "))
        // "service call" answers "Result: Parcel(...)"; anything else is an error message.
        if (!out.contains("Result: Parcel")) throw IllegalStateException(out.trim().lines().firstOrNull().orEmpty())
    }

    @SuppressLint("PrivateApi")
    private fun sysProp(name: String): String = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as String
    }.getOrDefault("")

    /** Whether a system service is registered, without waiting for it like getService does. */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun hasService(name: String): Boolean = runCatching {
        Class.forName("android.os.ServiceManager").getMethod("checkService", String::class.java).invoke(null, name) as IBinder?
    }.getOrNull() != null
}
