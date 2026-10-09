package com.openauto.dash.display

import com.openauto.dash.link.DisplayTime
import java.io.File
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

/**
 * The time the display shows. A Pi has no clock of its own (no battery): it
 * starts from the time it last saved, and may never get one from the network
 * on a phone's hotspot. Nor does its card know the driver's time zone (a card
 * written from the image is in the one Raspberry Pi OS ships with). So the
 * head unit sends both ([DisplayTime]) when it links and with every ping:
 * the offset corrects every picture at once, the zone is kept in `zone.txt`
 * in the config folder for the screens shown before the next link, and the
 * board's own clock is set too, so its logs read right.
 */
object LocalClock {
    private const val FILE = "zone.txt"
    /** The board's clock is only set when it is this far off: a few seconds don't matter, a reboot's worth does. */
    private const val SET_SYSTEM_BEYOND_MS = 2_000L

    /** Head unit clock minus the board's. */
    @Volatile private var offsetMs = 0L

    @Volatile var zone: ZoneId = ZoneId.systemDefault()
        private set

    /** Whether the system clock may be set ([DisplayTime]): the Pi, as root; never on a computer running tests. */
    @Volatile var setsSystemClock: (Long) -> Boolean = ::setSystemClock

    /** What time it is, by the head unit's clock when one has sent it. */
    fun now(): Long = System.currentTimeMillis() + offsetMs

    fun load(dir: File) {
        val saved = runCatching { File(dir, FILE).readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return
        zoneOf(saved, null)?.let { zone = it }
    }

    /**
     * The head unit's clock, from [DisplayTime] or the clock in its readings
     * (an older head unit sends only those, and no zone).
     */
    fun sync(clock: Long) {
        offsetMs = clock - System.currentTimeMillis()
    }

    /** True when the zone changed (and was saved for the next start). */
    fun set(dir: File, time: DisplayTime): Boolean {
        sync(time.clock)
        if (kotlin.math.abs(offsetMs) > SET_SYSTEM_BEYOND_MS && setsSystemClock(time.clock)) {
            log("clock set from the head unit (was ${offsetMs / 1000} s off)")
            offsetMs = 0
        }
        val next = zoneOf(time.zone, time.zoneOffsetMin) ?: return false
        if (next == zone) return false
        zone = next
        log("time zone $next")
        ReadOnlyCard.write(dir) { File(dir, FILE).writeText(next.id + "\n") }
        return true
    }

    /** [name] when it is a zone this JVM knows, else the fixed offset; null with neither. */
    internal fun zoneOf(name: String, offsetMin: Int?): ZoneId? =
        runCatching { ZoneId.of(name) }.getOrNull()
            ?: offsetMin?.let { runCatching { ZoneOffset.ofTotalSeconds(it * 60) }.getOrNull() }

    private fun setSystemClock(clock: Long): Boolean = runCatching {
        val p = ProcessBuilder("date", "-u", "-s", "@${clock / 1000}").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0
    }.getOrDefault(false)
}
