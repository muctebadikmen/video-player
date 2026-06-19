# P1.G-4 — Per-file Subtitle + Offset Memory Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Remember each file's chosen subtitle (embedded track, external/sibling file, or off) and its custom sync offset, and restore them automatically when the file is reopened — using the P1.C reserved columns (`subtitleTrackId`, `subtitleOffsetMs`). This is the final P1.G sub-package; it ends with a whole-feature subtitle device smoke + review.

**Architecture:** A pure token encodes the active selection — `null`/`"off"` = none, `"embedded:<trackId>"` = an embedded track, `"ext:<uri>"` = an external/sibling URI — with tested encode/decode helpers in `:core:playback`. `PlaybackMemoryRepository.persist` writes `subtitleTrackId`/`subtitleOffsetMs`; `resolveStart` returns them in `ResolvedStartSettings`. `PlayerScreen` saves the current token via the existing `saveNow()` path (guarded so a save before restore completes re-writes the *saved* token, never wiping it) and restores it per file (external sub re-loaded by URI; embedded re-selected once its track appears). Also hardens `selectEmbeddedTextTrack` against stale/unsupported ids (the two G-3 review carry-forwards), now reachable because remembered ids can target a re-opened file.

**Tech Stack:** Kotlin, Room + DataStore (existing persistence), Jetpack Compose, AndroidX Media3. Pure subtitle/cue logic in `:core:playback` (G-1/G-4); external path (G-2) and embedded path (G-3) already merged.

## Global Constraints

- Build with JDK 21 — prefix every gradle call: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew ...`. Repo root: `/Users/mustafa/Desktop/Projects/mobil uygulama/video-player`.
- `:core:*` stays pure Kotlin (the token helpers are pure; no `android.*`/Media3/Compose). All Android/persistence/UI in `:app`.
- Reuse the P1.C reserved columns — `PlaybackMemoryEntity.subtitleTrackId: String?` and `subtitleOffsetMs: Long?` ALREADY EXIST; do NOT add columns or a DB migration.
- No new dependencies; no new permissions; no `material-icons-extended`.
- Memory encoding (exact): `null`/`"off"` = none, `"embedded:<id>"` = embedded track (id from `PlaybackState.textTracks`), `"ext:<uri>"` = external/sibling URI.
- Must NOT regress P1.A–P1.G-3 (controls, gestures, resume, locks, PiP, background audio, playlist/auto-advance, settings, external+embedded subtitles, mutual exclusion, default-off).
- Commit after every green step.
- Spec: `docs/superpowers/specs/2026-06-19-p1g-subtitles-design.md`.

---

### Task G4.1: Pure subtitle-memory token helpers (TDD)

**Files:**
- Modify: `core/playback/src/main/kotlin/com/videoplayer/core/playback/Subtitle.kt`
- Modify: `core/playback/src/test/kotlin/com/videoplayer/core/playback/SubtitleTest.kt`

**Interfaces — Produces:**
- `sealed interface SubtitleSelection { data object Off; data class Embedded(val id: String); data class External(val uri: String) }`
- `fun subtitleMemoryToken(embeddedTrackId: String?, externalUri: String?): String?`
- `fun parseSubtitleToken(token: String?): SubtitleSelection`

- [ ] **Step 1: Write the failing tests** — append to `SubtitleTest.kt`:

```kotlin
    @Test fun `subtitleMemoryToken encodes embedded, ext, and off`() {
        assertThat(subtitleMemoryToken("text:0:1", null)).isEqualTo("embedded:text:0:1")
        assertThat(subtitleMemoryToken(null, "content://x")).isEqualTo("ext:content://x")
        assertThat(subtitleMemoryToken(null, null)).isNull()
        // Embedded wins if both are somehow set (paths are mutually exclusive in practice).
        assertThat(subtitleMemoryToken("text:0:1", "content://x")).isEqualTo("embedded:text:0:1")
    }

    @Test fun `parseSubtitleToken decodes tokens and treats junk as Off`() {
        assertThat(parseSubtitleToken(null)).isEqualTo(SubtitleSelection.Off)
        assertThat(parseSubtitleToken("off")).isEqualTo(SubtitleSelection.Off)
        assertThat(parseSubtitleToken("embedded:text:0:1")).isEqualTo(SubtitleSelection.Embedded("text:0:1"))
        assertThat(parseSubtitleToken("ext:content://x")).isEqualTo(SubtitleSelection.External("content://x"))
        assertThat(parseSubtitleToken("garbage")).isEqualTo(SubtitleSelection.Off)
    }
