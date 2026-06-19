# P1.Z — F-Droid Release Prep Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Make the app F-Droid-submission-ready: GPLv3 SPDX headers, a real launcher icon, fastlane metadata, a release signing config (no secrets), `versionName 1.0.0`, a green `assembleRelease`, and `docs/RELEASE.md` (FOSS audit + reproducible-build + submission guide). Stop before the actual submission.

**Architecture:** All changes are additive packaging/metadata/config — no app behavior change. The FOSS audit is already done (see spec): the graph is fully FOSS, no `INTERNET` permission, no anti-features.

**Tech Stack:** Gradle (Kotlin DSL), Android resources (vector/adaptive icon), fastlane metadata layout, Markdown docs.

## Global Constraints

- Build with JDK 21 — prefix every gradle call: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew ...`. Repo root: `/Users/mustafa/Desktop/Projects/mobil uygulama/video-player`.
- No app behavior change; no new dependencies (graph must stay fully FOSS). `:core:*` gets SPDX comments only.
- No secrets committed (keystore + `keystore.properties` gitignored).
- `assembleDebug` + all unit suites stay green; `assembleRelease` must build (unsigned if no keystore).
- Spec: `docs/superpowers/specs/2026-06-19-p1z-fdroid-release-prep-design.md`.
- **HARD STOP:** do NOT perform the F-Droid submission.

---

### Task Z1: GPLv3 SPDX headers on all Kotlin sources

**Files:** every `*.kt` under `core/*/src/**` and `app/src/**`.

- [ ] **Step 1: Prepend the SPDX header** to each `.kt` file that does not already start with it. Run from the repo root:

```bash
cd "/Users/mustafa/Desktop/Projects/mobil uygulama/video-player"
HEADER='// SPDX-License-Identifier: GPL-3.0-or-later'
find app/src core -name '*.kt' -type f | while read -r f; do
  if ! head -1 "$f" | grep -q 'SPDX-License-Identifier'; then
    printf '%s\n%s' "$HEADER" "$(cat "$f")" > "$f.tmp" && mv "$f.tmp" "$f"
  fi
done
```
This inserts the one-line comment above the existing `package` declaration (legal Kotlin). Idempotent (skips files already headed).

- [ ] **Step 2: Verify nothing broke** — `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:assembleDebug :app:testDebugUnitTest :core:playback:test :core:model:test`. Expected: all green (the header is a comment; no behavior change).

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "chore: add GPL-3.0-or-later SPDX headers to all Kotlin sources"
```

---

### Task Z2: Original launcher icon (adaptive vector)

**Files:**
- Create: `app/src/main/res/drawable/ic_launcher_background.xml` (solid color), `app/src/main/res/drawable/ic_launcher_foreground.xml` (play-triangle), `app/src/main/res/drawable/ic_launcher_monochrome.xml` (play-triangle, themed-icon layer)
- Create/replace: `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` + `ic_launcher_round.xml` (adaptive `<adaptive-icon>` referencing the three layers)
- Modify: `app/src/main/AndroidManifest.xml` `android:icon`/`android:roundIcon` if needed (point to `@mipmap/ic_launcher`)
- Remove: any default `mipmap-*/ic_launcher.*` PNG/webp + the default `ic_launcher_foreground`/`background` if they shadow the new ones.

- [ ] **Step 1: Background vector** (`ic_launcher_background.xml`) — a solid deep-indigo 108×108 canvas:
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#1A1A2E" android:pathData="M0,0h108v108h-108z" />
</vector>
```

- [ ] **Step 2: Foreground vector** (`ic_launcher_foreground.xml`) — a centered white play triangle inside the adaptive safe zone (the icon content should sit within the center ~66dp):
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#FFFFFF" android:pathData="M44,38 L72,54 L44,70 Z" />
</vector>
```

- [ ] **Step 3: Monochrome vector** (`ic_launcher_monochrome.xml`) — same play triangle, for Android 13+ themed icons:
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#FFFFFF" android:pathData="M44,38 L72,54 L44,70 Z" />
</vector>
```

- [ ] **Step 4: Adaptive icon XMLs** (`mipmap-anydpi-v26/ic_launcher.xml` and `ic_launcher_round.xml`, identical content):
```xml
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
```

- [ ] **Step 5: Ensure the manifest references the icon** — confirm `<application>` (or the existing icon attr) uses `android:icon="@mipmap/ic_launcher"` and `android:roundIcon="@mipmap/ic_launcher_round"`. Remove any conflicting default raster `ic_launcher` mipmaps so the adaptive XML is used at all densities (delete `mipmap-mdpi..xxxhdpi/ic_launcher*.png|webp` if present).

- [ ] **Step 6: Build + install + visually confirm the icon** — `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:assembleDebug`. Expected: BUILD SUCCESSFUL. (Controller verifies the launcher icon on the emulator in Z6.)

- [ ] **Step 7: Commit**

