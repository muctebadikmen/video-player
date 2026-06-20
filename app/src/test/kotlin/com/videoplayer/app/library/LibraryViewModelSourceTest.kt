// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.library

import com.google.common.truth.Truth.assertThat
import com.videoplayer.app.MainDispatcherRule
import com.videoplayer.app.data.saf.InMemorySourceStore
import com.videoplayer.app.data.saf.LibrarySourceId
import com.videoplayer.app.data.saf.LibrarySourceManager
import com.videoplayer.app.data.saf.SavedFolder
import com.videoplayer.core.model.MediaFolder
import com.videoplayer.core.model.MediaItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelSourceTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private fun folder(name: String) =
        MediaFolder(name, name, listOf(MediaItem(1, "u/$name", "$name.mp4", name, 0, 0, 0)))

    @Test fun `state exposes saved folders and switches active source`() = runTest {
        val store = InMemorySourceStore()
        val manager = LibrarySourceManager(
            store,
            FakeMediaRepository(listOf(folder("Global"))),
        ) { FakeMediaRepository(listOf(folder("Scoped"))) }
        val vm = LibraryViewModel(manager, FakeMemorySource(emptyList()))

        vm.addFolder(SavedFolder("content://tree/a", "Scoped"))   // also selects + refreshes it
        advanceUntilIdle()

        val s = vm.uiState.value
        assertThat(s.savedFolders).contains(SavedFolder("content://tree/a", "Scoped"))
        assertThat(s.activeSource).isEqualTo(LibrarySourceId.Folder("content://tree/a"))
        assertThat(s.folders.single().name).isEqualTo("Scoped")

        vm.selectSource(LibrarySourceId.Global); advanceUntilIdle()
        assertThat(vm.uiState.value.folders.single().name).isEqualTo("Global")
    }
}
