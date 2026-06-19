// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.data.memory

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-file playback memory, keyed by the media content/URI string we play.
 *
 * Wired: [positionMs], [durationMs], [aspectMode], [speed], [updatedAtEpochMs] (P1.C);
 *   [subtitleTrackId], [subtitleOffsetMs] (P1.G); [subtitleRate] (v1.2.0, DB v2); [orientation] (P1.E).
 * Reserved (nullable, no migration needed when a later phase starts writing them):
 *  - [audioTrackId] — P1.G (audio tracks)
 *  - [v2LoopMode], [v2NativeSubtitleTrackId] — V2 language-learning; documented only, no logic.
 */
@Entity(tableName = "playback_memory")
data class PlaybackMemoryEntity(
    @PrimaryKey val mediaUri: String,
    val positionMs: Long,
    val durationMs: Long,
    val aspectMode: String,
    val speed: Float,
    val updatedAtEpochMs: Long,
    val audioTrackId: String? = null,
    val subtitleTrackId: String? = null,
    val subtitleOffsetMs: Long? = null,
    /** Per-file external-subtitle playback-rate correction (1.0 = unscaled). Added in DB v2 (v1.2.0). */
    val subtitleRate: Float = 1.0f,
    val orientation: Int? = null,
    val v2LoopMode: String? = null,
    val v2NativeSubtitleTrackId: String? = null,
)