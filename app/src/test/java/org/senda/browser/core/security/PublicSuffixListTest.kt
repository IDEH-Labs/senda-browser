package org.senda.browser.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * The vault decides which saved accounts a site may see from the registrable domain (eTLD+1).
 * Runs on the PC against the archive the build generates from the official list.
 */
class PublicSuffixListTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun load() {
            val archive = File("build/generated/senda-assets/psl/public_suffix_list.dat")
            check(archive.isFile) { "Run :app:generatePublicSuffixList first ($archive)" }
            PublicSuffixList.initFromFile(archive)
        }
    }

    private fun domain(host: String) = PublicSuffixList.getRegistrableDomain(host)

    @Test
    fun commonSites() {
        assertEquals("google.com", domain("accounts.google.com"))
        assertEquals("wikipedia.org", domain("es.wikipedia.org"))
        assertEquals("banco.com.es", domain("login.banco.com.es"))
        assertEquals("bbc.co.uk", domain("www.bbc.co.uk"))
    }

    @Test
    fun sharedHostingSitesAreSeparate() {
        assertEquals("alice.github.io", domain("alice.github.io"))
        assertEquals("bob.github.io", domain("www.bob.github.io"))
        assertNotEquals(domain("alice.github.io"), domain("bob.github.io"))
        assertNotEquals(domain("a.pages.dev"), domain("b.pages.dev"))
        // The public suffix itself is not a site anyone owns
        assertEquals("github.io", domain("github.io"))
    }

    @Test
    fun lookalikeDomainsBelongToTheRealOwner() {
        assertEquals("evil-phish.net", domain("paypal.com.evil-phish.net"))
        assertEquals("otro-sitio.net", domain("banco.com.otro-sitio.net"))
    }

    @Test
    fun wildcardAndExceptionRules() {
        // *.kawasaki.jp with the exception !city.kawasaki.jp
        assertEquals("a.b.kawasaki.jp", domain("x.a.b.kawasaki.jp"))
        assertEquals("city.kawasaki.jp", domain("www.city.kawasaki.jp"))
    }

    @Test
    fun internationalizedDomainsInPunycode() {
        // .中国 reaches Senda as xn--fiqs8s
        assertEquals("example.xn--fiqs8s", domain("www.example.xn--fiqs8s"))
        // 公司.cn (xn--55qx5d.cn) is a public suffix
        assertEquals("example.xn--55qx5d.cn", domain("shop.example.xn--55qx5d.cn"))
    }

    @Test
    fun ipAddressesAreKeptWhole() {
        assertEquals("192.168.1.8", domain("192.168.1.8"))
    }
}
