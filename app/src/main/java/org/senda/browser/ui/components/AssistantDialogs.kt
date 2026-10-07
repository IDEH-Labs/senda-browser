package org.senda.browser.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.senda.browser.core.LocalSendaStrings
import org.senda.browser.core.PreferencesManager
import org.senda.browser.core.SendaStringPack
import org.senda.browser.core.assistant.Attachment
import org.senda.browser.core.assistant.ChatGptPlanClient
import org.senda.browser.core.assistant.ChatTurn
import org.senda.browser.core.assistant.Source
import org.senda.browser.core.assistant.RemoteAiException
import org.senda.browser.core.assistant.SendaAssistant
import org.senda.browser.ui.model.BrowserTab

/** User-facing text for a provider failure (without technical details that do not help). */
fun assistantErrorText(e: Throwable, strings: SendaStringPack, destination: String): String = when (e) {
    is RemoteAiException -> when (e.kind) {
        RemoteAiException.Kind.NOT_CONFIGURED -> strings.as_err_incomplete
        RemoteAiException.Kind.AUTH -> strings.as_err_auth
        RemoteAiException.Kind.RATE_LIMIT -> strings.as_err_rate
        RemoteAiException.Kind.NETWORK -> strings.as_err_network.format(destination)
        // HTTP code and the beginning of OpenAI's message
        RemoteAiException.Kind.BAD_RESPONSE -> strings.as_err_bad.format(e.message?.take(80)?.ifBlank { null } ?: "?")
        RemoteAiException.Kind.USAGE_LIMIT -> strings.as_chatgpt_limit
        RemoteAiException.Kind.NOT_ELIGIBLE -> strings.as_chatgpt_not_eligible
    }
    else -> strings.as_err_network.format(destination)
}

/** Opens an address in the default browser (usually Senda itself). */
fun openInBrowser(context: Context, url: String) {
    try {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: android.content.ActivityNotFoundException) {}
}

/**
 * OpenAI sign-in in another browser on the phone (RFC 8252: external browser). In Senda, strict
 * tracker blocking and uBlock cut requests OpenAI's page needs and the button does not respond
 * (tested on 2026-10-05); Senda's protections are not lowered for every site because of this. The redirect still
 * reaches Senda through 127.0.0.1. With no other browser installed, it opens in Senda.
 */
fun openSignInBrowser(context: Context, url: String) {
    val uri = android.net.Uri.parse(url)
    val view = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
    val others = context.packageManager.queryIntentActivities(view, android.content.pm.PackageManager.MATCH_ALL)
        .filter { it.activityInfo.packageName != context.packageName }
    if (others.isEmpty()) { openInBrowser(context, url); return }
    val own = context.packageManager.queryIntentActivities(view, android.content.pm.PackageManager.MATCH_ALL)
        .filter { it.activityInfo.packageName == context.packageName }
        .map { android.content.ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
    val chooser = android.content.Intent.createChooser(view, null)
        .putExtra(android.content.Intent.EXTRA_EXCLUDE_COMPONENTS, own.toTypedArray())
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    try { context.startActivity(chooser) } catch (_: android.content.ActivityNotFoundException) { openInBrowser(context, url) }
}

/** OpenAI's mandatory notice when using the ChatGPT plan for the first time, with its logo. */
@Composable
fun ChatGptWelcomeDialog(onDismiss: () -> Unit) {
    val strings = LocalSendaStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { ChatGptLogo(28) },
        title = { Text(strings.as_chatgpt_welcome_title) },
        text = { Text(strings.as_chatgpt_welcome_body) },
        confirmButton = { Button(onClick = onDismiss) { Text(strings.as_chatgpt_got_it) } }
    )
}

/** Plan usage limit notice, with the "Manage usage" action required by OpenAI's guide. */
/**
 * Usage limit of the AI in use reached (ChatGPT plan or the key's quota, e.g. Gemini's free tier).
 * Says what happened and offers what is useful: continue with another connected AI or see usage on that company's official page.
 */
