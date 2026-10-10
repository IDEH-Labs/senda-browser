package org.senda.browser.ui.screens

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.senda.browser.core.*
import org.senda.browser.ui.components.CastHelper
import org.senda.browser.ui.components.ExtensionsManagerDialog
import org.senda.browser.ui.components.FreeWallpapers
import org.senda.browser.ui.components.BackupDialog
import org.senda.browser.ui.components.ToolbarCustomizationDialog
import org.senda.browser.ui.components.SendaVaultDialog
import org.senda.browser.ui.components.UBlockOriginDialog
import org.senda.browser.ui.screens.settings.*
import org.senda.browser.ui.theme.SendaColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    prefs: PreferencesManager,
    onBack: () -> Unit,
    onOpenUrl: ((String) -> Unit)? = null,
    onSettingsChanged: () -> Unit
) {
    val context = LocalContext.current
    val strings = LocalSendaStrings.current

    // Reactive preference states
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // Active dialogs (saved persistently so they do not close on theme or configuration changes)
    var activeDialog by rememberSaveable { mutableStateOf<String?>(null) }
    // Default browser checker and launcher
    var isDefaultBrowserApp by remember {
        mutableStateOf(checkIsDefaultBrowser(context))
    }

    val defaultBrowserLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        isDefaultBrowserApp = checkIsDefaultBrowser(context)
    }

    fun requestSetDefaultBrowser() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_BROWSER)) {
                val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_BROWSER)
                defaultBrowserLauncher.launch(intent)
                return
            }
        }
        try {
            val intent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
                context.startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    val allSettings = remember(prefs, strings, isDefaultBrowserApp) {
        buildSettingsList(
            context = context,
            prefs = prefs,
            strings = strings,
            isDefaultBrowserApp = isDefaultBrowserApp,
            onRequestSetDefaultBrowser = { requestSetDefaultBrowser() },
            onOpenDialog = { activeDialog = it },
            onSettingsChanged = onSettingsChanged
        )
    }

    // Real-time search filtering
    val filteredSettings = remember(searchQuery, allSettings) {
        if (searchQuery.isBlank()) {
            allSettings
        } else {
            val q = searchQuery.trim().lowercase()
            allSettings.filter {
                it.title.lowercase().contains(q) ||
                        it.subtitle.lowercase().contains(q) ||
                        it.category.lowercase().contains(q)
            }
        }
    }

    val categories = listOf(strings.cat_nav, strings.cat_privacy, strings.cat_advanced, strings.cat_about)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isSearchActive) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text(strings.search_settings, fontSize = 16.sp) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = 8.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent
                            )
                        )
                    } else {
                        Text(
                            text = strings.tb_settings,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (isSearchActive) {
                                isSearchActive = false
                                searchQuery = ""
                            } else {
                                onBack()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = strings.back,
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    if (isSearchActive) {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = strings.general_clear,
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    } else {
                        IconButton(onClick = { isSearchActive = true }) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = strings.search_settings,
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // TOP CARD: SYNC & IDENTITY (if not actively searching)
            if (!isSearchActive || searchQuery.isBlank()) {
                item {
                    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Card(
                            onClick = { activeDialog = "sync_ethical" },
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            shape = RoundedCornerShape(16.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(18.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(if (prefs.lastBackupTime > 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.EnhancedEncryption,
                                        contentDescription = null,
                                        tint = if (prefs.lastBackupTime > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = strings.bk_title,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = if (prefs.lastBackupTime > 0) {
                                            strings.bk_card_last.format(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(prefs.lastBackupTime)))
                                        } else {
                                            strings.bk_card_never
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }

            // SETTINGS CONTENT (BY CATEGORY OR SEARCH RESULTS)
            if (isSearchActive && searchQuery.isNotBlank()) {
                if (filteredSettings.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = strings.st_search_no_results.replace("%s", searchQuery),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    items(filteredSettings, key = { it.id }) { item ->
                        SettingRow(item = item)
                    }
                }
            } else {
                categories.forEach { category ->
                    val categoryItems = allSettings.filter { it.category == category }
                    if (categoryItems.isNotEmpty()) {
                        item(key = "header_$category") {
                            Text(
                                text = category,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 6.dp)
                            )
                        }
                        items(categoryItems, key = { it.id }) { item ->
                            SettingRow(item = item)
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // ==================== SETTINGS DIALOGS ====================

    // DIALOG: SEARCH ENGINE
    if (activeDialog == "search") {
        org.senda.browser.ui.screens.settings.SettingsSearchDialog(
            prefs = prefs,
            strings = strings,
            onSettingsChanged = onSettingsChanged,
            onDismiss = { activeDialog = null }
        )
    }

    // DIALOG: TAB MANAGEMENT
    if (activeDialog == "tabs") {
        org.senda.browser.ui.screens.settings.SettingsTabsDialog(
            prefs = prefs,
            strings = strings,
            onSettingsChanged = onSettingsChanged,
            onDismiss = { activeDialog = null }
        )
    }

    // HOME PAGE: the same sheet as the "Home page design" button on the home page itself, so both places
    // always offer the same options
    if (activeDialog == "home") {
        org.senda.browser.ui.components.HomeLayoutSelectorSheet(
            prefs = prefs,
            onDismiss = { activeDialog = null },
            onLayoutChanged = onSettingsChanged
        )
    }

    // DIALOG: APPEARANCE AND VISUAL THEME
    if (activeDialog == "customize") {
        org.senda.browser.ui.screens.settings.SettingsAppearanceDialog(
            prefs = prefs,
            strings = strings,
            onSettingsChanged = onSettingsChanged,
            onDismiss = { activeDialog = null }
        )
    }

    // DIALOG: CUSTOMIZE TOOLBAR
    if (activeDialog == "toolbar_customization") {
        ToolbarCustomizationDialog(
            prefs = prefs,
            onDismiss = { activeDialog = null },
            onChanged = onSettingsChanged
        )
    }

    // DIALOG: PASSWORD VAULT & AUDIT
    if (activeDialog == "passwords") {
        SendaVaultDialog(
            prefs = prefs,
            strings = strings,
            onDismiss = { activeDialog = null },
            onRequireBiometricAuth = { callback ->
                org.senda.browser.core.security.SendaVaultAuth.request(context, strings.st_passwords_title, callback)
            }
        )
    }

    // DIALOG: AUTOFILL
    if (activeDialog == "autofill") {
        SettingsAutofillDialog(
            context = context,
            strings = strings,
            onDismiss = { activeDialog = null }
        )
    }

    // DIALOG: VISUAL ACCESSIBILITY
    if (activeDialog == "accessibility") {
        SettingsAccessibilityDialog(
            prefs = prefs,
            strings = strings,
            onOpenTypography = { activeDialog = "gnome_typography" },
            onSettingsChanged = onSettingsChanged,
            onDismiss = { activeDialog = null }
        )
    }

    // DIALOG: TYPEFACES AND RENDERING
    if (activeDialog == "gnome_typography") {
        SettingsGnomeTypographyDialog(
            prefs = prefs,
            strings = strings,
            onSettingsChanged = onSettingsChanged,
            onDismiss = { activeDialog = null }
        )
    }

    // DIALOG: LANGUAGE
    if (activeDialog == "language") {
        SettingsLanguageDialog(
            context = context,
            prefs = prefs,
            strings = strings,
            onSettingsChanged = onSettingsChanged,
            onDismiss = { activeDialog = null }
        )
    }

    // DIALOG: TRANSLATIONS
    if (activeDialog == "translations") {
        SettingsTranslationsDialog(
            prefs = prefs,
            strings = strings,
            onSettingsChanged = onSettingsChanged,
            onDismiss = { activeDialog = null }
        )
    }

    // DIALOG: READER MODE AND SUMMARIES
    if (activeDialog == "reader_mode") {
        SettingsReaderModeDialog(
            prefs = prefs,
            strings = strings,
            onSettingsChanged = onSettingsChanged,
            onDismiss = { activeDialog = null }
        )
    }


    // DIALOG: TOR & SOCKS5 PROXY ROUTING
    if (activeDialog == "tor_proxy") {
        SettingsTorDialog(
            context = context,
            prefs = prefs,
            strings = strings,
            onSettingsChanged = onSettingsChanged,
            onDismiss = { activeDialog = null }
        )
    }

    // DIALOG: PRIVATE BROWSING
    if (activeDialog == "private_browsing") {
        SettingsPrivateBrowsingDialog(
            prefs = prefs,
            strings = strings,
            onSettingsChanged = onSettingsChanged,
            onDismiss = { activeDialog = null }
        )
    }

    // DIALOG: HTTPS-ONLY MODE
    if (activeDialog == "https_only") {
        SettingsHttpsOnlyDialog(
            prefs = prefs,
            strings = strings,
            onSettingsChanged = onSettingsChanged,
            onDismiss = { activeDialog = null }
        )
    }

    // DIALOG: DNS OVER HTTPS (DOH)
    if (activeDialog == "dns_over_https") {
        SettingsDohDialog(
            prefs = prefs,
            strings = strings,
            onSettingsChanged = onSettingsChanged,
            onDismiss = { activeDialog = null }
        )
    }

    // DIALOG: ENHANCED TRACKING PROTECTION
    if (activeDialog == "tracking_protection") {
        SettingsTrackingProtectionDialog(
            prefs = prefs,
            strings = strings,
            onSettingsChanged = onSettingsChanged,
            onDismiss = { activeDialog = null }
        )
    }

    // DIALOG: UBLOCK ORIGIN (FILTERS)
    if (activeDialog == "ublock_origin") {
        UBlockOriginDialog(
            onNavigate = { url ->
                activeDialog = null
                onOpenUrl?.invoke(url)
            },
            onDismiss = { activeDialog = null }
        )
    }

    // DIALOG: BIOMETRIC LOCK & ANTI-SNOOPING
    if (activeDialog == "security_vault") {
        SettingsSecurityVaultDialog(
            prefs = prefs,
            strings = strings,
            onSettingsChanged = onSettingsChanged,
            onDismiss = { activeDialog = null }
        )
    }

    // DIALOG: SITE SETTINGS & PERMISSIONS
    if (activeDialog == "site_permissions") {
        SettingsSitePermissionsDialog(
            prefs = prefs,
            strings = strings,
            onSettingsChanged = onSettingsChanged,
            onDismiss = { activeDialog = null }
        )
    }


    // DIALOG: ADD-ONS AND EXTENSIONS
    if (activeDialog == "extensions") {
        ExtensionsManagerDialog(
            onDismiss = { activeDialog = null }
        )
    }

    // DIALOG: OPEN LINKS IN APPS
    if (activeDialog == "open_in_apps") {
        SettingsOpenInAppsDialog(
            prefs = prefs,
            strings = strings,
            onSettingsChanged = onSettingsChanged,
            onDismiss = { activeDialog = null }
        )
    }

    // DIALOG: DOWNLOAD SETTINGS
    if (activeDialog == "download_settings") {
        SettingsDownloadSettingsDialog(
            prefs = prefs,
            strings = strings,
            onSettingsChanged = onSettingsChanged,
            onDismiss = { activeDialog = null }
        )
    }

    // DIALOG: SENDA LABS (EXPERIMENTAL FEATURES)
    if (activeDialog == "senda_labs") {
        SettingsSendaLabsDialog(
            prefs = prefs,
            strings = strings,
            onSettingsChanged = onSettingsChanged,
            onDismiss = { activeDialog = null }
        )
    }

    // DIALOG: ABOUT SENDA & MANIFESTO
    if (activeDialog == "about_senda") {
        SettingsAboutDialog(
            strings = strings,
            onDismiss = { activeDialog = null }
        )
    }

    // DIALOG: ENCRYPTED BACKUP (age) AND BOOKMARKS AS HTML
    if (activeDialog == "sync_ethical") {
        BackupDialog(
            prefs = prefs,
            onDismiss = { activeDialog = null }
        )
    }

    // DIALOG: CASTING
    if (activeDialog == "chromecast_settings") {
        SettingsChromecastDialog(
            context = context,
            prefs = prefs,
            strings = strings,
            onSettingsChanged = onSettingsChanged,
            onDismiss = { activeDialog = null }
        )
    }
}

@Composable
private fun SettingRow(item: SettingItemData) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = item.onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (item.subtitle.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (item.isToggle) {
            Spacer(modifier = Modifier.width(8.dp))
            Switch(
                checked = item.isChecked,
                onCheckedChange = { isChecked ->
                    item.onToggleChange?.invoke(isChecked)
                }
            )
        }
    }
}

private fun checkIsDefaultBrowser(context: Context): Boolean {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
            roleManager?.isRoleHeld(RoleManager.ROLE_BROWSER) ?: false
        } else {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://senda.org"))
            val resolveInfo = context.packageManager.resolveActivity(intent, 0)
            resolveInfo?.activityInfo?.packageName == context.packageName
        }
    } catch (_: Exception) {
        false
    }
}
