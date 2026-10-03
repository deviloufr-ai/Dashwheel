package com.openauto.dash.display

import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * The boot picture (Plymouth's, see tools/pi/splash): it stays on the monitor
 * until this service's first picture replaces it, and the idle screen shows it
 * again, so start-up has no black gap.
 */
object BootLogo {
    private const val THEME_LOGO = "/usr/share/plymouth/themes/dashwheel/logo.png"

    /** The installed logo (the driver's own or Dashwheel's); null on a computer. */
    val image: BufferedImage? by lazy { runCatching { ImageIO.read(File(THEME_LOGO)) }.getOrNull() }

    @Volatile private var released = false

    /** Plymouth lets go of the screen, its picture left on it: right before ours takes over. */
    fun release() {
        if (released) return
        released = true
        runCatching {
            ProcessBuilder("plymouth", "quit", "--retain-splash").redirectErrorStream(true).start()
                .waitFor(5, TimeUnit.SECONDS)
        }
    }
}
