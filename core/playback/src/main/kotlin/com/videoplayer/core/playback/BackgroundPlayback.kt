package com.videoplayer.core.playback

/**
 * Whether a MediaSessionService should stop itself when its task is removed
 * (app swiped from recents). Stop unless something is actively playing.
 */
fun shouldStopOnTaskRemoved(playWhenReady: Boolean, mediaItemCount: Int): Boolean =
    !playWhenReady || mediaItemCount == 0
