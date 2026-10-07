# Third-party components

Senda is distributed under the **GNU GPL v3** (see `LICENSE`). It includes or uses these components, each under its own license:

| Component | Author | License | Use in Senda |
|---|---|---|---|
| [GeckoView](https://mozilla.github.io/geckoview/) | Mozilla | MPL 2.0 | Web engine (HTML, CSS, JavaScript) |
| [uBlock Origin](https://github.com/gorhill/uBlock) | Raymond Hill | GPL v3 | Content blocker; its signed `.xpi` is included unmodified (`app/src/main/assets/extensions/ublock.xpi`) |
| [Tor](https://gitlab.torproject.org/tpo/core/tor), [tor-android](https://github.com/guardianproject/tor-android), [jtorctl](https://github.com/guardianproject/jtorctl) | The Tor Project, Guardian Project | BSD 3-Clause | Built-in Tor network (optional) |
| [Jetpack Compose and AndroidX](https://developer.android.com/jetpack/androidx) (including `androidx.biometric`) | Google / AOSP | Apache 2.0 | User interface and fingerprint/PIN |
| [Kotlin and kotlinx.coroutines](https://kotlinlang.org) | JetBrains | Apache 2.0 | Language and concurrency |
| [HiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass) | LSPosed | Apache 2.0 | GeckoView compatibility on some Android versions |
| [Cantarell](https://gitlab.gnome.org/GNOME/cantarell-fonts) | The Cantarell Project Authors | SIL OFL 1.1 (notice included in the font metadata) | Optional interface typeface |
| [Mozilla Public Suffix List](https://publicsuffix.org/) | Mozilla | MPL 2.0 | Domain canonicalization and anti-phishing in Password Vault (`app/src/main/assets/public_suffix_list.dat`) |

## Wallpapers

The 64 wallpapers in `app/src/main/assets/wallpapers/` come from Debian, GNU, Gentoo, KDE, GNOME, Fedora, Arch Linux and Xfce. Each one keeps its original license: CC BY-SA 2.5 / 3.0 / 4.0, CC0 1.0, GPL-2.0, GPL-2.0+, GPL-3.0, LGPL-3.0 or GFDL-1.3. The author, license and source of every image are listed in the catalog `app/src/main/java/org/senda/browser/ui/components/FreeWallpapers.kt` and shown in the app next to each wallpaper.

## Inspiration (no code included)

The privacy configuration is inspired by [LibreWolf](https://librewolf.net) and [arkenfox user.js](https://github.com/arkenfox/user.js), but Senda includes no code or configuration files from those projects.
