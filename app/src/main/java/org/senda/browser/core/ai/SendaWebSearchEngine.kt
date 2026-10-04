package org.senda.browser.core.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class SearchResultSource(
    val title: String,
    val snippet: String,
    val url: String
)

data class WebSearchResponse(
    val success: Boolean,
    val query: String,
    val synthesizedSummary: String,
    val sources: List<SearchResultSource> = emptyList()
)

/**
 * Motor de búsqueda soberano y respetuoso de la privacidad para Senda AI.
 * Permite buscar en la red fuentes públicas y transparentes (Wikipedia, DuckDuckGo)
 * sin cookies, sin perfiles publicitarios y sin telemetría.
 */
object SendaWebSearchEngine {

    suspend fun searchAndSynthesize(query: String): WebSearchResponse? = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return@withContext null

        try {
            // 1. Intentar búsqueda en Wikipedia en Español
            val wikiResult = searchWikipedia(cleanQuery)
            if (wikiResult != null && wikiResult.synthesizedSummary.isNotBlank()) {
                return@withContext wikiResult
            }

            // 2. Intentar DuckDuckGo Instant Answer
            val ddgResult = searchDuckDuckGo(cleanQuery)
            if (ddgResult != null && ddgResult.synthesizedSummary.isNotBlank()) {
                return@withContext ddgResult
            }

            null
        } catch (_: Exception) {
            null
        }
    }

    private fun searchWikipedia(query: String): WebSearchResponse? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "https://es.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encoded&utf8=&format=json"

        val searchJsonStr = fetchUrlText(searchUrl) ?: return null
        val searchJson = JSONObject(searchJsonStr)
        val searchArr = searchJson.optJSONObject("query")?.optJSONArray("search") ?: return null
        if (searchArr.length() == 0) return null

        val firstHit = searchArr.getJSONObject(0)
        val title = firstHit.optString("title", "")
        val rawSnippet = firstHit.optString("snippet", "")
            .replace(Regex("<[^>]*>"), "")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&amp;", "&")

        // Obtener extracto introductorio completo
        val encodedTitle = URLEncoder.encode(title, "UTF-8")
        val extractUrl = "https://es.wikipedia.org/w/api.php?action=query&prop=extracts&exintro=&explaintext=&titles=$encodedTitle&format=json"
        val extractJsonStr = fetchUrlText(extractUrl)

        var fullExtract = ""
        if (extractJsonStr != null) {
            val extJson = JSONObject(extractJsonStr)
            val pagesObj = extJson.optJSONObject("query")?.optJSONObject("pages")
            if (pagesObj != null) {
                val keys = pagesObj.keys()
                if (keys.hasNext()) {
                    val pageObj = pagesObj.getJSONObject(keys.next())
                    fullExtract = pageObj.optString("extract", "")
                }
            }
        }

        val finalSnippet = if (fullExtract.isNotBlank()) fullExtract.take(700) else rawSnippet
        if (finalSnippet.isBlank()) return null

        val articleUrl = "https://es.wikipedia.org/wiki/${encodedTitle.replace("+", "_")}"

        val sources = mutableListOf(
            SearchResultSource(
                title = title,
                snippet = finalSnippet,
                url = articleUrl
            )
        )

        for (i in 1 until minOf(searchArr.length(), 3)) {
            val hit = searchArr.getJSONObject(i)
            val secTitle = hit.optString("title", "")
            val secUrl = "https://es.wikipedia.org/wiki/${URLEncoder.encode(secTitle, "UTF-8").replace("+", "_")}"
            val secSnippet = hit.optString("snippet", "").replace(Regex("<[^>]*>"), "")
            sources.add(SearchResultSource(secTitle, secSnippet, secUrl))
        }

        return WebSearchResponse(
            success = true,
            query = query,
            synthesizedSummary = finalSnippet,
            sources = sources
        )
    }

    private fun searchDuckDuckGo(query: String): WebSearchResponse? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val ddgUrl = "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"
        val ddgJsonStr = fetchUrlText(ddgUrl) ?: return null
        val ddgJson = JSONObject(ddgJsonStr)

        val abstractText = ddgJson.optString("AbstractText", "")
        val heading = ddgJson.optString("Heading", query)
        val sourceUrl = ddgJson.optString("AbstractURL", "")

        if (abstractText.isBlank()) return null

        return WebSearchResponse(
            success = true,
            query = query,
            synthesizedSummary = abstractText,
            sources = listOf(
                SearchResultSource(
                    title = if (heading.isNotBlank()) heading else query,
                    snippet = abstractText,
                    url = if (sourceUrl.isNotBlank()) sourceUrl else "https://duckduckgo.com/?q=$encoded"
                )
            )
        )
    }

    private fun fetchUrlText(urlString: String): String? {
        return try {
            val conn = org.senda.browser.core.SendaNet.open(urlString)
            conn.requestMethod = "GET"
            conn.connectTimeout = 3500
            conn.readTimeout = 3500
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}
