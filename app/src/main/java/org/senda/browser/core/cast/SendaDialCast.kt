package org.senda.browser.core.cast

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.Executors

/** Televisor o decodificador de la red local que puede abrir YouTube por DIAL. */
data class DialDevice(
    val name: String,
    val model: String,
    val appsUrl: String
)

/**
 * Envío de videos de YouTube a la TV con DIAL, el protocolo abierto de "enviar a la pantalla"
 * (el mismo que usa la app de YouTube con Smart TVs y decodificadores).
 *
 * Todo ocurre dentro de la red Wi‑Fi de casa: se buscan dispositivos por SSDP y se le pide a la
 * app de YouTube de la TV que abra el video. La TV lo reproduce por sí misma, a pantalla completa
 * y sin el retraso de duplicar la pantalla. Senda se identifica como ella misma (Origin) y no
 * contacta con ningún servidor externo.
 */
object SendaDialCast {

    private const val TAG = "SendaDial"
    private const val SSDP_ADDRESS = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val DIAL_SERVICE = "urn:dial-multiscreen-org:service:dial:1"
    private const val ORIGIN = "package:org.senda.browser"
    private const val SEARCH_MILLIS = 2000L
    private const val STORE_PREFS = "senda_dial_devices"
    private const val KEY_DEVICES = "devices"

