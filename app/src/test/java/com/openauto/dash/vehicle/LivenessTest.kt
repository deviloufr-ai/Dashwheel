package com.openauto.dash.vehicle

import com.openauto.dash.McuReader
import com.openauto.dash.REVERSE_MAX_KMH
import com.openauto.dash.ReverseReads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A source that goes quiet stops being believed, state keys included; and a reverse never outlives its evidence. */
class LivenessTest {
    private var t = 0L
    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private val bus = VehicleBus({ t }, scope)
    private val unit = Source("unit.bus", SourceKind.CANBOX, "Unit bus")
    private val other = Source("qf.mcu", SourceKind.MCU, "MCU")

    @After
    fun stop() = scope.cancel()

    // --- SilenceWatch ---------------------------------------------------------------------------------------

    @Test
    fun aWatchIsSilentOnceAfterItsTimeout() {
        val w = SilenceWatch(10_000) { t }
        assertFalse(w.check())              // never heard: nothing to lose
        assertTrue(w.heard())               // the first word brings it up
        assertFalse(w.heard())              // later words do not
        t += 9_999
        assertFalse(w.check())
        t += 1
        assertTrue(w.check())               // silent now...
        assertFalse(w.check())              // ...and said once
        assertFalse(w.isAlive)
        assertTrue(w.heard())               // a word after a silence is the source coming back
    }

    @Test
    fun everyWordMovesTheSilenceOn() {
        val w = SilenceWatch(10_000) { t }
        w.heard()
        t += 8_000
        w.heard()
        assertEquals(10_000, w.untilSilent())
        t += 9_000
        assertFalse(w.check())
        assertEquals(1_000, w.untilSilent())
    }

    @Test
    fun aStoppedFeedIsSilentAtOnce() {
        val w = SilenceWatch(10_000) { t }
        assertFalse(w.stop())
        w.heard()
        assertTrue(w.stop())
        assertFalse(w.check())
    }

    // --- SourceLiveness on a bus ----------------------------------------------------------------------------

    @Test
    fun aSilentSourceDropsAllItsValuesStateKeysIncluded() {
        val out = bus.register(unit)
        val live = SourceLiveness.of(out, 30_000, scope) { t }
        live.heard()
        out.put(K.REVERSE, true)
        out.put(K.DOORS, McuReader.DoorState(frontLeft = true))
        out.put(K.RPM, 900.0, ttlMs = 10 * 60_000)
        assertEquals(true, bus.now(K.REVERSE))
        t += 29_000
        live.check()
        assertEquals(true, bus.now(K.REVERSE))
        t += 1_000
        live.check()
        assertNull(bus.now(K.REVERSE))
        assertNull(bus.now(K.DOORS))
        assertNull(bus.now(K.RPM))
        assertFalse(bus.has(K.DOORS).value)
    }

    @Test
    fun whatItSaidBeforeTheSilenceDoesNotComeBack() {
        val out = bus.register(unit)
        val live = SourceLiveness.of(out, 30_000, scope) { t }
        live.heard()
        out.put(K.REVERSE, true)
        t += 30_000
        live.check()
        // The bus is back, with a word about something else: the reverse it said before is not.
        live.heard()
        out.put(K.RPM, 1_500.0)
        assertEquals(1_500.0, bus.now(K.RPM)!!, 0.0)
        assertNull(bus.now(K.REVERSE))
    }

    @Test
    fun aLostFeedDropsAtOnceAndLeavesTheOthersAlone() {
        val out = bus.register(unit)
        val mcu = bus.register(other)
        val live = SourceLiveness.of(out, 30_000, scope) { t }
        live.heard()
        out.put(K.HEADLIGHTS, true)
        mcu.put(K.HANDBRAKE, true)
        live.stopped()
        assertNull(bus.now(K.HEADLIGHTS))
        assertEquals(true, bus.now(K.HANDBRAKE))
        // Stopped again: nothing more to say.
        live.stopped()
        assertFalse(live.isAlive)
    }

    @Test
    fun theSourceIsDownWhileSilentAndUpWithTheNextWord() {
        val out = bus.register(unit)
        val live = SourceLiveness.of(out, 1_000, scope) { t }
        live.heard()
        out.put(K.HEADLIGHTS, true)
        t += 1_000
        live.check()
        // Down: even a put from a handle that did not notice (another thread) is not believed.
        out.put(K.HEADLIGHTS, true)
        assertNull(bus.now(K.HEADLIGHTS))
        live.heard()
        assertEquals(true, bus.now(K.HEADLIGHTS))
    }

    @Test
    fun clearWithdrawsOnlyThatSourcesValues() {
        val a = bus.register(unit)
        val b = bus.register(other)
        a.put(K.RPM, 800.0)
        a.put(K.REVERSE, true)
        b.put(K.REVERSE, false)
        a.clear()
        assertNull(bus.now(K.RPM))
        assertEquals(false, bus.now(K.REVERSE))
        assertEquals(setOf("qf.mcu"), bus.candidates(K.REVERSE).value.map { it.source.id }.toSet())
    }

    @Test
    fun aCounterStartsOverAfterClear() {
        val a = bus.register(unit)
        a.put(K.ODOMETER, 1_000.0)
        a.clear()
        // The glitch rule must not hold a restarted source to its old high mark.
        a.put(K.ODOMETER, 10.0)
        assertEquals(10.0, bus.now(K.ODOMETER)!!, 0.0)
    }

    // --- The reverse failsafe -------------------------------------------------------------------------------

    @Test
    fun reverseEndsOnAFastCarWhateverTheFlagSays() {
        var reads = ReverseReads().next(on = true, speedKmh = REVERSE_MAX_KMH)
        reads = reads.next(on = true, speedKmh = 40.0)
        assertFalse(reads.over)
        assertTrue(reads.next(on = true, speedKmh = 50.0).over)
    }

    @Test
    fun aSlowCarOrNoSpeedEndsNothing() {
        var reads = ReverseReads()
        repeat(10) { reads = reads.next(on = true, speedKmh = 12.0) }
        assertFalse(reads.over)
        repeat(10) { reads = reads.next(on = true, speedKmh = null) }
        assertFalse(reads.over)
    }

    @Test
    fun aFastLookAmongSlowOnesStartsOver() {
        val reads = ReverseReads().next(on = true, speedKmh = 40.0).next(on = true, speedKmh = 40.0)
            .next(on = true, speedKmh = 10.0).next(on = true, speedKmh = 40.0)
        assertFalse(reads.over)
        assertEquals(1, reads.fastInARow)
    }
}
