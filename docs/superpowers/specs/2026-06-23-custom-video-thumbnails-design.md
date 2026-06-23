# Custom & Smart Video Thumbnails — Design Spec

- **Date:** 2026-06-23
- **Status:** Approved (design); pending implementation plan
- **Topic:** Per-video thumbnails — fix black defaults + manual override
- **Author:** Orchestrated session (brainstorming → design)

---

## 1. Problem

The library shows a **black thumbnail for most videos**. The current implementation
(`app/.../library/LibraryScreen.kt`, `VideoThumbnail`) always extracts the frame at a
hardcoded `videoFrameMillis(1_000)` (1 second). Any video with a black intro, fade-in,
or leader card renders black. There is no fallback, no black-frame detection, and no way
for the user to choose a better frame.

The user wants two things:

1. **Not see black by default** — the app should pick a *good* frame on its own.
2. **Set the perfect thumbnail per video** — manually choose a frame (from the library
   or while watching), and have that choice stick to that video forever.

## 2. Goals

- Videos the user never touches get a **good automatic thumbnail** (skip black/blank frames).
- The user can **manually set a custom thumbnail** for any video via:
  - **Library:** long-press a video → frame picker (sampled grid + fine scrub).
  - **Player:** a "Set as thumbnail" action that captures the exact frame being watched.
- A manual choice **always wins** over the auto-default and **persists** across restarts.
- The user can **reset** a custom thumbnail back to automatic.
- All decision logic (which frame is "best") is a **pure, unit-tested function**.

## 3. Non-goals (YAGNI)

- No sharing/exporting thumbnails.
- No batch "regenerate all thumbnails."
- No thumbnail editing (crop, filter, rotate) — only *pick a frame*.
- No eager generation across the whole library — auto-defaults are computed **lazily**,
  only for videos actually displayed on screen.
- No changes to the `PlaybackEngine` interface or the engine seam.

## 4. Architecture overview

Two layers, sharing one persistence table and one frame-extraction utility:

```
            ┌──────────────────────────────────────────────┐
            │  :core:model  (pure Kotlin, JVM-tested)        │
            │    ThumbnailScoring.pickBestFrame(stats)       │
            └──────────────────────────────────────────────┘
                              ▲ FrameStats(avgLuminance, variance)
                              │
┌───────────────────────────────────────────────────────────────────────┐
│  :app                                                                   │
│                                                                         │
│  FrameExtractor (MediaMetadataRetriever)                                │
│    - sampleStats(uri, points) → List<FrameStats>   (smart default)      │
│    - extractFrame(uri, ms, w, h) → Bitmap?         (picker / capture)   │
│    - saveThumbnail(uri, bitmap) → filePath         (manual override)    │
│                                                                         │
│  ThumbnailRepository  +  Room table `video_thumbnail` (key = mediaUri)  │
│    - ensureAutoThumbnail(uri, durationMs)                               │
│    - setCustomThumbnail(uri, bitmap)                                    │
│    - resetToAuto(uri)                                                   │
│    - observeAll(): Flow<Map<uri, ThumbnailSpec>>                        │
│                                                                         │
│  LibraryViewModel.thumbnailByUri: StateFlow<Map<String, ThumbnailSpec>> │
│    (mirrors the existing progressByUri map)                             │
│                                                                         │
│  UI:                                                                     │
│    VideoThumbnail (resolves spec → Coil model)                          │
│    ThumbnailPickerSheet (ModalBottomSheet: grid + fine scrub)           │
│    Library long-press entry · Player "Set as thumbnail" action          │
└───────────────────────────────────────────────────────────────────────┘
```

### Key technical decision

For the **auto-default**, store the *chosen timestamp* in Room and let Coil decode +
disk-cache it (reuses the existing Coil video pipeline, tiny disk footprint, and keeps the
scoring logic a pure testable function). For the **manual override**, save the *actual
extracted JPEG* to internal storage (exact WYSIWYG frame, survives Coil cache clears).

Rejected alternatives:
- *Custom Coil decoder* — elegant caching but couples to Coil 3 internals and is hard to unit-test.
- *Save a JPEG for every video* — simple display but heavy disk + eager work across a big library.

## 5. Data model & persistence

New Room entity, keyed by `mediaUri` (the content URI string, exactly like
`PlaybackMemoryEntity`). Lives in a **new table** (a video can have a thumbnail without
ever being played; keeping it separate from playback memory is cleaner).

