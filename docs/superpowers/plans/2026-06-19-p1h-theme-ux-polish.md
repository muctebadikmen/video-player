# P1.H — Theme & UX Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Remove the remaining rough edges for a polished first F-Droid release: a proper library loading/empty/permission state, real (non-placeholder) icons without a heavy icon dependency, thumbnail accessibility, no brightness leak on player exit, and small code cleanups.

**Architecture:** All changes in `:app` (Compose UI + one ViewModel field). One pure testable seam (`libraryBodyState`); the rest is `[Android-verify]` (built via `:app:assembleDebug` + existing unit suites green, then emulator smoke). No `:core:*` change, no new dependencies, no new permissions.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), hand-authored `ImageVector`s.

## Global Constraints

- Build with JDK 21 — prefix every gradle call: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew ...`. Repo root: `/Users/mustafa/Desktop/Projects/mobil uygulama/video-player`.
- `:core:*` untouched; all changes in `:app`. No new dependencies (NO `material-icons-extended` — icons are hand-authored vectors). No new permissions.
- Must NOT regress P1.A–P1.G (player controls, gestures, resume, locks, PiP, background audio, playlist/auto-advance, settings, subtitles).
- Commit after every green step; device-verify on the `videoplayer` AVD.
- Spec: `docs/superpowers/specs/2026-06-19-p1h-theme-ux-polish-design.md`.

---

### Task H1: `libraryBodyState` pure helper (TDD)

**Files:**
- Create: `app/src/main/kotlin/com/videoplayer/app/library/LibraryBodyState.kt`
- Test: `app/src/test/kotlin/com/videoplayer/app/library/LibraryBodyStateTest.kt`

**Interfaces — Produces:** `enum class LibraryBodyState { PERMISSION, LOADING, EMPTY, CONTENT }` and `fun libraryBodyState(hasPermission: Boolean, isLoading: Boolean, hasAnyItems: Boolean): LibraryBodyState`.

- [ ] **Step 1: Write the failing test** (`LibraryBodyStateTest.kt`):

```kotlin
package com.videoplayer.app.library

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LibraryBodyStateTest {
    @Test fun `no permission shows permission regardless of loading or items`() {
        assertThat(libraryBodyState(hasPermission = false, isLoading = true, hasAnyItems = false))
            .isEqualTo(LibraryBodyState.PERMISSION)
        assertThat(libraryBodyState(hasPermission = false, isLoading = false, hasAnyItems = true))
            .isEqualTo(LibraryBodyState.PERMISSION)
    }
    @Test fun `granted and loading shows loading`() {
        assertThat(libraryBodyState(hasPermission = true, isLoading = true, hasAnyItems = false))
            .isEqualTo(LibraryBodyState.LOADING)
    }
    @Test fun `granted, loaded, no items shows empty`() {
        assertThat(libraryBodyState(hasPermission = true, isLoading = false, hasAnyItems = false))
            .isEqualTo(LibraryBodyState.EMPTY)
    }
    @Test fun `granted, loaded, with items shows content`() {
        assertThat(libraryBodyState(hasPermission = true, isLoading = false, hasAnyItems = true))
            .isEqualTo(LibraryBodyState.CONTENT)
    }
}
```

- [ ] **Step 2: Run to verify it fails** — `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest`. Expected: FAIL (unresolved).

- [ ] **Step 3: Implement** (`LibraryBodyState.kt`):

```kotlin
package com.videoplayer.app.library

/** Which body the library should render, after permission + loading + content are known. */
enum class LibraryBodyState { PERMISSION, LOADING, EMPTY, CONTENT }

/**
 * Resolve the library body to show. Permission takes priority (nothing loads without it),
 * then loading (scan in progress), then empty (scan complete, no items), else content.
 */
fun libraryBodyState(
    hasPermission: Boolean,
    isLoading: Boolean,
    hasAnyItems: Boolean,
): LibraryBodyState = when {
    !hasPermission -> LibraryBodyState.PERMISSION
    isLoading -> LibraryBodyState.LOADING
    !hasAnyItems -> LibraryBodyState.EMPTY
    else -> LibraryBodyState.CONTENT
}
```

- [ ] **Step 4: Run to verify pass** — `./gradlew :app:testDebugUnitTest`. Expected: PASS (4 new + all prior green).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/videoplayer/app/library/LibraryBodyState.kt app/src/test/kotlin/com/videoplayer/app/library/LibraryBodyStateTest.kt && git commit -m "feat(library): pure body-state helper (permission/loading/empty/content) with tests"
```

---

### Task H2: Library loading/permission/empty UI, real icons, thumbnail a11y

