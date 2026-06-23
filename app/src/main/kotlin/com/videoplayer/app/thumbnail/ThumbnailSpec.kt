// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.thumbnail

import com.videoplayer.app.data.memory.VideoThumbnailEntity

/** What the UI should actually render for a video. Absence (null) means "not resolved yet". */
sealed interface ThumbnailSpec {
    /** A user-set override saved to disk; [updatedAtEpochMs] feeds the Coil cache key. */
    data class Custom(val path: String, val updatedAtEpochMs: Long) : ThumbnailSpec
    /** The smart-chosen default timestamp; Coil decodes it via videoFrameMillis. */
    data class AutoFrame(val frameMs: Long) : ThumbnailSpec
}

/** Resolution precedence: manual override → resolved auto frame → null (recompute/placeholder). */
fun VideoThumbnailEntity.toSpec(): ThumbnailSpec? = when {
    customThumbnailPath != null -> ThumbnailSpec.Custom(customThumbnailPath, updatedAtEpochMs)
    autoResolved && autoFrameMs != null -> ThumbnailSpec.AutoFrame(autoFrameMs)
    else -> null
}
