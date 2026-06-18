package com.videoplayer.core.playback

/** Screen orientation the player should request. Maps to an ActivityInfo orientation in the UI. */
enum class OrientationMode { AUTO, PORTRAIT, LANDSCAPE, REVERSE_LANDSCAPE }

/** Cycles through [OrientationMode] in declared order, wrapping REVERSE_LANDSCAPE → AUTO. */
fun nextOrientationMode(current: OrientationMode): OrientationMode {
    val values = OrientationMode.entries
    return values[(current.ordinal + 1) % values.size]
}

/** How long the unlock control must be held to release a screen lock. */
const val UNLOCK_HOLD_MS = 3_000L

/** How long the "screen locked" hint stays visible after the screen is locked. */
const val LOCK_HINT_VISIBLE_MS = 3_000L
