// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.videoplayer.app.data.memory.MemorySource
import com.videoplayer.app.data.memory.SettingsRepository
import com.videoplayer.app.data.saf.LibrarySourceId
import com.videoplayer.app.data.saf.LibrarySourceManager
import com.videoplayer.app.data.saf.SavedFolder
import com.videoplayer.core.model.MediaFolder
import com.videoplayer.core.model.MediaItem
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class LibraryTab { FOLDERS, VIDEOS }
enum class ViewMode { LIST, GRID }
enum class GridSize(val columns: Int) { SMALL(4), MEDIUM(3), LARGE(2) }

/** One library row/tile: the media plus its 0..1 resume progress. */
data class LibraryItemUi(val item: MediaItem, val progress: Float)

data class LibraryUiState(
    val tab: LibraryTab = LibraryTab.FOLDERS,
    val viewMode: ViewMode = ViewMode.GRID,
    val sortKey: SortKey = SortKey.NAME,
    val sortOrder: SortOrder = SortOrder.ASC,
    val query: String = "",
    val gridSize: GridSize = GridSize.MEDIUM,
    val folders: List<MediaFolder> = emptyList(),
    val videos: List<MediaItem> = emptyList(),
    val continueWatching: List<LibraryItemUi> = emptyList(),
    val progressByUri: Map<String, Float> = emptyMap(),
    val savedFolders: List<SavedFolder> = emptyList(),
    val activeSource: LibrarySourceId = LibrarySourceId.Global,
    val isLoading: Boolean = true,
)

private data class Controls(
    val tab: LibraryTab = LibraryTab.FOLDERS,
    val viewMode: ViewMode = ViewMode.GRID,
    val sortKey: SortKey = SortKey.NAME,
    val sortOrder: SortOrder = SortOrder.ASC,
    val query: String = "",
    val gridSize: GridSize = GridSize.MEDIUM,
)

/**
 * Combines the media library, persisted playback memory, and UI control state (tab / view mode /
 * sort / search) into a single [LibraryUiState]. Sorting + search are applied by pure :core helpers.
 */
class LibraryViewModel(
    private val sourceManager: LibrarySourceManager,
    memorySource: MemorySource,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val controls = MutableStateFlow(Controls())
    private val loading = MutableStateFlow(true)

    init {
        viewModelScope.launch {
            val savedColumns = settings.gridColumns.first()
            val savedSize = GridSize.entries.firstOrNull { it.columns == savedColumns } ?: GridSize.MEDIUM
            controls.value = controls.value.copy(gridSize = savedSize)
        }
    }

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
                gridSize = c.gridSize,
                folders = sortedFolders, videos = videos, continueWatching = cw, progressByUri = progressByUri,
                savedFolders = src.saved, activeSource = src.active, isLoading = isLoading,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryUiState())

    fun refresh() {
        viewModelScope.launch { sourceManager.refreshActive(uiState.value.activeSource); loading.value = false }
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
    fun cycleGridSize() {
        val next = when (controls.value.gridSize) {
            GridSize.SMALL -> GridSize.MEDIUM
            GridSize.MEDIUM -> GridSize.LARGE
            GridSize.LARGE -> GridSize.SMALL
        }
        controls.value = controls.value.copy(gridSize = next)
        viewModelScope.launch { settings.setGridColumns(next.columns) }
    }
}
