# P1.D — Library Browser Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the bare folder-grouped list into a real library browser: a tabbed Folders/Videos view with a list⇄grid toggle, thumbnails + per-item resume-progress on every item, a "Continue watching" row of recently-played unfinished videos, and sort + search — all powered by the P1.C resume data.

**Architecture:** Pure list logic (sort/search/flatten/next-file) lives in `:core:model`; continue-watching selection + progress math lives in `:core:playback` (it reuses `effectiveResumePosition`). The `:app` layer adds a reactive `observeAll()` to the Room DAO, a `ThumbnailLoader` (built-in `MediaMetadataRetriever` + `LruCache`), and reworks `LibraryViewModel` to `combine` folders + persisted memory + UI control state into one `LibraryUiState`, which a reworked `LibraryScreen` renders. Auto-advance (playing the next file when one ends) is a separate follow-up plan.

**Tech Stack:** Kotlin 2.0.21 · Jetpack Compose (Material3) · Room 2.6.1 (Flow queries via room-ktx, already present) · Android `MediaMetadataRetriever` + `android.util.LruCache` (no new dependency) · JUnit4 + Truth + Robolectric + kotlinx-coroutines-test + Turbine (tests).

## Global Constraints

- **Platform:** Native Android, Kotlin + Jetpack Compose only.
- **Core is UI-agnostic:** `:core:model` and `:core:playback` MUST NOT import `android.*`, `androidx.*`, `media3`, Room, or DataStore. Continue-watching logic reuses `com.videoplayer.core.playback.effectiveResumePosition`.
- **No new dependencies.** Thumbnails use `android.media.MediaMetadataRetriever` + `android.util.LruCache` (both built-in). Do NOT add Glide/Coil/Media3 thumbnail libs.
- **Privacy:** zero telemetry/network. Thumbnails are decoded locally from the user's own media.
- **Build:** JDK 21 via `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"` before every `./gradlew`. minSdk 24 / targetSdk 35 / compileSdk 35.
- **Existing types to reuse (do not redefine):** `MediaItem(id, uri, displayName, folderPath, durationMs, sizeBytes, dateAddedSec)`, `MediaFolder(path, name, items)`, `MediaRepository` (`:core:model`); `PlaybackMemoryEntity(mediaUri, positionMs, durationMs, aspectMode, speed, updatedAtEpochMs, …)`, `PlaybackMemoryDao`, `PlaybackMemoryRepository`, `AppDatabase` (`:app`); `effectiveResumePosition` (`:core:playback`).
- **Commit after every green step.** Conventional commits.
- **TDD:** pure logic (`:core:*`) and DAO/VM logic = real red→green with unit/Robolectric tests. Thumbnail decoding + the Compose screen = `[Android-verify]` (assembleDebug + emulator).

---

## File Structure

**Created:**
- `core/model/src/main/kotlin/com/videoplayer/core/model/LibrarySort.kt` — `SortKey`, `SortOrder`, `sortItems`, `sortFoldersBy`, `searchItems`, `allVideos`, `nextInFolder`.
- `core/model/src/test/kotlin/com/videoplayer/core/model/LibrarySortTest.kt`
- `core/playback/src/main/kotlin/com/videoplayer/core/playback/ContinueWatching.kt` — `WatchProgress`, `progressFraction`, `continueWatching`.
- `core/playback/src/test/kotlin/com/videoplayer/core/playback/ContinueWatchingTest.kt`
- `app/src/main/kotlin/com/videoplayer/app/data/memory/ThumbnailLoader.kt`
- `app/src/test/kotlin/com/videoplayer/app/data/memory/PlaybackMemoryObserveAllTest.kt`
- `app/src/test/kotlin/com/videoplayer/app/library/LibraryViewModelStateTest.kt`

**Modified:**
- `app/src/main/kotlin/com/videoplayer/app/data/memory/PlaybackMemoryDao.kt` — add `observeAll(): Flow<List<PlaybackMemoryEntity>>`.
- `app/src/main/kotlin/com/videoplayer/app/data/memory/PlaybackMemoryRepository.kt` — add `observeAll()` passthrough.
- `app/src/main/kotlin/com/videoplayer/app/library/LibraryViewModel.kt` — combine folders + memory + control state into `LibraryUiState`.
- `app/src/main/kotlin/com/videoplayer/app/library/LibraryScreen.kt` — tabs, view toggle, sort menu, search, continue-watching row, thumbnails + progress.
- `app/src/main/kotlin/com/videoplayer/app/VideoPlayerApp.kt` — construct `LibraryViewModel` with both repositories.

**Deferred (separate plan):** auto-advance to next file on video end (needs the player to receive a playlist/context, not a single item).

---

## Task D1: Library sort / search / flatten / next-file (`:core:model`) [TDD]

**Files:**
- Create: `core/model/src/main/kotlin/com/videoplayer/core/model/LibrarySort.kt`
- Test: `core/model/src/test/kotlin/com/videoplayer/core/model/LibrarySortTest.kt`

