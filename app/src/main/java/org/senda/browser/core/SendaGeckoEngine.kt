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
import androidx.core.net.toUri

object SendaGeckoEngine {

    private var runtime: GeckoRuntime? = null
    val EMBEDDED_EXTENSION_IDS = setOf("uBlock0@raymondhill.net", "proxy@senda.org", "media@senda.org", "labs@senda.org")

    const val UBLOCK_ID = "uBlock0@raymondhill.net"

    /** GNU LibreJS: bundled, but only installed and enabled while JavaScript is set to "free only". */
    const val LIBREJS_ID = "jid1-KtlZuoiikVfFew@jetpack"
    private const val LIBREJS_URI = "resource://android/assets/extensions/librejs/"

    /** Extensions that come with Senda: the extension list does not show them. */
    fun isBundledExtension(id: String) = id in EMBEDDED_EXTENSION_IDS || id == LIBREJS_ID

    // Buttons of the bundled extensions (uBlock Origin, LibreJS): clicking one opens its panel for the tab in front
    private val extensionActions = androidx.compose.runtime.mutableStateMapOf<String, WebExtension.Action>()

    /** true while extension [id] runs and its panel can be opened. */
    fun hasExtensionPanel(id: String) = id in extensionActions

    /** Panel an extension opened from its button; the browser shows it while it is not null. */
    var extensionPopup by androidx.compose.runtime.mutableStateOf<GeckoSession?>(null)
        private set

    fun closeExtensionPopup() {
        extensionPopup?.close()
        extensionPopup = null
    }

    /** Opens the panel of extension [id] for the tab in front; false if it is not running. */
    fun openExtensionPanel(id: String): Boolean {
        val action = extensionActions[id] ?: return false
        action.click()
        return true
    }

    // Mode last applied to Gecko; pages already open load it on their next reload
    var appliedJavaScriptMode: String? = null
        private set

    /**
     * Applies the JavaScript setting: OFF turns it off in Gecko; FREE runs GNU LibreJS, which blocks nonfree
     * nontrivial scripts. LibreJS is installed the first time FREE is chosen and only disabled afterwards, so its
     * lists of allowed and blocked sites stay.
     *
     * With FREE, scripts stay off until LibreJS is filtering ([holdScriptsForLibreJs]): its background script starts
     * after Gecko's own startup and only adds its filters after reading its storage, and pages loaded in between ran
     * nonfree scripts (measured on 2026-10-12: 9 of 10 cold starts).
     */
    fun applyJavaScriptMode(mode: String) {
        val rt = runtime ?: return
        appliedJavaScriptMode = mode
        val controller = rt.webExtensionController
        val installed = installedExtensions.firstOrNull { it.id == LIBREJS_ID }
        if (mode == "FREE") {
            if (!libreJsFiltering) holdScriptsForLibreJs() else rt.settings.setJavaScriptEnabled(true)
            installBuiltInFolder(LIBREJS_URI, LIBREJS_ID) { ext ->
                if (ext.metaData.enabled) {
                    setupExtensionPanel(ext)
                } else {
                    controller.enable(ext, org.mozilla.geckoview.WebExtensionController.EnableSource.APP).accept(
                        { enabled -> enabled?.let { replaceInstalled(it); setupExtensionPanel(it) } },
                        { e -> android.util.Log.e("Senda", "No se pudo activar LibreJS: ${e?.message}") }
                    )
                }
            }
        } else {
            releaseHeldScripts(reload = false)
            rt.settings.setJavaScriptEnabled(mode != "OFF")
            if (installed != null && installed.metaData.enabled) {
                // Enabled again later, its background starts again: it is not filtering until it says so
                libreJsFiltering = false
                extensionActions.remove(LIBREJS_ID)
                closeExtensionPopup()
                controller.disable(installed, org.mozilla.geckoview.WebExtensionController.EnableSource.APP).accept(
                    { disabled -> disabled?.let { replaceInstalled(it) } },
                    { e -> android.util.Log.e("Senda", "No se pudo desactivar LibreJS: ${e?.message}") }
                )
            }
        }
    }

