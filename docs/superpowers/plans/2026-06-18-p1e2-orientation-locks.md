# P1.E-2 Implementation Plan — Orientation Lock, Kids Lock & Named Aspect Ratios

Spec: `docs/superpowers/specs/2026-06-18-p1e2-orientation-locks-design.md`
Branch: `feat/p1e2-orientation-locks` (BASE `8f0b28e`)
Build: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"` before every `./gradlew`.

## Task E2-1 — Pure core: orientation + lock constants (TDD)
File: `core/playback/src/main/kotlin/com/videoplayer/core/playback/ScreenControls.kt` (+ `ScreenControlsTest.kt`).
- `enum class OrientationMode { AUTO, PORTRAIT, LANDSCAPE, REVERSE_LANDSCAPE }`.
- `fun nextOrientationMode(current): OrientationMode` — wraps in declared order.
- `const val UNLOCK_HOLD_MS = 3_000L`, `const val LOCK_HINT_VISIBLE_MS = 3_000L`.
- RED→GREEN: cycle through all four + wrap; const values.
- Verify: `./gradlew :core:playback:test`. Commit.

## Task E2-2 — App: named aspect ratios (TDD)
Files: `app/.../player/gestures/AspectMode.kt`, `app/src/test/.../gestures/AspectModeTest.kt`.
- Extend enum with `RATIO_16_9, RATIO_4_3`; cycle Fit→Fill→Zoom→16:9→4:3→Fit.
- Update test to cover the full 5-step cycle.
- Verify: `./gradlew :app:testDebugUnitTest`. Commit.

## Task E2-3 — App: persistence wiring for orientation (TDD)
Files: `ResolvedStartSettings.kt`, `PlaybackMemoryRepository.kt`, `player/PlayerViewModel.kt`,
`app/src/test/.../data/memory/PlaybackMemoryRepositoryTest.kt`.
- `ResolvedStartSettings.orientation: Int?`.
- `resolveStart` reads `saved?.orientation`.
- `persistOrientation(mediaUri, orientation, nowEpochMs)` — preserves position/speed/aspect; existing `persist()` preserves `orientation`.
- `PlayerViewModel.persistOrientation(mediaUri, orientation)`.
- RED→GREEN tests: resolve surfaces saved orientation; persistOrientation round-trip preserves others; persist() preserves orientation.
- Verify: `./gradlew :app:testDebugUnitTest`. Commit.

(E2-1..E2-3 are file-disjoint plumbing; run as one fresh-context subagent, separate commits per task.)

## Task E2-4 — App: UI + Activity integration (build-verified)
Files: `player/Orientation.kt` (new), `player/HardwareKeyGuard.kt` (new), `MainActivity.kt`,
`player/PlayerControls.kt`, `player/PlayerScreen.kt`.
- `Orientation.kt`: enum ↔ `ActivityInfo` Int mapping.
- `HardwareKeyGuard` interface + `MainActivity` implements it + `dispatchKeyEvent` swallows volume keys when blocked.
- `PlayerControls`: add lock 🔒 button (top bar) + orientation cycle button (secondary row); new params/callbacks.
- `PlayerScreen`:
  - orientation state per `item.uri`, init from `resolved.orientation`, apply via `requestedOrientation`, reset to UNSPECIFIED on dispose, persist on change.
  - `locked` state; gate the 4 gesture `pointerInput`s on `locked`; `BackHandler(enabled = locked) {}`; `setHardwareKeysBlocked`.
  - lock overlay: tap → reveal centered "Hold to unlock" icon (auto-hide `LOCK_HINT_VISIBLE_MS`); hold `UNLOCK_HOLD_MS` → unlock, with circular progress.
  - aspect `update` block: ratio modes set `exo_content_frame` aspect + `RESIZE_MODE_FIT`.
- Verify: `./gradlew :app:assembleDebug`. Commit.

## Verification (orchestrator)
- `./gradlew test` green; `./gradlew :app:assembleDebug` OK; `:app:installDebug`.
- Emulator smoke per spec (orientation rotate+persist, lock blocks touch/back/volume + hold-to-unlock, 16:9/4:3 render, no crash).

## Done
Final whole-branch review (opus) → merge to local main → update memory + `.git/sdd/progress.md`.
