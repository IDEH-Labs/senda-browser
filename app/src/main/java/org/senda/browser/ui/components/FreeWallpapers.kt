package org.senda.browser.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

data class FreeWallpaper(
    val id: String,
    val name: String,
    val category: String,
    val license: String,
    val author: String,
    val colors: List<Color>
)

object FreeWallpapers {

    val items = listOf(
        FreeWallpaper(
            id = "tux_aurora",
            name = "Tux Aurora Cósmica",
            category = "Software Libre & Linux",
            license = "GPLv3 / CC-BY-SA",
            author = "Comunidad GNU/Linux",
            colors = listOf(Color(0xFF06181E), Color(0xFF003B36), Color(0xFF00594D), Color(0xFF0D1117))
        ),
        FreeWallpaper(
            id = "gnu_gold",
            name = "Horizonte Libre GNU",
            category = "Filosofía Libre",
            license = "Free Art License / GPL",
            author = "Richard Stallman & FSF Art",
            colors = listOf(Color(0xFF1F1600), Color(0xFF4A3500), Color(0xFF261B00), Color(0xFF0B0900))
        ),
        FreeWallpaper(
            id = "matrix_sovereign",
            name = "Soberanía Digital Matrix",
            category = "Privacidad & Cifrado",
            license = "GPLv3 / Copyleft",
            author = "Cypherpunk Collective",
            colors = listOf(Color(0xFF02130B), Color(0xFF082618), Color(0xFF03160D), Color(0xFF000000))
        ),
        FreeWallpaper(
            id = "debian_cosmic",
            name = "Espiral Cósmica Debian",
            category = "Comunidad Abierta",
            license = "Debian Free Software Guidelines (DFSG)",
            author = "Debian Artwork Project",
            colors = listOf(Color(0xFF120824), Color(0xFF240E44), Color(0xFF150A29), Color(0xFF090312))
        ),
        FreeWallpaper(
            id = "alpine_dawn",
            name = "Cumbres Alpinas Libres",
            category = "Fotografía Libre Profesional",
            license = "Creative Commons CC0 Public Domain",
            author = "Free Landscape Photography",
            colors = listOf(Color(0xFF141F32), Color(0xFF2A3D59), Color(0xFF1E2B3E), Color(0xFF0F1722))
        ),
        FreeWallpaper(
            id = "forest_sanctuary",
            name = "Santuario de Niebla",
            category = "Naturaleza Abierta CC0",
            license = "Creative Commons CC0 Public Domain",
            author = "Open Heritage Photos",
            colors = listOf(Color(0xFF0E1A14), Color(0xFF1B2E24), Color(0xFF14241C), Color(0xFF0A120E))
        )
    )

    fun getById(id: String): FreeWallpaper {
        return items.find { it.id == id } ?: items.first()
    }
}

@Composable
fun FreeWallpaperBackground(
    wallpaper: FreeWallpaper,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height

        // Fondo gradiente base
        drawRect(
            brush = Brush.verticalGradient(
                colors = wallpaper.colors,
                startY = 0f,
                endY = height
            )
        )

        // Resplandor atmosférico superior suave
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    wallpaper.colors.getOrNull(1)?.copy(alpha = 0.45f) ?: Color.Transparent,
                    Color.Transparent
                ),
                center = Offset(width * 0.5f, height * 0.28f),
                radius = width * 0.85f
            ),
            center = Offset(width * 0.5f, height * 0.28f),
            radius = width * 0.85f
        )

        // Resplandor sutil inferior para contraste
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    wallpaper.colors.getOrNull(2)?.copy(alpha = 0.30f) ?: Color.Transparent,
                    Color.Transparent
                ),
                center = Offset(width * 0.85f, height * 0.85f),
                radius = width * 0.65f
            ),
            center = Offset(width * 0.85f, height * 0.85f),
            radius = width * 0.65f
        )
    }
}
