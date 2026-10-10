package org.senda.browser.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.senda.browser.core.PreferencesManager
import org.senda.browser.core.SendaGeckoEngine
import org.senda.browser.core.SendaTorManager
import org.senda.browser.core.TorState
import org.senda.browser.core.SendaStringPack

@Composable
fun SettingsAutofillDialog(
    context: Context,
    strings: SendaStringPack,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.st_autofill_title) },
        text = {
            Column {
                Text(
                    text = strings.dlg_autofill_desc,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = {
                        try {
                            // Android screen to choose the password manager (Android 14+)
                            context.startActivity(
                                Intent(
                                    if (android.os.Build.VERSION.SDK_INT >= 34) "android.settings.CREDENTIAL_PROVIDER"
                                    else Settings.ACTION_SETTINGS
                                )
                            )
                        } catch (e: Exception) {
                            context.startActivity(Intent(Settings.ACTION_SETTINGS))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(strings.autofill_open_system, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.general_ok)
            }
        }
    )
}

@Composable
fun SettingsTorDialog(
    context: Context,
    prefs: PreferencesManager,
    strings: SendaStringPack,
    onSettingsChanged: () -> Unit,
    onDismiss: () -> Unit
) {
    var selectedMode by remember { mutableStateOf(prefs.proxyMode) }
    var hostInput by remember { mutableStateOf(prefs.proxyHost) }
    var portInput by remember { mutableStateOf(prefs.proxyPort.toString()) }
    var dnsRemote by remember { mutableStateOf(prefs.proxyDnsRemote) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.VpnKey,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(strings.st_tor_title, style = MaterialTheme.typography.titleMedium)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text(
                    text = strings.dlg_tor_desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(14.dp))

                // 1. Built-in Tor / Orbot
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { selectedMode = "TOR_ORBOT" },
                    color = if (selectedMode == "TOR_ORBOT") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, if (selectedMode == "TOR_ORBOT") MaterialTheme.colorScheme.primary else Color.Transparent)
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("🧅", fontSize = 20.sp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(strings.dlg_tor_integrated, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(
                                text = if (SendaTorManager.state == TorState.CONNECTED)
                                    strings.dlg_tor_status_connected
                                else if (SendaTorManager.state == TorState.STARTING)
                                    strings.dlg_tor_status_connecting
                                else
                                    strings.dlg_tor_integrated_sub,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        RadioButton(
                            selected = selectedMode == "TOR_ORBOT",
                            onClick = { selectedMode = "TOR_ORBOT" }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 2. Custom SOCKS5
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { selectedMode = "CUSTOM_SOCKS5" },
                    color = if (selectedMode == "CUSTOM_SOCKS5") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, if (selectedMode == "CUSTOM_SOCKS5") MaterialTheme.colorScheme.primary else Color.Transparent)
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("🛡️", fontSize = 20.sp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(strings.dlg_proxy_socks5, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(
                                text = strings.dlg_proxy_socks5_sub,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        RadioButton(
                            selected = selectedMode == "CUSTOM_SOCKS5",
                            onClick = { selectedMode = "CUSTOM_SOCKS5" }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 3. Off (direct)
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { selectedMode = "NONE" },
                    color = if (selectedMode == "NONE") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, if (selectedMode == "NONE") MaterialTheme.colorScheme.primary else Color.Transparent)
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("⚡", fontSize = 20.sp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(strings.dlg_proxy_off, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(
                                text = strings.dlg_proxy_off_sub,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        RadioButton(
                            selected = selectedMode == "NONE",
                            onClick = { selectedMode = "NONE" }
                        )
                    }
                }

                if (selectedMode == "CUSTOM_SOCKS5") {
                    Spacer(modifier = Modifier.height(14.dp))
                    OutlinedTextField(
                        value = hostInput,
                        onValueChange = { hostInput = it },
                        label = { Text(strings.dlg_proxy_host) },
                        placeholder = { Text("127.0.0.1") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = portInput,
                        onValueChange = { portInput = it },
                        label = { Text(strings.dlg_proxy_port) },
                        placeholder = { Text("9050") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(strings.dlg_proxy_remote_dns, fontSize = 12.sp)
                        Switch(
                            checked = dnsRemote,
                            onCheckedChange = { dnsRemote = it }
                        )
                    }
                    Text(
                        text = strings.dlg_proxy_remote_dns_sub,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (selectedMode == "TOR_ORBOT") {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = strings.dlg_tor_orbot_note,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val parsedPort = portInput.toIntOrNull() ?: 9050
                    prefs.proxyMode = selectedMode
                    if (selectedMode != "TOR_ORBOT") {
                        prefs.proxyHost = hostInput
                        prefs.proxyPort = parsedPort
                        prefs.proxyDnsRemote = dnsRemote
                    }
                    SendaTorManager.applyMode(context, prefs)
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

@Composable
fun SettingsPrivateBrowsingDialog(
    prefs: PreferencesManager,
    strings: SendaStringPack,
    onSettingsChanged: () -> Unit,
    onDismiss: () -> Unit
) {
    var alwaysPrivate by remember { mutableStateOf(prefs.alwaysPrivateMode) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.st_private_title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = strings.dlg_private_desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(text = strings.dlg_private_always, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = strings.dlg_private_always_sub,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = alwaysPrivate,
                        onCheckedChange = {
                            alwaysPrivate = it
                            prefs.alwaysPrivateMode = it
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
fun SettingsHttpsOnlyDialog(
    prefs: PreferencesManager,
    strings: SendaStringPack,
    onSettingsChanged: () -> Unit,
    onDismiss: () -> Unit
) {
    var httpsMode by remember { mutableStateOf(prefs.httpsOnlyMode) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.st_https_title) },
        text = {
            Column {
                Text(
                    text = strings.dlg_https_desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                listOf(
                    "ALL_TABS" to strings.dlg_https_all,
                    "PRIVATE_ONLY" to strings.dlg_https_private,
                    "DISABLED" to strings.dlg_https_disabled
                ).forEach { (code, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                httpsMode = code
                                prefs.httpsOnlyMode = code
                                onSettingsChanged()
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = httpsMode == code,
                            onClick = {
                                httpsMode = code
                                prefs.httpsOnlyMode = code
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
                Text(strings.general_ok)
            }
        }
    )
}

@Composable
fun SettingsDohDialog(
    prefs: PreferencesManager,
    strings: SendaStringPack,
    onSettingsChanged: () -> Unit,
    onDismiss: () -> Unit
) {
    var dohMode by remember { mutableStateOf(prefs.dnsOverHttpsMode) }
    var provider by remember { mutableStateOf(prefs.dohProvider) }
    var customUrl by remember { mutableStateOf(prefs.customDohUrl) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.st_doh_title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = strings.dlg_doh_desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = strings.dlg_doh_level, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                listOf(
                    "MAX_PROTECTION" to strings.dlg_doh_max,
                    "INCREASED" to strings.dlg_doh_increased,
                    "DISABLED" to strings.dlg_doh_disabled
                ).forEach { (mode, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                dohMode = mode
                                prefs.dnsOverHttpsMode = mode
                                onSettingsChanged()
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = dohMode == mode,
                            onClick = {
                                dohMode = mode
                                prefs.dnsOverHttpsMode = mode
                                onSettingsChanged()
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = label, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                if (dohMode != "DISABLED") {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    Text(text = strings.dlg_doh_provider, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(6.dp))
                    listOf(
                        "QUAD9" to strings.dlg_doh_quad9_desc,
                        "MULLVAD" to strings.dlg_doh_mullvad_desc,
                        "ADGUARD" to strings.dlg_doh_adguard_desc,
                        "CLOUDFLARE" to "Cloudflare (1.1.1.1)",
                        "CUSTOM" to strings.dlg_search_custom_name
                    ).forEach { (prov, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    provider = prov
                                    prefs.dohProvider = prov
                                    onSettingsChanged()
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = provider == prov,
                                onClick = {
                                    provider = prov
                                    prefs.dohProvider = prov
                                    onSettingsChanged()
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    if (provider == "CUSTOM") {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = customUrl,
                            onValueChange = {
                                customUrl = it
                                prefs.customDohUrl = it
                                onSettingsChanged()
                            },
                            label = { Text(strings.dlg_doh_custom_url_label) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
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
fun SettingsTrackingProtectionDialog(
    prefs: PreferencesManager,
    strings: SendaStringPack,
    onSettingsChanged: () -> Unit,
    onDismiss: () -> Unit
) {
    var level by remember { mutableStateOf(prefs.trackingProtectionLevel) }
    var blockFp by remember { mutableStateOf(prefs.blockFingerprinting) }
    var blockSocial by remember { mutableStateOf(prefs.blockSocialTrackers) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.st_tracking_title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = strings.st_tracking_sub,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                listOf(
                    "STRICT" to strings.dlg_tracking_strict,
                    "STANDARD" to strings.dlg_tracking_standard
                ).forEach { (key, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                level = key
                                prefs.trackingProtectionLevel = key
                                onSettingsChanged()
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = level == key,
                            onClick = {
                                level = key
                                prefs.trackingProtectionLevel = key
                                onSettingsChanged()
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = label, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(text = strings.dlg_block_fingerprinting, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = strings.dlg_block_fingerprinting_sub,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = blockFp,
                        onCheckedChange = {
                            blockFp = it
                            prefs.blockFingerprinting = it
                            onSettingsChanged()
                        }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(text = strings.dlg_block_social, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = strings.dlg_block_social_sub,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = blockSocial,
                        onCheckedChange = {
                            blockSocial = it
                            prefs.blockSocialTrackers = it
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
fun SettingsSecurityVaultDialog(
    prefs: PreferencesManager,
    strings: SendaStringPack,
    onSettingsChanged: () -> Unit,
    onDismiss: () -> Unit
) {
    var requireBio by remember { mutableStateOf(prefs.requireBiometrics) }
    var enableAntiSnooping by remember { mutableStateOf(prefs.enableAntiSnooping) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.st_security_vault_title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(text = strings.dlg_bio_lock, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = strings.dlg_bio_lock_sub,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(text = strings.dlg_anti_snooping, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = strings.dlg_anti_snooping_sub,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.general_done)
            }
        }
    )
}
