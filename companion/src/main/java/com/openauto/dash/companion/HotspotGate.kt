package com.openauto.dash.companion

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.net.InetAddress
import java.net.Socket

/**
 * Keeps the link to the phone's own hotspot. The server socket listens on
 * every address the phone has, so a connection is looked at when it arrives:
 * the address it dialled tells which side of the phone it came from.
 *
 * The networks the phone is itself a guest on (a Wi-Fi it joined, mobile
 * data, a VPN) are the ones the system lists; its hotspot, and USB or
 * Bluetooth tethering, are not among them. A connection that reached one of
 * the listed addresses came from a café's Wi-Fi or the mobile network, where
 * no head unit is, and is turned away before the pairing is even checked.
 */
internal object HotspotGate {

    /** False for a [client] that dialled the phone on a network it is a guest on, or from the phone itself. */
    fun allows(context: Context, client: Socket): Boolean {
        // A test build is reached through adb's port forwarding, on the loopback address.
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) return true
        val guestOn = guestAddresses(context) ?: return true
        return fromHotspot(client.localAddress, guestOn)
    }

    /** The rule itself: [dialled] is the phone's address the connection came in on. */
    fun fromHotspot(dialled: InetAddress?, guestOn: Collection<InetAddress>): Boolean =
        dialled != null && !dialled.isLoopbackAddress && !dialled.isAnyLocalAddress && dialled !in guestOn

    /**
     * The phone's addresses on the networks it joined; null when the system can't be asked (then nothing is refused).
     * Only those that lead to the internet: Android 15 lists the phone's own hotspot too, as a local network,
     * and counting it turned the car away on every dial.
     */
    @Suppress("DEPRECATION") // allNetworks: every network, not only the default one.
    private fun guestAddresses(context: Context): Set<InetAddress>? = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        cm.allNetworks.filter { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@filter false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !(Build.VERSION.SDK_INT >= 35 && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_LOCAL_NETWORK))
        }.flatMapTo(HashSet()) { network ->
            cm.getLinkProperties(network)?.linkAddresses?.map { it.address }.orEmpty()
        }
    }.getOrNull()
}
