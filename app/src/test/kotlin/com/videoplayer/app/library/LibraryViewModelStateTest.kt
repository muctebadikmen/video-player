// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.library

import com.google.common.truth.Truth.assertThat
import com.videoplayer.app.MainDispatcherRule
import com.videoplayer.app.data.memory.PlaybackMemoryEntity
import com.videoplayer.core.model.MediaFolder
import com.videoplayer.core.model.MediaItem
import com.videoplayer.core.model.SortKey
import com.videoplayer.core.model.SortOrder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelStateTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private fun item(name: String, uri: String = "uri/$name", dur: Long = 120_000) =
        MediaItem(name.hashCode().toLong(), uri, name, "/f", dur, 0, 0)

    @Test fun `uiState exposes sorted videos and per-item progress`() = runTest {
        val folders = listOf(MediaFolder("/f", "f", listOf(item("b.mp4"), item("a.mp4"))))
        val memory = listOf(PlaybackMemoryEntity("uri/a.mp4", 30_000, 120_000, "FIT", 1f, 5))
        val vm = LibraryViewModel(fakeSourceManager(FakeMediaRepository(folders)), FakeMemorySource(memory), FakeThumbnailController(), FakeGridSizePreferences())
        vm.refresh()
        advanceUntilIdle()
        val s = vm.uiState.value
        assertThat(s.videos.map { it.displayName }).containsExactly("a.mp4", "b.mp4").inOrder()
        assertThat(s.progressByUri["uri/a.mp4"]).isWithin(0.0001f).of(0.25f)
        assertThat(s.continueWatching.map { it.item.displayName }).containsExactly("a.mp4")
    }

    @Test fun `setQuery filters videos`() = runTest {
        val folders = listOf(MediaFolder("/f", "f", listOf(item("holiday.mp4"), item("work.mkv"))))
        val vm = LibraryViewModel(fakeSourceManager(FakeMediaRepository(folders)), FakeMemorySource(emptyList()), FakeThumbnailController(), FakeGridSizePreferences())
        vm.refresh(); advanceUntilIdle()
        vm.setQuery("holi"); advanceUntilIdle()
        assertThat(vm.uiState.value.videos.map { it.displayName }).containsExactly("holiday.mp4")
    }

    @Test fun `setSort reorders videos`() = runTest {
        val folders = listOf(MediaFolder("/f", "f", listOf(item("a.mp4"), item("b.mp4"))))
        val vm = LibraryViewModel(fakeSourceManager(FakeMediaRepository(folders)), FakeMemorySource(emptyList()), FakeThumbnailController(), FakeGridSizePreferences())
        vm.refresh(); advanceUntilIdle()
        vm.setSort(SortKey.NAME, SortOrder.DESC); advanceUntilIdle()
        assertThat(vm.uiState.value.videos.map { it.displayName }).containsExactly("b.mp4", "a.mp4").inOrder()
    }
}