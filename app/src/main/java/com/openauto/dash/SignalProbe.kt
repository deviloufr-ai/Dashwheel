package com.openauto.dash

/*
 * The Signal Finder's core, free of Android: every value the head unit shows
 * Dashwheel, sliced into the pieces one could stand for a door or a light
 * ([SignalProbe]), and the ranking of the pieces that followed the driver's
 * rounds of "open it, close it" ([SignalMatch]).
 *
 * A value is a string under a key "<source>:<name>". The car box's frames and
 * the car app's shared data are bytes, written as hex ("00 20 1C"); each of
 * their bytes and bits is a piece of its own. Anything else (a flag, a setting,
 * a message's extra) is one piece, its whole text.
 */

/** Where a value comes from, by its key's prefix. */
enum class SignalSource(val prefix: String) {
    /** The car box's frames, from the unit's log (root). */
    CAN("can"),
    /** The car app's shared data (DATA_SHARE), by its type. */
    SHARED("share"),
    /** A head unit system property. */
    FLAG("prop"),
    /** A global setting. */
    SETTING("global"),
    /** A head unit broadcast's extra. */
    BROADCAST("bc");

    fun key(name: String) = "$prefix:$name"

    companion object {
        fun of(key: String): SignalSource? = entries.firstOrNull { key.startsWith(it.prefix + ":") }

        /** The name after the key's prefix. */
        fun name(key: String): String = key.substringAfter(':')

        /** Whether the value under [key] is bytes, sliced into bytes and bits. */
        fun isBytes(key: String): Boolean = key.startsWith("can:") || key.startsWith("share:") || key.endsWith("[]")
    }
}

/**
 * One piece of a value that could stand for a signal: the whole value, one
 * byte of it ([byteIndex]) or one bit of that byte ([bit], 0 = lowest). The
 * signal is on when the piece reads [onValue].
 */
data class SignalProbe(val key: String, val byteIndex: Int = -1, val bit: Int = -1, val onValue: String = "") {

    /** The piece without its on value: what the ranking compares. */
    val id: String get() = idOf(key, byteIndex, bit)

    /** The piece's text in [value] (the whole value under [key]), or null when it has none. */
    fun read(value: String?): String? = piece(value, byteIndex, bit)

    /** Whether the signal is on by [value]; null when the value has no such piece. */
    fun isOn(value: String?): Boolean? = read(value)?.let { it == onValue }

    /** For storing: the fields on one line, apart by a separator no key holds. */
    fun encode(): String = listOf(key, byteIndex.toString(), bit.toString(), onValue).joinToString(SEP)

    companion object {
        private const val SEP = "\u001F"

        fun decode(text: String?): SignalProbe? {
            val parts = text?.split(SEP) ?: return null
            if (parts.size != 4 || SignalSource.of(parts[0]) == null) return null
            val byte = parts[1].toIntOrNull() ?: return null
            val bit = parts[2].toIntOrNull() ?: return null
            if (bit !in -1..7 || (bit >= 0 && byte < 0)) return null
            return SignalProbe(parts[0], byte, bit, parts[3])
        }

        internal fun idOf(key: String, byteIndex: Int, bit: Int) = "$key@$byteIndex.$bit"

        /** The probe [id] names, with [onValue]. */
        internal fun fromId(id: String, onValue: String): SignalProbe {
            val key = id.substringBeforeLast('@')
            val (b, bit) = id.substringAfterLast('@').split('.').map { it.toInt() }
            return SignalProbe(key, b, bit, onValue)
        }

        /** The bytes of a hex value ("00 2A ff"), or null when it is not one. */
        internal fun bytesOf(value: String?): IntArray? {
            if (value.isNullOrBlank()) return null
            val parts = value.trim().split(' ').filter { it.isNotEmpty() }
            val out = IntArray(parts.size)
            for ((i, p) in parts.withIndex()) out[i] = p.toIntOrNull(16)?.takeIf { p.length <= 2 } ?: return null
            return out
        }

        internal fun piece(value: String?, byteIndex: Int, bit: Int): String? {
            if (value == null) return null
            if (byteIndex < 0) return value
            val b = bytesOf(value)?.getOrNull(byteIndex) ?: return null
            return if (bit < 0) hex2(b) else ((b shr bit) and 1).toString()
        }

        /** Every piece of every value, by id: the whole of each, and the bytes and bits of byte values. */
        fun flatten(values: Map<String, String>): Map<String, String> {
            val out = HashMap<String, String>(values.size * 4)
            for ((key, value) in values) {
                out[idOf(key, -1, -1)] = value
                if (!SignalSource.isBytes(key)) continue
                val bytes = bytesOf(value) ?: continue
                for ((i, b) in bytes.withIndex()) {
                    out[idOf(key, i, -1)] = hex2(b)
                    for (bit in 0..7) out[idOf(key, i, bit)] = ((b shr bit) and 1).toString()
                }
            }
            return out
        }

        private const val HEX = "0123456789ABCDEF"
        internal fun hex2(b: Int): String = charArrayOf(HEX[(b shr 4) and 15], HEX[b and 15]).concatToString()
    }
}

