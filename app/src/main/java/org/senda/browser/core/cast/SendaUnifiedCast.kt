package org.senda.browser.core.cast

import android.content.Context
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
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.Executors

/** Televisor con reproductor DLNA/UPnP (MediaRenderer con AVTransport) en la red local. */
data class CastDevice(
    val id: String,
    val name: String,
    val model: String,
    val controlUrl: String
) {
    val host: String
        get() = URL(controlUrl).host
}

/**
 * Envío de videos directo al televisor por DLNA, sin apps intermedias: el reproductor del propio televisor
 * descarga el video (a través de [SendaCastRelay]) y lo reproduce a su resolución y en su formato original.
 * Ni la pantalla ni la interfaz del teléfono aparecen en la TV.
 *
 * Probado con un LG webOS (LM6370PDB): MP4 y HLS, pausa, salto y detener.
 */
object SendaUnifiedCast {
    private const val TAG = "SendaUnifiedCast"
    private const val SSDP_ADDRESS = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    private const val MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"
    // Lo que tarda la TV en abrir su reproductor: el LG contesta a Play después de unos 8 s
    private const val START_TIMEOUT_MS = 25_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val searchExecutor = Executors.newCachedThreadPool { r -> Thread(r, "senda-cast-search") }
    private val commandExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "senda-cast-cmd") }

    val devices = mutableStateListOf<CastDevice>()
    var isSearching by mutableStateOf(false)
        private set

    data class ActiveCastPlayback(
        val device: CastDevice,
        val title: String,
        val paused: Boolean = false,
        val positionSeconds: Int = 0,
        val durationSeconds: Int = 0
    )

    var activePlayback by mutableStateOf<ActiveCastPlayback?>(null)
        private set

    /** Resultado de un envío, para decirle al usuario qué pasó. */
    enum class SendResult { PLAYING, TV_CANNOT_PLAY, TV_UNREACHABLE }

    private var relay: SendaCastRelay? = null
    private var positionPoller: Runnable? = null

    // --- Búsqueda de televisores ---

    /** Busca televisores DLNA en la red (unos 3 s). Los ya encontrados se conservan. */
    fun startDiscovery(context: Context) {
        if (isSearching) return
        isSearching = true
        searchExecutor.execute {
            try {
                discoverSsdpDevices()
            } catch (e: Exception) {
                Log.w(TAG, "Búsqueda SSDP: ${e.message}")
            }
            mainHandler.post { isSearching = false }
        }
    }

    private fun discoverSsdpDevices() {
        val seen = HashSet<String>()
        DatagramSocket().use { socket ->
            socket.soTimeout = 300
            val group = InetAddress.getByName(SSDP_ADDRESS)
            val packets = listOf(MEDIA_RENDERER, AV_TRANSPORT).map { st ->
                val req = ("M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 2\r\n" +
                    "ST: $st\r\n\r\n").toByteArray(Charsets.US_ASCII)
                DatagramPacket(req, req.size, group, SSDP_PORT)
            }
            val buffer = ByteArray(4096)
            val start = System.currentTimeMillis()
            var sends = 0
            while (System.currentTimeMillis() - start < 3000L) {
                // UDP en Wi‑Fi pierde paquetes: se pregunta tres veces
                if (sends < 3 && System.currentTimeMillis() - start >= sends * 500L) {
                    packets.forEach { socket.send(it) }
                    sends++
                }
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val location = headerValue(text, "LOCATION") ?: continue
                    val responder = packet.address
                    if (!isUrlOnResponder(location, responder)) continue
                    if (seen.add(location)) searchExecutor.execute { inspectSsdpDevice(location, responder) }
                } catch (_: SocketTimeoutException) {
                }
            }
        }
    }

    private fun inspectSsdpDevice(location: String, responder: InetAddress) {
        try {
            val conn = URL(location).openConnection() as HttpURLConnection
            conn.connectTimeout = 1800
            conn.readTimeout = 2500
            val xml = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            if (!xml.contains(AV_TRANSPORT)) return
            val controlUrl = extractControlUrl(xml, AV_TRANSPORT, location) ?: return
            if (!isUrlOnResponder(controlUrl, responder)) return
            val manufacturer = xmlTag(xml, "manufacturer") ?: ""
            val modelName = xmlTag(xml, "modelName") ?: ""
            val device = CastDevice(
                id = xmlTag(xml, "UDN") ?: location,
                name = xmlTag(xml, "friendlyName") ?: URL(location).host,
                model = listOf(manufacturer, modelName).filter { it.isNotBlank() }.joinToString(" "),
                controlUrl = controlUrl
            )
            mainHandler.post {
                // Por identificador, nunca por nombre: una misma TV anuncia varios servicios con el mismo nombre
                val index = devices.indexOfFirst { it.id == device.id }
                if (index >= 0) devices[index] = device else devices.add(device)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Descripción UPnP no válida en $location: ${e.message}")
        }
    }

    private fun extractControlUrl(xml: String, serviceType: String, location: String): String? {
        for (match in Regex("<service>[\\s\\S]*?</service>").findAll(xml)) {
            val block = match.value
            if (!block.contains(serviceType)) continue
            val raw = xmlTag(block, "controlURL") ?: continue
            return try {
                URL(URL(location), raw).toString()
            } catch (_: Exception) {
                null
            }
        }
        return null
    }

    // --- Envío y control ---

    /**
     * Envía [media] a la TV y espera a que su reproductor empiece. [startSeconds] es el punto en el que iba el video
     * en el teléfono. [proxy] es el que usa la navegación (Tor o el del usuario), para que el video vaya por el mismo
     * camino que la página. [onResult] se llama en el hilo principal.
     */
    fun playOnDlna(
        device: CastDevice,
        media: SendaMediaCatalog.Media,
        title: String,
        startSeconds: Int,
        proxy: Proxy,
        onResult: (SendResult) -> Unit
    ) {
        commandExecutor.execute {
            val result = try {
                startPlayback(device, media, title, startSeconds, proxy)
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo enviar a ${device.name}: ${e.message}")
                SendResult.TV_UNREACHABLE
            }
            if (result != SendResult.PLAYING) closeRelay()
            mainHandler.post {
                if (result == SendResult.PLAYING) {
                    activePlayback = ActiveCastPlayback(device, title)
                    startPositionPolling()
                }
                onResult(result)
            }
        }
    }

    private fun startPlayback(
        device: CastDevice,
        media: SendaMediaCatalog.Media,
        title: String,
        startSeconds: Int,
        proxy: Proxy
    ): SendResult {
        closeRelay()
        val tv = InetAddress.getByName(device.host)
        val newRelay = SendaCastRelay.open(tv, media.referer, proxy)
        relay = newRelay
        val url = newRelay.urlFor(media.url)

        // Si la TV ya reproducía algo, algunas no aceptan una dirección nueva sin detener antes
        soap(device, "Stop", "", readTimeout = 8000)
        val didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
            "<dc:title>${xmlEscape(title.ifBlank { "Senda" })}</dc:title>" +
            "<upnp:class>object.item.videoItem</upnp:class>" +
            "<res protocolInfo=\"http-get:*:${media.tvMime}:${SendaCastRelay.DLNA_FEATURES}\">${xmlEscape(url)}</res>" +
            "</item></DIDL-Lite>"
        val setUri = soap(
            device, "SetAVTransportURI",
            "<CurrentURI>${xmlEscape(url)}</CurrentURI><CurrentURIMetaData>${xmlEscape(didl)}</CurrentURIMetaData>",
            readTimeout = 15_000
        )
        if (setUri.first != HttpURLConnection.HTTP_OK) {
            Log.w(TAG, "${device.name} rechazó el video (HTTP ${setUri.first}): ${setUri.second.take(300)}")
            return SendResult.TV_CANNOT_PLAY
        }
        // El LG contesta a Play cuando su reproductor ya cargó (unos 8 s): una respuesta tardía no es un fallo
        try {
            soap(device, "Play", "<Speed>1</Speed>", readTimeout = 20_000)
        } catch (_: SocketTimeoutException) {
        }

        val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val info = soap(device, "GetTransportInfo", "", readTimeout = 5000).second
            val state = xmlTag(info, "CurrentTransportState")
            val status = xmlTag(info, "CurrentTransportStatus")
            if (status == "ERROR_OCCURRED") return SendResult.TV_CANNOT_PLAY
            if (state == "PLAYING") {
                if (startSeconds > 5) {
                    soap(device, "Seek", "<Unit>REL_TIME</Unit><Target>${hms(startSeconds)}</Target>", readTimeout = 8000)
                }
                Log.i(TAG, "Reproduciendo en ${device.name}: ${media.tvMime} desde ${startSeconds}s")
                return SendResult.PLAYING
            }
            Thread.sleep(1000)
        }
        // La TV aceptó la dirección pero su reproductor nunca arrancó: formato o códec que no soporta
        soap(device, "Stop", "", readTimeout = 5000)
        return SendResult.TV_CANNOT_PLAY
    }

    fun pause() = control { device ->
        soap(device, "Pause", "")
        mainHandler.post { activePlayback = activePlayback?.copy(paused = true) }
    }

    fun resume() = control { device ->
        soap(device, "Play", "<Speed>1</Speed>", readTimeout = 10_000)
        mainHandler.post { activePlayback = activePlayback?.copy(paused = false) }
    }

    /** Salta [deltaSeconds] (negativo para atrás) desde la posición actual. */
    fun seekBy(deltaSeconds: Int) = control { device ->
        val current = activePlayback ?: return@control
        val target = (current.positionSeconds + deltaSeconds).coerceIn(0, if (current.durationSeconds > 0) current.durationSeconds - 1 else Int.MAX_VALUE)
        soap(device, "Seek", "<Unit>REL_TIME</Unit><Target>${hms(target)}</Target>", readTimeout = 8000)
        mainHandler.post { activePlayback = activePlayback?.copy(positionSeconds = target) }
    }

    /** Detiene el video en la TV y cierra el relé. */
    fun stopActivePlayback(onResult: (Boolean) -> Unit = {}) {
        val current = activePlayback ?: return onResult(true)
        activePlayback = null
        stopPositionPolling()
        commandExecutor.execute {
            val ok = try {
                soap(current.device, "Stop", "").first == HttpURLConnection.HTTP_OK
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo detener en ${current.device.name}: ${e.message}")
                false
            }
            closeRelay()
            mainHandler.post { onResult(ok) }
        }
    }

    private fun control(action: (CastDevice) -> Unit) {
        val device = activePlayback?.device ?: return
        commandExecutor.execute {
            try {
                action(device)
            } catch (e: Exception) {
                Log.w(TAG, "Orden a ${device.name} fallida: ${e.message}")
            }
        }
    }

    // Posición y fin del video en la TV, cada 2 s, para los controles del teléfono
    private fun startPositionPolling() {
        stopPositionPolling()
        val poller = object : Runnable {
            override fun run() {
                val current = activePlayback ?: return
                commandExecutor.execute {
                    try {
                        val info = soap(current.device, "GetTransportInfo", "", readTimeout = 4000).second
                        val state = xmlTag(info, "CurrentTransportState")
                        val position = soap(current.device, "GetPositionInfo", "", readTimeout = 4000).second
                        val rel = parseHms(xmlTag(position, "RelTime"))
                        val duration = parseHms(xmlTag(position, "TrackDuration"))
                        mainHandler.post {
                            val now = activePlayback ?: return@post
                            if (now.device.id != current.device.id) return@post
                            if (state == "STOPPED" || state == "NO_MEDIA_PRESENT") {
                                // Terminó o alguien lo detuvo con el mando de la TV
                                activePlayback = null
                                stopPositionPolling()
                                commandExecutor.execute { closeRelay() }
                            } else {
                                activePlayback = now.copy(
                                    paused = state == "PAUSED_PLAYBACK",
                                    positionSeconds = rel ?: now.positionSeconds,
                                    durationSeconds = duration ?: now.durationSeconds
                                )
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
                if (positionPoller === this) mainHandler.postDelayed(this, 2000)
            }
        }
        positionPoller = poller
        mainHandler.postDelayed(poller, 2000)
    }

    private fun stopPositionPolling() {
        positionPoller?.let { mainHandler.removeCallbacks(it) }
        positionPoller = null
    }

    private fun closeRelay() {
        relay?.close()
        relay = null
    }

    /** Envía una acción AVTransport. Devuelve el código HTTP y la respuesta. */
    private fun soap(device: CastDevice, action: String, args: String, readTimeout: Int = 5000): Pair<Int, String> {
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:$action xmlns:u=\"$AV_TRANSPORT\"><InstanceID>0</InstanceID>$args</u:$action></s:Body></s:Envelope>"
        val conn = URL(device.controlUrl).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 3000
        conn.readTimeout = readTimeout
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        conn.setRequestProperty("SOAPACTION", "\"$AV_TRANSPORT#$action\"")
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return code to text
        } finally {
            conn.disconnect()
        }
    }

    // --- Utilidades ---

    private fun hms(seconds: Int): String = "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)

    private fun parseHms(value: String?): Int? {
        val parts = value?.substringBefore('.')?.split(':') ?: return null
        if (parts.size != 3) return null
        val (h, m, s) = parts.map { it.toIntOrNull() ?: return null }
        return h * 3600 + m * 60 + s
    }

    private fun xmlEscape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun headerValue(response: String, name: String): String? =
        response.lineSequence()
            .firstOrNull { it.startsWith("$name:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()

    private fun xmlTag(xml: String, tag: String): String? =
        Regex("<$tag>([^<]*)</$tag>").find(xml)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * Una respuesta SSDP la puede mandar cualquiera en la red y decide a qué URL se conecta Senda (y a dónde
 * envía la página). Solo se acepta una URL http cuyo host sea, literalmente, la IP local que respondió.
 */
internal fun isUrlOnResponder(url: String?, responder: java.net.InetAddress): Boolean {
    if (url == null) return false
    if (!(responder.isSiteLocalAddress || responder.isLinkLocalAddress)) return false
    return try {
        val parsed = java.net.URL(url)
        parsed.protocol == "http" && parsed.host.trim('[', ']') == responder.hostAddress?.substringBefore('%')
    } catch (_: java.net.MalformedURLException) {
        false
    }
}