```kotlin
@Entity(tableName = "video_thumbnail")
data class VideoThumbnailEntity(
    @PrimaryKey val mediaUri: String,
    val customThumbnailPath: String? = null, // internal file path to a saved JPEG (manual)
    val autoFrameMs: Long? = null,           // smart-chosen default timestamp (computed once)
    val autoResolved: Boolean = false,        // true once auto compute ran (success OR fallback)
    val updatedAtEpochMs: Long = 0L,          // for Coil cache-busting on re-set
)
```

- **Database:** existing `video_player.db`, currently version **2** → bump to **3**.
- **Migration 2→3:** `CREATE TABLE video_thumbnail (...)`. Additive, no data loss.
- **DAO:** `upsert`, `getByUri(uri)`, `observeAll(): Flow<List<VideoThumbnailEntity>>`.
- `autoResolved` prevents infinite recompute for unreadable / audio-only videos
  (we store the fallback and mark resolved).

### Resolution precedence (display)

For each `mediaUri`:
1. `customThumbnailPath != null` and file exists → **load that file** (manual override).
2. else `autoResolved && autoFrameMs != null` → **Coil `videoFrameMillis(autoFrameMs)`**.
3. else → **placeholder icon** + trigger `ensureAutoThumbnail(uri, durationMs)` in background.

`ThumbnailSpec` is a small UI-facing sealed type the ViewModel emits:
`Custom(path, updatedAt)` | `AutoFrame(uri, frameMs)` | `Pending`.

## 6. Frame extraction (`FrameExtractor`, :app)

Thin wrapper over `MediaMetadataRetriever` (already used in the repo for durations —
`ExternalVideo.kt`, `SafFolderRepository.kt`). minSdk 24 / compileSdk 35.

- `getScaledFrameAtTime(timeUs, OPTION_CLOSEST_SYNC, w, h)` on API 27+;
  fallback `getFrameAtTime(timeUs, OPTION_CLOSEST_SYNC)` + manual `Bitmap.createScaledBitmap`
  on API 24–26.
- All calls on `Dispatchers.IO`, wrapped in try/catch/finally with `retriever.release()`.
- **Sampling for scoring:** decode tiny bitmaps (~80×45) at sample points so scoring is fast.
- **Saving:** `Bitmap.compress(JPEG, ~85)` to `filesDir/thumbnails/<hash(uri)>-<updatedAt>.jpg`;
  write the new file, persist its path, then delete the previous file (atomic-ish swap that
  also dodges stale Coil cache because the filename changes).

### Smart default algorithm

1. Sample frames at fractions **15%, 30%, 50%, 70%, 85%** of duration (skip extreme ends).
2. For each, compute `FrameStats(avgLuminance, variance)` from the tiny bitmap.
3. `ThumbnailScoring.pickBestFrame(stats)` returns the index of the best:
   - **penalize** near-black (`avgLuminance` below a low threshold),
   - **penalize** flat/uniform frames (very low `variance` — e.g. solid color / blank),
   - **reward** mid-range luminance + higher variance (detail).
4. Persist the winning `autoFrameMs`, set `autoResolved = true`.
5. On total failure (MMR throws, no video track), persist a fallback
   `autoFrameMs = min(1000, durationMs/2)`, `autoResolved = true` (no retry loop).

`pickBestFrame` + `FrameStats` live in **`:core:model`** (pure, no Android) so the
heuristic is unit-tested without an emulator — consistent with `formatDuration` /
`groupIntoFolders` already living there.

### Concurrency

`ThumbnailRepository.ensureAutoThumbnail` is **idempotent + deduped**: it no-ops if a
record is already resolved or a compute is in-flight for that uri, and limits concurrent
MMR work via a semaphore (e.g. 2–3) so scrolling a large library never spawns dozens of
retrievers.

## 7. UI

### 7.1 Display — `VideoThumbnail`

Takes the resolved `ThumbnailSpec` (from `LibraryViewModel.thumbnailByUri`) and builds the
Coil request accordingly. On `Pending`, shows the existing placeholder icon and calls
`viewModel.ensureThumbnail(uri, durationMs)` from a `LaunchedEffect(uri)` so compute only
fires for on-screen tiles. Coil request includes `updatedAt` in the cache key for custom
thumbnails so a re-set refreshes immediately. Applies to both `ThumbnailTile` (grid /
continue-watching) and `MediaRow` (list).

### 7.2 Library entry — long-press

Add a long-press handler to `ThumbnailTile` and `MediaRow` (via `combinedClickable`).
Long-press opens `ThumbnailPickerSheet` for that `MediaItem`. Single tap still plays.

### 7.3 `ThumbnailPickerSheet` (ModalBottomSheet)

Reuses the established `ModalBottomSheet` pattern (`SubtitleSearchSheet`).

