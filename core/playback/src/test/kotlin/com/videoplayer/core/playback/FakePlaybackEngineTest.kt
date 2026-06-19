// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.core.playback

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FakePlaybackEngineTest {

    @Test
    fun `starts idle and not playing`() = runTest {
        val engine = FakePlaybackEngine()
        assertThat(engine.state.value.status).isEqualTo(PlayerStatus.IDLE)
        assertThat(engine.state.value.isPlaying).isFalse()
    }

    @Test
    fun `setMediaUri moves to ready with the media duration`() = runTest {
        val engine = FakePlaybackEngine(fakeDurationMs = 60_000)
        engine.setMediaUri("file:///a.mp4")
        assertThat(engine.state.value.status).isEqualTo(PlayerStatus.READY)
        assertThat(engine.state.value.durationMs).isEqualTo(60_000)
    }

    @Test
    fun `play then pause toggles isPlaying`() = runTest {
        val engine = FakePlaybackEngine()
        engine.setMediaUri("u")
        engine.play()
        assertThat(engine.state.value.isPlaying).isTrue()
        engine.pause()
        assertThat(engine.state.value.isPlaying).isFalse()
    }

    @Test
    fun `seekTo clamps within zero and duration`() = runTest {
        val engine = FakePlaybackEngine(fakeDurationMs = 1_000)
        engine.setMediaUri("u")
        engine.seekTo(5_000)
        assertThat(engine.state.value.positionMs).isEqualTo(1_000)
        engine.seekTo(-10)
        assertThat(engine.state.value.positionMs).isEqualTo(0)
    }

    @Test
    fun `setSpeed updates state`() = runTest {
        val engine = FakePlaybackEngine()
        engine.setSpeed(2f)
        assertThat(engine.state.value.speed).isEqualTo(2f)
    }

    @Test
    fun `release resets state to defaults`() = runTest {
        val engine = FakePlaybackEngine(fakeDurationMs = 1_000)
        engine.setMediaUri("u")
        engine.play()
        engine.release()
        assertThat(engine.state.value).isEqualTo(PlaybackState())
    }

    @Test fun `textTracks default empty and no track selected`() = runTest {
        val e = FakePlaybackEngine()
        assertThat(e.state.value.textTracks).isEmpty()
        assertThat(e.state.value.selectedTextTrackId).isNull()
    }

    @Test fun `selectEmbeddedTextTrack updates and clears selectedTextTrackId`() = runTest {
        val e = FakePlaybackEngine()
        e.selectEmbeddedTextTrack("text:0:1")
        assertThat(e.state.value.selectedTextTrackId).isEqualTo("text:0:1")
        e.selectEmbeddedTextTrack(null)
        assertThat(e.state.value.selectedTextTrackId).isNull()
    }
}