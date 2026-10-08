package com.openauto.dash

import java.util.Locale

/**
 * Pure decoding of ELM327 / OBD-II replies, kept free of Android types so it
 * runs under plain JVM unit tests. [ObdBluetoothManager] owns the socket and
 * delegates every reply here.
 */
object ObdParser {

    // --- PID response parsing -------------------------------------------------
    // Responses look like "41 0D 32" for the query "010D". The mode byte is
    // 0x40 + request mode (0x41), followed by the PID and its data bytes.

    internal fun parseSpeed(response: String): Int? {
        val bytes = dataBytes(response, "410D") ?: return null
        return bytes.firstOrNull()
    }

    internal fun parseRpm(response: String): Int? {
        val bytes = dataBytes(response, "410C") ?: return null
        if (bytes.size < 2) return null
        return ((bytes[0] * 256) + bytes[1]) / 4
    }

    internal fun parseCoolant(response: String): Int? {
        val bytes = dataBytes(response, "4105") ?: return null
        val a = bytes.firstOrNull() ?: return null
        return a - 40
    }

    /** Engine fuel rate (PID 015E): (256A + B) / 20 litres an hour. */
    internal fun parseFuelRate(response: String): Double? {
        val bytes = dataBytes(response, "415E") ?: return null
        if (bytes.size < 2) return null
        return (bytes[0] * 256 + bytes[1]) / 20.0
    }

    /**
     * Petrol litres an hour from the air flow (PID 0110, (256A + B) / 100 g/s):
     * a petrol engine burns one gram of fuel for [STOICH_AFR] of air, and a
     * litre of petrol weighs [PETROL_G_PER_L] g.
     */
    internal fun parseMafAsPetrol(response: String): Double? {
        val bytes = dataBytes(response, "4110") ?: return null
        if (bytes.size < 2) return null
        val gramsPerSecond = (bytes[0] * 256 + bytes[1]) / 100.0
        return gramsPerSecond * 3600 / STOICH_AFR / PETROL_G_PER_L
    }

    private const val STOICH_AFR = 14.7
    private const val PETROL_G_PER_L = 745.0

    /** Temperature PIDs: value = A - 40 (°C). */
    internal fun tempFrom(response: String, header: String): Int? {
        val a = dataBytes(response, header)?.firstOrNull() ?: return null
        return a - 40
    }

    /** Percentage PIDs: value = A * 100 / 255. */
    internal fun percentFrom(response: String, header: String): Int? {
        val a = dataBytes(response, header)?.firstOrNull() ?: return null
        return (a * 100) / 255
    }

    /** Parses the ELM327 `ATRV` reply, e.g. "12.3V". */
    internal fun parseVoltage(response: String): Double? =
        NUMBER.find(response)?.groupValues?.getOrNull(1)?.toDoubleOrNull()

    /** Control-module voltage (PID 0142): value = ((A*256)+B) / 1000 volts. */
    internal fun parseControlModuleVoltage(response: String): Double? {
        val bytes = dataBytes(response, "4142") ?: return null
        if (bytes.size < 2) return null
        return ((bytes[0] * 256) + bytes[1]) / 1000.0
    }

    /**
     * Monitor status (PID 0101): the top bit of A is the engine warning lamp,
     * the other seven bits the number of stored emission-related codes.
     */
    internal fun parseEngineLamp(response: String): EngineLamp? {
        // Several computers answer (engine, gearbox): the lamp is on if any says so.
        val bytes = dtcMessages(response).filter { it.size >= 3 && it[0] == 0x41 && it[1] == 0x01 }.map { it[2] }
        if (bytes.isEmpty()) return null
        return EngineLamp(on = bytes.any { it and 0x80 != 0 }, storedCodes = bytes.sumOf { it and 0x7F })
    }

    /** Whether an `ATDPN` reply ("A6", "6") names an 11-bit CAN protocol, where the engine computer is 7E0. */
    internal fun isCan11Bit(reply: String): Boolean =
        reply.trim().uppercase().removePrefix("A").firstOrNull() in setOf('6', '8')

