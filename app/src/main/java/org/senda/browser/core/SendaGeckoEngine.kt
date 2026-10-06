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
    val EMBEDDED_EXTENSION_IDS = setOf("uBlock0@raymondhill.net", "proxy@senda.org", "media@senda.org")
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
    private var cachedPrefs: PreferencesManager? = null
    var appContext: Context? = null
        private set

    fun initialize(context: Context, prefs: PreferencesManager? = null) {
        cachedPrefs = prefs
        appContext = context.applicationContext
        if (runtime != null) return

        // Configuración estricta de privacidad inspirada en LibreWolf y estándares de privacidad
        val settings = GeckoRuntimeSettings.Builder()
            // 1. Bloqueo estricto de rastreo y aislamiento dinámico de cookies
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
            // Sin detección de portal cautivo de Firefox: Android ya la hace y así Senda no contacta a Mozilla al arrancar
            .configFilePath(writeGeckoConfig(context))
            // 2. Habilitar about:config para que el usuario tenga control técnico
            .aboutConfigEnabled(true)
            // 3. Desactivar logs innecesarios en consola y proteger privacidad
            .consoleOutput(false)
            // 4. Modo de depuración web USB controlado por el usuario
            .remoteDebuggingEnabled(prefs?.remoteDebuggingEnabled ?: false)
            // 5. Global Privacy Control activado por defecto
            .globalPrivacyControlEnabled(true)
            // 6. Detección proactiva de baja memoria para purgar cachés antes de que el SO mate el proceso
            .lowMemoryDetection(true)
            .build()

        runtime = GeckoRuntime.create(context.applicationContext, settings)
        // Cuentas de la Bóveda en los formularios de inicio de sesión
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
                // Contraseñas: nada se rellena solo al cargar la página; se elige la cuenta al tocar el campo
                "  signon.autofillForms: false\n" +
                // Control multimedia (MediaSession): sin él Senda no sabe cuándo suena un video ni puede pausarlo
                // al pasarlo a la TV (podría oírse a la vez en el teléfono y en la TV)
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
     * Aplica en caliente todas las preferencias seleccionadas por el usuario en GeckoView
     */
    fun applyPreferences(prefs: PreferencesManager) {
        val rt = runtime ?: return
        val s = rt.settings

        // Depuración remota USB
        s.setRemoteDebuggingEnabled(prefs.remoteDebuggingEnabled)

        s.contentBlocking.setSafeBrowsing(
            if (prefs.safeBrowsingEnabled) ContentBlocking.SafeBrowsing.DEFAULT else ContentBlocking.SafeBrowsing.NONE
        )

        // Accesibilidad: Forzar zoom en sitios rebeldes y factor de tamaño de texto
        s.setForceUserScalableEnabled(prefs.forceEnableZoom)
        val effectiveScale = if (prefs.syncWebFontScale) prefs.uiFontScalePercent else prefs.fontScalePercent
        s.setFontSizeFactor((effectiveScale / 100f).coerceIn(0.5f, 2.0f))

        // Modo solo HTTPS
        val httpsMode = when (prefs.httpsOnlyMode) {
            "ALL_TABS" -> GeckoRuntimeSettings.HTTPS_ONLY
            "PRIVATE_ONLY" -> GeckoRuntimeSettings.HTTPS_ONLY_PRIVATE
            else -> GeckoRuntimeSettings.ALLOW_ALL
        }
        s.setAllowInsecureConnections(httpsMode)

        // DNS sobre HTTPS (DoH)
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

        // Bloqueo de contenido y aislamiento de cookies
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

        // Protección contra huellas digitales (la de Firefox: falsea núcleos, zona horaria, lienzo, etc.).
        // Las pestañas privadas siempre la llevan
        s.setFingerprintingProtection(prefs.blockFingerprinting)
        s.setFingerprintingProtectionPrivateBrowsing(true)

        // Traducciones y control global de privacidad
        s.setTranslationsOfferPopup(prefs.offerTranslations)
        s.setGlobalPrivacyControl(true)

        // Enrutamiento Tor / proxy seguro
        applyProxy(prefs)
    }

    /**
     * Crea una nueva sesión de Gecko (Pestaña) con configuraciones de seguridad
     */
    fun createSession(isPrivate: Boolean = true, allowJs: Boolean = true, open: Boolean = true): GeckoSession {
        val sessionSettings = org.mozilla.geckoview.GeckoSessionSettings.Builder()
            .usePrivateMode(isPrivate)
            .userAgentMode(org.mozilla.geckoview.GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .suspendMediaWhenInactive(false)
            .allowJavascript(allowJs)
            .build()

        val session = GeckoSession(sessionSettings)
        // Las sesiones para onNewSession deben entregarse sin abrir: las abre Gecko
        if (open) session.open(getRuntime())
        return session
    }

    /** Extensión esperando que el usuario acepte sus permisos (null si no hay ninguna). */
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
     * Inicializa las extensiones libres integradas por defecto (uBlock Origin)
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
                    false    // isTechnicalAndInteractionDataGranted: nunca se comparten datos de uso
                )
                // Solo las extensiones que trae Senda se instalan sin preguntar
                if (extension.id in EMBEDDED_EXTENSION_IDS) {
                    return org.mozilla.geckoview.GeckoResult.fromValue(response(true))
                }
                // Cualquier otra (instalada desde un archivo, un enlace o una web) muestra sus permisos y espera al usuario
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

                // uBlock y el proxy/Tor de Senda deben estar siempre activos (la app no deja desactivarlos). El
                // 2026-10-05 aparecieron desactivados en un teléfono tras reinstalar (userDisabled en el perfil,
                // causa sin determinar): sin uBlock no hay bloqueo de rastreadores. Se reactivan y se registra por qué
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

                // Refresco asíncrono para asegurar captura de UUIDs y opciones de extensiones
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    refreshExtensions()
                }, 1000)

                // 2. Senda Proxy & Tor Controller (Built-in folder). ensureBuiltIn la instala o la actualiza
                // cuando cambia su versión: con installBuiltIn solo, las correcciones nunca llegaban
                installBuiltInFolder("resource://android/assets/extensions/senda_proxy/", "proxy@senda.org") { ext ->
                    setupProxyMessageDelegate(ext)
                    android.util.Log.i("Senda", "Senda Proxy listo: ${ext.id} ${ext.metaData.version}")
                }

                // 3. Detector de videos para mostrarlos en la TV al duplicar: solo observa la red, no toca las páginas
                installBuiltInFolder("resource://android/assets/extensions/senda_media/", "media@senda.org") { ext ->
                    ext.setMessageDelegate(org.senda.browser.core.cast.SendaMediaCatalog.messageDelegate, "senda_media")
                }

                // Retirar el detector de video de Chromecast de una versión de prueba: no debe inyectar nada en las páginas
                list?.firstOrNull { it.id == "cast@senda.org" }?.let { controller.uninstall(it) }
            },
            { err ->
                android.util.Log.e("Senda", "Error al listar extensiones: ${err?.message}")
            }
        )
    }

    private fun installBuiltInFolder(resourceUri: String, id: String, onInstalled: (WebExtension) -> Unit) {
        try {
            val rt = runtime ?: return
            val controller = rt.webExtensionController
            val uri = if (resourceUri.endsWith("/")) resourceUri else "$resourceUri/"
            controller.ensureBuiltIn(uri, id).accept(
                { ext ->
                    if (ext != null) {
                        installedExtensions.removeAll { it.id == ext.id }
                        installedExtensions.add(ext)
                        controller.setAllowedInPrivateBrowsing(ext, true)
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
     * Permite instalar libremente cualquier extensión .xpi desde un archivo local o URI
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

    fun installExtensionFromUrl(
        context: Context,
        downloadUrl: String,
        onSuccess: (WebExtension) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val tempFile = java.io.File(context.cacheDir, "temp_ext_${System.currentTimeMillis()}.xpi")
                SendaNet.open(downloadUrl).inputStream.use { input ->
                    java.io.FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    installExtension(
                        Uri.fromFile(tempFile),
                        onSuccess = { ext ->
                            tempFile.delete()
                            onSuccess(ext)
                        },
                        onError = { err ->
                            tempFile.delete()
                            onError(err)
                        }
                    )
                }
            } catch (e: Throwable) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onError(e)
                }
            }
        }
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
