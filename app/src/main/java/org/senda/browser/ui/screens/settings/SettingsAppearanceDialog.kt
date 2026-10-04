package org.senda.browser.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import org.senda.browser.core.SendaStringPack
import org.senda.browser.ui.theme.SendaColors

@Composable
fun SettingsAppearanceDialog(
    prefs: PreferencesManager,
    strings: SendaStringPack,
    onSettingsChanged: () -> Unit,
    onDismiss: () -> Unit
) {
    var themeMode by remember { mutableStateOf(prefs.themeMode) }
    var useSystemColor by remember { mutableStateOf(prefs.useSystemColor) }
    var accentHex by remember { mutableStateOf(prefs.accentColorHex) }
    var isOled by remember { mutableStateOf(prefs.isTrueOledBlack) }
    var showBookmarksBar by remember { mutableStateOf(prefs.showBookmarksBar) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.st_appearance_title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = strings.dlg_theme_title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppThemeMode.values().forEach { mode ->
                        FilterChip(
                            selected = mode == themeMode,
                            onClick = {
                                themeMode = mode
                                prefs.themeMode = mode
                                onSettingsChanged()
                            },
                            label = {
                                Text(
                                    when (mode) {
                                        AppThemeMode.SYSTEM -> strings.dlg_theme_system
                                        AppThemeMode.LIGHT -> strings.dlg_theme_light
                                        AppThemeMode.DARK -> strings.dlg_theme_dark
                                    }
                                )
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(text = strings.dlg_theme_oled, style = MaterialTheme.typography.bodyMedium)
                        Text(text = strings.dlg_theme_oled_sub, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(text = strings.dlg_dynamic_color, style = MaterialTheme.typography.bodyMedium)
                        Text(text = strings.dlg_dynamic_color_sub, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

                if (!useSystemColor) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(text = strings.dlg_manual_accent, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(8.dp))
                    val palette = listOf(
                        "#00D2A0" to strings.color_mint,
                        "#4EE5B6" to strings.color_cyan,
                        "#FFB300" to strings.color_amber,
                        "#2979FF" to strings.color_blue,
                        "#FF4081" to strings.color_pink,
                        "#FFFFFF" to strings.color_white
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
                                    .size(34.dp)
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

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(text = strings.dlg_bookmarks_bar, style = MaterialTheme.typography.bodyMedium)
                        Text(text = strings.dlg_bookmarks_bar_sub, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = showBookmarksBar,
                        onCheckedChange = {
                            showBookmarksBar = it
                            prefs.showBookmarksBar = it
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
