package org.senda.browser.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.senda.browser.core.LocalSendaStrings
import org.senda.browser.core.PreferencesManager
import org.senda.browser.core.ToolbarPosition
import org.senda.browser.core.ToolbarWidgetSize
import org.senda.browser.ui.theme.SendaColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolbarCustomizationDialog(
    prefs: PreferencesManager,
    onDismiss: () -> Unit,
    onChanged: () -> Unit
) {
    val strings = LocalSendaStrings.current
    var toolbarPos by remember { mutableStateOf(prefs.toolbarPosition) }
    var isFullWidth by remember { mutableStateOf(prefs.toolbarFullWidth) }
    var widgetSize by remember { mutableStateOf(prefs.toolbarWidgetSize) }
    var showBack by remember { mutableStateOf(prefs.showBackButton) }
    var showForward by remember { mutableStateOf(prefs.showForwardButton) }
    var showHome by remember { mutableStateOf(prefs.showHomeButton) }
    var showTabs by remember { mutableStateOf(prefs.showTabsButton) }
    var showMenu by remember { mutableStateOf(prefs.showMenuButton) }
    var showNewTab by remember { mutableStateOf(prefs.showNewTabButton) }
    var showShare by remember { mutableStateOf(prefs.showShareButton) }
    var showBookmarks by remember { mutableStateOf(prefs.showBookmarksButton) }
    var showCast by remember { mutableStateOf(prefs.showCastButton) }
    var showDevTools by remember { mutableStateOf(prefs.showDevToolsButton) }
    var showReader by remember { mutableStateOf(prefs.showReaderButton) }
    var showReload by remember { mutableStateOf(prefs.showReloadButton) }
    var showSecurity by remember { mutableStateOf(prefs.showSecurityIndicator) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = strings.tb_customize,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = strings.tc_header_sub,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                // VISTA PREVIA EN VIVO
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 14.dp)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = strings.tc_preview,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        // Mini barra simulada
                        val btnSize = when (widgetSize) {
                            ToolbarWidgetSize.COMPACT -> 28.dp
                            ToolbarWidgetSize.BALANCED -> 34.dp
                            ToolbarWidgetSize.COMFORTABLE -> 38.dp
                        }
                        val iconSize = when (widgetSize) {
                            ToolbarWidgetSize.COMPACT -> 15.dp
                            ToolbarWidgetSize.BALANCED -> 18.dp
                            ToolbarWidgetSize.COMFORTABLE -> 20.dp
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(
                                    when (widgetSize) {
                                        ToolbarWidgetSize.COMPACT -> 40.dp
                                        ToolbarWidgetSize.BALANCED -> 46.dp
                                        ToolbarWidgetSize.COMFORTABLE -> 52.dp
                                    }
                                )
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            if (showBack) MiniPreviewButton(Icons.AutoMirrored.Filled.ArrowBack, btnSize, iconSize)
                            if (showForward) MiniPreviewButton(Icons.AutoMirrored.Filled.ArrowForward, btnSize, iconSize)
                            if (showHome) MiniPreviewButton(Icons.Default.Home, btnSize, iconSize)

                            // Barra de direcciones simulada
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(
                                        when (widgetSize) {
                                            ToolbarWidgetSize.COMPACT -> 28.dp
                                            ToolbarWidgetSize.BALANCED -> 34.dp
                                            ToolbarWidgetSize.COMFORTABLE -> 40.dp
                                        }
                                    )
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                    .padding(horizontal = 8.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    if (showSecurity) {
                                        Icon(
                                            Icons.Default.Lock,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(11.dp)
                                        )
                                        Spacer(modifier = Modifier.width(3.dp))
                                    }
                                    FitText(
                                        text = "senda.org",
                                        maxSize = 11.sp,
                                        minSize = 7.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Start,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (showReader) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.MenuBook,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(11.dp)
                                        )
                                        Spacer(modifier = Modifier.width(3.dp))
                                    }
                                    if (showReload) {
                                        Icon(
                                            Icons.Default.Refresh,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(11.dp)
                                        )
                                    }
                                }
                            }

                            if (showNewTab) MiniPreviewButton(Icons.Default.Add, btnSize, iconSize)
                            if (showBookmarks) MiniPreviewButton(Icons.Default.BookmarkBorder, btnSize, iconSize)
                            if (showShare) MiniPreviewButton(Icons.Default.Share, btnSize, iconSize)
                            if (showCast) MiniPreviewButton(Icons.Default.Cast, btnSize, iconSize)
                            if (showDevTools) MiniPreviewButton(Icons.Default.Code, btnSize, iconSize)
                            if (showTabs) {
                                Box(
                                    modifier = Modifier
                                        .size(btnSize)
                                        .clip(RoundedCornerShape(4.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(iconSize)
                                            .border(1.2.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(3.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("1", fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            if (showMenu) MiniPreviewButton(Icons.Default.Menu, btnSize, iconSize)
                        }
                    }
                }

                // POSICIÓN DE LA BARRA
                Text(
                    text = strings.dlg_toolbar_pos_title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val posGroup = rememberFitGroup()
                    ToolbarPosition.values().forEach { pos ->
                        FilterChip(
                            selected = pos == toolbarPos,
                            onClick = {
                                toolbarPos = pos
                                prefs.toolbarPosition = pos
                                onChanged()
                            },
                            label = {
                                FitText(
                                    when (pos) {
                                        ToolbarPosition.BOTTOM -> strings.dlg_toolbar_pos_bottom
                                        ToolbarPosition.TOP -> strings.dlg_toolbar_pos_top
                                        ToolbarPosition.FLOATING -> strings.dlg_toolbar_pos_floating
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    sharedSize = posGroup
                                )
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // ESTILO DE BARRA
                Text(
                    text = strings.dlg_toolbar_style_title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val styleGroup = rememberFitGroup()
                    FilterChip(
                        selected = isFullWidth,
                        onClick = {
                            isFullWidth = true
                            prefs.toolbarFullWidth = true
                            onChanged()
                        },
                        label = { FitText(strings.dlg_toolbar_full_width, modifier = Modifier.fillMaxWidth(), sharedSize = styleGroup) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = !isFullWidth,
                        onClick = {
                            isFullWidth = false
                            prefs.toolbarFullWidth = false
                            onChanged()
                        },
                        label = { FitText(strings.dlg_toolbar_floating_capsule, modifier = Modifier.fillMaxWidth(), sharedSize = styleGroup) },
                        modifier = Modifier.weight(1f)
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // SELECTOR DE TAMAÑO DE WIDGETS
                Text(
                    text = strings.tc_widget_size_title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val sizeGroup = rememberFitGroup()
                    listOf(
                        ToolbarWidgetSize.COMPACT to strings.tc_size_compact,
                        ToolbarWidgetSize.BALANCED to strings.tc_size_balanced,
                        ToolbarWidgetSize.COMFORTABLE to strings.tc_size_comfortable
                    ).forEach { (size, label) ->
                        FilterChip(
                            selected = widgetSize == size,
                            onClick = {
                                widgetSize = size
                                prefs.toolbarWidgetSize = size
                                onChanged()
                            },
                            label = { FitText(label, modifier = Modifier.fillMaxWidth(), sharedSize = sizeGroup) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // SECCIÓN: BOTONES PRINCIPALES
                Text(
                    text = strings.tc_sec_nav,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))

                WidgetToggleRow(
                    title = strings.tc_btn_back,
                    subtitle = strings.tc_btn_back_sub,
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    checked = showBack,
                    onCheckedChange = {
                        showBack = it
                        prefs.showBackButton = it
                        onChanged()
                    }
                )

                WidgetToggleRow(
                    title = strings.tc_btn_forward,
                    subtitle = strings.tc_btn_forward_sub,
                    icon = Icons.AutoMirrored.Filled.ArrowForward,
                    checked = showForward,
                    onCheckedChange = {
                        showForward = it
                        prefs.showForwardButton = it
                        onChanged()
                    }
                )

                WidgetToggleRow(
                    title = strings.tc_btn_home,
                    subtitle = strings.tc_btn_home_sub,
                    icon = Icons.Default.Home,
                    checked = showHome,
                    onCheckedChange = {
                        showHome = it
                        prefs.showHomeButton = it
                        onChanged()
                    }
                )

                WidgetToggleRow(
                    title = strings.tc_btn_tabs,
                    subtitle = strings.tc_btn_tabs_sub,
                    icon = Icons.Default.Layers,
                    checked = showTabs,
                    onCheckedChange = {
                        showTabs = it
                        prefs.showTabsButton = it
                        onChanged()
                    }
                )

                WidgetToggleRow(
                    title = strings.tc_btn_menu,
                    subtitle = strings.tc_btn_menu_sub,
                    icon = Icons.Default.Menu,
                    checked = showMenu,
                    onCheckedChange = {
                        showMenu = it
                        prefs.showMenuButton = it
                        onChanged()
                    }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // SECCIÓN: WIDGETS ADICIONALES
                Text(
                    text = strings.tc_sec_extra,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))

                WidgetToggleRow(
                    title = strings.tc_btn_new_tab,
                    subtitle = strings.tc_btn_new_tab_sub,
                    icon = Icons.Default.Add,
                    checked = showNewTab,
                    onCheckedChange = {
                        showNewTab = it
                        prefs.showNewTabButton = it
                        onChanged()
                    }
                )

                WidgetToggleRow(
                    title = strings.tc_btn_share,
                    subtitle = strings.tc_btn_share_sub,
                    icon = Icons.Default.Share,
                    checked = showShare,
                    onCheckedChange = {
                        showShare = it
                        prefs.showShareButton = it
                        onChanged()
                    }
                )

                WidgetToggleRow(
                    title = strings.tc_btn_bookmarks,
                    subtitle = strings.tc_btn_bookmarks_sub,
                    icon = Icons.Default.BookmarkBorder,
                    checked = showBookmarks,
                    onCheckedChange = {
                        showBookmarks = it
                        prefs.showBookmarksButton = it
                        onChanged()
                    }
                )

                WidgetToggleRow(
                    title = strings.tc_btn_cast,
                    subtitle = strings.tc_btn_cast_sub,
                    icon = Icons.Default.Cast,
                    checked = showCast,
                    onCheckedChange = {
                        showCast = it
                        prefs.showCastButton = it
                        onChanged()
                    }
                )

                WidgetToggleRow(
                    title = strings.tc_btn_devtools,
                    subtitle = strings.tc_btn_devtools_sub,
                    icon = Icons.Default.Code,
                    checked = showDevTools,
                    onCheckedChange = {
                        showDevTools = it
                        prefs.showDevToolsButton = it
                        onChanged()
                    }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // SECCIÓN: DENTRO DE LA BARRA DE DIRECCIONES
                Text(
                    text = strings.tc_sec_address,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))

                WidgetToggleRow(
                    title = strings.tc_item_security,
                    subtitle = strings.tc_item_security_sub,
                    icon = Icons.Default.Shield,
                    checked = showSecurity,
                    onCheckedChange = {
                        showSecurity = it
                        prefs.showSecurityIndicator = it
                        onChanged()
                    }
                )

                WidgetToggleRow(
                    title = strings.tc_item_reader,
                    subtitle = strings.tc_item_reader_sub,
                    icon = Icons.AutoMirrored.Filled.MenuBook,
                    checked = showReader,
                    onCheckedChange = {
                        showReader = it
                        prefs.showReaderButton = it
                        onChanged()
                    }
                )

                WidgetToggleRow(
                    title = strings.tc_item_reload,
                    subtitle = strings.tc_item_reload_sub,
                    icon = Icons.Default.Refresh,
                    checked = showReload,
                    onCheckedChange = {
                        showReload = it
                        prefs.showReloadButton = it
                        onChanged()
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Botón Restablecer
                TextButton(
                    onClick = {
                        widgetSize = ToolbarWidgetSize.BALANCED
                        prefs.toolbarWidgetSize = ToolbarWidgetSize.BALANCED
                        showBack = true
                        prefs.showBackButton = true
                        showForward = false
                        prefs.showForwardButton = false
                        showHome = false
                        prefs.showHomeButton = false
                        showTabs = true
                        prefs.showTabsButton = true
                        showMenu = true
                        prefs.showMenuButton = true
                        showNewTab = false
                        prefs.showNewTabButton = false
                        showShare = false
                        prefs.showShareButton = false
                        showBookmarks = false
                        prefs.showBookmarksButton = false
                        showCast = false
                        prefs.showCastButton = false
                        showDevTools = false
                        prefs.showDevToolsButton = false
                        showReader = true
                        prefs.showReaderButton = true
                        showReload = true
                        prefs.showReloadButton = true
                        showSecurity = true
                        prefs.showSecurityIndicator = true
                        onChanged()
                    },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Icon(Icons.Default.RestartAlt, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(strings.tc_reset, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(strings.general_done)
            }
        }
    )
}

@Composable
private fun MiniPreviewButton(
    icon: ImageVector,
    btnSize: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.Dp,
    tint: Color = MaterialTheme.colorScheme.onSurface
) {
    Box(
        modifier = Modifier
            .size(btnSize)
            .clip(CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(iconSize)
        )
    }
}

@Composable
private fun WidgetToggleRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 7.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
