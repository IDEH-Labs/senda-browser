package org.senda.browser.core.backup

import org.json.JSONArray
import org.json.JSONObject
import org.senda.browser.core.security.VaultCsv

/**
 * Contents of a Senda backup: an open, documented JSON document (docs/backup-format.md) that is encrypted
 * with age before it leaves memory. Any future Senda (Android or desktop) or any other program can read it.
 *
 * Reading is defensive: only http/https addresses are accepted for bookmarks, shortcuts and history (a
 * backup cannot plant javascript: or file: links), sizes are capped, and unknown fields are ignored.
 */
object SendaBackup {
    const val FORMAT = "senda-backup"
    const val VERSION = 1
    private const val MAX_ITEMS = 50_000
    private const val MAX_TEXT = 8_192

    enum class Section { BOOKMARKS, SETTINGS, HISTORY, PASSWORDS }

    data class Bookmark(val title: String, val url: String, val created: Long)
    data class Shortcut(val title: String, val url: String, val monogram: String, val color: String?)
    data class Visit(val title: String, val url: String, val visited: Long)

    data class Contents(
        val created: Long,
        val bookmarks: List<Bookmark> = emptyList(),
        val shortcuts: List<Shortcut> = emptyList(),
        val history: List<Visit> = emptyList(),
        val passwords: List<VaultCsv.Login> = emptyList(),
        val settings: JSONObject = JSONObject(),
        val sections: Set<Section> = emptySet()
    )

    class InvalidBackupException(message: String) : Exception(message)

    fun encode(c: Contents, app: String): ByteArray {
        val root = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("created", c.created)
            .put("app", app)
        if (Section.BOOKMARKS in c.sections) {
            root.put("bookmarks", JSONArray().apply {
                c.bookmarks.forEach { put(JSONObject().put("title", it.title).put("url", it.url).put("created", it.created)) }
            })
            root.put("shortcuts", JSONArray().apply {
                c.shortcuts.forEach {
                    put(JSONObject().put("title", it.title).put("url", it.url).put("monogram", it.monogram).apply { it.color?.let { col -> put("color", col) } })
                }
            })
        }
        if (Section.HISTORY in c.sections) root.put("history", JSONArray().apply {
            c.history.forEach { put(JSONObject().put("title", it.title).put("url", it.url).put("visited", it.visited)) }
        })
        if (Section.PASSWORDS in c.sections) root.put("passwords", JSONArray().apply {
            c.passwords.forEach { put(JSONObject().put("url", it.url).put("username", it.username).put("password", it.password).put("note", it.note)) }
        })
        if (Section.SETTINGS in c.sections) root.put("settings", c.settings)
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): Contents {
        val root = try { JSONObject(String(bytes, Charsets.UTF_8)) } catch (e: Exception) { throw InvalidBackupException("Not JSON") }
        if (root.optString("format") != FORMAT) throw InvalidBackupException("Not a Senda backup")
        val version = root.optInt("version", -1)
        if (version < 1) throw InvalidBackupException("Bad version")
        if (version > VERSION) throw InvalidBackupException("Made by a newer Senda (version $version)")
        val sections = mutableSetOf<Section>()

        fun array(name: String): JSONArray? = root.optJSONArray(name)?.also {
            if (it.length() > MAX_ITEMS) throw InvalidBackupException("Too many items in $name")
        }
        fun text(o: JSONObject, key: String) = o.optString(key, "").take(MAX_TEXT)

        val bookmarks = array("bookmarks")?.let { a ->
            sections += Section.BOOKMARKS
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i) }.mapNotNull { o ->
                val url = text(o, "url").trim()
                if (!isWebUrl(url)) null else Bookmark(text(o, "title").ifBlank { url }, url, o.optLong("created", 0L))
            }
        }.orEmpty()
        val shortcuts = array("shortcuts")?.let { a ->
            sections += Section.BOOKMARKS
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i) }.mapNotNull { o ->
                val url = text(o, "url").trim()
                val color = o.optString("color", "").takeIf { Regex("#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?").matches(it) }
                if (!isWebUrl(url)) null else Shortcut(text(o, "title").ifBlank { url }, url, text(o, "monogram").take(3), color)
            }
        }.orEmpty()
        val history = array("history")?.let { a ->
            sections += Section.HISTORY
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i) }.mapNotNull { o ->
                val url = text(o, "url").trim()
                if (!isWebUrl(url)) null else Visit(text(o, "title").ifBlank { url }, url, o.optLong("visited", 0L))
            }
        }.orEmpty()
        val passwords = array("passwords")?.let { a ->
            sections += Section.PASSWORDS
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i) }.mapNotNull { o ->
                val url = text(o, "url").trim()
                val password = o.optString("password", "")
                if (!isWebUrl(url) || password.isEmpty()) null
                else VaultCsv.Login(url, text(o, "username"), password, text(o, "note"))
            }
        }.orEmpty()
        val settings = root.optJSONObject("settings")?.also { sections += Section.SETTINGS } ?: JSONObject()
        return Contents(root.optLong("created", 0L), bookmarks, shortcuts, history, passwords, settings, sections)
    }

    fun isWebUrl(url: String): Boolean {
        val scheme = url.substringBefore("://", "").lowercase()
        return (scheme == "http" || scheme == "https") && url.substringAfter("://").substringBefore('/').isNotBlank() &&
            url.none { it.isISOControl() }
    }
}