**Files:**
- Create: `app/src/main/kotlin/com/videoplayer/app/library/LibraryIcons.kt`
- Modify: `app/src/main/kotlin/com/videoplayer/app/library/LibraryViewModel.kt` (add `isLoading`)
- Modify: `app/src/main/kotlin/com/videoplayer/app/library/LibraryScreen.kt` (always-on top bar; body branch; permission CTA; spinner; icon swaps; thumbnail contentDescription)

**Interfaces — Consumes:** `libraryBodyState`/`LibraryBodyState` (H1). **Produces:** `GridViewIcon`/`SortIcon` ImageVectors; `LibraryUiState.isLoading`.

This task is **[Android-verify]** (green gate = `:app:assembleDebug` + `:app:testDebugUnitTest`).

- [ ] **Step 1: Create `LibraryIcons.kt`** — hand-authored vectors (avoids `material-icons-extended`):

```kotlin
package com.videoplayer.app.library

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** 2×2 grid glyph for the grid-view toggle (Icon applies the tint, so the fill color is a placeholder). */
val GridViewIcon: ImageVector = ImageVector.Builder(
    name = "GridView",
    defaultWidth = 24.dp, defaultHeight = 24.dp,
    viewportWidth = 24f, viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(4f, 4f); horizontalLineTo(10f); verticalLineTo(10f); horizontalLineTo(4f); close()
        moveTo(14f, 4f); horizontalLineTo(20f); verticalLineTo(10f); horizontalLineTo(14f); close()
        moveTo(4f, 14f); horizontalLineTo(10f); verticalLineTo(20f); horizontalLineTo(4f); close()
        moveTo(14f, 14f); horizontalLineTo(20f); verticalLineTo(20f); horizontalLineTo(14f); close()
    }
}.build()

/** Descending bars glyph for the sort menu. */
val SortIcon: ImageVector = ImageVector.Builder(
    name = "Sort",
    defaultWidth = 24.dp, defaultHeight = 24.dp,
    viewportWidth = 24f, viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(3f, 6f); horizontalLineTo(21f); verticalLineTo(8f); horizontalLineTo(3f); close()
        moveTo(3f, 11f); horizontalLineTo(15f); verticalLineTo(13f); horizontalLineTo(3f); close()
        moveTo(3f, 16f); horizontalLineTo(9f); verticalLineTo(18f); horizontalLineTo(3f); close()
    }
}.build()
```

- [ ] **Step 2: Add `isLoading` to `LibraryViewModel`** — add the field to `LibraryUiState`, a `loading` flow, widen the `combine` to 4 inputs, and clear loading after `refresh()`:

In `LibraryUiState` (after `query` or anywhere in the data class), add:
```kotlin
    val isLoading: Boolean = true,
```
Add the flow with the other private state (after `private val controls = ...`):
```kotlin
    private val loading = MutableStateFlow(true)
```
Change the `combine(...)` to include `loading` and set `isLoading` in the produced state:
```kotlin
    val uiState: StateFlow<LibraryUiState> =
        combine(mediaRepository.observeFolders(), memorySource.observeAll(), controls, loading) { folders, memory, c, isLoading ->
            val progressByUri = memory.associate { it.mediaUri to progressFraction(it.positionMs, it.durationMs) }
            val sorted = sortFoldersBy(folders, c.sortKey, c.sortOrder)
            val sortedFolders = sorted
                .map { it.copy(items = searchItems(it.items, c.query)) }
                .filter { it.items.isNotEmpty() }
            val videos = searchItems(allVideos(sorted), c.query)
            val itemByUri = allVideos(folders).associateBy { it.uri }
            val cw = continueWatching(memory.map { WatchProgress(it.mediaUri, it.positionMs, it.durationMs, it.updatedAtEpochMs) })
                .mapNotNull { wp -> itemByUri[wp.mediaUri]?.let { LibraryItemUi(it, progressFraction(wp.positionMs, wp.durationMs)) } }
            LibraryUiState(
                tab = c.tab, viewMode = c.viewMode, sortKey = c.sortKey, sortOrder = c.sortOrder, query = c.query,
                folders = sortedFolders, videos = videos, continueWatching = cw, progressByUri = progressByUri,
                isLoading = isLoading,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryUiState())
```
Change `refresh()` to clear loading when the scan completes:
```kotlin
    fun refresh() { viewModelScope.launch { mediaRepository.refresh(); loading.value = false } }
```

- [ ] **Step 3: Restructure `LibraryScreen` body** — always render the top bar; branch the body on `libraryBodyState`. Read the file first. Replace the early-return empty block (the `if (state.folders.isEmpty() && state.videos.isEmpty() && state.continueWatching.isEmpty()) { ... return }`) — DELETE it. Track permission as state and feed the branch.

Near the top of `LibraryScreen` (where `permissionLauncher`/`LaunchedEffect(Unit)` currently are), replace them with:
```kotlin
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, readVideoPermission) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        if (granted) viewModel.refresh()
    }
    LaunchedEffect(Unit) {
        if (hasPermission) viewModel.refresh() else permissionLauncher.launch(readVideoPermission)
    }
```

