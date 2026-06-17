package com.videoplayer.app.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.videoplayer.core.model.MediaFolder
import com.videoplayer.core.model.MediaRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Surfaces the media library as observable UI state. Folders come straight from
 * the [MediaRepository]; [refresh] triggers a (re)scan.
 */
class LibraryViewModel(
    private val repository: MediaRepository,
) : ViewModel() {

    val folders: StateFlow<List<MediaFolder>> = repository.observeFolders()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun refresh() {
        viewModelScope.launch { repository.refresh() }
    }
}
