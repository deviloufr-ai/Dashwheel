package com.openauto.dash

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The unit report's lines on what a tap on an app tile cost ([TapCost]) and the minute's summary ([TapStats]). */
class TapCostTest {

    private val maps = "com.google.android.apps.maps"

    @Test
    fun aQuickTapIsOneQuietLine() {
        val tap = TapCost(maps)
        assertFalse(tap.add(MotionEvent.ACTION_DOWN, raiseMs = 18, injected = true, injectMs = 2, lateMs = 7))
        assertTrue(tap.add(MotionEvent.ACTION_UP, raiseMs = 12, injected = true, injectMs = 1))
        assertEquals("tap on $maps: 7 ms late, raise 18 ms, inject ok 2 ms, up raise 12 ms, inject ok 1 ms", tap.line())
        assertFalse(tap.worrying)
        assertEquals(18, tap.slowestRaiseMs)
    }

    @Test
    fun aSlowRaiseOrARefusedTouchIsWorrying() {
        // The tester's unit: Android took 180 ms to raise Maps' task, and refused the lift.
        val tap = TapCost(maps)
        tap.add(MotionEvent.ACTION_DOWN, raiseMs = 180, injected = true, injectMs = 3, lateMs = 40)
        tap.add(MotionEvent.ACTION_MOVE, raiseMs = null, injected = true, injectMs = 1)
        tap.add(MotionEvent.ACTION_MOVE, raiseMs = null, injected = false, injectMs = 1)
        assertTrue(tap.add(MotionEvent.ACTION_UP, raiseMs = 9, injected = false, injectMs = 2))
        assertEquals("tap on $maps: 40 ms late, raise 180 ms, inject ok 3 ms, 2 moves (1 refused), up raise 9 ms, inject refused 2 ms", tap.line())
        assertTrue(tap.slow)
        assertTrue(tap.lost)
        assertEquals(180, tap.slowestRaiseMs)

        // A raise just under the bar with every touch taken is fine.
        val fine = TapCost(maps).apply {
            add(MotionEvent.ACTION_DOWN, raiseMs = 100, injected = true, injectMs = 1)
            add(MotionEvent.ACTION_UP, raiseMs = null, injected = true, injectMs = 1)
        }
        assertFalse(fine.worrying)
        assertEquals("tap on $maps: raise 100 ms, inject ok 1 ms, up no raise, inject ok 1 ms", fine.line())
    }

    @Test
    fun aCancelledTapAndAThrowingInjectionAreSaid() {
        val tap = TapCost(maps)
        tap.add(MotionEvent.ACTION_DOWN, raiseMs = null, injected = null, injectMs = 0, lateMs = 3)
        assertTrue(tap.add(MotionEvent.ACTION_CANCEL, raiseMs = null, injected = null, injectMs = 0))
        assertEquals("tap on $maps: 3 ms late, no raise, inject failed, cancelled", tap.line())
        assertTrue(tap.lost)
        assertFalse(tap.slow)
    }

    @Test
    fun aTapToldAgainSaysSo() {
        val tap = TapCost(maps)
        tap.add(MotionEvent.ACTION_DOWN, raiseMs = null, injected = true, injectMs = 1, lateMs = 4)
        tap.retapped()
        assertTrue(tap.add(MotionEvent.ACTION_UP, raiseMs = 60, injected = true, injectMs = 3))
        assertEquals("tap on $maps: 4 ms late, no raise, inject ok 1 ms, up raise 60 ms, inject ok 3 ms, tapped again", tap.line())
        assertFalse(tap.worrying)
    }

    @Test
    fun theSummaryComesOncePerPeriodAndStartsAfresh() {
        val stats = TapStats(periodMs = 60_000)
        fun tap(raise: Long, up: Boolean) = TapCost(maps).apply {
            add(MotionEvent.ACTION_DOWN, raiseMs = raise, injected = true, injectMs = 1)
            add(MotionEvent.ACTION_UP, raiseMs = null, injected = up, injectMs = 1)
        }
        assertNull(stats.add(tap(20, up = true), now = 1_000))
        assertNull(stats.add(tap(340, up = false), now = 10_000))
        assertNull(stats.raised(150, "keys back to the main screen", now = 12_000))
        // Still within the minute: nothing yet, however many taps.
        assertNull(stats.add(tap(5, up = true), now = 60_999))
        assertEquals(
            "tile taps, last 61 s: 4 taps, 1 lost, 2 slow raises, slowest 340 ms (tap on $maps)",
            stats.add(tap(5, up = true), now = 62_000),
        )
        // The next period counts from the summary on, and only what came since.
        assertNull(stats.add(tap(1, up = true), now = 100_000))
        assertEquals("tile taps, last 60 s: 2 taps, 0 lost, 0 slow raises, slowest 1 ms (tap on $maps)", stats.add(tap(1, up = true), now = 122_000))
    }

    @Test
    fun keysBackRaisesCountEvenWithoutTaps() {
        val stats = TapStats(periodMs = 1_000)
        assertNull(stats.raised(230, "keys back to the main screen (task 7 raised)", now = 0))
        assertEquals(
            "tile taps, last 1 s: 0 taps, 0 lost, 1 slow raise, slowest 230 ms (keys back to the main screen (task 7 raised))",
            stats.raised(20, "keys still away: main screen raised again", now = 1_000),
        )
    }
}
