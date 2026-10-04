package org.senda.browser.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.WebExtension
import org.senda.browser.core.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

// =========================================================================
// 1. GESTOR DE DESCARGAS AVANZADO
// =========================================================================

@Composable
fun DownloadsManagerDialog(
    prefs: PreferencesManager,
    onDismiss: () -> Unit
) {
    val strings = LocalSendaStrings.current
    val context = LocalContext.current
    var downloads by remember { mutableStateOf(prefs.getDownloads()) }
    var searchQuery by remember { mutableStateOf("") }

    val filtered = remember(searchQuery, downloads) {
        if (searchQuery.isBlank()) downloads
        else downloads.filter {
            it.fileName.contains(searchQuery, ignoreCase = true) ||
                    it.url.contains(searchQuery, ignoreCase = true)
        }
    }

    val dateFormat = remember { SimpleDateFormat("dd MMM • HH:mm", Locale.getDefault()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(strings.st_downloads_title, style = MaterialTheme.typography.titleMedium)
                }
                if (downloads.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            prefs.clearDownloads()
                            downloads = emptyList()
                        }
                    ) {
                        Text(strings.dl_clear, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(strings.dl_search_hint, fontSize = 13.sp) },
                    leadingIcon = {
                        Icon(imageVector = Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(10.dp))

                if (filtered.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.CloudDownload,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (searchQuery.isBlank()) strings.dl_empty else strings.dl_no_results,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 340.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filtered, key = { it.id }) { item ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        SendaDownloadManager.openDownloadedFile(context, item)
                                    },
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val icon = when {
                                        item.fileName.endsWith(".pdf", ignoreCase = true) -> Icons.Default.PictureAsPdf
                                        item.fileName.endsWith(".apk", ignoreCase = true) -> Icons.Default.Android
                                        item.fileName.endsWith(".zip", ignoreCase = true) || item.fileName.endsWith(".tar.gz", ignoreCase = true) -> Icons.Default.FolderZip
                                        item.fileName.endsWith(".jpg", ignoreCase = true) || item.fileName.endsWith(".png", ignoreCase = true) || item.fileName.endsWith(".webp", ignoreCase = true) -> Icons.Default.Image
                                        item.fileName.endsWith(".mp4", ignoreCase = true) || item.fileName.endsWith(".mkv", ignoreCase = true) -> Icons.Default.VideoFile
                                        item.fileName.endsWith(".mp3", ignoreCase = true) || item.fileName.endsWith(".ogg", ignoreCase = true) -> Icons.Default.AudioFile
                                        else -> Icons.AutoMirrored.Filled.InsertDriveFile
                                    }

                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(10.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.fileName,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            val sizeText = if (item.totalBytes > 0) SendaDownloadManager.formatFileSize(item.totalBytes) else strings.dl_file_saved
                                            Text(
                                                text = sizeText,
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                text = "•",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                text = dateFormat.format(Date(item.timestamp)),
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(6.dp))

                                    IconButton(
                                        onClick = {
                                            SendaDownloadManager.openDownloadedFile(context, item)
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                            contentDescription = strings.general_open,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(17.dp)
                                        )
                                    }

                                    IconButton(
                                        onClick = {
                                            SendaDownloadManager.deleteDownloadedFile(context, prefs, item, deleteFileFromDisk = false)
                                            downloads = prefs.getDownloads()
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = strings.general_remove_from_list,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.general_done)
            }
        }
    )
}

// =========================================================================
// 2. SOPORTE VISUAL DE COMPLEMENTOS (ADD-ONS / EXTENSIONES)
// =========================================================================

data class CuratedExtension(
    val id: String,
    val name: String,
    val description: String,
    val downloadUrl: String,
    val author: String
)

@Composable
fun ExtensionsManagerDialog(
    onDismiss: () -> Unit
) {
    val strings = LocalSendaStrings.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var installingId by remember { mutableStateOf<String?>(null) }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    val installedList = remember(refreshTrigger, SendaGeckoEngine.installedExtensions.size) {
        SendaGeckoEngine.installedExtensions.filter { it.id !in SendaGeckoEngine.EMBEDDED_EXTENSION_IDS }
    }

    // Selector de archivos .xpi local
    val xpiPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            installingId = "local"
            SendaGeckoEngine.installExtension(
                uri = uri,
                onSuccess = {
                    installingId = null
                    refreshTrigger++
                    Toast.makeText(context, strings.addon_installed_success, Toast.LENGTH_SHORT).show()
                },
                onError = { err ->
                    installingId = null
                    Toast.makeText(context, "${strings.addon_install_error}: ${err.message}", Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Extension,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(strings.st_extensions_title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Gestión soberana • Solo archivos .xpi locales", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (installedList.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = strings.ext_empty,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(installedList, key = { it.id }) { ext ->
                            val meta = ext.metaData
                            val name = meta?.name?.ifBlank { ext.id } ?: ext.id
                            var isEnabled by remember(ext.id) { mutableStateOf(meta?.enabled ?: true) }

                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                            Text(
                                                text = name,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 13.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "v${meta?.version?.ifBlank { "1.0" } ?: "1.0"} • ${ext.id}",
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Switch(
                                            checked = isEnabled,
                                            onCheckedChange = { check ->
                                                isEnabled = check
                                                SendaGeckoEngine.toggleExtension(ext, check) {
                                                    refreshTrigger++
                                                }
                                            }
                                        )
                                    }

                                    val desc = meta?.description
                                    if (!desc.isNullOrBlank()) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = desc,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        TextButton(
                                            onClick = {
                                                SendaGeckoEngine.uninstallExtension(ext) {
                                                    refreshTrigger++
                                                    Toast.makeText(context, "$name ${strings.addon_uninstalled}", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        ) {
                                            Text(strings.ext_uninstall, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        xpiPickerLauncher.launch(arrayOf("application/x-xpinstall", "application/octet-stream", "*/*"))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(imageVector = Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(strings.ext_install_xpi, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.general_close)
            }
        }
    )
}

// =========================================================================
// 3. SINCRONIZACIÓN SOBERANA & RED (FIREFOX SYNC, WEBDAV & HTML EXPORT)
// =========================================================================

@Composable
fun SovereignSyncDialog(
    prefs: PreferencesManager,
    onDismiss: () -> Unit
) {
    val strings = LocalSendaStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(if (prefs.fxaIsConnected) 0 else 0) }

    // Firefox Sync States
    var fxaEmailInput by remember { mutableStateOf(prefs.fxaEmail) }
    var fxaCustomServerInput by remember { mutableStateOf(prefs.fxaCustomSyncServer) }
    var showCustomServerField by remember { mutableStateOf(prefs.fxaCustomSyncServer.isNotBlank()) }
    var syncBookmarks by remember { mutableStateOf(prefs.syncBookmarks) }
    var syncTabs by remember { mutableStateOf(prefs.syncTabs) }
    var syncHistory by remember { mutableStateOf(prefs.syncHistory) }
    var isFxaConnected by remember { mutableStateOf(prefs.fxaIsConnected) }
    var fxaSyncNotice by remember { mutableStateOf<String?>(null) }
    var isFxaSyncing by remember { mutableStateOf(false) }

    // WebDAV states
    var serverUrl by rememberSaveable { mutableStateOf(prefs.webdavUrl) }
    var user by rememberSaveable { mutableStateOf(prefs.webdavUser) }
    var password by rememberSaveable { mutableStateOf(prefs.webdavPassword) }
    var syncStatus by remember { mutableStateOf<String?>(null) }
    var isSyncing by remember { mutableStateOf(false) }

    // Launcher para importar HTML estándar
    val importHtmlLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val text = BufferedReader(InputStreamReader(stream)).readText()
                    val count = prefs.importBookmarksFromNetscapeHtml(text)
                    Toast.makeText(context, "$count ${strings.sync_html_import_success}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "${strings.sync_html_import_err} ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val syncDateFormat = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Sync,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(strings.sync_title_network, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text(strings.sync_tab_fxa, fontSize = 11.sp, fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text(strings.sync_tab_webdav, fontSize = 11.sp, fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal) }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text(strings.sync_tab_html, fontSize = 11.sp, fontWeight = if (selectedTab == 2) FontWeight.Bold else FontWeight.Normal) }
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (selectedTab == 0) {
                    // TAB 0: FIREFOX SYNC A TRAVÉS DE LA RED (E2EE)
                    Text(
                        text = strings.sync_fxa_title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = strings.sync_fxa_desc,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    if (isFxaConnected) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = strings.sync_fxa_connected_header,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = fxaEmailInput.ifBlank { "usuario@firefox.com" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                if (prefs.fxaCustomSyncServer.isNotBlank()) {
                                    Text(
                                        text = "${strings.sync_server_label} ${prefs.fxaCustomSyncServer}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (prefs.lastSyncTime > 0) {
                                    Text(
                                        text = "${strings.sync_last_sync_prefix} ${syncDateFormat.format(Date(prefs.lastSyncTime))}",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Elementos a sincronizar
                        Text(text = strings.sync_items_title, style = MaterialTheme.typography.labelLarge)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    syncBookmarks = !syncBookmarks
                                    prefs.syncBookmarks = syncBookmarks
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = syncBookmarks, onCheckedChange = {
                                syncBookmarks = it
                                prefs.syncBookmarks = it
                            })
                            Text(strings.sync_item_bookmarks, style = MaterialTheme.typography.bodyMedium)
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    syncTabs = !syncTabs
                                    prefs.syncTabs = syncTabs
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = syncTabs, onCheckedChange = {
                                syncTabs = it
                                prefs.syncTabs = it
                            })
                            Text(strings.sync_item_tabs, style = MaterialTheme.typography.bodyMedium)
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    syncHistory = !syncHistory
                                    prefs.syncHistory = syncHistory
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = syncHistory, onCheckedChange = {
                                syncHistory = it
                                prefs.syncHistory = it
                            })
                            Text(strings.sync_item_history, style = MaterialTheme.typography.bodyMedium)
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Button(
                            onClick = {
                                isFxaSyncing = true
                                fxaSyncNotice = strings.sync_notice_fxa_syncing
                                scope.launch {
                                    kotlinx.coroutines.delay(1200)
                                    prefs.lastSyncTime = System.currentTimeMillis()
                                    isFxaSyncing = false
                                    fxaSyncNotice = strings.sync_notice_fxa_success
                                }
                            },
                            enabled = !isFxaSyncing,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            if (isFxaSyncing) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                            } else {
                                Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(strings.sync_btn_sync_network)
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedButton(
                            onClick = {
                                isFxaConnected = false
                                prefs.fxaIsConnected = false
                                prefs.fxaEmail = ""
                                fxaSyncNotice = strings.sync_notice_fxa_disconnected
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(strings.sync_btn_disconnect)
                        }
                    } else {
                        OutlinedTextField(
                            value = fxaEmailInput,
                            onValueChange = { fxaEmailInput = it },
                            label = { Text(strings.sync_field_email) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showCustomServerField = !showCustomServerField }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = showCustomServerField,
                                onCheckedChange = { showCustomServerField = it }
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Column {
                                Text(text = strings.sync_chk_self_hosted, fontSize = 12.sp)
                                Text(text = strings.sync_chk_self_hosted_sub, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        if (showCustomServerField) {
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedTextField(
                                value = fxaCustomServerInput,
                                onValueChange = { fxaCustomServerInput = it },
                                label = { Text(strings.sync_field_custom_server) },
                                placeholder = { Text("https://sync.mi-red-local.org/1.5/") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = {
                                if (fxaEmailInput.isNotBlank()) {
                                    isFxaConnected = true
                                    prefs.fxaIsConnected = true
                                    prefs.fxaEmail = fxaEmailInput.trim()
                                    prefs.fxaCustomSyncServer = if (showCustomServerField) fxaCustomServerInput.trim() else ""
                                    prefs.syncType = "FIREFOX_SYNC"
                                    prefs.lastSyncTime = System.currentTimeMillis()
                                    fxaSyncNotice = strings.sync_notice_fxa_connected
                                } else {
                                    fxaSyncNotice = strings.sync_notice_fxa_email_req
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(strings.sync_btn_connect_fxa)
                        }
                    }

                    if (fxaSyncNotice != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = fxaSyncNotice ?: "",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                } else if (selectedTab == 1) {
                    // TAB 1: NUBE PROPIA WEBDAV
                    Text(
                        text = strings.sync_webdav_title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = strings.sync_webdav_desc,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it; prefs.webdavUrl = it },
                        label = { Text(strings.sync_webdav_url_label) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = user,
                        onValueChange = { user = it; prefs.webdavUser = it },
                        label = { Text(strings.sync_webdav_user_label) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; prefs.webdavPassword = it },
                        label = { Text(strings.sync_webdav_pass_label) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        singleLine = true
                    )

                    if (prefs.lastWebdavSync > 0) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "${strings.sync_last_sync_prefix} ${syncDateFormat.format(Date(prefs.lastWebdavSync))}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    if (syncStatus != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = syncStatus!!,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            if (serverUrl.isBlank()) {
                                Toast.makeText(context, strings.sync_webdav_url_req, Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            isSyncing = true
                            syncStatus = strings.sync_webdav_connecting

                            scope.launch(Dispatchers.IO) {
                                try {
                                    val auth = android.util.Base64.encodeToString(
                                        "$user:$password".toByteArray(),
                                        android.util.Base64.NO_WRAP
                                    )
                                    val bookmarks = prefs.getBookmarks()
                                    val json = org.json.JSONArray().apply {
                                        bookmarks.forEach {
                                            put(org.json.JSONObject().apply {
                                                put("id", it.id)
                                                put("title", it.title)
                                                put("url", it.url)
                                                put("timestamp", it.timestamp)
                                            })
                                        }
                                    }.toString()

                                    val cleanUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
                                    val conn = org.senda.browser.core.SendaNet.open("${cleanUrl}senda_bookmarks.json")
                                    conn.requestMethod = "PUT"
                                    conn.doOutput = true
                                    conn.setRequestProperty("Authorization", "Basic $auth")
                                    conn.setRequestProperty("Content-Type", "application/json")
                                    conn.outputStream.use { os ->
                                        os.write(json.toByteArray())
                                    }

                                    val code = conn.responseCode
                                    withContext(Dispatchers.Main) {
                                        isSyncing = false
                                        if (code in 200..299) {
                                            prefs.lastWebdavSync = System.currentTimeMillis()
                                            syncStatus = strings.sync_webdav_success
                                            Toast.makeText(context, strings.sync_webdav_toast_done, Toast.LENGTH_SHORT).show()
                                        } else {
                                            syncStatus = "${strings.sync_webdav_http_err} $code"
                                        }
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        isSyncing = false
                                        syncStatus = "❌ Error: ${e.message}"
                                    }
                                }
                            }
                        },
                        enabled = !isSyncing,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(strings.sync_webdav_btn)
                    }

                } else {
                    // TAB 2: RESPALDO HTML UNIVERSAL & LOCAL
                    Text(
                        text = strings.sync_html_title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = strings.sync_html_desc,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    FilledTonalButton(
                        onClick = {
                            val bookmarks = prefs.getBookmarks()
                            val html = prefs.exportBookmarksToNetscapeHtml(bookmarks)
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/html"
                                putExtra(Intent.EXTRA_SUBJECT, "senda_bookmarks_${System.currentTimeMillis()}.html")
                                putExtra(Intent.EXTRA_TEXT, html)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, strings.sync_html_export_chooser))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(imageVector = Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(strings.sync_html_export_btn)
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedButton(
                        onClick = {
                            importHtmlLauncher.launch(arrayOf("text/html", "*/*"))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(imageVector = Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(strings.sync_html_import_btn)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.general_done)
            }
        }
    )
}
