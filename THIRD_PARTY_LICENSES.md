# Componentes de terceros

Senda se distribuye bajo la **GNU GPL v3** (ver `LICENSE`). Incluye o usa estos componentes, cada uno con su propia licencia:

| Componente | Autor | Licencia | Uso en Senda |
|---|---|---|---|
| [GeckoView](https://mozilla.github.io/geckoview/) | Mozilla | MPL 2.0 | Motor web (HTML, CSS, JavaScript) |
| [uBlock Origin](https://github.com/gorhill/uBlock) | Raymond Hill | GPL v3 | Bloqueador de contenido; se incluye su `.xpi` firmado sin modificar (`app/src/main/assets/extensions/ublock.xpi`) |
| [Tor](https://gitlab.torproject.org/tpo/core/tor), [tor-android](https://github.com/guardianproject/tor-android), [jtorctl](https://github.com/guardianproject/jtorctl) | The Tor Project, Guardian Project | BSD 3-Clause | Red Tor integrada (opcional) |
| [Jetpack Compose y AndroidX](https://developer.android.com/jetpack/androidx) (incluida `androidx.biometric`) | Google / AOSP | Apache 2.0 | Interfaz y huella/PIN |
| [Kotlin y kotlinx.coroutines](https://kotlinlang.org) | JetBrains | Apache 2.0 | Lenguaje y concurrencia |
| [HiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass) | LSPosed | Apache 2.0 | Compatibilidad de GeckoView en algunas versiones de Android |
| [Cantarell](https://gitlab.gnome.org/GNOME/cantarell-fonts) | The Cantarell Project Authors | SIL OFL 1.1 (aviso incluido en los metadatos de la fuente) | Tipo de letra opcional de la interfaz |

## Fondos de pantalla

Los 64 fondos de `app/src/main/assets/wallpapers/` proceden de Debian, GNU, Gentoo, KDE, GNOME, Fedora, Arch Linux y Xfce. Cada uno conserva su licencia original: CC BY-SA 2.5 / 3.0 / 4.0, CC0 1.0, GPL-2.0, GPL-2.0+, GPL-3.0, LGPL-3.0 o GFDL-1.3. El autor, la licencia y la fuente de cada imagen están en el catálogo `app/src/main/java/org/senda/browser/ui/components/FreeWallpapers.kt` y se muestran dentro de la app junto a cada fondo.

## Inspiración (sin código incluido)

La configuración de privacidad se inspira en [LibreWolf](https://librewolf.net) y [arkenfox user.js](https://github.com/arkenfox/user.js), pero Senda no incluye código ni archivos de configuración de esos proyectos.
