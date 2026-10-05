package com.openauto.dash.display

import com.openauto.dash.link.DisplayBrightness

/**
 * The monitor's backlight, set by pressing its own `+` and `−` buttons: the
 * Pi's GPIOs wired to them (through a divider, as the buttons sit at ~4.6 V),
 * pulled low to press, let float to release. Measured on a JRP7002 board
 * (CUQI 7"), whose firmware answers neither DDC/CI nor CEC:
 *
 * - with its bar closed, the first press of `+` only opens the bar, and the first two of `−`;
 *   each further press is one step;
 * - while the bar is open, every press is a step, the other way too;
 * - the bar closes [BAR_OPEN_MS] after the last press;
 * - held, a button repeats ~20 steps a second, so [HOLD_TO_MIN_MS] reaches 1 from anywhere;
 * - after that hold, the first `+` press is swallowed;
 * - presses of [PRESS_MS] down and [GAP_MS] up all count (60 ms lost one in twenty).
 *
 * The level isn't readable, so the first change after a start goes the long
 * way (to 1, then up); later ones are counted from the level last set. The
 * latest wish wins: one asked while the buttons are busy follows once they're done.
 */
class BrightnessButtons(
    private val up: Int,
    private val down: Int,
    private val pins: Pins = Pins.Pinctrl,
    private val clock: Clock = Clock.System
) {
    /** Driving a GPIO: low (pressed) or let go (input, no pull). */
    interface Pins {
        fun press(gpio: Int)
        fun release(gpio: Int)

        object Pinctrl : Pins {
            override fun press(gpio: Int) = run("op", "dl", gpio = gpio)
            override fun release(gpio: Int) = run("ip", "pn", gpio = gpio)

            private fun run(vararg how: String, gpio: Int) {
                runCatching { ProcessBuilder(listOf("pinctrl", "set", gpio.toString()) + how).redirectErrorStream(true).start().apply { inputStream.readBytes() }.waitFor() }
                    .onFailure { log("brightness: pinctrl failed: ${it.message}") }
            }
        }
    }

    interface Clock {
        fun now(): Long
        fun sleep(ms: Long)

        object System : Clock {
            override fun now() = java.lang.System.currentTimeMillis()
            override fun sleep(ms: Long) = Thread.sleep(ms)
        }
    }

    private val lock = Object()
    private var wanted: Int? = null
    private var worker: Thread? = null

    /** The level last set, or null until the first one since the start. */
    @Volatile var level: Int? = null
        private set
    private var lastPress = Long.MIN_VALUE / 2

    /** Both buttons let go: a pin left low from a previous run would hold a button down. */
    fun start() {
        pins.release(up)
        pins.release(down)
    }

    /** Asks for [target]; returns at once, the buttons take a few seconds. */
    fun set(target: Int) {
        synchronized(lock) {
            wanted = target.coerceIn(DisplayBrightness.MIN, DisplayBrightness.MAX)
            if (worker?.isAlive == true) return
            worker = Thread({ work() }, "display-brightness").apply { isDaemon = true; start() }
        }
    }

    private fun work() {
        while (true) {
            val target = synchronized(lock) { wanted.also { wanted = null } } ?: return
            apply(target)
        }
    }

    /** Runs on the calling thread: the buttons to [target]. */
    internal fun apply(target: Int) {
        val from = level
        if (from == target) return
        if (from == null) {
            hold(down, HOLD_TO_MIN_MS)
            clock.sleep(AFTER_HOLD_MS)
            // The first + after the hold is swallowed, so from 1 it takes [target] presses.
            repeat(target) { tap(up) }
        } else {
            val since = clock.now() - lastPress
            // Near its closing, the bar may or may not still be open: wait until it surely isn't.
            if (since in BAR_SURELY_OPEN_MS until BAR_OPEN_MS + MARGIN_MS) clock.sleep(BAR_OPEN_MS + MARGIN_MS - since)
            val open = since < BAR_SURELY_OPEN_MS
            val button = if (target > from) up else down
            // While the bar is open every press is a step, either way; closed, the first presses only open it.
            val opening = when {
                open -> 0
                button == up -> OPEN_PRESSES_UP
                else -> OPEN_PRESSES_DOWN
            }
            repeat(opening + kotlin.math.abs(target - from)) { tap(button) }
        }
        level = target
        log("brightness $target")
    }

    private fun tap(gpio: Int) {
        pins.press(gpio)
        clock.sleep(PRESS_MS)
        pins.release(gpio)
        clock.sleep(GAP_MS)
        lastPress = clock.now()
    }

    private fun hold(gpio: Int, ms: Long) {
        pins.press(gpio)
        clock.sleep(ms)
        pins.release(gpio)
        lastPress = clock.now()
    }

    companion object {
        const val PRESS_MS = 100L
        const val GAP_MS = 100L
        const val HOLD_TO_MIN_MS = 7_000L
        const val AFTER_HOLD_MS = 300L
        const val BAR_OPEN_MS = 11_000L
        const val MARGIN_MS = 1_500L
        /** Presses that only open the closed bar: one for `+`, two for `−` (measured, 45 → 36 for eleven). */
        const val OPEN_PRESSES_UP = 1
        const val OPEN_PRESSES_DOWN = 2
        /** Since the last press: the bar is open for sure, with room for the presses themselves. */
        const val BAR_SURELY_OPEN_MS = 8_000L
    }
}
