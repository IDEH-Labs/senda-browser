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
 * B5 — the real Tor network (only with `-e network 1`): it contacts Tor relays and check.torproject.org.
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
}
