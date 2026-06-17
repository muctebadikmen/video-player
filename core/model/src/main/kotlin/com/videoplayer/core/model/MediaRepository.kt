package com.videoplayer.core.model

import kotlinx.coroutines.flow.Flow

/**
 * Read-only repository that surfaces the user's video library as a stream of [MediaFolder] lists.
 *
 * Platform implementations (e.g. MediaStore-backed on Android) are in the :app module.
 * The interface lives here so ViewModel / domain code can depend on it without touching Android.
 */
interface MediaRepository {
    /** Emits the current folder list and any subsequent updates. */
    fun observeFolders(): Flow<List<MediaFolder>>

    /** Re-queries the underlying data source and refreshes the flow. */
    suspend fun refresh()
}
