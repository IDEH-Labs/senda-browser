package org.senda.browser.e2e.test

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/** D. Extension panels and pages (priority 1), plus H8 (security indicator on extension pages). */
@RunWith(AndroidJUnit4::class)
class ExtensionsTest : E2eTest() {

    /** D1 — guards b4695a8. uBlock Origin's panel shows the site in front, not about:blank or its own page. */
    @Test
    fun d1_uBlockPanelShowsSiteInFront() {
        E2e.openLink(LocalWeb.url("/a.html"))
        assertTrue(E2e.pageShows("e2e-a"))
        E2e.openUBlockPanel()
        // The panel names the page it is about in its title ("uBlock Origin - <page title>"); the host itself is not
        // offered to accessibility, so the title is the evidence that it is the page in front
        val title = E2e.has(By.text("uBlock Origin - e2e-a"), 8000)
        assertTrue("The panel is not about the page in front. Screen: ${E2e.screenTexts()}", title)
        assertFalse(E2e.has(By.textContains("about:blank"), 500))
        assertFalse(E2e.has(By.textContains("moz-extension"), 500))
        // Found on 2026-10-12: Senda did not give Gecko its language, so the panel was in English
        assertTrue("uBlock's panel is not in Spanish. Screen: ${E2e.screenTexts()}", E2e.has(By.text("Bloqueado en esta página"), 3000))
    }

    /** D2 + D3 — guards b4695a8. uBlock's dashboard opens in a normal tab whose bar names the extension. */
    @Test
    fun d2d3_uBlockDashboardInNormalTab() {
        E2e.openLink(LocalWeb.url("/a.html"))
        assertTrue(E2e.pageShows("e2e-a"))
        E2e.openSettings()
        E2e.tapTextInList("uBlock Origin")
        E2e.tapText(Es.ublockDashboard)
        E2e.waitForApp()
        // The bar may cut the name on the left ("…ck Origin") when it does not fit: that still names uBlock Origin
        val named = E2e.has(By.pkg(E2e.APP).text(Pattern.compile("(uBlock Origin|….*Origin)")), 8000)
        assertTrue("The bar does not name uBlock Origin. Screen: ${E2e.screenTexts()}", named)
        val tabs = E2e.normalTabAddresses()
        assertTrue("The dashboard is not in a normal tab: $tabs", tabs.isNotEmpty())
    }

    /** D2 + D3 — LibreJS's "Settings…" opens in a normal tab whose bar says GNU LibreJS. */
    @Test
    fun d2d3_libreJsSettingsInNormalTab() {
        E2e.launch()
        E2e.setJavaScriptMode(Es.jsFree)
        E2e.leaveSettings()
        E2e.openLink(LocalWeb.url("/nonfree.html"))
        assertTrue(E2e.pageShows("nonfree"))
        E2e.openLibreJsPanel()
        E2e.tapText("Settings...")
        val named = E2e.has(By.pkg(E2e.APP).text(Pattern.compile("GNU LibreJS|LibreJS")), 8000)
        assertTrue("The bar does not name GNU LibreJS. Screen: ${E2e.screenTexts()}", named)
    }

    /** H8 — seen on 2026-10-11: the security indicator must not say "connection not secure" on an extension's own page. */
    @Test
    fun h8_noNotSecureOnExtensionPage() {
        E2e.openLink(LocalWeb.url("/a.html"))
        assertTrue(E2e.pageShows("e2e-a"))
        E2e.openSettings()
        E2e.tapTextInList("uBlock Origin")
        E2e.tapText(Es.ublockDashboard)
        assertTrue(E2e.has(By.textContains("uBlock — "), 8000))
        assertFalse(
            "The bar says \"connection not secure\" on uBlock Origin's own page. Screen: ${E2e.screenTexts()}",
            E2e.has(By.desc(Es.notSecure), 2000)
        )
    }

    /** H8b — found on 2026-10-12: the browser's own about: pages must not say "connection not secure" either. */
    @Test
    fun h8b_noNotSecureOnInternalPage() {
        E2e.launch()
        E2e.typeAddress("about:config")
        assertTrue(E2e.has(By.textContains("about:config"), 8000))
        assertFalse(
            "The bar says \"connection not secure\" on about:config. Screen: ${E2e.screenTexts()}",
            E2e.has(By.desc(Es.notSecure), 2000)
        )
    }
}
