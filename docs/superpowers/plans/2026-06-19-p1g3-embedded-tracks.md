# P1.G-3 — Embedded Text Tracks (Media3) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Surface a video's embedded subtitle (text) tracks through the engine, let the user pick one from the CC menu, and render it via `PlayerView`'s built-in `SubtitleView` (which respects system caption styles) — while keeping the external/custom subtitle path (G-2) and embedded path mutually exclusive (only one subtitle shows at a time).

**Architecture:** The `PlaybackEngine` interface gains `selectEmbeddedTextTrack(id: String?)`; `PlaybackState` gains `textTracks: List<TextTrackInfo>` and `selectedTextTrackId: String?`. `Media3PlaybackEngine` populates `textTracks` from `Player.Listener.onTracksChanged` (one `TextTrackInfo` per text track, stable id `"text:<textGroupIndex>:<trackIndex>"`), selects a track via `TrackSelectionParameters` override, and disables text output with `setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)`. `PlayerScreen` adds embedded tracks to the CC menu and enforces mutual exclusion: picking an embedded track clears the external sub; picking an external sub or **Off disables embedded text**. **Embedded text defaults to OFF on each file** (offer-don't-auto-show; also clears any text-disable/override left from a prior file's external sub — track-selection params persist across ExoPlayer items).

**Tech Stack:** Kotlin, AndroidX Media3 (ExoPlayer/MediaController track selection), Jetpack Compose. Pure subtitle/cue logic already in `:core:playback` (G-1); external path in `:app` (G-2).

## Global Constraints

- Build with JDK 21 — prefix every gradle call: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew ...`. Repo root: `/Users/mustafa/Desktop/Projects/mobil uygulama/video-player`.
- `:core:*` stays pure Kotlin. The ONLY core change here is additive: two new `PlaybackState` fields and one new `PlaybackEngine` method, implemented by `FakePlaybackEngine`. `TextTrackInfo` already exists in `:core:playback` (added in G-1) — reuse it, do NOT redefine.
- Adding a method to `PlaybackEngine` requires every implementor to implement it. There are exactly two: `FakePlaybackEngine` (`:core:playback`) and `Media3PlaybackEngine` (`:app`). Before finishing G3.1, grep for any other `: PlaybackEngine` impl (e.g. in tests) and update it. Command: `grep -rn "PlaybackEngine" --include=*.kt .`
- No new dependencies; no new permissions; no `material-icons-extended` (core `Icons.Filled.*` only).
- Only ONE subtitle visible at a time — embedded and external paths are mutually exclusive (enforced in G3.2).
- Must NOT regress P1.A–P1.G-2 (controls, gestures, resume, locks, PiP, background audio, native playlist/auto-advance, settings, external/sibling subtitles).
- Commit after every green step.
- Spec: `docs/superpowers/specs/2026-06-19-p1g-subtitles-design.md`.

---

### Task G3.1: Engine text-track surface (core interface + state + Fake + Media3 impl)

**Files:**
- Modify: `core/playback/src/main/kotlin/com/videoplayer/core/playback/PlaybackState.kt` (two new fields)
- Modify: `core/playback/src/main/kotlin/com/videoplayer/core/playback/PlaybackEngine.kt` (one new method)
- Modify: `core/playback/src/main/kotlin/com/videoplayer/core/playback/FakePlaybackEngine.kt` (implement method)
- Modify: `core/playback/src/test/kotlin/com/videoplayer/core/playback/FakePlaybackEngineTest.kt` (new tests)
- Modify: `app/src/main/kotlin/com/videoplayer/app/engine/Media3PlaybackEngine.kt` (onTracksChanged, selectEmbeddedTextTrack, id helpers)
- Create: `app/src/test/kotlin/com/videoplayer/app/engine/Media3TextTrackIdTest.kt`

**Interfaces — Produces:**
- `PlaybackState.textTracks: List<TextTrackInfo>` (default `emptyList()`), `PlaybackState.selectedTextTrackId: String?` (default `null`)
- `PlaybackEngine.selectEmbeddedTextTrack(id: String?)` (null = disable embedded text)
- top-level `fun textTrackId(groupIndex: Int, trackIndex: Int): String` and `fun parseTextTrackId(id: String): Pair<Int, Int>?` in the engine file
**Consumes:** existing `TextTrackInfo(id, label, language?)` from `:core:playback` (G-1).

- [ ] **Step 1: Add the two `PlaybackState` fields** — in `PlaybackState.kt`, append to the data class (after `currentMediaIndex`):

```kotlin
    val currentMediaIndex: Int = 0,
    /** Embedded (in-container) subtitle tracks of the current media; empty until known. */
    val textTracks: List<TextTrackInfo> = emptyList(),
    /** Id of the selected embedded text track (see PlaybackEngine.selectEmbeddedTextTrack), or null when none/disabled. */
    val selectedTextTrackId: String? = null,
