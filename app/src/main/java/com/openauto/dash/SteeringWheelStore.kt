package com.openauto.dash

import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Identifies one physical button, seen one of four ways:
 * - a standard Android [keyCode] (most remotes), or one of the head unit's
 *   own keys from its key service ([HeadUnitKeys]);
 * - a raw HID [scanCode] Android can't name (`keyCode == KEYCODE_UNKNOWN`);
 * - a key the input system reports but no app is given ([input],
 *   "KEY_NEXTSONG@event2", read with root `getevent` by [WheelMonitor]);
 * - a CAN frame from the car ([canKey], a [McuReader] key) taking the value
 *   [canHex] while the button is held. On MCU head units (the ROCO K706)
 *   some wheel buttons only show up here.
 */
internal data class WheelKey(
    val keyCode: Int,
    val scanCode: Int,
    val canKey: String? = null,
    val canHex: String? = null,
    val input: String? = null
) {
    val id: String
        get() = when {
            canKey != null -> "c:$canKey=$canHex"
            input != null -> "i:$input"
            keyCode != KeyEvent.KEYCODE_UNKNOWN -> "k$keyCode"
            else -> "s$scanCode"
        }

    /** A readable name: "Media Next", "Volume Up"... The map words the raw and CAN ones in the chosen language. */
    val label: String
        get() {
            if (canKey != null) return "CAN $canKey: $canHex"
            if (input != null) return input
            if (keyCode == KeyEvent.KEYCODE_UNKNOWN) return "Button #$scanCode"
            // The head unit's own keys ([HeadUnitKeys]) have no Android name: the map words them.
            if (keyCode >= HeadUnitKeys.FIRST_VENDOR_KEY) return "Key $keyCode"
            val raw = runCatching { KeyEvent.keyCodeToString(keyCode) }.getOrDefault("KEYCODE_$keyCode")
            return raw.removePrefix("KEYCODE_")
                .split("_")
                .filter { it.isNotEmpty() }
                .joinToString(" ") { it.lowercase().replaceFirstChar(Char::uppercase) }
        }

    /**
     * Which way to keep when one press shows up several ways at once: the one
     * that takes the least to hear afterwards. A key reaches Dashwheel by
     * itself; an input event or a CAN frame needs a root reader running.
     */
    val rank: Int
        get() = when {
            canKey != null -> 3
            input != null -> 2
            keyCode == KeyEvent.KEYCODE_UNKNOWN -> 1
            else -> 0
        }

    companion object {
        fun can(key: String, hex: String) = WheelKey(KeyEvent.KEYCODE_UNKNOWN, 0, canKey = key, canHex = hex)
        fun input(name: String) = WheelKey(KeyEvent.KEYCODE_UNKNOWN, 0, input = name)
    }
}

/** Where a button sits, for the map: either side of the wheel, the stalk behind it, beside the screen. */
internal enum class WheelZone { LEFT, RIGHT, STALK, PANEL, UNPLACED }

/**
 * One button on the map: its signal ([key], null for a button that gives
 * none the unit can see), the name the driver gave it (empty: named after
 * its signal), where it sits and what it does (null: nothing yet, it only
 * lights up when pressed).
 */
internal data class WheelButton(
    val uid: String,
    val key: WheelKey?,
    val name: String = "",
    val zone: WheelZone = WheelZone.UNPLACED,
    val assignment: WheelAssignment? = null
)

/**
 * Tells a steering wheel button apart from the rest of the CAN stream: a frame
 * that had been still for [quietMs] takes a new value, then goes back to the
 * old one within [releaseMs]: pressed, then let go. Speed, revs and the like
 * change too often to be still; fuel or temperature drift and never come back
 * to the exact old value. Two more things a car value flipping by itself does
 * and a button doesn't: it keeps doing it ([busyChanges] changes within
 * [busyWindowMs]: a channel alternating between two messages), and it holds
 * the new value about as long as the old one (a button is held for less time
 * than the frame was still before it). Pure, for the tests.
 */
