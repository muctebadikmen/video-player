package com.videoplayer.core.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaylistTest {
    private val uris = listOf("a", "b", "c")
    @Test fun `returns index of matching uri`() {
        assertThat(startIndexFor(uris, "b")).isEqualTo(1)
    }
    @Test fun `returns zero when uri absent`() {
        assertThat(startIndexFor(uris, "z")).isEqualTo(0)
    }
    @Test fun `returns zero for empty list`() {
        assertThat(startIndexFor(emptyList(), "a")).isEqualTo(0)
    }
}
