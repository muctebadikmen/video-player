// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.data.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room database for playback persistence.
 *
 * `exportSchema = false`: there is a single schema version and no migrations yet.
 * When the schema version is bumped (a non-reserved column change), flip this to
 * `true`, add a `schemas/` dir + `room.schemaLocation` KSP arg, and provide a Migration.
 * Adding values to the reserved nullable columns does NOT require a version bump.
 */
@Database(entities = [PlaybackMemoryEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun playbackMemoryDao(): PlaybackMemoryDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "video_player.db",
                ).build().also { INSTANCE = it }
            }
    }
}