// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.library

import com.google.common.truth.Truth.assertThat
import com.videoplayer.app.MainDispatcherRule
import com.videoplayer.core.model.MediaFolder
import com.videoplayer.core.model.MediaItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `folders is empty before refresh`() = runTest {
        val vm = LibraryViewModel(fakeSourceManager(FakeMediaRepository(listOf(folder("m")))), FakeMemorySource(emptyList()), FakeGridSizePreferences())
        assertThat(vm.uiState.value.folders).isEmpty()
    }

    @Test
    fun `refresh publishes folders from the repository`() = runTest {
        val vm = LibraryViewModel(fakeSourceManager(FakeMediaRepository(listOf(folder("movies"), folder("clips")))), FakeMemorySource(emptyList()), FakeGridSizePreferences())
        vm.refresh()
        advanceUntilIdle()
        assertThat(vm.uiState.value.folders.map { it.name }).containsExactly("movies", "clips")
    }

    private fun folder(name: String) = MediaFolder(
        path = "/$name",
        name = name,
        items = listOf(
            MediaItem(1, "uri", "v.mp4", "/$name", 1_000, 10, 0),
        ),
    )
}