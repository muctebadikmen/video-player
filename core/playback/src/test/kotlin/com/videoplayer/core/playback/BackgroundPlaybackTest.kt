package com.videoplayer.core.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BackgroundPlaybackTest {
    @Test fun `stops when not playing`() {
        assertThat(shouldStopOnTaskRemoved(playWhenReady = false, mediaItemCount = 1)).isTrue()
    }
    @Test fun `stops when no media items`() {
        assertThat(shouldStopOnTaskRemoved(playWhenReady = true, mediaItemCount = 0)).isTrue()
    }
    @Test fun `keeps playing when playing with media`() {
        assertThat(shouldStopOnTaskRemoved(playWhenReady = true, mediaItemCount = 1)).isFalse()
    }
    @Test fun `audioSessionId defaults to zero`() {
        assertThat(PlaybackState().audioSessionId).isEqualTo(0)
    }
}