@Composable
fun UsageLimitDialog(prefs: PreferencesManager, onSwitched: () -> Unit, onDismiss: () -> Unit) {
    val strings = LocalSendaStrings.current
    val context = LocalContext.current
    val current = prefs.assistantProvider ?: SendaAssistant.PROVIDER_ID
    val name = SendaAssistant.displayName(current)
    // Another AI already connected and accepted to switch to with one tap (ChatGPT first)
    val alternative = SendaAssistant.connected(prefs).firstOrNull { it != current && prefs.assistantConsentFor(it) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { if (current == SendaAssistant.PROVIDER_ID) ChatGptLogo(28) },
        title = { Text(strings.as_limit_title.format(name)) },
        text = { Text(if (current == SendaAssistant.PROVIDER_ID) strings.as_chatgpt_limit else strings.as_limit_body.format(name)) },
        confirmButton = {
            if (alternative != null) {
                Button(onClick = { SendaAssistant.use(prefs, alternative); onSwitched(); onDismiss() }) {
                    Text(strings.as_use_other.format(SendaAssistant.displayName(alternative)))
                }
            } else {
                Button(onClick = { openInBrowser(context, SendaAssistant.usagePageOf(current)); onDismiss() }) { Text(strings.as_see_usage) }
            }
        },
        dismissButton = {
            Row {
                if (alternative != null) {
                    TextButton(onClick = { openInBrowser(context, SendaAssistant.usagePageOf(current)); onDismiss() }) { Text(strings.as_see_usage) }
                }
                TextButton(onClick = onDismiss) { Text(strings.general_close) }
            }
        }
    )
}

@Composable
internal fun ChatGptLogo(sizeDp: Int) {
    Icon(
        painter = androidx.compose.ui.res.painterResource(org.senda.browser.R.drawable.ic_chatgpt_logo),
        contentDescription = "ChatGPT",
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(sizeDp.dp)
    )
}

/** Assistant settings: provider, encrypted key, connection test, model and privacy notice. */
private data class AssistantMessage(
    val role: ChatTurn.Role,
    val shown: String,
    val sent: String,
    val attachments: List<Attachment> = emptyList(),
    val sources: List<Source> = emptyList()
)

/** Photos and PDF: what ChatGPT accepts with the plan (measured on 2026-10-06). */
private const val ATTACH_MAX_MB = 20
private const val IMAGE_MAX_SIDE = 1600

/**
 * Prepares a file chosen by the user: photos are reduced to 1600 px JPEG (ChatGPT needs no more and the
 * request is much lighter); PDFs go as they are up to 20 MB. Other types are not accepted.
 */
private fun readAttachment(context: Context, uri: android.net.Uri): Result<Attachment> = runCatching {
    val resolver = context.contentResolver
    val mime = resolver.getType(uri).orEmpty()
    val name = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: uri.lastPathSegment ?: "archivo"
    when {
        mime.startsWith("image/") -> {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= IMAGE_MAX_SIDE) sample *= 2
            val bmp = resolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: error("unreadable")
            val scale = IMAGE_MAX_SIDE.toFloat() / maxOf(bmp.width, bmp.height)
            val sized = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
            val out = java.io.ByteArrayOutputStream()
            sized.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
            Attachment(name, "image/jpeg", android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP))
        }
        mime == "application/pdf" -> {
            val bytes = resolver.openInputStream(uri)?.use { input ->
                val buf = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                    if (buf.size() > ATTACH_MAX_MB * 1024 * 1024) throw IllegalStateException("too_big:$name")
                }
                buf.toByteArray()
            } ?: error("unreadable")
            Attachment(name, mime, android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
        }
        else -> throw IllegalArgumentException("unsupported")
    }
}

