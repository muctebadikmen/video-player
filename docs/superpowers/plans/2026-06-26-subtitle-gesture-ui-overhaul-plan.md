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
| 1 | Turkish subtitle **encoding** | `core/playback/SubtitleCharset.kt` (new), `app/subtitle/SubtitleLoader.kt` | `SubtitleCharsetTest` (JVM) | ☐ |
| 2 | Fast **unlock** (3s→0.7s) | `core/playback/ScreenControls.kt` | `ScreenControlsTest` | ☐ |
| 3 | **Typography** + search polish | `theme/Type.kt`, `library/LibraryScreen.kt` | build + emulator | ☐ |
| 4 | **Gesture lockout** (hold-to-speed) | `player/PlayerScreen.kt`, `player/gestures/GestureMath.kt` | `GestureMathTest` + emulator | ☐ |
| 5 | **Subtitle sync sheet** | `player/SubtitleSyncSheet.kt` (new), `PlayerControls.kt`, `PlayerScreen.kt` | build + emulator | ☐ |
| 6 | **Settings reorg + Player options sheet** | `settings/SettingsViewModel.kt`, `settings/SettingsScreen.kt`, `PlayerControls.kt`, `PlayerScreen.kt` | build + emulator | ☐ |

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