```

- [ ] **Step 2: Add the interface method** — in `PlaybackEngine.kt`, append inside the interface:

```kotlin
    /**
     * Select an embedded text (subtitle) track by its [id] (from [PlaybackState.textTracks]),
     * or pass null to disable embedded text output. Ids are engine-defined and opaque to callers.
     */
    fun selectEmbeddedTextTrack(id: String?)
```

- [ ] **Step 3: Write the failing Fake tests** — in `FakePlaybackEngineTest.kt`, add:

```kotlin
    @Test fun `textTracks default empty and no track selected`() = runTest {
        val e = FakePlaybackEngine()
        assertThat(e.state.value.textTracks).isEmpty()
        assertThat(e.state.value.selectedTextTrackId).isNull()
    }

    @Test fun `selectEmbeddedTextTrack updates and clears selectedTextTrackId`() = runTest {
        val e = FakePlaybackEngine()
        e.selectEmbeddedTextTrack("text:0:1")
        assertThat(e.state.value.selectedTextTrackId).isEqualTo("text:0:1")
        e.selectEmbeddedTextTrack(null)
        assertThat(e.state.value.selectedTextTrackId).isNull()
    }
```

(If `runTest`/`assertThat`/`Test` imports aren't already present in the file, they are — the existing tests use them.)

- [ ] **Step 4: Run to verify it fails** — `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :core:playback:test`. Expected: FAIL (`selectEmbeddedTextTrack` unresolved on Fake; possibly the whole module fails to compile because `FakePlaybackEngine` no longer satisfies the interface — that is the expected red).

- [ ] **Step 5: Implement in `FakePlaybackEngine.kt`** — add (e.g. after `setPauseAtEndOfMediaItems`):

```kotlin
    override fun selectEmbeddedTextTrack(id: String?) = _state.update { it.copy(selectedTextTrackId = id) }
```

- [ ] **Step 6: Run core tests to verify pass** — `./gradlew :core:playback:test`. Expected: PASS (all prior + 2 new).

- [ ] **Step 7: Commit the core change**

```bash
git add core/playback && git commit -m "feat(core): engine text-track surface — textTracks/selectedTextTrackId + selectEmbeddedTextTrack"
```

- [ ] **Step 8: Add id helpers + the failing app unit test** — create `app/src/test/kotlin/com/videoplayer/app/engine/Media3TextTrackIdTest.kt`:

```kotlin
package com.videoplayer.app.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class Media3TextTrackIdTest {
    @Test fun `formats and parses round-trip`() {
        assertThat(textTrackId(0, 1)).isEqualTo("text:0:1")
        assertThat(parseTextTrackId("text:0:1")).isEqualTo(0 to 1)
        assertThat(parseTextTrackId("text:2:3")).isEqualTo(2 to 3)
    }

    @Test fun `parse rejects malformed`() {
        assertThat(parseTextTrackId("")).isNull()
        assertThat(parseTextTrackId("audio:0:1")).isNull()
        assertThat(parseTextTrackId("text:x:1")).isNull()
        assertThat(parseTextTrackId("text:0")).isNull()
    }
}
```

- [ ] **Step 9: Implement the Media3 engine changes** in `Media3PlaybackEngine.kt`:

  (a) Add imports (with the other media3.common imports):
```kotlin
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import com.videoplayer.core.playback.TextTrackInfo
```

  (b) Add the top-level pure helpers (near `videoAspectRatio`, file scope):
```kotlin
/** Stable id for an embedded text track: its index among text track-groups and the track index within that group. */
fun textTrackId(groupIndex: Int, trackIndex: Int): String = "text:$groupIndex:$trackIndex"

