# Library UI Overhaul Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the video library genuinely usable — real video thumbnails, a resizable grid, a navigable expandable folder tree, and embedded subtitles that turn on automatically.

**Architecture:** Pure, JVM-unit-tested logic lives in `:core:model` (folder tree) and `:core:playback` (default subtitle picker). The `:app` module wires Coil 3 for thumbnail loading, persists a grid-size preference, renders the folder tree, and calls the subtitle picker from `Media3PlaybackEngine`.

**Tech Stack:** Kotlin, Jetpack Compose (BOM 2024.12.01), Media3 1.5.1, Coil 3 (new), DataStore, JUnit (JVM unit tests).

## Global Constraints

- `:core:*` modules MUST NOT import `android.*`, `androidx.compose.*`, or `media3`. Android/Media3/Coil live only in `:app`.
- Builds require JDK 21: prefix every Gradle call with `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.
- JVM target 17, minSdk 24, targetSdk 35, compileSdk 35.
- Commit after every green step. Small, focused commits ending with `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.
- Match existing code style; surgical changes only.
- Subtitle default = first available embedded track; folder browsing = expandable tree; thumbnail resize = size button cycling columns.

---

### Task A: `buildFolderTree` in `:core:model` (pure, TDD)

**Files:**
- Create: `core/model/src/main/kotlin/com/videoplayer/core/model/FolderTree.kt`
- Test: `core/model/src/test/kotlin/com/videoplayer/core/model/FolderTreeTest.kt`

**Interfaces:**
- Consumes: `MediaItem` (`folderPath: String`, `displayName: String`), `MediaFolder` (`path`, `name`, `items`).
- Produces:
  ```kotlin
  data class FolderNode(
      val name: String,
      val path: String,
      val items: List<MediaItem>,        // videos whose folderPath == this.path
      val children: List<FolderNode>,
  ) {
      val directVideoCount: Int get() = items.size
      val totalVideoCount: Int          // items.size + sum of children.totalVideoCount
  }
  fun buildFolderTree(folders: List<MediaFolder>): List<FolderNode>
  ```

**Design notes:** Split each `MediaFolder.path` on `'/'` (drop empty leading segment). Build a trie of segments; attach a folder's `items` to its full-path node. Intermediate segments with no direct videos still become nodes. Roots = top-level segments. Sort children case-insensitively by `name`; preserve each folder's existing `items` order. `totalVideoCount` is recursive.

- [ ] **Step 1: Write failing tests**

```kotlin
package com.videoplayer.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class FolderTreeTest {
    private fun item(path: String, name: String) =
        MediaItem(id = name.hashCode().toLong(), uri = "u/$path/$name", displayName = name,
            folderPath = path, durationMs = 0, sizeBytes = 0, dateAddedSec = 0)

    private fun folder(path: String, vararg names: String) =
        MediaFolder(path = path, name = path.substringAfterLast('/'),
            items = names.map { item(path, it) })

    @Test fun empty() {
        assertEquals(emptyList(), buildFolderTree(emptyList()))
    }

    @Test fun singleFolderBecomesRoot() {
        val tree = buildFolderTree(listOf(folder("Movies", "a.mkv")))
        assertEquals(1, tree.size)
        assertEquals("Movies", tree[0].name)
        assertEquals(1, tree[0].directVideoCount)
        assertEquals(1, tree[0].totalVideoCount)
        assertEquals(emptyList(), tree[0].children)
    }

    @Test fun nestedSubfolderNestsUnderParent() {
        val tree = buildFolderTree(listOf(
            folder("Movies", "a.mkv"),
            folder("Movies/Action", "b.mkv", "c.mkv"),
        ))
        assertEquals(1, tree.size)
        val movies = tree[0]
        assertEquals("Movies", movies.name)
        assertEquals(1, movies.directVideoCount)
        assertEquals(3, movies.totalVideoCount)
        assertEquals(1, movies.children.size)
        assertEquals("Action", movies.children[0].name)
        assertEquals(2, movies.children[0].directVideoCount)
    }

    @Test fun intermediateSegmentWithoutVideosBecomesNode() {
        val tree = buildFolderTree(listOf(folder("A/B/C", "x.mkv")))
        assertEquals("A", tree[0].name)
        assertEquals(0, tree[0].directVideoCount)
        assertEquals(1, tree[0].totalVideoCount)
        assertEquals("B", tree[0].children[0].name)
        assertEquals("C", tree[0].children[0].children[0].name)
        assertEquals(1, tree[0].children[0].children[0].directVideoCount)
    }

    @Test fun siblingsSortedCaseInsensitively() {
        val tree = buildFolderTree(listOf(
            folder("zebra", "a"), folder("Apple", "b"), folder("mango", "c"),
        ))
        assertEquals(listOf("Apple", "mango", "zebra"), tree.map { it.name })
    }
}
```

