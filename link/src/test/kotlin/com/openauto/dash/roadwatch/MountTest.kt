package com.openauto.dash.roadwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

class MountTest {
    private fun rad(deg: Double) = Math.toRadians(deg)

    @Test
    fun aPhoneSidewaysAndUprightIsLevelAndAimedAtTheMiddle() {
        // Sideways (the screen turned 90°): its right edge, +x, points up.
        val got = Mount.read(floatArrayOf(9.81f, 0f, 0f), 90, 46f)
        assertEquals(0f, got.rollDeg, 0.01f)
        assertEquals(0f, got.pitchDeg, 0.01f)
        assertEquals(0.5f, got.horizonY, 0.001f)
        assertTrue(got.level)
    }

    @Test
    fun turnedAnticlockwiseItsRightEndIsHigh() {
        val a = rad(5.0)
        val got = Mount.read(floatArrayOf(cos(a).toFloat(), -sin(a).toFloat(), 0f), 90, 46f)
        assertEquals(5f, got.rollDeg, 0.01f)
        assertFalse(got.level)
        // The other way up (270°), the same lean reads the same.
        val other = Mount.read(floatArrayOf(-cos(a).toFloat(), sin(a).toFloat(), 0f), 270, 46f)
        assertEquals(5f, other.rollDeg, 0.01f)
    }

    @Test
    fun aimedDownTheHorizonRisesInThePicture() {
        val a = rad(10.0)
        val got = Mount.read(floatArrayOf(cos(a).toFloat(), 0f, sin(a).toFloat()), 90, 46f)
        assertEquals(10f, got.pitchDeg, 0.01f)
        assertEquals(0.5f - tan(a).toFloat() / (2 * tan(rad(23.0)).toFloat()), got.horizonY, 0.001f)
        assertFalse("aimed this far down, the horizon is too high: the picture is all road", got.aimed)
        val b = rad(5.0)
        assertTrue("a little above the middle is where it belongs", Mount.read(floatArrayOf(cos(b).toFloat(), 0f, sin(b).toFloat()), 90, 46f).aimed)
        val up = Mount.read(floatArrayOf(cos(a).toFloat(), 0f, -sin(a).toFloat()), 90, 46f)
        assertFalse("aimed at the sky", up.aimed)
    }

    @Test
    fun turningInTheHolderIsMeasured() {
        val a = rad(8.0)
        assertEquals(8f, Mount.turnedDeg(floatArrayOf(1f, 0f, 0f), floatArrayOf(cos(a).toFloat(), 0f, sin(a).toFloat())), 0.01f)
    }

    /** A 160 x 120 picture: sky, road, and [dashboard] of its height dark at the bottom, [pillar] of its width dark on the left. */
    private fun picture(dashboard: Float, pillar: Float, light: Int = 1): Luma {
        val img = Luma(160, 120)
        for (y in 0 until 120) for (x in 0 until 160) {
            val v = when {
                y >= (1 - dashboard) * 120 -> 25
                x < pillar * 160 -> 30
                y < 50 -> 190
                else -> 120
            }
            img.px[y * 160 + x] = v / light
        }
        return img
    }

    @Test
    fun theDashboardIsTheDarkBandAlongTheBottom() {
        assertEquals(0.75f, Mount.dashboardTop(picture(dashboard = 0.25f, pillar = 0f), 0.42f)!!, 0.01f)
        assertEquals(1f, Mount.dashboardTop(picture(dashboard = 0f, pillar = 0f), 0.42f)!!, 0.01f)
    }

    @Test
    fun thePillarIsTheDarkBandAlongASide() {
        assertEquals(0.15f, Mount.pillar(picture(dashboard = 0.1f, pillar = 0.15f), 0.42f)!!, 0.01f)
        assertEquals(0f, Mount.pillar(picture(dashboard = 0.1f, pillar = 0f), 0.42f)!!, 0.01f)
    }

    @Test
    fun aDarkPictureTellsNothing() {
        assertNull(Mount.dashboardTop(picture(dashboard = 0.2f, pillar = 0f, light = 5), 0.42f))
        assertNull(Mount.pillar(picture(dashboard = 0.2f, pillar = 0.1f, light = 5), 0.42f))
    }
}
