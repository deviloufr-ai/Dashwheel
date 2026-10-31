package com.openauto.dash

import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import com.openauto.dash.unit.QfAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
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
 *   some wheel buttons only show up here;
 * - another firmware's own key code ([vendor] names the firmware, "fyt",
 *   [vendorCode] its number), from its [KeySource]: no Android key at all;
 * - the CAN box's own code for the button ([vendor] [CarBoxKeys.VENDOR]),
 *   which tells apart buttons the firmware gives the same key;
 * - a line of the unit's log ([log], the text a line must contain, read with
 *   root `logcat` by [WheelMonitor]): a firmware that logs each wheel key and
 *   hands it to no app. The driver taps the line on the map, then trims the
 *   text down to the part that names the button.
 */
internal data class WheelKey(
    val keyCode: Int,
    val scanCode: Int,
    val canKey: String? = null,
    val canHex: String? = null,
    val input: String? = null,
    val vendor: String? = null,
    val vendorCode: Int = 0,
    val log: String? = null
) {
    val id: String
        get() = when {
            vendor != null -> "v:$vendor:$vendorCode"
            canKey != null -> "c:$canKey=$canHex"
            input != null -> "i:$input"
            log != null -> "l:$log"
            keyCode != KeyEvent.KEYCODE_UNKNOWN -> "k$keyCode"
            else -> "s$scanCode"
        }

    /** Whether the log line [line] ([WheelMonitor.logPattern]) is this button's: it holds the learned text. */
    fun matchesLog(line: String): Boolean = log != null && log.isNotEmpty() && line.contains(log)

    /** A readable name: "Media Next", "Volume Up"... The map words the raw and CAN ones in the chosen language. */
    val label: String
        get() {
            if (vendor == CarBoxKeys.VENDOR) return wheelBoxLabel(vendorCode)
            if (vendor != null) return "${vendor.uppercase()} key $vendorCode"
            if (canKey != null) return "CAN $canKey: $canHex"
            if (input != null) return input
            if (log != null) return log
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
     * itself; an input event, a CAN frame or a log line needs a root reader running.
     */
    val rank: Int
        get() = when {
            log != null -> 4
            canKey != null -> 3
            input != null -> 2
            keyCode == KeyEvent.KEYCODE_UNKNOWN -> 1
            else -> 0
        }

    companion object {
        fun can(key: String, hex: String) = WheelKey(KeyEvent.KEYCODE_UNKNOWN, 0, canKey = key, canHex = hex)
        fun input(name: String) = WheelKey(KeyEvent.KEYCODE_UNKNOWN, 0, input = name)
        fun vendor(vendor: String, code: Int) = WheelKey(KeyEvent.KEYCODE_UNKNOWN, 0, vendor = vendor, vendorCode = code)
        fun log(text: String) = WheelKey(KeyEvent.KEYCODE_UNKNOWN, 0, log = text)
    }
}

/** Where a button sits, for the map: either side of the wheel, the stalk behind it, beside the screen. */
internal enum class WheelZone { LEFT, RIGHT, STALK, PANEL, UNPLACED }

/**
 * One button on the map: its signal ([key], null for a button that gives
 * none the unit can see), the name the driver gave it (empty: named after
 * its signal), where it sits and what it does (null: nothing yet, it only
 * lights up when pressed). [unitIgnores]: a CAN box button the unit itself
 * does nothing with any more ([FirmwareKeyTable]). [second]: what it does
 * instead in mode 2 ([ScreenFocus]); null: the same as in mode 1.
 */
internal data class WheelButton(
    val uid: String,
    val key: WheelKey?,
    val name: String = "",
    val zone: WheelZone = WheelZone.UNPLACED,
    val assignment: WheelAssignment? = null,
    val unitIgnores: Boolean = false,
    val second: WheelAssignment? = null
) {
    /** The button that switches between mode 1 and mode 2: it does the same in both, or nothing could switch back. */
    val switchesMode: Boolean get() = assignment == WheelAssignment.Preset(SteeringWheelAction.SCREEN_FOCUS)

    /** Does something in one mode or the other. */
    val isBound: Boolean get() = assignment != null || second != null

    /** What a press runs in mode 2 ([mode2]) or mode 1: its second action, where it has one, else its own. */
    fun actionFor(mode2: Boolean): WheelAssignment? = if (mode2 && second != null && !switchesMode) second else assignment
}

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
 * key service ([HeadUnitKeys]), the CAN stream ([McuReader.changes]), the
 * input events ([WheelMonitor.inputPresses]) or the unit's log ([WheelMonitor.logLines]).
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
    /** A CAN box hold the box never let go of ends by itself after this. */
    private const val BOX_HOLD_MAX_MS = 3_000L
    /** A box code new to the map waits this long after its release for the firmware's key, then is a button of its own. */
    private const val BOX_LEARN_MS = 600L
    /** A held box button bound to a volume step: the first repeat, then each one after (run() lets one through per [SAME_PRESS_MS]). */
    private const val BOX_REPEAT_FIRST_MS = 500L
    private const val BOX_REPEAT_MS = 300L

    /** Whether the wheel buttons are offered at all (Settings, Driving). */
    val AVAILABLE = true

    private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val detector = CanButtonDetector()
    /** This store holds a [McuReader.start]: while the map is open, or while a CAN button does something. */
    private var holdsReader = false
    /** This store holds [WheelMonitor.holdInput]: while an input-event button does something. */
    private var holdsInput = false
    /** This store holds [WheelMonitor.holdLog]: while a log-line button does something. */
    private var holdsLog = false

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
    /** A CAN box press and the firmware's key for it, paired whichever comes first. */
    private val box = BoxPressPairing(holdMaxMs = BOX_HOLD_MAX_MS)
    /** A box code seen on the map that no button has yet: it waits for the firmware's key for the same press, whose button it takes over. */
    private var boxLearn: WheelKey? = null
    private var boxLearnJob: Job? = null
    /** A held box button bound to a volume step, stepping again and again as a held key would. */
    private var boxRepeat: Job? = null

    fun setContext(context: Context) {
        if (!AVAILABLE || appContext != null) return
        appContext = context.applicationContext
        _buttons.value = load(appContext!!)
        scope.launch { McuReader.changes.collect { onCanChange(it) } }
        scope.launch { WheelMonitor.inputPresses.collect { onInputPress(it) } }
        scope.launch { WheelMonitor.logLines.collect { onLogLine(it) } }
        updateReaders()
        syncSilenced(_buttons.value)
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
        clearBox()
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

    fun assignSecond(uid: String, assignment: WheelAssignment?) = update(uid) { it.copy(second = assignment) }

    fun setUnitIgnores(uid: String, ignores: Boolean) = update(uid) { it.copy(unitIgnores = ignores) }

    /**
     * The text a log line must contain for the button [uid], learned from a
     * tapped line and trimmed by the driver down to what names the button
     * (a process id or a count in it would change). Blank: left as it was.
     */
    fun setLogMatch(uid: String, text: String) {
        val match = text.trim().ifEmpty { return }
        update(uid) { if (it.key?.log != null) it.copy(key = WheelKey.log(match)) else it }
    }

    /** Whether the unit can be told to ignore [key]: a CAN box code, on a rooted QF unit. */
    fun canSilence(key: WheelKey?): Boolean =
        key?.vendor == CarBoxKeys.VENDOR && Edition.full && PrivilegedShell.access.value == PrivilegedShell.Access.ROOT &&
            appContext?.let { UnitPlatform.hasPackage(it, QfAdapter.VEHICLE_PACKAGE) } == true

    /** The box codes the unit is told to ignore, as last written; null before the first time. */
    private var silencedWritten: Set<Int>? = null

    /**
     * The unit's key table follows the map's buttons that it must ignore.
     * At start only when some are, to put them back if the unit's own SWC study
     * was saved since; written by [FirmwareKeyTable] only when the table changes.
     */
    private fun syncSilenced(buttons: List<WheelButton>) {
        val silenced = buttons.filter { it.unitIgnores && it.key?.vendor == CarBoxKeys.VENDOR }.mapNotNull { it.key?.vendorCode }.toSet()
        if (silenced == silencedWritten || (silencedWritten == null && silenced.isEmpty())) return
        silencedWritten = silenced
        scope.launch(Dispatchers.IO) { FirmwareKeyTable.apply(silenced) }
    }

    fun remove(uid: String) {
        if (_listeningFor.value == uid) _listeningFor.value = null
        persist(_buttons.value.filterNot { it.uid == uid })
    }

    /** Back to the unit's own buttons: the map emptied, every action gone. */
    fun resetAll() {
        _listeningFor.value = null
        clearCandidate()
        clearBox()
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
        syncSilenced(updated)
    }

    private fun known(key: WheelKey): WheelButton? = _buttons.value.firstOrNull { it.key?.id == key.id }

    private fun bound(key: WheelKey): WheelAssignment? = known(key)?.actionFor(ScreenFocus.second.value)

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
        val bound = _buttons.value.filter { it.isBound }.mapNotNull { it.key }
        val can = _mapping.value || bound.any { it.canKey != null || it.vendor == CarBoxKeys.VENDOR }
        if (can != holdsReader) {
            holdsReader = can
            if (can) McuReader.start() else {
                McuReader.stop()
                clearBox()
            }
        }
        val input = bound.any { it.input != null }
        if (input != holdsInput) {
            holdsInput = input
            WheelMonitor.holdInput(input)
        }
        val log = bound.any { it.log != null }
        if (log != holdsLog) {
            holdsLog = log
            WheelMonitor.holdLog(log)
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
        val now = SystemClock.uptimeMillis()
        // The CAN box's code stood for this press: counted or run as that, not twice.
        // Only the press is taken on its account; its repeats and release follow it, other keys pass.
        if (press) {
            box.pending(now)?.let { b ->
                if (_mapping.value || bound(b) != null) {
                    box.swallow(key.id)
                    boxFollowed(b, key)
                    return true
                }
            }
        } else if (box.follows(key.id, release = event.action == KeyEvent.ACTION_UP)) return true
        if (_mapping.value) {
            if (press) {
                WheelMonitor.add(WheelMonitor.Source.KEY, "${key.label} (${event.keyCode}/${event.scanCode})", key)
                mapPress(key)
                box.firmwarePress(key, now, handled = true)
            }
            return true
        }
        val assignment = bound(key)
        if (press) box.firmwarePress(key, now, handled = assignment != null)
        if (assignment == null) return false
        // Held down, Android sends the key again and again: for the volume each
        // one is a step (paced by run()), as on the unit's own buttons.
        val heldVolume = event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0 && assignment.isVolumeStep()
        if (press || heldVolume) run(context, key, assignment)
        return true
    }

    /**
     * Whether [event] is the firmware's key for a CAN box press bound on the
     * map: [onKeyEvent] takes it on that press's account, so nothing ahead of
     * it may act on it as well.
     */
    fun belongsToBoxPress(event: KeyEvent): Boolean {
        val press = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
        return if (press) box.pending(SystemClock.uptimeMillis())?.let { bound(it) } != null
        else box.swallowing(WheelKey(event.keyCode, event.scanCode).id)
    }

    private fun WheelAssignment.isVolumeStep(): Boolean =
        this is WheelAssignment.Preset && (action == SteeringWheelAction.VOLUME_UP || action == SteeringWheelAction.VOLUME_DOWN)

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
        unitPress(context, WheelKey(keyCode, 0), keyCode)
    }

    /**
     * A key from another firmware's key source ([KeySource]): its own code,
     * no Android key. Counted on the map, else runs what it's bound to.
     */
    fun onUnitKey(context: Context, press: UnitKeyPress, source: KeySource) {
        if (!press.down) return
        // A firmware that hands Android's own codes out (the QF key service) is wired straight to [onUnitKey] above.
        unitPress(context, WheelKey.vendor(source.vendor, press.code), press.code)
    }

    /**
     * One press of a unit key ([code] on the map): a box press's own when one
     * is pending, else counted on the map, else runs what it's bound to.
     */
    private fun unitPress(context: Context, key: WheelKey, code: Int) {
        val now = SystemClock.uptimeMillis()
        box.pending(now)?.let { b ->
            if (_mapping.value || bound(b) != null) return boxFollowed(b, key)
        }
        if (_mapping.value) {
            WheelMonitor.add(WheelMonitor.Source.KEY, "${key.label} ($code)", key)
            mapPress(key)
            box.firmwarePress(key, now, handled = true)
            return
        }
        val assignment = bound(key)
        box.firmwarePress(key, now, handled = assignment != null)
        run(context, key, assignment ?: return)
    }

    /**
     * A button's code from the CAN box's key frame ([CarBoxKeys]), 0 when it
     * is let go. Counted on the map, else runs what it's bound to, as it is
     * pressed. The firmware's key for the same press usually follows when it
     * is let go, and is taken on the box press's account ([BoxPressPairing]);
     * read off the root log it may come first instead, and then the box code
     * runs nothing it already ran. The firmware still does its own thing with it.
     *
     * On the map, a code no button has yet waits for the firmware's key: the
     * button that key has (learned before box codes told the buttons apart)
     * is keyed by the code from then on, instead of a second button for the
     * same press ([adopt]). Held and bound to a volume step, it keeps stepping.
     */
    fun onBoxKey(code: Int) {
        val now = SystemClock.uptimeMillis()
        if (code == 0) {
            box.release(now)
            boxRepeat?.cancel()
            boxRepeat = null
            if (boxLearn != null) {
                boxLearnJob?.cancel()
                boxLearnJob = scope.launch {
                    delay(BOX_LEARN_MS)
                    boxLearn?.let { b ->
                        boxLearn = null
                        count(b)
                    }
                }
            }
            return
        }
        val key = WheelKey.vendor(CarBoxKeys.VENDOR, code)
        // The MCU service hands the code over before the firmware's key; only the root log may lag behind it.
        val first = box.press(key, now, late = !McuFeed.listening.value)
        boxRepeat?.cancel()
        boxRepeat = null
        if (_mapping.value) {
            WheelMonitor.add(WheelMonitor.Source.CAN, wheelBoxLabel(code), key)
            when {
                known(key) != null -> mapPress(key)
                first != null -> adopt(key, first.key)
                else -> {
                    boxLearnJob?.cancel()
                    // The one before never got its firmware key: a button of its own.
                    boxLearn?.takeIf { it != key }?.let { count(it) }
                    boxLearn = key
                }
            }
            return
        }
        if (first?.handled == true) return
        val context = appContext ?: return
        val assignment = bound(key) ?: return
        run(context, key, assignment)
        if (!assignment.isVolumeStep()) return
        boxRepeat = scope.launch {
            val until = now + BOX_HOLD_MAX_MS
            delay(BOX_REPEAT_FIRST_MS)
            while (isActive && SystemClock.uptimeMillis() < until) {
                run(context, key, assignment)
                delay(BOX_REPEAT_MS)
            }
        }
    }

    /**
     * A wheel or unit key Dashwheel did nothing with was just pressed: the
     * screen the firmware opens for it is the driver's wish ([UnitPhoneScreen]).
     */
    fun keyLeftToFirmware(): Boolean = box.leftToFirmware(SystemClock.uptimeMillis())

    /** The firmware's key [firmware] came for the box press [boxKey]: a code waiting on the map takes over that key's button. */
    private fun boxFollowed(boxKey: WheelKey, firmware: WheelKey) {
        if (boxLearn != boxKey) return
        boxLearn = null
        boxLearnJob?.cancel()
        adopt(boxKey, firmware)
    }

    /**
     * A box code new to the map, paired with the firmware key [firmware] for
     * the same press. The button that key has is keyed by the box code from
     * now on, with its name, place and action: no second button for one
     * press. With none, or a button waiting to learn, the code is a button of
     * its own, and the firmware key still waiting its moment is dropped.
     */
    private fun adopt(boxKey: WheelKey, firmware: WheelKey) {
        known(boxKey)?.let { light(it.uid); return }
        if (_listeningFor.value == null) {
            known(firmware)?.let { old ->
                update(old.uid) { it.copy(key = boxKey) }
                light(old.uid)
                return
            }
        }
        burst.remove(firmware)
        count(boxKey)
    }

    private fun clearBox() {
        box.clear()
        boxLearn = null
        boxLearnJob?.cancel()
        boxLearnJob = null
        boxRepeat?.cancel()
        boxRepeat = null
    }

    /** A key only the input system saw ([WheelMonitor.inputPresses]). */
    private fun onInputPress(name: String) {
        val key = WheelKey.input(name)
        if (_mapping.value) return mapPress(key)
        run(appContext ?: return, key, bound(key) ?: return)
    }

    /**
     * A line of the unit's log ([WheelMonitor.logLines], tidied). On the map a
     * button learned from such a line lights up; a new line is learned only by
     * tapping it there ([learnFromMonitor]), since the log says much that is no
     * button. Off the map, the button runs what it is bound to. A key the
     * firmware logs on its way down and up runs once ([run]'s pacing).
     */
    private fun onLogLine(line: String) {
        val button = _buttons.value.firstOrNull { it.key?.matchesLog(line) == true } ?: return
        val key = button.key ?: return
        if (_mapping.value) return count(key)
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

/**
 * One steering wheel press seen twice: as the CAN box's own key code
 * ([CarBoxKeys], as the button goes down) and as the firmware's key for it
 * (as it is let go, a few ms later). Whichever comes first stands for the
 * press, the other is dropped:
 * - a firmware key pressed while the box code is held, or within [followMs]
 *   of its release, is the box press's ([pending]). Only that press is taken;
 *   its repeats and its release go with it ([swallow], [follows]), other keys pass;
 * - a box code within [lateMs] of a firmware press that nothing stood for
 *   (the root log hands the stream over late) belongs to that press: [press]
 *   gives that key and whether it already did the press's job, and no key
 *   is taken on that box press's account.
 * A hold the box never let go of ends by itself after [holdMaxMs]: a lost
 * release frame must not take every key on the unit.
 *
 * A firmware key Dashwheel did nothing with is left to the firmware: the
 * screen it opens within [screenMs] is wanted ([leftToFirmware]).
 * Pure, for the tests.
 */
internal class BoxPressPairing(
    private val followMs: Long = 400L,
    private val holdMaxMs: Long = 3_000L,
    private val lateMs: Long = 1_000L,
    private val screenMs: Long = 2_500L
) {
    private var box: WheelKey? = null
    private var held = false
    private var pressedAt = NEVER
    private var releasedAt = NEVER
    /** The firmware key for this box press came first: nothing is taken on its account. */
    private var taken = false
    private var firmware: FirmwarePress? = null
    /** The firmware keys taken as a box press's, by [WheelKey.id], until their release. */
    private val swallowed = HashSet<String>()
    /** When a firmware key Dashwheel did nothing with was last pressed. */
    private var leftAt = NEVER

    /** A firmware key pressed with no box press to belong to, and whether it did the press's job (ran an action, or was counted on the map). */
    class FirmwarePress(val key: WheelKey, val handled: Boolean, val at: Long)

    /**
     * The box code [key] went down at [now]. The firmware's key for this
     * press when it came just before, else null: the box press then runs
     * nothing that key already did.
     */
    fun press(key: WheelKey, now: Long, late: Boolean = true): FirmwarePress? {
        box = key
        held = true
        pressedAt = now
        // Only a stream that lags ([late]) can bring the box code after its key: else that key was another press.
        val first = firmware?.takeIf { late && now - it.at < lateMs }
        taken = first != null
        firmware = null
        return first
    }

    /** The box let its key go at [now]. */
    fun release(now: Long) {
        if (held) releasedAt = now
        held = false
    }

    /** Whether a screen the firmware opens at [now] follows a key Dashwheel left to it. */
    fun leftToFirmware(now: Long): Boolean = leftAt != NEVER && now - leftAt < screenMs

    /** The box key a firmware key pressed at [now] belongs to: one held, or let go just before; null for none. */
    fun pending(now: Long): WheelKey? {
        val b = box ?: return null
        if (taken) return null
        val holding = held && now - pressedAt < holdMaxMs
        return b.takeIf { holding || (releasedAt != NEVER && now - releasedAt < followMs) }
    }

    /** The firmware key [id], pressed, was taken as the box press's: its repeats and release are too. */
    fun swallow(id: String) {
        swallowed += id
    }

    /** Whether the press of the firmware key [id] was taken ([swallow]) and it isn't let go yet. */
    fun swallowing(id: String): Boolean = id in swallowed

    /** A repeat or the release of the firmware key [id]: true when its press was taken ([swallow]). */
    fun follows(id: String, release: Boolean): Boolean {
        if (id !in swallowed) return false
        if (release) swallowed -= id
        return true
    }

    /** The firmware key [key] pressed at [now] with no box press to belong to; [handled] when it ran an action or was counted on the map. */
    fun firmwarePress(key: WheelKey, now: Long, handled: Boolean) {
        firmware = FirmwarePress(key, handled, now)
        if (!handled) leftAt = now
    }

    /** Forgets everything: the map closed, the reader stopped. */
    fun clear() {
        box = null
        held = false
        pressedAt = NEVER
        releasedAt = NEVER
        taken = false
        firmware = null
        swallowed.clear()
        leftAt = NEVER
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE
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
        if (k.vendor != null) obj.put("vendor", k.vendor).put("vendorCode", k.vendorCode)
        if (k.log != null) obj.put("log", k.log)
    }
    assignment?.let { obj.putAssignment(it, "") }
    // Mode 2's action under the same names with a 2: a map saved before mode 2 reads as having none.
    second?.let { obj.putAssignment(it, "2") }
    if (unitIgnores) obj.put("unitIgnores", true)
    return obj
}

private fun JSONObject.putAssignment(a: WheelAssignment, suffix: String) {
    when (a) {
        is WheelAssignment.Preset -> put("t$suffix", "preset").put("a$suffix", a.action.name)
        is WheelAssignment.LaunchApp -> put("t$suffix", "app").put("pkg$suffix", a.packageName).put("label$suffix", a.appLabel)
    }
}

private fun JSONObject.assignment(suffix: String): WheelAssignment? = when (optString("t$suffix")) {
    "preset" -> runCatching { SteeringWheelAction.valueOf(optString("a$suffix")) }.getOrNull()?.let { WheelAssignment.Preset(it) }
    "app" -> optString("pkg$suffix").takeIf { it.isNotBlank() }?.let { WheelAssignment.LaunchApp(it, optString("label$suffix")) }
    else -> null
}

private fun JSONObject.toButton(): WheelButton {
    val canKey = if (has("canKey")) optString("canKey") else null
    val canHex = if (has("canHex")) optString("canHex") else null
    val key = when {
        has("vendor") -> WheelKey.vendor(optString("vendor"), optInt("vendorCode", 0))
        canKey != null && canHex != null -> WheelKey.can(canKey, canHex)
        has("input") -> WheelKey.input(optString("input"))
        has("log") -> WheelKey.log(optString("log"))
        has("keyCode") -> WheelKey(optInt("keyCode", KeyEvent.KEYCODE_UNKNOWN), optInt("scanCode", 0))
        else -> null
    }
    return WheelButton(
        uid = optString("uid").ifEmpty { key?.id ?: newWheelUid() },
        key = key,
        name = optString("name"),
        zone = runCatching { WheelZone.valueOf(optString("zone")) }.getOrDefault(WheelZone.UNPLACED),
        assignment = assignment(""),
        unitIgnores = optBoolean("unitIgnores", false),
        second = assignment("2")
    )
}

internal fun newWheelUid(): String = UUID.randomUUID().toString().take(8)

/** A CAN box key code as the map shows it: "0x29". */
internal fun wheelBoxLabel(code: Int): String = "0x%02X".format(code)
