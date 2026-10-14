package org.senda.browser.core.cast

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Control of an LG webOS TV through its own interface (SSAP over a WebSocket on port 3001, the one LG's phone app
 * uses). Senda only asks it to open a page in the TV's browser: Senda's player page, served by [SendaCastRelay],
 * which plays a live broadcast in original quality. The TV's DLNA player cannot do that: with Caracol's live
 * broadcast it received the video and never started (2026-10-13), while its browser played it in seconds.
 *
 * The first time the TV asks the user to accept Senda with its remote; the key it gives is kept only on the phone,
 * together with the fingerprint of the TV's certificate, so another device cannot take its place later.
 * Everything stays on the home network, never through Tor or a proxy.
 */
class SendaWebOs private constructor(private val socket: SSLSocket) : AutoCloseable {
    private val input: InputStream = socket.inputStream
    private var nextId = 0

    /** Opens [url] in the TV's browser. */
    fun openInBrowser(url: String): Boolean =
        request("ssap://system.launcher/open", JSONObject().put("target", url))?.optBoolean("returnValue") == true

    /** The app on screen (an input such as com.webos.app.hdmi2, the browser…), or null if the TV does not say. */
    fun foregroundApp(): String? =
        request("ssap://com.webos.applicationManager/getForegroundAppInfo", JSONObject())?.optString("appId")?.ifBlank { null }

    /** Brings back an app or input (what was on screen before the video). */
    fun launch(appId: String): Boolean =
        request("ssap://system.launcher/launch", JSONObject().put("id", appId))?.optBoolean("returnValue") == true

    private fun request(uri: String, payload: JSONObject): JSONObject? {
        val id = "senda${++nextId}"
        send(JSONObject().put("type", "request").put("id", id).put("uri", uri).put("payload", payload))
        while (true) {
            val message = receive() ?: continue
            if (message.optString("id") != id) continue
            if (message.optString("type") == "error") {
                Log.w(TAG, "$uri: ${message.optString("error")}")
                return null
            }
            return message.optJSONObject("payload")
        }
    }

    private fun send(message: JSONObject) {
        val data = message.toString().toByteArray(Charsets.UTF_8)
        val frame = ByteArrayOutputStream()
        frame.write(0x81) // a whole text message
        when {
            data.size < 126 -> frame.write(0x80 or data.size)
            data.size < 65536 -> {
                frame.write(0x80 or 126)
                frame.write(data.size shr 8)
                frame.write(data.size and 0xff)
            }
            else -> throw IllegalArgumentException("Message too long")
        }
        // A client always masks what it sends (RFC 6455)
        val mask = ByteArray(4).also { random.nextBytes(it) }
        frame.write(mask)
        data.forEachIndexed { i, b -> frame.write(b.toInt() xor mask[i % 4].toInt()) }
        socket.outputStream.apply {
            write(frame.toByteArray())
            flush()
        }
    }

    /** The next text message, or null for any other kind of frame. */
    private fun receive(): JSONObject? {
        val first = readByte()
        val second = readByte()
        var length = (second and 0x7f).toLong()
        if (length == 126L) length = ((readByte() shl 8) or readByte()).toLong()
        else if (length == 127L) {
            length = 0
            repeat(8) { length = (length shl 8) or readByte().toLong() }
        }
        if (length > MAX_MESSAGE) throw java.io.IOException("Message too long")
        val data = ByteArray(length.toInt())
        var read = 0
        while (read < data.size) {
            val n = input.read(data, read, data.size - read)
            if (n < 0) throw EOFException()
            read += n
        }
        return when (first and 0x0f) {
            0x1 -> JSONObject(String(data, Charsets.UTF_8))
            0x8 -> throw EOFException("The TV closed the connection")
            else -> null
        }
    }

    private fun readByte(): Int {
        val b = input.read()
        if (b < 0) throw EOFException()
        return b
    }

    override fun close() {
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }

    class PairingRejected : Exception("The TV did not accept Senda")

