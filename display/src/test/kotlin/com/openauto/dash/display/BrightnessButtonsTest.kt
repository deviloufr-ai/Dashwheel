package com.openauto.dash.display

import org.junit.Assert.assertEquals
import org.junit.Test

class BrightnessButtonsTest {

    private class Bench : BrightnessButtons.Pins, BrightnessButtons.Clock {
        var t = 0L
        val taps = mutableListOf<Int>()
        val holds = mutableListOf<Pair<Int, Long>>()
        private val down = mutableMapOf<Int, Long>()

        override fun press(gpio: Int) { down[gpio] = t }
        override fun release(gpio: Int) {
            val since = down.remove(gpio) ?: return
            if (t - since > 1_000) holds += gpio to t - since else taps += gpio
        }
        override fun now() = t
        override fun sleep(ms: Long) { t += ms }
    }

    @Test
    fun theFirstLevelGoesToTheBottomThenCountsUp() {
        val bench = Bench()
        val buttons = BrightnessButtons(up = 17, down = 27, pins = bench, clock = bench)
        buttons.apply(30)
        assertEquals(listOf(27 to BrightnessButtons.HOLD_TO_MIN_MS), bench.holds)
        // From 1, the first + is swallowed: thirty presses land on 30.
        assertEquals(List(30) { 17 }, bench.taps)
        assertEquals(30, buttons.level)
    }

    @Test
    fun whileTheBarIsOpenEachPressIsAStep() {
        val bench = Bench()
        val buttons = BrightnessButtons(up = 17, down = 27, pins = bench, clock = bench)
        buttons.apply(30)
        bench.taps.clear()
        buttons.apply(20)
        assertEquals(List(10) { 27 }, bench.taps)
        bench.taps.clear()
        buttons.apply(25)
        assertEquals(List(5) { 17 }, bench.taps)
        bench.taps.clear()
        buttons.apply(25)
        assertEquals(emptyList<Int>(), bench.taps)
    }

    @Test
    fun onceTheBarClosedTheFirstPressOpensIt() {
        val bench = Bench()
        val buttons = BrightnessButtons(up = 17, down = 27, pins = bench, clock = bench)
        buttons.apply(30)
        bench.taps.clear()
        bench.t += 60_000
        // Down, the closed bar swallows two presses (45 → 36 for eleven, on the bench).
        buttons.apply(20)
        assertEquals(List(12) { 27 }, bench.taps)
        bench.taps.clear()
        bench.t += 60_000
        // Up, just the one that opens it (55 → 65 for eleven).
        buttons.apply(30)
        assertEquals(List(11) { 17 }, bench.taps)
    }

    @Test
    fun aBarAboutToCloseIsWaitedOut() {
        val bench = Bench()
        val buttons = BrightnessButtons(up = 17, down = 27, pins = bench, clock = bench)
        buttons.apply(30)
        bench.taps.clear()
        val doneAt = bench.t
        bench.t += 10_000
        buttons.apply(31)
        assertEquals(List(2) { 17 }, bench.taps)
        assert(bench.t - doneAt >= BrightnessButtons.BAR_OPEN_MS + BrightnessButtons.MARGIN_MS)
    }

    @Test
    fun levelsOutsideTheBarAreClamped() {
        val bench = Bench()
        val buttons = BrightnessButtons(up = 17, down = 27, pins = bench, clock = bench)
        buttons.set(500)
        val until = System.currentTimeMillis() + 5_000
        while (buttons.level == null && System.currentTimeMillis() < until) Thread.sleep(10)
        assertEquals(100, buttons.level)
    }
}
