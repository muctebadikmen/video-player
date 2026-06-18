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
}
