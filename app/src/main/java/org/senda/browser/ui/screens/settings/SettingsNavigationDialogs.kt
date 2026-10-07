package org.senda.browser.ui.screens.settings

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.senda.browser.core.PreferencesManager
import org.senda.browser.core.SendaStringPack
import org.senda.browser.core.ZenHomeLayout
import org.senda.browser.ui.components.FreeWallpapers

@Composable
fun SettingsTabsDialog(
    prefs: PreferencesManager,
    strings: SendaStringPack,
    onSettingsChanged: () -> Unit,
    onDismiss: () -> Unit
) {
    var startupMode by remember { mutableStateOf(prefs.startupMode) }
    var closePolicy by remember { mutableStateOf(prefs.closeTabsPolicy) }
    var viewMode by remember { mutableStateOf(prefs.tabsViewMode) }
    var openInBackground by remember { mutableStateOf(prefs.openLinksInBackground) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.st_tabs_title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = strings.dlg_startup_title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                listOf(
                    "HOME" to strings.dlg_startup_home,
                    "RESUME" to strings.dlg_startup_resume,
                    "CLEAN" to strings.dlg_startup_clean
                ).forEach { (key, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                startupMode = key
                                prefs.startupMode = key
                                onSettingsChanged()
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = startupMode == key,
                            onClick = {
                                startupMode = key
                                prefs.startupMode = key
                                onSettingsChanged()
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = label, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                Text(
                    text = strings.dlg_tabs_autoclose_title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                listOf(
                    "MANUAL" to strings.summary_tabs_manual,
                    "ON_EXIT" to strings.summary_tabs_on_exit,
                    "AFTER_ONE_DAY" to strings.summary_tabs_one_day,
                    "AFTER_ONE_WEEK" to strings.summary_tabs_one_week
                ).forEach { (key, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                closePolicy = key
                                prefs.closeTabsPolicy = key
                                onSettingsChanged()
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = closePolicy == key,
                            onClick = {
                                closePolicy = key
                                prefs.closeTabsPolicy = key
                                onSettingsChanged()
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = label, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                Text(
                    text = strings.dlg_tabs_layout,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = viewMode == "GRID",
                        onClick = {
                            viewMode = "GRID"
                            prefs.tabsViewMode = "GRID"
                            onSettingsChanged()
                        },
                        label = { Text(strings.dlg_tabs_grid) }
                    )
                    FilterChip(
                        selected = viewMode == "LIST",
                        onClick = {
                            viewMode = "LIST"
                            prefs.tabsViewMode = "LIST"
                            onSettingsChanged()
                        },
                        label = { Text(strings.dlg_tabs_list) }
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        // This switch controls openLinksInBackground; it used to say "Close tabs on exit",
                        // which is already one of the options above
                        Text(text = strings.tabs_open_background, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = strings.tabs_open_background_sub,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = openInBackground,
                        onCheckedChange = {
                            openInBackground = it
                            prefs.openLinksInBackground = it
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
fun SettingsHomeDialog(
    context: Context,
    prefs: PreferencesManager,
    strings: SendaStringPack,
    onSettingsChanged: () -> Unit,
    onDismiss: () -> Unit
) {
    var zenLayout by remember { mutableStateOf(prefs.zenHomeLayout) }
    var showWallpaper by remember { mutableStateOf(prefs.showZenWallpaper) }
    var showShortcuts by remember { mutableStateOf(prefs.showZenShortcuts) }
    var showNews by remember { mutableStateOf(prefs.showZenNewsFeed) }
    var selectedWp by remember { mutableStateOf(prefs.selectedWallpaperId) }
    var dimPct by remember { mutableIntStateOf(prefs.wallpaperDimPercent) }
    var customWpPath by remember { mutableStateOf(prefs.customWallpaperPath) }

    val settingsCustomWpLauncher = rememberLauncherForActivityResult(
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
                customWpPath = target.absolutePath
                FreeWallpapers.choose(prefs, "custom_user")
                selectedWp = "custom_user"
                onSettingsChanged()
            } catch (_: Exception) {}
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.st_home_title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = strings.home_design_title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                ZenHomeLayout.values().forEach { layout ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                zenLayout = layout
                                prefs.zenHomeLayout = layout
                                onSettingsChanged()
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = zenLayout == layout,
                            onClick = {
                                zenLayout = layout
                                prefs.zenHomeLayout = layout
                                onSettingsChanged()
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = when (layout) {
                                    ZenHomeLayout.FOCUSED -> strings.preset_focused
                                    ZenHomeLayout.INSPIRATIONAL -> strings.preset_inspirational
                                    ZenHomeLayout.INFORMATIONAL -> strings.preset_informational
                                    ZenHomeLayout.CUSTOM -> strings.preset_custom
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = when (layout) {
                                    ZenHomeLayout.FOCUSED -> strings.preset_focused_desc
                                    ZenHomeLayout.INSPIRATIONAL -> strings.preset_inspirational_desc
                                    ZenHomeLayout.INFORMATIONAL -> strings.preset_informational_desc
                                    ZenHomeLayout.CUSTOM -> strings.preset_custom_desc
                                },
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (zenLayout == ZenHomeLayout.CUSTOM || zenLayout == ZenHomeLayout.INSPIRATIONAL) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    Text(
                        text = strings.home_free_wallpapers,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // Fixed or rotating wallpaper
                    var rotation by remember { mutableIntStateOf(prefs.wallpaperRotationMinutes) }
                    Text(text = strings.wp_rotation_title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        listOf(
                            0 to strings.wp_rotation_fixed,
                            -1 to strings.wp_rotation_new_tab,
                            1 to "1 min", 5 to "5 min", 15 to "15 min", 60 to "1 h"
                        ).forEach { (minutes, label) ->
                            FilterChip(
                                selected = rotation == minutes,
                                onClick = {
                                    rotation = minutes
                                    prefs.wallpaperRotationMinutes = minutes
                                    // When rotation is turned on it starts with the chosen wallpaper
                                    if (minutes != 0) FreeWallpapers.choose(prefs, selectedWp.takeIf { it != "custom_user" } ?: FreeWallpapers.items.first().id)
                                    onSettingsChanged()
                                },
                                label = { Text(label, fontSize = 12.sp) }
                            )
                        }
                    }
                    Text(
                        text = if (rotation == 0) strings.wp_rotation_fixed_hint else strings.wp_rotation_on_hint,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                try {
                                    settingsCustomWpLauncher.launch("image/*")
                                } catch (_: Exception) {}
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedWp == "custom_user",
                            onClick = {
                                if (customWpPath != null) {
                                    selectedWp = "custom_user"
                                    FreeWallpapers.choose(prefs, "custom_user"); rotation = 0
                                    onSettingsChanged()
                                } else {
                                    try {
                                        settingsCustomWpLauncher.launch("image/*")
                                    } catch (_: Exception) {}
                                }
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (customWpPath != null) strings.home_custom_label else strings.home_custom_wallpaper,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (selectedWp == "custom_user") FontWeight.Bold else FontWeight.Normal
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    imageVector = Icons.Default.AddPhotoAlternate,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    FreeWallpapers.items.forEach { wp ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedWp = wp.id
                                    FreeWallpapers.choose(prefs, wp.id)
                                    onSettingsChanged()
                                }
                                .padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedWp == wp.id,
                                onClick = {
                                    selectedWp = wp.id
                                    FreeWallpapers.choose(prefs, wp.id)
                                    onSettingsChanged()
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(text = wp.name, style = MaterialTheme.typography.bodyMedium, fontWeight = if (selectedWp == wp.id) FontWeight.Bold else FontWeight.Normal)
                                Text(text = "${wp.category} · ${wp.author} · ${wp.license}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                wp.sourceUrl?.let { Text(text = it, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)) }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = strings.home_dim_contrast, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(text = "$dimPct%", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    Slider(
                        value = dimPct.toFloat(),
                        onValueChange = {
                            dimPct = it.toInt()
                            prefs.wallpaperDimPercent = dimPct
                            onSettingsChanged()
                        },
                        valueRange = 0f..80f,
                        steps = 15,
                        modifier = Modifier.fillMaxWidth()
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = strings.home_show_shortcuts, style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = showShortcuts,
                            onCheckedChange = {
                                showShortcuts = it
                                prefs.showZenShortcuts = it
                                onSettingsChanged()
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = strings.home_show_news, style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = showNews,
                            onCheckedChange = {
                                showNews = it
                                prefs.showZenNewsFeed = it
                                onSettingsChanged()
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
