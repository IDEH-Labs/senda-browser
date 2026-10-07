package org.senda.browser.core.security

import android.content.Context
import android.util.Log
import org.senda.browser.core.SendaGeckoEngine
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * Resolución de dominios canónicos (eTLD+1) y sufijos públicos mediante la
 * Mozilla Public Suffix List oficial (MPL 2.0).
 *
 * Previene ataques de cruce de credenciales y phishing en dominios multi-inquilino
 * (como github.io, pages.dev, vercel.app, etc.).
 */
object PublicSuffixList {
    private const val TAG = "PublicSuffixList"
    private const val ASSET_NAME = "public_suffix_list.dat"

    // Fallback estático con sufijos multipartes comunes y plataformas compartidas habituales
    // si el archivo de recursos aún no se ha inicializado
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
                // Una actualización de la app puede traer una lista más nueva: la copia se rehace
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
                    // Copia dañada (p. ej. cierre a mitad de escritura): se rehace una vez
                    copyAsset(context, cacheFile)
                    ZipFile(cacheFile)
                }
                initialized = true
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo cargar la Public Suffix List desde assets: ${e.message}")
            }
        }
    }

    // Copia a un temporal y renombra, para no dejar nunca un archivo a medias
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
     * Obtiene el dominio registrable (eTLD+1) de un host.
     * Ejemplo: "alice.github.io" -> "alice.github.io"
     * Ejemplo: "login.banco.com.es" -> "banco.com.es"
     * Ejemplo: "es.wikipedia.org" -> "wikipedia.org"
     */
    fun getRegistrableDomain(host: String, context: Context? = null): String {
        if (host.isBlank() || !host.contains('.')) return host.trim().lowercase()
        val cleanHost = host.trim().lowercase().trimEnd('.')
        val parts = cleanHost.split('.').filter { it.isNotBlank() }
        if (parts.size <= 1) return cleanHost
        // Si es una dirección IPv4 numérica no se procesa como nombre de dominio
        if (parts.all { it.all { c -> c.isDigit() } }) return cleanHost

        val effectiveContext = context ?: SendaGeckoEngine.appContext
        if (!initialized && effectiveContext != null) {
            init(effectiveContext.applicationContext)
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
                    // 1. Reglas de excepción (!)
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

                    // 2. Reglas comodín y exactas
                    var matchedRuleLabels = 1 // Por defecto el TLD (1 etiqueta)
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

        // Fallback robusto con lista estática si el zip no está disponible
        val lastTwo = "${parts[parts.size - 2]}.${parts.last()}"
        if (FALLBACK_SUFFIXES.contains(lastTwo) && parts.size >= 3) {
            return "${parts[parts.size - 3]}.$lastTwo"
        }
        return lastTwo
    }
}
