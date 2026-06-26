# v1.9.0 Overhaul — Execution Ledger

Spec: `docs/superpowers/specs/2026-06-26-subtitle-gesture-ui-overhaul-design.md`
Branch: `feat/subtitle-gesture-ui-overhaul`
Detailed per-workstream fix designs (code-quoted, line-exact): captured from the
verification workflow; each subagent is handed its own.

Execution is **sequential subagents** (player files are shared). Each: implement
→ test/build green → commit. Order chosen to avoid collisions and let later
player-file refactors build on earlier ones.

| # | Workstream | Key files | Test surface | Status |
|---|---|---|---|---|
| 1 | Turkish subtitle **encoding** | `core/playback/SubtitleCharset.kt` (new), `app/subtitle/SubtitleLoader.kt` | `SubtitleCharsetTest` (JVM) | ✅ `f5778f6` — 8 tests; on-device render "Çocuğun ışığı söndü; şık güneş, öğün." correct |
| 2 | Fast **unlock** (3s→0.7s) | `core/playback/ScreenControls.kt` | `ScreenControlsTest` | ✅ `b98313f` |
| 3 | **Typography** + search polish | `theme/Type.kt`, `library/LibraryScreen.kt` | build + emulator | ✅ `2c92744` — verified library/settings/player |
| 4 | **Gesture lockout** (hold-to-speed) | `player/PlayerScreen.kt`, `player/gestures/GestureMath.kt` | `GestureMathTest` + emulator | ✅ `eb35d87` — predicate tested + guards |
| 5 | **Subtitle sync sheet** | `player/SubtitleSyncSheet.kt` (new), `PlayerControls.kt`, `PlayerScreen.kt` | build + emulator | ✅ `25a8a24` — sheet readout +0→+550ms verified |
| 6 | **Settings reorg + Player options sheet** | `settings/SettingsViewModel.kt`, `settings/SettingsScreen.kt`, `PlayerControls.kt`, `PlayerScreen.kt` | build + emulator | ✅ `d3cf8b4` — all sections + options sheet verified |

**Status: all six implemented, green, and verified on `kuran_test` emulator.**

## Adversarial review (6 reviewers) + fixes
Gesture lockout, cleanliness, and release build came back clean. Findings fixed in `7488fc1` + `bc74374`:
- MAJOR: Player options sheet hoisted to top-level so the 3s control auto-hide no longer dismisses it mid-interaction (verified on-device: sheet survives 5s idle). Matches the sync sheet.
- MAJOR: grid-columns slider constrained to 2–4 (GridSize supports 2/3/4 only) and made live-reactive in `LibraryViewModel` (verified on-device: grid changes columns live).
- MINOR: charset terminal fallback uses ISO-8859-9 when windows-1254 would emit U+FFFD → never mojibake.
- NIT: `formatSpeedLabel` quarter presets + **`Locale.ROOT`** for all float formatting (Turkish devices were rendering `1,000×`/`2,00×` with a comma) in the sync sheet rate and OpenSubtitles rating; double `substringBefore` and redundant `ProgressDots` box cleaned up.

## Release
v1.9.0 / versionCode 11; full `./gradlew test` green; signed `assembleRelease` (CN=Mustafa Dikmen) verified; merged to main, tagged, GitHub release published.

## Reconciliations / decisions baked in
- **Subtitle sync** = its own dedicated `SubtitleSyncSheet` (WS5's options sheet excludes sync).
- **Encoding**: fix the single decode point in `SubtitleLoader`; `SubtitleDownloader` keeps raw bytes (correct). `.ass/.ssa` → graceful empty + optional `Log.w`.
- **Unlock**: only `UNLOCK_HOLD_MS` changes (700L); `LOCK_HINT_VISIBLE_MS` stays 3000.
- **Gesture lockout**: guard `onVerticalDrag`/`onHorizontalDrag`/`onDragEnd` on `speedBoostActive`; consume in the boost loop; `&& !speedBoostActive` on the two-finger subtitle pinch.
- **Typography**: metric-preserving scale (only weights/letter-spacing change) → near-zero layout risk. Player `Color.White` stays (over video).
- **Settings**: surface persisted-but-hidden resume/default-speed/grid-columns; no theme/AMOLED key invented (would need new repo plumbing — out of scope).

## After all six
- Consolidated `./gradlew test` + `:app:assembleDebug`; install on `kuran_test`.
- On-device verification of each fix (Turkish subtitle render, sync sheet, gesture lockout, fast unlock, settings, options sheet).
- Adversarial multi-dimension review workflow.
- Version → 1.9.0 / versionCode 11; signed release; push; GitHub release + download URL.
