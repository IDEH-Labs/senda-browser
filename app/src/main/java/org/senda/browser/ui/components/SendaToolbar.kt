package org.senda.browser.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.senda.browser.core.ToolbarPosition
import org.senda.browser.ui.model.BrowserTab
import org.senda.browser.ui.theme.SendaColors

@Composable
fun SendaToolbar(
    activeTab: BrowserTab?,
    tabsCount: Int,
    position: ToolbarPosition,
    showFireButton: Boolean,
    showDevToolsButton: Boolean,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onRefresh: () -> Unit,
    onDissolveCurrentTab: () -> Unit,
    onOpenTabsOverview: () -> Unit,
    onOpenDevTools: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    var isEditing by remember { mutableStateOf(false) }
    var inputUrl by remember(activeTab?.url) { mutableStateOf(activeTab?.url ?: "") }

    val isHomeTab = activeTab == null || activeTab.url.isBlank() || activeTab.url == "about:blank"
    val canGoBack = activeTab?.canGoBack == true

    val toolbarShape = when (position) {
        ToolbarPosition.FLOATING -> RoundedCornerShape(24.dp)
        ToolbarPosition.BOTTOM -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
        ToolbarPosition.TOP -> RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp)
    }

    val paddingModifier = when (position) {
        ToolbarPosition.FLOATING -> modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        else -> modifier
    }

    Surface(
        modifier = paddingModifier
            .fillMaxWidth()
            .clip(toolbarShape)
            .border(
                width = 0.8.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                shape = toolbarShape
            ),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Barra de progreso de carga suave
            if (activeTab?.isLoading == true) {
                LinearProgressIndicator(
                    progress = { (activeTab.progress / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.5.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.Transparent
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Botón Atrás animado: visible cuando hay historial previo
                AnimatedVisibility(
                    visible = canGoBack,
                    enter = fadeIn() + expandHorizontally(),
                    exit = fadeOut() + shrinkHorizontally()
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Atrás",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }

                // Píldora interactiva de URL / Búsqueda
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                        .border(
                            width = 0.8.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                            shape = RoundedCornerShape(20.dp)
                        )
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Ícono de estado: Búsqueda (en inicio), Escudo (con bloqueos) o Candado (seguro)
                        if (isHomeTab) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Búsqueda ética",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(end = 6.dp)
                                    .size(17.dp)
                            )
                        } else if (activeTab != null && activeTab.trackersBlocked > 0) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .padding(end = 6.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                                    .padding(horizontal = 5.dp, vertical = 2.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Shield,
                                    contentDescription = "Protegido",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "${activeTab.trackersBlocked}",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Conexión cifrada",
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                                modifier = Modifier
                                    .padding(end = 6.dp)
                                    .size(14.dp)
                            )
                        }

                        // Campo de texto de URL o consulta
                        BasicTextField(
                            value = if (isEditing) inputUrl else displayUrl(inputUrl),
                            onValueChange = { inputUrl = it },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 13.5.sp,
                                fontWeight = if (isHomeTab && !isEditing) FontWeight.Normal else FontWeight.Medium
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Go
                            ),
                            keyboardActions = KeyboardActions(
                                onGo = {
                                    focusManager.clearFocus()
                                    isEditing = false
                                    onNavigate(inputUrl)
                                }
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .onFocusChanged { focusState ->
                                    isEditing = focusState.isFocused
                                    if (focusState.isFocused) {
                                        inputUrl = if (activeTab?.url == "about:blank") "" else (activeTab?.url ?: "")
                                    }
                                }
                        )

                        // Botón de limpiar texto cuando se edita
                        if (isEditing && inputUrl.isNotEmpty()) {
                            IconButton(
                                onClick = { inputUrl = "" },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Limpiar",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    }
                }

                // Botón de DevTools (Inspección web en móvil)
                if (showDevToolsButton) {
                    IconButton(
                        onClick = onOpenDevTools,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Code,
                            contentDescription = "DevTools",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }

                // Botón de Fuego (Disolver pestaña / Sesión efímera)
                if (showFireButton) {
                    IconButton(
                        onClick = onDissolveCurrentTab,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocalFireDepartment,
                            contentDescription = "Disolver pestaña",
                            tint = SendaColors.PanicFireRed,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Botón contador de pestañas elegante
                IconButton(
                    onClick = onOpenTabsOverview,
                    modifier = Modifier.size(34.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .border(
                                width = 1.5.dp,
                                color = MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(6.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "$tabsCount",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // Menú / Ajustes
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Ajustes",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

private fun displayUrl(raw: String): String {
    if (raw.isBlank() || raw == "about:blank") return "Buscar o escribir URL…"
    return raw
        .removePrefix("https://")
        .removePrefix("http://")
        .removePrefix("www.")
        .take(35)
}
