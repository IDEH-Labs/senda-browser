package org.senda.browser.core.news

import android.util.Xml
import kotlinx.coroutines.Dispatchers
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

    private val fallbackNews = listOf(
        EthicalNewsItem(
            title = "Privacidad digital: cómo proteger tu huella y datos en Android",
            url = "https://www.eff.org",
            source = "EFF",
            publishedDate = "Hoy",
            snippet = "Herramientas de código abierto que devuelven el control y la privacidad a los usuarios."
        ),
        EthicalNewsItem(
            title = "El Manifiesto de Software Libre y las 4 Libertades Esenciales",
            url = "https://www.fsf.org/philosophy/free-sw.es.html",
            source = "FSF",
            publishedDate = "Filosofía Libre",
            snippet = "La libertad 0, 1, 2 y 3: ejecutar el programa, estudiar el código, distribuir copias y mejorar la herramienta para la comunidad."
        ),
        EthicalNewsItem(
            title = "Novedades en el ecosistema GNU/Linux y privacidad web",
            url = "https://www.muylinux.com",
            source = "MuyLinux",
            publishedDate = "Software Libre",
            snippet = "Grandes avances en motores de navegación independientes y el avance de estándares abiertos libres de telemetría."
        ),
        EthicalNewsItem(
            title = "Por qué los estándares abiertos garantizan la neutralidad de Internet",
            url = "https://blog.mozilla.org",
            source = "Mozilla",
            publishedDate = "Web Abierta",
            snippet = "Una web sin monopolios cerrados permite que cualquier persona cree su propio navegador y determine sus propias reglas."
        )
    )

    suspend fun fetchEthicalNews(forceRefresh: Boolean = false): List<EthicalNewsItem> {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedItems.isNotEmpty() && (now - lastFetchTime < CACHE_DURATION_MS)) {
            return cachedItems
        }

        return withContext(Dispatchers.IO) {
            val results = mutableListOf<EthicalNewsItem>()

            // 1. MuyLinux (Español)
            try {
                results.addAll(fetchRss("https://www.muylinux.com/feed/", "MuyLinux", 4))
            } catch (t: Throwable) {
                // Silencioso, continuamos con otras fuentes
            }

            // 2. Electronic Frontier Foundation (EFF)
            try {
                results.addAll(fetchRss("https://www.eff.org/rss/updates.xml", "EFF", 4))
            } catch (t: Throwable) {
                // Silencioso
            }

            // 3. Free Software Foundation (FSF)
            try {
                results.addAll(fetchRss("https://www.fsf.org/static/fsforg/rss/news.xml", "FSF", 3))
            } catch (t: Throwable) {
                // Silencioso
            }

            if (results.isEmpty()) {
                fallbackNews
            } else {
                cachedItems.clear()
                cachedItems.addAll(results)
                lastFetchTime = now
                results
            }
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
                                if (currentTitle.isNotBlank() && currentLink.isNotBlank()) {
                                    items.add(
                                        EthicalNewsItem(
                                            title = currentTitle,
                                            url = currentLink,
                                            source = sourceName,
                                            publishedDate = currentPubDate.ifBlank { "Reciente" },
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
