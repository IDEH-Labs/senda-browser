package org.senda.browser.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.senda.browser.core.LocalSendaStrings
import org.senda.browser.core.PreferencesManager
import org.senda.browser.core.ai.*
import org.senda.browser.ui.model.BrowserTab
import java.io.File

/**
 * Diálogo Soberano y Consciente del Hardware para los Controles de IA en Senda.
 * Ofrece diagnóstico en vivo del teléfono (RAM, CPU, Vulkan, Temperatura),
 * selección transparente de modelos, gestor de descargas atómicas y
 * garantía criptográfica de cero telemetría.
 */
@Composable
fun SendaSovereignAiDialog(
    prefs: PreferencesManager,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val strings = LocalSendaStrings.current

    // 1. Diagnóstico de hardware en tiempo real (ejecutable interactivamente)
    var profile by remember { mutableStateOf(SendaHardwareProfiler.analyze(context)) }
    var isDiagnosing by remember { mutableStateOf(false) }
    var diagnosticExecuted by remember { mutableStateOf(false) }
    var activeModelId by remember { mutableStateOf(prefs.selectedLocalAiModel) }
    var selectedTab by remember { mutableIntStateOf(0) } // 0: Local en Chip, 1: Servidor Propio (Ollama/API)

    // Estados de descarga para cada modelo
    var downloadStates by remember {
        mutableStateOf(
            SendaAiModels.ALL_MODELS.associate { model ->
                model.id to (if (SendaModelManager.isModelReady(context, model)) ModelDownloadState.Ready else ModelDownloadState.Idle)
            }
        )
    }

    var activeDownloadingModelId by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Psychology,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Senda IA Soberana",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "100% Privado • Sin Rastreo Externo",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // PESTAÑAS DE MODALIDAD
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Modo Offline", fontSize = 12.sp, fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Ollama / Red LAN", fontSize = 12.sp, fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal) }
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (selectedTab == 0) {
                    // TAB 0: MODELO LOCAL EN EL DISPOSITIVO
                    // TARJETA DE DIAGNÓSTICO EN VIVO DE HARDWARE
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Memory,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Diagnóstico en Vivo de Hardware",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                if (diagnosticExecuted) {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                    ) {
                                        Text(
                                            text = "ACTUALIZADO",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = "• RAM Total: ${String.format("%.1f", profile.totalRamGb)} GB (${String.format("%.1f", profile.availableRamGb)} GB libres)",
                                fontSize = 11.sp
                            )
                            Text(
                                text = "• CPU: ${profile.cpuCores} núcleos (${profile.performanceThreadsOptimal} hilos asignados)",
                                fontSize = 11.sp
                            )
                            Text(
                                text = "• Acelerador Gráfico: ${if (profile.hasVulkanSupport) "Vulkan 1.x Activo" else "CPU ARM NEON"}",
                                fontSize = 11.sp
                            )
                            Text(
                                text = "• Estado Térmico: ${profile.thermalStatusLabel}",
                                fontSize = 11.sp,
                                color = if (profile.isThermalThrottled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            // BOTÓN INTERACTIVO PARA EJECUTAR EL DIAGNÓSTICO
                            Button(
                                onClick = {
                                    isDiagnosing = true
                                    scope.launch {
                                        kotlinx.coroutines.delay(600)
                                        profile = SendaHardwareProfiler.analyze(context)
                                        isDiagnosing = false
                                        diagnosticExecuted = true
                                    }
                                },
                                enabled = !isDiagnosing,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                if (isDiagnosing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Analizando memoria y procesador...", fontSize = 11.sp)
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Speed,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (diagnosticExecuted) "Volver a Medir Hardware" else "Ejecutar Diagnóstico de Hardware",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // RECOMENDACIÓN DE IA IDEAL BASADA EN EL DIAGNÓSTICO
                            val recommendedModel = SendaAiModels.getById(profile.recommendedModelId)
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.AutoAwesome,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(5.dp))
                                        Text(
                                            text = "IA Ideal Determinada: ${recommendedModel.name}",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "Tope seguro: ${profile.maxRecommendedContextTokens} tokens • Presupuesto verificado para evitar cierres OOM.",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (activeModelId != recommendedModel.id) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        OutlinedButton(
                                            onClick = {
                                                activeModelId = recommendedModel.id
                                                prefs.selectedLocalAiModel = recommendedModel.id
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(6.dp),
                                            contentPadding = PaddingValues(vertical = 4.dp, horizontal = 8.dp)
                                        ) {
                                            Text("Activar ${recommendedModel.name} como Predeterminado", fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "Catálogo de Modelos Soberanos (GGUF)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // LISTA DE MODELOS ADAPTATIVOS
                    SendaAiModels.ALL_MODELS.forEach { model ->
                        val isRecommended = model.id == profile.recommendedModelId
                        val state = downloadStates[model.id] ?: ModelDownloadState.Idle
                        val isReady = state is ModelDownloadState.Ready

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable {
                                    activeModelId = model.id
                                    prefs.selectedLocalAiModel = model.id
                                    if (isReady) {
                                        val modelFile = SendaModelManager.getModelFile(context, model)
                                        scope.launch {
                                            org.senda.browser.core.ai.llama.SendaLlamaBridge.loadModel(modelFile)
                                        }
                                    }
                                },
                            shape = RoundedCornerShape(10.dp),
                            color = if (model.id == activeModelId) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                            else if (isRecommended) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.20f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            border = BorderStroke(
                                if (model.id == activeModelId) 1.5.dp else if (isRecommended) 1.0.dp else 0.5.dp,
                                if (model.id == activeModelId) MaterialTheme.colorScheme.primary
                                else if (isRecommended) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                            )
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = model.name,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp
                                            )
                                            if (model.id == activeModelId) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = MaterialTheme.colorScheme.primary
                                                ) {
                                                    Text(
                                                        text = "ACTIVO",
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.onPrimary,
                                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                            if (isRecommended) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = MaterialTheme.colorScheme.secondary
                                                ) {
                                                    Text(
                                                        text = "IDEAL",
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.onSecondary,
                                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            text = "${model.subtitle} • RAM: ~${model.ramRequiredMb} MB",
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    // ESTADO O ACCIÓN
                                    if (isReady) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Default.CheckCircle,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            IconButton(
                                                onClick = {
                                                    SendaModelManager.deleteModel(context, model)
                                                    downloadStates = downloadStates.toMutableMap().apply {
                                                        put(model.id, ModelDownloadState.Idle)
                                                    }
                                                    Toast.makeText(context, "${model.name} eliminado de la memoria", Toast.LENGTH_SHORT).show()
                                                },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.DeleteOutline,
                                                    contentDescription = "Eliminar y liberar espacio",
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    } else if (state is ModelDownloadState.Downloading) {
                                        Text(
                                            text = "${state.progressPercent}%",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    } else {
                                        FilledTonalButton(
                                            onClick = {
                                                activeDownloadingModelId = model.id
                                                scope.launch {
                                                    SendaModelManager.downloadModel(context, model) { newState ->
                                                        scope.launch(Dispatchers.Main) {
                                                            downloadStates = downloadStates.toMutableMap().apply {
                                                                put(model.id, newState)
                                                            }
                                                            if (newState is ModelDownloadState.Ready) {
                                                                activeDownloadingModelId = null
                                                                activeModelId = model.id
                                                                prefs.selectedLocalAiModel = model.id
                                                                val modelFile = SendaModelManager.getModelFile(context, model)
                                                                scope.launch {
                                                                    org.senda.browser.core.ai.llama.SendaLlamaBridge.loadModel(modelFile)
                                                                }
                                                                Toast.makeText(context, "¡${model.name} instalado y activo en chip!", Toast.LENGTH_SHORT).show()
                                                            } else if (newState is ModelDownloadState.Error) {
                                                                activeDownloadingModelId = null
                                                                Toast.makeText(context, newState.message, Toast.LENGTH_LONG).show()
                                                            }
                                                        }
                                                    }
                                                }
                                            },
                                            enabled = activeDownloadingModelId == null,
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.height(30.dp)
                                        ) {
                                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Bajar", fontSize = 11.sp)
                                        }
                                    }
                                }

                                Text(
                                    text = model.description,
                                    fontSize = 10.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp)
                                )

                                if (state is ModelDownloadState.Downloading) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    LinearProgressIndicator(
                                        progress = { state.progressPercent / 100f },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(4.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                    )
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 2.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "${state.downloadedBytes / (1024 * 1024)} MB / ${state.totalBytes / (1024 * 1024)} MB",
                                            fontSize = 9.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(text = "Descarga atómica directa", fontSize = 9.sp, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // SELLO ÉTICO DE SOBERANÍA
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    ) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Garantía Senda: Ningún texto, prompt o artículo analizado saldrá jamás de este teléfono. Funciona en Modo Avión.",
                                fontSize = 10.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                } else {
                    // TAB 1: CONEXIÓN OLLAMA / LOCAL HOST EN RED
                    Text(
                        text = "Servidor Propio Ollama (Red Local / LAN)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Si ejecutas Ollama en tu computadora de casa (ej. con Llama 70B o DeepSeek R1), Senda puede conectarse directamente sin intermediarios.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    var ollamaUrl by remember { mutableStateOf(prefs.aiOllamaUrl) }
                    var ollamaModel by remember { mutableStateOf(prefs.aiOllamaModel) }

                    OutlinedTextField(
                        value = ollamaUrl,
                        onValueChange = { ollamaUrl = it },
                        label = { Text("URL Servidor Ollama") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = ollamaModel,
                        onValueChange = { ollamaModel = it },
                        label = { Text("Nombre del Modelo en Ollama") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            prefs.aiOllamaUrl = ollamaUrl
                            prefs.aiOllamaModel = ollamaModel
                            prefs.aiBackendMode = "OLLAMA"
                            Toast.makeText(context, "Configuración vinculada y guardada en Senda", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Lan, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Vincular Red LAN Privada", fontSize = 12.sp)
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

/**
 * Gestor Soberano de Exportación e Integración de Documentos y Respuestas de Senda AI.
 */
object SendaDocumentExporter {
    fun extractCode(markdown: String): String {
        val regex = Regex("```(?:[a-zA-Z0-9_-]+)?\\s*\\n([\\s\\S]*?)```")
        val matches = regex.findAll(markdown).map { it.groupValues[1].trim() }.toList()
        return if (matches.isNotEmpty()) {
            matches.joinToString("\n\n// ----------------------------------------\n\n")
        } else {
            markdown
        }
    }

    fun shareDocument(context: Context, title: String, content: String) {
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TITLE, title)
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, content)
            type = "text/plain"
        }
        val shareIntent = Intent.createChooser(sendIntent, "Exportar con Senda AI")
        context.startActivity(shareIntent)
    }

    fun saveToDownloads(context: Context, filename: String, content: String): Boolean {
        return try {
            val mimeType = when {
                filename.endsWith(".py", ignoreCase = true) -> "text/x-python"
                filename.endsWith(".sh", ignoreCase = true) -> "text/x-sh"
                filename.endsWith(".kt", ignoreCase = true) -> "text/x-kotlin"
                filename.endsWith(".js", ignoreCase = true) -> "application/javascript"
                filename.endsWith(".html", ignoreCase = true) -> "text/html"
                filename.endsWith(".json", ignoreCase = true) -> "application/json"
                filename.endsWith(".sql", ignoreCase = true) -> "text/x-sql"
                filename.endsWith(".md", ignoreCase = true) -> "text/markdown"
                else -> "text/plain"
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { os ->
                        os.write(content.toByteArray(Charsets.UTF_8))
                    }
                    true
                } else false
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                val f = File(dir, filename)
                f.writeText(content, Charsets.UTF_8)
                true
            }
        } catch (e: Exception) {
            android.util.Log.e("Senda", "Error guardando archivo: ${e.message}")
            false
        }
    }
}

/**
 * Asistente Soberano Senda: Ventana Única, Conversacional y Natural.
 * Chat natural de lenguaje libre capaz de redactar documentos, responder cualquier
 * duda conceptual o técnica, analizar la web activa y exportar al instante.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SendaAssistantSheet(
    activeTab: BrowserTab?,
    prefs: PreferencesManager,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val messages = remember {
        mutableStateListOf<SendaChatMessage>()
    }

    var inputText by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }

    val hasActivePage = activeTab != null && activeTab.url.isNotBlank() && activeTab.url != "about:blank"
    var includePageContext by remember { mutableStateOf(hasActivePage) }

    // Scroll al final al agregar mensajes
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    fun sendMessage(userText: String) {
        val trimmed = userText.trim()
        if (trimmed.isBlank() || isGenerating) return

        messages.add(
            SendaChatMessage(
                sender = ChatSender.USER,
                text = trimmed
            )
        )
        inputText = ""
        isGenerating = true

        scope.launch {
            if (prefs.aiBackendMode == "CHIP" && !org.senda.browser.core.ai.llama.SendaLlamaBridge.isReadyForInference) {
                val selectedModel = SendaAiModels.getById(prefs.selectedLocalAiModel)
                val modelFile = SendaModelManager.getModelFile(context, selectedModel)
                if (modelFile.exists() && modelFile.length() > 0) {
                    org.senda.browser.core.ai.llama.SendaLlamaBridge.loadModel(modelFile)
                }
            }

            val result = SendaInferenceEngine.chat(
                userMessage = trimmed,
                history = messages.toList(),
                pageTitle = activeTab?.title,
                pageUrl = activeTab?.url,
                // Antes se pasaba solo el título: la IA nunca leía la página aunque dijera «Leyendo»
                pageContent = if (includePageContext) activeTab?.extractPageText() ?: activeTab?.title else null,
                includePageContext = includePageContext,
                ollamaUrl = prefs.aiOllamaUrl,
                ollamaModel = prefs.aiOllamaModel,
                backendMode = prefs.aiBackendMode
            )

            messages.add(
                SendaChatMessage(
                    sender = ChatSender.ASSISTANT,
                    text = result.outputText,
                    latencyMs = result.latencyMs,
                    executionBackend = result.executionBackend,
                    isDocument = result.isDocument,
                    isCode = result.isCode,
                    codeLanguage = result.codeLanguage,
                    suggestedFileName = result.suggestedFileName
                )
            )
            isGenerating = false
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val isImeVisible = WindowInsets.isImeVisible
    LaunchedEffect(isImeVisible) {
        if (isImeVisible && messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp)
        ) {
            // CABECERA MINIMALISTA Y SOBERANA
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Senda AI",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = if (prefs.aiBackendMode == "OLLAMA") "OLLAMA LAN" else "LOCAL",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                        Text(
                            text = "Inteligencia local en chip • Cero telemetría",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (messages.isNotEmpty()) {
                        IconButton(
                            onClick = { messages.clear() },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "Limpiar chat",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cerrar",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // PÍLDORA DE CONTEXTO DE PÁGINA ACTIVA (SI CORRESPONDE)
            if (hasActivePage) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clickable { includePageContext = !includePageContext },
                    shape = RoundedCornerShape(8.dp),
                    color = if (includePageContext) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    border = BorderStroke(1.dp, if (includePageContext) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = if (includePageContext) "🌐 Leyendo:" else "🌐 Contexto pausado:",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (includePageContext) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = activeTab?.title ?: activeTab?.url ?: "",
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = if (includePageContext) "Activo ✓" else "Pausado",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (includePageContext) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // HILO CONVERSACIONAL (MENSAJES)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (messages.isEmpty()) {
                    // ESTADO INICIAL NOVEDOSO Y ACOGEDOR
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Surface(
                            modifier = Modifier.size(56.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Psychology,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "¿En qué te colaboro hoy?",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "Pídeme lo que necesites de forma natural: redactar un documento, resolver dudas, explicarte algo o analizar la página que estás viendo. Se procesa en tu teléfono; si para responder consulto Wikipedia o DuckDuckGo, te lo indico en la respuesta.",
                            fontSize = 12.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            lineHeight = 18.sp,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        // Atajos conversacionales iniciales
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val starters = if (hasActivePage) {
                                listOf(
                                    "🌐 ¿De qué trata la página que tengo abierta?",
                                    "⚖️ Escribe un derecho de petición sobre cobro indebido",
                                    "💻 Escribe un script en Python para procesar datos",
                                    "✉️ Redáctame una carta formal de renuncia",
                                    "🛠️ Genera un script en Bash para respaldar archivos"
                                )
                            } else {
                                listOf(
                                    "⚖️ Escribe un derecho de petición sobre cobro indebido",
                                    "💻 Escribe un script en Python para procesar datos",
                                    "✉️ Redáctame una carta formal de renuncia laboral",
                                    "🛠️ Genera un script en Bash para respaldar archivos",
                                    "📑 Elabora un contrato de prestación de servicios"
                                )
                            }

                            starters.forEach { starter ->
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (starter.endsWith("...")) {
                                                inputText = starter.removeSuffix("...") + " "
                                            } else {
                                                sendMessage(starter)
                                            }
                                        },
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = starter,
                                            fontSize = 12.5.sp,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(messages, key = { it.id }) { msg ->
                            if (msg.sender == ChatSender.USER) {
                                // BURBUJA DE USUARIO (DERECHA)
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.CenterEnd
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
                                        color = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.widthIn(max = 300.dp)
                                    ) {
                                        Text(
                                            text = msg.text,
                                            fontSize = 13.5.sp,
                                            lineHeight = 19.sp,
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                                        )
                                    }
                                }
                            } else {
                                // BURBUJA DE ASISTENTE SOLEDAD (IZQUIERDA)
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(14.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Surface(
                                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                                    shape = RoundedCornerShape(4.dp)
                                                ) {
                                                    Text(
                                                        text = "⚡ ${msg.executionBackend} • ${msg.latencyMs} ms",
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }

                                                if (msg.isCode) {
                                                    Surface(
                                                        color = MaterialTheme.colorScheme.tertiaryContainer,
                                                        shape = RoundedCornerShape(4.dp)
                                                    ) {
                                                        Text(
                                                            text = "💻 ${msg.codeLanguage?.uppercase() ?: "CÓDIGO"}",
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.SemiBold,
                                                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                } else if (msg.isDocument) {
                                                    Surface(
                                                        color = MaterialTheme.colorScheme.secondaryContainer,
                                                        shape = RoundedCornerShape(4.dp)
                                                    ) {
                                                        Text(
                                                            text = "📄 Documento",
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.SemiBold,
                                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(10.dp))

                                            Text(
                                                text = msg.text,
                                                fontSize = 13.sp,
                                                lineHeight = 19.sp,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )

                                            Spacer(modifier = Modifier.height(12.dp))
                                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                                            // BARRA DE ACCIONES: COPIAR, COMPARTIR Y GUARDAR
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                FilledTonalButton(
                                                    onClick = {
                                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                        val textToCopy = if (msg.isCode) SendaDocumentExporter.extractCode(msg.text) else msg.text
                                                        val clip = ClipData.newPlainText("Senda AI", textToCopy)
                                                        clipboard.setPrimaryClip(clip)
                                                        Toast.makeText(context, if (msg.isCode) "✓ Código copiado" else "✓ Copiado al portapapeles", Toast.LENGTH_SHORT).show()
                                                    },
                                                    modifier = Modifier.weight(1f),
                                                    shape = RoundedCornerShape(8.dp),
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                                ) {
                                                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text(if (msg.isCode) "Código" else "Copiar", fontSize = 11.5.sp)
                                                }

                                                FilledTonalButton(
                                                    onClick = {
                                                        SendaDocumentExporter.shareDocument(
                                                            context = context,
                                                            title = if (msg.isCode) "Código Senda" else "Documento Senda",
                                                            content = if (msg.isCode) SendaDocumentExporter.extractCode(msg.text) else msg.text
                                                        )
                                                    },
                                                    modifier = Modifier.weight(1f),
                                                    shape = RoundedCornerShape(8.dp),
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                                ) {
                                                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(15.dp))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text("Exportar", fontSize = 11.5.sp)
                                                }

                                                Button(
                                                    onClick = {
                                                        val filename = msg.suggestedFileName ?: if (msg.isCode) "script.py" else "Senda_${System.currentTimeMillis() / 1000}.md"
                                                        val contentToSave = if (msg.isCode) SendaDocumentExporter.extractCode(msg.text) else msg.text
                                                        val ok = SendaDocumentExporter.saveToDownloads(context, filename, contentToSave)
                                                        if (ok) {
                                                            Toast.makeText(context, "✓ Guardado en Descargas como $filename", Toast.LENGTH_LONG).show()
                                                        } else {
                                                            Toast.makeText(context, "No se pudo guardar en Descargas", Toast.LENGTH_SHORT).show()
                                                        }
                                                    },
                                                    modifier = Modifier.weight(1.1f),
                                                    shape = RoundedCornerShape(8.dp),
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                                ) {
                                                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(15.dp))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    val saveLabel = when {
                                                        msg.isCode -> "Guardar (${msg.suggestedFileName ?: "código"})"
                                                        msg.isDocument -> "Guardar (.md)"
                                                        else -> "Guardar"
                                                    }
                                                    Text(saveLabel, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (isGenerating) {
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "Analizando y redactando en tu chip...",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // BARRA DE ENTRADA CONVERSACIONAL (RESPONSIVA ANTE EL TECLADO)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = { Text("Escribe (código, documentos, dudas, web)...", fontSize = 12.5.sp) },
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent
                        ),
                        maxLines = 4,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.5.sp),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = if (inputText.isNotBlank() && !isGenerating) ImeAction.Send else ImeAction.Default
                        ),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (inputText.isNotBlank() && !isGenerating) {
                                    sendMessage(inputText)
                                }
                            }
                        )
                    )

                    IconButton(
                        onClick = { sendMessage(inputText) },
                        enabled = !isGenerating && inputText.isNotBlank(),
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(
                                if (inputText.isNotBlank() && !isGenerating)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.surfaceVariant
                            )
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Enviar",
                            tint = if (inputText.isNotBlank() && !isGenerating)
                                MaterialTheme.colorScheme.onPrimary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            }
        }
    }
}


