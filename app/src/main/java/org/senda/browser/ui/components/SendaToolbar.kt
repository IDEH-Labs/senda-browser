package org.senda.browser.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
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

    val toolbarShape = when (position) {
        ToolbarPosition.FLOATING -> RoundedCornerShape(24.dp)
        ToolbarPosition.BOTTOM -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
        ToolbarPosition.TOP -> RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
    }

    val paddingModifier = if (position == ToolbarPosition.FLOATING) {
        modifier.padding(horizontal = 12.dp, vertical = 8.dp)
    } else {
        modifier
    }

    Surface(
        modifier = paddingModifier
            .fillMaxWidth()
            .clip(toolbarShape)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), toolbarShape),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Barra de progreso de carga ultra-delgada
            if (activeTab?.isLoading == true) {
                LinearProgressIndicator(
                    progress = { (activeTab.progress / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.Transparent
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Botón Atrás
                IconButton(
                    onClick = onBack,
                    enabled = activeTab?.canGoBack == true,
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Atrás",
                        tint = if (activeTab?.canGoBack == true) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                    )
                }

                // Campo de URL / Búsqueda
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.background)
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Indicador de privacidad (Escudo + contador de rastreadores)
                        if (activeTab != null && activeTab.trackersBlocked > 0) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .padding(end = 6.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Shield,
                                    contentDescription = "Protegido",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "${activeTab.trackersBlocked}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Seguro",
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                modifier = Modifier
                                    .padding(end = 6.dp)
                                    .size(14.dp)
                            )
                        }

                        BasicTextField(
                            value = if (isEditing) inputUrl else displayUrl(inputUrl),
                            onValueChange = {
                                inputUrl = it
                            },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = MaterialTheme.colorScheme.onBackground,
                                fontSize = 14.sp
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
                                        inputUrl = activeTab?.url ?: ""
                                    }
                                }
                        )

                        if (isEditing && inputUrl.isNotEmpty()) {
                            IconButton(
                                onClick = { inputUrl = "" },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Limpiar",
                                    tint = SendaColors.TextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                // Botón de DevTools (Inspección web en móvil)
                if (showDevToolsButton) {
                    IconButton(
                        onClick = onOpenDevTools,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Code,
                            contentDescription = "DevTools",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Botón de Fuego (Disolver pestaña / Sesión efímera)
                if (showFireButton) {
                    IconButton(
                        onClick = onDissolveCurrentTab,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocalFireDepartment,
                            contentDescription = "Disolver pestaña",
                            tint = SendaColors.PanicFireRed,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                // Botón contador de pestañas
                IconButton(
                    onClick = onOpenTabsOverview,
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(5.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "$tabsCount",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // Menú / Ajustes
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Ajustes",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

private fun displayUrl(raw: String): String {
    if (raw.isBlank() || raw == "about:blank") return "Escribe una dirección web o busca…"
    return raw
        .removePrefix("https://")
        .removePrefix("http://")
        .removePrefix("www.")
        .take(40)
}