    /**
     * Decodes a mode-03 reply into DTC strings like "P0133".
     *
     * Each ECU answers with its own message, and the layout depends on the bus:
     *  - Older protocols: one line per 3 codes, always `43` + 6 bytes (7, odd),
     *    zero-padded: `43 01 33 00 00 00 00`.
     *  - CAN (the C4 Picasso): `43`, a code count, then the codes, so always an
     *    even byte count: `43 01 01 33`. More than 2 codes arrive multi-frame as
     *    a byte-count line and numbered lines: `00A` / `0: 43 04 ..` / `1: ..`.
     * Reading a CAN reply the old way turns the count byte into a bogus code.
     */
    internal fun parseDtcs(response: String): List<String> = parseDtcReply(response).orEmpty()

    /**
     * Codes from a mode 03 (stored) or, with [mode] 0x47, mode 07 (pending)
     * reply; null when no computer answered at all ("NO DATA"), which must
     * not read as "no fault".
     */
    internal fun parseDtcReply(response: String, mode: Int = 0x43): List<String>? {
        val codes = mutableListOf<String>()
        var answered = false
        dtcMessages(response).forEach { bytes ->
            if (bytes.size < 2 || bytes[0] != mode) return@forEach
            answered = true
            val can = bytes.size % 2 == 0
            val payload = if (can) bytes.drop(2).take(bytes[1] * 2) else bytes.drop(1)
            payload.chunked(2).forEach { pair ->
                if (pair.size == 2 && (pair[0] != 0 || pair[1] != 0)) codes.add(decodeDtc(pair[0], pair[1]))
            }
        }
        return if (answered) codes.distinct() else null
    }

