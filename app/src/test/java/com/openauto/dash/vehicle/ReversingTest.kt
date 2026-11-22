package com.openauto.dash.vehicle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReversingTest {
    @Test fun reverseWithIgnitionOnOrUnknown() {
        assertTrue(reversing(true, true))
        assertTrue(reversing(true, null))
    }

    @Test fun ignitionOffEndsReverse() = assertFalse(reversing(true, false))

    @Test fun noReverse() {
        assertFalse(reversing(false, true))
        assertFalse(reversing(null, null))
    }
}
