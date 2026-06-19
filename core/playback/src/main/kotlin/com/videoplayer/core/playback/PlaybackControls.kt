// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.core.playback

/** An A–B repeat loop. Both points must be set and ordered (end after start) to be active. */
data class AbLoop(val startMs: Long? = null, val endMs: Long? = null) {
    val isComplete: Boolean get() = startMs != null && endMs != null && endMs > startMs
}

/** Where playback should jump back to for an active A–B loop once it reaches B, else null. */
fun abLoopTarget(positionMs: Long, loop: AbLoop): Long? =
    if (loop.isComplete && positionMs >= loop.endMs!!) loop.startMs else null

/** User-selectable playback speeds. */
val SPEED_PRESETS: List<Float> = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f)

const val MIN_SPEED = 0.25f
const val MAX_SPEED = 4f

/** Clamps an arbitrary speed into the supported range. */
fun clampSpeed(speed: Float): Float = speed.coerceIn(MIN_SPEED, MAX_SPEED)

/** Step size for frame-by-frame stepping (~1 frame at 25fps; approximate, codec-independent). */
const val FRAME_STEP_MS = 40L

/** Milliseconds left until a sleep deadline, clamped at 0. */
fun sleepRemainingMs(deadlineEpochMs: Long, nowEpochMs: Long): Long =
    (deadlineEpochMs - nowEpochMs).coerceAtLeast(0)

/** Whether a sleep deadline has been reached. */
fun isSleepExpired(deadlineEpochMs: Long, nowEpochMs: Long): Boolean = nowEpochMs >= deadlineEpochMs