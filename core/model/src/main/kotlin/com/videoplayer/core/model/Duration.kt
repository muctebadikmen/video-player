package com.videoplayer.core.model

/**
 * Formats a duration in milliseconds as `m:ss` (or `h:mm:ss` when an hour or
 * more). Negative inputs clamp to `0:00`. Used throughout the UI for both
 * elapsed position and total length so playback time reads consistently.
 */
fun formatDuration(ms: Long): String {
    val totalSec = ms.coerceAtLeast(0) / 1000
    val hours = totalSec / 3600
    val minutes = (totalSec % 3600) / 60
    val seconds = totalSec % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
