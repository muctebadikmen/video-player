# Library UI Overhaul — Design

**Date:** 2026-06-20
**Branch:** `feat/ui-overhaul-thumbnails-folders-subs`
**Status:** Approved (forks confirmed with user)

## Problem

The library UI is hard to use:

1. **No thumbnails visible.** Code already has a `ThumbnailLoader` (MediaMetadataRetriever) wired into grid tiles and list rows, yet the user sees none — thumbnails fail to render, especially for SAF (folder-picked) `content://` tree URIs, and the default view is a text-heavy list. The user cannot tell what a video is without reading its name.
2. **No thumbnail size control.** Grid is fixed at `GridCells.Adaptive(140.dp)`; the user wants bigger/smaller.
3. **Folder structure is flattened.** When a folder with subfolders is picked via SAF, the UI (`FoldersContent`) renders every folder as a flat section header. The subfolder hierarchy is preserved in data (`MediaItem.folderPath`) but "dumped into one place" in the UI.
4. **Embedded subtitles are off by default.** `.mkv` files with embedded subtitle tracks require manual selection. Media3 only shows a sub if the stream pre-selects one.

## Decisions (confirmed with user)

| Fork | Decision |
|------|----------|
| Folder browsing | **Expandable tree** — folders shown inline with expand/collapse chevrons; subfolders nested with indentation; everything on one scroll. |
| Thumbnail resize | **Size button** in the top bar that cycles column counts (Small / Medium / Large). |
| Thumbnail engine | **Add Coil 3 + video-frame decoder.** Robust SAF support, disk caching (persist across launches), smooth scrolling. |
| Subtitle default | **Auto-on, first available** embedded text track. Respect the user's manual choice afterward. |

## Architecture & Components

The work splits into pure-core logic (unit-testable, no Android) and `:app` UI/engine wiring.

### A. Folder tree model — `:core:model` (pure, TDD)

New pure function + data type:

```kotlin
data class FolderNode(
    val name: String,
    val path: String,
    val items: List<MediaItem>,          // videos directly in THIS folder
    val children: List<FolderNode>,      // subfolders
) {
    val directVideoCount: Int
    val totalVideoCount: Int             // this folder + all descendants
}

fun buildFolderTree(folders: List<MediaFolder>): List<FolderNode>
```

Builds a hierarchy from the flat `folderPath` strings (split on `/`). Intermediate path
segments with no direct videos still become nodes so the tree is navigable. Roots are the
shallowest common segments. Deterministic ordering: folders by case-insensitive name, items
by existing sort. This is the single source of truth for the tree UI and is fully unit-tested
(empty, single folder, deep nesting, siblings, videos at multiple levels, path-prefix edge cases).

### B. Default subtitle selection — `:core:playback` (pure, TDD)

New pure helper:

```kotlin
fun pickDefaultTextTrack(
    tracks: List<TextTrackInfo>,
    currentlySelectedId: String?,
    userHasChosen: Boolean,
): String?   // track id to enable, or null to leave as-is
```

Rule: if `tracks` is non-empty, nothing is selected, and the user has not made a manual
choice for this media, return the **first** track's id; otherwise return null (leave as-is).
Unit-tested for: no tracks, already-selected, user-disabled (must not re-enable), fresh media.

### C. Coil 3 thumbnails — `:app`

- Add `coil-compose` + `coil-video` (Coil 3.x) to `gradle/libs.versions.toml` and `app/build.gradle.kts`.
- Add an `Application` subclass (`VideoPlayerApplication`) implementing `SingletonImageLoader.Factory`,
  registered in `AndroidManifest.xml`, returning an `ImageLoader` with `VideoFrameDecoder.Factory()`,
  a crossfade, and a bounded disk + memory cache.
- Replace `rememberThumbnail(uri)` + `Image(bitmap)` in `ThumbnailTile` and `MediaRow` with Coil
  `AsyncImage` driven by an `ImageRequest` (`data = uri`, `videoFrameMillis`, content-scale crop).
  Show a subtle placeholder while loading and a film-strip fallback icon on decode failure.
- Remove the now-orphaned `ThumbnailLoader.kt` / `rememberThumbnail` (directly replaced).

### D. Resizable grid — `:app`

- New `GridSize` enum (`SMALL`, `MEDIUM`, `LARGE`) → column counts (e.g. 4 / 3 / 2) via
  `GridCells.Fixed`, or adaptive min-widths. Default `MEDIUM`.
- Persist via the existing `SettingsRepository` / DataStore so it survives relaunch.
- Top-bar icon button cycles the size; grid (videos tab + tree video grids) reacts immediately.

### E. Expandable folder tree UI — `:app`

- Replace `FoldersContent` flat sections with a tree renderer driven by `buildFolderTree`.
- Maintain an `expandedPaths: Set<String>` (hoisted in the screen). A folder row shows a
  chevron, folder name, and total video count; tapping toggles expansion. Children render with
  left indentation proportional to depth. Videos in an expanded folder render as a thumbnail
  grid (respecting the grid-size setting) or list rows.
- Flatten the visible tree into a list of typed rows (`FolderRow` / `VideoRow`) for `LazyColumn`
  performance rather than nested scrollables. Search filtering keeps ancestor folders of matches.

### F. Subtitle auto-on wiring — `:app`

- `Media3PlaybackEngine` calls `pickDefaultTextTrack` inside `applyTracks` when tracks change.
  Track a per-media `userChoseTextTrack` flag (reset when a new media item is set; set true when
  the user calls `selectEmbeddedTextTrack`). When the helper returns an id, enable it via the
  existing override path. Ensure default `trackSelectionParameters` do not globally disable text.

### G. UI polish pass — `:app`

Focused, not a rewrite: rounded thumbnail corners, a duration chip overlaid on tiles, a bottom
gradient scrim so titles stay legible over bright frames, consistent spacing/typography, clear
empty + loading states, and a tidier top bar. Default the Videos tab to GRID so thumbnails are
visible immediately. Keep Material You + AMOLED theme intact.

## Data Flow

`MediaRepository` (MediaStore or SAF) → `List<MediaFolder>` → ViewModel (sort/search/progress)
→ **Folders tab:** `buildFolderTree` → flattened visible rows → tree UI; **Videos tab:** flat
grid. Thumbnails: `AsyncImage(uri)` → Coil `ImageLoader` (VideoFrameDecoder) → disk/memory cache.
Playback: `Media3PlaybackEngine.applyTracks` → `pickDefaultTextTrack` → enable embedded sub.

## Testing

- `:core:model` — `buildFolderTree` unit tests (JVM).
- `:core:playback` — `pickDefaultTextTrack` unit tests (JVM).
- `:app` — `./gradlew :app:assembleDebug` must build; manual emulator smoke (AVD `kuran_test`):
  thumbnails appear (MediaStore + SAF), size button changes columns and persists, folder tree
  expands/collapses with correct nesting, `.mkv` shows embedded subs automatically on open.

## Out of Scope

Favorite-language subtitle matching (chosen: first-available), Compose Navigation migration,
network image loading, libmpv engine work.

## Execution Order

1. Parallel pure-core: **A** (folder tree) + **B** (subtitle picker) — independent, TDD.
2. Sequential `:app` (all touch `LibraryScreen.kt` / engine — avoid merge collisions):
   **C** Coil → **D** grid size → **E** folder tree UI → **F** subtitle wiring → **G** polish.
3. Build, emulator smoke test, verify, commit per step, finish branch.
