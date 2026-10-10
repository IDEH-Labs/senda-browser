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
import kotlinx.coroutines.launch
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
import org.senda.browser.ui.components.ClearBrowsingDataDialog
import org.senda.browser.ui.components.DevToolsSheet
import org.senda.browser.ui.components.DownloadsManagerDialog
import org.senda.browser.ui.components.HistoryManagerDialog
import org.senda.browser.ui.components.NewTabZenView
import org.senda.browser.ui.components.BookmarksBar
import org.senda.browser.ui.components.SendaPromptHost
import org.senda.browser.ui.components.SendaToolbar
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
    var showCastDialog by remember { mutableStateOf(false) }
    var showBookmarksDialog by remember { mutableStateOf(false) }
    var showHistoryDialog by remember { mutableStateOf(false) }
    var showClearDataDialog by remember { mutableStateOf(false) }
    var showDownloadsDialog by remember { mutableStateOf(false) }
    var isAddressBarEditing by remember { mutableStateOf(false) }
    var showFindBar by remember { mutableStateOf(false) }

    // Tab view thumbnails: captured from the visible web view, reduced and kept in memory only
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
                // The view may already be showing another tab when the capture arrives
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
    // Translation failure (no network to download the model, unsupported language…)
    LaunchedEffect(activeTab?.translationError) {
        if (activeTab?.translationError != null) {
            android.widget.Toast.makeText(context, strings.translate_failed, android.widget.Toast.LENGTH_LONG).show()
        }
    }
    // When a page finishes loading, update its thumbnail
    LaunchedEffect(activeTab?.id, activeTab?.isLoading, activeTab?.url) {
        if (activeTab != null && !activeTab.isLoading) {
            kotlinx.coroutines.delay(800)
            captureThumbnail()
        }
    }
    // When switching tabs the previous search no longer applies
    LaunchedEffect(activeTab?.id) { showFindBar = false }

    // Priority 1: if a video or web element is in full screen, exit full screen
    BackHandler(enabled = activeTab?.isFullScreen == true) {
        activeTab?.exitFullScreen()
    }

    // Priority 2: intercept the Back gesture/button while the address bar is being edited, to close it
    BackHandler(enabled = activeTab?.isFullScreen != true && isAddressBarEditing) {
        isAddressBarEditing = false
    }

    // Priority 3: intercept the back button if the current tab can go back in its web history
    // Emergency exit: a page that puts itself in front again when going back (redirect or pushState)
    // left Senda unable to close with Back. If 3 presses in a row do not go back in the tab's
    // history, Back sends Senda to the background. Going back quickly through real pages does lower the position
    var backTrapTabId by remember { mutableStateOf<String?>(null) }
    var backTrapIndex by remember { mutableIntStateOf(-1) }
    var backTrapCount by remember { mutableIntStateOf(0) }
    var backTrapAt by remember { mutableLongStateOf(0L) }
    BackHandler(enabled = activeTab?.isFullScreen != true && !isAddressBarEditing && activeTab?.canGoBack == true) {
        val tab = activeTab ?: return@BackHandler
        val now = android.os.SystemClock.elapsedRealtime()
        val index = tab.historyIndex
        if (index >= 0 && tab.id == backTrapTabId && index >= backTrapIndex && now - backTrapAt < 2500L) {
            backTrapCount++
        } else {
            backTrapTabId = tab.id
            backTrapIndex = index
            backTrapCount = 1
        }
        backTrapAt = now
        if (backTrapCount >= 3) {
            android.util.Log.w("SendaBack", "Atrás atrapado en ${tab.url}: Senda pasa a segundo plano")
            backTrapTabId = null
            backTrapCount = 0
            generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
                .filterIsInstance<android.app.Activity>().firstOrNull()?.moveTaskToBack(true)
            return@BackHandler
        }
        tab.goBack()
    }

    // Priority 4: tab opened from a link and with no history of its own: close it and return to the original one
    val parentTab = activeTab?.parentTabId?.let { id -> tabs.firstOrNull { it.id == id } }
    BackHandler(enabled = activeTab?.isFullScreen != true && !isAddressBarEditing && activeTab?.canGoBack != true && parentTab != null) {
        val child = activeTab ?: return@BackHandler
        val parent = parentTab ?: return@BackHandler
        onSelectTab(parent)
        onCloseTab(child)
    }

    // A single feature: without a TV, the button opens Android's panel to pick it (only the system can connect);
    // with a TV, its controls. Videos move to the TV on their own
    val openCast: () -> Unit = {
        if (org.senda.browser.core.cast.SendaTvMode.tvConnected) {
            showCastDialog = true
        } else if (prefs.tvModeEnabled) {
            fun startMirroring() {
                org.senda.browser.core.cast.SendaTvMode.evaluate()
                // Keep Android from freezing Senda while the TV is being picked: TV mode is applied on connecting
                org.senda.browser.core.cast.SendaTvMode.awaitTv()
                org.senda.browser.ui.components.CastHelper.openSystemCast(context)
            }
            // Without notification permission Android hides the TV mode notification and its "Normal screen" button
            val requester = org.senda.browser.ui.model.BrowserTab.androidPermissionRequester
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU && requester != null) {
                requester(listOf(android.Manifest.permission.POST_NOTIFICATIONS)) { startMirroring() }
            } else {
                startMirroring()
            }
        } else {
            org.senda.browser.ui.components.CastHelper.openSystemCast(context)
        }
    }

    val toolbarPos = prefs.toolbarPosition
    val showDevTools = prefs.showDevToolsButton
    val showCast = prefs.showCastButton
    val isFullWidth = prefs.toolbarFullWidth
    val showBookmarksBar = prefs.showBookmarksBar
    // Immersive full screen only when the user turns it on in the tab
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
                        onOpenCast = openCast,
                        onOpenBookmarks = { showBookmarksDialog = true },
                        onOpenHistory = { showHistoryDialog = true },
                        onOpenDownloads = { showDownloadsDialog = true },
                        onFindInPage = { showFindBar = true },
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
                        onOpenCast = openCast,
                        onOpenBookmarks = { showBookmarksDialog = true },
                        onOpenHistory = { showHistoryDialog = true },
                        onOpenDownloads = { showDownloadsDialog = true },
                        onFindInPage = { showFindBar = true },
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
                // Bookmarks bar: below the top bar, or at the very top if the bar is at the bottom
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
                        // GeckoView container for web rendering with Gecko.
                        // In landscape full screen with "Fill screen", the view becomes 16:9 across the width
                        // (taller than the 20:9 screen and cropped top/bottom), so the video fills the whole screen
                        // without side bars and without touching the page content.
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

                // Zen canvas visible only when the tab is empty
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

        // Tab management sheet / full view
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

        // Native DevTools sheet
        if (showDevToolsSheet) {
            DevToolsSheet(
                activeTab = activeTab,
                onDismiss = { showDevToolsSheet = false }
            )
        }

        // Mirroring to a TV: the playing video moves to the TV as a secondary display (the phone does not change)
        // Every time Senda comes back to the foreground: the video removed on leaving returns to the TV from where it was
        val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        var foregroundCount by remember { mutableIntStateOf(0) }
        DisposableEffect(lifecycleOwner) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_START) foregroundCount++
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        LaunchedEffect(
            org.senda.browser.core.cast.SendaTvMode.tvConnected,
            activeTab?.url,
            org.senda.browser.core.cast.SendaMediaCatalog.version,
            foregroundCount
        ) {
            // With TV mode the TV shows the phone in TV format: the video is seen on both at once and in real time
            // (full screen or phone rotated). The separate TV player only without TV mode
            org.senda.browser.core.cast.SendaTvPlayer.autoStart(activeTab, enabled = !prefs.tvModeEnabled)
        }
        val tvPlayback = org.senda.browser.core.cast.SendaTvPlayer.playback
        if (tvPlayback != null) {
            org.senda.browser.ui.components.TvPresentationHost(tvPlayback)
        }

        // Casting with the TV already connected: the TV's video and "Disconnect TV"
        if (showCastDialog) {
            CastDialog(onDismiss = { showCastDialog = false })
        }

        // Bookmarks manager dialog
        if (showBookmarksDialog) {
            BookmarksManagerDialog(
                prefs = prefs,
                currentUrl = activeTab?.url ?: "",
                currentTitle = activeTab?.title ?: "",
                onNavigate = { url -> activeTab?.loadUri(url) },
                onDismiss = { showBookmarksDialog = false }
            )
        }

        // Browsing history manager dialog
        if (showHistoryDialog) {
            HistoryManagerDialog(
                prefs = prefs,
                onNavigate = { url -> activeTab?.loadUri(url) },
                onOpenClearData = { showClearDataDialog = true },
                onDismiss = { showHistoryDialog = false }
            )
        }

        // Clear browsing data dialog
        if (showClearDataDialog) {
            ClearBrowsingDataDialog(
                prefs = prefs,
                onDataCleared = {
                    onSettingsChanged()
                },
                onDismiss = { showClearDataDialog = false }
            )
        }

        // Advanced downloads manager dialog
        if (showDownloadsDialog) {
            DownloadsManagerDialog(
                prefs = prefs,
                onDismiss = { showDownloadsDialog = false }
            )
        }

        // Uploading files to a page (<input type="file">). There used to be no picker: the prompt was left
        // unresolved and no page could receive a file
        SendaFilePromptHandler(activeTab)

        // Host for interactive web dialogs (dropdowns / select, alerts, confirmations)
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


