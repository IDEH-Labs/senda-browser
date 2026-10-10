package org.senda.browser.core.cast

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Tells whether an address belongs to the phone itself or to the local network (router, NAS, other devices).
 * Used so that a page cannot make Senda request addresses on the user's network on its behalf.
 */
object LocalNetwork {
    private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home", ".internal", ".home.arpa", ".localdomain")
    private val IPV4_LITERAL = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    /**
     * True if [host] is local. IP literals and local names are recognised without any lookup; other names are
     * resolved only when [resolve] is true (without a proxy: with Tor or a proxy, resolving here would send the
     * DNS query outside the tunnel).
     */
    fun isLocalHost(host: String, resolve: Boolean): Boolean {
        val h = host.trim().trimStart('[').trimEnd(']').trimEnd('.').lowercase()
        if (h.isEmpty()) return true
        if (h == "localhost" || h.endsWith(".localhost") || LOCAL_SUFFIXES.any { h.endsWith(it) }) return true
        if (IPV4_LITERAL.matches(h) || h.contains(':')) {
            // An IP literal: getByName parses it without asking any DNS server
            return runCatching { isLocalAddress(InetAddress.getByName(h)) }.getOrDefault(true)
        }
        if (!h.contains('.')) return true // A bare name ("router", "nas") only exists on the local network
        if (!resolve) return false
        return runCatching { InetAddress.getAllByName(h).any(::isLocalAddress) }.getOrDefault(false)
    }

    fun isLocalAddress(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isAnyLocalAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return true
        val b = address.address
        return when (address) {
            // 100.64.0.0/10 (carrier-grade NAT)
            is Inet4Address -> (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 64
            // fc00::/7 (unique local addresses)
            is Inet6Address -> (b[0].toInt() and 0xFE) == 0xFC
            else -> false
        }
    }
}
