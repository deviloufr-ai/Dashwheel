package com.openauto.dash

import android.view.MotionEvent

/**
 * What one tap on an app tile cost, from the finger's down to its lift
 * ([EmbeddedApp.Host.touch]): how late the down reached the sender, how long
 * each raise for the keys took, and whether Android took each touch. Pure
 * bookkeeping, so the unit report's lines ("Log now") can be tested.
 */
internal class TapCost(val packageName: String) {

    /** One touch's part: the raise made before it (null: none needed), whether it was taken (null: the call threw) and how long the injection took. */
    private class Part(val raiseMs: Long?, val injected: Boolean?, val injectMs: Long)

    private var down: Part? = null
    private var up: Part? = null
    private var cancelled = false
    private var again = false

    /** How long after the finger landed its down was sent on; -1 until known. */
    var downLateMs: Long = -1
        private set

    /** The finger's moves in between, and how many of them Android refused. */
    var moves = 0
        private set
    var movesRefused = 0
        private set

    /**
     * Notes the touch [action]'s part: [raiseMs] when a raise was made before it,
     * [injected] what the injection returned (null: it threw) in [injectMs].
     * True once the tap is over (its up or a cancel), when it is worth a line.
     */
    fun add(action: Int, raiseMs: Long?, injected: Boolean?, injectMs: Long, lateMs: Long = -1): Boolean = when (action) {
        MotionEvent.ACTION_DOWN -> { down = Part(raiseMs, injected, injectMs); downLateMs = lateMs; false }
        MotionEvent.ACTION_UP -> { up = Part(raiseMs, injected, injectMs); true }
        MotionEvent.ACTION_CANCEL -> { cancelled = true; true }
        MotionEvent.ACTION_MOVE -> { moves++; if (injected != true) movesRefused++; false }
        else -> false
    }

    /** The app lost the keys during the tap, which was then told again whole ([EmbeddedApp.Host]). */
    fun retapped() {
        again = true
    }

    /** Whether any of the tap's touches never reached the app. */
    val lost: Boolean
        get() = down?.injected != true || movesRefused > 0 || (!cancelled && up?.injected != true)

    /** The longest raise made for this tap, 0 without one. */
    val slowestRaiseMs: Long
        get() = maxOf(down?.raiseMs ?: 0, up?.raiseMs ?: 0)

    /** Whether a raise took longer than a tap can hide ([SLOW_RAISE_MS]). */
    val slow: Boolean get() = slowestRaiseMs > SLOW_RAISE_MS

    /** Whether the line deserves a warning: something was lost or slow. */
    val worrying: Boolean get() = lost || slow

    /** One compact line for the log: "tap on pkg: 35 ms late, raise 180 ms, inject ok 3 ms, 12 moves, up raise 12 ms, inject ok 2 ms[, tapped again]". */
    fun line(): String = buildString {
        append("tap on ").append(packageName).append(": ")
        if (downLateMs >= 0) append(downLateMs).append(" ms late, ")
        append(part(down))
        if (moves > 0) {
            append(", ").append(moves).append(if (moves == 1) " move" else " moves")
            if (movesRefused > 0) append(" (").append(movesRefused).append(" refused)")
        }
        if (cancelled) append(", cancelled") else append(", up ").append(part(up))
        if (again) append(", tapped again")
    }

    private fun part(p: Part?): String {
        if (p == null) return "no raise, no inject"
        val raise = if (p.raiseMs == null) "no raise" else "raise ${p.raiseMs} ms"
        val inject = when (p.injected) {
            true -> "inject ok ${p.injectMs} ms"
            false -> "inject refused ${p.injectMs} ms"
            null -> "inject failed"
        }
        return "$raise, $inject"
    }

    companion object {
        /** A raise longer than this is felt as lag on a tap. */
        const val SLOW_RAISE_MS = 100L
    }
}

/**
 * The taps' costs added up, summed in one line at most once a [periodMs], so
 * a laggy unit's report says how many taps were lost without flooding logcat.
 * Taps come on the touch thread and the keys' raises on the main one, hence
 * synchronized.
 */
internal class TapStats(private val periodMs: Long = 60_000L) {

    private var since = -1L
    private var taps = 0
    private var lost = 0
    private var slowRaises = 0
    private var slowestMs = 0L
    private var slowestWhat = ""

    /** Adds a finished [tap] at [now] (an uptime); the period's summary line when one is due, else null. */
    @Synchronized
    fun add(tap: TapCost, now: Long): String? {
        if (since < 0) since = now
        taps++
        if (tap.lost) lost++
        raise(tap.slowestRaiseMs, "tap on ${tap.packageName}")
        return flush(now)
    }

    /** Adds a raise of the keys made outside a tap ([EmbeddedApp.giveKeysBack]) that took [ms]; the summary line when due. */
    @Synchronized
    fun raised(ms: Long, what: String, now: Long): String? {
        if (since < 0) since = now
        raise(ms, what)
        return flush(now)
    }

    private fun raise(ms: Long, what: String) {
        if (ms > TapCost.SLOW_RAISE_MS) slowRaises++
        if (ms > slowestMs) { slowestMs = ms; slowestWhat = what }
    }

    private fun flush(now: Long): String? {
        if (now - since < periodMs) return null
        val line = line(now - since)
        since = now
        taps = 0; lost = 0; slowRaises = 0; slowestMs = 0; slowestWhat = ""
        return line
    }

    /** "tile taps, last 60 s: 12 taps, 3 lost, 2 slow raises, slowest 340 ms (tap on pkg)". */
    private fun line(overMs: Long): String = buildString {
        append("tile taps, last ").append(overMs / 1000).append(" s: ")
        append(taps).append(if (taps == 1) " tap, " else " taps, ")
        append(lost).append(" lost, ")
        append(slowRaises).append(if (slowRaises == 1) " slow raise" else " slow raises")
        if (slowestMs > 0) append(", slowest ").append(slowestMs).append(" ms (").append(slowestWhat).append(')')
    }
}
