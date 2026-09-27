package com.openauto.dash

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

/** Where a tile's panel stands: the driver's side, unless the tile is under it there. */
class TilePanelSideTest {

    private val area = Rect(0f, 0f, 1280f, 640f)
    private val panel = 360f

    private fun side(tile: Rect, driverOnRight: Boolean = false, vertical: Boolean = false, area: Rect = this.area) =
        panelSide(tile, area, panel, vertical, driverOnRight)

    @Test
    fun opensOnTheDriversSideWhenTheTileIsClear() {
        val middle = Rect(500f, 0f, 900f, 300f)
        assertEquals(PanelSide.LEFT, side(middle))
        assertEquals(PanelSide.RIGHT, side(middle, driverOnRight = true))
    }

    @Test
    fun movesAcrossWhenTheTileIsUnderItOnTheDriversSide() {
        assertEquals(PanelSide.RIGHT, side(Rect(0f, 0f, 530f, 360f)))
        assertEquals(PanelSide.LEFT, side(Rect(960f, 280f, 1280f, 460f), driverOnRight = true))
    }

    @Test
    fun aTileAcrossTheWholeWidthKeepsTheSideCoveringLess() {
        // Under both sides: the left covers 360 px of it, the right only 280.
        assertEquals(PanelSide.RIGHT, side(Rect(0f, 0f, 1000f, 200f)))
        // Evenly under both: the driver's side wins.
        assertEquals(PanelSide.LEFT, side(Rect(0f, 0f, 1280f, 200f)))
        assertEquals(PanelSide.RIGHT, side(Rect(0f, 0f, 1280f, 200f), driverOnRight = true))
    }

    @Test
    fun uprightItIsAtTheBottomUnlessTheTileIsThere() {
        val upright = Rect(0f, 0f, 720f, 1100f)
        assertEquals(PanelSide.BOTTOM, side(Rect(0f, 280f, 400f, 560f), vertical = true, area = upright))
        assertEquals(PanelSide.TOP, side(Rect(0f, 840f, 300f, 1020f), vertical = true, area = upright))
    }
}