    /**
     * Splits a reply into per-message byte lists, dropping status lines. Knows
     * the adapter's layouts:
     *  - plain, one message per line: `43 01 13 52`;
     *  - CAN multi-frame without headers: `00A` / `0: 43 04 ..` / `1: ..`;
     *  - headers on (some adapters start that way): each line opens with the
     *    sender's address and the CAN frame's length byte, padding after:
     *    `7E8 04 43 01 13 52 FF FF FF` (11-bit), `18DAF110 04 43 ..` (29-bit).
     *    Multi-frame parts (`7E8 10 0A ..` / `7E8 21 ..`) are joined per sender.
     * Spaces are optional everywhere.
     */
    private fun dtcMessages(response: String): List<List<Int>> {
        val messages = mutableListOf<MutableList<Int>>()
        // Headerless multi-frame: bytes still expected by the message being assembled.
        var remaining = 0
        // With headers: each sender's message being assembled, and the bytes it still expects.
        val assembling = mutableMapOf<String, Pair<MutableList<Int>, Int>>()
        response.uppercase().split('\r', '\n').map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            val frame = FRAME_LINE.find(line)
            val body = (frame?.groupValues?.get(2) ?: line).replace(" ", "")
            // "SEARCHING...", "NO DATA" and the like carry no bytes.
            if (body.isEmpty() || !body.all { it in '0'..'9' || it in 'A'..'F' }) return@forEach
            // A message is whole bytes, so an odd length means a 3-digit sender address in front.
            val header = when {
                frame != null -> 0
                body.length >= 5 && body.length % 2 == 1 -> 3
                body.length >= 10 && (body.startsWith("18DA") || body.startsWith("18DB")) -> 8
                else -> 0
            }
            if (header > 0) {
                val sender = body.take(header)
                val bytes = body.drop(header).chunked(2).mapNotNull { it.toIntOrNull(16) }
                val pci = bytes.firstOrNull() ?: return@forEach
                when (pci shr 4) {
                    // Single frame: the low nibble is the length; the rest is padding.
                    0 -> messages.add(bytes.drop(1).take(pci and 0x0F).toMutableList())
                    // First frame of a long message: 12-bit length, then its first bytes.
                    1 -> if (bytes.size >= 2) {
                        val total = ((pci and 0x0F) shl 8) or bytes[1]
                        val first = bytes.drop(2).take(total).toMutableList()
                        messages.add(first)
                        assembling[sender] = first to (total - first.size)
                    }
                    // Consecutive frame: more of that sender's message.
                    2 -> assembling[sender]?.let { (message, left) ->
                        val more = bytes.drop(1).take(left)
                        message.addAll(more)
                        assembling[sender] = message to (left - more.size)
                    }
                }
                return@forEach
            }
            when {
                frame == null && body.length == 3 -> {
                    remaining = body.toInt(16)
                    messages.add(mutableListOf())
                }
                frame != null && remaining > 0 -> {
                    val bytes = body.chunked(2).mapNotNull { it.toIntOrNull(16) }.take(remaining)
                    messages.last().addAll(bytes)
                    remaining -= bytes.size
                }
                body.length % 2 == 0 -> {
                    remaining = 0
                    messages.add(body.chunked(2).map { it.toInt(16) }.toMutableList())
                }
            }
        }
        return messages
    }

    internal fun decodeDtc(a: Int, b: Int): String {
        val letter = charArrayOf('P', 'C', 'B', 'U')[(a and 0xC0) shr 6]
        val d1 = (a and 0x30) shr 4
        val d2 = a and 0x0F
        val d3 = (b and 0xF0) shr 4
        val d4 = b and 0x0F
        return "%c%d%X%X%X".format(Locale.US, letter, d1, d2, d3, d4)
    }

    /** Extracts the data bytes that follow [header] (e.g. "410D") in [response]. */
    internal fun dataBytes(response: String, header: String): List<Int>? {
        // Runs for every reply of every poll: a plain loop, no regex or extra copies.
        val hex = buildString(response.length) {
            for (c in response) {
                val u = c.uppercaseChar()
                if (u in '0'..'9' || u in 'A'..'F') append(u)
            }
        }
        val index = hex.indexOf(header)
        if (index < 0) return null

        val payload = hex.substring(index + header.length)
        val bytes = mutableListOf<Int>()
        var i = 0
        while (i + 2 <= payload.length) {
            val value = payload.substring(i, i + 2).toIntOrNull(16) ?: break
            bytes.add(value)
            i += 2
        }
        return if (bytes.isEmpty()) null else bytes
    }

    /**
     * The PIDs a "supported PIDs" request (0100, 0120, 0140...) says are served,
     * [base] + 1 to [base] + 32, one bit each, most significant first. Every
     * computer that answers (engine, gearbox) adds its own; null when none did.
     */
    internal fun parseSupportedPids(response: String, base: Int): Set<Int>? {
        val header = "41" + HEX_DIGITS[base shr 4] + HEX_DIGITS[base and 0x0F]
        var answered = false
        val pids = HashSet<Int>()
        response.split('\r', '\n').forEach { line ->
            val bytes = dataBytes(line, header)?.takeIf { it.size >= 4 } ?: return@forEach
            answered = true
            for (i in 0 until 32) {
                if (bytes[i / 8] and (0x80 shr (i % 8)) != 0) pids += base + i + 1
            }
        }
        return if (answered) pids else null
    }

    /**
     * Asks the car which mode-01 PIDs it serves, up to 0160: 0100, then 0120
     * and 0140 when the range before says they exist. Null when the first
     * request goes unanswered (engine computer asleep): then nothing is known.
     */
    internal fun supportedPids(ask: (String) -> String?): SupportedPids? {
        val pids = HashSet<Int>()
        var base = 0
        while (base <= 0x40) {
            val command = "01" + HEX_DIGITS[base shr 4] + HEX_DIGITS[base and 0x0F]
            val range = ask(command)?.let { parseSupportedPids(it, base) }
                ?: return if (base == 0) null else SupportedPids(pids, knownUpTo = base)
            pids += range
            // The last bit of each range says whether the next one exists at all.
            if (base + 0x20 !in range) return SupportedPids(pids, knownUpTo = 0xFF)
            base += 0x20
        }
        return SupportedPids(pids, knownUpTo = base)
    }

    private const val HEX_DIGITS = "0123456789ABCDEF"
    private val NUMBER = Regex("([0-9]+\\.?[0-9]*)")
    private val FRAME_LINE = Regex("^([0-9A-F]):\\s*(.*)$")
}

/**
 * The mode-01 PIDs the car said it serves. A PID past the ranges it was asked
 * about ([knownUpTo]) gets the benefit of the doubt.
 */
internal class SupportedPids(private val pids: Set<Int>, private val knownUpTo: Int) {
    fun has(pid: Int): Boolean = pid > knownUpTo || pid in pids
}
