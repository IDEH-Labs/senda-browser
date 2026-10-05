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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Settings
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.senda.browser.core.LocalSendaStrings
import org.senda.browser.core.PreferencesManager
import org.senda.browser.core.SendaStringPack
import org.senda.browser.core.assistant.ChatTurn
import org.senda.browser.core.assistant.RemoteAiClients
import org.senda.browser.core.assistant.RemoteAiException
import org.senda.browser.core.assistant.RemoteAiProvider
import org.senda.browser.core.assistant.SendaAssistant
import org.senda.browser.ui.model.BrowserTab

/** Texto para el usuario a partir de un fallo del proveedor (sin detalles técnicos que no ayudan). */
fun assistantErrorText(e: Throwable, strings: SendaStringPack, destination: String): String = when (e) {
    is RemoteAiException -> when (e.kind) {
        RemoteAiException.Kind.NOT_CONFIGURED -> strings.as_err_incomplete
        RemoteAiException.Kind.INSECURE_URL -> strings.as_err_insecure
        RemoteAiException.Kind.AUTH -> strings.as_err_auth
        RemoteAiException.Kind.RATE_LIMIT -> strings.as_err_rate
        RemoteAiException.Kind.NETWORK -> strings.as_err_network.format(destination)
        RemoteAiException.Kind.REFUSED -> strings.as_err_refused
        // Código HTTP y el comienzo del mensaje del proveedor (nunca incluye la clave)
        RemoteAiException.Kind.BAD_RESPONSE -> strings.as_err_bad.format(e.message?.take(80)?.ifBlank { null } ?: "?")
        RemoteAiException.Kind.USAGE_LIMIT -> strings.as_chatgpt_limit
        RemoteAiException.Kind.NOT_ELIGIBLE -> strings.as_chatgpt_not_eligible
    }
    else -> strings.as_err_network.format(destination)
}

/** Abre una dirección en el navegador predeterminado (normalmente el propio Senda). */
fun openInBrowser(context: Context, url: String) {
    try {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: android.content.ActivityNotFoundException) {}
}

/**
 * Inicio de sesión de OpenAI en otro navegador del teléfono (RFC 8252: navegador externo). En Senda, el bloqueo
 * estricto de rastreadores y uBlock cortan peticiones que necesita la página de OpenAI y el botón no responde
 * (probado el 2026-10-05); no se rebajan las protecciones de Senda para todos los sitios por esto. La vuelta llega
 * igual a Senda por 127.0.0.1. Sin otro navegador instalado, se abre en Senda.
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

/** Aviso obligatorio de OpenAI al usar el plan de ChatGPT por primera vez, con su logo. */
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

/** Aviso de límite de uso del plan, con la acción «Manage usage» que exige la guía de OpenAI. */
@Composable
fun ChatGptUsageLimitDialog(onDismiss: () -> Unit) {
    val strings = LocalSendaStrings.current
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { ChatGptLogo(28) },
        title = { Text(strings.as_chatgpt_using_plan) },
        text = { Text(strings.as_chatgpt_limit) },
        confirmButton = {
            Button(onClick = { openInBrowser(context, org.senda.browser.core.assistant.ChatGptPlanClient.MANAGE_USAGE_URL); onDismiss() }) {
                Text(strings.as_chatgpt_manage_usage)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.general_cancel) } }
    )
}

@Composable
private fun ChatGptLogo(sizeDp: Int) {
    Icon(
        painter = androidx.compose.ui.res.painterResource(org.senda.browser.R.drawable.ic_chatgpt_logo),
        contentDescription = "ChatGPT",
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(sizeDp.dp)
    )
}