internal class CanButtonDetector(
    private val quietMs: Long = 1_500L,
    private val releaseMs: Long = 5_000L,
    private val busyWindowMs: Long = 60_000L,
    private val busyChanges: Int = 8
) {
    private data class Pending(val idle: String, val pressed: String, val at: Long, val idleFor: Long)

    private val lastChange = HashMap<String, Long>()
    // When each frame changed lately: one that keeps flipping is live data, not a button.
    private val recent = HashMap<String, ArrayDeque<Long>>()
    // Per frame: other frames keep ticking while a button is held.
    private val pending = HashMap<String, Pending>()

    /** Whether the last change fed in came from a frame that had been still: worth showing while learning. */
    var lastWasQuiet = false
        private set

    /** How long the last press [onChange] returned was held. */
    var lastHeldMs = 0L
        private set

    fun reset() {
        pending.clear()
    }

    /** Feeds one change; returns the pressed value once its frame [key] has gone back to idle. */
    fun onChange(key: String, previousHex: String?, hex: String, at: Long): String? {
        val stillFor = lastChange[key]?.let { at - it } ?: Long.MAX_VALUE
        lastChange[key] = at
        val history = recent.getOrPut(key) { ArrayDeque() }
        while (history.isNotEmpty() && at - history.first() > busyWindowMs) history.removeFirst()
        val busy = history.size >= busyChanges
        if (previousHex != null) history.addLast(at)
        lastWasQuiet = previousHex != null && stillFor >= quietMs
        val p = pending.remove(key)
        if (p != null) {
            val held = at - p.at
            if (hex != p.idle || held > releaseMs || held >= p.idleFor) return null
            lastHeldMs = held
            return p.pressed
        }
        if (previousHex != null && stillFor >= quietMs && !busy) pending[key] = Pending(previousHex, hex, at, stillFor)
        return null
    }
}

/** Of the ways one press showed up, the one to keep ([WheelKey.rank]). */
internal fun bestOf(keys: List<WheelKey>): WheelKey? = keys.minByOrNull { it.rank }

/**
 * The steering wheel button map: every button the driver pressed while the
 * map was open, persisted as JSON, and what each one does.
 *
 * While the map is open ([mapping]) a press runs nothing: a known button
 * lights up ([lit]), an unknown one is added to the map, or given to the
 * button waiting for a signal ([listeningFor]). One press can show up
 * several ways within a moment (an Android key, the unit's key service, an
 * input event): a known one wins at once, else the best one after
 * [SAME_PRESS_MS]. A CAN press is only a [candidate] until it is pressed
 * again: a car value can flip and flip back by itself, but not on cue.
 *
 * With the map closed, a press runs what its button is bound to, from
 * [MainActivity.dispatchKeyEvent] (or the accessibility service), the unit's
 * key service ([HeadUnitKeys]), the CAN stream ([McuReader.changes]) or the
 * input events ([WheelMonitor.inputPresses]).
 */
internal object SteeringWheelStore {
    private const val PREFS = "steering_wheel"
    private const val KEY_BUTTONS = "mappings"
    /** How long a [candidate] waits for its second press. */
    private const val CONFIRM_MS = 15_000L
    /** One press arriving several ways lands within this; the same key twice within it runs once. */
    private const val SAME_PRESS_MS = 250L
    /** How long a pressed button stays lit on the map. */
    private const val LIT_MS = 700L

    /** Whether the wheel buttons are offered at all (Settings, Driving). */
    val AVAILABLE = true

    private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val detector = CanButtonDetector()
    /** This store holds a [McuReader.start]: while the map is open, or while a CAN button does something. */
    private var holdsReader = false
    /** This store holds [WheelMonitor.holdInput]: while an input-event button does something. */
    private var holdsInput = false

    private val _buttons = MutableStateFlow<List<WheelButton>>(emptyList())
    val buttons: StateFlow<List<WheelButton>> = _buttons.asStateFlow()

    private val _mapping = MutableStateFlow(false)
    /** The map is open: presses light their button or add one, and run nothing. */
    val mapping: StateFlow<Boolean> = _mapping.asStateFlow()

