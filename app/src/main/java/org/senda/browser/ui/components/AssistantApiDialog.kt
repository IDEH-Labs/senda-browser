package org.senda.browser.ui.components

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
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
import kotlinx.coroutines.launch
import org.senda.browser.core.LocalSendaStrings
import org.senda.browser.core.PreferencesManager
import org.senda.browser.core.assistant.ApiProvider
import org.senda.browser.core.assistant.SendaAssistant

/**
 * Para usuarios avanzados: el asistente con Claude, Gemini, Grok o Mistral mediante clave de API. Separado de la
 * configuración normal (ChatGPT con el plan) para no confundir: aquí se paga aparte a cada empresa y la
 * suscripción no cuenta. Solo conversación por texto hasta comprobar más con una clave real de cada una.
 */
@Composable
fun AssistantApiSettingsDialog(prefs: PreferencesManager, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val strings = LocalSendaStrings.current
    val scope = rememberCoroutineScope()

    var provider by remember { mutableStateOf(SendaAssistant.apiProvider(prefs) ?: ApiProvider.ANTHROPIC) }
    var apiKey by remember(provider) { mutableStateOf(prefs.getAssistantKey(provider.id)) }
    var models by remember(provider) { mutableStateOf<List<String>>(emptyList()) }
    var model by remember(provider) { mutableStateOf(if (prefs.assistantProvider == provider.id) prefs.assistantModel else "") }
    var accepted by remember(provider) { mutableStateOf(prefs.assistantProvider == provider.id && prefs.assistantPrivacyAccepted) }
    var status by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    fun loadModels() {
        loading = true; status = strings.as_loading
        val current = provider
        scope.launch {
            try {
                val list = current.client(apiKey).listModels()
                models = list
                if (model !in list) model = list.firstOrNull().orEmpty()
                status = strings.as_models_loaded.format(list.size)
            } catch (e: Exception) {
                status = assistantErrorText(e, strings, current.host)
            } finally {
                loading = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.as_api_title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(strings.as_api_intro, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Text(strings.as_api_text_only, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                ApiProvider.entries.forEach { p ->
                    Row(
                        modifier = Modifier.fillMaxWidth().selectable(selected = p == provider, onClick = { provider = p; status = null }),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = p == provider, onClick = { provider = p; status = null })
                        Text(p.displayName, fontSize = 14.sp)
                    }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = apiKey, onValueChange = { apiKey = it.trim() },
                    label = { Text(strings.as_api_key) },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(onClick = { openInBrowser(context, provider.keysPage); onDismiss() }, contentPadding = PaddingValues(0.dp)) {
                    Text(strings.as_get_key.format(java.net.URL(provider.keysPage).host), fontSize = 12.sp)
                }
                Text(strings.as_key_encrypted.format(provider.host), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (provider == ApiProvider.GEMINI) {
                    // Condiciones de Google para el nivel gratuito de la API de Gemini
                    Text(strings.as_api_gemini_free_note, fontSize = 11.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
                }
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(enabled = !loading && apiKey.isNotBlank(), onClick = { loadModels() }, modifier = Modifier.fillMaxWidth()) {
                    Text(strings.as_load_models)
                }
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
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.clickable { accepted = !accepted }) {
                    Checkbox(checked = accepted, onCheckedChange = { accepted = it })
                    Text(strings.as_privacy_ack.format(provider.host), fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                }
                if (prefs.getAssistantKey(provider.id).isNotBlank()) {
                    TextButton(onClick = {
                        prefs.setAssistantKey(provider.id, "")
                        if (prefs.assistantProvider == provider.id) {
                            prefs.assistantProvider = null; prefs.assistantModel = ""; prefs.assistantPrivacyAccepted = false
                        }
                        apiKey = ""; models = emptyList(); model = ""
                    }) { Text(strings.as_api_remove, color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !loading && accepted && apiKey.isNotBlank() && model.isNotBlank(),
                onClick = {
                    // Se prueba el modelo antes de guardarlo: la lista de la empresa incluye modelos que esa clave no
                    // puede usar (retirados para usuarios nuevos o especiales), y fallarían en el chat sin explicación
                    val current = provider
                    loading = true; status = strings.as_api_checking
                    scope.launch {
                        try {
                            current.client(apiKey).chat(model, "Reply with: OK", listOf(org.senda.browser.core.assistant.ChatTurn(
                                org.senda.browser.core.assistant.ChatTurn.Role.USER, "OK")), deep = false) {}
                            if (!prefs.setAssistantKey(current.id, apiKey)) {
                                Toast.makeText(context, strings.as_key_not_saved, Toast.LENGTH_LONG).show()
                                return@launch
                            }
                            prefs.assistantProvider = current.id
                            prefs.assistantModel = model
                            prefs.assistantPrivacyAccepted = true
                            Toast.makeText(context, strings.as_saved, Toast.LENGTH_SHORT).show()
                            onDismiss()
                        } catch (e: org.senda.browser.core.assistant.RemoteAiException) {
                            status = if (e.kind == org.senda.browser.core.assistant.RemoteAiException.Kind.BAD_RESPONSE &&
                                (e.message.orEmpty().startsWith("HTTP 404") || e.message.orEmpty().startsWith("HTTP 400"))) strings.as_api_model_unusable
                            else assistantErrorText(e, strings, current.host)
                        } catch (e: Exception) {
                            status = assistantErrorText(e, strings, current.host)
                        } finally {
                            loading = false
                        }
                    }
                }
            ) { Text(strings.as_api_use) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.general_cancel) } }
    )
}
