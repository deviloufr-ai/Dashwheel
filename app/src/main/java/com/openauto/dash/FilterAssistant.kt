package com.openauto.dash

import kotlin.math.roundToInt

/*
 * The particle filter's spoken helper, fed by the experimental readings the car
 * confirmed (PidExplorer): says when the filter is cleaning itself so the driver
 * keeps going a little longer, when it's nearly full, and when the fuel additive
 * (Eolys) runs low. A clogged filter costs a lot; a word at the right moment
 * avoids most of it. Pure, so it's unit-tested; CarCare speaks what it returns.
 */

internal data class FilterWatch(
    /** The car reports a regeneration, once the reading settled. */
    val regenActive: Boolean = false,
    /** Fresh reads in a row saying on / off: a flapping reading doesn't start or end one. */
    val activeReads: Int = 0,
    val inactiveReads: Int = 0,
    /** When the last regeneration reading was taken, so each poll counts once. */
    val regenReadAt: Long = 0,
    val keepDrivingSaid: Boolean = false,
    val standingSince: Long? = null,
    val standingSaid: Boolean = false,
    /** A drive just started: its filter advice waits here for the readings. */
    val startAt: Long? = null,
    /** The short-drive reminder due at that start, to be said with the soot load. */
    val pendingStreak: Int? = null,
    /** The day (epoch day) the soot load was last spoken about; kept across restarts. */
    val sootSaidDay: Long = -1,
    /** When the low additive was last spoken about; kept across restarts. */
    val additiveSaidAt: Long = 0
)

internal sealed interface FilterEvent {
    /** A regeneration started: keep driving a while if possible. */
    data object RegenKeepDriving : FilterEvent
    /** Standing still with the regeneration still going: a few more minutes before switching off. */
    data object RegenStanding : FilterEvent
    /** The existing short-drive reminder, with nothing to add. */
    data class ShortDrives(val streak: Int) : FilterEvent
    /** The filter is nearly full; [streak] when the short-drive reminder was due too, said as one line. */
    data class SootHigh(val percent: Int, val streak: Int?) : FilterEvent
    data class AdditiveLow(val percent: Int) : FilterEvent
}

internal object FilterRules {
    const val REGEN_ON_READS = 2
    const val REGEN_OFF_READS = 3
    /** No fresh regeneration reading for this long (the adapter dropped): it's over, silently. */
    const val REGEN_STALE_MS = 60_000L
    const val STANDING_MS = 30_000L
    /** Soot load and additive level are percentages ([ExtraReading.unit]). */
    const val SOOT_HIGH_PCT = 80.0
    const val ADDITIVE_LOW_PCT = 15.0
    const val ADDITIVE_REPEAT_MS = 7 * 24 * 3_600_000L
    /**
     * How long the start-of-drive advice waits for the readings: the extra
     * requests are polled every 8 s and the adapter often connects with the engine.
     */
    const val START_WAIT_MS = 45_000L
    /** A reading older than this is not the car's current state. */
    const val FRESH_MS = 60_000L

    private val START_READINGS = setOf(ExtraReading.SOOT_LOAD, ExtraReading.ADDITIVE_LEVEL)

    /** A drive started: [streak] is the short-drive reminder CareRules raised then, if any. */
    fun driveStarted(w: FilterWatch, now: Long, streak: Int?): FilterWatch = w.copy(startAt = now, pendingStreak = streak)

    /**
     * One OBD reading. [expected] are the readings the car confirmed and will
     * be polled for, [today] the local epoch day for the once-a-day soot line.
     */
    fun step(
        w: FilterWatch,
        readings: Map<ExtraReading, ExtraValue>,
        expected: Set<ExtraReading>,
        speedKmh: Int,
        running: Boolean,
        now: Long,
        today: Long,
        car: CarProfile
    ): Pair<FilterWatch, List<FilterEvent>> {
        val events = mutableListOf<FilterEvent>()
        var x = regen(w, readings[ExtraReading.REGEN_ACTIVE], speedKmh, running, now, events)
        x = atStart(x, readings, expected, now, today, car, events)
        return x to events
    }

    private fun regen(w: FilterWatch, r: ExtraValue?, speedKmh: Int, running: Boolean, now: Long, events: MutableList<FilterEvent>): FilterWatch {
        var x = w
        if (r == null || now - r.at > REGEN_STALE_MS) {
            return if (x.regenActive || x.activeReads > 0 || x.inactiveReads > 0) quiet(x) else x
        }
        if (r.at != x.regenReadAt) {
            val on = r.value >= 0.5
            x = x.copy(
                regenReadAt = r.at,
                activeReads = if (on) x.activeReads + 1 else 0,
                inactiveReads = if (on) 0 else x.inactiveReads + 1
            )
            if (!x.regenActive && x.activeReads >= REGEN_ON_READS) {
                x = x.copy(regenActive = true, keepDrivingSaid = false, standingSince = null, standingSaid = false)
            } else if (x.regenActive && x.inactiveReads >= REGEN_OFF_READS) {
                // Done: nothing to say, the driver can stop whenever.
                return quiet(x)
            }
        }
        if (!x.regenActive || !running) return x.copy(standingSince = null)
        if (!x.keepDrivingSaid) {
            events += FilterEvent.RegenKeepDriving
            x = x.copy(keepDrivingSaid = true)
        }
        // Stopped with the engine running: the one moment the driver may switch off mid-clean.
        if (speedKmh <= CareRules.MOVING_KMH) {
            val since = x.standingSince ?: now
            x = x.copy(standingSince = since)
            if (!x.standingSaid && now - since >= STANDING_MS) {
                events += FilterEvent.RegenStanding
                x = x.copy(standingSaid = true)
            }
        } else x = x.copy(standingSince = null)
        return x
    }

    private fun quiet(w: FilterWatch) = w.copy(
        regenActive = false, activeReads = 0, inactiveReads = 0,
        keepDrivingSaid = false, standingSince = null, standingSaid = false
    )

    private fun atStart(
        w: FilterWatch,
        readings: Map<ExtraReading, ExtraValue>,
        expected: Set<ExtraReading>,
        now: Long,
        today: Long,
        car: CarProfile,
        events: MutableList<FilterEvent>
    ): FilterWatch {
        val startAt = w.startAt ?: return w
        fun fresh(r: ExtraReading) = readings[r]?.takeIf { now - it.at <= FRESH_MS }?.value
        // Wait for the readings the car will give, but not for ever; with none, say at once.
        val waitFor = if (car.particleFilter) expected intersect START_READINGS else emptySet()
        if (waitFor.any { fresh(it) == null } && now - startAt < START_WAIT_MS) return w

        var x = w.copy(startAt = null, pendingStreak = null)
        val soot = fresh(ExtraReading.SOOT_LOAD)?.takeIf { car.particleFilter }
        if (soot != null && soot >= SOOT_HIGH_PCT && w.sootSaidDay != today) {
            events += FilterEvent.SootHigh(soot.roundToInt(), w.pendingStreak)
            x = x.copy(sootSaidDay = today)
        } else if (w.pendingStreak != null) {
            events += FilterEvent.ShortDrives(w.pendingStreak)
        }
        val additive = fresh(ExtraReading.ADDITIVE_LEVEL)?.takeIf { car.particleFilter }
        if (additive != null && additive < ADDITIVE_LOW_PCT && (w.additiveSaidAt == 0L || now - w.additiveSaidAt >= ADDITIVE_REPEAT_MS)) {
            events += FilterEvent.AdditiveLow(additive.roundToInt())
            x = x.copy(additiveSaidAt = now)
        }
        return x
    }
}