    private val _lit = MutableStateFlow<Set<String>>(emptySet())
    /** The buttons just pressed, by [WheelButton.uid]. */
    val lit: StateFlow<Set<String>> = _lit.asStateFlow()

    private val _listeningFor = MutableStateFlow<String?>(null)
    /** A button the next unknown press is given to: a silent one, or one whose signal is learned again. */
    val listeningFor: StateFlow<String?> = _listeningFor.asStateFlow()

    /** A CAN press seen on the map, waiting to be pressed once more (within [CONFIRM_MS]) before it counts. */
    val candidate = MutableStateFlow<WheelKey?>(null)
    private var candidateTimeout: Job? = null

    /** When a press was last counted on the map ([SystemClock.uptimeMillis]). */
    private var lastPressAt = Long.MIN_VALUE / 2
    private val burst = mutableListOf<WheelKey>()
    private var burstJob: Job? = null
    private val litJobs = HashMap<String, Job>()
    /** When each key last ran its action: the same press from two ways runs once. */
    private val lastRun = HashMap<String, Long>()

    fun setContext(context: Context) {
        if (!AVAILABLE || appContext != null) return
        appContext = context.applicationContext
        _buttons.value = load(appContext!!)
        scope.launch { McuReader.changes.collect { onCanChange(it) } }
        scope.launch { WheelMonitor.inputPresses.collect { onInputPress(it) } }
        updateReaders()
    }

    fun openMap() {
        burst.clear()
        clearCandidate()
        detector.reset()
        _mapping.value = true
        WheelMonitor.start()
        updateReaders()
    }

    fun closeMap() {
        _mapping.value = false
        _listeningFor.value = null
        burstJob?.cancel()
        burst.clear()
        clearCandidate()
        WheelMonitor.stop()
        updateReaders()
    }

    /** The next unknown press goes to [uid]; null: back to adding new buttons. */
    fun listenFor(uid: String?) {
        _listeningFor.value = uid
    }

    /** A button that gives no signal, put on the map by hand. */
    fun addSilent(name: String, zone: WheelZone): String {
        val button = WheelButton(newWheelUid(), key = null, name = name.trim(), zone = zone)
        persist(_buttons.value + button)
        return button.uid
    }

    fun rename(uid: String, name: String) = update(uid) { it.copy(name = name.trim()) }

    fun place(uid: String, zone: WheelZone) = update(uid) { it.copy(zone = zone) }

    fun assign(uid: String, assignment: WheelAssignment?) = update(uid) { it.copy(assignment = assignment) }

    fun remove(uid: String) {
        if (_listeningFor.value == uid) _listeningFor.value = null
        persist(_buttons.value.filterNot { it.uid == uid })
    }

    /** Back to the unit's own buttons: the map emptied, every action gone. */
    fun resetAll() {
        _listeningFor.value = null
        clearCandidate()
        persist(emptyList())
    }

    /** A line of the map's monitor tapped: that's the button, as if it had been recognised by itself. */
    fun learnFromMonitor(key: WheelKey) {
        if (_mapping.value) count(key)
    }

    private fun update(uid: String, change: (WheelButton) -> WheelButton) {
        persist(_buttons.value.map { if (it.uid == uid) change(it) else it })
    }

    private fun persist(updated: List<WheelButton>) {
        _buttons.value = updated
        appContext?.let { save(it, updated) }
        updateReaders()
    }

    private fun known(key: WheelKey): WheelButton? = _buttons.value.firstOrNull { it.key?.id == key.id }

    private fun bound(key: WheelKey): WheelAssignment? = known(key)?.assignment

    /** A press counted on the map: its button lights, or it becomes one. */
    private fun count(key: WheelKey) {
        lastPressAt = SystemClock.uptimeMillis()
        val button = known(key) ?: run {
            val waiting = _listeningFor.value?.let { uid -> _buttons.value.firstOrNull { it.uid == uid } }
            _listeningFor.value = null
            if (waiting != null) waiting.copy(key = key).also { b -> update(b.uid) { b } }
            else WheelButton(newWheelUid(), key).also { persist(_buttons.value + it) }
        }
        light(button.uid)
    }

