package com.videoplayer.app.player.controls

/** How far double-tap-to-skip jumps, in ms. */
const val SKIP_MS = 10_000L

/** Horizontal third of the player surface a tap landed in. */
enum class TapZone { LEFT, CENTER, RIGHT }

/** What a double-tap does, by zone. */
enum class DoubleTapAction { SEEK_BACKWARD, PLAY_PAUSE, SEEK_FORWARD }

/** Maps a tap x-position to a [TapZone] by horizontal thirds. Non-positive width → CENTER. */
fun resolveTapZone(x: Float, width: Float): TapZone {
    if (width <= 0f) return TapZone.CENTER
    return when {
        x < width / 3f -> TapZone.LEFT
        x > width * 2f / 3f -> TapZone.RIGHT
        else -> TapZone.CENTER
    }
}

fun doubleTapAction(zone: TapZone): DoubleTapAction = when (zone) {
    TapZone.LEFT -> DoubleTapAction.SEEK_BACKWARD
    TapZone.CENTER -> DoubleTapAction.PLAY_PAUSE
    TapZone.RIGHT -> DoubleTapAction.SEEK_FORWARD
}

/** New seek position after applying [deltaMs], clamped to `0..durationMs`. */
fun seekTarget(currentMs: Long, deltaMs: Long, durationMs: Long): Long =
    (currentMs + deltaMs).coerceIn(0, durationMs)
