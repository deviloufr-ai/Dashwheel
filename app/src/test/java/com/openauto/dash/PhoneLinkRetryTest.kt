package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Test

/** How often the unit looks for the phone on a Wi-Fi where nobody answers. */
class PhoneLinkRetryTest {

    @Test
    fun triesSlowDownToTwoMinutesWhileNobodyAnswers() {
        assertEquals(10_000L, PhoneLink.retryDelay(0))
        assertEquals(10_000L, PhoneLink.retryDelay(1))
        assertEquals(20_000L, PhoneLink.retryDelay(2))
        assertEquals(40_000L, PhoneLink.retryDelay(3))
        assertEquals(80_000L, PhoneLink.retryDelay(4))
        assertEquals(120_000L, PhoneLink.retryDelay(5))
        assertEquals(120_000L, PhoneLink.retryDelay(500))
    }
}
