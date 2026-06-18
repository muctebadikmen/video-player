# P1.B — Gesture System Implementation Plan

> Part of Phase 1. Depends on P1.A (player controls). Uses TDD for pure math; [Android-verify] for Compose/Android integration on emulator.

**Goal:** The signature MX-Player-style gesture feel on the player surface: left-half vertical = brightness, right-half vertical = volume (to 200% via loudness boost), horizontal drag = seek (keyframe), **long-press = 2× speed while held / restore on release (LOCKED V1 must-have)**, pinch = zoom/pan + aspect-ratio cycle — each with a live transient overlay.

**Architecture:** All gesture *decision math* is pure Kotlin in `app/.../player/gestures/GestureMath.kt` (JVM-unit-tested). `PlayerScreen` adds gesture detectors (`pointerInput`) that call into the math and apply effects via Android (`WindowManager` brightness, `AudioManager` + `LoudnessEnhancer` volume/boost, `engine.seekTo`, `engine.setSpeed`) and drive a transient `GestureOverlay`. Engine seam unchanged.

## Global Constraints
- Long-press 2×-while-held is mandatory and must restore the prior speed exactly on release.
- Volume boost ceiling 200% (`1.0` = system max, up to `2.0` via `LoudnessEnhancer`).
- Gestures are individually conceptually toggleable + sensitivity is a named constant (tunable). NO settings UI in P1.B (deferred) — defaults only, documented as tunable.
- Keyframe seek already set on the engine (P1.A). Seek-drag reuses `engine.seekTo`.
- Build with `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`. Commit per green step.

---

## Task B1: Pure gesture math  [TDD]
**Files:** Create `app/src/main/kotlin/com/videoplayer/app/player/gestures/GestureMath.kt`; Test `app/src/test/kotlin/com/videoplayer/app/player/gestures/GestureMathTest.kt`

**Produces:**
- `enum class VerticalSide { BRIGHTNESS, VOLUME }` ; `fun verticalSide(x: Float, width: Float): VerticalSide` — left half→BRIGHTNESS, right half→VOLUME (x ≥ width/2 → VOLUME; width≤0→BRIGHTNESS).
- `fun applyBrightness(current: Float, dragYpx: Float, heightPx: Float): Float` — drag **up** raises: `(current - dragYpx/heightPx).coerceIn(0f, 1f)` (guard height≤0 → current).
- `const val MAX_VOLUME_FACTOR = 2f` ; `fun applyVolumeFactor(current: Float, dragYpx: Float, heightPx: Float): Float` — `(current - dragYpx/heightPx).coerceIn(0f, MAX_VOLUME_FACTOR)`.
- `const val SEEK_MS_PER_WIDTH = 90_000L` ; `fun horizontalSeekDeltaMs(dragXpx: Float, widthPx: Float): Long` — `((dragXpx/widthPx) * SEEK_MS_PER_WIDTH).toLong()` (guard width≤0 → 0).
- `const val BOOST_SPEED = 2f`.

**Steps:** RED test (verticalSide boundaries incl. width 0; brightness up raises & clamps 0..1; volume clamps 0..2; seek delta sign + zero-width) → run FAIL → implement → run PASS → commit `feat(player): pure gesture math (brightness/volume/seek) with tests`.

Test bodies (verbatim):
```kotlin
@Test fun `left half is brightness`() { assertThat(verticalSide(10f, 100f)).isEqualTo(VerticalSide.BRIGHTNESS) }
@Test fun `right half is volume`() { assertThat(verticalSide(80f, 100f)).isEqualTo(VerticalSide.VOLUME) }
@Test fun `zero width defaults brightness`() { assertThat(verticalSide(0f, 0f)).isEqualTo(VerticalSide.BRIGHTNESS) }
@Test fun `drag up raises brightness`() { assertThat(applyBrightness(0.5f, -50f, 100f)).isWithin(1e-4f).of(1.0f) }
@Test fun `brightness clamps at 1`() { assertThat(applyBrightness(0.9f, -100f, 100f)).isEqualTo(1f) }
@Test fun `brightness clamps at 0`() { assertThat(applyBrightness(0.1f, 100f, 100f)).isEqualTo(0f) }
@Test fun `volume can exceed 1 up to 2`() { assertThat(applyVolumeFactor(1f, -100f, 100f)).isEqualTo(2f) }
@Test fun `volume clamps at 0`() { assertThat(applyVolumeFactor(0.1f, 100f, 100f)).isEqualTo(0f) }
@Test fun `seek delta positive for rightward drag`() { assertThat(horizontalSeekDeltaMs(50f, 100f)).isEqualTo(45_000L) }
@Test fun `seek delta negative for leftward drag`() { assertThat(horizontalSeekDeltaMs(-50f, 100f)).isEqualTo(-45_000L) }
@Test fun `seek zero width is zero`() { assertThat(horizontalSeekDeltaMs(50f, 0f)).isEqualTo(0L) }
```