/** Parse a [textTrackId] back to (groupIndex, trackIndex); null if malformed. */
fun parseTextTrackId(id: String): Pair<Int, Int>? {
    val parts = id.split(':')
    if (parts.size != 3 || parts[0] != "text") return null
    val g = parts[1].toIntOrNull() ?: return null
    val t = parts[2].toIntOrNull() ?: return null
    return g to t
}
```

  (c) Add a field next to the other private fields (e.g. after `private var released = false`):
```kotlin
    private var latestTracks: Tracks? = null
```

  (d) Add an `onTracksChanged` override to the `listener` object and a shared `applyTracks` helper. In the `listener` object, add:
```kotlin
        override fun onTracksChanged(tracks: Tracks) = applyTracks(tracks)
```
  and add this private method on the class (e.g. after `seedStateFromController`):
```kotlin
    /** Derive [PlaybackState.textTracks] + [PlaybackState.selectedTextTrackId] from [tracks]. */
    private fun applyTracks(tracks: Tracks) {
        latestTracks = tracks
        val infos = mutableListOf<TextTrackInfo>()
        var selectedId: String? = null
        var textGroupIndex = 0
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_TEXT) continue
            for (t in 0 until group.length) {
                if (!group.isTrackSupported(t)) continue
                val format = group.getTrackFormat(t)
                val id = textTrackId(textGroupIndex, t)
                val label = format.label ?: format.language ?: "Track ${infos.size + 1}"
                infos.add(TextTrackInfo(id = id, label = label, language = format.language))
                if (group.isTrackSelected(t)) selectedId = id
            }
            textGroupIndex++
        }
        _state.update { it.copy(textTracks = infos, selectedTextTrackId = selectedId) }
    }
```

  (e) Seed tracks on connect — in `seedStateFromController`, after the existing `_state.update { ... }`, add:
```kotlin
        applyTracks(c.currentTracks)
```

  (f) Implement the interface method (with the other `override fun`s):
```kotlin
    override fun selectEmbeddedTextTrack(id: String?) = withController { c ->
        val builder = c.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
        val parsed = id?.let { parseTextTrackId(it) }
        val textGroups = latestTracks?.groups?.filter { it.type == C.TRACK_TYPE_TEXT }.orEmpty()
        val group = parsed?.first?.let { textGroups.getOrNull(it) }
        if (id != null && parsed != null && group != null && parsed.second < group.length) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, parsed.second))
        } else {
            // null id (disable) or unknown/stale id: turn embedded text off.
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        }
        c.trackSelectionParameters = builder.build()
        // Optimistic reflect; onTracksChanged refines selectedTextTrackId after selection settles.
        _state.update { it.copy(selectedTextTrackId = if (group != null && parsed != null) id else null) }
    }
```

- [ ] **Step 10: Run app build + tests** — `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:assembleDebug :app:testDebugUnitTest`. Expected: `BUILD SUCCESSFUL`; the 2 new id tests pass; all 47 prior app tests stay green. Also confirm no other `PlaybackEngine` implementor was missed (grep from Global Constraints).

- [ ] **Step 11: Commit the engine change**

```bash
git add app && git commit -m "feat(engine): expose embedded text tracks and select/disable via TrackSelectionParameters"
```

---

### Task G3.2: CC menu embedded integration + mutual exclusion + default-off

**Files:**
- Modify: `app/src/main/kotlin/com/videoplayer/app/player/PlayerControls.kt` (3 new params, embedded items in CC menu)
- Modify: `app/src/main/kotlin/com/videoplayer/app/player/PlayerScreen.kt` (pass embedded props, mutual-exclusion callbacks, per-file default-off effect)

**Interfaces — Consumes:** `PlaybackState.textTracks`/`selectedTextTrackId`, `PlaybackEngine.selectEmbeddedTextTrack` (G3.1); `TextTrackInfo` (core); existing CC `DropdownMenu` (G-2).

This task is **[Android-verify]** (Compose + engine integration; green gate = `:app:assembleDebug` + `:app:testDebugUnitTest`). Runtime verified in G3.3.

- [ ] **Step 1: Extend `PlayerControls.kt`** — add three params to the `PlayerControls` signature (group them with the other subtitle params, e.g. right after `subtitleOptions`):

```kotlin
    textTracks: List<TextTrackInfo>,
    selectedTextTrackId: String?,
    onSelectEmbedded: (String) -> Unit,
