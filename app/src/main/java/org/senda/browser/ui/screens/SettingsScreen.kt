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
import org.senda.browser.ui.components.SovereignSyncDialog
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

    // Estados reactivos de preferencias
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // Diálogos activos (guardado persistente para no cerrarse en cambios de tema o configuración)
    var activeDialog by rememberSaveable { mutableStateOf<String?>(null) }
    var extensionInstallStatus by remember { mutableStateOf<String?>(null) }

    // Selector de archivos para extensiones .xpi locales
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            SendaGeckoEngine.installExtension(
                uri = uri,
                onSuccess = { ext ->
                    extensionInstallStatus = strings.addon_installed_success
                    onSettingsChanged()
                },
                onError = { err ->
                    extensionInstallStatus = "${strings.addon_install_error}: ${err.message}"
                }
            )
        }
    }

    // Comprobador y lanzador de navegador predeterminado
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

    // Filtrado de búsqueda en tiempo real
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
            // TARJETA SUPERIOR: SINCRONIZACIÓN ÉTICA & IDENTIDAD (si no está buscando activamente)
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
                                        .background(if (prefs.lastWebdavSync > 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (prefs.lastWebdavSync > 0) Icons.Default.CloudDone else Icons.Default.CloudUpload,
                                        contentDescription = null,
                                        tint = if (prefs.lastWebdavSync > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = strings.sync_card_title,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = if (prefs.lastWebdavSync > 0) {
                                            "${strings.sync_card_connected} ${java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(prefs.lastWebdavSync))}"
                                        } else {
                                            strings.sync_card_disconnected
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

            // CONTENIDO DE AJUSTES (POR CATEGORÍAS O RESULTADOS DE BÚSQUEDA)
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

    // ==================== DIÁLOGOS DE CONFIGURACIÓN ====================

    // DIÁLOGO: MOTOR DE BÚSQUEDA
    if (activeDialog == "search") {
        org.senda.browser.ui.screens.settings.SettingsSearchDialog(
            prefs = prefs,
            strings = strings,
            onSettingsChanged = onSettingsChanged,
            onDismiss = { activeDialog = null }
        )
    }

    // DIÁLOGO: GESTIÓN DE PESTAÑAS
    if (activeDialog == "tabs") {
        org.senda.browser.ui.screens.settings.SettingsTabsDialog(
            prefs = prefs,
            strings = strings,
            onSettingsChanged = onSettingsChanged,
            onDismiss = { activeDialog = null }
        )
    }

    // DIÁLOGO: PÁGINA DE INICIO (ZEN)
    if (activeDialog == "home") {
        org.senda.browser.ui.screens.settings.SettingsHomeDialog(
            context = context,
            prefs = prefs,
            strings = strings,
            onSettingsChanged = onSettingsChanged,
            onDismiss = { activeDialog = null }
        )
    }

    // DIÁLOGO: APARIENCIA Y TEMA VISUAL
    if (activeDialog == "customize") {
        org.senda.browser.ui.screens.settings.SettingsAppearanceDialog(
            prefs = prefs,
            strings = strings,
            onSettingsChanged = onSettingsChanged,
            onDismiss = { activeDialog = null }
        )
    }

    // DIÁLOGO: PERSONALIZAR BARRA DE HERRAMIENTAS
    if (activeDialog == "toolbar_customization") {
        ToolbarCustomizationDialog(
            prefs = prefs,
            onDismiss = { activeDialog = null },
            onChanged = onSettingsChanged
        )
    }

    // DIÁLOGO: BÓVEDA SOBERANA DE CONTRASEÑAS & AUDITORÍA
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

    // DIÁLOGO: AUTOCOMPLETADO
    if (activeDialog == "autofill") {
        SettingsAutofillDialog(
            context = context,
            strings = strings,
            onDismiss = { activeDialog = null }
        )
    }

    // DIÁLOGO: ACCESIBILIDAD VISUAL
    if (activeDialog == "accessibility") {
        SettingsAccessibilityDialog(
            prefs = prefs,
            strings = strings,
            onOpenTypography = { activeDialog = "gnome_typography" },
            onSettingsChanged = onSettingsChanged,
            onDismiss = { activeDialog = null }
        )
    }

    // DIÁLOGO: TIPOGRAFÍAS Y RENDERIZADO
    if (activeDialog == "gnome_typography") {
        SettingsGnomeTypographyDialog(
            prefs = prefs,
            strings = strings,
            onSettingsChanged = onSettingsChanged,
            onDismiss = { activeDialog = null }
        )
    }

    // DIÁLOGO: IDIOMA
    if (activeDialog == "language") {
        SettingsLanguageDialog(
            context = context,
            prefs = prefs,
            strings = strings,
            onSettingsChanged = onSettingsChanged,
            onDismiss = { activeDialog = null }
        )
    }

    // DIÁLOGO: TRADUCCIONES
    if (activeDialog == "translations") {
        SettingsTranslationsDialog(
            prefs = prefs,
            strings = strings,
            onSettingsChanged = onSettingsChanged,
            onDismiss = { activeDialog = null }
        )
    }

    // DIÁLOGO: MODO LECTURA Y RESÚMENES
    if (activeDialog == "reader_mode" || activeDialog == "summaries") {
        SettingsReaderModeDialog(
            prefs = prefs,
            strings = strings,
            onSettingsChanged = onSettingsChanged,
            onDismiss = { activeDialog = null }
        )
    }

    // DIÁLOGO: ASISTENTE CON IA EXTERNA
    if (activeDialog == "assistant") {
        org.senda.browser.ui.components.SendaAssistantSettingsDialog(prefs = prefs, onDismiss = { activeDialog = null })
    }

    // DIÁLOGO: ENRUTAMIENTO TOR & PROXY SOCKS5
    if (activeDialog == "tor_proxy") {
        SettingsTorDialog(
            context = context,
            prefs = prefs,
            strings = strings,
            onSettingsChanged = onSettingsChanged,
            onDismiss = { activeDialog = null }
        )
    }

    // DIÁLOGO: NAVEGACIÓN PRIVADA
    if (activeDialog == "private_browsing") {
        SettingsPrivateBrowsingDialog(
            prefs = prefs,
            strings = strings,
            onSettingsChanged = onSettingsChanged,
            onDismiss = { activeDialog = null }
        )
    }

    // DIÁLOGO: MODO SOLO HTTPS
    if (activeDialog == "https_only") {
        SettingsHttpsOnlyDialog(
            prefs = prefs,
            strings = strings,
            onSettingsChanged = onSettingsChanged,
            onDismiss = { activeDialog = null }
        )
    }

    // DIÁLOGO: DNS SOBRE HTTPS (DOH)
    if (activeDialog == "dns_over_https") {
        SettingsDohDialog(
            prefs = prefs,
            strings = strings,
            onSettingsChanged = onSettingsChanged,
            onDismiss = { activeDialog = null }
        )
    }

    // DIÁLOGO: PROTECCIÓN CONTRA RASTREO MEJORADA
    if (activeDialog == "tracking_protection") {
        SettingsTrackingProtectionDialog(
            prefs = prefs,
            strings = strings,
            onSettingsChanged = onSettingsChanged,
            onDismiss = { activeDialog = null }
        )
    }

    // DIÁLOGO: UBLOCK ORIGIN (SOBERANÍA Y FILTROS)
    if (activeDialog == "ublock_origin") {
        UBlockOriginDialog(
            onNavigate = { url ->
                activeDialog = null
                onOpenUrl?.invoke(url)
            },
            onDismiss = { activeDialog = null }
        )
    }

    // DIÁLOGO: BÓVEDA BIOMÉTRICA & ANTI-ESPIONAJE
    if (activeDialog == "security_vault") {
        SettingsSecurityVaultDialog(
            prefs = prefs,
            strings = strings,
            onSettingsChanged = onSettingsChanged,
            onDismiss = { activeDialog = null }
        )
    }

    // DIÁLOGO: CONFIGURACIÓN DEL SITIO & PERMISOS
    if (activeDialog == "site_permissions") {
        SettingsSitePermissionsDialog(
            prefs = prefs,
            strings = strings,
            onSettingsChanged = onSettingsChanged,
            onDismiss = { activeDialog = null }
        )
    }

    // DIÁLOGO: RECOPILACIÓN DE DATOS & PRIVACIDAD
    if (activeDialog == "data_collection") {
        SettingsDataCollectionDialog(
            strings = strings,
            onDismiss = { activeDialog = null }
        )
    }

    // DIÁLOGO: COMPLEMENTOS Y EXTENSIONES
    if (activeDialog == "extensions") {
        ExtensionsManagerDialog(
            onDismiss = { activeDialog = null }
        )
    }

    // DIÁLOGO: ABRIR ENLACES EN APLICACIONES
    if (activeDialog == "open_in_apps") {
        SettingsOpenInAppsDialog(
            prefs = prefs,
            strings = strings,
            onSettingsChanged = onSettingsChanged,
            onDismiss = { activeDialog = null }
        )
    }

    // DIÁLOGO: AJUSTES DE DESCARGA
    if (activeDialog == "download_settings") {
        SettingsDownloadSettingsDialog(
            prefs = prefs,
            strings = strings,
            onSettingsChanged = onSettingsChanged,
            onDismiss = { activeDialog = null }
        )
    }

    // DIÁLOGO: SENDA LABS (FUNCIONES EXPERIMENTALES)
    if (activeDialog == "senda_labs") {
        SettingsSendaLabsDialog(
            prefs = prefs,
            strings = strings,
            onSettingsChanged = onSettingsChanged,
            onDismiss = { activeDialog = null }
        )
    }

    // DIÁLOGO: SOBRE SENDA & MANIFIESTO
    if (activeDialog == "about_senda" || activeDialog == "ethical_manifesto" || activeDialog == "third_party_licenses") {
        SettingsAboutDialog(
            activeDialog = activeDialog ?: "about_senda",
            strings = strings,
            onDismiss = { activeDialog = null }
        )
    }

    // DIÁLOGO: SINCRONIZACIÓN Y RESPALDO (WEBDAV & NETSCAPE HTML)
    if (activeDialog == "sync_ethical") {
        SovereignSyncDialog(
            prefs = prefs,
            onDismiss = { activeDialog = null }
        )
    }

    // DIÁLOGO: TRANSMISIÓN Y CHROMECAST
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
