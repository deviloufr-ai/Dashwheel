package com.openauto.dash.vehicle

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * When a source stops being believed. A state key (doors, reverse, the belt...) has no clock of its own: it
 * lives while its source is up. So every provider whose feed can go quiet says so, and the bus then drops
 * everything the source said, instead of holding a door open or a reverse on for good.
 */

/**
 * Pure bookkeeping of "has this feed said anything lately": [heard] each word, [check] now and then. The clock
 * is injected so a test drives it.
 */
class SilenceWatch(val timeoutMs: Long, private val clock: () -> Long = SystemClock::elapsedRealtime) {
    private var lastAt = 0L
    private var alive = false

    /** A word from the source; true when it ends a silence (or is the first one). */
    @Synchronized
    fun heard(): Boolean {
        lastAt = clock()
        val comesBack = !alive
        alive = true
        return comesBack
    }

    /** True once when the source has just gone silent: [timeoutMs] since its last word. */
    @Synchronized
    fun check(): Boolean {
        if (!alive || clock() - lastAt < timeoutMs) return false
        alive = false
        return true
    }

    /** The feed itself stopped: silent at once; true when it was not already. */
    @Synchronized
    fun stop(): Boolean {
        val wasAlive = alive
        alive = false
        return wasAlive
    }

    @get:Synchronized
    val isAlive: Boolean get() = alive

    /** How long until [check] can say silent, if no word comes. */
    @Synchronized
    fun untilSilent(): Long = if (alive) (lastAt + timeoutMs - clock()).coerceAtLeast(0L) else timeoutMs
}

/**
 * A provider's liveness: call [heard] for every word the source says (also the ones the bus never sees) and
 * [stopped] when its feed is stopped or lost. After [silenceMs] without a word the source goes [onSilent];
 * the next word brings it [onBack]. [of] does what almost every provider needs: clear and go down, and up again.
 */
class SourceLiveness(
    silenceMs: Long,
    private val scope: CoroutineScope,
    clock: () -> Long = SystemClock::elapsedRealtime,
    private val onBack: () -> Unit,
    private val onSilent: () -> Unit
) {
    private val watch = SilenceWatch(silenceMs, clock)
    private var timer: Job? = null

    /** Any word from the source. */
    fun heard() {
        if (!watch.heard()) return
        onBack()
        synchronized(this) {
            timer?.cancel()
            timer = scope.launch {
                while (watch.isAlive) {
                    delay(watch.untilSilent().coerceAtLeast(MIN_TICK_MS))
                    check()
                }
            }
        }
    }

    /** The feed is stopped, unregistered or lost: silent now, without waiting. */
    fun stopped() {
        if (!watch.stop()) return
        synchronized(this) { timer?.cancel(); timer = null }
        onSilent()
    }

    /** One look at the clock (the timer's, and a test's). */
    fun check() {
        if (watch.check()) onSilent()
    }

    val isAlive: Boolean get() = watch.isAlive

    companion object {
        private const val MIN_TICK_MS = 200L

        /** The usual: silent means the source's values are withdrawn and the source is down; a word brings it up. */
        fun of(out: Publisher, silenceMs: Long, scope: CoroutineScope, clock: () -> Long = out.clock) =
            SourceLiveness(silenceMs, scope, clock, onBack = { out.up(true) }, onSilent = { out.clear(); out.up(false) })
    }
}
