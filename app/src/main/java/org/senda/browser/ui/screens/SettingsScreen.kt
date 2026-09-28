package org.senda.browser.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.senda.browser.core.AppThemeMode
import org.senda.browser.core.PreferencesManager
import org.senda.browser.core.SendaGeckoEngine
import org.senda.browser.core.ToolbarPosition
import org.senda.browser.core.ZenHomeLayout
import org.senda.browser.ui.theme.SendaColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    prefs: PreferencesManager,
    onBack: () -> Unit,
    onSettingsChanged: () -> Unit
) {
    var toolbarPos by remember { mutableStateOf(prefs.toolbarPosition) }
    var zenLayout by remember { mutableStateOf(prefs.zenHomeLayout) }
    var themeMode by remember { mutableStateOf(prefs.themeMode) }
    var useSystemColor by remember { mutableStateOf(prefs.useSystemColor) }
    var accentHex by remember { mutableStateOf(prefs.accentColorHex) }
    var isOled by remember { mutableStateOf(prefs.isTrueOledBlack) }
    var requireBio by remember { mutableStateOf(prefs.requireBiometrics) }
    var enableAntiSnooping by remember { mutableStateOf(prefs.enableAntiSnooping) }
    var showDevTools by remember { mutableStateOf(prefs.showDevToolsButton) }
    var showFire by remember { mutableStateOf(prefs.showFireButton) }
    var extensionInstallStatus by remember { mutableStateOf<String?>(null) }

    // Selector de archivos para extensiones .xpi locales
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            SendaGeckoEngine.installExtension(
                uri = uri,
                onSuccess = { ext ->
                    extensionInstallStatus = "Extensión instalada con éxito."
                },
                onError = { err ->
                    extensionInstallStatus = "Error al instalar: ${err.message}"
                }
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ajustes & Soberanía Visual", color = MaterialTheme.colorScheme.onSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Atrás",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // SECCIÓN: DISEÑO Y APARIENCIA
            item {
                Text(
                    text = "DISEÑADOR DE INTERFAZ",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium
                )
            }

            // Posición de la barra
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Posición de la barra de navegación",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ToolbarPosition.values().forEach { pos ->
                                val isSelected = pos == toolbarPos
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        toolbarPos = pos
                                        prefs.toolbarPosition = pos
                                        onSettingsChanged()
                                    },
                                    label = {
                                        Text(
                                            when (pos) {
                                                ToolbarPosition.TOP -> "Arriba (Predeterminado)"
                                                ToolbarPosition.BOTTOM -> "Abajo (Móvil)"
                                                ToolbarPosition.FLOATING -> "Flotante"
                                            }
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // Diseño de la página de inicio (Nueva pestaña estilo Edge)
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Diseño de la página de inicio (Nueva pestaña)",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Enfocado (mínimo), Inspirador (fondo libre), Informativo (noticias éticas) o Personalizado.",
                            fontSize = 12.sp,
                            color = SendaColors.TextSecondary
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ZenHomeLayout.values().forEach { layout ->
                                val isSelected = layout == zenLayout
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        zenLayout = layout
                                        prefs.zenHomeLayout = layout
                                        onSettingsChanged()
                                    },
                                    label = {
                                        Text(
                                            when (layout) {
                                                ZenHomeLayout.FOCUSED -> "Enfocado"
                                                ZenHomeLayout.INSPIRATIONAL -> "Inspirador"
                                                ZenHomeLayout.INFORMATIONAL -> "Informativo"
                                                ZenHomeLayout.CUSTOM -> "Personalizado"
                                            }
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // Modo de tema visual (Claro / Oscuro / Sistema)
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Modo de apariencia (Tema)",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AppThemeMode.values().forEach { mode ->
                                val isSelected = mode == themeMode
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        themeMode = mode
                                        prefs.themeMode = mode
                                        onSettingsChanged()
                                    },
                                    label = {
                                        Text(
                                            when (mode) {
                                                AppThemeMode.SYSTEM -> "Sistema (Auto)"
                                                AppThemeMode.LIGHT -> "Claro"
                                                AppThemeMode.DARK -> "Oscuro"
                                            }
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // Color del sistema (Material You)
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Color del sistema (Material You)",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Sincroniza la paleta automáticamente con el fondo y estilo de Android",
                                fontSize = 12.sp,
                                color = SendaColors.TextSecondary
                            )
                        }
                        Switch(
                            checked = useSystemColor,
                            onCheckedChange = {
                                useSystemColor = it
                                prefs.useSystemColor = it
                                onSettingsChanged()
                            }
                        )
                    }
                }
            }

            // Color de acento personalizado (si no usa el color del sistema)
            if (!useSystemColor) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Color de Acento Manual",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            val palette = listOf(
                                "#00D2A0" to "Menta Senda",
                                "#4EE5B6" to "Cian",
                                "#FFB300" to "Ámbar",
                                "#2979FF" to "Azul Eléctrico",
                                "#FF4081" to "Rosa",
                                "#FFFFFF" to "Blanco Puro"
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                palette.forEach { (hex, _) ->
                                    val color = SendaColors.parseHexColor(hex)
                                    val isSelected = hex.equals(accentHex, ignoreCase = true)
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(color)
                                            .clickable {
                                                accentHex = hex
                                                prefs.accentColorHex = hex
                                                onSettingsChanged()
                                            }
                                            .border(
                                                width = if (isSelected) 3.dp else 1.dp,
                                                color = if (isSelected) Color.White else SendaColors.BorderSubtle,
                                                shape = CircleShape
                                            )
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Tema True OLED
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Negro Absoluto OLED (#000000)",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Apaga los píxeles de pantallas OLED para ahorrar batería (en modo oscuro)",
                                fontSize = 12.sp,
                                color = SendaColors.TextSecondary
                            )
                        }
                        Switch(
                            checked = isOled,
                            onCheckedChange = {
                                isOled = it
                                prefs.isTrueOledBlack = it
                                onSettingsChanged()
                            }
                        )
                    }
                }
            }

            // SECCIÓN: BOTONES PERSONALIZADOS
            item {
                Text(
                    text = "CONTROLES DE LA BARRA",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium
                )
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Mostrar botón DevTools (< / >)",
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Switch(
                                checked = showDevTools,
                                onCheckedChange = {
                                    showDevTools = it
                                    prefs.showDevToolsButton = it
                                    onSettingsChanged()
                                }
                            )
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = SendaColors.BorderSubtle)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Mostrar botón Fuego / Incinerar (🔥)",
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Switch(
                                checked = showFire,
                                onCheckedChange = {
                                    showFire = it
                                    prefs.showFireButton = it
                                    onSettingsChanged()
                                }
                            )
                        }
                    }
                }
            }

            // SECCIÓN: EXTENSIONES & LIBERTAD DE CÓDIGO
            item {
                Text(
                    text = "LIBERTAD DE EXTENSIONES (.XPI)",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium
                )
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Instalar extensión desde archivo local",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Instala cualquier extensión (.xpi) sin cuentas de Mozilla ni restricciones.",
                            fontSize = 12.sp,
                            color = SendaColors.TextSecondary
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = {
                                filePickerLauncher.launch("application/x-xpinstall")
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(imageVector = Icons.Default.Extension, contentDescription = null, tint = Color.Black)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Seleccionar archivo .xpi", color = Color.Black)
                        }
                        if (extensionInstallStatus != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = extensionInstallStatus ?: "",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // SECCIÓN: SEGURIDAD FÍSICA
            item {
                Text(
                    text = "BÓVEDA DE HARDWARE",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium
                )
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Bloqueo con Huella / Biometría",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Exige autenticación biométrica obligatoria al abrir Senda",
                                fontSize = 12.sp,
                                color = SendaColors.TextSecondary
                            )
                        }
                        Switch(
                            checked = requireBio,
                            onCheckedChange = {
                                requireBio = it
                                prefs.requireBiometrics = it
                                onSettingsChanged()
                            }
                        )
                    }
                }
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Anti-Espionaje Multitarea (FLAG_SECURE)",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Bloquea capturas de pantalla y oculta la vista previa en el menú de aplicaciones recientes.",
                                fontSize = 12.sp,
                                color = SendaColors.TextSecondary
                            )
                        }
                        Switch(
                            checked = enableAntiSnooping,
                            onCheckedChange = {
                                enableAntiSnooping = it
                                prefs.enableAntiSnooping = it
                                onSettingsChanged()
                            }
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}
