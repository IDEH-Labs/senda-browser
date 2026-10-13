package org.senda.browser.e2e.test

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The test pages and the test proxy. Both live in the test process and nothing they do leaves the phone:
 * the proxy only answers for the test pages and refuses everything else, writing down what was asked.
 */
object LocalWeb {

    /** Script that LibreJS counts as nontrivial (a function and a loop), the same as in the manual test of 2026-10-11. */
    private const val SCRIPT =
        "function mark(n){var s=\"\";for(var i=0;i<n;i++){s+=\"x\";}document.getElementById(\"r\").textContent=\"JS-RAN-\"+s.length;}mark(3);"

    fun page(title: String, body: String) =
        "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width\"><title>$title</title></head>" +
            "<body><h1>$title</h1>$body</body></html>"

    private val pages = mapOf(
        "/nonfree.html" to page("nonfree", "<p id=\"r\">NOJS</p><script>$SCRIPT</script>"),
        "/free.html" to page(
            "free",
            "<p id=\"r\">NOJS</p><script>\n" +
                "// @license magnet:?xt=urn:btih:1f739d935676111cfff4b4693e3816e664797050&dn=gpl-3.0.txt GPL-3.0-or-later\n" +
                "$SCRIPT\n// @license-end\n</script>"
        ),
        "/external.html" to page("external", "<p id=\"r\">NOJS</p><script src=\"ext.js\"></script>"),
        // Asks for Widevine the way a streaming site does; GNU's guidelines rule out browsers that load DRM modules
        "/drm.html" to page(
            "drm",
            "<p id=\"r\">DRM-WAIT</p><script>function show(t){document.getElementById(\"r\").textContent=t;}" +
                "if(!navigator.requestMediaKeySystemAccess){show(\"DRM-BLOCKED\");}else{" +
                "navigator.requestMediaKeySystemAccess(\"com.widevine.alpha\",[{initDataTypes:[\"cenc\"]," +
                "videoCapabilities:[{contentType:'video/mp4; codecs=\"avc1.42E01E\"'}]}])" +
                ".then(function(){show(\"DRM-AVAILABLE\");},function(e){show(\"DRM-BLOCKED \"+e.message);});}</script>"
        ),
        "/a.html" to page("e2e-a", "<p>Page A</p>"),
        "/b.html" to page("e2e-b", "<p>Page B</p>"),
        "/c.html" to page("e2e-c", "<p>Page C</p>"),
        "/button.html" to page(
            "e2e-button",
            "<p style=\"height:300px\">Space above</p><button id=\"b\" style=\"font-size:24px\" " +
                "onclick=\"this.textContent='PRESSED'\">PRESS-ME</button>"
        )
    )

    /** Requests the test pages received, and how each one arrived. */
    val directRequests = AtomicInteger()
    val proxiedRequests = AtomicInteger()

    /** Hosts the proxy refused (anything that is not a test page): the e2e app's own connections. */
    val refusedHosts: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Volatile var slowDelayMs = 10_000L

    private var server: ServerSocket? = null
    private var proxy: ServerSocket? = null

    /** Address the test pages are served on: 127.0.0.1, or the phone's own Wi-Fi address (see [start]). */
    lateinit var host: String
        private set
    val port get() = server!!.localPort
    val proxyPort get() = proxy!!.localPort
    fun url(path: String) = "http://$host:$port$path"

