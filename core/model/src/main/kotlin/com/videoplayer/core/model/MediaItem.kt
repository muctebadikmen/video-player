package com.videoplayer.core.model

/**
 * A single playable video discovered in the user's library.
 *
 * Pure data — no Android types — so it can move to a shared KMP module later.
 * [uri] is an opaque string (a `content://` or `file://` URI on Android) that
 * the platform layer resolves; the core never parses it.
 */
data class MediaItem(
    val id: Long,
    val uri: String,
    val displayName: String,
    val folderPath: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateAddedSec: Long,
)
