// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.data.memory

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoThumbnailDao {
    @Upsert
    suspend fun upsert(entity: VideoThumbnailEntity)

    @Query("SELECT * FROM video_thumbnail WHERE mediaUri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): VideoThumbnailEntity?

    @Query("SELECT * FROM video_thumbnail")
    fun observeAll(): Flow<List<VideoThumbnailEntity>>
}
