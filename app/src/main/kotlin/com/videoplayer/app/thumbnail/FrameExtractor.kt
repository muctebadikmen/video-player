// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.thumbnail

import com.videoplayer.core.model.FrameStats

/**
 * Decodes still frames from a video. Implemented with MediaMetadataRetriever in :app; abstracted as
 * an interface so [com.videoplayer.app.thumbnail.ThumbnailRepository] can be unit-tested with a fake.
 */
interface FrameExtractor {
    /** Tiny-bitmap luminance/variance samples at [com.videoplayer.core.model.THUMBNAIL_SAMPLE_FRACTIONS]. */
    suspend fun sampleStats(mediaUri: String, durationMs: Long): List<FrameStats>

    /** Extracts the frame at [frameMs], saves it as a JPEG in internal storage, returns its path (or null). */
    suspend fun extractAndSave(mediaUri: String, frameMs: Long): String?
}

/** Stable per-(uri,time) JPEG filename. The time component busts Coil's file-path cache on re-set. */
fun thumbnailFileName(mediaUri: String, updatedAtEpochMs: Long): String {
    val hash = mediaUri.hashCode().toLong() and 0xFFFFFFFFL
    return "${hash.toString(16)}-$updatedAtEpochMs.jpg"
}
