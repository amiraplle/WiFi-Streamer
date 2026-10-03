package com.example.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    /**
     * Retrieves the current active IPv4 address on the Wi-Fi or local network interface.
     * Returns null if not connected to any local network.
     */
    fun getLocalIpAddress(context: Context): String? {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null) {
                val activeNetwork = cm.activeNetwork
                val linkProperties: LinkProperties? = cm.getLinkProperties(activeNetwork)
                if (linkProperties != null) {
                    for (linkAddress in linkProperties.linkAddresses) {
                        val address = linkAddress.address
                        if (address is Inet4Address && !address.isLoopbackAddress) {
                            val ip = address.hostAddress
                            if (!ip.isNullOrBlank() && !ip.startsWith("127.")) {
                                return ip
                            }
                        }
                    }
                }
            }

            // Fallback: iterate active network interfaces
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (!ip.startsWith("127.")) {
                            return ip
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }
}
