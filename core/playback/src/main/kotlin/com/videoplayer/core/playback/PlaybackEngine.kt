// SPDX-License-Identifier: GPL-3.0-or-later
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

    /**
     * Seek to [positionMs]. Negative values clamp to 0; values past the media
     * duration are clamped by the engine (the exact upper bound is engine-defined).
     */
    fun seekTo(positionMs: Long)

    /** Set playback [speed] (1f = normal). */
    fun setSpeed(speed: Float)

    /** Release all engine resources. The instance must not be used afterward. */
    fun release()

    /** Replace the queue with [uris] and start at [startIndex]. */
    fun setMediaPlaylist(uris: List<String>, startIndex: Int)

    /** When true, pause at the end of each media item instead of advancing. */
    fun setPauseAtEndOfMediaItems(enabled: Boolean)

    /**
     * Select an embedded text (subtitle) track by its [id] (from [PlaybackState.textTracks]),
     * or pass null to disable embedded text output. Ids are engine-defined and opaque to callers.
     */
    fun selectEmbeddedTextTrack(id: String?)
}