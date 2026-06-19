<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
# Release & F-Droid Submission Guide

This document covers everything needed to release the app and submit it to F-Droid.
**The submission itself (opening the F-Droid merge request) is a manual, outward-facing
step — it has not been performed.**

## App identity

| Field | Value |
|---|---|
| Application ID | `com.videoplayer.app` |
| versionName / versionCode | `1.0.0` / `1` |
| License | GPL-3.0-or-later (full text in `LICENSE`; SPDX headers on every source file) |
| Source code | https://github.com/muctebadikmen/video-player |
| minSdk / targetSdk | 24 / 35 |

> The source repository must be **public** before F-Droid can build it.

## FOSS dependency audit (F-Droid eligibility)

Audited `gradle/libs.versions.toml`, all `build.gradle.kts`, and `AndroidManifest.xml`.
**Result: fully FOSS, no anti-features, F-Droid eligible.**

**Shipped runtime dependencies — all Apache-2.0:**
- androidx.core:core-ktx, androidx.lifecycle (runtime-ktx / runtime-compose / viewmodel-compose), androidx.activity:activity-compose
- androidx.compose (BOM, ui, ui-graphics, ui-tooling-preview, material3)
- androidx.media3 (exoplayer, ui, session) — FOSS; uses the platform's codecs, no bundled proprietary codecs
- androidx.room (runtime, ktx), androidx.datastore:datastore-preferences
- org.jetbrains.kotlinx:kotlinx-coroutines-core

**Build-time only — Apache-2.0:** Android Gradle Plugin, Kotlin (android/jvm/compose-compiler), KSP, Room compiler.

**Test-only (not shipped):** JUnit4 (EPL-1.0, FOSS), Truth, kotlinx-coroutines-test, Turbine, Robolectric, androidx.test.ext:junit (Apache-2.0).

**No proprietary dependencies:** no Google Play Services / Firebase / analytics / crash-reporting / advertising SDKs.

**No `INTERNET` permission.** The app cannot make network calls — zero telemetry is enforced at the manifest level, not just by policy.

**Anti-features:** none (no `NonFreeDep`, `NonFreeNet`, `Tracking`, `Ads`, `UpstreamNonFree`).

### Permissions (all justified by playback features)

| Permission | Why |
|---|---|
| `READ_MEDIA_VIDEO` (33+) / `READ_EXTERNAL_STORAGE` (≤32) | discover and play local videos |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | background audio via the media session |
| `POST_NOTIFICATIONS` (33+) | the media playback notification |

## Reproducible-build posture

- All dependency, Gradle, Kotlin, AGP, and KSP versions are pinned in the version catalog — no dynamic (`+`) versions.
- `release { isMinifyEnabled = false }` — no obfuscation/shrinking to complicate reproducibility.
- No build-time timestamps, `Date.now()`, or random values are baked into code or resources.
- Build with the pinned toolchain: JDK 21 (`JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`), the Gradle wrapper (8.11.1), and the SDK at compileSdk 35.
- F-Droid builds from source in its own controlled environment and **signs the APK with the F-Droid key**, so the developer signing key is not part of the F-Droid pipeline.

## Release signing (for non-F-Droid distribution: GitHub releases, Play Store)

The release build is unsigned unless a `keystore.properties` file exists at the repo root.
This file is **gitignored** — never commit it or the keystore.

`keystore.properties` format:
```properties
storeFile=/absolute/path/to/release.jks
storePassword=********
keyAlias=********
keyPassword=********
```
Generate a key (once):
```bash
keytool -genkey -v -keystore release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias videoplayer
```
Then `./gradlew :app:assembleRelease` produces a signed APK at
`app/build/outputs/apk/release/app-release.apk`. Without `keystore.properties` it produces
`app-release-unsigned.apk` (which is what F-Droid expects to sign itself).

## Store metadata

Localized metadata for F-Droid (and Play, via the same layout) lives under
`fastlane/metadata/android/en-US/`:
- `title.txt`, `short_description.txt`, `full_description.txt`
- `changelogs/1.txt` (keyed by versionCode)
- `images/phoneScreenshots/` (library, player, subtitles, settings)

F-Droid auto-imports these from the repo. The launcher icon is the adaptive play-triangle
icon in the APK; F-Droid extracts it automatically.

## F-Droid submission steps (manual — DO THIS to publish)

1. Make the source repository **public** and push a signed tag for the release commit, e.g. `git tag -s v1.0.0 && git push origin v1.0.0` (or an unsigned `git tag v1.0.0`).
2. Fork **fdroiddata**: https://gitlab.com/fdroid/fdroiddata
3. Add `metadata/com.videoplayer.app.yml` (draft below).
4. Run `fdroid lint com.videoplayer.app` and a test build (`fdroid build -v -l com.videoplayer.app`) per the F-Droid contributor docs.
5. Open a merge request against fdroiddata and respond to reviewer feedback.

### Draft `metadata/com.videoplayer.app.yml`

```yaml
Categories:
  - Multimedia
License: GPL-3.0-or-later
AuthorName: Mustafa
SourceCode: https://github.com/muctebadikmen/video-player
IssueTracker: https://github.com/muctebadikmen/video-player/issues

AutoName: Video Player

RepoType: git
Repo: https://github.com/muctebadikmen/video-player.git

Builds:
  - versionName: "1.0.0"
    versionCode: 1
    commit: v1.0.0
    subdir: app
    gradle:
      - yes

AutoUpdateMode: Version v%v
UpdateCheckMode: Tags
CurrentVersion: "1.0.0"
CurrentVersionCode: 1
```

## Open decisions for the maintainer (surfaced, non-blocking)

- **App display name** is "Video Player" (generic; many F-Droid apps share it). Consider a more distinctive brand name (changeable in `res/values/strings.xml` `app_name` and `title.txt`).
- **Launcher icon** is a simple original play-triangle vector. A commissioned icon can replace `res/drawable/ic_launcher_*.xml` later.
- **versionName 1.0.0** marks the first stable V1; adjust if you prefer a 0.x first release.
