package org.senda.browser.ui.screens

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.mozilla.geckoview.GeckoView
import org.senda.browser.core.LocalSendaStrings
import org.senda.browser.core.PreferencesManager
import org.senda.browser.core.ToolbarPosition
import org.senda.browser.ui.components.BookmarksManagerDialog
import org.senda.browser.ui.components.CastDialog
import org.senda.browser.ui.components.TvVideoPlayer
import org.senda.browser.ui.components.ClearBrowsingDataDialog
import org.senda.browser.ui.components.DevToolsSheet
import org.senda.browser.ui.components.DownloadsManagerDialog
import org.senda.browser.ui.components.HistoryManagerDialog
import org.senda.browser.ui.components.NewTabZenView
import org.senda.browser.ui.components.SendaAssistantSheet
import org.senda.browser.ui.components.BookmarksBar
import org.senda.browser.ui.components.SendaPromptHost
import org.senda.browser.ui.components.SendaToolbar
import org.senda.browser.ui.components.SovereignSyncDialog
import org.senda.browser.ui.components.TabsOverview
import org.senda.browser.ui.model.BrowserTab
import org.senda.browser.ui.model.SendaPrompt
import org.senda.browser.ui.theme.SendaColors

@Composable
fun BrowserScreen(
    prefs: PreferencesManager,
    tabs: MutableList<BrowserTab>,
    activeTab: BrowserTab?,
    onNewTab: (String) -> Unit,
    onNewPrivateTab: (String) -> Unit = { onNewTab(it) },
    onCloseTab: (BrowserTab) -> Unit,
    onSelectTab: (BrowserTab) -> Unit,
    onCloseAllTabs: () -> Unit,
    onOpenSettings: () -> Unit,
    onSettingsChanged: () -> Unit
) {
    val context = LocalContext.current
    val strings = LocalSendaStrings.current

    var showTabsSheet by remember { mutableStateOf(false) }
    var showDevToolsSheet by remember { mutableStateOf(false) }
    var showFireConfirmDialog by remember { mutableStateOf(false) }
    var showCastDialog by remember { mutableStateOf(false) }
    var showBookmarksDialog by remember { mutableStateOf(false) }
    var showHistoryDialog by remember { mutableStateOf(false) }
    var showClearDataDialog by remember { mutableStateOf(false) }
    var showDownloadsDialog by remember { mutableStateOf(false) }
    var showSyncDialog by remember { mutableStateOf(false) }
    var showAiAssistantSheet by remember { mutableStateOf(false) }
    var isAddressBarEditing by remember { mutableStateOf(false) }
    var showFindBar by remember { mutableStateOf(false) }

    // Miniaturas de la vista de pestañas: se capturan de la vista web visible, se reducen y quedan solo en memoria
    var geckoView by remember { mutableStateOf<GeckoView?>(null) }
    fun captureThumbnail() {
        val view = geckoView ?: return
        val tab = activeTab ?: return
        if (tab.url.isBlank() || tab.url == "about:blank" || tab.isCrashed) {
            tab.thumbnail = null
            return
        }
        try {
            view.capturePixels().accept({ bitmap ->
                if (bitmap == null || bitmap.width == 0 || bitmap.height == 0) return@accept
                // La vista ya puede mostrar otra pestaña cuando llega la captura
                if (view.session !== tab.session) {
                    bitmap.recycle()
                    return@accept
                }
                val width = 360
                val height = (bitmap.height * width / bitmap.width).coerceAtMost(width * 2)
                val scaled = android.graphics.Bitmap.createScaledBitmap(bitmap, width, (bitmap.height * width / bitmap.width), true)
                val cropped = if (scaled.height > height) android.graphics.Bitmap.createBitmap(scaled, 0, 0, width, height) else scaled
                if (cropped !== scaled) scaled.recycle()
                if (scaled !== bitmap) bitmap.recycle()
                tab.thumbnail = cropped
            }, { })
        } catch (_: Exception) {}
    }
    val openTabsOverview = {
        captureThumbnail()
        showTabsSheet = true
    }
    // Fallo de la traducción (sin red para bajar el modelo, idioma no admitido…)
    LaunchedEffect(activeTab?.translationError) {
        if (activeTab?.translationError != null) {
            android.widget.Toast.makeText(context, strings.translate_failed, android.widget.Toast.LENGTH_LONG).show()
        }
    }
    // Al terminar de cargar una página, actualizar su miniatura
    LaunchedEffect(activeTab?.id, activeTab?.isLoading, activeTab?.url) {
        if (activeTab != null && !activeTab.isLoading) {
            kotlinx.coroutines.delay(800)
            captureThumbnail()
        }
    }
    // Al cambiar de pestaña la búsqueda anterior ya no aplica
    LaunchedEffect(activeTab?.id) { showFindBar = false }

    // Prioridad 1: Si hay un video o elemento web en pantalla completa, salir de pantalla completa
    BackHandler(enabled = activeTab?.isFullScreen == true) {
        activeTab?.exitFullScreen()
    }

    // Prioridad 2: Interceptar gesto/botón Atrás cuando la barra de direcciones está en edición para cerrarla
    BackHandler(enabled = activeTab?.isFullScreen != true && isAddressBarEditing) {
        isAddressBarEditing = false
    }

    // Prioridad 3: Interceptar botón atrás si la pestaña actual puede retroceder en su historial web
    BackHandler(enabled = activeTab?.isFullScreen != true && !isAddressBarEditing && activeTab?.canGoBack == true) {
        activeTab?.goBack()
    }

    // Prioridad 4: pestaña abierta desde un enlace y sin historial propio: cerrarla y volver a la de origen
    val parentTab = activeTab?.parentTabId?.let { id -> tabs.firstOrNull { it.id == id } }
    BackHandler(enabled = activeTab?.isFullScreen != true && !isAddressBarEditing && activeTab?.canGoBack != true && parentTab != null) {
        val child = activeTab ?: return@BackHandler
        val parent = parentTab ?: return@BackHandler
        onSelectTab(parent)
        onCloseTab(child)
    }

    val toolbarPos = prefs.toolbarPosition
    val showFire = prefs.showFireButton
    val showDevTools = prefs.showDevToolsButton
    val showCast = prefs.showCastButton
    val isFullWidth = prefs.toolbarFullWidth
    val showBookmarksBar = prefs.showBookmarksBar
    // Pantalla completa inmersiva solo cuando el usuario la activa en la pestaña
    val isFullScreen = activeTab?.isFullScreen == true

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                if (!isFullScreen && !showTabsSheet && (toolbarPos == ToolbarPosition.BOTTOM || toolbarPos == ToolbarPosition.FLOATING)) {
                    SendaToolbar(
                        activeTab = activeTab,
                        tabsCount = tabs.size,
                        position = toolbarPos,
                        showDevToolsButton = showDevTools,
                        showCastButton = showCast,
                        isFullWidth = isFullWidth,
                        prefs = prefs,
                        isEditing = isAddressBarEditing,
                        onEditingChange = { isAddressBarEditing = it },
                        onNavigate = { url -> activeTab?.loadUri(url) },
                        onBack = { activeTab?.goBack() },
                        onForward = { activeTab?.goForward() },
                        onRefresh = { activeTab?.reload() },
                        onOpenTabsOverview = openTabsOverview,
                        onOpenDevTools = { showDevToolsSheet = true },
                        onOpenSettings = onOpenSettings,
                        onOpenCast = { showCastDialog = true },
                        onOpenBookmarks = { showBookmarksDialog = true },
                        onOpenHistory = { showHistoryDialog = true },
                        onOpenDownloads = { showDownloadsDialog = true },
                        onFindInPage = { showFindBar = true },
                        onOpenSync = { showSyncDialog = true },
                        onOpenAiAssistant = { showAiAssistantSheet = true },
                        onGoHome = { activeTab?.loadUri("about:blank") },
                        onSwitchNextTab = {
                            val idx = tabs.indexOf(activeTab)
                            if (tabs.size > 1 && idx in 0 until tabs.size - 1) {
                                onSelectTab(tabs[idx + 1])
                            }
                        },
                        onSwitchPrevTab = {
                            val idx = tabs.indexOf(activeTab)
                            if (tabs.size > 1 && idx > 0) {
                                onSelectTab(tabs[idx - 1])
                            }
                        },
                        onNewTab = onNewTab,
                        onNewPrivateTab = { onNewPrivateTab("about:blank") },
                        onCloseCurrentTab = { if (activeTab != null) onCloseTab(activeTab) },
                        onCloseAllTabs = onCloseAllTabs
                    )
                }
            },
            contentWindowInsets = if (isFullScreen || showTabsSheet) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
            topBar = {
                Column {
                if (!isFullScreen && !showTabsSheet && toolbarPos == ToolbarPosition.TOP) {
                    SendaToolbar(
                        activeTab = activeTab,
                        tabsCount = tabs.size,
                        position = toolbarPos,
                        showDevToolsButton = showDevTools,
                        showCastButton = showCast,
                        isFullWidth = isFullWidth,
                        prefs = prefs,
                        isEditing = isAddressBarEditing,
                        onEditingChange = { isAddressBarEditing = it },
                        onNavigate = { url -> activeTab?.loadUri(url) },
                        onBack = { activeTab?.goBack() },
                        onForward = { activeTab?.goForward() },
                        onRefresh = { activeTab?.reload() },
                        onOpenTabsOverview = openTabsOverview,
                        onOpenDevTools = { showDevToolsSheet = true },
                        onOpenSettings = onOpenSettings,
                        onOpenCast = { showCastDialog = true },
                        onOpenBookmarks = { showBookmarksDialog = true },
                        onOpenHistory = { showHistoryDialog = true },
                        onOpenDownloads = { showDownloadsDialog = true },
                        onFindInPage = { showFindBar = true },
                        onOpenSync = { showSyncDialog = true },
                        onOpenAiAssistant = { showAiAssistantSheet = true },
                        onGoHome = { activeTab?.loadUri("about:blank") },
                        onSwitchNextTab = {
                            val idx = tabs.indexOf(activeTab)
                            if (tabs.size > 1 && idx in 0 until tabs.size - 1) {
                                onSelectTab(tabs[idx + 1])
                            }
                        },
                        onSwitchPrevTab = {
                            val idx = tabs.indexOf(activeTab)
                            if (tabs.size > 1 && idx > 0) {
                                onSelectTab(tabs[idx - 1])
                            }
                        },
                        onNewTab = onNewTab,
                        onNewPrivateTab = { onNewPrivateTab("about:blank") },
                        onCloseCurrentTab = { if (activeTab != null) onCloseTab(activeTab) },
                        onCloseAllTabs = onCloseAllTabs
                    )
                }
                // Barra de favoritos: bajo la barra superior, o arriba del todo si la barra va abajo
                if (showBookmarksBar && !isFullScreen && !showTabsSheet && !isAddressBarEditing) {
                    BookmarksBar(
                        prefs = prefs,
                        currentUrl = activeTab?.url.orEmpty(),
                        currentTitle = activeTab?.title.orEmpty(),
                        onNavigate = { url -> activeTab?.loadUri(url) ?: onNewTab(url) },
                        onOpenManager = { showBookmarksDialog = true },
                        modifier = if (toolbarPos == ToolbarPosition.TOP) Modifier else Modifier.statusBarsPadding()
                    )
                }
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(if (isFullScreen || showTabsSheet) PaddingValues(0.dp) else padding)
            ) {
                if (activeTab != null) {
                    if (activeTab.isCrashed) {
                        TabCrashedView(
                            tab = activeTab,
                            onRestore = { activeTab.restoreSession() }
                        )
                    } else {
                        // Contenedor GeckoView para renderizado web con Gecko.
                        // En pantalla completa horizontal con "Llenar pantalla", la vista se hace 16:9 a lo ancho
                        // (más alta que la pantalla 20:9 y recortada arriba/abajo), así el video ocupa toda la pantalla
                        // sin franjas laterales y sin tocar el contenido de la página.
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clipToBounds(),
                            contentAlignment = Alignment.Center
                        ) {
                            AndroidView(
                                factory = { context ->
                                    GeckoView(context).apply {
                                        layoutParams = ViewGroup.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.MATCH_PARENT
                                        )
                                        setSession(activeTab.session)
                                    }.also { geckoView = it }
                                },
                                update = { view ->
                                    view.setSession(activeTab.session)
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }

                if (showFindBar && activeTab != null) {
                    org.senda.browser.ui.components.FindInPageBar(
                        tab = activeTab,
                        onClose = { showFindBar = false },
                        modifier = Modifier.align(Alignment.TopCenter)
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
                            prefs = prefs,
                            onSearch = { query ->
                                if (activeTab != null) {
                                    activeTab.loadUri(query)
                                } else {
                                    onNewTab(query)
                                }
                            },
                            onSettingsChanged = onSettingsChanged
                        )
                    }
                }

            }
        }

        // Hoja / Vista completa de gestión de pestañas
        if (showTabsSheet) {
            TabsOverview(
                tabs = tabs,
                activeTabId = activeTab?.id ?: "",
                tabsViewMode = prefs.tabsViewMode,
                prefs = prefs,
                onSelectTab = { tab ->
                    onSelectTab(tab)
                    showTabsSheet = false
                },
                onCloseTab = onCloseTab,
                onNewTab = {
                    onNewTab("about:blank")
                },
                onNewPrivateTab = {
                    onNewPrivateTab("about:blank")
                },
                onCloseAll = {
                    onCloseAllTabs()
                    showTabsSheet = false
                },
                onDismiss = { showTabsSheet = false },
                onSettingsChanged = onSettingsChanged
            )
        }

        // Hoja de DevTools nativo
        if (showDevToolsSheet) {
            DevToolsSheet(
                activeTab = activeTab,
                onDismiss = { showDevToolsSheet = false }
            )
        }

        // Hoja del Asistente Soberano Senda (IA en chip / Redacción / Consultas / Exportación)
        if (showAiAssistantSheet) {
            SendaAssistantSheet(
                activeTab = activeTab,
                prefs = prefs,
                onDismiss = { showAiAssistantSheet = false }
            )
        }

        // El reproductor de TV solo se activa si el usuario habilitó explícitamente el modo TV en horizontal en ajustes
        LaunchedEffect(org.senda.browser.core.cast.SendaTvMode.tvConnected, activeTab?.isMediaPlaying, activeTab?.url, prefs.tvModeEnabled, prefs.tvModeLandscape) {
            if (prefs.tvModeEnabled && prefs.tvModeLandscape) {
                org.senda.browser.core.cast.SendaTvPlayer.maybeStart(activeTab)
            }
        }
        if (prefs.tvModeEnabled && prefs.tvModeLandscape) {
            org.senda.browser.core.cast.SendaTvPlayer.playback?.let { playback ->
                TvVideoPlayer(playback)
            }
        }

        // Diálogo de transmisión con Chromecast
        if (showCastDialog) {
            CastDialog(
                activeTab = activeTab,
                onDismiss = { showCastDialog = false }
            )
        }

        // Diálogo gestor de favoritos / marcadores
        if (showBookmarksDialog) {
            BookmarksManagerDialog(
                prefs = prefs,
                currentUrl = activeTab?.url ?: "",
                currentTitle = activeTab?.title ?: "",
                onNavigate = { url -> activeTab?.loadUri(url) },
                onDismiss = { showBookmarksDialog = false }
            )
        }

        // Diálogo gestor de historial de navegación
        if (showHistoryDialog) {
            HistoryManagerDialog(
                prefs = prefs,
                onNavigate = { url -> activeTab?.loadUri(url) },
                onOpenClearData = { showClearDataDialog = true },
                onDismiss = { showHistoryDialog = false }
            )
        }

        // Diálogo de borrado de datos de navegación
        if (showClearDataDialog) {
            ClearBrowsingDataDialog(
                prefs = prefs,
                onDataCleared = {
                    onSettingsChanged()
                },
                onDismiss = { showClearDataDialog = false }
            )
        }

        // Diálogo gestor de descargas avanzadas
        if (showDownloadsDialog) {
            DownloadsManagerDialog(
                prefs = prefs,
                onDismiss = { showDownloadsDialog = false }
            )
        }

        // Diálogo de sincronización soberana (WebDAV + Netscape HTML)
        if (showSyncDialog) {
            SovereignSyncDialog(
                prefs = prefs,
                onDismiss = { showSyncDialog = false }
            )
        }

        // Anfitrión de diálogos interactivos web (Desplegables / Select, Alertas, Confirmaciones)
        if (activeTab?.activePrompt != null && activeTab.activePrompt !is SendaPrompt.File) {
            SendaPromptHost(
                prompt = activeTab.activePrompt,
                onDismiss = { activeTab.dismissActivePrompt() },
                tab = activeTab
            )
        }
    }
}


@Composable
private fun TabCrashedView(
    tab: BrowserTab,
    onRestore: () -> Unit
) {
    val strings = LocalSendaStrings.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .clip(RoundedCornerShape(20.dp)),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            ),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Healing,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Text(
                    text = strings.crash_sheet_title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = strings.crash_sheet_desc,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )

                Spacer(modifier = Modifier.height(6.dp))

                Button(
                    onClick = onRestore,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(0.85f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(strings.crash_sheet_restore, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
