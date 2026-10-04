package org.senda.browser.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.senda.browser.core.BookmarkItem
import org.senda.browser.core.HistoryItem
import org.senda.browser.core.LocalSendaStrings
import org.senda.browser.core.PreferencesManager
import org.senda.browser.core.SendaGeckoEngine
import org.senda.browser.ui.theme.SendaColors
import java.text.SimpleDateFormat
import java.util.*

/**
 * Barra horizontal de marcadores / favoritos estilo GNOME Libadwaita.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookmarksBar(
    prefs: PreferencesManager,
    currentUrl: String,
    currentTitle: String,
    onNavigate: (String) -> Unit,
    onOpenManager: () -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = org.senda.browser.core.LocalSendaStrings.current
    // Se relee al navegar, por si se editaron favoritos desde el gestor u otra pestaña
    var bookmarks by remember(currentUrl) { mutableStateOf(prefs.getBookmarks()) }
    var editingBookmark by remember { mutableStateOf<BookmarkItem?>(null) }
    val isCurrentBookmarked = remember(currentUrl, bookmarks) {
        prefs.isBookmarked(currentUrl)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Botón de acceso rápido a favoritos / añadir favorito
            IconButton(
                onClick = {
                    if (currentUrl.isNotBlank() && currentUrl != "about:blank") {
                        prefs.toggleBookmark(currentTitle, currentUrl)
                        bookmarks = prefs.getBookmarks()
                    } else {
                        onOpenManager()
                    }
                },
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(6.dp))
            ) {
                Icon(
                    imageVector = if (isCurrentBookmarked) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = strings.tb_bookmarks,
                    tint = if (isCurrentBookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            // Lista horizontal de favoritos
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                bookmarks.forEach { bookmark ->
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .combinedClickable(
                                onClick = { onNavigate(bookmark.url) },
                                onLongClick = { editingBookmark = bookmark }
                            ),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = BorderStroke(
                            0.5.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Language,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = bookmark.title,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(4.dp))

            // Botón para abrir el gestor completo de marcadores
            IconButton(
                onClick = onOpenManager,
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(6.dp))
            ) {
                Icon(
                    imageVector = Icons.Default.Bookmark,
                    contentDescription = strings.bm_manager_title,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }

    if (editingBookmark != null) {
        EditBookmarkDialog(
            bookmark = editingBookmark!!,
            onDismiss = { editingBookmark = null },
            onSave = { updatedTitle, updatedUrl ->
                prefs.updateBookmark(editingBookmark!!.id, updatedTitle, updatedUrl)
                bookmarks = prefs.getBookmarks()
                editingBookmark = null
            },
            onDelete = {
                prefs.deleteBookmark(editingBookmark!!.id)
                bookmarks = prefs.getBookmarks()
                editingBookmark = null
            }
        )
    }
}

/**
 * Diálogo gestor de Favoritos (Marcadores).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookmarksManagerDialog(
    prefs: PreferencesManager,
    currentUrl: String,
    currentTitle: String,
    onNavigate: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val strings = LocalSendaStrings.current
    var bookmarks by remember { mutableStateOf(prefs.getBookmarks()) }
    var searchQuery by remember { mutableStateOf("") }
    var editingBookmark by remember { mutableStateOf<BookmarkItem?>(null) }
    val isCurrentBookmarked = remember(currentUrl, bookmarks) { prefs.isBookmarked(currentUrl) }

    val filtered = remember(searchQuery, bookmarks) {
        if (searchQuery.isBlank()) bookmarks
        else bookmarks.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
                    it.url.contains(searchQuery, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(strings.bm_manager_title, style = MaterialTheme.typography.titleMedium)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Barra de búsqueda de favoritos
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(strings.bm_search_placeholder, fontSize = 13.sp) },
                    leadingIcon = {
                        Icon(imageVector = Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Añadir página actual si no está guardada
                if (currentUrl.isNotBlank() && currentUrl != "about:blank") {
                    FilledTonalButton(
                        onClick = {
                            prefs.toggleBookmark(currentTitle, currentUrl)
                            bookmarks = prefs.getBookmarks()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(
                            imageVector = if (isCurrentBookmarked) Icons.Default.StarBorder else Icons.Default.Star,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isCurrentBookmarked) strings.bm_remove_current else strings.bm_save_current)
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                HorizontalDivider(modifier = Modifier.padding(bottom = 6.dp))

                if (filtered.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (searchQuery.isBlank()) strings.bookmarks_empty else strings.dl_no_results,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(filtered, key = { it.id }) { item ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .combinedClickable(
                                        onClick = {
                                            onDismiss()
                                            onNavigate(item.url)
                                        },
                                        onLongClick = {
                                            editingBookmark = item
                                        }
                                    ),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Language,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.title,
                                            fontWeight = FontWeight.Medium,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = item.url,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    IconButton(
                                        onClick = { editingBookmark = item },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Edit,
                                            contentDescription = strings.general_edit,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(2.dp))
                                    IconButton(
                                        onClick = {
                                            prefs.deleteBookmark(item.id)
                                            bookmarks = prefs.getBookmarks()
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = strings.general_delete,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.general_done)
            }
        }
    )

    if (editingBookmark != null) {
        EditBookmarkDialog(
            bookmark = editingBookmark!!,
            onDismiss = { editingBookmark = null },
            onSave = { updatedTitle, updatedUrl ->
                prefs.updateBookmark(editingBookmark!!.id, updatedTitle, updatedUrl)
                bookmarks = prefs.getBookmarks()
                editingBookmark = null
            },
            onDelete = {
                prefs.deleteBookmark(editingBookmark!!.id)
                bookmarks = prefs.getBookmarks()
                editingBookmark = null
            }
        )
    }
}

/**
 * Diálogo para editar el título y la dirección web (URL) de un favorito.
 */
