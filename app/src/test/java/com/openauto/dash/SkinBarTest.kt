package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkinBarTest {

    // --- Weather with nothing to show ---

    @Test
    fun `no position reads as no GPS, whatever the last answer was`() {
        assertEquals(WeatherWait.NO_GPS, weatherWait(hasLocation = false, error = null))
        assertEquals(WeatherWait.NO_GPS, weatherWait(hasLocation = false, error = "timeout"))
    }

    @Test
    fun `a failed answer reads as unavailable, even without a detail`() {
        assertEquals(WeatherWait.UNAVAILABLE, weatherWait(hasLocation = true, error = "HTTP 503"))
        assertEquals(WeatherWait.UNAVAILABLE, weatherWait(hasLocation = true, error = ""))
    }

    @Test
    fun `a position and no failure yet is still loading`() {
        assertEquals(WeatherWait.LOADING, weatherWait(hasLocation = true, error = null))
    }

    // --- The OBD mark's tap ---

    @Test
    fun `an error with the phone holding the adapter opens the steps`() {
        assertTrue(obdTapOpensHelp(ObdConnectionState.ERROR, phoneBlocking = true))
    }

    @Test
    fun `any other tap is a plain try`() {
        assertFalse(obdTapOpensHelp(ObdConnectionState.ERROR, phoneBlocking = false))
        assertFalse(obdTapOpensHelp(ObdConnectionState.DISCONNECTED, phoneBlocking = true))
        assertFalse(obdTapOpensHelp(ObdConnectionState.CONNECTING, phoneBlocking = true))
        assertFalse(obdTapOpensHelp(ObdConnectionState.CONNECTED, phoneBlocking = true))
    }

    // --- Who gets the bar's room ---

    @Test
    fun `the menu and the OBD mark come first, a skin's outside temperature last`() {
        val ranks = listOf(BarRank.MORE, BarRank.OBD, BarRank.CLOCK, BarRank.ALERTS, BarRank.DEMO, BarRank.SETUP, BarRank.PHONE, BarRank.TEMP)
        assertEquals(ranks.sorted(), ranks)
        assertEquals(ranks.size, ranks.toSet().size)
    }
}
