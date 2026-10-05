package org.senda.browser.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.senda.browser.core.LocalSendaStrings
import org.senda.browser.core.PreferencesManager
import org.senda.browser.core.ZenHomeLayout
import org.senda.browser.ui.model.BrowserTab
import org.senda.browser.ui.theme.SendaColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabsOverview(
    tabs: List<BrowserTab>,
    activeTabId: String,
    tabsViewMode: String = "GRID",
    prefs: PreferencesManager? = null,
    onSelectTab: (BrowserTab) -> Unit,
    onCloseTab: (BrowserTab) -> Unit,
    onNewTab: () -> Unit,
    onNewPrivateTab: () -> Unit = {},
    onCloseAll: () -> Unit,
    onDismiss: () -> Unit,
    onSettingsChanged: () -> Unit = {}
) {
    BackHandler(onBack = onDismiss)
    val strings = LocalSendaStrings.current

    var showConfirmCloseAllDialog by remember { mutableStateOf(false) }
    // Privadas y normales van por separado; se abre la sección de la pestaña activa
    var showPrivate by remember { mutableStateOf(tabs.firstOrNull { it.id == activeTabId }?.isPrivate == true) }
    val sectionTabs = tabs.filter { it.isPrivate == showPrivate }
    val privateCount = tabs.count { it.isPrivate }
    val newTabInSection = if (showPrivate) onNewPrivateTab else onNewTab

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = strings.tb_tabs,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "${sectionTabs.size}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = strings.back,
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    // Botón + exactamente como en el ejemplo: limpio, elegante y bien posicionado
                    IconButton(onClick = newTabInSection) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = if (showPrivate) strings.tabs_new_private else strings.tb_new_tab,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    // Menú de opciones (tres puntos verticales ⋮): Pestaña privada y Cerrar todas las pestañas
                    var showMoreMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { showMoreMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = strings.tabs_more_options,
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        DropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("${strings.tb_new_tab} (${strings.tabs_private})") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Security,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                },
                                onClick = {
                                    showMoreMenu = false
                                    onNewPrivateTab()
                                }
                            )

                            if (sectionTabs.isNotEmpty()) {
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (showPrivate) strings.tabs_close_all_private else strings.tabs_close_all,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.DeleteSweep,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    },
                                    onClick = {
                                        showMoreMenu = false
                                        showConfirmCloseAllDialog = true
                                    }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            PrimaryTabRow(
                selectedTabIndex = if (showPrivate) 1 else 0,
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                Tab(
                    selected = !showPrivate,
                    onClick = { showPrivate = false },
                    text = { Text("${strings.tabs_section_normal} (${tabs.size - privateCount})") },
                    icon = { Icon(Icons.Default.Tab, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
                Tab(
                    selected = showPrivate,
                    onClick = { showPrivate = true },
                    text = { Text("${strings.tabs_section_private} ($privateCount)") },
                    icon = { Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
            }
        Box(modifier = Modifier.fillMaxSize()) {
            if (sectionTabs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tab,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(64.dp)
                        )
                        Text(
                            text = if (showPrivate) strings.tabs_private_empty else strings.tabs_empty,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (showPrivate) {
                            Text(
                                text = strings.tabs_private_explainer,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 32.dp)
                            )
                        }
                        Button(onClick = newTabInSection) {
                            Icon(imageVector = Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (showPrivate) strings.tabs_new_private else strings.tb_new_tab)
                        }
                    }
                }
            } else {
                val isGrid = tabsViewMode != "LIST"
                val columns = if (isGrid) GridCells.Fixed(2) else GridCells.Fixed(1)
                val gridState = rememberLazyGridState()

                // Al abrir, mostrar la pestaña activa; al crear una nueva, ir hasta ella
                LaunchedEffect(showPrivate) {
                    val index = sectionTabs.indexOfFirst { it.id == activeTabId }
                    if (index >= 0) gridState.scrollToItem(index)
                }
                var previousCount by remember(showPrivate) { mutableIntStateOf(sectionTabs.size) }
                LaunchedEffect(sectionTabs.size) {
                    if (sectionTabs.size > previousCount) gridState.animateScrollToItem(sectionTabs.size - 1)
                    previousCount = sectionTabs.size
                }

                LazyVerticalGrid(
                    state = gridState,
                    columns = columns,
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(sectionTabs, key = { it.id }) { tab ->
                        val isSelected = tab.id == activeTabId
                        // Deslizar a un lado cierra la pestaña (el botón ✕ sigue disponible)
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { value ->
                                if (value != SwipeToDismissBoxValue.Settled) {
                                    onCloseTab(tab)
                                    true
                                } else false
                            },
                            positionalThreshold = { distance -> distance * 0.4f }
                        )
                        SwipeToDismissBox(
                            state = dismissState,
                            modifier = Modifier.animateItem(),
                            backgroundContent = {}
                        ) {
                            TabCardItem(
                                modifier = Modifier.graphicsLayer {
                                    alpha = 1f - (kotlin.math.abs(dismissState.progress.takeIf {
                                        dismissState.targetValue != SwipeToDismissBoxValue.Settled
                                    } ?: 0f) * 0.6f)
                                },
                                tab = tab,
                                isSelected = isSelected,
                                isGrid = isGrid,
                                onSelect = { onSelectTab(tab) },
                                onClose = { onCloseTab(tab) }
                            )
                        }
                    }
                }
            }
        }
        }
    }

    if (showConfirmCloseAllDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmCloseAllDialog = false },
            title = { Text(if (showPrivate) strings.tabs_close_all_private else strings.tabs_close_all) },
            text = { Text(if (showPrivate) strings.dlg_tabs_close_private_confirm_text else strings.dlg_tabs_close_all_confirm_text) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirmCloseAllDialog = false
                        // Si son todas las pestañas, el cierre completo; si no, solo las de esta sección
                        if (sectionTabs.size == tabs.size) onCloseAll() else sectionTabs.toList().forEach(onCloseTab)
                    }
                ) {
                    Text(if (showPrivate) strings.tabs_close_all_private else strings.tabs_close_all, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmCloseAllDialog = false }) {
                    Text(strings.general_cancel)
                }
            }
        )
    }
}