    /** true once LibreJS has shown it is filtering in this process (it updated its button for a tab). */
    private var libreJsFiltering = false

    /** true while scripts are off waiting for LibreJS (only with JavaScript set to "free only"). */
    var scriptsHeldForLibreJs = false
        private set

    // Pages that started loading while scripts were held: loaded again once LibreJS filters, so they work
    private val sessionsLoadedWhileHeld = java.util.WeakHashMap<GeckoSession, Unit>()
    private var activeExtensionTab: java.lang.ref.WeakReference<GeckoSession>? = null
    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    private fun holdScriptsForLibreJs() {
        runtime?.settings?.setJavaScriptEnabled(false)
        if (scriptsHeldForLibreJs) return
        scriptsHeldForLibreJs = true
        pokeLibreJs(attempt = 0)
    }

    /**
     * LibreJS updates its button for the tab in front when a tab is activated, and its tab listeners are added in the
     * same step as its filters: telling it the tab in front again until it answers is how Senda knows it is filtering.
     * If it never answers, scripts stay off, as "free only" promises.
     */
    private fun pokeLibreJs(attempt: Int) {
        if (!scriptsHeldForLibreJs) return
        activeExtensionTab?.get()?.let { session ->
            listenToLibreJsOn(session)
            runtime?.webExtensionController?.let { controller ->
                controller.setTabActive(session, false)
                controller.setTabActive(session, true)
            }
        }
        mainHandler.postDelayed({ pokeLibreJs(attempt + 1) }, if (attempt < 60) 500L else 2000L)
    }

    private fun onLibreJsFiltering() {
        libreJsFiltering = true
        if (scriptsHeldForLibreJs && appliedJavaScriptMode == "FREE") {
            runtime?.settings?.setJavaScriptEnabled(true)
            releaseHeldScripts(reload = true)
        }
    }

    private fun releaseHeldScripts(reload: Boolean) {
        scriptsHeldForLibreJs = false
        val loaded = sessionsLoadedWhileHeld.keys.toList()
        sessionsLoadedWhileHeld.clear()
        if (reload) loaded.filter { it.isOpen }.forEach { it.reload() }
    }

    /** A tab started loading a page: if scripts are held, it is loaded again once LibreJS filters. */
    fun notePageStart(session: GeckoSession) {
        if (scriptsHeldForLibreJs) sessionsLoadedWhileHeld[session] = Unit
    }

    // LibreJS's button for a tab only reaches a delegate set on that tab's session
    private val libreJsTabDelegate = object : WebExtension.ActionDelegate {
        override fun onBrowserAction(extension: WebExtension, session: GeckoSession?, action: WebExtension.Action) {
            if (extension.id !in extensionActions) extensionActions[extension.id] = action
            onLibreJsFiltering()
        }
    }

    private fun listenToLibreJsOn(session: GeckoSession) {
        val libreJs = installedExtensions.firstOrNull { it.id == LIBREJS_ID && it.metaData.enabled } ?: return
        if (session.webExtensionController.getActionDelegate(libreJs) !== libreJsTabDelegate) {
            session.webExtensionController.setActionDelegate(libreJs, libreJsTabDelegate)
        }
    }

    fun setExtensionTabActive(session: GeckoSession, active: Boolean) {
        if (active) {
            activeExtensionTab = java.lang.ref.WeakReference(session)
            if (appliedJavaScriptMode == "FREE") listenToLibreJsOn(session)
        }
        runtime?.webExtensionController?.setTabActive(session, active)
    }

    private fun replaceInstalled(ext: WebExtension) {
        installedExtensions.removeAll { it.id == ext.id }
        installedExtensions.add(ext)
    }

