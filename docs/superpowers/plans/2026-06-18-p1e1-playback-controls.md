# P1.E-1 — Playback Controls (speed, frame-step, A–B repeat, sleep timer, auto-advance) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the signature playback controls — pitch-corrected speed (0.25–4×), frame-by-frame step, A–B repeat, a sleep timer (duration or end-of-video), and auto-advance to the next file in the folder — building on the existing custom control overlay and the P1.C persistence.

**Architecture:** All new logic that can be pure lives in `:core:playback` (A–B loop target, sleep-timer math, speed presets/clamp, frame-step delta) and is JVM-unit-tested. The Media3 engine needs **no changes** — speed is already pitch-corrected (`ExoPlayer.setPlaybackSpeed` → Sonic time-stretch), and frame-step / A–B / sleep are composed in `PlayerScreen` from existing `pause`/`seekTo`/`setSpeed` plus the live `positionMs` poller. Auto-advance plumbs the current folder's item list from `VideoPlayerApp` into `PlayerScreen`, which on `ENDED` resolves `nextInFolder` (from P1.D) and bubbles a swap up.

**Tech Stack:** Kotlin 2.0.21 · Jetpack Compose (Material3) · AndroidX Media3 (existing) · JUnit4 + Truth (tests). No new dependencies.

## Global Constraints

- **Platform:** Native Android, Kotlin + Compose only.
- **Core is UI-agnostic:** `:core:playback` MUST stay free of `android.*`/`androidx.*`/`media3`. New pure logic goes there.
- **No new dependencies.** No new permissions (sleep timer uses an in-app coroutine, not AlarmManager).
- **Speed is pitch-corrected already:** `Media3PlaybackEngine.setSpeed` calls `player.setPlaybackSpeed`, which sets `PlaybackParameters(speed, pitch=1.0)`; ExoPlayer's Sonic audio processor time-stretches preserving pitch. Do NOT change pitch. Persisted speed (P1.C) now becomes user-meaningful and auto-resumes via the existing resume-apply effect.
- **Auto-advance default = ON** (user-confirmed). No settings UI yet; a `const val AUTO_ADVANCE_DEFAULT = true` with a documented "make it a setting in P1.H" note.
- **Reuse existing:** `seekTarget(startMs, deltaMs, durationMs)` (controls), `nextInFolder(items, currentUri)` (`:core:model`), `PlayerStatus.ENDED`, `PlaybackState`, the live `state.positionMs` poller.
- **Commit after every green step.** Conventional commits.
- **TDD:** pure logic (`:core:playback`) = red→green unit tests. Overlay + enforcement + nav = `[Android-verify]` (assembleDebug + emulator).

---

## File Structure

**Created:**
- `core/playback/src/main/kotlin/com/videoplayer/core/playback/PlaybackControls.kt` — `AbLoop`, `abLoopTarget`, `SPEED_PRESETS`, `MIN_SPEED`/`MAX_SPEED`/`clampSpeed`, `FRAME_STEP_MS`, `sleepRemainingMs`, `isSleepExpired`.
- `core/playback/src/test/kotlin/com/videoplayer/core/playback/PlaybackControlsTest.kt`

**Modified:**
- `app/src/main/kotlin/com/videoplayer/app/player/PlayerControls.kt` — add a secondary control row: speed picker, A–B button, sleep button, and frame-step buttons; surface current speed / A–B / sleep state.
- `app/src/main/kotlin/com/videoplayer/app/player/PlayerScreen.kt` — hold A–B + sleep + speed state; wire the new control callbacks; enforce A–B loop and sleep via effects; handle auto-advance on `ENDED`.
- `app/src/main/kotlin/com/videoplayer/app/VideoPlayerApp.kt` — track the selected item's folder playlist and pass it to `PlayerScreen`; advance swaps the selected item.

---

## Task E1: Pure playback-control logic (`:core:playback`) [TDD]

**Files:**
- Create: `core/playback/src/main/kotlin/com/videoplayer/core/playback/PlaybackControls.kt`
- Test: `core/playback/src/test/kotlin/com/videoplayer/core/playback/PlaybackControlsTest.kt`

