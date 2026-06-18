package com.videoplayer.core.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FakePlaybackEnginePlaylistTest {
    @Test fun `setMediaPlaylist becomes ready at start index`() {
        val e = FakePlaybackEngine(fakeDurationMs = 1000)
        e.setMediaPlaylist(listOf("a", "b", "c"), startIndex = 2)
        assertThat(e.state.value.status).isEqualTo(PlayerStatus.READY)
        assertThat(e.state.value.currentMediaIndex).isEqualTo(2)
        assertThat(e.state.value.durationMs).isEqualTo(1000)
    }
    @Test fun `setMediaPlaylist clamps out-of-range start index`() {
        val e = FakePlaybackEngine()
        e.setMediaPlaylist(listOf("a", "b"), startIndex = 9)
        assertThat(e.state.value.currentMediaIndex).isEqualTo(1)
    }
    @Test fun `pause at end of media items flag is recorded`() {
        val e = FakePlaybackEngine()
        e.setPauseAtEndOfMediaItems(true)
        assertThat(e.pauseAtEndOfMediaItems).isTrue()
    }
    @Test fun `currentMediaIndex defaults to zero`() {
        assertThat(PlaybackState().currentMediaIndex).isEqualTo(0)
    }
}
