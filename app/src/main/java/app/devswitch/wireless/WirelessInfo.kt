// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lennox

package app.devswitch.wireless

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

/** The active connection, as far as an unprivileged app can read it. */
data class NetworkStatus(
    val ipv4Addresses: List<String>,
    val onWifi: Boolean,
    val onEthernet: Boolean,
) {
    val connected: Boolean get() = ipv4Addresses.isNotEmpty()

    /** A private LAN address to show as "this device", preferred over a VPN or CGNAT tunnel. */
    val primaryIpv4: String?
        get() = ipv4Addresses.firstOrNull { it.isPrivateLan() } ?: ipv4Addresses.firstOrNull()

    fun owns(host: String?): Boolean = host != null && host in ipv4Addresses
}

private fun String.isPrivateLan(): Boolean {
    if (startsWith("192.168.") || startsWith("10.")) return true
    if (startsWith("172.")) {
        val second = substringAfter('.').substringBefore('.').toIntOrNull() ?: return false
        return second in 16..31
    }
    return false
}

/**
 * Reads the device's own IPv4 addresses. It enumerates every non-loopback interface rather than
 * only the default route, so the Wi-Fi address adb advertises is still found when a VPN owns the
 * default network. Needs no permission and no location access.
 */
class WirelessInfo(context: Context) {
    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    fun current(): NetworkStatus {
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val addresses = buildList {
            runCatching {
                for (nif in NetworkInterface.getNetworkInterfaces()) {
                    if (!nif.isUp || nif.isLoopback) continue
                    for (address in nif.inetAddresses) {
                        if (address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress) {
                            address.hostAddress?.let { add(it) }
                        }
                    }
                }
            }
        }.distinct()
        return NetworkStatus(
            ipv4Addresses = addresses,
            onWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
            onEthernet = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true,
        )
    }
}
