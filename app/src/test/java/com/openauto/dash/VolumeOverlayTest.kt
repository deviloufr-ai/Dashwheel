package com.openauto.dash

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeOverlayTest {

    private val at8 = VolumeShown(level = 8, max = 40, muted = false, source = VolumeSource.MEDIA)

    @Test
    fun theSameLevelSentAgainWhenAVoiceSpeaksShowsNothing() {
        // The firmware's "changed" when Dashwheel's voice or Gemini takes the sound: still 8.
        assertFalse(worthShowing(at8, at8, carKeys = false))
    }

    @Test
    fun aRealChangeShows() {
        assertTrue(worthShowing(at8, at8.copy(level = 9), carKeys = false))
        assertTrue(worthShowing(at8, at8.copy(muted = true), carKeys = false))
        assertTrue(worthShowing(at8, at8.copy(source = VolumeSource.RADIO), carKeys = false))
    }

    @Test
    fun theCarsVolumeKeysAlwaysShow() {
        // At the end of the scale the level stays the same, and the driver still wants to see it.
        assertTrue(worthShowing(at8, at8, carKeys = true))
    }
}
