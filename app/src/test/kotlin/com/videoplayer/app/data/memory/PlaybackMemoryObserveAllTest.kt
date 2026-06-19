// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.data.memory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaybackMemoryObserveAllTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: PlaybackMemoryDao

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        dao = db.playbackMemoryDao()
    }

    @After fun tearDown() { db.close() }

    @Test fun `observeAll emits empty then the inserted rows`() = runTest {
        assertThat(dao.observeAll().first()).isEmpty()
        dao.upsert(PlaybackMemoryEntity("a", 1, 2, "FIT", 1f, 10))
        dao.upsert(PlaybackMemoryEntity("b", 3, 4, "FILL", 1f, 20))
        assertThat(dao.observeAll().first().map { it.mediaUri }).containsExactly("a", "b")
    }
}