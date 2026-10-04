package org.senda.browser.core.cast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
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

enum class CastDeviceType {
    CHROMECAST,
    DLNA_SMART_TV,
    DIAL_YOUTUBE
}

data class CastDevice(
    val id: String,
    val name: String,
    val model: String,
    val type: CastDeviceType,
    val endpoint: String,
    val controlUrl: String? = null
)

/**
 * Complete, privacy-first Cast & Media Streaming subsystem for Senda.
 * Supports:
 * 1. Google Cast / Chromecast via open mDNS (NsdManager _googlecast._tcp) with zero proprietary Play Services trackers.
 * 2. DLNA / UPnP AVTransport for Smart TVs (Samsung Tizen, LG webOS, Sony, Roku, DLNA Renderers).
 * 3. DIAL for YouTube on Smart TVs and set-top boxes.
 */
object SendaUnifiedCast {
    private const val TAG = "SendaUnifiedCast"
    private const val SSDP_ADDRESS = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val DIAL_SERVICE = "urn:dial-multiscreen-org:service:dial:1"
    private const val DLNA_AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    private const val DLNA_MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"
    private const val ORIGIN = "package:org.senda.browser"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val searchExecutor = Executors.newCachedThreadPool { r -> Thread(r, "senda-cast-search") }
    private val commandExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "senda-cast-cmd") }

    val devices = mutableStateListOf<CastDevice>()
    var isSearching by mutableStateOf(false)
        private set

    var activePlayback by mutableStateOf<ActiveCastPlayback?>(null)
        private set

    data class ActiveCastPlayback(
        val device: CastDevice,
        val mediaUrlOrId: String,
        val title: String,
        val isDlna: Boolean
    )

    private var nsdManager: NsdManager? = null
    private var nsdDiscoveryListener: NsdManager.DiscoveryListener? = null

    fun initialize(context: Context) {
        if (nsdManager == null) {
            nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
        }
    }

    /**
     * Triggers concurrent discovery across mDNS (Chromecast), SSDP DLNA (Smart TVs), and SSDP DIAL.
     */
    fun startDiscovery(context: Context) {
        initialize(context)
        if (isSearching) return
        isSearching = true

        searchExecutor.execute {
            try {
                // 1. Discover DLNA AVTransport and DIAL devices via SSDP UDP multicast
                discoverSsdpDevices()
            } catch (e: Exception) {
                Log.w(TAG, "SSDP discovery error: ${e.message}")
            }
        }

        // 2. Discover Chromecast devices via mDNS
        startMdnsDiscovery()

        // Auto-stop searching flag after 4 seconds
        mainHandler.postDelayed({
            isSearching = false
            stopMdnsDiscovery()
        }, 4000L)
    }

    private fun startMdnsDiscovery() {
        val manager = nsdManager ?: return
        try {
            stopMdnsDiscovery()
            val listener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                    Log.w(TAG, "mDNS Cast start failed: $errorCode")
                }
                override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                    Log.w(TAG, "mDNS Cast stop failed: $errorCode")
                }
                override fun onDiscoveryStarted(serviceType: String?) {
                    Log.i(TAG, "mDNS Cast discovery active for $serviceType")
                }
                override fun onDiscoveryStopped(serviceType: String?) {}

                override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                    if (serviceInfo == null) return
                    resolveCastService(serviceInfo)
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}
            }
            nsdDiscoveryListener = listener
            manager.discoverServices("_googlecast._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "mDNS discovery initiation failed: ${e.message}")
        }
    }

    private fun resolveCastService(serviceInfo: NsdServiceInfo) {
        val manager = nsdManager ?: return
        try {
            manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    Log.w(TAG, "mDNS Cast resolve failed: $errorCode")
                }

                override fun onServiceResolved(resolved: NsdServiceInfo?) {
                    if (resolved == null) return
                    val host = resolved.host?.hostAddress ?: return
                    val port = resolved.port
                    val attributes = resolved.attributes
                    val friendlyName = attributes["fn"]?.let { String(it, Charsets.UTF_8) } ?: resolved.serviceName
                    val modelName = attributes["md"]?.let { String(it, Charsets.UTF_8) } ?: "Chromecast"

                    val device = CastDevice(
                        id = "cast://$host:$port",
                        name = friendlyName,
                        model = modelName,
                        type = CastDeviceType.CHROMECAST,
                        endpoint = "https://$host:$port"
                    )
                    mainHandler.post { upsertDevice(device) }
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Exception resolving cast service: ${e.message}")
        }
    }

    private fun stopMdnsDiscovery() {
        val listener = nsdDiscoveryListener ?: return
        try {
            nsdManager?.stopServiceDiscovery(listener)
        } catch (_: Exception) {}
        nsdDiscoveryListener = null
    }

    private fun discoverSsdpDevices() {
        val seen = HashSet<String>()
        DatagramSocket().use { socket ->
            socket.soTimeout = 400
            val group = InetAddress.getByName(SSDP_ADDRESS)

            // Send queries for DIAL, DLNA AVTransport, and MediaRenderer
            val targets = listOf(DLNA_AV_TRANSPORT, DLNA_MEDIA_RENDERER, DIAL_SERVICE)
            for (st in targets) {
                val req = ("M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 2\r\n" +
                        "ST: $st\r\n\r\n").toByteArray(Charsets.US_ASCII)
                socket.send(DatagramPacket(req, req.size, group, SSDP_PORT))
            }

            val buffer = ByteArray(4096)
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < 2500L) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val location = headerValue(text, "LOCATION") ?: continue

                    if (seen.add(location)) {
                        searchExecutor.execute {
                            inspectSsdpDevice(location)
                        }
                    }
                } catch (_: SocketTimeoutException) {}
            }
        }
    }

    private fun inspectSsdpDevice(location: String) {
        try {
            val conn = URL(location).openConnection() as HttpURLConnection
            conn.connectTimeout = 1800
            conn.readTimeout = 2500
            val appsUrl = conn.getHeaderField("Application-URL")
            val xml = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val friendlyName = xmlTag(xml, "friendlyName") ?: URL(location).host
            val manufacturer = xmlTag(xml, "manufacturer") ?: ""
            val modelName = xmlTag(xml, "modelName") ?: ""
            val fullModel = listOf(manufacturer, modelName).filter { it.isNotBlank() }.joinToString(" ")

            // Check if it is a DLNA AVTransport TV / Renderer
            if (xml.contains("urn:schemas-upnp-org:service:AVTransport:1")) {
                val controlUrl = extractControlUrl(xml, "urn:schemas-upnp-org:service:AVTransport:1", location)
                if (controlUrl != null) {
                    val device = CastDevice(
                        id = location,
                        name = friendlyName,
                        model = if (fullModel.isNotBlank()) fullModel else "DLNA Smart TV",
                        type = CastDeviceType.DLNA_SMART_TV,
                        endpoint = location,
                        controlUrl = controlUrl
                    )
                    mainHandler.post { upsertDevice(device) }
                    return
                }
            }

            // Check if it is DIAL (YouTube)
            if (appsUrl != null) {
                val base = if (appsUrl.endsWith("/")) appsUrl else "$appsUrl/"
                val device = CastDevice(
                    id = base,
                    name = friendlyName,
                    model = if (fullModel.isNotBlank()) fullModel else "DIAL Smart TV",
                    type = CastDeviceType.DIAL_YOUTUBE,
                    endpoint = base
                )
                mainHandler.post { upsertDevice(device) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing SSDP description from $location: ${e.message}")
        }
    }

    private fun extractControlUrl(xml: String, serviceType: String, location: String): String? {
        val serviceBlockRegex = Regex("<service>[\\s\\S]*?</service>")
        for (match in serviceBlockRegex.findAll(xml)) {
            val block = match.value
            if (block.contains(serviceType)) {
                val rawControl = xmlTag(block, "controlURL") ?: continue
                return try {
                    URL(URL(location), rawControl).toString()
                } catch (_: Exception) {
                    rawControl
                }
            }
        }
        return null
    }

    private fun upsertDevice(device: CastDevice) {
        val existingIndex = devices.indexOfFirst { it.id == device.id || it.name == device.name }
        if (existingIndex >= 0) {
            devices[existingIndex] = device
        } else {
            devices.add(device)
        }
    }

    // --- DLNA AVTransport Action Execution ---

    fun playOnDlna(
        device: CastDevice,
        mediaUrl: String,
        title: String = "Senda Stream",
        onResult: (Boolean) -> Unit
    ) {
        val controlUrl = device.controlUrl ?: return onResult(false)
        commandExecutor.execute {
            var ok = false
            try {
                // 1. SetAVTransportURI
                val escapedUri = mediaUrl.replace("&", "&amp;")
                val setUriSoap = """
                    <?xml version="1.0" encoding="utf-8"?>
                    <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                      <s:Body>
                        <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                          <InstanceID>0</InstanceID>
                          <CurrentURI>$escapedUri</CurrentURI>
                          <CurrentURIMetaData></CurrentURIMetaData>
                        </u:SetAVTransportURI>
                      </s:Body>
                    </s:Envelope>
                """.trimIndent()

                sendSoapAction(controlUrl, "urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI", setUriSoap)

                // 2. Play
                val playSoap = """
                    <?xml version="1.0" encoding="utf-8"?>
                    <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                      <s:Body>
                        <u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                          <InstanceID>0</InstanceID>
                          <Speed>1</Speed>
                        </u:Play>
                      </s:Body>
                    </s:Envelope>
                """.trimIndent()

                val playCode = sendSoapAction(controlUrl, "urn:schemas-upnp-org:service:AVTransport:1#Play", playSoap)
                ok = (playCode == HttpURLConnection.HTTP_OK)
            } catch (e: Exception) {
                Log.w(TAG, "DLNA play error on ${device.name}: ${e.message}")
            }

            mainHandler.post {
                if (ok) {
                    activePlayback = ActiveCastPlayback(device, mediaUrl, title, isDlna = true)
                }
                onResult(ok)
            }
        }
    }

    fun stopDlna(device: CastDevice, onResult: (Boolean) -> Unit = {}) {
        val controlUrl = device.controlUrl ?: return onResult(false)
        commandExecutor.execute {
            val stopSoap = """
                <?xml version="1.0" encoding="utf-8"?>
                <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                  <s:Body>
                    <u:Stop xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                      <InstanceID>0</InstanceID>
                    </u:Stop>
                  </s:Body>
                </s:Envelope>
            """.trimIndent()
            val code = sendSoapAction(controlUrl, "urn:schemas-upnp-org:service:AVTransport:1#Stop", stopSoap)
            val ok = (code == HttpURLConnection.HTTP_OK)
            mainHandler.post {
                if (activePlayback?.device?.id == device.id) {
                    activePlayback = null
                }
                onResult(ok)
            }
        }
    }

    /**
     * Transmisión directa y fluida a Chromecast vía Eureka / DIAL REST o receptor multimedia.
     * Cero lag, calidad nativa directa desde el servidor a 60 fps sin sobrecargar la CPU del móvil.
     */
    fun playOnChromecast(
        device: CastDevice,
        mediaUrl: String,
        title: String = "Senda Stream",
        startSeconds: Int = 0,
        onResult: (Boolean) -> Unit
    ) {
        val host = try {
            val uri = Uri.parse(device.endpoint)
            uri.host ?: device.id.removePrefix("cast://").substringBefore(":")
        } catch (_: Exception) {
            device.id.removePrefix("cast://").substringBefore(":")
        }
        val videoId = SendaDialCast.youTubeVideoId(mediaUrl)
        if (videoId != null) {
            val dialDevice = DialDevice(
                name = device.name,
                model = device.model,
                appsUrl = "http://$host:8008/apps/"
            )
            SendaDialCast.playYouTube(dialDevice, videoId, startSeconds) { ok ->
                mainHandler.post {
                    if (ok) {
                        activePlayback = ActiveCastPlayback(device, mediaUrl, title, isDlna = false)
                    }
                    onResult(ok)
                }
            }
            return
        }

        commandExecutor.execute {
            var ok = false
            try {
                val conn = URL("http://$host:8008/apps/DefaultMediaReceiver").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 3000
                conn.readTimeout = 5000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                conn.outputStream.use { it.write(mediaUrl.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                ok = (code == HttpURLConnection.HTTP_CREATED || code == HttpURLConnection.HTTP_OK)
                conn.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "Error enviando a Chromecast $host: ${e.message}")
            }
            mainHandler.post {
                if (ok) {
                    activePlayback = ActiveCastPlayback(device, mediaUrl, title, isDlna = false)
                }
                onResult(ok)
            }
        }
    }

    /** Detiene cualquier transmisión activa, sea DLNA o DIAL/Chromecast. */
    fun stopActivePlayback(onResult: (Boolean) -> Unit = {}) {
        val current = activePlayback ?: return onResult(true)
        if (current.isDlna) {
            stopDlna(current.device, onResult)
        } else {
            SendaDialCast.stop { ok ->
                mainHandler.post {
                    activePlayback = null
                    onResult(ok)
                }
            }
        }
    }

    private fun sendSoapAction(controlUrl: String, soapAction: String, body: String): Int {
        val conn = URL(controlUrl).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 3000
        conn.readTimeout = 4000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        conn.setRequestProperty("SOAPACTION", "\"$soapAction\"")
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        conn.disconnect()
        return code
    }

    // --- Helpers ---

    private fun headerValue(response: String, name: String): String? =
        response.lineSequence()
            .firstOrNull { it.startsWith("$name:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()

    private fun xmlTag(xml: String, tag: String): String? =
        Regex("<$tag>([^<]*)</$tag>").find(xml)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
}
