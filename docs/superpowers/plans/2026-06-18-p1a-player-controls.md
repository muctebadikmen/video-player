# P1.A — Player Controls & Overlay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development / test-driven-development. Steps use checkbox (`- [ ]`) tracking. Part of Phase 1 of the video player; depends on Phase 0 (tag `phase-0`).

**Goal:** Replace Media3's built-in PlayerView controls with a custom Compose control overlay — seek bar, play/pause, time labels, single-tap show/hide with auto-hide, and double-tap gestures (±10s on sides, play/pause in center) — backed by live `positionMs` from the engine and keyframe (instant) seeking.

**Architecture:** Pure tap/seek interaction logic lives in `:app` under `player/controls/` as plain Kotlin functions (JVM-unit-tested). The Compose overlay (`PlayerControls`) renders from `engine.state` and calls engine methods. The Media3 engine gains a position poller (fixes Phase-0 review item I2) and keyframe seek params. Engine seam unchanged — UI still talks only to `PlaybackEngine` for control/state.

**Tech Stack:** Kotlin, Compose Material3, AndroidX Media3 (ExoPlayer `SeekParameters`), coroutines.

## Global Constraints
- Core stays UI-agnostic; control-interaction logic is app-level pure Kotlin (no Compose import in the tested functions).
- Double-tap default: **±10s on sides, play/pause in center** (user-confirmed). Make the side delta a named constant (`SKIP_MS = 10_000`) and the behavior overridable later — but no settings UI in P1.A (YAGNI).
- Auto-hide delay: 3000 ms after last interaction.
- Seek must be keyframe-instant: ExoPlayer `SeekParameters.CLOSEST_SYNC`.
- Commit after every green step. Build with `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.

---

## Task A1: Engine — live position polling + keyframe seek  [Android-verify]

**Files:**
- Modify: `app/src/main/kotlin/com/videoplayer/app/engine/Media3PlaybackEngine.kt`

**Interfaces:**
- Produces: `Media3PlaybackEngine` now (a) updates `PlaybackState.positionMs` ~4×/sec while playing and immediately on `seekTo`, and (b) builds ExoPlayer with `setSeekParameters(SeekParameters.CLOSEST_SYNC)` so the custom seek bar gets instant seeks.
- Consumes: existing `PlaybackEngine` contract.

- [ ] **Step 1:** Give the engine an internal `CoroutineScope(Dispatchers.Main + SupervisorJob())`. On construction set `player.setSeekParameters(SeekParameters.CLOSEST_SYNC)`.
- [ ] **Step 2:** Add a poller: a coroutine started on `onIsPlayingChanged(true)` / first play that, while `player.isPlaying`, every 250 ms sets `positionMs = player.currentPosition.coerceAtLeast(0)`; cancels when not playing. Also update `positionMs` synchronously in `seekTo` (`_state.update { it.copy(positionMs = positionMs.coerceAtLeast(0)) }`) so the scrubber snaps immediately.
- [ ] **Step 3:** Cancel the scope in `release()`.
- [ ] **Step 4: [Android-verify]** Build + run on emulator: open the sample, confirm `state.positionMs` advances (log it) and a seek snaps. `:app:assembleDebug` green; manual smoke with the custom bar arrives in A3 — here, verify via a temporary log or the existing PlayerView. Acceptable proof: assembleDebug green + position log increasing.
- [ ] **Step 5: Commit** `feat(player): live position polling and keyframe seek in Media3 engine`.

---

## Task A2: Pure tap/seek interaction logic  [TDD]

**Files:**
- Create: `app/src/main/kotlin/com/videoplayer/app/player/controls/TapControls.kt`
- Test: `app/src/test/kotlin/com/videoplayer/app/player/controls/TapControlsTest.kt`

**Interfaces:**
- Produces:
  - `enum class TapZone { LEFT, CENTER, RIGHT }`
  - `fun resolveTapZone(x: Float, width: Float): TapZone` — left third → LEFT, middle third → CENTER, right third → RIGHT; guards width ≤ 0 → CENTER.
  - `enum class DoubleTapAction { SEEK_BACKWARD, PLAY_PAUSE, SEEK_FORWARD }`
  - `fun doubleTapAction(zone: TapZone): DoubleTapAction` — LEFT→SEEK_BACKWARD, CENTER→PLAY_PAUSE, RIGHT→SEEK_FORWARD.
  - `const val SKIP_MS = 10_000L`
  - `fun seekTarget(currentMs: Long, deltaMs: Long, durationMs: Long): Long` — `(currentMs + deltaMs).coerceIn(0, durationMs)`.

- [ ] **Step 1: RED** — write `TapControlsTest` covering: zone boundaries (x=0→LEFT, x=width/2→CENTER, x=width→RIGHT, width=0→CENTER); `doubleTapAction` for all three zones; `seekTarget` forward/backward clamp at 0 and at durationMs. Run `:app:testDebugUnitTest --tests "*TapControlsTest"` → FAIL.
- [ ] **Step 2: GREEN** — implement `TapControls.kt`. Run → PASS.
- [ ] **Step 3: Commit** `feat(player): pure tap-zone and seek-target control logic with tests`.

Test bodies (use verbatim):
```kotlin
@Test fun `left third resolves LEFT`() { assertThat(resolveTapZone(10f, 300f)).isEqualTo(TapZone.LEFT) }
@Test fun `middle resolves CENTER`() { assertThat(resolveTapZone(150f, 300f)).isEqualTo(TapZone.CENTER) }
@Test fun `right third resolves RIGHT`() { assertThat(resolveTapZone(290f, 300f)).isEqualTo(TapZone.RIGHT) }
@Test fun `zero width is CENTER`() { assertThat(resolveTapZone(0f, 0f)).isEqualTo(TapZone.CENTER) }
@Test fun `double tap maps zones to actions`() {
    assertThat(doubleTapAction(TapZone.LEFT)).isEqualTo(DoubleTapAction.SEEK_BACKWARD)
    assertThat(doubleTapAction(TapZone.CENTER)).isEqualTo(DoubleTapAction.PLAY_PAUSE)
    assertThat(doubleTapAction(TapZone.RIGHT)).isEqualTo(DoubleTapAction.SEEK_FORWARD)
}
@Test fun `seek clamps at zero`() { assertThat(seekTarget(3_000, -10_000, 60_000)).isEqualTo(0) }
@Test fun `seek clamps at duration`() { assertThat(seekTarget(58_000, 10_000, 60_000)).isEqualTo(60_000) }
@Test fun `seek adds delta in range`() { assertThat(seekTarget(20_000, 10_000, 60_000)).isEqualTo(30_000) }
```

---

## Task A3: Custom Compose control overlay wired to engine + gestures  [Android-verify]

**Files:**
- Create: `app/src/main/kotlin/com/videoplayer/app/player/PlayerControls.kt`
- Modify: `app/src/main/kotlin/com/videoplayer/app/player/PlayerScreen.kt` (disable PlayerView controller; overlay the controls; collect engine state)

**Interfaces:**
- Consumes: `PlaybackEngine`/`PlaybackState`, `TapControls` (A2), engine position (A1).
- Produces: a `PlayerControls(state, onPlayPause, onSeekTo, onBack, modifier)` composable + the tap/gesture wiring in `PlayerScreen`.

- [ ] **Step 1:** In `PlayerScreen`, set `PlayerView(ctx).apply { useController = false }`. Collect `engine.state` via `collectAsStateWithLifecycle` (engine exposes `state: StateFlow`). 
- [ ] **Step 2:** Build `PlayerControls`: a `Box` overlay with (bottom) a `Slider` bound to `positionMs/durationMs` calling `onSeekTo`, current/total time via `formatDuration`, a centered play/pause `IconButton`. Visible state hoisted; semi-transparent scrim.
- [ ] **Step 3:** Gestures on the player `Box` via `pointerInput`: single tap → toggle controls visibility; double tap → `resolveTapZone` then dispatch `doubleTapAction` (seek uses `seekTarget(state.positionMs, ±SKIP_MS, state.durationMs)` → `engine.seekTo`; play/pause toggles). Auto-hide: `LaunchedEffect(visible, lastInteraction)` → `delay(3000)` → hide (only while playing).
- [ ] **Step 4: [Android-verify]** Emulator: open sample → controls show, seek bar advances (proves A1), drag-seek snaps instantly (proves keyframe), single-tap hides/shows, double-tap right/left skips ±10s, center toggles play/pause, controls auto-hide after 3s. Screenshot evidence.
- [ ] **Step 5: Commit** `feat(player): custom Compose control overlay with tap gestures and seek bar`.

---

## Self-Review
- Master prompt §3 (single tap show/hide + auto-hide, double tap play/pause-or-skip): A3. ✅
- §2 keyframe instant seek: A1 (CLOSEST_SYNC). ✅
- Review item I2 (positionMs): A1. ✅
- Pure logic tested (tap zones, seek math): A2. ✅
- Not in P1.A (later packages): brightness/volume/seek drag gestures + long-press 2× (P1.B), persistence of position (P1.C), speed/aspect (P1.E).