**Interfaces:**
- Produces:
  - `enum class SortKey { NAME, DATE_ADDED, DURATION }`
  - `enum class SortOrder { ASC, DESC }`
  - `fun sortItems(items: List<MediaItem>, key: SortKey, order: SortOrder): List<MediaItem>`
  - `fun sortFoldersBy(folders: List<MediaFolder>, key: SortKey, order: SortOrder): List<MediaFolder>`
  - `fun searchItems(items: List<MediaItem>, query: String): List<MediaItem>`
  - `fun allVideos(folders: List<MediaFolder>): List<MediaItem>`
  - `fun nextInFolder(folderItems: List<MediaItem>, currentUri: String): MediaItem?`

- [ ] **Step 1: Write the failing tests**

`core/model/src/test/kotlin/com/videoplayer/core/model/LibrarySortTest.kt`:
```kotlin
package com.videoplayer.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LibrarySortTest {
    private fun item(name: String, date: Long = 0, dur: Long = 0) =
        MediaItem(name.hashCode().toLong(), "uri/$name", name, "/f", dur, 0, date)

    @Test fun `sortItems by name ascending is case-insensitive`() {
        val r = sortItems(listOf(item("banana"), item("Apple"), item("cherry")), SortKey.NAME, SortOrder.ASC)
        assertThat(r.map { it.displayName }).containsExactly("Apple", "banana", "cherry").inOrder()
    }
    @Test fun `sortItems by name descending`() {
        val r = sortItems(listOf(item("a"), item("b"), item("c")), SortKey.NAME, SortOrder.DESC)
        assertThat(r.map { it.displayName }).containsExactly("c", "b", "a").inOrder()
    }
    @Test fun `sortItems by date added ascending`() {
        val r = sortItems(listOf(item("a", date = 30), item("b", date = 10), item("c", date = 20)), SortKey.DATE_ADDED, SortOrder.ASC)
        assertThat(r.map { it.displayName }).containsExactly("b", "c", "a").inOrder()
    }
    @Test fun `sortItems by duration descending`() {
        val r = sortItems(listOf(item("a", dur = 100), item("b", dur = 300), item("c", dur = 200)), SortKey.DURATION, SortOrder.DESC)
        assertThat(r.map { it.displayName }).containsExactly("b", "c", "a").inOrder()
    }
    @Test fun `searchItems matches displayName case-insensitively`() {
        val r = searchItems(listOf(item("Holiday.mp4"), item("work.mkv"), item("HOLIDAY2.mp4")), "holiday")
        assertThat(r.map { it.displayName }).containsExactly("Holiday.mp4", "HOLIDAY2.mp4")
    }
    @Test fun `searchItems blank query returns all`() {
        val all = listOf(item("a"), item("b"))
        assertThat(searchItems(all, "   ")).isEqualTo(all)
    }
    @Test fun `allVideos flattens folders preserving folder order`() {
        val f1 = MediaFolder("/x", "x", listOf(item("a"), item("b")))
        val f2 = MediaFolder("/y", "y", listOf(item("c")))
        assertThat(allVideos(listOf(f1, f2)).map { it.displayName }).containsExactly("a", "b", "c").inOrder()
    }
    @Test fun `sortFoldersBy sorts each folder's items but keeps folders by name`() {
        val f1 = MediaFolder("/y", "y", listOf(item("b"), item("a")))
        val f2 = MediaFolder("/x", "x", listOf(item("d"), item("c")))
        val r = sortFoldersBy(listOf(f1, f2), SortKey.NAME, SortOrder.ASC)
        assertThat(r.map { it.name }).containsExactly("x", "y").inOrder()
        assertThat(r.first { it.name == "x" }.items.map { it.displayName }).containsExactly("c", "d").inOrder()
    }
    @Test fun `nextInFolder returns following item`() {
        val items = listOf(item("a"), item("b"), item("c"))
        assertThat(nextInFolder(items, "uri/b")?.displayName).isEqualTo("c")
    }
    @Test fun `nextInFolder returns null for last item`() {
        val items = listOf(item("a"), item("b"))
        assertThat(nextInFolder(items, "uri/b")).isNull()
    }
    @Test fun `nextInFolder returns null when uri not present`() {
        assertThat(nextInFolder(listOf(item("a")), "uri/zzz")).isNull()
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :core:model:test`
Expected: FAIL — symbols unresolved.

- [ ] **Step 3: Implement `LibrarySort.kt`**

```kotlin
package com.videoplayer.core.model

/** What to order library items by. */
enum class SortKey { NAME, DATE_ADDED, DURATION }

/** Ordering direction. */
enum class SortOrder { ASC, DESC }

/** Returns [items] ordered by [key] in [order]. Name sort is case-insensitive. */
fun sortItems(items: List<MediaItem>, key: SortKey, order: SortOrder): List<MediaItem> {
    val base: Comparator<MediaItem> = when (key) {
        SortKey.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }
        SortKey.DATE_ADDED -> compareBy { it.dateAddedSec }
        SortKey.DURATION -> compareBy { it.durationMs }
    }
    val comparator = if (order == SortOrder.ASC) base else base.reversed()
    return items.sortedWith(comparator)
}

