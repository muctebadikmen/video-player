// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.data.saf

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.videoplayer.core.model.MediaFolder
import com.videoplayer.core.model.MediaItem
import com.videoplayer.core.model.MediaRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LibrarySourceManagerTest {

    private fun folder(name: String) =
        MediaFolder(name, name, listOf(MediaItem(1, "u/$name", "$name.mp4", name, 0, 0, 0)))

    private class FakeRepo(initial: List<MediaFolder>) : MediaRepository {
        val state = MutableStateFlow(initial)
        override fun observeFolders(): Flow<List<MediaFolder>> = state
        override suspend fun refresh() {}
    }

    @Test
    fun `activeFolders follows the active source`() = runTest {
        val store = InMemorySourceStore()
        val global = FakeRepo(listOf(folder("Global")))
        val scoped = FakeRepo(listOf(folder("Scoped")))
        val manager = LibrarySourceManager(store, global) { scoped }

        store.addFolder(SavedFolder("content://tree/a", "Scoped"))

        manager.activeFolders().test {
            assertThat(awaitItem().single().name).isEqualTo("Global")   // default
            manager.selectSource(LibrarySourceId.Folder("content://tree/a"))
            assertThat(awaitItem().single().name).isEqualTo("Scoped")
            manager.selectSource(LibrarySourceId.Global)
            assertThat(awaitItem().single().name).isEqualTo("Global")
        }
    }
}
