// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.library

import com.google.common.truth.Truth.assertThat
import com.videoplayer.app.MainDispatcherRule
import com.videoplayer.app.data.memory.VideoThumbnailEntity
import com.videoplayer.app.thumbnail.ThumbnailSpec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class LibraryViewModelThumbnailTest {

    @get:Rule val mainRule = MainDispatcherRule()

    private fun viewModel(thumbs: FakeThumbnailController) = LibraryViewModel(
        sourceManager = fakeSourceManager(FakeMediaRepository(emptyList())),
        memorySource = FakeMemorySource(emptyList()),
        thumbnails = thumbs,
        settings = FakeGridSizePreferences(),
    )

    @Test fun `thumbnailByUri maps a resolved auto row to AutoFrame`() = runTest {
        val thumbs = FakeThumbnailController()
        val vm = viewModel(thumbs)
        thumbs.emit(listOf(VideoThumbnailEntity("u", autoFrameMs = 2500L, autoResolved = true)))
        val state = vm.uiState.first { it.thumbnailByUri.containsKey("u") }
        assertThat(state.thumbnailByUri["u"]).isEqualTo(ThumbnailSpec.AutoFrame(2500L))
    }

    @Test fun `custom row wins over auto`() = runTest {
        val thumbs = FakeThumbnailController()
        val vm = viewModel(thumbs)
        thumbs.emit(listOf(VideoThumbnailEntity("u", "/p.jpg", 2500L, true, 9L)))
        val state = vm.uiState.first { it.thumbnailByUri["u"] is ThumbnailSpec.Custom }
        assertThat(state.thumbnailByUri["u"]).isEqualTo(ThumbnailSpec.Custom("/p.jpg", 9L))
    }

    @Test fun `ensureThumbnail forwards to the controller`() = runTest {
        val thumbs = FakeThumbnailController()
        viewModel(thumbs).ensureThumbnail("u", 12_000L)
        assertThat(thumbs.ensured).contains("u" to 12_000L)
    }
}
