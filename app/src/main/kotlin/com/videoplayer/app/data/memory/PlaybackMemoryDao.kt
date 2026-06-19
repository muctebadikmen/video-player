// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.data.memory

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaybackMemoryDao {
    @Upsert
    suspend fun upsert(entity: PlaybackMemoryEntity)

    @Query("SELECT * FROM playback_memory WHERE mediaUri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): PlaybackMemoryEntity?

    @Query("SELECT * FROM playback_memory")
    fun observeAll(): Flow<List<PlaybackMemoryEntity>>
}