package org.senda.browser.core

import android.content.Context
import android.net.Uri
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object SendaGeckoEngine {

    private var runtime: GeckoRuntime? = null
    val EMBEDDED_EXTENSION_IDS = setOf("uBlock0@raymondhill.net", "proxy@senda.org", "media@senda.org", "labs@senda.org")
    val installedExtensions = mutableStateListOf<WebExtension>()
    var cachedUBlockOptionsUrl: String? = null
        private set
    var cachedUBlockBaseUrl: String? = null
        private set

    fun updateFromExtensionList(list: List<WebExtension>?) {
        if (list == null) return
        installedExtensions.clear()
        installedExtensions.addAll(list)
        val ublock = list.firstOrNull { it.id == "uBlock0@raymondhill.net" }
        val opts = ublock?.metaData?.optionsPageUrl
        if (!opts.isNullOrBlank() && opts.startsWith("moz-extension://")) {
            cachedUBlockOptionsUrl = opts
            val idx = opts.lastIndexOf('/')
            if (idx != -1) {
                cachedUBlockBaseUrl = opts.substring(0, idx + 1)
            }
        }
        val base = ublock?.metaData?.baseUrl
        if (!base.isNullOrBlank() && base.startsWith("moz-extension://")) {
            cachedUBlockBaseUrl = if (base.endsWith("/")) base else "$base/"
        }
    }

    private var proxyPort: WebExtension.Port? = null
    private var labsPort: WebExtension.Port? = null
    private var cachedPrefs: PreferencesManager? = null
    var appContext: Context? = null
        private set

    fun initialize(context: Context, prefs: PreferencesManager? = null) {
        cachedPrefs = prefs
        appContext = context.applicationContext
        if (runtime != null) return

        // Strict privacy configuration inspired by LibreWolf and privacy standards
        val settings = GeckoRuntimeSettings.Builder()
            // 1. Strict tracking blocking and dynamic cookie isolation
            .contentBlocking(
                ContentBlocking.Settings.Builder()
                    .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.STRICT)
                    .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)
                    .strictSocialTrackingProtection(true)
                    .queryParameterStrippingEnabled(true)
                    .queryParameterStrippingPrivateBrowsingEnabled(true)
                    .cookiePurging(true)
                    .safeBrowsing(if (prefs?.safeBrowsingEnabled != false) ContentBlocking.SafeBrowsing.DEFAULT else ContentBlocking.SafeBrowsing.NONE)
                    .build()
            )
            // No Firefox captive portal detection: Android already does it, and this way Senda does not contact Mozilla at startup
            .configFilePath(writeGeckoConfig(context))
            // 2. Enable about:config so the user has technical control
            .aboutConfigEnabled(true)
            // 3. Turn off unnecessary console logs and protect privacy
            .consoleOutput(false)
            // 4. USB web debugging mode controlled by the user
            .remoteDebuggingEnabled(prefs?.remoteDebuggingEnabled ?: false)
            // 5. Global Privacy Control on by default
            .globalPrivacyControlEnabled(true)
            // 6. Proactive low-memory detection to purge caches before the OS kills the process
            .lowMemoryDetection(true)
            .build()

        runtime = GeckoRuntime.create(context.applicationContext, settings)
        // Vault accounts in sign-in forms
        runtime?.autocompleteStorageDelegate = org.senda.browser.core.security.SendaVaultLoginStorage(context)

        if (prefs != null) {
            applyPreferences(prefs)
        }

        initializeBuiltInExtensions(context)
    }

    private fun writeGeckoConfig(context: Context): String {
        val file = java.io.File(context.filesDir, "geckoview-config.yaml")
        file.writeText(
            "prefs:\n" +
                "  network.captive-portal-service.enabled: false\n" +
                "  network.connectivity-service.enabled: false\n" +
                // Passwords: nothing is filled in on page load; the account is chosen when tapping the field
                "  signon.autofillForms: false\n" +
                // Security: restricts autofill to the exact origin, not to any subdomain
                "  signon.includeOtherSubdomainsInLookup: false\n" +
                // Media control (MediaSession): without it Senda does not know when a video is playing and cannot pause it
                // when sending it to the TV (it could be heard on the phone and the TV at the same time)
                "  media.hardwaremediakeys.enabled: true\n"
        )
        return file.absolutePath
    }

    fun getRuntime(): GeckoRuntime {
        return checkNotNull(runtime) {
            "SendaGeckoEngine no fue inicializado antes de solicitar el runtime"
        }
    }

    /**
     * Applies all the preferences chosen by the user to GeckoView on the fly
     */
    fun applyPreferences(prefs: PreferencesManager) {
        val rt = runtime ?: return
        val s = rt.settings

        // USB remote debugging
        s.setRemoteDebuggingEnabled(prefs.remoteDebuggingEnabled)

        s.contentBlocking.setSafeBrowsing(
            if (prefs.safeBrowsingEnabled) ContentBlocking.SafeBrowsing.DEFAULT else ContentBlocking.SafeBrowsing.NONE
        )

        // Accessibility: force zoom on stubborn sites and text size factor
        s.setForceUserScalableEnabled(prefs.forceEnableZoom)
        val effectiveScale = if (prefs.syncWebFontScale) prefs.uiFontScalePercent else prefs.fontScalePercent
        s.setFontSizeFactor((effectiveScale / 100f).coerceIn(0.5f, 2.0f))

        // HTTPS-only mode
        val httpsMode = when (prefs.httpsOnlyMode) {
            "ALL_TABS" -> GeckoRuntimeSettings.HTTPS_ONLY
            "PRIVATE_ONLY" -> GeckoRuntimeSettings.HTTPS_ONLY_PRIVATE
            else -> GeckoRuntimeSettings.ALLOW_ALL
        }
        s.setAllowInsecureConnections(httpsMode)

        // DNS over HTTPS (DoH)
        val dohUrl = when (prefs.dohProvider) {
            "QUAD9" -> "https://dns.quad9.net/dns-query"
            "MULLVAD" -> "https://doh.mullvad.net/dns-query"
            "CLOUDFLARE" -> "https://mozilla.cloudflare-dns.com/dns-query"
            "ADGUARD" -> "https://dns.adguard-dns.com/dns-query"
            "CUSTOM" -> prefs.customDohUrl.ifBlank { "https://dns.quad9.net/dns-query" }
            else -> "https://dns.quad9.net/dns-query"
        }

        when (prefs.dnsOverHttpsMode) {
            "MAX_PROTECTION" -> {
                s.setTrustedRecursiveResolverMode(GeckoRuntimeSettings.TRR_MODE_ONLY)
                s.setTrustedRecursiveResolverUri(dohUrl)
            }
            "INCREASED" -> {
                s.setTrustedRecursiveResolverMode(GeckoRuntimeSettings.TRR_MODE_FIRST)
                s.setTrustedRecursiveResolverUri(dohUrl)
            }
            else -> {
                s.setTrustedRecursiveResolverMode(GeckoRuntimeSettings.TRR_MODE_OFF)
            }
        }

        // Content blocking and cookie isolation
        val cb = s.contentBlocking
        val etpLevel = when (prefs.trackingProtectionLevel) {
            "STRICT" -> ContentBlocking.EtpLevel.STRICT
            "STANDARD" -> ContentBlocking.EtpLevel.DEFAULT
            else -> ContentBlocking.EtpLevel.STRICT
        }
        val cookieBeh = when (prefs.cookiePolicy) {
            "ISOLATE_THIRD_PARTY" -> ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS
            "BLOCK_ALL_THIRD_PARTY" -> ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY
            "BLOCK_ALL" -> ContentBlocking.CookieBehavior.ACCEPT_NONE
            else -> ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS
        }

        cb.setEnhancedTrackingProtectionLevel(etpLevel)
        cb.setCookieBehavior(cookieBeh)
        cb.setStrictSocialTrackingProtection(prefs.blockSocialTrackers)
        cb.setQueryParameterStrippingEnabled(true)
        cb.setQueryParameterStrippingPrivateBrowsingEnabled(true)
        cb.setCookiePurging(true)

        // Fingerprinting protection (Firefox's: spoofs cores, time zone, canvas, etc.).
        // Private tabs always have it
        s.setFingerprintingProtection(prefs.blockFingerprinting)
        s.setFingerprintingProtectionPrivateBrowsing(true)

        // Translations and Global Privacy Control
        s.setTranslationsOfferPopup(prefs.offerTranslations)
        s.setGlobalPrivacyControl(true)

        // Tor routing / secure proxy
        applyProxy(prefs)
    }

    /**
     * Creates a new Gecko session (tab) with security settings
     */
    fun createSession(isPrivate: Boolean = true, allowJs: Boolean = true, open: Boolean = true): GeckoSession {
        val sessionSettings = org.mozilla.geckoview.GeckoSessionSettings.Builder()
            .usePrivateMode(isPrivate)
            .userAgentMode(org.mozilla.geckoview.GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .suspendMediaWhenInactive(false)
            .allowJavascript(allowJs)
            .build()

        val session = GeckoSession(sessionSettings)
        // Sessions for onNewSession must be handed over unopened: Gecko opens them
        if (open) session.open(getRuntime())
        return session
    }

    /** Extension waiting for the user to accept its permissions (null if there is none). */
    data class ExtensionInstallRequest(
        val extension: WebExtension,
        val permissions: List<String>,
        val origins: List<String>,
        val onDecision: (Boolean) -> Unit
    )

    var pendingExtensionInstall by androidx.compose.runtime.mutableStateOf<ExtensionInstallRequest?>(null)
        private set

    fun resolveExtensionInstall(accept: Boolean) {
        val request = pendingExtensionInstall ?: return
        pendingExtensionInstall = null
        request.onDecision(accept)
    }

    /**
     * Initializes the free extensions bundled by default (uBlock Origin)
     */
    fun initializeBuiltInExtensions(context: Context) {
        val rt = runtime ?: return
        val controller = rt.webExtensionController

        controller.promptDelegate = object : org.mozilla.geckoview.WebExtensionController.PromptDelegate {
            override fun onInstallPromptRequest(
                extension: WebExtension,
                permissions: Array<out String>,
                origins: Array<out String>,
                dataCollectionPermissions: Array<out String>
            ): org.mozilla.geckoview.GeckoResult<WebExtension.PermissionPromptResponse> {
                fun response(granted: Boolean) = WebExtension.PermissionPromptResponse(
                    granted, // isPermissionsGranted
                    granted, // isPrivateModeGranted
                    false    // isTechnicalAndInteractionDataGranted: usage data is never shared
                )
                // Only the extensions Senda ships are installed without asking
                if (extension.id in EMBEDDED_EXTENSION_IDS) {
                    return org.mozilla.geckoview.GeckoResult.fromValue(response(true))
                }
                // Any other one (installed from a file, a link or a website) shows its permissions and waits for the user
                val result = org.mozilla.geckoview.GeckoResult<WebExtension.PermissionPromptResponse>()
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    pendingExtensionInstall?.onDecision?.invoke(false)
                    pendingExtensionInstall = ExtensionInstallRequest(
                        extension = extension,
                        permissions = permissions.toList(),
                        origins = origins.toList()
                    ) { accept -> result.complete(response(accept)) }
                }
                return result
            }
        }

        controller.list().accept(
            { list ->
                updateFromExtensionList(list)

                // uBlock and Senda's proxy/Tor must always be enabled (the app does not let you disable them). On
                // 2026-10-05 they showed up disabled on a phone after reinstalling (userDisabled in the profile,
                // cause unknown): without uBlock there is no tracker blocking. They are re-enabled and the reason is logged
                list?.filter { it.id in EMBEDDED_EXTENSION_IDS && !it.metaData.enabled }?.forEach { ext ->
                    android.util.Log.w("Senda", "Extensión integrada desactivada (${ext.id}, disabledFlags=${ext.metaData.disabledFlags}): se reactiva")
                    controller.enable(ext, org.mozilla.geckoview.WebExtensionController.EnableSource.USER).accept(
                        { refreshExtensions() },
                        { e -> android.util.Log.e("Senda", "No se pudo reactivar ${ext.id}: ${e?.message}") }
                    )
                }

                // 1. uBlock Origin
                val hasUBlock = list?.any { it.id == "uBlock0@raymondhill.net" } == true
                if (!hasUBlock) {
                    installSignedXpi(context, "ublock.xpi") { ext ->
                        android.util.Log.i("Senda", "uBlock Origin instalado con éxito: ${ext.id}")
                    }
                } else {
                    list?.forEach { ext ->
                        controller.setAllowedInPrivateBrowsing(ext, true)
                    }
                }

                // Asynchronous refresh to make sure extension UUIDs and options are captured
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    refreshExtensions()
                }, 1000)

                // 2. Senda Proxy & Tor Controller (built-in folder). ensureBuiltIn installs or updates it
                // when its version changes: with installBuiltIn alone, fixes never arrived
                installBuiltInFolder("resource://android/assets/extensions/senda_proxy/", "proxy@senda.org") { ext ->
                    setupProxyMessageDelegate(ext)
                    android.util.Log.i("Senda", "Senda Proxy listo: ${ext.id} ${ext.metaData.version}")
                }

                // 3. Video detector to show videos on the TV while mirroring: it only watches the network, it does not touch pages
                installBuiltInFolder("resource://android/assets/extensions/senda_media/", "media@senda.org") { ext ->
                    ext.setMessageDelegate(org.senda.browser.core.cast.SendaMediaCatalog.messageDelegate, "senda_media")
                }

                // 4. Senda Labs: the user's custom CSS and script, isolated from pages and never in private tabs
                installBuiltInFolder("resource://android/assets/extensions/senda_labs/", "labs@senda.org", allowPrivate = false) { ext ->
                    setupLabsMessageDelegate(ext)
                }

                // Remove the Chromecast video detector from a test version: it must not inject anything into pages
                list?.firstOrNull { it.id == "cast@senda.org" }?.let { controller.uninstall(it) }
            },
            { err ->
                android.util.Log.e("Senda", "Error al listar extensiones: ${err?.message}")
            }
        )
    }

    private fun installBuiltInFolder(resourceUri: String, id: String, allowPrivate: Boolean = true, onInstalled: (WebExtension) -> Unit) {
        try {
            val rt = runtime ?: return
            val controller = rt.webExtensionController
            val uri = if (resourceUri.endsWith("/")) resourceUri else "$resourceUri/"
            controller.ensureBuiltIn(uri, id).accept(
                { ext ->
                    if (ext != null) {
                        installedExtensions.removeAll { it.id == ext.id }
                        installedExtensions.add(ext)
                        controller.setAllowedInPrivateBrowsing(ext, allowPrivate)
                        onInstalled(ext)
                    }
                },
                { err ->
                    android.util.Log.e("Senda", "Error al instalar builtin $uri: ${err?.message}")
                }
            )
        } catch (e: Exception) {
            android.util.Log.e("Senda", "Excepción al instalar builtin $resourceUri: ${e.message}")
        }
    }

    private fun installSignedXpi(context: Context, assetName: String, onInstalled: (WebExtension) -> Unit) {
        try {
            val rt = runtime ?: return
            val controller = rt.webExtensionController
            val extDir = java.io.File(context.filesDir, "extensions")
            extDir.mkdirs()
            val targetFile = java.io.File(extDir, assetName)
            context.assets.open("extensions/$assetName").use { input ->
                java.io.FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            controller.install("file://${targetFile.absolutePath}").accept(
                { ext ->
                    if (ext != null) {
                        installedExtensions.removeAll { it.id == ext.id }
                        installedExtensions.add(ext)
                        controller.setAllowedInPrivateBrowsing(ext, true)
                        onInstalled(ext)
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            refreshExtensions()
                        }, 1200)
                    }
                },
                { err ->
                    android.util.Log.e("Senda", "Error al instalar $assetName: ${err?.message}")
                }
            )
        } catch (e: Exception) {
            android.util.Log.e("Senda", "Error al cargar $assetName de assets: ${e.message}")
        }
    }

    private fun setupProxyMessageDelegate(ext: WebExtension) {
        ext.setMessageDelegate(object : WebExtension.MessageDelegate {
            override fun onConnect(port: WebExtension.Port) {
                proxyPort = port
                android.util.Log.i("Senda", "Senda Proxy Native Port connected")
                cachedPrefs?.let { applyProxy(it) }
            }
            override fun onMessage(nativeMessage: String, message: Any, sender: WebExtension.MessageSender): org.mozilla.geckoview.GeckoResult<Any>? {
                android.util.Log.i("Senda", "Senda Proxy onMessage recibido: $message")
                val prefs = cachedPrefs
                val mode = prefs?.proxyMode ?: "OFF"
                val host = prefs?.proxyHost ?: "127.0.0.1"
                val port = prefs?.proxyPort ?: 9050
                val dns = prefs?.proxyDnsRemote ?: true

                val resp = org.json.JSONObject().apply {
                    put("type", "SET_PROXY")
                    put("mode", mode)
                    put("host", host)
                    put("port", port)
                    put("proxyDNS", dns)
                }
                return org.mozilla.geckoview.GeckoResult.fromValue(resp)
            }
        }, "senda_proxy")
    }

    private fun labsMessage(prefs: PreferencesManager?) = org.json.JSONObject().apply {
        put("type", "SET_LABS")
        put("css", prefs?.userCustomCss.orEmpty().trim())
        put("js", prefs?.userCustomScript.orEmpty().trim())
    }

    private fun setupLabsMessageDelegate(ext: WebExtension) {
        ext.setMessageDelegate(object : WebExtension.MessageDelegate {
            override fun onConnect(port: WebExtension.Port) {
                labsPort = port
            }
            override fun onMessage(nativeMessage: String, message: Any, sender: WebExtension.MessageSender): org.mozilla.geckoview.GeckoResult<Any>? =
                org.mozilla.geckoview.GeckoResult.fromValue(labsMessage(cachedPrefs))
        }, "senda_labs")
    }

    /** Sends the saved Senda Labs CSS and script to the Labs extension, which registers them again. */
    fun applyLabs(prefs: PreferencesManager) {
        cachedPrefs = prefs
        try {
            labsPort?.postMessage(labsMessage(prefs))
        } catch (e: Exception) {
            android.util.Log.e("Senda", "Error al enviar Senda Labs: ${e.message}")
        }
    }

    fun applyProxy(prefs: PreferencesManager) {
        cachedPrefs = prefs
        applyProxy(
            mode = prefs.proxyMode,
            host = prefs.proxyHost,
            port = prefs.proxyPort,
            proxyDns = prefs.proxyDnsRemote
        )
    }

    fun applyProxy(mode: String, host: String = "127.0.0.1", port: Int = 9050, proxyDns: Boolean = true) {
        try {
            val msg = org.json.JSONObject().apply {
                put("type", "SET_PROXY")
                put("mode", mode)
                put("host", host)
                put("port", port)
                put("proxyDNS", proxyDns)
            }
            proxyPort?.postMessage(msg)
            android.util.Log.i("Senda", "Enrutamiento Proxy aplicado: mode=$mode, host=$host, port=$port, dns=$proxyDns")
        } catch (e: Exception) {
            android.util.Log.e("Senda", "Error al postear configuración a Senda Proxy: ${e.message}")
        }
    }

    /**
     * Lets the user freely install any .xpi extension from a local file or URI
     */
    fun installExtension(uri: Uri, onSuccess: (WebExtension) -> Unit, onError: (Throwable) -> Unit) {
        val controller = getRuntime().webExtensionController
        controller.install(uri.toString())
            .accept(
                { extension: WebExtension? ->
                    if (extension != null) {
                        installedExtensions.removeAll { it.id == extension.id }
                        installedExtensions.add(extension)
                        controller.setAllowedInPrivateBrowsing(extension, true)
                        onSuccess(extension)
                    }
                },
                { throwable: Throwable? ->
                    if (throwable != null) {
                        onError(throwable)
                    }
                }
            )
    }

    fun uninstallExtension(extension: WebExtension, onComplete: () -> Unit) {
        if (extension.id in EMBEDDED_EXTENSION_IDS) {
            onComplete()
            return
        }
        val controller = getRuntime().webExtensionController
        controller.uninstall(extension).accept(
            {
                installedExtensions.removeAll { it.id == extension.id }
                onComplete()
            },
            {
                onComplete()
            }
        )
    }

    fun toggleExtension(extension: WebExtension, enable: Boolean, onComplete: () -> Unit) {
        if (extension.id in EMBEDDED_EXTENSION_IDS) {
            onComplete()
            return
        }
        val controller = getRuntime().webExtensionController
        val result = if (enable) {
            controller.enable(extension, org.mozilla.geckoview.WebExtensionController.EnableSource.USER)
        } else {
            controller.disable(extension, org.mozilla.geckoview.WebExtensionController.EnableSource.USER)
        }
        result.accept({ onComplete() }, { onComplete() })
    }

    fun refreshExtensions(onComplete: (() -> Unit)? = null) {
        val rt = runtime ?: run {
            onComplete?.invoke()
            return
        }
        try {
            rt.webExtensionController.list().accept(
                { list ->
                    updateFromExtensionList(list)
                    onComplete?.invoke()
                },
                {
                    onComplete?.invoke()
                }
            )
        } catch (_: Exception) {
            onComplete?.invoke()
        }
    }

    fun getUBlockExtension(): WebExtension? {
        val ext = installedExtensions.firstOrNull { it.id == "uBlock0@raymondhill.net" }
        if (ext == null || ext.metaData?.optionsPageUrl == null) {
            refreshExtensions()
        }
        return ext
    }

    fun getExtensionBaseUrl(ext: WebExtension): String? {
        val options = ext.metaData?.optionsPageUrl
        if (!options.isNullOrBlank() && options.startsWith("moz-extension://")) {
            val idx = options.lastIndexOf('/')
            if (idx != -1) {
                return options.substring(0, idx + 1)
            }
        }
        val base = ext.metaData?.baseUrl
        if (!base.isNullOrBlank() && base.startsWith("moz-extension://")) {
            return if (base.endsWith("/")) base else "$base/"
        }
        return null
    }

    fun getExtensionPageUrl(ext: WebExtension, relativePath: String = ""): String? {
        val base = getExtensionBaseUrl(ext) ?: return null
        val cleanPath = relativePath.removePrefix("/")
        return "$base$cleanPath"
    }

    fun getUBlockDashboardUrl(): String? {
        if (!cachedUBlockOptionsUrl.isNullOrBlank()) return cachedUBlockOptionsUrl
        val ext = getUBlockExtension()
        val options = ext?.metaData?.optionsPageUrl
        if (!options.isNullOrBlank() && options.startsWith("moz-extension://")) {
            cachedUBlockOptionsUrl = options
            return options
        }
        return if (ext != null) getExtensionPageUrl(ext, "dashboard.html") else null
    }

    fun getUBlockFiltersUrl(): String? {
        val base = cachedUBlockBaseUrl ?: (getUBlockExtension()?.let { getExtensionBaseUrl(it) })
        if (base != null) {
            return "${base}dashboard.html#3p-filters.html"
        }
        val dash = getUBlockDashboardUrl()
        if (dash != null) {
            val root = dash.substringBefore("dashboard.html")
            return "${root}dashboard.html#3p-filters.html"
        }
        return null
    }

    fun getUBlockPopupUrl(): String? {
        val base = cachedUBlockBaseUrl ?: (getUBlockExtension()?.let { getExtensionBaseUrl(it) })
        if (base != null) {
            return "${base}popup-fenix.html"
        }
        return null
    }

    fun clearBrowsingData(clearCookies: Boolean, clearCache: Boolean, clearStorage: Boolean = true) {
        val controller = runtime?.storageController ?: return
        var flags = 0L
        if (clearCookies) {
            flags = flags or org.mozilla.geckoview.StorageController.ClearFlags.COOKIES
        }
        if (clearCache) {
            flags = flags or org.mozilla.geckoview.StorageController.ClearFlags.ALL_CACHES
        }
        if (clearStorage) {
            flags = flags or org.mozilla.geckoview.StorageController.ClearFlags.DOM_STORAGES
        }
        if (flags != 0L) {
            controller.clearData(flags)
        }
    }
}