- [ ] **Step 2: Run, verify FAIL**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :core:model:test --tests "com.videoplayer.core.model.FolderTreeTest"
```
Expected: FAIL (unresolved `buildFolderTree` / `FolderNode`).

- [ ] **Step 3: Implement `FolderTree.kt`**

```kotlin
package com.videoplayer.core.model

data class FolderNode(
    val name: String,
    val path: String,
    val items: List<MediaItem>,
    val children: List<FolderNode>,
) {
    val directVideoCount: Int get() = items.size
    val totalVideoCount: Int get() = items.size + children.sumOf { it.totalVideoCount }
}

private class MutableNode(val name: String, val path: String) {
    var items: List<MediaItem> = emptyList()
    val children = LinkedHashMap<String, MutableNode>()
    fun toNode(): FolderNode = FolderNode(
        name = name,
        path = path,
        items = items,
        children = children.values
            .map { it.toNode() }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }),
    )
}

fun buildFolderTree(folders: List<MediaFolder>): List<FolderNode> {
    val roots = LinkedHashMap<String, MutableNode>()
    for (folder in folders) {
        val segments = folder.path.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) continue
        var levelMap = roots
        var accPath = ""
        var node: MutableNode? = null
        for (segment in segments) {
            accPath = if (accPath.isEmpty()) segment else "$accPath/$segment"
            node = levelMap.getOrPut(segment) { MutableNode(segment, accPath) }
            levelMap = node.children
        }
        node?.items = folder.items
    }
    return roots.values
        .map { it.toNode() }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
}
```

- [ ] **Step 4: Run, verify PASS**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :core:model:test --tests "com.videoplayer.core.model.FolderTreeTest"
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/model/src/main/kotlin/com/videoplayer/core/model/FolderTree.kt core/model/src/test/kotlin/com/videoplayer/core/model/FolderTreeTest.kt
git commit -m "feat(core): buildFolderTree builds nested folder hierarchy

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task B: `pickDefaultTextTrack` in `:core:playback` (pure, TDD)

**Files:**
- Create: `core/playback/src/main/kotlin/com/videoplayer/core/playback/DefaultTextTrack.kt`
- Test: `core/playback/src/test/kotlin/com/videoplayer/core/playback/DefaultTextTrackTest.kt`

**Interfaces:**
- Consumes: `TextTrackInfo` (in `Subtitle.kt`: `id`, `label`, `language`).
- Produces: `fun pickDefaultTextTrack(tracks: List<TextTrackInfo>, currentlySelectedId: String?, userHasChosen: Boolean): String?`

**Rule:** return `tracks.first().id` only when `tracks` is non-empty AND `currentlySelectedId == null` AND `!userHasChosen`; otherwise `null`.

- [ ] **Step 1: Write failing tests**

```kotlin
package com.videoplayer.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DefaultTextTrackTest {
    private fun t(id: String) = TextTrackInfo(id = id, label = id, language = null)

    @Test fun noTracksReturnsNull() =
        assertNull(pickDefaultTextTrack(emptyList(), null, false))

    @Test fun freshMediaPicksFirst() =
        assertEquals("text:0:0", pickDefaultTextTrack(listOf(t("text:0:0"), t("text:0:1")), null, false))

    @Test fun alreadySelectedReturnsNull() =
        assertNull(pickDefaultTextTrack(listOf(t("text:0:0")), "text:0:0", false))

    @Test fun userDisabledNotReEnabled() =
        assertNull(pickDefaultTextTrack(listOf(t("text:0:0")), null, true))
}
```

- [ ] **Step 2: Run, verify FAIL**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :core:playback:test --tests "com.videoplayer.core.playback.DefaultTextTrackTest"
```
Expected: FAIL (unresolved reference).

