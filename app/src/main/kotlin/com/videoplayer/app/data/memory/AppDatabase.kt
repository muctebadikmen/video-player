// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.data.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room database for playback persistence.
 *
 * `exportSchema = false`: migrations are hand-written (no `schemas/` dir). DB v2 (v1.2.0) added the
 * non-nullable `subtitleRate` column via [MIGRATION_1_2]. Adding values to the reserved nullable
 * columns still needs no bump; a new non-reserved column (like subtitleRate) does.
 */
@Database(
    entities = [PlaybackMemoryEntity::class, VideoThumbnailEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun playbackMemoryDao(): PlaybackMemoryDao
    abstract fun videoThumbnailDao(): VideoThumbnailDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE playback_memory ADD COLUMN subtitleRate REAL NOT NULL DEFAULT 1.0",
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `video_thumbnail` (" +
                        "`mediaUri` TEXT NOT NULL, " +
                        "`customThumbnailPath` TEXT, " +
                        "`autoFrameMs` INTEGER, " +
                        "`autoResolved` INTEGER NOT NULL DEFAULT 0, " +
                        "`updatedAtEpochMs` INTEGER NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY(`mediaUri`))",
                )
            }
        }

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "video_player.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { INSTANCE = it }
            }
    }
}
