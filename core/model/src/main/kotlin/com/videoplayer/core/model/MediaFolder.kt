package com.videoplayer.core.model

/**
 * A folder containing one or more [MediaItem]s. The library browser shows only
 * folders that actually hold media, so an empty folder should never be built.
 */
data class MediaFolder(
    val path: String,
    val name: String,
    val items: List<MediaItem>,
) {
    val videoCount: Int get() = items.size
}
