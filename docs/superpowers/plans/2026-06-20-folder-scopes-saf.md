# SAF Folder Scopes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a left navigation drawer that holds "All videos" (the existing MediaStore global library, unchanged) plus a user-managed list of folders picked through the Android system folder picker; selecting a folder scopes the Library to that folder and every subfolder beneath it, recursively, and reaches hidden `.folders`.

**Architecture:** All SAF/Android code lives in `:app`; `:core:model` stays Android-free and is reused as-is. A pure `walkSafTree` function does the recursive traversal logic (JVM-unit-tested with an injected child-lister). `SafFolderRepository` implements the existing `MediaRepository` interface so the entire downstream pipeline (grouping/sort/search/UI/playback) is unchanged. `LibrarySourceManager` switches the active source (Global vs a saved folder) via `flatMapLatest`. `LibrarySourceStore` persists saved folders + the active selection in DataStore.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), `DocumentsContract` (SAF), `androidx.datastore.preferences`, `kotlinx.serialization` (JSON), Media3 (playback, unchanged). Tests: JUnit + Truth + Turbine (all already in `:app`).

## Global Constraints

- `:core:*` must never import `android.*`, `androidx.compose.*`, or `media3`. All such code goes in `:app`.
- Builds require JDK 21: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"` before every Gradle call.
- SPDX header on every new Kotlin file: `// SPDX-License-Identifier: GPL-3.0-or-later`
- Package roots: pure logic that's reusable → keep in `:app` under `com.videoplayer.app.data.saf`; UI under `com.videoplayer.app`.
- No new dependencies — everything needed is already in the version catalog and `:app` build file.
- Reuse `MediaItem`, `MediaFolder`, `groupIntoFolders`, `MediaRepository` unchanged. SAF `MediaItem.id` must be **negative** to avoid colliding with positive MediaStore ids.
- Test cadence: failing test → run (see it fail) → implement → run (see it pass) → commit. One concept per commit.

---

## File Structure

**New (`:app` main):**
- `app/src/main/kotlin/com/videoplayer/app/data/saf/SafTreeWalker.kt` — pure traversal (`SafEntry`, `walkSafTree`, `safMediaId`, `DIR_MIME`).
- `app/src/main/kotlin/com/videoplayer/app/data/saf/SavedFolder.kt` — persistence model (`SavedFolder`, `LibrarySourceId`, JSON codec).
- `app/src/main/kotlin/com/videoplayer/app/data/saf/LibrarySourceStore.kt` — DataStore read/write of saved folders + active id.
- `app/src/main/kotlin/com/videoplayer/app/data/saf/SafFolderRepository.kt` — `MediaRepository` impl over a tree URI (enumeration + background durations).
- `app/src/main/kotlin/com/videoplayer/app/data/saf/LibrarySourceManager.kt` — active-source switching + add/remove/persist.
- `app/src/main/kotlin/com/videoplayer/app/library/LibraryDrawer.kt` — drawer composable.

**New (`:app` test):**
- `app/src/test/kotlin/com/videoplayer/app/data/saf/SafTreeWalkerTest.kt`
- `app/src/test/kotlin/com/videoplayer/app/data/saf/SavedFolderTest.kt`
- `app/src/test/kotlin/com/videoplayer/app/data/saf/LibrarySourceStoreTest.kt`
- `app/src/test/kotlin/com/videoplayer/app/data/saf/LibrarySourceManagerTest.kt`
- `app/src/test/kotlin/com/videoplayer/app/library/LibraryViewModelSourceTest.kt`

**Modified:**
- `app/src/main/kotlin/com/videoplayer/app/library/LibraryViewModel.kt` — consume `LibrarySourceManager`; add `savedFolders`/`activeSource` to state + actions.
- `app/src/main/kotlin/com/videoplayer/app/VideoPlayerApp.kt` — `ModalNavigationDrawer` shell, SAF launcher, scope title.

---

## Task 1: Pure SAF tree walker

**Files:**
- Create: `app/src/main/kotlin/com/videoplayer/app/data/saf/SafTreeWalker.kt`
- Test: `app/src/test/kotlin/com/videoplayer/app/data/saf/SafTreeWalkerTest.kt`

**Interfaces:**
- Consumes: `com.videoplayer.core.model.MediaItem`.
- Produces:
  - `data class SafEntry(val documentId: String, val uri: String, val displayName: String, val mimeType: String, val sizeBytes: Long, val lastModifiedMs: Long)`
  - `const val DIR_MIME = "vnd.android.document/directory"`
  - `fun safMediaId(uri: String): Long` — deterministic, always negative.
  - `fun walkSafTree(rootDocumentId: String, rootName: String, listChildren: (documentId: String) -> List<SafEntry>): List<MediaItem>` — returns all `video/*` files under the root (any depth), each `MediaItem.folderPath` = the `/`-joined display path from `rootName` down to the file's parent, `durationMs = 0`, `dateAddedSec = lastModifiedMs / 1000`, `id = safMediaId(uri)`.

