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
    val engine: EngineType = EngineType.MEDIA3,
)
