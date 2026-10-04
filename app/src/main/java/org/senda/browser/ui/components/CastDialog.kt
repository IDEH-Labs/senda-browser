package org.senda.browser.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.senda.browser.core.cast.SendaDialCast
import org.senda.browser.ui.model.BrowserTab

object CastHelper {
    fun openSystemCast(context: Context): Boolean {
        val strings = org.senda.browser.core.SendaStrings.get("SYSTEM", context)
        val intents = listOf(
            Intent(Settings.ACTION_CAST_SETTINGS),
            Intent("android.settings.WIFI_DISPLAY_SETTINGS"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS)
        )
        for (intent in intents) {
            try {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                return true
            } catch (_: Exception) {
            }
        }
        Toast.makeText(context, strings.cast_no_system_menu, Toast.LENGTH_SHORT).show()
        return false
    }

    fun shareToCastApp(context: Context, url: String, title: String) {
        val strings = org.senda.browser.core.SendaStrings.get("SYSTEM", context)
        try {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_TEXT, url)
                putExtra(Intent.EXTRA_SUBJECT, title.ifBlank { "Senda Browser" })
                type = "text/plain"
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val chooser = Intent.createChooser(sendIntent, strings.cast_share_link_chooser)
            chooser.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(context, "${strings.cast_share_error}: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun copyUrl(context: Context, url: String) {
        val strings = org.senda.browser.core.SendaStrings.get("SYSTEM", context)
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Senda URL", url)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, strings.tb_link_copied_clipboard, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
        }
    }
}

