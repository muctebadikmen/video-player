// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.library

import com.videoplayer.core.model.MediaFolder
import com.videoplayer.core.model.MediaRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-memory [MediaRepository] for tests: [refresh] publishes [foldersToEmit]. */
class FakeMediaRepository(
    private val foldersToEmit: List<MediaFolder>,
) : MediaRepository {
    private val folders = MutableStateFlow<List<MediaFolder>>(emptyList())
    override fun observeFolders(): Flow<List<MediaFolder>> = folders.asStateFlow()
    override suspend fun refresh() {
        folders.value = foldersToEmit
    }
}