---

## Task B2: Vertical brightness + volume gestures + overlay  [Android-verify]
**Files:** Create `app/.../player/GestureOverlay.kt`; Create `app/.../player/VolumeController.kt` (AudioManager + LoudnessEnhancer wrapper); Modify `PlayerScreen.kt`.

- `GestureOverlay(kind, value, modifier)`: a transient centered chip showing an icon + % (brightness sun, volume) — pure Compose from inputs.
- `VolumeController(context, audioSessionId?)`: `setFactor(f: Float)` — 0..1 maps to `AudioManager` STREAM_MUSIC 0..max; 1..2 keeps stream at max and applies `LoudnessEnhancer` gain (0..~+1000 mB). Expose current factor. (Engine must surface its ExoPlayer `audioSessionId` for the enhancer — add `val audioSessionId: Int` to `Media3PlaybackEngine`.)
- `PlayerScreen`: add a `pointerInput` with `detectVerticalDragGestures`; on drag, pick `verticalSide(startX, width)`, compute new brightness via `applyBrightness` → set `window.attributes.screenBrightness`; or new volume via `applyVolumeFactor` → `VolumeController.setFactor`; show overlay; hide ~700ms after drag end.
- **Verify:** emulator — left-drag changes brightness overlay; right-drag changes volume overlay past 100%. Commit `feat(player): brightness and volume (to 200%) vertical gestures with overlay`.

---

## Task B3: Horizontal seek-drag gesture + overlay  [Android-verify]
**Files:** Modify `PlayerScreen.kt`; reuse `GestureOverlay`.

- Add `detectHorizontalDragGestures`: accumulate dragX; `target = seekTarget(startPosition, horizontalSeekDeltaMs(totalDragX, width), durationMs)`; show overlay "«/» mm:ss (±delta)"; on end `engine.seekTo(target)`.
- **Verify:** emulator — horizontal drag scrubs, overlay shows target, release seeks. Commit `feat(player): horizontal drag-to-seek gesture with overlay`.

---

## Task B4: Long-press 2× speed (hold/release)  [Android-verify] — LOCKED
**Files:** Modify `PlayerScreen.kt`; reuse `GestureOverlay`.

- Custom detector via `awaitPointerEventScope`: on `awaitFirstDown`, `withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation() }`; if it times out → long-press began: remember `prevSpeed = engine.state.value.speed`, `engine.setSpeed(BOOST_SPEED)`, show "2×" overlay; then `waitForUpOrCancellation()` → on release `engine.setSpeed(prevSpeed)`, hide overlay.
- Must coexist with tap/double-tap and drags (separate `pointerInput` keys; long-press only fires when no drag).
- **Verify:** emulator — press-and-hold speeds to 2× with overlay; release restores 1×. Commit `feat(player): long-press to 2x speed while held (restores on release)`.

---

## Task B5: Pinch zoom/pan + aspect-ratio cycle  [Android-verify]
**Files:** Modify `PlayerScreen.kt`; add `fun nextResizeMode(current: Int): Int` pure helper + test (cycles PlayerView AspectRatioFrameLayout RESIZE_MODE_FIT → ZOOM → FILL → FIT).

- `detectTransformGestures` for pinch → scale (clamp 1f..3f) + pan offset applied to the `PlayerView` via `scaleX/scaleY/translation` or graphicsLayer on the AndroidView; double-finger or a control toggles `resizeMode`.
- **Verify:** emulator — pinch zooms/pans; aspect toggle changes fit. Commit `feat(player): pinch zoom/pan and aspect-ratio cycle`.
- (If pinch proves heavy, ship aspect-ratio cycle first and note pinch-pan as a follow-up — don't block B1–B4.)

## Self-Review
- §3 brightness/volume(200%)/seek/long-press-2×/pinch: B2/B3/B4/B5. Long-press LOCKED in B4. ✅
- Live overlays per gesture: GestureOverlay (B2) reused. ✅
- Sensitivity tunable (constants), per-gesture toggles + settings UI: deferred (documented). 
- Deferred to later: gesture enable/disable settings screen, fine sensitivity sliders (P1.H/settings).
