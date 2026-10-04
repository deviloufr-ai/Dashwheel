package com.openauto.dash

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The Signal Finder's rounds: the driver keeps still a moment, then does
 * the action and undoes it, three times, told each step out loud. Each step
 * moves on by itself once something that kept still before changes and holds
 * a moment (or goes back), so it works from outside the car; [next] moves on
 * by hand. After each round the pieces that followed are ranked
 * ([SignalMatch]) for the driver to pick one.
 */
object SignalFinder {

    enum class Step { STILL, ON, OFF, DONE }

    data class State(
        val target: CarSignal,
        val step: Step,
        /** The round under way, 1 to [ROUNDS]; after the last, [ROUNDS]. */
        val round: Int,
        /** Rounds finished. */
        val done: Int,
        val candidates: List<SignalCandidate>,
        /** How many values are being listened to. */
        val values: Int
    )

    const val ROUNDS = 3
    private const val TICK_MS = 250L
    private const val STILL_MS = 4_000L
    /** A change has to hold this many readings before the step moves on: a flicker doesn't. */
    private const val HOLD_TICKS = 3

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state

    @Volatile private var nudged = false

    /** Starts finding [target] over, from keeping still. */
    fun start(context: Context, target: CarSignal) {
        stop()
        val app = AppLanguage.wrap(context.applicationContext)
        nudged = false
        _state.value = State(target, Step.STILL, 1, 0, emptyList(), 0)
        SignalSources.start(app)
        job = scope.launch {
            try {
                run(app, target)
            } finally {
                SignalSources.stop()
            }
        }
    }

    /** The step is done: moves on without waiting for the car. */
    fun next() {
        nudged = true
    }

    fun stop() {
        job?.cancel()
        job = null
        _state.value = null
    }

    private suspend fun run(app: Context, target: CarSignal) {
        val locale = app.resources.configuration.locales[0] ?: Locale.getDefault()
        fun say(text: String) = CarVoice.speak(text, locale)
        fun update(change: (State) -> State) {
            _state.value?.let { _state.value = change(it).copy(values = SignalSources.values.value.size) }
        }
        fun sample(): Map<String, String> = SignalProbe.flatten(SignalSources.values.value)

        // The first reading needs the lists to be in.
        var waited = 0L
        while (SignalSources.values.value.isEmpty() && waited < 3_000) { delay(TICK_MS); waited += TICK_MS }

        say(app.getString(R.string.signals_say_still))
        val baseline = ArrayList<Map<String, String>>()
        var elapsed = 0L
        while (elapsed < STILL_MS) {
            baseline += sample()
            update { it }
            delay(TICK_MS)
            elapsed += TICK_MS
        }
        val noisy = SignalMatch.noisy(baseline)
        // Each piece's resting value; one heard for the first time in a round (a message the
        // car sends only when the door opens) gets the one it went back to after it.
        val rest = HashMap(baseline.last())
        val quiet = rest.keys - noisy
        val changes = HashMap<String, Int>()
        var previous: Map<String, String> = rest
        val rounds = ArrayList<SignalRound>()
        var candidates = emptyList<SignalCandidate>()

        fun tick(): Map<String, String> {
            val now = sample()
            for (id in quiet) if (now[id] != previous[id]) changes[id] = (changes[id] ?: 0) + 1
            previous = now
            return now
        }

        for (round in 1..ROUNDS) {
            update { it.copy(step = Step.ON, round = round) }
            val prompt = app.getString(target.onPrompt)
            say(if (round == 1) prompt else app.getString(R.string.signals_say_again, prompt))
            // From the second round, what followed so far is what to wait for.
            val watch = candidates.filter { it.matched == round - 1 }.map { it.probe.id }.ifEmpty { quiet.toList() }
            nudged = false
            var held = 0
            var on: Map<String, String>
            var moved: Set<String>
            while (true) {
                delay(TICK_MS)
                on = tick()
                moved = SignalMatch.flipped(rest, on, watch) + (if (round == 1) on.keys - rest.keys else emptySet())
                held = if (moved.isNotEmpty()) held + 1 else 0
                update { it }
                if (held >= HOLD_TICKS || nudged) break
            }
            if (moved.isEmpty()) moved = SignalMatch.flipped(rest, on, quiet)

            update { it.copy(step = Step.OFF) }
            say(app.getString(target.offPrompt))
            nudged = false
            held = 0
            var off: Map<String, String>
            while (true) {
                delay(TICK_MS)
                off = tick()
                val back = moved.count { id -> rest[id]?.let { off[id] == it } ?: (off[id] != on[id]) }
                held = if (moved.isNotEmpty() && back * 2 >= moved.size) held + 1 else 0
                update { it }
                if (held >= HOLD_TICKS || nudged) break
            }
            for (id in on.keys - rest.keys) off[id]?.let { rest[id] = it }
            rounds += SignalRound(on, off)
            candidates = SignalMatch.rank(rest, rounds, noisy, changes)
            update { it.copy(done = round, candidates = candidates) }
        }
        update { it.copy(step = Step.DONE, round = ROUNDS) }
        say(app.getString(if (candidates.any { it.matched == ROUNDS }) R.string.signals_say_found else R.string.signals_say_none))
    }
}
