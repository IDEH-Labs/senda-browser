package org.senda.browser.e2e.test

import org.junit.After
import org.junit.Before

/**
 * Every test starts from a freshly cleared e2e app whose proxy is the test proxy: page requests to 127.0.0.1 never
 * go through a proxy, and anything else the app tries to reach is refused inside the phone instead of going out.
 */
abstract class E2eTest {

    /** Network mode tests serve the pages on the phone's Wi-Fi address (see [LocalWeb.start]). */
    open val overNetworkAddress = false

    /** Settings the test needs before the app starts, on top of the test proxy. */
    open fun startPrefs(): Map<String, Any> = emptyMap()

    @Before
    fun setUpE2e() {
        E2e.guard()
        LocalWeb.start(overNetworkAddress)
        E2e.reset(testProxyPrefs() + startPrefs())
    }

    @After
    fun tearDownE2e() {
        runCatching { E2e.stop() }
        LocalWeb.stop()
    }

    fun testProxyPrefs(port: Int = LocalWeb.proxyPort): Map<String, Any> = mapOf(
        "proxy_mode" to "CUSTOM_HTTP",
        "proxy_host" to "127.0.0.1",
        "proxy_port" to port
    )
}
