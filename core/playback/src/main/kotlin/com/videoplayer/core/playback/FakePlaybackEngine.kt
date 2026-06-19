// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.core.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Deterministic in-memory [PlaybackEngine] for unit tests and Compose previews.
 * No threads, no real decoding — every call updates [state] synchronously, so
 * tests can assert on it immediately. [fakeDurationMs] is reported once media
 * is set, which lets seek-clamping be exercised.
 */
class FakePlaybackEngine(private val fakeDurationMs: Long = 0) : PlaybackEngine {

    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    override fun setMediaUri(uri: String) = _state.update {
        it.copy(status = PlayerStatus.READY, durationMs = fakeDurationMs, positionMs = 0)
    }

    override fun play() = _state.update { it.copy(isPlaying = true) }

    override fun pause() = _state.update { it.copy(isPlaying = false) }

    override fun seekTo(positionMs: Long) = _state.update {
        it.copy(positionMs = positionMs.coerceIn(0, it.durationMs))
    }

    override fun setSpeed(speed: Float) = _state.update { it.copy(speed = speed) }

    override fun release() = _state.update { PlaybackState() }

    var pauseAtEndOfMediaItems: Boolean = false
        private set

    override fun setMediaPlaylist(uris: List<String>, startIndex: Int) = _state.update {
        val idx = if (uris.isEmpty()) 0 else startIndex.coerceIn(0, uris.lastIndex)
        it.copy(status = PlayerStatus.READY, durationMs = fakeDurationMs, positionMs = 0, currentMediaIndex = idx)
    }

    override fun setPauseAtEndOfMediaItems(enabled: Boolean) {
        pauseAtEndOfMediaItems = enabled
    }

    override fun selectEmbeddedTextTrack(id: String?) = _state.update { it.copy(selectedTextTrackId = id) }
}