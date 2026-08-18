package com.engfred.musicplayer.core.util

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * Utility for resolving the local device's IPv4 address on the active LAN / Wi-Fi network.
 */
object LanAddressUtil {

    /**
     * Resolves the primary local IPv4 address of the device on Wi-Fi or Hotspot.
     * Returns null if no active non-loopback IPv4 interface is found.
     */
    fun getLocalIpAddress(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            // First pass: look specifically for Wi-Fi / AP interfaces (wlan, ap, eth)
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val name = intf.name.lowercase()
                if (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("eth")) {
                    val addrs = Collections.list(intf.inetAddresses)
                    for (addr in addrs) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            val hostAddress = addr.hostAddress
                            if (!hostAddress.isNullOrEmpty() && hostAddress != "127.0.0.1") {
                                return hostAddress
                            }
                        }
                    }
                }
            }

            // Second pass: fallback to any active non-loopback IPv4 interface
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val hostAddress = addr.hostAddress
                        if (!hostAddress.isNullOrEmpty() && hostAddress != "127.0.0.1") {
                            return hostAddress
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore network inspection failures
        }
        return null
    }
}
