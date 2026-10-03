package com.openauto.dash.display

import com.openauto.dash.link.DISPLAY_BEACON_PORT
import com.openauto.dash.link.DISPLAY_BEACON_PREFIX
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Calls out on every network, once a second, while no head unit is [linked],
 * so the head unit dials this display as soon as both are on the hotspot.
 */
class Beacon(private val id: String, private val linked: () -> Boolean) {

    fun start() {
        Thread({ loop() }, "display-beacon").apply { isDaemon = true }.start()
    }

    private fun loop() {
        val payload = (DISPLAY_BEACON_PREFIX + id).toByteArray(Charsets.US_ASCII)
        val socket = runCatching { DatagramSocket().apply { broadcast = true } }
            .onFailure { log("no beacon: ${it.message}") }
            .getOrNull() ?: return
        socket.use {
            while (true) {
                if (!linked()) {
                    // No network yet, or one just gone: the next round tries again.
                    for (target in targets()) runCatching { socket.send(DatagramPacket(payload, payload.size, target, DISPLAY_BEACON_PORT)) }
                }
                try {
                    Thread.sleep(EVERY_MS)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }

    /** Each network's broadcast address, and the all-ones one for good measure. */
    private fun targets(): List<InetAddress> =
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses }
                .mapNotNull { it.broadcast }
        }.getOrDefault(emptyList()) + InetAddress.getByAddress(byteArrayOf(-1, -1, -1, -1))

    private companion object {
        const val EVERY_MS = 1_000L
    }
}
