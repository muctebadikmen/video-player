# Folder Scopes via SAF — Design

**Date:** 2026-06-20
**Status:** Approved pending spec review
**Topic:** Let the user point the library at any folder on the device (including hidden `.folders`) and browse every video under it — its own subtree, recursively — while keeping the existing global MediaStore library available.

---

## 1. Problem & Goal

Today the Library shows only what `MediaStore` indexes, grouped one level deep by immediate
parent folder ([`groupIntoFolders`](../../../core/model/src/main/kotlin/com/videoplayer/core/model/FolderGrouping.kt)).
Two gaps:

1. **Hidden folders are invisible.** Folders starting with `.` and any folder containing a
   `.nomedia` file are deliberately skipped by MediaStore, so they can never appear.
2. **No "show me this folder tree" view.** Subfolders surface as separate sibling entries;
   there's no way to scope the whole library to one folder and see everything beneath it.

**Goal:** A left navigation drawer holding **"All videos"** (the existing global view, unchanged)
plus a user-managed list of **saved folders**. Selecting a saved folder scopes the entire Library
(Folders/Videos tabs, search, sort, grid/list) to that folder and **all of its subfolders,
recursively**. The user adds folders through the Android system folder picker, which can reach
hidden/dot folders. Switching back to "All videos" is always one tap away.

### Non-goals
- No broad `READ_EXTERNAL_STORAGE` / `MANAGE_EXTERNAL_STORAGE`; SAF persisted grants only.
- No editing/moving/deleting files. Read + play only.
- No change to playback, the player screen, settings, or the global library path.

---

## 2. User Experience

- A hamburger opens a **`ModalNavigationDrawer`** (the app has no drawer today — this is the one
  new piece of nav scaffolding).
- Drawer contents:
  ```
  ★ All videos            ← default, selected on first launch
  ─────────────
  📁 .private             ← saved folders (tap to scope; long-press / trailing icon to remove)
  📁 Movies / Anime
  📁 USB-Drive
  ─────────────
  + Add folder…           ← launches the OS folder picker
  ```
- **Add folder…** → `ACTION_OPEN_DOCUMENT_TREE`. On result we `takePersistableUriPermission`
  and persist the tree URI + a display name. The new folder appears in the drawer and becomes active.
- Selecting a saved folder rebuilds the Library from that subtree. The current scope is reflected
  in the top-bar title (e.g. "Anime" instead of the app name) so the user always knows where they are.
- Removing a folder releases its persisted URI permission and drops it from the drawer; if it was
  active, the view falls back to "All videos".

---

## 3. Architecture

Respects the module rule: **all Android/SAF code lives in `:app`; `:core:model` stays Android-free.**
`MediaItem`, `MediaFolder`, and `groupIntoFolders` are reused unchanged.

### 3.1 New components (`:app`)

**`SafFolderRepository`** — implements the existing
[`MediaRepository`](../../../core/model/src/main/kotlin/com/videoplayer/core/model/MediaRepository.kt)
interface (`observeFolders()` / `refresh()`), constructed with one tree `Uri`. On `refresh()` it walks
the tree and emits `groupIntoFolders(items)` — so the rest of the pipeline (sort/search/grouping/UI)
is identical to the global source. This is the heart of the feature.

**Efficient recursive enumeration** (inside `SafFolderRepository`):
- Use `DocumentsContract` child-document queries, **not** the slow `DocumentFile` object API.
- Start from `DocumentsContract.getTreeDocumentId(treeUri)`; for each directory query
  `buildChildDocumentsUriUsingTree(treeUri, parentDocId)` projecting
  `DOCUMENT_ID, DISPLAY_NAME, MIME_TYPE, SIZE, LAST_MODIFIED`.
- Iterative worklist (BFS/DFS over a deque, no deep recursion): MIME `vnd.android.document/directory`
  → enqueue as a directory; MIME `video/*` → emit a `MediaItem`.
- One `ContentResolver.query` per directory; runs on `Dispatchers.IO`.

**Mapping a document → `MediaItem`:**
| field | source |
|-------|--------|
| `uri` | child document URI (plays directly in Media3) |
| `id` | stable hash of the document URI string (negative, to avoid colliding with MediaStore ids) |
| `displayName` | `DISPLAY_NAME` |
| `folderPath` | derived parent path from the document id (relative to the picked root) so grouping/tabs work |
| `sizeBytes` | `SIZE` |
| `dateAddedSec` | `LAST_MODIFIED / 1000` |
| `durationMs` | `0` initially — filled in lazily (see §3.3) |