    companion object {
        private const val TAG = "SendaWebOs"
        private const val PORT = 3001
        private const val MAX_MESSAGE = 1L shl 20
        private const val PREFS = "senda_tv"
        private val random = SecureRandom()
        private val PERMISSIONS = listOf("LAUNCH", "LAUNCH_WEBAPP", "APP_TO_APP", "READ_RUNNING_APPS")

        /** Whether [host] answers on webOS's control port: then it is an LG webOS TV. Off the main thread. */
        fun isWebOs(host: String): Boolean = try {
            Socket(java.net.Proxy.NO_PROXY).use { it.connect(InetSocketAddress(host, PORT), 1500) }
            true
        } catch (_: Exception) {
            false
        }

        /**
         * Connects to [device] and registers Senda. The first time the TV shows a prompt: [onPrompt] is called and
         * the user has up to a minute to accept it with the remote. Off the main thread.
         */
        fun connect(context: Context, device: DlnaDevice, onPrompt: () -> Unit): SendaWebOs {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val keyName = "webos_key_${device.id}"
            val pinName = "webos_cert_${device.id}"
            val trust = PinningTrustManager(prefs.getString(pinName, null))
            val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), random) }
            val raw = Socket(java.net.Proxy.NO_PROXY).apply {
                connect(InetSocketAddress(device.host, PORT), 3000)
                soTimeout = 10_000
            }
            val socket = tls.socketFactory.createSocket(raw, device.host, PORT, true) as SSLSocket
            try {
                socket.startHandshake()
                handshake(socket, device.host)
                val tv = SendaWebOs(socket)
                val key = tv.register(prefs.getString(keyName, null), onPrompt)
                // Only kept once the user accepted Senda on this TV
                prefs.edit().putString(keyName, key).putString(pinName, trust.seen).apply()
                return tv
            } catch (e: Exception) {
                try { socket.close() } catch (_: Exception) {}
                throw e
            }
        }

        private fun handshake(socket: SSLSocket, host: String) {
            val key = Base64.getEncoder().encodeToString(ByteArray(16).also { random.nextBytes(it) })
            socket.outputStream.apply {
                write(("GET / HTTP/1.1\r\nHost: $host:$PORT\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray(Charsets.US_ASCII))
                flush()
            }
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                val b = socket.inputStream.read()
                if (b < 0 || head.length > 8192) throw EOFException("No WebSocket answer")
                head.append(b.toChar())
            }
            if (!head.startsWith("HTTP/1.1 101")) throw java.io.IOException("WebSocket refused: ${head.lineSequence().first()}")
        }

        /** Fingerprint of the TV's certificate, checked from the second connection on (trust on first use). */
        private class PinningTrustManager(private val pinned: String?) : X509TrustManager {
            @Volatile var seen: String? = null

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val certificate = chain?.firstOrNull() ?: throw CertificateException("No certificate")
                val fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
                    .joinToString("") { "%02x".format(it) }
                // The TV's certificate is its own (self-signed): no authority can vouch for it, only the first visit
                if (pinned != null && pinned != fingerprint) throw CertificateException("The TV's certificate changed")
                seen = fingerprint
            }

            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
                throw CertificateException("Not a server")

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
    }

    private fun register(clientKey: String?, onPrompt: () -> Unit): String {
        val manifest = JSONObject()
            .put("manifestVersion", 1)
            .put("appVersion", "1.0")
            .put("signed", JSONObject()
                .put("appId", "org.senda.browser")
                .put("vendorId", "org.senda")
                .put("localizedAppNames", JSONObject().put("", "Senda"))
                .put("permissions", JSONArray(PERMISSIONS))
                .put("serial", "1"))
            .put("permissions", JSONArray(PERMISSIONS))
            .put("signatures", JSONArray())
        val payload = JSONObject().put("forcePairing", false).put("pairingType", "PROMPT").put("manifest", manifest)
        clientKey?.let { payload.put("client-key", it) }
        send(JSONObject().put("type", "register").put("id", "register").put("payload", payload))
        // The user may take a while to find the remote
        socket.soTimeout = 60_000
        try {
            while (true) {
                val message = receive() ?: continue
                if (message.optString("id") != "register") continue
                when (message.optString("type")) {
                    "registered" -> return message.getJSONObject("payload").getString("client-key")
                    "response" -> if (message.optJSONObject("payload")?.optString("pairingType") == "PROMPT") onPrompt()
                    "error" -> throw PairingRejected()
                }
            }
        } finally {
            socket.soTimeout = 10_000
        }
    }
}