/** Sorts each folder's items by [key]/[order]; the folder list itself stays ordered by name. */
fun sortFoldersBy(folders: List<MediaFolder>, key: SortKey, order: SortOrder): List<MediaFolder> =
    folders
        .map { it.copy(items = sortItems(it.items, key, order)) }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

/** Case-insensitive substring match on [MediaItem.displayName]. Blank query returns [items] unchanged. */
fun searchItems(items: List<MediaItem>, query: String): List<MediaItem> {
    val q = query.trim()
    if (q.isEmpty()) return items
    return items.filter { it.displayName.contains(q, ignoreCase = true) }
}

/** Flattens all folders' items into one list, preserving folder then item order. */
fun allVideos(folders: List<MediaFolder>): List<MediaItem> = folders.flatMap { it.items }

/** The item after the one with [currentUri] in [folderItems], or null if it is last / not found. */
fun nextInFolder(folderItems: List<MediaItem>, currentUri: String): MediaItem? {
    val idx = folderItems.indexOfFirst { it.uri == currentUri }
    if (idx < 0 || idx >= folderItems.lastIndex) return null
    return folderItems[idx + 1]
}
```
> `MediaFolder` is a `data class`, so `.copy(items = …)` is available.

- [ ] **Step 4: Run to verify pass**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :core:model:test`
Expected: PASS (all LibrarySortTest + pre-existing model tests).

- [ ] **Step 5: Commit**

```bash
git add core/model
git commit -m "feat(core): library sort/search/flatten and next-file helpers with tests"
```

---

## Task D2: Continue-watching selection + progress (`:core:playback`) [TDD]

**Files:**
- Create: `core/playback/src/main/kotlin/com/videoplayer/core/playback/ContinueWatching.kt`
- Test: `core/playback/src/test/kotlin/com/videoplayer/core/playback/ContinueWatchingTest.kt`

**Interfaces:**
- Consumes: `effectiveResumePosition` (same package).
- Produces:
  - `data class WatchProgress(val mediaUri: String, val positionMs: Long, val durationMs: Long, val updatedAtEpochMs: Long)`
  - `fun progressFraction(positionMs: Long, durationMs: Long): Float`
  - `fun continueWatching(entries: List<WatchProgress>, limit: Int = 20): List<WatchProgress>`

- [ ] **Step 1: Write the failing tests**

`core/playback/src/test/kotlin/com/videoplayer/core/playback/ContinueWatchingTest.kt`:
```kotlin
package com.videoplayer.core.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ContinueWatchingTest {
    @Test fun `progressFraction is position over duration`() {
        assertThat(progressFraction(30_000, 120_000)).isWithin(0.0001f).of(0.25f)
    }
    @Test fun `progressFraction clamps to 0..1 and handles unknown duration`() {
        assertThat(progressFraction(50, 0)).isEqualTo(0f)
        assertThat(progressFraction(200, 100)).isEqualTo(1f)
        assertThat(progressFraction(-5, 100)).isEqualTo(0f)
    }
    @Test fun `continueWatching keeps only resumable entries`() {
        val entries = listOf(
            WatchProgress("a", positionMs = 30_000, durationMs = 120_000, updatedAtEpochMs = 1), // resumable
            WatchProgress("b", positionMs = 1_000, durationMs = 120_000, updatedAtEpochMs = 2),   // too early -> drop
            WatchProgress("c", positionMs = 119_000, durationMs = 120_000, updatedAtEpochMs = 3), // near end -> drop
        )
        assertThat(continueWatching(entries).map { it.mediaUri }).containsExactly("a")
    }
    @Test fun `continueWatching orders by most recently updated`() {
        val entries = listOf(
            WatchProgress("a", 30_000, 120_000, updatedAtEpochMs = 10),
            WatchProgress("b", 40_000, 120_000, updatedAtEpochMs = 30),
            WatchProgress("c", 50_000, 120_000, updatedAtEpochMs = 20),
        )
        assertThat(continueWatching(entries).map { it.mediaUri }).containsExactly("b", "c", "a").inOrder()
    }
    @Test fun `continueWatching respects the limit`() {
        val entries = (1..30).map { WatchProgress("u$it", 30_000, 120_000, updatedAtEpochMs = it.toLong()) }
        assertThat(continueWatching(entries, limit = 5)).hasSize(5)
    }
}
```

- [ ] **Step 2: Run to verify fail**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :core:playback:test`
Expected: FAIL — symbols unresolved.

- [ ] **Step 3: Implement `ContinueWatching.kt`**

```kotlin
package com.videoplayer.core.playback

/** A persisted playback position for one media item, as needed by the library's continue-watching row. */
data class WatchProgress(
    val mediaUri: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAtEpochMs: Long,
)

