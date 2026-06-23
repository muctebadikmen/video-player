// SPDX-License-Identifier: GPL-3.0-or-later
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

    @Test fun `seekToNext advances to the next item and resets position`() {
        val e = FakePlaybackEngine(fakeDurationMs = 100_000)
        e.setMediaPlaylist(listOf("a", "b", "c"), startIndex = 0)
        e.seekTo(5_000)
        e.seekToNext()
        assertThat(e.state.value.currentMediaIndex).isEqualTo(1)
        assertThat(e.state.value.positionMs).isEqualTo(0)
    }

    @Test fun `seekToNext is a no-op at the last item`() {
        val e = FakePlaybackEngine()
        e.setMediaPlaylist(listOf("a", "b", "c"), startIndex = 2)
        e.seekToNext()
        assertThat(e.state.value.currentMediaIndex).isEqualTo(2)
    }

    @Test fun `seekToPrevious past the threshold restarts the current item`() {
        val e = FakePlaybackEngine(fakeDurationMs = 100_000)
        e.setMediaPlaylist(listOf("a", "b", "c"), startIndex = 1)
        e.seekTo(5_000) // > 3s threshold
        e.seekToPrevious()
        assertThat(e.state.value.currentMediaIndex).isEqualTo(1) // same item
        assertThat(e.state.value.positionMs).isEqualTo(0) // restarted
    }

    @Test fun `seekToPrevious within the threshold steps to the previous item`() {
        val e = FakePlaybackEngine(fakeDurationMs = 100_000)
        e.setMediaPlaylist(listOf("a", "b", "c"), startIndex = 1)
        // position is 0 (< 3s threshold)
        e.seekToPrevious()
        assertThat(e.state.value.currentMediaIndex).isEqualTo(0)
        assertThat(e.state.value.positionMs).isEqualTo(0)
    }

    @Test fun `seekToPrevious at the first item restarts instead of underflowing`() {
        val e = FakePlaybackEngine(fakeDurationMs = 100_000)
        e.setMediaPlaylist(listOf("a", "b", "c"), startIndex = 0)
        e.seekTo(5_000)
        e.seekToPrevious()
        assertThat(e.state.value.currentMediaIndex).isEqualTo(0)
        assertThat(e.state.value.positionMs).isEqualTo(0)
    }
}