package com.openauto.dash.display

import java.io.File
import java.util.Properties

/**
 * `display.conf` in the config folder (`/boot/firmware/dashwheel/` on the Pi,
 * editable from any computer by taking out the SD card). Every key is optional.
 *
 * ```
 * name=Rear screen
 * # Percent of each edge a TV (composite) crops off.
 * overscan=5
 * # Force a size when the monitor reports none (composite has no EDID).
 * width=720
 * height=576
 * # Where pictures go: kms (the Pi, no desktop) or auto (a desktop window, for testing).
 * sink=kms
 * # The whole GStreamer chain for the video, when the default doesn't suit a board.
 * video_pipeline=...
 * ```
 */
data class DisplayConfig(
    val name: String = "Dashwheel display",
    val overscanPct: Int = 0,
    val width: Int? = null,
    val height: Int? = null,
    val sink: Sink = Sink.KMS,
    val videoPipeline: String? = null,
    val port: Int = com.openauto.dash.link.DISPLAY_PORT
) {
    enum class Sink(val element: String) {
        /** Straight to the screen through DRM/KMS: the Pi with no desktop. */
        KMS("kmssink sync=false"),

        /** A window on a desktop, for trying the display out on a computer. */
        AUTO("autovideosink sync=false")
    }

    companion object {
        const val FILE = "display.conf"

        fun load(dir: File): DisplayConfig {
            val file = File(dir, FILE)
            if (!file.isFile) return DisplayConfig()
            val props = Properties()
            runCatching { file.reader().use(props::load) }
            return parse(props)
        }

        fun parse(props: Properties): DisplayConfig {
            fun int(key: String) = props.getProperty(key)?.trim()?.toIntOrNull()
            fun text(key: String) = props.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
            val defaults = DisplayConfig()
            return DisplayConfig(
                name = text("name")?.take(64) ?: defaults.name,
                overscanPct = int("overscan")?.coerceIn(0, 15) ?: 0,
                width = int("width")?.takeIf { it in 160..4096 },
                height = int("height")?.takeIf { it in 120..4096 },
                sink = text("sink")?.let { s -> Sink.entries.firstOrNull { it.name.equals(s, ignoreCase = true) } } ?: Sink.KMS,
                videoPipeline = text("video_pipeline"),
                port = int("port")?.takeIf { it in 1024..65535 } ?: defaults.port
            )
        }
    }
}
