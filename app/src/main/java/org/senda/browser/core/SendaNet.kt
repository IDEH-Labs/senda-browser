package org.senda.browser.core

import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * Connections Senda makes on its own, outside Gecko (news, icons, AI, sync).
 * They use the same Tor or proxy as browsing: otherwise, with Tor on, these requests went out
 * directly, with the real IP. If Tor is not ready the connection fails; it never goes out unprotected.
 *
 * Local network addresses (a TV or WebDAV at home) go direct: an external
 * proxy cannot reach them.
 */
object SendaNet {

    /** Generic Firefox for Android User-Agent: one specific to Senda would identify its users. */
    const val USER_AGENT = "Mozilla/5.0 (Android 15; Mobile; rv:157.0) Gecko/157.0 Firefox/157.0"

    fun open(url: String): HttpURLConnection {
        val target = URL(url)
        val conn = target.openConnection(proxyFor(target.host)) as HttpURLConnection
        conn.setRequestProperty("User-Agent", USER_AGENT)
        return conn
    }

    /** The same Tor or proxy as browsing (direct on the local network). The Anthropic client uses it too. */
    fun proxyFor(host: String?): Proxy {
        val prefs = SendaGeckoEngine.appContext?.let { PreferencesManager(it) } ?: return Proxy.NO_PROXY
        if (host == null || isLocal(host)) return Proxy.NO_PROXY
        return when (prefs.proxyMode) {
            // Unresolved address: the proxy resolves the name (remote DNS, no leaks)
            "TOR_ORBOT" -> Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", 9050))
            "CUSTOM_SOCKS5" -> Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(prefs.proxyHost, prefs.proxyPort))
            "CUSTOM_HTTP" -> Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(prefs.proxyHost, prefs.proxyPort))
            else -> Proxy.NO_PROXY
        }
    }

    fun isLocal(host: String): Boolean {
        val h = host.lowercase().trim('[', ']')
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") || h == "::1") return true
        val parts = h.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        return parts[0] == 10 || parts[0] == 127 ||
            (parts[0] == 192 && parts[1] == 168) ||
            (parts[0] == 172 && parts[1] in 16..31) ||
            (parts[0] == 169 && parts[1] == 254)
    }
}
