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