- [ ] **Step 3: Implement**

```kotlin
package com.videoplayer.core.playback

/**
 * Picks the embedded text track to enable by default.
 * Returns the first available track only for fresh media the user hasn't
 * touched and where nothing is already selected; otherwise null (leave as-is).
 */
fun pickDefaultTextTrack(
    tracks: List<TextTrackInfo>,
    currentlySelectedId: String?,
    userHasChosen: Boolean,
): String? {
    if (tracks.isEmpty()) return null
    if (currentlySelectedId != null) return null
    if (userHasChosen) return null
    return tracks.first().id
}
```

- [ ] **Step 4: Run, verify PASS**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :core:playback:test --tests "com.videoplayer.core.playback.DefaultTextTrackTest"
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/playback/src/main/kotlin/com/videoplayer/core/playback/DefaultTextTrack.kt core/playback/src/test/kotlin/com/videoplayer/core/playback/DefaultTextTrackTest.kt
git commit -m "feat(core): pickDefaultTextTrack selects first embedded subtitle

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task C: Coil 3 thumbnails in `:app`

**Files:**
- Modify: `gradle/libs.versions.toml` (add Coil 3 versions + libraries)
- Modify: `app/build.gradle.kts` (add Coil deps)
- Create: `app/src/main/kotlin/com/videoplayer/app/VideoPlayerApplication.kt`
- Modify: `app/src/main/AndroidManifest.xml` (register Application via `android:name`)
- Modify: `app/src/main/kotlin/com/videoplayer/app/library/LibraryScreen.kt` (`ThumbnailTile`, `MediaRow`)
- Delete (orphaned by this task): `app/src/main/kotlin/com/videoplayer/app/library/ThumbnailLoader.kt`

**Interfaces:**
- Produces: a Compose `VideoThumbnail(uri: String, modifier: Modifier)` helper usable by tiles/rows.

**Design notes:** Coil 3 with `coil-video`'s `VideoFrameDecoder` decodes a frame from `content://`/`file://` video URIs. The `ImageLoader` is provided app-wide via `SingletonImageLoader.Factory` on the `Application`. Use the latest stable Coil 3.x release; if a pinned version fails to resolve, bump to the newest 3.x on Maven Central and note it.

- [ ] **Step 1: Add versions + libraries to `gradle/libs.versions.toml`**

Under `[versions]` add:
```toml
coil = "3.0.4"
```
Under `[libraries]` add:
```toml
coil-compose = { group = "io.coil-kt.coil3", name = "coil-compose", version.ref = "coil" }
coil-video = { group = "io.coil-kt.coil3", name = "coil-video", version.ref = "coil" }
```

- [ ] **Step 2: Add deps to `app/build.gradle.kts`**

In the `dependencies { }` block, near the other `implementation(...)` lines:
```kotlin
implementation(libs.coil.compose)
implementation(libs.coil.video)
```

- [ ] **Step 3: Create `VideoPlayerApplication.kt`**

```kotlin
package com.videoplayer.app

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder

class VideoPlayerApplication : Application(), SingletonImageLoader.Factory {
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(VideoFrameDecoder.Factory()) }
            .crossfade(true)
            .memoryCache {
                MemoryCache.Builder().maxSizePercent(context, 0.25).build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("video_thumbnails"))
                    .maxSizeBytes(128L * 1024 * 1024)
                    .build()
            }
            .build()
}
```

- [ ] **Step 4: Register Application in `AndroidManifest.xml`**

On the `<application>` tag add `android:name=".VideoPlayerApplication"` (keep all existing attributes).

- [ ] **Step 5: Replace thumbnail rendering in `LibraryScreen.kt`**

Add imports:
```kotlin
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.video.videoFrameMillis
```
Add a shared composable (place near the other private composables):
```kotlin
@Composable
private fun VideoThumbnail(uri: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(uri)
            .videoFrameMillis(1_000)
            .crossfade(true)
            .build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}
```
In `ThumbnailTile` (currently ~line 416-466): replace the `rememberThumbnail(item.uri)` + `Image(bitmap = ...)` block with `VideoThumbnail(item.uri, Modifier.fillMaxSize())` inside the existing thumbnail `Box`. Keep the existing fallback/placeholder Box behind it (a `Surface`/`Box` with the film-strip icon) so failures still show something. In `MediaRow` (~line 469-523): same replacement at the 80dp thumbnail.

