package com.openauto.dash.display

import com.openauto.dash.link.ClusterState
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/** Writes every page to PNG when -Drender.dir is set (preview only). */
class RenderPreview {
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
            "alert_warn" to base.copy(alert = ClusterState.Alert("Doors", "Front left, Tailgate", ClusterState.Alert.WARN)),
            "alert_crit" to base.copy(alert = ClusterState.Alert("Engine hot", "Coolant 118°", ClusterState.Alert.CRITICAL)),
        )
        File(dir).mkdirs()
        val painter = Painter(1024, 600, overscanPct = 0)
        for ((name, s) in states) {
            ImageIO.write(painter.paintCluster(s, s.clock), "png", File(dir, "$name.png"))
        }
        for (design in Design.entries) for ((name, s) in states) {
            if (!name.startsWith("drive") && name != "nav_night" && name != "media_night") continue
            ImageIO.write(painter.paintCluster(s.copy(design = design.name), s.clock), "png", File(dir, "${design.name.lowercase()}_$name.png"))
        }
    }
}