/** Fraction watched in 0f..1f. Returns 0f when duration is unknown (<= 0). */
fun progressFraction(positionMs: Long, durationMs: Long): Float {
    if (durationMs <= 0L) return 0f
    return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
}

/**
 * The "continue watching" list: entries that are actually resumable (per [effectiveResumePosition] —
 * i.e. not too-early and not finished), most-recently-updated first, capped at [limit].
 */
fun continueWatching(entries: List<WatchProgress>, limit: Int = 20): List<WatchProgress> =
    entries
        .filter { effectiveResumePosition(it.positionMs, it.durationMs) > 0L }
        .sortedByDescending { it.updatedAtEpochMs }
        .take(limit)
```

- [ ] **Step 4: Run to verify pass**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :core:playback:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/playback
git commit -m "feat(core): continue-watching selection and progress fraction with tests"
```

---

## Task D3: Reactive `observeAll` on the memory DAO + repository [TDD]

**Files:**
- Modify: `app/src/main/kotlin/com/videoplayer/app/data/memory/PlaybackMemoryDao.kt`
- Modify: `app/src/main/kotlin/com/videoplayer/app/data/memory/PlaybackMemoryRepository.kt`
- Test: `app/src/test/kotlin/com/videoplayer/app/data/memory/PlaybackMemoryObserveAllTest.kt`

**Interfaces:**
- Produces:
  - `PlaybackMemoryDao.observeAll(): Flow<List<PlaybackMemoryEntity>>`
  - `PlaybackMemoryRepository.observeAll(): Flow<List<PlaybackMemoryEntity>>`

- [ ] **Step 1: Write the failing test**

`app/src/test/kotlin/com/videoplayer/app/data/memory/PlaybackMemoryObserveAllTest.kt`:
```kotlin
package com.videoplayer.app.data.memory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaybackMemoryObserveAllTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: PlaybackMemoryDao

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        dao = db.playbackMemoryDao()
    }

    @After fun tearDown() { db.close() }

    @Test fun `observeAll emits empty then the inserted rows`() = runTest {
        assertThat(dao.observeAll().first()).isEmpty()
        dao.upsert(PlaybackMemoryEntity("a", 1, 2, "FIT", 1f, 10))
        dao.upsert(PlaybackMemoryEntity("b", 3, 4, "FILL", 1f, 20))
        assertThat(dao.observeAll().first().map { it.mediaUri }).containsExactly("a", "b")
    }
}
```

- [ ] **Step 2: Run to verify fail**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.videoplayer.app.data.memory.PlaybackMemoryObserveAllTest"`
Expected: FAIL — `observeAll` unresolved.

- [ ] **Step 3: Add `observeAll` to the DAO**

In `PlaybackMemoryDao.kt`, add the import `import kotlinx.coroutines.flow.Flow` and the method:
```kotlin
    @Query("SELECT * FROM playback_memory")
    fun observeAll(): Flow<List<PlaybackMemoryEntity>>
```

- [ ] **Step 4: Add the repository passthrough**

In `PlaybackMemoryRepository.kt`, add the import `import kotlinx.coroutines.flow.Flow` and the method (anywhere in the class body):
```kotlin
    /** Observes every persisted memory row — used by the library for progress + continue-watching. */
    fun observeAll(): Flow<List<PlaybackMemoryEntity>> = dao.observeAll()
```

- [ ] **Step 5: Run to verify pass**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.videoplayer.app.data.memory.PlaybackMemoryObserveAllTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/videoplayer/app/data/memory app/src/test/kotlin/com/videoplayer/app/data/memory/PlaybackMemoryObserveAllTest.kt
git commit -m "feat(app): observe all playback-memory rows reactively (DAO + repository)"
```

---

## Task D4: ThumbnailLoader (MediaMetadataRetriever + LruCache) [Android-verify]

**Files:**
- Create: `app/src/main/kotlin/com/videoplayer/app/data/memory/ThumbnailLoader.kt`

**Interfaces:**
- Produces:
  - `object ThumbnailLoader { suspend fun load(context: Context, uri: String): Bitmap? }`
  - `@Composable fun rememberThumbnail(uri: String): Bitmap?`

- [ ] **Step 1: Implement `ThumbnailLoader.kt`**

```kotlin
package com.videoplayer.app.data.memory

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decodes a representative video frame as a thumbnail, off the main thread, with a small
 * in-memory LRU cache keyed by URI. Uses the built-in [MediaMetadataRetriever] — no image
 * library dependency. Returns null if the frame can't be read.
 */
object ThumbnailLoader {
    private const val MAX_CACHE_ENTRIES = 256
    private const val FRAME_TIME_US = 1_000_000L // 1s in — usually past black intros

    private val cache = object : LruCache<String, Bitmap>(MAX_CACHE_ENTRIES) {}