- [ ] **Step 6: Delete orphaned `ThumbnailLoader.kt`**

```bash
git rm app/src/main/kotlin/com/videoplayer/app/library/ThumbnailLoader.kt
```
Then remove any now-unused `rememberThumbnail` / `asImageBitmap` imports from `LibraryScreen.kt`.

- [ ] **Step 7: Build, verify success**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL. If Coil 3.0.4 fails to resolve, set `coil` to the newest stable 3.x and rebuild.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat(library): Coil 3 video-frame thumbnails with disk cache

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task D: Resizable grid (size button + persistence) in `:app`

**Files:**
- Modify: `app/src/main/kotlin/com/videoplayer/app/library/LibraryViewModel.kt` (add `GridSize`, controls field, setter)
- Modify: `app/src/main/kotlin/com/videoplayer/app/data/memory/SettingsRepository.kt` (persist grid size) — verify exact path first
- Modify: `app/src/main/kotlin/com/videoplayer/app/library/LibraryScreen.kt` (size button + apply columns)

**Interfaces:**
- Produces: `enum class GridSize { SMALL, MEDIUM, LARGE }` with `val columns: Int` (SMALL=4, MEDIUM=3, LARGE=2); `LibraryUiState.gridSize`; `LibraryViewModel.cycleGridSize()`.

**Design notes:** First inspect `SettingsRepository` to follow its existing DataStore key pattern; mirror it for an int/string `gridSize` key. Load the persisted value when the ViewModel initializes its `controls`. Larger tiles = fewer columns.

- [ ] **Step 1: Add `GridSize` enum + state**

In `LibraryViewModel.kt`, near `enum class ViewMode`:
```kotlin
enum class GridSize(val columns: Int) { SMALL(4), MEDIUM(3), LARGE(2) }
```
Add `val gridSize: GridSize = GridSize.MEDIUM` to both the `Controls` data class and `LibraryUiState`, and propagate it in the `combine { ... }` mapping (copy it from controls into the emitted ui state).

- [ ] **Step 2: Add cycle setter + persistence**

In `LibraryViewModel`:
```kotlin
fun cycleGridSize() {
    val next = when (controls.value.gridSize) {
        GridSize.SMALL -> GridSize.MEDIUM
        GridSize.MEDIUM -> GridSize.LARGE
        GridSize.LARGE -> GridSize.SMALL
    }
    controls.value = controls.value.copy(gridSize = next)
    viewModelScope.launch { settings.setGridColumns(next.columns) }
}
```
Add `suspend fun setGridColumns(columns: Int)` + a `gridColumns` read to `SettingsRepository` following its existing DataStore pattern, and load the saved value into the initial `controls` (default MEDIUM if unset). Wire the `SettingsRepository` instance into `LibraryViewModel`'s constructor if not already present (it already receives one via `PlaybackMemoryRepository`; pass a direct `SettingsRepository` if needed and update the `viewModel { }` factory in `VideoPlayerApp.kt`).

- [ ] **Step 3: Add the size button + apply columns in `LibraryScreen.kt`**

In `LibraryTopBar`, add an `IconButton` (use `Icons.Default.GridView` / `Icons.Default.Apps` or an existing grid icon) calling `onCycleGridSize()`; thread that lambda from `LibraryScreen` → `viewModel.cycleGridSize()`. In `VideosContent` and the tree video grids, change `GridCells.Adaptive(140.dp)` / `Fixed(...)` to `GridCells.Fixed(uiState.gridSize.columns)`.

- [ ] **Step 4: Build, verify success**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(library): grid size button cycling columns, persisted

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task E: Expandable folder tree UI in `:app`

**Files:**
- Modify: `app/src/main/kotlin/com/videoplayer/app/library/LibraryScreen.kt` (replace `FoldersContent`)
- Possibly Create: `app/src/main/kotlin/com/videoplayer/app/library/FolderTreeContent.kt` (if `LibraryScreen.kt` grows unwieldy, extract the tree composables here)

