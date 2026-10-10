package org.senda.browser.core

import android.content.Context
import android.content.SharedPreferences

enum class ToolbarPosition {
    BOTTOM,
    TOP,
    FLOATING
}

enum class ToolbarWidgetSize {
    COMPACT,    // Compact: 32dp buttons, 18dp icons, 48dp bar (more widgets fit)
    BALANCED,   // Balanced: 38dp buttons, 20dp icons, 54dp bar
    COMFORTABLE // Comfortable: 44dp buttons, 22dp icons, 60dp bar
}

enum class AppThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

enum class ZenHomeLayout {
    FOCUSED,        // Focused: minimal, no background or news, only search and shortcuts
    INSPIRATIONAL,  // Inspiring: rotating free/GPL wallpaper + search + shortcuts + short headlines
    INFORMATIONAL,  // Informative: centered, scrollable feed from free news sources
    CUSTOM          // Custom: the user turns background, shortcuts and news on or off as they like
}

data class ZenShortcut(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String,
    val url: String,
    val monogram: String = "",
    val colorHex: String? = null
)

class PreferencesManager(context: Context) {

    private companion object {
        val historyExecutor: java.util.concurrent.ExecutorService =
            java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "senda-history") }

        // Clearing bumps the generation: visits already queued must not write the history again
        val historyLock = Any()
        val historyGeneration = java.util.concurrent.atomic.AtomicLong()

        // Old data cleanup: once per process. SendaNet creates a PreferencesManager on every connection
        // and it used to walk through all preferences every time
        @Volatile var legacyCleanupDone = false
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences("senda_preferences", Context.MODE_PRIVATE)

    init {
        if (!legacyCleanupDone) {
            legacyCleanupDone = true
            cleanUpLegacyData()
        }
    }

    private fun cleanUpLegacyData() {
        // Retired WebDAV upload (bookmarks only, unencrypted, with no restore): its stored server password goes too
        if (prefs.all.keys.any { it.startsWith("webdav_") || it == "last_webdav_sync" }) {
            prefs.edit().apply {
                prefs.all.keys.filter { it.startsWith("webdav_") || it == "last_webdav_sync" }.forEach { remove(it) }
            }.apply()
        }
        // Settings of the retired AI: no data should remain from a feature that no longer exists
        if (prefs.all.keys.any { it.startsWith("ai_") || it == "selected_local_ai_model" }) {
            prefs.edit().apply {
                prefs.all.keys.filter { it.startsWith("ai_") || it == "selected_local_ai_model" }.forEach { remove(it) }
            }.apply()
        }
        // The assistant uses ChatGPT or, for advanced users, Claude, Gemini, Grok or Mistral with a key (2026-10-06): the
        // OpenAI API key and the self-hosted server, which no longer exist, are deleted
        val oldKeys = listOf("openai", "own").flatMap { listOf("assistant_key_enc_$it", "assistant_key_iv_$it") } + "assistant_server_url"
        val stillValid = setOf("chatgpt_plan", "anthropic", "gemini", "xai", "mistral")
        val provider = prefs.getString("assistant_provider", null)
        if (oldKeys.any { prefs.contains(it) } || (provider != null && provider !in stillValid)) {
            prefs.edit().apply {
                oldKeys.forEach { remove(it) }
                if (provider != null && provider !in stillValid) {
                    remove("assistant_provider"); remove("assistant_model"); remove("assistant_privacy_accepted")
                }
            }.apply()
        }
        // Model and consent of the AI in use, now stored per AI
        val activeAi = prefs.getString("assistant_provider", null)
        if (activeAi != null && prefs.contains("assistant_model") && !prefs.contains("assistant_model_$activeAi")) {
            prefs.edit()
                .putString("assistant_model_$activeAi", prefs.getString("assistant_model", "") ?: "")
                .putBoolean("assistant_consent_$activeAi", prefs.getBoolean("assistant_privacy_accepted", false))
                .apply()
        }
        // Earlier versions faked Firefox Sync: delete the "connected account" that never existed.
        if (prefs.contains("fxa_is_connected") || prefs.contains("sync_type")) {
            prefs.edit()
                .remove("sync_type").remove("fxa_email").remove("fxa_is_connected").remove("fxa_custom_server")
                .remove("sync_bookmarks").remove("sync_tabs").remove("sync_history").remove("last_sync_time")
                .apply()
        }
    }

    // --- HOME PAGE AND ZEN STYLE ---
    var zenHomeLayout: ZenHomeLayout
        get() {
            val name = prefs.getString("zen_home_layout", ZenHomeLayout.INSPIRATIONAL.name)
            return try {
                ZenHomeLayout.valueOf(name ?: ZenHomeLayout.INSPIRATIONAL.name)
            } catch (e: Exception) {
                ZenHomeLayout.INSPIRATIONAL
            }
        }
        set(value) = prefs.edit().putString("zen_home_layout", value.name).apply()

    var showZenWallpaper: Boolean
        get() = prefs.getBoolean("show_zen_wallpaper", true)
        set(value) = prefs.edit().putBoolean("show_zen_wallpaper", value).apply()

    var showZenShortcuts: Boolean
        get() = prefs.getBoolean("show_zen_shortcuts", true)
        set(value) = prefs.edit().putBoolean("show_zen_shortcuts", value).apply()

    var showZenNewsFeed: Boolean
        get() = prefs.getBoolean("show_zen_news_feed", true)
        set(value) = prefs.edit().putBoolean("show_zen_news_feed", value).apply()

    var selectedWallpaperId: String
        get() = prefs.getString("selected_wallpaper_id", "debian_ceratopsian") ?: "debian_ceratopsian"
        set(value) = prefs.edit().putString("selected_wallpaper_id", value).apply()

    /** Wallpaper change: 0 = fixed, -1 = a different one in each new tab, >0 = every that many minutes. */
    /** Minutes between wallpapers on the home page (0 = fixed, -1 = a new one per tab). By default they rotate every minute. */
    var wallpaperRotationMinutes: Int
        get() = prefs.getInt("wallpaper_rotation_minutes", 1)
        set(value) = prefs.edit().putInt("wallpaper_rotation_minutes", value).apply()

    /** Wallpaper currently shown in the rotation and when it started (to keep the turn across tabs). */
    var wallpaperRotationCurrentId: String?
        get() = prefs.getString("wallpaper_rotation_current_id", null)
        set(value) = prefs.edit().putString("wallpaper_rotation_current_id", value).apply()

    var wallpaperRotationChangedAt: Long
        get() = prefs.getLong("wallpaper_rotation_changed_at", 0L)
        set(value) = prefs.edit().putLong("wallpaper_rotation_changed_at", value).apply()

    var customWallpaperPath: String?
        get() = prefs.getString("custom_wallpaper_path", null)
        set(value) = prefs.edit().putString("custom_wallpaper_path", value).apply()

    var wallpaperDimPercent: Int
        get() = prefs.getInt("wallpaper_dim_percent", 35)
        set(value) = prefs.edit().putInt("wallpaper_dim_percent", value.coerceIn(0, 80)).apply()

    fun getZenShortcuts(): List<ZenShortcut> {
        val raw = prefs.getString("zen_custom_shortcuts", null)
        if (raw.isNullOrBlank()) {
            return listOf(
                ZenShortcut(id = "ddg", title = "DuckDuckGo", url = "https://duckduckgo.com", monogram = "DDG", colorHex = "#DE5833"),
                ZenShortcut(id = "wiki", title = "Wikipedia", url = "https://es.wikipedia.org", monogram = "W", colorHex = "#333333"),
                ZenShortcut(id = "fdroid", title = "F-Droid", url = "https://f-droid.org", monogram = "FD", colorHex = "#0288D1"),
                ZenShortcut(id = "archive", title = "Archive", url = "https://archive.org", monogram = "IA", colorHex = "#546E7A"),
                ZenShortcut(id = "muylinux", title = "MuyLinux", url = "https://www.muylinux.com", monogram = "ML", colorHex = "#00B0FF")
            )
        }
        return try {
            val array = org.json.JSONArray(raw)
            val list = mutableListOf<ZenShortcut>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    ZenShortcut(
                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        title = obj.getString("title"),
                        url = obj.getString("url"),
                        monogram = obj.optString("monogram", ""),
                        colorHex = obj.optString("colorHex").takeIf { it.isNotBlank() }
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveZenShortcuts(shortcuts: List<ZenShortcut>) {
        val array = org.json.JSONArray()
        shortcuts.forEach {
            val obj = org.json.JSONObject()
            obj.put("id", it.id)
            obj.put("title", it.title)
            obj.put("url", it.url)
            obj.put("monogram", it.monogram)
            obj.put("colorHex", it.colorHex ?: "")
            array.put(obj)
        }
        prefs.edit().putString("zen_custom_shortcuts", array.toString()).apply()
    }

    fun addZenShortcut(title: String, url: String) {
        val current = getZenShortcuts().toMutableList()
        val formattedUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) "https://$url" else url
        val mono = title.trim().take(3).uppercase()
        current.add(ZenShortcut(title = title.trim(), url = formattedUrl.trim(), monogram = mono))
        saveZenShortcuts(current)
    }

    fun updateZenShortcut(id: String, title: String, url: String) {
        val formattedUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) "https://$url" else url
        val current = getZenShortcuts().map {
            if (it.id == id) {
                it.copy(title = title.trim(), url = formattedUrl.trim(), monogram = title.trim().take(3).uppercase())
            } else it
        }
        saveZenShortcuts(current)
    }

    fun deleteZenShortcut(id: String) {
        val current = getZenShortcuts().filterNot { it.id == id }
        saveZenShortcuts(current)
    }

    fun resetZenShortcuts() {
        prefs.edit().remove("zen_custom_shortcuts").apply()
    }

    // --- APPEARANCE & INTERFACE ---
    var toolbarPosition: ToolbarPosition
        get() {
            val name = prefs.getString("toolbar_position", ToolbarPosition.TOP.name)
            return try {
                ToolbarPosition.valueOf(name ?: ToolbarPosition.TOP.name)
            } catch (e: Exception) {
                ToolbarPosition.TOP
            }
        }
        set(value) = prefs.edit().putString("toolbar_position", value.name).apply()

    var themeMode: AppThemeMode
        get() {
            val name = prefs.getString("theme_mode", AppThemeMode.SYSTEM.name)
            return try {
                AppThemeMode.valueOf(name ?: AppThemeMode.SYSTEM.name)
            } catch (e: Exception) {
                AppThemeMode.SYSTEM
            }
        }
        set(value) = prefs.edit().putString("theme_mode", value.name).apply()

    var useSystemColor: Boolean
        get() = prefs.getBoolean("use_system_color", true)
        set(value) = prefs.edit().putBoolean("use_system_color", value).apply()

    var accentColorHex: String
        get() = prefs.getString("accent_color_hex", "#00D2A0") ?: "#00D2A0"
        set(value) = prefs.edit().putString("accent_color_hex", value).apply()

    var isTrueOledBlack: Boolean
        get() = prefs.getBoolean("true_oled_black", false)
        set(value) = prefs.edit().putBoolean("true_oled_black", value).apply()

    var showDevToolsButton: Boolean
        get() = prefs.getBoolean("show_devtools_button", false)
        set(value) = prefs.edit().putBoolean("show_devtools_button", value).apply()

    var showCastButton: Boolean
        get() = prefs.getBoolean("show_cast_button", true)
        set(value) = prefs.edit().putBoolean("show_cast_button", value).apply()

    /**
     * TV mode: while mirroring to a TV, in portrait the phone does not change; when rotated (full-screen video
     * in any app) the picture switches to 16:9 and fills the TV. On disconnect everything goes back to how it
     * was. On by default (2026-10-06, at the user's request); new key because the old one was left at false.
     * Off: the TV shows the phone as it is and only Senda's videos go to the TV in TV format.
     */
    var tvModeEnabled: Boolean
        get() = prefs.getBoolean("tv_mode_enabled_v3", true)
        set(value) = prefs.edit().putBoolean("tv_mode_enabled_v3", value).apply()

    var toolbarFullWidth: Boolean
        get() = prefs.getBoolean("toolbar_full_width", true)
        set(value) = prefs.edit().putBoolean("toolbar_full_width", value).apply()

    var toolbarWidgetSize: ToolbarWidgetSize
        get() {
            val name = prefs.getString("toolbar_widget_size", ToolbarWidgetSize.BALANCED.name)
            return try {
                ToolbarWidgetSize.valueOf(name ?: ToolbarWidgetSize.BALANCED.name)
            } catch (e: Exception) {
                ToolbarWidgetSize.BALANCED
            }
        }
        set(value) = prefs.edit().putString("toolbar_widget_size", value.name).apply()

    var showBackButton: Boolean
        get() = prefs.getBoolean("show_back_button", true)
        set(value) = prefs.edit().putBoolean("show_back_button", value).apply()

    var showForwardButton: Boolean
        get() = prefs.getBoolean("show_forward_button", false)
        set(value) = prefs.edit().putBoolean("show_forward_button", value).apply()

    var showHomeButton: Boolean
        get() = prefs.getBoolean("show_home_button", false)
        set(value) = prefs.edit().putBoolean("show_home_button", value).apply()

    var showTabsButton: Boolean
        get() = prefs.getBoolean("show_tabs_button", true)
        set(value) = prefs.edit().putBoolean("show_tabs_button", value).apply()

    var showMenuButton: Boolean
        get() = prefs.getBoolean("show_menu_button", true)
        set(value) = prefs.edit().putBoolean("show_menu_button", value).apply()

    var showNewTabButton: Boolean
        get() = prefs.getBoolean("show_new_tab_button", false)
        set(value) = prefs.edit().putBoolean("show_new_tab_button", value).apply()

    var showShareButton: Boolean
        get() = prefs.getBoolean("show_share_button", false)
        set(value) = prefs.edit().putBoolean("show_share_button", value).apply()

    var showBookmarksButton: Boolean
        get() = prefs.getBoolean("show_bookmarks_button", false)
        set(value) = prefs.edit().putBoolean("show_bookmarks_button", value).apply()

    var showReaderButton: Boolean
        get() = prefs.getBoolean("show_reader_button", true)
        set(value) = prefs.edit().putBoolean("show_reader_button", value).apply()

    // Off by default: Reload/Stop are in the menu and the bar's space is for the domain (on a
    // 360 dp phone only "es.wiki…" fit)
    var showReloadButton: Boolean
        get() = prefs.getBoolean("show_reload_button", false)
        set(value) = prefs.edit().putBoolean("show_reload_button", value).apply()

    var showSecurityIndicator: Boolean
        get() = prefs.getBoolean("show_security_indicator", true)
        set(value) = prefs.edit().putBoolean("show_security_indicator", value).apply()

    // --- SEARCH ---
    var searchEngineName: String
        get() = prefs.getString("search_engine_name", "DuckDuckGo") ?: "DuckDuckGo"
        set(value) = prefs.edit().putString("search_engine_name", value).apply()

    var customSearchEngineUrl: String
        get() = prefs.getString("search_engine_url", "https://duckduckgo.com/?q=") ?: "https://duckduckgo.com/?q="
        set(value) = prefs.edit().putString("search_engine_url", value).apply()

    var searchSuggestionsEnabled: Boolean
        // Local suggestions (bookmarks and history): they never leave the phone, so they are on
        get() = prefs.getBoolean("search_suggestions_enabled", true)
        set(value) = prefs.edit().putBoolean("search_suggestions_enabled", value).apply()

    // --- TABS ---
    var closeTabsPolicy: String
        get() = prefs.getString("close_tabs_policy", "MANUAL") ?: "MANUAL"
        set(value) = prefs.edit().putString("close_tabs_policy", value).apply()

    /** Tab saved to reopen it when coming back to Senda (private ones are never saved). */
    data class SavedTab(val url: String, val title: String, val lastUsed: Long)

    /**
     * When opening Senda: HOME = clean home page in front (previous tabs stay in the list),
     * RESUME = continue in the last tab, CLEAN = start without the previous tabs.
     */
    var startupMode: String
        get() = prefs.getString("startup_mode", "HOME") ?: "HOME"
        set(value) = prefs.edit().putString("startup_mode", value).apply()

    fun saveOpenTabs(tabs: List<SavedTab>, activeIndex: Int) {
        val array = org.json.JSONArray()
        tabs.forEach { t ->
            array.put(org.json.JSONObject().put("url", t.url).put("title", t.title).put("lastUsed", t.lastUsed))
        }
        prefs.edit()
            .putString("open_tabs_session", array.toString())
            .putInt("open_tabs_active", activeIndex)
            .apply()
    }

    /** Tabs to restore according to "Close tabs": none with "On exit", only recent ones with a time limit. */
    fun loadOpenTabs(): Pair<List<SavedTab>, Int> {
        val maxAge = when (closeTabsPolicy) {
            "ON_EXIT" -> return emptyList<SavedTab>() to 0
            "AFTER_ONE_DAY" -> 24L * 60 * 60 * 1000
            "AFTER_ONE_WEEK" -> 7L * 24 * 60 * 60 * 1000
            else -> Long.MAX_VALUE
        }
        val raw = prefs.getString("open_tabs_session", null) ?: return emptyList<SavedTab>() to 0
        return try {
            val array = org.json.JSONArray(raw)
            val now = System.currentTimeMillis()
            val list = (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                SavedTab(o.getString("url"), o.optString("title"), o.optLong("lastUsed", now))
            }.filter { now - it.lastUsed <= maxAge }
            list to prefs.getInt("open_tabs_active", 0).coerceIn(0, (list.size - 1).coerceAtLeast(0))
        } catch (_: Exception) {
            emptyList<SavedTab>() to 0
        }
    }

    var tabsViewMode: String
        get() = prefs.getString("tabs_view_mode", "GRID") ?: "GRID"
        set(value) = prefs.edit().putString("tabs_view_mode", value).apply()

    var openLinksInBackground: Boolean
        get() = prefs.getBoolean("open_links_in_background", false)
        set(value) = prefs.edit().putBoolean("open_links_in_background", value).apply()

    // --- PRIVACY AND SECURITY ---
    var alwaysPrivateMode: Boolean
        get() = prefs.getBoolean("always_private_mode", false)
        set(value) = prefs.edit().putBoolean("always_private_mode", value).apply()

    var httpsOnlyMode: String
        get() = prefs.getString("https_only_mode", "ALL_TABS") ?: "ALL_TABS"
        set(value) = prefs.edit().putString("https_only_mode", value).apply()

    var dnsOverHttpsMode: String
        get() = prefs.getString("dns_over_https_mode", "MAX_PROTECTION") ?: "MAX_PROTECTION"
        set(value) = prefs.edit().putString("dns_over_https_mode", value).apply()

    var dohProvider: String
        get() = prefs.getString("doh_provider", "QUAD9") ?: "QUAD9"
        set(value) = prefs.edit().putString("doh_provider", value).apply()

    var customDohUrl: String
        get() = prefs.getString("custom_doh_url", "") ?: ""
        set(value) = prefs.edit().putString("custom_doh_url", value).apply()

    // --- TOR ROUTING & SECURE PROXY ---
    var proxyMode: String
        get() = prefs.getString("proxy_mode", "OFF") ?: "OFF" // OFF, TOR_ORBOT, CUSTOM_SOCKS5, CUSTOM_HTTP
        set(value) = prefs.edit().putString("proxy_mode", value).apply()

    var proxyHost: String
        get() = prefs.getString("proxy_host", "127.0.0.1") ?: "127.0.0.1"
        set(value) = prefs.edit().putString("proxy_host", value).apply()

    var proxyPort: Int
        get() = prefs.getInt("proxy_port", 9050)
        set(value) = prefs.edit().putInt("proxy_port", value).apply()

    var proxyDnsRemote: Boolean
        get() = prefs.getBoolean("proxy_dns_remote", true)
        set(value) = prefs.edit().putBoolean("proxy_dns_remote", value).apply()

    // --- READER MODE (SETTINGS) ---
    var readerTheme: String
        get() = prefs.getString("reader_theme", "SEPIA") ?: "SEPIA" // LIGHT, SEPIA, OLED_BLACK
        set(value) = prefs.edit().putString("reader_theme", value).apply()

    var readerFontFamily: String
        get() = prefs.getString("reader_font_family", "SERIF") ?: "SERIF" // SERIF, SANS, MONO
        set(value) = prefs.edit().putString("reader_font_family", value).apply()

    var readerFontSizePercent: Int
        get() = prefs.getInt("reader_font_size_percent", 100)
        set(value) = prefs.edit().putInt("reader_font_size_percent", value).apply()

    var trackingProtectionLevel: String
        get() = prefs.getString("tracking_protection_level", "STRICT") ?: "STRICT"
        set(value) = prefs.edit().putString("tracking_protection_level", value).apply()

    var cookiePolicy: String
        get() = prefs.getString("cookie_policy", "ISOLATE_THIRD_PARTY") ?: "ISOLATE_THIRD_PARTY"
        set(value) = prefs.edit().putString("cookie_policy", value).apply()

    var blockFingerprinting: Boolean
        get() = prefs.getBoolean("block_fingerprinting", true)
        set(value) = prefs.edit().putBoolean("block_fingerprinting", value).apply()

    var blockSocialTrackers: Boolean
        get() = prefs.getBoolean("block_social_trackers", true)
        set(value) = prefs.edit().putBoolean("block_social_trackers", value).apply()

    var requireBiometrics: Boolean
        get() = prefs.getBoolean("require_biometrics", false)
        set(value) = prefs.edit().putBoolean("require_biometrics", value).apply()

    var enableAntiSnooping: Boolean
        get() = prefs.getBoolean("enable_anti_snooping", false)
        set(value) = prefs.edit().putBoolean("enable_anti_snooping", value).apply()

    // --- SITE PERMISSIONS & NOTIFICATIONS ---
    var sitePermissionCamera: String
        get() = prefs.getString("site_perm_camera", "ASK") ?: "ASK"
        set(value) = prefs.edit().putString("site_perm_camera", value).apply()

    var sitePermissionMic: String
        get() = prefs.getString("site_perm_mic", "ASK") ?: "ASK"
        set(value) = prefs.edit().putString("site_perm_mic", value).apply()

    var sitePermissionLocation: String
        get() = prefs.getString("site_perm_location", "ASK") ?: "ASK"
        set(value) = prefs.edit().putString("site_perm_location", value).apply()

    var sitePermissionNotifications: String
        get() = prefs.getString("site_perm_notifications", "BLOCK") ?: "BLOCK"
        set(value) = prefs.edit().putString("site_perm_notifications", value).apply()

    var blockWebPopups: Boolean
        get() = prefs.getBoolean("block_web_popups", true)
        set(value) = prefs.edit().putBoolean("block_web_popups", value).apply()

    // --- DOWNLOADS AND EXTERNAL APPS ---
    var openLinksInApps: String
        get() = prefs.getString("open_links_in_apps", "ASK") ?: "ASK"
        set(value) = prefs.edit().putString("open_links_in_apps", value).apply()

    var askDownloadLocation: Boolean
        get() = prefs.getBoolean("ask_download_location", true)
        set(value) = prefs.edit().putBoolean("ask_download_location", value).apply()

    // --- ACCESSIBILITY, TYPOGRAPHY AND LANGUAGE ---
    var uiFontFamily: String
        get() = prefs.getString("ui_font_family", "SERIF") ?: "SERIF"
        set(value) = prefs.edit().putString("ui_font_family", value).apply()

    var uiFontScalePercent: Int
        get() = prefs.getInt("ui_font_scale_percent", 100)
        set(value) = prefs.edit().putInt("ui_font_scale_percent", value).apply()

    var fontHinting: String
        get() = prefs.getString("font_hinting", "SLIGHT") ?: "SLIGHT" // NONE, SLIGHT, MEDIUM, FULL
        set(value) = prefs.edit().putString("font_hinting", value).apply()

    var syncWebFontScale: Boolean
        get() = prefs.getBoolean("sync_web_font_scale", true)
        set(value) = prefs.edit().putBoolean("sync_web_font_scale", value).apply()

    var fontScalePercent: Int
        get() = prefs.getInt("font_scale_percent", 100)
        set(value) = prefs.edit().putInt("font_scale_percent", value).apply()

    var forceEnableZoom: Boolean
        get() = prefs.getBoolean("force_enable_zoom", true)
        set(value) = prefs.edit().putBoolean("force_enable_zoom", value).apply()

    var appLanguage: String
        get() = prefs.getString("app_language", "SYSTEM") ?: "SYSTEM"
        set(value) = prefs.edit().putString("app_language", value).apply()

    var offerTranslations: Boolean
        get() = prefs.getBoolean("offer_translations", false)
        set(value) = prefs.edit().putBoolean("offer_translations", value).apply()

    // --- SENDA LABS & DEVELOPER ---
    /** Safe Browsing downloads Google lists at startup: on by default, but the user can turn it off. */
    var safeBrowsingEnabled: Boolean
        get() = prefs.getBoolean("safe_browsing_enabled", true)
        set(value) = prefs.edit().putBoolean("safe_browsing_enabled", value).apply()

    var remoteDebuggingEnabled: Boolean
        get() = prefs.getBoolean("remote_debugging_enabled", false)
        set(value) = prefs.edit().putBoolean("remote_debugging_enabled", value).apply()

    var userCustomCss: String
        get() = prefs.getString("user_custom_css", "") ?: ""
        set(value) = prefs.edit().putString("user_custom_css", value).apply()

    var userCustomScript: String
        get() = prefs.getString("user_custom_script", "") ?: ""
        set(value) = prefs.edit().putString("user_custom_script", value).apply()

    // --- REMOTE AI ASSISTANT ---
    // "assistant_" prefix: the "ai_*" keys of the retired on-device AI are deleted at startup
    /** "chatgpt_plan" (SendaAssistant.PROVIDER_ID), the id of an AI with a key (ApiProvider) or null. */
    var assistantProvider: String?
        get() = prefs.getString("assistant_provider", null)
        set(value) = prefs.edit().putString("assistant_provider", value).apply()

    /** "Sign in with ChatGPT": stable identifier of this installation (ext_agent_host_id) and issued client. */
    var assistantChatGptHostId: String
        get() = prefs.getString("assistant_chatgpt_host_id", "") ?: ""
        set(value) = prefs.edit().putString("assistant_chatgpt_host_id", value).apply()

    var assistantChatGptClientId: String
        get() = prefs.getString("assistant_chatgpt_client_id", "") ?: ""
        set(value) = prefs.edit().putString("assistant_chatgpt_client_id", value).apply()

    /** Has already seen OpenAI's mandatory welcome notice ("Eligible usage in this app uses your ChatGPT plan"). */
    var assistantChatGptWelcomeSeen: Boolean
        get() = prefs.getBoolean("assistant_chatgpt_welcome_seen", false)
        set(value) = prefs.edit().putBoolean("assistant_chatgpt_welcome_seen", value).apply()

    // "My AIs": each connected AI keeps its own model and consent, to switch between them without losing any
    // settings. assistant_provider is the one in use; assistant_model and assistant_privacy_accepted, from earlier
    // versions, were moved to the AI that was in use (see init)
    fun assistantModelFor(id: String): String = prefs.getString("assistant_model_$id", "") ?: ""
    fun setAssistantModelFor(id: String, model: String) = prefs.edit().putString("assistant_model_$id", model).apply()
    fun assistantConsentFor(id: String): Boolean = prefs.getBoolean("assistant_consent_$id", false)
    fun setAssistantConsentFor(id: String, accepted: Boolean) = prefs.edit().putBoolean("assistant_consent_$id", accepted).apply()

    /** Assistant credentials (ChatGPT session or API key), encrypted with the phone's keystore. */
    fun getAssistantKey(providerId: String): String {
        val enc = prefs.getString("assistant_key_enc_$providerId", null) ?: return ""
        val iv = prefs.getString("assistant_key_iv_$providerId", null) ?: return ""
        return try {
            val chars = org.senda.browser.core.security.SendaVaultManager.decryptPassword(enc, iv, org.senda.browser.core.security.SendaVaultManager.APP_KEY_ALIAS)
            String(chars).also { org.senda.browser.core.security.SendaVaultManager.wipe(chars) }
        } catch (e: Exception) {
            ""
        }
    }

    /** Saves the encrypted key; if the chip cannot encrypt, it is not saved (returns false). */
    fun setAssistantKey(providerId: String, key: String): Boolean {
        if (key.isBlank()) {
            prefs.edit().remove("assistant_key_enc_$providerId").remove("assistant_key_iv_$providerId").apply()
            return true
        }
        return try {
            val chars = key.trim().toCharArray()
            val (enc, iv) = org.senda.browser.core.security.SendaVaultManager.encryptPassword(chars, org.senda.browser.core.security.SendaVaultManager.APP_KEY_ALIAS)
            org.senda.browser.core.security.SendaVaultManager.wipe(chars)
            prefs.edit().putString("assistant_key_enc_$providerId", enc).putString("assistant_key_iv_$providerId", iv).apply()
            true
        } catch (e: Exception) {
            android.util.Log.e("SendaPrefs", "No se pudo cifrar la clave del asistente: ${e.javaClass.simpleName}")
            false
        }
    }

    // --- BOOKMARKS ---
    var showBookmarksBar: Boolean
        get() = prefs.getBoolean("show_bookmarks_bar", false)
        set(value) = prefs.edit().putBoolean("show_bookmarks_bar", value).apply()

    fun getBookmarks(): List<BookmarkItem> {
        val raw = prefs.getString("user_bookmarks", null)
        if (raw.isNullOrBlank()) {
            return listOf(
                BookmarkItem(id = "bm_ddg", title = "DuckDuckGo", url = "https://duckduckgo.com"),
                BookmarkItem(id = "bm_wiki", title = "Wikipedia", url = "https://es.wikipedia.org"),
                BookmarkItem(id = "bm_osm", title = "OpenStreetMap", url = "https://www.openstreetmap.org"),
                BookmarkItem(id = "bm_archive", title = "Internet Archive", url = "https://archive.org"),
                BookmarkItem(id = "bm_eff", title = "EFF (Privacidad)", url = "https://www.eff.org")
            )
        }
        return try {
            val array = org.json.JSONArray(raw)
            val list = mutableListOf<BookmarkItem>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    BookmarkItem(
                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        title = obj.getString("title"),
                        url = obj.getString("url"),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveBookmarks(bookmarks: List<BookmarkItem>) {
        val array = org.json.JSONArray()
        bookmarks.forEach {
            val obj = org.json.JSONObject()
            obj.put("id", it.id)
            obj.put("title", it.title)
            obj.put("url", it.url)
            obj.put("timestamp", it.timestamp)
            array.put(obj)
        }
        prefs.edit().putString("user_bookmarks", array.toString()).apply()
    }

    fun isBookmarked(url: String): Boolean {
        if (url.isBlank() || url == "about:blank") return false
        val clean = url.trimEnd('/')
        return getBookmarks().any { it.url.trimEnd('/') == clean }
    }

    fun toggleBookmark(title: String, url: String): Boolean {
        if (url.isBlank() || url == "about:blank") return false
        val current = getBookmarks().toMutableList()
        val clean = url.trimEnd('/')
        val existingIndex = current.indexOfFirst { it.url.trimEnd('/') == clean }
        return if (existingIndex >= 0) {
            current.removeAt(existingIndex)
            saveBookmarks(current)
            false
        } else {
            val cleanTitle = title.ifBlank { url }
            current.add(0, BookmarkItem(title = cleanTitle, url = url))
            saveBookmarks(current)
            true
        }
    }

    fun deleteBookmark(id: String) {
        val current = getBookmarks().filterNot { it.id == id }
        saveBookmarks(current)
    }

    fun updateBookmark(id: String, newTitle: String, newUrl: String) {
        val formattedUrl = if (!newUrl.startsWith("http://") && !newUrl.startsWith("https://")) "https://$newUrl" else newUrl
        val current = getBookmarks().map {
            if (it.id == id) {
                it.copy(title = newTitle.trim().ifBlank { formattedUrl }, url = formattedUrl.trim())
            } else it
        }
        saveBookmarks(current)
    }

    // --- LOCAL BROWSING HISTORY ---
    fun getHistory(): List<HistoryItem> {
        val raw = prefs.getString("user_browsing_history", null) ?: return emptyList()
        return try {
            val array = org.json.JSONArray(raw)
            val list = mutableListOf<HistoryItem>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    HistoryItem(
                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        title = obj.getString("title"),
                        url = obj.getString("url"),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveHistory(history: List<HistoryItem>) {
        val array = org.json.JSONArray()
        // Limit to the 1000 most recent items for performance
        history.take(1000).forEach {
            val obj = org.json.JSONObject()
            obj.put("id", it.id)
            obj.put("title", it.title)
            obj.put("url", it.url)
            obj.put("timestamp", it.timestamp)
            array.put(obj)
        }
        prefs.edit().putString("user_browsing_history", array.toString()).apply()
    }

    fun addHistoryItem(title: String, url: String) {
        if (url.isBlank() || url == "about:blank" || url.startsWith("data:") || url.startsWith("about:")) return
        val now = System.currentTimeMillis()
        // Reading, editing and saving ~1000 JSON entries happened on the main thread on every page (twice):
        // now it runs in the background and in order, without stalling scrolling or loading
        val generation = historyGeneration.get()
        historyExecutor.execute {
            synchronized(historyLock) {
                if (generation != historyGeneration.get()) return@execute
                val current = getHistory().toMutableList()
                val cleanUrl = url.trim()
                val cleanTitle = title.ifBlank { cleanUrl }
                // Same page as the last one visited (reload, restored session, reopening Senda):
                // its time is updated instead of repeating it in the list
                current.removeAll { it.url == cleanUrl && now - it.timestamp < 30_000L }
                if (current.firstOrNull()?.url == cleanUrl) current.removeAt(0)
                current.add(0, HistoryItem(title = cleanTitle, url = cleanUrl, timestamp = now))
                saveHistory(current)
            }
        }
    }

    fun deleteHistoryItem(id: String) = synchronized(historyLock) {
        val current = getHistory().filterNot { it.id == id }
        saveHistory(current)
    }

    fun clearHistory() = synchronized(historyLock) {
        historyGeneration.incrementAndGet()
        prefs.edit().remove("user_browsing_history").apply()
    }

    fun clearHistoryRange(rangeMillis: Long) {
        if (rangeMillis <= 0L) {
            clearHistory()
        } else synchronized(historyLock) {
            historyGeneration.incrementAndGet()
            val cutoff = System.currentTimeMillis() - rangeMillis
            val filtered = getHistory().filter { it.timestamp < cutoff }
            saveHistory(filtered)
        }
    }

    // --- ENCRYPTED BACKUP (see core/backup and docs/backup-format.md) ---
    /** When the last encrypted backup was saved (0 = never). */
    var lastBackupTime: Long
        get() = prefs.getLong("last_backup_time", 0L)
        set(value) = prefs.edit().putLong("last_backup_time", value).apply()

    /**
     * Settings that travel in a backup: portable, not secret and not tied to this phone. Left out on purpose:
     * AI sessions and keys, proxy server and custom DNS (a backup must not be able to divert traffic), custom
     * CSS and scripts (nor inject code into pages), remote debugging, open tabs, TV mode and wallpaper state.
     */
    private val portableSettingKeys = setOf(
        "accent_color_hex", "always_private_mode", "app_language", "ask_download_location",
        "block_fingerprinting", "block_social_trackers", "block_web_popups", "close_tabs_policy", "cookie_policy",
        "dns_over_https_mode", "doh_provider", "enable_anti_snooping", "font_hinting", "font_scale_percent",
        "force_enable_zoom", "https_only_mode", "offer_translations", "open_links_in_apps", "open_links_in_background",
        "reader_font_family", "reader_font_size_percent", "reader_theme", "require_biometrics", "safe_browsing_enabled",
        "search_engine_name", "search_engine_url", "search_suggestions_enabled", "selected_wallpaper_id",
        "show_back_button", "show_bookmarks_bar", "show_bookmarks_button", "show_cast_button", "show_devtools_button",
        "show_forward_button", "show_home_button", "show_menu_button", "show_new_tab_button", "show_reader_button",
        "show_reload_button", "show_security_indicator", "show_share_button", "show_tabs_button",
        "show_zen_news_feed", "show_zen_shortcuts", "show_zen_wallpaper", "site_perm_camera", "site_perm_location",
        "site_perm_mic", "site_perm_notifications", "startup_mode", "sync_web_font_scale", "tabs_view_mode",
        "theme_mode", "toolbar_full_width", "toolbar_position", "toolbar_widget_size", "tracking_protection_level",
        "true_oled_black", "ui_font_family", "ui_font_scale_percent", "use_system_color", "wallpaper_dim_percent",
        "wallpaper_rotation_minutes", "zen_home_layout"
    )

    fun exportPortableSettings(): org.json.JSONObject {
        val out = org.json.JSONObject()
        prefs.all.forEach { (key, value) ->
            if (key !in portableSettingKeys || value == null) return@forEach
            // The user's own photo lives only on this phone
            if (key == "selected_wallpaper_id" && value == "custom_user") return@forEach
            when (value) {
                is Boolean, is Int, is Long, is Float, is String -> out.put(key, value)
            }
        }
        return out
    }

    /** Applies the portable settings of a backup; only known keys and only with the type they already have. */
    fun importPortableSettings(settings: org.json.JSONObject): Int {
        val editor = prefs.edit()
        var applied = 0
        settings.keys().forEach { key ->
            if (key !in portableSettingKeys) return@forEach
            val value = settings.opt(key)
            val current = prefs.all[key]
            val ok = when {
                value is Boolean && (current == null || current is Boolean) -> { editor.putBoolean(key, value); true }
                value is Number && current is Long -> { editor.putLong(key, value.toLong()); true }
                value is Number && current is Float -> { editor.putFloat(key, value.toFloat()); true }
                value is Number && (current == null || current is Int) -> { editor.putInt(key, value.toInt()); true }
                value is String && (current == null || current is String) && value.length <= 2048 &&
                    !(key == "search_engine_url" && !value.startsWith("https://")) -> { editor.putString(key, value); true }
                else -> false
            }
            if (ok) applied++
        }
        editor.apply()
        return applied
    }

    /** Adds bookmarks that are not saved yet (same address); nothing is removed or replaced. */
    fun mergeBookmarks(items: List<BookmarkItem>): Int {
        val current = getBookmarks().toMutableList()
        val known = current.map { it.url.trimEnd('/') }.toMutableSet()
        var added = 0
        items.forEach { if (known.add(it.url.trimEnd('/'))) { current.add(it); added++ } }
        if (added > 0) saveBookmarks(current)
        return added
    }

    /** Adds home shortcuts that are not there yet (same address). */
    fun mergeZenShortcuts(items: List<ZenShortcut>): Int {
        val current = getZenShortcuts().toMutableList()
        val known = current.map { it.url.trimEnd('/') }.toMutableSet()
        var added = 0
        items.forEach { if (known.add(it.url.trimEnd('/'))) { current.add(it); added++ } }
        if (added > 0) saveZenShortcuts(current)
        return added
    }

    /** Adds visits of a backup to the history (same address and time are not repeated), newest first. */
    fun mergeHistory(items: List<HistoryItem>): Int = synchronized(historyLock) {
        val current = getHistory()
        val known = current.map { it.url to it.timestamp }.toHashSet()
        val fresh = items.filter { known.add(it.url to it.timestamp) }
        if (fresh.isNotEmpty()) saveHistory((current + fresh).sortedByDescending { it.timestamp })
        fresh.size
    }

    // --- ADVANCED DOWNLOAD MANAGER ---
    fun getDownloads(): List<DownloadItem> {
        val raw = prefs.getString("user_downloads_list", null) ?: return emptyList()
        return try {
            val array = org.json.JSONArray(raw)
            val list = mutableListOf<DownloadItem>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    DownloadItem(
                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        fileName = obj.optString("fileName", "descarga"),
                        url = obj.optString("url", ""),
                        filePath = obj.optString("filePath", ""),
                        totalBytes = obj.optLong("totalBytes", 0L),
                        downloadedBytes = obj.optLong("downloadedBytes", 0L),
                        status = obj.optString("status", "COMPLETED"),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        mimeType = obj.optString("mimeType", "")
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveDownloads(list: List<DownloadItem>) {
        val array = org.json.JSONArray()
        list.take(300).forEach {
            val obj = org.json.JSONObject()
            obj.put("id", it.id)
            obj.put("fileName", it.fileName)
            obj.put("url", it.url)
            obj.put("filePath", it.filePath)
            obj.put("totalBytes", it.totalBytes)
            obj.put("downloadedBytes", it.downloadedBytes)
            obj.put("status", it.status)
            obj.put("timestamp", it.timestamp)
            obj.put("mimeType", it.mimeType)
            array.put(obj)
        }
        prefs.edit().putString("user_downloads_list", array.toString()).apply()
    }

    fun addDownload(item: DownloadItem) {
        val current = getDownloads().toMutableList()
        current.removeAll { it.id == item.id }
        current.add(0, item)
        saveDownloads(current)
    }

    fun updateDownload(item: DownloadItem) {
        val current = getDownloads().map {
            if (it.id == item.id) item else it
        }
        saveDownloads(current)
    }

    fun deleteDownload(id: String) {
        val current = getDownloads().filterNot { it.id == id }
        saveDownloads(current)
    }

    fun clearDownloads() {
        prefs.edit().remove("user_downloads_list").apply()
    }

    // --- STANDARD HTML EXPORT AND IMPORT (NETSCAPE BOOKMARKS) ---
    fun exportBookmarksToNetscapeHtml(bookmarks: List<BookmarkItem>): String {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n")
        sb.append("<!-- This is an automatically generated file by Senda Browser. -->\n")
        sb.append("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n")
        sb.append("<TITLE>Bookmarks</TITLE>\n")
        sb.append("<H1>Bookmarks</H1>\n")
        sb.append("<DL><p>\n")
        bookmarks.forEach { bm ->
            val cleanTitle = bm.title.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
            val cleanUrl = bm.url.replace("&", "&amp;").replace("\"", "&quot;")
            sb.append("    <DT><A HREF=\"$cleanUrl\" ADD_DATE=\"${bm.timestamp / 1000}\">$cleanTitle</A>\n")
        }
        sb.append("</DL><p>\n")
        return sb.toString()
    }

    fun importBookmarksFromNetscapeHtml(html: String): Int {
        val current = getBookmarks().toMutableList()
        val known = current.map { it.url.trimEnd('/') }.toMutableSet()
        var addedCount = 0
        for (b in org.senda.browser.core.backup.BookmarkHtml.parse(html)) {
            if (known.add(b.url.trimEnd('/'))) {
                current.add(0, BookmarkItem(title = b.title, url = b.url, timestamp = b.added ?: System.currentTimeMillis()))
                addedCount++
            }
        }
        if (addedCount > 0) {
            saveBookmarks(current)
        }
        return addedCount
    }
}

data class BookmarkItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String,
    val url: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class HistoryItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String,
    val url: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class DownloadItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val fileName: String,
    val url: String,
    val filePath: String = "",
    val totalBytes: Long = 0L,
    val downloadedBytes: Long = 0L,
    val status: String = "COMPLETED", // DOWNLOADING, COMPLETED, PAUSED, FAILED
    val timestamp: Long = System.currentTimeMillis(),
    val mimeType: String = ""
)
