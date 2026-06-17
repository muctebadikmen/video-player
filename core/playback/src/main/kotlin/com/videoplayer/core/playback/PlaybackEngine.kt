package com.videoplayer.core.playback

import kotlinx.coroutines.flow.StateFlow

/**
 * The single abstraction the UI talks to for playback. Implementations wrap a
 * concrete engine (Media3/ExoPlayer today, libmpv later) and expose a uniform
 * surface so the rest of the app is engine-agnostic — the key seam that lets a
 * second engine slot in without UI changes.
 */
interface PlaybackEngine {
    /** Observable, always-current playback snapshot. */
    val state: StateFlow<PlaybackState>

    /** Load a media [uri] (opaque to the engine; e.g. `content://` or `file://`). */
    fun setMediaUri(uri: String)

    fun play()
    fun pause()

    /** Seek to [positionMs]; implementations clamp into `0..durationMs`. */
    fun seekTo(positionMs: Long)

    /** Set playback [speed] (1f = normal). */
    fun setSpeed(speed: Float)

    /** Release all engine resources. The instance must not be used afterward. */
    fun release()
}
