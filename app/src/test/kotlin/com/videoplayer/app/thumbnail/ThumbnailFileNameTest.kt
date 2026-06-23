// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.thumbnail

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ThumbnailFileNameTest {

    @Test fun `same uri and time produce a stable name`() {
        val a = thumbnailFileName("content://media/external/video/media/12", 555L)
        val b = thumbnailFileName("content://media/external/video/media/12", 555L)
        assertThat(a).isEqualTo(b)
        assertThat(a).endsWith("-555.jpg")
    }

    @Test fun `different uris produce different names`() {
        val a = thumbnailFileName("content://v/1", 1L)
        val b = thumbnailFileName("content://v/2", 1L)
        assertThat(a).isNotEqualTo(b)
    }

    @Test fun `re-set at a new time changes the name so Coil cache busts`() {
        val a = thumbnailFileName("content://v/1", 1L)
        val b = thumbnailFileName("content://v/1", 2L)
        assertThat(a).isNotEqualTo(b)
    }
}