```

Add the import:
```kotlin
import com.videoplayer.core.playback.TextTrackInfo
```

In the CC `DropdownMenu`, the **Off** item's check must reflect both paths being off, and embedded tracks must be listed between Off and the external options. Replace the current Off item + add the embedded loop so the menu reads:

```kotlin
        DropdownMenuItem(
            text = { Text("Off") },
            onClick = {
                onSelectSubtitle(null)
                subtitleMenuExpanded = false
            },
            trailingIcon = {
                if (selectedSubtitleUri == null && selectedTextTrackId == null) {
                    Icon(Icons.Filled.Check, contentDescription = null)
                }
            },
        )
        textTracks.forEach { track ->
            DropdownMenuItem(
                text = { Text(track.label) },
                onClick = {
                    onSelectEmbedded(track.id)
                    subtitleMenuExpanded = false
                },
                trailingIcon = {
                    if (selectedTextTrackId == track.id) Icon(Icons.Filled.Check, contentDescription = null)
                },
            )
        }
        subtitleOptions.forEach { option ->
            // ... unchanged external/sibling items ...
        }
```

Update the CC button tint so it highlights for either path:
```kotlin
        Text(
            text = "CC",
            color = if (selectedSubtitleUri != null || selectedTextTrackId != null) {
                MaterialTheme.colorScheme.primary
            } else {
                Color.White
            },
        )
```

Leave the `Load subtitle file…` item and the `if (selectedSubtitleUri != null) { …nudge… }` block unchanged (nudge is external-only — embedded subs are rendered by Media3 with no offset, per the spec).

- [ ] **Step 2: Wire `PlayerScreen.kt`** — add the three new args to the `PlayerControls(...)` call and make the selection callbacks mutually exclusive. Change the existing `onSelectSubtitle` line and add the embedded args:

```kotlin
                onSelectSubtitle = { uri ->
                    engine.selectEmbeddedTextTrack(null) // external/off disables embedded text
                    selectedSubtitleUri = uri
                },
                onLoadSubtitleFile = { subtitlePicker.launch(arrayOf("*/*")) },
                onNudgeSubtitle = { delta -> subtitleOffsetMs = nudgeSubtitleOffset(subtitleOffsetMs, delta) },
                textTracks = state.textTracks,
                selectedTextTrackId = state.selectedTextTrackId,
                onSelectEmbedded = { id ->
                    selectedSubtitleUri = null // embedded selection clears the custom overlay
                    engine.selectEmbeddedTextTrack(id)
                },
```

Also update the SAF picker callback so loading a file via SAF disables embedded too (it sets `selectedSubtitleUri`, but embedded must be turned off for mutual exclusion). In the `subtitlePicker` result lambda, before `selectedSubtitleUri = option.uri`, add:

```kotlin
            engine.selectEmbeddedTextTrack(null)
            selectedSubtitleUri = option.uri
```

- [ ] **Step 3: Add the per-file default-off effect** in `PlayerScreen.kt` — next to the existing sibling-scan / cue-load `LaunchedEffect`s:

```kotlin
    // Embedded text defaults to OFF on each file: offer-don't-auto-show, and clears any
    // text-disable/override left from a previous file's external subtitle (track-selection
    // params persist across ExoPlayer items). The user enables an embedded track via the CC menu.
    LaunchedEffect(currentItem.uri) {
        engine.selectEmbeddedTextTrack(null)
    }
