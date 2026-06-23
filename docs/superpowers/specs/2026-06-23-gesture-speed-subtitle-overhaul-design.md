# Design: Hold-to-Speed Overhaul + Subtitle Styling/Move/Resize

**Date:** 2026-06-23
**Branch:** `feat/gesture-speed-subtitle-overhaul`
**Status:** Approved (decisions locked via brainstorming)

## 1. Problem / Goals

Three user-requested improvements to the player:

1. **Hold-to-speed feedback + configurability.** Today a long-press boosts playback to a
   hardcoded `2×` and shows a large centered "2×" overlay. The user wants:
   - The big centered overlay gone, replaced by a **subtle top-center badge** that does
     not interrupt the viewing experience.
   - **One-finger hold** speed and **two-finger hold** speed, each **configurable in
     Settings** (defaults: 1-finger `2×`, 2-finger `3×`).
2. **Subtitle styling.** Today external/sibling subs render white text on a 60% black box
   (`CueOverlay`), and embedded subs ride Media3's default `SubtitleView`. The user wants
   readable, research-backed subtitle styling — default **white text + black outline** —
   with a **picker** in Settings: `Outline` (default), `Drop Shadow`, `Background Box`,
   `Follow system captions`, plus a **size** control.
3. **Subtitle move/resize.** The user wants to **drag** the subtitle up/down and
   **resize** it, *and* to set size/position from Settings sliders.

Non-goals: per-video subtitle style (global only); bitmap subtitle (PGS/VOBSUB) restyling
(handled natively by `SubtitleView`); changing the existing seek/brightness/volume gesture
overlays.

## 2. Current code (verified)

- **Hold gesture + 2× overlay:** `app/.../player/PlayerScreen.kt:577-590` (long-press via
  `awaitEachGesture`/`awaitLongPressOrCancellation`, single pointer only) and
  `PlayerScreen.kt:771` (`if (speedBoostActive) GestureOverlay(label = "${BOOST_SPEED.toInt()}×")`).
- **Boost constant:** `app/.../player/gestures/GestureMath.kt:11` (`BOOST_SPEED = 2f`).
- **Speed apply:** `app/.../engine/Media3PlaybackEngine.kt:291` (`setSpeed`).
- **External subs:** `app/.../player/subtitle/CueOverlay.kt` (hardcoded white-on-black box);
  rendered at `PlayerScreen.kt:774-782` anchored BottomCenter, `bottom = 72.dp`.
- **Embedded subs:** Media3 `PlayerView` created at `PlayerScreen.kt:592-632`;
  `view.subtitleView` is reachable for `setStyle`/`setFractionalTextSize`/`setBottomPaddingFraction`.
- **Settings store:** `app/.../data/memory/SettingsRepository.kt` (DataStore-backed,
  implements the `GridSizePreferences` seam). Seam fakes used in tests
  (`FakeGridSizePreferences`).
- **Settings UI:** `app/.../settings/SettingsScreen.kt` + `SettingsViewModel.kt`
  (background-playback toggle + OpenSubtitles login today).

## 3. Design

### 3.1 Preferences (one source of truth)

Extend `SettingsRepository` with five new keys and expose them via two new seam interfaces
(mirroring `GridSizePreferences`) so ViewModels/screens stay testable with fakes:

```kotlin
interface PlaybackGesturePreferences {
    val holdSpeedOneFinger: Flow<Float>   // default 2.0
    val holdSpeedTwoFinger: Flow<Float>   // default 3.0
    suspend fun setHoldSpeedOneFinger(speed: Float)
    suspend fun setHoldSpeedTwoFinger(speed: Float)
}

interface SubtitlePreferences {
    val subtitleStyle: Flow<SubtitleStyle>          // default OUTLINE
    val subtitleSizeFraction: Flow<Float>           // default 0.0533, range 0.04..0.10
    val subtitleBottomPaddingFraction: Flow<Float>  // default 0.08, range 0.02..0.50
    suspend fun setSubtitleStyle(style: SubtitleStyle)
    suspend fun setSubtitleSizeFraction(f: Float)
    suspend fun setSubtitleBottomPaddingFraction(f: Float)
}
```

`SettingsRepository` implements both (DataStore keys:
`hold_speed_one_finger` float, `hold_speed_two_finger` float, `subtitle_style` string,
`subtitle_size_fraction` float, `subtitle_bottom_padding_fraction` float). Setters clamp via
the pure helpers in 3.4 before writing. `subtitle_style` persists as the enum `name`;
unknown/missing → `OUTLINE`.

