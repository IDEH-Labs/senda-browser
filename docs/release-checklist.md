# Release checklist

Every Senda release goes through these steps, in order. Nothing is published until all of them pass; a step that
cannot be done is written in the release notes with the reason. Written so that a trusted second maintainer can make a
security release without help.

## 1. Engine and security

- `python3 -I scripts/check_geckoview.py`: "Up to date". If Mozilla published a newer GeckoView, update
  `app/build.gradle` first (a newer GeckoView may need a newer `compileSdkMinor`). The weekly schedule of the
  `senda-fdroid` repository runs this check on the published version and emails the maintainer when it fails.
- Security releases go out within days of a Mozilla advisory that applies to GeckoView.

## 2. Code

- `./gradlew :app:testDebugUnitTest` and `./gradlew :app:lintRelease`: no failures, no lint errors, no new
  warnings without a reason.
- The app compiles without Kotlin warnings.

## 3. On a phone ([automated-tests.md](automated-tests.md))

- The whole e2e suite once, then priority 1 (groups A-D) three times in a row. Report "passed N of 3" for every test
  that did not pass all three; known failures are listed in that document's limits.
- Look at the H2, H5 and H9 screenshots: no cut, overlapping or untranslated text.
- If GeckoView changed: the network audit, `scripts/network-audit/audit_e2e.sh` (75 minutes), normal and `free`.
- Never on the maintainer's own Senda: only the separate e2e app.

## 4. Build and sign

- `bash ~/.local/bin/senda-compilar-firmado` (asks for the GPG passphrase; it packages from scratch, so no old
  library is left inside the APK).
- Check the APK: certificate SHA-256 `431d64a9…5309`, size below 100 MiB, and that the sum of its entries is close to
  the file size.
- Bump `versionCode` and `versionName`, add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.

## 5. Publish

- Signed tag `v<version>` (GPG), pushed to GitHub, GitLab and Codeberg.
- Pre-release on GitHub and Codeberg with the APK and its `.sha256`; GitLab release with links. Download the published
  APK and compare its SHA-256.
- `senda-fdroid`: add the version, run its pipeline, check the index and the APK hash.
- Release notes in English: what changed, what was measured, what was not tested, known problems. No claim that was
  not measured.

## 6. Afterwards

- Install the published APK as an update on a phone and open it once.
- Keep the signing key backup outside the computer up to date (`.jks.gpg` and `.env.gpg`).