```

- [ ] **Step 4: Build + test** — `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:assembleDebug :app:testDebugUnitTest`. Expected: `BUILD SUCCESSFUL`; all app tests green (47 + the 2 id tests from G3.1).

- [ ] **Step 5: Commit**

```bash
git add app && git commit -m "feat(player): embedded subtitle tracks in CC menu with mutual exclusion and default-off"
```

---

### Task G3.3: Device smoke — embedded track selection **[Android-verify]**

**Goal:** Confirm a clip with an embedded subtitle track lists the track in the CC menu, that selecting it renders via `PlayerView`'s SubtitleView, that switching to Off / an external sub hides it (no double-render), and that the embedded sub does not auto-show on a fresh file. Controller runs this after both code tasks pass review.

- [ ] **Step 1: Create a clip with an embedded subtitle track** (the existing test clips have none). Requires `ffmpeg` locally:

```bash
ffmpeg -y -i /tmp/vpclips/clipA40s-blue-440hz.mp4 -i /tmp/vpclips/clipA40s-blue-440hz.srt \
  -map 0:v -map 0:a? -map 1 -c:v copy -c:a copy -c:s srt \
  -metadata:s:s:0 language=eng -metadata:s:s:0 title=English \
  /tmp/vpclips/clipEmbed.mkv
```
If `ffmpeg` is unavailable, install it or obtain any small .mkv with a text subtitle track; record the substitution in the ledger.

- [ ] **Step 2: Push + scan + install**

```bash
/opt/homebrew/bin/adb push /tmp/vpclips/clipEmbed.mkv /sdcard/Movies/VPTest/
/opt/homebrew/bin/adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Movies/VPTest/clipEmbed.mkv
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:installDebug
/opt/homebrew/bin/adb shell am start -n com.videoplayer.app/.MainActivity
```

- [ ] **Step 3: Verify (pause first; resize screenshots `sips -Z 1600`, scale taps ×1.5):**
  - **A. Default off** — open `clipEmbed.mkv`; no subtitle shows on screen by default.
  - **B. Track listed** — open the CC menu; an embedded track ("English" or "eng") appears between `Off` and any external options; `Off` is checked.
  - **C. Embedded renders** — select the embedded track; the cue text renders (via PlayerView's SubtitleView) in sync; the CC button highlights and the track shows a check.
  - **D. Mutual exclusion** — with the embedded track active, pick `Load subtitle file…` → choose the .srt (or pick a sibling if listed); confirm only ONE subtitle shows (the custom overlay), not two; and the embedded check moves off. Then select `Off`; confirm nothing shows.
  - **E. No regression** — play/pause, seek, lock, PiP still work; external subs on a plain clip (clipA + clipA.srt) still work; no crash (`adb logcat -d | grep -i "AndroidRuntime.*com.videoplayer"`).

- [ ] **Step 4: Record the smoke result in the ledger.**

---

## Self-Review

- **Spec coverage (G-3 section):** engine text-track surface (`textTracks`/`selectedTextTrackId`/`selectEmbeddedTextTrack`) ✅ (G3.1); stable id from group/track indices ✅ (`textTrackId`/`parseTextTrackId`, tested); CC-menu integration ✅ (G3.2); PlayerView SubtitleView render ✅ (existing PlayerView renders text output; G3.3 verifies); `FakePlaybackEngine` updated ✅ (G3.1); device-verify embedded selection ✅ (G3.3).
- **Mutual exclusion (spec "only one subtitle active at a time"):** embedded selection clears `selectedSubtitleUri`; external/Off/SAF-load call `selectEmbeddedTextTrack(null)`. ✅
- **Embedded id stability risk (spec Risks):** ids derived consistently from text-group/track indices via the shared enumeration in `applyTracks` and applied via the same filter in `selectEmbeddedTextTrack`; stale/unknown ids fall back to disabled. ✅
- **Placeholder scan:** concrete code in every step.
- **Type consistency:** `TextTrackInfo(id,label,language?)` reused from core; `selectEmbeddedTextTrack(id: String?)` signature consistent across interface/Fake/Media3/call-site; `textTrackId`/`parseTextTrackId` round-trip tested.
- **Purity:** core change is additive data/interface only; all Media3 track logic in `:app`.
- **No new deps/permissions.**
- **Decision noted:** embedded text defaults OFF per file (predictable + clean interface + avoids cross-item param leak); revisitable to auto-show-default later.
