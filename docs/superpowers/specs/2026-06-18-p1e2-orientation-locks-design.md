# P1.E-2 — Orientation Lock, Kids Lock & Named Aspect Ratios (Design)

Date: 2026-06-18
Status: Approved
Depends on: P1.E-1 (playback controls), P1.C (smart memory / reserved `orientation` column)

## Goal

Add three player capabilities, keeping `:core:*` pure (no `android.*`) and adding no new dependencies:

1. **Per-file orientation lock** — Auto / Portrait / Landscape / Reverse-landscape, saved per file and re-applied on open.
2. **Kids Lock** — a single lock that blocks all touch **and** volume/back hardware keys; unlocked by holding a lock icon for 3 seconds.
3. **Named aspect ratios** — extend the existing Fit/Fill/Zoom cycle with fixed 16:9 and 4:3 frames.

## User-confirmed decisions

- One lock control only (it *is* the Kids Lock — no separate "quick" touch lock).
- Unlock gesture: **press-and-hold the lock icon for 3s** (with a visible progress affordance); releasing early cancels.
- Orientation modes: **Auto / Portrait / Landscape / Reverse-landscape** (cycle button).
- Include named aspect ratios (16:9 + 4:3) in this phase.

## Architecture

### Pure core (`:core:playback`, TDD) — new `ScreenControls.kt`
- `enum class OrientationMode { AUTO, PORTRAIT, LANDSCAPE, REVERSE_LANDSCAPE }`
- `fun nextOrientationMode(current: OrientationMode): OrientationMode` — cycles in declared order, wrapping.
- `const val UNLOCK_HOLD_MS = 3_000L` — single source of truth for the hold-to-unlock duration.
- `const val LOCK_HINT_VISIBLE_MS = 3_000L` — how long the "hold to unlock" affordance stays after a tap.
- No `android.*` imports. Mapping enum ↔ `ActivityInfo` Int lives in `:app`.

### App — aspect ratios (`player/gestures/AspectMode.kt`)
- Extend enum: `FIT, FILL, ZOOM, RATIO_16_9, RATIO_4_3`.
- `nextAspectMode`: Fit→Fill→Zoom→16:9→4:3→Fit. Update `AspectModeTest.kt`.
- In `PlayerScreen`'s `AndroidView.update`: Fit/Fill/Zoom map to existing `RESIZE_MODE_*`; ratio modes set the PlayerView content frame's aspect ratio (`exo_content_frame`, an `AspectRatioFrameLayout`) via `setAspectRatio(16f/9f | 4f/3f)` + `RESIZE_MODE_FIT` (fixed-ratio frame, letterboxed). `update` re-runs on recomposition so the forced ratio survives PlayerView's occasional video-size callbacks.
- Persisted aspect already round-trips through P1.C (stored by enum name); new names persist transparently.

### App — orientation mapping (`player/Orientation.kt`, new)
- `OrientationMode.toActivityInfo(): Int` and `orientationModeFromActivityInfo(Int?): OrientationMode`, mapping to
  `ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED / _PORTRAIT / _LANDSCAPE / _REVERSE_LANDSCAPE`.
- The DB stores the `ActivityInfo` Int (matches the entity comment "ActivityInfo.screenOrientation int"); `AUTO` ⇒ `UNSPECIFIED`.

### App — persistence wiring
- `ResolvedStartSettings` gains `orientation: Int?` (null ⇒ no per-file override / Auto).
- `PlaybackMemoryRepository.resolveStart` reads `saved?.orientation` into the resolved settings.
- New `PlaybackMemoryRepository.persistOrientation(mediaUri, orientation, nowEpochMs)` — loads the base row (creating a default if absent), copies with the new `orientation`, upserts. Separate write path so it never clobbers position/speed/aspect. Existing `persist()` already preserves the `orientation` column (it isn't in its `copy`).
- `PlayerViewModel.persistOrientation(mediaUri, orientation)` delegates with `System.currentTimeMillis()`.

### App — hardware-key guard (`player/HardwareKeyGuard.kt`, new) + `MainActivity`
- `interface HardwareKeyGuard { fun setHardwareKeysBlocked(blocked: Boolean) }`.
- `MainActivity` implements it with a `@Volatile var` flag and overrides `dispatchKeyEvent`: when blocked, consume (return true) for `KEYCODE_VOLUME_UP/VOLUME_DOWN/VOLUME_MUTE` (both down/up). All other keys pass through.
- Back key is handled in Compose (see below), not here.

### App — `PlayerScreen` integration
- **Orientation:** local `orientationMode` state (per `item.uri`); initialized from `resolved.orientation` when it arrives (own `LaunchedEffect(resolved)`), applied via `activity.requestedOrientation`. The cycle button updates the state, applies, and persists. On dispose, reset `activity.requestedOrientation = UNSPECIFIED` so the library isn't left rotated.
- **Lock:** `var locked` (transient). Locking hides controls and engages the lock. While locked:
  - The four gesture `pointerInput` layers are keyed on `locked` and early-return (inert).
  - `BackHandler(enabled = locked) {}` consumes back to a no-op (it sits in front of the existing `BackHandler(onBack)`); when unlocked the existing back behavior applies.
  - `setHardwareKeysBlocked(true)` is set on lock and cleared on unlock and on dispose.
  - A top overlay consumes touches: a single tap reveals a centered "Hold to unlock" lock icon (auto-hides after `LOCK_HINT_VISIBLE_MS`); pressing and holding it for `UNLOCK_HOLD_MS` unlocks, with a circular progress indicator that fills over the hold and resets if released early.
- **Controls:** the lock 🔒 button (top bar) and the orientation cycle button (secondary row) are added to `PlayerControls`.

## Module boundary

`:core:playback` gains only an enum + a pure function + two consts — no Android types. All Android (`ActivityInfo`, `PlayerView`/`AspectRatioFrameLayout`, key dispatch, `requestedOrientation`) stays in `:app`. No new Gradle dependencies; `Icons.Filled.Lock` is in material-icons-core (already on the classpath via material3), with an emoji fallback if unavailable.

## Testing

- **Pure (TDD):** `ScreenControlsTest` — orientation cycle wraps through all four modes; consts have expected values.
- **App unit (TDD):** `AspectModeTest` — new 5-step cycle; `PlaybackMemoryRepositoryTest` — `resolveStart` surfaces a saved orientation, `persistOrientation` preserves position/speed/aspect, `persist()` preserves a previously saved orientation.
- **Build:** `./gradlew test` green; `./gradlew :app:assembleDebug` succeeds.
- **Emulator smoke (orchestrator):**
  1. Orientation — cycle Auto→Portrait→Landscape→Reverse rotates the activity; reopen the file and the saved orientation re-applies.
  2. Kids Lock — tap lock; gestures/controls do nothing; `input keyevent BACK` does not exit; volume keys swallowed; tap shows the unlock icon; holding 3s unlocks.
  3. Aspect — cycle reaches 16:9 and 4:3 and the frame visibly changes.
  4. No crash; existing playback/resume/controls unaffected.

## Out of scope

Folder-level orientation defaults; landscape-aware control layout polish; configurable unlock duration. (Deferred to later phases.)
