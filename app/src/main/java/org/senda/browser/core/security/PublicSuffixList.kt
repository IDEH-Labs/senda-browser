package org.senda.browser.core.security

import android.content.Context
import android.util.Log
import org.senda.browser.core.SendaGeckoEngine
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * Canonical domain (eTLD+1) and public suffix resolution using the
 * official Mozilla Public Suffix List (MPL 2.0).
 *
 * Prevents credential crossover and phishing attacks on multi-tenant domains
 * (such as github.io, pages.dev, vercel.app, etc.).
 */
object PublicSuffixList {
    private const val TAG = "PublicSuffixList"
    private const val ASSET_NAME = "public_suffix_list.dat"

    // Static fallback with common multi-part suffixes and common shared platforms
    // in case the resource file has not been initialized yet
    private val FALLBACK_SUFFIXES = setOf(
        "com.es", "nom.es", "org.es", "gob.es", "edu.es",
        "co.uk", "org.uk", "me.uk", "gov.uk", "ac.uk",
        "com.ar", "com.br", "com.mx", "com.co", "com.pe", "com.ve", "com.uy", "com.cl",
        "co.jp", "ne.jp", "ac.jp", "go.jp",
        "com.au", "net.au", "org.au", "edu.au",
        "github.io", "gitlab.io", "pages.dev", "vercel.app", "firebaseapp.com",
        "web.app", "azurewebsites.net", "herokuapp.com", "cloudfront.net"
    )

    private val tldRulesCache = ConcurrentHashMap<String, List<String>>()
    @Volatile private var cachedZipFile: ZipFile? = null
    @Volatile private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            try {
                val cacheFile = File(context.cacheDir, ASSET_NAME)
                // An app update may bring a newer list: the copy is rebuilt
                val installedAt = try {
                    context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
                } catch (_: Exception) {
                    Long.MAX_VALUE
                }
                if (!cacheFile.exists() || cacheFile.length() == 0L || cacheFile.lastModified() < installedAt) {
                    copyAsset(context, cacheFile)
                }
                cachedZipFile = try {
                    ZipFile(cacheFile)
                } catch (_: Exception) {
                    // Damaged copy (e.g. killed in the middle of writing): rebuilt once
                    copyAsset(context, cacheFile)
                    ZipFile(cacheFile)
                }
                initialized = true
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo cargar la Public Suffix List desde assets: ${e.message}")
            }
        }
    }

    /** Loads the list from an archive already on disk (JVM tests, which have no Android Context). */
    @androidx.annotation.VisibleForTesting
    internal fun initFromFile(file: File) {
        synchronized(this) {
            tldRulesCache.clear()
            cachedZipFile = ZipFile(file)
            initialized = true
        }
    }

    // Copy to a temporary file and rename, so a half-written file is never left behind
    private fun copyAsset(context: Context, target: File) {
        val tmp = File(target.parentFile, "$ASSET_NAME.tmp")
        context.assets.open(ASSET_NAME).use { input ->
            FileOutputStream(tmp).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw java.io.IOException("No se pudo renombrar $tmp")
        }
    }

    /**
     * Gets the registrable domain (eTLD+1) of a host.
     * Example: "alice.github.io" -> "alice.github.io"
     * Example: "login.banco.com.es" -> "banco.com.es"
     * Example: "es.wikipedia.org" -> "wikipedia.org"
     */
    fun getRegistrableDomain(host: String, context: Context? = null): String {
        if (host.isBlank() || !host.contains('.')) return host.trim().lowercase()
        val cleanHost = host.trim().lowercase().trimEnd('.')
        val parts = cleanHost.split('.').filter { it.isNotBlank() }
        if (parts.size <= 1) return cleanHost
        // A numeric IPv4 address is not processed as a domain name
        if (parts.all { it.all { c -> c.isDigit() } }) return cleanHost

        if (!initialized) {
            val effectiveContext = context ?: SendaGeckoEngine.appContext
            if (effectiveContext != null) init(effectiveContext.applicationContext)
        }

        val zip = cachedZipFile
        val tld = parts.last()
        if (zip != null) {
            try {
                val rules = tldRulesCache.computeIfAbsent(tld) {
                    val entry = zip.getEntry(tld) ?: return@computeIfAbsent emptyList()
                    zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).useLines { lines ->
                        lines.map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .map { if (it[0] == '\u0000' || it[0] == '\u0001') it.substring(1) else it }
                            .toList()
                    }
                }

                if (rules.isNotEmpty()) {
                    // 1. Exception rules (!)
                    for (rule in rules) {
                        if (rule.startsWith('!')) {
                            val exParts = rule.substring(1).split('.')
                            if (parts.size >= exParts.size && parts.takeLast(exParts.size) == exParts) {
                                val suffixLen = exParts.size - 1
                                if (parts.size <= suffixLen) return cleanHost
                                return parts.takeLast(suffixLen + 1).joinToString(".")
                            }
                        }
                    }

                    // 2. Wildcard and exact rules
                    var matchedRuleLabels = 1 // By default the TLD (1 label)
                    for (rule in rules) {
                        if (rule.startsWith('!')) continue
                        if (rule.startsWith("*.")) {
                            val subParts = rule.substring(2).split('.')
                            if (parts.size > subParts.size && parts.takeLast(subParts.size) == subParts) {
                                val labels = subParts.size + 1
                                if (labels > matchedRuleLabels) matchedRuleLabels = labels
                            }
                        } else {
                            val ruleParts = rule.split('.')
                            if (parts.size >= ruleParts.size && parts.takeLast(ruleParts.size) == ruleParts) {
                                val labels = ruleParts.size
                                if (labels > matchedRuleLabels) matchedRuleLabels = labels
                            }
                        }
                    }

                    if (parts.size <= matchedRuleLabels) return cleanHost
                    return parts.takeLast(matchedRuleLabels + 1).joinToString(".")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error resolviendo dominio con PSL: ${e.message}")
            }
        }

        // Robust fallback with the static list if the zip is not available
        val lastTwo = "${parts[parts.size - 2]}.${parts.last()}"
        if (FALLBACK_SUFFIXES.contains(lastTwo) && parts.size >= 3) {
            return "${parts[parts.size - 3]}.$lastTwo"
        }
        return lastTwo
    }
}
