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
