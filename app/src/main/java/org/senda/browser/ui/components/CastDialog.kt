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
        val strings = org.senda.browser.core.SendaStrings.forApp(context)
        val intents = listOf(
            Intent(Settings.ACTION_CAST_SETTINGS),
            Intent("android.settings.WIFI_DISPLAY_SETTINGS"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS)
        )
        for (intent in intents) {
            try {
                // Clean: if Settings was left open on another screen, Android brought it back as is instead of "Cast screen"
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

/**
 * A single casting feature: with the TV already connected (the cast button opens Android's panel if it is
 * not), this only has the video shown on the TV and "Disconnect TV". Everything else is automatic.
 */
@Composable
fun CastDialog(onDismiss: () -> Unit) {
    val strings = org.senda.browser.core.LocalSendaStrings.current
    // If the TV disconnects with the dialog open, there is nothing left to show
    LaunchedEffect(org.senda.browser.core.cast.SendaTvMode.tvConnected) {
        if (!org.senda.browser.core.cast.SendaTvMode.tvConnected) onDismiss()
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
                    imageVector = Icons.Default.CastConnected,
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
        text = { TvControlsCard(onDone = onDismiss) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.general_close)
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp)
    )
}
