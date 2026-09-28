package org.senda.browser.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.senda.browser.ui.model.BrowserTab
import org.senda.browser.ui.theme.SendaColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevToolsSheet(
    activeTab: BrowserTab?,
    onDismiss: () -> Unit
) {
    var jsCode by remember { mutableStateOf("") }
    val consoleLogs = remember { mutableStateListOf("Senda DevTools v0.1 inicializada.", "Pestaña activa: ${activeTab?.url ?: "ninguna"}") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f)
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Consola Web & Inspector Móvil",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cerrar",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // Información rápida de auditoría
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "URL: ${activeTab?.url}",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = SendaColors.TextSecondary
                    )
                    Text(
                        text = "Rastreadores interceptados: ${activeTab?.trackersBlocked ?: 0}",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Historial de consola
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black)
                    .border(1.dp, SendaColors.BorderSubtle, RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                items(consoleLogs) { log ->
                    Text(
                        text = "> $log",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (log.startsWith("Error")) SendaColors.PanicFireRed else Color(0xFF00FF66)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Campo de ejecución de JavaScript
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.background)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    BasicTextField(
                        value = jsCode,
                        onValueChange = { jsCode = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (jsCode.isNotBlank() && activeTab != null) {
                                executeJs(activeTab, jsCode, consoleLogs)
                                jsCode = ""
                            }
                        }),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (jsCode.isEmpty()) {
                        Text(
                            text = "ej. document.title o alert(1)",
                            fontSize = 12.sp,
                            color = SendaColors.TextSecondary,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = {
                        if (jsCode.isNotBlank() && activeTab != null) {
                            executeJs(activeTab, jsCode, consoleLogs)
                            jsCode = ""
                        }
                    },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primary)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Ejecutar",
                        tint = Color.Black
                    )
                }
            }
        }
    }
}

private fun executeJs(tab: BrowserTab, code: String, logs: MutableList<String>) {
    try {
        logs.add(code)
        // Inyección directa de JavaScript en la pestaña activa
        tab.session.loadUri("javascript:(function(){ try { let r = eval(${escapeForJs(code)}); console.log(r); } catch(e){ console.error(e); } })();")
        logs.add("Código inyectado con éxito.")
    } catch (e: Exception) {
        logs.add("Error al inyectar: ${e.message}")
    }
}

private fun escapeForJs(code: String): String {
    return "\"" + code.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""
}