@Composable
fun CastDialog(
    activeTab: BrowserTab?,
    onDismiss: () -> Unit
) {
    val strings = org.senda.browser.core.LocalSendaStrings.current
    val context = LocalContext.current
    val currentUrl = activeTab?.url ?: "about:blank"
    val currentTitle = activeTab?.title ?: "Senda Browser"
    val isSpanish = strings === org.senda.browser.core.SendaStringsEs

    val canDrawOverlay = remember { org.senda.browser.core.cast.SendaTvMode.canDrawOverlay(context) }
    val canAdaptAspect = remember { org.senda.browser.core.cast.SendaTvMode.canAdaptAspect(context) }

    // Si ya se está duplicando la pantalla, evaluar modo TV y buscar dispositivos directos
    LaunchedEffect(Unit) {
        org.senda.browser.core.cast.SendaTvMode.evaluate()
        org.senda.browser.core.cast.SendaDialCast.init(context)
        org.senda.browser.core.cast.SendaDialCast.search()
        org.senda.browser.core.cast.SendaUnifiedCast.startDiscovery(context)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Cast,
                    contentDescription = strings.cast_title,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
            }
        },
        title = {
            Text(
                text = strings.cast_title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (currentUrl.isNotBlank() && currentUrl != "about:blank") {
                    Text(
                        text = currentUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }

                org.senda.browser.core.cast.SendaUnifiedCast.activePlayback?.let { playback ->
                    CastActionCard(
                        icon = Icons.Default.Stop,
                        title = if (isSpanish) "Detener en ${playback.device.name}" else "Stop on ${playback.device.name}",
                        subtitle = if (isSpanish) "Detiene la transmisión en la TV" else "Stops streaming on the TV",
                        onClick = {
                            org.senda.browser.core.cast.SendaUnifiedCast.stopActivePlayback()
                            onDismiss()
                        }
                    )
                }

                // 1. Dispositivos Google Cast / Chromecast directos en la red Wi-Fi
                val chromecastDevices = org.senda.browser.core.cast.SendaUnifiedCast.devices.filter {
                    it.type == org.senda.browser.core.cast.CastDeviceType.CHROMECAST
                }

                if (chromecastDevices.isNotEmpty()) {
                    Text(
                        text = if (isSpanish) "Chromecast (Transmisión Directa 60 fps)" else "Chromecast (Direct 60 fps)",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    chromecastDevices.forEach { device ->
                        CastActionCard(
                            icon = Icons.Default.Cast,
                            title = device.name,
                            subtitle = if (isSpanish) "Flujo directo sin lag ni compresión • ${device.model}"
                                       else "Direct stream without lag • ${device.model}",
                            onClick = {
                                Toast.makeText(
                                    context,
                                    if (isSpanish) "Conectando con ${device.name}…" else "Connecting to ${device.name}…",
                                    Toast.LENGTH_SHORT
                                ).show()
                                val startSecs = activeTab?.currentMediaSeconds ?: 0
                                org.senda.browser.core.cast.SendaUnifiedCast.playOnChromecast(
                                    device = device,
                                    mediaUrl = currentUrl,
                                    title = activeTab?.title ?: "Senda Stream",
                                    startSeconds = startSecs
                                ) { ok ->
                                    val msg = if (ok) {
                                        if (isSpanish) "Transmitiendo a ${device.name} con máxima fluidez"
                                        else "Playing on ${device.name} at full quality"
                                    } else {
                                        if (isSpanish) "No se pudo iniciar transmisión directa en ${device.name}"
                                        else "Could not stream to ${device.name}"
                                    }
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                                onDismiss()
                            }
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                }

                // 2. Dispositivos Smart TV con YouTube / DIAL
                val dialDevices = (org.senda.browser.core.cast.SendaDialCast.devices.map {
                    org.senda.browser.core.cast.CastDevice(
                        id = it.appsUrl,
                        name = it.name,
                        model = it.model,
                        type = org.senda.browser.core.cast.CastDeviceType.DIAL_YOUTUBE,
                        endpoint = it.appsUrl
                    )
                } + org.senda.browser.core.cast.SendaUnifiedCast.devices.filter {
                    it.type == org.senda.browser.core.cast.CastDeviceType.DIAL_YOUTUBE
                }).distinctBy { it.name }

                if (dialDevices.isNotEmpty()) {
                    Text(
                        text = if (isSpanish) "Smart TVs (Transmisión Directa YouTube)" else "Smart TVs (Direct YouTube)",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    dialDevices.forEach { device ->
                        CastActionCard(
                            icon = Icons.Default.Tv,
                            title = device.name,
                            subtitle = if (isSpanish) "Transmisión nativa sin carga para el celular • ${device.model}"
                                       else "Native stream without phone load • ${device.model}",
                            onClick = {
                                val videoId = org.senda.browser.core.cast.SendaDialCast.youTubeVideoId(currentUrl)
                                if (videoId != null) {
                                    val startPos = activeTab?.currentMediaSeconds ?: 0
                                    org.senda.browser.core.cast.SendaDialCast.playYouTube(
                                        org.senda.browser.core.cast.DialDevice(device.name, device.model, device.endpoint),
                                        videoId,
                                        startPos
                                    ) { ok ->
                                        val msg = if (ok) {
                                            if (isSpanish) "Reproduciendo en ${device.name}" else "Playing on ${device.name}"
                                        } else {
                                            if (isSpanish) "No se pudo reproducir en ${device.name}" else "Could not play on ${device.name}"
                                        }
                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    Toast.makeText(context, if (isSpanish) "Abre un video de YouTube para transmitir directamente" else "Open a YouTube video to cast directly", Toast.LENGTH_SHORT).show()
                                }
                                onDismiss()
                            }
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                }

                // 3. Dispositivos Smart TV DLNA / UPnP encontrados en la red local
                val dlnaDevices = org.senda.browser.core.cast.SendaUnifiedCast.devices.filter {
                    it.type == org.senda.browser.core.cast.CastDeviceType.DLNA_SMART_TV
                }

                if (dlnaDevices.isNotEmpty()) {
                    Text(
                        text = if (isSpanish) "Televisores Smart TV (DLNA / UPnP)" else "Smart TVs (DLNA / UPnP)",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    dlnaDevices.forEach { device ->
                        CastActionCard(
                            icon = Icons.Default.Tv,
                            title = device.name,
                            subtitle = device.model,
                            onClick = {
                                Toast.makeText(
                                    context,
                                    if (isSpanish) "Conectando con ${device.name}…" else "Connecting to ${device.name}…",
                                    Toast.LENGTH_SHORT
                                ).show()
                                org.senda.browser.core.cast.SendaUnifiedCast.playOnDlna(
                                    device = device,
                                    mediaUrl = currentUrl,
                                    title = activeTab?.title ?: "Senda Stream"
                                ) { ok ->
                                    val msg = if (ok) {
                                        if (isSpanish) "Reproduciendo en ${device.name}" else "Playing on ${device.name}"
                                    } else {
                                        if (isSpanish) "No se pudo reproducir en ${device.name}" else "Could not play on ${device.name}"
                                    }
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                                onDismiss()
                            }
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                }

                Text(
                    text = strings.cast_desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Opción: Duplicar pantalla completa vía Android Cast
                CastActionCard(
                    icon = Icons.Default.Tv,
                    title = if (isSpanish) "Duplicar pantalla completa (Modo espejo)" else strings.cast_action_system_title,
                    subtitle = if (isSpanish) "Muestra toda la pantalla del celular en la TV. (Para videos usa la transmisión directa de arriba)."
                               else strings.cast_action_system_sub,
                    onClick = {
                        CastHelper.openSystemCast(context)
                        onDismiss()
                    }
                )

                // Modo TV: sin «Mostrar sobre otras apps» Telegram y las demás apps no se adaptan a la TV
                if (!canDrawOverlay) {
                    CastActionCard(
                        icon = Icons.Default.Fullscreen,
                        title = if (isSpanish) "Pantalla completa en la TV para todas las apps" else "Full screen on TV for every app",
                        subtitle = if (isSpanish) "Permite «Mostrar sobre otras apps» para que Telegram y la galería llenen la TV al duplicar"
                        else "Allow «Display over other apps» so Telegram and the gallery fill the TV while mirroring",
                        onClick = {
                            try {
                                context.startActivity(
                                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}"))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            } catch (_: Exception) {
                            }
                            onDismiss()
                        }
                    )
                } else if (!canAdaptAspect) {
                    val landscapeForced = org.senda.browser.core.PreferencesManager(context).tvModeLandscape
                    Text(
                        text = if (isSpanish) {
                            if (landscapeForced) "Modo TV activo: 60 Hz y horizontal al duplicar."
                            else "Transmisión activa: La orientación del celular se mantiene libre y natural."
                        } else {
                            if (landscapeForced) "TV mode active: 60 Hz and forced landscape while mirroring."
                            else "Casting active: Phone orientation remains free and natural."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Opción 2: Enviar video o web a app de transmisión
                CastActionCard(
                    icon = Icons.Default.PlayCircle,
                    title = strings.cast_action_app_title,
                    subtitle = strings.cast_action_app_sub,
                    onClick = {
                        CastHelper.shareToCastApp(context, currentUrl, currentTitle)
                        onDismiss()
                    }
                )

                // Opción 3: Copiar enlace
                CastActionCard(
                    icon = Icons.Default.ContentCopy,
                    title = strings.cast_action_copy_title,
                    subtitle = strings.cast_action_copy_sub,
                    onClick = {
                        CastHelper.copyUrl(context, currentUrl)
                        onDismiss()
                    }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.general_close)
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp)
    )
}

@Composable
private fun CastActionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .border(
                width = 0.8.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                shape = RoundedCornerShape(14.dp)
            ),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
