package org.senda.browser.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PromptDelegate.DateTimePrompt.Type as DtType
import org.senda.browser.core.LocalSendaStrings
import org.senda.browser.ui.model.SendaPrompt
import org.senda.browser.ui.theme.SendaColors

data class FlattenedChoice(
    val choice: GeckoSession.PromptDelegate.ChoicePrompt.Choice,
    val isGroupHeader: Boolean = false,
    val indentLevel: Int = 0
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendaPromptHost(
    prompt: SendaPrompt?,
    onDismiss: () -> Unit,
    tab: org.senda.browser.ui.model.BrowserTab? = null
) {
    if (prompt == null) return

    when (prompt) {
        is SendaPrompt.Choice -> {
            ChoicePromptSheet(
                choicePrompt = prompt,
                onDismiss = onDismiss
            )
        }
        is SendaPrompt.Alert -> {
            AlertPromptDialog(
                alertPrompt = prompt,
                onDismiss = onDismiss
            )
        }
        is SendaPrompt.Confirm -> {
            ConfirmPromptDialog(
                confirmPrompt = prompt,
                onDismiss = onDismiss
            )
        }
        is SendaPrompt.Text -> {
            TextPromptDialog(
                textPrompt = prompt,
                onDismiss = onDismiss
            )
        }
        is SendaPrompt.BeforeUnload -> {
            BeforeUnloadDialog(
                beforeUnloadPrompt = prompt,
                onDismiss = onDismiss
            )
        }
        is SendaPrompt.RepostConfirm -> {
            RepostConfirmDialog(
                repostPrompt = prompt,
                onDismiss = onDismiss
            )
        }
        is SendaPrompt.File -> {
            // El selector de archivos se gestiona mediante el launcher de sistema en BrowserScreen
        }
        is SendaPrompt.DateTime -> {
            DateTimePromptDialog(request = prompt, onDismiss = onDismiss)
        }
        is SendaPrompt.Permission -> {
            PermissionPromptDialog(
                request = prompt,
                isPrivate = tab?.isPrivate == true,
                onDismiss = onDismiss
            )
        }
        is SendaPrompt.OpenInApp -> {
            OpenInAppDialog(request = prompt, onDismiss = onDismiss)
        }
        is SendaPrompt.Auth -> AuthPromptDialog(request = prompt, onDismiss = onDismiss)
        is SendaPrompt.LoginSelect -> LoginSelectDialog(request = prompt, onDismiss = onDismiss)
        is SendaPrompt.LoginSave -> LoginSaveBar(request = prompt, onDismiss = onDismiss)
        is SendaPrompt.SlowScript -> SlowScriptDialog(request = prompt, onDismiss = onDismiss)
        is SendaPrompt.Download -> DownloadConfirmDialog(request = prompt, onDismiss = onDismiss)
        is SendaPrompt.ContextMenu -> {
            if (tab != null) {
                ContextMenuSheet(element = prompt.element, tab = tab, onDismiss = onDismiss)
            }
        }
    }
}

@Composable
private fun AuthPromptDialog(request: SendaPrompt.Auth, onDismiss: () -> Unit) {
    val strings = LocalSendaStrings.current
    val raw = request.prompt
    val options = raw.authOptions
    val onlyPassword = options.flags and GeckoSession.PromptDelegate.AuthPrompt.AuthOptions.Flags.ONLY_PASSWORD != 0
    val insecure = options.level == GeckoSession.PromptDelegate.AuthPrompt.AuthOptions.Level.NONE
    val host = remember(options.uri) {
        try { java.net.URI(options.uri).host } catch (_: Exception) { null } ?: options.uri ?: ""
    }
    var user by remember { mutableStateOf(options.username ?: "") }
    var password by remember { mutableStateOf("") }
    fun finish(confirm: Boolean) {
        if (!raw.isComplete) {
            request.result.complete(
                when {
                    !confirm -> raw.dismiss()
                    onlyPassword -> raw.confirm(password)
                    else -> raw.confirm(user, password)
                }
            )
        }
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = { finish(false) },
        icon = { Icon(Icons.Default.Lock, contentDescription = null) },
        title = { Text(strings.auth_title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(strings.auth_message.format(host), style = MaterialTheme.typography.bodyMedium)
                if (insecure) {
                    Text(strings.auth_insecure, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (!onlyPassword) {
                    OutlinedTextField(
                        value = user, onValueChange = { user = it }, singleLine = true,
                        label = { Text(strings.auth_user) }, modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = password, onValueChange = { password = it }, singleLine = true,
                    label = { Text(strings.auth_password) }, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Password
                    )
                )
            }
        },
        confirmButton = { Button(onClick = { finish(true) }) { Text(strings.auth_login) } },
        dismissButton = { TextButton(onClick = { finish(false) }) { Text(strings.general_cancel) } }
    )
}

/**
 * Barra inferior (no un diálogo): un AlertDialog abre otra ventana y le quita el foco a la página, y GeckoView
 * cancela entonces el selector, así que al elegir la cuenta ya no había nada que rellenar.
 */
@Composable
private fun LoginSelectDialog(request: SendaPrompt.LoginSelect, onDismiss: () -> Unit) {
    val strings = LocalSendaStrings.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val tag = org.senda.browser.core.security.SendaVaultLoginStorage.TAG
    val raw = request.request
    // Solo cuentas de la Bóveda: las contraseñas generadas por Gecko u otras opciones no se ofrecen
    val options = remember(raw) { raw.options.filter { !it.value.guid.isNullOrBlank() } }
    fun finish(response: GeckoSession.PromptDelegate.PromptResponse) {
        if (!raw.isComplete) request.result.complete(response)
        onDismiss()
    }

    // La contraseña se descifra solo aquí, después de que el usuario elige; si pasaron más de 30 s desde la
    // última identificación, el chip rechaza la clave y se pide huella o PIN una vez
    fun fill(option: org.mozilla.geckoview.Autocomplete.LoginSelectOption) {
        fun fail() {
            android.widget.Toast.makeText(context, strings.vault_login_fill_error, android.widget.Toast.LENGTH_LONG).show()
            finish(raw.dismiss())
        }
        fun confirmOrDefer(filled: org.mozilla.geckoview.Autocomplete.LoginSelectOption) {
            if (!raw.isComplete) {
                android.util.Log.i(tag, "selector: cuenta confirmada")
                request.result.complete(raw.confirm(filled))
                onDismiss()
            } else {
                // GeckoView ya canceló el selector (p. ej. mientras se pedía la huella): queda pendiente
                android.util.Log.i(tag, "selector: ya cancelado, relleno pendiente hasta tocar el campo")
                org.senda.browser.core.security.SendaVaultLoginStorage.setPending(option.value.guid ?: "")
                android.widget.Toast.makeText(context, strings.vault_login_tap_again, android.widget.Toast.LENGTH_LONG).show()
                onDismiss()
            }
        }
        try {
            val filled = org.senda.browser.core.security.SendaVaultLoginStorage.filledOption(context, option)
            if (filled != null) confirmOrDefer(filled) else fail()
        } catch (_: org.senda.browser.core.security.VaultLockedException) {
            android.util.Log.i(tag, "selector: Bóveda bloqueada, se pide huella o PIN")
            org.senda.browser.core.security.SendaVaultAuth.request(context, strings.vault_login_pick_title) { ok ->
                if (!ok) {
                    finish(raw.dismiss())
                    return@request
                }
                try {
                    val filled = org.senda.browser.core.security.SendaVaultLoginStorage.filledOption(context, option)
                    if (filled != null) confirmOrDefer(filled) else fail()
                } catch (e: Exception) {
                    android.util.Log.w(tag, "selector: error tras identificarse: ${e.javaClass.simpleName}")
                    fail()
                }
            }
        } catch (e: Exception) {
            android.util.Log.w(tag, "selector: error al descifrar: ${e.javaClass.simpleName}")
            fail()
        }
    }

    if (options.isEmpty()) {
        LaunchedEffect(raw) { finish(raw.dismiss()) }
        return
    }
    androidx.compose.ui.window.Popup(
        alignment = Alignment.BottomCenter,
        onDismissRequest = {},
        properties = androidx.compose.ui.window.PopupProperties(focusable = false, dismissOnClickOutside = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(8.dp),
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
            shadowElevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        strings.vault_login_pick_title, style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { finish(raw.dismiss()) }) { Text(strings.general_cancel) }
                }
                Text(strings.vault_login_pick_hint, style = MaterialTheme.typography.bodySmall)
                options.forEach { option ->
                    val host = remember(option) {
                        try { java.net.URI(option.value.origin).host } catch (_: Exception) { null } ?: option.value.origin
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { fill(option) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.AccountCircle, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                option.value.username ?: "",
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                host, style = MaterialTheme.typography.bodySmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Barra inferior para guardar en la Bóveda la cuenta con la que se acaba de iniciar sesión. */
@Composable
private fun LoginSaveBar(request: SendaPrompt.LoginSave, onDismiss: () -> Unit) {
    val strings = LocalSendaStrings.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val tag = org.senda.browser.core.security.SendaVaultLoginStorage.TAG
    val raw = request.request
    val entry = raw.options.firstOrNull()?.value
    fun finish() {
        // Senda guarda en su propia Bóveda; a GeckoView solo se le cierra el aviso
        if (!raw.isComplete) request.result.complete(raw.dismiss())
        onDismiss()
    }
    if (entry == null) {
        LaunchedEffect(raw) { finish() }
        return
    }
    val host = remember(entry) { try { java.net.URI(entry.origin).host } catch (_: Exception) { null } ?: entry.origin }
    // Algunos sitios piden el usuario y la contraseña en pantallas distintas: GeckoView solo ve la contraseña
    // y la cuenta quedaba sin usuario. Se puede escribir o corregir aquí antes de guardar
    var username by remember(entry) { mutableStateOf(entry.username ?: "") }

    fun save() {
        fun store() {
            val chars = entry.password.toCharArray()
            try {
                org.senda.browser.core.security.SendaVaultManager.saveCredential(context, entry.origin, username.trim(), chars)
            } finally {
                org.senda.browser.core.security.SendaVaultManager.wipe(chars)
            }
            android.util.Log.i(tag, "guardar: cuenta guardada en la Bóveda")
            android.widget.Toast.makeText(context, strings.vault_saved, android.widget.Toast.LENGTH_SHORT).show()
            finish()
        }
        try {
            store()
        } catch (_: org.senda.browser.core.security.VaultLockedException) {
            org.senda.browser.core.security.SendaVaultAuth.request(context, strings.vault_save_login_title) { ok ->
                if (!ok) return@request
                try {
                    store()
                } catch (e: Exception) {
                    android.util.Log.w(tag, "guardar: error tras identificarse: ${e.javaClass.simpleName}")
                    android.widget.Toast.makeText(context, strings.vault_err_generic.format(e.message ?: ""), android.widget.Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            android.util.Log.w(tag, "guardar: error: ${e.javaClass.simpleName}")
            android.widget.Toast.makeText(context, strings.vault_err_generic.format(e.message ?: ""), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    androidx.compose.ui.window.Popup(
        alignment = Alignment.BottomCenter,
        onDismissRequest = {},
        properties = androidx.compose.ui.window.PopupProperties(focusable = true, dismissOnClickOutside = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(8.dp),
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
            shadowElevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(strings.vault_save_login_title, style = MaterialTheme.typography.titleSmall)
                }
                Text(host, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(strings.vault_user) },
                    singleLine = true,
                    isError = username.isBlank(),
                    supportingText = if (username.isBlank()) {
                        { Text(strings.vault_save_login_user_missing) }
                    } else null,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(strings.vault_save_login_hint, style = MaterialTheme.typography.bodySmall)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { finish() }) { Text(strings.vault_save_login_not_now) }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { save() }) { Text(strings.vault_save) }
                }
            }
        }
    }
}

@Composable
private fun SlowScriptDialog(request: SendaPrompt.SlowScript, onDismiss: () -> Unit) {
    val strings = LocalSendaStrings.current
    fun decide(stop: Boolean) {
        request.onDecision(stop)
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = { decide(false) },
        icon = { Icon(Icons.Default.HourglassTop, contentDescription = null) },
        title = { Text(strings.slow_script_title, style = MaterialTheme.typography.titleMedium) },
        text = { Text(strings.slow_script_message.format(request.host), style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = { decide(true) }) { Text(strings.slow_script_stop) } },
        dismissButton = { TextButton(onClick = { decide(false) }) { Text(strings.slow_script_wait) } }
    )
}

@Composable
private fun DownloadConfirmDialog(request: SendaPrompt.Download, onDismiss: () -> Unit) {
    val strings = LocalSendaStrings.current
    val context = androidx.compose.ui.platform.LocalContext.current
    fun decide(accept: Boolean) {
        request.onDecision(accept)
        onDismiss()
    }
    val size = if (request.sizeBytes > 0) " (${android.text.format.Formatter.formatShortFileSize(context, request.sizeBytes)})" else ""
    AlertDialog(
        onDismissRequest = { decide(false) },
        icon = { Icon(Icons.Default.Download, contentDescription = null) },
        title = { Text(strings.dl_confirm_title, style = MaterialTheme.typography.titleMedium) },
        text = { Text(strings.dl_confirm_message.format(request.host, request.fileName + size), style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { Button(onClick = { decide(true) }) { Text(strings.dl_confirm_yes) } },
        dismissButton = { TextButton(onClick = { decide(false) }) { Text(strings.general_cancel) } }
    )
}

@Composable
private fun OpenInAppDialog(request: SendaPrompt.OpenInApp, onDismiss: () -> Unit) {
    val strings = LocalSendaStrings.current
    fun decide(open: Boolean) {
        request.onDecision(open)
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = { decide(false) },
        icon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
        title = {
            Text(
                text = request.appName?.let { strings.open_in_app_title.format(it) } ?: strings.open_in_app_title_generic,
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = { Text(strings.open_in_app_body, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = { decide(true) }) { Text(strings.open_in_app_open) } },
        dismissButton = { TextButton(onClick = { decide(false) }) { Text(strings.open_in_app_stay) } }
    )
}

@Composable
private fun PermissionPromptDialog(
    request: SendaPrompt.Permission,
    isPrivate: Boolean,
    onDismiss: () -> Unit
) {
    val strings = LocalSendaStrings.current
    fun decide(granted: Boolean) {
        request.onDecision(granted)
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = { decide(false) },
        icon = {
            Icon(
                imageVector = when (request.kinds.first()) {
                    org.senda.browser.ui.model.PermissionKind.LOCATION -> Icons.Default.LocationOn
                    org.senda.browser.ui.model.PermissionKind.CAMERA -> Icons.Default.Videocam
                    org.senda.browser.ui.model.PermissionKind.MICROPHONE -> Icons.Default.Mic
                    org.senda.browser.ui.model.PermissionKind.NOTIFICATIONS -> Icons.Default.Notifications
                },
                contentDescription = null
            )
        },
        title = { Text(strings.perm_request_title.format(request.host), style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                request.kinds.forEach { kind ->
                    Text(
                        text = "• " + when (kind) {
                            org.senda.browser.ui.model.PermissionKind.LOCATION -> strings.perm_location
                            org.senda.browser.ui.model.PermissionKind.CAMERA -> strings.perm_camera
                            org.senda.browser.ui.model.PermissionKind.MICROPHONE -> strings.perm_microphone
                            org.senda.browser.ui.model.PermissionKind.NOTIFICATIONS -> strings.perm_notifications
                        },
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                if (isPrivate) {
                    Text(
                        text = strings.perm_private_note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = { Button(onClick = { decide(true) }) { Text(strings.perm_allow) } },
        dismissButton = { TextButton(onClick = { decide(false) }) { Text(strings.perm_block) } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContextMenuSheet(
    element: GeckoSession.ContentDelegate.ContextElement,
    tab: org.senda.browser.ui.model.BrowserTab,
    onDismiss: () -> Unit
) {
    val strings = LocalSendaStrings.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val link = element.linkUri?.takeIf { it.isNotBlank() }
    val media = element.srcUri?.takeIf {
        it.isNotBlank() && element.type != GeckoSession.ContentDelegate.ContextElement.TYPE_NONE
    }
    val isImage = element.type == GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE
    val prefs = tab.prefs

    fun copy(text: String) {
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("URL", text))
        // Android 13+ ya muestra su propio aviso al copiar
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            android.widget.Toast.makeText(context, strings.general_copied, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    fun share(text: String) {
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, text)
        }
        context.startActivity(android.content.Intent.createChooser(send, strings.ctx_share_link))
    }
    fun download(url: String) {
        if (prefs != null) {
            org.senda.browser.core.SendaDownloadManager.downloadUrl(context, prefs, url, tab.isPrivate, referrer = tab.url)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            val address = link ?: media ?: ""
            val heading = element.title?.takeIf { it.isNotBlank() }
                ?: element.linkText?.trim()?.takeIf { it.isNotBlank() }
                ?: element.altText?.takeIf { it.isNotBlank() }
            if (heading != null && heading != address) {
                Text(
                    text = heading,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                )
            }
            Text(
                text = address,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp)
            )
            HorizontalDivider()

            @Composable
            fun item(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, action: () -> Unit) {
                ListItem(
                    headlineContent = { Text(label) },
                    leadingContent = { Icon(icon, contentDescription = null) },
                    modifier = Modifier.clickable {
                        onDismiss()
                        action()
                    }
                )
            }

            if (link != null) {
                item(strings.ctx_open_new_tab, Icons.Default.Add) { tab.openInNewTab(link) }
                if (!tab.isPrivate) {
                    item(strings.ctx_open_private_tab, Icons.Default.VpnKey) { tab.openInNewTab(link, private = true) }
                }
                item(strings.ctx_copy_link, Icons.Default.ContentCopy) { copy(link) }
                item(strings.ctx_share_link, Icons.Default.Share) { share(link) }
                item(strings.ctx_download_link, Icons.Default.Download) { download(link) }
            }
            if (media != null) {
                if (link != null) HorizontalDivider()
                if (isImage) {
                    item(strings.ctx_open_image, Icons.Default.Image) { tab.openInNewTab(media) }
                    item(strings.ctx_download_image, Icons.Default.Download) { download(media) }
                    item(strings.ctx_copy_image_link, Icons.Default.ContentCopy) { copy(media) }
                } else if (!media.startsWith("blob:")) {
                    // Los videos por streaming (blob:/MSE) no son un archivo descargable
                    item(strings.ctx_download_media, Icons.Default.Download) { download(media) }
                    item(strings.ctx_copy_link, Icons.Default.ContentCopy) { copy(media) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoicePromptSheet(
    choicePrompt: SendaPrompt.Choice,
    onDismiss: () -> Unit
) {
    val rawPrompt = choicePrompt.prompt
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Aplanar las opciones respetando posibles optgroups anidados
    val flattenedList = remember(rawPrompt.choices) {
        val list = mutableListOf<FlattenedChoice>()
        fun processChoice(c: GeckoSession.PromptDelegate.ChoicePrompt.Choice, depth: Int) {
            val subItems = c.items
            if (subItems != null && subItems.isNotEmpty()) {
                list.add(FlattenedChoice(c, isGroupHeader = true, indentLevel = depth))
                subItems.forEach { sub -> processChoice(sub, depth + 1) }
            } else {
                list.add(FlattenedChoice(c, isGroupHeader = false, indentLevel = depth))
            }
        }
        rawPrompt.choices.forEach { processChoice(it, 0) }
        list
    }

    val isMultiple = rawPrompt.type == GeckoSession.PromptDelegate.ChoicePrompt.Type.MULTIPLE
    val selectedIds = remember {
        mutableStateListOf<String>().apply {
            flattenedList.filter { !it.isGroupHeader && it.choice.selected }.forEach { add(it.choice.id) }
        }
    }

    val strings = LocalSendaStrings.current
    var searchQuery by remember { mutableStateOf("") }
    val filteredChoices = remember(flattenedList, searchQuery) {
        if (searchQuery.isBlank()) flattenedList
        else flattenedList.filter {
            it.isGroupHeader || it.choice.label.contains(searchQuery, ignoreCase = true)
        }
    }

    fun safeComplete(block: () -> Unit) {
        if (!rawPrompt.isComplete) {
            try {
                block()
            } catch (_: Exception) {}
        }
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = {
            safeComplete {
                choicePrompt.result.complete(rawPrompt.dismiss())
            }
        },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            // Cabecera del formulario / desplegable
            val displayTitle = rawPrompt.title?.ifBlank { null }
                ?: rawPrompt.message?.ifBlank { null }
                ?: strings.prompt_select_option

            Text(
                text = displayTitle,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 6.dp)
            )

            if (!rawPrompt.message.isNullOrBlank() && rawPrompt.message != displayTitle) {
                Text(
                    text = rawPrompt.message ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            // Barra de búsqueda rápida si hay muchas opciones
            if (flattenedList.size > 7) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(strings.prompt_search_list, fontSize = 14.sp) },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = strings.prompt_clear, modifier = Modifier.size(18.dp))
                            }
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Lista de opciones seleccionables
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(max = 420.dp)
            ) {
                items(filteredChoices) { item ->
                    if (item.isGroupHeader) {
                        Text(
                            text = item.choice.label.uppercase(),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            ),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp, horizontal = (item.indentLevel * 12).dp)
                        )
                    } else if (item.choice.separator) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    } else {
                        val isSelected = if (isMultiple) {
                            selectedIds.contains(item.choice.id)
                        } else {
                            item.choice.selected || selectedIds.contains(item.choice.id)
                        }
                        val isDisabled = item.choice.disabled

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                   else MaterialTheme.colorScheme.surface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .alpha(if (isDisabled) 0.38f else 1.0f)
                                .clickable(enabled = !isDisabled) {
                                    if (isMultiple) {
                                        if (selectedIds.contains(item.choice.id)) {
                                            selectedIds.remove(item.choice.id)
                                        } else {
                                            selectedIds.add(item.choice.id)
                                        }
                                    } else {
                                        safeComplete {
                                            choicePrompt.result.complete(rawPrompt.confirm(item.choice))
                                        }
                                    }
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp + (item.indentLevel * 12).dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isMultiple) {
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = null,
                                        modifier = Modifier.padding(end = 12.dp)
                                    )
                                } else {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = null,
                                        modifier = Modifier.padding(end = 12.dp)
                                    )
                                }

                                Text(
                                    text = item.choice.label,
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                    ),
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Botones inferiores
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = {
                        safeComplete {
                            choicePrompt.result.complete(rawPrompt.dismiss())
                        }
                    }
                ) {
                    Text(strings.general_cancel)
                }

                if (isMultiple) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            safeComplete {
                                val selectedChoices = flattenedList
                                    .filter { !it.isGroupHeader && selectedIds.contains(it.choice.id) }
                                    .map { it.choice }
                                    .toTypedArray()
                                choicePrompt.result.complete(rawPrompt.confirm(selectedChoices))
                            }
                        }
                    ) {
                        Text("${strings.general_ok} (${selectedIds.size})")
                    }
                }
            }
        }
    }
}

@Composable
private fun AlertPromptDialog(
    alertPrompt: SendaPrompt.Alert,
    onDismiss: () -> Unit
) {
    val strings = LocalSendaStrings.current
    val rawPrompt = alertPrompt.prompt
    AlertDialog(
        onDismissRequest = {
            if (!rawPrompt.isComplete) {
                alertPrompt.result.complete(rawPrompt.dismiss())
            }
            onDismiss()
        },
        title = {
            Text(rawPrompt.title?.ifBlank { null } ?: strings.prompt_page_msg)
        },
        text = {
            Text(rawPrompt.message ?: "")
        },
        confirmButton = {
            Button(
                onClick = {
                    if (!rawPrompt.isComplete) {
                        alertPrompt.result.complete(rawPrompt.dismiss())
                    }
                    onDismiss()
                }
            ) {
                Text(strings.general_ok)
            }
        },
        containerColor = MaterialTheme.colorScheme.surface
    )
}

@Composable
private fun ConfirmPromptDialog(
    confirmPrompt: SendaPrompt.Confirm,
    onDismiss: () -> Unit
) {
    val strings = LocalSendaStrings.current
    val rawPrompt = confirmPrompt.prompt
    AlertDialog(
        onDismissRequest = {
            if (!rawPrompt.isComplete) {
                confirmPrompt.result.complete(rawPrompt.confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.NEGATIVE))
            }
            onDismiss()
        },
        title = {
            Text(rawPrompt.title?.ifBlank { null } ?: strings.prompt_confirmation)
        },
        text = {
            Text(rawPrompt.message ?: "")
        },
        confirmButton = {
            Button(
                onClick = {
                    if (!rawPrompt.isComplete) {
                        confirmPrompt.result.complete(rawPrompt.confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.POSITIVE))
                    }
                    onDismiss()
                }
            ) {
                Text(strings.general_ok)
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    if (!rawPrompt.isComplete) {
                        confirmPrompt.result.complete(rawPrompt.confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.NEGATIVE))
                    }
                    onDismiss()
                }
            ) {
                Text(strings.general_cancel)
            }
        },
        containerColor = MaterialTheme.colorScheme.surface
    )
}

@Composable
private fun TextPromptDialog(
    textPrompt: SendaPrompt.Text,
    onDismiss: () -> Unit
) {
    val strings = LocalSendaStrings.current
    val rawPrompt = textPrompt.prompt
    var input by remember { mutableStateOf(rawPrompt.defaultValue ?: "") }

    AlertDialog(
        onDismissRequest = {
            if (!rawPrompt.isComplete) {
                textPrompt.result.complete(rawPrompt.dismiss())
            }
            onDismiss()
        },
        title = {
            Text(rawPrompt.title?.ifBlank { null } ?: strings.prompt_text_input)
        },
        text = {
            Column {
                if (!rawPrompt.message.isNullOrBlank()) {
                    Text(rawPrompt.message ?: "", modifier = Modifier.padding(bottom = 12.dp))
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (!rawPrompt.isComplete) {
                        textPrompt.result.complete(rawPrompt.confirm(input))
                    }
                    onDismiss()
                }
            ) {
                Text(strings.general_ok)
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    if (!rawPrompt.isComplete) {
                        textPrompt.result.complete(rawPrompt.dismiss())
                    }
                    onDismiss()
                }
            ) {
                Text(strings.general_cancel)
            }
        },
        containerColor = MaterialTheme.colorScheme.surface
    )
}

@Composable
private fun BeforeUnloadDialog(
    beforeUnloadPrompt: SendaPrompt.BeforeUnload,
    onDismiss: () -> Unit
) {
    val strings = LocalSendaStrings.current
    val rawPrompt = beforeUnloadPrompt.prompt
    AlertDialog(
        onDismissRequest = {
            if (!rawPrompt.isComplete) {
                beforeUnloadPrompt.result.complete(rawPrompt.confirm(AllowOrDeny.DENY))
            }
            onDismiss()
        },
        title = { Text(strings.prompt_leave_site_title) },
        text = { Text(strings.prompt_leave_site_desc) },
        confirmButton = {
            Button(
                colors = ButtonDefaults.buttonColors(containerColor = SendaColors.PanicFireRed),
                onClick = {
                    if (!rawPrompt.isComplete) {
                        beforeUnloadPrompt.result.complete(rawPrompt.confirm(AllowOrDeny.ALLOW))
                    }
                    onDismiss()
                }
            ) {
                Text(strings.prompt_btn_leave)
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    if (!rawPrompt.isComplete) {
                        beforeUnloadPrompt.result.complete(rawPrompt.confirm(AllowOrDeny.DENY))
                    }
                    onDismiss()
                }
            ) {
                Text(strings.prompt_btn_stay)
            }
        },
        containerColor = MaterialTheme.colorScheme.surface
    )
}

@Composable
private fun RepostConfirmDialog(
    repostPrompt: SendaPrompt.RepostConfirm,
    onDismiss: () -> Unit
) {
    val strings = LocalSendaStrings.current
    val rawPrompt = repostPrompt.prompt
    AlertDialog(
        onDismissRequest = {
            if (!rawPrompt.isComplete) {
                repostPrompt.result.complete(rawPrompt.confirm(AllowOrDeny.DENY))
            }
            onDismiss()
        },
        title = { Text(strings.prompt_repost_title) },
        text = { Text(strings.prompt_repost_desc) },
        confirmButton = {
            Button(
                onClick = {
                    if (!rawPrompt.isComplete) {
                        repostPrompt.result.complete(rawPrompt.confirm(AllowOrDeny.ALLOW))
                    }
                    onDismiss()
                }
            ) {
                Text(strings.prompt_btn_resend)
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    if (!rawPrompt.isComplete) {
                        repostPrompt.result.complete(rawPrompt.confirm(AllowOrDeny.DENY))
                    }
                    onDismiss()
                }
            ) {
                Text(strings.general_cancel)
            }
        },
        containerColor = MaterialTheme.colorScheme.surface
    )
}


/**
 * Selector nativo para campos de fecha y hora. Gecko entrega y espera los valores en formato HTML
 * (yyyy-MM-dd, HH:mm, yyyy-MM-ddTHH:mm, yyyy-MM). Las semanas (type=week) se dejan sin selector.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimePromptDialog(request: SendaPrompt.DateTime, onDismiss: () -> Unit) {
    val strings = LocalSendaStrings.current
    val raw = request.prompt
    val type = raw.type
    val utc = java.util.TimeZone.getTimeZone("UTC")

    fun finish(value: String?) {
        if (!raw.isComplete) {
            request.result.complete(if (value == null) raw.dismiss() else raw.confirm(value))
        }
        onDismiss()
    }

    if (type == DtType.WEEK) {
        LaunchedEffect(Unit) { finish(null) }
        return
    }

    val default = raw.defaultValue.orEmpty()
    fun parseDateMillis(text: String): Long? = runCatching {
        val fmt = java.text.SimpleDateFormat(if (type == DtType.MONTH) "yyyy-MM" else "yyyy-MM-dd", java.util.Locale.US).apply { timeZone = utc }
        fmt.parse(text.take(if (type == DtType.MONTH) 7 else 10))?.time
    }.getOrNull()
    fun formatDate(millis: Long, pattern: String) =
        java.text.SimpleDateFormat(pattern, java.util.Locale.US).apply { timeZone = utc }.format(java.util.Date(millis))

    val needsDate = type == DtType.DATE || type == DtType.MONTH || type == DtType.DATETIME_LOCAL
    val needsTime = type == DtType.TIME || type == DtType.DATETIME_LOCAL
    var pickedDate by remember { mutableStateOf<String?>(null) }

    if (needsDate && pickedDate == null) {
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = parseDateMillis(default) ?: System.currentTimeMillis()
        )
        DatePickerDialog(
            onDismissRequest = { finish(null) },
            confirmButton = {
                TextButton(onClick = {
                    val millis = dateState.selectedDateMillis ?: return@TextButton
                    when (type) {
                        DtType.MONTH -> finish(formatDate(millis, "yyyy-MM"))
                        DtType.DATE -> finish(formatDate(millis, "yyyy-MM-dd"))
                        else -> pickedDate = formatDate(millis, "yyyy-MM-dd")
                    }
                }) { Text(strings.general_ok) }
            },
            dismissButton = { TextButton(onClick = { finish(null) }) { Text(strings.general_cancel) } }
        ) {
            DatePicker(state = dateState)
        }
        return
    }

    if (needsTime) {
        val timeText = if (type == DtType.TIME) default else default.substringAfter('T', "")
        val hour = timeText.take(2).toIntOrNull() ?: java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val minute = timeText.drop(3).take(2).toIntOrNull() ?: 0
        val timeState = rememberTimePickerState(initialHour = hour, initialMinute = minute)
        AlertDialog(
            onDismissRequest = { finish(null) },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(onClick = {
                    val time = String.format(java.util.Locale.US, "%02d:%02d", timeState.hour, timeState.minute)
                    finish(if (type == DtType.TIME) time else "${pickedDate}T$time")
                }) { Text(strings.general_ok) }
            },
            dismissButton = { TextButton(onClick = { finish(null) }) { Text(strings.general_cancel) } }
        )
    }
}
