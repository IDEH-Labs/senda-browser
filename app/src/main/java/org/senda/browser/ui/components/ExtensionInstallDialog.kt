package org.senda.browser.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.senda.browser.core.LocalSendaStrings
import org.senda.browser.core.SendaGeckoEngine

/**
 * Confirmation before installing an extension that does not ship with Senda: shows what it will be able to do and
 * installs nothing until the user accepts.
 */
@Composable
fun ExtensionInstallDialog(request: SendaGeckoEngine.ExtensionInstallRequest) {
    val strings = LocalSendaStrings.current
    val name = request.extension.metaData.name?.takeIf { it.isNotBlank() } ?: request.extension.id
    val allSites = request.origins.any { it == "<all_urls>" || it.startsWith("*://*/") || it == "*://*" }
    val sites = request.origins.filterNot { it == "<all_urls>" || it.startsWith("*://*/") || it == "*://*" }

    AlertDialog(
        onDismissRequest = { SendaGeckoEngine.resolveExtensionInstall(false) },
        icon = { Icon(Icons.Default.Extension, contentDescription = null) },
        title = { Text(strings.ext_install_title.format(name), style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (!allSites && sites.isEmpty() && request.permissions.isEmpty()) {
                    Text(strings.ext_install_no_perms)
                } else {
                    Text(strings.ext_install_desc, fontWeight = FontWeight.SemiBold)
                    if (allSites) Text("• " + strings.ext_install_all_sites, color = MaterialTheme.colorScheme.error)
                    sites.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    request.permissions.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = {
            Button(onClick = { SendaGeckoEngine.resolveExtensionInstall(true) }) { Text(strings.ext_install_confirm) }
        },
        dismissButton = {
            TextButton(onClick = { SendaGeckoEngine.resolveExtensionInstall(false) }) { Text(strings.general_cancel) }
        }
    )
}
