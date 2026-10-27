package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How far the phone's pointer goes on the car's screen (PhoneMouse.kt). */
class PhoneMouseTest {
    @Test
    fun aSlowMoveIsPrecise() {
        assertEquals(1.2f, pointerGain(0f), 0.001f)
        assertTrue(pointerGain(1f) < 1.4f)
    }

    @Test
    fun aQuickSwipeGoesFurtherButNotWithoutLimit() {
        assertTrue(pointerGain(15f) > pointerGain(3f))
        assertEquals(3f, pointerGain(18f), 0.001f)
        assertEquals(3f, pointerGain(500f), 0.001f)
    }
}
