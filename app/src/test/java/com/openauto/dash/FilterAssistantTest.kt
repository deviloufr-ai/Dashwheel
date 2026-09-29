package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The particle filter helper: regenerations, a full filter and a low additive. */
class FilterRulesTest {

    private val car = CarProfile.PRESET
    private val t0 = 1_000_000_000L
    private val today = 20_000L
    private val everything = setOf(ExtraReading.SOOT_LOAD, ExtraReading.ADDITIVE_LEVEL, ExtraReading.REGEN_ACTIVE)

    private fun regen(on: Boolean, at: Long) = mapOf(ExtraReading.REGEN_ACTIVE to ExtraValue(if (on) 1.0 else 0.0, at))

    /**
     * Drives [seconds] at [kmh], the regeneration reading polled every 8 s as
     * [on] says (by seconds since [from]), returning the state and every event.
     */
    private fun drive(
        w: FilterWatch, from: Long, seconds: Int, kmh: Int = 90, running: Boolean = true, on: (Int) -> Boolean
    ): Triple<FilterWatch, List<FilterEvent>, Long> {
        var state = w
        val events = mutableListOf<FilterEvent>()
        var now = from
        repeat(seconds) { s ->
            val readAt = from + (s / 8) * 8_000L
            val (next, e) = FilterRules.step(state, regen(on(s / 8 * 8), readAt), everything, kmh, running, now, today, car)
            state = next
            events += e
            now += 1000
        }
        return Triple(state, events, now)
    }

    @Test
    fun aRegenerationIsSaidOnceAfterTwoReads() {
        val (w, events, _) = drive(FilterWatch(), t0, 60) { true }
        assertTrue(w.regenActive)
        assertEquals(listOf<FilterEvent>(FilterEvent.RegenKeepDriving), events)
    }

    @Test
    fun oneActiveReadIsNotARegeneration() {
        val (w, events, _) = drive(FilterWatch(), t0, 60) { it == 8 }
        assertFalse(w.regenActive)
        assertTrue(events.isEmpty())
    }

    @Test
    fun aFlappingReadingNeitherEndsNorRepeatsIt() {
        // Active, then off for two reads out of every three: never three off in a row.
        val (w, events, _) = drive(FilterWatch(), t0, 5 * 60) { s -> s < 16 || (s / 8) % 3 == 0 }
        assertTrue(w.regenActive)
        assertEquals(1, events.count { it == FilterEvent.RegenKeepDriving })
    }

    @Test
    fun theEndIsSilentAndTheNextRegenerationIsSaidAgain() {
        val (active, _, t1) = drive(FilterWatch(), t0, 40) { true }
        val (ended, quiet, t2) = drive(active, t1, 40) { false }
        assertFalse(ended.regenActive)
        assertTrue(quiet.isEmpty())
        val (_, again, _) = drive(ended, t2, 40) { true }
        assertEquals(listOf<FilterEvent>(FilterEvent.RegenKeepDriving), again)
    }

    @Test
    fun standingStillMidCleanIsSaidOnceAfterHalfAMinute() {
        val (driving, _, t1) = drive(FilterWatch(), t0, 40) { true }
        val (short, e1, t2) = drive(driving, t1, 25, kmh = 0) { true }
        assertTrue(e1.isEmpty())
        // Moving again resets the wait.
        val (moving, _, t3) = drive(short, t2, 10) { true }
        val (_, e2, _) = drive(moving, t3, 90, kmh = 0) { true }
        assertEquals(listOf<FilterEvent>(FilterEvent.RegenStanding), e2)
    }

    @Test
    fun theEngineOffIsNotStanding() {
        val (driving, _, t1) = drive(FilterWatch(), t0, 40) { true }
        val (_, events, _) = drive(driving, t1, 90, kmh = 0, running = false) { true }
        assertTrue(events.isEmpty())
    }

    @Test
    fun aReadingThatStopsComingEndsTheRegeneration() {
        val (active, _, t1) = drive(FilterWatch(), t0, 40) { true }
        val (w, e) = FilterRules.step(active, emptyMap(), everything, 90, true, t1 + 1_000, today, car)
        assertFalse(w.regenActive)
        assertTrue(e.isEmpty())
    }

    // --- At the start of a drive ------------------------------------------------------

    private fun start(w: FilterWatch, streak: Int?, readings: Map<ExtraReading, ExtraValue>, now: Long = t0, day: Long = today) =
        FilterRules.step(FilterRules.driveStarted(w, now, streak), readings, everything, 0, true, now, day, car)