```

- [ ] **Step 2: Run to verify it fails** — `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :core:playback:test`. Expected: FAIL (symbols unresolved).

- [ ] **Step 3: Implement in `Subtitle.kt`** — append:

```kotlin
/** The active subtitle selection, decoded from a per-file memory token. */
sealed interface SubtitleSelection {
    data object Off : SubtitleSelection
    data class Embedded(val id: String) : SubtitleSelection
    data class External(val uri: String) : SubtitleSelection
}

/**
 * Encode the active subtitle selection for per-file memory:
 * `"embedded:<id>"`, `"ext:<uri>"`, or null when nothing is selected.
 * Embedded takes precedence (the two paths are mutually exclusive at runtime).
 */
fun subtitleMemoryToken(embeddedTrackId: String?, externalUri: String?): String? = when {
    embeddedTrackId != null -> "embedded:$embeddedTrackId"
    externalUri != null -> "ext:$externalUri"
    else -> null
}

/** Decode a memory token (see [subtitleMemoryToken]); unrecognized input ⇒ [SubtitleSelection.Off]. */
fun parseSubtitleToken(token: String?): SubtitleSelection = when {
    token == null || token == "off" -> SubtitleSelection.Off
    token.startsWith("embedded:") -> SubtitleSelection.Embedded(token.removePrefix("embedded:"))
    token.startsWith("ext:") -> SubtitleSelection.External(token.removePrefix("ext:"))
    else -> SubtitleSelection.Off
}
```

- [ ] **Step 4: Run to verify pass** — `./gradlew :core:playback:test`. Expected: PASS (all prior + 2 new).

- [ ] **Step 5: Commit**

```bash
git add core/playback && git commit -m "feat(core): subtitle-memory token encode/decode helpers with tests"
```

---

### Task G4.2: Persist + resolve subtitle columns (TDD, persistence layer)

**Files:**
- Modify: `app/src/main/kotlin/com/videoplayer/app/data/memory/ResolvedStartSettings.kt`
- Modify: `app/src/main/kotlin/com/videoplayer/app/data/memory/PlaybackMemoryRepository.kt`
- Modify: `app/src/main/kotlin/com/videoplayer/app/player/PlayerViewModel.kt`
- Modify: `app/src/test/kotlin/com/videoplayer/app/data/memory/PlaybackMemoryRepositoryTest.kt`

**Interfaces — Produces:** `ResolvedStartSettings` gains `subtitleTrackId: String?` + `subtitleOffsetMs: Long`; `PlaybackMemoryRepository.persist` gains `subtitleTrackId: String? = null, subtitleOffsetMs: Long? = null` params (written to the row); `resolveStart` returns the saved subtitle values; `PlayerViewModel.persist` gains the two params (defaulted) and forwards them.

- [ ] **Step 1: Write the failing repo tests** — append to `PlaybackMemoryRepositoryTest.kt`:

```kotlin
    @Test fun `persist then resolveStart restores subtitle track and offset`() = runTest {
        repo.persist("u", positionMs = 10_000, durationMs = 120_000, speed = 1f, aspectMode = "FIT",
            subtitleTrackId = "ext:content://x", subtitleOffsetMs = 150L, nowEpochMs = 1L)
        val r = repo.resolveStart("u")
        assertThat(r.subtitleTrackId).isEqualTo("ext:content://x")
        assertThat(r.subtitleOffsetMs).isEqualTo(150L)
    }

    @Test fun `resolveStart defaults subtitle to null and zero offset when absent`() = runTest {
        val r = repo.resolveStart("none")
        assertThat(r.subtitleTrackId).isNull()
        assertThat(r.subtitleOffsetMs).isEqualTo(0L)
    }

    @Test fun `persistOrientation preserves a saved subtitle`() = runTest {
        repo.persist("u", positionMs = 10_000, durationMs = 120_000, speed = 1f, aspectMode = "FIT",
            subtitleTrackId = "embedded:text:0:0", subtitleOffsetMs = 0L, nowEpochMs = 1L)
        repo.persistOrientation("u", orientation = 6, nowEpochMs = 2L)
        assertThat(repo.resolveStart("u").subtitleTrackId).isEqualTo("embedded:text:0:0")
    }
