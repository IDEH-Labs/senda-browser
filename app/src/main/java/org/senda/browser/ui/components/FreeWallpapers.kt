package org.senda.browser.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Free wallpaper: a real work from a free software project, with its author and license as stated in the
 * official source (the package's copyright file, metadata or the project's art page).
 * [focusX] is where the important part of the image is (0 = left, 1 = right): on a phone in portrait
 * the crop is centered there and does not cut off, for example, the mascot on one side.
 */
data class FreeWallpaper(
    val id: String,
    val name: String,
    val category: String,
    val license: String,
    val author: String,
    val sourceUrl: String? = null,
    val assetPath: String? = null,
    val filePath: String? = null,
    val focusX: Float = 0.5f,
    val colors: List<Color>
)

object FreeWallpapers {

    // Verified on 2026-10-05 at each project's source. Arch: its trademark policy allows using the logo
    // without asking for permission in non-commercial uses that do not suggest official endorsement (if Senda were sold,
    // it would have to be requested from trademarks@archlinux.org). No BSD art: FreeBSD, NetBSD, OpenBSD, DragonFly and GhostBSD
    // publish it as a registered trademark, with prior permission or without a license. The Pixabay photos from the
    // Arch package are left out: their license forbids redistributing them as wallpapers.
    // Xfce: the 10 xfdesktop wallpapers with author and license in backgrounds/README.md (commit d08a94c, SVGs
    // rendered at 3840 px). Left out: xfce-blue (Xfce itself says its origin is unknown), the
    // photos from the old xfce4-artwork package (no published author or license) and artwork/public (no license)
    val items = listOf(
        FreeWallpaper(
            id = "debian_ceratopsian", name = "Ceratopsian (Debian 13)", category = "Debian", author = "Elise Couper", license = "GPL-2.0+",
            sourceUrl = "https://wiki.debian.org/DebianArt/Themes/Ceratopsian", assetPath = "wallpapers/debian_ceratopsian.webp", focusX = 0.5f,
            colors = listOf(Color(0xFF264456), Color(0xFF254C63), Color(0xFF275A78), Color(0xFF266386))
        ),
        FreeWallpaper(
            id = "debian_emerald", name = "Emerald (Debian 12)", category = "Debian", author = "Juliette Taka Belin", license = "GPL-2.0+",
            sourceUrl = "https://wiki.debian.org/DebianArt/Themes/Emerald", assetPath = "wallpapers/debian_emerald.webp", focusX = 0.5f,
            colors = listOf(Color(0xFF095B66), Color(0xFF095463), Color(0xFF085061), Color(0xFF064B5E))
        ),
        FreeWallpaper(
            id = "debian_homeworld", name = "Homeworld (Debian 11)", category = "Debian", author = "Juliette Taka Belin", license = "GPL-2.0+",
            sourceUrl = "https://wiki.debian.org/DebianArt/Themes/Homeworld", assetPath = "wallpapers/debian_homeworld.webp", focusX = 0.5f,
            colors = listOf(Color(0xFF010027), Color(0xFF020329), Color(0xFF020228), Color(0xFF010027))
        ),
        FreeWallpaper(
            id = "debian_futureprototype", name = "futurePrototype (Debian 10)", category = "Debian", author = "Alex Makas", license = "GPL-2.0+",
            sourceUrl = "https://wiki.debian.org/DebianArt/Themes/futurePrototype", assetPath = "wallpapers/debian_futureprototype.webp", focusX = 0.5f,
            colors = listOf(Color(0xFF253249), Color(0xFF425167), Color(0xFF617087), Color(0xFF76889F))
        ),
        FreeWallpaper(
            id = "debian_moonlight", name = "Moonlight", category = "Debian", author = "Juliette Taka Belin", license = "GPL-2.0+",
            sourceUrl = "https://wiki.debian.org/DebianArt/Themes/MoonLight", assetPath = "wallpapers/debian_moonlight.webp", focusX = 0.47f,
            colors = listOf(Color(0xFF183948), Color(0xFF1D3F4D), Color(0xFF153746), Color(0xFF133544))
        ),
        FreeWallpaper(
            id = "debian_softwaves", name = "softWaves (Debian 9)", category = "Debian", author = "Juliette Taka Belin", license = "GPL-2.0+",
            sourceUrl = "https://wiki.debian.org/DebianArt/Themes/softWaves", assetPath = "wallpapers/debian_softwaves.webp", focusX = 0.6f,
            colors = listOf(Color(0xFF3A5E6E), Color(0xFF75918F), Color(0xFF7B948D), Color(0xFF516764))
        ),
        FreeWallpaper(
            id = "debian_lines", name = "Lines (Debian 8)", category = "Debian", author = "Juliette Taka Belin", license = "GPL-2.0+",
            sourceUrl = "https://wiki.debian.org/DebianArt/Themes/Lines", assetPath = "wallpapers/debian_lines.webp", focusX = 0.5f,
            colors = listOf(Color(0xFF2F6D74), Color(0xFF347479), Color(0xFF36787D), Color(0xFF36777C))
        ),
        FreeWallpaper(
            id = "debian_joy", name = "Joy (Debian 7)", category = "Debian", author = "Adrien Aubourg", license = "GPL-2.0+",
            sourceUrl = "https://wiki.debian.org/DebianArt/Themes/Joy", assetPath = "wallpapers/debian_joy.webp", focusX = 0.5f,
            colors = listOf(Color(0xFF40485A), Color(0xFF596376), Color(0xFF596376), Color(0xFF40485A))
        ),
        FreeWallpaper(
            id = "debian_spacefun", name = "Spacefun (Debian 6)", category = "Debian", author = "Valessio Brito", license = "GPL-2.0",
            sourceUrl = "https://wiki.debian.org/DebianArt/Themes/SpaceFun", assetPath = "wallpapers/debian_spacefun.webp", focusX = 0.85f,
            colors = listOf(Color(0xFF1F3D63), Color(0xFF264A78), Color(0xFF274871), Color(0xFF162A44))
        ),
        FreeWallpaper(
            id = "gnu_free_your_soul", name = "Free Your Soul", category = "GNU", author = "Sayem Chaklader", license = "GPL-3.0",
            sourceUrl = "https://www.gnu.org/graphics/free-your-soul.html", assetPath = "wallpapers/gnu_free_your_soul.webp", focusX = 0.62f,
            colors = listOf(Color(0xFF69B681), Color(0xFF6FB186), Color(0xFF88C186), Color(0xFF7AC7AB))
        ),
        FreeWallpaper(
            id = "gnu_this_is_freedom", name = "This is Freedom", category = "GNU", author = "Vadim Gush", license = "GPL-3.0",
            sourceUrl = "https://www.gnu.org/graphics/this-is-freedom-wallpaper.html", assetPath = "wallpapers/gnu_this_is_freedom.webp", focusX = 0.5f,
            colors = listOf(Color(0xFF18354D), Color(0xFF2B4A5C), Color(0xFF244B5E), Color(0xFF1C5469))
        ),
        FreeWallpaper(
            id = "gnu_skwid_free_side", name = "Free Side of the Force", category = "GNU", author = "Ben «Skwid» Gailly", license = "CC BY-SA 4.0",
            sourceUrl = "https://www.gnu.org/graphics/skwid-wallpapers.html", assetPath = "wallpapers/gnu_skwid_free_side.webp", focusX = 0.5f,
            colors = listOf(Color(0xFF0A0A0C), Color(0xFF191A1E), Color(0xFF131314), Color(0xFF020203))
        ),
        FreeWallpaper(
            id = "gnu_skwid_fsfs", name = "Free Software, Free Society", category = "GNU", author = "Ben «Skwid» Gailly", license = "CC BY-SA 4.0",
            sourceUrl = "https://www.gnu.org/graphics/skwid-wallpapers.html", assetPath = "wallpapers/gnu_skwid_fsfs.webp", focusX = 0.5f,
            colors = listOf(Color(0xFF2E2E2E), Color(0xFF323232), Color(0xFF2A2A2A), Color(0xFF282828))
        ),
        FreeWallpaper(
            id = "gnu_skwid_gnu_fsf", name = "GNU/Linux + FSF", category = "GNU", author = "Ben «Skwid» Gailly", license = "CC BY-SA 4.0",
            sourceUrl = "https://www.gnu.org/graphics/skwid-wallpapers.html", assetPath = "wallpapers/gnu_skwid_gnu_fsf.webp", focusX = 0.5f,
            colors = listOf(Color(0xFF111010), Color(0xFF0F0F0F), Color(0xFF171717), Color(0xFF090909))
        ),
        FreeWallpaper(
            id = "gnu_skwid_hurd", name = "GNU Hurd", category = "GNU", author = "Ben «Skwid» Gailly", license = "CC BY-SA 4.0",
            sourceUrl = "https://www.gnu.org/graphics/skwid-wallpapers.html", assetPath = "wallpapers/gnu_skwid_hurd.webp", focusX = 0.5f,
            colors = listOf(Color(0xFF7C341F), Color(0xFF6C3E1D), Color(0xFF7C4119), Color(0xFF742C1C))
        ),
        FreeWallpaper(
            id = "gnu_wilgus_gnutiling", name = "Mosaico de cabezas GNU", category = "GNU", author = "Kacper Wilgus", license = "GFDL-1.3 o CC BY-SA 4.0",
            sourceUrl = "https://www.gnu.org/graphics/wilgus-gnutiling.html", assetPath = "wallpapers/gnu_wilgus_gnutiling.webp", focusX = 0.5f,
            colors = listOf(Color(0xFF5F5D9B), Color(0xFF5E5D9B), Color(0xFF5F5D9B), Color(0xFF5F5D9B))
        ),
        FreeWallpaper(
            id = "gentoo_g10_purple", name = "10 Years Compiling (morado)", category = "Gentoo", author = "Ben Stedman y Alex Legler", license = "CC BY-SA 4.0",
            sourceUrl = "https://www.gentoo.org/inside-gentoo/artwork/", assetPath = "wallpapers/gentoo_g10_purple.webp", focusX = 0.7f,
            colors = listOf(Color(0xFFCECECD), Color(0xFFD0D0CF), Color(0xFFB4ACC8), Color(0xFF9F93BD))
        ),
        FreeWallpaper(
            id = "gentoo_g10_blue", name = "10 Years Compiling (azul)", category = "Gentoo", author = "Ben Stedman y Alex Legler", license = "CC BY-SA 4.0",
            sourceUrl = "https://www.gentoo.org/inside-gentoo/artwork/", assetPath = "wallpapers/gentoo_g10_blue.webp", focusX = 0.7f,
            colors = listOf(Color(0xFFCECECD), Color(0xFFD0D0CF), Color(0xFFA0B3D4), Color(0xFF819DCF))
        ),
        FreeWallpaper(
            id = "gentoo_abducted", name = "Abducted", category = "Gentoo", author = "Matteo «Peach» Pescarin y Ethan Dunham", license = "CC BY-SA 2.5",
            sourceUrl = "https://www.gentoo.org/inside-gentoo/artwork/", assetPath = "wallpapers/gentoo_abducted.webp", focusX = 0.5f,
            colors = listOf(Color(0xFF0C031F), Color(0xFF170D2C), Color(0xFF171325), Color(0xFF030703))
        ),
        FreeWallpaper(
            id = "gentoo_cow", name = "Gentoo Cow", category = "Gentoo", author = "Sebastian Pipping y Ethan Dunham", license = "CC BY-SA 2.5",
            sourceUrl = "https://www.gentoo.org/inside-gentoo/artwork/", assetPath = "wallpapers/gentoo_cow.webp", focusX = 0.5f,
            colors = listOf(Color(0xFF455D69), Color(0xFF3F5865), Color(0xFF3C5663), Color(0xFF254150))
        ),
        FreeWallpaper(
            id = "gentoo_larry", name = "Red Larry", category = "Gentoo", author = "Dávid Kótai, Matteo Pescarin y Ethan Dunham", license = "CC BY-SA 2.5",
            sourceUrl = "https://www.gentoo.org/inside-gentoo/artwork/", assetPath = "wallpapers/gentoo_larry.webp", focusX = 0.25f,
            colors = listOf(Color(0xFFC1C1C1), Color(0xFFA5A5A5), Color(0xFF868382), Color(0xFFA23939))
        ),
        FreeWallpaper(
            id = "kde_flyingkonqui", name = "Flying Konqui", category = "KDE Plasma", author = "Timothée Giet", license = "LGPL-3.0",
            sourceUrl = "https://invent.kde.org/plasma/plasma-workspace-wallpapers", assetPath = "wallpapers/kde_flyingkonqui.webp", focusX = 0.83f,
            colors = listOf(Color(0xFF096DC1), Color(0xFF1777BC), Color(0xFF3589D5), Color(0xFF559FDF))
        ),
        FreeWallpaper(
            id = "kde_honeywave", name = "Honeywave", category = "KDE Plasma", author = "Ken Vermette", license = "CC BY-SA 4.0",
            sourceUrl = "https://invent.kde.org/plasma/plasma-workspace-wallpapers", assetPath = "wallpapers/kde_honeywave.webp", focusX = 0.5f,
            colors = listOf(Color(0xFF593E3A), Color(0xFF5D555F), Color(0xFF575164), Color(0xFF594C5C))
        ),
        FreeWallpaper(
            id = "kde_kokkini", name = "Kokkini", category = "KDE Plasma", author = "Ken Vermette", license = "LGPL-3.0",
            sourceUrl = "https://invent.kde.org/plasma/plasma-workspace-wallpapers", assetPath = "wallpapers/kde_kokkini.webp", focusX = 0.5f,
            colors = listOf(Color(0xFF6E2B3E), Color(0xFF603A53), Color(0xFF486283), Color(0xFF274A74))
        ),
        FreeWallpaper(
            id = "kde_elarun", name = "Elarun", category = "KDE Plasma", author = "Nuno Pinheiro", license = "LGPL-3.0",
            sourceUrl = "https://invent.kde.org/plasma/plasma-workspace-wallpapers", assetPath = "wallpapers/kde_elarun.webp", focusX = 0.5f,
            colors = listOf(Color(0xFF022E48), Color(0xFF034D85), Color(0xFF144C99), Color(0xFF183172))
        ),
        FreeWallpaper(
            id = "kde_milkyway", name = "Milky Way", category = "KDE Plasma", author = "ruvkr", license = "CC BY-SA 4.0",
            sourceUrl = "https://invent.kde.org/plasma/plasma-workspace-wallpapers", assetPath = "wallpapers/kde_milkyway.webp", focusX = 0.5f,
            colors = listOf(Color(0xFF3D1233), Color(0xFF341030), Color(0xFF2C0F2E), Color(0xFF220E2A))
        ),
        FreeWallpaper(
            id = "kde_altai", name = "Altai", category = "KDE Plasma", author = "Alesya Khoteeva", license = "CC BY-SA 4.0",
            sourceUrl = "https://invent.kde.org/plasma/plasma-workspace-wallpapers", assetPath = "wallpapers/kde_altai.webp", focusX = 0.5f,
            colors = listOf(Color(0xFF8DD0F5), Color(0xFF7CB5D2), Color(0xFF427CA1), Color(0xFF4C8FC3))
        ),
        FreeWallpaper(
            id = "kde_mountain", name = "Mountain", category = "KDE Plasma", author = "Andy Betts", license = "CC BY-SA 4.0",
            sourceUrl = "https://invent.kde.org/plasma/plasma-workspace-wallpapers", assetPath = "wallpapers/kde_mountain.webp", focusX = 0.5f,
            colors = listOf(Color(0xFF47717C), Color(0xFF67929A), Color(0xFF709395), Color(0xFF22393E))
        ),
        FreeWallpaper(
            id = "kde_volna", name = "Volna", category = "KDE Plasma", author = "Nikita Babin", license = "CC BY-SA 4.0",
            sourceUrl = "https://invent.kde.org/plasma/plasma-workspace-wallpapers", assetPath = "wallpapers/kde_volna.webp", focusX = 0.5f,
            colors = listOf(Color(0xFF838C8B), Color(0xFF95948F), Color(0xFFA49488), Color(0xFFC69E84))
        ),
        FreeWallpaper(
            id = "gnome_adwaita", name = "Adwaita", category = "GNOME", author = "Jakub Steiner", license = "CC BY-SA 3.0",
            sourceUrl = "https://gitlab.gnome.org/GNOME/gnome-backgrounds", assetPath = "wallpapers/gnome_adwaita.webp", focusX = 0.5f,
            colors = listOf(Color(0xFF0D1D3C), Color(0xFF0F2551), Color(0xFF0E2452), Color(0xFF05173D))
        ),
        FreeWallpaper(
            id = "gnome_amber", name = "Amber", category = "GNOME", author = "David Lapshin", license = "CC BY-SA 3.0",
            sourceUrl = "https://gitlab.gnome.org/GNOME/gnome-backgrounds", assetPath = "wallpapers/gnome_amber.webp", focusX = 0.5f,
            colors = listOf(Color(0xFFB941A9), Color(0xFFC03985), Color(0xFF86338B), Color(0xFF483C9C))
        ),
        FreeWallpaper(
            id = "gnome_blobs", name = "Blobs", category = "GNOME", author = "Jakub Steiner", license = "CC BY-SA 3.0",
            sourceUrl = "https://gitlab.gnome.org/GNOME/gnome-backgrounds", assetPath = "wallpapers/gnome_blobs.webp", focusX = 0.5f,
            colors = listOf(Color(0xFF682832), Color(0xFF532240), Color(0xFF512241), Color(0xFF6B2934))
        ),
        FreeWallpaper(
            id = "gnome_fold", name = "Fold", category = "GNOME", author = "Jakub Steiner", license = "CC BY-SA 3.0",
            sourceUrl = "https://gitlab.gnome.org/GNOME/gnome-backgrounds", assetPath = "wallpapers/gnome_fold.webp", focusX = 0.5f,
            colors = listOf(Color(0xFF6F2777), Color(0xFF993972), Color(0xFF973954), Color(0xFF8B3962))
        ),
        FreeWallpaper(
            id = "gnome_ditheredsun", name = "Dithered Sun", category = "GNOME", author = "Tobias Bernard", license = "CC BY-SA 3.0",
            sourceUrl = "https://gitlab.gnome.org/GNOME/gnome-backgrounds", assetPath = "wallpapers/gnome_ditheredsun.webp", focusX = 0.5f,
            colors = listOf(Color(0xFF690327), Color(0xFF775294), Color(0xFFB165A2), Color(0xFFAE6BAC))
        ),
        FreeWallpaper(
            id = "gnome_map", name = "Map", category = "GNOME", author = "Dominik Baran", license = "CC BY-SA 3.0",
            sourceUrl = "https://gitlab.gnome.org/GNOME/gnome-backgrounds", assetPath = "wallpapers/gnome_map.webp", focusX = 0.5f,
            colors = listOf(Color(0xFF1C2731), Color(0xFF222E37), Color(0xFF242D35), Color(0xFF1E2A32))
        ),
        FreeWallpaper(
            id = "fedora_f43", name = "Fedora 43 (noche)", category = "Fedora", author = "Equipo de Diseño de Fedora", license = "CC BY-SA 4.0",
            sourceUrl = "https://github.com/fedoradesign/backgrounds", assetPath = "wallpapers/fedora_f43.webp", focusX = 0.45f,
            colors = listOf(Color(0xFF2A2E3F), Color(0xFF686A7B), Color(0xFFA8A49E), Color(0xFF99989A))
        ),
        FreeWallpaper(
            id = "fedora_f42", name = "Fedora 42 (noche)", category = "Fedora", author = "Equipo de Diseño de Fedora", license = "CC BY-SA 4.0",
            sourceUrl = "https://github.com/fedoradesign/backgrounds", assetPath = "wallpapers/fedora_f42.webp", focusX = 0.4f,
            colors = listOf(Color(0xFF363E45), Color(0xFF3E494C), Color(0xFF454F49), Color(0xFF3C474B))
        ),
        FreeWallpaper(
            id = "fedora_f41", name = "Fedora 41 (noche)", category = "Fedora", author = "Equipo de Diseño de Fedora", license = "CC BY-SA 4.0",
            sourceUrl = "https://github.com/fedoradesign/backgrounds", assetPath = "wallpapers/fedora_f41.webp", focusX = 0.5f,
            colors = listOf(Color(0xFF2B358A), Color(0xFF3C529E), Color(0xFF21206E), Color(0xFF1A1467))
        ),
        FreeWallpaper(
            id = "fedora_f40", name = "Fedora 40 (noche)", category = "Fedora", author = "Equipo de Diseño de Fedora", license = "CC BY-SA 4.0",
            sourceUrl = "https://github.com/fedoradesign/backgrounds", assetPath = "wallpapers/fedora_f40.webp", focusX = 0.5f,
            colors = listOf(Color(0xFF262D37), Color(0xFF2C323E), Color(0xFF2B3142), Color(0xFF262C3B))
        ),
        FreeWallpaper(
            id = "fedora_f39", name = "Fedora 39 (noche)", category = "Fedora", author = "Equipo de Diseño de Fedora", license = "CC BY-SA 4.0",
            sourceUrl = "https://github.com/fedoradesign/backgrounds", assetPath = "wallpapers/fedora_f39.webp", focusX = 0.5f,
            colors = listOf(Color(0xFF252727), Color(0xFF0E1B20), Color(0xFF312B2E), Color(0xFF2E2224))
        ),
        FreeWallpaper(
            id = "fedora_f38", name = "Fedora 38 (noche)", category = "Fedora", author = "Equipo de Diseño de Fedora", license = "CC BY-SA 4.0",
            sourceUrl = "https://github.com/fedoradesign/backgrounds", assetPath = "wallpapers/fedora_f38.webp", focusX = 0.5f,
            colors = listOf(Color(0xFF555762), Color(0xFF7C8089), Color(0xFF6C717C), Color(0xFF5A5F67))
        ),
        FreeWallpaper(
            id = "arch_archbtw", name = "Arch btw", category = "Arch Linux", author = "xyproto", license = "CC0 1.0",
            sourceUrl = "https://bbs.archlinux.org/viewtopic.php?pid=1931165", assetPath = "wallpapers/arch_archbtw.webp", focusX = 0.5f,
            colors = listOf(Color(0xFF000000), Color(0xFF1D1917), Color(0xFF242525), Color(0xFF131213))
        ),
        FreeWallpaper(
            id = "arch_archwave", name = "Arch Wave", category = "Arch Linux", author = "rhysperry111", license = "CC0 1.0",
            sourceUrl = "https://bbs.archlinux.org/viewtopic.php?pid=1931341", assetPath = "wallpapers/arch_archwave.webp", focusX = 0.5f,
            colors = listOf(Color(0xFF000000), Color(0xFF010606), Color(0xFF041B24), Color(0xFF020F13))
        ),
        FreeWallpaper(
            id = "arch_archwaveinv", name = "Arch Wave (invertido)", category = "Arch Linux", author = "rhysperry111", license = "CC0 1.0",
            sourceUrl = "https://bbs.archlinux.org/viewtopic.php?pid=1931567", assetPath = "wallpapers/arch_archwaveinv.webp", focusX = 0.5f,
            colors = listOf(Color(0xFFFFFFFF), Color(0xFFF8F8F8), Color(0xFFD6D6D6), Color(0xFFE9E9E9))
        ),
        FreeWallpaper(
            id = "arch_awesome", name = "Awesome", category = "Arch Linux", author = "baron-digit", license = "CC0 1.0",
            sourceUrl = "https://bbs.archlinux.org/viewtopic.php?pid=1931273", assetPath = "wallpapers/arch_awesome.webp", focusX = 0.5f,
            colors = listOf(Color(0xFF31192C), Color(0xFF290D2A), Color(0xFF2D0F2C), Color(0xFF271128))
        ),
        FreeWallpaper(
            id = "arch_conference", name = "Conference", category = "Arch Linux", author = "svimanet", license = "CC0 1.0",
            sourceUrl = "https://bbs.archlinux.org/viewtopic.php?pid=1931283", assetPath = "wallpapers/arch_conference.webp", focusX = 0.5f,
            colors = listOf(Color(0xFF145B7B), Color(0xFF145F81), Color(0xFF156489), Color(0xFF145B7B))
        ),
        FreeWallpaper(
            id = "arch_geolanes", name = "Geolanes", category = "Arch Linux", author = "xyproto", license = "CC0 1.0",
            sourceUrl = "https://bbs.archlinux.org/viewtopic.php?pid=1930379", assetPath = "wallpapers/arch_geolanes.webp", focusX = 0.5f,
            colors = listOf(Color(0xFF050505), Color(0xFF0A0A0A), Color(0xFF050505), Color(0xFF060606))
        ),
        FreeWallpaper(
            id = "arch_geowaves", name = "Geowaves", category = "Arch Linux", author = "xyproto", license = "CC0 1.0",
            sourceUrl = "https://bbs.archlinux.org/viewtopic.php?pid=1930379", assetPath = "wallpapers/arch_geowaves.webp", focusX = 0.5f,
            colors = listOf(Color(0xFF070707), Color(0xFF0B0B0B), Color(0xFF070707), Color(0xFF070707))
        ),
        FreeWallpaper(
            id = "arch_gritty", name = "Gritty", category = "Arch Linux", author = "astize y xyproto", license = "CC0 1.0",
            sourceUrl = "https://bbs.archlinux.org/viewtopic.php?pid=1931174", assetPath = "wallpapers/arch_gritty.webp", focusX = 0.5f,
            colors = listOf(Color(0xFF121619), Color(0xFF1E2931), Color(0xFF2A3339), Color(0xFF353C40))
        ),
        FreeWallpaper(
            id = "arch_simple", name = "Simple", category = "Arch Linux", author = "xyproto", license = "CC0 1.0",
            sourceUrl = "https://bbs.archlinux.org/viewtopic.php?pid=1931204", assetPath = "wallpapers/arch_simple.webp", focusX = 0.5f,
            colors = listOf(Color(0xFF202320), Color(0xFF222624), Color(0xFF222725), Color(0xFF202320))
        ),
        FreeWallpaper(
            id = "arch_split", name = "Split", category = "Arch Linux", author = "astize", license = "CC0 1.0",
            sourceUrl = "https://bbs.archlinux.org/viewtopic.php?pid=1930404", assetPath = "wallpapers/arch_split.webp", focusX = 0.5f,
            colors = listOf(Color(0xFF10130F), Color(0xFF121511), Color(0xFF131612), Color(0xFF10130F))
        ),
        FreeWallpaper(
            id = "arch_wave", name = "Wave", category = "Arch Linux", author = "baron-digit", license = "CC0 1.0",
            sourceUrl = "https://bbs.archlinux.org/viewtopic.php?pid=1931273", assetPath = "wallpapers/arch_wave.webp", focusX = 0.5f,
            colors = listOf(Color(0xFF230E26), Color(0xFF281734), Color(0xFF2F2138), Color(0xFF210C25))
        ),
        FreeWallpaper(
            id = "arch_wirefeather", name = "Wirefeather", category = "Arch Linux", author = "xyproto", license = "CC0 1.0",
            sourceUrl = "https://bbs.archlinux.org/viewtopic.php?pid=1930379", assetPath = "wallpapers/arch_wirefeather.webp", focusX = 0.5f,
            colors = listOf(Color(0xFFFCFCFC), Color(0xFFCBCBCB), Color(0xFFC9C9C9), Color(0xFFB9B9B9))
        ),
        FreeWallpaper(
            id = "arch_wireparts", name = "Wireparts", category = "Arch Linux", author = "xyproto", license = "CC0 1.0",
            sourceUrl = "https://bbs.archlinux.org/viewtopic.php?pid=1930379", assetPath = "wallpapers/arch_wireparts.webp", focusX = 0.5f,
            colors = listOf(Color(0xFFFFFFFF), Color(0xFFFAFAFA), Color(0xFFFCFCFC), Color(0xFFF7F7F7))
        ),
        FreeWallpaper(
            id = "xfce_x", name = "X (Xfce 4.20)", category = "Xfce", author = "kaz sb", license = "CC BY-SA 4.0",
            sourceUrl = "https://gitlab.xfce.org/xfce/xfdesktop/-/blob/d08a94c7d1daaccab3dedb07542133cfabf3025e/backgrounds/README.md", assetPath = "wallpapers/xfce_x.webp", focusX = 0.68f,
            colors = listOf(Color(0xFF023E52), Color(0xFF05485E), Color(0xFF03485D), Color(0xFF023F52))
        ),
        FreeWallpaper(
            id = "xfce_light", name = "Light (Xfce 4.20)", category = "Xfce", author = "Denys DEKUVE", license = "CC BY-SA 4.0",
            sourceUrl = "https://gitlab.xfce.org/xfce/xfdesktop/-/blob/d08a94c7d1daaccab3dedb07542133cfabf3025e/backgrounds/README.md", assetPath = "wallpapers/xfce_light.webp", focusX = 0.5f,
            colors = listOf(Color(0xFF898775), Color(0xFF403F38), Color(0xFF263030), Color(0xFF4C5656))
        ),
        FreeWallpaper(
            id = "xfce_mouserace", name = "Mouse Race (Xfce 4.20)", category = "Xfce", author = "Rose Pierce", license = "CC BY-SA 4.0",
            sourceUrl = "https://gitlab.xfce.org/xfce/xfdesktop/-/blob/d08a94c7d1daaccab3dedb07542133cfabf3025e/backgrounds/README.md", assetPath = "wallpapers/xfce_mouserace.webp", focusX = 0.32f,
            colors = listOf(Color(0xFF074053), Color(0xFF124F62), Color(0xFF165569), Color(0xFF0B4557))
        ),
        FreeWallpaper(
            id = "xfce_cp_dark", name = "CP Dark (Xfce 4.20)", category = "Xfce", author = "Farhang Bakhshi", license = "CC BY-SA 4.0",
            sourceUrl = "https://gitlab.xfce.org/xfce/xfdesktop/-/blob/d08a94c7d1daaccab3dedb07542133cfabf3025e/backgrounds/README.md", assetPath = "wallpapers/xfce_cp_dark.webp", focusX = 0.55f,
            colors = listOf(Color(0xFF202C34), Color(0xFF202D36), Color(0xFF1F2D35), Color(0xFF202C34))
        ),
        FreeWallpaper(
            id = "xfce_shapes", name = "Shapes (Xfce 4.18)", category = "Xfce", author = "Katerina Shkel", license = "CC BY-SA 4.0",
            sourceUrl = "https://gitlab.xfce.org/xfce/xfdesktop/-/blob/d08a94c7d1daaccab3dedb07542133cfabf3025e/backgrounds/README.md", assetPath = "wallpapers/xfce_shapes.webp", focusX = 0.52f,
            colors = listOf(Color(0xFF242D3C), Color(0xFF222E3E), Color(0xFF232F40), Color(0xFF242C3B))
        ),
        FreeWallpaper(
            id = "xfce_flower", name = "Flower (Xfce 4.18)", category = "Xfce", author = "Denis Kuzminok", license = "CC BY-SA 4.0",
            sourceUrl = "https://gitlab.xfce.org/xfce/xfdesktop/-/blob/d08a94c7d1daaccab3dedb07542133cfabf3025e/backgrounds/README.md", assetPath = "wallpapers/xfce_flower.webp", focusX = 0.67f,
            colors = listOf(Color(0xFF008BB6), Color(0xFF0188B1), Color(0xFF018BB6), Color(0xFF0091BE))
        ),
        FreeWallpaper(
            id = "xfce_leaves", name = "Leaves (Xfce 4.18)", category = "Xfce", author = "Denis Kuzminok", license = "CC BY-SA 4.0",
            sourceUrl = "https://gitlab.xfce.org/xfce/xfdesktop/-/blob/d08a94c7d1daaccab3dedb07542133cfabf3025e/backgrounds/README.md", assetPath = "wallpapers/xfce_leaves.webp", focusX = 0.5f,
            colors = listOf(Color(0xFF33313B), Color(0xFF453940), Color(0xFF3E373F), Color(0xFF32313B))
        ),
        FreeWallpaper(
            id = "xfce_verticals", name = "Verticals (Xfce 4.16)", category = "Xfce", author = "Pasi Lallinaho", license = "CC BY-SA 4.0",
            sourceUrl = "https://gitlab.xfce.org/xfce/xfdesktop/-/blob/d08a94c7d1daaccab3dedb07542133cfabf3025e/backgrounds/README.md", assetPath = "wallpapers/xfce_verticals.webp", focusX = 0.5f,
            colors = listOf(Color(0xFF006584), Color(0xFF00799E), Color(0xFF038BB5), Color(0xFF0190BC))
        ),
        FreeWallpaper(
            id = "xfce_stripes", name = "Stripes (Xfce 4.14)", category = "Xfce", author = "Pasi Lallinaho", license = "CC BY-SA 4.0",
            sourceUrl = "https://gitlab.xfce.org/xfce/xfdesktop/-/blob/d08a94c7d1daaccab3dedb07542133cfabf3025e/backgrounds/README.md", assetPath = "wallpapers/xfce_stripes.webp", focusX = 0.49f,
            colors = listOf(Color(0xFF1AA6F7), Color(0xFF42A9F5), Color(0xFF0F7CEE), Color(0xFF0C64E8))
        ),
        FreeWallpaper(
            id = "xfce_teal", name = "Teal (Xfce 4.12)", category = "Xfce", author = "Pasi Lallinaho", license = "CC BY-SA 4.0",
            sourceUrl = "https://gitlab.xfce.org/xfce/xfdesktop/-/blob/d08a94c7d1daaccab3dedb07542133cfabf3025e/backgrounds/README.md", assetPath = "wallpapers/xfce_teal.webp", focusX = 0.5f,
            colors = listOf(Color(0xFF28C3BA), Color(0xFF41C1C8), Color(0xFF54B6C9), Color(0xFF1481A8))
        )
    )