    suspend fun load(context: Context, uri: String): Bitmap? {
        cache.get(uri)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, Uri.parse(uri))
                retriever.getFrameAtTime(FRAME_TIME_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } catch (e: Exception) {
                null
            } finally {
                runCatching { retriever.release() }
            }
        }
        if (bitmap != null) cache.put(uri, bitmap)
        return bitmap
    }
}

/** Loads (and caches) the thumbnail for [uri], returning null until it is ready or if it fails. */
@Composable
fun rememberThumbnail(uri: String): Bitmap? {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uri) { bitmap = ThumbnailLoader.load(context, uri) }
    return bitmap
}
```

- [ ] **Step 2: [Android-verify] Build**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. (Visual verification happens in D6 on the emulator.)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/videoplayer/app/data/memory/ThumbnailLoader.kt
git commit -m "feat(app): video thumbnail loader via MediaMetadataRetriever with LRU cache"
```

---

## Task D5: LibraryViewModel → LibraryUiState (combine folders + memory + controls) [TDD]

**Files:**
- Modify: `app/src/main/kotlin/com/videoplayer/app/library/LibraryViewModel.kt`
- Modify: `app/src/main/kotlin/com/videoplayer/app/VideoPlayerApp.kt`
- Test: `app/src/test/kotlin/com/videoplayer/app/library/LibraryViewModelStateTest.kt`

**Interfaces:**
- Consumes: `MediaRepository`, `PlaybackMemoryRepository.observeAll()` (D3), `sortFoldersBy`/`allVideos`/`searchItems` (D1), `continueWatching`/`progressFraction`/`WatchProgress` (D2), `MediaItem`/`MediaFolder`.
- Produces:
  - `enum class LibraryTab { FOLDERS, VIDEOS }`
  - `enum class ViewMode { LIST, GRID }`
  - `data class LibraryItemUi(val item: MediaItem, val progress: Float)`
  - `data class LibraryUiState(val tab, val viewMode, val sortKey, val sortOrder, val query, val folders: List<MediaFolder>, val videos: List<MediaItem>, val continueWatching: List<LibraryItemUi>, val progressByUri: Map<String, Float>)`
  - `class LibraryViewModel(mediaRepository, memoryRepository)` with `val uiState: StateFlow<LibraryUiState>` and setters: `refresh()`, `setTab(LibraryTab)`, `setViewMode(ViewMode)`, `setSort(SortKey, SortOrder)`, `setQuery(String)`.

- [ ] **Step 1: Write the failing test**

`app/src/test/kotlin/com/videoplayer/app/library/LibraryViewModelStateTest.kt`:
```kotlin
package com.videoplayer.app.library

import com.google.common.truth.Truth.assertThat
import com.videoplayer.app.MainDispatcherRule
import com.videoplayer.app.data.memory.PlaybackMemoryEntity
import com.videoplayer.core.model.MediaFolder
import com.videoplayer.core.model.MediaItem
import com.videoplayer.core.model.SortKey
import com.videoplayer.core.model.SortOrder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelStateTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private fun item(name: String, uri: String = "uri/$name", dur: Long = 120_000) =
        MediaItem(name.hashCode().toLong(), uri, name, "/f", dur, 0, 0)

    @Test fun `uiState exposes sorted videos and per-item progress`() = runTest {
        val folders = listOf(MediaFolder("/f", "f", listOf(item("b.mp4"), item("a.mp4"))))
        val memory = listOf(PlaybackMemoryEntity("uri/a.mp4", 30_000, 120_000, "FIT", 1f, 5))
        val vm = LibraryViewModel(FakeMediaRepository(folders), FakeMemorySource(memory))
        vm.refresh()
        advanceUntilIdle()
        val s = vm.uiState.value
        assertThat(s.videos.map { it.displayName }).containsExactly("a.mp4", "b.mp4").inOrder()
        assertThat(s.progressByUri["uri/a.mp4"]).isWithin(0.0001f).of(0.25f)
        assertThat(s.continueWatching.map { it.item.displayName }).containsExactly("a.mp4")
    }

    @Test fun `setQuery filters videos`() = runTest {
        val folders = listOf(MediaFolder("/f", "f", listOf(item("holiday.mp4"), item("work.mkv"))))
        val vm = LibraryViewModel(FakeMediaRepository(folders), FakeMemorySource(emptyList()))
        vm.refresh(); advanceUntilIdle()
        vm.setQuery("holi"); advanceUntilIdle()
        assertThat(vm.uiState.value.videos.map { it.displayName }).containsExactly("holiday.mp4")
    }

    @Test fun `setSort reorders videos`() = runTest {
        val folders = listOf(MediaFolder("/f", "f", listOf(item("a.mp4"), item("b.mp4"))))
        val vm = LibraryViewModel(FakeMediaRepository(folders), FakeMemorySource(emptyList()))
        vm.refresh(); advanceUntilIdle()
        vm.setSort(SortKey.NAME, SortOrder.DESC); advanceUntilIdle()
        assertThat(vm.uiState.value.videos.map { it.displayName }).containsExactly("b.mp4", "a.mp4").inOrder()
    }
}
```
This task also needs a `FakeMemorySource` test double. To keep the VM testable without Room, the VM consumes a narrow interface rather than the concrete repository:

