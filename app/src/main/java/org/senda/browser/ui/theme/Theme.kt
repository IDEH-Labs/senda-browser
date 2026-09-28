package org.senda.browser.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object SendaColors {
    val MintAccent = Color(0xFF00D2A0)
    val CyanGlow = Color(0xFF4EE5B6)
    val DarkGraphite = Color(0xFF0D1117)
    val PureOledBlack = Color(0xFF000000)
    val SurfaceDark = Color(0xFF161B22)
    val SurfaceHighlight = Color(0xFF21262D)
    val TextPrimary = Color(0xFFF0F6FC)
    val TextSecondary = Color(0xFF8B949E)
    val BorderSubtle = Color(0xFF30363D)
    val PanicFireRed = Color(0xFFFF5252)

    fun parseHexColor(hex: String, fallback: Color = MintAccent): Color {
        return try {
            val cleanHex = hex.removePrefix("#")
            val colorInt = cleanHex.toLong(16)
            if (cleanHex.length == 6) {
                Color(colorInt or 0x00000000FF000000)
            } else if (cleanHex.length == 8) {
                Color(colorInt)
            } else {
                fallback
            }
        } catch (e: Exception) {
            fallback
        }
    }
}

@Composable
fun SendaTheme(
    accentHex: String = "#00D2A0",
    isTrueOled: Boolean = true,
    content: @Composable () -> Unit
) {
    val dynamicAccent = SendaColors.parseHexColor(accentHex, SendaColors.MintAccent)
    val backgroundColor = if (isTrueOled) SendaColors.PureOledBlack else SendaColors.DarkGraphite
    val surfaceColor = if (isTrueOled) Color(0xFF0F0F0F) else SendaColors.SurfaceDark

    val colorScheme: ColorScheme = darkColorScheme(
        primary = dynamicAccent,
        onPrimary = Color.Black,
        background = backgroundColor,
        onBackground = SendaColors.TextPrimary,
        surface = surfaceColor,
        onSurface = SendaColors.TextPrimary,
        outline = SendaColors.BorderSubtle,
        secondary = SendaColors.CyanGlow,
        error = SendaColors.PanicFireRed
    )

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
