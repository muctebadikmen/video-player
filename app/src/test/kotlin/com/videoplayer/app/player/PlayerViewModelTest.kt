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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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

    @get:Rule val mainDispatcherRule = MainDispatcherRule()
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
    }

    @Test fun `load publishes resolved defaults for an unknown file`() = runTest {
        val vm = PlayerViewModel(repo)
        vm.load("file:///unknown.mp4")
        // viewModelScope runs on Dispatchers.Main (test dispatcher); DataStore on IO.
        // Drain the test scheduler first, then yield to IO threads to let DataStore emit.
        advanceUntilIdle()
        withContext(Dispatchers.IO) { Thread.sleep(200) }
        advanceUntilIdle()
        val r = vm.resolved.value!!
        assertThat(r.startPositionMs).isEqualTo(0L)
        assertThat(r.aspectMode).isEqualTo("FIT")
        assertThat(r.speed).isEqualTo(1f)
    }

    @Test fun `persist then load resumes the saved position`() = runTest {
        val vm = PlayerViewModel(repo)
        vm.persist("u", positionMs = 42_000, durationMs = 120_000, speed = 1f, aspectMode = "FILL")
        advanceUntilIdle()
        withContext(Dispatchers.IO) { Thread.sleep(200) }
        advanceUntilIdle()
        vm.load("u")
        advanceUntilIdle()
        withContext(Dispatchers.IO) { Thread.sleep(200) }
        advanceUntilIdle()
        val r = vm.resolved.value!!
        assertThat(r.startPositionMs).isEqualTo(42_000L)
        assertThat(r.aspectMode).isEqualTo("FILL")
    }
}
