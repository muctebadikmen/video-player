package com.videoplayer.app.data.memory

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-file playback memory, keyed by the media content/URI string we play.
 *
 * Wired in V1 (P1.C): [positionMs], [durationMs], [aspectMode], [speed], [updatedAtEpochMs].
 * Reserved (nullable, no migration needed when a later phase starts writing them):
 *  - [audioTrackId], [subtitleTrackId], [subtitleOffsetMs] — P1.G (subtitles/audio tracks)
 *  - [orientation] — P1.E (per-file orientation lock; ActivityInfo.screenOrientation int)
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
    val orientation: Int? = null,
    val v2LoopMode: String? = null,
    val v2NativeSubtitleTrackId: String? = null,
)
