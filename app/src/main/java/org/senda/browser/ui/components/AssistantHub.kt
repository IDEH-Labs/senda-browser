package org.senda.browser.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.senda.browser.core.LocalSendaStrings
import org.senda.browser.core.PreferencesManager
import org.senda.browser.core.SendaStringPack
import org.senda.browser.core.assistant.ApiProvider
import org.senda.browser.core.assistant.ChatGptPlanAuth
import org.senda.browser.core.assistant.RemoteAiException
import org.senda.browser.core.assistant.SendaAssistant

/** Texto para cuando no se encontró un modelo que responda o la cuenta no puede usarlo. */
private fun connectErrorText(e: Throwable, strings: SendaStringPack, destination: String): String =
    if (e is RemoteAiException && e.kind == RemoteAiException.Kind.BAD_RESPONSE) strings.as_no_model
    else assistantErrorText(e, strings, destination)

/**
 * «Mis IA»: un único lugar para elegir con qué IA habla el asistente. ChatGPT (con el plan del usuario) primero;
 * debajo, las conectadas con clave de API; y «Añadir otra IA (avanzado)». Cada tarjeta muestra solo las
 * capacidades comprobadas. Al conectar, el modelo se elige solo (probado); elegirlo a mano es opcional.
 */
@Composable
fun AssistantHubDialog(
    prefs: PreferencesManager,
    onDismiss: () -> Unit,
    // El inicio de sesión con ChatGPT abre la página de OpenAI: hay que quitar de encima lo que la tape
    onLeaveForSignIn: () -> Unit = onDismiss
) {
    val context = LocalContext.current
    val strings = LocalSendaStrings.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    val connected = remember(revision) { SendaAssistant.connected(prefs) }
    val active = remember(revision) { prefs.assistantProvider }
    var status by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var consentFor by remember { mutableStateOf<String?>(null) }
    var modelPickerFor by remember { mutableStateOf<String?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var showWelcome by remember { mutableStateOf(false) }

    /** «En uso» solo si de verdad está lista (en uso, con modelo y consentimiento); si no, se ofrece «Usar». */
    fun ready(id: String) = active == id && prefs.assistantModelFor(id).isNotBlank() && prefs.assistantConsentFor(id)

    /** Elige el modelo solo (probándolo) y deja la IA en uso. */
    fun finishConnecting(id: String) {
        working = true; status = strings.as_finding_model
        scope.launch {
            try {
                val model = SendaAssistant.autoSelectModel(SendaAssistant.backendFor(id, prefs))
                prefs.setAssistantModelFor(id, model)
                SendaAssistant.use(prefs, id)
                status = null
            } catch (e: Exception) {
                status = connectErrorText(e, strings, SendaAssistant.destinationOf(id))
            } finally {
                working = false
                revision++
            }
        }
    }

    if (showWelcome) ChatGptWelcomeDialog { prefs.assistantChatGptWelcomeSeen = true; showWelcome = false }

    // Resultado del inicio de sesión con ChatGPT, aunque el cuadro se haya cerrado y vuelto a abrir mientras tanto
    val signIn by ChatGptPlanAuth.signInState.collectAsState()
    LaunchedEffect(signIn) {
        when (val st = signIn) {
            is ChatGptPlanAuth.SignInState.Waiting -> status = strings.as_chatgpt_waiting
            is ChatGptPlanAuth.SignInState.Done -> {
                ChatGptPlanAuth.acknowledge()
                if (!prefs.assistantChatGptWelcomeSeen) showWelcome = true
                finishConnecting(SendaAssistant.PROVIDER_ID)
            }
            is ChatGptPlanAuth.SignInState.Failed -> {
                status = strings.as_chatgpt_signin_failed.format(st.code)
                ChatGptPlanAuth.acknowledge()
            }
            else -> {}
        }
    }

    // Consentimiento, una vez por IA: lo que se envíe lo recibe esa empresa
    consentFor?.let { id ->
        AlertDialog(
            onDismissRequest = { consentFor = null },
            title = { Text(SendaAssistant.displayName(id)) },
            text = { Text(strings.as_privacy_ack.format(SendaAssistant.destinationOf(id)), fontSize = 14.sp) },
            confirmButton = {
                Button(onClick = {
                    consentFor = null
                    prefs.setAssistantConsentFor(id, true)
                    when {
                        id == SendaAssistant.PROVIDER_ID && !ChatGptPlanAuth.isSignedIn(prefs) -> {
                            ChatGptPlanAuth.startSignIn(context, prefs) { url -> openSignInBrowser(context, url) }
                            onLeaveForSignIn()
                        }
                        prefs.assistantModelFor(id).isBlank() -> finishConnecting(id)
                        else -> { SendaAssistant.use(prefs, id); revision++ }
                    }
                }) { Text(strings.as_accept) }
            },
            dismissButton = { TextButton(onClick = { consentFor = null }) { Text(strings.general_cancel) } }
        )
    }

    modelPickerFor?.let { id ->
        ModelPickerDialog(prefs, id, onDismiss = { modelPickerFor = null; revision++ })
    }

    if (showAdd) {
        AddApiAiDialog(prefs, onDismiss = { showAdd = false; revision++ })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.as_settings_title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(strings.as_hub_intro, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                // ChatGPT: siempre primero
                if (SendaAssistant.PROVIDER_ID in connected) {
                    AiCard(
                        prefs, SendaAssistant.PROVIDER_ID, ready(SendaAssistant.PROVIDER_ID), working,
                        status = strings.as_conn_plan.format(ChatGptPlanAuth.email(prefs) ?: "ChatGPT"),
                        onUse = {
                            when {
                                !prefs.assistantConsentFor(SendaAssistant.PROVIDER_ID) -> consentFor = SendaAssistant.PROVIDER_ID
                                prefs.assistantModelFor(SendaAssistant.PROVIDER_ID).isBlank() -> finishConnecting(SendaAssistant.PROVIDER_ID)
                                else -> { SendaAssistant.use(prefs, SendaAssistant.PROVIDER_ID); revision++ }
                            }
                        },
                        onChangeModel = { modelPickerFor = SendaAssistant.PROVIDER_ID },
                        onDisconnect = { SendaAssistant.disconnect(prefs, SendaAssistant.PROVIDER_ID); revision++ }
                    )
                } else {
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            // Botón «Continue with ChatGPT» con el logo, como piden las pautas de OpenAI
                            Button(
                                enabled = !working && signIn !is ChatGptPlanAuth.SignInState.Waiting,
                                onClick = { consentFor = SendaAssistant.PROVIDER_ID },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                ChatGptLogo(18)
                                Spacer(Modifier.width(8.dp))
                                Text(strings.as_chatgpt_continue)
                            }
                            Text(strings.as_chatgpt_plan_note, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                            CapabilityRow(SendaAssistant.PROVIDER_ID)
                        }
                    }
                }

                // Las conectadas con clave
                connected.filter { it != SendaAssistant.PROVIDER_ID }.forEach { id ->
                    AiCard(
                        prefs, id, ready(id), working,
                        status = strings.as_conn_key,
                        onUse = {
                            when {
                                !prefs.assistantConsentFor(id) -> consentFor = id
                                prefs.assistantModelFor(id).isBlank() -> finishConnecting(id)
                                else -> { SendaAssistant.use(prefs, id); revision++ }
                            }
                        },
                        onChangeModel = { modelPickerFor = id },
                        onDisconnect = { SendaAssistant.disconnect(prefs, id); revision++ }
                    )
                }

                status?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (working) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(it, fontSize = 12.sp)
                    }
                }

                TextButton(onClick = { showAdd = true }, enabled = !working) { Text(strings.as_add_ai) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(strings.general_close) } }
    )
}

