package org.senda.browser.core.news

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

data class EthicalNewsItem(
    val title: String,
    val url: String,
    val source: String,
    val publishedDate: String = "",
    val snippet: String = ""
)

object EthicalNewsRepository {

    private val cachedItems = mutableListOf<EthicalNewsItem>()
    private var lastFetchTime = 0L
    private const val CACHE_DURATION_MS = 15 * 60 * 1000L // 15 minutos

    suspend fun fetchEthicalNews(forceRefresh: Boolean = false): List<EthicalNewsItem> {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedItems.isNotEmpty() && (now - lastFetchTime < CACHE_DURATION_MS)) {
            return cachedItems
        }

        // Las tres fuentes a la vez: en serie, con 8 s de espera por fuente, la portada podía tardar 24 s.
        // Si ninguna responde se devuelve una lista vacía y la portada lo dice (nunca titulares de relleno)
        val results = coroutineScope {
            listOf(
                async { fetchRssSafely("https://www.muylinux.com/feed/", "MuyLinux", 4) },
                async { fetchRssSafely("https://www.eff.org/rss/updates.xml", "EFF", 4) },
                async { fetchRssSafely("https://www.fsf.org/static/fsforg/rss/news.xml", "FSF", 3) }
            ).awaitAll().flatten()
        }
        if (results.isNotEmpty()) {
            cachedItems.clear()
            cachedItems.addAll(results)
            lastFetchTime = now
        }
        return results
    }

    private suspend fun fetchRssSafely(feedUrl: String, sourceName: String, maxItems: Int): List<EthicalNewsItem> =
        withContext(Dispatchers.IO) {
            try {
                fetchRss(feedUrl, sourceName, maxItems)
            } catch (_: Throwable) {
                emptyList()
            }
        }

    private fun fetchRss(feedUrl: String, sourceName: String, maxItems: Int): List<EthicalNewsItem> {
        val items = mutableListOf<EthicalNewsItem>()
        var connection: HttpURLConnection? = null
        var stream: InputStream? = null

        try {
            connection = org.senda.browser.core.SendaNet.open(feedUrl)
            connection.connectTimeout = 4000
            connection.readTimeout = 4000
            connection.instanceFollowRedirects = true

            if (connection.responseCode in 200..299) {
                stream = connection.inputStream
                val parser: XmlPullParser = Xml.newPullParser()
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                parser.setInput(stream, null)

                var eventType = parser.eventType
                var insideItem = false
                var currentTitle = ""
                var currentLink = ""
                var currentSnippet = ""
                var currentPubDate = ""

                while (eventType != XmlPullParser.END_DOCUMENT && items.size < maxItems) {
                    val name = parser.name
                    when (eventType) {
                        XmlPullParser.START_TAG -> {
                            if (name.equals("item", ignoreCase = true) || name.equals("entry", ignoreCase = true)) {
                                insideItem = true
                                currentTitle = ""
                                currentLink = ""
                                currentSnippet = ""
                                currentPubDate = ""
                            } else if (insideItem) {
                                when (name.lowercase()) {
                                    "title" -> currentTitle = parser.nextText().trim()
                                    "link" -> {
                                        val href = parser.getAttributeValue(null, "href")
                                        currentLink = if (!href.isNullOrBlank()) href else parser.nextText().trim()
                                    }
                                    "description", "summary" -> {
                                        val raw = parser.nextText()
                                        currentSnippet = cleanHtml(raw).take(140)
                                    }
                                    "pubdate", "published", "updated" -> {
                                        currentPubDate = parser.nextText().trim().take(16)
                                    }
                                }
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            if ((name.equals("item", ignoreCase = true) || name.equals("entry", ignoreCase = true)) && insideItem) {
                                insideItem = false
                                // Solo enlaces web: un feed alterado no debe poder abrir javascript: ni file:
                                val isWebLink = currentLink.startsWith("https://", ignoreCase = true) ||
                                    currentLink.startsWith("http://", ignoreCase = true)
                                if (currentTitle.isNotBlank() && isWebLink) {
                                    items.add(
                                        EthicalNewsItem(
                                            title = currentTitle,
                                            url = currentLink,
                                            source = sourceName,
                                            publishedDate = currentPubDate,
                                            snippet = currentSnippet
                                        )
                                    )
                                }
                            }
                        }
                    }
                    eventType = parser.next()
                }
            }
        } finally {
            try { stream?.close() } catch (_: Exception) {}
            try { connection?.disconnect() } catch (_: Exception) {}
        }

        return items
    }

    private fun cleanHtml(html: String): String {
        return html
            .replace(Regex("<[^>]*>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .trim()
    }
}