**Interfaces:**
- Produces:
  - `data class AbLoop(val startMs: Long? = null, val endMs: Long? = null) { val isComplete: Boolean }`
  - `fun abLoopTarget(positionMs: Long, loop: AbLoop): Long?`
  - `val SPEED_PRESETS: List<Float>`
  - `const val MIN_SPEED = 0.25f`, `const val MAX_SPEED = 4f`
  - `fun clampSpeed(speed: Float): Float`
  - `const val FRAME_STEP_MS = 40L`
  - `fun sleepRemainingMs(deadlineEpochMs: Long, nowEpochMs: Long): Long`
  - `fun isSleepExpired(deadlineEpochMs: Long, nowEpochMs: Long): Boolean`

- [ ] **Step 1: Write the failing tests**

`core/playback/src/test/kotlin/com/videoplayer/core/playback/PlaybackControlsTest.kt`:
```kotlin
package com.videoplayer.core.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackControlsTest {
    @Test fun `abLoopTarget null when loop incomplete`() {
        assertThat(abLoopTarget(50_000, AbLoop(startMs = 10_000, endMs = null))).isNull()
        assertThat(abLoopTarget(50_000, AbLoop())).isNull()
    }
    @Test fun `abLoopTarget null before end point`() {
        assertThat(abLoopTarget(15_000, AbLoop(10_000, 20_000))).isNull()
    }
    @Test fun `abLoopTarget returns start at or after end point`() {
        assertThat(abLoopTarget(20_000, AbLoop(10_000, 20_000))).isEqualTo(10_000)
        assertThat(abLoopTarget(25_000, AbLoop(10_000, 20_000))).isEqualTo(10_000)
    }
    @Test fun `abLoopTarget null when end not after start`() {
        assertThat(abLoopTarget(30_000, AbLoop(20_000, 20_000))).isNull()
        assertThat(abLoopTarget(30_000, AbLoop(20_000, 10_000))).isNull()
    }
    @Test fun `isComplete reflects both points and ordering`() {
        assertThat(AbLoop(1, 2).isComplete).isTrue()
        assertThat(AbLoop(2, 1).isComplete).isFalse()
        assertThat(AbLoop(1, null).isComplete).isFalse()
    }
    @Test fun `clampSpeed bounds to 0_25 and 4`() {
        assertThat(clampSpeed(0.1f)).isEqualTo(0.25f)
        assertThat(clampSpeed(5f)).isEqualTo(4f)
        assertThat(clampSpeed(1.5f)).isEqualTo(1.5f)
    }
    @Test fun `speed presets are within bounds and include 1x`() {
        assertThat(SPEED_PRESETS).contains(1f)
        assertThat(SPEED_PRESETS.all { it in MIN_SPEED..MAX_SPEED }).isTrue()
    }
    @Test fun `sleepRemainingMs is non-negative`() {
        assertThat(sleepRemainingMs(10_000, 3_000)).isEqualTo(7_000)
        assertThat(sleepRemainingMs(10_000, 12_000)).isEqualTo(0)
    }
    @Test fun `isSleepExpired when now reaches deadline`() {
        assertThat(isSleepExpired(10_000, 9_999)).isFalse()
        assertThat(isSleepExpired(10_000, 10_000)).isTrue()
    }
}
```

- [ ] **Step 2: Run to verify fail**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :core:playback:test`
Expected: FAIL — symbols unresolved.

- [ ] **Step 3: Implement `PlaybackControls.kt`**

```kotlin
package com.videoplayer.core.playback

/** An A–B repeat loop. Both points must be set and ordered (end after start) to be active. */
data class AbLoop(val startMs: Long? = null, val endMs: Long? = null) {
    val isComplete: Boolean get() = startMs != null && endMs != null && endMs > startMs
}

/** Where playback should jump back to for an active A–B loop once it reaches B, else null. */
fun abLoopTarget(positionMs: Long, loop: AbLoop): Long? =
    if (loop.isComplete && positionMs >= loop.endMs!!) loop.startMs else null

/** User-selectable playback speeds. */
val SPEED_PRESETS: List<Float> = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f)

const val MIN_SPEED = 0.25f
const val MAX_SPEED = 4f

/** Clamps an arbitrary speed into the supported range. */
fun clampSpeed(speed: Float): Float = speed.coerceIn(MIN_SPEED, MAX_SPEED)

