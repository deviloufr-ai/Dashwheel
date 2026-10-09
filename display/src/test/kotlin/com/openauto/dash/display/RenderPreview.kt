package com.openauto.dash.display

import com.openauto.dash.link.ClusterState
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/** Writes every page to PNG when -Drender.dir is set (preview only). */
class RenderPreview {
    private val MEASURES = listOf(
        ClusterState.Measure("tr/min", "2100"), ClusterState.Measure("Vitesse", "87 km/h"),
        ClusterState.Measure("Liquide de refroidissement", "90 °C"), ClusterState.Measure("Air d'admission", "31 °C"),
        ClusterState.Measure("Accélérateur", "18 %"), ClusterState.Measure("Charge moteur", "42 %"),
        ClusterState.Measure("Batterie", "14.3 V"), ClusterState.Measure("Niveau de carburant", "40 %")
    )

    @Test
    fun render() {
        val dir = System.getenv("RENDER_DIR") ?: return
        System.setProperty("java.awt.headless", "true")
        val base = ClusterState(
            clock = 1_700_000_000_000, speedKmh = 87, rpm = 2100, coolantC = 90, fuelPct = 40, rangeKm = 310,
            obdConnected = true, night = true,
            media = ClusterState.Media("Midnight City", "M83", "Spotify", true, 95_000, 243_000),
            nav = ClusterState.Nav("Turn right onto Avenue Jean Jaurès", "300 m", "Avenue Jean Jaurès", "18:42")
        )
        val states = mapOf(
            "drive_night" to base,
            "drive_day" to base.copy(night = false),
            "drive_open" to base.copy(open = listOf("Front left", "Tailgate")),
            "drive_noobd" to ClusterState(clock = base.clock, speedKmh = 52, night = true),
            "media_night" to base.copy(page = "MEDIA"),
            "media_day" to base.copy(page = "MEDIA", night = false),
            "nav_night" to base.copy(page = "NAV"),
            "nav_day" to base.copy(page = "NAV", night = false),
            "nav_none" to base.copy(page = "NAV", nav = null),
            "nav_roundabout_day" to base.copy(page = "NAV", night = false, nav = ClusterState.Nav("Au rond-point, prenez la 2e sortie", "1,2 km", "D 906", "18:42"), labels = ClusterState.Labels(arrive = "Arrivée %s")),
            "nav_left" to base.copy(page = "NAV", nav = ClusterState.Nav("Turn left", "50 m", "Rue de la Paix", "09:05")),
            "media_none" to base.copy(page = "MEDIA", media = null),
            "media_paused_long" to base.copy(page = "MEDIA", media = ClusterState.Media("A very long song title that will not fit on one line at all, really", "Some Artist feat. Another", "YouTube Music", false, 5_000, 600_000)),
            "drive_three" to base.copy(rpm = null, coolantC = 52, fuelPct = 8, speedKmh = 132),
            "obd_night" to base.copy(page = "OBD", measures = MEASURES),
            "obd_day" to base.copy(page = "OBD", night = false, measures = MEASURES),
            "obd_none" to base.copy(page = "OBD", labels = ClusterState.Labels(measuresNone = "En attente de l'adaptateur OBD…")),
            "alert_warn" to base.copy(alert = ClusterState.Alert("Doors", "Front left, Tailgate", ClusterState.Alert.WARN)),
            "alert_crit" to base.copy(alert = ClusterState.Alert("Engine hot", "Coolant 118°", ClusterState.Alert.CRITICAL)),
            // The board's own pages, as the head unit lays them out by default.
            "board_drive" to base.copy(layout = ClusterState.Layout("BIG_STACK", listOf("SPEED", "CLOCK", "RANGE"))),
            "board_media" to base.copy(page = "MEDIA", layout = ClusterState.Layout("BIG_STACK", listOf("MEDIA", "SPEED", "CLOCK"))),
            "board_nav" to base.copy(page = "NAV", maneuver = "ROUNDABOUT", layout = ClusterState.Layout("BIG_STACK", listOf("NAV", "SPEED", "CLOCK"))),
            "board_obd" to base.copy(page = "OBD", measures = MEASURES, layout = ClusterState.Layout("BIG_SIDE", listOf("TELEMETRY", "MEASURES"))),
            "board_grid" to base.copy(open = listOf("Front left"), layout = ClusterState.Layout("GRID", listOf("SPEED", "DOORS", "FUEL", "COOLANT"))),
            "board_grid_noobd" to ClusterState(clock = base.clock, speedKmh = 52, night = true, layout = ClusterState.Layout("GRID", listOf("SPEED", "DOORS", "FUEL", "COOLANT"))),
            // The map the display draws itself: tiles at hand, the route, the next turn.
            "board_map" to base.copy(page = "NAV", maneuver = "RIGHT", position = ClusterState.Position(48.8530, 2.3499, 10), layout = ClusterState.Layout("BIG_STACK", listOf("MAP", "SPEED", "CLOCK"))),
            "board_map_day" to base.copy(page = "NAV", night = false, maneuver = "ROUNDABOUT", position = ClusterState.Position(48.8530, 2.3499, 10), open = listOf("Tailgate"), layout = ClusterState.Layout("ONE", listOf("MAP"))),
            "board_map_nofix" to base.copy(page = "NAV", layout = ClusterState.Layout("BIG_STACK", listOf("MAP", "SPEED", "CLOCK"))),
        )
        File(dir).mkdirs()
        val painter = Painter(1024, 600, overscanPct = 0)
        // Stand-in tiles: a block pattern with a street grid, so the map's layout can be judged without a tile server.
        painter.tiles = object : TileSource {
            override val zoom = 16
            override fun tile(z: Int, x: Int, y: Int): java.awt.image.BufferedImage {
                val img = java.awt.image.BufferedImage(256, 256, java.awt.image.BufferedImage.TYPE_INT_RGB)
                val g = img.createGraphics()
                g.color = java.awt.Color(0xE9ECEF); g.fillRect(0, 0, 256, 256)
                g.color = java.awt.Color(0xDADFE4)
                for (bx in 0 until 256 step 64) for (by in 0 until 256 step 64) g.fillRect(bx + 6, by + 6, 52, 52)
                g.color = java.awt.Color.WHITE
                for (i in 0 until 256 step 64) { g.fillRect(i - 3, 0, 6, 256); g.fillRect(0, i - 3, 256, 6) }
                g.dispose()
                return img
            }
            override fun prefetch(keys: Collection<TileKey>) = Unit
        }
        painter.route = listOf(48.8500, 2.3499, 48.8530, 2.3499, 48.8560, 2.3499, 48.8560, 2.3600, 48.8560, 2.3800)
        for ((name, s) in states) {
            ImageIO.write(painter.paintCluster(s, s.clock), "png", File(dir, "$name.png"))
        }
        for (design in Design.entries) for ((name, s) in states) {
            if (!name.startsWith("drive") && !name.startsWith("board") && name != "nav_night" && name != "media_night") continue
            ImageIO.write(painter.paintCluster(s.copy(design = design.name), s.clock), "png", File(dir, "${design.name.lowercase()}_$name.png"))
        }
    }
}
