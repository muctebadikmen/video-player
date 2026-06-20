// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.core.playback

/**
 * Picks the embedded text track to enable by default.
 * Returns the first available track only for fresh media the user hasn't
 * touched and where nothing is already selected; otherwise null (leave as-is).
 */
fun pickDefaultTextTrack(
    tracks: List<TextTrackInfo>,
    currentlySelectedId: String?,
    userHasChosen: Boolean,
): String? {
    if (tracks.isEmpty()) return null
    if (currentlySelectedId != null) return null
    if (userHasChosen) return null
    return tracks.first().id
}