    private val panelDelegate = object : WebExtension.ActionDelegate {
        override fun onBrowserAction(extension: WebExtension, session: GeckoSession?, action: WebExtension.Action) {
            // The default button (session null); clicking it opens the panel for the tab in front. After a cold
            // start LibreJS's default button did not always arrive (2026-10-12), so its button for a tab also counts
            if (session == null || extension.id !in extensionActions) extensionActions[extension.id] = action
            if (extension.id == LIBREJS_ID && session != null) onLibreJsFiltering()
        }

        override fun onOpenPopup(extension: WebExtension, action: WebExtension.Action) = showExtensionPopup()

        override fun onTogglePopup(extension: WebExtension, action: WebExtension.Action) = showExtensionPopup()
    }

    private fun setupExtensionPanel(ext: WebExtension) {
        ext.setActionDelegate(panelDelegate)
        // Its settings and pages it opens (the LibreJS report, the uBlock dashboard) go in a normal tab
        ext.tabDelegate = object : WebExtension.TabDelegate {
            override fun onNewTab(
                extension: WebExtension,
                createDetails: WebExtension.CreateTabDetails
            ): org.mozilla.geckoview.GeckoResult<GeckoSession>? {
                val session = createSession(isPrivate = false, open = false)
                return if (openExtensionTab(createDetails.url ?: "about:blank", session)) {
                    org.mozilla.geckoview.GeckoResult.fromValue(session)
                } else null
            }

            override fun onOpenOptionsPage(extension: WebExtension) {
                extension.metaData.optionsPageUrl?.let { openExtensionTab(it, null) }
            }
        }
    }

    private fun openExtensionTab(url: String, session: GeckoSession?): Boolean {
        val opener = org.senda.browser.ui.model.BrowserTab.tabOpener ?: return false
        closeExtensionPopup()
        val prefs = cachedPrefs
        opener(
            org.senda.browser.ui.model.BrowserTab(
                searchBaseUrl = prefs?.customSearchEngineUrl ?: "https://duckduckgo.com/?q=",
                prefs = prefs,
                initialUrl = url,
                existingSession = session
            ),
            false
        )
        return true
    }