    /**
     * [overNetworkAddress]: Gecko never sends 127.0.0.1 through a proxy, so the network mode tests serve the
     * pages on the phone's own Wi-Fi address. Connections to it stay inside the phone; any connection that does
     * not come from the phone itself is closed without an answer.
     */
    fun start(overNetworkAddress: Boolean = false) {
        stop()
        val address = if (overNetworkAddress) ownAddress()
            ?: throw IllegalStateException("No Wi-Fi address: the network mode tests cannot run")
        else InetAddress.getByName("127.0.0.1")
        host = address.hostAddress!!
        directRequests.set(0); proxiedRequests.set(0); refusedHosts.clear()
        server = ServerSocket(0, 50, address).also { s -> acceptLoop(s) { handlePage(it, viaProxy = false) } }
        proxy = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).also { s -> acceptLoop(s) { handleProxy(it) } }
    }

    fun stop() {
        runCatching { server?.close() }
        runCatching { proxy?.close() }
        server = null
        proxy = null
    }

    /** A port where nothing listens: a proxy that is down. */
    fun deadPort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    private fun ownAddress(): InetAddress? = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && it.name.startsWith("wlan") }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address }

    private fun isFromThisPhone(socket: Socket): Boolean {
        val remote = socket.inetAddress
        return remote.isLoopbackAddress || NetworkInterface.getByInetAddress(remote) != null
    }

    private fun acceptLoop(socket: ServerSocket, handler: (Socket) -> Unit) = thread(isDaemon = true) {
        while (!socket.isClosed) {
            val client = try { socket.accept() } catch (_: Exception) { break }
            thread(isDaemon = true) {
                client.use {
                    if (isFromThisPhone(it)) runCatching { handler(it) }
                }
            }
        }
    }

    private class Request(val method: String, val target: String, val headers: Map<String, String>)

    private fun readRequest(input: InputStream): Request? {
        val lines = mutableListOf<String>()
        val line = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return null
            if (b == '\n'.code) {
                val text = line.toString().trimEnd('\r')
                if (text.isEmpty()) break
                lines += text
                line.clear()
            } else line.append(b.toChar())
            if (line.length > 8192) return null
        }
        val parts = lines.firstOrNull()?.split(' ') ?: return null
        if (parts.size < 2) return null
        val headers = lines.drop(1).mapNotNull { h ->
            val i = h.indexOf(':')
            if (i <= 0) null else h.substring(0, i).trim().lowercase() to h.substring(i + 1).trim()
        }.toMap()
        return Request(parts[0], parts[1], headers)
    }

    private fun handlePage(socket: Socket, viaProxy: Boolean, request: Request? = null) {
        val input = BufferedInputStream(socket.getInputStream())
        val req = request ?: readRequest(input) ?: return
        val path = req.target.substringAfter("://").let { if (req.target.contains("://")) "/" + it.substringAfter('/', "") else req.target }
            .substringBefore('?')
        (if (viaProxy) proxiedRequests else directRequests).incrementAndGet()
        val out = socket.getOutputStream()
        when {
            path == "/headers" -> respond(
                out, "text/html",
                page("e2e-headers", "<p>accept-language=${req.headers["accept-language"] ?: "none"}</p>")
            )
            path == "/whoami" -> respond(out, "text/html", page("whoami", "<p>${if (viaProxy) "proxy" else "direct"}</p>"))
            path == "/ext.js" -> respond(out, "application/javascript", SCRIPT)
            path == "/slow.html" -> {
                Thread.sleep(slowDelayMs)
                respond(out, "text/html", page("e2e-slow", "<p>Slow page</p>"))
            }
            pages.containsKey(path) -> respond(out, "text/html", pages.getValue(path))
            else -> respond(out, "text/plain", "not found", status = "404 Not Found")
        }
    }

    private fun handleProxy(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val req = readRequest(input) ?: return
        val out = socket.getOutputStream()
        if (req.method == "CONNECT") {
            refusedHosts += req.target
            respond(out, "text/plain", "refused by the test proxy", status = "403 Forbidden")
            return
        }
        val authority = req.target.substringAfter("://", "").substringBefore('/')
        if (::host.isInitialized && authority == "$host:$port") {
            handlePage(socket, viaProxy = true, request = req)
        } else {
            refusedHosts += authority.ifEmpty { req.headers["host"] ?: "?" }
            respond(out, "text/plain", "refused by the test proxy", status = "403 Forbidden")
        }
    }

    private fun respond(out: OutputStream, type: String, body: String, status: String = "200 OK") {
        val bytes = body.toByteArray()
        out.write(
            ("HTTP/1.1 $status\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray()
        )
        out.write(bytes)
        out.flush()
    }
}
