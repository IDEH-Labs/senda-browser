package org.senda.browser.core.backup

/**
 * Reads the "Netscape bookmark file" HTML that every browser exports and imports. Only http/https addresses
 * are taken; entities are decoded (browsers write "&" as "&amp;" inside HREF) and the original date is kept.
 */
object BookmarkHtml {
    data class Entry(val title: String, val url: String, val added: Long?)

    private val anchor = Regex("""<A\s+([^>]*)>(.*?)</A>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    fun parse(html: String): List<Entry> = anchor.findAll(html).mapNotNull { m ->
        val attrs = m.groupValues[1]
        fun attr(name: String) = Regex("""\b$name\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(attrs)?.groupValues?.get(1)
        val url = decodeEntities(attr("HREF") ?: return@mapNotNull null).trim()
        if (!SendaBackup.isWebUrl(url)) return@mapNotNull null
        val title = decodeEntities(m.groupValues[2].replace(Regex("<[^>]*>"), "")).trim().ifBlank { url }
        val added = attr("ADD_DATE")?.toLongOrNull()?.takeIf { it > 0 }?.let { it * 1000 }
        Entry(title, url, added)
    }.toList()

    fun decodeEntities(text: String): String =
        Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|amp|lt|gt|quot|apos|nbsp);").replace(text) { m ->
            when (val e = m.groupValues[1]) {
                "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"; "nbsp" -> " "
                else -> {
                    val code = if (e[1] == 'x' || e[1] == 'X') e.substring(2).toIntOrNull(16) else e.substring(1).toIntOrNull()
                    if (code != null && Character.isValidCodePoint(code)) String(Character.toChars(code)) else m.value
                }
            }
        }
}
