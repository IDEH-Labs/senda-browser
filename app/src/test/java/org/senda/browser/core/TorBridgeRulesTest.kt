package org.senda.browser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorBridgeRulesTest {

    @Test
    fun directMode_producesEmptyBridgeConfig() {
        val config = TorBridgeRules.buildTorrcBridgeConfig("NONE", "")
        assertEquals("", config)
    }

    @Test
    fun snowflakeMode_withoutPort_producesEmptyBridgeConfig() {
        val config = TorBridgeRules.buildTorrcBridgeConfig("SNOWFLAKE", "", snowflakePort = 0L)
        assertEquals("", config)
    }

    @Test
    fun snowflakeMode_withPort_producesUseBridgesPluginAndOfficialBridges() {
        val config = TorBridgeRules.buildTorrcBridgeConfig("SNOWFLAKE", "", snowflakePort = 54321L)
        assertTrue(config.contains("UseBridges 1"))
        assertTrue(config.contains("ClientTransportPlugin snowflake socks5 127.0.0.1:54321"))
        assertTrue(config.contains("Bridge snowflake 192.0.2.3:80"))
        assertTrue(config.contains("Bridge snowflake 192.0.2.4:80"))
    }

    @Test
    fun customBridges_prefixedWithBridgeAndIgnoresComments() {
        val input = """
            # This is a comment from BridgeDB
            obfs4 198.51.100.1:443 CERT=abc iat-mode=0
            
            Bridge obfs4 198.51.100.2:443 CERT=def iat-mode=0
        """.trimIndent()

        val lines = TorBridgeRules.buildBridgeLines(input)
        assertEquals(2, lines.size)
        assertEquals("Bridge obfs4 198.51.100.1:443 CERT=abc iat-mode=0", lines[0])
        assertEquals("Bridge obfs4 198.51.100.2:443 CERT=def iat-mode=0", lines[1])
    }

    @Test
    fun customBridges_detectsObfs4AndSnowflakeNeeds() {
        val obfs4Only = "obfs4 1.2.3.4:443 cert=xyz"
        assertTrue(TorBridgeRules.needsObfs4(obfs4Only))
        assertFalse(TorBridgeRules.needsSnowflake(obfs4Only))

        val sfOnly = "snowflake 1.2.3.4:443 cert=xyz"
        assertFalse(TorBridgeRules.needsObfs4(sfOnly))
        assertTrue(TorBridgeRules.needsSnowflake(sfOnly))

        val both = "obfs4 1.2.3.4:443\nsnowflake 5.6.7.8:80"
        assertTrue(TorBridgeRules.needsObfs4(both))
        assertTrue(TorBridgeRules.needsSnowflake(both))
    }

    @Test
    fun customBridges_detectsWebtunnelAndRejectsUnknownTransports() {
        val webtunnel = "webtunnel 198.51.100.3:443 url=https://bridge.example.com/ ver=0.0.1"
        assertTrue(TorBridgeRules.needsWebtunnel(webtunnel))
        assertEquals(null, TorBridgeRules.unsupportedTransport(webtunnel))

        val vanilla = "198.51.100.4:9001 0123456789ABCDEF0123456789ABCDEF01234567"
        assertEquals(null, TorBridgeRules.unsupportedTransport(vanilla))
        assertEquals("meek", TorBridgeRules.unsupportedTransport("meek 198.51.100.5:443"))
    }

    @Test
    fun customBridges_buildsFullConfigWithPlugins() {
        val input = "obfs4 198.51.100.1:443 cert=abc"
        val config = TorBridgeRules.buildTorrcBridgeConfig("CUSTOM", input, obfs4Port = 44444L)

        assertTrue(config.contains("UseBridges 1"))
        assertTrue(config.contains("ClientTransportPlugin obfs4 socks5 127.0.0.1:44444"))
        assertTrue(config.contains("Bridge obfs4 198.51.100.1:443 cert=abc"))
    }

    @Test
    fun customBridges_buildsWebtunnelPlugin() {
        val input = "webtunnel 198.51.100.3:443 url=https://bridge.example.com/ ver=0.0.1"
        val config = TorBridgeRules.buildTorrcBridgeConfig("CUSTOM", input, webtunnelPort = 45555L)

        assertTrue(config.contains("UseBridges 1"))
        assertTrue(config.contains("ClientTransportPlugin webtunnel socks5 127.0.0.1:45555"))
    }
}
