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

    // Note: releasePersistableUriPermission is intentionally NOT called here — this class is
    // Context-free by design. The app layer (VideoPlayerApp.kt) owns full teardown including
    // releasing the persistable URI permission before delegating to this method.
    suspend fun removeFolder(treeUri: String) {
        folderRepos.remove(treeUri)
        store.removeFolder(treeUri)
    }
}
