package org.senda.browser.core

import android.content.Context
import android.content.SharedPreferences

enum class ToolbarPosition {
    BOTTOM,
    TOP,
    FLOATING
}

enum class AppThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

enum class ZenHomeLayout {
    FOCUSED,        // Enfocado: minimalista, sin fondo ni noticias, solo buscador y accesos
    INSPIRATIONAL,  // Inspirador: fondo libre/GPL rotativo + buscador + accesos + titulares éticos
    INFORMATIONAL,  // Informativo: feed ético de noticias libres centrado y desplazable
    CUSTOM          // Personalizado: el usuario activa/desactiva fondo, accesos y noticias a gusto
}

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("senda_preferences", Context.MODE_PRIVATE)

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
        get() = prefs.getString("selected_wallpaper_id", "tux_aurora") ?: "tux_aurora"
        set(value) = prefs.edit().putString("selected_wallpaper_id", value).apply()

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

    var requireBiometrics: Boolean
        get() = prefs.getBoolean("require_biometrics", false)
        set(value) = prefs.edit().putBoolean("require_biometrics", value).apply()

    var enableAntiSnooping: Boolean
        get() = prefs.getBoolean("enable_anti_snooping", false)
        set(value) = prefs.edit().putBoolean("enable_anti_snooping", value).apply()

    var showDevToolsButton: Boolean
        get() = prefs.getBoolean("show_devtools_button", true)
        set(value) = prefs.edit().putBoolean("show_devtools_button", value).apply()

    var showFireButton: Boolean
        get() = prefs.getBoolean("show_fire_button", true)
        set(value) = prefs.edit().putBoolean("show_fire_button", value).apply()

    var customSearchEngineUrl: String
        get() = prefs.getString("search_engine_url", "https://duckduckgo.com/?q=") ?: "https://duckduckgo.com/?q="
        set(value) = prefs.edit().putString("search_engine_url", value).apply()

    var userCustomCss: String
        get() = prefs.getString("user_custom_css", "") ?: ""
        set(value) = prefs.edit().putString("user_custom_css", value).apply()

    var userCustomScript: String
        get() = prefs.getString("user_custom_script", "") ?: ""
        set(value) = prefs.edit().putString("user_custom_script", value).apply()
}
