package com.openauto.dash.display

import com.openauto.dash.link.PairingOffer
import java.io.File

/**
 * The display's one pairing, kept as its QR text in `pairing.txt` in the
 * config folder. It is made once, the first time the service runs (the
 * installer runs it before the SD card is made read-only), and shown as a QR
 * code until a head unit has connected with it, then again whenever none has
 * come for a few minutes ([Screen.pairingShown]). Delete the file to pair
 * afresh: every head unit that knew the old one then has to pair again.
 */
class DisplayPairing(val dir: File, name: String) {

    val offer: PairingOffer = load() ?: create(name)

    /** True once a head unit has connected with it; the idle screen then drops the QR code while that one is around. */
    var used: Boolean = File(dir, USED).exists()
        private set

    /** The secret for [id], or null when it isn't this display's pairing. */
    fun secretFor(id: String): ByteArray? = offer.secret.takeIf { id == offer.id }

    fun markUsed() {
        if (used) return
        used = true
        ReadOnlyCard.write(dir) { File(dir, USED).writeText("") }
    }

    private fun load(): PairingOffer? =
        runCatching { File(dir, FILE).readText() }.getOrNull()
            ?.let(PairingOffer::parse)
            ?.takeIf { it.kind == PairingOffer.Kind.DISPLAY }

    private fun create(name: String): PairingOffer {
        val offer = PairingOffer.create(name, kind = PairingOffer.Kind.DISPLAY)
        // Kept even on a read-only card (pairing afresh after deleting the file from a computer).
        ReadOnlyCard.write(dir) {
            dir.mkdirs()
            File(dir, FILE).writeText(offer.toUri() + "\n")
            File(dir, USED).delete()
        }
        return offer
    }

    companion object {
        const val FILE = "pairing.txt"
        private const val USED = "paired"
    }
}