Then wrap the body. The `Column(modifier = modifier.fillMaxSize())` keeps `LibraryTopBar(...)` first (unchanged args), and everything below the top bar becomes a `when` on the body state:
```kotlin
    Column(modifier = modifier.fillMaxSize()) {
        LibraryTopBar(
            query = state.query,
            onQueryChange = viewModel::setQuery,
            sortKey = state.sortKey,
            sortOrder = state.sortOrder,
            onSortChange = viewModel::setSort,
            viewMode = state.viewMode,
            onToggleViewMode = {
                viewModel.setViewMode(if (state.viewMode == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST)
            },
            onOpenSettings = onOpenSettings,
        )
        val hasAnyItems = state.folders.isNotEmpty() || state.videos.isNotEmpty() || state.continueWatching.isNotEmpty()
        when (libraryBodyState(hasPermission, state.isLoading, hasAnyItems)) {
            LibraryBodyState.PERMISSION -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Allow access to your videos", style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = { permissionLauncher.launch(readVideoPermission) }) { Text("Grant access") }
                }
            }
            LibraryBodyState.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            LibraryBodyState.EMPTY -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No videos found", style = MaterialTheme.typography.bodyLarge)
            }
            LibraryBodyState.CONTENT -> {
                if (state.continueWatching.isNotEmpty()) {
                    ContinueWatchingRow(items = state.continueWatching, onItemClick = onItemClick)
                }
                TabRow(selectedTabIndex = state.tab.ordinal) {
                    Tab(
                        selected = state.tab == LibraryTab.FOLDERS,
                        onClick = { viewModel.setTab(LibraryTab.FOLDERS) },
                        text = { Text("Folders") },
                    )
                    Tab(
                        selected = state.tab == LibraryTab.VIDEOS,
                        onClick = { viewModel.setTab(LibraryTab.VIDEOS) },
                        text = { Text("Videos") },
                    )
                }
                when (state.tab) {
                    LibraryTab.FOLDERS -> FoldersContent(
                        folders = state.folders, viewMode = state.viewMode,
                        progress = state.progressByUri, onItemClick = onItemClick,
                    )
                    LibraryTab.VIDEOS -> VideosContent(
                        videos = state.videos, viewMode = state.viewMode,
                        progress = state.progressByUri, onItemClick = onItemClick,
                    )
                }
            }
        }
    }
```
Add the imports the compiler needs that aren't already present: `androidx.compose.material3.Button`, `androidx.compose.material3.CircularProgressIndicator`, `androidx.compose.foundation.layout.Arrangement`, `androidx.compose.foundation.layout.Column` (likely already there). Keep all existing imports.

- [ ] **Step 4: Swap the placeholder icons** in `LibraryTopBar`:
  - Sort button: replace `Icon(Icons.Default.MoreVert, contentDescription = "Sort")` with `Icon(SortIcon, contentDescription = "Sort")`.
  - Grid toggle: replace `Icon(Icons.Default.Menu, contentDescription = "Switch to grid view")` with `Icon(GridViewIcon, contentDescription = "Switch to grid view")`.
  - Leave the list-toggle `Icons.AutoMirrored.Filled.List`, `Search`, `Settings`, `Check` as-is. Remove the now-unused `Icons.Default.MoreVert`/`Icons.Default.Menu` imports if they are no longer referenced.

- [ ] **Step 5: Add thumbnail `contentDescription`** — the two thumbnail `Image`s currently pass `contentDescription = null`. Set each to the video's display name in scope (the tile/row composable has the `MediaItem`). Use `contentDescription = item.displayName` (or the equivalent item variable name in that composable). Leave decorative progress overlays null.

- [ ] **Step 6: Build + test** — `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:assembleDebug :app:testDebugUnitTest`. Expected: BUILD SUCCESSFUL; all tests green.

- [ ] **Step 7: Commit**

```bash
git add app && git commit -m "feat(library): loading/permission/empty states, real grid+sort icons, thumbnail a11y"
```

---

### Task H3: Player brightness reset + code cleanups

**Files:**
- Modify: `app/src/main/kotlin/com/videoplayer/app/player/PlayerScreen.kt` (brightness reset on dispose; FRAME_STEP_MS import; comment)
- Move: `app/src/main/kotlin/com/videoplayer/app/data/memory/ThumbnailLoader.kt` → `app/src/main/kotlin/com/videoplayer/app/library/ThumbnailLoader.kt`

This task is **[Android-verify]** (green gate = `:app:assembleDebug` + `:app:testDebugUnitTest`).