Define (in main, D5 Step 3) `interface MemorySource { fun observeAll(): Flow<List<PlaybackMemoryEntity>> }`, make `PlaybackMemoryRepository` implement it, and have the VM depend on `MemorySource`. The test double:
```kotlin
// app/src/test/kotlin/com/videoplayer/app/library/FakeMemorySource.kt
package com.videoplayer.app.library
import com.videoplayer.app.data.memory.MemorySource
import com.videoplayer.app.data.memory.PlaybackMemoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
class FakeMemorySource(private val rows: List<PlaybackMemoryEntity>) : MemorySource {
    override fun observeAll(): Flow<List<PlaybackMemoryEntity>> = flowOf(rows)
}
```
(`FakeMediaRepository` already exists in `app/src/test/.../library/`.)

- [ ] **Step 2: Run to verify fail**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.videoplayer.app.library.LibraryViewModelStateTest"`
Expected: FAIL — `LibraryUiState`/`LibraryViewModel(…, MemorySource)`/`MemorySource` unresolved.

- [ ] **Step 3: Add the `MemorySource` seam**

In `PlaybackMemoryRepository.kt`, define the interface and implement it:
```kotlin
/** Narrow read seam the library depends on, so it can be faked in tests without Room. */
interface MemorySource {
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<PlaybackMemoryEntity>>
}
```
Change the class declaration to `class PlaybackMemoryRepository(...) : MemorySource {` and annotate the existing `observeAll()` with `override`.

- [ ] **Step 4: Rewrite `LibraryViewModel.kt`**

```kotlin
package com.videoplayer.app.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.videoplayer.app.data.memory.MemorySource
import com.videoplayer.core.model.MediaFolder
import com.videoplayer.core.model.MediaItem
import com.videoplayer.core.model.MediaRepository
import com.videoplayer.core.model.SortKey
import com.videoplayer.core.model.SortOrder
import com.videoplayer.core.model.allVideos
import com.videoplayer.core.model.searchItems
import com.videoplayer.core.model.sortFoldersBy
import com.videoplayer.core.playback.WatchProgress
import com.videoplayer.core.playback.continueWatching
import com.videoplayer.core.playback.progressFraction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class LibraryTab { FOLDERS, VIDEOS }
enum class ViewMode { LIST, GRID }

/** One library row/tile: the media plus its 0..1 resume progress. */
data class LibraryItemUi(val item: MediaItem, val progress: Float)

data class LibraryUiState(
    val tab: LibraryTab = LibraryTab.FOLDERS,
    val viewMode: ViewMode = ViewMode.LIST,
    val sortKey: SortKey = SortKey.NAME,
    val sortOrder: SortOrder = SortOrder.ASC,
    val query: String = "",
    val folders: List<MediaFolder> = emptyList(),
    val videos: List<MediaItem> = emptyList(),
    val continueWatching: List<LibraryItemUi> = emptyList(),
    val progressByUri: Map<String, Float> = emptyMap(),
)

private data class Controls(
    val tab: LibraryTab = LibraryTab.FOLDERS,
    val viewMode: ViewMode = ViewMode.LIST,
    val sortKey: SortKey = SortKey.NAME,
    val sortOrder: SortOrder = SortOrder.ASC,
    val query: String = "",
)

/**
 * Combines the media library, persisted playback memory, and UI control state (tab / view mode /
 * sort / search) into a single [LibraryUiState]. Sorting + search are applied by pure :core helpers.
 */
class LibraryViewModel(
    private val mediaRepository: MediaRepository,
    memorySource: MemorySource,
) : ViewModel() {

    private val controls = MutableStateFlow(Controls())

    val uiState: StateFlow<LibraryUiState> =
        combine(mediaRepository.observeFolders(), memorySource.observeAll(), controls) { folders, memory, c ->
            val progressByUri = memory.associate { it.mediaUri to progressFraction(it.positionMs, it.durationMs) }
            val sortedFolders = sortFoldersBy(folders, c.sortKey, c.sortOrder)
                .map { it.copy(items = searchItems(it.items, c.query)) }
                .filter { it.items.isNotEmpty() }
            val videos = searchItems(allVideos(sortFoldersBy(folders, c.sortKey, c.sortOrder)), c.query)
            val itemByUri = allVideos(folders).associateBy { it.uri }
            val cw = continueWatching(memory.map { WatchProgress(it.mediaUri, it.positionMs, it.durationMs, it.updatedAtEpochMs) })
                .mapNotNull { wp -> itemByUri[wp.mediaUri]?.let { LibraryItemUi(it, progressFraction(wp.positionMs, wp.durationMs)) } }
            LibraryUiState(
                tab = c.tab, viewMode = c.viewMode, sortKey = c.sortKey, sortOrder = c.sortOrder, query = c.query,
                folders = sortedFolders, videos = videos, continueWatching = cw, progressByUri = progressByUri,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryUiState())

    fun refresh() { viewModelScope.launch { mediaRepository.refresh() } }
    fun setTab(tab: LibraryTab) { controls.value = controls.value.copy(tab = tab) }
    fun setViewMode(mode: ViewMode) { controls.value = controls.value.copy(viewMode = mode) }
    fun setSort(key: SortKey, order: SortOrder) { controls.value = controls.value.copy(sortKey = key, sortOrder = order) }
    fun setQuery(query: String) { controls.value = controls.value.copy(query = query) }
}
```

