package org.senda.browser.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import org.senda.browser.core.LocalSendaStrings
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.senda.browser.core.PreferencesManager
import org.senda.browser.core.SendaGeckoEngine
import org.senda.browser.core.ToolbarPosition
import org.senda.browser.core.ToolbarWidgetSize
import org.senda.browser.ui.model.BrowserTab
import org.senda.browser.ui.theme.SendaColors

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SendaToolbar(
    activeTab: BrowserTab?,
    tabsCount: Int,
    position: ToolbarPosition,
    showFireButton: Boolean = false,
    showDevToolsButton: Boolean,
    showCastButton: Boolean = true,
    isFullWidth: Boolean = true,
    prefs: PreferencesManager? = null,
    onOpenToolbarCustomization: (() -> Unit)? = null,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onRefresh: () -> Unit,
    onDissolveCurrentTab: () -> Unit = {},
    onOpenTabsOverview: () -> Unit,
    onOpenDevTools: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCast: () -> Unit = {},
    onOpenBookmarks: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    onFindInPage: () -> Unit = {},
    onOpenExtensions: () -> Unit = {},
    onOpenSync: () -> Unit = {},
    onOpenAiAssistant: (() -> Unit)? = null,
    onGoHome: (() -> Unit)? = null,
    onSwitchNextTab: (() -> Unit)? = null,
    onSwitchPrevTab: (() -> Unit)? = null,
    onNewTab: ((String) -> Unit)? = null,
    onNewPrivateTab: (() -> Unit)? = null,
    onCloseCurrentTab: (() -> Unit)? = null,
    onCloseAllTabs: (() -> Unit)? = null,
    isEditing: Boolean = false,
    onEditingChange: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    val widgetSize = prefs?.toolbarWidgetSize ?: ToolbarWidgetSize.BALANCED
    val btnSize = when (widgetSize) {
        ToolbarWidgetSize.COMPACT -> 36.dp
        ToolbarWidgetSize.BALANCED -> 42.dp
        ToolbarWidgetSize.COMFORTABLE -> 48.dp
    }
    val iconSize = when (widgetSize) {
        ToolbarWidgetSize.COMPACT -> 19.dp
        ToolbarWidgetSize.BALANCED -> 22.dp
        ToolbarWidgetSize.COMFORTABLE -> 25.dp
    }
    val toolbarRowHeight = when (widgetSize) {
        ToolbarWidgetSize.COMPACT -> 52.dp
        ToolbarWidgetSize.BALANCED -> 60.dp
        ToolbarWidgetSize.COMFORTABLE -> 68.dp
    }
    val addressBarHeight = when (widgetSize) {
        ToolbarWidgetSize.COMPACT -> 40.dp
        ToolbarWidgetSize.BALANCED -> 46.dp
        ToolbarWidgetSize.COMFORTABLE -> 52.dp
    }
    val addressBarFontSize = when (widgetSize) {
        ToolbarWidgetSize.COMPACT -> 14.sp
        ToolbarWidgetSize.BALANCED -> 15.5.sp
        ToolbarWidgetSize.COMFORTABLE -> 17.sp
    }
    val itemSpacing = when (widgetSize) {
        ToolbarWidgetSize.COMPACT -> 2.dp
        ToolbarWidgetSize.BALANCED -> 4.dp
        ToolbarWidgetSize.COMFORTABLE -> 6.dp
    }
    val badgeSize = when (widgetSize) {
        ToolbarWidgetSize.COMPACT -> 20.dp
        ToolbarWidgetSize.BALANCED -> 23.dp
        ToolbarWidgetSize.COMFORTABLE -> 26.dp
    }
    val badgeFontSize = when (widgetSize) {
        ToolbarWidgetSize.COMPACT -> 10.5.sp
        ToolbarWidgetSize.BALANCED -> 12.sp
        ToolbarWidgetSize.COMFORTABLE -> 13.5.sp
    }

    val strings = LocalSendaStrings.current

    var localIsEditing by remember { mutableStateOf(false) }
    val effectiveIsEditing = onEditingChange?.let { isEditing } ?: localIsEditing
    val setEditing: (Boolean) -> Unit = { value ->
        if (onEditingChange != null) {
            onEditingChange(value)
        } else {
            localIsEditing = value
        }
    }

    var textFieldValue by remember {
        mutableStateOf(TextFieldValue(if (activeTab?.url == "about:blank") "" else (activeTab?.url ?: "")))
    }

    // Interceptor directo del botón/gesto Atrás de Android: descarta edición y restaura la barra
    BackHandler(enabled = effectiveIsEditing) {
        focusManager.clearFocus()
        keyboardController?.hide()
        setEditing(false)
    }

    LaunchedEffect(effectiveIsEditing) {
        if (effectiveIsEditing) {
            val currentText = if (activeTab?.url == "about:blank") "" else (activeTab?.url ?: "")
            textFieldValue = TextFieldValue(
                text = currentText,
                selection = TextRange(0, currentText.length)
            )
            kotlinx.coroutines.delay(80)
            try {
                focusRequester.requestFocus()
                keyboardController?.show()
            } catch (_: Exception) {}
        } else {
            focusManager.clearFocus()
            keyboardController?.hide()
        }
    }

    var showSecurityDialog by remember { mutableStateOf(false) }
    var showTranslateDialog by remember { mutableStateOf(false) }
    if (showTranslateDialog && activeTab != null) {
        TranslateDialog(tab = activeTab, onDismiss = { showTranslateDialog = false })
    }
    var showProxyDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showBackHistoryMenu by remember { mutableStateOf(false) }
    var showTabQuickMenu by remember { mutableStateOf(false) }

    val haptic = LocalHapticFeedback.current
    var totalDrag by remember { mutableFloatStateOf(0f) }

    val dragModifier = if (!effectiveIsEditing && (onSwitchNextTab != null || onSwitchPrevTab != null)) {
        Modifier.pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { totalDrag = 0f },
                onDragEnd = {
                    val threshold = 55.dp.toPx()
                    if (totalDrag > threshold) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSwitchPrevTab?.invoke()
                    } else if (totalDrag < -threshold) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSwitchNextTab?.invoke()
                    }
                    totalDrag = 0f
                },
                onDragCancel = { totalDrag = 0f },
                onHorizontalDrag = { _, dragAmount ->
                    totalDrag += dragAmount
                }
            )
        }
    } else Modifier

    val isHomeTab = activeTab == null || activeTab.url.isBlank() || activeTab.url == "about:blank"
    val canGoBack = activeTab?.canGoBack == true
    val canGoForward = activeTab?.canGoForward == true
    val isLoading = activeTab?.isLoading == true

    // Si es modo borde a borde (diámetro completo) o cápsula flotante
    val isFullWidthMode = isFullWidth && position != ToolbarPosition.FLOATING
    val containerShape = if (isFullWidthMode) RectangleShape else RoundedCornerShape(28.dp)

    val insetsModifier = when (position) {
        ToolbarPosition.TOP -> if (isFullWidthMode) {
            Modifier.statusBarsPadding()
        } else {
            Modifier
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp)
        }
        ToolbarPosition.BOTTOM -> if (isFullWidthMode) {
            Modifier.navigationBarsPadding()
        } else {
            Modifier
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp)
        }
        ToolbarPosition.FLOATING -> Modifier
            .padding(horizontal = 10.dp, vertical = 10.dp)
    }

    val outlineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    val borderModifier = if (isFullWidthMode) {
        when (position) {
            ToolbarPosition.TOP -> Modifier.drawBehind {
                drawLine(
                    color = outlineColor,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx()
                )
            }
            ToolbarPosition.BOTTOM -> Modifier.drawBehind {
                drawLine(
                    color = outlineColor,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx()
                )
            }
            else -> Modifier
        }
    } else {
        Modifier
            .shadow(elevation = 6.dp, shape = containerShape, ambientColor = Color.Black.copy(alpha = 0.25f))
            .border(width = 1.dp, color = outlineColor, shape = containerShape)
    }

    Box(
        modifier = modifier
            .then(insetsModifier)
            .fillMaxWidth()
            .then(dragModifier)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(containerShape)
                .then(borderModifier),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = if (isFullWidthMode) 3.dp else 4.dp
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (effectiveIsEditing) {
                    // Modo de Edición Expandido estilo GNOME Web (Adwaita Entry Focus)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(toolbarRowHeight)
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(itemSpacing)
                    ) {
                        // Botón de Volver / Cancelar (Adwaita flat button)
                        IconButton(
                            onClick = {
                                focusManager.clearFocus()
                                keyboardController?.hide()
                                setEditing(false)
                            },
                            modifier = Modifier
                                .size(btnSize)
                                .clip(CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = strings.tb_cancel_search,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(iconSize)
                            )
                        }

                        // Caja de entrada estilo Omnibox Pill con anillo de foco
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(addressBarHeight)
                                .clip(RoundedCornerShape(24.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.70f))
                                .border(
                                    width = 1.5.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = RoundedCornerShape(24.dp)
                                )
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .size(iconSize)
                                        .padding(end = 4.dp)
                                )

                                BasicTextField(
                                    value = textFieldValue,
                                    onValueChange = { textFieldValue = it },
                                    singleLine = true,
                                    textStyle = TextStyle(
                                        fontFamily = MaterialTheme.typography.bodyLarge.fontFamily,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = addressBarFontSize,
                                        fontWeight = FontWeight.Normal
                                    ),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Uri,
                                        imeAction = ImeAction.Go
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onGo = {
                                            focusManager.clearFocus()
                                            keyboardController?.hide()
                                            setEditing(false)
                                            val target = textFieldValue.text.trim()
                                            if (target.isNotBlank()) {
                                                onNavigate(target)
                                            }
                                        }
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .focusRequester(focusRequester)
                                )

                                if (textFieldValue.text.isNotEmpty()) {
                                    IconButton(
                                        onClick = { textFieldValue = TextFieldValue("") },
                                        modifier = Modifier
                                            .size((btnSize.value - 8).coerceAtLeast(24f).dp)
                                            .clip(RoundedCornerShape(8.dp))
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = strings.tb_clear_text,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size((iconSize.value - 4).coerceAtLeast(14f).dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Botón Ir / Navegar (Adwaita Suggestion Button)
                        IconButton(
                            onClick = {
                                focusManager.clearFocus()
                                keyboardController?.hide()
                                setEditing(false)
                                val target = textFieldValue.text.trim()
                                if (target.isNotBlank()) {
                                    onNavigate(target)
                                }
                            },
                            modifier = Modifier
                                .size(btnSize)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = strings.tb_navigate_address,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(iconSize)
                            )
                        }
                    }

                    // Píldoras de acción rápida en modo edición: Pegar e ir, Copiar enlace actual, Compartir
                    val clipboardManager = remember {
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    }
                    val clipboardText = remember(effectiveIsEditing) {
                        try {
                            val clip = clipboardManager.primaryClip
                            if (clip != null && clip.itemCount > 0) {
                                clip.getItemAt(0)?.text?.toString()?.trim()
                            } else null
                        } catch (_: Exception) {
                            null
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp)
                            .padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!clipboardText.isNullOrBlank()) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                modifier = Modifier.clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                    setEditing(false)
                                    onNavigate(clipboardText)
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentPaste,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = strings.tb_paste_and_go,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }

                        val activeUrl = activeTab?.url ?: ""
                        if (activeUrl.isNotBlank() && activeUrl != "about:blank") {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                                modifier = Modifier.clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    val clip = ClipData.newPlainText("URL", activeUrl)
                                    clipboardManager.setPrimaryClip(clip)
                                    Toast.makeText(context, strings.tb_link_copied, Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = strings.tb_copy_link,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                                modifier = Modifier.clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    val sendIntent = Intent().apply {
                                        action = Intent.ACTION_SEND
                                        putExtra(Intent.EXTRA_TEXT, activeUrl)
                                        type = "text/plain"
                                    }
                                    val shareIntent = Intent.createChooser(sendIntent, strings.tb_share_link)
                                    context.startActivity(shareIntent)
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Share,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = strings.tb_share,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }

                    // Sugerencias mientras se escribe: solo de favoritos e historial guardados en el teléfono,
                    // nunca se envía lo tecleado a un buscador
                    if (prefs?.searchSuggestionsEnabled == true) {
                        val sources = remember(effectiveIsEditing) {
                            prefs.getBookmarks().map { Triple(it.title, it.url, true) } +
                                prefs.getHistory().sortedByDescending { it.timestamp }.map { Triple(it.title, it.url, false) }
                        }
                        val query = textFieldValue.text.trim()
                        val suggestions = remember(query, sources) {
                            if (query.length < 2) emptyList()
                            else sources.asSequence()
                                .filter { (title, url, _) -> url.contains(query, ignoreCase = true) || title.contains(query, ignoreCase = true) }
                                .distinctBy { it.second.trimEnd('/') }
                                .take(6)
                                .toList()
                        }
                        suggestions.forEach { (title, url, isBookmark) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        focusManager.clearFocus()
                                        keyboardController?.hide()
                                        setEditing(false)
                                        onNavigate(url)
                                    }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (isBookmark) Icons.Default.Star else Icons.Default.History,
                                    contentDescription = if (isBookmark) strings.tb_bookmarks else strings.tb_history,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = title.ifBlank { url },
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = url.removePrefix("https://").removePrefix("http://").removePrefix("www."),
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                // Llevar la dirección al campo para seguir editándola
                                IconButton(
                                    onClick = { textFieldValue = TextFieldValue(url, TextRange(url.length)) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.NorthWest,
                                        contentDescription = strings.tb_suggestion_fill,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // Barra de Navegación estilo GNOME Web (Epiphany Adwaita HeaderBar)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(toolbarRowHeight)
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(itemSpacing)
                    ) {
                        // 1. Botón Atrás
                        if (prefs?.showBackButton != false) {
                            Box {
                                Surface(
                                    shape = CircleShape,
                                    color = Color.Transparent,
                                    modifier = Modifier
                                        .size(btnSize)
                                        .clip(CircleShape)
                                        .combinedClickable(
                                            onClick = {
                                                if (canGoBack) {
                                                    onBack()
                                                } else if (!isHomeTab) {
                                                    onGoHome?.invoke()
                                                }
                                            },
                                            onLongClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                showBackHistoryMenu = true
                                            }
                                        )
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = strings.tb_back_options,
                                            tint = if (canGoBack || !isHomeTab) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.32f),
                                            modifier = Modifier.size(iconSize)
                                        )
                                    }
                                }

                                DropdownMenu(
                                    expanded = showBackHistoryMenu,
                                    onDismissRequest = { showBackHistoryMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(strings.tb_homepage) },
                                        leadingIcon = { Icon(Icons.Default.Home, null) },
                                        onClick = {
                                            showBackHistoryMenu = false
                                            onGoHome?.invoke()
                                        }
                                    )
                                    if (canGoBack) {
                                        DropdownMenuItem(
                                            text = { Text(strings.tb_previous_page) },
                                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) },
                                            onClick = {
                                                showBackHistoryMenu = false
                                                onBack()
                                            }
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text(strings.tb_history) },
                                        leadingIcon = { Icon(Icons.Default.History, null) },
                                        onClick = {
                                            showBackHistoryMenu = false
                                            onOpenHistory()
                                        }
                                    )
                                }
                            }
                        }

                        // 2. Botón Adelante
                        if (prefs?.showForwardButton == true) {
                            IconButton(
                                onClick = onForward,
                                enabled = canGoForward,
                                modifier = Modifier
                                    .size(btnSize)
                                    .clip(CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = strings.forward,
                                    tint = if (canGoForward) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f),
                                    modifier = Modifier.size(iconSize)
                                )
                            }
                        }

                        // 3. Botón de Inicio / Home
                        if (prefs?.showHomeButton == true && !isHomeTab) {
                            IconButton(
                                onClick = { onGoHome?.invoke() },
                                modifier = Modifier
                                    .size(btnSize)
                                    .clip(CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Home,
                                    contentDescription = strings.tb_homepage,
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(iconSize)
                                )
                            }
                        }

                        // 4. Barra Central estilo Omnibox Pill
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(addressBarHeight)
                                .clip(RoundedCornerShape(24.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f))
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                    shape = RoundedCornerShape(24.dp)
                                )
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    setEditing(true)
                                }
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                // Indicador de pestaña privada
                                if (activeTab?.isPrivate == true) {
                                    Icon(
                                        imageVector = Icons.Default.Security,
                                        contentDescription = strings.tabs_private,
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier
                                            .padding(end = 4.dp)
                                            .size((iconSize.value - 2).coerceAtLeast(14f).dp)
                                    )
                                }

                                // Indicador de seguridad / búsqueda
                                if (prefs?.showSecurityIndicator != false) {
                                    if (isHomeTab) {
                                        Icon(
                                            imageVector = Icons.Default.Search,
                                            contentDescription = strings.general_search,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier
                                                .padding(end = 4.dp)
                                                .size((iconSize.value - 2).coerceAtLeast(14f).dp)
                                        )
                                    } else if (activeTab.trackersBlocked > 0 && activeTab.displaySecure) {
                                        // En páginas sin cifrar se muestra el candado abierto, no el escudo
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .padding(end = 4.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                                                .clickable { showSecurityDialog = true }
                                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Shield,
                                                contentDescription = strings.tb_protected,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size((iconSize.value - 6).coerceAtLeast(12f).dp)
                                            )
                                            Spacer(modifier = Modifier.width(2.dp))
                                            Text(
                                                text = "${activeTab.trackersBlocked}",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    } else {
                                        IconButton(
                                            onClick = { showSecurityDialog = true },
                                            modifier = Modifier
                                                .size((btnSize.value - 10).coerceAtLeast(22f).dp)
                                                .clip(RoundedCornerShape(4.dp))
                                        ) {
                                            // El candado solo aparece si Gecko validó el certificado de la página
                                            val secure = activeTab.displaySecure
                                            Icon(
                                                imageVector = if (secure) Icons.Default.Lock else Icons.Default.LockOpen,
                                                contentDescription = if (secure) strings.tb_encrypted_conn else strings.sec_not_secure,
                                                tint = if (secure) MaterialTheme.colorScheme.primary.copy(alpha = 0.9f) else MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size((iconSize.value - 4).coerceAtLeast(14f).dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(2.dp))
                                    }
                                }

                                // Texto de URL
                                Text(
                                    text = if (isHomeTab) strings.search_or_type_url else formatDisplayHost(activeTab?.displayUrl ?: ""),
                                    style = TextStyle(
                                        fontFamily = MaterialTheme.typography.bodyLarge.fontFamily,
                                        color = if (isHomeTab) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                        fontSize = addressBarFontSize,
                                        fontWeight = if (isHomeTab) FontWeight.Normal else FontWeight.SemiBold,
                                        letterSpacing = (-0.15).sp
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )

                                // Acciones dentro de la barra
                                if (!isHomeTab) {
                                    if (prefs?.showReaderButton != false) {
                                        IconButton(
                                            onClick = { activeTab?.toggleReaderMode() },
                                            modifier = Modifier
                                                .size((btnSize.value - 8).coerceAtLeast(24f).dp)
                                                .clip(RoundedCornerShape(6.dp))
                                        ) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.MenuBook,
                                                contentDescription = strings.tb_reader_mode,
                                                tint = if (activeTab?.isReaderMode == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size((iconSize.value - 2).coerceAtLeast(16f).dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(2.dp))
                                    }
                                    if (prefs?.showReloadButton != false) {
                                        if (isLoading) {
                                            IconButton(
                                                onClick = { activeTab?.stop() },
                                                modifier = Modifier
                                                    .size((btnSize.value - 8).coerceAtLeast(24f).dp)
                                                    .clip(RoundedCornerShape(6.dp))
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Close,
                                                    contentDescription = strings.tb_stop_loading,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size((iconSize.value - 2).coerceAtLeast(16f).dp)
                                                )
                                            }
                                        } else {
                                            IconButton(
                                                onClick = onRefresh,
                                                modifier = Modifier
                                                    .size((btnSize.value - 8).coerceAtLeast(24f).dp)
                                                    .clip(RoundedCornerShape(6.dp))
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Refresh,
                                                    contentDescription = strings.tb_reload,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size((iconSize.value - 2).coerceAtLeast(16f).dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Botón Nueva Pestaña rápida (+)
                        if (prefs?.showNewTabButton == true) {
                            IconButton(
                                onClick = { onNewTab?.invoke("about:blank") },
                                modifier = Modifier
                                    .size(btnSize)
                                    .clip(CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = strings.tb_new_tab,
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(iconSize)
                                )
                            }
                        }

                        // Botón Compartir
                        if (prefs?.showShareButton == true && !isHomeTab) {
                            IconButton(
                                onClick = {
                                    val currentUrl = activeTab?.url ?: ""
                                    if (currentUrl.isNotBlank()) {
                                        val sendIntent = Intent().apply {
                                            action = Intent.ACTION_SEND
                                            putExtra(Intent.EXTRA_TEXT, currentUrl)
                                            type = "text/plain"
                                        }
                                        context.startActivity(Intent.createChooser(sendIntent, strings.tb_share))
                                    }
                                },
                                modifier = Modifier
                                    .size(btnSize)
                                    .clip(CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = strings.tb_share,
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(iconSize)
                                )
                            }
                        }

                        // Botón Marcadores
                        if (prefs?.showBookmarksButton == true) {
                            IconButton(
                                onClick = onOpenBookmarks,
                                modifier = Modifier
                                    .size(btnSize)
                                    .clip(CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.BookmarkBorder,
                                    contentDescription = strings.tb_bookmarks,
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(iconSize)
                                )
                            }
                        }

                        // Botón DevTools (si está habilitado)
                        if (showDevToolsButton && prefs?.showDevToolsButton == true) {
                            IconButton(
                                onClick = onOpenDevTools,
                                modifier = Modifier
                                    .size(btnSize)
                                    .clip(CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Code,
                                    contentDescription = strings.dlg_devtools_btn,
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(iconSize)
                                )
                            }
                        }

                        // Contador / Selector de pestañas
                        if (prefs?.showTabsButton != false) {
                            Box {
                                Surface(
                                    shape = CircleShape,
                                    color = Color.Transparent,
                                    modifier = Modifier
                                        .size(btnSize)
                                        .clip(CircleShape)
                                        .combinedClickable(
                                            onClick = onOpenTabsOverview,
                                            onLongClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                showTabQuickMenu = true
                                            }
                                        )
                                ) {
                                    val currentDensity = LocalDensity.current
                                    CompositionLocalProvider(
                                        LocalDensity provides Density(
                                            density = currentDensity.density,
                                            fontScale = 1.0f
                                        )
                                    ) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier.fillMaxSize()
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(badgeSize)
                                                    .border(
                                                        width = 1.6.dp,
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                        shape = RoundedCornerShape(6.dp)
                                                    ),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = if (tabsCount > 99) "∞" else "$tabsCount",
                                                    style = TextStyle(
                                                        fontSize = if (tabsCount > 9) (badgeFontSize.value - 1.5f).sp else badgeFontSize,
                                                        fontWeight = FontWeight.Bold,
                                                        textAlign = TextAlign.Center,
                                                        platformStyle = @Suppress("DEPRECATION") PlatformTextStyle(
                                                            includeFontPadding = false
                                                        ),
                                                        lineHeightStyle = LineHeightStyle(
                                                            alignment = LineHeightStyle.Alignment.Center,
                                                            trim = LineHeightStyle.Trim.Both
                                                        )
                                                    ),
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                        }
                                    }
                                }

                                DropdownMenu(
                                    expanded = showTabQuickMenu,
                                    onDismissRequest = { showTabQuickMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(strings.tb_new_tab) },
                                        leadingIcon = { Icon(Icons.Default.Add, null) },
                                        onClick = {
                                            showTabQuickMenu = false
                                            onNewTab?.invoke("about:blank")
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(strings.tabs_private) },
                                        leadingIcon = { Icon(Icons.Default.VpnKey, null) },
                                        onClick = {
                                            showTabQuickMenu = false
                                            onNewPrivateTab?.invoke()
                                        }
                                    )
                                    if (tabsCount > 1) {
                                        DropdownMenuItem(
                                            text = { Text(strings.general_close) },
                                            leadingIcon = { Icon(Icons.Default.Close, null) },
                                            onClick = {
                                                showTabQuickMenu = false
                                                onCloseCurrentTab?.invoke()
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(strings.tabs_close_all, color = MaterialTheme.colorScheme.error) },
                                            leadingIcon = { Icon(Icons.Default.DeleteSweep, null, tint = MaterialTheme.colorScheme.error) },
                                            onClick = {
                                                showTabQuickMenu = false
                                                onCloseAllTabs?.invoke()
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // Botón Transmitir (Chromecast) - Ubicado al lado derecho del selector de pestañas
                        if (showCastButton && prefs?.showCastButton == true) {
                            IconButton(
                                onClick = onOpenCast,
                                modifier = Modifier
                                    .size(btnSize)
                                    .clip(CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Cast,
                                    contentDescription = strings.st_chromecast_title,
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(iconSize)
                                )
                            }
                        }

                        // Menú Principal
                        if (prefs?.showMenuButton != false) {
                            Box {
                                IconButton(
                                    onClick = { showMenu = true },
                                    modifier = Modifier
                                        .size(btnSize)
                                        .clip(CircleShape)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Menu,
                                        contentDescription = strings.tb_main_menu,
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(iconSize)
                                    )
                                }

                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                                modifier = Modifier.width(260.dp)
                            ) {
                            // Fila superior rápida de navegación estilo GNOME Web dentro del menú (sin duplicados)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = {
                                        showMenu = false
                                        if (canGoBack) onBack() else if (!isHomeTab) onGoHome?.invoke()
                                    },
                                    enabled = canGoBack || !isHomeTab,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = strings.back,
                                        tint = if (canGoBack || !isHomeTab) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        showMenu = false
                                        onForward()
                                    },
                                    enabled = canGoForward,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = strings.forward,
                                        tint = if (canGoForward) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        showMenu = false
                                        onRefresh()
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = strings.tb_reload,
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        showMenu = false
                                        onGoHome?.invoke()
                                    },
                                    enabled = !isHomeTab,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Home,
                                        contentDescription = strings.tb_homepage,
                                        tint = if (!isHomeTab) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                            // Senda AI (Asistente en chip / Codex / Documentos)
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("Senda AI", fontWeight = FontWeight.Bold)
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Surface(
                                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                                shape = RoundedCornerShape(4.dp)
                                            ) {
                                                Text(
                                                    "LOCAL",
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                        Text("Asistente, documentos y código", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.AutoAwesome,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    onOpenAiAssistant?.invoke()
                                }
                            )

                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                            // Red Tor y Proxy
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(strings.tb_tor_proxy, fontWeight = FontWeight.SemiBold)
                                        val proxySub = when (activeTab?.prefs?.proxyMode) {
                                             "TOR_ORBOT" -> "🧅 Tor / Orbot"
                                             "CUSTOM_SOCKS5" -> "🛡️ SOCKS5"
                                             "CUSTOM_HTTP" -> "🌐 HTTP"
                                             else -> strings.dlg_proxy_off
                                        }
                                        Text(proxySub, fontSize = 11.sp, color = if (activeTab?.prefs?.proxyMode != "OFF") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.VpnKey,
                                        contentDescription = null,
                                        tint = if (activeTab?.prefs?.proxyMode != "OFF") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    showProxyDialog = true
                                }
                            )

                            // Transmitir por Chromecast (accesible aquí solo si el usuario ocultó el botón de la barra de navegación, evitando duplicados innecesarios)
                            if (!showCastButton) {
                                DropdownMenuItem(
                                    text = { Text(strings.st_chromecast_title) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Cast,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        onOpenCast()
                                    }
                                )
                            }

                            // Sitio para escritorio
                            DropdownMenuItem(
                                text = { Text(strings.tb_desktop_site) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.DesktopWindows,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                trailingIcon = {
                                    Checkbox(
                                        checked = activeTab?.isDesktopMode == true,
                                        onCheckedChange = {
                                            showMenu = false
                                            activeTab?.toggleDesktopMode()
                                        }
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    activeTab?.toggleDesktopMode()
                                }
                            )

                            // Compartir
                            DropdownMenuItem(
                                text = { Text("${strings.tb_share}...") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Share,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        putExtra(Intent.EXTRA_TEXT, activeTab?.url ?: "")
                                        putExtra(Intent.EXTRA_SUBJECT, activeTab?.title ?: "Senda Browser")
                                        type = "text/plain"
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, "${strings.tb_share}...").apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    })
                                }
                            )

                            // Copiar enlace
                            DropdownMenuItem(
                                text = { Text(strings.ctx_copy_link) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("URL", activeTab?.url ?: ""))
                                    Toast.makeText(context, strings.general_copied, Toast.LENGTH_SHORT).show()
                                }
                            )

                            // Buscar en la página
                            if (!isHomeTab) {
                                DropdownMenuItem(
                                    text = { Text(strings.find_in_page) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.FindInPage,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        onFindInPage()
                                    }
                                )

                                // Traducir en el teléfono (o volver al original)
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            when {
                                                activeTab?.isTranslating == true -> strings.translate_in_progress
                                                activeTab?.translatedTo != null -> strings.translate_menu_translated
                                                else -> strings.translate_menu
                                            }
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Translate,
                                            contentDescription = null,
                                            tint = if (activeTab?.translatedTo != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        showTranslateDialog = true
                                    }
                                )

                                // Imprimir o guardar como PDF (el diálogo de Android ofrece ambas cosas)
                                DropdownMenuItem(
                                    text = { Text(strings.page_print) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Print,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        activeTab?.printPage()
                                    }
                                )

                                // Acceso directo en la pantalla de inicio
                                DropdownMenuItem(
                                    text = { Text(strings.page_add_to_home) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.AddToHomeScreen,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        val tab = activeTab
                                        if (tab != null) {
                                            org.senda.browser.core.SendaPageActions.addToHomeScreen(context, tab.url, tab.title, null)
                                        }
                                    }
                                )
                            }

                            // Favoritos y marcadores
                            DropdownMenuItem(
                                text = { Text(strings.tb_bookmarks) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Bookmark,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    onOpenBookmarks()
                                }
                            )

                            // Historial de navegación
                            DropdownMenuItem(
                                text = { Text(strings.tb_history) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.History,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    onOpenHistory()
                                }
                            )

                            // Descargas
                            DropdownMenuItem(
                                text = { Text(strings.tb_downloads) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Download,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    onOpenDownloads()
                                }
                            )

                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                            // Ajustes / Configuración
                            DropdownMenuItem(
                                text = { Text(strings.tb_settings) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Settings,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    onOpenSettings()
                                }
                            )
                        }
                    }
                }
            }
        }

            // Barra de progreso de carga suave integrada
            if (isLoading) {
                    LinearProgressIndicator(
                        progress = { ((activeTab?.progress ?: 0) / 100f).coerceIn(0.05f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.5.dp)
                            .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color.Transparent
                    )
                }
            }
        }
    }

    // Diálogo de Seguridad del Sitio Web
    if (showSecurityDialog && activeTab != null && !isHomeTab) {
        AlertDialog(
            onDismissRequest = { showSecurityDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = strings.sec_site_title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = activeTab.displayUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (activeTab.displaySecure) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = null,
                            tint = if (activeTab.displaySecure) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (activeTab.displaySecure) strings.sec_encrypted else strings.sec_unencrypted,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Block,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${activeTab.trackersBlocked} ${strings.sec_trackers_count_suffix}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = strings.sec_cookies_isolated,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSecurityDialog = false }) {
                    Text(strings.general_close)
                }
            }
        )
    }

    // DIÁLOGO: ENRUTAMIENTO TOR & PROXY SOBERANO
    if (showProxyDialog) {
        val prefs = activeTab?.prefs
        var selectedMode by remember { mutableStateOf(prefs?.proxyMode ?: "OFF") }
        var hostInput by remember { mutableStateOf(prefs?.proxyHost ?: "127.0.0.1") }
        var portInput by remember { mutableStateOf((prefs?.proxyPort ?: 9050).toString()) }
        var dnsRemote by remember { mutableStateOf(prefs?.proxyDnsRemote ?: true) }

        AlertDialog(
            onDismissRequest = { showProxyDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.VpnKey,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(strings.st_tor_title, style = MaterialTheme.typography.titleMedium)
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    Text(
                        text = strings.dlg_tor_desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    // 1. Tor Integrado / Orbot
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { selectedMode = "TOR_ORBOT" },
                        color = if (selectedMode == "TOR_ORBOT") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, if (selectedMode == "TOR_ORBOT") MaterialTheme.colorScheme.primary else Color.Transparent)
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("🧅", fontSize = 20.sp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(strings.dlg_tor_integrated, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text(
                                    text = if (org.senda.browser.core.SendaTorManager.state == org.senda.browser.core.TorState.CONNECTED)
                                        strings.dlg_tor_status_connected
                                    else if (org.senda.browser.core.SendaTorManager.state == org.senda.browser.core.TorState.STARTING)
                                        strings.dlg_tor_status_connecting
                                    else
                                        strings.dlg_tor_integrated_sub,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            RadioButton(
                                selected = selectedMode == "TOR_ORBOT",
                                onClick = { selectedMode = "TOR_ORBOT" }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 2. SOCKS5 Personalizado
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { selectedMode = "CUSTOM_SOCKS5" },
                        color = if (selectedMode == "CUSTOM_SOCKS5") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, if (selectedMode == "CUSTOM_SOCKS5") MaterialTheme.colorScheme.primary else Color.Transparent)
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(strings.dlg_proxy_socks5, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text(strings.dlg_proxy_socks5_sub, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            RadioButton(
                                selected = selectedMode == "CUSTOM_SOCKS5",
                                onClick = { selectedMode = "CUSTOM_SOCKS5" }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 3. HTTP/HTTPS
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { selectedMode = "CUSTOM_HTTP" },
                        color = if (selectedMode == "CUSTOM_HTTP") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, if (selectedMode == "CUSTOM_HTTP") MaterialTheme.colorScheme.primary else Color.Transparent)
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Language, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(strings.dlg_proxy_http, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text(strings.dlg_proxy_http_sub, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            RadioButton(
                                selected = selectedMode == "CUSTOM_HTTP",
                                onClick = { selectedMode = "CUSTOM_HTTP" }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 4. Conexión Directa (OFF)
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { selectedMode = "OFF" },
                        color = if (selectedMode == "OFF") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, if (selectedMode == "OFF") MaterialTheme.colorScheme.primary else Color.Transparent)
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.PowerSettingsNew, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(strings.dlg_proxy_off, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text(strings.dlg_proxy_off_sub, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            RadioButton(
                                selected = selectedMode == "OFF",
                                onClick = { selectedMode = "OFF" }
                            )
                        }
                    }

                    if (selectedMode == "CUSTOM_SOCKS5" || selectedMode == "CUSTOM_HTTP") {
                        Spacer(modifier = Modifier.height(14.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = hostInput,
                                onValueChange = { hostInput = it },
                                label = { Text(strings.dlg_proxy_host) },
                                singleLine = true,
                                modifier = Modifier.weight(2f)
                            )
                            OutlinedTextField(
                                value = portInput,
                                onValueChange = { portInput = it },
                                label = { Text(strings.dlg_proxy_port) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { dnsRemote = !dnsRemote },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = dnsRemote, onCheckedChange = { dnsRemote = it })
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.dlg_proxy_remote_dns_sub, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val parsedPort = portInput.toIntOrNull() ?: 9050
                        prefs?.let { p ->
                            p.proxyMode = selectedMode
                            p.proxyHost = hostInput.trim()
                            p.proxyPort = parsedPort
                            p.proxyDnsRemote = dnsRemote
                            if (selectedMode == "TOR_ORBOT") {
                                p.proxyHost = "127.0.0.1"
                                p.proxyPort = 9050
                                p.proxyDnsRemote = true
                                org.senda.browser.core.SendaTorManager.start(context) {
                                    SendaGeckoEngine.applyProxy(p)
                                }
                            } else {
                                org.senda.browser.core.SendaTorManager.stop(context)
                                SendaGeckoEngine.applyProxy(p)
                            }
                        }
                        showProxyDialog = false
                    }
                ) {
                    Text(strings.general_save)
                }
            },
            dismissButton = {
                TextButton(onClick = { showProxyDialog = false }) {
                    Text(strings.general_cancel)
                }
            }
        )
    }
}

private fun formatDisplayHost(raw: String): String {
    if (raw.isBlank() || raw == "about:blank") return "Buscar o ingresar URL…"
    if (raw.startsWith("moz-extension://")) return "uBlock Origin"
    return try {
        val uri = android.net.Uri.parse(raw)
        val host = uri.host?.removePrefix("www.")
        if (!host.isNullOrBlank()) {
            host
        } else {
            raw.removePrefix("https://").removePrefix("http://").removePrefix("www.").take(36)
        }
    } catch (_: Exception) {
        raw.removePrefix("https://").removePrefix("http://").removePrefix("www.").take(36)
    }
}
