package com.openauto.dash.link

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * What the head unit's pairing QR code carries: a random id for this pairing,
 * the head unit's name to show on the phone, and the 32-byte secret both sides
 * then prove they hold on every connection. Anyone who sees the code can pair,
 * so it is only shown on demand and replaced each time.
 *
 * `dashwheel://pair?v=1&id=<hex>&n=<name>&k=<base64url secret>`
 *
 * A second-screen display (the Raspberry Pi) shows the same offer under
 * `dashwheel://display?…` ([Kind.DISPLAY]): the phone scans it and hands it to
 * the head unit, which then dials the display with it. The other host keeps an
 * older companion app from taking a display's code for a head unit's.
 *
 * For a phone whose camera can't read the QR, the same offer is also a [code]
 * to type: a 120-bit seed in Crockford base32 (`7KQ2-M9XA-…`, 6 groups of 4),
 * from which the id and secret are derived. It carries no name.
 */
class PairingOffer(
    val id: String,
    val unitName: String,
    val secret: ByteArray,
    val kind: Kind = Kind.HEAD_UNIT,
    /** The offer as a code to type, when it was made from a seed ([create]). */
    val code: String? = null
) {

    /** Who showed the code, which is who the head unit will dial. */
    enum class Kind(val host: String) { HEAD_UNIT("pair"), DISPLAY("display") }

    fun toUri(): String =
        "$SCHEME://${kind.host}?v=$VERSION&id=$id&n=${URLEncoder.encode(unitName, "UTF-8")}" +
            "&k=${Base64.getUrlEncoder().withoutPadding().encodeToString(secret)}"

    companion object {
        const val SCHEME = "dashwheel"
        const val HOST = "pair"
        private const val VERSION = 1
        const val SECRET_BYTES = 32
        private val ID = Regex("[0-9a-f]{16}")

        /** The name a typed code pairs under, the code having none. */
        const val TYPED_NAME = "Dashwheel"
        private const val SEED_BYTES = 15
        private const val CODE_CHARS = SEED_BYTES * 8 / 5
        private const val BASE32 = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

        fun create(unitName: String, random: SecureRandom = SecureRandom(), kind: Kind = Kind.HEAD_UNIT): PairingOffer =
            fromSeed(ByteArray(SEED_BYTES).also(random::nextBytes), unitName.trim().take(64), kind)

        private fun fromSeed(seed: ByteArray, name: String, kind: Kind): PairingOffer {
            fun derive(tag: Int) = MessageDigest.getInstance("SHA-256").run { update(seed); update(tag.toByte()); digest() }
            val id = derive(1).copyOf(8).joinToString("") { "%02x".format(it) }
            return PairingOffer(id, name, derive(2), kind, encodeCode(seed))
        }

        private fun encodeCode(seed: ByteArray): String {
            val bits = seed.joinToString("") { (it.toInt() and 0xff).toString(2).padStart(8, '0') }
            return bits.chunked(5).joinToString("") { BASE32[it.toInt(2)].toString() }.chunked(4).joinToString("-")
        }

        /** A typed code, forgiving case, spaces, dashes and the letters read as digits (O, I, L). */
        private fun parseCode(text: String): PairingOffer? {
            val clean = text.uppercase().filter { it.isLetterOrDigit() }
                .replace('O', '0').replace('I', '1').replace('L', '1')
            if (clean.length != CODE_CHARS) return null
            val bits = clean.map { BASE32.indexOf(it).takeIf { i -> i >= 0 } ?: return null }
                .joinToString("") { it.toString(2).padStart(5, '0') }
            val seed = bits.chunked(8).map { it.toInt(2).toByte() }.toByteArray()
            return fromSeed(seed, TYPED_NAME, Kind.HEAD_UNIT)
        }

        /** Null for anything that isn't a well-formed offer (a link or a typed [code]) of a version this side knows. */
        fun parse(text: String): PairingOffer? {
            if (':' !in text) return parseCode(text)
            val uri = runCatching { URI(text.trim()) }.getOrNull() ?: return null
            if (!uri.scheme.equals(SCHEME, ignoreCase = true)) return null
            val kind = Kind.entries.firstOrNull { uri.host.equals(it.host, ignoreCase = true) } ?: return null
            val params = uri.rawQuery.orEmpty().split('&').mapNotNull { part ->
                val eq = part.indexOf('=')
                if (eq <= 0) null
                else part.substring(0, eq) to runCatching { URLDecoder.decode(part.substring(eq + 1), "UTF-8") }.getOrNull()
            }.toMap()
            if (params["v"] != VERSION.toString()) return null
            val id = params["id"]?.takeIf { ID.matches(it) } ?: return null
            val name = params["n"]?.trim()?.takeIf { it.isNotEmpty() }?.take(64) ?: return null
            val secret = params["k"]
                ?.let { runCatching { Base64.getUrlDecoder().decode(it) }.getOrNull() }
                ?.takeIf { it.size == SECRET_BYTES } ?: return null
            return PairingOffer(id, name, secret, kind)
        }
    }
}
