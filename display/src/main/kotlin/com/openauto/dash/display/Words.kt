package com.openauto.dash.display

import com.openauto.dash.link.DisplayWords
import java.io.File
import java.util.Properties

/**
 * The lines the Pi writes itself, in the head unit's language ([DisplayWords]).
 * Kept in `words.properties` in the config folder, so the screens shown before
 * a head unit links (waiting, the pairing code) read in the driver's language
 * too. English until a head unit has sent its words.
 */
object Words {
    private const val FILE = "words.properties"

    @Volatile var current = DisplayWords()
        private set

    val waiting: String get() = current.waiting ?: "Waiting for Dashwheel…"
    val noSignal: String get() = current.noSignal ?: "No signal from Dashwheel"
    val connected: String get() = current.connected ?: "Connected"
    val scanApp: String get() = current.scanApp ?: "Scan with the Dashwheel phone app"

    fun load(dir: File) {
        val file = File(dir, FILE)
        if (!file.isFile) return
        runCatching {
            val p = Properties().apply { file.reader(Charsets.UTF_8).use { load(it) } }
            current = DisplayWords(
                language = p.getProperty("language", ""),
                waiting = p.getProperty("waiting"),
                noSignal = p.getProperty("noSignal"),
                connected = p.getProperty("connected"),
                scanApp = p.getProperty("scanApp")
            )
        }.onFailure { log("could not read $FILE: ${it.message}") }
    }

    /** True when they changed (and were saved for the next start). */
    fun set(dir: File, words: DisplayWords): Boolean {
        if (words == current) return false
        current = words
        val p = Properties()
        p.setProperty("language", words.language)
        words.waiting?.let { p.setProperty("waiting", it) }
        words.noSignal?.let { p.setProperty("noSignal", it) }
        words.connected?.let { p.setProperty("connected", it) }
        words.scanApp?.let { p.setProperty("scanApp", it) }
        ReadOnlyCard.write(dir) {
            File(dir, FILE).writer(Charsets.UTF_8).use { p.store(it, "Dashwheel display: the head unit's words") }
        }
        return true
    }
}