```

- [ ] **Step 2: Run to verify it fails** — `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest`. Expected: FAIL (persist has no subtitle params; ResolvedStartSettings has no subtitle fields).

- [ ] **Step 3: Add the `ResolvedStartSettings` fields** — append to the data class:

```kotlin
    /** Per-file orientation override (ActivityInfo.screenOrientation int), or null for no override. */
    val orientation: Int?,
    /** Per-file subtitle memory token ("embedded:<id>" / "ext:<uri>" / null = none). */
    val subtitleTrackId: String?,
    /** Per-file custom-subtitle sync offset in ms (0 when none). */
    val subtitleOffsetMs: Long,
```

(Keep `orientation` as the existing field; add the two new ones after it. Update any positional constructor calls accordingly — `resolveStart` is the only one.)

- [ ] **Step 4: Update `PlaybackMemoryRepository`** — add the two params to `persist` and write them; have `resolveStart` return them. In `persist`, change the signature and the `dao.upsert(base.copy(...))`:

```kotlin
    suspend fun persist(
        mediaUri: String,
        positionMs: Long,
        durationMs: Long,
        speed: Float,
        aspectMode: String,
        subtitleTrackId: String? = null,
        subtitleOffsetMs: Long? = null,
        nowEpochMs: Long,
    ) {
        if (durationMs <= 0L) return
        val existing = dao.getByUri(mediaUri)
        val base = existing ?: PlaybackMemoryEntity(
            mediaUri = mediaUri, positionMs = 0, durationMs = 0,
            aspectMode = "FIT", speed = 1f, updatedAtEpochMs = 0L,
        )
        dao.upsert(
            base.copy(
                positionMs = positionMs,
                durationMs = durationMs,
                speed = speed,
                aspectMode = aspectMode,
                subtitleTrackId = subtitleTrackId,
                subtitleOffsetMs = subtitleOffsetMs,
                updatedAtEpochMs = nowEpochMs,
            ),
        )
    }
```

> Note: `persist` now overwrites the subtitle columns with whatever it is given (its sole production caller, `saveNow`, always supplies the current token — see G4.3). `persistOrientation` still only touches `orientation`, so it preserves the saved subtitle (verified by the new test).

In `resolveStart`, change the returned object:

```kotlin
        return ResolvedStartSettings(
            startPositionMs,
            speed,
            aspectMode,
            saved?.orientation,
            saved?.subtitleTrackId,
            saved?.subtitleOffsetMs ?: 0L,
        )
```

- [ ] **Step 5: Update `PlayerViewModel.persist`** — add the two defaulted params and forward them:

```kotlin
    fun persist(
        mediaUri: String,
        positionMs: Long,
        durationMs: Long,
        speed: Float,
        aspectMode: String,
        subtitleTrackId: String? = null,
        subtitleOffsetMs: Long? = null,
    ) {
        viewModelScope.launch {
            repo.persist(
                mediaUri, positionMs, durationMs, speed, aspectMode,
                subtitleTrackId, subtitleOffsetMs, System.currentTimeMillis(),
            )
        }
    }
```

- [ ] **Step 6: Run to verify pass** — `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest :app:assembleDebug`. Expected: BUILD SUCCESSFUL; all app tests green (52 incl. 3 new repo tests). Existing repo tests that call `persist(...)` without subtitle args still compile (params are defaulted).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/videoplayer/app/data app/src/main/kotlin/com/videoplayer/app/player/PlayerViewModel.kt app/src/test/kotlin/com/videoplayer/app/data && git commit -m "feat(memory): persist and resolve per-file subtitle track + offset"
```

---

### Task G4.3: PlayerScreen restore/save wiring + engine hardening

**Files:**
- Modify: `app/src/main/kotlin/com/videoplayer/app/player/PlayerScreen.kt`
- Modify: `app/src/main/kotlin/com/videoplayer/app/engine/Media3PlaybackEngine.kt`

**Interfaces — Consumes:** `subtitleMemoryToken`/`parseSubtitleToken`/`SubtitleSelection` (G4.1), `ResolvedStartSettings.subtitleTrackId`/`subtitleOffsetMs` + `PlayerViewModel.persist` subtitle params (G4.2), existing subtitle state + engine `selectEmbeddedTextTrack` (G-2/G-3).

