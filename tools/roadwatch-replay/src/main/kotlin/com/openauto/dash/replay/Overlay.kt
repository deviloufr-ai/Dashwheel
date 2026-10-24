package com.openauto.dash.replay

import com.openauto.dash.roadwatch.RoadBand
import com.openauto.dash.roadwatch.RoadWarning
import com.openauto.dash.roadwatch.SeenRoad
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage

/**
 * What the phone's screen would have drawn, drawn over the frame: the lane's
 * lines, the car ahead and its distance, the horizon, the warning, and a line
 * of figures. Plus, on request, a ruler along the bonnet's row with the
 * width's tenths, for labelling frames by hand.
 */
object Overlay {
    private val teal = Color(45, 212, 191)
    private val amber = Color(245, 185, 66)
    private val red = Color(248, 113, 113)
    private val ink = Color(0, 0, 0, 150)
    private val roadTint = Color(34, 197, 94, 60)
    private val laneTint = Color(239, 68, 68, 120)

    fun draw(image: BufferedImage, result: FrameResult, band: RoadBand, ruler: Boolean = false, seen: SeenRoad? = null) {
        val w = image.width
        val h = image.height
        val g = image.createGraphics()
        // What the lane model saw, faint: the road green, the lane lines red.
        if (seen != null) {
            val sw = seen.road.w
            val sh = seen.road.h
            for (y in 0 until sh) for (x in 0 until sw) {
                g.color = when {
                    seen.lane[x, y] >= 128 -> laneTint
                    seen.road[x, y] >= 128 -> roadTint
                    else -> continue
                }
                g.fillRect(x * w / sw, y * h / sh, (x + 1) * w / sw - x * w / sw, (y + 1) * h / sh - y * h / sh)
            }
        }
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        val unit = h / 36f

        // The horizon.
        g.color = Color(255, 255, 255, 180)
        g.stroke = BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, floatArrayOf(12f, 8f), 0f)
        val hy = (band.horizonY * h).toInt()
        g.drawLine(0, hy, w, hy)

        // The lane's lines, from the top of the searched band to the bonnet's row.
        val warn = result.warning
        g.stroke = BasicStroke(unit * 0.35f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        listOfNotNull(result.lane.left?.let { it to (warn == RoadWarning.LANE_LEFT) }, result.lane.right?.let { it to (warn == RoadWarning.LANE_RIGHT) }).forEach { (line, hot) ->
            g.color = if (hot) amber else teal
            g.drawLine((line.topX * w).toInt(), (band.topY * h).toInt(), (line.bottomX * w).toInt(), (band.bonnetY * h).toInt())
        }

        // The car ahead.
        result.lead?.let { lead ->
            val b = lead.box
            g.color = if (warn == RoadWarning.COLLISION || warn == RoadWarning.TOO_CLOSE) red else Color.WHITE
            g.stroke = BasicStroke(unit * 0.18f)
            g.drawRect((b.left * w).toInt(), (b.top * h).toInt(), (b.width * w).toInt(), (b.height * h).toInt())
            val text = buildString {
                append("${lead.distanceM.toInt()} m")
                lead.closingMps?.let { append("  ${"%+.1f".format(-it)} m/s") }
                lead.ttcS?.let { append("  ttc ${"%.1f".format(it)} s") }
            }
            tag(g, text, (b.left * w).toInt(), (b.bottom * h).toInt() + 6, unit * 0.9f)
        }

        // The warning, large, in the middle.
        warn?.let {
            val text = when (it) {
                RoadWarning.COLLISION -> "BRAKE"
                RoadWarning.LANE_LEFT -> "LANE LEFT"
                RoadWarning.LANE_RIGHT -> "LANE RIGHT"
                RoadWarning.TOO_CLOSE -> "TOO CLOSE"
                RoadWarning.MOVED_OFF -> "TRAFFIC MOVING"
            }
            g.font = Font(Font.SANS_SERIF, Font.BOLD, (unit * 2.2f).toInt())
            val fm = g.fontMetrics
            val tw = fm.stringWidth(text)
            val x = (w - tw) / 2
            val y = (h * 0.18f).toInt()
            g.color = if (it == RoadWarning.COLLISION) red else amber
            g.fillRoundRect(x - 24, y - fm.ascent - 8, tw + 48, fm.height + 16, 24, 24)
            g.color = Color(11, 16, 32)
            g.drawString(text, x, y)
        }

        // The figures, top left.
        val lane = result.lane
        val figures = buildString {
            append("f ${result.index}  ${"%.2f".format(result.timeMs / 1000.0)} s")
            result.speedKmh?.let { append("  ${it.toInt()} km/h") }
            append("  lines ${lane.lines}")
            lane.offset?.let { append("  offset ${"%+.2f".format(it)}") }
            result.place?.let { append("  lane ${it.lane} of ${it.lanes}") }
        }
        tag(g, figures, 12, 12 + (unit * 0.9f).toInt(), unit * 0.9f)

        if (ruler) ruler(g, w, h, band, unit)
        g.dispose()
    }

    /**
     * Tenths of the width along the bonnet's row, and the row itself, to read a
     * label's figures off; and the height's fractions down the left edge, to
     * read the horizon's (--horizon) and the bonnet's (--bonnet) off a frame.
     */
    private fun ruler(g: Graphics2D, w: Int, h: Int, band: RoadBand, unit: Float) {
        val y = (band.bonnetY * h).toInt()
        g.color = Color(255, 255, 255, 200)
        g.stroke = BasicStroke(1.5f)
        g.drawLine(0, y, w, y)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, (unit * 0.7f).toInt())
        for (i in 0..10) {
            val x = w * i / 10
            g.drawLine(x, y - 14, x, y + 14)
            g.drawString("%.1f".format(i / 10f), x + 3, y - 18)
        }
        for (i in 4..19) {
            val yy = h * i / 20
            g.drawLine(0, yy, if (i % 2 == 0) 40 else 22, yy)
            if (i % 2 == 0) g.drawString("%.2f".format(i / 20f), 46, yy + 5)
        }
    }

    private fun tag(g: Graphics2D, text: String, x: Int, baseline: Int, size: Float) {
        g.font = Font(Font.SANS_SERIF, Font.BOLD, size.toInt())
        val fm = g.fontMetrics
        g.color = ink
        g.fillRoundRect(x - 6, baseline - fm.ascent - 4, fm.stringWidth(text) + 12, fm.height + 8, 10, 10)
        g.color = Color.WHITE
        g.drawString(text, x, baseline)
    }
}
