package org.senda.browser.core

/**
 * Rules and formatting for Tor pluggable transports and bridge configuration in torrc.
 * Kept apart from [SendaTorManager] so bridge formatting and parser logic can be tested on PC.
 */
internal object TorBridgeRules {
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

    fun buildTorrcBridgeConfig(
        bridgeMode: String,
        customBridges: String,
        snowflakePort: Long = 0L,
        obfs4Port: Long = 0L
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
                (listOf("UseBridges 1") + plugins + lines).joinToString("\n")
            }
            else -> ""
        }
    }
}
