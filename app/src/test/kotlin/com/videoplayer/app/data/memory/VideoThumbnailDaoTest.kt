// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.data.memory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VideoThumbnailDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: VideoThumbnailDao

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.videoThumbnailDao()
    }

    @After fun tearDown() { db.close() }

    @Test fun `missing uri returns null`() = runTest {
        assertThat(dao.getByUri("nope")).isNull()
    }

    @Test fun `upsert then read round-trips all fields`() = runTest {
        val e = VideoThumbnailEntity("content://v/1", "/files/thumbnails/a.jpg", 4200L, true, 99L)
        dao.upsert(e)
        assertThat(dao.getByUri("content://v/1")).isEqualTo(e)
    }

    @Test fun `defaults persist for an auto-only row`() = runTest {
        dao.upsert(VideoThumbnailEntity(mediaUri = "u", autoFrameMs = 1000L, autoResolved = true))
        val row = dao.getByUri("u")!!
        assertThat(row.customThumbnailPath).isNull()
        assertThat(row.autoResolved).isTrue()
    }

    @Test fun `upsert replaces an existing row`() = runTest {
        dao.upsert(VideoThumbnailEntity("u", autoFrameMs = 1L, autoResolved = true, updatedAtEpochMs = 1L))
        dao.upsert(VideoThumbnailEntity("u", customThumbnailPath = "/p.jpg", updatedAtEpochMs = 2L))
        val row = dao.getByUri("u")!!
        assertThat(row.customThumbnailPath).isEqualTo("/p.jpg")
        assertThat(row.updatedAtEpochMs).isEqualTo(2L)
    }
}