### 3.2 Hold-to-speed gesture (one vs two finger) + top badge

Replace the long-press block at `PlayerScreen.kt:577-590`:

- Read `holdSpeedOneFinger` / `holdSpeedTwoFinger` as state, captured with
  `rememberUpdatedState` so the gesture lambda sees current values without restarting
  `pointerInput`.
- After `awaitLongPressOrCancellation` fires, enter boost mode, then loop on
  `awaitPointerEvent()` counting currently-pressed pointers. Apply
  `boostSpeedForPointers(pressedCount, one, two)` whenever the count changes; exit when
  pressed count reaches 0; restore `previousSpeed`.
- State: `speedBoostActive: Boolean` (kept) + `speedBoostLabel: String` (new) so the badge
  shows the *active* speed (`2×`, `2.5×`, `3×`, …).

New composable `SpeedBadge(label)` anchored **top-center** (`Alignment.TopCenter`,
`statusBarsPadding()` + small top padding), a small low-opacity pill with compact text —
deliberately less prominent than `GestureOverlay`. Render at `PlayerScreen.kt:769-772`:
- Remove the speed branch from the centered `GestureOverlay`.
- Keep `gestureLabel?.let { GestureOverlay(...) }` for seek/brightness/volume (unchanged).
- Add `if (speedBoostActive) SpeedBadge(label = speedBoostLabel)`.

`waitForUpOrCancellation` import may be dropped if no longer used; remove only if *our*
change orphans it.

### 3.3 Subtitle styling — shared model, both render paths

New enum (app module, pure Kotlin, JVM-testable):

```kotlin
enum class SubtitleStyle { OUTLINE, DROP_SHADOW, BACKGROUND_BOX, SYSTEM }
```

A pure mapping `subtitleStyleSpec(style): SubtitleStyleSpec` returns app-agnostic data
(text color, edge kind, edge color, background color+alpha, weight) used by both paths.
Concrete values from research:

| Style          | Text  | Edge / extra                      | Background        |
|----------------|-------|-----------------------------------|-------------------|
| OUTLINE (def.) | White | Outline, black, ~10% of font size | Transparent       |
| DROP_SHADOW    | White | Drop shadow, black                | Transparent       |
| BACKGROUND_BOX | White | None                              | Black @ ~65% alpha|
| SYSTEM         | —     | from `CaptioningManager`          | from system       |

Size + position are **fractions of the player height**, identical for both paths so they
match visually: text size = `sizeFraction × heightPx`, bottom inset = `bottomPaddingFraction × heightPx`.

- **Embedded (`SubtitleView`):** in the `AndroidView` `update` block, read style/size/position
  state and apply to `view.subtitleView`:
  - `SYSTEM` → `setApplyEmbeddedStyles(true)`, `setUserDefaultStyle()`, `setUserDefaultTextSize()`.
  - else → `setApplyEmbeddedStyles(false)`, `setStyle(captionStyleCompatFor(style))`,
    `setFractionalTextSize(sizeFraction)`.
  - always → `setBottomPaddingFraction(bottomPaddingFraction)`.
  `captionStyleCompatFor` maps `SubtitleStyleSpec` → `CaptionStyleCompat` with
  `EDGE_TYPE_OUTLINE` / `EDGE_TYPE_DROP_SHADOW` / `EDGE_TYPE_NONE`.
- **External/sibling (`CueOverlay`):** re-implement to take `style`, `sizeFraction`,
  `bottomPaddingFraction`. Measure height (`BoxWithConstraints`). Render:
  - OUTLINE → stacked text: black `Text` with `TextStyle(drawStyle = Stroke(width))`
    behind a white fill `Text` (same text/size/align/position).
  - DROP_SHADOW → white `Text` with `TextStyle(shadow = Shadow(black, offset, blur))`.
  - BACKGROUND_BOX → current behavior (white text on ~65% black rounded box).
  - SYSTEM → render like OUTLINE (custom external cues can't read system caption style
    cheaply; OUTLINE is the safe, readable equivalent — documented limitation).
  Font size in `sp` derived from `sizeFraction × heightPx` (converted px→sp); bottom inset
  from `bottomPaddingFraction × heightPx`.

### 3.4 Subtitle move/resize (drag + sliders)

Source of truth = the two fraction prefs. PlayerScreen holds local `mutableState` for
`subtitleSizeFraction` / `subtitleBottomPaddingFraction`, seeded from the prefs flows; both
renderers read these. Updated two ways:

