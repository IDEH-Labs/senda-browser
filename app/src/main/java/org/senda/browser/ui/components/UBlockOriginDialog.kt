package org.senda.browser.ui.components

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.senda.browser.core.LocalSendaStrings
import org.senda.browser.core.SendaGeckoEngine

@Composable
fun UBlockOriginDialog(
    onNavigate: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val strings = LocalSendaStrings.current
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        SendaGeckoEngine.refreshExtensions {
            refreshKey++
        }
    }

    val filtersUrl = remember(refreshKey, SendaGeckoEngine.installedExtensions.size) {
        SendaGeckoEngine.getUBlockFiltersUrl()
    }
    val dashboardUrl = remember(refreshKey, SendaGeckoEngine.installedExtensions.size) {
        SendaGeckoEngine.getUBlockDashboardUrl()
    }
    val popupUrl = remember(refreshKey, SendaGeckoEngine.installedExtensions.size) {
        SendaGeckoEngine.getUBlockPopupUrl()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(strings.ublock_dlg_title, style = MaterialTheme.typography.titleMedium)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = strings.ublock_dlg_desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Opción 1: Filtros Regionales (AR, CO, FR...)
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            val target = filtersUrl ?: SendaGeckoEngine.getUBlockFiltersUrl()
                            if (target != null) {
                                onNavigate(target)
                            } else {
                                Toast.makeText(context, "Conectando con uBlock Origin...", Toast.LENGTH_SHORT).show()
                                SendaGeckoEngine.refreshExtensions {
                                    refreshKey++
                                    val fresh = SendaGeckoEngine.getUBlockFiltersUrl()
                                    if (fresh != null) onNavigate(fresh)
                                }
                            }
                        },
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(strings.ublock_dlg_regional_title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(strings.ublock_dlg_regional_desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Opción 2: Escudo Rápido Móvil
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            val target = popupUrl ?: dashboardUrl ?: SendaGeckoEngine.getUBlockPopupUrl() ?: SendaGeckoEngine.getUBlockDashboardUrl()
                            if (target != null) {
                                onNavigate(target)
                            } else {
                                Toast.makeText(context, "Conectando con uBlock Origin...", Toast.LENGTH_SHORT).show()
                                SendaGeckoEngine.refreshExtensions {
                                    refreshKey++
                                    val fresh = SendaGeckoEngine.getUBlockPopupUrl() ?: SendaGeckoEngine.getUBlockDashboardUrl()
                                    if (fresh != null) onNavigate(fresh)
                                }
                            }
                        },
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(strings.ublock_dlg_shield_title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(strings.ublock_dlg_shield_desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Opción 3: Panel de Control Avanzado
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            val target = dashboardUrl ?: SendaGeckoEngine.getUBlockDashboardUrl()
                            if (target != null) {
                                onNavigate(target)
                            } else {
                                Toast.makeText(context, "Conectando con uBlock Origin...", Toast.LENGTH_SHORT).show()
                                SendaGeckoEngine.refreshExtensions {
                                    refreshKey++
                                    val fresh = SendaGeckoEngine.getUBlockDashboardUrl()
                                    if (fresh != null) onNavigate(fresh)
                                }
                            }
                        },
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Dashboard,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(strings.ublock_dlg_dashboard_title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(strings.ublock_dlg_dashboard_desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
