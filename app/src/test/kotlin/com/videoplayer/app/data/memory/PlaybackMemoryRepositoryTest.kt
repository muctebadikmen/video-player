package com.videoplayer.app.data.memory

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaybackMemoryRepositoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var repo: PlaybackMemoryRepository
    private val dsScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val dataStore = PreferenceDataStoreFactory.create(scope = dsScope) {
            tmp.newFile("settings.preferences_pb")
        }
        settings = SettingsRepository(dataStore)
        repo = PlaybackMemoryRepository(db.playbackMemoryDao(), settings)
    }

    @After fun tearDown() {
        db.close()
        dsScope.cancel()
    }

    @Test fun `resolveStart with no saved row uses global defaults`() = runTest {
        val r = repo.resolveStart("file:///new.mp4")
        assertThat(r.startPositionMs).isEqualTo(0L)
        assertThat(r.speed).isEqualTo(1f)
        assertThat(r.aspectMode).isEqualTo("FIT")
    }

    @Test fun `persist then resolveStart resumes saved position, aspect, and speed`() = runTest {
        repo.persist("u", positionMs = 40_000, durationMs = 120_000, speed = 1.5f, aspectMode = "ZOOM", nowEpochMs = 99L)
        val r = repo.resolveStart("u")
        assertThat(r.startPositionMs).isEqualTo(40_000L)
        assertThat(r.aspectMode).isEqualTo("ZOOM")  // per-file overrides global
        assertThat(r.speed).isEqualTo(1.5f)
    }

    @Test fun `resolveStart returns zero position when resume is disabled`() = runTest {
        repo.persist("u", positionMs = 40_000, durationMs = 120_000, speed = 1f, aspectMode = "FIT", nowEpochMs = 1L)
        settings.setResumeEnabled(false)
        assertThat(repo.resolveStart("u").startPositionMs).isEqualTo(0L)
    }

    @Test fun `near-end saved position restarts from zero`() = runTest {
        repo.persist("u", positionMs = 119_000, durationMs = 120_000, speed = 1f, aspectMode = "FIT", nowEpochMs = 1L)
        assertThat(repo.resolveStart("u").startPositionMs).isEqualTo(0L)
    }

    @Test fun `persist with non-positive duration does not overwrite an existing row`() = runTest {
        repo.persist("u", positionMs = 30_000, durationMs = 120_000, speed = 1.5f, aspectMode = "ZOOM", nowEpochMs = 1L)
        // Simulates a save reading a post-release/blank engine state.
        repo.persist("u", positionMs = 0, durationMs = 0, speed = 1f, aspectMode = "FIT", nowEpochMs = 2L)
        val r = repo.resolveStart("u")
        assertThat(r.startPositionMs).isEqualTo(30_000L)
        assertThat(r.aspectMode).isEqualTo("ZOOM")
    }

    @Test fun `resolveStart exposes saved orientation, or null when absent`() = runTest {
        assertThat(repo.resolveStart("none").orientation).isNull()
        repo.persistOrientation("u", orientation = 6, nowEpochMs = 5L)
        assertThat(repo.resolveStart("u").orientation).isEqualTo(6)
    }

    @Test fun `persistOrientation writes orientation and preserves position, speed, aspect`() = runTest {
        repo.persist("u", positionMs = 30_000, durationMs = 120_000, speed = 1.5f, aspectMode = "ZOOM", nowEpochMs = 1L)
        repo.persistOrientation("u", orientation = 0, nowEpochMs = 2L)
        val r = repo.resolveStart("u")
        assertThat(r.orientation).isEqualTo(0)
        assertThat(r.startPositionMs).isEqualTo(30_000L)
        assertThat(r.speed).isEqualTo(1.5f)
        assertThat(r.aspectMode).isEqualTo("ZOOM")
    }

    @Test fun `persist after persistOrientation preserves the saved orientation`() = runTest {
        repo.persistOrientation("u", orientation = 6, nowEpochMs = 1L)
        repo.persist("u", positionMs = 10_000, durationMs = 120_000, speed = 2f, aspectMode = "FILL", nowEpochMs = 2L)
        assertThat(repo.resolveStart("u").orientation).isEqualTo(6)
    }

    @Test fun `persist then resolveStart restores subtitle track and offset`() = runTest {
        repo.persist("u", positionMs = 10_000, durationMs = 120_000, speed = 1f, aspectMode = "FIT",
            subtitleTrackId = "ext:content://x", subtitleOffsetMs = 150L, nowEpochMs = 1L)
        val r = repo.resolveStart("u")
        assertThat(r.subtitleTrackId).isEqualTo("ext:content://x")
        assertThat(r.subtitleOffsetMs).isEqualTo(150L)
    }

    @Test fun `resolveStart defaults subtitle to null and zero offset when absent`() = runTest {
        val r = repo.resolveStart("none")
        assertThat(r.subtitleTrackId).isNull()
        assertThat(r.subtitleOffsetMs).isEqualTo(0L)
    }

    @Test fun `persistOrientation preserves a saved subtitle`() = runTest {
        repo.persist("u", positionMs = 10_000, durationMs = 120_000, speed = 1f, aspectMode = "FIT",
            subtitleTrackId = "embedded:text:0:0", subtitleOffsetMs = 0L, nowEpochMs = 1L)
        repo.persistOrientation("u", orientation = 6, nowEpochMs = 2L)
        assertThat(repo.resolveStart("u").subtitleTrackId).isEqualTo("embedded:text:0:0")
    }
}
