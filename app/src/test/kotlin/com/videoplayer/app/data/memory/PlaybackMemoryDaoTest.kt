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
class PlaybackMemoryDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: PlaybackMemoryDao

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.playbackMemoryDao()
    }

    @After fun tearDown() {
        db.close()
    }

    @Test fun `missing uri returns null`() = runTest {
        assertThat(dao.getByUri("nope")).isNull()
    }

    @Test fun `upsert then read round-trips`() = runTest {
        val e = PlaybackMemoryEntity("file:///a.mp4", 30_000, 120_000, "FILL", 1.5f, 111L)
        dao.upsert(e)
        assertThat(dao.getByUri("file:///a.mp4")).isEqualTo(e)
    }

    @Test fun `upsert replaces an existing row`() = runTest {
        dao.upsert(PlaybackMemoryEntity("u", 10, 100, "FIT", 1f, 1L))
        dao.upsert(PlaybackMemoryEntity("u", 50, 100, "ZOOM", 2f, 2L))
        val row = dao.getByUri("u")!!
        assertThat(row.positionMs).isEqualTo(50)
        assertThat(row.aspectMode).isEqualTo("ZOOM")
    }

    @Test fun `forward-compat reserved columns round-trip`() = runTest {
        val e = PlaybackMemoryEntity(
            mediaUri = "u", positionMs = 1, durationMs = 2, aspectMode = "FIT",
            speed = 1f, updatedAtEpochMs = 0L,
            audioTrackId = "a1", subtitleTrackId = "s1", subtitleOffsetMs = -50,
            orientation = 0, v2LoopMode = "AB", v2NativeSubtitleTrackId = "n1",
        )
        dao.upsert(e)
        assertThat(dao.getByUri("u")).isEqualTo(e)
    }
}
