package org.senda.browser.e2e.test

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * B. Network modes (priority 1), with the test proxy and no internet. The pages are served on the phone's own
 * Wi-Fi address, because Gecko never sends 127.0.0.1 through a proxy.
 */
@RunWith(AndroidJUnit4::class)
class NetworkModesTest : E2eTest() {

    override val overNetworkAddress = true

    /** B1 — guards ce2f040. Started cold with an HTTP proxy, every page request goes through it. */
    @Test
    fun b1_coldStartThroughProxy() {
        E2e.openLink(LocalWeb.url("/whoami"))
        assertTrue("whoami did not say \"proxy\"", E2e.pageShows("proxy"))
        E2e.openLink(LocalWeb.url("/a.html"))
        assertTrue(E2e.pageShows("e2e-a"))
        assertEquals("Requests that reached the pages directly", 0, LocalWeb.directRequests.get())
        assertTrue("No request went through the proxy", LocalWeb.proxiedRequests.get() >= 2)
    }

    /** B2 — guards 5c1b1e5. From the proxy to direct: Senda restarts by itself and reopens the same tab, now direct. */
    @Test
    fun b2_proxyToDirectRestartsAndGoesDirect() {
        E2e.openLink(LocalWeb.url("/whoami"))
        assertTrue(E2e.pageShows("proxy"))
        val before = E2e.pid()
        E2e.chooseDirect()
        E2e.waitFor("Senda to restart by itself") { E2e.pid().let { it != null && it != before } }
        E2e.waitForApp()
        assertTrue("After the restart, the same tab does not reach the page directly", E2e.pageShows("direct"))
        assertTrue("The tab in front is not the same page", E2e.has(By.text("whoami"), 2000))
    }

    /** B3 — from direct to the proxy works without a restart. */
    @Test
    fun b3_directToProxyWithoutRestart() {
        E2e.openLink(LocalWeb.url("/whoami"))
        assertTrue(E2e.pageShows("proxy"))
        E2e.chooseDirect()
        E2e.waitFor("Senda to restart in direct mode") { E2e.pid() != null }
        E2e.waitForApp()
        assertTrue(E2e.pageShows("direct"))

        val pid = E2e.pid()
        E2e.chooseHttpProxy("127.0.0.1", LocalWeb.proxyPort)
        Thread.sleep(1500)
        E2e.openLink(LocalWeb.url("/whoami"))
        assertTrue("After choosing the proxy, the request did not go through it", E2e.pageShows("proxy"))
        assertEquals("Senda restarted when choosing a proxy", pid, E2e.pid())
    }
}

/** B4 — with the proxy chosen and down, pages fail instead of going out directly. */
@RunWith(AndroidJUnit4::class)
class ProxyDownTest : E2eTest() {

    override val overNetworkAddress = true

    override fun startPrefs(): Map<String, Any> = testProxyPrefs(port = LocalWeb.deadPort())

    @Test
    fun b4_proxyDownFailsClosed() {
        E2e.openLink(LocalWeb.url("/a.html"))
        assertFalse("The page loaded although the proxy is down", E2e.pageShows("e2e-a", 10_000))
        assertEquals("Requests that went around the stopped proxy", 0, LocalWeb.directRequests.get())
        assertNotEquals("The e2e app is not running", null, E2e.pid())
    }
}
