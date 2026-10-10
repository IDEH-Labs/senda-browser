# Senda

**A free and private web browser for Android, built on Mozilla GeckoView.**

The interface is available in 8 languages: Spanish, English, German, French, Portuguese, Italian, Japanese and Chinese.

> **Status: beta (0.2.0-beta.5).** Its author uses it every day and it is ready for wider testing, but it is **not yet recommended for sensitive data**:
> - Tested on a single phone model (Motorola moto g34 5G, Android 15).
> - Signed release builds have been tested on that phone: startup, browsing, reader mode, Tor, uBlock Origin and Settings (0.1.1); the password vault filling in and saving accounts, and TV mode (a pre-release build of 0.1.2); browsing and the password vault (0.1.3, 0.1.4).
> - Tried by hand on that phone (October 9): the saved passwords open only after fingerprint or PIN, and wallpaper downloads work. Not yet tried by hand (only by automated tests): password import/export and the encrypted backup.
> - Passkeys tried by hand on that phone (October 9, 0.2.0-beta.2): creating one and signing in with it on webauthn.io, stored in Bitwarden.
> - TV mode measured on that phone (October 10, 0.2.0-beta.5) with an LG TV over Miracast: rotating the phone fills the TV in 16:9, and a 1080p 60 fps VP9 video played without dropped frames, decoded by the phone's hardware.
> - It has not had an external security audit.
> - Known issue: with Tor on, the first page opened right after Senda starts can show a connection error until Tor finishes connecting; tapping Retry loads it.
> - Known report not yet reproduced: on October 5, 2026 Senda once would not close and kept reopening while casting to a TV, watching video and using the AI assistant that Senda had then, all at the same time. If it happens to you, please report it.
> - Privacy policy: [PRIVACY.md](PRIVACY.md).
> - Security problems: please report them privately, as described in [SECURITY.md](SECURITY.md).
>
> Bug reports are welcome.

---

## Features

**Browsing**
- GeckoView 157 engine (Mozilla), independent of Chromium.
- Separate normal and private tabs, in grid or list view. On launch: a clean home page, resume where you left off, or start fresh.
- Bookmarks, history and downloads; find in page; desktop site; print or save as PDF; shortcut on the Android home screen.
- Reader mode in 8 languages, with "key sentences" and search within the text. It works offline and only shows literal sentences from the article.
- On-device page translation using Gecko's local translator; the text never leaves the phone.
- Home page with shortcuts and four layouts (Focused, Inspirational, Informational and Custom); 64 freely licensed wallpapers that fill any screen without cutting the important part (a few wide designs are shown only in landscape). Five come with the app; the others are downloaded only when you choose them.