/** Step size for frame-by-frame stepping (~1 frame at 25fps; approximate, codec-independent). */
const val FRAME_STEP_MS = 40L

/** Milliseconds left until a sleep deadline, clamped at 0. */
fun sleepRemainingMs(deadlineEpochMs: Long, nowEpochMs: Long): Long =
    (deadlineEpochMs - nowEpochMs).coerceAtLeast(0)

/** Whether a sleep deadline has been reached. */
fun isSleepExpired(deadlineEpochMs: Long, nowEpochMs: Long): Boolean = nowEpochMs >= deadlineEpochMs
```

- [ ] **Step 4: Run to verify pass**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :core:playback:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/playback
git commit -m "feat(core): A-B loop, sleep-timer, and speed-preset logic with tests"
```

---

## Task E2: Speed picker + frame-step controls [Android-verify]

**Files:**
- Modify: `app/src/main/kotlin/com/videoplayer/app/player/PlayerControls.kt`
- Modify: `app/src/main/kotlin/com/videoplayer/app/player/PlayerScreen.kt`

**Interfaces:**
- Consumes: `SPEED_PRESETS`, `clampSpeed`, `FRAME_STEP_MS` (E1); `seekTarget` (controls); `engine.setSpeed`/`pause`/`seekTo`.
- Produces: speed picker + frame-step in the overlay; `PlayerControls` gains `currentSpeed: Float`, `onSetSpeed: (Float) -> Unit`, `onFrameStep: (Long) -> Unit`.

- [ ] **Step 1: Add params + UI to `PlayerControls`**

Add to the `PlayerControls` signature: `currentSpeed: Float`, `onSetSpeed: (Float) -> Unit`, `onFrameStep: (Long) -> Unit` (and the A–B / sleep params from E3 will be added there too — coordinate so the final signature is consistent).

Add a **secondary control row** just above the existing seek-bar `Row` (still aligned to bottom, stacked in a `Column` with the seek row). It contains:
- A speed `TextButton` showing `"${currentSpeed}×"` (trim trailing `.0`) that opens a `DropdownMenu` listing `SPEED_PRESETS` (label `"${it}×"`); selecting calls `onSetSpeed(it)`.
- Two frame-step `IconButton`s — previous-frame and next-frame — calling `onFrameStep(-FRAME_STEP_MS)` and `onFrameStep(FRAME_STEP_MS)`. Use available core icons (e.g. `Icons.Filled.KeyboardArrowLeft`/`KeyboardArrowRight`) with content descriptions "Previous frame" / "Next frame".

Keep all existing controls (back, aspect, center play/pause, seek bar) unchanged.

- [ ] **Step 2: Wire in `PlayerScreen`**

Pass to `PlayerControls`:
```kotlin
currentSpeed = state.speed,
onSetSpeed = { engine.setSpeed(clampSpeed(it)); interactionTick++ },
onFrameStep = { delta ->
    val s = engine.state.value
    engine.pause()
    engine.seekTo(seekTarget(s.positionMs, delta, s.durationMs))
    interactionTick++
},
```
Add imports for `clampSpeed` (`com.videoplayer.core.playback.clampSpeed`) and `FRAME_STEP_MS` if referenced in the screen. The chosen speed persists automatically (P1.C `saveNow` records `state.speed`; it is restored on reopen by the existing resume-apply effect).

