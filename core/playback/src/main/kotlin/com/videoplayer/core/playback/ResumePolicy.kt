package com.videoplayer.core.playback

/** Minimum watched position worth resuming. Below this we start from the beginning. */
const val MIN_RESUME_MS = 5_000L

/** If the saved position is within this of the end, treat the file as finished and restart. */
const val END_GUARD_MS = 5_000L

/**
 * The position playback should start from, given a previously saved [savedPositionMs]
 * and the media [durationMs]. Silent auto-resume policy:
 *  - too-early saves (`< MIN_RESUME_MS`, incl. negatives) start from 0,
 *  - near-the-end saves (within [END_GUARD_MS] of a known duration) start from 0 (finished),
 *  - everything else resumes exactly where it left off.
 *
 * A non-positive [durationMs] means duration is unknown (player hasn't reported it yet);
 * we still honor a valid saved position so resume works before duration is known.
 */
fun effectiveResumePosition(savedPositionMs: Long, durationMs: Long): Long {
    if (savedPositionMs < MIN_RESUME_MS) return 0
    if (durationMs > 0 && savedPositionMs >= durationMs - END_GUARD_MS) return 0
    return savedPositionMs
}

/**
 * Three-tier preference precedence: a per-file value wins, then a folder default,
 * then the always-present global value. Folder defaults are not yet persisted in V1
 * (no setter exists); callers pass `null` for [folder] until that lands.
 */
fun <T> resolvePreference(file: T?, folder: T?, global: T): T = file ?: folder ?: global
