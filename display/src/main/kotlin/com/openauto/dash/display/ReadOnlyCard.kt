package com.openauto.dash.display

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The SD card's read-only mode (raspi-config's overlay file system): with it,
 * cutting the power, as the ignition does, can't harm the card. The display
 * turns it on by itself once a head unit has paired, and still saves its
 * pairing on a read-only card by unlocking the boot partition for a moment.
 */
object ReadOnlyCard {
    private const val CMDLINE = "/proc/cmdline"
    private const val RASPI_CONFIG = "/usr/bin/raspi-config"

    @Volatile private var asked = false

    /** Read-only from this start on. */
    val active: Boolean
        get() = runCatching { File(CMDLINE).readText().contains("overlayroot=tmpfs") }.getOrDefault(false)

    /**
     * Read-only from the next start on: raspi-config prepares it now (about a
     * minute on a Pi 3, in the background), and nothing changes until then.
     */
    fun turnOnFromNextStart(bootDir: File) {
        if (asked || active || !File(RASPI_CONFIG).exists()) return
        // Already asked on an earlier start, and not taken effect yet.
        if (runCatching { File(bootDir, "cmdline.txt").readText().contains("overlayroot=") }.getOrDefault(false)) return
        asked = true
        Thread({
            log("read-only card: turning on from the next start")
            val ok = run(RASPI_CONFIG, "nonint", "do_overlayfs", "0", timeoutS = 300)
            log(if (ok) "read-only card: on from the next start" else "read-only card: raspi-config failed")
        }, "read-only-card").apply { isDaemon = true }.start()
    }

    /**
     * [write] into [dir] on the boot partition; if the card is read-only, that
     * partition is unlocked for the moment and locked again after.
     */
    fun write(dir: File, write: () -> Unit) {
        if (runCatching(write).isSuccess) return
        val mount = mountPointOf(dir) ?: return
        if (!run("mount", "-o", "remount,rw", mount)) return
        try {
            runCatching(write).onFailure { System.err.println("could not write in $dir: $it") }
        } finally {
            run("mount", "-o", "remount,ro", mount)
        }
    }

    private fun mountPointOf(dir: File): String? =
        runCatching {
            val out = ProcessBuilder("findmnt", "-n", "-o", "TARGET", "--target", dir.absolutePath)
                .redirectErrorStream(true).start()
            val text = out.inputStream.bufferedReader().readText().trim()
            out.waitFor(5, TimeUnit.SECONDS)
            text.lines().firstOrNull()?.takeIf { it.startsWith("/boot") }
        }.getOrNull()

    private fun run(vararg command: String, timeoutS: Long = 10): Boolean =
        runCatching {
            val p = ProcessBuilder(*command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            p.waitFor(timeoutS, TimeUnit.SECONDS) && p.exitValue() == 0
        }.getOrDefault(false)
}