/** Opens Android's document picker for the tab's file prompt and hands it what was chosen. */
@Composable
private fun SendaFilePromptHandler(tab: BrowserTab?) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val filePrompt = tab?.activePrompt as? SendaPrompt.File
    // Avoids opening the picker twice for the same prompt (e.g. when rotating the screen)
    var launchedFor by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(0) }

    fun deliver(uris: List<android.net.Uri>) {
        val t = tab ?: return
        val p = t.activePrompt as? SendaPrompt.File ?: return
        scope.launch {
            val copies = org.senda.browser.core.SendaWebUploads.copyToCache(context, uris)
            if (!p.prompt.isComplete) {
                p.result.complete(
                    when {
                        copies.isEmpty() -> p.prompt.dismiss()
                        p.prompt.type == org.mozilla.geckoview.GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE ->
                            p.prompt.confirm(context, copies.toTypedArray())
                        else -> p.prompt.confirm(context, copies.first())
                    }
                )
            }
            if (t.activePrompt === p) t.dismissActivePrompt()
        }
    }

    val pickOne = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> deliver(listOfNotNull(uri)) }
    val pickMany = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> deliver(uris) }

    LaunchedEffect(filePrompt) {
        val p = filePrompt ?: return@LaunchedEffect
        val t = tab ?: return@LaunchedEffect
        val id = System.identityHashCode(p)
        if (launchedFor == id) return@LaunchedEffect
        launchedFor = id
        // Gecko gives MIME types ("image/*") and sometimes extensions (".pdf"): the picker only understands MIME
        val mimes = p.prompt.mimeTypes?.filter { it.contains('/') }?.distinct()?.takeIf { it.isNotEmpty() }
            ?.toTypedArray() ?: arrayOf("*/*")
        try {
            when (p.prompt.type) {
                org.mozilla.geckoview.GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE -> pickMany.launch(mimes)
                org.mozilla.geckoview.GeckoSession.PromptDelegate.FilePrompt.Type.SINGLE -> pickOne.launch(mimes)
                // Folders: GeckoView has no way to receive them from the document picker
                else -> t.dismissActivePrompt()
            }
        } catch (e: android.content.ActivityNotFoundException) {
            t.dismissActivePrompt()
        }
    }
}
