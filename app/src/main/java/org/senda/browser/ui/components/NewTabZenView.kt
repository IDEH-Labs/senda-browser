package org.senda.browser.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.senda.browser.R
import org.senda.browser.core.PreferencesManager
import org.senda.browser.core.ZenHomeLayout
import org.senda.browser.core.news.EthicalNewsItem
import org.senda.browser.core.news.EthicalNewsRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewTabZenView(
    prefs: PreferencesManager,
    onSearch: (String) -> Unit,
    onSettingsChanged: () -> Unit = {}
) {
    var queryText by remember { mutableStateOf("") }
    var currentLayout by remember { mutableStateOf(prefs.zenHomeLayout) }
    var showWallpaper by remember { mutableStateOf(prefs.showZenWallpaper) }
    var showShortcuts by remember { mutableStateOf(prefs.showZenShortcuts) }
    var showNews by remember { mutableStateOf(prefs.showZenNewsFeed) }
    var selectedWallpaperId by remember { mutableStateOf(prefs.selectedWallpaperId) }

    var showLayoutSelectorSheet by remember { mutableStateOf(false) }

    val newsList by produceState(initialValue = emptyList<EthicalNewsItem>()) {
        value = EthicalNewsRepository.fetchEthicalNews()
    }

    val activeWallpaper = remember(selectedWallpaperId) {
        FreeWallpapers.getById(selectedWallpaperId)
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

    Box(modifier = Modifier.fillMaxSize()) {
        // Fondo artístico libre (GPL/CC0) si está activo en el modo elegido
        if (displayWallpaper) {
            FreeWallpaperBackground(wallpaper = activeWallpaper)
            // Capa sutil de oscurecimiento para legibilidad
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.40f))
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            )
        }

        // Botón superior de selector de diseño rápido (estilo Microsoft Edge)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.End
        ) {
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { showLayoutSelectorSheet = true },
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                tonalElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = when (currentLayout) {
                            ZenHomeLayout.FOCUSED -> Icons.Default.FilterCenterFocus
                            ZenHomeLayout.INSPIRATIONAL -> Icons.Default.AutoAwesome
                            ZenHomeLayout.INFORMATIONAL -> Icons.Default.Newspaper
                            ZenHomeLayout.CUSTOM -> Icons.Default.Tune
                        },
                        contentDescription = "Diseño de Inicio",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = when (currentLayout) {
                            ZenHomeLayout.FOCUSED -> "Enfocado"
                            ZenHomeLayout.INSPIRATIONAL -> "Inspirador"
                            ZenHomeLayout.INFORMATIONAL -> "Informativo"
                            ZenHomeLayout.CUSTOM -> "Personalizado"
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // Contenido principal según el modo seleccionado
        if (currentLayout == ZenHomeLayout.INFORMATIONAL || (currentLayout == ZenHomeLayout.CUSTOM && displayNewsFeed && !displayShortcuts)) {
            // MODO INFORMATIVO: Feed centrado con buscador superior
            InformationalLayout(
                queryText = queryText,
                onQueryChange = { queryText = it },
                onSearch = onSearch,
                newsList = newsList
            )
        } else {
            // MODO ENFOCADO / INSPIRADOR / PERSONALIZADO
            FocusedOrInspirationalLayout(
                queryText = queryText,
                onQueryChange = { queryText = it },
                onSearch = onSearch,
                displayShortcuts = displayShortcuts,
                displayWallpaper = displayWallpaper,
                activeWallpaper = activeWallpaper,
                featuredArticle = if (currentLayout == ZenHomeLayout.INSPIRATIONAL) newsList.firstOrNull() else null
            )
        }

        // Hoja inferior para elegir diseño (Edge Preset Selector)
        if (showLayoutSelectorSheet) {
            ModalBottomSheet(
                onDismissRequest = { showLayoutSelectorSheet = false },
                containerColor = MaterialTheme.colorScheme.surface,
                dragHandle = { BottomSheetDefaults.DragHandle() }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 24.dp)
                ) {
                    Text(
                        text = "Diseño de Página de Inicio",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Elige el equilibrio perfecto entre concentración, arte libre o noticias éticas.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Opciones predefinidas
                    LayoutOptionItem(
                        icon = Icons.Default.FilterCenterFocus,
                        title = "Enfocado",
                        desc = "La opción más minimalista: sin fondo ni noticias, solo cuadro de búsqueda y accesos rápidos.",
                        isSelected = currentLayout == ZenHomeLayout.FOCUSED,
                        onClick = {
                            currentLayout = ZenHomeLayout.FOCUSED
                            prefs.zenHomeLayout = ZenHomeLayout.FOCUSED
                            onSettingsChanged()
                            showLayoutSelectorSheet = false
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    LayoutOptionItem(
                        icon = Icons.Default.AutoAwesome,
                        title = "Inspirador",
                        desc = "Añade fondos alusivos al Software Libre y fotografía libre profesional con noticias breves.",
                        isSelected = currentLayout == ZenHomeLayout.INSPIRATIONAL,
                        onClick = {
                            currentLayout = ZenHomeLayout.INSPIRATIONAL
                            prefs.zenHomeLayout = ZenHomeLayout.INSPIRATIONAL
                            onSettingsChanged()
                            showLayoutSelectorSheet = false
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    LayoutOptionItem(
                        icon = Icons.Default.Newspaper,
                        title = "Informativo",
                        desc = "Lleva las noticias éticas al centro de la pantalla con un feed continuo de EFF, FSF y MuyLinux.",
                        isSelected = currentLayout == ZenHomeLayout.INFORMATIONAL,
                        onClick = {
                            currentLayout = ZenHomeLayout.INFORMATIONAL
                            prefs.zenHomeLayout = ZenHomeLayout.INFORMATIONAL
                            onSettingsChanged()
                            showLayoutSelectorSheet = false
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    LayoutOptionItem(
                        icon = Icons.Default.Tune,
                        title = "Personalizado",
                        desc = "Guarda y aplica tus preferencias: activa o desactiva fondos libres, accesos y noticias a gusto.",
                        isSelected = currentLayout == ZenHomeLayout.CUSTOM,
                        onClick = {
                            currentLayout = ZenHomeLayout.CUSTOM
                            prefs.zenHomeLayout = ZenHomeLayout.CUSTOM
                            onSettingsChanged()
                        }
                    )

                    // Opciones detalladas para Personalizado o selección de fondo
                    if (currentLayout == ZenHomeLayout.CUSTOM) {
                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                        Spacer(modifier = Modifier.height(12.dp))

                        CustomToggleRow("Fondo libre alusivo", showWallpaper) {
                            showWallpaper = it
                            prefs.showZenWallpaper = it
                            onSettingsChanged()
                        }
                        CustomToggleRow("Accesos directos éticos", showShortcuts) {
                            showShortcuts = it
                            prefs.showZenShortcuts = it
                            onSettingsChanged()
                        }
                        CustomToggleRow("Feed de noticias éticas", showNews) {
                            showNews = it
                            prefs.showZenNewsFeed = it
                            onSettingsChanged()
                        }
                    }

                    // Selector de fondo libre
                    if (currentLayout == ZenHomeLayout.INSPIRATIONAL || (currentLayout == ZenHomeLayout.CUSTOM && showWallpaper)) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "Colección de Fondos Libres (GPL & CC0)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FreeWallpapers.items.forEach { wallpaper ->
                                val isSelected = wallpaper.id == selectedWallpaperId
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(44.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            selectedWallpaperId = wallpaper.id
                                            prefs.selectedWallpaperId = wallpaper.id
                                            onSettingsChanged()
                                        }
                                        .border(
                                            width = if (isSelected) 2.dp else 1.dp,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                            shape = RoundedCornerShape(8.dp)
                                        ),
                                    color = wallpaper.colors.getOrNull(1) ?: Color.DarkGray
                                ) {}
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FocusedOrInspirationalLayout(
    queryText: String,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    displayShortcuts: Boolean,
    displayWallpaper: Boolean,
    activeWallpaper: FreeWallpaper,
    featuredArticle: EthicalNewsItem?
) {
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

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = "SENDA",
            style = MaterialTheme.typography.headlineMedium,
            letterSpacing = 4.sp,
            color = if (displayWallpaper) Color.White else MaterialTheme.colorScheme.primary
        )

        Text(
            text = "Navega tu propio camino. Sin rastros.",
            fontSize = 13.sp,
            color = if (displayWallpaper) Color(0xFFE0E0E0) else MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(28.dp))

        // Barra de búsqueda central
        OutlinedTextField(
            value = queryText,
            onValueChange = onQueryChange,
            placeholder = {
                Text(
                    "Buscar o escribir dirección web…",
                    color = if (displayWallpaper) Color(0xFFCCCCCC) else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
            },
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
                onSearch = { if (queryText.isNotBlank()) onSearch(queryText) }
            ),
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = if (displayWallpaper) MaterialTheme.colorScheme.surface.copy(alpha = 0.90f) else MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = if (displayWallpaper) MaterialTheme.colorScheme.surface.copy(alpha = 0.80f) else MaterialTheme.colorScheme.surface,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
            ),
            modifier = Modifier.fillMaxWidth(0.95f)
        )

        if (displayShortcuts) {
            Spacer(modifier = Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                EthicalQuickLink(title = "DuckDuckGo", url = "https://duckduckgo.com", onClick = onSearch)
                EthicalQuickLink(title = "Wikipedia", url = "https://wikipedia.org", onClick = onSearch)
                EthicalQuickLink(title = "F-Droid", url = "https://f-droid.org", onClick = onSearch)
            }
        }

        // Titular ético destacado al pie (Modo Inspirador)
        if (featuredArticle != null) {
            Spacer(modifier = Modifier.height(32.dp))
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onSearch(featuredArticle.url) },
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                tonalElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "📰 ${featuredArticle.source}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "· ${featuredArticle.publishedDate}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = featuredArticle.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Leer noticia",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        // Crédito de fondo libre
        if (displayWallpaper) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "🎨 ${activeWallpaper.name} · ${activeWallpaper.license}",
                fontSize = 10.sp,
                color = Color.White.copy(alpha = 0.70f)
            )
        }
    }
}

@Composable
fun InformationalLayout(
    queryText: String,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    newsList: List<EthicalNewsItem>
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 60.dp, bottom = 24.dp)
    ) {
        // Cabecera con barra de búsqueda
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                OutlinedTextField(
                    value = queryText,
                    onValueChange = onQueryChange,
                    placeholder = { Text("Buscar en la web…", fontSize = 14.sp) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Buscar",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Search
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onSearch = { if (queryText.isNotBlank()) onSearch(queryText) }
                    ),
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "NOTICIAS ÉTICAS Y SOBERANÍA",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "EFF · FSF · MuyLinux",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Lista de noticias éticas
        items(newsList) { article ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSearch(article.url) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = article.source,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Text(
                            text = article.publishedDate,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = article.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    if (article.snippet.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = article.snippet,
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LayoutOptionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    desc: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                shape = RoundedCornerShape(12.dp)
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(12.dp)
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
                Text(
                    text = desc,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Seleccionado",
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

@Composable
fun EthicalQuickLink(title: String, url: String, onClick: (String) -> Unit) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .clickable { onClick(url) },
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f)
    ) {
        Text(
            text = title,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