    private fun light(uid: String) {
        _lit.update { it + uid }
        litJobs.remove(uid)?.cancel()
        litJobs[uid] = scope.launch {
            delay(LIT_MS)
            _lit.update { it - uid }
        }
    }

    /**
     * A key press on the map. A known button lights at once; an unknown one
     * waits a moment for the other ways the same press may come, and the
     * best of them is kept.
     */
    private fun mapPress(key: WheelKey) {
        if (known(key) != null) {
            burstJob?.cancel()
            burst.clear()
            count(key)
            return
        }
        if (SystemClock.uptimeMillis() - lastPressAt < SAME_PRESS_MS) return
        burst += key
        if (burstJob?.isActive == true) return
        burstJob = scope.launch {
            delay(SAME_PRESS_MS)
            val best = bestOf(burst)
            burst.clear()
            if (best != null) count(best)
        }
    }

    /** A CAN press was seen on the map: remember it and ask for it again; forgotten if it doesn't come. */
    private fun propose(key: WheelKey) {
        candidateTimeout?.cancel()
        candidate.value = key
        candidateTimeout = scope.launch {
            delay(CONFIRM_MS)
            if (candidate.value == key) candidate.value = null
        }
    }

    private fun clearCandidate() {
        candidateTimeout?.cancel()
        candidateTimeout = null
        candidate.value = null
    }

    /** The root readers run only while they can be needed: the map open, or a button bound to them. */
    private fun updateReaders() {
        val bound = _buttons.value.filter { it.assignment != null }.mapNotNull { it.key }
        val can = _mapping.value || bound.any { it.canKey != null }
        if (can != holdsReader) {
            holdsReader = can
            if (can) McuReader.start() else McuReader.stop()
        }
        val input = bound.any { it.input != null }
        if (input != holdsInput) {
            holdsInput = input
            WheelMonitor.holdInput(input)
        }
    }

    /**
     * Every hardware key the launcher's windows get reaches here first.
     * True means "handled" — swallow it: either the map took it, or it just
     * ran the action it's bound to. False leaves the key to Android's own
     * handling (volume UI, back, an unbound media button...).
     */
    fun onKeyEvent(context: Context, event: KeyEvent): Boolean {
        // The on-screen keyboard's keys (Delete while naming a button) are typing, not buttons.
        if (event.flags and KeyEvent.FLAG_SOFT_KEYBOARD != 0) return false
        val key = WheelKey(event.keyCode, event.scanCode)
        val press = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
        if (_mapping.value) {
            if (press) {
                WheelMonitor.add(WheelMonitor.Source.KEY, "${key.label} (${event.keyCode}/${event.scanCode})", key)
                mapPress(key)
            }
            return true
        }
        val assignment = bound(key) ?: return false
        if (press) run(context, key, assignment)
        return true
    }

    /**
     * A key from the head unit's own key service ([HeadUnitKeys]): the
     * firmware's keys (NAVI, MODE, PHONE…), which never come as Android
     * keys, and the Android ones the unit may keep to itself (its volume).
     * Counted on the map; otherwise runs what it's bound to, once per press
     * however many ways it came. The unit does its own thing with the key as
     * well.
     */
    fun onUnitKey(context: Context, keyCode: Int, event: KeyEvent?) {
        // One press: its down (the event is left out by some senders: count that as the press).
        if (event != null && (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0)) return
        val key = WheelKey(keyCode, 0)
        if (_mapping.value) {
            WheelMonitor.add(WheelMonitor.Source.KEY, "${key.label} ($keyCode)", key)
            mapPress(key)
            return
        }
        run(context, key, bound(key) ?: return)
    }

    /** A key only the input system saw ([WheelMonitor.inputPresses]). */
    private fun onInputPress(name: String) {
        val key = WheelKey.input(name)
        if (_mapping.value) return mapPress(key)
        run(appContext ?: return, key, bound(key) ?: return)
    }

