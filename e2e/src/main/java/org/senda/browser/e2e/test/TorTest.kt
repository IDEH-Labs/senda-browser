package org.senda.browser.e2e.test

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * B5, B6 — the real Tor network (only with `-e network 1`): they contact Tor relays, check.torproject.org and
 * ipv4.myip.wtf (B6, a second site that shows the address it sees). Both are HTML: GeckoView does not offer the text
 * of a plain-text page to accessibility services, so an address in one could not be read.
 * Senda's Tor picks its own SOCKS port on each start and Senda asks it of its own TorService, so the person's Senda
 * (or Orbot) running at the same time does not matter. B7 checks that an app holding 9050 gets nothing.
 */
@RunWith(AndroidJUnit4::class)
class TorTest : E2eTest() {

    override fun startPrefs(): Map<String, Any> = mapOf("proxy_mode" to "TOR_ORBOT")

    @Before
    fun onlyWithNetworkAndFreePort() {
        assumeTrue("Not run: needs -e network 1 (it uses the real Tor network)",
            InstrumentationRegistry.getArguments().getString("network") == "1")
    }

    private fun usesTor(what: String) {
        E2e.openLink("https://check.torproject.org/")
        assertTrue("check.torproject.org did not say it uses Tor ($what). Screen: ${E2e.screenTexts()}",
            E2e.has(By.textContains("Congratulations"), TOR_FIRST_START_MS))
    }

    /** B5 — guards de65914. Cold start with Tor; after 90 s in the background, Tor works again on return. */
    @Test
    fun b5_torColdStartAndAfterBackground() {
        val start = System.currentTimeMillis()
        usesTor("cold start")
        val ready = (System.currentTimeMillis() - start) / 1000
        E2e.device.pressHome()
        Thread.sleep(90_000)
        E2e.launch()
        usesTor("after 90 s in the background")
        assertTrue("Tor took $ready s at the cold start", ready < TOR_FIRST_START_MS / 1000)
    }

    /** The Tor exit address [url] shows, read from the screen. */
    private fun exitAddress(url: String, site: String): String {
        E2e.openLink(url)
        // Only once the new site is in front, so the address read is never the previous page's. [site] is the end
        // of the host: the address bar cuts long ones from the start ("…roject.org")
        E2e.waitForAddress(site, site, TOR_FIRST_START_MS)
        var address: String? = null
        E2e.waitFor("an address from $url", 90_000) {
            address = IP.find(E2e.screenTexts())?.value
            address != null
        }
        return address!!
    }

    /**
     * B6 — a circuit per site (2026-10-13), as in Tor Browser: two sites leave through different circuits, and the
     * same site keeps its own. Without it Tor reuses one circuit for every site for 10 minutes, and all three
     * addresses are the same. Two different circuits can still end at the same exit (about 1-2 % of runs, depending
     * on the big exits' share): a failure of the first check is retried once by hand, as the docs say.
     */
    @Test
    fun b6_torCircuitPerSite() {
        // Tor replaces its first circuits for a while after it starts, even for a fixed SOCKS username (measured from
        // the PC through the e2e app's Tor on 2026-10-13: same username, new exit 9 s after start, then stable; longer
        // with no saved guards, as here). So it measures once the same site gives the same exit twice in a row
        var first = exitAddress("https://check.torproject.org/", "roject.org")
        var settled = false
        repeat(8) {
            if (settled) return@repeat
            Thread.sleep(10_000)
            val next = exitAddress("https://check.torproject.org/", "roject.org")
            settled = next == first
            first = next
        }
        assertTrue("Tor never kept one exit for check.torproject.org (last $first)", settled)
        // Both sites only have IPv4 addresses. Senda's Tor lets exits use IPv6, as Tor Browser does (PreferIPv6), so a
        // site with IPv6 shows another address even through the same exit: ifconfig.co did (2026-10-14). ip4.me
        // answers Tor exits with 403 Forbidden (measured the same day)
        val other = exitAddress("https://ipv4.myip.wtf/", "myip.wtf")
        val again = exitAddress("https://check.torproject.org/", "roject.org")
        assertTrue("Two sites left through the same exit $first: no circuit per site?", first != other)
        assertTrue("The same site changed exit ($first, then $again): its circuit was not kept", first == again)
    }

    private companion object {
        // Every test starts with no Tor data, so Tor first downloads the network's directory: measured from 22 s to
        // more than 90 s over mobile data on a moto g34 (2026-10-14)
        const val TOR_FIRST_START_MS = 180_000L
        // A static page with no cookies and no other requests, only for these tests (Codeberg IDEH-Labs/pages, branch
        // pages). Not on GitLab Pages: it cut about 2 of 3 Tor exit nodes, Codeberg Pages passed 12 of 12 (2026-10-14)
        const val FINGERPRINT_PAGE = "https://ideh-labs.codeberg.page/e2e/fingerprint.html"

        // IPv4 only: both sites have only IPv4 addresses, and an IPv6 pattern would also match times such as 12:34:56
        val IP = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
    }

    /**
     * B7 — guards the port fix of 2026-10-13. Another app listens on 9050, Tor's usual port, before Senda starts: here
     * the test process itself, as any app could. Before the fix Tor could not start and Senda sent its SOCKS requests
     * to that app. Now Tor picks another port: the page loads through Tor and the listener receives no connection.
     */
    @Test
    fun b7_anAppOn9050GetsNothing() {
        E2e.stop()
        val received = java.util.concurrent.atomic.AtomicInteger()
        val squatter = java.net.ServerSocket()
        squatter.reuseAddress = true
        squatter.bind(java.net.InetSocketAddress("127.0.0.1", 9050))
        val accepting = Thread {
            while (!squatter.isClosed) {
                try {
                    squatter.accept().close()
                    received.incrementAndGet()
                } catch (_: java.io.IOException) {
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            usesTor("with another app on 9050")
            assertTrue("The app on 9050 received ${received.get()} connections from Senda", received.get() == 0)
        } finally {
            squatter.close()
            accepting.join(2000)
        }
    }

    /** What [FINGERPRINT_PAGE] reads about the browser ("tz-offset=… | tz=… | cores=…"), once its script ran. */
    private fun fingerprint(what: String): String {
        E2e.openLink(FINGERPRINT_PAGE)
        var seen = ""
        val end = System.currentTimeMillis() + TOR_FIRST_START_MS
        while (seen.isEmpty() && System.currentTimeMillis() < end) {
            seen = Regex("""tz-offset=[^\n]*""").find(E2e.screenTexts())?.value.orEmpty()
            if (seen.isEmpty()) Thread.sleep(500)
        }
        assertTrue("The fingerprint page did not show its values ($what). Screen: ${E2e.screenTexts()}", seen.isNotEmpty())
        return seen
    }

    /**
     * B8 — protection against fingerprinting with Tor, as in Tor Browser (privacy.resistFingerprinting, 2026-10-14):
     * a site sees the time zone as UTC, not the phone's. Switching to a direct connection restarts Senda without it.
     * The phone must not be on UTC itself (the test phone is on UTC-5).
     */
    @Test
    fun b8_torResistsFingerprinting() {
        val tor = fingerprint("Tor")
        assertTrue("With Tor a site reads the phone's time zone: $tor", tor.startsWith("tz-offset=0 "))
        val before = E2e.pid()
        E2e.chooseDirect()
        E2e.waitFor("Senda to restart in direct mode") { E2e.pid().let { it != null && it != before } }
        E2e.waitForApp()
        val direct = fingerprint("direct")
        assertTrue("Without Tor the protection stayed on: $direct", !direct.startsWith("tz-offset=0 "))
    }
}