**Privacy and security**
- Strict tracking protection, isolated third-party cookies, tracking-parameter stripping in URLs, and Global Privacy Control.
- Built-in uBlock Origin.
- HTTPS-only mode and DNS over HTTPS, both on by default.
- Built-in Tor network (optional), or your own SOCKS5/HTTP proxy.
- Password vault encrypted with AES-256-GCM. The key is generated and used only inside the phone's secure hardware (StrongBox or TEE); on phones without secure hardware the vault stays disabled. It requires a fingerprint or PIN, and it fills in and saves accounts on web pages. It is designed to offer an account only on the site where it was saved (domains are resolved with Mozilla's Public Suffix List); this has not yet been tested across different sites.
- Import passwords from the CSV exported by Chrome, Brave, Edge, Firefox, Bitwarden or KeePassXC, and export them (after fingerprint or PIN).
- Encrypted backup of bookmarks, settings, history and passwords, with a passphrase only you know, in the open [age](https://age-encryption.org) format: save it wherever you want (Syncthing, Nextcloud, a USB drive) and open it with `age -d` on any system. Format: [docs/backup-format.md](docs/backup-format.md).
- Passkeys and security keys (WebAuthn) through Android's Credential Manager (Android 14 and later), with the password
  manager you use, such as Bitwarden. Google Password Manager does not accept Senda yet: Google only serves browsers it
  has approved.
- No proprietary code: the Google Play Services client library that GeckoView depends on is replaced with microG's free
  one.
- Optional app lock with fingerprint, and optional screenshot protection.

**Other**
- Casting to a TV through Android screen mirroring (Miracast), with an optional TV mode that fills the TV in 16:9 when you
  rotate the phone (it needs a permission that can only be granted once with ADB). Videos pause when you leave Senda for
  another app, unless they are playing on the TV.
- Extensions from `.xpi` files, custom CSS and scripts (Senda Labs: they run isolated from pages, like an extension,
  and never in private tabs), and a developer console on the phone itself.

## Connections Senda makes on its own

So you know exactly what leaves your phone without you asking:

- **Safe Browsing (Google)**, on by default: downloads lists of dangerous sites. It can be turned off in Settings.
- **uBlock Origin**: periodically updates its filter lists from each list's servers.
- **Home page news**, in the Inspirational and Informational layouts, and in Custom if you turn news on: reads the RSS feeds of MuyLinux, EFF and FSF. The Focused layout neither shows nor downloads news.
- **Translation**: the first time you translate a language, it downloads that language's model from Mozilla. Translation then runs on the phone.
- **Removed assistant**: once only, when updating from a version that had the AI assistant, a ChatGPT session you had signed in with is revoked at OpenAI; the assistant's data is deleted from the phone.
- **Wallpapers**: only when you choose a wallpaper that is not included (and after asking), it is downloaded from this repository's `wallpapers-1` release on GitHub (GitLab as fallback), through the same Tor or proxy as browsing; its SHA-256 is checked.

Senda includes no telemetry or usage reports. Firefox's captive portal detection is turned off.

## Installing

- **Releases**: signed APKs on [GitHub](https://github.com/IDEH-Labs/senda-browser/releases) (mirrored on
  [GitLab](https://gitlab.com/ideh-labs/senda-browser/-/releases)), each with its SHA-256.
- **F-Droid client**: add the IDEH Labs repository `https://senda-fdroid-7a0636.gitlab.io/fdroid/repo` and check that its
  fingerprint is `2A4DF40FECF9E96C3986E500F42C2EC99065BAA5AA7CA90B50F887D7A886FCF2`. It serves the same APKs as the releases.
- Every APK is signed by IDEH Labs with certificate SHA-256
  `431d64a9a39e299956aef5880b88c4830fc81add2acdd9571c76d5323c775309`.

Senda is not in the official F-Droid repository yet: F-Droid builds everything from source, and Senda still uses
Mozilla's prebuilt GeckoView.

## Building

Requirements: JDK 17, the Android SDK with platform 37, and network access: Gradle downloads the dependencies and the official signed uBlock Origin release, which is checked against a pinned SHA-256 (see `app/bundled-assets.gradle`). The APK only includes `arm64-v8a`, and the app runs on Android 8.0 (API 26) and later.

```bash
git clone https://github.com/IDEH-Labs/senda-browser.git
cd senda-browser
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

**Release build.** Without a key, `./gradlew assembleRelease` produces an **unsigned** APK; the debug key is never used. To sign it, set `KEYSTORE_PATH`, `KEY_ALIAS`, `STORE_PASSWORD` and `KEY_PASSWORD`, or create `keystore.properties` in the project root with `storeFile`, `keyAlias`, `storePassword` and `keyPassword`. That file is listed in `.gitignore`.

## Tests

Unit tests run on the host JVM: the Public Suffix List, password CSV import/export, the backup format, and the age implementation against the official [C2SP CCTV](https://github.com/C2SP/CCTV) test vectors:

```bash
./gradlew testDebugUnitTest
```

Instrumented tests (`app/src/androidTest`) need a connected phone.

```bash
./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w org.senda.browser.test/androidx.test.runner.AndroidJUnitRunner
```

> **Warning:** `./gradlew connectedAndroidTest` uninstalls the app and **deletes its data** (passwords, history…). Tests that delete data only run if you pass `-e allowDestructive 1`.

## License

Senda is free software under the **GNU General Public License v3** (see [`LICENSE`](LICENSE)). Third-party components and their licenses are listed in [`THIRD_PARTY_LICENSES.md`](THIRD_PARTY_LICENSES.md).

---

An **[IDEH Labs](https://github.com/IDEH-Labs)** project · [GitHub](https://github.com/IDEH-Labs/senda-browser) · [GitLab](https://gitlab.com/ideh-labs/senda-browser)
