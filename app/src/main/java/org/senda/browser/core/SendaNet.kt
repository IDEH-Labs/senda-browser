package org.senda.browser.core

import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * Conexiones que Senda hace por su cuenta, fuera de Gecko (noticias, iconos, IA, sincronización).
 * Usan el mismo Tor o proxy que la navegación: si no, con Tor activado estas peticiones salían
 * directas, con la IP real. Si Tor no está listo la conexión falla, nunca sale sin protección.
 *
 * Las direcciones de la red local (TV o WebDAV en casa) van directas: un proxy
 * externo no puede alcanzarlas.
 */
object SendaNet {

    /** User-Agent genérico de Firefox para Android: uno propio de Senda identificaría a sus usuarios. */
    const val USER_AGENT = "Mozilla/5.0 (Android 15; Mobile; rv:157.0) Gecko/157.0 Firefox/157.0"

    fun open(url: String): HttpURLConnection {
        val target = URL(url)
        val conn = target.openConnection(proxyFor(target.host)) as HttpURLConnection
        conn.setRequestProperty("User-Agent", USER_AGENT)
        return conn
    }

    /** El mismo Tor o proxy que la navegación (directo en la red local). También lo usa el cliente de Anthropic. */
    fun proxyFor(host: String?): Proxy {
        val prefs = SendaGeckoEngine.appContext?.let { PreferencesManager(it) } ?: return Proxy.NO_PROXY
        if (host == null || isLocal(host)) return Proxy.NO_PROXY
        return when (prefs.proxyMode) {
            // Dirección sin resolver: el nombre lo resuelve el proxy (DNS remoto, sin fugas)
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