**Interfaces:**
- Consumes: `buildFolderTree(folders)` and `FolderNode` from Task A; `uiState.folders` (List<MediaFolder>), `uiState.gridSize`.
- Produces: expandable tree UI replacing flat folder sections.

**Design notes:** Compute `buildFolderTree(uiState.folders)` with `remember(uiState.folders)`. Hoist `var expandedPaths by rememberSaveable { mutableStateOf(setOf<String>()) }` (use a `Set<String>` saver, or store as a sorted joined string). Flatten visible nodes (only descend into expanded paths) into a `List<TreeRow>` where `TreeRow` is `Folder(node, depth)` or `Videos(node, depth)`. Render in the existing `LazyColumn`. Folder rows: `start` padding = `depth * 16.dp`, a rotating chevron (`Icons.Default.ChevronRight` rotated 90° when expanded), folder name, and `node.totalVideoCount`. Tapping toggles membership in `expandedPaths`. When expanded and `node.items` is non-empty, emit a `Videos` row that lays the items out as a thumbnail grid (`FlowRow` or a fixed-height grid) using `gridSize.columns`, indented under the folder. Search: when a query is active, keep folders that contain matching descendants and auto-expand them.

- [ ] **Step 1: Define the row model + flattening helper (top of file or new file)**

```kotlin
private sealed interface TreeRow {
    val depth: Int
    data class Folder(val node: FolderNode, override val depth: Int, val expanded: Boolean) : TreeRow
    data class Videos(val node: FolderNode, override val depth: Int) : TreeRow
}

private fun flattenTree(
    nodes: List<FolderNode>,
    expanded: Set<String>,
    depth: Int = 0,
): List<TreeRow> = buildList {
    for (node in nodes) {
        val isExpanded = node.path in expanded
        add(TreeRow.Folder(node, depth, isExpanded))
        if (isExpanded) {
            if (node.items.isNotEmpty()) add(TreeRow.Videos(node, depth + 1))
            addAll(flattenTree(node.children, expanded, depth + 1))
        }
    }
}
```

- [ ] **Step 2: Replace `FoldersContent` body**

Build the tree + flatten + render. Skeleton:
```kotlin
@Composable
private fun FoldersContent(
    folders: List<MediaFolder>,
    gridSize: GridSize,
    onPlay: (MediaItem) -> Unit,
    progressByUri: Map<String, Float>,
) {
    val tree = remember(folders) { buildFolderTree(folders) }
    var expanded by rememberSaveable(
        saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList().toMutableSet() })
    ) { mutableStateOf(mutableSetOf<String>()) }
    val rows = remember(tree, expanded.toSet()) { flattenTree(tree, expanded) }

    LazyColumn(Modifier.fillMaxSize()) {
        items(rows, key = { row ->
            when (row) { is TreeRow.Folder -> "f:${row.node.path}"; is TreeRow.Videos -> "v:${row.node.path}" }
        }) { row ->
            when (row) {
                is TreeRow.Folder -> FolderRow(
                    node = row.node, depth = row.depth, expanded = row.expanded,
                    onToggle = {
                        expanded = expanded.toMutableSet().apply {
                            if (!add(row.node.path)) remove(row.node.path)
                        }
                    },
                )
                is TreeRow.Videos -> VideoGridRow(row.node.items, row.depth, gridSize, onPlay, progressByUri)
            }
        }
    }
}
```
Implement `FolderRow` (indented row with rotating chevron, folder icon, name, count) and `VideoGridRow` (a `FlowRow` of `ThumbnailTile`s indented by `depth`, columns from `gridSize`). Use existing `ThumbnailTile`. Update the call site in `LibraryScreen` to pass `gridSize` and `progressByUri`.

- [ ] **Step 3: Build, verify success**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat(library): expandable folder tree replacing flat sections

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task F: Subtitle auto-on wiring in `:app`

**Files:**
- Modify: `app/src/main/kotlin/com/videoplayer/app/engine/Media3PlaybackEngine.kt`

**Interfaces:**
- Consumes: `pickDefaultTextTrack(tracks, currentlySelectedId, userHasChosen)` from Task B.

