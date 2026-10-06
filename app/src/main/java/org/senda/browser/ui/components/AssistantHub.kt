package org.senda.browser.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.ui.text.style.TextOverflow
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

    /** «En uso» si es la activa y tiene consentimiento (el modelo se elige solo si falta); si no, «Usar». */
    fun ready(id: String) = active == id && prefs.assistantConsentFor(id)

    // Una IA preparándose (aunque este cuadro se haya cerrado y vuelto a abrir mientras tanto)
    val preparing by SendaAssistant.connecting.collectAsState()
    LaunchedEffect(preparing) {
        working = preparing != null
        if (preparing != null) status = strings.as_finding_model
        revision++
    }

    /**
     * La deja en uso y elige el modelo solo (probándolo). En el ámbito del asistente, no de esta pantalla: cerrar
     * «Mis IA» antes de que termine ya no lo cancela (pasó el 2026-10-06 y la IA quedó sin modelo).
     */
    fun finishConnecting(id: String) {
        SendaAssistant.use(prefs, id)
        SendaAssistant.connecting.value = id
        SendaAssistant.scope.launch {
            try {
                SendaAssistant.ensureModel(prefs)
                status = null
            } catch (e: Exception) {
                status = connectErrorText(e, strings, SendaAssistant.destinationOf(id))
            } finally {
                SendaAssistant.connecting.value = null
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
                Text(strings.as_hub_intro, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                // Orden: la que responde ahora, luego las demás conectadas (ChatGPT antes que las de clave)
                val inUse = connected.filter { ready(it) }
                val others = connected.filterNot { ready(it) }
                fun cardFor(id: String) = @Composable {
                    AiCard(
                        prefs, id, ready(id), working,
                        connection = if (id == SendaAssistant.PROVIDER_ID)
                            strings.as_conn_plan.format(ChatGptPlanAuth.email(prefs) ?: "ChatGPT") else strings.as_conn_key,
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
                if (inUse.isNotEmpty()) {
                    SectionTitle(strings.as_section_in_use)
                    inUse.forEach { cardFor(it)() }
                }
                if (others.isNotEmpty() || SendaAssistant.PROVIDER_ID !in connected) {
                    SectionTitle(if (inUse.isEmpty()) strings.as_section_choose else strings.as_section_others)
                    others.forEach { cardFor(it)() }
                    if (SendaAssistant.PROVIDER_ID !in connected) {
                        ChatGptConnectCard(
                            enabled = !working && signIn !is ChatGptPlanAuth.SignInState.Waiting,
                            onConnect = { consentFor = SendaAssistant.PROVIDER_ID }
                        )
                    }
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

                SectionTitle(strings.as_add_ai)
                OutlinedCard(
                    onClick = { showAdd = true }, enabled = !working,
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text(strings.as_add_ai_sub, fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(strings.general_close) } }
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp,
        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp)
    )
}

/** Marca de cada IA: el logo de ChatGPT (obligatorio en sus pautas) o la inicial en un círculo. */
@Composable
private fun AiMark(id: String) {
    if (id == SendaAssistant.PROVIDER_ID) {
        ChatGptLogo(28)
    } else {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(28.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text(SendaAssistant.displayName(id).take(1), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}

/**
 * Cabecera común: marca, nombre y cómo está conectada (una línea cada uno; lo largo se corta con «…») y, a la
 * derecha, «✓ En uso» si es la que responde.
 */
@Composable
private fun AiHeader(id: String, connection: String, inUse: Boolean = false) {
    val strings = LocalSendaStrings.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        AiMark(id)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(SendaAssistant.displayName(id), fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(connection, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (inUse) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(strings.as_in_use, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun AiCard(
    prefs: PreferencesManager,
    id: String,
    inUse: Boolean,
    working: Boolean,
    connection: String,
    onUse: () -> Unit,
    onChangeModel: () -> Unit,
    onDisconnect: () -> Unit
) {
    val strings = LocalSendaStrings.current
    val model = prefs.assistantModelFor(id)
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (inUse) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = if (inUse) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)) else null
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AiHeader(id, connection, inUse)
            CapabilityPills(id)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(strings.as_model_label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(8.dp))
                Text(
                    model.ifBlank { strings.as_model_pending },
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            // Acciones secundarias en su fila; la principal («Usar»), ancha y al final
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onChangeModel, enabled = !working, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(strings.as_change_model, fontSize = 13.sp, maxLines = 1)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDisconnect, enabled = !working, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(strings.as_disconnect, fontSize = 13.sp, color = MaterialTheme.colorScheme.error, maxLines = 1)
                }
            }
            if (!inUse) {
                FilledTonalButton(onClick = onUse, enabled = !working, modifier = Modifier.fillMaxWidth()) { Text(strings.as_use) }
            }
        }
    }
}

/** ChatGPT aún sin conectar: la misma tarjeta, con el botón oficial «Continuar con ChatGPT». */
@Composable
private fun ChatGptConnectCard(enabled: Boolean, onConnect: () -> Unit) {
    val strings = LocalSendaStrings.current
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AiHeader(SendaAssistant.PROVIDER_ID, strings.as_conn_plan_offer)
            CapabilityPills(SendaAssistant.PROVIDER_ID)
            // Botón «Continue with ChatGPT» con el logo, como piden las pautas de OpenAI
            Button(enabled = enabled, onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
                ChatGptLogo(18)
                Spacer(Modifier.width(8.dp))
                Text(strings.as_chatgpt_continue)
            }
        }
    }
}

/** Etiquetas de lo que esa IA tiene comprobado en Senda (bajan de línea si no caben); lo demás no se muestra. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CapabilityPills(id: String) {
    val strings = LocalSendaStrings.current
    val caps = SendaAssistant.capabilities(id)
    val items: List<Pair<ImageVector?, String>> = listOfNotNull(
        (Icons.Default.Search to strings.as_cap_search).takeIf { caps.searchWeb },
        (Icons.Default.AttachFile to strings.as_cap_attach).takeIf { caps.attach },
        (Icons.Default.Psychology to strings.as_deep).takeIf { caps.thinkDeep }
    ).ifEmpty { listOf(null to strings.as_cap_text_only) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { (icon, label) ->
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    icon?.let {
                        Icon(it, contentDescription = null, modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 1)
                }
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
                            CapabilityPills(p.id)
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
