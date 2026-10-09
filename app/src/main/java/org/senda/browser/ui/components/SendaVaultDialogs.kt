package org.senda.browser.ui.components

import androidx.compose.foundation.selection.toggleable
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.senda.browser.core.PreferencesManager
import org.senda.browser.core.SendaStringPack
import org.senda.browser.core.security.SendaVaultAuditRunner
import org.senda.browser.core.security.SendaVaultManager
import org.senda.browser.core.security.VaultAuditReport
import org.senda.browser.core.security.VaultCredential

/**
 * Main dialog of Senda's password vault.
 * Gives full control over credentials, a cryptographic generator and a real-time audit.
 */
@Composable
fun SendaVaultDialog(
    prefs: PreferencesManager,
    strings: SendaStringPack,
    onDismiss: () -> Unit,
    onRequireBiometricAuth: ((Boolean) -> Unit) -> Unit
) {
    val context = LocalContext.current
    // Key invalidated by Android (the screen lock was removed or reset): before, only a notice appeared and the
    // vault was unusable forever
    var showVaultReset by remember { mutableStateOf(false) }

    // Runs an operation with the vault key. If more than 30 s have passed since the last authentication,
    // the chip rejects it: fingerprint or PIN is asked for and it is retried once
    fun secure(action: () -> Unit) {
        fun explain(e: org.senda.browser.core.security.VaultUnavailableException) {
            when (e.reason) {
                org.senda.browser.core.security.VaultUnavailableException.Reason.NO_SCREEN_LOCK ->
                    Toast.makeText(context, strings.vault_err_no_lock, Toast.LENGTH_LONG).show()
                org.senda.browser.core.security.VaultUnavailableException.Reason.KEY_INVALIDATED ->
                    showVaultReset = true
                org.senda.browser.core.security.VaultUnavailableException.Reason.NO_SECURE_HARDWARE ->
                    Toast.makeText(context, strings.vault_err_no_secure_hw, Toast.LENGTH_LONG).show()
            }
        }
        try {
            action()
        } catch (_: org.senda.browser.core.security.VaultLockedException) {
            onRequireBiometricAuth { ok ->
                if (!ok) return@onRequireBiometricAuth
                try {
                    action()
                } catch (e: org.senda.browser.core.security.VaultUnavailableException) {
                    explain(e)
                } catch (e: Exception) {
                    Toast.makeText(context, strings.vault_err_generic.format(e.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: org.senda.browser.core.security.VaultUnavailableException) {
            explain(e)
        } catch (e: Exception) {
            Toast.makeText(context, strings.vault_err_generic.format(e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }
    val coroutineScope = rememberCoroutineScope()

    var activeSubView by rememberSaveable { mutableStateOf("main") } // main, list, generator, audit, add, edit
    // Editing: the decrypted password lives only in memory while the form is open
    var editId by remember { mutableStateOf<String?>(null) }
    var editSite by remember { mutableStateOf("") }
    var editUser by remember { mutableStateOf("") }
    var editPass by remember { mutableStateOf("") }
    var editPassVisible by remember { mutableStateOf(false) }
    fun closeEdit() {
        editId = null
        editPass = ""
        editPassVisible = false
        activeSubView = "list"
    }
    var credentials by remember { mutableStateOf(SendaVaultManager.getCredentials(context)) }
    var searchQuery by remember { mutableStateOf("") }

    // Checked on open: with no saved passwords nothing is lost and a new key is created without asking
    LaunchedEffect(Unit) {
        if (SendaVaultManager.isVaultKeyInvalidated()) {
            if (credentials.isEmpty()) SendaVaultManager.resetInvalidatedVaultKey(context) else showVaultReset = true
        }
    }

    if (showVaultReset) {
        AlertDialog(
            onDismissRequest = { showVaultReset = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(strings.vault_reset_title) },
            text = { Text(strings.vault_reset_body) },
            confirmButton = {
                TextButton(onClick = {
                    SendaVaultManager.resetInvalidatedVaultKey(context)
                    credentials = SendaVaultManager.getCredentials(context)
                    showVaultReset = false
                    Toast.makeText(context, strings.vault_reset_done, Toast.LENGTH_SHORT).show()
                }) {
                    Text(strings.vault_reset_confirm, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showVaultReset = false }) { Text(strings.general_cancel) }
            }
        )
    }

    // Password waiting for the user to confirm its deletion
    var confirmDelete by remember { mutableStateOf<VaultCredential?>(null) }
    confirmDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(strings.vault_delete_confirm_title) },
            text = { Text(strings.vault_delete_confirm_body.format(item.username.ifBlank { "—" }, item.domain)) },
            confirmButton = {
                TextButton(onClick = {
                    SendaVaultManager.deleteCredential(context, item.id)
                    credentials = SendaVaultManager.getCredentials(context)
                    confirmDelete = null
                    Toast.makeText(context, strings.vault_deleted, Toast.LENGTH_SHORT).show()
                }) { Text(strings.general_delete, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(strings.general_cancel) } }
        )
    }

    // ---- Import and export (CSV) ----
    class ImportPreview(val uri: android.net.Uri, val fileName: String, val result: org.senda.browser.core.security.VaultCsv.ImportResult, val existing: Int)
    var importPreview by remember { mutableStateOf<ImportPreview?>(null) }
    var replaceExisting by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var deleteCsvOffer by remember { mutableStateOf<Pair<android.net.Uri, String>?>(null) }
    var showExportWarning by remember { mutableStateOf(false) }

    fun displayName(uri: android.net.Uri): String =
        runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty()

    // Heavy work with the vault key off the main thread; if the key is locked (more than 30 s since the
    // fingerprint or PIN) it is asked for and the work is retried once. Nothing is written until it all succeeds
    fun <T> secureInBackground(work: () -> T, onDone: (T) -> Unit, onFail: () -> Unit = {}) {
        fun run(retry: Boolean) {
            coroutineScope.launch {
                val result = withContext(Dispatchers.Default) { runCatching(work) }
                result.onSuccess(onDone).onFailure { e ->
                    when (e) {
                        is org.senda.browser.core.security.VaultLockedException ->
                            if (retry) onRequireBiometricAuth { ok -> if (ok) run(false) else onFail() } else onFail()
                        is org.senda.browser.core.security.VaultUnavailableException -> {
                            when (e.reason) {
                                org.senda.browser.core.security.VaultUnavailableException.Reason.NO_SCREEN_LOCK ->
                                    Toast.makeText(context, strings.vault_err_no_lock, Toast.LENGTH_LONG).show()
                                org.senda.browser.core.security.VaultUnavailableException.Reason.KEY_INVALIDATED -> showVaultReset = true
                                org.senda.browser.core.security.VaultUnavailableException.Reason.NO_SECURE_HARDWARE ->
                                    Toast.makeText(context, strings.vault_err_no_secure_hw, Toast.LENGTH_LONG).show()
                            }
                            onFail()
                        }
                        else -> {
                            Toast.makeText(context, strings.vault_err_generic.format(e.message ?: ""), Toast.LENGTH_LONG).show()
                            onFail()
                        }
                    }
                }
            }
        }
        run(true)
    }

    // The system picker: Senda only sees the file the user chooses
    val importPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            val name = displayName(uri)
            val parsed = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                        val buf = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(chunk)
                            if (n < 0) break
                            buf.write(chunk, 0, n)
                            if (buf.size() > org.senda.browser.core.security.VaultCsv.MAX_IMPORT_BYTES) throw java.io.IOException("too_big")
                        }
                        buf.toByteArray()
                    } ?: throw java.io.IOException("unreadable")
                    val result = org.senda.browser.core.security.VaultCsv.parseLogins(String(bytes, Charsets.UTF_8))
                    java.util.Arrays.fill(bytes, 0)
                    // Accounts already saved (same site and user): counted without decrypting anything
                    val saved = SendaVaultManager.getCredentials(context).map { it.domain.lowercase() to it.username }.toSet()
                    val existing = result.logins.count { SendaVaultManager.extractCanonicalDomain(it.url, context).lowercase() to it.username in saved }
                    ImportPreview(uri, name, result, existing)
                }
            }
            parsed.onSuccess { preview ->
                if (preview.result.logins.isEmpty()) Toast.makeText(context, strings.vault_import_none, Toast.LENGTH_LONG).show()
                else { replaceExisting = false; importPreview = preview }
            }.onFailure { e ->
                val msg = when {
                    e is org.senda.browser.core.security.VaultCsv.UnsupportedFormatException -> strings.vault_import_unsupported
                    e.message == "too_big" -> strings.vault_import_too_big.format(org.senda.browser.core.security.VaultCsv.MAX_IMPORT_BYTES / (1024 * 1024))
                    else -> strings.vault_err_generic.format(e.message ?: "")
                }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    importPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { if (!importing) importPreview = null },
            icon = { Icon(Icons.Default.FileDownload, contentDescription = null) },
            title = { Text(strings.vault_import_btn) },
            text = {
                Column {
                    Text(strings.vault_import_found.format(preview.result.logins.size), fontWeight = FontWeight.SemiBold)
                    if (preview.result.skipped > 0) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(strings.vault_import_skipped.format(preview.result.skipped), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (preview.existing > 0) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(strings.vault_import_existing.format(preview.existing), fontSize = 12.sp)
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().toggleable(
                            value = replaceExisting, enabled = !importing, role = androidx.compose.ui.semantics.Role.Checkbox,
                            onValueChange = { replaceExisting = it })) {
                            Checkbox(checked = replaceExisting, onCheckedChange = null, enabled = !importing)
                            Text(strings.vault_import_replace, fontSize = 12.sp)
                        }
                    }
                    if (importing) {
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                Button(enabled = !importing, onClick = {
                    importing = true
                    secureInBackground(
                        work = { SendaVaultManager.importLogins(context, preview.result.logins, replaceExisting) },
                        onDone = { summary ->
                            importing = false
                            importPreview = null
                            credentials = SendaVaultManager.getCredentials(context)
                            Toast.makeText(context, strings.vault_import_done.format(summary.added, summary.replaced, summary.kept), Toast.LENGTH_LONG).show()
                            // The file still holds every password unencrypted: offer to delete it
                            deleteCsvOffer = preview.uri to preview.fileName
                        },
                        onFail = { importing = false }
                    )
                }) { Text(strings.vault_import_confirm) }
            },
            dismissButton = { TextButton(enabled = !importing, onClick = { importPreview = null }) { Text(strings.general_cancel) } }
        )
    }

    deleteCsvOffer?.let { (uri, name) ->
        AlertDialog(
            onDismissRequest = { deleteCsvOffer = null },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(strings.vault_import_delete_title) },
            text = { Text(strings.vault_import_delete_body.format(name)) },
            confirmButton = {
                Button(onClick = {
                    val deleted = runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false)
                    Toast.makeText(context, if (deleted) strings.vault_import_deleted else strings.vault_import_delete_failed.format(name), Toast.LENGTH_LONG).show()
                    deleteCsvOffer = null
                }) { Text(strings.vault_import_delete_confirm) }
            },
            dismissButton = { TextButton(onClick = { deleteCsvOffer = null }) { Text(strings.vault_import_keep) } }
        )
    }

    // Export: the user chooses where; a fingerprint or PIN is always asked right before decrypting
    val exportPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = displayName(uri)
        fun discard() { runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) } }
        onRequireBiometricAuth { ok ->
            if (!ok) {
                discard()
                Toast.makeText(context, strings.vault_export_failed, Toast.LENGTH_SHORT).show()
                return@onRequireBiometricAuth
            }
            secureInBackground(
                work = {
                    val logins = SendaVaultManager.exportLogins(context)
                    val bytes = org.senda.browser.core.security.VaultCsv.write(logins).toByteArray(Charsets.UTF_8)
                    try {
                        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: throw java.io.IOException("unwritable")
                    } finally {
                        java.util.Arrays.fill(bytes, 0)
                    }
                    logins.size
                },
                onDone = { count -> Toast.makeText(context, strings.vault_export_done.format(count, name), Toast.LENGTH_LONG).show() },
                onFail = {
                    discard()
                    Toast.makeText(context, strings.vault_export_failed, Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    if (showExportWarning) {
        AlertDialog(
            onDismissRequest = { showExportWarning = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(strings.vault_export_warn_title) },
            text = { Text(strings.vault_export_warn_body) },
            confirmButton = {
                Button(onClick = {
                    showExportWarning = false
                    val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date())
                    exportPicker.launch("senda-passwords-$date.csv")
                }) { Text(strings.vault_export_confirm) }
            },
            dismissButton = { TextButton(onClick = { showExportWarning = false }) { Text(strings.general_cancel) } }
        )
    }

    // Password generator
    var genLength by rememberSaveable { mutableFloatStateOf(18f) }
    var genUpper by rememberSaveable { mutableStateOf(true) }
    var genLower by rememberSaveable { mutableStateOf(true) }
    var genDigits by rememberSaveable { mutableStateOf(true) }
    var genSymbols by rememberSaveable { mutableStateOf(true) }
    var generatedPassword by remember { mutableStateOf("") }
    var generatedEntropy by remember { mutableDoubleStateOf(0.0) }

    fun refreshGeneratedPassword() {
        val (chars, entropy) = SendaVaultManager.generateStrongPassword(
            length = genLength.toInt(),
            includeUpper = genUpper,
            includeLower = genLower,
            includeDigits = genDigits,
            includeSymbols = genSymbols
        )
        generatedPassword = String(chars)
        generatedEntropy = entropy
        SendaVaultManager.wipe(chars)
    }

    LaunchedEffect(Unit) {
        refreshGeneratedPassword()
    }

    // Where Android really keeps the master key (shown as is, without assuming secure hardware)
    val keyLevel = remember { SendaVaultManager.keySecurityLevel() }

    // Audit & stress
    var isRunningAudit by remember { mutableStateOf(false) }
    var auditReport by remember { mutableStateOf<VaultAuditReport?>(null) }

    AlertDialog(
        onDismissRequest = {
            if (activeSubView != "main") activeSubView = "main" else onDismiss()
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when (activeSubView) {
                        "audit" -> Icons.Default.Assessment
                        "generator" -> Icons.Default.Key
                        "list" -> Icons.Default.FolderShared
                        else -> Icons.Default.Lock
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when (activeSubView) {
                        "audit" -> strings.vault_title_audit
                        "generator" -> strings.vault_title_generator
                        "list" -> strings.vault_title_list.format(credentials.size)
                        "add" -> strings.vault_title_add
                        "edit" -> strings.vault_title_edit
                        else -> strings.st_passwords_title
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
            ) {
                when (activeSubView) {
                    "main" -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                        ) {
                            // Hardware status chip
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Shield,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = strings.vault_badge_title,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                        Text(
                                            text = when (keyLevel) {
                                                "STRONGBOX" -> strings.vault_key_strongbox
                                                "TEE" -> strings.vault_key_tee
                                                "SOFTWARE" -> strings.vault_key_software
                                                else -> strings.vault_key_unknown
                                            },
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // Save and fill in pages: SendaVaultLoginStorage + SendaPrompt.LoginSelect/LoginSave
                            Text(
                                text = strings.dlg_passwords_info,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), thickness = 0.5.dp)

                            // Button: View saved credentials
                            OutlinedButton(
                                onClick = {
                                    onRequireBiometricAuth { success ->
                                        if (success) {
                                            credentials = SendaVaultManager.getCredentials(context)
                                            activeSubView = "list"
                                        } else {
                                            Toast.makeText(context, strings.vault_auth_required, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(strings.vault_open.format(credentials.size), fontSize = 12.sp)
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Buttons: import from other browsers and managers / export to move elsewhere
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { importPicker.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream")) },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(strings.vault_import_btn, fontSize = 12.sp)
                                }
                                OutlinedButton(
                                    onClick = { showExportWarning = true },
                                    enabled = credentials.isNotEmpty(),
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(strings.vault_export_btn, fontSize = 12.sp)
                                }
                            }
                            Text(strings.vault_import_help, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp))

                            Spacer(modifier = Modifier.height(8.dp))

                            // Button: Password generator
                            OutlinedButton(
                                onClick = { activeSubView = "generator" },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(strings.vault_title_generator, fontSize = 12.sp)
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Button: Audit and stress
                            Button(
                                onClick = {
                                    activeSubView = "audit"
                                    if (auditReport == null) {
                                        isRunningAudit = true
                                        coroutineScope.launch(Dispatchers.Default) {
                                            val rep = SendaVaultAuditRunner.runFullAudit(context, strings, stressCycles = 1000)
                                            withContext(Dispatchers.Main) {
                                                auditReport = rep
                                                isRunningAudit = false
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.filledTonalButtonColors()
                            ) {
                                Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(strings.vault_title_audit, fontSize = 12.sp)
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Button: Open system autofill
                            TextButton(
                                onClick = {
                                    try {
                                        // Android screen to choose the password manager (Android 14+). The previous
                                        // action tried to register Senda as a provider and did nothing useful
                                        context.startActivity(
                                            Intent(
                                                if (android.os.Build.VERSION.SDK_INT >= 34) "android.settings.CREDENTIAL_PROVIDER"
                                                else Settings.ACTION_SETTINGS
                                            )
                                        )
                                    } catch (e: Exception) {
                                        context.startActivity(Intent(Settings.ACTION_SETTINGS))
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(strings.vault_autofill, fontSize = 11.sp)
                            }
                        }
                    }

                    "list" -> {
                        // Credential list
                        Column(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text(strings.vault_search, fontSize = 12.sp) },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                shape = RoundedCornerShape(8.dp)
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            val filtered = credentials.filter {
                                it.domain.contains(searchQuery, ignoreCase = true) ||
                                it.username.contains(searchQuery, ignoreCase = true)
                            }

                            if (filtered.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(180.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (credentials.isEmpty()) strings.vault_empty else strings.vault_no_match,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f, fill = false),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(filtered, key = { it.id }) { item ->
                                        CredentialItemCard(
                                            item = item,
                                            // Deleting a password cannot be undone: always confirm first
                                            onDelete = { confirmDelete = item },
                                            runSecure = { action -> secure(action) },
                                            onEdit = {
                                                secure {
                                                    val chars = SendaVaultManager.decryptPassword(item.encryptedPasswordBase64, item.ivBase64)
                                                    editPass = String(chars)
                                                    SendaVaultManager.wipe(chars)
                                                    editId = item.id
                                                    editSite = item.originUrl.ifBlank { item.domain }
                                                    editUser = item.username
                                                    editPassVisible = false
                                                    activeSubView = "edit"
                                                }
                                            }
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Button(
                                onClick = { activeSubView = "add" },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(strings.vault_title_add, fontSize = 12.sp)
                            }
                        }
                    }

                    "add" -> {
                        // Add account form
                        var addDomain by rememberSaveable { mutableStateOf("") }
                        var addUser by rememberSaveable { mutableStateOf("") }
                        var addPass by rememberSaveable { mutableStateOf("") }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                        ) {
                            OutlinedTextField(
                                value = addDomain,
                                onValueChange = { addDomain = it },
                                label = { Text(strings.vault_site, fontSize = 12.sp) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = addUser,
                                onValueChange = { addUser = it },
                                label = { Text(strings.vault_user, fontSize = 12.sp) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = addPass,
                                onValueChange = { addPass = it },
                                label = { Text(strings.vault_password, fontSize = 12.sp) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                trailingIcon = {
                                    IconButton(onClick = {
                                        val (chars, _) = SendaVaultManager.generateStrongPassword(length = 18)
                                        addPass = String(chars)
                                        SendaVaultManager.wipe(chars)
                                    }) {
                                        Icon(Icons.Default.AutoFixHigh, contentDescription = strings.vault_generate, modifier = Modifier.size(18.dp))
                                    }
                                }
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { activeSubView = "list" }) {
                                    Text(strings.general_cancel)
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        if (addDomain.isNotBlank() && addUser.isNotBlank() && addPass.isNotBlank()) {
                                            secure {
                                                val passChars = addPass.toCharArray()
                                                try {
                                                    SendaVaultManager.saveCredential(context, addDomain, addUser, passChars)
                                                } finally {
                                                    SendaVaultManager.wipe(passChars)
                                                }
                                                credentials = SendaVaultManager.getCredentials(context)
                                                activeSubView = "list"
                                                Toast.makeText(context, strings.vault_saved, Toast.LENGTH_SHORT).show()
                                            }
                                        } else {
                                            Toast.makeText(context, strings.vault_fill_all, Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(strings.vault_save)
                                }
                            }
                        }
                    }

                    "edit" -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                        ) {
                            OutlinedTextField(
                                value = editSite,
                                onValueChange = { editSite = it },
                                label = { Text(strings.vault_site, fontSize = 12.sp) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = editUser,
                                onValueChange = { editUser = it },
                                label = { Text(strings.vault_user, fontSize = 12.sp) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = editPass,
                                onValueChange = { editPass = it },
                                label = { Text(strings.vault_password, fontSize = 12.sp) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                visualTransformation = if (editPassVisible) androidx.compose.ui.text.input.VisualTransformation.None
                                    else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                trailingIcon = {
                                    Row {
                                        IconButton(onClick = { editPassVisible = !editPassVisible }) {
                                            Icon(
                                                if (editPassVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                contentDescription = if (editPassVisible) strings.vault_hide else strings.vault_reveal,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        IconButton(onClick = {
                                            val (chars, _) = SendaVaultManager.generateStrongPassword(length = 18)
                                            editPass = String(chars)
                                            SendaVaultManager.wipe(chars)
                                            editPassVisible = true
                                        }) {
                                            Icon(Icons.Default.AutoFixHigh, contentDescription = strings.vault_generate, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { closeEdit() }) {
                                    Text(strings.general_cancel)
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        val id = editId
                                        if (id != null && editSite.isNotBlank() && editUser.isNotBlank() && editPass.isNotBlank()) {
                                            secure {
                                                val passChars = editPass.toCharArray()
                                                val ok = try {
                                                    SendaVaultManager.updateCredential(context, id, editSite, editUser, passChars)
                                                } finally {
                                                    SendaVaultManager.wipe(passChars)
                                                }
                                                if (ok) {
                                                    credentials = SendaVaultManager.getCredentials(context)
                                                    closeEdit()
                                                    Toast.makeText(context, strings.vault_saved, Toast.LENGTH_SHORT).show()
                                                } else {
                                                    Toast.makeText(context, strings.vault_edit_duplicate, Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        } else {
                                            Toast.makeText(context, strings.vault_fill_all, Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(strings.vault_save)
                                }
                            }
                        }
                    }

                    "generator" -> {
                        // Password generator
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = generatedPassword,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = strings.vault_entropy.format(String.format(java.util.Locale.ROOT, "%.0f", generatedEntropy)),
                                        fontSize = 10.sp,
                                        color = if (generatedEntropy >= 90) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(strings.vault_length.format(genLength.toInt()), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                IconButton(onClick = { refreshGeneratedPassword() }) {
                                    Icon(Icons.Default.Refresh, contentDescription = strings.vault_regenerate)
                                }
                            }

                            Slider(
                                value = genLength,
                                onValueChange = {
                                    genLength = it
                                    refreshGeneratedPassword()
                                },
                                valueRange = 10f..32f,
                                steps = 21
                            )

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = genSymbols, onCheckedChange = { genSymbols = it; refreshGeneratedPassword() })
                                Text(strings.vault_symbols, fontSize = 11.sp)
                                Spacer(modifier = Modifier.width(12.dp))
                                Checkbox(checked = genDigits, onCheckedChange = { genDigits = it; refreshGeneratedPassword() })
                                Text(strings.vault_digits, fontSize = 11.sp)
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Button(
                                onClick = {
                                    val chars = generatedPassword.toCharArray()
                                    SendaVaultManager.copyToClipboardSecurely(context, "Senda Password", chars)
                                    SendaVaultManager.wipe(chars)
                                    Toast.makeText(context, strings.vault_copied_30s, Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(strings.vault_copy)
                            }
                        }
                    }

                    "audit" -> {
                        // Audit and stress test view
                        if (isRunningAudit) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(240.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator()
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(strings.vault_audit_running.format(1000), fontSize = 12.sp)
                                    Text(strings.vault_audit_running_sub, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        } else {
                            val rep = auditReport
                            if (rep != null) {
                                LazyColumn(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    item {
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (rep.failedTests == 0) Color(0xFF1B5E20).copy(alpha = 0.15f) else MaterialTheme.colorScheme.errorContainer,
                                            border = BorderStroke(1.dp, if (rep.failedTests == 0) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(modifier = Modifier.padding(10.dp)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(
                                                        imageVector = if (rep.failedTests == 0) Icons.Default.CheckCircle else Icons.Default.Warning,
                                                        contentDescription = null,
                                                        tint = if (rep.failedTests == 0) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        text = rep.finalVerdict,
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 11.sp,
                                                        color = if (rep.failedTests == 0) Color(0xFF1B5E20) else MaterialTheme.colorScheme.error
                                                    )
                                                }
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    text = strings.vault_audit_summary.format(rep.passedTests, rep.totalTests, rep.totalStressCycles, String.format(java.util.Locale.ROOT, "%.3f", rep.avgLatencyMs), String.format(java.util.Locale.ROOT, "%.0f", rep.throughputOpsPerSec)),
                                                    fontSize = 10.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }

                                    items(rep.results) { item ->
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(modifier = Modifier.padding(10.dp)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(
                                                        imageVector = if (item.passed) Icons.Default.Check else Icons.Default.Close,
                                                        contentDescription = null,
                                                        tint = if (item.passed) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(text = item.name, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                                }
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(text = item.details, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                if (item.metrics != null) {
                                                    Spacer(modifier = Modifier.height(2.dp))
                                                    Text(text = item.metrics, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (activeSubView != "main") activeSubView = "main" else onDismiss()
                }
            ) {
                Text(if (activeSubView != "main") strings.back else strings.general_done)
            }
        }
    )
}

/**
 * Individual credential card with safe reveal and clipboard hygiene.
 */
@Composable
private fun CredentialItemCard(
    item: VaultCredential,
    onDelete: () -> Unit,
    runSecure: (() -> Unit) -> Unit,
    onEdit: () -> Unit
) {
    val context = LocalContext.current
    val strings = org.senda.browser.core.LocalSendaStrings.current
    var isRevealed by remember { mutableStateOf(false) }
    var revealedPassword by remember { mutableStateOf("") }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = item.domain, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(text = item.username, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Row {
                    // Reveal / Hide
                    IconButton(
                        onClick = {
                            if (!isRevealed) {
                                runSecure {
                                    val chars = SendaVaultManager.decryptPassword(item.encryptedPasswordBase64, item.ivBase64)
                                    revealedPassword = String(chars)
                                    SendaVaultManager.wipe(chars)
                                    isRevealed = true
                                }
                            } else {
                                isRevealed = false
                                revealedPassword = ""
                            }
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (isRevealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (isRevealed) strings.vault_hide else strings.vault_reveal,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    // Copy to the secure clipboard
                    IconButton(
                        onClick = {
                            runSecure {
                                val chars = SendaVaultManager.decryptPassword(item.encryptedPasswordBase64, item.ivBase64)
                                SendaVaultManager.copyToClipboardSecurely(context, item.domain, chars)
                                SendaVaultManager.wipe(chars)
                                Toast.makeText(context, strings.vault_copied_30s, Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = strings.vault_copy_cd, modifier = Modifier.size(16.dp))
                    }

                    // Edit
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = strings.vault_edit_cd, modifier = Modifier.size(16.dp))
                    }

                    // Delete
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = strings.vault_delete_cd, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                    }
                }
            }

            if (isRevealed) {
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = revealedPassword,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}
