package org.senda.browser.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import org.senda.browser.core.AppThemeMode

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
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    useSystemColor: Boolean = true,
    accentHex: String = "#00D2A0",
    isTrueOled: Boolean = false,
    fontFamilyKey: String = "SERIF",
    fontScalePercent: Int = 100,
    hinting: String = "SLIGHT",
    recomposeKey: Int = 0,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val systemInDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        AppThemeMode.SYSTEM -> systemInDark
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }

    val dynamicAccent = SendaColors.parseHexColor(accentHex, SendaColors.MintAccent)
    val supportsDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val baseColorScheme: ColorScheme = when {
        useSystemColor && supportsDynamic && isDark -> dynamicDarkColorScheme(context)
        useSystemColor && supportsDynamic && !isDark -> dynamicLightColorScheme(context)
        isDark -> darkColorScheme(
            primary = dynamicAccent,
            onPrimary = Color.Black,
            secondary = SendaColors.CyanGlow,
            background = SendaColors.DarkGraphite,
            surface = SendaColors.SurfaceDark,
            onBackground = SendaColors.TextPrimary,
            onSurface = SendaColors.TextPrimary,
            outline = SendaColors.BorderSubtle,
            error = SendaColors.PanicFireRed
        )
        else -> lightColorScheme(
            primary = dynamicAccent,
            onPrimary = Color.White,
            secondary = SendaColors.MintAccent,
            background = Color(0xFFF8F9FA),
            surface = Color.White,
            onBackground = Color(0xFF1F2328),
            onSurface = Color(0xFF1F2328),
            outline = Color(0xFFD0D7DE),
            error = SendaColors.PanicFireRed
        )
    }

    val colorScheme = if (isDark && isTrueOled) {
        baseColorScheme.copy(
            background = SendaColors.PureOledBlack,
            surface = Color(0xFF0F0F0F)
        )
    } else {
        baseColorScheme
    }

    val fontFamily = remember(fontFamilyKey, recomposeKey) { resolveFontFamily(fontFamilyKey) }
    val typography = remember(fontFamily, hinting, recomposeKey) { getSendaTypography(fontFamily, hinting) }

    val currentDensity = LocalDensity.current
    val scaleFactor = (fontScalePercent / 100f).coerceIn(0.75f, 1.8f)

    CompositionLocalProvider(
        LocalDensity provides Density(
            density = currentDensity.density,
            fontScale = currentDensity.fontScale * scaleFactor
        )
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            content = content
        )
    }
}

