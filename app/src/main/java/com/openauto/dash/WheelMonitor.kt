package com.openauto.dash

import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * What the head unit shows while the button map is open: Android keys, CAN
 * frames that change after being still, raw input events (root `getevent`)
 * and log lines about keys (root `logcat`). Firmware differs in where the
 * wheel buttons surface; this shows where they do on this one, and a key or
 * CAN line can be tapped to learn it ([Line.learn]).
 *
 * The input events are also a way in for buttons of their own
 * ([inputPresses]): a key the input system reports but never hands to an
 * app. Read while the map is open, or while such a button is bound
 * ([holdInput]).
 */
internal object WheelMonitor {
    enum class Source { KEY, CAN, INPUT, LOG }

    data class Line(val id: Long, val source: Source, val text: String, val learn: WheelKey?)

    private const val MAX_LINES = 14
    private val LOG_MATCH = Regex("""(?i)\b(key|keycode|swc|wheel|steer|button)""")
    // The CAN stream already has its own lines; McuReader parses them.
    private val LOG_SKIP = Regex("dispatchToClients")

    private val _lines = MutableStateFlow<List<Line>>(emptyList())
    /** Newest first. */
    val lines: StateFlow<List<Line>> = _lines.asStateFlow()

    private val _inputPresses = MutableSharedFlow<String>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Each key the input system reports going down, as "KEY_NEXTSONG@event2" ([WheelKey.input]). */
    val inputPresses: SharedFlow<String> = _inputPresses.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processes = mutableListOf<Process>()
    private val jobs = mutableListOf<Job>()
    private var nextId = 0L
    private var monitoring = false
    private var inputHeld = false
    private var inputJob: Job? = null
    private var inputProcess: Process? = null

    fun add(source: Source, text: String, learn: WheelKey? = null) {
        _lines.update { listOf(Line(nextLineId(), source, text.take(160), learn)) + it.take(MAX_LINES - 1) }
    }

    @Synchronized
    private fun nextLineId(): Long = nextId++

    @Synchronized
    fun start() {
        stopLocked()
        monitoring = true
        _lines.value = emptyList()
        updateInputLocked()
        // Keys and CAN lines are added by SteeringWheelStore whatever the
        // device; the raw input and the log are read through su, which is not
        // asked for where root was not found (PrivilegedShell), nor in the Play edition.
        if (Edition.play || !PrivilegedShell.access.value.root) return
        jobs += sniff("exec logcat -v brief -T 1", onStart = { processes += it }) { line ->
            line.takeIf { LOG_MATCH.containsMatchIn(it) && !LOG_SKIP.containsMatchIn(it) }?.let { add(Source.LOG, it.trim()) }
        }
    }

    @Synchronized
    fun stop() {
        stopLocked()
        monitoring = false
        updateInputLocked()
    }

    /** Keeps the input events read while a button bound to one needs them ([SteeringWheelStore]). */
    @Synchronized
    fun holdInput(on: Boolean) {
        inputHeld = on
        updateInputLocked()
    }

    private fun stopLocked() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        processes.forEach(::kill)
        processes.clear()
    }

    private fun updateInputLocked() {
        val wanted = (monitoring || inputHeld) && Edition.full && PrivilegedShell.access.value.root
        if (wanted == (inputJob?.isActive == true)) return
        if (!wanted) {
            inputJob?.cancel()
            inputJob = null
            inputProcess?.let(::kill)
            inputProcess = null
            return
        }
        inputJob = sniff("exec getevent -lq", onStart = { inputProcess = it }) { line ->
            val press = inputPress(line) ?: return@sniff
            _inputPresses.tryEmit(press)
            if (monitoring) add(Source.INPUT, tidyGetevent(line))
        }
    }

    /** "/dev/input/event2: EV_KEY KEY_NEXTSONG DOWN" → "KEY_NEXTSONG@event2"; null for anything but a key going down. */
    internal fun inputPress(line: String): String? {
        if ("EV_KEY" !in line) return null
        val parts = line.substringAfter("EV_KEY").trim().split(Regex("\\s+"))
        if (parts.size < 2 || parts[1] != "DOWN") return null
        val device = line.substringBefore(':').substringAfterLast('/')
        return if (device.isNotEmpty() && device != line) "${parts[0]}@$device" else parts[0]
    }

    /** "/dev/input/event2: EV_KEY KEY_NEXTSONG DOWN" → "KEY_NEXTSONG DOWN (event2)". */
    internal fun tidyGetevent(line: String): String {
        val device = line.substringBefore(':').substringAfterLast('/')
        val rest = line.substringAfter("EV_KEY").trim().split(Regex("\\s+")).joinToString(" ")
        return if (device.isNotEmpty() && device != line) "$rest ($device)" else rest
    }

    /** Runs [command] under su until cancelled, handing each line to [onLine]; [onStart] keeps the process to kill it. */
    private fun sniff(command: String, onStart: (Process) -> Unit = {}, onLine: (String) -> Unit): Job = scope.launch {
        if (Edition.play) return@launch
        val p = runCatching { Runtime.getRuntime().exec(arrayOf("su", "-c", command)) }.getOrNull() ?: return@launch
        synchronized(this@WheelMonitor) { onStart(p) }
        try {
            val reader = p.inputStream.bufferedReader()
            while (isActive) onLine(reader.readLine() ?: break)
        } catch (e: IOException) {
            // Killed by stop(), or su refused: nothing more to show from here.
        } finally {
            kill(p)
        }
    }

    private fun kill(p: Process) {
        runCatching { p.inputStream.close() }
        runCatching { p.destroy() }
        runCatching { p.destroyForcibly() }
    }
}