@Composable
private fun AiCard(
    prefs: PreferencesManager,
    id: String,
    inUse: Boolean,
    working: Boolean,
    status: String,
    onUse: () -> Unit,
    onChangeModel: () -> Unit,
    onDisconnect: () -> Unit
) {
    val strings = LocalSendaStrings.current
    val model = prefs.assistantModelFor(id)
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (inUse) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (id == SendaAssistant.PROVIDER_ID) { ChatGptLogo(20); Spacer(Modifier.width(8.dp)) }
                Column(Modifier.weight(1f)) {
                    Text(SendaAssistant.displayName(id), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text(status, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (inUse) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(strings.as_in_use, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                } else {
                    FilledTonalButton(onClick = onUse, enabled = !working) { Text(strings.as_use) }
                }
            }
            CapabilityRow(id)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (model.isBlank()) strings.as_model_pending else strings.as_model_line.format(model),
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onChangeModel, enabled = !working, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text(strings.as_change_model, fontSize = 12.sp)
                }
                TextButton(onClick = onDisconnect, enabled = !working, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text(strings.as_disconnect, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** Iconos de lo que esa IA tiene comprobado en Senda; lo demás no se muestra. */
@Composable
private fun CapabilityRow(id: String) {
    val strings = LocalSendaStrings.current
    val caps = SendaAssistant.capabilities(id)
    val items = listOfNotNull(
        (Icons.Default.Search to strings.as_cap_search).takeIf { caps.searchWeb },
        (Icons.Default.AttachFile to strings.as_cap_attach).takeIf { caps.attach },
        (Icons.Default.Psychology to strings.as_deep).takeIf { caps.thinkDeep }
    ).ifEmpty { listOf(null to strings.as_cap_text_only) }
    Row(modifier = Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items.forEach { (icon, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                (icon as ImageVector?)?.let { Icon(it, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                Spacer(Modifier.width(3.dp))
                Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Elegir el modelo a mano (opcional): se prueba antes de guardarlo. */
@Composable
private fun ModelPickerDialog(prefs: PreferencesManager, id: String, onDismiss: () -> Unit) {
    val strings = LocalSendaStrings.current
    val scope = rememberCoroutineScope()
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var selected by remember { mutableStateOf(prefs.assistantModelFor(id)) }
    var status by remember { mutableStateOf<String?>(strings.as_loading) }
    var working by remember { mutableStateOf(true) }
    val backend = remember(id) { SendaAssistant.backendFor(id, prefs) }

    LaunchedEffect(id) {
        try {
            models = backend.listModels()
            status = null
        } catch (e: Exception) {
            status = assistantErrorText(e, strings, SendaAssistant.destinationOf(id))
        } finally {
            working = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.as_change_model) },
        text = {
            Column {
                status?.let { Text(it, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp)) }
                Column(modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    models.forEach { m ->
                        Row(
                            modifier = Modifier.fillMaxWidth().selectable(selected = m == selected, onClick = { selected = m }),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = m == selected, onClick = { selected = m })
                            Text(m, fontSize = 13.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = !working && selected.isNotBlank(), onClick = {
                working = true; status = strings.as_finding_model
                scope.launch {
                    try {
                        backend.chat(selected, "Reply with: OK", listOf(org.senda.browser.core.assistant.ChatTurn(
                            org.senda.browser.core.assistant.ChatTurn.Role.USER, "OK")), deep = false) {}
                        prefs.setAssistantModelFor(id, selected)
                        onDismiss()
                    } catch (e: Exception) {
                        status = connectErrorText(e, strings, SendaAssistant.destinationOf(id))
                    } finally {
                        working = false
                    }
                }
            }) { Text(strings.as_save) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.general_cancel) } }
    )
}

/**
 * Añadir una IA con clave de API (avanzado): se paga aparte a cada empresa y la suscripción no cuenta. Un solo
 * botón «Conectar»: prueba la clave, elige el modelo solo y la deja en uso.
 */
@Composable
private fun AddApiAiDialog(prefs: PreferencesManager, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val strings = LocalSendaStrings.current
    val scope = rememberCoroutineScope()
    var provider by remember { mutableStateOf(ApiProvider.GEMINI) }
    var apiKey by remember(provider) { mutableStateOf("") }
    var accepted by remember(provider) { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.as_api_title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(strings.as_api_intro, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                ApiProvider.entries.forEach { p ->
                    Row(
                        modifier = Modifier.fillMaxWidth().selectable(selected = p == provider, onClick = { provider = p; status = null }),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = p == provider, onClick = { provider = p; status = null })
                        Column {
                            Text(p.displayName, fontSize = 14.sp)
                            CapabilityRow(p.id)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                // Paso 1: crear la clave en la página oficial
                OutlinedButton(onClick = { openInBrowser(context, provider.keysPage) }, modifier = Modifier.fillMaxWidth()) {
                    Text(strings.as_get_key.format(java.net.URL(provider.keysPage).host), fontSize = 13.sp)
                }
                Spacer(Modifier.height(6.dp))
                // Paso 2: pegarla
                OutlinedTextField(
                    value = apiKey, onValueChange = { apiKey = it.trim() },
                    label = { Text(strings.as_api_key) },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(strings.as_key_encrypted.format(provider.host), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (provider == ApiProvider.GEMINI) {
                    // Condiciones de Google para el nivel gratuito de la API de Gemini
                    Text(strings.as_api_gemini_free_note, fontSize = 11.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.clickable { accepted = !accepted }) {
                    Checkbox(checked = accepted, onCheckedChange = { accepted = it })
                    Text(strings.as_privacy_ack.format(provider.host), fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                }
                status?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        if (working) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(it, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            // Paso 3: conectar (prueba la clave, elige el modelo solo y la deja en uso)
            Button(enabled = !working && accepted && apiKey.isNotBlank(), onClick = {
                val current = provider
                working = true; status = strings.as_finding_model
                scope.launch {
                    try {
                        val model = SendaAssistant.autoSelectModel(SendaAssistant.backendFor(current.id, prefs, apiKey))
                        if (!prefs.setAssistantKey(current.id, apiKey)) {
                            status = strings.as_key_not_saved
                            return@launch
                        }
                        prefs.setAssistantModelFor(current.id, model)
                        prefs.setAssistantConsentFor(current.id, true)
                        SendaAssistant.use(prefs, current.id)
                        onDismiss()
                    } catch (e: Exception) {
                        status = connectErrorText(e, strings, current.host)
                    } finally {
                        working = false
                    }
                }
            }) { Text(strings.as_connect) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.general_cancel) } }
    )
}
