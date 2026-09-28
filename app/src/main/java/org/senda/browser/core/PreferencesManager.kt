package org.senda.browser.core

import android.content.Context
import android.content.SharedPreferences

enum class ToolbarPosition {
    BOTTOM,
    TOP,
    FLOATING
}

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("senda_preferences", Context.MODE_PRIVATE)

    var toolbarPosition: ToolbarPosition
        get() {
            val name = prefs.getString("toolbar_position", ToolbarPosition.BOTTOM.name)
            return try {
                ToolbarPosition.valueOf(name ?: ToolbarPosition.BOTTOM.name)
            } catch (e: Exception) {
                ToolbarPosition.BOTTOM
            }
        }
        set(value) = prefs.edit().putString("toolbar_position", value.name).apply()

    var accentColorHex: String
        get() = prefs.getString("accent_color_hex", "#00D2A0") ?: "#00D2A0"
        set(value) = prefs.edit().putString("accent_color_hex", value).apply()

    var isTrueOledBlack: Boolean
        get() = prefs.getBoolean("true_oled_black", true)
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
