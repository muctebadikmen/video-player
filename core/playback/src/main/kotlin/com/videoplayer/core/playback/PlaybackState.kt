// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.core.playback

/** Lifecycle status of the player, mirrored from the underlying engine. */
enum class PlayerStatus { IDLE, BUFFERING, READY, ENDED }

/**
 * Immutable snapshot of playback, surfaced as a [kotlinx.coroutines.flow.StateFlow]
 * by every [PlaybackEngine]. The UI renders purely from this — it never reaches
 * into the engine for state.
 */
data class PlaybackState(
    val status: PlayerStatus = PlayerStatus.IDLE,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    /** Intrinsic display aspect ratio (width/height, pixel-corrected) of the current video; 0 until known. */
    val videoAspectRatio: Float = 0f,
    val engine: EngineType = EngineType.MEDIA3,
    val audioSessionId: Int = 0,
    val currentMediaIndex: Int = 0,
    /** Embedded (in-container) subtitle tracks of the current media; empty until known. */
    val textTracks: List<TextTrackInfo> = emptyList(),
    /** Id of the selected embedded text track (see PlaybackEngine.selectEmbeddedTextTrack), or null when none/disabled. */
    val selectedTextTrackId: String? = null,
)