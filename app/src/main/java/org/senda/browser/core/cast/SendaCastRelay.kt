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
 * Relé del video hacia la TV durante una transmisión DLNA. La TV no puede pedir el video por su cuenta a
 * muchos sitios: lo exigen con el Referer de la página, o usan certificados HTTPS que un televisor de hace años
 * no reconoce. El teléfono lo pide como lo pidió la página (por el mismo proxy o Tor) y se lo pasa a la TV por
 * http en la red local, sin recomprimir nada: la TV recibe el archivo original.
 *
 * Solo atiende a la IP de la TV y a rutas con un secreto aleatorio, y se cierra al terminar la transmisión.
 * Las listas HLS se reescriben para que también sus trozos pasen por aquí.
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

    private val baseUrl: String
        get() = "http://${server.inetAddress.hostAddress}:${server.localPort}/$secret/"

    /** Dirección local que se le da a la TV para [original]. */
    fun urlFor(original: String): String {
        val encoded = Base64.encodeToString(original.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        // Algunas TVs deciden el reproductor por la extensión: se conserva el nombre del archivo original
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
                    // Nadie más en la red puede usar el relé
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
                // La TV corta conexiones a menudo (salta, cambia de calidad): no es un error
                if (!closed) Log.d(TAG, "Relé: ${e.message}")
            }
        }
    }

    private fun relay(original: String, method: String, range: String?, out: OutputStream) {
        val conn = URL(original).openConnection(proxy) as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", USER_AGENT)
        referer?.let { conn.setRequestProperty("Referer", it) }
        if (range != null) conn.setRequestProperty("Range", range)
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
                // La URL final (tras redirecciones) es la base de las rutas relativas de la lista
                val body = rewritePlaylist(text, conn.url.toString()).toByteArray(Charsets.UTF_8)
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
                "Accept-Ranges" to "bytes",
                "transferMode.dlna.org" to "Streaming",
                "contentFeatures.dlna.org" to DLNA_FEATURES
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

    /** Cada dirección de la lista (variantes, trozos, claves, subtítulos) pasa también por el relé. */
    private fun rewritePlaylist(text: String, base: String): String {
        val baseUrl = URL(base)
        fun relayed(uri: String): String = try {
            urlFor(URL(baseUrl, uri.trim()).toString())
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
        private const val USER_AGENT = "Mozilla/5.0 (Android 15; Mobile; rv:157.0) Gecko/157.0 Firefox/157.0"
        const val DLNA_FEATURES = "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"

        /** El mismo camino que la navegación (ver senda_proxy/background.js): con Tor el video también va por Tor. */
        fun proxyFrom(prefs: org.senda.browser.core.PreferencesManager): Proxy = when (prefs.proxyMode) {
            "TOR_ORBOT" -> Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", 9050))
            "CUSTOM_SOCKS5" -> Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(prefs.proxyHost, prefs.proxyPort))
            "CUSTOM_HTTP" -> Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(prefs.proxyHost, prefs.proxyPort))
            else -> Proxy.NO_PROXY
        }

        /**
         * Abre el relé en la interfaz de red por la que se llega a [tvAddress] (el Wi‑Fi de la casa), en un puerto libre.
         * Debe llamarse fuera del hilo principal.
         */
        fun open(tvAddress: InetAddress, referer: String?, proxy: Proxy): SendaCastRelay {
            val local = java.net.DatagramSocket().use { probe ->
                // Sin enviar nada: solo pregunta al sistema qué IP propia usaría para hablar con la TV
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
