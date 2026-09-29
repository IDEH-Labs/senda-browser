package org.senda.browser.ui.components

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
                    .background(Color.Black.copy(alpha = 0.42f))
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            )
        }

        // Contenido principal según el modo seleccionado
        if (currentLayout == ZenHomeLayout.INFORMATIONAL || (currentLayout == ZenHomeLayout.CUSTOM && displayNewsFeed && !displayShortcuts)) {
            // MODO INFORMATIVO: Feed continuo ético con selector en cabecera
            InformationalLayout(
                currentLayout = currentLayout,
                onOpenLayoutSelector = { showLayoutSelectorSheet = true },
                onSearch = onSearch,
                newsList = newsList
            )
        } else {
            // MODO ENFOCADO / INSPIRADOR / PERSONALIZADO
            FocusedOrInspirationalLayout(
                currentLayout = currentLayout,
                onOpenLayoutSelector = { showLayoutSelectorSheet = true },
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
                        .padding(bottom = 28.dp)
                ) {
                    Text(
                        text = "Diseño de Página de Inicio",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Elige la armonía entre concentración, arte libre o noticias éticas.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Opciones predefinidas (Inspiradas en presets modernos)
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
                        desc = "Fondos alusivos al Software Libre y fotografía libre profesional con noticias breves.",
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

                    // Opciones detalladas para Personalizado
                    if (currentLayout == ZenHomeLayout.CUSTOM) {
                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        Spacer(modifier = Modifier.height(12.dp))

                        CustomToggleRow("Fondo libre alusivo (GPL/CC0)", showWallpaper) {
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
                                            width = if (isSelected) 2.5.dp else 1.dp,
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
    currentLayout: ZenHomeLayout,
    onOpenLayoutSelector: () -> Unit,
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
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Barra superior con selector de diseño integrado elegantemente
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

        Spacer(modifier = Modifier.height(28.dp))

        // Identidad de marca Senda
        Image(
            painter = painterResource(id = R.drawable.ic_senda_logo),
            contentDescription = "Senda Logo",
            modifier = Modifier
                .size(68.dp)
                .clip(CircleShape)
                .border(
                    width = 1.5.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                    shape = CircleShape
                )
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "SENDA",
            fontSize = 24.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 5.sp,
            fontFamily = FontFamily.SansSerif,
            color = if (displayWallpaper) Color.White else MaterialTheme.colorScheme.primary
        )

        Text(
            text = "Navega tu propio camino · Privacidad sin concesiones",
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Normal,
            color = if (displayWallpaper) Color(0xFFDCDCDC) else MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Barra de búsqueda central estilizada
        OutlinedTextField(
            value = queryText,
            onValueChange = onQueryChange,
            placeholder = {
                Text(
                    "Buscar con DuckDuckGo…",
                    color = if (displayWallpaper) Color(0xFFD8D8D8) else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Buscar",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            },
            trailingIcon = {
                if (queryText.isNotBlank()) {
                    IconButton(
                        onClick = { onSearch(queryText) },
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Ir",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp)
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
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = if (displayWallpaper) MaterialTheme.colorScheme.surface.copy(alpha = 0.92f) else MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = if (displayWallpaper) MaterialTheme.colorScheme.surface.copy(alpha = 0.85f) else MaterialTheme.colorScheme.surface,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = if (displayWallpaper) Color.White.copy(alpha = 0.25f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
            ),
            modifier = Modifier.fillMaxWidth(0.96f)
        )

        // Accesos directos éticos con iconografía enriquecida
        if (displayShortcuts) {
            Spacer(modifier = Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Top
            ) {
                QuickLinkTile(
                    title = "DuckDuckGo",
                    url = "https://duckduckgo.com",
                    iconColor = Color(0xFFDE5833),
                    monogram = "DDG",
                    displayWallpaper = displayWallpaper,
                    onClick = onSearch
                )
                QuickLinkTile(
                    title = "Wikipedia",
                    url = "https://es.wikipedia.org",
                    iconColor = if (displayWallpaper) Color.White else Color(0xFF333333),
                    monogram = "W",
                    isSerif = true,
                    displayWallpaper = displayWallpaper,
                    onClick = onSearch
                )
                QuickLinkTile(
                    title = "F-Droid",
                    url = "https://f-droid.org",
                    iconColor = Color(0xFF0288D1),
                    iconVector = Icons.Default.Android,
                    displayWallpaper = displayWallpaper,
                    onClick = onSearch
                )
                QuickLinkTile(
                    title = "Archive",
                    url = "https://archive.org",
                    iconColor = Color(0xFF546E7A),
                    iconVector = Icons.Default.AccountBalance,
                    displayWallpaper = displayWallpaper,
                    onClick = onSearch
                )
                QuickLinkTile(
                    title = "MuyLinux",
                    url = "https://www.muylinux.com",
                    iconColor = Color(0xFF00B0FF),
                    iconVector = Icons.Default.Terminal,
                    displayWallpaper = displayWallpaper,
                    onClick = onSearch
                )
            }
        }

        // Titular ético destacado al pie (Modo Inspirador)
        if (featuredArticle != null) {
            Spacer(modifier = Modifier.height(28.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.96f)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onSearch(featuredArticle.url) },
                colors = CardDefaults.cardColors(
                    containerColor = if (displayWallpaper) {
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
                    } else {
                        MaterialTheme.colorScheme.surface
                    }
                ),
                border = BorderStroke(
                    width = 0.8.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = "📰 ${featuredArticle.source}",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "· ${featuredArticle.publishedDate}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = featuredArticle.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Leer noticia",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Crédito y licencia de fondo libre
        if (displayWallpaper) {
            Spacer(modifier = Modifier.height(20.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.Black.copy(alpha = 0.45f)
            ) {
                Text(
                    text = "🎨 ${activeWallpaper.name} · ${activeWallpaper.license}",
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
    onSearch: (String) -> Unit,
    newsList: List<EthicalNewsItem>
) {
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
        // Cabecera informativa con selector de modo
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Actualidad Ética & Soberanía",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Fuentes directas sin intermediarios algorítmicos",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    PresetSwitcherButton(
                        currentLayout = currentLayout,
                        onClick = onOpenLayoutSelector,
                        displayWallpaper = false
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Fila de chips de filtro por fuente ética
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
                                        "Todas" -> "Todas"
                                        "MuyLinux" -> "🐧 MuyLinux"
                                        "EFF" -> "🛡️ EFF"
                                        "FSF" -> "🦬 FSF"
                                        else -> source
                                    },
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
            }
        }

        // Lista de artículos éticos con diseño tipo tarjeta profesional
        items(filteredNews) { article ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onSearch(article.url) },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
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
    }
}

@Composable
fun QuickLinkTile(
    title: String,
    url: String,
    iconColor: Color,
    monogram: String? = null,
    iconVector: ImageVector? = null,
    isSerif: Boolean = false,
    displayWallpaper: Boolean = false,
    onClick: (String) -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(64.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick(url) }
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
                        tint = iconColor,
                        modifier = Modifier.size(24.dp)
                    )
                } else if (monogram != null) {
                    Text(
                        text = monogram,
                        fontSize = if (monogram.length > 1) 13.sp else 21.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = if (isSerif) FontFamily.Serif else FontFamily.SansSerif,
                        color = iconColor
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
            color = if (displayWallpaper) Color.White else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun PresetSwitcherButton(
    currentLayout: ZenHomeLayout,
    onClick: () -> Unit,
    displayWallpaper: Boolean = false
) {
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
                contentDescription = "Diseño de Inicio",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(15.dp)
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
