// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.core.playback

/**
 * Whether a MediaSessionService should stop itself when its task is removed
 * (app swiped from recents). Stop unless something is actively playing.
 */
fun shouldStopOnTaskRemoved(playWhenReady: Boolean, mediaItemCount: Int): Boolean =
    !playWhenReady || mediaItemCount == 0

/** Whether playback should continue when the player leaves the foreground. */
fun shouldPlayInBackground(settingEnabled: Boolean): Boolean = settingEnabled