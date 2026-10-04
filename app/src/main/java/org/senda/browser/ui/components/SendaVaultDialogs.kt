package org.senda.browser.ui.components

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
 * Diálogo principal de la Bóveda Soberana de Contraseñas de Senda.
 * Brinda control total de credenciales, generador criptográfico y auditoría forense en tiempo real.
 */
@Composable
fun SendaVaultDialog(
    prefs: PreferencesManager,
    strings: SendaStringPack,
    onDismiss: () -> Unit,
    onRequireBiometricAuth: ((Boolean) -> Unit) -> Unit
) {
    val context = LocalContext.current

    // Ejecuta una operación con la clave de la Bóveda. Si pasaron más de 30 s desde la última identificación,
    // el chip la rechaza: se pide huella o PIN y se reintenta una vez
    fun secure(action: () -> Unit) {
        fun explain(e: org.senda.browser.core.security.VaultUnavailableException) {
            val msg = when (e.reason) {
                org.senda.browser.core.security.VaultUnavailableException.Reason.NO_SCREEN_LOCK ->
                    "Configura un PIN, patrón o contraseña en el teléfono para usar la bóveda"
                org.senda.browser.core.security.VaultUnavailableException.Reason.KEY_INVALIDATED ->
                    "Se registró una huella nueva: por seguridad la clave de la bóveda quedó invalidada"
            }
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
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
                    Toast.makeText(context, "No se pudo usar la bóveda: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: org.senda.browser.core.security.VaultUnavailableException) {
            explain(e)
        } catch (e: Exception) {
            Toast.makeText(context, "No se pudo usar la bóveda: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    val coroutineScope = rememberCoroutineScope()

    var activeSubView by rememberSaveable { mutableStateOf("main") } // main, list, generator, audit, add
    var credentials by remember { mutableStateOf(SendaVaultManager.getCredentials(context)) }
    var searchQuery by remember { mutableStateOf("") }

    // Generador de claves
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

    // Auditoría & Estrés
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
                        "audit" -> "Auditoría Forense & Estrés"
                        "generator" -> "Generador de Claves Seguras"
                        "list" -> "Credenciales Guardadas (${credentials.size})"
                        "add" -> "Añadir Credencial"
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
                            // Chip de estado de hardware
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
                                            text = "Cifrado Hardware AES-256-GCM Activo",
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                        Text(
                                            text = "Custodiado en el silicio (Keystore / TEE)",
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // «Recordar inicios de sesión» y «Exigir huella para autocompletar» se quitaron: Senda aún
                            // no guarda ni rellena contraseñas en las páginas, y los interruptores no hacían nada
                            Text(
                                text = strings.dlg_passwords_info,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), thickness = 0.5.dp)

                            // Botón: Ver credenciales guardadas
                            OutlinedButton(
                                onClick = {
                                    onRequireBiometricAuth { success ->
                                        if (success) {
                                            credentials = SendaVaultManager.getCredentials(context)
                                            activeSubView = "list"
                                        } else {
                                            Toast.makeText(context, "Autenticación requerida para abrir la bóveda", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Ver Bóveda de Contraseñas (${credentials.size})", fontSize = 12.sp)
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Botón: Generador de claves
                            OutlinedButton(
                                onClick = { activeSubView = "generator" },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Generador de Claves Seguras", fontSize = 12.sp)
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Botón: Auditoría y Estrés
                            Button(
                                onClick = {
                                    activeSubView = "audit"
                                    if (auditReport == null) {
                                        isRunningAudit = true
                                        coroutineScope.launch(Dispatchers.Default) {
                                            val rep = SendaVaultAuditRunner.runFullAudit(context, stressCycles = 1000)
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
                                Text("Auditoría Criptográfica & Estrés", fontSize = 12.sp)
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Botón: Abrir autocompletado del sistema
                            TextButton(
                                onClick = {
                                    try {
                                        // Pantalla de Android para elegir gestor de contraseñas (Android 14+). La acción
                                        // anterior intentaba registrar a Senda como proveedor y no hacía nada útil
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
                                Text("Configurar Autocompletado del Sistema Android", fontSize = 11.sp)
                            }
                        }
                    }

                    "list" -> {
                        // Lista de credenciales
                        Column(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text("Buscar dominio o usuario...", fontSize = 12.sp) },
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
                                        text = if (credentials.isEmpty()) "No hay contraseñas guardadas aún." else "Sin coincidencias.",
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
                                            onDelete = {
                                                SendaVaultManager.deleteCredential(context, item.id)
                                                credentials = SendaVaultManager.getCredentials(context)
                                                Toast.makeText(context, "Credencial eliminada", Toast.LENGTH_SHORT).show()
                                            },
                                            runSecure = { action -> secure(action) }
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
                                Text("Añadir Nueva Cuenta", fontSize = 12.sp)
                            }
                        }
                    }

                    "add" -> {
                        // Formulario de añadir cuenta
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
                                label = { Text("Sitio web o Dominio (ej. github.com)", fontSize = 12.sp) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = addUser,
                                onValueChange = { addUser = it },
                                label = { Text("Usuario o Correo", fontSize = 12.sp) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = addPass,
                                onValueChange = { addPass = it },
                                label = { Text("Contraseña", fontSize = 12.sp) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                trailingIcon = {
                                    IconButton(onClick = {
                                        val (chars, _) = SendaVaultManager.generateStrongPassword(length = 18)
                                        addPass = String(chars)
                                        SendaVaultManager.wipe(chars)
                                    }) {
                                        Icon(Icons.Default.AutoFixHigh, contentDescription = "Generar", modifier = Modifier.size(18.dp))
                                    }
                                }
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { activeSubView = "list" }) {
                                    Text("Cancelar")
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
                                                Toast.makeText(context, "Credencial guardada de forma segura", Toast.LENGTH_SHORT).show()
                                            }
                                        } else {
                                            Toast.makeText(context, "Completa todos los campos", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Guardar Cifrado")
                                }
                            }
                        }
                    }

                    "generator" -> {
                        // Generador de claves
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
                                        text = "Entropía de Shannon: ${String.format(java.util.Locale.ROOT, "%.1f", generatedEntropy)} bits (Grado Militar)",
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
                                Text("Longitud: ${genLength.toInt()} caracteres", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                IconButton(onClick = { refreshGeneratedPassword() }) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Regenerar")
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
                                Text("Símbolos (!@#$)", fontSize = 11.sp)
                                Spacer(modifier = Modifier.width(12.dp))
                                Checkbox(checked = genDigits, onCheckedChange = { genDigits = it; refreshGeneratedPassword() })
                                Text("Números (0-9)", fontSize = 11.sp)
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Button(
                                onClick = {
                                    val chars = generatedPassword.toCharArray()
                                    SendaVaultManager.copyToClipboardSecurely(context, "Senda Password", chars)
                                    SendaVaultManager.wipe(chars)
                                    Toast.makeText(context, "Copiado al portapapeles (se borrará en 30s)", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Copiar al Portapapeles Seguro")
                            }
                        }
                    }

                    "audit" -> {
                        // Vista de Auditoría y Prueba de Estrés
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
                                    Text("Ejecutando 1.000 ciclos de estrés criptográfico...", fontSize = 12.sp)
                                    Text("Probando inviolabilidad de etiquetas y colisiones de Nonce", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                                    text = "Pruebas: ${rep.passedTests}/${rep.totalTests} superadas | Estrés: ${rep.totalStressCycles} ciclos | Latencia media: ${String.format(java.util.Locale.ROOT, "%.3f", rep.avgLatencyMs)} ms (${String.format(java.util.Locale.ROOT, "%.0f", rep.throughputOpsPerSec)} ops/seg)",
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
                Text(if (activeSubView != "main") "Volver" else strings.general_done)
            }
        }
    )
}

/**
 * Tarjeta individual de credencial con revelado seguro y portapapeles higiénico.
 */
@Composable
private fun CredentialItemCard(
    item: VaultCredential,
    onDelete: () -> Unit,
    runSecure: (() -> Unit) -> Unit
) {
    val context = LocalContext.current
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
                    // Revelar / Ocultar
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
                            contentDescription = "Revelar",
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    // Copiar al portapapeles seguro
                    IconButton(
                        onClick = {
                            runSecure {
                                val chars = SendaVaultManager.decryptPassword(item.encryptedPasswordBase64, item.ivBase64)
                                SendaVaultManager.copyToClipboardSecurely(context, item.domain, chars)
                                SendaVaultManager.wipe(chars)
                                Toast.makeText(context, "Copiado. Se autodestruirá en 30 segundos.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copiar", modifier = Modifier.size(16.dp))
                    }

                    // Borrar
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Eliminar", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
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
