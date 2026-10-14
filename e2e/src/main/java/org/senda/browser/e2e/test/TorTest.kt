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
 * ifconfig.co (B6, a second site that shows the address it sees). Both are HTML: GeckoView does not offer the text
 * of a plain-text page to accessibility services, so an address in one could not be read.
 * Senda's built-in Tor always uses port 9050: if the person's Senda is running or the port is taken, the e2e app would
 * go through that other Tor, so the test does not run and says why.
 */
@RunWith(AndroidJUnit4::class)
class TorTest : E2eTest() {

    override fun startPrefs(): Map<String, Any> = mapOf("proxy_mode" to "TOR_ORBOT")

    @Before
    fun onlyWithNetworkAndFreePort() {
        assumeTrue("Not run: needs -e network 1 (it uses the real Tor network)",
            InstrumentationRegistry.getArguments().getString("network") == "1")
        assumeTrue("Not run: the person's Senda is running (its Tor would be shared)", E2e.sh("pidof org.senda.browser").isBlank())
        // 9050 is 235A in /proc/net/tcp; state 0A = listening
        val listening = E2e.sh("cat /proc/net/tcp /proc/net/tcp6").lines().any { it.contains(":235A ") && it.trim().split(Regex("\\s+")).getOrNull(3) == "0A" }
        assumeTrue("Not run: port 9050 is already in use", !listening)
    }

    private fun usesTor(what: String) {
        E2e.openLink("https://check.torproject.org/")
        assertTrue("check.torproject.org did not say it uses Tor ($what). Screen: ${E2e.screenTexts()}",
            E2e.has(By.textContains("Congratulations"), 90_000))
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
        assertTrue("Tor took $ready s at the cold start", ready < 120)
    }

    /** The Tor exit address [url] shows, read from the screen. */
    private fun exitAddress(url: String, site: String): String {
        E2e.openLink(url)
        // Only once the new site is in front, so the address read is never the previous page's. [site] is the end
        // of the host: the address bar cuts long ones from the start ("…roject.org")
        E2e.waitForAddress(site, site, 90_000)
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
        val other = exitAddress("https://ifconfig.co/", "ifconfig.co")
        val again = exitAddress("https://check.torproject.org/", "roject.org")
        assertTrue("Two sites left through the same exit $first: no circuit per site?", first != other)
        assertTrue("The same site changed exit ($first, then $again): its circuit was not kept", first == again)
    }

    private companion object {
        // IPv4 only: Tor exits answered both sites over IPv4 (2026-10-13), and an IPv6 pattern would also match times such as 12:34:56
        val IP = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
    }
}