- [ ] **Step 5: Update `VideoPlayerApp.kt` to construct the VM with both repositories**

Replace the `libraryViewModel` construction:
```kotlin
    val libraryViewModel: LibraryViewModel = viewModel {
        LibraryViewModel(MediaStoreRepository(appContext))
    }
```
with:
```kotlin
    val libraryViewModel: LibraryViewModel = viewModel {
        val db = AppDatabase.getInstance(appContext)
        LibraryViewModel(
            MediaStoreRepository(appContext),
            PlaybackMemoryRepository(db.playbackMemoryDao(), SettingsRepository(appContext.settingsDataStore)),
        )
    }
```
Add imports: `com.videoplayer.app.data.memory.AppDatabase`, `com.videoplayer.app.data.memory.PlaybackMemoryRepository`, `com.videoplayer.app.data.memory.SettingsRepository`, `com.videoplayer.app.data.memory.settingsDataStore`.

- [ ] **Step 6: Run to verify pass**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.videoplayer.app.library.LibraryViewModelStateTest"`
Expected: PASS (3 tests). Then run the full app suite to confirm nothing else broke: `… ./gradlew :app:testDebugUnitTest`.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/videoplayer/app/library/LibraryViewModel.kt app/src/main/kotlin/com/videoplayer/app/data/memory/PlaybackMemoryRepository.kt app/src/main/kotlin/com/videoplayer/app/VideoPlayerApp.kt app/src/test/kotlin/com/videoplayer/app/library
git commit -m "feat(app): LibraryViewModel combines folders, memory, and controls into LibraryUiState"
```

---

## Task D6: LibraryScreen rework — tabs, view toggle, sort, search, continue-watching, thumbnails [Android-verify]

**Files:**
- Modify: `app/src/main/kotlin/com/videoplayer/app/library/LibraryScreen.kt`

**Interfaces:**
- Consumes: `LibraryViewModel.uiState` + setters (D5), `rememberThumbnail` (D4), `formatDuration`, `MediaItem`.
- Produces: the reworked library UI. Permission handling (already in this file) is preserved.

- [ ] **Step 1: Rework `LibraryScreen.kt`**

Keep the existing permission-request `LaunchedEffect` and `readVideoPermission` logic exactly as-is. Replace the body that renders folders with this structure (full composable — match the existing import/style conventions, add Material3 imports as needed):

Top-level layout:
```
Column {
    LibraryTopBar(
        query, onQueryChange = viewModel::setQuery,
        sortKey, sortOrder, onSortChange = viewModel::setSort,
        viewMode, onToggleViewMode = { viewModel.setViewMode(if LIST then GRID else LIST) },
    )                                   // a Row with a search TextField, a sort dropdown (3 keys × asc/desc), and a list/grid toggle IconButton
    if (state.continueWatching.isNotEmpty()) ContinueWatchingRow(state.continueWatching, onItemClick)
    TabRow(selectedTabIndex = state.tab.ordinal) {
        Tab(selected = state.tab == FOLDERS, onClick = { viewModel.setTab(FOLDERS) }, text = { Text("Folders") })
        Tab(selected = state.tab == VIDEOS,  onClick = { viewModel.setTab(VIDEOS) },  text = { Text("Videos") })
    }
    when (state.tab) {
        FOLDERS -> FoldersContent(state.folders, state.viewMode, state.progressByUri, onItemClick)
        VIDEOS  -> VideosContent(state.videos, state.viewMode, state.progressByUri, onItemClick)
    }
}
```
Required composables to add in this file:

```kotlin
@Composable
private fun ContinueWatchingRow(items: List<LibraryItemUi>, onItemClick: (MediaItem) -> Unit) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Text("Continue watching", style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.item.id }) { ui ->
                ThumbnailTile(ui.item, ui.progress, width = 160.dp, onClick = { onItemClick(ui.item) })
            }
        }
    }
}

@Composable
private fun VideosContent(videos: List<MediaItem>, viewMode: ViewMode, progress: Map<String, Float>, onItemClick: (MediaItem) -> Unit) {
    if (viewMode == ViewMode.GRID) {
        LazyVerticalGrid(columns = GridCells.Adaptive(140.dp), contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(videos, key = { it.id }) { ThumbnailTile(it, progress[it.uri] ?: 0f, width = 140.dp) { onItemClick(it) } }
        }
    } else {
        LazyColumn { items(videos, key = { it.id }) { MediaRow(it, progress[it.uri] ?: 0f) { onItemClick(it) } } }
    }
}