@Composable
private fun TabCardItem(
    modifier: Modifier = Modifier,
    tab: BrowserTab,
    isSelected: Boolean,
    isGrid: Boolean = true,
    onSelect: () -> Unit,
    onClose: () -> Unit
) {
    val strings = LocalSendaStrings.current
    val cardHeight = if (isGrid) 210.dp else 72.dp

    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(cardHeight)
            .shadow(
                elevation = if (isSelected) 5.dp else 1.5.dp,
                shape = RoundedCornerShape(14.dp),
                ambientColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else Color.Black.copy(alpha = 0.12f)
            )
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onSelect)
            .border(
                width = if (isSelected) 2.dp else 0.8.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                shape = RoundedCornerShape(14.dp)
            ),
        colors = CardDefaults.cardColors(
            // Opaco: con transparencia la sombra de la tarjeta se veía como un recuadro gris encima del título
            containerColor = if (isSelected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Cabecera de la tarjeta: Título + Botón Cerrar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (tab.isPrivate) Icons.Default.Security else if (tab.url == "about:blank" || tab.url.isBlank()) Icons.Default.Home else Icons.Default.Language,
                        contentDescription = null,
                        tint = if (tab.isPrivate) MaterialTheme.colorScheme.secondary else if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (tab.url == "about:blank" || tab.url.isBlank()) strings.tabs_new else tab.title.ifBlank { strings.tb_tabs },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 16.sp
                    )
                }
                // Zona táctil de 36 dp (antes 24 dp, difícil de acertar con el dedo)
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = strings.general_close,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            // Miniatura de la página (solo en memoria; nunca se guarda en disco)
            if (isGrid) {
                val thumbnail = tab.thumbnail
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (thumbnail != null) {
                        Image(
                            bitmap = remember(thumbnail) { thumbnail.asImageBitmap() },
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            alignment = Alignment.TopCenter,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = if (tab.isPrivate) Icons.Default.Security else if (tab.url == "about:blank" || tab.url.isBlank()) Icons.Default.Home else Icons.Default.Language,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }

            // Pie de la tarjeta: URL y Badge de Activa / Reposo / Privada
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (tab.url == "about:blank" || tab.url.isBlank()) "senda://zen" else tab.url.removePrefix("https://").removePrefix("http://"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (tab.isPrivate) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = strings.tabs_private,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
                if (tab.isCrashed) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = strings.general_disabled,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else if (isSelected) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = strings.general_active,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }
    }
}
