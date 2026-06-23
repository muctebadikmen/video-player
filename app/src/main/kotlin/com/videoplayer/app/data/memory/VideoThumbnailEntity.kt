// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.data.memory

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-video thumbnail state, keyed by the same media content/URI string as [PlaybackMemoryEntity].
 *
 * Two layers: [autoFrameMs] is the smart-chosen default timestamp (computed once, then cached);
 * [customThumbnailPath] is a user-set override saved as a JPEG in internal storage and always wins.
 * [autoResolved] is set true once auto compute has run (success OR fallback) so it never re-runs.
 */
@Entity(tableName = "video_thumbnail")
data class VideoThumbnailEntity(
    @PrimaryKey val mediaUri: String,
    val customThumbnailPath: String? = null,
    val autoFrameMs: Long? = null,
    val autoResolved: Boolean = false,
    val updatedAtEpochMs: Long = 0L,
)