This task is **[Android-verify]** (Compose + engine; green gate = `:app:assembleDebug` + `:app:testDebugUnitTest`). Runtime verified in G4.4.

- [ ] **Step 1: Harden `selectEmbeddedTextTrack`** in `Media3PlaybackEngine.kt` (the two G-3 review carry-forwards: optimistic state must match the actual decision, and resolution must re-check `isTrackSupported` — now reachable because a remembered id can target a re-opened file whose tracks differ). Replace the body's decision block with:

```kotlin
    override fun selectEmbeddedTextTrack(id: String?) = withController { c ->
        val builder = c.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
        val parsed = id?.let { parseTextTrackId(it) }
        val textGroups = latestTracks?.groups?.filter { it.type == C.TRACK_TYPE_TEXT }.orEmpty()
        val group = parsed?.first?.let { textGroups.getOrNull(it) }
        if (id != null && parsed != null && group != null &&
            parsed.second < group.length && group.isTrackSupported(parsed.second)
        ) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, parsed.second))
            c.trackSelectionParameters = builder.build()
            _state.update { it.copy(selectedTextTrackId = id) }
        } else {
            // null id (disable) or stale/unsupported id: turn embedded text off.
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            c.trackSelectionParameters = builder.build()
            _state.update { it.copy(selectedTextTrackId = null) }
        }
    }
```

- [ ] **Step 2: Add subtitle-restore state + effect in `PlayerScreen.kt`.** Add imports:

```kotlin
import com.videoplayer.core.playback.SubtitleSelection
import com.videoplayer.core.playback.parseSubtitleToken
import com.videoplayer.core.playback.subtitleMemoryToken
```

Add a restore flag with the other per-file state (re-keyed on `currentItem.uri`, next to the subtitle state block):

```kotlin
    var subtitleRestored by remember(currentItem.uri) { mutableStateOf(false) }
```

The existing G-3 default-off effect stays as the immediate per-file reset (it guarantees embedded text is off until restore re-applies a remembered selection):

```kotlin
    LaunchedEffect(currentItem.uri) {
        engine.selectEmbeddedTextTrack(null)
    }
```

Add the restore effect right after it. It keys on `state.textTracks` so an embedded selection is re-applied once its track appears:

```kotlin
    // Restore the remembered subtitle selection + offset once resolved settings (and, for an
    // embedded track, the track list) are available. Guarded so it runs once per file.
    LaunchedEffect(currentItem.uri, resolved, state.textTracks) {
        val r = resolved ?: return@LaunchedEffect
        if (subtitleRestored) return@LaunchedEffect
        when (val sel = parseSubtitleToken(r.subtitleTrackId)) {
            is SubtitleSelection.Off -> subtitleRestored = true // default-off effect already disabled embedded
            is SubtitleSelection.External -> {
                val uri = sel.uri
                val name = subtitleDisplayName(context, Uri.parse(uri))
                    ?: uri.substringAfterLast('/').ifEmpty { "Subtitle" }
                externalSubtitles = (externalSubtitles + SubtitleOption(uri, name)).distinctBy { it.uri }
                subtitleOffsetMs = r.subtitleOffsetMs
                engine.selectEmbeddedTextTrack(null)
                selectedSubtitleUri = uri
                subtitleRestored = true
            }
            is SubtitleSelection.Embedded -> {
                if (state.textTracks.any { it.id == sel.id }) {
                    selectedSubtitleUri = null
                    engine.selectEmbeddedTextTrack(sel.id)
                    subtitleRestored = true
                }
                // else: track not present yet — wait for state.textTracks to update (effect re-runs).
            }
        }
    }
```

- [ ] **Step 3: Save the current subtitle via `saveNow()` (restore-guarded).** Add the derived token and the `rememberUpdatedState` snapshots near the other `latest*` snapshots (around the existing `latestPositionMs` block):

```kotlin
    val subtitleToken = subtitleMemoryToken(state.selectedTextTrackId, selectedSubtitleUri)
    val latestSubtitleToken by rememberUpdatedState(subtitleToken)
    val latestSubtitleOffset by rememberUpdatedState(subtitleOffsetMs)
    val latestSubtitleRestored by rememberUpdatedState(subtitleRestored)
    val latestResolvedToken by rememberUpdatedState(resolved?.subtitleTrackId)
    val latestResolvedOffset by rememberUpdatedState(resolved?.subtitleOffsetMs ?: 0L)
```

