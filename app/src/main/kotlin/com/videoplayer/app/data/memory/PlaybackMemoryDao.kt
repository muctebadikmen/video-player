package com.videoplayer.app.data.memory

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface PlaybackMemoryDao {
    @Upsert
    suspend fun upsert(entity: PlaybackMemoryEntity)

    @Query("SELECT * FROM playback_memory WHERE mediaUri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): PlaybackMemoryEntity?
}