@Composable
fun EditBookmarkDialog(
    bookmark: BookmarkItem,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
    onDelete: () -> Unit
) {
    val strings = LocalSendaStrings.current
    var editTitle by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(bookmark.title, TextRange(bookmark.title.length)))
    }
    var editUrl by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(bookmark.url, TextRange(bookmark.url.length)))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(strings.bm_edit_title, style = MaterialTheme.typography.titleMedium)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = strings.bm_edit_desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = editTitle,
                    onValueChange = { editTitle = it },
                    label = { Text(strings.bm_name_label) },
                    leadingIcon = {
                        Icon(imageVector = Icons.Default.Title, contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                    trailingIcon = {
                        if (editTitle.text.isNotEmpty()) {
                            IconButton(onClick = { editTitle = TextFieldValue("", TextRange.Zero) }) {
                                Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    maxLines = 4,
                    singleLine = false
                )

                OutlinedTextField(
                    value = editUrl,
                    onValueChange = { editUrl = it },
                    label = { Text(strings.bm_url_label) },
                    leadingIcon = {
                        Icon(imageVector = Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                    trailingIcon = {
                        if (editUrl.text.isNotEmpty()) {
                            IconButton(onClick = { editUrl = TextFieldValue("", TextRange.Zero) }) {
                                Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    maxLines = 4,
                    singleLine = false
                )
            }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(strings.general_delete)
                }
                Spacer(modifier = Modifier.width(4.dp))
                TextButton(onClick = onDismiss) {
                    Text(strings.general_cancel)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (editUrl.text.isNotBlank()) {
                        onSave(editTitle.text.trim(), editUrl.text.trim())
                    }
                },
                enabled = editUrl.text.isNotBlank()
            ) {
                Text(strings.general_save)
            }
        }
    )
}

/**
 * Diálogo gestor de Historial de Navegación Local.
 */
@Composable
fun HistoryManagerDialog(
    prefs: PreferencesManager,
    onNavigate: (String) -> Unit,
    onOpenClearData: () -> Unit,
    onDismiss: () -> Unit
) {
    val strings = LocalSendaStrings.current
    var history by remember { mutableStateOf(prefs.getHistory()) }
    var searchQuery by remember { mutableStateOf("") }
    val dateFormat = remember { SimpleDateFormat("HH:mm - dd MMM", Locale.getDefault()) }

    val filtered = remember(searchQuery, history) {
        if (searchQuery.isBlank()) history
        else history.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
                    it.url.contains(searchQuery, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(strings.history_title, style = MaterialTheme.typography.titleMedium)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Buscador de historial
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(strings.hist_search_placeholder, fontSize = 13.sp) },
                    leadingIcon = {
                        Icon(imageVector = Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Botón para borrar datos de navegación
                FilledTonalButton(
                    onClick = onOpenClearData,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Icon(imageVector = Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(strings.cbd_title)
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                if (filtered.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (searchQuery.isBlank()) strings.history_empty else strings.dl_no_results,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(filtered, key = { it.id }) { item ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        onDismiss()
                                        onNavigate(item.url)
                                    },
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Schedule,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.title,
                                            fontWeight = FontWeight.Medium,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = item.url,
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = dateFormat.format(Date(item.timestamp)),
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = {
                                            prefs.deleteHistoryItem(item.id)
                                            history = prefs.getHistory()
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = strings.general_delete_from_history,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.general_done)
            }
        }
    )
}

/**
 * Diálogo estándar de "Borrar datos de navegación" (con selección de elementos e intervalos).
 */
@Composable
fun ClearBrowsingDataDialog(
    prefs: PreferencesManager,
    onDataCleared: () -> Unit,
    onDismiss: () -> Unit
) {
    val strings = LocalSendaStrings.current
    var clearHistory by remember { mutableStateOf(true) }
    var clearCookies by remember { mutableStateOf(true) }
    var clearCache by remember { mutableStateOf(true) }
    var selectedRange by remember { mutableLongStateOf(0L) } // 0L = Todo

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.DeleteSweep,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(strings.cbd_title)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = strings.cbd_desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Selector de intervalo
                Text(strings.cbd_time_range, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(6.dp))
                val ranges = listOf(
                    3600_000L to strings.cbd_range_hour,
                    86400_000L to strings.cbd_range_24h,
                    7 * 86400_000L to strings.cbd_range_7d,
                    0L to strings.cbd_range_all
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ranges.forEach { (time, label) ->
                        FilterChip(
                            modifier = Modifier.weight(1f),
                            selected = selectedRange == time,
                            onClick = { selectedRange = time },
                            label = {
                                Text(
                                    text = label,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                // Casillas de verificación de datos
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { clearHistory = !clearHistory }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = clearHistory, onCheckedChange = { clearHistory = it })
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(strings.cbd_item_history, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                        Text(strings.cbd_history_sub, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { clearCookies = !clearCookies }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = clearCookies, onCheckedChange = { clearCookies = it })
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(strings.cbd_item_cookies, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                        Text(strings.cbd_cookies_sub, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { clearCache = !clearCache }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = clearCache, onCheckedChange = { clearCache = it })
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(strings.cbd_item_cache, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                        Text(strings.cbd_cache_sub, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (clearHistory) {
                        prefs.clearHistoryRange(selectedRange)
                    }
                    if (clearCookies || clearCache) {
                        SendaGeckoEngine.clearBrowsingData(clearCookies = clearCookies, clearCache = clearCache)
                    }
                    onDataCleared()
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text(strings.cbd_btn_clear)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.general_cancel)
            }
        }
    )
}
