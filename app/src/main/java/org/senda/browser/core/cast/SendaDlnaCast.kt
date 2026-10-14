package org.senda.browser.core.cast

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

/** TV with a DLNA/UPnP player (MediaRenderer with AVTransport) on the local network. */
data class DlnaDevice(
    val id: String,
    val name: String,
    val model: String,
    val controlUrl: String
) {
    val host: String
        get() = URL(controlUrl).host
}

/**
 * Sends a video or an image straight to the TV in its original quality, next to mirroring (Miracast): the TV's
 * own player downloads the original file (through [SendaCastRelay]) and plays it at its resolution, with nothing
 * re-encoded. Neither the phone's screen nor its interface appears on the TV.
 *
 * Tested with an LG webOS (LM6370PDB): MP4 and HLS, pause, seek and stop. A live broadcast (HLS) goes to an LG
 * webOS TV's browser instead ([SendaWebOs]): its DLNA player does not start one. Then there are no pause or seek.
 */
object SendaDlnaCast {
    private const val TAG = "SendaDlnaCast"
    private const val SSDP_ADDRESS = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    private const val MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"
    private const val DLNA_FEATURES = "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
    private const val WEBOS_BROWSER = "com.webos.app.browser"
    // The TV's browser started Caracol's live broadcast in 7-11 s (2026-10-13)
    private const val WEBOS_START_TIMEOUT_MS = 45_000L
    // How long the TV may take to open its player: the LG answers Play after about 8 s, and with a live
    // broadcast (Caracol, 2026-10-13) it says PLAYING only after about 63 s, although it loads it from the start
    private const val START_TIMEOUT_MS = 90_000L
    private val LOADING_STATES = setOf("TRANSITIONING", "LG_TRANSITIONING")

