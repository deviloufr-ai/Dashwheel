package com.openauto.dash.companion

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Text shared to the car that only looks like a position. */
class SharedNumbersTest {
    @Test
    fun aNumberWithACommaIsNotAPlace() {
        // A French decimal, a price, a score: typed on the car as text.
        assertNull(SharedPlaces.parse("12,5"))
        assertNull(SharedPlaces.parse("19,99"))
        assertNull(SharedPlaces.parse("3, 2"))
    }

    @Test
    fun aPositionWithItsDecimalsStillIs() {
        assertTrue(SharedPlaces.parse("48.8583, 2.2945") is SharedPlace.Found)
        assertTrue(SharedPlaces.parse("-33.8568,151.2153") is SharedPlace.Found)
    }
}
