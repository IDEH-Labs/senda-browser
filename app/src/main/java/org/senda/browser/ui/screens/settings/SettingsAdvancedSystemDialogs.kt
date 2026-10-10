package org.senda.browser.ui.screens.settings

import org.senda.browser.ui.components.FitText
import org.senda.browser.ui.components.rememberFitGroup
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.senda.browser.ui.components.CastHelper
import org.senda.browser.core.PreferencesManager
import org.senda.browser.core.SendaStringPack

@Composable
fun SettingsSitePermissionsDialog(
    prefs: PreferencesManager,
    strings: SendaStringPack,
    onSettingsChanged: () -> Unit,
    onDismiss: () -> Unit
) {
    var cam by remember { mutableStateOf(prefs.sitePermissionCamera) }
    var mic by remember { mutableStateOf(prefs.sitePermissionMic) }
    var loc by remember { mutableStateOf(prefs.sitePermissionLocation) }
    var notif by remember { mutableStateOf(prefs.sitePermissionNotifications) }
    var popups by remember { mutableStateOf(prefs.blockWebPopups) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.st_site_permissions_title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = strings.dlg_site_permissions_desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                val permNameGroup = rememberFitGroup(14.sp)
                listOf(
                    strings.dlg_perm_camera to (cam to { v: String -> cam = v; prefs.sitePermissionCamera = v }),
                    strings.dlg_perm_mic to (mic to { v: String -> mic = v; prefs.sitePermissionMic = v }),
                    strings.dlg_perm_location to (loc to { v: String -> loc = v; prefs.sitePermissionLocation = v }),
                    strings.dlg_perm_notifications to (notif to { v: String -> notif = v; prefs.sitePermissionNotifications = v })
                ).forEach { (name, pair) ->
                    val (currentVal, setter) = pair
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Weight and margin: "Notifications" collided with the "Ask" button
                        FitText(
                            text = name,
                            maxSize = 14.sp,
                            minSize = 11.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Start,
                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                            sharedSize = permNameGroup
                        )
                        Row {
                            FilterChip(
                                selected = currentVal == "ASK",
                                onClick = { setter("ASK"); onSettingsChanged() },
                                label = { Text(strings.dlg_ask, fontSize = 11.sp) }
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            FilterChip(
                                selected = currentVal == "BLOCK",
                                onClick = { setter("BLOCK"); onSettingsChanged() },
                                label = { Text(strings.dlg_block, fontSize = 11.sp) }
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
                        Text(text = strings.dlg_perm_popups, style = MaterialTheme.typography.bodyMedium)
                        Text(text = strings.dlg_perm_popups_sub, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = popups,
                        onCheckedChange = {
                            popups = it
                            prefs.blockWebPopups = it
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
fun SettingsOpenInAppsDialog(
    prefs: PreferencesManager,
    strings: SendaStringPack,
    onSettingsChanged: () -> Unit,
    onDismiss: () -> Unit
) {
    var mode by remember { mutableStateOf(prefs.openLinksInApps) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.st_open_in_apps_title) },
        text = {
            Column {
                Text(
                    text = strings.dlg_open_apps_desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                listOf(
                    "ASK" to strings.dlg_open_apps_ask,
                    "NEVER" to strings.dlg_open_apps_never,
                    "ALWAYS" to strings.dlg_open_apps_always
                ).forEach { (key, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                mode = key
                                prefs.openLinksInApps = key
                                onSettingsChanged()
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = mode == key,
                            onClick = {
                                mode = key
                                prefs.openLinksInApps = key
                                onSettingsChanged()
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = label, style = MaterialTheme.typography.bodyMedium)
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
fun SettingsDownloadSettingsDialog(
    prefs: PreferencesManager,
    strings: SendaStringPack,
    onSettingsChanged: () -> Unit,
    onDismiss: () -> Unit
) {
    var askWhere by remember { mutableStateOf(prefs.askDownloadLocation) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.st_download_settings_title) },
        text = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(text = strings.dlg_download_ask, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = strings.dlg_download_ask_sub,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = askWhere,
                        onCheckedChange = {
                            askWhere = it
                            prefs.askDownloadLocation = it
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
fun SettingsSendaLabsDialog(
    prefs: PreferencesManager,
    strings: SendaStringPack,
    onSettingsChanged: () -> Unit,
    onDismiss: () -> Unit
) {
    var userCss by remember { mutableStateOf(prefs.userCustomCss) }
    var userJs by remember { mutableStateOf(prefs.userCustomScript) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.st_senda_labs_title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = strings.dlg_senda_labs_desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                Text(text = strings.dlg_senda_labs_css, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = userCss,
                    onValueChange = { userCss = it },
                    placeholder = { Text(strings.dlg_senda_labs_css_hint, style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)) },
                    // Code: monospaced font, as in any editor
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))
                Text(text = strings.dlg_senda_labs_js, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = userJs,
                    onValueChange = { userJs = it },
                    placeholder = { Text(strings.dlg_senda_labs_js_hint, style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)) },
                    // Code: monospaced font, as in any editor
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    prefs.userCustomCss = userCss
                    prefs.userCustomScript = userJs
                    // Saved only here: the Labs extension registers the new CSS and script for the next pages
                    org.senda.browser.core.SendaGeckoEngine.applyLabs(prefs)
                    onSettingsChanged()
                    onDismiss()
                }
            ) {
                Text(strings.general_save)
            }
        }
    )
}

@Composable
fun SettingsAboutDialog(
    strings: SendaStringPack,
    onDismiss: () -> Unit
) {
    // The installed version, read from Android (it used to be a fixed "0.1.0-alpha (Build 1)")
    val context = androidx.compose.ui.platform.LocalContext.current
    val (versionName, versionCode) = androidx.compose.runtime.remember {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            (info.versionName ?: "?") to androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info)
        }.getOrDefault("?" to 0L)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.st_about_title) },
        text = {
            // Version, principles and licenses in one place
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = strings.dlg_about_senda_build.format(versionName, versionCode),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = strings.dlg_about_content, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = strings.dlg_about_license_details,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(10.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = strings.st_privacy_commitment_title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = strings.dlg_privacy_principles, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = strings.st_licenses_title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = strings.dlg_about_third_party, style = MaterialTheme.typography.bodyMedium)
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
fun SettingsChromecastDialog(
    context: Context,
    prefs: PreferencesManager,
    strings: SendaStringPack,
    onSettingsChanged: () -> Unit,
    onDismiss: () -> Unit
) {
    var showCast by remember { mutableStateOf(prefs.showCastButton) }
    var tvMode by remember { mutableStateOf(prefs.tvModeEnabled) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Cast,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
        },
        title = { Text(strings.st_chromecast_title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = strings.dlg_chromecast_desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(text = strings.dlg_cast_show_bar, style = MaterialTheme.typography.bodyMedium)
                        Text(text = strings.dlg_cast_show_bar_sub, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = showCast,
                        onCheckedChange = {
                            showCast = it
                            prefs.showCastButton = it
                            onSettingsChanged()
                        }
                    )
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(text = strings.dlg_tv_mode, style = MaterialTheme.typography.bodyMedium)
                        Text(text = strings.dlg_tv_mode_sub, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = tvMode,
                        onCheckedChange = {
                            tvMode = it
                            prefs.tvModeEnabled = it
                            org.senda.browser.core.cast.SendaTvMode.evaluate()
                            onSettingsChanged()
                        }
                    )
                }
                HorizontalDivider()
                Button(
                    onClick = {
                        CastHelper.openSystemCast(context)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(imageVector = Icons.Default.Tv, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(strings.dlg_cast_open_system)
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