- **Candidate grid:** ~9 evenly-spaced frames (`LazyVerticalGrid`/`LazyRow`), each a Coil
  `AsyncImage` with `videoFrameMillis(offset)`; placeholders while decoding.
- **Fine scrub:** tapping a candidate reveals a `Slider` (live preview frame) so the user
  can nudge to the exact moment. Preview updates are debounced; the preview frame loads via
  Coil at the scrub ms.
- **Confirm:** extracts that exact frame via `FrameExtractor.extractFrame`, saves JPEG, and
  calls `setCustomThumbnail(uri, bitmap)`.
- **Reset to automatic:** shown when a custom thumbnail already exists → `resetToAuto(uri)`
  (clears the column and deletes the file).

### 7.4 Player entry — "Set as thumbnail"

Add an action to the player controls overflow (the existing `DropdownMenu` pattern in
`PlayerControls.kt`). It reads `state.positionMs` from the engine, extracts that exact frame
via `FrameExtractor`, saves + persists, and confirms with a brief snackbar/toast. No
`PlaybackEngine` changes — position is already exposed, which avoids the difficult Media3
`PlayerView` surface-capture path.

## 8. Error handling

- Every MMR access is guarded; failures fall back to a safe frame and mark `autoResolved`
  so we never loop.
- Audio-only / unreadable videos keep the placeholder icon (current behavior).
- Manual save uses a fresh filename + delete-old to avoid serving a stale cached bitmap.
- Semaphore-bounded sampling protects against retriever exhaustion while scrolling.

## 9. Testing strategy (TDD)

- **Pure unit tests (`:core:model`)** on `pickBestFrame` / `FrameStats`:
  - all-black candidates → falls back / lowest score not chosen,
  - uniform white or flat color → penalized,
  - textured mid-luminance frame → chosen,
  - tie/ordering behavior is deterministic.
- **Repository tests (`:app`)** — `setCustomThumbnail` / `resetToAuto` / `ensureAutoThumbnail`
  idempotency + precedence, following the existing `PlaybackMemoryRepositoryTest` pattern
  (fake DAO / in-memory).
- **Manual emulator smoke** for the two UI flows (library long-press picker, player capture)
  and to confirm black thumbnails are gone for sample videos.
- `./gradlew test` green before completion (JDK 21 / JBR per CLAUDE.md).

## 10. File-level change list

**New**
- `core/model/.../ThumbnailScoring.kt` (+ `FrameStats`) — pure scoring.
- `core/model/src/test/.../ThumbnailScoringTest.kt` — pure unit tests.
- `app/.../thumbnail/FrameExtractor.kt` — MMR sampling / extraction / save.
- `app/.../data/memory/VideoThumbnailEntity.kt` — Room entity.
- `app/.../data/memory/VideoThumbnailDao.kt` — DAO.
- `app/.../thumbnail/ThumbnailRepository.kt` — orchestration + concurrency.
- `app/.../thumbnail/ThumbnailSpec.kt` — UI-facing resolved type.
- `app/.../player/ThumbnailPickerSheet.kt` — picker UI.
- `app/.../data/memory/ThumbnailRepositoryTest.kt` — repo tests.

**Modified**
- `app/.../data/memory/AppDatabase.kt` — add entity, bump v2→v3, `MIGRATION_2_3`.
- `app/.../VideoPlayerApp.kt` (or DI wiring) — construct repo, pass to ViewModels.
- `app/.../library/LibraryViewModel.kt` — `thumbnailByUri` StateFlow + `ensureThumbnail`.
- `app/.../library/LibraryScreen.kt` — `VideoThumbnail` resolver, long-press, picker host.
- `app/.../player/PlayerControls.kt` — "Set as thumbnail" action.
- `app/.../player/PlayerScreen.kt` / `PlayerViewModel.kt` — wire capture action.

## 11. Risks & mitigations

- **MMR cost on scroll** → lazy + deduped + semaphore-bounded; result cached in Room + Coil.
- **Stale Coil cache after re-set** → versioned filename + `updatedAt` cache key.
- **API 24–26 lacks `getScaledFrameAtTime`** → `getFrameAtTime` + manual scale fallback.
- **Content/SAF URIs** → `MMR.setDataSource(context, uri)` handles both (already proven in repo).
- **Black-frame heuristic false positives** (e.g. legitimately dark videos) → manual override
  always available; thresholds tuned conservatively and unit-tested.

## 12. Rollout

Single feature branch off `main`, TDD per component, checkpoint commit after each green
step, emulator smoke, then finish per `finishing-a-development-branch`.