    /**
     * Choose a wallpaper. With rotation on, rotation continues from the chosen one (otherwise tapping a wallpaper
     * changed nothing visible); choosing your own image stops the rotation so it does not cover it.
     */
    fun choose(prefs: org.senda.browser.core.PreferencesManager, id: String) {
        prefs.selectedWallpaperId = id
        if (id == "custom_user") {
            prefs.wallpaperRotationMinutes = 0
        } else {
            prefs.wallpaperRotationCurrentId = id
            prefs.wallpaperRotationChangedAt = System.currentTimeMillis()
        }
    }

    /** Another random wallpaper, different from the current one, for the rotation. */
    fun nextRandom(currentId: String?): FreeWallpaper =
        items.filter { it.id != currentId }.random()

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

/** Crops centered on [focusX] (0 = left, 1 = right) without leaving empty borders. */
private class FocusAlignment(private val focusX: Float) : Alignment {
    override fun align(size: IntSize, space: IntSize, layoutDirection: LayoutDirection): IntOffset {
        val x = if (size.width > space.width) {
            (space.width / 2f - focusX * size.width).coerceIn((space.width - size.width).toFloat(), 0f).toInt()
        } else (space.width - size.width) / 2
        return IntOffset(x, (space.height - size.height) / 2)
    }
}

/**
 * The works come in high resolution (up to 5120 px) for PCs, tablets or viewers; they are decoded at the size
 * of the space where they are shown, so a phone screen does not load ~60 MB per wallpaper and each picker
 * thumbnail takes only what is visible.
 */
private fun decodeToFit(open: () -> java.io.InputStream, width: Int, height: Int): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    open().use { BitmapFactory.decodeStream(it, null, bounds) }
    val srcW = bounds.outWidth
    val srcH = bounds.outHeight
    if (srcW <= 0 || srcH <= 0) return null
    // "Crop to fill" scale, never above the original
    val scale = if (width > 0 && height > 0) minOf(1f, maxOf(width.toFloat() / srcW, height.toFloat() / srcH)) else 1f
    val targetW = maxOf(1, (srcW * scale).toInt())
    val targetH = maxOf(1, (srcH * scale).toInt())
    var sample = 1
    while (srcW / (sample * 2) >= targetW && srcH / (sample * 2) >= targetH) sample *= 2
    val decoded = open().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        ?: return null
    if (decoded.width <= targetW * 1.15f) return decoded
    return android.graphics.Bitmap.createScaledBitmap(decoded, targetW, targetH, true).also {
        if (it !== decoded) decoded.recycle()
    }
}