- [ ] **Step 3: [Android-verify] Build + emulator**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.
Emulator: open a video, change speed via the picker (audible/visible faster playback, pitch unchanged), tap frame-step buttons while paused (position nudges by ~1 frame). No crash.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/videoplayer/app/player/PlayerControls.kt app/src/main/kotlin/com/videoplayer/app/player/PlayerScreen.kt
git commit -m "feat(player): pitch-corrected speed picker and frame-by-frame step controls"
```

---

## Task E3: A–B repeat + sleep timer [Android-verify]

**Files:**
- Modify: `app/src/main/kotlin/com/videoplayer/app/player/PlayerControls.kt`
- Modify: `app/src/main/kotlin/com/videoplayer/app/player/PlayerScreen.kt`

**Interfaces:**
- Consumes: `AbLoop`, `abLoopTarget`, `isSleepExpired`, `sleepRemainingMs` (E1); `engine.seekTo`/`pause`; live `state.positionMs`.
- Produces: A–B + sleep controls in the overlay; enforcement effects in `PlayerScreen`.

- [ ] **Step 1: Add A–B + sleep controls to `PlayerControls`**

Add params: `abLoop: AbLoop`, `onToggleAb: () -> Unit`, `sleepActive: Boolean`, `onPickSleep: (SleepOption) -> Unit`. In the secondary row add:
- An **A–B** `TextButton`: label reflects state — `"A–B"` (no points), `"A•"` (start set), `"A–B ✓"` (active loop). Tapping calls `onToggleAb()` which cycles set-A → set-B → clear (logic in `PlayerScreen`).
- A **sleep** `IconButton`/`TextButton` (label `"💤"` or `Icons.Filled.Bedtime` if available, else `Icons.Filled.Schedule`) opening a `DropdownMenu` of options; tinted/marked when `sleepActive`.

Define a small UI enum in `PlayerScreen` (app layer, not core): `enum class SleepOption(val minutes: Int?) { OFF(null), M15(15), M30(30), M45(45), M60(60), END_OF_VIDEO(null) }` — distinguish `END_OF_VIDEO` from `OFF` by identity. Pass `SleepOption.entries` to the menu (labels: "Off", "15 min", …, "End of video").

- [ ] **Step 2: A–B cycle + enforcement in `PlayerScreen`**

State: `var abLoop by remember(item.uri) { mutableStateOf(AbLoop()) }`.
`onToggleAb`:
```kotlin
abLoop = when {
    abLoop.startMs == null -> abLoop.copy(startMs = engine.state.value.positionMs)
    abLoop.endMs == null -> abLoop.copy(endMs = engine.state.value.positionMs)
    else -> AbLoop()  // clear
}
```
Enforcement effect:
```kotlin
LaunchedEffect(state.positionMs, abLoop) {
    abLoopTarget(state.positionMs, abLoop)?.let { engine.seekTo(it) }
}
```

- [ ] **Step 3: Sleep timer state + enforcement in `PlayerScreen`**

State: `var sleepDeadlineMs by remember { mutableStateOf<Long?>(null) }`, `var sleepAtEndOfVideo by remember { mutableStateOf(false) }`. `sleepActive = sleepDeadlineMs != null || sleepAtEndOfVideo`.
`onPickSleep`:
```kotlin
when (option) {
    SleepOption.OFF -> { sleepDeadlineMs = null; sleepAtEndOfVideo = false }
    SleepOption.END_OF_VIDEO -> { sleepAtEndOfVideo = true; sleepDeadlineMs = null }
    else -> { sleepAtEndOfVideo = false; sleepDeadlineMs = System.currentTimeMillis() + option.minutes!! * 60_000L }
}
```
Duration enforcement (poll once per second against the deadline; pause when expired):
```kotlin
LaunchedEffect(sleepDeadlineMs) {
    val deadline = sleepDeadlineMs ?: return@LaunchedEffect
    while (!isSleepExpired(deadline, System.currentTimeMillis())) {
        delay(1_000L)
    }
    engine.pause()
    sleepDeadlineMs = null
}
```
End-of-video enforcement is handled in the auto-advance task (E4): when `status == ENDED && sleepAtEndOfVideo`, pause and DO NOT advance.

- [ ] **Step 4: [Android-verify] Build + emulator**

Build, then: set A then B a few seconds apart → playback loops back to A at B; clear it. Pick a 15-min sleep (or temporarily shorten the constant to verify) → playback pauses when it elapses. No crash.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/videoplayer/app/player
git commit -m "feat(player): A-B repeat loop and sleep timer (duration or end-of-video)"
```

---

## Task E4: Auto-advance to next file in folder [Android-verify]

**Files:**
- Modify: `app/src/main/kotlin/com/videoplayer/app/VideoPlayerApp.kt`
- Modify: `app/src/main/kotlin/com/videoplayer/app/player/PlayerScreen.kt`

**Interfaces:**
- Consumes: `nextInFolder` (`:core:model`), `PlayerStatus.ENDED`, `LibraryUiState.folders`/`videos`.
- Produces: `PlayerScreen(item, playlist: List<MediaItem>, onAdvance: (MediaItem) -> Unit, onBack, modifier)`; `VideoPlayerApp` resolves the playlist and swaps the selected item.