**Design notes:** Add `private var userChoseTextTrack = false`. Reset it to `false` wherever a new media item is loaded/set (find the `setMediaItem`/`prepare`/`open` path). In `selectEmbeddedTextTrack(id)` set `userChoseTextTrack = true` (the user made a choice — including disabling). In `applyTracks`, after computing `infos` and `selectedId`, call:
```kotlin
val autoId = pickDefaultTextTrack(infos, selectedId, userChoseTextTrack)
if (autoId != null) {
    selectEmbeddedTextTrack(autoId)   // enables it; note: this sets userChoseTextTrack=true
    // so guard: enable without flipping the user flag — see step
}
```
Because `selectEmbeddedTextTrack` sets the user flag, extract the actual override application into a private `applyTextTrackOverride(id: String?)` that both the public method and the auto path call; only the public method sets `userChoseTextTrack = true`. Ensure base `trackSelectionParameters` do not set `setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)` globally.

- [ ] **Step 1: Refactor override into private helper + add flag**

Extract the body of `selectEmbeddedTextTrack` (the override-building logic) into `private fun applyTextTrackOverride(id: String?)`. Make the public `override fun selectEmbeddedTextTrack(id: String?)` set `userChoseTextTrack = true` then call `applyTextTrackOverride(id)`. Add `private var userChoseTextTrack = false` and reset it `= false` in the media-load path.

- [ ] **Step 2: Call the picker in `applyTracks`**

After `_state.update { ... textTracks/selectedTextTrackId ... }`:
```kotlin
val autoId = pickDefaultTextTrack(infos, selectedId, userChoseTextTrack)
if (autoId != null) applyTextTrackOverride(autoId)
```

- [ ] **Step 3: Build, verify success**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat(player): auto-enable first embedded subtitle on open

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task G: UI polish pass in `:app`

**Files:**
- Modify: `app/src/main/kotlin/com/videoplayer/app/library/LibraryScreen.kt`
- Modify: `app/src/main/kotlin/com/videoplayer/app/library/LibraryViewModel.kt` (default Videos tab to GRID)

**Design notes:** Surgical visual improvements, no behavior changes beyond defaults.

- [ ] **Step 1: Polish tiles**

In `ThumbnailTile`: wrap the thumbnail in a `Card`/`Surface` with `RoundedCornerShape(12.dp)` and `clip`. Overlay a duration chip (bottom-end, small rounded `Surface` with `formatDuration(item.durationMs)`, semi-transparent background). Add a bottom gradient scrim (`Brush.verticalGradient`) behind the title so it stays legible over bright frames. Keep the resume progress bar. Title: `maxLines = 2`, `TextOverflow.Ellipsis`, `MaterialTheme.typography.bodyMedium`.

- [ ] **Step 2: Defaults + empty/loading states**

Set the default `viewMode = ViewMode.GRID` in `Controls`/`LibraryUiState` so thumbnails show immediately. Ensure empty-folder and loading states render a friendly centered message + icon (reuse existing `LibraryBodyState` if present).

- [ ] **Step 3: Build, verify success**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat(library): polished tiles, duration chips, grid-default

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task H: Full verification + finish

- [ ] **Step 1: Full unit test suite**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew test
```
Expected: all PASS.

- [ ] **Step 2: Build + install on emulator**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:installDebug
```
Boot AVD `kuran_test` if not running. Launch and verify via screencap:
- Thumbnails appear for MediaStore videos and for a SAF-picked folder.
- Size button changes columns and survives app restart.
- Folder tree expands/collapses with correct nesting + counts.
- Opening an `.mkv` with an embedded subtitle shows the subtitle automatically.

- [ ] **Step 3: Report results, then finish branch**

Summarize verification evidence. Then use `superpowers:finishing-a-development-branch` to present merge/PR options.

## Self-Review

- **Spec coverage:** Thumbnails→C; resize→D; folder tree→A+E; subtitles→B+F; polish→G; verify→H. All spec sections mapped.
- **Type consistency:** `FolderNode`/`buildFolderTree` (A) used in E; `pickDefaultTextTrack` (B) used in F; `GridSize.columns` (D) used in D+E; `VideoThumbnail` (C) reused by tiles.
- **Constraints:** `:core:*` tasks (A,B) add no Android imports. Coil/Media3 only in `:app`. JDK 21 prefix on every Gradle command.
