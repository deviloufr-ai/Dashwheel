package com.openauto.dash

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SameTrackTest {
    @Test fun `the unit's session naming the phone's track is the phone's`() = assertTrue(sameTrack("Song A", " song a "))
    @Test fun `another track is not`() = assertFalse(sameTrack("Song A", "Song B"))
    @Test fun `no phone or an untitled session is not`() {
        assertFalse(sameTrack(null, "Song A"))
        assertFalse(sameTrack("", ""))
        assertFalse(sameTrack("Song A", null))
    }
}
