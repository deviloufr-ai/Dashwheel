package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Test

class PanoramaSkinTest {
    @Test
    fun theWarmZoneFollowsTheCarsRedLine() {
        // 4 500 of 7 000 rpm on a 20-segment band: the 13th segment and up.
        assertEquals(13, warmSegment(20, 4_500))
        assertEquals(13, warmSegment(20, null))
        assertEquals(17, warmSegment(20, 6_000))
        // Never below the first segment, never past the last.
        assertEquals(1, warmSegment(20, 0))
        assertEquals(20, warmSegment(20, 9_000))
    }
}
