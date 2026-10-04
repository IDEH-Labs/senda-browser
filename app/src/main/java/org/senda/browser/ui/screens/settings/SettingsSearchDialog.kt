package org.senda.browser.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.senda.browser.core.PreferencesManager
import org.senda.browser.core.SendaStringPack

@Composable
fun SettingsSearchDialog(
    prefs: PreferencesManager,
    strings: SendaStringPack,
    onSettingsChanged: () -> Unit,
    onDismiss: () -> Unit
) {
    val searchEngines = listOf(
        "DuckDuckGo" to "https://duckduckgo.com/?q=",
        "SearXNG" to "https://searx.be/search?q=",
        "Startpage" to "https://www.startpage.com/sp/search?query=",
        "Qwant" to "https://www.qwant.com/?q=",
        "Brave" to "https://search.brave.com/search?q=",
        "Ecosia" to "https://www.ecosia.org/search?q=",
        "Google" to "https://www.google.com/search?q=",
        strings.dlg_search_custom_name to prefs.customSearchEngineUrl
    )
    var selectedEngine by remember {
        mutableStateOf(
            if (prefs.searchEngineName == "Personalizado" || prefs.searchEngineName == "Custom") {
                strings.dlg_search_custom_name
            } else {
                prefs.searchEngineName
            }
        )
    }
    var customUrl by remember { mutableStateOf(prefs.customSearchEngineUrl) }
    var suggestions by remember { mutableStateOf(prefs.searchSuggestionsEnabled) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.st_search_title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = strings.dlg_search_engine_desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                searchEngines.forEach { (name, url) ->
                    val isThisCustom = name == strings.dlg_search_custom_name
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedEngine = name
                                if (!isThisCustom) {
                                    prefs.searchEngineName = name
                                    prefs.customSearchEngineUrl = url
                                }
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedEngine == name,
                            onClick = {
                                selectedEngine = name
                                if (!isThisCustom) {
                                    prefs.searchEngineName = name
                                    prefs.customSearchEngineUrl = url
                                }
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(text = name, style = MaterialTheme.typography.bodyMedium)
                            if (name == "DuckDuckGo" || name == "SearXNG" || name == "Startpage") {
                                Text(
                                    text = strings.general_recommended_privacy,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }

                if (selectedEngine == strings.dlg_search_custom_name) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customUrl,
                        onValueChange = {
                            customUrl = it
                            prefs.searchEngineName = strings.dlg_search_custom_name
                            prefs.customSearchEngineUrl = it
                        },
                        label = { Text(strings.search_engine_custom_url) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(text = strings.dlg_search_suggestions, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = strings.dlg_search_suggestions_sub,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = suggestions,
                        onCheckedChange = {
                            suggestions = it
                            prefs.searchSuggestionsEnabled = it
                            onSettingsChanged()
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    prefs.searchEngineName = selectedEngine
                    if (selectedEngine == strings.dlg_search_custom_name) {
                        prefs.customSearchEngineUrl = customUrl
                    }
                    onSettingsChanged()
                    onDismiss()
                }
            ) {
                Text(strings.general_ok)
            }
        }
    )
}
