// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MediaFolderTest {

    @Test
    fun `videoCount reflects the number of items`() {
        val folder = MediaFolder(
            path = "/movies",
            name = "movies",
            items = listOf(sampleItem(1), sampleItem(2)),
        )
        assertThat(folder.videoCount).isEqualTo(2)
    }

    @Test
    fun `videoCount is zero for an empty folder`() {
        val folder = MediaFolder(path = "/movies", name = "movies", items = emptyList())
        assertThat(folder.videoCount).isEqualTo(0)
    }

    private fun sampleItem(id: Long) = MediaItem(
        id = id,
        uri = "content://media/$id",
        displayName = "video$id.mp4",
        folderPath = "/movies",
        durationMs = 1_000,
        sizeBytes = 10,
        dateAddedSec = 0,
    )
}