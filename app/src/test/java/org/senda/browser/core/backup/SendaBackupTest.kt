package org.senda.browser.core.backup

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.senda.browser.core.security.VaultCsv

class SendaBackupTest {
    private val full = SendaBackup.Contents(
        created = 1_760_000_000_000,
        bookmarks = listOf(SendaBackup.Bookmark("Wikipedia", "https://es.wikipedia.org/wiki/Ñandú?a=1&b=2", 1L)),
        shortcuts = listOf(SendaBackup.Shortcut("Docs", "https://example.org/", "D", "#FF00AA")),
        history = listOf(SendaBackup.Visit("Página", "https://example.org/p", 5L)),
        passwords = listOf(VaultCsv.Login("https://login.example.org", "ana", "p\"w,\n日本", "note")),
        settings = JSONObject().put("theme_mode", "DARK").put("font_scale_percent", 110).put("https_only_mode", "ALL"),
        sections = SendaBackup.Section.entries.toSet()
    )

    @Test fun roundTripThroughAge() {
        val file = Age.encrypt(SendaBackup.encode(full, "test"), "una frase larga".toCharArray(), logN = 10)
        val back = SendaBackup.decode(Age.decrypt(file, "una frase larga".toCharArray()))
        assertEquals(full.bookmarks, back.bookmarks)
        assertEquals(full.shortcuts, back.shortcuts)
        assertEquals(full.history, back.history)
        assertEquals(full.passwords, back.passwords)
        assertEquals(full.settings.toString(), back.settings.toString())
        assertEquals(full.sections, back.sections)
        assertEquals(full.created, back.created)
    }

    @Test fun onlyChosenSectionsAreWritten() {
        val json = JSONObject(String(SendaBackup.encode(full.copy(sections = setOf(SendaBackup.Section.BOOKMARKS)), "test")))
        assertTrue(json.has("bookmarks"))
        assertFalse(json.has("passwords"))
        assertFalse(json.has("history"))
        assertFalse(json.has("settings"))
        assertEquals(setOf(SendaBackup.Section.BOOKMARKS), SendaBackup.decode(json.toString().toByteArray()).sections)
    }

    @Test fun dangerousAddressesAreDropped() {
        val json = JSONObject().put("format", "senda-backup").put("version", 1)
            .put("bookmarks", org.json.JSONArray()
                .put(JSONObject().put("title", "x").put("url", "javascript:alert(1)"))
                .put(JSONObject().put("title", "y").put("url", "file:///data/data"))
                .put(JSONObject().put("title", "z").put("url", "https://ok.example")))
            .put("history", org.json.JSONArray().put(JSONObject().put("url", "intent://x#Intent;end")))
        val c = SendaBackup.decode(json.toString().toByteArray())
        assertEquals(listOf("https://ok.example"), c.bookmarks.map { it.url })
        assertTrue(c.history.isEmpty())
    }

    @Test(expected = SendaBackup.InvalidBackupException::class)
    fun rejectsOtherJson() { SendaBackup.decode("""{"format":"something-else","version":1}""".toByteArray()) }

    @Test fun rejectsNewerVersionClearly() {
        try {
            SendaBackup.decode("""{"format":"senda-backup","version":2}""".toByteArray())
            throw AssertionError("accepted")
        } catch (e: SendaBackup.InvalidBackupException) {
            assertTrue(e.message!!.startsWith("Made by a newer"))
        }
    }

    @Test fun bookmarkHtmlFromBrowsers() {
        val html = """
            <!DOCTYPE NETSCAPE-Bookmark-file-1>
            <DL><p>
            <DT><A HREF="https://example.org/search?q=a&amp;b=c" ADD_DATE="1700000000" ICON="data:x">Search &amp; find &#241;</A>
            <DT><a href='http://old.example'><b>Old</b></a>
            <DT><A HREF="javascript:alert(1)">bad</A>
            <DT><A HREF="https://x.example" ADD_DATE="0"></A>
            </DL>
        """.trimIndent()
        val e = BookmarkHtml.parse(html)
        assertEquals(listOf("https://example.org/search?q=a&b=c", "http://old.example", "https://x.example"), e.map { it.url })
        assertEquals("Search & find ñ", e[0].title)
        assertEquals(1_700_000_000_000L, e[0].added)
        assertEquals("Old", e[1].title)
        assertEquals("https://x.example", e[2].title)
        assertEquals(null, e[2].added)
    }
}
