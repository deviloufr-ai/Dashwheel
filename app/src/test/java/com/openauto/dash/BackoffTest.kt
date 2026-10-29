package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Test

class BackoffTest {
    @Test
    fun doublesFromTheBaseUpToTheCap() {
        assertEquals(60_000L, Backoff.doubling(60_000L, 1_800_000L, 0))
        assertEquals(60_000L, Backoff.doubling(60_000L, 1_800_000L, 1))
        assertEquals(120_000L, Backoff.doubling(60_000L, 1_800_000L, 2))
        assertEquals(240_000L, Backoff.doubling(60_000L, 1_800_000L, 3))
        assertEquals(960_000L, Backoff.doubling(60_000L, 1_800_000L, 5))
        assertEquals(1_800_000L, Backoff.doubling(60_000L, 1_800_000L, 6))
        assertEquals(1_800_000L, Backoff.doubling(60_000L, 1_800_000L, 7))
    }

    @Test
    fun aRunOfFailuresCannotOverflow() {
        assertEquals(60_000L, Backoff.doubling(8_000L, 60_000L, 100))
        assertEquals(60_000L, Backoff.doubling(8_000L, 60_000L, Int.MAX_VALUE))
    }

    @Test
    fun theCapHoldsEvenBelowTheBase() {
        assertEquals(5_000L, Backoff.doubling(8_000L, 5_000L, 1))
    }
}