**`LibrarySourceStore`** — DataStore-backed (extends the existing `settings` DataStore pattern in
[`SettingsRepository`](../../../app/src/main/kotlin/com/videoplayer/app/data/memory/SettingsRepository.kt)).
Persists:
- the ordered list of saved folders (`treeUri` string + display name) — JSON in a single
  `stringPreferencesKey`, or a small Room table; JSON-in-DataStore is the lighter fit.
- the active source id: `GLOBAL` or a specific `treeUri`.

**`LibrarySourceManager`** — small `:app` coordinator the ViewModel depends on. Exposes:
- `activeFolders(): Flow<List<MediaFolder>>` — `flatMapLatest` over the active-source flow: emits the
  `MediaStoreRepository` stream when `GLOBAL`, otherwise the matching `SafFolderRepository` stream.
- `savedFolders: Flow<List<SavedFolder>>`, `activeSource: Flow<SourceId>`.
- `selectSource(id)`, `addFolder(treeUri)`, `removeFolder(treeUri)`, `refresh()`.
- Caches one `SafFolderRepository` per saved tree URI.

### 3.2 Wiring into existing code

- **`LibraryViewModel`** swaps its `mediaRepository.observeFolders()` source for
  `librarySourceManager.activeFolders()`. The combine/sort/search logic is untouched. Add passthrough
  state for `savedFolders` + `activeSource` and actions (`selectSource`, `addFolder`, `removeFolder`).
  The global path is byte-for-byte unchanged when active source is `GLOBAL`.
- **`VideoPlayerApp`** wraps the library branch in `ModalNavigationDrawer`; the drawer reads
  `savedFolders`/`activeSource` from the ViewModel. The `ACTION_OPEN_DOCUMENT_TREE` launcher
  (`rememberLauncherForActivityResult`) lives here and calls `viewModel.addFolder(uri)`.
- **Playback** unchanged — `PlayerScreen` already takes a `MediaItem` whose `uri` Media3 resolves;
  document URIs work as-is. The in-folder playlist (next/prev) comes from the scoped folder's items.

### 3.3 Durations (the one real tradeoff)

SAF returns name/size/date instantly but **not** duration. So:
- List videos immediately with `durationMs = 0` — snappy even on huge trees.
- A background, concurrency-limited job resolves durations via `MediaMetadataRetriever`
  (`setDataSource(context, uri)` → `METADATA_KEY_DURATION`), caches them by URI, and updates the
  flow so badges fill in progressively. Listing never blocks on it.
- Resume/continue-watching depends on duration; for SAF items it simply activates once the duration
  is known. Acceptable.

---

## 4. Permissions & Persistence

- `ACTION_OPEN_DOCUMENT_TREE` grants access to the chosen subtree with no manifest permission.
- `contentResolver.takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION)` makes the grant
  survive reboots. On `removeFolder`, call `releasePersistableUriPermission`.
- On launch, validate each saved tree URI is still in
  `contentResolver.persistedUriPermissions`; if a grant was revoked (folder deleted, SD card pulled),
  mark that drawer row as unavailable rather than crashing.

---

## 5. Testing

- **Pure / JVM-unit (`:core` + `:app`):** the parent-path derivation and any document-id → folderPath
  helper are pure functions — test-driven first. Reuse existing `groupIntoFolders` tests as the
  contract that scoped output groups correctly.
- **`SafFolderRepository` enumeration:** unit-test the worklist traversal logic against a faked
  child-query provider (inject the "list children of docId" function) so recursion, video filtering,
  and nested folders are covered without a device.
- **Emulator smoke (AVD `kuran_test`):** push a folder tree incl. a `.hidden` subfolder with sample
  videos, add it via the picker, confirm all nested videos list and play, switch back to All videos,
  remove the folder.
- Existing global-library and playback tests must stay green (regression gate).

---

## 6. Build / Verify commands

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew test                  # all JVM unit tests
./gradlew :app:assembleDebug    # build debug APK
./gradlew :app:installDebug     # install on emulator for smoke test
```

---

## 7. Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| Deep trees slow to enumerate | `DocumentsContract` queries (not `DocumentFile`); IO dispatcher; emit folders as the walk completes |
| `MediaMetadataRetriever` slow / OOM on many files | concurrency-limited background job, cache by URI, never block listing |
| Persisted URI permission revoked | validate against `persistedUriPermissions` on launch; show row as unavailable |
| Module boundary violation | all SAF/Android code in `:app`; `:core` imports unchanged |
| Scope creep into a file manager | read+play only; no file ops; multiple-but-flat saved-folder list |
