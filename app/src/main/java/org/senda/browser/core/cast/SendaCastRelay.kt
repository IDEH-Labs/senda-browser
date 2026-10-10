package org.senda.browser.core.cast

import android.util.Base64
import android.util.Log
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * Local relay for the video shown on the TV ([org.senda.browser.ui.components.TvPresentationHost]). Android's
 * player does not use Senda's proxy and does not know the Referer many sites require: the relay requests the
 * video the way the page did (through the same proxy or Tor) and passes it on without re-encoding.
 *
 * It only serves the given address (127.0.0.1) and paths with a random secret, and it closes when done.
 * HLS playlists are rewritten so their chunks also go through here. It does not fetch addresses on the user's
 * network (router, NAS…) unless the page or the video itself is there: otherwise a page's playlist could make
 * Senda send requests to them.
 */
class SendaCastRelay private constructor(
    private val server: ServerSocket,
    private val tvAddress: InetAddress,
    private val referer: String?,
    private val proxy: Proxy
) {
    private val secret = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "senda-cast-relay").apply { isDaemon = true } }
    @Volatile private var closed = false
    private val resolveLocally = proxy == Proxy.NO_PROXY
    /** The page's and the video's own hosts are always allowed (a home media server under its own name). */
    private val trustedHosts: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet<String>().apply {
        referer?.let { hostOf(it) }?.let(::add)
    }
    /** True when the user is casting from their own network: then other local addresses are fine too. */
    @Volatile private var allowLocal = referer?.let { isLocalUrl(it) } ?: false

    private val baseUrl: String
        get() {
            val host = server.inetAddress.hostAddress?.substringBefore('%') ?: "127.0.0.1"
            return "http://${if (server.inetAddress is java.net.Inet6Address) "[$host]" else host}:${server.localPort}/$secret/"
        }

    /** Local address given to the TV for [original], the video the page is playing. */
    fun urlFor(original: String): String {
        hostOf(original)?.let(trustedHosts::add)
        if (isLocalUrl(original)) allowLocal = true
        return relayUrl(original)
    }

    private fun hostOf(url: String): String? = runCatching { URL(url).host.lowercase() }.getOrNull()?.ifBlank { null }

    /** Without any DNS lookup: this runs on the UI thread. */
    private fun isLocalUrl(url: String): Boolean =
        hostOf(url)?.let { LocalNetwork.isLocalHost(it, resolve = false) } ?: false

    /** Whether the relay may fetch [url]: http(s) only, and the user's network only when casting from it. */
    private fun mayFetch(url: URL): Boolean =
        (url.protocol == "http" || url.protocol == "https") &&
            (allowLocal || url.host.lowercase() in trustedHosts || !LocalNetwork.isLocalHost(url.host, resolveLocally))

    private fun relayUrl(original: String): String {
        val encoded = Base64.encodeToString(original.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        // Some TVs choose the player by the extension: the original file name is kept
        val name = original.substringBefore('?').substringAfterLast('/').ifBlank { "video" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)
        return "$baseUrl$encoded/$name"
    }

    private fun start() {
        Thread({
            while (!closed) {
                val socket = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                if (socket.inetAddress != tvAddress) {
                    // Nobody else on the network can use the relay
                    try { socket.close() } catch (_: Exception) {}
                    continue
                }
                pool.execute { handle(socket) }
            }
        }, "senda-cast-relay-accept").apply { isDaemon = true }.start()
    }

    fun close() {
        closed = true
        try { server.close() } catch (_: Exception) {}
        pool.shutdownNow()
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = 30_000
                val input = BufferedInputStream(s.getInputStream())
                val requestLine = readLine(input) ?: return
                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: return
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                }
                val parts = requestLine.split(' ')
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val out = s.getOutputStream()
                val path = parts[1]
                val prefix = "/$secret/"
                if ((method != "GET" && method != "HEAD") || !path.startsWith(prefix)) {
                    writeStatus(out, 404, "Not Found", emptyMap())
                    return
                }
                val encoded = path.removePrefix(prefix).substringBefore('/')
                val original = try {
                    String(Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
                } catch (_: IllegalArgumentException) {
                    writeStatus(out, 404, "Not Found", emptyMap())
                    return
                }
                if (!original.startsWith("http://") && !original.startsWith("https://")) {
                    writeStatus(out, 404, "Not Found", emptyMap())
                    return
                }
                relay(original, method, headers["range"], out)
            } catch (e: Exception) {
                // The TV drops connections often (seeking, quality changes): it is not an error
                if (!closed) Log.d(TAG, "Relé: ${e.message}")
            }
        }
    }

    private fun relay(original: String, method: String, range: String?, out: OutputStream) {
        // Redirects are followed here, one by one, so that each destination is checked as well
        var target = URL(original)
        var conn: HttpURLConnection
        var hops = 0
        while (true) {
            if (!mayFetch(target)) {
                Log.w(TAG, "Relé: dirección local rechazada")
                writeStatus(out, 403, "Forbidden", emptyMap())
                return
            }
            conn = target.openConnection(proxy) as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", org.senda.browser.core.SendaNet.USER_AGENT)
            referer?.let { conn.setRequestProperty("Referer", it) }
            if (range != null) conn.setRequestProperty("Range", range)
            val location = if (conn.responseCode in 300..399) conn.getHeaderField("Location") else null
            if (location == null) break
            conn.disconnect()
            if (++hops > 5) {
                writeStatus(out, 502, "Too Many Redirects", emptyMap())
                return
            }
            target = URL(target, location)
        }
        try {
            val code = conn.responseCode
            val type = conn.contentType?.substringBefore(';')?.trim() ?: ""
            val isPlaylist = type.contains("mpegurl", ignoreCase = true) ||
                original.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
            if (code !in 200..299) {
                writeStatus(out, code, "Upstream", emptyMap())
                return
            }
            if (isPlaylist) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                // The final URL (after redirects) is the base for the playlist's relative paths
                val body = rewritePlaylist(text, target.toString()).toByteArray(Charsets.UTF_8)
                writeStatus(out, 200, "OK", mapOf(
                    "Content-Type" to "application/vnd.apple.mpegurl",
                    "Content-Length" to body.size.toString(),
                    "Cache-Control" to "no-cache"
                ))
                if (method == "GET") out.write(body)
                out.flush()
                return
            }
            val responseHeaders = linkedMapOf(
                "Content-Type" to type.ifBlank { "video/mp4" },
                "Accept-Ranges" to "bytes"
            )
            conn.getHeaderField("Content-Length")?.let { responseHeaders["Content-Length"] = it }
            conn.getHeaderField("Content-Range")?.let { responseHeaders["Content-Range"] = it }
            writeStatus(out, code, if (code == 206) "Partial Content" else "OK", responseHeaders)
            if (method == "GET") copy(conn.inputStream, out)
            out.flush()
        } finally {
            conn.disconnect()
        }
    }

    /** Every address in the playlist (variants, chunks, keys, subtitles) also goes through the relay. */
    private fun rewritePlaylist(text: String, base: String): String {
        val baseUrl = URL(base)
        fun relayed(uri: String): String = try {
            relayUrl(URL(baseUrl, uri.trim()).toString())
        } catch (_: Exception) {
            uri
        }
        val uriAttribute = Regex("URI=\"([^\"]+)\"")
        return text.lineSequence().joinToString("\n") { line ->
            when {
                line.isBlank() -> line
                line.startsWith("#") -> uriAttribute.replace(line) { m -> "URI=\"${relayed(m.groupValues[1])}\"" }
                else -> relayed(line)
            }
        }
    }

    private fun copy(input: InputStream, out: OutputStream) {
        input.use {
            val buffer = ByteArray(64 * 1024)
            while (!closed) {
                val n = it.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
        }
    }

    private fun writeStatus(out: OutputStream, code: Int, reason: String, headers: Map<String, String>) {
        val sb = StringBuilder("HTTP/1.1 $code $reason\r\n")
        headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        if (!headers.containsKey("Content-Length") && code !in 200..299) sb.append("Content-Length: 0\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8192) return null
            sb.append(c.toChar())
        }
    }

    companion object {
        private const val TAG = "SendaCastRelay"

        /** The same path as browsing (see senda_proxy/background.js): with Tor the video also goes through Tor. */
        fun proxyFrom(prefs: org.senda.browser.core.PreferencesManager): Proxy = when (prefs.proxyMode) {
            "TOR_ORBOT" -> Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", 9050))
            "CUSTOM_SOCKS5" -> Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(prefs.proxyHost, prefs.proxyPort))
            "CUSTOM_HTTP" -> Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(prefs.proxyHost, prefs.proxyPort))
            else -> Proxy.NO_PROXY
        }

        /**
         * Opens the relay on the network interface that reaches [tvAddress] (the home Wi‑Fi), on a free port.
         * With a TV it must be called off the main thread; with [tvAddress] 127.0.0.1 (the phone's player) it need not.
         */
        fun open(tvAddress: InetAddress, referer: String?, proxy: Proxy): SendaCastRelay {
            // For the phone's own player 127.0.0.1 is enough (and so there is no networking on the main thread)
            val local = if (tvAddress.isLoopbackAddress) tvAddress else java.net.DatagramSocket().use { probe ->
                // Without sending anything: it only asks the system which own IP it would use to talk to the TV
                probe.connect(tvAddress, 1900)
                probe.localAddress
            }
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress(local, 0))
            return SendaCastRelay(server, tvAddress, referer, proxy).also { it.start() }
        }
    }
}
