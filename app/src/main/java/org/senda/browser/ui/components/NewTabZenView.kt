package org.senda.browser.ui.components

import androidx.compose.ui.graphics.luminance
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.senda.browser.R
import org.senda.browser.core.LocalSendaStrings
import org.senda.browser.core.PreferencesManager
import org.senda.browser.core.ZenHomeLayout
import org.senda.browser.core.ZenShortcut
import org.senda.browser.core.news.EthicalNewsItem
import org.senda.browser.core.news.EthicalNewsRepository
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import org.senda.browser.core.AppThemeMode

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NewTabZenView(
    prefs: PreferencesManager,
    onSearch: (String) -> Unit,
    onSettingsChanged: () -> Unit = {}
) {
    val context = LocalContext.current
    val strings = LocalSendaStrings.current
    var queryText by remember { mutableStateOf("") }
    var currentLayout by remember { mutableStateOf(prefs.zenHomeLayout) }
    var showWallpaper by remember { mutableStateOf(prefs.showZenWallpaper) }
    var showShortcuts by remember { mutableStateOf(prefs.showZenShortcuts) }
    var showNews by remember { mutableStateOf(prefs.showZenNewsFeed) }
    var selectedWallpaperId by remember { mutableStateOf(prefs.selectedWallpaperId) }
    var dimPercent by remember { mutableIntStateOf(prefs.wallpaperDimPercent) }
    var rotationMinutes by remember { mutableIntStateOf(prefs.wallpaperRotationMinutes) }

    val customWallpaperLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                val wallDir = java.io.File(context.filesDir, "wallpapers")
                wallDir.mkdirs()
                val target = java.io.File(wallDir, "custom_wallpaper.jpg")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    java.io.FileOutputStream(target).use { output ->
                        input.copyTo(output)
                    }
                }
                prefs.customWallpaperPath = target.absolutePath
                FreeWallpapers.choose(prefs, "custom_user")
                selectedWallpaperId = "custom_user"
                onSettingsChanged()
            } catch (_: Exception) {}
        }
    }

    var showLayoutSelectorSheet by remember { mutableStateOf(false) }

    val shortcutsList = remember { mutableStateListOf<ZenShortcut>() }
    LaunchedEffect(Unit) {
        shortcutsList.clear()
        shortcutsList.addAll(prefs.getZenShortcuts())
    }

    var showAddShortcutDialog by remember { mutableStateOf(false) }
    var editingShortcut by remember { mutableStateOf<ZenShortcut?>(null) }

    val activeWallpaper = remember(selectedWallpaperId, prefs.customWallpaperPath) {
        FreeWallpapers.getById(selectedWallpaperId, prefs.customWallpaperPath)
    }

    val displayWallpaper = when (currentLayout) {
        ZenHomeLayout.FOCUSED -> false
        ZenHomeLayout.INSPIRATIONAL -> true
        ZenHomeLayout.INFORMATIONAL -> false
        ZenHomeLayout.CUSTOM -> showWallpaper
    }

    val displayShortcuts = when (currentLayout) {
        ZenHomeLayout.FOCUSED -> true
        ZenHomeLayout.INSPIRATIONAL -> true
        ZenHomeLayout.INFORMATIONAL -> false
        ZenHomeLayout.CUSTOM -> showShortcuts
    }

    val displayNewsFeed = when (currentLayout) {
        ZenHomeLayout.FOCUSED -> false
        ZenHomeLayout.INSPIRATIONAL -> false
        ZenHomeLayout.INFORMATIONAL -> true
        ZenHomeLayout.CUSTOM -> showNews
    }

    // News outlets are only contacted if this layout shows news ("Focused" must not touch the network)
    val wantsNews = displayNewsFeed || currentLayout == ZenHomeLayout.INSPIRATIONAL // featured article
    // null while downloading; empty if no source answered (fixed headlines used to be shown as if they were news)
    val newsResult by produceState<List<EthicalNewsItem>?>(initialValue = null, wantsNews) {
        value = if (wantsNews) EthicalNewsRepository.fetchEthicalNews() else emptyList()
    }
    val newsList = newsResult.orEmpty()
    val newsUnavailable = displayNewsFeed && newsResult?.isEmpty() == true

    // Wallpaper rotation: the turn is saved in preferences so it stays the same across tabs and restarts
    var rotatingWallpaper by remember { mutableStateOf<FreeWallpaper?>(null) }
    LaunchedEffect(rotationMinutes, displayWallpaper, selectedWallpaperId) {
        if (!displayWallpaper || rotationMinutes == 0) {
            rotatingWallpaper = null
            return@LaunchedEffect
        }
        fun advance(): FreeWallpaper {
            val next = FreeWallpapers.nextRandom(prefs.wallpaperRotationCurrentId)
            prefs.wallpaperRotationCurrentId = next.id
            prefs.wallpaperRotationChangedAt = System.currentTimeMillis()
            return next
        }
        if (rotationMinutes < 0) {
            // Just chosen in the picker: that one is shown; otherwise, a new one for each tab
            val justChosen = System.currentTimeMillis() - prefs.wallpaperRotationChangedAt < 3_000L
            val current = prefs.wallpaperRotationCurrentId?.let { id -> FreeWallpapers.items.find { it.id == id } }
            rotatingWallpaper = if (justChosen && current != null) current else advance()
            return@LaunchedEffect
        }
        val intervalMs = rotationMinutes * 60_000L
        while (true) {
            val current = prefs.wallpaperRotationCurrentId?.let { id -> FreeWallpapers.items.find { it.id == id } }
            val elapsed = System.currentTimeMillis() - prefs.wallpaperRotationChangedAt
            rotatingWallpaper = if (current == null || elapsed !in 0 until intervalMs) advance() else current
            val waitMs = intervalMs - (System.currentTimeMillis() - prefs.wallpaperRotationChangedAt)
            kotlinx.coroutines.delay(waitMs.coerceAtLeast(1_000L))
        }
    }
    val shownWallpaper = rotatingWallpaper ?: activeWallpaper

    Box(modifier = Modifier.fillMaxSize()) {
        // Free art wallpaper (GPL/CC0) if it is on in the chosen mode
        if (displayWallpaper) {
            androidx.compose.animation.Crossfade(
                targetState = shownWallpaper,
                animationSpec = androidx.compose.animation.core.tween(durationMillis = 1600),
                label = "fondo"
            ) { wallpaper ->
                FreeWallpaperBackground(
                    wallpaper = wallpaper,
                    dimPercent = dimPercent
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            )
        }

        // Main content according to the selected mode
        if (currentLayout == ZenHomeLayout.INFORMATIONAL) {
            // INFORMATIVE MODE: continuous feed with a selector in the header and a search bar
            InformationalLayout(
                currentLayout = currentLayout,
                onOpenLayoutSelector = { showLayoutSelectorSheet = true },
                queryText = queryText,
                onQueryChange = { queryText = it },
                onSearch = onSearch,
                newsList = newsList,
                newsUnavailable = newsUnavailable
            )
        } else {
            // FOCUSED / INSPIRING / CUSTOM MODE
            FocusedOrInspirationalLayout(
                currentLayout = currentLayout,
                onOpenLayoutSelector = { showLayoutSelectorSheet = true },
                queryText = queryText,
                onQueryChange = { queryText = it },
                onSearch = onSearch,
                displayShortcuts = displayShortcuts,
                displayWallpaper = displayWallpaper,
                activeWallpaper = shownWallpaper,
                featuredArticle = if (currentLayout == ZenHomeLayout.INSPIRATIONAL) newsList.firstOrNull() else null,
                displayNewsFeed = displayNewsFeed,
                newsList = newsList,
                newsUnavailable = newsUnavailable,
                shortcuts = shortcutsList,
                onAddShortcut = { showAddShortcutDialog = true },
                onEditShortcut = { editingShortcut = it }
            )
        }

        // Dialog to add a shortcut
        if (showAddShortcutDialog) {
            var newTitle by remember { mutableStateOf("") }
            var newUrl by remember { mutableStateOf("") }

            AlertDialog(
                onDismissRequest = { showAddShortcutDialog = false },
                title = { Text(strings.shortcut_new_title) },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = newTitle,
                            onValueChange = { newTitle = it },
                            label = { Text(strings.shortcut_name_label) },
                            placeholder = { Text(strings.shortcut_name_placeholder) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = newUrl,
                            onValueChange = { newUrl = it },
                            label = { Text(strings.shortcut_url_label) },
                            placeholder = { Text(strings.shortcut_url_placeholder) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (newTitle.isNotBlank() && newUrl.isNotBlank()) {
                                prefs.addZenShortcut(newTitle, newUrl)
                                shortcutsList.clear()
                                shortcutsList.addAll(prefs.getZenShortcuts())
                                showAddShortcutDialog = false
                            }
                        },
                        enabled = newTitle.isNotBlank() && newUrl.isNotBlank()
                    ) {
                        Text(strings.shortcut_add)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddShortcutDialog = false }) {
                        Text(strings.general_cancel)
                    }
                }
            )
        }

        // Dialog to edit or delete a shortcut
        editingShortcut?.let { targetShortcut ->
            var editTitle by remember(targetShortcut) { mutableStateOf(targetShortcut.title) }
            var editUrl by remember(targetShortcut) { mutableStateOf(targetShortcut.url) }

            AlertDialog(
                onDismissRequest = { editingShortcut = null },
                title = { Text(strings.shortcut_edit_title) },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = editTitle,
                            onValueChange = { editTitle = it },
                            label = { Text(strings.shortcut_name_label) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = editUrl,
                            onValueChange = { editUrl = it },
                            label = { Text(strings.shortcut_url_label) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (editTitle.isNotBlank() && editUrl.isNotBlank()) {
                                prefs.updateZenShortcut(targetShortcut.id, editTitle, editUrl)
                                shortcutsList.clear()
                                shortcutsList.addAll(prefs.getZenShortcuts())
                                editingShortcut = null
                            }
                        },
                        enabled = editTitle.isNotBlank() && editUrl.isNotBlank()
                    ) {
                        Text(strings.general_save)
                    }
                },
                dismissButton = {
                    Row {
                        TextButton(
                            onClick = {
                                prefs.deleteZenShortcut(targetShortcut.id)
                                shortcutsList.clear()
                                shortcutsList.addAll(prefs.getZenShortcuts())
                                editingShortcut = null
                            }
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(strings.general_delete, color = MaterialTheme.colorScheme.error)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        TextButton(onClick = { editingShortcut = null }) {
                            Text(strings.general_cancel)
                        }
                    }
                }
            )
        }

        // Bottom sheet to choose the layout (Edge preset selector)
        if (showLayoutSelectorSheet) {
            HomeLayoutSelectorSheet(
                prefs = prefs,
                onDismiss = { showLayoutSelectorSheet = false },
                onLayoutChanged = {
                    currentLayout = prefs.zenHomeLayout
                    showWallpaper = prefs.showZenWallpaper
                    showShortcuts = prefs.showZenShortcuts
                    showNews = prefs.showZenNewsFeed
                    selectedWallpaperId = prefs.selectedWallpaperId
                    dimPercent = prefs.wallpaperDimPercent
                    rotationMinutes = prefs.wallpaperRotationMinutes
                    onSettingsChanged()
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeLayoutSelectorSheet(
    prefs: PreferencesManager,
    onDismiss: () -> Unit,
    onLayoutChanged: () -> Unit = {}
) {
    val context = LocalContext.current
    val strings = LocalSendaStrings.current
    var currentLayout by remember { mutableStateOf(prefs.zenHomeLayout) }
    var showWallpaper by remember { mutableStateOf(prefs.showZenWallpaper) }
    var showShortcuts by remember { mutableStateOf(prefs.showZenShortcuts) }
    var showNews by remember { mutableStateOf(prefs.showZenNewsFeed) }
    var selectedWallpaperId by remember { mutableStateOf(prefs.selectedWallpaperId) }
    var dimPercent by remember { mutableIntStateOf(prefs.wallpaperDimPercent) }

    val customWallpaperLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                val wallDir = java.io.File(context.filesDir, "wallpapers")
                wallDir.mkdirs()
                val target = java.io.File(wallDir, "custom_wallpaper.jpg")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    java.io.FileOutputStream(target).use { output ->
                        input.copyTo(output)
                    }
                }
                prefs.customWallpaperPath = target.absolutePath
                FreeWallpapers.choose(prefs, "custom_user")
                selectedWallpaperId = "custom_user"
                onLayoutChanged()
            } catch (_: Exception) {}
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = strings.home_design_title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = strings.home_design_sub,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Predefined options (inspired by modern presets)
            LayoutOptionItem(
                icon = Icons.Default.FilterCenterFocus,
                title = strings.preset_focused,
                desc = strings.preset_focused_desc,
                isSelected = currentLayout == ZenHomeLayout.FOCUSED,
                onClick = {
                    currentLayout = ZenHomeLayout.FOCUSED
                    prefs.zenHomeLayout = ZenHomeLayout.FOCUSED
                    onLayoutChanged()
                    onDismiss()
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            LayoutOptionItem(
                icon = Icons.Default.AutoAwesome,
                title = strings.preset_inspirational,
                desc = strings.preset_inspirational_desc,
                isSelected = currentLayout == ZenHomeLayout.INSPIRATIONAL,
                onClick = {
                    currentLayout = ZenHomeLayout.INSPIRATIONAL
                    prefs.zenHomeLayout = ZenHomeLayout.INSPIRATIONAL
                    onLayoutChanged()
                    onDismiss()
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            LayoutOptionItem(
                icon = Icons.Default.Newspaper,
                title = strings.preset_informational,
                desc = strings.preset_informational_desc,
                isSelected = currentLayout == ZenHomeLayout.INFORMATIONAL,
                onClick = {
                    currentLayout = ZenHomeLayout.INFORMATIONAL
                    prefs.zenHomeLayout = ZenHomeLayout.INFORMATIONAL
                    onLayoutChanged()
                    onDismiss()
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            LayoutOptionItem(
                icon = Icons.Default.Tune,
                title = strings.preset_custom,
                desc = strings.preset_custom_desc,
                isSelected = currentLayout == ZenHomeLayout.CUSTOM,
                onClick = {
                    currentLayout = ZenHomeLayout.CUSTOM
                    prefs.zenHomeLayout = ZenHomeLayout.CUSTOM
                    onLayoutChanged()
                }
            )

            // Detailed options for Custom
            if (currentLayout == ZenHomeLayout.CUSTOM) {
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Spacer(modifier = Modifier.height(12.dp))

                CustomToggleRow(strings.home_wallpapers_art, showWallpaper) {
                    showWallpaper = it
                    prefs.showZenWallpaper = it
                    onLayoutChanged()
                }
                CustomToggleRow(strings.zen_shortcuts, showShortcuts) {
                    showShortcuts = it
                    prefs.showZenShortcuts = it
                    onLayoutChanged()
                }
                CustomToggleRow(strings.zen_news, showNews) {
                    showNews = it
                    prefs.showZenNewsFeed = it
                    onLayoutChanged()
                }
            }

            // Free wallpaper picker
            if (currentLayout == ZenHomeLayout.INSPIRATIONAL || (currentLayout == ZenHomeLayout.CUSTOM && showWallpaper)) {
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = strings.home_free_wallpapers,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))

                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp)
                ) {
                    // Option to upload your own wallpaper
                    item {
                        val isCustomSelected = selectedWallpaperId == "custom_user"
                        Surface(
                            modifier = Modifier
                                .width(76.dp)
                                .height(96.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    try {
                                        customWallpaperLauncher.launch("image/*")
                                    } catch (_: Exception) {}
                                }
                                .border(
                                    width = if (isCustomSelected) 2.5.dp else 0.8.dp,
                                    color = if (isCustomSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                    shape = RoundedCornerShape(10.dp)
                                ),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AddPhotoAlternate,
                                    contentDescription = strings.home_custom_wallpaper,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = if (prefs.customWallpaperPath != null) strings.home_custom_label else strings.shortcut_add,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    items(FreeWallpapers.items, key = { it.id }) { wallpaper ->
                        val isSelected = wallpaper.id == selectedWallpaperId
                        Surface(
                            modifier = Modifier
                                .width(76.dp)
                                .height(96.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    selectedWallpaperId = wallpaper.id
                                    FreeWallpapers.choose(prefs, wallpaper.id)
                                    onLayoutChanged()
                                }
                                .border(
                                    width = if (isSelected) 2.5.dp else 0.8.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                    shape = RoundedCornerShape(10.dp)
                                ),
                            color = wallpaper.colors.getOrNull(1) ?: Color.DarkGray
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                FreeWallpaperBackground(wallpaper = wallpaper)
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(
                                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)),
                                                startY = 40f
                                            )
                                        )
                                )
                                Text(
                                    text = wallpaper.name,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    maxLines = 2,
                                    lineHeight = 11.sp,
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(5.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                var sheetRotation by remember { mutableIntStateOf(prefs.wallpaperRotationMinutes) }
                Text(text = strings.wp_rotation_title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(
                        0 to strings.wp_rotation_fixed,
                        -1 to strings.wp_rotation_new_tab,
                        1 to "1 min", 5 to "5 min", 15 to "15 min", 60 to "1 h"
                    ).forEach { (minutes, label) ->
                        FilterChip(
                            selected = sheetRotation == minutes,
                            onClick = {
                                sheetRotation = minutes
                                prefs.wallpaperRotationMinutes = minutes
                                if (minutes != 0) FreeWallpapers.choose(prefs, selectedWallpaperId.takeIf { it != "custom_user" } ?: FreeWallpapers.items.first().id)
                                onLayoutChanged()
                            },
                            label = { Text(label, fontSize = 12.sp) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = strings.home_dim_contrast,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "$dimPercent%",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = dimPercent.toFloat(),
                    onValueChange = {
                        dimPercent = it.toInt()
                        prefs.wallpaperDimPercent = dimPercent
                    },
                    valueRange = 0f..80f,
                    steps = 15,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun FocusedOrInspirationalLayout(
    currentLayout: ZenHomeLayout,
    onOpenLayoutSelector: () -> Unit,
    queryText: String,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    displayShortcuts: Boolean,
    displayWallpaper: Boolean,
    activeWallpaper: FreeWallpaper,
    featuredArticle: EthicalNewsItem?,
    displayNewsFeed: Boolean = false,
    newsList: List<EthicalNewsItem> = emptyList(),
    newsUnavailable: Boolean = false,
    shortcuts: List<ZenShortcut> = emptyList(),
    onAddShortcut: () -> Unit = {},
    onEditShortcut: (ZenShortcut) -> Unit = {}
) {
    val strings = LocalSendaStrings.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top bar with the layout selector built in
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            PresetSwitcherButton(
                currentLayout = currentLayout,
                onClick = onOpenLayoutSelector,
                displayWallpaper = displayWallpaper
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Compact Senda brand identity
        Image(
            painter = painterResource(id = R.drawable.ic_senda_logo),
            contentDescription = "Senda Logo",
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .border(
                    width = 1.2.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                    shape = CircleShape
                )
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "SENDA",
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 4.sp,
            fontFamily = FontFamily.SansSerif,
            color = if (displayWallpaper) Color.White else MaterialTheme.colorScheme.primary
        )

        Text(
            text = strings.zen_tagline,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Normal,
            color = if (displayWallpaper) Color(0xFFDCDCDC) else MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Styled central search bar
        OutlinedTextField(
            value = queryText,
            onValueChange = onQueryChange,
            placeholder = {
                Text(
                    strings.search_or_type_url,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = strings.general_search,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(19.dp)
                )
            },
            trailingIcon = {
                if (queryText.isNotBlank()) {
                    IconButton(
                        onClick = { onSearch(queryText) },
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = strings.tb_go,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Search
            ),
            keyboardActions = KeyboardActions(
                onSearch = { if (queryText.isNotBlank()) onSearch(queryText) }
            ),
            singleLine = true,
            shape = RoundedCornerShape(20.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            ),
            modifier = Modifier.fillMaxWidth(0.98f).searchOnEnterRelease { if (queryText.isNotBlank()) onSearch(queryText) }
        )

        // Shortcuts in a responsive grid (4 per row) using the full width
        if (displayShortcuts) {
            Spacer(modifier = Modifier.height(14.dp))

            val itemsWithAdd = shortcuts.map { it to false } + listOf(null to true)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                itemsWithAdd.chunked(4).forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        rowItems.forEach { (shortcut, isAdd) ->
                            if (isAdd) {
                                AddShortcutTile(
                                    displayWallpaper = displayWallpaper,
                                    onClick = onAddShortcut
                                )
                            } else if (shortcut != null) {
                                QuickLinkTile(
                                    title = shortcut.title,
                                    url = shortcut.url,
                                    iconColor = shortcut.colorHex?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
                                        ?: if (displayWallpaper) Color.White else MaterialTheme.colorScheme.primary,
                                    monogram = shortcut.monogram.ifBlank { shortcut.title.take(2).uppercase() },
                                    displayWallpaper = displayWallpaper,
                                    onClick = onSearch,
                                    onLongClick = { onEditShortcut(shortcut) }
                                )
                            }
                        }
                        if (rowItems.size < 4) {
                            repeat(4 - rowItems.size) {
                                Spacer(modifier = Modifier.width(64.dp))
                            }
                        }
                    }
                }
            }
        }

        if (newsUnavailable) {
            Spacer(modifier = Modifier.height(16.dp))
            NewsUnavailableNote()
        }

        // Full news feed if it is on
        if (displayNewsFeed && newsList.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))

            var selectedSource by remember { mutableStateOf("Todas") }
            val sources = listOf("Todas", "MuyLinux", "EFF", "FSF")

            val filteredNews = remember(selectedSource, newsList) {
                if (selectedSource == "Todas") {
                    newsList
                } else {
                    newsList.filter { it.source.contains(selectedSource, ignoreCase = true) }
                }
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = strings.zen_news,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (displayWallpaper) Color.White else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = strings.zen_news_subtitle,
                            fontSize = 11.5.sp,
                            color = if (displayWallpaper) Color(0xFFDCDCDC) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(sources) { source ->
                        val isSelected = source == selectedSource
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedSource = source },
                            label = {
                                Text(
                                    text = when (source) {
                                        "Todas" -> strings.zen_news_all
                                        "MuyLinux" -> "🐧 MuyLinux"
                                        "EFF" -> "🛡️ EFF"
                                        "FSF" -> "🦬 FSF"
                                        else -> source
                                    },
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            // Own background even when not selected: transparent ones got lost over the wallpaper
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                                labelColor = MaterialTheme.colorScheme.onSurface,
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                filteredNews.forEach { article ->
                    NewsArticleCard(
                        article = article,
                        displayWallpaper = displayWallpaper,
                        onClick = { onSearch(article.url) }
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }
        } else if (featuredArticle != null) {
            Spacer(modifier = Modifier.height(28.dp))
            NewsArticleCard(
                article = featuredArticle,
                displayWallpaper = displayWallpaper,
                onClick = { onSearch(featuredArticle.url) }
            )
        }

        // Credit and license of the free wallpaper
        if (displayWallpaper) {
            Spacer(modifier = Modifier.height(20.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.Black.copy(alpha = 0.45f)
            ) {
                Text(
                    text = "🎨 ${activeWallpaper.name} — ${activeWallpaper.author} · ${activeWallpaper.license}",
                    fontSize = 10.5.sp,
                    color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun InformationalLayout(
    currentLayout: ZenHomeLayout,
    onOpenLayoutSelector: () -> Unit,
    queryText: String,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    newsList: List<EthicalNewsItem>,
    newsUnavailable: Boolean = false
) {
    val strings = LocalSendaStrings.current
    var selectedSource by remember { mutableStateOf("Todas") }
    val sources = listOf("Todas", "MuyLinux", "EFF", "FSF")

    val filteredNews = remember(selectedSource, newsList) {
        if (selectedSource == "Todas") {
            newsList
        } else {
            newsList.filter { it.source.contains(selectedSource, ignoreCase = true) }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 28.dp)
    ) {
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Header with the layout selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_senda_logo),
                            contentDescription = "Senda Logo",
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "SENDA",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 2.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    PresetSwitcherButton(
                        currentLayout = currentLayout,
                        onClick = onOpenLayoutSelector,
                        displayWallpaper = false
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Central search bar in Informative mode
                OutlinedTextField(
                    value = queryText,
                    onValueChange = onQueryChange,
                    placeholder = {
                        Text(
                            strings.search_or_type_url,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = strings.general_search,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(19.dp)
                        )
                    },
                    trailingIcon = {
                        if (queryText.isNotBlank()) {
                            IconButton(
                                onClick = { onSearch(queryText) },
                                modifier = Modifier
                                    .padding(end = 4.dp)
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = strings.tb_go,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Search
                    ),
                    keyboardActions = KeyboardActions(
                        onSearch = { if (queryText.isNotBlank()) onSearch(queryText) }
                    ),
                    singleLine = true,
                    shape = RoundedCornerShape(20.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.fillMaxWidth().searchOnEnterRelease { if (queryText.isNotBlank()) onSearch(queryText) }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // News feed header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = strings.zen_news,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = strings.zen_news_subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(sources) { source ->
                        val isSelected = source == selectedSource
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedSource = source },
                            label = {
                                Text(
                                    text = when (source) {
                                        "Todas" -> strings.zen_news_all
                                        "MuyLinux" -> "🐧 MuyLinux"
                                        "EFF" -> "🛡️ EFF"
                                        "FSF" -> "🦬 FSF"
                                        else -> source
                                    },
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            // Own background even when not selected: transparent ones got lost over the wallpaper
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                                labelColor = MaterialTheme.colorScheme.onSurface,
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
            }
        }

        items(filteredNews) { article ->
            NewsArticleCard(
                article = article,
                displayWallpaper = false,
                onClick = { onSearch(article.url) }
            )
        }

        if (newsUnavailable) {
            item { NewsUnavailableNote() }
        }
    }
}

@Composable
private fun NewsUnavailableNote() {
    Text(
        text = LocalSendaStrings.current.news_offline_none,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp)
    )
}

@Composable
fun NewsArticleCard(
    article: EthicalNewsItem,
    displayWallpaper: Boolean = false,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (displayWallpaper) {
                MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = BorderStroke(
            width = 0.8.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
        ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = when (article.source) {
                        "MuyLinux" -> Color(0xFFE65100).copy(alpha = 0.12f)
                        "EFF" -> Color(0xFF1976D2).copy(alpha = 0.12f)
                        "FSF" -> Color(0xFF7B1FA2).copy(alpha = 0.12f)
                        else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    }
                ) {
                    Text(
                        text = when (article.source) {
                            "MuyLinux" -> "🐧 MuyLinux"
                            "EFF" -> "🛡️ EFF"
                            "FSF" -> "🦬 FSF"
                            else -> article.source
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = when (article.source) {
                            "MuyLinux" -> Color(0xFFD84315)
                            "EFF" -> Color(0xFF1565C0)
                            "FSF" -> Color(0xFF6A1B9A)
                            else -> MaterialTheme.colorScheme.primary
                        }
                    )
                }

                Text(
                    text = article.publishedDate,
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = article.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 22.sp
            )

            if (article.snippet.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = article.snippet,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
fun AddShortcutTile(
    displayWallpaper: Boolean = false,
    onClick: () -> Unit
) {
    val strings = LocalSendaStrings.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(64.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(vertical = 4.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (displayWallpaper) {
                Color.Black.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            },
            border = BorderStroke(
                width = 1.dp,
                color = if (displayWallpaper) Color.White.copy(alpha = 0.35f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            ),
            tonalElevation = 1.dp,
            modifier = Modifier.size(50.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = strings.shortcut_add_desc,
                    tint = if (displayWallpaper) Color.White else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = strings.shortcut_add,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            color = if (displayWallpaper) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            style = if (displayWallpaper) androidx.compose.material3.LocalTextStyle.current.merge(wallpaperLabelStyle) else androidx.compose.material3.LocalTextStyle.current
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QuickLinkTile(
    title: String,
    url: String,
    iconColor: Color,
    monogram: String? = null,
    iconVector: ImageVector? = null,
    isSerif: Boolean = false,
    displayWallpaper: Boolean = false,
    onClick: (String) -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(64.dp)
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = { onClick(url) },
                onLongClick = onLongClick
            )
            .padding(vertical = 4.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (displayWallpaper) {
                Color.Black.copy(alpha = 0.55f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
            },
            border = BorderStroke(
                width = 0.8.dp,
                color = if (displayWallpaper) Color.White.copy(alpha = 0.25f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
            ),
            tonalElevation = 2.dp,
            modifier = Modifier.size(50.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                if (iconVector != null) {
                    Icon(
                        imageVector = iconVector,
                        contentDescription = title,
                        tint = if (displayWallpaper && iconColor.luminance() < 0.2f) Color.White else iconColor,
                        modifier = Modifier.size(24.dp)
                    )
                } else if (monogram != null) {
                    Text(
                        text = monogram,
                        fontSize = if (monogram.length > 1) 13.sp else 21.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = if (isSerif) FontFamily.Serif else FontFamily.SansSerif,
                        // On the dark box, a very dark brand color (Wikipedia's "W") could not be seen
                        color = if (displayWallpaper && iconColor.luminance() < 0.2f) Color.White else iconColor
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = title,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            letterSpacing = (-0.3).sp,
            overflow = TextOverflow.Ellipsis,
            color = if (displayWallpaper) Color.White else MaterialTheme.colorScheme.onSurface,
            // Shadow over the wallpaper: on light areas the white name could not be read
            style = if (displayWallpaper) androidx.compose.material3.LocalTextStyle.current.merge(wallpaperLabelStyle) else androidx.compose.material3.LocalTextStyle.current
        )
    }
}

@Composable
fun PresetSwitcherButton(
    currentLayout: ZenHomeLayout,
    onClick: () -> Unit,
    displayWallpaper: Boolean = false
) {
    val strings = LocalSendaStrings.current
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        color = if (displayWallpaper) Color.Black.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        border = BorderStroke(
            width = 0.8.dp,
            color = if (displayWallpaper) Color.White.copy(alpha = 0.25f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
        ),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when (currentLayout) {
                    ZenHomeLayout.FOCUSED -> Icons.Default.FilterCenterFocus
                    ZenHomeLayout.INSPIRATIONAL -> Icons.Default.AutoAwesome
                    ZenHomeLayout.INFORMATIONAL -> Icons.Default.Newspaper
                    ZenHomeLayout.CUSTOM -> Icons.Default.Tune
                },
                contentDescription = strings.st_zen_title,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(15.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = when (currentLayout) {
                    ZenHomeLayout.FOCUSED -> strings.preset_focused
                    ZenHomeLayout.INSPIRATIONAL -> strings.preset_inspirational
                    ZenHomeLayout.INFORMATIONAL -> strings.preset_informational
                    ZenHomeLayout.CUSTOM -> strings.preset_custom
                },
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (displayWallpaper) Color.White else MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.width(3.dp))
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = if (displayWallpaper) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
fun LayoutOptionItem(
    icon: ImageVector,
    title: String,
    desc: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val strings = LocalSendaStrings.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .border(
                width = if (isSelected) 2.dp else 0.8.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                shape = RoundedCornerShape(14.dp)
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = desc,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isSelected) {
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = strings.general_selected,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun CustomToggleRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private val wallpaperLabelStyle = androidx.compose.ui.text.TextStyle(
    shadow = androidx.compose.ui.graphics.Shadow(color = Color.Black.copy(alpha = 0.8f), blurRadius = 6f)
)

/**
 * Enter on a physical keyboard: the search runs when the key is RELEASED and the field takes both events. When it searched
 * on press, the home page disappeared, focus moved to the bar and releasing Enter triggered the Home button, which
 * went back to the blank page (measured: it failed 5 of 11 startups). The on-screen keyboard's "Search" button does not
 * send keys and still uses keyboardActions.
 */
private fun Modifier.searchOnEnterRelease(onEnter: () -> Unit): Modifier = onPreviewKeyEvent { event ->
    if (event.key == Key.Enter || event.key == Key.NumPadEnter) {
        if (event.type == KeyEventType.KeyUp) onEnter()
        true
    } else {
        false
    }
}