@Composable
private fun FoldersContent(folders: List<MediaFolder>, viewMode: ViewMode, progress: Map<String, Float>, onItemClick: (MediaItem) -> Unit) {
    // LazyColumn (LIST) or LazyVerticalGrid (GRID); each folder a header (folder.name · count) followed by its items
    // rendered as MediaRow (LIST) or ThumbnailTile (GRID). For GRID, render a header item spanning full width then a grid;
    // simplest acceptable form: a LazyColumn of folder sections, each section's items in a row-wrapping FlowRow of tiles for GRID
    // or stacked MediaRows for LIST.
}

@Composable
private fun ThumbnailTile(item: MediaItem, progress: Float, width: Dp, onClick: () -> Unit) {
    val bitmap = rememberThumbnail(item.uri)
    Column(Modifier.width(width).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)) {
            if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = null,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (progress > 0f) LinearProgressIndicator(progress = { progress },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth())
        }
        Text(item.displayName, style = MaterialTheme.typography.bodySmall, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}
```
Update the existing `MediaRow` to take a `progress: Float` and render a leading thumbnail (via `rememberThumbnail`) + a trailing/under `LinearProgressIndicator` when `progress > 0f`. Keep its name/duration text.

The `LibraryTopBar` is a `Row` containing: an `OutlinedTextField`/`TextField` bound to `query` (leading search icon), a sort control (an `IconButton` opening a `DropdownMenu` of the 3 `SortKey`s, each toggling `SortOrder`), and an `IconButton` toggling `viewMode` (icon: list vs grid). Use Material3 `Icons.Default.Search`, `Icons.AutoMirrored.Filled.List` / `Icons.Default.GridView`, `Icons.AutoMirrored.Filled.Sort`.

- [ ] **Step 2: [Android-verify] Build**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: [Android-verify] Emulator smoke**

Install and verify on the `kuran_test` emulator (push at least 2 clips to `/sdcard/Movies/` and media-scan if not already present):
```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:installDebug
adb shell am start -n com.videoplayer.app/.MainActivity
adb exec-out screencap -p > /tmp/library.png
```
Verify (screenshot + interaction): Folders/Videos tabs switch; the list/grid toggle changes layout; thumbnails render; typing in search filters; the sort menu reorders; after playing a video partway (resume data exists) a "Continue watching" row appears with a progress bar. No crash (`adb logcat -d | grep -i AndroidRuntime | grep com.videoplayer || echo OK`).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/videoplayer/app/library/LibraryScreen.kt
git commit -m "feat(library): tabbed folders/videos browser with grid toggle, thumbnails, progress, search, sort, continue-watching"
```

---

## Self-Review (against the P1.D requirements)

**Spec coverage:**
- *Sort (name/date/duration)* → D1 `sortItems`/`sortFoldersBy` + D5 `setSort` + D6 sort menu. ✅
- *Search* → D1 `searchItems` + D5 `setQuery` + D6 search field. ✅
- *Thumbnails* → D4 `ThumbnailLoader`/`rememberThumbnail` (MediaMetadataRetriever, no new dep) + D6 tiles/rows. ✅
- *Watch history + "continue watching" row* → D2 `continueWatching` + D3 `observeAll` + D5 `continueWatching` state + D6 `ContinueWatchingRow`. ✅
- *All view modes (grouped list, thumbnail grid, tabbed Folders/Videos)* → D5 `LibraryTab` + `ViewMode` + D6 tabs + list/grid toggle. ✅ (user asked for all)
- *Continue-watching row on top (user choice)* → D6 renders it above the tabs. ✅
- *Auto-advance to next file in folder* → D1 `nextInFolder` provides the pure helper; the wiring is a **separate follow-up plan** (needs the player to take a playlist). Documented. ⏭️
- *[TDD] sort comparators, search predicate, next-file resolution* → D1 tests. ✅
- *[TDD] continue-watching ordering/filtering* → D2 tests. ✅

**Placeholder scan:** D1–D5 carry complete code + tests. D6 is a Compose UI task with a precise component structure and complete code for the non-obvious tiles/rows; folder-grid layout has stated acceptable forms (it is `[Android-verify]`, not unit-tested) — this is deliberate UI latitude, not a logic placeholder.

**Type consistency:** `SortKey`/`SortOrder`/`sortItems`/`sortFoldersBy`/`searchItems`/`allVideos`/`nextInFolder` (D1); `WatchProgress`/`progressFraction`/`continueWatching` (D2); `observeAll` (D3); `ThumbnailLoader.load`/`rememberThumbnail` (D4); `LibraryTab`/`ViewMode`/`LibraryItemUi`/`LibraryUiState`/`LibraryViewModel(MediaRepository, MemorySource)`/`MemorySource` (D5) — used consistently across D5/D6. ✅