- [ ] **Step 1: Reset window brightness on player exit** — extend the existing dispose effect in `PlayerScreen`. Find:
```kotlin
    DisposableEffect(activity) {
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
```
Replace with:
```kotlin
    DisposableEffect(activity) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            // Release the brightness override so system-controlled brightness resumes outside the player.
            activity?.window?.let { w ->
                w.attributes = w.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
            }
        }
    }
```
Add the import `import android.view.WindowManager` if not present.

- [ ] **Step 2: Move `ThumbnailLoader`** — relocate the file from `app/src/main/kotlin/com/videoplayer/app/data/memory/ThumbnailLoader.kt` to `app/src/main/kotlin/com/videoplayer/app/library/ThumbnailLoader.kt`, change its package declaration to `package com.videoplayer.app.library`, and update every usage. Find usages first:
```bash
grep -rn "ThumbnailLoader\|rememberThumbnail\|data.memory.ThumbnailLoader" app/src/main/kotlin
```
Update any `import com.videoplayer.app.data.memory.ThumbnailLoader` (and `rememberThumbnail` if it lives in that file) to `com.videoplayer.app.library.*`. Files in the `library` package that use it no longer need an import. This is a pure move — no behavior change.

- [ ] **Step 3: Replace the FQN `FRAME_STEP_MS` references with an import** — find them:
```bash
grep -rn "FRAME_STEP_MS" app/src/main/kotlin
```
For any fully-qualified `com.videoplayer.core.playback.FRAME_STEP_MS` reference, add `import com.videoplayer.core.playback.FRAME_STEP_MS` at the top of that file and use the bare `FRAME_STEP_MS`. (If it is already imported and bare, skip.)

- [ ] **Step 4: Add the clarifying comment** — near the sleep-timer handling in `PlayerScreen` (the duration `sleepDeadlineMs` enforcement effect), add a one-line comment:
```kotlin
    // The duration sleep timer is wall-clock and spans auto-advance (unlike the per-file A–B loop,
    // which resets per media item).
```

- [ ] **Step 5: Build + test** — `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:assembleDebug :app:testDebugUnitTest`. Expected: BUILD SUCCESSFUL; all tests green.

- [ ] **Step 6: Commit**

```bash
git add app && git commit -m "fix(player): reset brightness on exit; chore: move ThumbnailLoader to library, tidy imports"
```

---

### Task H4: Device smoke **[Android-verify]**

Controller runs this after H1–H3 pass review.

- [ ] **Step 1: Install + launch** (videoplayer AVD; clear data once for the cold-start loading-state check):
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:installDebug
/opt/homebrew/bin/adb shell pm clear com.videoplayer.app
/opt/homebrew/bin/adb shell am start -n com.videoplayer.app/.MainActivity
```

- [ ] **Step 2: Verify (resize screenshots `sips -Z 1600`, scale taps ×1.5):**
  - **A. Cold start** — on first launch the permission prompt appears; before granting, a "Grant access" body shows (top bar visible, no "No videos found" flash). Grant → a brief spinner, then the library — never a premature "No videos found".
  - **B. Icons** — the sort button shows a descending-bars glyph and the grid toggle shows a 2×2 grid glyph (not the old hamburger/3-dots). Both still work (open sort menu; toggle list↔grid).
  - **C. Thumbnail a11y** — (optional TalkBack) thumbnails are described by their file name.
  - **D. Brightness reset** — in the player, drag brightness down on the left half; press Back to the library; confirm the library/system brightness is no longer dimmed (override released).
  - **E. No regression** — playback, subtitles, PiP, settings all still work; no crash (`adb logcat -d | grep -i "AndroidRuntime.*com.videoplayer"`).

- [ ] **Step 3: Record the smoke result in the ledger.**

---

## Self-Review

- **Spec coverage:** loading/empty/permission state ✅ (H1+H2); real icons w/o material-icons-extended ✅ (H2 hand-authored vectors); thumbnail a11y ✅ (H2.5); brightness reset ✅ (H3.1); ThumbnailLoader move ✅ (H3.2); FRAME_STEP_MS import + comment ✅ (H3.3/3.4). Deferred items (control-bar customization, theme picker, sleep-at-end one-shot, custom-overlay caption styles) are explicitly out of scope per the spec.
- **Placeholder scan:** concrete code in every code step; the two prose steps (icon swap, thumbnail contentDescription, ThumbnailLoader usage update) give the exact change + a grep to locate sites in the real file.
- **Type consistency:** `libraryBodyState(hasPermission, isLoading, hasAnyItems)` / `LibraryBodyState` used identically in H1 and H2; `LibraryUiState.isLoading` produced in H2.2 consumed in H2.3; `GridViewIcon`/`SortIcon` produced in H2.1 consumed in H2.4.
- **No new deps/permissions/core changes:** confirmed (hand-authored vectors; existing READ_MEDIA_VIDEO permission).
