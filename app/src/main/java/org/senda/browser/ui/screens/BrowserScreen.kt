package org.senda.browser.ui.screens

import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.mozilla.geckoview.GeckoView
import org.senda.browser.R
import org.senda.browser.core.PreferencesManager
import org.senda.browser.core.ToolbarPosition
import org.senda.browser.ui.components.DevToolsSheet
import org.senda.browser.ui.components.SendaToolbar
import org.senda.browser.ui.components.TabsSheet
import org.senda.browser.ui.model.BrowserTab
import org.senda.browser.ui.theme.SendaColors

@Composable
fun BrowserScreen(
    prefs: PreferencesManager,
    tabs: MutableList<BrowserTab>,
    activeTab: BrowserTab?,
    onNewTab: (String) -> Unit,
    onCloseTab: (BrowserTab) -> Unit,
    onSelectTab: (BrowserTab) -> Unit,
    onCloseAllTabs: () -> Unit,
    onOpenSettings: () -> Unit,
    onSettingsChanged: () -> Unit
) {
    var showTabsSheet by remember { mutableStateOf(false) }
    var showDevToolsSheet by remember { mutableStateOf(false) }
    var showFireConfirmDialog by remember { mutableStateOf(false) }

    val toolbarPos = prefs.toolbarPosition
    val showFire = prefs.showFireButton
    val showDevTools = prefs.showDevToolsButton

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (toolbarPos == ToolbarPosition.BOTTOM || toolbarPos == ToolbarPosition.FLOATING) {
                SendaToolbar(
                    activeTab = activeTab,
                    tabsCount = tabs.size,
                    position = toolbarPos,
                    showFireButton = showFire,
                    showDevToolsButton = showDevTools,
                    onNavigate = { url -> activeTab?.loadUri(url) },
                    onBack = { activeTab?.session?.goBack() },
                    onForward = { activeTab?.session?.goForward() },
                    onRefresh = { activeTab?.session?.reload() },
                    onDissolveCurrentTab = { showFireConfirmDialog = true },
                    onOpenTabsOverview = { showTabsSheet = true },
                    onOpenDevTools = { showDevToolsSheet = true },
                    onOpenSettings = onOpenSettings
                )
            }
        },
        topBar = {
            if (toolbarPos == ToolbarPosition.TOP) {
                SendaToolbar(
                    activeTab = activeTab,
                    tabsCount = tabs.size,
                    position = toolbarPos,
                    showFireButton = showFire,
                    showDevToolsButton = showDevTools,
                    onNavigate = { url -> activeTab?.loadUri(url) },
                    onBack = { activeTab?.session?.goBack() },
                    onForward = { activeTab?.session?.goForward() },
                    onRefresh = { activeTab?.session?.reload() },
                    onDissolveCurrentTab = { showFireConfirmDialog = true },
                    onOpenTabsOverview = { showTabsSheet = true },
                    onOpenDevTools = { showDevToolsSheet = true },
                    onOpenSettings = onOpenSettings
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (activeTab != null) {
                // Contenedor GeckoView para renderizado web con Gecko
                AndroidView(
                    factory = { context ->
                        GeckoView(context).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            setSession(activeTab.session)
                        }
                    },
                    update = { view ->
                        view.setSession(activeTab.session)
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Lienzo Zen visible solo cuando la pestaña esté vacía
            if (activeTab == null || activeTab.url == "about:blank" || activeTab.url.isBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    NewTabZenView(
                        onSearch = { query ->
                            if (activeTab != null) {
                                activeTab.loadUri(query)
                            } else {
                                onNewTab(query)
                            }
                        }
                    )
                }
            }
        }

        // Hoja de gestión de pestañas
        if (showTabsSheet) {
            TabsSheet(
                tabs = tabs,
                activeTabId = activeTab?.id ?: "",
                onSelectTab = onSelectTab,
                onCloseTab = onCloseTab,
                onNewTab = {
                    onNewTab("about:blank")
                    showTabsSheet = false
                },
                onCloseAll = {
                    onCloseAllTabs()
                    showTabsSheet = false
                },
                onDismiss = { showTabsSheet = false }
            )
        }

        // Hoja de DevTools nativo
        if (showDevToolsSheet) {
            DevToolsSheet(
                activeTab = activeTab,
                onDismiss = { showDevToolsSheet = false }
            )
        }

        // Diálogo de incineración de pestaña
        if (showFireConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showFireConfirmDialog = false },
                title = { Text("¿Disolver sesión efímera?") },
                text = { Text("Se cerrará la pestaña actual y se purgará su caché y cookies de la memoria RAM inmediatamente.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showFireConfirmDialog = false
                            if (activeTab != null) {
                                onCloseTab(activeTab)
                            }
                        }
                    ) {
                        Text("Disolver", color = SendaColors.PanicFireRed)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showFireConfirmDialog = false }) {
                        Text("Cancelar")
                    }
                },
                containerColor = MaterialTheme.colorScheme.surface
            )
        }
    }
}

@Composable
fun NewTabZenView(onSearch: (String) -> Unit) {
    var queryText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Logo de Senda
        Image(
            painter = painterResource(id = R.drawable.ic_senda_logo),
            contentDescription = "Senda Logo",
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "SENDA",
            style = MaterialTheme.typography.headlineMedium,
            letterSpacing = 4.sp,
            color = MaterialTheme.colorScheme.primary
        )

        Text(
            text = "Navega tu propio camino. Sin rastros.",
            fontSize = 13.sp,
            color = SendaColors.TextSecondary
        )

        Spacer(modifier = Modifier.height(28.dp))

        // Barra de búsqueda central
        OutlinedTextField(
            value = queryText,
            onValueChange = { queryText = it },
            placeholder = { Text("Buscar o escribir dirección web…", color = SendaColors.TextSecondary, fontSize = 14.sp) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Buscar",
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            trailingIcon = {
                if (queryText.isNotBlank()) {
                    IconButton(onClick = { onSearch(queryText) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Ir",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                imeAction = androidx.compose.ui.text.input.ImeAction.Search
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onSearch = {
                    if (queryText.isNotBlank()) {
                        onSearch(queryText)
                    }
                }
            ),
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline
            ),
            modifier = Modifier.fillMaxWidth(0.95f)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Accesos directos locales éticos
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            EthicalQuickLink(title = "DuckDuckGo", url = "https://duckduckgo.com", onClick = onSearch)
            EthicalQuickLink(title = "Wikipedia", url = "https://wikipedia.org", onClick = onSearch)
            EthicalQuickLink(title = "F-Droid", url = "https://f-droid.org", onClick = onSearch)
        }
    }
}

@Composable
fun EthicalQuickLink(title: String, url: String, onClick: (String) -> Unit) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, SendaColors.BorderSubtle, RoundedCornerShape(12.dp))
            .clickable { onClick(url) },
        color = MaterialTheme.colorScheme.surface
    ) {
        Text(
            text = title,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
