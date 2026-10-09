package org.senda.browser.core.security

/**
 * Reads and writes the password CSV files that browsers and password managers exchange.
 *
 * Import recognises the columns by their name, so it accepts the usual exports:
 * - Chrome, Brave, Edge and Google Password Manager: name,url,username,password,note
 * - Firefox (desktop): url,username,password,httpRealm,formActionOrigin,guid,…
 * - Bitwarden: folder,favorite,type,name,notes,fields,reprompt,login_uri,login_username,login_password,login_totp
 * - KeePassXC and others with Title/URL/Username/Password/Notes
 * Only website logins are taken (http/https); app passwords (android://), notes and incomplete rows are
 * counted as skipped, never guessed.
 *
 * Export writes the Chrome format, which Chrome, Firefox, Bitwarden, KeePassXC and Google Password
 * Manager import. Everything happens in memory; nothing is sent anywhere.
 */
object VaultCsv {

    /** Largest file accepted: a real export with thousands of passwords is far smaller. */
    const val MAX_IMPORT_BYTES = 10 * 1024 * 1024

    data class Login(val url: String, val username: String, val password: String, val note: String)

    class ImportResult(val logins: List<Login>, val skipped: Int)

    class UnsupportedFormatException : Exception("No url/password columns")

    private val URL_COLUMNS = listOf("url", "login_uri", "website", "web site", "origin", "uri")
    private val USER_COLUMNS = listOf("username", "login_username", "user name", "user", "login", "email")
    private val PASSWORD_COLUMNS = listOf("password", "login_password")
    private val NOTE_COLUMNS = listOf("note", "notes", "comments", "extra")

    /** RFC 4180: quoted fields with commas, doubled quotes and line breaks; CRLF or LF; UTF-8 BOM. */
    fun parseRows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = if (text.startsWith('\uFEFF')) 1 else 0
        fun endField() { row.add(field.toString()); field.setLength(0) }
        fun endRow() {
            endField()
            if (!(row.size == 1 && row[0].isEmpty())) rows.add(row)
            row = mutableListOf()
        }
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ } else quoted = false
                } else field.append(c)
            } else when (c) {
                '"' -> quoted = true
                ',' -> endField()
                '\r' -> { endRow(); if (i + 1 < text.length && text[i + 1] == '\n') i++ }
                '\n' -> endRow()
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }

    fun parseLogins(text: String): ImportResult {
        val rows = parseRows(text)
        if (rows.isEmpty()) throw UnsupportedFormatException()
        val header = rows.first().map { it.trim().lowercase() }
        fun column(names: List<String>) = names.firstNotNullOfOrNull { n -> header.indexOf(n).takeIf { it >= 0 } }
        val urlCol = column(URL_COLUMNS) ?: throw UnsupportedFormatException()
        val passCol = column(PASSWORD_COLUMNS) ?: throw UnsupportedFormatException()
        val userCol = column(USER_COLUMNS)
        val noteCol = column(NOTE_COLUMNS)
        // Bitwarden also exports notes and cards: only "login" rows are passwords
        val typeCol = header.indexOf("type").takeIf { it >= 0 }

        val logins = mutableListOf<Login>()
        var skipped = 0
        for (r in rows.drop(1)) {
            fun get(col: Int?) = col?.let { r.getOrNull(it) }.orEmpty()
            val type = get(typeCol).trim().lowercase()
            val url = get(urlCol).trim()
            val password = get(passCol)
            val scheme = url.substringBefore("://", "").lowercase()
            if ((typeCol != null && type.isNotEmpty() && type != "login") ||
                password.isEmpty() || (scheme != "http" && scheme != "https") ||
                url.substringAfter("://").substringBefore('/').isBlank()
            ) {
                skipped++
                continue
            }
            logins.add(Login(url, get(userCol).trim(), password, get(noteCol)))
        }
        return ImportResult(logins, skipped)
    }

    /** Chrome format. Fields are always quoted, so commas, quotes and line breaks survive. */
    fun write(logins: List<Login>): String {
        fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        val sb = StringBuilder("name,url,username,password,note\r\n")
        for (l in logins) {
            val host = l.url.substringAfter("://").substringBefore('/').substringBefore(':')
            sb.append(q(host)).append(',').append(q(l.url)).append(',').append(q(l.username)).append(',')
                .append(q(l.password)).append(',').append(q(l.note)).append("\r\n")
        }
        return sb.toString()
    }
}