- [ ] **Step 1: Resolve the playlist in `VideoPlayerApp`**

Keep `onItemClick: (MediaItem) -> Unit` on `LibraryScreen` (no signature churn). When an item is selected, compute its sibling playlist from the library state:
```kotlin
val uiState by libraryViewModel.uiState.collectAsStateWithLifecycle()
var selected by remember { mutableStateOf<MediaItem?>(null) }
val current = selected
val playlist = remember(current, uiState.folders, uiState.videos) {
    val c = current ?: return@remember emptyList()
    uiState.folders.firstOrNull { f -> f.items.any { it.uri == c.uri } }?.items
        ?: uiState.videos.takeIf { vs -> vs.any { it.uri == c.uri } }
        ?: listOf(c)
}
```
Render:
```kotlin
if (current != null) {
    PlayerScreen(
        item = current,
        playlist = playlist,
        onAdvance = { next -> selected = next },
        onBack = { selected = null },
    )
}
```

- [ ] **Step 2: Auto-advance on `ENDED` in `PlayerScreen`**

Add params `playlist: List<MediaItem>` and `onAdvance: (MediaItem) -> Unit`. Add `private const val AUTO_ADVANCE_DEFAULT = true` (file-level; "TODO: make a setting in P1.H"). Add an effect:
```kotlin
LaunchedEffect(state.status) {
    if (state.status == PlayerStatus.ENDED) {
        if (sleepAtEndOfVideo) {
            engine.pause()
            sleepAtEndOfVideo = false
        } else if (AUTO_ADVANCE_DEFAULT) {
            nextInFolder(playlist, item.uri)?.let(onAdvance)
        }
    }
}
```
> When `onAdvance(next)` runs, `VideoPlayerApp` sets `selected = next`; `PlayerScreen`'s `engine`/state are `remember(item.uri)`-keyed, so a new engine loads the next file (silent-resuming via P1.C if it has prior progress, else from 0). `sleepAtEndOfVideo` correctly suppresses auto-advance.

- [ ] **Step 3: [Android-verify] Build + emulator**

Build. With ≥2 clips in one folder, let the first play to its end → the next file in the folder starts automatically. With sleep "end of video" set, the player pauses at the end instead of advancing. No crash.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/videoplayer/app/VideoPlayerApp.kt app/src/main/kotlin/com/videoplayer/app/player/PlayerScreen.kt
git commit -m "feat(player): auto-advance to next file in folder on end (sleep end-of-video suppresses it)"
```

---

## Self-Review (against P1.E-1 scope)

- *Speed 0.25–4× with pitch correction* → E1 presets/clamp + E2 picker; engine already pitch-corrects. Persists via P1.C. ✅
- *Frame-by-frame step* → E1 `FRAME_STEP_MS` + E2 buttons (pause + seek). ✅ (approximate, codec-independent — documented)
- *A–B repeat* → E1 `AbLoop`/`abLoopTarget` + E3 controls + enforcement. ✅
- *Sleep timer (duration OR end-of-video)* → E1 sleep math + E3 controls + enforcement; end-of-video handled in E4. ✅
- *Auto-advance (default ON)* → E4 playlist plumbing + ENDED handler; sleep end-of-video suppresses it. ✅
- *Aspect ratios* — Fit/Fill/Zoom already shipped (P1.B); additional named ratios (16:9/4:3) deferred (not signature; can add in P1.E-2/H). Noted.
- *Orientation lock, screen lock, Kids Lock* → P1.E-2 (separate plan). ✅
- **Placeholder scan:** E1 complete code+tests; E2–E4 give exact callbacks/effects/wiring with complete code for the non-obvious logic; overlay button layout has UI latitude (Android-verify), not a logic placeholder.
- **Type consistency:** `AbLoop`/`abLoopTarget`/`SPEED_PRESETS`/`clampSpeed`/`FRAME_STEP_MS`/`sleepRemainingMs`/`isSleepExpired` (E1); `currentSpeed`/`onSetSpeed`/`onFrameStep`/`onToggleAb`/`onPickSleep`/`SleepOption` (E2/E3); `playlist`/`onAdvance`/`AUTO_ADVANCE_DEFAULT`/`nextInFolder` (E4) — consistent.
