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
 * # The card turns read-only once paired; manual: only with install.sh --overlay.
 * read_only=manual
 * # GPIOs wired to the monitor's own brightness buttons (see BrightnessButtons).
 * brightness_up_gpio=17
 * brightness_down_gpio=27
 * # Raster tiles for the map the display draws itself ({z}, {x}, {y}), by day and, optionally, by night.
 * tile_url=https://tile.example.com/{z}/{x}/{y}.png?key=...
 * tile_url_night=...
 * # Where fetched tiles are kept (in memory on a read-only card unless this points elsewhere), and the zoom.
 * tile_cache=/var/cache/dashwheel-display/tiles
 * map_zoom=16
 * ```
 */
data class DisplayConfig(
    val name: String = "Dashwheel display",
    val overscanPct: Int = 0,
    val width: Int? = null,
    val height: Int? = null,
    val sink: Sink = Sink.KMS,
    val videoPipeline: String? = null,
    /** The card turns read-only once a head unit has paired; false: only by hand (install.sh --overlay). */
    val readOnlyAfterPairing: Boolean = true,
    /** GPIOs wired to the monitor's `+` and `−` buttons; both or nothing. */
    val brightnessUpGpio: Int? = null,
    val brightnessDownGpio: Int? = null,
    val port: Int = com.openauto.dash.link.DISPLAY_PORT,
    /** Raster tiles for the display's own map; null: no map tiles (the map shows the route and the car on a plain ground). */
    val tileUrl: String? = null,
    val tileUrlNight: String? = null,
    val tileCache: String = DEFAULT_TILE_CACHE,
    val mapZoom: Int = DEFAULT_MAP_ZOOM
) {
    enum class Sink(val element: String) {
        /** Straight to the screen through DRM/KMS: the Pi with no desktop. */
        KMS("kmssink sync=false"),

        /** A window on a desktop, for trying the display out on a computer. */
        AUTO("autovideosink sync=false")
    }

    companion object {
        const val FILE = "display.conf"
        const val DEFAULT_TILE_CACHE = "/var/cache/dashwheel-display/tiles"
        const val DEFAULT_MAP_ZOOM = 16

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
            val up = int("brightness_up_gpio")?.takeIf { it in 2..27 }
            val down = int("brightness_down_gpio")?.takeIf { it in 2..27 && it != up }
            return DisplayConfig(
                name = text("name")?.take(64) ?: defaults.name,
                overscanPct = int("overscan")?.coerceIn(0, 15) ?: 0,
                width = int("width")?.takeIf { it in 160..4096 },
                height = int("height")?.takeIf { it in 120..4096 },
                sink = text("sink")?.let { s -> Sink.entries.firstOrNull { it.name.equals(s, ignoreCase = true) } } ?: Sink.KMS,
                videoPipeline = text("video_pipeline"),
                readOnlyAfterPairing = !text("read_only").equals("manual", ignoreCase = true),
                brightnessUpGpio = up?.takeIf { down != null },
                brightnessDownGpio = down?.takeIf { up != null },
                port = int("port")?.takeIf { it in 1024..65535 } ?: defaults.port,
                tileUrl = text("tile_url")?.takeIf { "{z}" in it && "{x}" in it && "{y}" in it },
                tileUrlNight = text("tile_url_night")?.takeIf { "{z}" in it && "{x}" in it && "{y}" in it },
                tileCache = text("tile_cache") ?: defaults.tileCache,
                mapZoom = int("map_zoom")?.coerceIn(10, 18) ?: defaults.mapZoom
            )
        }
    }
}
