// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.player

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.videoplayer.app.MainDispatcherRule
import com.videoplayer.app.data.memory.AppDatabase
import com.videoplayer.app.data.memory.PlaybackMemoryRepository
import com.videoplayer.app.data.memory.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PlayerViewModelTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var repo: PlaybackMemoryRepository
    private val dsScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val dataStore = PreferenceDataStoreFactory.create(scope = dsScope) {
            tmp.newFile("settings.preferences_pb")
        }
        repo = PlaybackMemoryRepository(db.playbackMemoryDao(), SettingsRepository(dataStore))
    }

    @After fun tearDown() {
        db.close()
        dsScope.cancel()
    }

    @Test fun `load publishes resolved defaults for an unknown file`() = runTest {
        val vm = PlayerViewModel(repo)
        vm.load("file:///unknown.mp4")
        val r = vm.resolved.filterNotNull().first()
        assertThat(r.startPositionMs).isEqualTo(0L)
        assertThat(r.aspectMode).isEqualTo("FIT")
        assertThat(r.speed).isEqualTo(1f)
    }

    @Test fun `load resumes a previously persisted position`() = runTest {
        repo.persist("u", positionMs = 42_000, durationMs = 120_000, speed = 1f, aspectMode = "FILL", nowEpochMs = 1L)
        val vm = PlayerViewModel(repo)
        vm.load("u")
        val r = vm.resolved.filterNotNull().first()
        assertThat(r.startPositionMs).isEqualTo(42_000L)
        assertThat(r.aspectMode).isEqualTo("FILL")
    }

    @Test fun `resolved is tagged with the loaded media uri`() = runTest {
        // The player's per-file effects ignore a resolved result whose mediaUri != the current
        // item's uri (stale-resolved guard); this protects the field they rely on.
        val vm = PlayerViewModel(repo)
        vm.load("content://media/external/video/media/42")
        val r = vm.resolved.filterNotNull().first()
        assertThat(r.mediaUri).isEqualTo("content://media/external/video/media/42")
    }
}