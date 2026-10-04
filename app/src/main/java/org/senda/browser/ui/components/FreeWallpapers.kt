package org.senda.browser.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class FreeWallpaper(
    val id: String,
    val name: String,
    val category: String,
    val license: String,
    val author: String,
    val assetPath: String? = null,
    val filePath: String? = null,
    val colors: List<Color>
)

object FreeWallpapers {

    val items = listOf(
        FreeWallpaper(
            id = "wallpaper_gnulinux",
            name = "GNU/Linux: Aurora Boreal",
            category = "Software Libre & GNU/Linux",
            license = "Dominio Público / Copyleft Libre",
            author = "Comunidad GNU & Linux",
            assetPath = "wallpapers/wallpaper_gnulinux.jpg",
            colors = listOf(Color(0xFF04101A), Color(0xFF092938), Color(0xFF0E4352), Color(0xFF0D1117))
        ),
        FreeWallpaper(
            id = "wallpaper_freebsd",
            name = "FreeBSD: El Poder de Servir",
            category = "FreeBSD & Unix Heritage",
            license = "Licencia FreeBSD Libre (2-Clause)",
            author = "Comunidad FreeBSD & Marshall Kirk McKusick",
            assetPath = "wallpapers/wallpaper_freebsd.jpg",
            colors = listOf(Color(0xFF1B0505), Color(0xFF380C0C), Color(0xFF5A1414), Color(0xFF0D0303))
        ),
        FreeWallpaper(
            id = "wallpaper_openbsd",
            name = "OpenBSD Puffy: Seguro por Defecto",
            category = "OpenBSD & Criptografía",
            license = "Licencia ISC / BSD Abierta",
            author = "Proyecto OpenBSD & Theo de Raadt",
            assetPath = "wallpapers/wallpaper_openbsd.jpg",
            colors = listOf(Color(0xFF02101F), Color(0xFF072442), Color(0xFF0C3D6E), Color(0xFF030A14))
        ),
        FreeWallpaper(
            id = "wallpaper_netbsd",
            name = "NetBSD: Portabilidad Cósmica",
            category = "NetBSD & Arquitecturas",
            license = "Licencia NetBSD 2-Clause",
            author = "Fundación NetBSD (Of course it runs NetBSD)",
            assetPath = "wallpapers/wallpaper_netbsd.jpg",
            colors = listOf(Color(0xFF160A02), Color(0xFF361806), Color(0xFF5E2B0A), Color(0xFF0B0501))
        ),
        FreeWallpaper(
            id = "wallpaper_dragonfly",
            name = "DragonFly BSD: Microkernel HAMMER",
            category = "DragonFly BSD & Sistemas Distribuidos",
            license = "Licencia BSD 3-Clause",
            author = "Matthew Dillon & Proyecto DragonFly BSD",
            assetPath = "wallpapers/wallpaper_dragonfly.jpg",
            colors = listOf(Color(0xFF0D051A), Color(0xFF1D0C38), Color(0xFF0A2B20), Color(0xFF05020B))
        ),
        FreeWallpaper(
            id = "wallpaper_bsdheritage",
            name = "El Templo de BSD: Herencia Unix",
            category = "Genealogía Unix & BSD (1977-Presente)",
            license = "Dominio Público / Licencia BSD Original",
            author = "Berkeley Software Distribution & Unix Pioneers",
            assetPath = "wallpapers/wallpaper_bsdheritage.jpg",
            colors = listOf(Color(0xFF110C05), Color(0xFF281C0C), Color(0xFF453015), Color(0xFF080602))
        ),
        FreeWallpaper(
            id = "tux_aurora",
            name = "Tux Aurora Cósmica",
            category = "Software Libre & Linux",
            license = "GPLv3 / CC-BY-SA",
            author = "Comunidad GNU/Linux",
            assetPath = null,
            colors = listOf(Color(0xFF06181E), Color(0xFF003B36), Color(0xFF00594D), Color(0xFF0D1117))
        ),
        FreeWallpaper(
            id = "matrix_sovereign",
            name = "Matrix Digital",
            category = "Privacidad & Cifrado",
            license = "GPLv3 / Copyleft",
            author = "Cypherpunk Collective",
            assetPath = null,
            colors = listOf(Color(0xFF02130B), Color(0xFF082618), Color(0xFF03160D), Color(0xFF000000))
        ),
        FreeWallpaper(
            id = "debian_cosmic",
            name = "Espiral Cósmica Debian",
            category = "Comunidad Abierta",
            license = "Debian Free Software Guidelines (DFSG)",
            author = "Debian Artwork Project",
            assetPath = null,
            colors = listOf(Color(0xFF120824), Color(0xFF240E44), Color(0xFF150A29), Color(0xFF090312))
        )
    )

    fun getById(id: String, customPath: String? = null): FreeWallpaper {
        if (id == "custom_user") {
            return FreeWallpaper(
                id = "custom_user",
                name = "Fondo Personalizado",
                category = "Personal & Propio",
                license = "Almacenamiento local",
                author = "Tú",
                assetPath = null,
                filePath = customPath,
                colors = listOf(Color(0xFF0F141C), Color(0xFF1B2330), Color(0xFF283446), Color(0xFF0A0D12))
            )
        }
        return items.find { it.id == id } ?: items.first()
    }
}

@Composable
fun FreeWallpaperBackground(
    wallpaper: FreeWallpaper,
    dimPercent: Int = 0,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var imageBitmap by remember(wallpaper.assetPath, wallpaper.filePath) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(wallpaper.assetPath, wallpaper.filePath) {
        val asset = wallpaper.assetPath
        val file = wallpaper.filePath
        imageBitmap = withContext(Dispatchers.IO) {
            try {
                if (asset != null) {
                    context.assets.open(asset).use { input ->
                        BitmapFactory.decodeStream(input)?.asImageBitmap()
                    }
                } else if (file != null && java.io.File(file).exists()) {
                    BitmapFactory.decodeFile(file)?.asImageBitmap()
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        val currentBitmap = imageBitmap
        if (currentBitmap != null) {
            Image(
                bitmap = currentBitmap,
                contentDescription = wallpaper.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
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

    if (dimPercent > 0) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = (dimPercent / 100f).coerceIn(0f, 0.95f)))
            )
        }
    }
}
