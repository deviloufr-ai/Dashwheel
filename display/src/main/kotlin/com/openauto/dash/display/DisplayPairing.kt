package com.openauto.dash.display

import com.openauto.dash.link.PairingOffer
import java.io.File

/**
 * The display's one pairing, kept as its QR text in `pairing.txt` in the
 * config folder. It is made once, the first time the service runs (the
 * installer runs it before the SD card is made read-only), and shown as a QR
 * code until a head unit has connected with it. Delete the file to pair
 * afresh: every head unit that knew the old one then has to pair again.
 */
class DisplayPairing(private val dir: File, name: String) {

    val offer: PairingOffer = load() ?: create(name)

    /** True once a head unit has connected with it; the idle screen then drops the QR code. */
    var used: Boolean = File(dir, USED).exists()
        private set

    /** The secret for [id], or null when it isn't this display's pairing. */
    fun secretFor(id: String): ByteArray? = offer.secret.takeIf { id == offer.id }

    fun markUsed() {
        if (used) return
        used = true
        // A read-only card keeps showing the code; that only costs a corner of the idle screen.
        runCatching { File(dir, USED).writeText("") }
    }

    private fun load(): PairingOffer? =
        runCatching { File(dir, FILE).readText() }.getOrNull()
            ?.let(PairingOffer::parse)
            ?.takeIf { it.kind == PairingOffer.Kind.DISPLAY }

    private fun create(name: String): PairingOffer {
        val offer = PairingOffer.create(name, kind = PairingOffer.Kind.DISPLAY)
        runCatching {
            dir.mkdirs()
            File(dir, FILE).writeText(offer.toUri() + "\n")
            File(dir, USED).delete()
        }.onFailure { System.err.println("could not save the pairing in $dir: $it") }
        return offer
    }

    companion object {
        const val FILE = "pairing.txt"
        private const val USED = "paired"
    }
}
