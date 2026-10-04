package org.senda.browser.ui.screens.settings

import org.senda.browser.ui.components.FitText
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.senda.browser.core.PreferencesManager
import org.senda.browser.core.SendaLocaleManager
import org.senda.browser.core.SendaStringPack
import org.senda.browser.ui.theme.resolveFontFamily

@Composable
fun SettingsAccessibilityDialog(
    prefs: PreferencesManager,
    strings: SendaStringPack,
    onOpenTypography: () -> Unit,
    onSettingsChanged: () -> Unit,
    onDismiss: () -> Unit
) {
    var forceZoom by rememberSaveable { mutableStateOf(prefs.forceEnableZoom) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Accessibility,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
        },
        title = { Text(strings.st_accessibility_title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(text = strings.dlg_force_zoom, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = strings.dlg_force_zoom_sub,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = forceZoom,
                        onCheckedChange = {
                            forceZoom = it
                            prefs.forceEnableZoom = it
                            onSettingsChanged()
                        }
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                Text(
                    text = strings.st_typography_title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = strings.dlg_accessibility_typography_note,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onOpenTypography,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(imageVector = Icons.Default.FormatSize, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(strings.st_typography_title)
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

@Composable
fun SettingsGnomeTypographyDialog(
    prefs: PreferencesManager,
    strings: SendaStringPack,
    onSettingsChanged: () -> Unit,
    onDismiss: () -> Unit
) {
    var selectedFont by rememberSaveable { mutableStateOf(prefs.uiFontFamily) }
    var fontScale by rememberSaveable { mutableIntStateOf(prefs.uiFontScalePercent) }
    var selectedHinting by rememberSaveable { mutableStateOf(prefs.fontHinting) }
    var selectedAntialiasing by rememberSaveable { mutableStateOf(prefs.fontAntialiasing) }
    var syncWeb by rememberSaveable { mutableStateOf(prefs.syncWebFontScale) }

    val previewFontFamily = remember(selectedFont) { resolveFontFamily(selectedFont) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.FormatSize,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(strings.st_typography_title)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth()
            ) {
                // 1. Tarjeta de Muestra en Vivo
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(12.dp)
                        ),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = strings.dlg_live_preview,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                // Nombre traducido del suavizado, no la clave interna («full»)
                                text = "$fontScale% • " + when (selectedHinting.uppercase()) {
                                    "NONE" -> strings.dlg_hinting_none
                                    "SLIGHT" -> strings.dlg_hinting_slight
                                    "MEDIUM" -> strings.dlg_hinting_medium
                                    else -> strings.dlg_hinting_full
                                },
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = strings.dlg_font_pangram,
                            fontFamily = previewFontFamily,
                            fontSize = (16 * (fontScale / 100f)).sp,
                            fontWeight = FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "0123456789 • { [ ( Aa Bb Gg Qq Rr Tt ) ] } — Senda",
                            fontFamily = previewFontFamily,
                            fontSize = (13 * (fontScale / 100f)).sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 2. Tipo de Letra de la Interfaz
                Text(
                    text = strings.dlg_font_family_title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                val fontOptions = listOf(
                    "SERIF" to strings.dlg_font_serif,
                    "CANTARELL" to "Cantarell",
                    "ROBOTO" to "Roboto",
                    "MONOSPACE" to "Monospace",
                    "SYSTEM" to strings.dlg_font_system,
                    "CONDENSED" to strings.dlg_font_condensed
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    fontOptions.chunked(2).forEach { rowPair ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowPair.forEach { (key, label) ->
                                val chipFontFamily = remember(key) { resolveFontFamily(key) }
                                FilterChip(
                                    modifier = Modifier.weight(1f),
                                    selected = selectedFont.equals(key, ignoreCase = true),
                                    onClick = {
                                        selectedFont = key
                                        prefs.uiFontFamily = key
                                        onSettingsChanged()
                                    },
                                    label = {
                                        FitText(
                                            text = label,
                                            fontFamily = chipFontFamily,
                                            maxSize = 13.sp,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                )
                            }
                            if (rowPair.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // 3. Factor de Escala de Texto
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = strings.dlg_font_scale_title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "$fontScale%",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 14.sp
                    )
                }
                Slider(
                    value = fontScale.toFloat(),
                    onValueChange = {
                        fontScale = it.toInt()
                        prefs.uiFontScalePercent = fontScale
                        if (syncWeb) {
                            prefs.fontScalePercent = fontScale
                        }
                    },
                    onValueChangeFinished = {
                        onSettingsChanged()
                    },
                    valueRange = 85f..150f,
                    steps = 12
                )
                // Cinco botones iguales: con SpaceBetween el quinto no cabía y se dibujaba deformado
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(90, 100, 115, 130, 150).forEach { preset ->
                        // Botón propio sin el relleno interno de los chips: en cinco chips no cabía el «%»
                        val selected = fontScale == preset
                        Surface(
                            onClick = {
                                fontScale = preset
                                prefs.uiFontScalePercent = preset
                                if (syncWeb) {
                                    prefs.fontScalePercent = preset
                                }
                                onSettingsChanged()
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                            border = if (selected) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            modifier = Modifier.weight(1f).height(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                FitText(
                                    text = "$preset%",
                                    maxSize = 12.sp,
                                    minSize = 9.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(text = strings.dlg_sync_web_scale, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = strings.dlg_sync_web_scale_sub,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = syncWeb,
                        onCheckedChange = {
                            syncWeb = it
                            prefs.syncWebFontScale = it
                            if (it) {
                                prefs.fontScalePercent = fontScale
                            }
                            onSettingsChanged()
                        }
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // 4. Optimización de Contornos (Hinting)
                Text(
                    text = strings.dlg_hinting_title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = strings.dlg_hinting_sub,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                val hintingOptions = listOf(
                    "NONE" to strings.dlg_hinting_none,
                    "SLIGHT" to strings.dlg_hinting_slight,
                    "MEDIUM" to strings.dlg_hinting_medium,
                    "FULL" to strings.dlg_hinting_full
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    hintingOptions.forEach { (hKey, hLabel) ->
                        FilterChip(
                            modifier = Modifier.weight(1f),
                            selected = selectedHinting.equals(hKey, ignoreCase = true),
                            onClick = {
                                selectedHinting = hKey
                                prefs.fontHinting = hKey
                                onSettingsChanged()
                            },
                            label = {
                                Text(
                                    text = hLabel,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // 5. Suavizado (Antialiasing)
                Text(
                    text = strings.dlg_aa_title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = strings.dlg_aa_sub,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                val aaOptions = listOf(
                    "SUBPIXEL" to strings.dlg_aa_subpixel,
                    "GRAYSCALE" to strings.dlg_aa_grayscale,
                    "NONE" to strings.dlg_hinting_none
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    aaOptions.forEach { (aaKey, aaLabel) ->
                        FilterChip(
                            modifier = Modifier.weight(1f),
                            selected = selectedAntialiasing.equals(aaKey, ignoreCase = true),
                            onClick = {
                                selectedAntialiasing = aaKey
                                prefs.fontAntialiasing = aaKey
                                onSettingsChanged()
                            },
                            label = {
                                Text(
                                    text = aaLabel,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
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

@Composable
fun SettingsLanguageDialog(
    context: Context,
    prefs: PreferencesManager,
    strings: SendaStringPack,
    onSettingsChanged: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.dlg_language_title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                SendaLocaleManager.supportedLanguages.forEach { option ->
                    val isSelected = prefs.appLanguage.equals(option.code, ignoreCase = true)
                    val displayTitle = if (option.code == "SYSTEM") {
                        "${strings.system_default} (${SendaLocaleManager.getSystemLanguageLabel(context)})"
                    } else {
                        option.displayName
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                prefs.appLanguage = option.code
                                SendaLocaleManager.applyLocale(context, option.code)
                                onSettingsChanged()
                                onDismiss()
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = {
                                prefs.appLanguage = option.code
                                SendaLocaleManager.applyLocale(context, option.code)
                                onSettingsChanged()
                                onDismiss()
                            }
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = displayTitle,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                            if (option.code == "SYSTEM") {
                                Text(
                                    text = "${strings.general_recommended} • ${SendaLocaleManager.getSystemLanguageLabel(context)}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.general_close)
            }
        }
    )
}

@Composable
fun SettingsTranslationsDialog(
    prefs: PreferencesManager,
    strings: SendaStringPack,
    onSettingsChanged: () -> Unit,
    onDismiss: () -> Unit
) {
    var offerTrans by remember { mutableStateOf(prefs.offerTranslations) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.dlg_translations_title) },
        text = {
            Column {
                Text(
                    text = strings.dlg_translations_desc,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = strings.st_translations_title, style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = offerTrans,
                        onCheckedChange = {
                            offerTrans = it
                            prefs.offerTranslations = it
                            onSettingsChanged()
                        }
                    )
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

@Composable
fun SettingsReaderModeDialog(
    prefs: PreferencesManager,
    strings: SendaStringPack,
    onSettingsChanged: () -> Unit,
    onDismiss: () -> Unit
) {
    var selectedTheme by remember { mutableStateOf(prefs.readerTheme) }
    var selectedFont by remember { mutableStateOf(prefs.readerFontFamily) }
    var selectedSize by remember { mutableFloatStateOf(prefs.readerFontSizePercent.toFloat()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.MenuBook,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(strings.tb_reader_mode, style = MaterialTheme.typography.titleMedium)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text(
                    text = strings.dlg_reader_mode_desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(14.dp))

                Text(strings.rm_visual_theme, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        Triple("SEPIA", strings.rm_theme_sepia, Color(0xFFFBF0D9)),
                        Triple("OLED_BLACK", strings.rm_theme_oled, Color(0xFF000000)),
                        Triple("LIGHT", strings.rm_theme_light, Color(0xFFFFFFFF))
                    ).forEach { (themeId, label, bg) ->
                        val isSelected = selectedTheme == themeId
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { selectedTheme = themeId },
                            color = bg,
                            border = BorderStroke(
                                if (isSelected) 2.dp else 1.dp,
                                if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.4f)
                            )
                        ) {
                            Box(
                                modifier = Modifier.padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (themeId == "OLED_BLACK") Color.White else Color(0xFF2C2416)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                Text(strings.rm_typography, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        Pair("SERIF", "Serif"),
                        Pair("SANS_SERIF", "Sans"),
                        Pair("MONOSPACE", "Mono")
                    ).forEach { (fontId, label) ->
                        val isSelected = selectedFont == fontId
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedFont = fontId },
                            label = {
                                Text(
                                    text = label,
                                    fontSize = 12.sp,
                                    fontFamily = resolveFontFamily(fontId)
                                )
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(strings.rm_text_size, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("${selectedSize.toInt()}%", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = selectedSize,
                    onValueChange = { selectedSize = it },
                    valueRange = 80f..180f,
                    steps = 4,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = strings.rm_tip,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    prefs.readerTheme = selectedTheme
                    prefs.readerFontFamily = selectedFont
                    prefs.readerFontSizePercent = selectedSize.toInt()
                    onSettingsChanged()
                    onDismiss()
                }
            ) {
                Text(strings.general_save)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.general_cancel)
            }
        }
    )
}
