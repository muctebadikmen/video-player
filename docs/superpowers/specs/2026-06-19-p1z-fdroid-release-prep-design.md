# P1.Z — F-Droid Release Prep Design

**Date:** 2026-06-19
**Status:** Approved for autonomous execution. The F-Droid **submission** itself is the one hard stop (outward-facing) — everything up to it is prepared here.
**Roadmap:** `docs/superpowers/plans/2026-06-17-phase0-phase1-foundation-mvp.md` → P1.Z
**Depends on:** Phase 0 + P1.A–P1.H (all merged + pushed @ `2f13f23`).

## Goal

Make the app submittable to F-Droid: confirm a fully-FOSS dependency graph, add GPLv3 SPDX headers, provide fastlane metadata (descriptions, changelog, screenshots) and a real launcher icon, add a release signing config (no secrets committed), ensure `assembleRelease` builds, and document the reproducible-build + submission steps. Stop before the actual submission.

## FOSS dependency audit (result)

Audited `gradle/libs.versions.toml` + all `build.gradle.kts` + `AndroidManifest.xml`:

- **Shipped runtime deps — all Apache-2.0, FOSS:** AndroidX core-ktx, lifecycle (runtime/runtime-compose/viewmodel-compose), activity-compose, Compose (BOM, ui, ui-graphics, ui-tooling-preview, material3), Media3 (exoplayer, ui, session), Room (runtime, ktx), DataStore preferences, kotlinx-coroutines-core.
- **Build-time only — Apache-2.0:** AGP, Kotlin (android/jvm/compose-compiler), KSP, Room compiler.
- **Test-only (not shipped):** JUnit4 (EPL-1.0 — FOSS), Truth, kotlinx-coroutines-test, Turbine, Robolectric, androidx.test.ext:junit (all Apache-2.0).
- **No proprietary deps:** no Google Play Services / Firebase / analytics / crash-reporting / ad SDKs. Media3/ExoPlayer is FOSS and uses the platform's codecs (no bundled proprietary codecs).
- **No `INTERNET` permission** in the manifest → the app makes no network calls; **zero telemetry confirmed at the permission level.** Permissions are only `READ_MEDIA_VIDEO` / `READ_EXTERNAL_STORAGE`(≤32) / `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MEDIA_PLAYBACK` / `POST_NOTIFICATIONS` — all justified by playback features.

**Conclusion:** F-Droid-eligible, **no anti-features** (no NonFreeDep, NonFreeNet, Tracking, Ads). Nothing to flag.

## Scope & components (all additive; no app behavior change)

1. **GPLv3 SPDX headers.** Prepend `// SPDX-License-Identifier: GPL-3.0-or-later` to every Kotlin source file (`:app` + `:core:*`, main + test) that lacks it. The full GPLv3 text already lives in `LICENSE`. SPDX one-liners are the concise REUSE-compliant form. Must not break compilation (the comment precedes `package`).
2. **Launcher icon.** Replace the default Android adaptive icon with a simple, original vector adaptive icon (a play-triangle motif on a solid background) via `res/drawable` vectors + `mipmap-anydpi-v26` adaptive XML + a monochrome layer for themed icons. This is the icon F-Droid displays (pulled from the APK).
3. **fastlane metadata** under `fastlane/metadata/android/en-US/`: `title.txt`, `short_description.txt` (≤80 chars), `full_description.txt`, `changelogs/1.txt`, and `images/phoneScreenshots/` (clean emulator captures) + `images/icon.png`. F-Droid auto-imports these from the repo.
4. **Release signing config** in `app/build.gradle.kts`: a `release` signingConfig that reads a gitignored `keystore.properties` (storeFile/storePassword/keyAlias/keyPassword) when present, else leaves the release unsigned. Add `keystore.properties`, `*.jks`, `*.keystore` (except `debug.keystore`) to `.gitignore`. No secrets committed. (F-Droid signs with its own key; this config serves reproducible local/CI signing, GitHub releases, and a future Play Store build.)
5. **Version.** Bump `versionName` to `1.0.0` (first stable V1 release), keep `versionCode = 1`. Changelog `1.txt` documents the V1 feature set.
6. **`assembleRelease` sanity** — `./gradlew :app:assembleRelease` builds (unsigned if no keystore). Note reproducible-build posture (deterministic Gradle/Kotlin/AGP, pinned versions, no build-time timestamps in code).
7. **`docs/RELEASE.md`** — the FOSS audit summary, the keystore.properties format, reproducible-build notes, and the step-by-step F-Droid submission guide (the user's stop-point action: open a merge request against `fdroiddata` with the metadata yaml — License `GPL-3.0-or-later`, `SourceCode`/`IssueTracker` URLs, a `Builds` entry with `gradle: [yes]` and `subdir: app`). A draft `.fdroid.yml` build recipe is included for reference.

## Decisions (forks resolved, defensible)

- **versionName `1.0.0`** — this is the feature-complete V1 lean MVP; a 1.0.0 first release is conventional. (Surfaced for the user at the stop point.)
- **App display name stays "Video Player"** (matches `app_name`). A more distinctive brand name is a user branding choice — flagged at the stop point, not blocking.
- **Custom icon = simple original play-triangle vector** (not a commissioned design). Replaces the generic default; the user can supply a designed icon later.
- **F-Droid signs with its key**, so shipping a release keystore is unnecessary; the signing config is opt-in via `keystore.properties` for non-F-Droid distribution.

## Constraints preserved

- No app behavior change (headers/metadata/icon/signing only). `:core:*` stays pure (SPDX comments only).
- No new dependencies; the dependency graph remains fully FOSS.
- `assembleDebug` + all unit suites stay green; `assembleRelease` builds.
- No secrets committed (keystore + properties gitignored).

## Stop point (hard)

After all of the above is committed, pushed, and `assembleRelease` verified, **STOP** and report to the user that the app is F-Droid-submission-ready, with the exact remaining outward-facing steps (create/secure a signing key if desired, finalize branding/icon if desired, and open the `fdroiddata` merge request). Do not perform the submission.