In `saveNow()`, compute the values to save (before restore completes, re-write the *saved* token so an early save can't wipe it) and pass them to `persist`:

```kotlin
    fun saveNow() {
        if (!resumeApplied) return
        if (latestDurationMs <= 0L) return
        val speedToSave = if (latestBoost) 1f else latestSpeed
        val subTokenToSave = if (latestSubtitleRestored) latestSubtitleToken else latestResolvedToken
        val subOffsetToSave = if (latestSubtitleRestored) latestSubtitleOffset else latestResolvedOffset
        playerViewModel.persist(
            mediaUri = latestCurrentUri,
            positionMs = latestPositionMs,
            durationMs = latestDurationMs,
            speed = speedToSave,
            aspectMode = latestAspect.name,
            subtitleTrackId = subTokenToSave,
            subtitleOffsetMs = subOffsetToSave,
        )
    }
```

- [ ] **Step 4: Build + test** — `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:assembleDebug :app:testDebugUnitTest`. Expected: BUILD SUCCESSFUL; all app tests green (52).

- [ ] **Step 5: Commit**

```bash
git add app && git commit -m "feat(player): restore and save per-file subtitle selection + offset; harden embedded track selection"
```

---

### Task G4.4: Device smoke — per-file subtitle memory + whole-feature pass **[Android-verify]**

**Goal:** Confirm subtitle choice + offset persist across reopen for all three kinds (external/sibling, embedded, off), and run a final pass over the whole P1.G feature. Controller runs this after the code tasks pass review.

- [ ] **Step 1: Install + boot** (emulator from the memory file; clips `clipA.mp4`+`clipA.srt` sibling and `clipEmbed.mkv`+embedded English already pushed in G2/G3 — re-push if the AVD was wiped):

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:installDebug
/opt/homebrew/bin/adb shell am start -n com.videoplayer.app/.MainActivity
```

- [ ] **Step 2: Verify (pause first; resize screenshots `sips -Z 1600`, scale taps ×1.5):**
  - **A. External persists** — open clipA, CC → select sibling `clipA.srt`, nudge offset to e.g. +150ms; back to library; reopen clipA → the sibling is auto-selected (CC highlighted, cue renders) and the CC menu shows the saved offset.
  - **B. Embedded persists** — open clipEmbed.mkv, CC → select `English`; back; reopen clipEmbed → English auto-selected (renders via SubtitleView, checked in menu).
  - **C. Off persists** — with a sub active on a file, select `Off`; back; reopen → no subtitle, Off checked.
  - **D. Independence** — clipA's external choice and clipEmbed's embedded choice don't bleed into each other across reopen.
  - **E. Whole-feature regression** — SAF load still works; ±50ms nudge still shifts timing; mutual exclusion still holds; resume/locks/PiP/playlist/auto-advance/settings unaffected; no crash (`adb logcat -d | grep -i "AndroidRuntime.*com.videoplayer"`).

- [ ] **Step 3: Record the smoke result in the ledger; this closes P1.G.**

---

## Self-Review

- **Spec coverage (G-4 section):** persist/restore `subtitleTrackId` + `subtitleOffsetMs` ✅ (G4.2 + G4.3); memory encoding `null/off | embedded:<id> | ext:<uri>` ✅ (G4.1 tested); re-load cues for an `ext:` uri on open ✅ (restore sets `selectedSubtitleUri` → existing cue-load effect); re-select an embedded id when the track exists ✅ (restore waits on `state.textTracks`); full subtitle device smoke + whole-feature review ✅ (G4.4 + final review).
- **No clobber on restore race:** `saveNow` writes the *saved* token until `subtitleRestored`, then the current token. ✅
- **G-3 carry-forwards addressed:** optimistic `selectedTextTrackId` now matches the actual selection decision; resolution re-checks `isTrackSupported` (stale/unsupported remembered id ⇒ disabled). ✅
- **Placeholder scan:** concrete code in every step.
- **Type consistency:** `subtitleMemoryToken(embeddedTrackId, externalUri)` / `parseSubtitleToken` / `SubtitleSelection` used consistently; `ResolvedStartSettings` field order updated at its sole constructor (`resolveStart`); `persist`/`PlayerViewModel.persist` subtitle params defaulted so existing callers compile.
- **Purity:** token helpers pure in `:core:playback`; persistence/UI in `:app`. No new columns/migration/deps/permissions.
