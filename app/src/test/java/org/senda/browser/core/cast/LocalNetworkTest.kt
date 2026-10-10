package org.senda.browser.core.cast

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cast relay must not let a page's playlist reach the user's own network. No test here needs DNS. */
class LocalNetworkTest {
    private fun local(host: String) = LocalNetwork.isLocalHost(host, resolve = false)

    @Test
    fun privateAndLoopbackAddressesAreLocal() {
        listOf(
            "127.0.0.1", "127.8.9.10", "10.0.0.1", "172.16.0.1", "172.31.255.255", "192.168.1.1", "169.254.1.1",
            "100.64.0.1", "100.127.255.255", "0.0.0.0", "224.0.0.251",
            "::1", "[::1]", "fe80::1", "fd12:3456:789a::1", "fc00::1", "::ffff:192.168.1.8"
        ).forEach { assertTrue(it, local(it)) }
    }

    @Test
    fun localNamesAreLocal() {
        listOf("localhost", "LOCALHOST", "app.localhost", "nas.local", "router.lan", "printer.home.arpa",
            "box.internal", "router", "nas.", "").forEach { assertTrue(it, local(it)) }
    }

    @Test
    fun publicAddressesAndNamesAreNotLocal() {
        listOf("8.8.8.8", "1.1.1.1", "172.32.0.1", "100.128.0.1", "192.169.0.1", "2001:4860:4860::8888",
            "example.com", "video.example.org", "localhost.example.com").forEach { assertFalse(it, local(it)) }
    }
}
