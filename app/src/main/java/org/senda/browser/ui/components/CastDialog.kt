package org.senda.browser.ui.components

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                // Limpio: si Ajustes quedó abierto en otra pantalla, Android lo traía tal cual en vez de «Enviar pantalla»
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                context.startActivity(intent)
                return true
            } catch (_: Exception) {
            }
        }
        Toast.makeText(context, strings.cast_no_system_menu, Toast.LENGTH_SHORT).show()
        return false
    }
}

@Composable
fun CastDialog(
    activeTab: BrowserTab?,
    onDismiss: () -> Unit
) {
    val strings = org.senda.browser.core.LocalSendaStrings.current
    val context = LocalContext.current
    val cast = org.senda.browser.core.cast.SendaUnifiedCast
    val currentUrl = activeTab?.url ?: "about:blank"
    // Video que se puede enviar: el archivo real que descargó la página, nunca la dirección de la página
    val media = remember(currentUrl) { org.senda.browser.core.cast.SendaMediaCatalog.bestFor(currentUrl) }
    val isYouTube = remember(currentUrl) { org.senda.browser.core.cast.SendaYouTube.youTubeVideoId(currentUrl) != null }

    LaunchedEffect(Unit) {
        org.senda.browser.core.cast.SendaTvMode.evaluate()
        cast.startDiscovery(context)
    }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

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
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                cast.activePlayback?.let { playback -> CastControls(playback) }

                // 1. El video, directo al reproductor de la TV (DLNA)
                SectionHeader(strings.cast_send_video_header, strings.cast_send_video_sub)
                when {
                    media == null -> Hint(if (isYouTube) strings.cast_video_protected else strings.cast_no_video)
                    cast.devices.isEmpty() -> Hint(if (cast.isSearching) strings.cast_searching_tvs else strings.cast_no_tvs)
                }
                cast.devices.forEach { device ->
                    CastActionCard(
                        icon = Icons.Default.Tv,
                        title = device.name,
                        subtitle = device.model,
                        enabled = media != null,
                        onClick = {
                            val video = media ?: return@CastActionCard
                            val tab = activeTab ?: return@CastActionCard
                            toast(strings.cast_sending.replace("{tv}", device.name))
                            // El video sigue en la TV desde donde iba: en el teléfono se pausa
                            tab.pauseMediaAndGetPosition { start ->
                                cast.playOnDlna(
                                    device = device,
                                    media = video,
                                    title = tab.title,
                                    startSeconds = start,
                                    proxy = org.senda.browser.core.cast.SendaCastRelay.proxyFrom(
                                        org.senda.browser.core.PreferencesManager(context)
                                    )
                                ) { result ->
                                    when (result) {
                                        org.senda.browser.core.cast.SendaUnifiedCast.SendResult.PLAYING ->
                                            toast(strings.cast_playing_on.replace("{tv}", device.name))
                                        org.senda.browser.core.cast.SendaUnifiedCast.SendResult.TV_CANNOT_PLAY -> {
                                            toast(strings.cast_tv_cannot_play.replace("{tv}", device.name))
                                            tab.resumeMedia()
                                        }
                                        org.senda.browser.core.cast.SendaUnifiedCast.SendResult.TV_UNREACHABLE -> {
                                            toast(strings.cast_tv_unreachable.replace("{tv}", device.name))
                                            tab.resumeMedia()
                                        }
                                    }
                                }
                            }
                            onDismiss()
                        }
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                // 2. Todo el teléfono por Miracast. Android solo deja conectar desde su propio menú
                // (CONFIGURE_WIFI_DISPLAY es de sistema). El teléfono no cambia: los videos de Senda pasan solos a la
                // TV a pantalla completa (SendaTvPlayer)
                SectionHeader(strings.cast_mirror_header, null)
                CastActionCard(
                    icon = Icons.Default.ScreenShare,
                    title = strings.cast_mirror_header,
                    subtitle = strings.cast_mirror_sub,
                    onClick = {
                        CastHelper.openSystemCast(context)
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

/** Controles del video que se está viendo en la TV. */
@Composable
private fun CastControls(playback: org.senda.browser.core.cast.SendaUnifiedCast.ActiveCastPlayback) {
    val strings = org.senda.browser.core.LocalSendaStrings.current
    val cast = org.senda.browser.core.cast.SendaUnifiedCast
    fun clock(seconds: Int) = if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
        else "%d:%02d".format(seconds / 60, seconds % 60)
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = strings.cast_now_on.replace("{tv}", playback.device.name),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = playback.title,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            if (playback.durationSeconds > 0) {
                LinearProgressIndicator(
                    progress = { (playback.positionSeconds.toFloat() / playback.durationSeconds).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "${clock(playback.positionSeconds)} / ${clock(playback.durationSeconds)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                IconButton(onClick = { cast.seekBy(-10) }) {
                    Icon(Icons.Default.Replay10, contentDescription = strings.cast_back10)
                }
                IconButton(onClick = { if (playback.paused) cast.resume() else cast.pause() }) {
                    Icon(
                        if (playback.paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = if (playback.paused) strings.cast_resume else strings.cast_pause
                    )
                }
                IconButton(onClick = { cast.seekBy(10) }) {
                    Icon(Icons.Default.Forward10, contentDescription = strings.cast_fwd10)
                }
                IconButton(onClick = { cast.stopActivePlayback() }) {
                    Icon(Icons.Default.Stop, contentDescription = strings.cast_stop)
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String?) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    )
}

@Composable
private fun CastActionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled, onClick = onClick)
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
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
