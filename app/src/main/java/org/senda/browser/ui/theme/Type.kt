package org.senda.browser.ui.theme

import android.graphics.Typeface
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.senda.browser.R

val CantarellFontFamily = FontFamily(
    Font(R.font.cantarell_regular, FontWeight.Normal),
    Font(R.font.cantarell_bold, FontWeight.Bold),
    Font(R.font.cantarell_regular, FontWeight.Medium),
    Font(R.font.cantarell_bold, FontWeight.SemiBold)
)

val SerifFontFamily: FontFamily by lazy {
    val candidates = listOf(
        "/system/fonts/NotoSerif-Regular.ttf",
        "/system/fonts/NotoSerif-VF.ttf"
    )
    for (path in candidates) {
        val f = java.io.File(path)
        if (f.exists() && f.canRead()) {
            try {
                return@lazy FontFamily(Typeface.createFromFile(f))
            } catch (_: Throwable) {}
        }
    }
    try {
        FontFamily(Typeface.create(Typeface.SERIF, Typeface.NORMAL))
    } catch (_: Throwable) {
        FontFamily.Serif
    }
}

val MonospaceFontFamily: FontFamily by lazy {
    val candidates = listOf(
        "/system/fonts/DroidSansMono.ttf",
        "/system/fonts/CutiveMono.ttf"
    )
    for (path in candidates) {
        val f = java.io.File(path)
        if (f.exists() && f.canRead()) {
            try {
                return@lazy FontFamily(Typeface.createFromFile(f))
            } catch (_: Throwable) {}
        }
    }
    try {
        FontFamily(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL))
    } catch (_: Throwable) {
        FontFamily.Monospace
    }
}

val RobotoFontFamily: FontFamily by lazy {
    val candidates = listOf(
        "/system/fonts/Roboto-Regular.ttf",
        "/system/fonts/RobotoStatic-Regular.ttf",
        "/system/fonts/RobotoFlex-Regular.ttf"
    )
    for (path in candidates) {
        val f = java.io.File(path)
        if (f.exists() && f.canRead()) {
            try {
                return@lazy FontFamily(Typeface.createFromFile(f))
            } catch (_: Throwable) {}
        }
    }
    try {
        FontFamily(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL))
    } catch (_: Throwable) {
        FontFamily.SansSerif
    }
}

val CondensedFontFamily: FontFamily by lazy {
    try {
        FontFamily(Typeface.create("sans-serif-condensed", Typeface.NORMAL))
    } catch (_: Throwable) {
        FontFamily.SansSerif
    }
}

val CasualFontFamily: FontFamily by lazy {
    try {
        FontFamily(Typeface.create("casual", Typeface.NORMAL))
    } catch (_: Throwable) {
        FontFamily.SansSerif
    }
}

fun resolveFontFamily(key: String): FontFamily {
    return when (key.uppercase()) {
        "CANTARELL" -> CantarellFontFamily
        "SERIF" -> SerifFontFamily
        "MONOSPACE", "TERMINAL" -> MonospaceFontFamily
        "CONDENSED" -> CondensedFontFamily
        "CASUAL" -> CasualFontFamily
        "ROBOTO", "SANS_SERIF" -> RobotoFontFamily
        "SYSTEM" -> FontFamily.Default
        else -> SerifFontFamily
    }
}

fun getSendaTypography(
    fontFamily: FontFamily = SerifFontFamily,
    hinting: String = "SLIGHT"
): Typography {
    // Styles not defined here (dialog titles, descriptions, small labels) used to take Android's
    // default font and the UI mixed two or three typefaces. They all use the chosen one
    val defaults = Typography()
    val custom = Typography(
        displayLarge = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 32.sp,
            lineHeight = 40.sp,
            letterSpacing = (-0.5).sp
        ),
        headlineMedium = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp,
            lineHeight = 32.sp,
            letterSpacing = 0.5.sp
        ),
        titleLarge = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 20.sp,
            lineHeight = 28.sp,
            letterSpacing = 0.sp
        ),
        titleMedium = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 17.sp,
            lineHeight = 24.sp,
            letterSpacing = 0.15.sp
        ),
        titleSmall = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            letterSpacing = 0.1.sp
        ),
        bodyLarge = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            letterSpacing = 0.25.sp
        ),
        bodyMedium = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.25.sp
        ),
        labelLarge = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.5.sp
        ),
        labelMedium = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = 1.sp
        )
    )
    return custom.copy(
        displayMedium = defaults.displayMedium.copy(fontFamily = fontFamily),
        displaySmall = defaults.displaySmall.copy(fontFamily = fontFamily),
        headlineLarge = defaults.headlineLarge.copy(fontFamily = fontFamily),
        headlineSmall = defaults.headlineSmall.copy(fontFamily = fontFamily, fontWeight = FontWeight.SemiBold),
        bodySmall = defaults.bodySmall.copy(fontFamily = fontFamily, lineHeight = 18.sp),
        labelSmall = defaults.labelSmall.copy(fontFamily = fontFamily)
    )
}


