package com.openauto.dash

import java.util.Locale

/*
 * SLCAN, the text protocol of CANable and other USB-CAN adapters on their
 * slcan firmware (Lawicel's CAN232 commands). Each command and each frame is
 * a line ended by CR; the adapter answers a command with CR when it took it,
 * BEL (0x07) when it did not.
 *
 *     t1238DDDDDDDDDDDDDDDD   a frame with an 11-bit id 123, 8 data bytes
 *     T1234567F2DDDD          a 29-bit id
 *     r1230 / R12345678 0     a remote request (no data): skipped
 *
 * A frame may carry 4 hex digits of time stamp after its data. Pure, unit-tested.
 */

/** One frame as heard on the bus: its [id] and up to 8 data [bytes] (0..255). */
data class CanFrame(val id: Int, val extended: Boolean, val bytes: List<Int>) {
    /** "408", or "18DAF110" for a 29-bit id: how the driver types it. */
    val idText: String get() = canIdText(id, extended)

    /** "14 00 3A": the data bytes as hex. */
    val dataText: String get() = bytes.joinToString(" ") { "%02X".format(Locale.US, it) }
}

/** [id] as typed: three digits for an 11-bit id, eight for a 29-bit one. */
fun canIdText(id: Int, extended: Boolean): String =
    if (extended) "%08X".format(Locale.US, id) else "%03X".format(Locale.US, id)

object Slcan {
    const val OK = '\r'
    const val ERROR = '\u0007'

    /** The bit rates slcan knows, in bit/s, by their S command. */
    val BITRATES: Map<Int, Char> = linkedMapOf(
        10_000 to '0', 20_000 to '1', 50_000 to '2', 100_000 to '3', 125_000 to '4',
        250_000 to '5', 500_000 to '6', 800_000 to '7', 1_000_000 to '8'
    )

    /** The ones a car uses: 500k for the engine side of most cars since 2008, 125k and 250k for many body buses, 1M rarely. */
    val CAR_BITRATES = listOf(125_000, 250_000, 500_000, 1_000_000)

    const val DEFAULT_BITRATE = 500_000

    /** "S6\r" for 500 kbit/s; null for a rate slcan has no command for. */
    fun bitrateCommand(bps: Int): String? = BITRATES[bps]?.let { "S$it\r" }

    /** Closes the channel, whatever state the adapter was left in. */
    const val CLOSE = "C\r"

    /** Opens the channel listen-only (Lawicel): the adapter never sends, not even an acknowledgement. */
    const val OPEN_LISTEN = "L\r"

    /** CANable's own way to the same: silent mode, then a plain open. */
    const val SILENT_MODE = "M1\r"
    const val OPEN = "O\r"

    /** Time stamps off, where the firmware has them; a frame's stamp is read past anyway. */
    const val NO_TIMESTAMPS = "Z0\r"

    /** [line] (without its CR) as a frame; null when it is anything else, a remote request or garbled. */
    fun frame(line: String): CanFrame? {
        if (line.isEmpty()) return null
        val extended = when (line[0]) {
            't' -> false
            'T' -> true
            else -> return null
        }
        val idDigits = if (extended) 8 else 3
        if (line.length < 1 + idDigits + 1) return null
        val id = hex(line, 1, idDigits) ?: return null
        if (extended && id > 0x1FFFFFFF || !extended && id > 0x7FF) return null
        val length = line[1 + idDigits].digitToIntOrNull(16)?.takeIf { it <= 8 } ?: return null
        val dataAt = 2 + idDigits
        // The data, then nothing or a 4-digit time stamp.
        val rest = line.length - dataAt - length * 2
        if (rest != 0 && rest != 4) return null
        val bytes = ArrayList<Int>(length)
        for (i in 0 until length) bytes += hex(line, dataAt + i * 2, 2) ?: return null
        if (rest == 4 && hex(line, dataAt + length * 2, 4) == null) return null
        return CanFrame(id, extended, bytes)
    }

    private fun hex(s: String, from: Int, digits: Int): Int? {
        var v = 0
        for (i in from until from + digits) {
            val d = s.getOrNull(i)?.digitToIntOrNull(16) ?: return null
            v = v * 16 + d
        }
        return v
    }
}

/**
 * Lines out of the adapter's bytes, which USB hands over in pieces that don't
 * follow lines: what is left of one waits for the next piece. Each CR ends a
 * line; a BEL is an answer of its own. A line that runs past any frame's
 * length is noise and is dropped.
 */
internal class SlcanLineReader {
    private val pending = StringBuilder()

    /** What came in: the frames heard, and how many commands were taken ([oks]) or refused ([errors]). */
    class Read(val frames: List<CanFrame>, val oks: Int, val errors: Int)

    fun feed(chunk: ByteArray, length: Int = chunk.size): Read {
        val frames = ArrayList<CanFrame>()
        var oks = 0
        var errors = 0
        for (i in 0 until length) {
            val c = (chunk[i].toInt() and 0xFF).toChar()
            when (c) {
                Slcan.OK, '\n' -> {
                    if (pending.isEmpty()) {
                        if (c == Slcan.OK) oks++
                    } else {
                        Slcan.frame(pending.toString())?.let { frames += it } ?: run { if (c == Slcan.OK) oks++ }
                        pending.setLength(0)
                    }
                }
                Slcan.ERROR -> {
                    errors++
                    pending.setLength(0)
                }
                else -> {
                    if (c in ' '..'~') pending.append(c)
                    if (pending.length > MAX_LINE) pending.setLength(0)
                }
            }
        }
        return Read(frames, oks, errors)
    }

    private companion object {
        // T + 8 id digits + length + 16 data digits + 4 stamp digits.
        const val MAX_LINE = 30
    }
}