/** Configuración del asistente: proveedor, clave cifrada, prueba de conexión, modelo y aviso de privacidad. */
@Composable
fun SendaAssistantSettingsDialog(
    prefs: PreferencesManager,
    onDismiss: () -> Unit,
    // Al iniciar sesión con ChatGPT se abre la página de OpenAI en una pestaña: hay que quitar de encima lo que la
    // tape (este cuadro y, si viene del chat, la hoja del asistente). Al volver, el cuadro retoma el resultado
    onLeaveForSignIn: () -> Unit = onDismiss
) {
    val context = LocalContext.current
    val strings = LocalSendaStrings.current
    val scope = rememberCoroutineScope()

    var provider by remember { mutableStateOf(RemoteAiProvider.byId(prefs.assistantProvider) ?: RemoteAiProvider.ANTHROPIC) }
    var serverUrl by remember { mutableStateOf(prefs.assistantServerUrl) }
    var apiKey by remember(provider) { mutableStateOf(prefs.getAssistantKey(provider.id)) }
    var models by remember(provider) { mutableStateOf<List<String>>(emptyList()) }
    var model by remember(provider) {
        mutableStateOf(if (prefs.assistantProvider == provider.id) prefs.assistantModel else "")
    }
    var status by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var accepted by remember(provider) { mutableStateOf(prefs.assistantPrivacyAccepted && prefs.assistantProvider == provider.id) }
    val isPlan = provider.style == RemoteAiProvider.Style.CHATGPT_PLAN
    var planEmail by remember { mutableStateOf(org.senda.browser.core.assistant.ChatGptPlanAuth.email(prefs)) }
    var planSignedIn by remember { mutableStateOf(org.senda.browser.core.assistant.ChatGptPlanAuth.isSignedIn(prefs)) }
    var showWelcome by remember { mutableStateOf(false) }

    fun loadModels() {
        loading = true; status = strings.as_loading
        // Proveedor del momento de la llamada (tras iniciar sesión cambia en el mismo instante)
        val current = provider
        val plan = current.style == RemoteAiProvider.Style.CHATGPT_PLAN
        scope.launch {
            try {
                val client = if (plan) org.senda.browser.core.assistant.ChatGptPlanClient(prefs)
                    else RemoteAiClients.create(current, apiKey, serverUrl)
                val list = client.listModels()
                models = list
                if (model !in list) model = current.suggestedModel?.takeIf { it in list } ?: list.firstOrNull().orEmpty()
                status = strings.as_models_loaded.format(list.size)
            } catch (e: Exception) {
                status = assistantErrorText(e, strings, "api.openai.com".takeIf { plan } ?: current.baseUrl.orEmpty())
            } finally {
                loading = false
            }
        }
    }

    if (showWelcome) ChatGptWelcomeDialog { prefs.assistantChatGptWelcomeSeen = true; showWelcome = false }

    // Resultado del inicio de sesión con ChatGPT, aunque la pantalla se haya cerrado y vuelto a abrir mientras tanto
    val signIn by org.senda.browser.core.assistant.ChatGptPlanAuth.signInState.collectAsState()
    LaunchedEffect(signIn) {
        when (val st = signIn) {
            is org.senda.browser.core.assistant.ChatGptPlanAuth.SignInState.Waiting -> status = strings.as_chatgpt_waiting
            is org.senda.browser.core.assistant.ChatGptPlanAuth.SignInState.Done -> {
                provider = RemoteAiProvider.CHATGPT_PLAN
                planSignedIn = true; planEmail = st.email
                if (!prefs.assistantChatGptWelcomeSeen) showWelcome = true
                org.senda.browser.core.assistant.ChatGptPlanAuth.acknowledge()
                loadModels()
            }
            is org.senda.browser.core.assistant.ChatGptPlanAuth.SignInState.Failed -> {
                status = strings.as_chatgpt_signin_failed.format(st.code)
                org.senda.browser.core.assistant.ChatGptPlanAuth.acknowledge()
            }
            else -> {}
        }
    }

    val destination = provider.baseUrl?.let { runCatching { java.net.URL(it).host }.getOrNull() }
        ?: runCatching { java.net.URL(serverUrl).host }.getOrNull() ?: serverUrl

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.as_settings_title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(strings.as_settings_intro, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                Text(strings.as_provider, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                RemoteAiProvider.entries.forEach { p ->
                    Row(
                        modifier = Modifier.fillMaxWidth().selectable(selected = p == provider, onClick = {
                            provider = p; status = null
                        }).padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = p == provider, onClick = { provider = p; status = null })
                        Text(if (p.isOwnServer) strings.as_own_server else p.displayName, fontSize = 14.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (provider.isOwnServer) {
                    OutlinedTextField(
                        value = serverUrl, onValueChange = { serverUrl = it.trim() },
                        label = { Text(strings.as_server_url, fontSize = 12.sp) },
                        singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                }
                if (isPlan) {
                    Text(strings.as_chatgpt_plan_note, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    if (planSignedIn) {
                        Text(strings.as_chatgpt_signed_in.format(planEmail ?: "ChatGPT"), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        TextButton(onClick = {
                            org.senda.browser.core.assistant.ChatGptPlanAuth.signOut(prefs)
                            planSignedIn = false; planEmail = null; models = emptyList(); model = ""
                        }) { Text(strings.as_chatgpt_sign_out) }
                    } else {
                        // Botón «Continue with ChatGPT» con el logo, como piden las pautas de OpenAI
                        Button(
                            enabled = !loading && signIn !is org.senda.browser.core.assistant.ChatGptPlanAuth.SignInState.Waiting,
                            onClick = {
                                org.senda.browser.core.assistant.ChatGptPlanAuth.startSignIn(context, prefs) { url -> openSignInBrowser(context, url) }
                                onLeaveForSignIn()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            ChatGptLogo(18)
                            Spacer(Modifier.width(8.dp))
                            Text(strings.as_chatgpt_continue)
                        }
                    }
                } else OutlinedTextField(
                    value = apiKey, onValueChange = { apiKey = it.trim() },
                    label = { Text(if (provider.isOwnServer) strings.as_api_key_optional else strings.as_api_key) },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                provider.keysPage?.let { page ->
                    Text(strings.as_get_key.format(page), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                }
                if (destination.isNotBlank() && !isPlan) {
                    Text(strings.as_key_encrypted.format(destination), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                if (!isPlan || planSignedIn) FilledTonalButton(
                    enabled = !loading,
                    onClick = { loadModels() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(strings.as_load_models) }
                status?.let { Text(it, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
                if (models.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(strings.as_model, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Column(modifier = Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
                        models.forEach { m ->
                            Row(
                                modifier = Modifier.fillMaxWidth().selectable(selected = m == model, onClick = { model = m }),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = m == model, onClick = { model = m })
                                Text(m, fontSize = 13.sp)
                            }
                        }
                    }
                } else if (model.isNotBlank()) {
                    Text("${strings.as_model}: $model", fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.clickable { accepted = !accepted }) {
                    Checkbox(checked = accepted, onCheckedChange = { accepted = it })
                    Text(strings.as_privacy_ack.format(destination.ifBlank { "?" }), fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                }
                if (prefs.assistantProvider != null) {
                    TextButton(onClick = {
                        org.senda.browser.core.assistant.ChatGptPlanAuth.signOut(prefs)
                        RemoteAiProvider.entries.forEach { prefs.setAssistantKey(it.id, "") }
                        prefs.assistantProvider = null
                        prefs.assistantModel = ""
                        prefs.assistantPrivacyAccepted = false
                        onDismiss()
                    }) { Text(strings.as_remove, color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !loading && accepted && model.isNotBlank() && when {
                    isPlan -> planSignedIn
                    provider.isOwnServer -> serverUrl.isNotBlank()
                    else -> apiKey.isNotBlank()
                },
                onClick = {
                    if (!isPlan && !prefs.setAssistantKey(provider.id, apiKey)) {
                        Toast.makeText(context, strings.as_key_not_saved, Toast.LENGTH_LONG).show()
                        return@Button
                    }
                    prefs.assistantProvider = provider.id
                    prefs.assistantServerUrl = serverUrl
                    prefs.assistantModel = model
                    prefs.assistantPrivacyAccepted = true
                    Toast.makeText(context, strings.as_saved, Toast.LENGTH_SHORT).show()
                    onDismiss()
                }
            ) { Text(strings.as_save) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.general_cancel) } }
    )
}

private data class AssistantMessage(val role: ChatTurn.Role, val shown: String, val sent: String)

/** Chat con el proveedor configurado. La página solo se envía si el usuario la incluye en ese mensaje. */
@OptIn(ExperimentalMaterial3Api::class)
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
    val listState = rememberLazyListState()
    val hasPage = activeTab != null && activeTab.url.isNotBlank() && activeTab.url != "about:blank"
    val destination = SendaAssistant.destination(prefs)
    val language = remember { org.senda.browser.core.SendaLocaleManager.getEffectiveLanguage(prefs.appLanguage, context) }
    val usingPlan = prefs.assistantProvider == RemoteAiProvider.CHATGPT_PLAN.id
    var showLimit by remember { mutableStateOf(false) }
    if (showLimit) ChatGptUsageLimitDialog { showLimit = false }

    fun send(text: String, withPage: Boolean) {
        val question = text.trim()
        if (question.isEmpty() || busy != null) return
        input = ""
        partial = ""
        busy = scope.launch {
            val pageText = if (withPage && hasPage) activeTab?.extractPageText() else null
            val sent = if (pageText != null)
                SendaAssistant.pageBlock(activeTab?.title.orEmpty(), activeTab?.url.orEmpty(), pageText) + "\n\n" + question
            else question
            messages += AssistantMessage(ChatTurn.Role.USER, question, sent)
            includePage = false
            try {
                val turns = messages.map { ChatTurn(it.role, it.sent) }
                val answer = SendaAssistant.client(prefs).chat(prefs.assistantModel, SendaAssistant.systemPrompt(language), turns) { partial = it }
                messages += AssistantMessage(ChatTurn.Role.ASSISTANT, answer, answer)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // El error no entra en el historial que se envía: solo se muestra
                messages.removeAt(messages.lastIndex)
                if (e is RemoteAiException && e.kind == RemoteAiException.Kind.USAGE_LIMIT) showLimit = true
                else Toast.makeText(context, assistantErrorText(e, strings, destination), Toast.LENGTH_LONG).show()
                input = question
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
        SendaAssistantSettingsDialog(
            prefs,
            onDismiss = {
                showSettings = false
                configured = SendaAssistant.isConfigured(prefs)
            },
            // La página de OpenAI queda a la vista: se cierran el cuadro y la hoja del asistente
            onLeaveForSignIn = { showSettings = false; busy?.cancel(); onDismiss() }
        )
    }

    ModalBottomSheet(onDismissRequest = { busy?.cancel(); onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f).navigationBarsPadding().imePadding().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(strings.as_menu_title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    if (configured) {
                        Text(strings.as_sending_to.format(destination, prefs.assistantModel), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
                if (messages.isNotEmpty() && busy == null) {
                    TextButton(onClick = { messages.clear() }) { Text(strings.as_new_chat, fontSize = 12.sp) }
                }
                IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, contentDescription = strings.as_settings_title) }
            }
            if (!configured) {
                Spacer(Modifier.height(24.dp))
                Text(strings.as_not_configured, fontSize = 15.sp)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { showSettings = true }) { Text(strings.as_configure) }
                return@Column
            }
            Text(strings.as_drafts_note, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(messages) { m -> AssistantBubble(m, strings, context) }
                if (busy != null) {
                    item {
                        if (partial.isBlank()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(strings.as_waiting.format(destination), fontSize = 12.sp)
                            }
                        } else {
                            AssistantBubble(AssistantMessage(ChatTurn.Role.ASSISTANT, partial, partial), strings, context)
                        }
                    }
                }
            }
            if (hasPage) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = includePage, onClick = { includePage = !includePage }, label = { Text(strings.as_include_page, fontSize = 12.sp) })
                    Spacer(Modifier.width(8.dp))
                    AssistChip(enabled = busy == null, onClick = { send(strings.as_summarize_prompt, true) }, label = { Text(strings.as_summarize, fontSize = 12.sp) })
                }
                if (includePage) Text(strings.as_page_on, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
            }
            // Etiqueta obligatoria de OpenAI junto al cuadro de escritura cuando se usa el plan
            if (usingPlan) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    ChatGptLogo(14)
                    Spacer(Modifier.width(6.dp))
                    Text(strings.as_chatgpt_using_plan, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                OutlinedTextField(
                    value = input, onValueChange = { input = it }, placeholder = { Text(strings.as_input_hint) },
                    modifier = Modifier.weight(1f), maxLines = 5, shape = RoundedCornerShape(20.dp)
                )
                IconButton(enabled = busy == null && input.isNotBlank(), onClick = { send(input, includePage) }) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = strings.as_send)
                }
            }
        }
    }
}

@Composable
private fun AssistantBubble(m: AssistantMessage, strings: SendaStringPack, context: Context) {
    val mine = m.role == ChatTurn.Role.USER
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = if (mine) 300.dp else 600.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                SelectionContainer { Text(m.shown, fontSize = 14.sp, lineHeight = 20.sp) }
                if (!mine) {
                    TextButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Senda", m.shown))
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
