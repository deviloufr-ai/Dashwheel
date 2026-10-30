package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Test

/** The size Dashwheel's own reverse picture asks of the camera, against what the camera offers (#108). */
class ReversePictureSizeTest {

    @Test
    fun theRomAppsStreamWhenOffered() {
        assertEquals(1920 to 1080, pictureSize(listOf(640 to 480, 1280 to 720, 1920 to 1080)))
    }

    @Test
    fun theLargestThatFitsWhenNot() {
        // The car app's camera set to 720p: the 1080p buffer would leave the session unconfigured.
        assertEquals(1280 to 720, pictureSize(listOf(640 to 480, 1280 to 720)))
        // An analog input of 720 x 480, no 1080p in sight.
        assertEquals(720 to 480, pictureSize(listOf(720 to 480, 352 to 288)))
        // Only sizes beyond 1080p: the smallest of them.
        assertEquals(2560 to 1440, pictureSize(listOf(3840 to 2160, 2560 to 1440)))
        // Wide but short, or tall but narrow: both sides have to fit.
        assertEquals(1600 to 900, pictureSize(listOf(1600 to 900, 2048 to 1080, 1920 to 1200)))
    }

    @Test
    fun nothingListedKeepsTheOldAsk() {
        assertEquals(1920 to 1080, pictureSize(emptyList()))
        assertEquals(1280 to 720, pictureSize(emptyList(), 1280, 720))
    }
}
