package org.senda.browser.core.cast

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.WebExtension

/**
 * Videos que cada página descargó (MP4, WebM, HLS), según la extensión integrada media@senda.org, que solo
 * observa la red. Es lo que se muestra en la TV al duplicar la pantalla ([SendaTvPlayer]): hace falta la dirección
 * del archivo de video, no la de la página.
 *
 * Los videos que la web arma por trozos en el navegador (blob:, MSE: YouTube, Netflix y la mayoría de plataformas)
 * no aparecen aquí porque no existe un archivo que reproducir (YouTube usa su reproductor oficial).
 */
object SendaMediaCatalog {

    data class Media(
        val url: String,
        val mime: String,
        val length: Long,
        val referer: String?,
        val foundAt: Long
    ) {
        val isHls: Boolean
            get() = mime.contains("mpegurl", ignoreCase = true) ||
                url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)

        /** Tipo para la TV: el servidor a veces responde con uno genérico (application/octet-stream). */
        val tvMime: String
            get() = when {
                isHls -> "application/vnd.apple.mpegurl"
                mime.startsWith("video/") -> mime
                else -> when (url.substringBefore('?').substringAfterLast('.', "").lowercase()) {
                    "webm" -> "video/webm"
                    "mov" -> "video/quicktime"
                    "mkv" -> "video/x-matroska"
                    else -> "video/mp4"
                }
            }
    }

    private const val MAX_PAGES = 30
    private const val MAX_PER_PAGE = 12
    // Avances de un video en otro anuncio o vista previa: lo más corto no es lo que el usuario está viendo
    private const val MIN_FILE_BYTES = 512 * 1024L

    // Página (sin #fragmento) → videos, el más reciente al final. Solo en memoria: nunca se guarda en disco
    private val byPage = LinkedHashMap<String, MutableList<Media>>(MAX_PAGES, 0.75f, true)

    private fun pageKey(url: String) = url.substringBefore('#')

    /** Cambia con cada video nuevo: la interfaz lo lee para enterarse (el catálogo en sí no es estado de Compose). */
    var version by mutableIntStateOf(0)
        private set

    @Synchronized
    fun add(page: String, media: Media) {
        if (!media.url.startsWith("http://") && !media.url.startsWith("https://")) return
        val list = byPage.getOrPut(pageKey(page)) { mutableListOf() }
        list.removeAll { it.url == media.url }
        list.add(media)
        while (list.size > MAX_PER_PAGE) list.removeAt(0)
        while (byPage.size > MAX_PAGES) byPage.remove(byPage.keys.first())
        version++
    }

    /**
     * El video que conviene enviar para la página: primero una lista HLS (incluye todas las calidades) y si no el
     * archivo más grande; con igual prioridad, el más reciente (el que el usuario acaba de poner).
     */
    @Synchronized
    fun bestFor(pageUrl: String): Media? {
        // La pestaña muestra directamente un archivo de video: Gecko lo carga como página y no pasa por el detector
        if ((pageUrl.startsWith("https://") || pageUrl.startsWith("http://")) &&
            Regex("\\.(mp4|m4v|webm|mov|mkv|m3u8)$", RegexOption.IGNORE_CASE).containsMatchIn(pageUrl.substringBefore('?').substringBefore('#'))
        ) {
            return Media(pageUrl, "", -1L, null, System.currentTimeMillis())
        }
        val list = byPage[pageKey(pageUrl)] ?: return null
        list.lastOrNull { it.isHls }?.let { return it }
        return list.filter { it.length < 0 || it.length >= MIN_FILE_BYTES }
            .maxWithOrNull(compareBy<Media> { it.length }.thenBy { it.foundAt })
    }

    @Synchronized
    fun clear() = byPage.clear()

    val messageDelegate = object : WebExtension.MessageDelegate {
        override fun onConnect(port: WebExtension.Port) {
            port.setDelegate(object : WebExtension.PortDelegate {
                override fun onPortMessage(message: Any, port: WebExtension.Port) {
                    val json = message as? JSONObject ?: return
                    if (json.optString("type") != "MEDIA_FOUND") return
                    val page = json.optString("page").takeIf { it.isNotBlank() } ?: return
                    val url = json.optString("url").takeIf { it.isNotBlank() } ?: return
                    add(
                        page,
                        Media(
                            url = url,
                            mime = json.optString("mime"),
                            length = json.optLong("length", -1L),
                            referer = json.optString("referer").takeIf { it.isNotBlank() && it != "null" },
                            foundAt = System.currentTimeMillis()
                        )
                    )
                }
            })
        }

        override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? = null
    }
}