    private val mainHandler = Handler(Looper.getMainLooper())
    private val searchExecutor = Executors.newCachedThreadPool { r -> Thread(r, "senda-dlna-search") }
    private val commandExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "senda-dlna-cmd") }

    val devices = mutableStateListOf<DlnaDevice>()
    var isSearching by mutableStateOf(false)
        private set

    data class Playback(
        val device: DlnaDevice,
        val title: String,
        val isImage: Boolean,
        val paused: Boolean = false,
        val positionSeconds: Int = 0,
        val durationSeconds: Int = 0,
        /** False in the TV's browser (a live broadcast): it can only be stopped. */
        val controllable: Boolean = true
    )

    var activePlayback by mutableStateOf<Playback?>(null)
        private set

    /** An image the user asked to send from the page's menu: the cast dialog opens with it. */
    data class Request(val media: SendaMediaCatalog.Media, val title: String)

    var request by mutableStateOf<Request?>(null)

    enum class SendResult { PLAYING, TV_CANNOT_PLAY, TV_UNREACHABLE }

    private var relay: SendaCastRelay? = null
    private var positionPoller: Runnable? = null
    // While a live broadcast plays in an LG's browser: the connection to the TV and what was on screen before
    private var webOs: SendaWebOs? = null
    private var webOsPreviousApp: String? = null

    // --- Finding TVs ---

    /** Looks for DLNA TVs on the network (about 3 s). The ones already found are kept. */
    fun startDiscovery() {
        if (isSearching) return
        isSearching = true
        searchExecutor.execute {
            try {
                discoverSsdpDevices()
            } catch (e: Exception) {
                Log.w(TAG, "SSDP search: ${e.message}")
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
                // UDP over Wi‑Fi loses packets: ask three times
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
            val conn = URL(location).openConnection(Proxy.NO_PROXY) as HttpURLConnection
            conn.connectTimeout = 1800
            conn.readTimeout = 2500
            val xml = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            if (!xml.contains(AV_TRANSPORT)) return
            val controlUrl = extractControlUrl(xml, AV_TRANSPORT, location) ?: return
            if (!isUrlOnResponder(controlUrl, responder)) return
            val manufacturer = xmlTag(xml, "manufacturer") ?: ""
            val modelName = xmlTag(xml, "modelName") ?: ""
            val device = DlnaDevice(
                id = xmlTag(xml, "UDN") ?: location,
                name = xmlTag(xml, "friendlyName") ?: URL(location).host,
                model = listOf(manufacturer, modelName).filter { it.isNotBlank() }.joinToString(" "),
                controlUrl = controlUrl
            )
            mainHandler.post {
                // By identifier, never by name: one TV announces several services with the same name
                val index = devices.indexOfFirst { it.id == device.id }
                if (index >= 0) devices[index] = device else devices.add(device)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Invalid UPnP description at $location: ${e.message}")
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

    // --- Sending and controls ---

    /**
     * Sends [media] to the TV and waits for its player to start. [startSeconds] is where the video was on the
     * phone. [proxy] is the one browsing uses (Tor or the user's), so the file takes the same path as the page.
     * [onResult] is called on the main thread.
     */
    fun send(
        context: android.content.Context,
        device: DlnaDevice,
        media: SendaMediaCatalog.Media,
        title: String,
        startSeconds: Int,
        proxy: Proxy,
        onPairingPrompt: () -> Unit,
        onResult: (SendResult) -> Unit
    ) {
        val app = context.applicationContext
        commandExecutor.execute {
            // What was on the TV before is replaced: its controls go away
            mainHandler.post {
                stopPositionPolling()
                activePlayback = null
            }
            closeRelay()
            closeWebOs(restore = false)
            val inBrowser = !media.isImage && media.isHls && SendaWebOs.isWebOs(device.host)
            val result = try {
                if (inBrowser) startInWebOsBrowser(app, device, media, proxy) { mainHandler.post(onPairingPrompt) }
                else startPlayback(device, media, title, startSeconds, proxy)
            } catch (_: SendaWebOs.PairingRejected) {
                Log.w(TAG, "${device.name} did not accept Senda")
                SendResult.TV_UNREACHABLE
            } catch (e: Exception) {
                Log.w(TAG, "Could not send to ${device.name}: ${e.message}")
                SendResult.TV_UNREACHABLE
            }
            if (result != SendResult.PLAYING) {
                closeRelay()
                closeWebOs(restore = false)
            }
            mainHandler.post {
                if (result == SendResult.PLAYING) {
                    activePlayback = Playback(device, title, media.isImage, controllable = !inBrowser)
                    if (inBrowser) startWebOsWatch() else if (!media.isImage) startPositionPolling()
                }
                onResult(result)
            }
        }
    }

    /**
     * Opens Senda's player page in the TV's browser. The video still comes from the phone (the relay, through Tor
     * or the user's proxy like the page): the TV never contacts the site.
     */
    private fun startInWebOsBrowser(
        context: android.content.Context,
        device: DlnaDevice,
        media: SendaMediaCatalog.Media,
        proxy: Proxy,
        onPrompt: () -> Unit
    ): SendResult {
        val tv = SendaWebOs.connect(context, device, onPrompt)
        webOs = tv
        webOsPreviousApp = tv.foregroundApp()?.takeIf { it != WEBOS_BROWSER }
        val newRelay = SendaCastRelay.open(InetAddress.getByName(device.host), media.referer, proxy, context.assets)
        relay = newRelay
        if (!tv.openInBrowser(newRelay.playerUrlFor(media.url))) return SendResult.TV_CANNOT_PLAY
        val deadline = System.currentTimeMillis() + WEBOS_START_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (newRelay.mediaServed) {
                Log.i(TAG, "Playing in the browser of ${device.name}")
                return SendResult.PLAYING
            }
            Thread.sleep(500)
        }
        return SendResult.TV_CANNOT_PLAY
    }

    // Whether the browser is still on screen, every 3 s: the user may switch input or app with the TV remote
    private fun startWebOsWatch() {
        stopPositionPolling()
        val poller = object : Runnable {
            override fun run() {
                val current = activePlayback ?: return
                commandExecutor.execute {
                    val onScreen = try {
                        webOs?.foregroundApp()?.let { it == WEBOS_BROWSER } ?: false
                    } catch (_: Exception) {
                        false
                    }
                    if (!onScreen) {
                        closeRelay()
                        closeWebOs(restore = false)
                        mainHandler.post {
                            if (activePlayback === current) {
                                activePlayback = null
                                stopPositionPolling()
                            }
                        }
                    }
                }
                if (positionPoller === this) mainHandler.postDelayed(this, 3000)
            }
        }
        positionPoller = poller
        mainHandler.postDelayed(poller, 3000)
    }

    /** Closes the connection to the TV; with [restore], it first brings back what was on screen before the video. */
    private fun closeWebOs(restore: Boolean) {
        val tv = webOs ?: return
        if (restore) {
            webOsPreviousApp?.let {
                try {
                    tv.launch(it)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not bring back $it: ${e.message}")
                }
            }
        }
        tv.close()
        webOs = null
        webOsPreviousApp = null
    }

    private fun startPlayback(
        device: DlnaDevice,
        media: SendaMediaCatalog.Media,
        title: String,
        startSeconds: Int,
        proxy: Proxy
    ): SendResult {
        val tv = InetAddress.getByName(device.host)
        val newRelay = SendaCastRelay.open(tv, media.referer, proxy)
        relay = newRelay
        val url = newRelay.urlFor(media.url)
        // An image from the page's menu often has no extension: its real type is asked the way the TV will get it
        val mime = if (media.tvMime == "image/*") newRelay.typeOf(media.url)?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
            else media.tvMime

        // If the TV was already playing something, some do not take a new address without stopping first
        try {
            soap(device, "Stop", "", readTimeout = 8000)
        } catch (_: SocketTimeoutException) {
        }
        val upnpClass = if (media.isImage) "object.item.imageItem.photo" else "object.item.videoItem"
        val didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
            "<dc:title>${xmlEscape(title.ifBlank { "Senda" })}</dc:title>" +
            "<upnp:class>$upnpClass</upnp:class>" +
            "<res protocolInfo=\"http-get:*:$mime:$DLNA_FEATURES\">${xmlEscape(url)}</res>" +
            "</item></DIDL-Lite>"
        val setUri = soap(
            device, "SetAVTransportURI",
            "<CurrentURI>${xmlEscape(url)}</CurrentURI><CurrentURIMetaData>${xmlEscape(didl)}</CurrentURIMetaData>",
            readTimeout = 15_000
        )
        if (setUri.first != HttpURLConnection.HTTP_OK) {
            Log.w(TAG, "${device.name} refused the file (HTTP ${setUri.first}): ${setUri.second.take(300)}")
            return SendResult.TV_CANNOT_PLAY
        }
        // The LG answers Play once its player has loaded (about 8 s): a late answer is not a failure
        try {
            soap(device, "Play", "<Speed>1</Speed>", readTimeout = 20_000)
        } catch (_: SocketTimeoutException) {
        }

        val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            // While it loads a live broadcast the LG may take a while to answer: that is not a failure
            val info = try {
                soap(device, "GetTransportInfo", "", readTimeout = 10_000).second
            } catch (_: SocketTimeoutException) {
                continue
            }
            val state = xmlTag(info, "CurrentTransportState")
            val status = xmlTag(info, "CurrentTransportStatus")
            if (status == "ERROR_OCCURRED") return SendResult.TV_CANNOT_PLAY
            val seekNeeded = !media.isImage && startSeconds > 5
            // Loading the video itself already shows the TV can play it; jumping to a point needs it playing
            if (state in LOADING_STATES && newRelay.mediaServed && !seekNeeded) {
                Log.i(TAG, "Loading on ${device.name}: $mime")
                return SendResult.PLAYING
            }
            if (state == "PLAYING") {
                if (seekNeeded) {
                    soap(device, "Seek", "<Unit>REL_TIME</Unit><Target>${hms(startSeconds)}</Target>", readTimeout = 8000)
                }
                Log.i(TAG, "Playing on ${device.name}: $mime from ${startSeconds}s")
                return SendResult.PLAYING
            }
            Thread.sleep(1000)
        }
        // The TV took the address but its player never started: a format or codec it does not support
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

    /** Jumps [deltaSeconds] (negative to go back) from the current position. */
    fun seekBy(deltaSeconds: Int) = control { device ->
        val current = activePlayback ?: return@control
        val last = if (current.durationSeconds > 0) current.durationSeconds - 1 else Int.MAX_VALUE
        val target = (current.positionSeconds + deltaSeconds).coerceIn(0, last)
        soap(device, "Seek", "<Unit>REL_TIME</Unit><Target>${hms(target)}</Target>", readTimeout = 8000)
        mainHandler.post { activePlayback = activePlayback?.copy(positionSeconds = target) }
    }

    /** Stops the TV and closes the relay. */
    fun stop() {
        val current = activePlayback ?: return
        activePlayback = null
        stopPositionPolling()
        commandExecutor.execute {
            if (webOs != null) {
                closeRelay()
                closeWebOs(restore = true)
                return@execute
            }
            try {
                soap(current.device, "Stop", "")
            } catch (e: Exception) {
                Log.w(TAG, "Could not stop ${current.device.name}: ${e.message}")
            }
            closeRelay()
        }
    }

    private fun control(action: (DlnaDevice) -> Unit) {
        val device = activePlayback?.device ?: return
        commandExecutor.execute {
            try {
                action(device)
            } catch (e: Exception) {
                Log.w(TAG, "Command to ${device.name} failed: ${e.message}")
            }
        }
    }

    // Position and end of the video on the TV, every 2 s, for the phone's controls
    private fun startPositionPolling() {
        stopPositionPolling()
        val poller = object : Runnable {
            override fun run() {
                val current = activePlayback ?: return
                commandExecutor.execute {
                    try {
                        val info = soap(current.device, "GetTransportInfo", "", readTimeout = 4000).second
                        val state = xmlTag(info, "CurrentTransportState")
                        val failed = xmlTag(info, "CurrentTransportStatus") == "ERROR_OCCURRED"
                        val position = soap(current.device, "GetPositionInfo", "", readTimeout = 4000).second
                        val rel = parseHms(xmlTag(position, "RelTime"))
                        val duration = parseHms(xmlTag(position, "TrackDuration"))
                        mainHandler.post {
                            val now = activePlayback ?: return@post
                            if (now.device.id != current.device.id) return@post
                            if (failed || state == "STOPPED" || state == "NO_MEDIA_PRESENT") {
                                // It ended, failed, or someone stopped it with the TV remote
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

    /** Sends an AVTransport action. Returns the HTTP code and the answer. */
    private fun soap(device: DlnaDevice, action: String, args: String, readTimeout: Int = 5000): Pair<Int, String> {
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:$action xmlns:u=\"$AV_TRANSPORT\"><InstanceID>0</InstanceID>$args</u:$action></s:Body></s:Envelope>"
        // The TV is on the home network: never through Tor or the user's proxy
        val conn = URL(device.controlUrl).openConnection(Proxy.NO_PROXY) as HttpURLConnection
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

    // --- Helpers ---

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
 * Anyone on the network can send an SSDP answer, and it decides which URL Senda connects to (and where it sends
 * the file). Only an http URL whose host is, literally, the local IP that answered is accepted.
 */
internal fun isUrlOnResponder(url: String?, responder: InetAddress): Boolean {
    if (url == null) return false
    if (!(responder.isSiteLocalAddress || responder.isLinkLocalAddress)) return false
    return try {
        val parsed = URL(url)
        parsed.protocol == "http" && parsed.host.trim('[', ']') == responder.hostAddress?.substringBefore('%')
    } catch (_: java.net.MalformedURLException) {
        false
    }
}