    private val mainHandler = Handler(Looper.getMainLooper())
    // La búsqueda y las órdenes van por hilos separados: enviar un video nunca espera a que acabe una búsqueda
    private val searchExecutor = Executors.newCachedThreadPool { r -> Thread(r, "senda-dial-search") }
    private val commandExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "senda-dial-cmd") }

    val devices = mutableStateListOf<DialDevice>()
    var isSearching by mutableStateOf(false)
        private set

    /** Video que Senda está reproduciendo ahora en una TV (null si ninguno). */
    var activeCast by mutableStateOf<ActiveCast?>(null)
        private set

    data class ActiveCast(val device: DialDevice, val videoId: String, val runUrl: String)

    private var store: SharedPreferences? = null

    /** Carga las TVs encontradas en otras sesiones para que aparezcan al instante. */
    fun init(context: Context) {
        if (store != null) return
        val prefs = context.applicationContext.getSharedPreferences(STORE_PREFS, Context.MODE_PRIVATE)
        store = prefs
        prefs.getStringSet(KEY_DEVICES, emptySet())?.mapNotNull { decode(it) }?.forEach { device ->
            if (devices.none { it.appsUrl == device.appsUrl }) devices.add(device)
        }
    }

    /**
     * Busca en la red local televisores con la app de YouTube disponible por DIAL. Cada TV se muestra
     * en cuanto responde, sin esperar a que termine la búsqueda.
     */
    fun search() {
        if (isSearching) return
        isSearching = true
        searchExecutor.execute {
            try {
                discover { device -> mainHandler.post { upsert(device) } }
            } catch (e: Exception) {
                Log.w(TAG, "Búsqueda DIAL fallida: ${e.message}")
            }
            mainHandler.post { isSearching = false }
        }
    }

    private fun upsert(device: DialDevice) {
        // Conservar los ya conocidos que no respondieron a esta búsqueda puntual
        val idx = devices.indexOfFirst { it.appsUrl == device.appsUrl || it.name == device.name }
        if (idx >= 0) devices[idx] = device else devices.add(device)
        store?.edit()?.putStringSet(KEY_DEVICES, devices.map { encode(it) }.toSet())?.apply()
    }

    /**
     * Abre el video en la app de YouTube del dispositivo, empezando en [startSeconds].
     * [onResult] se llama en el hilo principal con true si la TV aceptó la orden.
     */
    fun playYouTube(device: DialDevice, videoId: String, startSeconds: Int, onResult: (Boolean) -> Unit) {
        commandExecutor.execute {
            val acceptedRunUrl: String? = try {
                val body = "v=${Uri.encode(videoId)}&t=${startSeconds.coerceAtLeast(0)}"
                val conn = URL(device.appsUrl + "YouTube").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 2500
                conn.readTimeout = 8000
                conn.doOutput = true
                conn.setRequestProperty("Origin", ORIGIN)
                conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                // DIAL devuelve en Location la instancia en ejecución, que sirve para detenerla después
                val appUrl = URL(device.appsUrl + "YouTube/")
                val runUrl = conn.getHeaderField("Location")?.let { URL(appUrl, it).toString() } ?: URL(appUrl, "run").toString()
                conn.disconnect()
                Log.i(TAG, "DIAL YouTube en ${device.name}: HTTP $code (v=$videoId t=$startSeconds)")
                if (code == HttpURLConnection.HTTP_CREATED || code == HttpURLConnection.HTTP_OK) runUrl else null
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo enviar a ${device.name}: ${e.message}")
                null
            }
            mainHandler.post {
                if (acceptedRunUrl != null) {
                    activeCast = ActiveCast(device, videoId, acceptedRunUrl)
                } else {
                    // La TV pudo cambiar de dirección (router): buscarla de nuevo para el próximo intento
                    search()
                }
                onResult(acceptedRunUrl != null)
            }
        }
    }

    /** Cierra en la TV el video enviado por Senda. */
    fun stop(onResult: (Boolean) -> Unit = {}) {
        val cast = activeCast ?: return
        activeCast = null
        commandExecutor.execute {
            val ok = try {
                val conn = URL(cast.runUrl).openConnection() as HttpURLConnection
                conn.requestMethod = "DELETE"
                conn.connectTimeout = 2500
                conn.readTimeout = 4000
                conn.setRequestProperty("Origin", ORIGIN)
                val code = conn.responseCode
                conn.disconnect()
                Log.i(TAG, "DIAL detener en ${cast.device.name}: HTTP $code")
                code == HttpURLConnection.HTTP_OK
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo detener en ${cast.device.name}: ${e.message}")
                false
            }
            mainHandler.post { onResult(ok) }
        }
    }

    private fun discover(onFound: (DialDevice) -> Unit) {
        val seen = HashSet<String>()
        DatagramSocket().use { socket ->
            socket.soTimeout = 250
            val request = (
                "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 1\r\n" +
                    "ST: $DIAL_SERVICE\r\n\r\n"
                ).toByteArray(Charsets.US_ASCII)
            val group = InetAddress.getByName(SSDP_ADDRESS)
            val packet = DatagramPacket(request, request.size, group, SSDP_PORT)
            socket.send(packet)

            val buffer = ByteArray(2048)
            val start = System.currentTimeMillis()
            var resent = false
            while (System.currentTimeMillis() - start < SEARCH_MILLIS) {
                // Segundo envío: UDP puede perder el primero en Wi‑Fi
                if (!resent && System.currentTimeMillis() - start > 300) {
                    socket.send(packet)
                    resent = true
                }
                try {
                    val response = DatagramPacket(buffer, buffer.size)
                    socket.receive(response)
                    val text = String(response.data, 0, response.length, Charsets.UTF_8)
                    if (!text.contains(DIAL_SERVICE, ignoreCase = true)) continue
                    val location = headerValue(text, "LOCATION") ?: continue
                    // Consultar cada TV en paralelo en cuanto responde
                    if (seen.add(location)) searchExecutor.execute { describe(location)?.let(onFound) }
                } catch (_: SocketTimeoutException) {
                }
            }
        }
    }

    /** Lee la descripción del dispositivo y confirma que tiene la app de YouTube. */
    private fun describe(location: String): DialDevice? = try {
        val conn = URL(location).openConnection() as HttpURLConnection
        conn.connectTimeout = 1500
        conn.readTimeout = 2000
        val appsUrl = conn.getHeaderField("Application-URL")
        val xml = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        if (appsUrl == null) {
            null
        } else {
            val base = if (appsUrl.endsWith("/")) appsUrl else "$appsUrl/"
            if (!hasYouTube(base)) {
                null
            } else {
                DialDevice(
                    name = xmlTag(xml, "friendlyName") ?: URL(location).host,
                    model = listOfNotNull(xmlTag(xml, "manufacturer"), xmlTag(xml, "modelName")).joinToString(" "),
                    appsUrl = base
                )
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Descripción DIAL no válida en $location: ${e.message}")
        null
    }

    private fun hasYouTube(appsUrl: String): Boolean = try {
        val conn = URL(appsUrl + "YouTube").openConnection() as HttpURLConnection
        conn.connectTimeout = 1500
        conn.readTimeout = 2000
        val code = conn.responseCode
        conn.disconnect()
        code == HttpURLConnection.HTTP_OK
    } catch (_: Exception) {
        false
    }

    private fun headerValue(response: String, name: String): String? =
        response.lineSequence()
            .firstOrNull { it.startsWith("$name:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()

    private fun xmlTag(xml: String, tag: String): String? =
        Regex("<$tag>([^<]*)</$tag>").find(xml)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    private fun encode(d: DialDevice): String = listOf(d.name, d.model, d.appsUrl).joinToString("\u001F")

    private fun decode(raw: String): DialDevice? {
        val parts = raw.split('\u001F')
        return if (parts.size == 3) DialDevice(parts[0], parts[1], parts[2]) else null
    }

    /** Extrae el identificador de video de cualquier enlace de YouTube (watch, youtu.be, shorts, live, embed). */
    fun youTubeVideoId(url: String): String? {
        val uri = try { Uri.parse(url) } catch (_: Exception) { return null }
        val host = uri.host?.lowercase() ?: return null
        val idPattern = Regex("^[A-Za-z0-9_-]{11}$")
        val id = when {
            host == "youtu.be" -> uri.pathSegments.firstOrNull()
            host.endsWith("youtube.com") || host.endsWith("youtube-nocookie.com") -> {
                val segments = uri.pathSegments
                when (segments.firstOrNull()) {
                    "watch" -> uri.getQueryParameter("v")
                    "shorts", "live", "embed", "v" -> segments.getOrNull(1)
                    else -> null
                }
            }
            else -> null
        }
        return id?.takeIf { idPattern.matches(it) }
    }
}
