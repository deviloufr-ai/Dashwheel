package com.openauto.dash.companion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class HotspotGateTest {
    private fun ip(text: String) = InetAddress.getByName(text)

    // The phone on a home Wi-Fi and mobile data, its hotspot on 192.168.43.1.
    private val guestOn = setOf(ip("192.168.1.24"), ip("10.142.7.3"), ip("2a01:cb00::24"))

    @Test
    fun theHotspotSideIsLetIn() {
        assertTrue(HotspotGate.fromHotspot(ip("192.168.43.1"), guestOn))
        // No network joined at all: the hotspot alone.
        assertTrue(HotspotGate.fromHotspot(ip("192.168.43.1"), emptySet()))
    }

    @Test
    fun aNetworkThePhoneJoinedIsTurnedAway() {
        assertFalse(HotspotGate.fromHotspot(ip("192.168.1.24"), guestOn))
        assertFalse(HotspotGate.fromHotspot(ip("10.142.7.3"), guestOn))
        assertFalse(HotspotGate.fromHotspot(ip("2a01:cb00::24"), guestOn))
    }

    @Test
    fun thePhoneItselfIsTurnedAway() {
        assertFalse(HotspotGate.fromHotspot(ip("127.0.0.1"), guestOn))
        assertFalse(HotspotGate.fromHotspot(ip("::1"), guestOn))
        assertFalse(HotspotGate.fromHotspot(null, guestOn))
    }
}
