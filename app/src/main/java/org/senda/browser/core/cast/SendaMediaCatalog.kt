package org.senda.browser.core.cast

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.WebExtension

/**
 * Videos each page downloaded (MP4, WebM, HLS), according to the built-in media@senda.org extension, which only
 * watches the network. It is what is shown on the TV while mirroring ([SendaTvPlayer]): it needs the address
 * of the video file, not of the page.
 *
 * Videos the site assembles in chunks inside the browser (blob:, MSE: YouTube, Netflix and most platforms)
 * do not show up here because there is no file to play (YouTube uses its official player).
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

        /** Type for the TV: the server sometimes answers with a generic one (application/octet-stream). */
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
    // Previews of a video in another ad or preview: the shortest one is not what the user is watching
    private const val MIN_FILE_BYTES = 512 * 1024L

    // Page (without #fragment) → videos, the newest last. Memory only: never stored on disk
    private val byPage = LinkedHashMap<String, MutableList<Media>>(MAX_PAGES, 0.75f, true)

    private fun pageKey(url: String) = url.substringBefore('#')

    /** Changes with each new video: the UI reads it to find out (the catalog itself is not Compose state). */
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
     * The video worth sending for the page: first an HLS playlist (includes every quality) and otherwise the
     * largest file; with equal priority, the newest one (the one the user just started).
     */
    @Synchronized
    fun bestFor(pageUrl: String): Media? {
        // The tab shows a video file directly: Gecko loads it as a page and it does not go through the detector
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
