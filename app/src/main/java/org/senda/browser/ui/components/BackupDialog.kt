package org.senda.browser.ui.components

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.senda.browser.core.LocalSendaStrings
import org.senda.browser.core.PreferencesManager
import org.senda.browser.core.backup.Age
import org.senda.browser.core.backup.BackupService
import org.senda.browser.core.backup.SendaBackup
import org.senda.browser.core.security.SendaVaultAuth
import org.senda.browser.core.security.VaultLockedException
import org.senda.browser.core.security.VaultUnavailableException

private const val MIN_PASSPHRASE = 12

/**
 * Encrypted backup: the user's data, encrypted on the phone with a passphrase only they know (open age
 * format), saved wherever they choose through the system picker (a Syncthing folder, Nextcloud or DAVx5,
 * a USB drive…). Senda has no servers and receives nothing. Also exports and imports bookmarks as HTML.
 */
@Composable
fun BackupDialog(prefs: PreferencesManager, onDismiss: () -> Unit) {
    val strings = LocalSendaStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dateFormat = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT) }
    val appVersion = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }

    var view by remember { mutableStateOf("main") } // main, create
    var busy by remember { mutableStateOf<String?>(null) }
    var lastBackup by remember { mutableLongStateOf(prefs.lastBackupTime) }

    // Create
    var sections by remember { mutableStateOf(setOf(SendaBackup.Section.BOOKMARKS, SendaBackup.Section.SETTINGS)) }
    var pass by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }

    // Restore
    var openFile by remember { mutableStateOf<Pair<ByteArray, String>?>(null) }
    var openPass by remember { mutableStateOf("") }
    var openError by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<SendaBackup.Contents?>(null) }
    var restoreSections by remember { mutableStateOf(emptySet<SendaBackup.Section>()) }
    var replacePasswords by remember { mutableStateOf(false) }

    fun displayName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()

    fun auth(then: (Boolean) -> Unit) = SendaVaultAuth.request(context, strings.bk_title, then)

    // Work in the background; if the vault key is locked (passwords), fingerprint or PIN and one retry
    fun <T> background(label: String, work: () -> T, onDone: (T) -> Unit, onError: (Throwable) -> Unit) {
        fun run(retry: Boolean) {
            busy = label
            scope.launch {
                val result = withContext(Dispatchers.Default) { runCatching(work) }
                busy = null
                result.onSuccess(onDone).onFailure { e ->
                    if (e is VaultLockedException && retry) auth { ok -> if (ok) run(false) else onError(e) } else onError(e)
                }
            }
        }
        run(true)
    }

    fun errorText(e: Throwable): String = when {
        e is Age.AgeException && e.kind == Age.AgeException.Kind.NO_MATCH -> strings.bk_wrong_pass
        e is Age.AgeException && e.kind == Age.AgeException.Kind.UNSUPPORTED -> strings.bk_too_heavy
        e is SendaBackup.InvalidBackupException && e.message?.startsWith("Made by a newer") == true -> strings.bk_newer
        e is Age.AgeException || e is SendaBackup.InvalidBackupException -> strings.bk_bad_file
        e is VaultUnavailableException -> strings.vault_err_no_lock
        else -> strings.bk_failed.format(e.message ?: e.javaClass.simpleName)
    }

    // ---- Create: the user picks where; then the backup is built, encrypted and written ----
    val createLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = displayName(uri)
        val chosen = sections
        val phrase = pass.toCharArray()
        fun discard() { runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) } }
        fun go() = background(
            strings.bk_working,
            work = {
                val contents = BackupService.collect(context, prefs, chosen)
                val bytes = BackupService.encrypt(contents, phrase, appVersion)
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: throw java.io.IOException("unwritable")
            },
            onDone = {
                java.util.Arrays.fill(phrase, '\u0000')
                pass = ""; pass2 = ""
                prefs.lastBackupTime = System.currentTimeMillis()
                lastBackup = prefs.lastBackupTime
                view = "main"
                Toast.makeText(context, strings.bk_created.format(name), Toast.LENGTH_LONG).show()
            },
            onError = { e ->
                java.util.Arrays.fill(phrase, '\u0000')
                discard()
                Toast.makeText(context, errorText(e), Toast.LENGTH_LONG).show()
            }
        )
        // Passwords leave the vault only after a fresh fingerprint or PIN
        if (SendaBackup.Section.PASSWORDS in chosen) auth { ok -> if (ok) go() else { java.util.Arrays.fill(phrase, '\u0000'); discard() } } else go()
    }

    // ---- Restore: open the file, ask its passphrase, show what it contains ----
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val read = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val buf = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(chunk)
                            if (n < 0) break
                            buf.write(chunk, 0, n)
                            if (buf.size() > BackupService.MAX_FILE_BYTES) throw java.io.IOException("too big")
                        }
                        buf.toByteArray()
                    } ?: throw java.io.IOException("unreadable")
                }
            }
            read.onSuccess { openPass = ""; openError = null; openFile = it to displayName(uri) }
                .onFailure { Toast.makeText(context, strings.bk_bad_file, Toast.LENGTH_LONG).show() }
        }
    }

    // ---- Bookmarks as HTML (standard format, unencrypted): a real file, not text in the share menu ----
    val htmlExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bookmarks = prefs.getBookmarks()
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(prefs.exportBookmarksToNetscapeHtml(bookmarks).toByteArray(Charsets.UTF_8)) }
                ?: throw java.io.IOException("unwritable")
        }.isSuccess
        Toast.makeText(context, if (ok) strings.bk_html_exported.format(bookmarks.size, displayName(uri)) else strings.bk_html_failed, Toast.LENGTH_LONG).show()
    }
    val htmlImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val count = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val text = input.readBytes().also { if (it.size > BackupService.MAX_FILE_BYTES) throw java.io.IOException("too big") }
                prefs.importBookmarksFromNetscapeHtml(String(text, Charsets.UTF_8))
            } ?: throw java.io.IOException("unreadable")
        }
        Toast.makeText(context, count.fold({ strings.bk_html_imported.format(it) }, { strings.bk_html_failed }), Toast.LENGTH_LONG).show()
    }

    AlertDialog(
        onDismissRequest = { if (busy == null) { if (view != "main") view = "main" else onDismiss() } },
        icon = { Icon(Icons.Default.EnhancedEncryption, contentDescription = null) },
        title = { Text(strings.bk_title, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                if (view == "main") {
                    Text(strings.bk_intro, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(if (lastBackup > 0) strings.bk_card_last.format(dateFormat.format(java.util.Date(lastBackup))) else strings.bk_card_never,
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = { view = "create" }, enabled = busy == null, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) {
                            Text(strings.bk_create, fontSize = 13.sp)
                        }
                        OutlinedButton(onClick = { openLauncher.launch(arrayOf("*/*")) }, enabled = busy == null, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) {
                            Text(strings.bk_restore, fontSize = 13.sp)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(strings.bk_open_hint, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), thickness = 0.5.dp)
                    Text(strings.bk_html_title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(strings.bk_html_desc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        OutlinedButton(onClick = {
                            val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date())
                            htmlExportLauncher.launch("senda-bookmarks-$date.html")
                        }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) { Text(strings.bk_html_export, fontSize = 12.sp) }
                        OutlinedButton(onClick = { htmlImportLauncher.launch(arrayOf("text/html", "*/*")) },
                            modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) { Text(strings.bk_html_import, fontSize = 12.sp) }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), thickness = 0.5.dp)
                    Text(strings.bk_sync_future, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    // What to include
                    listOf(
                        SendaBackup.Section.BOOKMARKS to strings.bk_sec_bookmarks,
                        SendaBackup.Section.SETTINGS to strings.bk_sec_settings,
                        SendaBackup.Section.HISTORY to strings.bk_sec_history,
                        SendaBackup.Section.PASSWORDS to strings.bk_sec_passwords
                    ).forEach { (section, label) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = section in sections, enabled = busy == null,
                                onCheckedChange = { sections = if (it) sections + section else sections - section })
                            Text(label, fontSize = 13.sp)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = pass, onValueChange = { pass = it }, label = { Text(strings.bk_pass_label) },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(), enabled = busy == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(value = pass2, onValueChange = { pass2 = it }, label = { Text(strings.bk_pass_confirm) },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(), enabled = busy == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    val problem = when {
                        pass.isNotEmpty() && pass.length < MIN_PASSPHRASE -> strings.bk_pass_short.format(MIN_PASSPHRASE)
                        pass2.isNotEmpty() && pass != pass2 -> strings.bk_pass_mismatch
                        else -> null
                    }
                    Text(problem ?: strings.bk_pass_hint.format(MIN_PASSPHRASE), fontSize = 11.sp,
                        color = if (problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                busy?.let {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(it, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            if (view == "create") {
                Button(
                    enabled = busy == null && sections.isNotEmpty() && pass.length >= MIN_PASSPHRASE && pass == pass2,
                    onClick = {
                        val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date())
                        createLauncher.launch("senda-backup-$date.senda")
                    }
                ) { Text(strings.bk_save) }
            } else {
                TextButton(onClick = onDismiss, enabled = busy == null) { Text(strings.general_done) }
            }
        },
        dismissButton = {
            if (view == "create") TextButton(onClick = { pass = ""; pass2 = ""; view = "main" }, enabled = busy == null) { Text(strings.general_cancel) }
        }
    )

    // Passphrase of the file being restored
    openFile?.let { (bytes, name) ->
        AlertDialog(
            onDismissRequest = { if (busy == null) { openFile = null; openPass = "" } },
            title = { Text(strings.bk_open_title) },
            text = {
                Column {
                    Text(strings.bk_open_body.format(name), fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = openPass, onValueChange = { openPass = it; openError = null }, label = { Text(strings.bk_pass_label) },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(), enabled = busy == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
                    openError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                    busy?.let {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(it, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                Button(enabled = busy == null && openPass.isNotEmpty(), onClick = {
                    val phrase = openPass.toCharArray()
                    background(strings.bk_decrypting, work = { BackupService.decrypt(bytes, phrase) }, onDone = { contents ->
                        java.util.Arrays.fill(phrase, '\u0000')
                        openFile = null; openPass = ""
                        restoreSections = contents.sections
                        replacePasswords = false
                        preview = contents
                    }, onError = { e ->
                        java.util.Arrays.fill(phrase, '\u0000')
                        openError = errorText(e)
                    })
                }) { Text(strings.bk_restore) }
            },
            dismissButton = { TextButton(enabled = busy == null, onClick = { openFile = null; openPass = "" }) { Text(strings.general_cancel) } }
        )
    }

    // What the backup contains, and what to restore
    preview?.let { c ->
        AlertDialog(
            onDismissRequest = { if (busy == null) preview = null },
            title = { Text(strings.bk_restore) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(strings.bk_preview.format(if (c.created > 0) dateFormat.format(java.util.Date(c.created)) else "?"), fontSize = 13.sp)
                    listOf(
                        SendaBackup.Section.BOOKMARKS to strings.bk_count_bookmarks.format(c.bookmarks.size, c.shortcuts.size),
                        SendaBackup.Section.SETTINGS to strings.bk_count_settings.format(c.settings.length()),
                        SendaBackup.Section.HISTORY to strings.bk_count_history.format(c.history.size),
                        SendaBackup.Section.PASSWORDS to strings.bk_count_passwords.format(c.passwords.size)
                    ).filter { it.first in c.sections }.forEach { (section, label) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = section in restoreSections, enabled = busy == null,
                                onCheckedChange = { restoreSections = if (it) restoreSections + section else restoreSections - section })
                            Text(label, fontSize = 13.sp)
                        }
                    }
                    if (SendaBackup.Section.PASSWORDS in restoreSections) Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = replacePasswords, onCheckedChange = { replacePasswords = it }, enabled = busy == null)
                        Text(strings.vault_import_replace, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(strings.bk_restore_note, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    busy?.let {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                Button(enabled = busy == null && restoreSections.isNotEmpty(), onClick = {
                    val chosen = restoreSections
                    val replace = replacePasswords
                    fun go() = background(strings.bk_working, work = { BackupService.restore(context, prefs, c, chosen, replace) }, onDone = { r ->
                        preview = null
                        Toast.makeText(context, strings.bk_restored.format(r.bookmarks, r.shortcuts, r.settings, r.history, r.passwords), Toast.LENGTH_LONG).show()
                    }, onError = { e -> Toast.makeText(context, errorText(e), Toast.LENGTH_LONG).show() })
                    if (SendaBackup.Section.PASSWORDS in chosen && c.passwords.isNotEmpty()) auth { ok -> if (ok) go() } else go()
                }) { Text(strings.bk_restore_confirm) }
            },
            dismissButton = { TextButton(enabled = busy == null, onClick = { preview = null }) { Text(strings.general_cancel) } }
        )
    }
}