1. **Settings sliders** (guaranteed path) — see 3.5. Writes prefs directly.
2. **On-screen drag/pinch** (active only when `controlsVisible`, so it never clashes with
   the hold-to-speed gesture on the bare surface): a bottom-band Compose layer with
   `pointerInput` that **consumes** events —
   - vertical drag → `applySubtitleBottomPadding(current, dragDy, heightPx)` (drag up = move
     up),
   - pinch (two-finger zoom) → `applySubtitleSize(current, zoom)`.
   Updates the local state live; **persists to prefs on gesture end**. Clamp with the pure
   helpers below. Sliders remain the robust fallback if drag proves fiddly in testing.

Pure, JVM-tested helpers (in `GestureMath.kt` or a new `SubtitleGestureMath.kt`):
`boostSpeedForPointers(count, one, two)`, `formatSpeedLabel(speed)`, `clampHoldSpeed(s)` (→
1.0..4.0), `clampSubtitleSize(f)` (→ 0.04..0.10), `clampSubtitleBottomPadding(f)` (→
0.02..0.50), `applySubtitleBottomPadding(...)`, `applySubtitleSize(...)`.

### 3.5 Settings UI

Add two sections to `SettingsScreen` (and the backing flows/setters to `SettingsViewModel`):

- **Playback gestures**
  - "Hold to speed up (1 finger)" — slider 1.0–4.0×, 0.1 step, shows current value.
  - "Hold to speed up (2 fingers)" — slider 1.0–4.0×, 0.1 step.
- **Subtitles**
  - "Style" — single-choice (Outline / Drop Shadow / Background Box / Follow system).
  - "Size" — slider mapping to `sizeFraction` 0.04–0.10.
  - "Position" — slider mapping to `bottomPaddingFraction` 0.02–0.50 (+ a Reset).
  - **Live preview**: a small sample box rendering "Sample subtitle" with the current style
    + size so the choice is visible immediately.

Use a reusable `SettingSliderRow` composable alongside the existing `SettingSwitchRow`.

## 4. Testing

- **JVM unit tests (TDD):** `boostSpeedForPointers`, `formatSpeedLabel`, `clampHoldSpeed`,
  `clampSubtitleSize`, `clampSubtitleBottomPadding`, `applySubtitleBottomPadding`,
  `applySubtitleSize`, and `subtitleStyleSpec(style)` mapping.
- **Settings round-trip:** extend the existing in-memory-DataStore test pattern (or a
  `SettingsViewModel` test) for the five new prefs (default + set/read).
- **Seam fakes:** `FakePlaybackGesturePreferences`, `FakeSubtitlePreferences` for any VM
  tests.
- **Manual / emulator (AVD `kuran_test`):** 1-finger hold → default 2× + top badge; add 2nd
  finger → 3× live; release → restore; no centered "2×". Each subtitle style renders;
  size/position sliders move both embedded & external subs; drag/pinch moves/resizes when
  controls visible; values persist across reopen.
- **Gates:** `./gradlew test` green; `./gradlew :app:assembleDebug` builds.

## 5. Files touched (estimate)

- `GestureMath.kt` (+ maybe `SubtitleGestureMath.kt`) — pure helpers.
- `SubtitleStyle.kt` + `SubtitleStyleSpec.kt` (new) — style model + mapping.
- `SettingsRepository.kt` — 5 prefs + 2 seam interfaces; `GridSizePreferences.kt` sibling.
- `CueOverlay.kt` — style/size/position aware rendering.
- `PlayerScreen.kt` — gesture rewrite, `SpeedBadge`, embedded `SubtitleView` styling,
  subtitle drag layer, wiring new prefs.
- `SpeedBadge.kt` (new) — top-center badge.
- `SettingsScreen.kt` + `SettingsViewModel.kt` — new sections, slider row, preview.
- Tests under `app/src/test/...`.

## 6. Risks

- **Long-press + multi-pointer interplay** in Compose (`awaitLongPressOrCancellation` then
  manual pointer counting) — verify on emulator; the pure speed-selection logic is unit
  tested regardless.
- **Drag vs existing gestures** — mitigated by enabling the subtitle drag layer only when
  controls are visible and having it consume events; sliders are the guaranteed fallback.
- **Embedded vs external visual parity** — both driven by the same fractions; SYSTEM style
  for external cues falls back to OUTLINE (documented).