```bash
git add app/src/main/res app/src/main/AndroidManifest.xml && git commit -m "feat: original adaptive launcher icon (play-triangle), replacing the default"
```

---

### Task Z3: Release signing config + version + .gitignore

**Files:**
- Modify: `app/build.gradle.kts` (signingConfigs + release signingConfig + versionName)
- Modify: `.gitignore` (keystore secrets)

- [ ] **Step 1: Add the signing config + version bump** to `app/build.gradle.kts`. At the top of the file (after the existing imports / before `android {`), add:
```kotlin
import java.util.Properties
```
Inside `android { }`, set `versionName = "1.0.0"` (keep `versionCode = 1`), and add a `signingConfigs` block + wire the release build type:
```kotlin
    signingConfigs {
        create("release") {
            val propsFile = rootProject.file("keystore.properties")
            if (propsFile.exists()) {
                val props = Properties().apply { propsFile.inputStream().use { load(it) } }
                storeFile = props.getProperty("storeFile")?.let { file(it) }
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (rootProject.file("keystore.properties").exists()) {
                signingConfigs.getByName("release")
            } else {
                null // F-Droid signs with its own key; unsigned release is fine for the F-Droid pipeline.
            }
        }
    }
```
(Replace the existing `buildTypes { release { isMinifyEnabled = false } }` block.)

- [ ] **Step 2: Gitignore keystore secrets** — append to `.gitignore`:
```
# Release signing (never commit)
keystore.properties
*.jks
*.keystore
!debug.keystore
```

- [ ] **Step 3: Verify both build types build** — `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:assembleDebug :app:assembleRelease`. Expected: BUILD SUCCESSFUL (release is unsigned since no `keystore.properties`); APK at `app/build/outputs/apk/release/app-release-unsigned.apk`.

- [ ] **Step 4: Commit**

```bash
git add app/build.gradle.kts .gitignore && git commit -m "build: release signing config (keystore.properties, gitignored) + versionName 1.0.0"
```

---

### Task Z4: fastlane metadata + RELEASE.md (controller authors content; verify layout)

**Files (created by the controller, committed as one step):**
- `fastlane/metadata/android/en-US/title.txt`
- `fastlane/metadata/android/en-US/short_description.txt`
- `fastlane/metadata/android/en-US/full_description.txt`
- `fastlane/metadata/android/en-US/changelogs/1.txt`
- `fastlane/metadata/android/en-US/images/phoneScreenshots/*.png` (clean emulator captures)
- `fastlane/metadata/android/en-US/images/icon.png`
- `docs/RELEASE.md` (FOSS audit summary + keystore format + reproducible-build notes + F-Droid submission guide + draft `.fdroid.yml`)

This task is authored directly (it is deliverable content, not delegable logic) and verified by file presence + the F-Droid fastlane layout. No code build needed beyond what Z1–Z3 covered.

- [ ] **Step 1: Write the metadata text files** (title ≤50, short_description ≤80 chars; full_description plain text/limited markdown).
- [ ] **Step 2: Capture 3–4 clean phone screenshots** from the `videoplayer` AVD (library + player-with-controls + subtitle CC menu + settings) and place them in `images/phoneScreenshots/`; export the launcher icon foreground as `images/icon.png`.
- [ ] **Step 3: Write `docs/RELEASE.md`** with the audit, keystore.properties format, reproducible-build notes, and the submission guide + draft `.fdroid.yml`.
- [ ] **Step 4: Commit**

```bash
git add fastlane docs/RELEASE.md && git commit -m "docs: fastlane metadata, screenshots, and F-Droid release/submission guide"
```

---

### Task Z5: Final release verification (controller)

- [ ] `./gradlew clean :app:assembleRelease` builds from clean. Record the APK path + size.
- [ ] Install the release (unsigned won't install directly; install debug for the icon check) and confirm on the emulator: the new launcher icon shows; the app launches and plays; no crash.
- [ ] Record results in the ledger; whole-branch review; merge to main + push.

---

### Task Z6: STOP — report submission-ready (HARD STOP)

- [ ] Do NOT submit. Report to the user that the app is F-Droid-submission-ready and list the remaining outward-facing steps (generate/secure a release keystore if non-F-Droid distribution is wanted; finalize branding/icon if desired; open the `fdroiddata` merge request per `docs/RELEASE.md`).

---

## Self-Review

- **Spec coverage:** SPDX headers ✅ (Z1); FOSS audit ✅ (done in spec, documented in Z4 RELEASE.md); launcher icon ✅ (Z2); fastlane metadata + screenshots ✅ (Z4); signing config (no secrets) ✅ (Z3); versionName 1.0.0 ✅ (Z3); assembleRelease sanity ✅ (Z3/Z5); reproducible-build notes + submission guide ✅ (Z4); stop-before-submission ✅ (Z6).
- **Placeholder scan:** concrete commands/XML/steps throughout; Z4 content is authored by the controller (the deliverable IS the content).
- **No new deps / no behavior change / no secrets:** confirmed.