    // GeckoView marks the session as an extension popup and loads the panel in it: it is not a tab
    private fun showExtensionPopup(): org.mozilla.geckoview.GeckoResult<GeckoSession> {
        closeExtensionPopup()
        val session = createSession(isPrivate = false)
        // The panel closes itself (window.close) after some buttons
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onCloseRequest(closing: GeckoSession) {
                if (closing === extensionPopup) closeExtensionPopup()
            }
        }
        extensionPopup = session
        return org.mozilla.geckoview.GeckoResult.fromValue(session)
    }

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
                    .safeBrowsing(ContentBlocking.SafeBrowsing.NONE)
                    .build()
            )
            // No Firefox captive portal detection: Android already does it, and this way Senda does not contact Mozilla at startup
            .configFilePath(writeGeckoConfig(context, prefs))
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
            // 7. JavaScript off if the user chose it, and with "free only" until LibreJS is filtering (applyJavaScriptMode)
            .javaScriptEnabled(prefs?.javascriptMode == null || prefs.javascriptMode == "NORMAL")
            // Language of the engine: extensions (uBlock's panel was in English) and websites follow Senda's language
            .locales(geckoLocales(prefs))
            .build()

        runtime = GeckoRuntime.create(context.applicationContext, settings)
        // Vault accounts in sign-in forms
        runtime?.autocompleteStorageDelegate = org.senda.browser.core.security.SendaVaultLoginStorage(context)

        if (prefs != null) {
            applyPreferences(prefs)
        }

        initializeBuiltInExtensions(context)
    }

    /**
     * Tor or the user's proxy, set in Gecko itself before the first request. The proxy extension loads a moment
     * after the engine: until then the tab restored at startup went out directly, with the real IP (measured on
     * 2026-10-10: 3 of 3 cold starts with Tor on gave IsTor:false). Nothing here for "OFF".
     */
    private fun startupProxyPrefs(prefs: PreferencesManager?): String {
        val mode = prefs?.proxyMode ?: return ""
        startupProxyMode = mode
        // Only characters of a host name or address: the value goes into YAML
        val host = prefs.proxyHost.filter { it.isLetterOrDigit() || it in ".:-[]" }.ifBlank { "127.0.0.1" }
        val port = prefs.proxyPort.coerceIn(1, 65535)
        return when (mode) {
            // Tor's socket, as Tor Browser sets it (TorLauncherUtil.setProxyConfiguration: a file: URL, port 0). Known
            // before Tor starts: a request made before the proxy extension is ready already goes to Senda's Tor, and
            // fails while Tor has not opened the socket
            "TOR_ORBOT" -> socksPrefs(appContext?.let { SendaTorManager.socksSocketUrl(it) } ?: "file:///", 0, remoteDns = true)
            "CUSTOM_SOCKS5" -> socksPrefs(host, port, prefs.proxyDnsRemote)
            "CUSTOM_HTTP" -> "  network.proxy.type: 1\n" +
                "  network.proxy.http: \"$host\"\n  network.proxy.http_port: $port\n" +
                "  network.proxy.ssl: \"$host\"\n  network.proxy.ssl_port: $port\n"
            else -> ""
        }
    }

    /** Proxy mode set in Gecko's prefs when this process started ([startupProxyPrefs]). */
    private var startupProxyMode = "OFF"

    /**
     * Whether switching to [mode] needs Senda to start again: Gecko keeps the proxy set at startup when the proxy
     * extension asks for a direct connection (ProxyChannelFilter: "direct" returns the default proxy). Any other
     * proxy from the extension does replace it.
     */
    fun needsRestartFor(mode: String) = mode == "OFF" && startupProxyMode in setOf("TOR_ORBOT", "CUSTOM_SOCKS5", "CUSTOM_HTTP") ||
        // Tor's protection against fingerprinting is set when Gecko starts ([torFingerprintingPrefs])
        (mode == "TOR_ORBOT") != (startupProxyMode == "TOR_ORBOT")

    /**
     * With Tor, protection against fingerprinting as in Tor Browser: the prefs of its "Fingerprinting" block that apply
     * to Android (tor-browser 15.0, browser/app/profile/001-base-profile.js). privacy.resistFingerprinting makes
     * Senda's Tor tabs report the same values to every site (screen, time zone, timers, canvas…), so one site cannot
     * recognize them on another by those. Letterboxing is left out: its code (RFPHelper.sys.mjs) is never loaded by
     * GeckoView. Senda still does not look like Tor Browser to sites (another engine version, other extensions).
     * Only read when Gecko starts, so switching to or from Tor restarts Senda ([needsRestartFor]).
     */
    private fun torFingerprintingPrefs(prefs: PreferencesManager?): String {
        if (prefs?.proxyMode != "TOR_ORBOT") return ""
        return "  privacy.resistFingerprinting: true\n" +
            "  privacy.resistFingerprinting.exemptedDomains: \"\"\n" +
            // Without it the canvas is read completely white (Tor Browser locks it to true)
            "  privacy.resistFingerprinting.randomDataOnCanvasExtract: true\n" +
            "  privacy.resistFingerprinting.skipEarlyBlankFirstPaint: true\n" +
            "  privacy.resistFingerprinting.block_mozAddonManager: true\n" +
            "  webgl.disable-fail-if-major-performance-caveat: true\n" +
            "  webgl.enable-webgl2: false\n" +
            "  gfx.offscreencanvas.enabled: false\n" +
            "  media.benchmark.vp9.threshold: 0\n" +
            "  media.hevc.enabled: false\n" +
            "  dom.webmidi.enabled: false\n" +
            "  media.devices.enumerate.legacy.enabled: false\n" +
            "  dom.netinfo.enabled: false\n" +
            "  network.http.referer.XOriginTrimmingPolicy: 2\n"
    }

    /**
     * Security level as in Tor Browser (SecurityLevel.sys.mjs, tor-browser-153.5.0esr-16.0-1: kSecuritySettings and
     * its NoScript policy). Each pref was measured on the phone on 2026-10-14 to change what a page sees:
     * JIT off makes a CPU loop about 11 times slower (125 to 1383 ms), mathml.disabled stops MathML drawing,
     * svg.disabled leaves an SVG at size 0, webgl.disabled and wasm=false remove WebGL and WebAssembly.
     * Not set here: audio and video (Tor Browser blocks them with NoScript, which Senda does not have), and
     * JavaScript, which is the "JavaScript" setting. Only read when Gecko starts, so changing the level restarts
     * Senda ([needsRestartForLevel]). STANDARD sets nothing.
     */
    private fun securityLevelPrefs(prefs: PreferencesManager?): String {
        val level = prefs?.securityLevel ?: return ""
        startupSecurityLevel = level
        if (level == "STANDARD") return ""
        return "  javascript.options.ion: false\n" +
            "  javascript.options.baselinejit: false\n" +
            "  javascript.options.native_regexp: false\n" +
            "  javascript.options.wasm: false\n" +
            "  mathml.disabled: true\n" +
            "  gfx.font_rendering.graphite.enabled: false\n" +
            "  gfx.font_rendering.opentype_svg.enabled: false\n" +
            "  webgl.disabled: true\n" +
            // Only SAFEST: SAFER keeps SVG, as in Tor Browser
            (if (level == "SAFEST") "  svg.disabled: true\n" else "")
    }

    /** Security level set in Gecko's prefs when this process started ([securityLevelPrefs]). */
    private var startupSecurityLevel = "STANDARD"

    /** Whether choosing [level] needs Senda to start again: its Gecko prefs are only read at startup. */
    fun needsRestartForLevel(level: String) = level != startupSecurityLevel

    private fun socksPrefs(host: String, port: Int, remoteDns: Boolean) =
        "  network.proxy.type: 1\n  network.proxy.socks: \"$host\"\n  network.proxy.socks_port: $port\n" +
            "  network.proxy.socks_version: 5\n  network.proxy.socks_remote_dns: $remoteDns\n"

    /**
     * Without a hardware AV1 decoder (e.g. Snapdragon 695), AV1 is decoded by the CPU: YouTube serves it to Firefox
     * when it is available, and the phone heats up and drops frames. Without AV1, sites send VP9 or H.264, which the
     * phone's hardware decodes. Phones that do have it keep AV1.
     */
    private fun av1Prefs(): String {
        val hardwareAv1 = runCatching {
            android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                // Before Android 10 there is no isHardwareAccelerated: Android's own software decoders have these names
                val hardware = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) info.isHardwareAccelerated
                    else !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")
                !info.isEncoder && hardware &&
                    info.supportedTypes.any { it.equals("video/av01", ignoreCase = true) }
            }
        }.getOrDefault(true)
        return if (hardwareAv1) "" else "  media.av1.enabled: false\n"
    }

    private fun writeGeckoConfig(context: Context, prefs: PreferencesManager?): String {
        val file = java.io.File(context.filesDir, "geckoview-config.yaml")
        file.writeText(
            "prefs:\n" +
                "  network.captive-portal-service.enabled: false\n" +
                "  network.connectivity-service.enabled: false\n" +
                // GeckoView defaults that LibreWolf, arkenfox and GNU IceCat also turn off (compared on 2026-10-12):
                // no country lookup from the IP address at location.services.mozilla.com (seen in Senda's traffic)
                "  browser.region.network.url: \"\"\n" +
                "  browser.region.update.enabled: false\n" +
                // the bundled extensions do not need their metadata from addons.mozilla.org, which got the list of
                // installed extensions (services.addons.mozilla.org, seen in Senda's traffic at startup)
                "  extensions.getAddons.cache.enabled: false\n" +
                // no connections to links and servers the person has not opened
                "  network.prefetch-next: false\n" +
                "  network.dns.disablePrefetch: true\n" +
                "  network.predictor.enabled: false\n" +
                "  network.http.speculative-parallel-limit: 0\n" +
                // WebRTC: no local addresses; through the proxy when there is one (the proxy extension also does it)
                "  media.peerconnection.ice.default_address_only: true\n" +
                "  media.peerconnection.ice.proxy_only_if_behind_proxy: true\n" +
                // PDFs do not run scripts
                "  pdfjs.enableScripting: false\n" +
                // No DRM: EME, the browser feature that loads DRM modules (here Android's Widevine), is off, as in
                // GNU IceCat. GNU's free system distribution guidelines rule out browsers that implement it
                "  media.eme.enabled: false\n" +
                "  media.mediadrm-widevinecdm.visible: false\n" +
                "  media.eme.require-app-approval: true\n" +
                // Passwords: nothing is filled in on page load; the account is chosen when tapping the field
                "  signon.autofillForms: false\n" +
                // Accounts of the same site (registrable domain, Mozilla's Public Suffix List) are offered on its other
                // addresses too, as in Firefox: sign-in often lives on another one (my.fsf.org → cas.fsf.org). Nothing
                // is filled in by itself, the selector names each account's address, and the password needs the
                // fingerprint or PIN
                "  signon.includeOtherSubdomainsInLookup: true\n" +
                // Media control (MediaSession): without it Senda does not know when a video is playing and cannot pause it
                // when sending it to the TV (it could be heard on the phone and the TV at the same time)
                "  media.hardwaremediakeys.enabled: true\n" +
                // With Tor or a proxy, a connection that fails through it must fail, never go out directly with the
                // real IP; nor may anything bypass the proxy (the same as Tor Browser)
                "  network.proxy.failover_direct: false\n" +
                "  network.proxy.allow_bypass: false\n" +
                // No Mozilla experiments, studies or health report, as in LibreWolf and arkenfox. GeckoView 158 does
                // not start them, yet it downloads their collections (nimbus-*, normandy-*): off in case it ever does
                "  app.shield.optoutstudies.enabled: false\n" +
                "  nimbus.rollouts.enabled: false\n" +
                "  datareporting.healthreport.uploadEnabled: false\n" +
                "  app.normandy.enabled: false\n" +
                googleSafeBrowsingOffPrefs() +
                av1Prefs() +
                startupProxyPrefs(prefs) +
                torFingerprintingPrefs(prefs) +
                securityLevelPrefs(prefs)
        )
        return file.absolutePath
    }

    /**
     * Senda does not use Google Safe Browsing. Turning it off in GeckoView was not enough: measured on 2026-10-11, the
     * "google5" provider still downloaded a Google list (the "global cache"). Every Google feature is off, the v5
     * provider is not created, and the servers of every Google provider are empty, so nothing can reach them.
     */
    private fun googleSafeBrowsingOffPrefs(): String {
        val off = listOf(
            "browser.safebrowsing.malware.enabled", "browser.safebrowsing.phishing.enabled",
            "browser.safebrowsing.downloads.enabled", "browser.safebrowsing.downloads.remote.enabled",
            "browser.safebrowsing.globalCache.enabled", "browser.safebrowsing.provider.google5.enabled"
        )
        val noServer = listOf("google", "google4", "google5").flatMap { provider ->
            listOf("updateURL", "gethashURL").map { "browser.safebrowsing.provider.$provider.$it" }
        } + "browser.safebrowsing.downloads.remote.url"
        return off.joinToString("") { "  $it: false\n" } + noServer.joinToString("") { "  $it: \"\"\n" }
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

        geckoLocales(prefs).let { if (!it.contentEquals(s.locales)) s.setLocales(it) }

        // JavaScript: only when it changes (FREE checks the LibreJS install)
        if (prefs.javascriptMode != appliedJavaScriptMode) applyJavaScriptMode(prefs.javascriptMode)

        // USB remote debugging
        s.setRemoteDebuggingEnabled(prefs.remoteDebuggingEnabled)

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

    /** Senda's language for Gecko: the one chosen in Settings, or the phone's languages with "system default". */
    private fun geckoLocales(prefs: PreferencesManager?): Array<String> {
        val chosen = prefs?.appLanguage?.takeIf { it != "SYSTEM" }?.lowercase()
        return if (chosen != null) arrayOf(chosen)
        else android.os.LocaleList.getDefault().toLanguageTags().split(',').filter { it.isNotBlank() }.toTypedArray()
    }

    /**
     * Initializes the free extensions bundled by default (uBlock Origin)
     */
    // GeckoView marks these fields non-null, yet Gecko can leave some empty (metaData.version and baseUrl are read
    // without a default): the safe calls do check for null at run time, so the warning is wrong here
    @Suppress("UNNECESSARY_SAFE_CALL")
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

                // GNU LibreJS, only with JavaScript set to "free only"
                applyJavaScriptMode(cachedPrefs?.javascriptMode ?: "NORMAL")

                // 1. uBlock Origin
                val hasUBlock = list?.any { it.id == UBLOCK_ID } == true
                if (!hasUBlock) {
                    installSignedXpi(context, "ublock.xpi") { ext ->
                        android.util.Log.i("Senda", "uBlock Origin instalado con éxito: ${ext.id}")
                        setupExtensionPanel(ext)
                    }
                } else {
                    list?.firstOrNull { it.id == UBLOCK_ID }?.let { setupExtensionPanel(it) }
                    // Except Senda Labs: the user's own CSS and script must never run in private tabs
                    list?.filter { it.id != "labs@senda.org" }?.forEach { ext ->
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
                return org.mozilla.geckoview.GeckoResult.fromValue(proxyMessage(cachedPrefs))
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
                // Sent as soon as the extension connects (like the proxy): its own request at startup can arrive
                // before this delegate exists and get no answer
                try { port.postMessage(labsMessage(cachedPrefs)) } catch (e: Exception) {
                    android.util.Log.e("Senda", "Error al enviar Senda Labs: ${e.message}")
                }
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

    /**
     * Proxy configuration for the extension. With Tor, "torState" tells whether pages must wait ("starting") or can
     * go out ("ready"; "failed" also goes through Tor's port, so it fails with an error instead of going direct).
     */
    private fun proxyMessage(prefs: PreferencesManager?) = org.json.JSONObject().apply {
        put("type", "SET_PROXY")
        put("mode", prefs?.proxyMode ?: "OFF")
        put("host", prefs?.proxyHost ?: "127.0.0.1")
        put("port", prefs?.proxyPort ?: 9050)
        // With Tor, its socket ([SendaTorManager.socksSocket])
        appContext?.let { put("socket", SendaTorManager.socksSocketUrl(it)) }
        put("proxyDNS", prefs?.proxyDnsRemote ?: true)
        put("torState", when (SendaTorManager.state) {
            TorState.CONNECTED -> "ready"
            TorState.ERROR -> "failed"
            else -> "starting"
        })
    }

    fun applyProxy(prefs: PreferencesManager) {
        cachedPrefs = prefs
        try {
            val msg = proxyMessage(prefs)
            proxyPort?.postMessage(msg)
            android.util.Log.i("Senda", "Enrutamiento Proxy aplicado: mode=${msg.optString("mode")}, tor=${msg.optString("torState")}")
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

    // Safe calls on GeckoView fields that can be null: see initializeBuiltInExtensions
    @Suppress("UNNECESSARY_SAFE_CALL")
    fun getUBlockExtension(): WebExtension? {
        val ext = installedExtensions.firstOrNull { it.id == "uBlock0@raymondhill.net" }
        if (ext == null || ext.metaData?.optionsPageUrl == null) {
            refreshExtensions()
        }
        return ext
    }

    /** Name of the extension a moz-extension:// page belongs to (uBlock Origin, LibreJS...). */
    // Safe calls on GeckoView fields that can be null: see initializeBuiltInExtensions
    @Suppress("UNNECESSARY_SAFE_CALL")
    fun extensionNameFor(url: String): String {
        val host = url.toUri().host
        val ext = installedExtensions.firstOrNull { ext ->
            host != null && ext.metaData.baseUrl?.toUri()?.host == host
        }
        return ext?.metaData?.name?.takeIf { it.isNotBlank() } ?: "Extension"
    }

    // Safe calls on GeckoView fields that can be null: see initializeBuiltInExtensions
    @Suppress("UNNECESSARY_SAFE_CALL")
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
