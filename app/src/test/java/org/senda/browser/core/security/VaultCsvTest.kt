package org.senda.browser.core.security

import org.junit.Assert.assertEquals
import org.junit.Test

class VaultCsvTest {

    @Test fun chromeExport() {
        val csv = "name,url,username,password,note\r\n" +
            "github.com,https://github.com/login,ana,\"p,a\"\"ss\",\r\n" +
            "app,android://hash@com.example/,ana,secret,\r\n"
        val r = VaultCsv.parseLogins(csv)
        assertEquals(1, r.logins.size)
        assertEquals(1, r.skipped)
        assertEquals(VaultCsv.Login("https://github.com/login", "ana", "p,a\"ss", ""), r.logins[0])
    }

    @Test fun firefoxExport() {
        val csv = "\"url\",\"username\",\"password\",\"httpRealm\",\"formActionOrigin\",\"guid\",\"timeCreated\",\"timeLastUsed\",\"timePasswordChanged\"\n" +
            "\"https://accounts.example.org\",\"bob@example.org\",\"línea1\nlínea2\",,\"https://accounts.example.org\",\"{x}\",\"1\",\"2\",\"3\"\n" +
            "\"chrome://FirefoxAccounts\",\"\",\"token\",\"Firefox Accounts credentials\",,\"{y}\",\"1\",\"2\",\"3\"\n"
        val r = VaultCsv.parseLogins(csv)
        assertEquals(1, r.logins.size)
        assertEquals(1, r.skipped)
        assertEquals("línea1\nlínea2", r.logins[0].password)
        assertEquals("bob@example.org", r.logins[0].username)
    }

    @Test fun bitwardenExportOnlyLogins() {
        val csv = "folder,favorite,type,name,notes,fields,reprompt,login_uri,login_username,login_password,login_totp\n" +
            ",,login,Example,my note,,0,https://example.com,eve,pw1,\n" +
            ",,note,Secret note,text,,0,,,,\n" +
            ",,login,No password,,,0,https://example.net,eve,,\n"
        val r = VaultCsv.parseLogins(csv)
        assertEquals(listOf(VaultCsv.Login("https://example.com", "eve", "pw1", "my note")), r.logins)
        assertEquals(2, r.skipped)
    }

    @Test fun keepassxcHeadersAndBom() {
        val csv = "\uFEFF\"Group\",\"Title\",\"Username\",\"Password\",\"URL\",\"Notes\"\n\"Root\",\"Mail\",\"joe\",\"x\",\"http://mail.example\",\"\"\n"
        val r = VaultCsv.parseLogins(csv)
        assertEquals("http://mail.example", r.logins.single().url)
    }

    @Test(expected = VaultCsv.UnsupportedFormatException::class)
    fun rejectsFilesWithoutPasswordColumn() {
        VaultCsv.parseLogins("name,email\nAna,ana@example.com\n")
    }

    @Test(expected = VaultCsv.UnsupportedFormatException::class)
    fun rejectsEmptyFile() {
        VaultCsv.parseLogins("")
    }

    @Test fun exportThenImportKeepsEverything() {
        val logins = listOf(
            VaultCsv.Login("https://a.example/login", "ana", "p\"q,r\ns", "note, with comma"),
            VaultCsv.Login("https://b.example", "", "日本語🙂", "")
        )
        val back = VaultCsv.parseLogins(VaultCsv.write(logins))
        assertEquals(logins, back.logins)
        assertEquals(0, back.skipped)
    }
}
