package com.openauto.dash

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Whether a next / previous from the tile was heard by the player ([skipAnswered]). */
class MediaSkipTest {

    private val radio = MediaState(title = "99.9 MHz", artist = "FM1", isPlaying = true, hasMedia = true)

    @Test
    fun theSameStationStillPlayingIsAStepUnheard() {
        assertFalse(skipAnswered(radio, radio.copy()))
        // Playing or not, a position or a cover is no step.
        assertFalse(skipAnswered(radio, radio.copy(isPlaying = false, durationMs = 5_000L)))
    }

    @Test
    fun anotherStationOrSongIsAStepHeard() {
        assertTrue(skipAnswered(radio, radio.copy(title = "100.4 MHz")))
        assertTrue(skipAnswered(radio, radio.copy(title = "France Inter", artist = "99.9 MHz · FM1")))
        assertTrue(skipAnswered(radio, radio.copy(station = "Radio Si")))
        // The player went quiet: something happened, nothing to send again.
        assertTrue(skipAnswered(radio, MediaState()))
    }
}
