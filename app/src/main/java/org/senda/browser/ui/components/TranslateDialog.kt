package org.senda.browser.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.mozilla.geckoview.TranslationsController.Language
import org.mozilla.geckoview.TranslationsController.RuntimeTranslation
import org.senda.browser.core.LocalSendaStrings
import org.senda.browser.ui.model.BrowserTab
import kotlin.coroutines.resume

/**
 * Page translation with Firefox's engine, which runs on the phone. The first time for each language
 * pair the models are downloaded (from Mozilla's servers, over the same connection as the rest
 * of Senda); the page's text is never sent to any service.
 */
@Composable
fun TranslateDialog(tab: BrowserTab, onDismiss: () -> Unit) {
    val strings = LocalSendaStrings.current

    // null = querying; empty list = the engine is not available on this device
    var fromLanguages by remember { mutableStateOf<List<Language>?>(null) }
    var toLanguages by remember { mutableStateOf<List<Language>>(emptyList()) }
    LaunchedEffect(Unit) {
        val supported = awaitGecko { RuntimeTranslation.isTranslationsEngineSupported() } == true
        val languages = if (supported) awaitGecko { RuntimeTranslation.listSupportedLanguages() } else null
        toLanguages = languages?.toLanguages.orEmpty().sortedBy { languageName(it, strings.ui_language_tag) }
        fromLanguages = languages?.fromLanguages.orEmpty().sortedBy { languageName(it, strings.ui_language_tag) }
    }

    var from by remember { mutableStateOf<String?>(null) }
    var to by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(fromLanguages) {
        val sources = fromLanguages ?: return@LaunchedEffect
        from = matchLanguage(tab.pageLanguage, sources)
        to = matchLanguage(tab.userLanguage ?: java.util.Locale.getDefault().language, toLanguages)
    }

    // Size of the missing models; 0 = already downloaded, null = unknown
    var downloadBytes by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(from, to) {
        downloadBytes = null
        val f = from ?: return@LaunchedEffect
        val t = to ?: return@LaunchedEffect
        if (f != t) downloadBytes = awaitGecko { RuntimeTranslation.checkPairDownloadSize(f, t) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Translate, contentDescription = null) },
        title = { Text(strings.translate_title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val sources = fromLanguages
                when {
                    sources == null -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    }
                    sources.isEmpty() || toLanguages.isEmpty() -> Text(strings.translate_unsupported)
                    else -> {
                        LanguagePicker(strings.translate_from, from, sources) { from = it }
                        LanguagePicker(strings.translate_to, to, toLanguages) { to = it }
                        if (from != null && from == to) {
                            Text(strings.translate_same_language, color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            text = when (val bytes = downloadBytes) {
                                null -> strings.translate_privacy
                                0L -> strings.translate_privacy + " " + strings.translate_models_ready
                                else -> strings.translate_privacy + " " +
                                    strings.translate_models_download.format(org.senda.browser.core.SendaDownloadManager.formatFileSize(bytes))
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            val f = from
            val t = to
            TextButton(
                enabled = f != null && t != null && f != t && !fromLanguages.isNullOrEmpty(),
                onClick = {
                    if (f != null && t != null) tab.translatePage(f, t)
                    onDismiss()
                }
            ) { Text(strings.translate_action) }
        },
        dismissButton = {
            Row {
                if (tab.translatedTo != null) {
                    TextButton(onClick = {
                        tab.showOriginalPage()
                        onDismiss()
                    }) { Text(strings.translate_show_original) }
                }
                TextButton(onClick = onDismiss) { Text(strings.general_cancel) }
            }
        }
    )
}

@Composable
private fun LanguagePicker(label: String, selected: String?, options: List<Language>, onSelect: (String) -> Unit) {
    val strings = LocalSendaStrings.current
    var expanded by remember { mutableStateOf(false) }
    val selectedName = options.firstOrNull { it.code == selected }?.let { languageName(it, strings.ui_language_tag) } ?: "—"
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(selectedName, modifier = Modifier.weight(1f))
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { language ->
                    DropdownMenuItem(
                        text = { Text(languageName(language, strings.ui_language_tag)) },
                        onClick = {
                            onSelect(language.code)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

/** Looks for [tag] ("es-MX", "en") among the engine's languages: first exact and then by base language. */
private fun matchLanguage(tag: String?, options: List<Language>): String? {
    if (tag.isNullOrBlank()) return null
    options.firstOrNull { it.code.equals(tag, ignoreCase = true) }?.let { return it.code }
    val base = tag.substringBefore('-').lowercase()
    return options.firstOrNull { it.code.substringBefore('-').lowercase() == base }?.code
}

private suspend fun <T> awaitGecko(call: () -> org.mozilla.geckoview.GeckoResult<T>): T? =
    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        try {
            call().accept({ cont.resume(it) }, { cont.resume(null) })
        } catch (_: Exception) {
            cont.resume(null)
        }
    }

/** Name of the language in Senda's language (Gecko gives it in English); if Java does not know it, Gecko's */
private fun languageName(language: Language, uiLanguageTag: String): String {
    val uiLocale = java.util.Locale.forLanguageTag(uiLanguageTag)
    val name = java.util.Locale.forLanguageTag(language.code).getDisplayName(uiLocale)
    return if (name.isNotBlank() && !name.equals(language.code, ignoreCase = true)) {
        name.replaceFirstChar { it.titlecase(uiLocale) }
    } else {
        language.localizedDisplayName ?: language.code
    }
}
