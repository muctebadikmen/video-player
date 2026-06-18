package com.videoplayer.core.playback

/** A persisted playback position for one media item, as needed by the library's continue-watching row. */
data class WatchProgress(
    val mediaUri: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAtEpochMs: Long,
)

/** Fraction watched in 0f..1f. Returns 0f when duration is unknown (<= 0). */
fun progressFraction(positionMs: Long, durationMs: Long): Float {
    if (durationMs <= 0L) return 0f
    return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
}

/**
 * The "continue watching" list: entries that are actually resumable (per [effectiveResumePosition] —
 * i.e. not too-early and not finished), most-recently-updated first, capped at [limit].
 */
fun continueWatching(entries: List<WatchProgress>, limit: Int = 20): List<WatchProgress> =
    entries
        .filter { effectiveResumePosition(it.positionMs, it.durationMs) > 0L }
        .sortedByDescending { it.updatedAtEpochMs }
        .take(limit)