    private fun onCanChange(change: McuReader.Change) {
        val e = change.entry
        val pressed = detector.onChange(e.key, change.previousHex, e.hex, e.changedAt)
        if (_mapping.value) {
            if (detector.lastWasQuiet) {
                WheelMonitor.add(WheelMonitor.Source.CAN, "${e.key}: ${change.previousHex} → ${e.hex}", WheelKey.can(e.key, e.hex))
            }
            if (pressed != null) {
                val key = WheelKey.can(e.key, pressed)
                when {
                    known(key) != null -> count(key)
                    // The same press already came another way while it was held.
                    SystemClock.uptimeMillis() - lastPressAt < detector.lastHeldMs + SAME_PRESS_MS -> Unit
                    candidate.value == key -> {
                        clearCandidate()
                        count(key)
                    }
                    else -> propose(key)
                }
            }
            return
        }
        // Run as the frame takes the pressed value, not when the button is let go.
        val key = WheelKey.can(e.key, e.hex)
        run(appContext ?: return, key, bound(key) ?: return)
    }

    private fun run(context: Context, key: WheelKey, assignment: WheelAssignment) {
        val now = SystemClock.uptimeMillis()
        if (now - (lastRun[key.id] ?: Long.MIN_VALUE / 2) < SAME_PRESS_MS) return
        lastRun[key.id] = now
        when (assignment) {
            is WheelAssignment.Preset -> assignment.action.run(context)
            is WheelAssignment.LaunchApp -> AppLauncher.launch(context, assignment.packageName)
        }
    }

    private fun load(context: Context): List<WheelButton> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_BUTTONS, null) ?: return emptyList()
        return runCatching { wheelButtonsFromJson(raw) }.getOrDefault(emptyList())
    }

    private fun save(context: Context, buttons: List<WheelButton>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_BUTTONS, wheelButtonsToJson(buttons)).apply()
    }
}

/** The map from JSON; buttons learned before the map (a key and an action, no uid) come back unplaced. */
internal fun wheelButtonsFromJson(raw: String): List<WheelButton> {
    val arr = JSONArray(raw)
    return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.toButton() }
}

internal fun wheelButtonsToJson(buttons: List<WheelButton>): String {
    val arr = JSONArray()
    buttons.forEach { arr.put(it.toJson()) }
    return arr.toString()
}

private fun WheelButton.toJson(): JSONObject {
    val obj = JSONObject().put("uid", uid).put("name", name).put("zone", zone.name)
    key?.let { k ->
        obj.put("keyCode", k.keyCode).put("scanCode", k.scanCode)
        if (k.canKey != null) obj.put("canKey", k.canKey).put("canHex", k.canHex)
        if (k.input != null) obj.put("input", k.input)
    }
    when (val a = assignment) {
        is WheelAssignment.Preset -> obj.put("t", "preset").put("a", a.action.name)
        is WheelAssignment.LaunchApp -> obj.put("t", "app").put("pkg", a.packageName).put("label", a.appLabel)
        null -> Unit
    }
    return obj
}

private fun JSONObject.toButton(): WheelButton {
    val canKey = if (has("canKey")) optString("canKey") else null
    val canHex = if (has("canHex")) optString("canHex") else null
    val key = when {
        canKey != null && canHex != null -> WheelKey.can(canKey, canHex)
        has("input") -> WheelKey.input(optString("input"))
        has("keyCode") -> WheelKey(optInt("keyCode", KeyEvent.KEYCODE_UNKNOWN), optInt("scanCode", 0))
        else -> null
    }
    val assignment = when (optString("t")) {
        "preset" -> runCatching { SteeringWheelAction.valueOf(optString("a")) }.getOrNull()?.let { WheelAssignment.Preset(it) }
        "app" -> optString("pkg").takeIf { it.isNotBlank() }?.let { WheelAssignment.LaunchApp(it, optString("label")) }
        else -> null
    }
    return WheelButton(
        uid = optString("uid").ifEmpty { key?.id ?: newWheelUid() },
        key = key,
        name = optString("name"),
        zone = runCatching { WheelZone.valueOf(optString("zone")) }.getOrDefault(WheelZone.UNPLACED),
        assignment = assignment
    )
}

internal fun newWheelUid(): String = UUID.randomUUID().toString().take(8)