/** A piece that followed the rounds: [matched] of [rounds] went on with the action and back off after. */
data class SignalCandidate(val probe: SignalProbe, val matched: Int, val rounds: Int, val changes: Int)

/** One round: every piece's value once the action was done ([on]) and once it was undone ([off]). */
data class SignalRound(val on: Map<String, String>, val off: Map<String, String>)

object SignalMatch {

    /**
     * The pieces that changed while nothing was being done ([baseline], every
     * piece's values sampled over a few seconds): counters, clocks, the engine
     * running. They can't be told apart from the action, so they're left out.
     */
    fun noisy(baseline: List<Map<String, String>>): Set<String> {
        if (baseline.size < 2) return emptySet()
        val first = baseline.first()
        val out = HashSet<String>()
        for (sample in baseline.drop(1)) for ((id, v) in sample) if (first[id] != v) out += id
        // A piece only there for part of the time changed too.
        for (sample in baseline) for (id in first.keys) if (id !in sample) out += id
        return out
    }

    /**
     * The pieces that went from [rest] (everything before the first round) to
     * one same value in each round and back to [rest] after it, best first:
     * the most rounds, then the fewest other changes, then a bit before its
     * byte before the whole value (the byte and whole value of a matching bit
     * are left out when they match no better). At most [limit].
     *
     * @param changes how many times each piece changed in all, over the rounds
     */
    fun rank(
        rest: Map<String, String>,
        rounds: List<SignalRound>,
        noisy: Set<String>,
        changes: Map<String, Int> = emptyMap(),
        limit: Int = 8
    ): List<SignalCandidate> {
        if (rounds.isEmpty()) return emptyList()
        val found = ArrayList<SignalCandidate>()
        for ((id, before) in rest) {
            if (id in noisy) continue
            // The on value is the first round's that changed; a round on another value doesn't count.
            var onValue: String? = null
            var matched = 0
            for (r in rounds) {
                val on = r.on[id] ?: continue
                if (on == before || r.off[id] != before) continue
                if (onValue == null) onValue = on
                if (on == onValue) matched++
            }
            if (matched == 0 || onValue == null) continue
            val probe = SignalProbe.fromId(id, onValue)
            // Two changes a round are the action itself; more are the piece doing other things too.
            val extra = ((changes[id] ?: (2 * matched)) - 2 * matched).coerceAtLeast(0)
            found += SignalCandidate(probe, matched, rounds.size, extra)
        }
        // A bit that matches makes its byte and whole value no news.
        val bestBit = HashMap<String, Int>()
        val bestByte = HashMap<String, Int>()
        for (c in found) {
            val p = c.probe
            if (p.bit >= 0) bestBit.merge("${p.key}@${p.byteIndex}", c.matched, ::maxOf)
            if (p.byteIndex >= 0) bestByte.merge(p.key, c.matched, ::maxOf)
        }
        val kept = found.filter { c ->
            val p = c.probe
            when {
                p.bit >= 0 -> true
                p.byteIndex >= 0 -> (bestBit["${p.key}@${p.byteIndex}"] ?: 0) < c.matched
                else -> (bestByte[p.key] ?: 0) < c.matched
            }
        }
        return kept.sortedWith(
            compareByDescending<SignalCandidate> { it.matched }
                .thenBy { it.changes }
                .thenBy { if (it.probe.bit >= 0) 0 else if (it.probe.byteIndex >= 0) 1 else 2 }
                .thenBy { sourceRank(it.probe.key) }
                .thenBy { it.probe.id }
        ).take(limit)
    }

    /** Readable without root first: what works on every unit beats the car box's log. */
    private fun sourceRank(key: String): Int = when (SignalSource.of(key)) {
        SignalSource.SHARED -> 0
        SignalSource.FLAG -> 1
        SignalSource.BROADCAST -> 2
        SignalSource.SETTING -> 3
        SignalSource.CAN -> 4
        null -> 5
    }

    /** The pieces among [among] whose value in [sample] is not their value in [rest]. */
    fun flipped(rest: Map<String, String>, sample: Map<String, String>, among: Collection<String>): Set<String> {
        val out = HashSet<String>()
        for (id in among) {
            val now = sample[id] ?: continue
            val before = rest[id] ?: continue
            if (now != before) out += id
        }
        return out
    }
}
