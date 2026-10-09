package com.openauto.dash.display

import com.openauto.dash.link.DisplayStats
import java.io.File

/**
 * How the board itself is doing: the firmware's throttle flags (under-voltage,
 * capped, throttled, too hot) and the SoC's temperature, as `vcgencmd
 * get_throttled` and `measure_temp` report them, read from sysfs. Sent to the
 * head unit with every [DisplayStats], so a stutter that is the board's own
 * (a weak supply, no airflow) is never taken for a slow link, and logged here
 * whenever the flags change.
 */
object BoardHealth {
    private const val THROTTLED = "/sys/devices/platform/soc/soc:firmware/get_throttled"
    private const val TEMP = "/sys/class/thermal/thermal_zone0/temp"

    private var lastFlags = -1

    data class Reading(val throttled: Int, val tempC: Int?)

    fun read(throttledFile: File = File(THROTTLED), tempFile: File = File(TEMP)): Reading {
        val flags = runCatching { parseFlags(throttledFile.readText()) }.getOrDefault(0)
        val temp = runCatching { tempFile.readText().trim().toInt() / 1000 }.getOrNull()
        return Reading(flags, temp)
    }

    /** Reads, and logs the flags when they changed since the last call. */
    fun readAndLog(): Reading {
        val reading = read()
        if (reading.throttled != lastFlags) {
            lastFlags = reading.throttled
            log("board: ${describe(reading.throttled)}" + (reading.tempC?.let { ", $it °C" } ?: ""))
        }
        return reading
    }

    /** The file holds the flags in hex ("50005"), with or without a 0x. */
    internal fun parseFlags(text: String): Int = text.trim().removePrefix("0x").toInt(16)

    internal fun describe(flags: Int): String {
        if (flags == 0) return "fine"
        val now = mutableListOf<String>()
        if (flags and DisplayStats.THROTTLE_UNDER_VOLTAGE != 0) now += "under-voltage"
        if (flags and DisplayStats.THROTTLE_FREQ_CAPPED != 0) now += "frequency capped"
        if (flags and DisplayStats.THROTTLE_THROTTLED != 0) now += "throttled"
        if (flags and DisplayStats.THROTTLE_SOFT_TEMP != 0) now += "at the soft temperature limit"
        val since = (flags shr 16) and DisplayStats.THROTTLE_NOW
        val line = if (now.isEmpty()) "fine now" else now.joinToString(", ")
        return if (since != 0) "$line (0x${Integer.toHexString(flags)}: trouble since the start)" else line
    }
}
