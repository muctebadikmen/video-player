// SPDX-License-Identifier: GPL-3.0-or-later
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
    val isLoading: Boolean = true,
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
    private val loading = MutableStateFlow(true)

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

    fun refresh() { viewModelScope.launch { mediaRepository.refresh(); loading.value = false } }
    fun setTab(tab: LibraryTab) { controls.value = controls.value.copy(tab = tab) }
    fun setViewMode(mode: ViewMode) { controls.value = controls.value.copy(viewMode = mode) }
    fun setSort(key: SortKey, order: SortOrder) { controls.value = controls.value.copy(sortKey = key, sortOrder = order) }
    fun setQuery(query: String) { controls.value = controls.value.copy(query = query) }
}