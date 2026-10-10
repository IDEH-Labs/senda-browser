package org.senda.browser.ui.screens.settings

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import org.senda.browser.core.*

data class SettingItemData(
    val id: String,
    val title: String,
    val subtitle: String,
    val category: String,
    val icon: ImageVector? = null,
    val isToggle: Boolean = false,
    val isChecked: Boolean = false,
    val onToggleChange: ((Boolean) -> Unit)? = null,
    val onClick: () -> Unit
)

fun buildSettingsList(
    context: Context,
    prefs: PreferencesManager,
    strings: SendaStringPack,
    isDefaultBrowserApp: Boolean,
    onRequestSetDefaultBrowser: () -> Unit,
    onOpenDialog: (String) -> Unit,
    onSettingsChanged: () -> Unit
): List<SettingItemData> {
    val homeSummary = when (prefs.zenHomeLayout) {
        ZenHomeLayout.FOCUSED -> strings.preset_focused
        ZenHomeLayout.INSPIRATIONAL -> strings.preset_inspirational
        ZenHomeLayout.INFORMATIONAL -> strings.preset_informational
        ZenHomeLayout.CUSTOM -> strings.preset_custom
    }
    val appearanceSummary = buildString {
        append(
            when (prefs.themeMode) {
                AppThemeMode.SYSTEM -> strings.summary_theme_system
                AppThemeMode.LIGHT -> strings.summary_theme_light
                AppThemeMode.DARK -> if (prefs.isTrueOledBlack) strings.summary_theme_oled else strings.summary_theme_dark
            }
        )
        if (prefs.useSystemColor) {
            append(" • Material You")
        }
    }
    val toolbarSummary = when (prefs.toolbarPosition) {
        ToolbarPosition.BOTTOM -> strings.summary_toolbar_bottom
        ToolbarPosition.TOP -> strings.summary_toolbar_top
        ToolbarPosition.FLOATING -> strings.summary_toolbar_floating
    }
    val httpsSummary = when (prefs.httpsOnlyMode) {
        "ALL_TABS" -> strings.summary_https_all
        "PRIVATE_ONLY" -> strings.summary_https_private
        else -> strings.summary_https_disabled
    }
    val dohSummary = when (prefs.dnsOverHttpsMode) {
        "MAX_PROTECTION" -> "${strings.summary_doh_max_prefix} (${prefs.dohProvider})"
        "INCREASED" -> "${strings.summary_doh_increased_prefix} (${prefs.dohProvider})"
        else -> strings.summary_doh_disabled
    }
    val trackingSummary = when (prefs.trackingProtectionLevel) {
        "STRICT" -> strings.summary_tracking_strict
        "STANDARD" -> strings.summary_tracking_standard
        else -> strings.summary_tracking_custom
    }
    val openLinksSummary = when (prefs.openLinksInApps) {
        "ALWAYS" -> strings.summary_open_links_always
        "ASK" -> strings.summary_open_links_ask
        "NEVER" -> strings.summary_open_links_never
        else -> strings.summary_open_links_ask
    }
    val downloadsSummary = if (prefs.askDownloadLocation) strings.dlg_download_ask else strings.st_downloads_title

    return listOf(
        // GENERAL / BROWSING & SEARCH
        SettingItemData(
            id = "search",
            title = strings.st_search_title,
            subtitle = prefs.searchEngineName,
            category = strings.cat_nav,
            icon = Icons.Default.Search,
            onClick = { onOpenDialog("search") }
        ),
        SettingItemData(
            id = "tabs",
            title = strings.st_tabs_title,
            subtitle = strings.st_tabs_sub,
            category = strings.cat_nav,
            icon = Icons.Default.Tab,
            onClick = { onOpenDialog("tabs") }
        ),
        SettingItemData(
            id = "home",
            title = strings.st_home_title,
            subtitle = homeSummary,
            category = strings.cat_nav,
            icon = Icons.Default.Home,
            onClick = { onOpenDialog("home") }
        ),
        SettingItemData(
            id = "customize",
            title = strings.st_appearance_title,
            subtitle = appearanceSummary,
            category = strings.cat_nav,
            icon = Icons.Default.Palette,
            onClick = { onOpenDialog("customize") }
        ),
        SettingItemData(
            id = "toolbar_customization",
            title = strings.tb_customize,
            subtitle = toolbarSummary,
            category = strings.cat_nav,
            icon = Icons.Default.Tune,
            onClick = { onOpenDialog("toolbar_customization") }
        ),
        SettingItemData(
            id = "gnome_typography",
            title = strings.st_typography_title,
            subtitle = strings.st_typography_sub,
            category = strings.cat_nav,
            icon = Icons.Default.FormatSize,
            onClick = { onOpenDialog("gnome_typography") }
        ),
        SettingItemData(
            id = "accessibility",
            title = strings.st_accessibility_title,
            subtitle = strings.st_accessibility_sub,
            category = strings.cat_nav,
            icon = Icons.Default.Accessibility,
            onClick = { onOpenDialog("accessibility") }
        ),
        SettingItemData(
            id = "language",
            title = strings.st_language_title,
            subtitle = when (prefs.appLanguage) {
                "SYSTEM" -> "${strings.system_default} (${SendaLocaleManager.getSystemLanguageLabel(context)})"
                "DE" -> "Deutsch"
                "EN" -> "English"
                "ES" -> "Español"
                "FR" -> "Français"
                "PT" -> "Português"
                "IT" -> "Italiano"
                "JA" -> "日本語"
                "ZH" -> "中文"
                else -> prefs.appLanguage
            },
            category = strings.cat_nav,
            icon = Icons.Default.Language,
            onClick = { onOpenDialog("language") }
        ),
        SettingItemData(
            id = "translations",
            title = strings.st_translations_title,
            subtitle = strings.st_translations_sub,
            category = strings.cat_nav,
            icon = Icons.Default.Translate,
            onClick = { onOpenDialog("translations") }
        ),
        SettingItemData(
            id = "reader_mode",
            title = strings.tb_reader_mode,
            subtitle = strings.dlg_reader_mode_desc,
            category = strings.cat_nav,
            icon = Icons.AutoMirrored.Filled.MenuBook,
            onClick = { onOpenDialog("reader_mode") }
        ),

        SettingItemData(
            id = "default_browser",
            title = strings.st_default_browser_title,
            subtitle = strings.st_default_browser_sub,
            category = strings.cat_nav,
            icon = Icons.Default.Language,
            isToggle = true,
            isChecked = isDefaultBrowserApp,
            onToggleChange = {
                onRequestSetDefaultBrowser()
            },
            onClick = {
                onRequestSetDefaultBrowser()
            }
        ),

        // PRIVACY & SECURITY
        SettingItemData(
            id = "passwords",
            title = strings.st_passwords_title,
            subtitle = strings.st_passwords_sub,
            category = strings.cat_privacy,
            icon = Icons.Default.Password,
            onClick = { onOpenDialog("passwords") }
        ),
        SettingItemData(
            id = "autofill",
            title = strings.st_autofill_title,
            subtitle = strings.st_autofill_sub,
            category = strings.cat_privacy,
            icon = Icons.Default.AssignmentTurnedIn,
            onClick = { onOpenDialog("autofill") }
        ),
        SettingItemData(
            id = "private_browsing",
            title = strings.st_private_title,
            subtitle = strings.st_private_sub,
            category = strings.cat_privacy,
            icon = Icons.Default.VisibilityOff,
            onClick = { onOpenDialog("private_browsing") }
        ),
        SettingItemData(
            id = "https_only",
            title = strings.st_https_title,
            subtitle = httpsSummary,
            category = strings.cat_privacy,
            icon = Icons.Default.Lock,
            onClick = { onOpenDialog("https_only") }
        ),
        SettingItemData(
            id = "dns_over_https",
            title = strings.st_doh_title,
            subtitle = dohSummary,
            category = strings.cat_privacy,
            icon = Icons.Default.Dns,
            onClick = { onOpenDialog("dns_over_https") }
        ),
        SettingItemData(
            id = "tor_proxy",
            title = strings.st_tor_title,
            subtitle = strings.st_tor_sub,
            category = strings.cat_privacy,
            icon = Icons.Default.VpnKey,
            onClick = { onOpenDialog("tor_proxy") }
        ),
        SettingItemData(
            id = "tracking_protection",
            title = strings.st_tracking_title,
            subtitle = trackingSummary,
            category = strings.cat_privacy,
            icon = Icons.Default.Shield,
            onClick = { onOpenDialog("tracking_protection") }
        ),
        SettingItemData(
            id = "safe_browsing",
            title = strings.st_safe_browsing_title,
            subtitle = strings.st_safe_browsing_sub,
            category = strings.cat_privacy,
            icon = Icons.Default.GppMaybe,
            isToggle = true,
            isChecked = prefs.safeBrowsingEnabled,
            onToggleChange = {
                prefs.safeBrowsingEnabled = it
                onSettingsChanged()
            },
            onClick = {
                prefs.safeBrowsingEnabled = !prefs.safeBrowsingEnabled
                onSettingsChanged()
            }
        ),
        SettingItemData(
            id = "ublock_origin",
            title = "uBlock Origin",
            subtitle = strings.ublock_menu_sub,
            category = strings.cat_privacy,
            icon = Icons.Default.Shield,
            onClick = { onOpenDialog("ublock_origin") }
        ),
        SettingItemData(
            id = "security_vault",
            title = strings.st_security_vault_title,
            subtitle = strings.st_security_vault_sub,
            category = strings.cat_privacy,
            icon = Icons.Default.Fingerprint,
            onClick = { onOpenDialog("security_vault") }
        ),
        SettingItemData(
            id = "site_permissions",
            title = strings.st_site_permissions_title,
            subtitle = strings.st_site_permissions_sub,
            category = strings.cat_privacy,
            icon = Icons.Default.SettingsApplications,
            onClick = { onOpenDialog("site_permissions") }
        ),

        // ADVANCED
        SettingItemData(
            id = "extensions",
            title = strings.st_extensions_title,
            subtitle = strings.st_extensions_sub,
            category = strings.cat_advanced,
            icon = Icons.Default.Extension,
            onClick = { onOpenDialog("extensions") }
        ),
        SettingItemData(
            id = "download_settings",
            title = strings.st_downloads_title,
            subtitle = downloadsSummary,
            category = strings.cat_advanced,
            icon = Icons.Default.Download,
            onClick = { onOpenDialog("download_settings") }
        ),
        SettingItemData(
            id = "open_in_apps",
            title = strings.st_open_in_apps_title,
            subtitle = openLinksSummary,
            category = strings.cat_advanced,
            icon = Icons.AutoMirrored.Filled.OpenInNew,
            onClick = { onOpenDialog("open_in_apps") }
        ),
        SettingItemData(
            id = "senda_labs",
            title = strings.st_senda_labs_title,
            subtitle = strings.st_senda_labs_sub,
            category = strings.cat_advanced,
            icon = Icons.Default.Science,
            onClick = { onOpenDialog("senda_labs") }
        ),
        SettingItemData(
            id = "remote_debugging",
            title = strings.st_remote_debug_title,
            subtitle = strings.st_remote_debug_sub,
            category = strings.cat_advanced,
            icon = Icons.Default.BugReport,
            isToggle = true,
            isChecked = prefs.remoteDebuggingEnabled,
            onToggleChange = {
                prefs.remoteDebuggingEnabled = it
                onSettingsChanged()
            },
            onClick = {
                prefs.remoteDebuggingEnabled = !prefs.remoteDebuggingEnabled
                onSettingsChanged()
            }
        ),
        SettingItemData(
            id = "chromecast_settings",
            title = strings.st_chromecast_title,
            subtitle = strings.st_chromecast_sub,
            category = strings.cat_advanced,
            icon = Icons.Default.Cast,
            onClick = { onOpenDialog("chromecast_settings") }
        ),

        // ABOUT SENDA
        SettingItemData(
            id = "about_senda",
            title = strings.st_about_title,
            subtitle = strings.st_about_sub,
            category = strings.cat_about,
            icon = Icons.Default.Info,
            onClick = { onOpenDialog("about_senda") }
        )
    )
}