    private fun soot(pct: Double, at: Long = t0) = ExtraReading.SOOT_LOAD to ExtraValue(pct, at)
    private fun additive(pct: Double, at: Long = t0) = ExtraReading.ADDITIVE_LEVEL to ExtraValue(pct, at)

    @Test
    fun aFullFilterAndShortDrivesMakeOneLine() {
        val (w, events) = start(FilterWatch(), 4, mapOf(soot(85.0), additive(60.0)))
        assertEquals(listOf<FilterEvent>(FilterEvent.SootHigh(85, 4)), events)
        assertEquals(today, w.sootSaidDay)
        assertNull(w.startAt)
    }

    @Test
    fun aFullFilterIsSaidOnceADay() {
        val (w, _) = start(FilterWatch(), null, mapOf(soot(85.0), additive(60.0)))
        // The same day, the short-drive reminder comes on its own.
        val (_, sameDay) = start(w, 5, mapOf(soot(86.0), additive(60.0)))
        assertEquals(listOf<FilterEvent>(FilterEvent.ShortDrives(5)), sameDay)
        val (_, nextDay) = start(w, null, mapOf(soot(86.0), additive(60.0)), day = today + 1)
        assertEquals(listOf<FilterEvent>(FilterEvent.SootHigh(86, null)), nextDay)
    }

    @Test
    fun aFilterBelowTheLineKeepsTheUsualReminder() {
        val (_, events) = start(FilterWatch(), 3, mapOf(soot(60.0), additive(60.0)))
        assertEquals(listOf<FilterEvent>(FilterEvent.ShortDrives(3)), events)
    }

    @Test
    fun theAdviceWaitsForTheReadingsThenGivesUp() {
        val (waiting, e1) = start(FilterWatch(), 3, emptyMap())
        assertTrue(e1.isEmpty())
        val t1 = t0 + 16_000
        val (_, e2) = FilterRules.step(waiting, mapOf(soot(90.0, t1), additive(50.0, t1)), everything, 20, true, t1, today, car)
        assertEquals(listOf<FilterEvent>(FilterEvent.SootHigh(90, 3)), e2)
        // Nothing ever comes: the reminder alone, after the wait.
        val late = t0 + FilterRules.START_WAIT_MS
        val (_, e3) = FilterRules.step(waiting, emptyMap(), everything, 20, true, late, today, car)
        assertEquals(listOf<FilterEvent>(FilterEvent.ShortDrives(3)), e3)
    }

    @Test
    fun withoutConfirmedReadingsTheReminderIsNotDelayed() {
        val (_, events) = FilterRules.step(FilterRules.driveStarted(FilterWatch(), t0, 3), emptyMap(), emptySet(), 0, true, t0, today, car)
        assertEquals(listOf<FilterEvent>(FilterEvent.ShortDrives(3)), events)
    }

    @Test
    fun aLowAdditiveIsSaidOnceAWeek() {
        val (w, e1) = start(FilterWatch(), null, mapOf(soot(40.0), additive(12.0)))
        assertEquals(listOf<FilterEvent>(FilterEvent.AdditiveLow(12)), e1)
        val (_, e2) = start(w, null, mapOf(soot(40.0, t0 + 86_400_000), additive(11.0, t0 + 86_400_000)), now = t0 + 86_400_000)
        assertTrue(e2.isEmpty())
        val week = t0 + FilterRules.ADDITIVE_REPEAT_MS
        val (_, e3) = start(w, null, mapOf(soot(40.0, week), additive(10.0, week)), now = week)
        assertEquals(listOf<FilterEvent>(FilterEvent.AdditiveLow(10)), e3)
    }

    @Test
    fun anOldReadingIsNotTrusted() {
        val stale = t0 - 5 * 60_000L
        val old = mapOf(soot(95.0, stale), additive(5.0, stale))
        val (waiting, e1) = start(FilterWatch(), null, old)
        assertTrue(e1.isEmpty())
        val (w, e2) = FilterRules.step(waiting, old, everything, 20, true, t0 + FilterRules.START_WAIT_MS, today, car)
        assertTrue(e2.isEmpty())
        assertNull(w.startAt)
    }

    @Test
    fun aCarWithoutAFilterHearsNothingAboutIt() {
        val petrol = car.copy(particleFilter = false)
        val (_, events) = FilterRules.step(
            FilterRules.driveStarted(FilterWatch(), t0, null), mapOf(soot(95.0), additive(5.0)), everything, 0, true, t0, today, petrol
        )
        assertTrue(events.isEmpty())
    }
}
