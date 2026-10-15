package org.senda.browser.core

/**
 * Rules and formatting for Tor pluggable transports and bridge configuration in torrc.
 * Kept apart from [SendaTorManager] so bridge formatting and parser logic can be tested on PC.
 */
internal object TorBridgeRules {
    /**
     * Transports bundled by Senda's IPtProxy dependency.  A custom bridge may
     * also be a vanilla bridge (and therefore need no local transport).
     *
     * Keep this list deliberately small and explicit.  Saving an arbitrary
     * transport name and then starting Tor without its client transport would
     * silently turn a bridge connection into a direct Tor connection.
     */
    enum class Transport(val bridgeName: String) {
        OBFS4("obfs4"),
        SNOWFLAKE("snowflake"),
        WEBTUNNEL("webtunnel")
    }

    /** Official fallback Snowflake bridges used in Tor Browser for Android. */
    val BUILTIN_SNOWFLAKE_BRIDGES = listOf(
        "Bridge snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72",
        "Bridge snowflake 192.0.2.4:80 8838024498816A039FCBBAB14E6E4CD963071865"
    )

    fun buildBridgeLines(customBridges: String): List<String> =
        customBridges.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { if (it.startsWith("bridge ", ignoreCase = true)) it else "Bridge $it" }

    fun needsObfs4(customBridges: String): Boolean =
        customBridges.lines().any { line ->
            val trimmed = line.trim()
            trimmed.isNotEmpty() && !trimmed.startsWith("#") && trimmed.contains("obfs4", ignoreCase = true)
        }

    fun needsSnowflake(customBridges: String): Boolean =
        customBridges.lines().any { line ->
            val trimmed = line.trim()
            trimmed.isNotEmpty() && !trimmed.startsWith("#") && trimmed.contains("snowflake", ignoreCase = true)
        }

    fun needsWebtunnel(customBridges: String): Boolean =
        customBridges.lines().any { line ->
            val trimmed = line.trim()
            trimmed.isNotEmpty() && !trimmed.startsWith("#") && trimmed.contains("webtunnel", ignoreCase = true)
        }

    /**
     * Names of the local pluggable transports required by custom bridge lines.
     * A line beginning with an address is a vanilla bridge.  An unknown first
     * token is a transport Senda cannot start, so callers must reject it rather
     * than launching Tor without `UseBridges`.
     */
    fun unsupportedTransport(customBridges: String): String? =
        buildBridgeLines(customBridges).firstNotNullOfOrNull { line ->
            val first = line.removePrefix("Bridge").trim().substringBefore(' ')
            when {
                first.equals("obfs4", ignoreCase = true) ||
                    first.equals("snowflake", ignoreCase = true) ||
                    first.equals("webtunnel", ignoreCase = true) -> null
                first.contains(':') || first.startsWith('[') -> null // vanilla IPv4/IPv6 bridge
                else -> first
            }
        }

    fun buildTorrcBridgeConfig(
        bridgeMode: String,
        customBridges: String,
        snowflakePort: Long = 0L,
        obfs4Port: Long = 0L,
        webtunnelPort: Long = 0L
    ): String {
        return when (bridgeMode) {
            "SNOWFLAKE" -> {
                if (snowflakePort > 0L) {
                    (listOf(
                        "UseBridges 1",
                        "ClientTransportPlugin snowflake socks5 127.0.0.1:$snowflakePort"
                    ) + BUILTIN_SNOWFLAKE_BRIDGES).joinToString("\n")
                } else ""
            }
            "CUSTOM" -> {
                val lines = buildBridgeLines(customBridges)
                if (lines.isEmpty()) return ""
                val plugins = mutableListOf<String>()
                if (needsObfs4(customBridges) && obfs4Port > 0L) {
                    plugins.add("ClientTransportPlugin obfs4 socks5 127.0.0.1:$obfs4Port")
                }
                if (needsSnowflake(customBridges) && snowflakePort > 0L) {
                    plugins.add("ClientTransportPlugin snowflake socks5 127.0.0.1:$snowflakePort")
                }
                if (needsWebtunnel(customBridges) && webtunnelPort > 0L) {
                    plugins.add("ClientTransportPlugin webtunnel socks5 127.0.0.1:$webtunnelPort")
                }
                (listOf("UseBridges 1") + plugins + lines).joinToString("\n")
            }
            else -> ""
        }
    }
}