/** Chat with ChatGPT. The page is only sent if the user includes it in that message. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SendaAssistantSheet(prefs: PreferencesManager, activeTab: BrowserTab?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val strings = LocalSendaStrings.current
    val scope = rememberCoroutineScope()
    var configured by remember { mutableStateOf(SendaAssistant.isConfigured(prefs)) }
    var showSettings by remember { mutableStateOf(false) }
    val messages = remember { mutableStateListOf<AssistantMessage>() }
    var input by remember { mutableStateOf("") }
    var partial by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<Job?>(null) }
    var includePage by remember { mutableStateOf(false) }
    var deep by remember { mutableStateOf(false) }
    val pending = remember { mutableStateListOf<Attachment>() }
    val listState = rememberLazyListState()
    val hasPage = activeTab != null && activeTab.url.isNotBlank() && activeTab.url != "about:blank"
    // Changes when switching to another AI from the limit notice
    var switched by remember { mutableIntStateOf(0) }
    val destination = remember(switched) { SendaAssistant.destination(prefs) }
    // What the chosen AI has verified: the chat does not offer anything else
    val backend = remember(configured, switched) { runCatching { SendaAssistant.client(prefs) }.getOrNull() }
    val usingChatGpt = backend is ChatGptPlanClient
    val language = remember { org.senda.browser.core.SendaLocaleManager.getEffectiveLanguage(prefs.appLanguage, context) }
    var showLimit by remember { mutableStateOf(false) }
    if (showLimit) UsageLimitDialog(prefs, onSwitched = { switched++ }, onDismiss = { showLimit = false })

    // Photos and PDF: the system picker; Senda sees no files other than the chosen ones
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        scope.launch {
            uris.forEach { uri ->
                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { readAttachment(context, uri) }
                result.onSuccess { pending += it }.onFailure { e ->
                    val msg = when {
                        e.message?.startsWith("too_big:") == true -> strings.as_attach_too_big.format(e.message!!.removePrefix("too_big:"), ATTACH_MAX_MB)
                        else -> strings.as_attach_unsupported
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun openInTab(url: String) {
        val opener = BrowserTab.tabOpener
        if (opener != null) {
            opener(BrowserTab(isPrivate = activeTab?.isPrivate ?: false, prefs = prefs,
                searchBaseUrl = activeTab?.searchBaseUrl ?: prefs.customSearchEngineUrl, initialUrl = url), false)
        } else {
            openInBrowser(context, url)
        }
        busy?.cancel(); onDismiss()
    }

    fun send(text: String, withPage: Boolean) {
        val question = text.trim()
        if ((question.isEmpty() && pending.isEmpty()) || busy != null) return
        val files = pending.toList()
        input = ""
        partial = ""
        pending.clear()
        busy = scope.launch {
            val pageText = if (withPage && hasPage) activeTab?.extractPageText() else null
            val sent = if (pageText != null)
                SendaAssistant.pageBlock(activeTab?.title.orEmpty(), activeTab?.url.orEmpty(), pageText) + "\n\n" + question
            else question
            messages += AssistantMessage(ChatTurn.Role.USER, question, sent, files)
            includePage = false
            try {
                val turns = messages.map { ChatTurn(it.role, it.sent, it.attachments) }
                // If the AI in use has no model yet (just connected or retired), it is chosen now
                val model = SendaAssistant.ensureModel(prefs)
                val reply = SendaAssistant.client(prefs).chat(model, SendaAssistant.systemPrompt(language), turns, deep && backend?.canThinkDeep == true) { partial = it }
                messages += AssistantMessage(ChatTurn.Role.ASSISTANT, reply.text, reply.text, sources = reply.sources)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // The error does not go into the history that is sent: it is only shown
                messages.removeAt(messages.lastIndex)
                // The company retired the model or it is not for chatting (it happens: Google retires models often):
                // it is forgotten and, when that AI is used again in "My AIs", Senda picks another one that works
                val modelGone = e is RemoteAiException && e.kind == RemoteAiException.Kind.BAD_RESPONSE &&
                    Regex("HTTP 40[04]").containsMatchIn(e.message.orEmpty()) && e.message.orEmpty().contains("model", ignoreCase = true)
                if (modelGone) {
                    // Forgotten: the next message will pick another model that works (ensureModel)
                    prefs.assistantProvider?.let { prefs.setAssistantModelFor(it, "") }
                    Toast.makeText(context, strings.as_model_gone, Toast.LENGTH_LONG).show()
                } else if (e is RemoteAiException && e.kind == RemoteAiException.Kind.USAGE_LIMIT) showLimit = true
                else Toast.makeText(context, assistantErrorText(e, strings, destination), Toast.LENGTH_LONG).show()
                input = question
                pending.addAll(files)
            } finally {
                partial = ""
                busy = null
            }
        }
    }

    LaunchedEffect(messages.size, partial.length / 120) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size)
    }

    if (showSettings) {
        AssistantHubDialog(
            prefs,
            onDismiss = {
                showSettings = false
                configured = SendaAssistant.isConfigured(prefs)
            },
            // OpenAI's page is left in view: the dialog and the assistant sheet are closed
            onLeaveForSignIn = { showSettings = false; busy?.cancel(); onDismiss() }
        )
    }

    ModalBottomSheet(onDismissRequest = { busy?.cancel(); onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f).navigationBarsPadding().imePadding().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(strings.as_menu_title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    if (configured) {
                        // Name of the AI (not the technical address) and its model
                        val aiName = remember(switched) { SendaAssistant.displayName(prefs.assistantProvider ?: SendaAssistant.PROVIDER_ID) }
                        Text(strings.as_sending_to.format(aiName, SendaAssistant.model(prefs).ifBlank { strings.as_model_pending }),
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
                if (messages.isNotEmpty() && busy == null) {
                    TextButton(onClick = { messages.clear(); pending.clear() }) { Text(strings.as_new_chat, fontSize = 12.sp) }
                }
                IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, contentDescription = strings.as_settings_title) }
            }
            if (!configured) {
                Spacer(Modifier.height(24.dp))
                Text(strings.as_not_configured, fontSize = 15.sp)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { showSettings = true }) {
                    ChatGptLogo(18)
                    Spacer(Modifier.width(8.dp))
                    Text(strings.as_chatgpt_continue)
                }
                return@Column
            }
            Text(strings.as_drafts_note, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(messages) { m -> AssistantBubble(m, strings, context) { url -> openInTab(url) } }
                if (busy != null) {
                    item {
                        if (partial.isBlank()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(strings.as_waiting.format(destination), fontSize = 12.sp)
                            }
                        } else {
                            AssistantBubble(AssistantMessage(ChatTurn.Role.ASSISTANT, partial, partial), strings, context) { }
                        }
                    }
                }
            }
            // Message options: page, think deeply and create images (on chatgpt.com: OpenAI does not allow it here)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hasPage) {
                    FilterChip(selected = includePage, onClick = { includePage = !includePage }, label = { Text(strings.as_include_page, fontSize = 12.sp) })
                    AssistChip(enabled = busy == null, onClick = { send(strings.as_summarize_prompt, true) }, label = { Text(strings.as_summarize, fontSize = 12.sp) })
                }
                if (backend?.canThinkDeep == true) FilterChip(
                    selected = deep, onClick = { deep = !deep },
                    leadingIcon = { Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = { Text(strings.as_deep, fontSize = 12.sp) }
                )
                if (usingChatGpt) AssistChip(
                    onClick = { openInTab(ChatGptPlanClient.CHATGPT_WEB) },
                    leadingIcon = { Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    trailingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp)) },
                    label = { Text(strings.as_create_image, fontSize = 12.sp) }
                )
            }
            if (includePage) Text(strings.as_page_on, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
            if (pending.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    pending.forEach { a ->
                        InputChip(
                            selected = false, onClick = { pending.remove(a) },
                            label = { Text(a.name, fontSize = 12.sp, maxLines = 1) },
                            trailingIcon = { Icon(Icons.Default.Close, contentDescription = strings.as_remove_attachment, modifier = Modifier.size(14.dp)) }
                        )
                    }
                }
            }
            // OpenAI's mandatory label next to the input box when the plan is used
            if (usingChatGpt) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                ChatGptLogo(14)
                Spacer(Modifier.width(6.dp))
                Text(strings.as_chatgpt_using_plan, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                if (backend?.canAttach == true) IconButton(enabled = busy == null, onClick = { picker.launch(arrayOf("image/*", "application/pdf")) }) {
                    Icon(Icons.Default.AttachFile, contentDescription = strings.as_attach)
                }
                OutlinedTextField(
                    value = input, onValueChange = { input = it }, placeholder = { Text(strings.as_input_hint) },
                    modifier = Modifier.weight(1f), maxLines = 5, shape = RoundedCornerShape(20.dp)
                )
                IconButton(enabled = busy == null && (input.isNotBlank() || pending.isNotEmpty()), onClick = { send(input, includePage) }) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = strings.as_send)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AssistantBubble(m: AssistantMessage, strings: SendaStringPack, context: Context, onOpenSource: (String) -> Unit) {
    val mine = m.role == ChatTurn.Role.USER
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = if (mine) 300.dp else 600.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                m.attachments.forEach { a ->
                    Text(strings.as_attached.format(a.name), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (m.shown.isNotBlank()) {
                    val linkColor = MaterialTheme.colorScheme.primary
                    val shown = if (mine) AnnotatedString(m.shown) else remember(m.shown, linkColor) { assistantMarkdown(m.shown, linkColor, onOpenSource) }
                    SelectionContainer { Text(shown, fontSize = 14.sp, lineHeight = 20.sp) }
                }
                if (m.sources.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(strings.as_sources, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        m.sources.forEach { src ->
                            if (src.url.isBlank()) {
                                // OpenAI data service without a page (e.g. the weather): it is named, not opened
                                AssistChip(onClick = {}, enabled = false, label = { Text("OpenAI · ${src.title}", fontSize = 11.sp, maxLines = 1) })
                            } else {
                                val host = runCatching { java.net.URL(src.url).host.removePrefix("www.") }.getOrDefault(src.url)
                                AssistChip(
                                    onClick = { onOpenSource(src.url) },
                                    label = { Text(host, fontSize = 11.sp, maxLines = 1) },
                                    trailingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(12.dp)) }
                                )
                            }
                        }
                    }
                }
                if (!mine) {
                    TextButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Senda", assistantPlainText(m.shown)))
                        Toast.makeText(context, strings.as_copied, Toast.LENGTH_SHORT).show()
                    }, contentPadding = PaddingValues(0.dp)) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(strings.as_copy, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
