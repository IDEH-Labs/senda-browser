package org.senda.browser.core.backup

import android.content.Context
import org.senda.browser.core.BookmarkItem
import org.senda.browser.core.HistoryItem
import org.senda.browser.core.PreferencesManager
import org.senda.browser.core.ZenShortcut
import org.senda.browser.core.security.SendaVaultManager

/**
 * Creates and restores encrypted backups. Everything happens on the phone: the backup is built in memory,
 * encrypted with the user's passphrase (age) and only then written where the user chose. Restoring adds
 * what is missing and never deletes anything.
 */
object BackupService {
    /** Largest backup file accepted (a real one with thousands of entries is a few MB). */
    const val MAX_FILE_BYTES = 64 * 1024 * 1024

    data class RestoreSummary(val bookmarks: Int, val shortcuts: Int, val settings: Int, val history: Int, val passwords: Int)

    /**
     * Gathers the chosen sections. Passwords are decrypted from the vault, so a fingerprint or PIN in the last
     * 30 s is needed (otherwise [org.senda.browser.core.security.VaultLockedException]).
     */
    fun collect(context: Context, prefs: PreferencesManager, sections: Set<SendaBackup.Section>): SendaBackup.Contents {
        val s = SendaBackup.Section.entries.filter { it in sections }.toSet()
        return SendaBackup.Contents(
            created = System.currentTimeMillis(),
            bookmarks = if (SendaBackup.Section.BOOKMARKS in s)
                prefs.getBookmarks().filter { SendaBackup.isWebUrl(it.url) }.map { SendaBackup.Bookmark(it.title, it.url, it.timestamp) }
            else emptyList(),
            shortcuts = if (SendaBackup.Section.BOOKMARKS in s)
                prefs.getZenShortcuts().filter { SendaBackup.isWebUrl(it.url) }.map { SendaBackup.Shortcut(it.title, it.url, it.monogram, it.colorHex) }
            else emptyList(),
            history = if (SendaBackup.Section.HISTORY in s)
                prefs.getHistory().filter { SendaBackup.isWebUrl(it.url) }.map { SendaBackup.Visit(it.title, it.url, it.timestamp) }
            else emptyList(),
            passwords = if (SendaBackup.Section.PASSWORDS in s) SendaVaultManager.exportLogins(context) else emptyList(),
            settings = if (SendaBackup.Section.SETTINGS in s) prefs.exportPortableSettings() else org.json.JSONObject(),
            sections = s
        )
    }

    fun encrypt(contents: SendaBackup.Contents, passphrase: CharArray, appVersion: String): ByteArray {
        val json = SendaBackup.encode(contents, "Senda $appVersion (Android)")
        return try { Age.encrypt(json, passphrase) } finally { java.util.Arrays.fill(json, 0) }
    }

    /** Throws [Age.AgeException] (wrong passphrase, damaged file…) or [SendaBackup.InvalidBackupException]. */
    fun decrypt(file: ByteArray, passphrase: CharArray): SendaBackup.Contents {
        val json = Age.decrypt(file, passphrase)
        return try { SendaBackup.decode(json) } finally { java.util.Arrays.fill(json, 0) }
    }

    /**
     * Restores the chosen sections. Existing accounts keep Senda's password unless [replacePasswords];
     * saving passwords needs a recent fingerprint or PIN.
     */
    fun restore(
        context: Context,
        prefs: PreferencesManager,
        c: SendaBackup.Contents,
        sections: Set<SendaBackup.Section>,
        replacePasswords: Boolean
    ): RestoreSummary {
        // Passwords first: if the vault is locked nothing else has been changed yet and the user can retry
        val passwords = if (SendaBackup.Section.PASSWORDS in sections && c.passwords.isNotEmpty())
            SendaVaultManager.importLogins(context, c.passwords, replacePasswords).let { it.added + it.replaced } else 0
        var bookmarks = 0
        var shortcuts = 0
        if (SendaBackup.Section.BOOKMARKS in sections) {
            bookmarks = prefs.mergeBookmarks(c.bookmarks.map { BookmarkItem(title = it.title, url = it.url, timestamp = it.created.takeIf { t -> t > 0 } ?: System.currentTimeMillis()) })
            shortcuts = prefs.mergeZenShortcuts(c.shortcuts.map { ZenShortcut(title = it.title, url = it.url, monogram = it.monogram, colorHex = it.color) })
        }
        val history = if (SendaBackup.Section.HISTORY in sections)
            prefs.mergeHistory(c.history.filter { it.visited > 0 }.map { HistoryItem(title = it.title, url = it.url, timestamp = it.visited) }) else 0
        val settings = if (SendaBackup.Section.SETTINGS in sections) prefs.importPortableSettings(c.settings) else 0
        return RestoreSummary(bookmarks, shortcuts, settings, history, passwords)
    }
}