- [ ] **Step 1: Write the failing test**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.data.saf

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SafTreeWalkerTest {

    private fun dir(id: String, name: String) =
        SafEntry(id, "uri://$id", name, DIR_MIME, 0, 0)

    private fun video(id: String, name: String, size: Long = 10, modified: Long = 7000) =
        SafEntry(id, "uri://$id", name, "video/mp4", size, modified)

    /** root/ has a.mp4; root/sub/ has b.mp4; root/.hidden/ has c.mp4 */
    private val tree: Map<String, List<SafEntry>> = mapOf(
        "root" to listOf(video("a", "a.mp4"), dir("sub", "sub"), dir("hid", ".hidden")),
        "sub" to listOf(video("b", "b.mp4")),
        "hid" to listOf(video("c", "c.mp4")),
    )

    private fun listChildren(id: String): List<SafEntry> = tree[id].orEmpty()

    @Test
    fun `walks all subfolders recursively including hidden`() {
        val items = walkSafTree("root", "Movies", ::listChildren)
        assertThat(items.map { it.displayName }).containsExactly("a.mp4", "b.mp4", "c.mp4")
    }

    @Test
    fun `folderPath reflects nesting from root name`() {
        val items = walkSafTree("root", "Movies", ::listChildren).associateBy { it.displayName }
        assertThat(items["a.mp4"]!!.folderPath).isEqualTo("Movies")
        assertThat(items["b.mp4"]!!.folderPath).isEqualTo("Movies/sub")
        assertThat(items["c.mp4"]!!.folderPath).isEqualTo("Movies/.hidden")
    }

    @Test
    fun `non-video files are ignored`() {
        val withDoc = tree + ("root" to (tree["root"]!! + SafEntry("d", "uri://d", "notes.txt", "text/plain", 1, 1)))
        val items = walkSafTree("root", "Movies") { withDoc[it].orEmpty() }
        assertThat(items.map { it.displayName }).doesNotContain("notes.txt")
    }

    @Test
    fun `maps metadata onto MediaItem`() {
        val items = walkSafTree("root", "Movies", ::listChildren)
        val a = items.first { it.displayName == "a.mp4" }
        assertThat(a.uri).isEqualTo("uri://a")
        assertThat(a.dateAddedSec).isEqualTo(7L)   // 7000ms / 1000
        assertThat(a.id).isLessThan(0L)            // never collides with MediaStore
        assertThat(a.durationMs).isEqualTo(0L)
    }

    @Test
    fun `safMediaId is deterministic and negative`() {
        assertThat(safMediaId("uri://x")).isEqualTo(safMediaId("uri://x"))
        assertThat(safMediaId("uri://x")).isLessThan(0L)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest --tests "com.videoplayer.app.data.saf.SafTreeWalkerTest"`
Expected: FAIL — unresolved references (`walkSafTree`, `SafEntry`, etc.).

- [ ] **Step 3: Write minimal implementation**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.data.saf

import com.videoplayer.core.model.MediaItem
import kotlin.math.absoluteValue

/** SAF directory MIME type (from DocumentsContract.Document.MIME_TYPE_DIR). */
const val DIR_MIME = "vnd.android.document/directory"

/** One child returned by a SAF child-documents query, already resolved to a playable URI. */
data class SafEntry(
    val documentId: String,
    val uri: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
)

/** Deterministic, always-negative id so SAF items never collide with positive MediaStore ids. */
fun safMediaId(uri: String): Long = -(uri.hashCode().toLong().absoluteValue + 1)

/**
 * Walks a SAF document tree breadth-first via [listChildren] and returns every `video/*` file
 * found at any depth. [rootName] is the display name of the picked folder; each item's
 * [MediaItem.folderPath] is the `/`-joined path of display names from the root down to its parent,
 * so [com.videoplayer.core.model.groupIntoFolders] groups the scoped view meaningfully.
 * Durations are left at 0 (filled in later by the repository).
 */
fun walkSafTree(
    rootDocumentId: String,
    rootName: String,
    listChildren: (documentId: String) -> List<SafEntry>,
): List<MediaItem> {
    val out = ArrayList<MediaItem>()
    val queue = ArrayDeque<Pair<String, String>>()  // documentId -> display path of that dir
    queue.add(rootDocumentId to rootName)
    while (queue.isNotEmpty()) {
        val (docId, path) = queue.removeFirst()
        for (child in listChildren(docId)) {
            when {
                child.mimeType == DIR_MIME ->
                    queue.add(child.documentId to "$path/${child.displayName}")
                child.mimeType.startsWith("video/") ->
                    out.add(
                        MediaItem(
                            id = safMediaId(child.uri),
                            uri = child.uri,
                            displayName = child.displayName,
                            folderPath = path,
                            durationMs = 0,
                            sizeBytes = child.sizeBytes,
                            dateAddedSec = child.lastModifiedMs / 1000,
                        )
                    )
            }
        }
    }
    return out
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest --tests "com.videoplayer.app.data.saf.SafTreeWalkerTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/videoplayer/app/data/saf/SafTreeWalker.kt app/src/test/kotlin/com/videoplayer/app/data/saf/SafTreeWalkerTest.kt
git commit -m "feat(saf): pure recursive tree walker for folder scopes"
```

---

## Task 2: Saved-folder model + JSON codec

**Files:**
- Create: `app/src/main/kotlin/com/videoplayer/app/data/saf/SavedFolder.kt`
- Test: `app/src/test/kotlin/com/videoplayer/app/data/saf/SavedFolderTest.kt`

**Interfaces:**
- Produces:
  - `@Serializable data class SavedFolder(val treeUri: String, val displayName: String)`
  - `sealed interface LibrarySourceId { data object Global : LibrarySourceId; data class Folder(val treeUri: String) : LibrarySourceId }`
  - `fun encodeSavedFolders(folders: List<SavedFolder>): String`
  - `fun decodeSavedFolders(json: String?): List<SavedFolder>` — null/blank/garbage → `emptyList()`.
  - `fun encodeSourceId(id: LibrarySourceId): String` / `fun decodeSourceId(raw: String?): LibrarySourceId` — null/blank/`"GLOBAL"` → `Global`, else `Folder(raw)`.

- [ ] **Step 1: Write the failing test**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.data.saf

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SavedFolderTest {

    @Test
    fun `round-trips a folder list through json`() {
        val list = listOf(SavedFolder("content://tree/a", "Anime"), SavedFolder("content://tree/b", ".private"))
        assertThat(decodeSavedFolders(encodeSavedFolders(list))).isEqualTo(list)
    }

    @Test
    fun `decode tolerates null and garbage`() {
        assertThat(decodeSavedFolders(null)).isEmpty()
        assertThat(decodeSavedFolders("")).isEmpty()
        assertThat(decodeSavedFolders("not json")).isEmpty()
    }

    @Test
    fun `source id round-trips`() {
        assertThat(decodeSourceId(encodeSourceId(LibrarySourceId.Global))).isEqualTo(LibrarySourceId.Global)
        val f = LibrarySourceId.Folder("content://tree/a")
        assertThat(decodeSourceId(encodeSourceId(f))).isEqualTo(f)
    }

    @Test
    fun `source id defaults to global`() {
        assertThat(decodeSourceId(null)).isEqualTo(LibrarySourceId.Global)
        assertThat(decodeSourceId("")).isEqualTo(LibrarySourceId.Global)
        assertThat(decodeSourceId("GLOBAL")).isEqualTo(LibrarySourceId.Global)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest --tests "com.videoplayer.app.data.saf.SavedFolderTest"`
Expected: FAIL — unresolved references.

- [ ] **Step 3: Write minimal implementation**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.data.saf

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A folder the user added via SAF, persisted across launches. */
@Serializable
data class SavedFolder(val treeUri: String, val displayName: String)

/** Which library source is active: the global MediaStore view, or one saved folder. */
sealed interface LibrarySourceId {
    data object Global : LibrarySourceId
    data class Folder(val treeUri: String) : LibrarySourceId
}

private val json = Json { ignoreUnknownKeys = true }
private const val GLOBAL_RAW = "GLOBAL"

fun encodeSavedFolders(folders: List<SavedFolder>): String = json.encodeToString(folders)

fun decodeSavedFolders(jsonStr: String?): List<SavedFolder> {
    if (jsonStr.isNullOrBlank()) return emptyList()
    return runCatching { json.decodeFromString<List<SavedFolder>>(jsonStr) }.getOrDefault(emptyList())
}

fun encodeSourceId(id: LibrarySourceId): String = when (id) {
    is LibrarySourceId.Global -> GLOBAL_RAW
    is LibrarySourceId.Folder -> id.treeUri
}

fun decodeSourceId(raw: String?): LibrarySourceId =
    if (raw.isNullOrBlank() || raw == GLOBAL_RAW) LibrarySourceId.Global else LibrarySourceId.Folder(raw)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest --tests "com.videoplayer.app.data.saf.SavedFolderTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/videoplayer/app/data/saf/SavedFolder.kt app/src/test/kotlin/com/videoplayer/app/data/saf/SavedFolderTest.kt
git commit -m "feat(saf): saved-folder model and json codec"
```

---

## Task 3: LibrarySourceStore (DataStore persistence)

**Files:**
- Create: `app/src/main/kotlin/com/videoplayer/app/data/saf/LibrarySourceStore.kt`
- Test: `app/src/test/kotlin/com/videoplayer/app/data/saf/LibrarySourceStoreTest.kt`

**Interfaces:**
- Consumes: `SavedFolder`, `LibrarySourceId`, codecs (Task 2); `androidx.datastore.core.DataStore<Preferences>`.
- Produces:
  - `interface SourceStore` with: `val savedFolders: Flow<List<SavedFolder>>`, `val activeSource: Flow<LibrarySourceId>`, `suspend fun addFolder(folder: SavedFolder)`, `suspend fun removeFolder(treeUri: String)`, `suspend fun setActive(id: LibrarySourceId)`. (This interface lets the manager + viewmodel tests use a synchronous in-memory fake — Task 6.)
  - `class LibrarySourceStore(private val dataStore: DataStore<Preferences>) : SourceStore` — the DataStore-backed impl:
    - `addFolder` appends if `treeUri` not already present.
    - `removeFolder` drops it; if it was active, resets active to `Global`.
    - `setActive` persists the active id.

- [ ] **Step 1: Write the failing test**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.data.saf

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LibrarySourceStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun newStore(): LibrarySourceStore {
        val ds: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            produceFile = { File(tmp.newFolder(), "src.preferences_pb") }
        )
        return LibrarySourceStore(ds)
    }

    @Test
    fun `defaults are empty list and global`() = runTest {
        val store = newStore()
        assertThat(store.savedFolders.first()).isEmpty()
        assertThat(store.activeSource.first()).isEqualTo(LibrarySourceId.Global)
    }

    @Test
    fun `addFolder persists and dedupes by treeUri`() = runTest {
        val store = newStore()
        store.addFolder(SavedFolder("content://tree/a", "Anime"))
        store.addFolder(SavedFolder("content://tree/a", "Anime again"))
        assertThat(store.savedFolders.first()).containsExactly(SavedFolder("content://tree/a", "Anime"))
    }

    @Test
    fun `setActive then removeFolder resets active to global`() = runTest {
        val store = newStore()
        store.addFolder(SavedFolder("content://tree/a", "Anime"))
        store.setActive(LibrarySourceId.Folder("content://tree/a"))
        assertThat(store.activeSource.first()).isEqualTo(LibrarySourceId.Folder("content://tree/a"))
        store.removeFolder("content://tree/a")
        assertThat(store.savedFolders.first()).isEmpty()
        assertThat(store.activeSource.first()).isEqualTo(LibrarySourceId.Global)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest --tests "com.videoplayer.app.data.saf.LibrarySourceStoreTest"`
Expected: FAIL — `LibrarySourceStore` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.data.saf

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Read/write the user's saved SAF folders and the active source. Faked in tests by InMemorySourceStore. */
interface SourceStore {
    val savedFolders: Flow<List<SavedFolder>>
    val activeSource: Flow<LibrarySourceId>
    suspend fun addFolder(folder: SavedFolder)
    suspend fun removeFolder(treeUri: String)
    suspend fun setActive(id: LibrarySourceId)
}

/** Persists the user's saved SAF folders and which source is currently active. */
class LibrarySourceStore(private val dataStore: DataStore<Preferences>) : SourceStore {

    private object Keys {
        val FOLDERS = stringPreferencesKey("saf_saved_folders")
        val ACTIVE = stringPreferencesKey("saf_active_source")
    }

    override val savedFolders: Flow<List<SavedFolder>> =
        dataStore.data.map { decodeSavedFolders(it[Keys.FOLDERS]) }

    override val activeSource: Flow<LibrarySourceId> =
        dataStore.data.map { decodeSourceId(it[Keys.ACTIVE]) }

    override suspend fun addFolder(folder: SavedFolder) {
        dataStore.edit { prefs ->
            val current = decodeSavedFolders(prefs[Keys.FOLDERS])
            if (current.none { it.treeUri == folder.treeUri }) {
                prefs[Keys.FOLDERS] = encodeSavedFolders(current + folder)
            }
        }
    }

    override suspend fun removeFolder(treeUri: String) {
        dataStore.edit { prefs ->
            val current = decodeSavedFolders(prefs[Keys.FOLDERS])
            prefs[Keys.FOLDERS] = encodeSavedFolders(current.filterNot { it.treeUri == treeUri })
            if (decodeSourceId(prefs[Keys.ACTIVE]) == LibrarySourceId.Folder(treeUri)) {
                prefs[Keys.ACTIVE] = encodeSourceId(LibrarySourceId.Global)
            }
        }
    }

    override suspend fun setActive(id: LibrarySourceId) {
        dataStore.edit { it[Keys.ACTIVE] = encodeSourceId(id) }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest --tests "com.videoplayer.app.data.saf.LibrarySourceStoreTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/videoplayer/app/data/saf/LibrarySourceStore.kt app/src/test/kotlin/com/videoplayer/app/data/saf/LibrarySourceStoreTest.kt
git commit -m "feat(saf): datastore persistence for saved folders and active source"
```

---

## Task 4: SafFolderRepository — enumeration

**Files:**
- Create: `app/src/main/kotlin/com/videoplayer/app/data/saf/SafFolderRepository.kt`
- (No new unit test — the traversal logic is covered by Task 1; this is thin Android glue verified by build + the emulator smoke in Task 9.)

**Interfaces:**
- Consumes: `walkSafTree`, `SafEntry`, `DIR_MIME` (Task 1); `com.videoplayer.core.model.{MediaRepository, MediaFolder, groupIntoFolders}`; Android `Context`, `DocumentsContract`.
- Produces:
  - `class SafFolderRepository(private val context: Context, private val treeUri: Uri) : MediaRepository`
  - implements `observeFolders(): Flow<List<MediaFolder>>` and `suspend fun refresh()`.
  - `private fun listChildren(parentDocId: String): List<SafEntry>` using `DocumentsContract.buildChildDocumentsUriUsingTree`.

- [ ] **Step 1: Implement (no separate test — covered by Task 1 + Task 9 smoke)**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.data.saf

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.videoplayer.core.model.MediaFolder
import com.videoplayer.core.model.MediaRepository
import com.videoplayer.core.model.groupIntoFolders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * [MediaRepository] backed by a SAF document tree. Walks the picked folder and all of its
 * subfolders (including hidden ones) via [DocumentsContract] child-document queries — fast,
 * no per-file DocumentFile objects — and groups the result with the shared [groupIntoFolders].
 */
class SafFolderRepository(
    private val context: Context,
    private val treeUri: Uri,
) : MediaRepository {

    private val _folders = MutableStateFlow<List<MediaFolder>>(emptyList())
    override fun observeFolders(): Flow<List<MediaFolder>> = _folders.asStateFlow()

    override suspend fun refresh() = withContext(Dispatchers.IO) {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootName = rootDisplayName(rootDocId)
        _folders.value = groupIntoFolders(walkSafTree(rootDocId, rootName, ::listChildren))
    }

    private fun rootDisplayName(rootDocId: String): String {
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
        context.contentResolver.query(
            rootUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getString(0)?.let { return it } }
        return rootDocId.substringAfterLast('/').substringAfterLast(':')
    }

    private fun listChildren(parentDocId: String): List<SafEntry> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        return context.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val docId = c.getString(0) ?: continue
                    add(
                        SafEntry(
                            documentId = docId,
                            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId).toString(),
                            displayName = c.getString(1) ?: "",
                            mimeType = c.getString(2) ?: "",
                            sizeBytes = if (c.isNull(3)) 0L else c.getLong(3),
                            lastModifiedMs = if (c.isNull(4)) 0L else c.getLong(4),
                        )
                    )
                }
            }
        } ?: emptyList()
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/videoplayer/app/data/saf/SafFolderRepository.kt
git commit -m "feat(saf): MediaRepository backed by a SAF document tree"
```

---

## Task 5: SafFolderRepository — background durations

**Files:**
- Modify: `app/src/main/kotlin/com/videoplayer/app/data/saf/SafFolderRepository.kt`
- (Verified by build + emulator smoke; `MediaMetadataRetriever` needs real files.)

**Interfaces:**
- Consumes: existing `SafFolderRepository`; Android `MediaMetadataRetriever`; `kotlinx.coroutines` (`coroutineScope`, `Semaphore`).
- Produces: `refresh()` now emits folders immediately, then re-emits the same folders with `durationMs` filled per item. New private `suspend fun resolveDurations(folders): List<MediaFolder>`.

- [ ] **Step 1: Modify `refresh()` and add duration resolution**

Replace the `refresh()` body and add the helper:

```kotlin
    override suspend fun refresh() = withContext(Dispatchers.IO) {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootName = rootDisplayName(rootDocId)
        val folders = groupIntoFolders(walkSafTree(rootDocId, rootName, ::listChildren))
        _folders.value = folders                       // show immediately, durations = 0
        _folders.value = resolveDurations(folders)     // fill durations, re-emit
    }

    private suspend fun resolveDurations(folders: List<MediaFolder>): List<MediaFolder> = coroutineScope {
        val gate = Semaphore(permits = 4)              // bound concurrent retrievers
        folders.map { folder ->
            val items = folder.items.map { item ->
                async {
                    gate.withPermit {
                        val ms = durationMsOf(Uri.parse(item.uri))
                        if (ms > 0) item.copy(durationMs = ms) else item
                    }
                }
            }
            folder.copy(items = items.awaitAll())
        }
    }

    private fun durationMsOf(uri: Uri): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { r.release() }
        }
    }
```

Add imports:

```kotlin
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
```

- [ ] **Step 2: Verify it compiles**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/videoplayer/app/data/saf/SafFolderRepository.kt
git commit -m "feat(saf): resolve scoped-folder durations in a bounded background pass"
```

---

## Task 6: LibrarySourceManager — active-source switching

**Files:**
- Create: `app/src/main/kotlin/com/videoplayer/app/data/saf/LibrarySourceManager.kt`
- Test: `app/src/test/kotlin/com/videoplayer/app/data/saf/LibrarySourceManagerTest.kt`

**Interfaces:**
- Consumes: `SourceStore` (Task 3), `SavedFolder`/`LibrarySourceId` (Task 2), `com.videoplayer.core.model.{MediaRepository, MediaFolder}`.
- Produces:
  - `class LibrarySourceManager(private val store: SourceStore, private val globalRepository: MediaRepository, private val folderRepositoryFactory: (treeUri: String) -> MediaRepository)`
  - `val savedFolders: Flow<List<SavedFolder>>` (delegates to store)
  - `val activeSource: Flow<LibrarySourceId>` (delegates to store)
  - `fun activeFolders(): Flow<List<MediaFolder>>` — `flatMapLatest` over `activeSource`: `Global` → `globalRepository.observeFolders()`, else the cached folder repo's `observeFolders()`.
  - `suspend fun refreshActive(activeId: LibrarySourceId)` — calls `refresh()` on the repo for that id.
  - `suspend fun selectSource(id: LibrarySourceId)`, `suspend fun addFolder(folder: SavedFolder)`, `suspend fun removeFolder(treeUri: String)` (delegate to store; `removeFolder` also evicts the cached repo).

- [ ] **Step 1: Create the in-memory store fake (shared by Tasks 6 & 7)**

Create `app/src/test/kotlin/com/videoplayer/app/data/saf/InMemorySourceStore.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.data.saf

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Synchronous in-memory [SourceStore] for tests — no DataStore, deterministic under runTest. */
class InMemorySourceStore : SourceStore {
    private val _saved = MutableStateFlow<List<SavedFolder>>(emptyList())
    private val _active = MutableStateFlow<LibrarySourceId>(LibrarySourceId.Global)
    override val savedFolders: Flow<List<SavedFolder>> = _saved
    override val activeSource: Flow<LibrarySourceId> = _active
    override suspend fun addFolder(folder: SavedFolder) {
        if (_saved.value.none { it.treeUri == folder.treeUri }) _saved.value = _saved.value + folder
    }
    override suspend fun removeFolder(treeUri: String) {
        _saved.value = _saved.value.filterNot { it.treeUri == treeUri }
        if (_active.value == LibrarySourceId.Folder(treeUri)) _active.value = LibrarySourceId.Global
    }
    override suspend fun setActive(id: LibrarySourceId) { _active.value = id }
}
```

- [ ] **Step 2: Write the failing test**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.data.saf

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.videoplayer.core.model.MediaFolder
import com.videoplayer.core.model.MediaItem
import com.videoplayer.core.model.MediaRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LibrarySourceManagerTest {

    private fun folder(name: String) =
        MediaFolder(name, name, listOf(MediaItem(1, "u/$name", "$name.mp4", name, 0, 0, 0)))

    private class FakeRepo(initial: List<MediaFolder>) : MediaRepository {
        val state = MutableStateFlow(initial)
        override fun observeFolders(): Flow<List<MediaFolder>> = state
        override suspend fun refresh() {}
    }

    @Test
    fun `activeFolders follows the active source`() = runTest {
        val store = InMemorySourceStore()
        val global = FakeRepo(listOf(folder("Global")))
        val scoped = FakeRepo(listOf(folder("Scoped")))
        val manager = LibrarySourceManager(store, global) { scoped }

        store.addFolder(SavedFolder("content://tree/a", "Scoped"))

        manager.activeFolders().test {
            assertThat(awaitItem().single().name).isEqualTo("Global")   // default
            manager.selectSource(LibrarySourceId.Folder("content://tree/a"))
            assertThat(awaitItem().single().name).isEqualTo("Scoped")
            manager.selectSource(LibrarySourceId.Global)
            assertThat(awaitItem().single().name).isEqualTo("Global")
        }
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest --tests "com.videoplayer.app.data.saf.LibrarySourceManagerTest"`
Expected: FAIL — `LibrarySourceManager` unresolved.

- [ ] **Step 4: Write minimal implementation**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.data.saf

import com.videoplayer.core.model.MediaFolder
import com.videoplayer.core.model.MediaRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest

/**
 * Switches the library's active source between the global MediaStore repository and one of the
 * user's saved SAF folders. Folder repositories are created on demand by [folderRepositoryFactory]
 * and cached per tree URI.
 */
class LibrarySourceManager(
    private val store: SourceStore,
    private val globalRepository: MediaRepository,
    private val folderRepositoryFactory: (treeUri: String) -> MediaRepository,
) {
    private val folderRepos = mutableMapOf<String, MediaRepository>()

    val savedFolders: Flow<List<SavedFolder>> = store.savedFolders
    val activeSource: Flow<LibrarySourceId> = store.activeSource

    private fun repoFor(id: LibrarySourceId): MediaRepository = when (id) {
        is LibrarySourceId.Global -> globalRepository
        is LibrarySourceId.Folder -> folderRepos.getOrPut(id.treeUri) { folderRepositoryFactory(id.treeUri) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun activeFolders(): Flow<List<MediaFolder>> =
        store.activeSource.flatMapLatest { repoFor(it).observeFolders() }

    suspend fun refreshActive(activeId: LibrarySourceId) = repoFor(activeId).refresh()

    suspend fun selectSource(id: LibrarySourceId) = store.setActive(id)

    suspend fun addFolder(folder: SavedFolder) = store.addFolder(folder)

    suspend fun removeFolder(treeUri: String) {
        folderRepos.remove(treeUri)
        store.removeFolder(treeUri)
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest --tests "com.videoplayer.app.data.saf.LibrarySourceManagerTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/videoplayer/app/data/saf/LibrarySourceManager.kt app/src/test/kotlin/com/videoplayer/app/data/saf/LibrarySourceManagerTest.kt app/src/test/kotlin/com/videoplayer/app/data/saf/InMemorySourceStore.kt
git commit -m "feat(saf): library source manager switching global vs saved folders"
```

---

## Task 7: Wire the manager into LibraryViewModel

**Files:**
- Modify: `app/src/main/kotlin/com/videoplayer/app/library/LibraryViewModel.kt`
- Test: `app/src/test/kotlin/com/videoplayer/app/library/LibraryViewModelSourceTest.kt`

**Interfaces:**
- Consumes: `LibrarySourceManager`, `SavedFolder`, `LibrarySourceId` (Tasks 2/6); existing `MemorySource`.
- Produces (changes to `LibraryViewModel`):
  - Constructor becomes `LibraryViewModel(private val sourceManager: LibrarySourceManager, memorySource: MemorySource)`.
  - `LibraryUiState` gains `val savedFolders: List<SavedFolder> = emptyList()` and `val activeSource: LibrarySourceId = LibrarySourceId.Global`.
  - `uiState` folders source becomes `sourceManager.activeFolders()`, and the combine also folds in `sourceManager.savedFolders` and `sourceManager.activeSource`.
  - `refresh()` calls `sourceManager.refreshActive(currentActive)`.
  - New actions: `fun selectSource(id)`, `fun addFolder(folder)`, `fun removeFolder(treeUri)` — each `viewModelScope.launch { ... }`, and `selectSource`/`addFolder` also trigger a refresh of the newly active source.

- [ ] **Step 1: Write the failing test** (mirrors the existing `LibraryViewModelStateTest` conventions: `MainDispatcherRule` + `advanceUntilIdle`, reusing `FakeMediaRepository`/`FakeMemorySource`)

Create `app/src/test/kotlin/com/videoplayer/app/library/LibraryViewModelSourceTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.library

import com.google.common.truth.Truth.assertThat
import com.videoplayer.app.MainDispatcherRule
import com.videoplayer.app.data.saf.InMemorySourceStore
import com.videoplayer.app.data.saf.LibrarySourceId
import com.videoplayer.app.data.saf.LibrarySourceManager
import com.videoplayer.app.data.saf.SavedFolder
import com.videoplayer.core.model.MediaFolder
import com.videoplayer.core.model.MediaItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelSourceTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private fun folder(name: String) =
        MediaFolder(name, name, listOf(MediaItem(1, "u/$name", "$name.mp4", name, 0, 0, 0)))

    @Test fun `state exposes saved folders and switches active source`() = runTest {
        val store = InMemorySourceStore()
        val manager = LibrarySourceManager(
            store,
            FakeMediaRepository(listOf(folder("Global"))),
        ) { FakeMediaRepository(listOf(folder("Scoped"))) }
        val vm = LibraryViewModel(manager, FakeMemorySource(emptyList()))

        vm.addFolder(SavedFolder("content://tree/a", "Scoped"))   // also selects + refreshes it
        advanceUntilIdle()

        val s = vm.uiState.value
        assertThat(s.savedFolders).contains(SavedFolder("content://tree/a", "Scoped"))
        assertThat(s.activeSource).isEqualTo(LibrarySourceId.Folder("content://tree/a"))
        assertThat(s.folders.single().name).isEqualTo("Scoped")

        vm.selectSource(LibrarySourceId.Global); advanceUntilIdle()
        assertThat(vm.uiState.value.folders.single().name).isEqualTo("Global")
    }
}
```

> Note: `FakeMediaRepository.refresh()` publishes `foldersToEmit` to its flow, so `addFolder`→refresh and `selectSource(Global)`→refresh both produce visible folders. This matches the real repos' refresh-then-emit behavior.

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest --tests "com.videoplayer.app.library.LibraryViewModelSourceTest"`
Expected: FAIL — constructor signature mismatch / missing state fields.

- [ ] **Step 3: Modify `LibraryViewModel`**

Add imports:

```kotlin
import com.videoplayer.app.data.saf.LibrarySourceId
import com.videoplayer.app.data.saf.LibrarySourceManager
import com.videoplayer.app.data.saf.SavedFolder
import kotlinx.coroutines.flow.combine
```

Add the two fields to `LibraryUiState`:

```kotlin
    val savedFolders: List<SavedFolder> = emptyList(),
    val activeSource: LibrarySourceId = LibrarySourceId.Global,
```

Replace the constructor and `uiState`/`refresh`, and add actions:

```kotlin
class LibraryViewModel(
    private val sourceManager: LibrarySourceManager,
    memorySource: MemorySource,
) : ViewModel() {

    private val controls = MutableStateFlow(Controls())
    private val loading = MutableStateFlow(true)

    private data class Sources(
        val folders: List<MediaFolder>,
        val saved: List<SavedFolder>,
        val active: LibrarySourceId,
    )

    private val sources = combine(
        sourceManager.activeFolders(),
        sourceManager.savedFolders,
        sourceManager.activeSource,
    ) { folders, saved, active -> Sources(folders, saved, active) }

    val uiState: StateFlow<LibraryUiState> =
        combine(sources, memorySource.observeAll(), controls, loading) { src, memory, c, isLoading ->
            val folders = src.folders
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
                savedFolders = src.saved, activeSource = src.active, isLoading = isLoading,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryUiState())

    fun refresh() = viewModelScope.launch {
        sourceManager.refreshActive(uiState.value.activeSource); loading.value = false
    }
    fun selectSource(id: LibrarySourceId) = viewModelScope.launch {
        sourceManager.selectSource(id); sourceManager.refreshActive(id)
    }.let {}
    fun addFolder(folder: SavedFolder) = viewModelScope.launch {
        sourceManager.addFolder(folder)
        sourceManager.selectSource(LibrarySourceId.Folder(folder.treeUri))
        sourceManager.refreshActive(LibrarySourceId.Folder(folder.treeUri))
    }.let {}
    fun removeFolder(treeUri: String) = viewModelScope.launch { sourceManager.removeFolder(treeUri) }.let {}
    fun setTab(tab: LibraryTab) { controls.value = controls.value.copy(tab = tab) }
    fun setViewMode(mode: ViewMode) { controls.value = controls.value.copy(viewMode = mode) }
    fun setSort(key: SortKey, order: SortOrder) { controls.value = controls.value.copy(sortKey = key, sortOrder = order) }
    fun setQuery(query: String) { controls.value = controls.value.copy(query = query) }
}
```

> Note: the `.let {}` suffix discards the `Job` return so these stay `Unit`-returning like `setTab`. If the existing `refresh()` was `Unit`-returning, keep it consistent.

- [ ] **Step 4: Run test to verify it passes**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest --tests "com.videoplayer.app.library.LibraryViewModelSourceTest"`
Expected: PASS.

- [ ] **Step 5: Migrate all existing call sites (main must compile)**

The new constructor breaks 6 call sites. Fix them all now so `:app` main + the whole test suite compile.

**(a) Production — `VideoPlayerApp.kt`:** replace the `LibraryViewModel(MediaStoreRepository(appContext), ...)` construction with a real manager. Add imports and swap the body:

```kotlin
import android.net.Uri
import com.videoplayer.app.data.saf.LibrarySourceManager
import com.videoplayer.app.data.saf.LibrarySourceStore
import com.videoplayer.app.data.saf.SafFolderRepository
import com.videoplayer.app.data.saf.libraryDataStore
```

```kotlin
val libraryViewModel: LibraryViewModel = viewModel {
    val db = AppDatabase.getInstance(appContext)
    val manager = LibrarySourceManager(
        store = LibrarySourceStore(appContext.libraryDataStore),
        globalRepository = MediaStoreRepository(appContext),
        folderRepositoryFactory = { treeUri -> SafFolderRepository(appContext, Uri.parse(treeUri)) },
    )
    LibraryViewModel(
        manager,
        PlaybackMemoryRepository(db.playbackMemoryDao(), SettingsRepository(appContext.settingsDataStore)),
    )
}
```

> This requires the `libraryDataStore` extension — add it now (it's also listed in Task 8). Create `app/src/main/kotlin/com/videoplayer/app/data/saf/LibraryDataStore.kt`:
> ```kotlin
> // SPDX-License-Identifier: GPL-3.0-or-later
> package com.videoplayer.app.data.saf
> import android.content.Context
> import androidx.datastore.core.DataStore
> import androidx.datastore.preferences.core.Preferences
> import androidx.datastore.preferences.preferencesDataStore
> val Context.libraryDataStore: DataStore<Preferences> by preferencesDataStore(name = "library_sources")
> ```

**(b) Tests — add a shared helper** `app/src/test/kotlin/com/videoplayer/app/library/FakeSourceManager.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.library

import com.videoplayer.app.data.saf.InMemorySourceStore
import com.videoplayer.app.data.saf.LibrarySourceManager
import com.videoplayer.core.model.MediaRepository

/** Wraps a single global repo as a Global-only LibrarySourceManager for existing VM tests. */
fun fakeSourceManager(repo: MediaRepository): LibrarySourceManager =
    LibrarySourceManager(InMemorySourceStore(), repo) { repo }
```

Then update the 5 existing call sites to wrap their repo:
- `app/src/test/kotlin/com/videoplayer/app/library/LibraryViewModelTest.kt` (lines 22, 28)
- `app/src/test/kotlin/com/videoplayer/app/library/LibraryViewModelStateTest.kt` (lines 31, 42, 50)

Change each `LibraryViewModel(FakeMediaRepository(...), FakeMemorySource(...))`
to `LibraryViewModel(fakeSourceManager(FakeMediaRepository(...)), FakeMemorySource(...))`.

- [ ] **Step 6: Run the full unit suite (regression gate)**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew test`
Expected: PASS — new test green, all existing tests still green, main compiles.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/videoplayer/app/library/LibraryViewModel.kt app/src/main/kotlin/com/videoplayer/app/data/saf/LibraryDataStore.kt app/src/main/kotlin/com/videoplayer/app/VideoPlayerApp.kt app/src/test/kotlin/com/videoplayer/app/library/
git commit -m "feat(library): view model consumes library source manager"
```

---

## Task 8: Drawer UI, SAF picker, permissions, and app wiring

**Files:**
- Create: `app/src/main/kotlin/com/videoplayer/app/library/LibraryDrawer.kt`
- Modify: `app/src/main/kotlin/com/videoplayer/app/VideoPlayerApp.kt`
- (Verified by build + Task 9 emulator smoke. No Compose UI unit tests are set up in this project.)

**Interfaces:**
- Consumes: `LibraryUiState.savedFolders` / `.activeSource`, `viewModel.selectSource/addFolder/removeFolder`; `LibrarySourceManager`, `LibrarySourceStore`, `SafFolderRepository`, `MediaStoreRepository`; `androidx.activity.compose.rememberLauncherForActivityResult`, `ActivityResultContracts.OpenDocumentTree`.
- Produces:
  - `@Composable fun LibraryDrawer(savedFolders, activeSource, onSelectGlobal, onSelectFolder, onAddFolder, onRemoveFolder, content)` wrapping a `ModalNavigationDrawer`.
  - App-level construction of `LibrarySourceManager` + the SAF launcher with `takePersistableUriPermission`.

- [ ] **Step 1: Create the drawer composable**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VideoLibrary
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.DrawerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.videoplayer.app.data.saf.LibrarySourceId
import com.videoplayer.app.data.saf.SavedFolder

@Composable
fun LibraryDrawer(
    drawerState: DrawerState,
    savedFolders: List<SavedFolder>,
    activeSource: LibrarySourceId,
    onSelectGlobal: () -> Unit,
    onSelectFolder: (SavedFolder) -> Unit,
    onAddFolder: () -> Unit,
    onRemoveFolder: (SavedFolder) -> Unit,
    content: @Composable () -> Unit,
) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                LazyColumn(Modifier.padding(horizontal = 12.dp)) {
                    item {
                        NavigationDrawerItem(
                            icon = { Icon(Icons.AutoMirrored.Filled.VideoLibrary, null) },
                            label = { Text("All videos") },
                            selected = activeSource is LibrarySourceId.Global,
                            onClick = onSelectGlobal,
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                        )
                    }
                    item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                    items(savedFolders, key = { it.treeUri }) { folder ->
                        NavigationDrawerItem(
                            icon = { Icon(Icons.Filled.Folder, null) },
                            label = { Text(folder.displayName) },
                            selected = activeSource == LibrarySourceId.Folder(folder.treeUri),
                            onClick = { onSelectFolder(folder) },
                            badge = {
                                IconButton(onClick = { onRemoveFolder(folder) }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Remove ${folder.displayName}")
                                }
                            },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                        )
                    }
                    item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                    item {
                        NavigationDrawerItem(
                            icon = { Icon(Icons.Filled.Add, null) },
                            label = { Text("Add folder…") },
                            selected = false,
                            onClick = onAddFolder,
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                        )
                    }
                }
            }
        },
        content = content,
    )
}
```

- [ ] **Step 2: Wire the drawer + SAF launcher into `VideoPlayerApp`**

The `LibrarySourceManager` is already constructed in `VideoPlayerApp` (Task 7). Here, wrap the library branch (the final `else ->` Scaffold) in the drawer and add the folder picker. Add imports:

```kotlin
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.videoplayer.app.data.saf.LibrarySourceId
import com.videoplayer.app.data.saf.SavedFolder
import com.videoplayer.app.library.LibraryDrawer
import kotlinx.coroutines.launch
```

```kotlin
// inside the library `else ->` branch, wrap LibraryScreen:
val drawerState = rememberDrawerState(DrawerValue.Closed)
val scope = rememberCoroutineScope()
val context = LocalContext.current
val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
    if (uri != null) {
        context.contentResolver.takePersistableUriPermission(
            uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        val name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "Folder"
        libraryViewModel.addFolder(SavedFolder(uri.toString(), name))
        scope.launch { drawerState.close() }
    }
}
LibraryDrawer(
    drawerState = drawerState,
    savedFolders = uiState.savedFolders,
    activeSource = uiState.activeSource,
    onSelectGlobal = { libraryViewModel.selectSource(LibrarySourceId.Global); scope.launch { drawerState.close() } },
    onSelectFolder = { f -> libraryViewModel.selectSource(LibrarySourceId.Folder(f.treeUri)); scope.launch { drawerState.close() } },
    onAddFolder = { pickFolder.launch(null) },
    onRemoveFolder = { f -> libraryViewModel.removeFolder(f.treeUri) },
) {
    Scaffold { innerPadding ->
        LibraryScreen(
            viewModel = libraryViewModel,
            onItemClick = { selected = it },
            onOpenSettings = { showSettings = true },
            onOpenDrawer = { scope.launch { drawerState.open() } },   // add this param to LibraryScreen's top bar
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        )
    }
}
```

> `LibraryScreen` must expose a way to open the drawer — add an `onOpenDrawer: () -> Unit = {}` parameter and a hamburger `IconButton(Icons.Filled.Menu)` as the top app bar's `navigationIcon` (read `LibraryScreen.kt` first and follow its existing top-bar style; it already has an `onOpenSettings` IconButton to mirror). Also surface the active scope name as the title when `activeSource is LibrarySourceId.Folder` by passing the matching `SavedFolder.displayName` into `LibraryScreen`.

> The `libraryDataStore` extension and the manager construction were already added in Task 7 — do not duplicate them here.

- [ ] **Step 3: Build the debug APK**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/videoplayer/app/library/LibraryDrawer.kt app/src/main/kotlin/com/videoplayer/app/VideoPlayerApp.kt app/src/main/kotlin/com/videoplayer/app/library/LibraryScreen.kt
git commit -m "feat(library): left drawer with SAF folder picker and scope switching"
```

---

## Task 9: End-to-end emulator smoke + final verification

**Files:** none (verification only).

- [ ] **Step 1: Full unit suite**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew test`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 2: Install on the emulator**

```bash
~/Library/Android/sdk/emulator/emulator -avd kuran_test &   # if not already running
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:installDebug
```

- [ ] **Step 3: Seed a nested folder tree incl. a hidden subfolder**

```bash
adb shell mkdir -p /sdcard/SafTest/sub /sdcard/SafTest/.hidden
# push three short sample videos (use any small .mp4 on hand):
adb push sample.mp4 /sdcard/SafTest/a.mp4
adb push sample.mp4 /sdcard/SafTest/sub/b.mp4
adb push sample.mp4 /sdcard/SafTest/.hidden/c.mp4
```

- [ ] **Step 4: Manual smoke checklist** (launch app, then verify each):
  - Hamburger opens the drawer; "All videos" is selected and shows the global library.
  - "Add folder…" opens the OS picker; navigate to `SafTest`, "Use this folder", allow access.
  - The view scopes to SafTest and lists **a.mp4, b.mp4, and c.mp4** (the hidden `.hidden/c.mp4` included), grouped by subfolder.
  - Durations appear within a couple seconds after the list shows.
  - Tapping a video plays it; back returns to the scoped view.
  - Drawer → "All videos" returns to the global library; the saved folder remains in the drawer.
  - Remove the folder via its ✕; it disappears and the view falls back to All videos.
  - Force-stop and relaunch: the saved folder is still in the drawer and still scopes correctly (persisted permission survived).

- [ ] **Step 5: Capture a screenshot of the scoped view for the record**

```bash
adb exec-out screencap -p > /tmp/saf-scope.png
```

- [ ] **Step 6: Final commit (if any smoke-fix tweaks were needed)**

```bash
git add -A && git commit -m "test(saf): emulator smoke verified folder scopes incl. hidden folders"
```

---

## Self-Review

**Spec coverage:**
- §2 drawer with All videos + saved folders + Add folder → Task 8. ✅
- §3.1 SafFolderRepository + efficient `DocumentsContract` traversal → Tasks 1, 4. ✅
- §3.1 document→MediaItem mapping (negative id, derived folderPath) → Task 1. ✅
- §3.1 LibrarySourceStore (DataStore) → Task 3; LibrarySourceManager (flatMapLatest) → Task 6. ✅
- §3.2 ViewModel/app wiring, playback unchanged → Tasks 7, 8. ✅
- §3.3 background durations → Task 5. ✅
- §4 permissions (take/release persistable, validate on launch) → Task 8 (take), Task 6/3 (release on remove). ⚠ **Launch-time validation against `persistedUriPermissions` (showing revoked folders as unavailable) is described in the spec but only lightly covered.** Acceptable for v1: a revoked folder simply enumerates empty; add the explicit "unavailable" badge as a follow-up if smoke shows a poor UX.
- §5 testing strategy → Tasks 1,2,3,6,7 (unit) + Task 9 (smoke). ✅

**Placeholder scan:** No TBD/TODO; every code step shows complete code. ✅

**Type consistency:** `walkSafTree`, `SafEntry`, `safMediaId`, `SavedFolder`, `LibrarySourceId.{Global,Folder}`, `LibrarySourceStore`, `LibrarySourceManager.activeFolders()/refreshActive/selectSource/addFolder/removeFolder`, `LibraryViewModel(sourceManager, memorySource)` used consistently across tasks. ✅

**Known integration risk (flagged for executor):** existing tests or call sites that build `LibraryViewModel(MediaStoreRepository(...), ...)` directly will break with the new constructor — Task 7 Step 5 catches this and instructs the fix. The executor must grep for `LibraryViewModel(` and `MemorySource` before finalizing Task 7.