@Composable
fun FreeWallpaperBackground(
    wallpaper: FreeWallpaper,
    dimPercent: Int = 0,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    androidx.compose.foundation.layout.BoxWithConstraints(modifier = modifier.fillMaxSize()) {
    val targetW = constraints.maxWidth.takeIf { it != androidx.compose.ui.unit.Constraints.Infinity } ?: 0
    val targetH = constraints.maxHeight.takeIf { it != androidx.compose.ui.unit.Constraints.Infinity } ?: 0
    var imageBitmap by remember(wallpaper.assetPath, wallpaper.filePath) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(wallpaper.assetPath, wallpaper.filePath, targetW, targetH) {
        val asset = wallpaper.assetPath
        val file = wallpaper.filePath
        imageBitmap = withContext(Dispatchers.IO) {
            try {
                // In small spaces (picker thumbnails) the 320 px copy is used, not the original of
                // up to 5120 px: decoding 54 originals while scrolling the row made it stutter
                val thumb = asset?.let { "wallpapers/thumbs/" + it.substringAfterLast('/') }
                if (asset != null && thumb != null && targetH in 1..400) {
                    decodeToFit({ context.assets.open(thumb) }, targetW, targetH)?.asImageBitmap()
                } else if (asset != null) {
                    decodeToFit({ context.assets.open(asset) }, targetW, targetH)?.asImageBitmap()
                } else if (file != null && java.io.File(file).exists()) {
                    decodeToFit({ java.io.FileInputStream(file) }, targetW, targetH)?.asImageBitmap()
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val currentBitmap = imageBitmap
        if (currentBitmap != null) {
            Image(
                bitmap = currentBitmap,
                contentDescription = wallpaper.name,
                contentScale = ContentScale.Crop,
                alignment = FocusAlignment(wallpaper.focusX),
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val width = size.width
                val height = size.height

                // Base gradient background
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = wallpaper.colors,
                        startY = 0f,
                        endY = height
                    )
                )

            // Soft atmospheric glow at the top
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

            // Subtle glow at the bottom for contrast
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
}
